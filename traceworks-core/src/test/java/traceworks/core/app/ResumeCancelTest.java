package traceworks.core.app;

import traceworks.core.app.AppFixture.Background;
import traceworks.core.blocks.CoreBlocks;
import traceworks.core.engine.EventLogException;
import traceworks.core.model.RunStatus;
import traceworks.core.port.ContainerRuntime;
import traceworks.core.port.InboxRequest;
import traceworks.core.record.RecordTypes;
import traceworks.core.record.RecordedEvent;
import traceworks.core.record.RunRecorder;
import traceworks.core.testing.InMemoryRunStore.InMemoryStoredRun;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static traceworks.core.app.AppFixture.DEPLOY;
import static traceworks.core.app.AppFixture.request;
import static traceworks.core.app.AppFixture.resumeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A resume and a cancel of the same run: a cancel is never stale, whenever it reaches the run. */
class ResumeCancelTest {

    private final AppFixture fx = new AppFixture();

    /**
     * Review C, M1: a cancel sent while a resume still prepares (it holds the writer lock, but its inbox watcher has
     * not started) goes into the inbox. The resume carries it out: the run ends CANCELLED without a step, and the
     * waiting cancel gets that end.
     */
    @Test
    void aCancelSentWhileAResumePrepares_endsTheRunCancelled_withoutAStep() throws Exception {
        AtomicBoolean hold = new AtomicBoolean(false);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ContainerSupport holding = new ContainerSupport(() -> {
            if (hold.getAndSet(false)) {   // the resume's runtime lookup: it holds the writer lock already
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return Optional.<ContainerRuntime>empty();
        }, fx.builtIns, fx.directories);
        RunServices services = new RunServices(fx.store, fx.packages, fx.ledger, fx.clock, fx.ids,
                CoreBlocks.registry(null), fx.commands, holding, fx.sources, fx.clis,
                () -> new SecretValues(Map.of(), Map.of()), fx.settings, fx.registers, fx.hooks, AppFixture.ACTOR, SealSupport.none(),
                Duration.ofSeconds(5), Duration.ofSeconds(10));
        String id = services.start(request(DEPLOY), null, null).runId();
        services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        int commandsBefore = fx.commands.commands().size();

        hold.set(true);
        Background<RunResult> resuming = new Background<>(() -> services.resume(resumeOf(id), null, null));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        Background<RunResult> cancelling = new Background<>(() -> services.cancel(id));
        fx.run(id).inbox().awaitPosted(1, AppFixture.WAIT);
        release.countDown();

        assertThat(resuming.join().status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(cancelling.join().status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(fx.commands.commands()).hasSize(commandsBefore);
        List<String> types = fx.types(id);
        assertThat(types).doesNotContain(RecordTypes.REQUEST_REFUSED, RecordTypes.RUN_RESUMED);
        assertThat(types.subList(types.size() - 2, types.size()))
                .containsExactly(RecordTypes.RUN_CANCEL_REQUESTED, RecordTypes.RUN_FINISHED);
        RecordedEvent cancel = fx.ofType(id, RecordTypes.RUN_CANCEL_REQUESTED).getFirst();
        assertThat(cancel.fields().path("request").asText())
                .isEqualTo(fx.run(id).inbox().posted().getFirst().id());
        assertThat(fx.run(id).readManifest()).isPresent();
    }

    /**
     * A cancel left in the inbox of a run nobody runs (its sender gave up) is meant for whichever process runs the
     * run next: a resume records it with its sender and ends the run. An answer left there keeps today's rule: it
     * was bound to a question of a run that went on without it, and is refused.
     */
    @Test
    void aCancelLeftInTheInbox_isCarriedOutByTheResume_anAnswerIsRefused() {
        RunServices services = fx.services();
        String id = services.start(request(DEPLOY), null, null).runId();
        services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        int commandsBefore = fx.commands.commands().size();
        fx.run(id).inbox().post(new InboxRequest("answer-1", "approval", id, 1, 7, "No", Map.of(), null,
                "anna@laptop", Instant.parse("2026-11-03T12:00:00Z")));
        fx.run(id).inbox().post(new InboxRequest("cancel-1", "cancel", null, null, null, null, Map.of(), null,
                "anna@laptop", Instant.parse("2026-11-03T12:00:01Z")));

        RunResult resumed = services.resume(resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(fx.commands.commands()).hasSize(commandsBefore);
        RecordedEvent refused = fx.ofType(id, RecordTypes.REQUEST_REFUSED).getFirst();
        assertThat(refused.fields().path("request").asText()).isEqualTo("answer-1");
        assertThat(refused.fields().path("reason").asText()).isEqualTo("the run was not running when the request"
                + " arrived");
        assertThat(fx.ofType(id, RecordTypes.REQUEST_REFUSED)).hasSize(1);
        RecordedEvent cancel = fx.ofType(id, RecordTypes.RUN_CANCEL_REQUESTED).getFirst();
        assertThat(cancel.fields().path("request").asText()).isEqualTo("cancel-1");
        assertThat(cancel.fields().path("actor").asText()).isEqualTo("anna@laptop");
        assertThat(fx.events(id).getLast().fields().path("status").asText()).isEqualTo("CANCELLED");
        assertThat(fx.types(id)).doesNotContain(RecordTypes.RUN_RESUMED);
    }

    /**
     * Review C, L5: a cancel was recorded (Ctrl-C, or an inbox cancel the run's process took), but the process ended
     * before the run did. The next resume finishes the run as CANCELLED instead of going on with its steps.
     */
    @Test
    void aRecordedCancelWithoutAnEnd_isFinishedByTheNextResume_notContinued() {
        RunServices services = fx.services();
        String id = services.start(request(DEPLOY), null, null).runId();
        services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        int commandsBefore = fx.commands.commands().size();
        InMemoryStoredRun other = fx.run(id);
        assertThat(other.tryLockWriter()).isTrue();
        RunRecorder recorder = RunRecorder.continueAfter(other.events(), other.blobs(), fx.clock, id,
                RunRecords.RECORDING_ONLY, fx.events(id));
        recorder.record(RecordTypes.RUN_CANCEL_REQUESTED, RunRecords.cancelRequested("anna@laptop", "cancel-1"));
        other.close();

        RunResult resumed = services.resume(resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(fx.commands.commands()).hasSize(commandsBefore);
        assertThat(fx.ofType(id, RecordTypes.RUN_CANCEL_REQUESTED)).hasSize(1);
        assertThat(fx.types(id)).doesNotContain(RecordTypes.RUN_RESUMED);
        assertThat(fx.events(id).getLast().fields().path("status").asText()).isEqualTo("CANCELLED");
        assertThat(fx.run(id).readManifest()).isPresent();
    }

    /**
     * Review C, L1: a resume of a parked, answered run records run.resumed and dies before the step took the answer
     * (killed, a full disk). The next resume goes on with the answer the record holds; it does not park again.
     */
    @Test
    void aResumeThatDiedAfterRunResumed_keepsTheParkedAnswer() {
        RunServices services = fx.services();
        String id = services.start(request(DEPLOY), null, null).runId();
        services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        int answer = fx.ofType(id, RecordTypes.APPROVAL_ANSWERED).getFirst().seq();
        fx.run(id).log().failAfter(fx.events(id).size() + 1);   // run.resumed is written, then the disk is full
        assertThatThrownBy(() -> services.resume(resumeOf(id), null, null)).isInstanceOf(EventLogException.class);
        fx.run(id).log().failAfter(-1);
        assertThat(services.view(id).status()).isEqualTo(RunStatus.INTERRUPTED);

        RunResult resumed = services.resume(resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.ofType(id, RecordTypes.RUN_RESUMED)).extracting(e -> e.fields().path("answer").asInt())
                .containsExactly(answer, answer);
        assertThat(fx.ofType(id, RecordTypes.RUN_PARKED)).hasSize(1);
        assertThat(fx.commands.commands()).endsWith("./done");
        RecordedEvent replayed = fx.ofType(id, RecordTypes.APPROVAL_ANSWERED).getLast();
        assertThat(replayed.fields().path("source").asText()).isEqualTo("resume");
        assertThat(replayed.fields().path("actor").asText()).isEqualTo(AppFixture.ACTOR);
    }
}
