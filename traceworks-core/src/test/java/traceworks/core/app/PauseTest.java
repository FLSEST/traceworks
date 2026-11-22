package traceworks.core.app;

import traceworks.core.app.AppFixture.Background;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepStatus;
import traceworks.core.record.RecordedEvent;
import traceworks.core.record.RunView;
import traceworks.core.testing.FakeCommandRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static traceworks.core.app.AppFixture.ACTOR;
import static traceworks.core.app.AppFixture.BUILD;
import static traceworks.core.app.AppFixture.request;
import static traceworks.core.app.AppFixture.resumeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A pause parks the run to disk: the step or the loop item in flight is finished, the run ends {@code PARKED} with
 * the reason {@code pause}, and {@code resume} goes on, in a loop with the items that finished. A running run of
 * another process is asked through its inbox, as for a cancel.
 */
class PauseTest {

    /** A loop over four items, then a step that reads the loop's output. */
    private static final Path LOOP = Path.of("/pkg/loop");

    private final AppFixture fx = new AppFixture();
    private final RunServices services = fx.services();
    private final List<Background<?>> background = new ArrayList<>();
    /** Opened by the test to let the command that waits go on. */
    private final CountDownLatch goOn = new CountDownLatch(1);
    /** Counted down when the command that waits has started. */
    private final CountDownLatch started = new CountDownLatch(1);

    PauseTest() {
        fx.packages.put(LOOP, """
                {"id": "wf-loop", "name": "Loop", "blocks": [
                  {"type": "foreach", "name": "each", "config": {"execution_mode": "shell",
                   "items": ["a", "b", "c", "d"], "command": "./work {{ item }}"}},
                  {"type": "shell", "name": "report", "config": {"command": "./report {{ block.each.output }}"}}
                ]}""");
        fx.commands.on("./work ", req -> FakeCommandRunner.ok("did " + req.command().substring("./work ".length())))
                .on("./report", "reported");
    }

    @AfterEach
    void noThreadOutlivesAUseCase() throws InterruptedException {
        goOn.countDown();
        try {
            services.cancelActiveRuns();
        } finally {
            for (Background<?> work : background) {
                work.awaitEnd();
            }
        }
        assertThat(AppFixture.liveServiceThreads()).isEmpty();
    }

    private <T> Background<T> inBackground(Supplier<T> work) {
        Background<T> begun = new Background<>(work);
        background.add(begun);
        return begun;
    }

    /** The command that starts with {@code prefix} waits until the test lets it go on. */
    private void waitsAt(String prefix, String output) {
        fx.commands.on(prefix, req -> {
            started.countDown();
            try {
                goOn.await(AppFixture.WAIT.toSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return FakeCommandRunner.ok(output);
        });
    }

    private void awaitStarted() throws InterruptedException {
        assertThat(started.await(AppFixture.WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
    }

    private long ran(String command) {
        return fx.commands.commands().stream().filter(command::equals).count();
    }

    /** {@code traceworks pause} of a run another process runs: asked through the inbox, recorded with its sender. */
    @Test
    void pause_ofARunningRun_parksItAfterTheStepInFlight_andTheResumeGoesOn() throws Exception {
        waitsAt("./build", "built");
        Background<RunResult> run = inBackground(() -> services.start(request(BUILD), null, null));
        awaitStarted();
        String id = fx.store.runIds().getFirst();

        RunResult asked = fx.services().pause(id);

        assertThat(asked.status()).as("the step in flight is finished first").isEqualTo(RunStatus.RUNNING);
        assertThat(asked.error()).contains("pause requested");
        RecordedEvent requested = fx.ofType(id, "run.pause_requested").getFirst();
        assertThat(requested.fields().path("actor").asText()).isEqualTo(ACTOR);
        assertThat(requested.fields().path("request").asText()).isNotBlank();

        goOn.countDown();
        RunResult parked = run.join();

        assertThat(parked.status()).isEqualTo(RunStatus.PARKED);
        RecordedEvent last = fx.events(id).getLast();
        assertThat(last.type()).isEqualTo("run.parked");
        assertThat(last.fields().path("kind").asText()).isEqualTo("pause");
        assertThat(last.fields().path("step").isNull()).as("it parked between two steps").isTrue();
        RunView view = services.view(id);
        assertThat(view.status()).isEqualTo(RunStatus.PARKED);
        assertThat(view.parkedBy()).isEqualTo("pause");
        assertThat(view.pendingQuestion()).isNull();
        assertThat(view.steps()).extracting(s -> s.status()).containsExactly(StepStatus.COMPLETED);
        assertThat(view.interventions()).anySatisfy(i ->
                assertThat(i.description()).startsWith("requested a pause"));
        assertThat(fx.run(id).readManifest()).as("a parked run has not ended").isEmpty();

        RunResult resumed = services.resume(resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.commands.commands()).containsExactly("./build", "./test");
        assertThat(fx.ofType(id, "run.resumed").getFirst().fields().path("records")).extracting(n -> n.asText())
                .as("the resume says what it records, as the start does")
                .containsExactly("output_cut", "answer_agent", "called_packages", "resumed_with", "request_agent");
        assertThat(services.view(id).recordsOutputCut()).isTrue();
        assertThat(services.verify(id).ok()).isTrue();
    }

    /** In a loop the pause takes effect after the item in flight; the resume runs only what did not finish. */
    @Test
    void pause_inALoop_parksAfterTheItemInFlight_andNothingRunsTwice() throws Exception {
        waitsAt("./work b", "did b");
        Background<RunResult> run = inBackground(() -> services.start(request(LOOP), null, null));
        awaitStarted();
        String id = fx.store.runIds().getFirst();

        services.pauseActiveRuns();
        goOn.countDown();
        RunResult parked = run.join();

        assertThat(parked.status()).isEqualTo(RunStatus.PARKED);
        RecordedEvent last = fx.events(id).getLast();
        assertThat(last.fields().path("kind").asText()).isEqualTo("pause");
        assertThat(last.fields().path("step").asInt(-1)).as("the loop's step, which is not finished").isZero();
        assertThat(fx.ofType(id, "step.finished")).isEmpty();
        assertThat(fx.ofType(id, "run.pause_requested")).singleElement()
                .satisfies(e -> assertThat(e.fields().path("actor").asText()).isEqualTo(ACTOR));

        RunResult resumed = services.resume(resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        for (String item : List.of("a", "b", "c", "d")) {
            assertThat(ran("./work " + item)).as("item " + item).isEqualTo(1);
        }
        assertThat(fx.commands.commands().getLast())
                .isEqualTo("./report [a]: did a\n[b]: did b\n[c]: did c\n[d]: did d");
        assertThat(services.verify(id).ok()).isTrue();
    }

    /** A run paused twice parks twice, and still runs each item once. */
    @Test
    void pause_ofAResumedLoop_parksAgain() throws Exception {
        waitsAt("./work a", "did a");
        Background<RunResult> run = inBackground(() -> services.start(request(LOOP), null, null));
        awaitStarted();
        String id = fx.store.runIds().getFirst();
        services.pauseActiveRuns();
        goOn.countDown();
        assertThat(run.join().status()).isEqualTo(RunStatus.PARKED);
        fx.commands.on("./work c", req -> {
            services.pauseActiveRuns();
            return FakeCommandRunner.ok("did c");
        });

        RunResult again = services.resume(resumeOf(id), null, null);
        assertThat(again.status()).isEqualTo(RunStatus.PARKED);
        RunResult resumed = services.resume(resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        for (String item : List.of("a", "b", "c", "d")) {
            assertThat(ran("./work " + item)).as("item " + item).isEqualTo(1);
        }
        assertThat(fx.ofType(id, "run.parked")).hasSize(2);
    }

    @Test
    void pause_ofARunNobodyRuns_isRefused() {
        String id = services.start(request(AppFixture.DEPLOY), null, null).runId();   // parked at its approval
        String done = services.start(request(BUILD), null, null).runId();

        assertThatThrownBy(() -> services.pause(id)).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("is not running (PARKED)");
        assertThatThrownBy(() -> services.pause(done)).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("is not running (COMPLETED)");
        assertThat(fx.ofType(id, "run.pause_requested")).isEmpty();
    }

    /** A pause nobody records within the wait: the request stays queued, as for a cancel. */
    @Test
    void pause_thatTheRunsProcessDoesNotRecord_isQueued() {
        String id = services.start(request(AppFixture.DEPLOY), null, null).runId();
        // Another process holds the run and does not look into its inbox
        try (var other = fx.run(id)) {
            assertThat(other.tryLockWriter()).isTrue();

            assertThatThrownBy(() -> fx.services(Duration.ofMillis(300)).pause(id))
                    .isInstanceOf(RequestQueuedException.class).hasMessageContaining("pause");
        }
    }

    @Test
    void aPausedRun_canBeCancelled_andTakesNoAnswer() throws Exception {
        waitsAt("./build", "built");
        Background<RunResult> run = inBackground(() -> services.start(request(BUILD), null, null));
        awaitStarted();
        String id = fx.store.runIds().getFirst();
        services.pauseActiveRuns();
        goOn.countDown();
        assertThat(run.join().status()).isEqualTo(RunStatus.PARKED);

        AnswerResult answer = services.answer(id, new AnswerRequest("approval", null, "Yes", null, null));
        assertThat(answer.accepted()).isFalse();
        assertThat(answer.message()).contains("is paused and waits for no answer");

        RunResult cancelled = services.cancel(id);

        assertThat(cancelled.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(fx.commands.commands()).containsExactly("./build");
    }

    /** A pause left in the inbox of a run nobody runs is stale: the resume refuses it and does not park for it. */
    @Test
    void aPauseTheRunNeverSaw_doesNotParkTheResume() throws Exception {
        String id = services.start(request(AppFixture.DEPLOY), null, null).runId();
        services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        try (var other = fx.run(id)) {
            assertThat(other.tryLockWriter()).isTrue();
            assertThatThrownBy(() -> fx.services(Duration.ofMillis(300)).pause(id))
                    .isInstanceOf(RequestQueuedException.class);
        }

        RunResult resumed = services.resume(resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.ofType(id, "request.refused")).singleElement()
                .satisfies(e -> assertThat(e.fields().path("kind").asText()).isEqualTo("pause"));
    }
}
