package traceworks.core.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.analyzer.AnalyzerFinding;
import traceworks.core.app.AppFixture.Background;
import traceworks.core.app.AppFixture.Events;
import traceworks.core.engine.EventLogException;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepStatus;
import traceworks.core.port.InboxRequest;
import traceworks.core.port.Ledger;
import traceworks.core.record.RecordTypes;
import traceworks.core.record.RunRecorder;
import traceworks.core.record.EventChain;
import traceworks.core.record.QuestionView;
import traceworks.core.record.RecordedEvent;
import traceworks.core.record.RunView;
import traceworks.core.record.StepView;
import traceworks.core.testing.FakeCommandRunner;
import traceworks.core.testing.InMemoryEventLog;
import traceworks.core.testing.InMemoryLedger;
import traceworks.core.testing.InMemoryRunStore.InMemoryStoredRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static traceworks.core.app.AppFixture.ACTOR;
import static traceworks.core.app.AppFixture.BUILD;
import static traceworks.core.app.AppFixture.DEPLOY;
import static traceworks.core.app.AppFixture.GATE;
import static traceworks.core.app.AppFixture.PARENT;
import static traceworks.core.app.AppFixture.SHADOW;
import static traceworks.core.app.AppFixture.WHO;
import static traceworks.core.app.AppFixture.WORKSPACE;
import static traceworks.core.app.AppFixture.request;
import static traceworks.core.app.AppFixture.resumeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The use cases on in-memory ports. A run that waits for an answer runs in the {@link Background}; the tests wait for
 * events, never for time.
 */
class RunServicesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AppFixture fx = new AppFixture();
    private final RunServices services = fx.services();
    private final List<Background<?>> background = new ArrayList<>();

    /** No thread of a use case outlives it; a run a failed test left waiting is cancelled first. */
    @AfterEach
    void noThreadOutlivesAUseCase() throws InterruptedException {
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
        Background<T> started = new Background<>(work);
        background.add(started);
        return started;
    }

    // ---- a cancel that ends a step ----

    /**
     * A cancel while a step runs: the command it killed ends the step CANCELLED in the record, the view and run.html,
     * not FAILED; the run ends CANCELLED as before, starts no further step and still verifies.
     */
    @Test
    void aCancelWhileAStepRuns_endsTheStepCancelled_inTheRecordAndTheView() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        fx.commands.on("./build", request -> {
            started.countDown();
            long deadline = System.nanoTime() + AppFixture.WAIT.toNanos();
            while (!traceworks.core.util.ProcessTracking.current().cancelled()
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            return new traceworks.core.port.CommandRunner.CommandResult(143, "half done", "", 1,
                    false);
        });
        Background<RunResult> run = inBackground(() -> services.start(request(BUILD), null, null));
        assertThat(started.await(AppFixture.WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

        services.cancelActiveRuns();

        RunResult result = run.join();
        String id = result.runId();
        assertThat(result.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(fx.ofType(id, "step.finished")).singleElement()
                .satisfies(e -> assertThat(e.fields().path("status").asText()).isEqualTo("CANCELLED"));
        assertThat(fx.ofType(id, "run.finished")).singleElement()
                .satisfies(e -> assertThat(e.fields().path("status").asText()).isEqualTo("CANCELLED"));
        RunView view = services.view(id);
        assertThat(view.steps()).extracting(StepView::status).containsExactly(StepStatus.CANCELLED);
        assertThat(view.steps().getFirst().cancelled()).as("it finished").isFalse();
        assertThat(services.verify(id).ok()).isTrue();
        assertThat(fx.commands.commands()).noneMatch(command -> command.contains("./test"));
    }

    /** A runner that throws after the cancel: the step and the run end CANCELLED, without an error, as for a kill. */
    @Test
    void aCancelWhileAStepRuns_thatMakesTheRunnerThrow_endsTheRunCancelled() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        fx.commands.on("./build", request -> {
            started.countDown();
            long deadline = System.nanoTime() + AppFixture.WAIT.toNanos();
            while (!traceworks.core.util.ProcessTracking.current().cancelled()
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            throw new IllegalStateException("stream closed");
        });
        Background<RunResult> run = inBackground(() -> services.start(request(BUILD), null, null));
        assertThat(started.await(AppFixture.WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

        services.cancelActiveRuns();

        RunResult result = run.join();
        assertThat(result.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(result.error()).isNull();
        assertThat(services.view(result.runId()).steps()).extracting(StepView::status)
                .containsExactly(StepStatus.CANCELLED);
        assertThat(services.verify(result.runId()).ok()).isTrue();
    }

    // ---- start ----

    /** Without the operator's consent a run is a container run; on a machine without a runtime it cannot start. */
    @Test
    void start_withoutHostExecution_isAContainerRun_andCreatesNoRunWithoutARuntime() {
        fx.settings = new OperatorSettings(false, null, List.of());
        RunServices strict = fx.services();

        assertThatThrownBy(() -> strict.start(request(BUILD), null, null))
                .isInstanceOf(RunRefusedException.class)
                .message().contains("no container runtime", "podman", "docker")
                .doesNotContainIgnoringCase("host execution is not allowed");
        assertThat(fx.store.runIds()).isEmpty();
        assertThat(fx.commands.commands()).isEmpty();
    }

    @Test
    void start_withHostExecutionAllowedForTheRun_runs() {
        fx.settings = new OperatorSettings(false, null, List.of());

        RunResult result = fx.services().start(new StartRequest(BUILD, Map.of(), WORKSPACE, true, false), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void start_reservedInput_isRefused() {
        StartRequest reserved = new StartRequest(BUILD, Map.of("package_dir", "/elsewhere"), WORKSPACE, false, false);

        assertThatThrownBy(() -> services.start(reserved, null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("package_dir");
        assertThat(fx.store.runIds()).isEmpty();
    }

    @Test
    void start_invalidPackage_isRefused() {
        assertThatThrownBy(() -> services.start(request(Path.of("/pkg/missing")), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("/pkg/missing");
        assertThat(fx.store.runIds()).isEmpty();
    }

    @Test
    void start_recordsRunStartedFirst() throws Exception {
        String id = services.start(request(BUILD), null, null).runId();

        RecordedEvent first = fx.events(id).getFirst();
        assertThat(first.seq()).isZero();
        assertThat(first.type()).isEqualTo("run.started");
        assertThat(first.fields().path("workflow").path("id").asText()).isEqualTo("wf-build");
        assertThat(first.fields().path("package").path("dir").asText()).isEqualTo(BUILD.toString());
        assertThat(first.fields().path("package").path("digest").asText())
                .isEqualTo(fx.packages.load(BUILD).digest());
        assertThat(first.fields().path("actor").asText()).isEqualTo(ACTOR);
        assertThat(first.fields().path("execution").asText()).isEqualTo("host");
        assertThat(first.fields().path("container_runtime").isNull()).as("a host run that found none").isTrue();
        assertThat(first.fields().path("trigger").asText()).isEqualTo("MANUAL");
        assertThat(first.fields().path("workspace").asText()).isEqualTo(WORKSPACE.toString());
        assertThat(first.fields().path("version").asText()).isEqualTo(TraceworksVersion.current());
        JsonNode inputs = JSON.readTree(fx.text(id, first, "inputs"));
        assertThat(inputs.path("version").asText()).isEqualTo("1.2");
        assertThat(inputs.has("package_dir")).as("the engine sets it from the run's paths").isFalse();
        assertThat(fx.text(id, first, "definition"))
                .isEqualTo(new String(fx.packages.load(BUILD).workflowJson(), StandardCharsets.UTF_8));
    }

    @Test
    void start_completed_writesManifestLedgerAndView() throws Exception {
        RunResult result = services.start(request(BUILD), null, null);
        String id = result.runId();

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.location()).isEqualTo("memory:" + id);
        assertThat(result.error()).isNull();
        InMemoryStoredRun run = fx.run(id);
        byte[] manifest = run.readManifest().orElseThrow();
        assertThat(fx.ledger.find(id)).hasValueSatisfying(copy -> assertThat(copy).isEqualTo(manifest));
        JsonNode m = JSON.readTree(manifest);
        assertThat(m.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(m.path("events").asInt()).isEqualTo(fx.events(id).size());
        assertThat(m.path("head").asText()).isEqualTo(EventChain.sha256Hex(run.log().readAll().getLast()));
        assertThat(m.path("traceworks_version").asText()).isEqualTo(TraceworksVersion.current());
        assertThat(run.view()).hasValueSatisfying(html -> assertThat(html).contains(id).contains("chain intact"));
        assertThat(fx.types(id).getLast()).isEqualTo("run.finished");
        assertThat(run.writerAlive()).as("the writer lock is released").isFalse();
    }

    /** The one command runner of the run records the {@code run_if} test and the step's command alike. */
    @Test
    void runIfAndStepCommands_areBothRecorded_forTheirStep() {
        String id = services.start(request(DEPLOY), null, null).runId();

        List<RecordedEvent> commands = fx.ofType(id, "step.command");
        assertThat(commands).extracting(e -> fx.text(id, e, "command")).containsExactly("test -f ready", "./build");
        assertThat(commands).extracting(RecordedEvent::step).containsExactly(0, 0);
    }

    @Test
    void listener_seesEveryEventInOrder_offTheWritersThread() {
        Events events = new Events();

        String id = services.start(request(BUILD), events, null).runId();

        assertThat(events.all()).extracting(RecordedEvent::seq)
                .containsExactlyElementsOf(fx.events(id).stream().map(RecordedEvent::seq).toList());
        assertThat(events.threads()).isNotEmpty()
                .allSatisfy(name -> assertThat(name).isNotEqualTo(Thread.currentThread().getName()));
    }

    @Test
    void aThrowingListener_doesNotStopTheRun() {
        RunResult result = services.start(request(BUILD), event -> {
            throw new IllegalStateException("the terminal is gone");
        }, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void start_notInteractive_parksAtTheTopLevelApproval_withoutManifest() {
        RunResult result = services.start(request(DEPLOY), null, null);
        String id = result.runId();

        assertThat(result.status()).isEqualTo(RunStatus.PARKED);
        assertThat(fx.types(id).getLast()).isEqualTo("run.parked");
        assertThat(fx.run(id).readManifest()).isEmpty();
        assertThat(fx.ledger.find(id)).isEmpty();
        assertThat(fx.run(id).view()).hasValueSatisfying(html -> assertThat(html).contains("traceworks approve"));
        assertThat(fx.commands.commands()).containsExactly("test -f ready", "./build");
        RunView view = services.view(id);
        assertThat(view.status()).isEqualTo(RunStatus.PARKED);
        assertThat(view.pendingQuestion().options()).containsExactly("Yes", "No");
    }

    /** A ledger whose writes fail, as without git: {@code prepare} fails when {@code usable} is off. */
    private Ledger failingLedger(boolean usable) {
        return new Ledger() {
            @Override
            public void prepare() {
                if (!usable) {
                    throw new IllegalStateException("Cannot run program \"git\"");
                }
            }

            @Override
            public String record(String runId, String status, byte[] manifestJson) {
                throw new IllegalStateException("git failed");
            }

            @Override
            public Optional<byte[]> find(String runId) {
                return Optional.empty();
            }

            @Override
            public String recordStart(String runId, byte[] startJson) {
                return fx.ledger.recordStart(runId, startJson);   // the start is noted; the end is what fails
            }

            @Override
            public Optional<byte[]> findStart(String runId) {
                return fx.ledger.findStart(runId);
            }

            @Override
            public traceworks.core.port.LedgerRuns runs() {
                return fx.ledger.runs();
            }

            @Override
            public void acknowledge(String runId, byte[] line) {
                fx.ledger.acknowledge(runId, line);
            }

            @Override
            public List<String> changes(String runId) {
                return fx.ledger.changes(runId);
            }
        };
    }

    @Test
    void start_withALedgerThatCannotBeWritten_isRefused_andCreatesNoRun() {
        assertThatThrownBy(() -> fx.services(failingLedger(false)).start(request(BUILD), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("ledger")
                .hasMessageContaining("Cannot run program \"git\"");
        assertThat(fx.store.runIds()).isEmpty();
        assertThat(fx.commands.commands()).isEmpty();
    }

    @Test
    void resume_ofAFinishedRunWithoutItsLedgerEntry_completesTheFinalisation_andWritesNothingToTheLog() {
        assertThatThrownBy(() -> fx.services(failingLedger(true)).start(request(BUILD), null, null))
                .isInstanceOf(IllegalStateException.class);
        String id = fx.store.runIds().getFirst();
        byte[] manifest = fx.run(id).readManifest().orElseThrow();
        int before = fx.events(id).size();
        assertThat(services.verify(id).ledger()).isEqualTo(VerifyReport.MISSING);

        RunResult completed = services.resume(resumeOf(id), null, null);

        assertThat(completed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(completed.completed()).containsExactly("ledger entry");
        assertThat(fx.events(id)).hasSize(before);
        assertThat(fx.ledger.find(id)).hasValueSatisfying(copy -> assertThat(copy).isEqualTo(manifest));
        assertThat(services.verify(id).ok()).isTrue();
        assertThat(fx.commands.commands()).containsExactly("./build", "./test");
        assertThatThrownBy(() -> services.resume(resumeOf(id), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("COMPLETED");
        assertThat(fx.events(id)).hasSize(before);
    }

    /** A manifest someone removed from the run folder is written again: the ledger's copy, which the record gives. */
    @Test
    void resume_ofAFinishedRunWithoutItsManifest_writesItFromTheLedgersCopy() {
        String id = services.start(request(BUILD), null, null).runId();
        byte[] manifest = fx.run(id).readManifest().orElseThrow();
        fx.run(id).removeManifest();
        int before = fx.events(id).size();

        RunResult completed = services.resume(resumeOf(id), null, null);

        assertThat(completed.completed()).containsExactly("manifest");
        assertThat(fx.run(id).readManifest()).hasValueSatisfying(written -> assertThat(written).isEqualTo(manifest));
        assertThat(fx.events(id)).hasSize(before);
        assertThat(services.verify(id).ok()).isTrue();
    }

    /** A ledger entry that is not the manifest the record gives is no source for a missing manifest. */
    @Test
    void resume_ofAFinishedRunWithoutItsManifest_isRefused_whenTheLedgersCopyDoesNotMatchTheRecord() {
        String id = services.start(request(BUILD), null, null).runId();
        fx.run(id).removeManifest();
        InMemoryLedger forged = new InMemoryLedger();
        forged.record(id, "COMPLETED", "not a manifest\n".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> fx.services(forged).resume(resumeOf(id), null, null))
                .isInstanceOf(RecordDamagedException.class)
                .hasMessageContaining("The ledger entry of run " + id + " does not match its record");
        assertThat(fx.run(id).readManifest()).isEmpty();
    }

    @Test
    void resume_ofAFinishedRunFolderOutsideThisHome_isRefused_andWritesNoLedgerEntry() {
        assertThatThrownBy(() -> fx.services(failingLedger(true)).start(request(BUILD), null, null))
                .isInstanceOf(IllegalStateException.class);
        String id = fx.store.runIds().getFirst();
        fx.run(id).simulateOutsideStore();
        int before = fx.events(id).size();

        assertThatThrownBy(() -> services.resume(resumeOf(id), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("not in the runs folder of this home")
                .hasMessageContaining("traceworks verify");

        assertThat(fx.ledger.find(id)).isEmpty();
        assertThat(fx.events(id)).hasSize(before);
    }

    @Test
    void ledgerFailure_stillWritesTheView_andIsRethrown() {
        Ledger broken = failingLedger(true);

        assertThatThrownBy(() -> fx.services(broken).start(request(BUILD), null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("git failed");

        String id = fx.store.runIds().getFirst();
        assertThat(fx.run(id).readManifest()).isPresent();
        assertThat(fx.run(id).view()).hasValueSatisfying(html -> assertThat(html).contains(id));
    }

    @Test
    void eventLogFailure_writesNoManifestAndNoLedger() {
        fx.store.failEventWritesAfter(3);

        assertThatThrownBy(() -> services.start(request(BUILD), null, null)).isInstanceOf(EventLogException.class);

        String id = fx.store.runIds().getFirst();
        assertThat(fx.run(id).readManifest()).isEmpty();
        assertThat(fx.ledger.find(id)).isEmpty();
        assertThat(fx.run(id).writerAlive()).as("the writer lock is released").isFalse();
    }

    // ---- answer and resume ----

    @Test
    void answer_onAParkedRun_recordsItWithActorAndSourceCli_thenResumeCompletes() {
        String id = services.start(request(DEPLOY), null, null).runId();

        AnswerResult answered = services.answer(id, new AnswerRequest("approval", 1, "yes", null, "ship it"));

        assertThat(answered.accepted()).isTrue();
        assertThat(answered.runParked()).isTrue();
        RecordedEvent cli = fx.events(id).getLast();
        assertThat(cli.type()).isEqualTo("approval.answered");
        assertThat(cli.step()).isEqualTo(1);
        assertThat(cli.fields().path("kind").asText()).isEqualTo("approval");
        assertThat(fx.text(id, cli, "answer")).as("in the option's spelling").isEqualTo("Yes");
        assertThat(cli.fields().path("actor").asText()).isEqualTo(ACTOR);
        assertThat(cli.fields().path("source").asText()).isEqualTo("cli");
        assertThat(cli.fields().path("request").asText()).isNotBlank();
        assertThat(fx.text(id, cli, "comment")).isEqualTo("ship it");
        assertThat(fx.run(id).writerAlive()).as("the writer lock is released").isFalse();

        RunResult resumed = services.resume(resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        RecordedEvent resumedEvent = fx.ofType(id, "run.resumed").getFirst();
        assertThat(resumedEvent.fields().path("actor").asText()).isEqualTo(ACTOR);
        assertThat(resumedEvent.fields().path("answer").asInt()).isEqualTo(cli.seq());
        assertThat(resumedEvent.fields().path("rerun_step").asInt()).as("the parked step asks again").isEqualTo(1);
        assertThat(resumedEvent.fields().path("package_changed").asBoolean()).isFalse();
        List<RecordedEvent> answers = fx.ofType(id, "approval.answered");
        assertThat(answers).extracting(e -> e.fields().path("source").asText()).containsExactly("cli", "resume");
        assertThat(fx.text(id, answers.get(1), "answer")).isEqualTo("Yes");
        assertThat(fx.commands.commands()).containsExactly("test -f ready", "./build", "./done");
        assertThat(fx.types(id)).filteredOn("run.finished"::equals).hasSize(1);
        byte[] manifest = fx.run(id).readManifest().orElseThrow();
        assertThat(fx.ledger.find(id)).hasValueSatisfying(copy -> assertThat(copy).isEqualTo(manifest));
        assertThat(services.view(id).steps()).extracting(StepView::attempts).containsExactly(1, 2, 1);
    }

    @Test
    void answer_withAnOptionNotOffered_isRefused_andRecordsNothing() {
        String id = services.start(request(DEPLOY), null, null).runId();
        int before = fx.events(id).size();

        AnswerResult result = services.answer(id, new AnswerRequest("approval", 1, "Maybe", null, null));

        assertThat(result.accepted()).isFalse();
        assertThat(result.runParked()).isTrue();
        assertThat(result.message()).contains("not one of the offered options");
        assertThat(fx.events(id)).hasSize(before);
        assertThat(fx.run(id).writerAlive()).isFalse();
    }

    @Test
    void answer_ofAnotherKindOrStep_isRefused() {
        String id = services.start(request(DEPLOY), null, null).runId();

        AnswerResult otherKind = services.answer(id, new AnswerRequest("human-input", null, null,
                Map.of("who", "Alex"), null));
        AnswerResult otherStep = services.answer(id, new AnswerRequest("approval", 0, "Yes", null, null));

        assertThat(otherKind.accepted()).isFalse();
        assertThat(otherKind.message()).contains("approval");
        assertThat(otherStep.accepted()).isFalse();
        assertThat(otherStep.message()).contains("step 1");
        assertThat(fx.ofType(id, "approval.answered")).isEmpty();
    }

    @Test
    void answer_twiceOnAParkedRun_secondIsRefused() {
        String id = services.start(request(DEPLOY), null, null).runId();

        AnswerResult first = services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        AnswerResult second = services.answer(id, new AnswerRequest("approval", 1, "No", null, null));

        assertThat(first.accepted()).isTrue();
        assertThat(second.accepted()).isFalse();
        assertThat(second.runParked()).isTrue();
        assertThat(second.message()).contains("already answered");
        assertThat(fx.ofType(id, "approval.answered")).hasSize(1);
    }

    @Test
    void answer_whileAnotherProcessWritesTheParkedRun_isRefused() {
        String id = services.start(request(DEPLOY), null, null).runId();
        fx.run(id).simulateOtherWriter(true);

        AnswerResult result = services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));

        assertThat(result.accepted()).isFalse();
        assertThat(result.message()).containsIgnoringCase("another process");
        assertThat(fx.ofType(id, "approval.answered")).isEmpty();
    }

    @Test
    void answer_withValues_answersAParkedHumanInput() {
        String id = services.start(request(WHO), null, null).runId();

        AnswerResult answered = services.answer(id, new AnswerRequest("human-input", 0, null,
                Map.of("who", "Alex"), null));
        RunResult resumed = services.resume(resumeOf(id), null, null);

        assertThat(answered.accepted()).isTrue();
        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.commands.commands()).containsExactly("echo Alex");
    }

    @Test
    void answer_withPlainText_isTheValueOfTheQuestionsVariable() {
        String id = services.start(request(WHO), null, null).runId();

        AnswerResult answered = services.answer(id, new AnswerRequest("human-input", null, "Alex", null, null));
        RunResult resumed = services.resume(resumeOf(id), null, null);

        assertThat(answered.accepted()).as(answered.message()).isTrue();
        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.commands.commands()).containsExactly("echo Alex");
    }

    @Test
    void resume_withoutAnAnswer_isRefused() {
        String id = services.start(request(DEPLOY), null, null).runId();

        assertThatThrownBy(() -> services.resume(resumeOf(id), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("traceworks approve");
        assertThat(fx.run(id).writerAlive()).isFalse();
        assertThat(fx.types(id).getLast()).isEqualTo("run.parked");
    }

    @Test
    void resume_ofAFinishedRun_isRefused() {
        String id = services.start(request(BUILD), null, null).runId();
        int before = fx.events(id).size();

        assertThatThrownBy(() -> services.resume(resumeOf(id), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("COMPLETED");
        assertThat(fx.events(id)).hasSize(before);
    }

    @Test
    void resume_whileAnotherProcessWrites_isRefused() {
        String id = services.start(request(DEPLOY), null, null).runId();
        services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        fx.run(id).simulateOtherWriter(true);

        assertThatThrownBy(() -> services.resume(resumeOf(id), null, null))
                .isInstanceOf(RunRefusedException.class)
                .message().containsIgnoringCase("another process");
    }

    @Test
    void resume_afterThePackageChanged_isRefused_unlessAllowed() {
        String id = services.start(request(DEPLOY), null, null).runId();
        services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        fx.packages.put(DEPLOY, """
                {"id": "wf-deploy", "name": "Deploy", "blocks": [
                  {"type": "shell", "name": "build", "config": {"command": "./build", "run_if": "test -f ready"}},
                  {"type": "approval", "name": "publish",
                   "config": {"question": "Publish {{ version }}?", "options": ["Yes", "No"]}},
                  {"type": "shell", "name": "done", "config": {"command": "./done --changed"}}
                ]}""");
        String newDigest = fx.packages.load(DEPLOY).digest();

        assertThatThrownBy(() -> services.resume(resumeOf(id), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("changed")
                .hasMessageContaining("--allow-changed-package");
        assertThat(fx.ofType(id, "run.resumed")).isEmpty();

        RunResult resumed = services.resume(new ResumeRequest(id, false, true), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        RecordedEvent resumedEvent = fx.ofType(id, "run.resumed").getFirst();
        assertThat(resumedEvent.fields().path("package_changed").asBoolean()).isTrue();
        assertThat(resumedEvent.fields().path("package_digest").asText()).isEqualTo(newDigest);
        assertThat(fx.commands.commands()).as("the definition recorded at the start runs")
                .containsExactly("test -f ready", "./build", "./done");
    }

    @Test
    void resume_ofAnInterruptedRun_rerunsTheStepInFlight() {
        AtomicBoolean diskFills = new AtomicBoolean(true);
        fx.commands.on("./test", request -> {
            if (diskFills.getAndSet(false)) {
                InMemoryEventLog log = fx.run(fx.store.runIds().getFirst()).log();
                log.failAfter(log.readAll().size());   // the process can record nothing more and goes down
            }
            return FakeCommandRunner.ok("tested");
        });
        assertThatThrownBy(() -> services.start(request(BUILD), null, null)).isInstanceOf(EventLogException.class);
        String id = fx.store.runIds().getFirst();
        assertThat(services.view(id).status()).isEqualTo(RunStatus.INTERRUPTED);
        fx.run(id).log().failAfter(-1);

        RunResult resumed = services.resume(resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        RecordedEvent resumedEvent = fx.ofType(id, "run.resumed").getFirst();
        assertThat(resumedEvent.fields().path("rerun_step").asInt()).isEqualTo(1);
        assertThat(resumedEvent.fields().path("next_position").asInt()).isEqualTo(1);
        assertThat(resumedEvent.fields().path("answer").isNull()).isTrue();
        assertThat(resumedEvent.fields().has("container_runtime")).isTrue();
        assertThat(resumedEvent.fields().path("container_runtime").isNull()).as("no runtime found").isTrue();
        assertThat(fx.commands.commands()).containsExactly("./build", "./test", "./test");
        assertThat(services.view(id).steps()).extracting(StepView::attempts).containsExactly(1, 2);
    }

    @Test
    void resume_withoutHostExecution_isRefusedBeforeThePackageIsLoaded_andRecordsNothing() {
        String id = services.start(request(DEPLOY), null, null).runId();
        services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        fx.packages.put(DEPLOY, "{ not a package");   // loading it now would fail with another message
        fx.settings = new OperatorSettings(false, null, List.of());
        RunServices strict = fx.services();
        int before = fx.events(id).size();

        assertThatThrownBy(() -> strict.resume(resumeOf(id), null, null))
                .isInstanceOf(RunRefusedException.class)
                .message().containsIgnoringCase("host execution is not allowed")
                .contains("--allow-host-execution");
        assertThat(fx.events(id)).hasSize(before);
        assertThat(fx.commands.commands()).doesNotContain("./done");
    }

    @Test
    void resume_withHostExecutionAllowedForTheResume_runs() {
        String id = services.start(request(DEPLOY), null, null).runId();
        services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        fx.settings = new OperatorSettings(false, null, List.of());

        RunResult resumed = fx.services().resume(new ResumeRequest(id, true, false), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
    }

    /**
     * {@code run.started} records the output limit of the operator's settings, and {@code run.resumed} the one in
     * force when the run resumes: the commands after the resume run with that one.
     */
    @Test
    void theOutputLimit_isRecordedAtTheStart_andAgainAtTheResume() {
        fx.settings = new OperatorSettings(true, null, List.of(), null, null, 4);
        String id = fx.services().start(request(DEPLOY), null, null).runId();
        fx.services().answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        fx.settings = new OperatorSettings(true, null, List.of(), null, null, 64);

        RunResult resumed = fx.services().resume(resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.ofType(id, "run.started").getFirst().fields().path("output_limit_mib").asInt()).isEqualTo(4);
        assertThat(fx.ofType(id, "run.resumed").getFirst().fields().path("output_limit_mib").asInt()).isEqualTo(64);
        String plain = services.start(request(BUILD), null, null).runId();
        assertThat(fx.ofType(plain, "run.started").getFirst().fields().path("output_limit_mib").asInt())
                .as("the default").isEqualTo(16);
    }

    /**
     * {@code run.started} records the output budget in force, the default as a number too, and {@code run.resumed} the
     * one in force when the run resumes.
     */
    @Test
    void theOutputBudget_isRecordedAtTheStart_andAgainAtTheResume() {
        fx.settings = new OperatorSettings(true, null, List.of(), null, null, 4, java.util.Map.of(), 40);
        String id = fx.services().start(request(DEPLOY), null, null).runId();
        fx.services().answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        fx.settings = new OperatorSettings(true, null, List.of(), null, null, 64);

        RunResult resumed = fx.services().resume(resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.ofType(id, "run.started").getFirst().fields().path("run_output_budget_mib").asInt())
                .isEqualTo(40);
        assertThat(fx.ofType(id, "run.resumed").getFirst().fields().path("run_output_budget_mib").asInt())
                .as("eight times the output limit of 64 MiB").isEqualTo(512);
        String plain = services.start(request(BUILD), null, null).runId();
        assertThat(fx.ofType(plain, "run.started").getFirst().fields().path("run_output_budget_mib").asInt())
                .as("the default").isEqualTo(128);
    }

    /** The operator's budget binds the run: the step beyond it fails with the note, and the run ends there. */
    @Test
    void aStepBeyondTheOperatorsOutputBudget_failsTheRun_andTheRecordSaysWhy() {
        fx.settings = new OperatorSettings(true, null, List.of(), null, null, 1, java.util.Map.of(), 2);
        fx.commands.on("./build", "x".repeat(3 * 1024 * 1024));
        String note = "The output this run holds in memory would exceed its budget of 2 MiB; Traceworks failed this"
                + " step and ended the run";

        RunResult result = fx.services().start(request(BUILD), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.error()).isEqualTo("Step 0 (shell) failed: " + note);
        assertThat(fx.commands.commands()).containsExactly("./build");
        RecordedEvent output = fx.ofType(result.runId(), "step.output").getFirst();
        assertThat(fx.text(result.runId(), output, "output")).isEqualTo("x".repeat(3 * 1024 * 1024) + "\n" + note);
        assertThat(fx.types(result.runId())).last().isEqualTo("run.finished");
    }

    /**
     * A parked run whose checkpoint holds more than the budget now in force is refused before anything is recorded,
     * and its answer stays for a resume with a larger budget.
     */
    @Test
    void aResumeBeyondTheBudgetInForce_isRefused_andTheParkedAnswerStaysUnused() {
        fx.commands.on("./build", "x".repeat(3 * 1024 * 1024));
        String id = fx.services().start(request(DEPLOY), null, null).runId();
        fx.services().answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        int recorded = fx.events(id).size();
        fx.settings = new OperatorSettings(true, null, List.of(), null, null, 1, java.util.Map.of(), 2);

        assertThatThrownBy(() -> fx.services().resume(resumeOf(id), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessage("Run " + id + " cannot resume: its checkpoint holds 3.0 MiB of output text in memory,"
                        + " more than its output budget of 2 MiB; raise run_output_budget_mib in traceworks.json to"
                        + " resume it");
        assertThat(fx.events(id)).as("nothing recorded").hasSize(recorded);

        fx.settings = new OperatorSettings(true, null, List.of());
        RunResult resumed = fx.services().resume(resumeOf(id), null, null);

        assertThat(resumed.status()).as("the parked answer approves the step now").isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.ofType(id, "run.resumed")).as("only the resume that ran is recorded").hasSize(1);
        assertThat(fx.commands.commands()).as("./done after the approval").contains("./done");
    }

    /**
     * Review C, L3: a checkpoint whose step texts alone take more than the budget is refused from the sizes its
     * references name, before a text is read into memory: here the step's output blob is even gone, which a read
     * would report instead.
     */
    @Test
    void aResumeFarBeyondTheBudget_isRefusedBeforeItReadsATextOfTheCheckpoint() {
        fx.commands.on("./build", "x".repeat(5 * 1024 * 1024));
        String id = fx.services().start(request(DEPLOY), null, null).runId();
        fx.services().answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        RecordedEvent output = fx.ofType(id, "step.output").getFirst();
        fx.run(id).blobs().delete(output.blob("output").sha256());
        fx.settings = new OperatorSettings(true, null, List.of(), null, null, 1, java.util.Map.of(), 2);

        assertThatThrownBy(() -> fx.services().resume(resumeOf(id), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessage("Run " + id + " cannot resume: its checkpoint holds at least 2.5 MiB of output text in"
                        + " memory, more than its output budget of 2 MiB; raise run_output_budget_mib in"
                        + " traceworks.json to resume it");
    }

    /** {@code run.resumed} records the replacements of the AI images in force when the run resumes. */
    @Test
    void theAiImages_areRecordedAtTheStart_andAgainAtTheResume() {
        fx.settings = new OperatorSettings(true, null, List.of(), null, null, 16, java.util.Map.of("pi", "pi:2"));
        String id = fx.services().start(request(DEPLOY), null, null).runId();
        fx.services().answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        fx.settings = new OperatorSettings(true, null, List.of(), null, null, 16,
                java.util.Map.of("claude", "claude:7", "pi", "pi:3"));

        fx.services().resume(resumeOf(id), null, null);

        assertThat(fx.ofType(id, "run.started").getFirst().fields().path("ai_images").toString())
                .isEqualTo("{\"pi\":\"pi:2\"}");
        assertThat(fx.ofType(id, "run.resumed").getFirst().fields().path("ai_images").toString())
                .isEqualTo("{\"claude\":\"claude:7\",\"pi\":\"pi:3\"}");
    }

    /** Where the AI containers can connect is the operator's setting when a process runs: a resume records its own. */
    @Test
    void theAiNetwork_isRecordedAtTheStart_andAgainAtTheResume() {
        fx.settings = new OperatorSettings(true, null, List.of()).withAiNetwork(
                new traceworks.core.port.AiNetwork(false,
                        java.util.Map.of("claude", List.of("gateway.example:8443"))));
        String id = fx.services().start(request(DEPLOY), null, null).runId();
        fx.services().answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        fx.settings = new OperatorSettings(true, null, List.of()).withAiNetwork(
                new traceworks.core.port.AiNetwork(true, java.util.Map.of()));

        fx.services().resume(resumeOf(id), null, null);

        com.fasterxml.jackson.databind.JsonNode started = fx.ofType(id, "run.started").getFirst().fields();
        assertThat(started.path("ai_network").asText()).isEqualTo("restricted");
        assertThat(started.path("ai_hosts").toString()).isEqualTo("{\"claude\":[\"gateway.example:8443\"]}");
        com.fasterxml.jackson.databind.JsonNode resumed = fx.ofType(id, "run.resumed").getFirst().fields();
        assertThat(resumed.path("ai_network").asText()).isEqualTo("open");
        assertThat(resumed.path("ai_hosts").toString()).isEqualTo("{}");
    }

    @Test
    void resume_withATamperedAnswerBlob_isRefused_andRunsNothing() {
        String id = services.start(request(DEPLOY), null, null).runId();
        services.answer(id, new AnswerRequest("approval", 1, "No", null, null));
        String answer = fx.ofType(id, "approval.answered").getFirst().blob("answer").sha256();
        fx.run(id).blobs().putRaw(answer, "Yes".getBytes(StandardCharsets.UTF_8));
        int before = fx.events(id).size();

        assertRefusedAsDamaged(id, answer, "parked answer");
        assertThat(fx.events(id)).hasSize(before);
        assertThat(fx.commands.commands()).containsExactly("test -f ready", "./build");
        assertThat(fx.ledger.find(id)).isEmpty();
    }

    @Test
    void resume_withATamperedCheckpoint_isRefused() throws Exception {
        String id = services.start(request(DEPLOY), null, null).runId();
        services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        String state = services.view(id).lastCheckpoint().state().sha256();
        String original = new String(fx.run(id).blobs().get(state).orElseThrow(), StandardCharsets.UTF_8);
        String forged = original.replace("\"previous_output_tainted\":false", "\"previous_output_tainted\":true");
        assertThat(forged).isNotEqualTo(original);
        fx.run(id).blobs().putRaw(state, forged.getBytes(StandardCharsets.UTF_8));

        assertRefusedAsDamaged(id, state, "checkpoint state");
        assertThat(fx.ofType(id, "run.resumed")).isEmpty();
    }

    @Test
    void resume_withATamperedDefinition_isRefused() {
        String id = services.start(request(DEPLOY), null, null).runId();
        services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        String definition = services.view(id).definition().sha256();
        fx.run(id).blobs().putRaw(definition, """
                {"id": "wf-deploy", "name": "Deploy", "blocks": [
                  {"type": "shell", "name": "build", "config": {"command": "./build"}},
                  {"type": "approval", "name": "publish",
                   "config": {"question": "Publish {{ version }}?", "options": ["Yes", "No"]}},
                  {"type": "shell", "name": "done", "config": {"command": "./evil"}}
                ]}""".getBytes(StandardCharsets.UTF_8));

        assertRefusedAsDamaged(id, definition, "workflow definition");
        assertThat(fx.commands.commands()).doesNotContain("./evil");
    }

    @Test
    void answer_toAParkedQuestionWhoseBlobWasTampered_isRefused() {
        String id = services.start(request(DEPLOY), null, null).runId();
        String question = services.view(id).pendingQuestion().question().sha256();
        fx.run(id).blobs().putRaw(question, "Publish 9.9?".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null)))
                .isInstanceOf(RecordDamagedException.class)
                .hasMessageContaining(question)
                .hasMessageContaining("traceworks verify " + id);
        assertThat(fx.ofType(id, "approval.answered")).isEmpty();
    }

    /**
     * A run that another process writes and that waits for an answer at step 0, but whose process never reads its
     * inbox: a request to it is never recorded.
     */
    private String runningRunThatNeverReadsItsInbox() {
        String id = "00000000-0000-0000-0000-00000000beef";
        try (var run = fx.store.create(id, fx.clock.now())) {
            var definition = fx.packages.load(SHADOW).definition();
            RunRecorder recorder = RunRecorder.start(run.events(), run.blobs(), fx.clock, id, definition);
            recorder.runStarted(new traceworks.core.record.RunStart(definition.id(),
                    definition.name(), SHADOW.toString(), "digest", null, null,
                    "{}".getBytes(StandardCharsets.UTF_8), Map.of(), traceworks.core.model
                    .TriggerSource.MANUAL, ACTOR, "0.1.0", "host", null, null));
            recorder.emit(new traceworks.core.port.RunEvent.StepStarted(0, "publish", "approval", 0,
                    false));
            recorder.emit(new traceworks.core.port.RunEvent.ApprovalRequested(0, "b1", "approval",
                    "Publish?", List.of("Yes", "No"), List.of(), null, null, false, null, null, null));
            recorder.emit(new traceworks.core.port.RunEvent.StepWaiting(0, true));
        }
        fx.run(id).simulateOtherWriter(true);
        return id;
    }

    @Test
    void answer_thatTheRunsProcessDoesNotRecordInTime_isQueued_notRefused() {
        String id = runningRunThatNeverReadsItsInbox();
        RunServices impatient = fx.services(java.time.Duration.ofMillis(300));

        assertThatThrownBy(() -> impatient.answer(id, new AnswerRequest("approval", 0, "Yes", null, null)))
                .isInstanceOf(RequestQueuedException.class)
                .hasMessageContaining("not yet recorded")
                .hasMessageContaining(fx.run(id).inbox().posted().getLast().id());
        assertThat(fx.ofType(id, "approval.answered")).isEmpty();
    }

    @Test
    void cancel_thatTheRunsProcessDoesNotRecordInTime_isQueued() {
        String id = runningRunThatNeverReadsItsInbox();
        RunServices impatient = fx.services(java.time.Duration.ofMillis(300));

        assertThatThrownBy(() -> impatient.cancel(id))
                .isInstanceOf(RequestQueuedException.class)
                .hasMessageContaining("not yet recorded")
                .hasMessageContaining(fx.run(id).inbox().posted().getLast().id());
        assertThat(fx.ofType(id, "run.finished")).isEmpty();
    }

    @Test
    void cancel_withATamperedCheckpoint_isRefused() {
        String id = services.start(request(DEPLOY), null, null).runId();
        String state = services.view(id).lastCheckpoint().state().sha256();
        fx.run(id).blobs().putRaw(state, "{}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> services.cancel(id))
                .isInstanceOf(RecordDamagedException.class)
                .hasMessageContaining(state)
                .hasMessageContaining("traceworks verify " + id);
        assertThat(fx.ofType(id, "run.finished")).isEmpty();
    }

    @Test
    void finishing_withAChainThatDoesNotHold_writesNoManifestAndNoLedgerEntry() {
        String id = services.start(request(DEPLOY), null, null).runId();
        services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        // A blob the checkpoint does not refer to, so the run goes on to its end; a forged output refuses the resume
        String metadata = fx.ofType(id, "step.finished").getFirst().blob("metadata").sha256();
        fx.run(id).blobs().putRaw(metadata, "{\"forged\":true}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> services.resume(resumeOf(id), null, null))
                .isInstanceOf(RecordDamagedException.class)
                .hasMessageContaining("traceworks verify " + id)
                .hasMessageContaining(metadata);
        assertThat(fx.ofType(id, "run.finished")).hasSize(1);
        assertThat(fx.run(id).readManifest()).isEmpty();
        assertThat(fx.ledger.find(id)).isEmpty();
        assertThat(fx.run(id).view()).hasValueSatisfying(html -> assertThat(html).contains("NOT intact"));
    }

    private void assertRefusedAsDamaged(String id, String sha, String what) {
        assertThatThrownBy(() -> services.resume(resumeOf(id), null, null))
                .isInstanceOf(RecordDamagedException.class)
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining(what)
                .hasMessageContaining(sha)
                .hasMessageContaining("traceworks verify " + id);
        assertThat(fx.run(id).writerAlive()).isFalse();
    }

    @Test
    void resume_afterAStepFinishedButBeforeItsCheckpoint_namesThatStepAsRerun() {
        AtomicBoolean diskFills = new AtomicBoolean(true);
        fx.commands.on("./test", request -> {
            if (diskFills.getAndSet(false)) {
                InMemoryEventLog log = fx.run(fx.store.runIds().getFirst()).log();
                // step.command.finished, step.output and step.finished are written; the checkpoint is not
                log.failAfter(log.readAll().size() + 3);
            }
            return FakeCommandRunner.ok("tested");
        });
        assertThatThrownBy(() -> services.start(request(BUILD), null, null)).isInstanceOf(EventLogException.class);
        String id = fx.store.runIds().getFirst();
        assertThat(fx.types(id).getLast()).isEqualTo("step.finished");
        assertThat(services.view(id).steps().get(1).interrupted()).as("the step is not open").isFalse();
        fx.run(id).log().failAfter(-1);

        services.resume(resumeOf(id), null, null);

        RecordedEvent resumedEvent = fx.ofType(id, "run.resumed").getFirst();
        assertThat(resumedEvent.fields().path("rerun_step").asInt()).isEqualTo(1);
        assertThat(fx.commands.commands()).containsExactly("./build", "./test", "./test");
    }

    @Test
    void resume_recoversATornTail() {
        String id = services.start(request(DEPLOY), null, null).runId();
        services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        fx.run(id).log().tearTail("{\"seq\":99,\"at\"".getBytes(StandardCharsets.UTF_8));

        services.resume(resumeOf(id), null, null);

        List<String> types = fx.types(id);
        assertThat(types.indexOf("run.recovered")).isPositive().isLessThan(types.indexOf("run.resumed"));
        RecordedEvent recovered = fx.ofType(id, "run.recovered").getFirst();
        assertThat(fx.text(id, recovered, "discarded")).isEqualTo("{\"seq\":99,\"at\"");
        assertThat(services.verify(id).ok()).isTrue();
    }

    /** The resumed step asks its parked question again and takes the preset answer: nobody is asked. */
    @Test
    void resume_withAnAsker_doesNotAskTheReplayedQuestion() {
        String id = services.start(request(DEPLOY), null, null).runId();
        services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        List<QuestionView> asked = new CopyOnWriteArrayList<>();

        RunResult resumed = services.resume(resumeOf(id), null, question -> {
            asked.add(question);
            return Optional.empty();
        });

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(asked).isEmpty();
    }

    // ---- a running run: the inbox and the terminal ----

    @Test
    void inboxAnswer_reachesANestedApprovalWhileRunning() throws Exception {
        Events events = new Events();
        Background<RunResult> running = inBackground(() -> services.start(request(PARENT), events, null));
        RecordedEvent asked = events.await("approval.requested");
        String id = asked.parent();

        AnswerResult result = services.answer(id, new AnswerRequest("approval", null, "Yes", null, "fine"));

        assertThat(result.accepted()).isTrue();
        assertThat(result.runParked()).isFalse();
        assertThat(running.join().status()).isEqualTo(RunStatus.COMPLETED);
        RecordedEvent answered = fx.ofType(id, "approval.answered").getFirst();
        assertThat(answered.run()).isEqualTo(asked.run()).isNotEqualTo(id);
        assertThat(answered.fields().path("source").asText()).isEqualTo("inbox");
        assertThat(answered.fields().path("actor").asText()).isEqualTo(ACTOR);
        assertThat(answered.fields().path("request").asText()).isNotBlank();
        assertThat(fx.text(id, answered, "comment")).isEqualTo("fine");
        assertThat(fx.commands.commands()).containsExactly("./after");
        assertThat(fx.run(id).inbox().posted()).as("the answer names the question the record showed")
                .singleElement().satisfies(posted -> {
                    assertThat(posted.run()).isEqualTo(asked.run());
                    assertThat(posted.step()).isEqualTo(asked.step());
                    assertThat(posted.questionSeq()).isEqualTo(asked.seq());
                });
    }

    @Test
    void inboxAnswer_whileNoQuestionIsOpen_isRefusedWithoutAPost() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        fx.commands.on("./build", request -> {
            try {
                release.await(AppFixture.WAIT.toSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return FakeCommandRunner.ok("built");
        });
        Events events = new Events();
        Background<RunResult> running = inBackground(() -> services.start(request(BUILD), events, null));
        String id = events.await("step.command").run();

        AnswerResult result;
        try {
            result = services.answer(id, new AnswerRequest("approval", null, "Yes", null, null));
        } finally {
            release.countDown();
        }

        assertThat(result.accepted()).isFalse();
        assertThat(result.runParked()).isFalse();
        assertThat(result.message()).contains("waits for no answer");
        assertThat(running.join().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.run(id).inbox().posted()).isEmpty();
        assertThat(fx.ofType(id, "request.refused")).isEmpty();
        assertThat(fx.ofType(id, "approval.answered")).isEmpty();
    }

    /**
     * B answers the first of two questions; the answer arrives only after A answered it and the second one opened.
     * It must not become the answer to the second question.
     */
    @Test
    void inboxAnswer_forAQuestionAnsweredMeanwhile_isRefused_andTheNextStaysOpen() throws Exception {
        Events events = new Events();
        StartRequest interactive = new StartRequest(AppFixture.TWICE, Map.of(), WORKSPACE, false, true);
        Background<RunResult> running = inBackground(
                () -> services.start(interactive, events, question -> Optional.empty()));
        RecordedEvent first = events.await(e -> topLevelQuestion(e, 0));
        String id = first.run();
        assertThat(services.answer(id, new AnswerRequest("approval", null, "Yes", null, null)).accepted()).isTrue();
        events.await(e -> topLevelQuestion(e, 1));

        fx.run(id).inbox().post(new InboxRequest("late-b", "approval", id, first.step(), first.seq(), "No",
                Map.of(), null, "b@box", fx.clock.now()));
        RecordedEvent refused = events.await(e -> refusalOf(e, "late-b"));

        assertThat(refused.fields().path("reason").asText()).contains("no longer open");
        assertThat(services.view(id).steps().get(1).status()).as("the second question still waits")
                .isEqualTo(StepStatus.WAITING_APPROVAL);
        assertThat(services.answer(id, new AnswerRequest("approval", null, "Yes", null, null)).accepted()).isTrue();
        assertThat(running.join().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.ofType(id, "approval.answered")).hasSize(2)
                .noneMatch(e -> "late-b".equals(e.fields().path("request").asText()));
        assertThat(fx.run(id).inbox().posted().getFirst().questionSeq()).isEqualTo(first.seq());
    }

    /** A child's question at step 0 is not the top-level question at step 0. */
    @Test
    void inboxAnswer_forATopLevelQuestion_doesNotReachAChildQuestionAtTheSameStep() throws Exception {
        Events events = new Events();
        StartRequest interactive = new StartRequest(AppFixture.NEST, Map.of(), WORKSPACE, false, true);
        Background<RunResult> running = inBackground(
                () -> services.start(interactive, events, question -> Optional.empty()));
        RecordedEvent top = events.await(e -> topLevelQuestion(e, 0));
        String id = top.run();
        assertThat(services.answer(id, new AnswerRequest("approval", null, "Yes", null, null)).accepted()).isTrue();
        RecordedEvent child = events.await(e -> "approval.requested".equals(e.type()) && e.parent() != null);
        assertThat(child.step()).isEqualTo(top.step());

        fx.run(id).inbox().post(new InboxRequest("late-top", "approval", id, top.step(), top.seq(), "No",
                Map.of(), null, "b@box", fx.clock.now()));
        RecordedEvent refused = events.await(e -> refusalOf(e, "late-top"));

        assertThat(refused.fields().path("reason").asText()).contains("no longer open");
        assertThat(services.answer(id, new AnswerRequest("approval", null, "Yes", null, null)).accepted()).isTrue();
        assertThat(fx.run(id).inbox().posted().getLast().run()).isEqualTo(child.run());
        assertThat(running.join().status()).isEqualTo(RunStatus.COMPLETED);
        List<RecordedEvent> answers = fx.ofType(id, "approval.answered");
        assertThat(answers).extracting(RecordedEvent::run).containsExactly(id, child.run());
        assertThat(answers).noneMatch(e -> "late-top".equals(e.fields().path("request").asText()));
        assertThat(fx.text(id, answers.get(1), "answer")).isEqualTo("Yes");
    }

    private static boolean topLevelQuestion(RecordedEvent event, int step) {
        return "approval.requested".equals(event.type()) && event.parent() == null
                && Objects.equals(event.step(), step);
    }

    private static boolean refusalOf(RecordedEvent event, String requestId) {
        return "request.refused".equals(event.type())
                && requestId.equals(event.fields().path("request").asText());
    }

    @Test
    void inboxCancel_endsTheRunCancelled() throws Exception {
        Events events = new Events();
        Background<RunResult> running = inBackground(() -> services.start(request(PARENT), events, null));
        String id = events.await("approval.requested").parent();

        RunResult cancelled = services.cancel(id);

        assertThat(cancelled.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(running.join().status()).isEqualTo(RunStatus.CANCELLED);
        List<String> types = fx.types(id);
        assertThat(types.getLast()).isEqualTo("run.finished");
        assertThat(types.indexOf("run.cancel_requested")).isPositive().isLessThan(types.size() - 1);
        RecordedEvent requested = fx.ofType(id, "run.cancel_requested").getFirst();
        assertThat(requested.fields().path("actor").asText()).isEqualTo(ACTOR);
        assertThat(requested.fields().path("request").asText()).isNotBlank();
        assertThat(fx.run(id).readManifest()).isPresent();
        assertThat(fx.ledger.find(id)).isPresent();
        assertThat(fx.commands.commands()).as("no further block starts").isEmpty();
    }

    @Test
    void cancelActiveRuns_cancelsTheRunsOfThisProcess() throws Exception {
        Events events = new Events();
        Background<RunResult> running = inBackground(() -> services.start(request(PARENT), events, null));
        String id = events.await("approval.requested").parent();

        services.cancelActiveRuns();

        assertThat(running.join().status()).isEqualTo(RunStatus.CANCELLED);
        RecordedEvent requested = fx.ofType(id, "run.cancel_requested").getFirst();
        assertThat(requested.fields().path("actor").asText()).isEqualTo(ACTOR);
    }

    @Test
    void interactive_asksTheAsker_andRecordsSourceTerminal() {
        List<QuestionView> asked = new CopyOnWriteArrayList<>();
        StartRequest interactive = new StartRequest(DEPLOY, Map.of("version", "1.2"), WORKSPACE, false, true);

        RunResult result = services.start(interactive, null, question -> {
            asked.add(question);
            return Optional.of("yes");
        });

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        String id = result.runId();
        assertThat(asked).singleElement().satisfies(question -> {
            assertThat(question.step()).isEqualTo(1);
            assertThat(question.kind()).isEqualTo("approval");
            assertThat(question.options()).containsExactly("Yes", "No");
            assertThat(new String(fx.run(id).blobs().get(question.question().sha256()).orElseThrow(),
                    StandardCharsets.UTF_8)).isEqualTo("Publish 1.2?");
        });
        RecordedEvent answered = fx.ofType(id, "approval.answered").getFirst();
        assertThat(answered.fields().path("source").asText()).isEqualTo("terminal");
        assertThat(answered.fields().path("actor").asText()).isEqualTo(ACTOR);
        assertThat(fx.text(id, answered, "answer")).isEqualTo("Yes");
        assertThat(fx.types(id)).doesNotContain("run.parked");
    }

    @Test
    void interactive_withoutAnAnswerFromTheTerminal_takesOneFromTheInbox() throws Exception {
        Events events = new Events();
        StartRequest interactive = new StartRequest(DEPLOY, Map.of("version", "1.2"), WORKSPACE, false, true);
        Background<RunResult> running = inBackground(
                () -> services.start(interactive, events, question -> Optional.empty()));
        String id = events.await("approval.requested").run();

        AnswerResult result = services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));

        assertThat(result.accepted()).isTrue();
        assertThat(running.join().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.ofType(id, "approval.answered")).singleElement()
                .satisfies(e -> assertThat(e.fields().path("source").asText()).isEqualTo("inbox"));
    }

    /** A shadow question never parks: without a terminal it waits in the process, and the inbox answers it. */
    @Test
    void shadowQuestion_withoutATerminal_waitsForTheInbox() throws Exception {
        fx.withClaude();
        fx.claude.answers("Yes");
        Events events = new Events();
        Background<RunResult> running = inBackground(
                () -> services.start(request(AppFixture.SHADOW), events, null));
        String id = events.await("approval.requested").run();

        AnswerResult result = services.answer(id, new AnswerRequest("approval", 0, "Yes", null, null));

        assertThat(result.accepted()).isTrue();
        assertThat(running.join().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.types(id)).doesNotContain("run.parked");
        assertThat(fx.claude.prompts()).as("the AI proposed once").hasSize(1);
    }

    @Test
    void approvalRequested_saysWhetherTheRunParksAtIt() throws Exception {
        String parkedId = services.start(request(DEPLOY), null, null).runId();
        fx.withClaude();
        fx.claude.answers("Yes");
        Events events = new Events();
        Background<RunResult> shadow = inBackground(() -> services.start(request(AppFixture.SHADOW), events, null));
        String shadowId = events.await("approval.requested").run();
        services.answer(shadowId, new AnswerRequest("approval", 0, "Yes", null, null));
        shadow.join();

        assertThat(fx.ofType(parkedId, "approval.requested")).singleElement()
                .satisfies(e -> {
                    assertThat(e.fields().path("parks").asBoolean()).isTrue();
                    assertThat(e.fields().path("preset").asBoolean()).isFalse();
                });
        QuestionView parkedQuestion = services.view(parkedId).pendingQuestion();
        assertThat(parkedQuestion.parks()).isTrue();
        assertThat(parkedQuestion.preset()).isFalse();
        assertThat(fx.ofType(shadowId, "approval.requested")).singleElement()
                .satisfies(e -> {
                    assertThat(e.fields().path("parks").asBoolean()).isFalse();
                    assertThat(e.fields().path("preset").asBoolean()).isFalse();
                });
    }

    @Test
    void approvalRequested_afterAResume_isPresetAndDoesNotPark() {
        String id = services.start(request(DEPLOY), null, null).runId();
        services.answer(id, new AnswerRequest("approval", null, "Yes", null, null));
        services.resume(resumeOf(id), null, null);

        assertThat(fx.ofType(id, "approval.requested")).hasSize(2);
        JsonNode again = fx.ofType(id, "approval.requested").getLast().fields();
        assertThat(again.path("parks").asBoolean()).isFalse();
        assertThat(again.path("preset").asBoolean()).isTrue();
    }

    // ---- cancel ----

    @Test
    void cancel_ofAParkedRun_finishesItWithManifestAndLedger() throws Exception {
        String id = services.start(request(GATE), null, null).runId();

        RunResult cancelled = services.cancel(id);

        assertThat(cancelled.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(cancelled.gate()).containsEntry("gate_status", "YELLOW").containsEntry("gate_category", "lint");
        List<String> types = fx.types(id);
        assertThat(types.subList(types.size() - 2, types.size()))
                .containsExactly("run.cancel_requested", "run.finished");
        RecordedEvent finished = fx.events(id).getLast();
        assertThat(finished.fields().path("status").asText()).isEqualTo("CANCELLED");
        assertThat(finished.fields().path("gate").path("status").asText()).isEqualTo("YELLOW");
        byte[] manifest = fx.run(id).readManifest().orElseThrow();
        assertThat(JSON.readTree(manifest).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(fx.ledger.find(id)).hasValueSatisfying(copy -> assertThat(copy).isEqualTo(manifest));
        assertThat(fx.run(id).view()).hasValueSatisfying(html -> assertThat(html).contains("CANCELLED"));
        assertThat(services.view(id).status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(fx.run(id).writerAlive()).isFalse();
    }

    /** Another process refused the cancel (its inbox watcher could not handle it): the cancel says so. */
    @Test
    void cancel_whoseRequestIsRefused_reportsTheRefusal() throws Exception {
        String id = services.start(request(DEPLOY), null, null).runId();
        InMemoryStoredRun other = fx.run(id);
        assertThat(other.tryLockWriter()).isTrue();
        try {
            Background<RunResult> cancelling = inBackground(() -> services.cancel(id));
            InboxRequest cancel = fx.run(id).inbox().awaitPosted(1, AppFixture.WAIT);
            RunRecorder recorder = RunRecorder.continueAfter(other.events(), other.blobs(), fx.clock, id,
                    RunRecords.RECORDING_ONLY, fx.events(id));
            other.inbox().take();
            RunRecords.refuse(recorder, cancel, "the request could not be handled: disk full");

            assertThat(cancelling.failure()).isInstanceOf(RunRefusedException.class)
                    .hasMessageContaining("could not be handled: disk full");
        } finally {
            other.close();
        }
        assertThat(fx.types(id)).doesNotContain("run.cancel_requested", "run.finished");
    }

    /** The cancel reached the run's process, which died before the run ended: the cancel finishes the run here. */
    @Test
    void cancel_ofARunThatStopsAfterTheCancelWasSeen_finishesItHere() throws Exception {
        String id = services.start(request(GATE), null, null).runId();
        InMemoryStoredRun other = fx.run(id);
        assertThat(other.tryLockWriter()).isTrue();
        Background<RunResult> cancelling = inBackground(() -> services.cancel(id));
        InboxRequest cancel = fx.run(id).inbox().awaitPosted(1, AppFixture.WAIT);
        other.inbox().take();
        RunRecorder recorder = RunRecorder.continueAfter(other.events(), other.blobs(), fx.clock, id,
                RunRecords.RECORDING_ONLY, fx.events(id));
        recorder.record(RecordTypes.RUN_CANCEL_REQUESTED, RunRecords.cancelRequested(cancel.actor(), cancel.id()));
        other.close();

        RunResult result = cancelling.join();

        assertThat(result.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(fx.ofType(id, "run.cancel_requested")).as("recorded once").singleElement()
                .satisfies(e -> assertThat(e.fields().path("request").asText()).isEqualTo(cancel.id()));
        assertThat(fx.types(id).getLast()).isEqualTo("run.finished");
        assertThat(fx.run(id).readManifest()).isPresent();
    }

    @Test
    void cancel_ofAFinishedRun_isRefused() {
        String id = services.start(request(BUILD), null, null).runId();

        assertThatThrownBy(() -> services.cancel(id))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("COMPLETED");
    }

    // ---- a checkout of a source (--source) ----

    /** A shell step that prints the commit of its checkout. */
    private static final Path SHA_STEP = Path.of("/pkg/sha");
    /** A shell step that may write its workspace. */
    private static final Path WRITER = Path.of("/pkg/writer");
    private static final String SOURCE = "https://git.example.org/org/repo";

    private Path checkoutOf(String runId) {
        return AppFixture.Sources.ROOT.resolve(runId);
    }

    /** A container run: its commands see the workspace read-only unless a block says otherwise. */
    private RunServices inContainers() {
        fx.settings = OperatorSettings.defaults();
        fx.withRuntime();
        fx.containers.on(AppFixture.SHELL_ID, FakeCommandRunner.ok("in a container"));
        fx.packages.put(SHA_STEP, """
                {"id": "wf-sha", "name": "Sha", "blocks": [
                  {"type": "shell", "name": "sha", "config": {"command": "echo {{ workspace_source_sha }}"}}
                ]}""");
        fx.packages.put(WRITER, """
                {"id": "wf-writer", "name": "Writer", "blocks": [
                  {"type": "shell", "name": "write", "config": {"command": "./build", "workspace_writable": true}}
                ]}""");
        return fx.services();
    }

    @Test
    void start_withASource_worksInItsCheckout_andRecordsSourceAndSha() {
        fx.sources.recorded = source -> SOURCE;

        RunResult result = services.start(AppFixture.sourced(BUILD, SOURCE + "?token=x", "main"), null, null);

        String id = result.runId();
        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.sources.calls().getFirst())
                .isEqualTo("checkout " + SOURCE + "?token=x main " + checkoutOf(id));
        JsonNode started = fx.events(id).getFirst().fields();
        assertThat(started.path("workspace").asText()).isEqualTo(checkoutOf(id).toString());
        assertThat(started.path("workspace_source").asText()).as("as the checkout names it").isEqualTo(SOURCE);
        assertThat(started.path("workspace_source_sha").asText()).isEqualTo(AppFixture.Sources.SHA);
        assertThat(fx.commands.requests()).extracting(r -> r.workingDirectory())
                .containsOnly(checkoutOf(id).toString());
    }

    @Test
    void start_withoutASource_recordsNoSourceFields() {
        String id = services.start(request(BUILD), null, null).runId();

        JsonNode started = fx.events(id).getFirst().fields();
        assertThat(started.has("workspace_source")).isFalse();
        assertThat(started.has("workspace_source_sha")).isFalse();
        assertThat(fx.sources.calls()).isEmpty();
    }

    @Test
    void runVariable_workspaceSourceSha_isSet() {
        RunServices containers = inContainers();

        RunResult sourced = containers.start(new StartRequest(SHA_STEP, Map.of("workspace_source_sha", "forged"),
                null, false, false, SOURCE, null), null, null);
        RunResult plain = containers.start(new StartRequest(SHA_STEP, Map.of(), WORKSPACE, false, false), null, null);

        assertThat(fx.containers.runs()).extracting(run -> run.args().getLast())
                .containsExactly("echo " + AppFixture.Sources.SHA, "echo ");
        assertThat(sourced.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(plain.status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void start_withASourceAndAWorkspace_orARefWithoutASource_isNoRequest() {
        assertThatThrownBy(() -> new StartRequest(BUILD, Map.of(), WORKSPACE, false, false, SOURCE, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("workspace");
        assertThatThrownBy(() -> new StartRequest(BUILD, Map.of(), WORKSPACE, false, false, null, "main"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ref");
    }

    @Test
    void start_whoseSourceCannotBeCheckedOut_isRefused_andLeavesNoRun() {
        fx.sources.failure = new IllegalStateException("git cannot clone " + SOURCE + " (exit code 128): fatal: no");

        assertThatThrownBy(() -> services.start(AppFixture.sourced(BUILD, SOURCE, null), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("git cannot clone " + SOURCE).hasMessageContaining("fatal: no");
        assertThat(fx.store.runIds()).isEmpty();
        fx.sources.failure = new IllegalArgumentException("the source scheme http:// is not allowed");
        assertThatThrownBy(() -> services.start(AppFixture.sourced(BUILD, "http://host/repo", null), null, null))
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("http:// is not allowed");
        assertThat(fx.store.runIds()).isEmpty();
    }

    /** The start check runs before the clone: a run it refuses clones nothing. */
    @Test
    void start_refusedByTheStartCheck_clonesNothing() {
        fx.settings = OperatorSettings.defaults();

        assertThatThrownBy(() -> fx.services().start(AppFixture.sourced(BUILD, SOURCE, null), null, null))
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("no container runtime");
        assertThat(fx.sources.calls()).isEmpty();
    }

    @Test
    void start_whoseCheckoutCannotBeMounted_isRefused_andRemovesIt() {
        String next = "00000000-0000-0000-0000-000000000001";
        fx.refusedDirectories.put(checkoutOf(next), "it holds a socket");

        assertThatThrownBy(() -> services.start(AppFixture.sourced(BUILD, SOURCE, null), null, null))
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("it holds a socket");
        assertThat(fx.sources.present).isEmpty();
        assertThat(fx.sources.calls()).last().isEqualTo("remove " + checkoutOf(next));
        assertThat(fx.store.runIds()).isEmpty();
    }

    /** The checkout is judged and resolved once: what is swapped in after the check is not the run's workspace. */
    @Test
    void start_whoseCheckoutIsSwappedAfterItsCheck_runsInThePathTheCheckJudged() {
        String next = "00000000-0000-0000-0000-000000000001";
        fx.swappedAfterFirstResolution.put(checkoutOf(next), "/swapped/checkout");

        RunResult result = services.start(AppFixture.sourced(BUILD, SOURCE, null), null, null);

        assertThat(fx.events(result.runId()).getFirst().fields().path("workspace").asText())
                .isEqualTo(checkoutOf(next).toString());
    }

    /** No command could write in the checkout: a finished run leaves nothing of it, whatever its status. */
    @Test
    void checkout_ofAFinishedRunWhoseCommandsSawItReadOnly_isRemoved() {
        RunServices containers = inContainers();

        RunResult completed = containers.start(AppFixture.sourced(BUILD, SOURCE, null), null, null);
        fx.containers.on(AppFixture.SHELL_ID, FakeCommandRunner.exit(1, "broken"));
        RunResult failed = containers.start(AppFixture.sourced(BUILD, SOURCE, null), null, null);

        assertThat(completed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(failed.status()).isEqualTo(RunStatus.FAILED);
        assertThat(fx.sources.present).isEmpty();
        assertThat(fx.sources.calls()).contains("remove " + checkoutOf(completed.runId()),
                "remove " + checkoutOf(failed.runId()));
        assertThat(completed.checkout()).isNull();
        assertThat(failed.checkout()).isNull();
    }

    /** A command that could write in the checkout keeps it, and the result names it. */
    @Test
    void checkout_ofAFinishedRunWithAWritableWorkspace_staysAndIsNamed() {
        RunServices containers = inContainers();

        RunResult result = containers.start(AppFixture.sourced(WRITER, SOURCE, null), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.sources.present).containsExactly(checkoutOf(result.runId()));
        assertThat(result.checkout()).isNotNull();
        assertThat(result.checkout().path()).isEqualTo(checkoutOf(result.runId()).toString());
        assertThat(result.checkout().reason()).contains("could write");
    }

    /** A host run's commands on the host can write anywhere, the checkout included. */
    @Test
    void checkout_ofAHostRun_stays() {
        RunResult result = services.start(AppFixture.sourced(BUILD, SOURCE, null), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.sources.present).containsExactly(checkoutOf(result.runId()));
        assertThat(result.checkout().reason()).contains("host");
    }

    @Test
    void checkout_thatCannotBeRemoved_staysAndIsNamed() {
        RunServices containers = inContainers();
        fx.sources.removeFailure = new java.io.UncheckedIOException(new java.io.IOException("busy"));

        RunResult result = containers.start(AppFixture.sourced(BUILD, SOURCE, null), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.checkout().path()).isEqualTo(checkoutOf(result.runId()).toString());
        assertThat(result.checkout().reason()).contains("busy");
    }

    /**
     * A run whose record cannot be written leaves its checkout: the failure that ends it names the checkout, so the
     * terminal output can say where it is. So does a resume that fails the same way.
     */
    @Test
    void checkout_ofARunThatEndsInAFailure_isNamedByTheFailure() {
        fx.store.failEventWritesAfter(3);

        Throwable failed = org.assertj.core.api.Assertions.catchThrowable(() ->
                services.start(AppFixture.sourced(BUILD, SOURCE, null), null, null));

        String id = fx.store.runIds().getFirst();
        assertThat(failed).isInstanceOf(EventLogException.class);
        assertThat(failed.getSuppressed()).singleElement().isInstanceOf(KeptCheckoutNotice.class)
                .satisfies(notice -> assertThat(((KeptCheckoutNotice) notice).path())
                        .isEqualTo(checkoutOf(id).toString()));
        assertThat(fx.sources.present).containsExactly(checkoutOf(id));

        fx.store.failEventWritesAfter(-1);
        String parked = services.start(AppFixture.sourced(DEPLOY, SOURCE, null), null, null).runId();
        services.answer(parked, new AnswerRequest("approval", 1, "Yes", null, null));
        fx.run(parked).log().failAfter(fx.events(parked).size() + 2);
        Throwable resumed = org.assertj.core.api.Assertions.catchThrowable(() ->
                services.resume(resumeOf(parked), null, null));
        assertThat(resumed).isInstanceOf(EventLogException.class);
        assertThat(resumed.getSuppressed()).singleElement().isInstanceOf(KeptCheckoutNotice.class);
    }

    /** A resume refused before it runs leaves the run as it was, parked with its checkout: nothing to name. */
    @Test
    void resume_refusedBeforeItRuns_namesNoCheckout() {
        String parked = services.start(AppFixture.sourced(DEPLOY, SOURCE, null), null, null).runId();

        Throwable refused = org.assertj.core.api.Assertions.catchThrowable(() ->
                services.resume(resumeOf(parked), null, null));

        assertThat(refused).isInstanceOf(RunRefusedException.class).hasMessageContaining("waits for an answer");
        assertThat(refused.getSuppressed()).isEmpty();
    }

    /** A parked run keeps its checkout for its resume; a cancel finishes it, and removes it. */
    @Test
    void checkout_ofAParkedRun_staysForItsResume_andGoesWithItsCancel() {
        RunServices containers = inContainers();

        RunResult parked = containers.start(AppFixture.sourced(DEPLOY, SOURCE, null), null, null);

        assertThat(parked.status()).isEqualTo(RunStatus.PARKED);
        assertThat(fx.sources.present).containsExactly(checkoutOf(parked.runId()));
        assertThat(parked.checkout()).isNull();

        RunResult cancelled = containers.cancel(parked.runId());

        assertThat(cancelled.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(fx.sources.present).isEmpty();
        assertThat(cancelled.checkout()).isNull();
    }

    /**
     * A resume works in the checkout as it is, with no git call in it; a checkout that is missing is cloned again from
     * the recorded source at the recorded commit, and the record says so.
     */
    @Test
    void resume_reusesTheCheckout_orChecksItOutAgainAtTheRecordedSha() {
        fx.sources.recorded = source -> SOURCE;
        String kept = services.start(AppFixture.sourced(DEPLOY, SOURCE + "?token=x", "v1"), null, null).runId();
        String gone = services.start(AppFixture.sourced(DEPLOY, SOURCE + "?token=x", "v1"), null, null).runId();
        fx.sources.present.remove(checkoutOf(gone));
        for (String id : List.of(kept, gone)) {
            services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        }
        int callsBefore = fx.sources.calls().size();

        RunResult reused = services.resume(resumeOf(kept), null, null);
        RunResult cloned = services.resume(resumeOf(gone), null, null);

        assertThat(reused.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(cloned.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.sources.calls().subList(callsBefore, fx.sources.calls().size()))
                .as("one clone, of the missing checkout, from the recorded source at the recorded commit")
                .filteredOn(call -> call.startsWith("checkout"))
                .containsExactly("checkout " + SOURCE + " " + AppFixture.Sources.SHA + " " + checkoutOf(gone));
        assertThat(fx.ofType(kept, "run.resumed").getFirst().fields().path("workspace_cloned").asBoolean(true))
                .isFalse();
        assertThat(fx.ofType(gone, "run.resumed").getFirst().fields().path("workspace_cloned").asBoolean(false))
                .isTrue();
        assertThat(fx.commands.requests()).extracting(r -> r.workingDirectory())
                .contains(checkoutOf(kept).toString(), checkoutOf(gone).toString());
    }

    /** A missing checkout that cannot be cloned again is a refusal that names why; nothing is recorded. */
    @Test
    void resume_whoseCheckoutCannotBeClonedAgain_isRefused() {
        String id = services.start(AppFixture.sourced(DEPLOY, SOURCE, null), null, null).runId();
        services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        fx.sources.present.remove(checkoutOf(id));
        fx.sources.failure = new IllegalStateException("git cannot clone " + SOURCE + " (exit code 128): fatal:"
                + " could not read Username");
        int events = fx.events(id).size();

        assertThatThrownBy(() -> services.resume(resumeOf(id), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("Run " + id + " cannot resume")
                .hasMessageContaining(checkoutOf(id).toString())
                .hasMessageContaining("could not read Username");
        assertThat(fx.events(id)).hasSize(events);

        fx.sources.failure = null;
        fx.sources.sha = "0".repeat(40);
        assertThatThrownBy(() -> services.resume(resumeOf(id), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining(AppFixture.Sources.SHA).hasMessageContaining("0".repeat(40));
        assertThat(fx.sources.present).as("a clone at another commit is not kept").isEmpty();
        assertThat(fx.events(id)).hasSize(events);
    }

    // ---- verify, render, list, check ----

    @Test
    void verify_intactRun_isOk() {
        String id = services.start(request(BUILD), null, null).runId();

        VerifyReport report = services.verify(id);

        assertThat(report.chain().intact()).isTrue();
        assertThat(report.manifest()).isEqualTo(VerifyReport.OK);
        assertThat(report.ledger()).isEqualTo(VerifyReport.OK);
        assertThat(report.ok()).isTrue();
    }

    @Test
    void verify_tamperedRun_isNotOk() {
        String id = services.start(request(BUILD), null, null).runId();
        RecordedEvent output = fx.ofType(id, "step.output").getFirst();
        fx.run(id).blobs().putRaw(output.blob("output").sha256(), "forged".getBytes(StandardCharsets.UTF_8));

        VerifyReport report = services.verify(id);

        assertThat(report.chain().intact()).isFalse();
        assertThat(report.ok()).isFalse();
    }

    /** A cut-off tail leaves an intact chain; only the manifest's head and event count show it. */
    @Test
    void verify_truncatedLog_isAManifestMismatch() {
        String id = services.start(request(BUILD), null, null).runId();
        fx.run(id).log().dropLast(1);

        VerifyReport report = services.verify(id);

        assertThat(report.chain().intact()).isTrue();
        assertThat(report.manifest()).isEqualTo(VerifyReport.MISMATCH);
        assertThat(report.ok()).isFalse();
    }

    /** The head and the event count still agree; only a manifest regenerated from the record shows the change. */
    @Test
    void verify_aManifestWithARewrittenStatusOrGate_isAMismatch() {
        String id = services.start(request(GATE), null, null).runId();
        services.cancel(id);
        String manifest = new String(fx.run(id).readManifest().orElseThrow(), StandardCharsets.UTF_8);
        assertThat(manifest).contains("\"status\" : \"CANCELLED\"", "\"status\" : \"YELLOW\"");

        for (String forged : List.of(manifest.replace("\"status\" : \"CANCELLED\"", "\"status\" : \"COMPLETED\""),
                manifest.replace("\"status\" : \"YELLOW\"", "\"status\" : \"GREEN\""))) {
            fx.run(id).writeManifest(forged.getBytes(StandardCharsets.UTF_8));

            VerifyReport report = services.verify(id);

            assertThat(report.chain().intact()).isTrue();
            assertThat(report.manifest()).isEqualTo(VerifyReport.MISMATCH);
            assertThat(report.ok()).isFalse();
        }
        fx.run(id).writeManifest(manifest.getBytes(StandardCharsets.UTF_8));
        assertThat(services.verify(id).ok()).isTrue();
    }

    @Test
    void verify_withALedgerCopyThatDiffers_isALedgerMismatch() {
        String id = services.start(request(BUILD), null, null).runId();
        InMemoryLedger otherLedger = new InMemoryLedger();
        otherLedger.record(id, "COMPLETED", "{\"forged\": true}\n".getBytes(StandardCharsets.UTF_8));

        VerifyReport report = fx.services(otherLedger).verify(id);

        assertThat(report.manifest()).isEqualTo(VerifyReport.OK);
        assertThat(report.ledger()).isEqualTo(VerifyReport.MISMATCH);
        assertThat(report.ok()).isFalse();
    }

    @Test
    void verify_withoutLedgerEntry_reportsMissing() {
        String id = services.start(request(BUILD), null, null).runId();

        VerifyReport report = fx.services(new InMemoryLedger()).verify(id);

        assertThat(report.ledger()).isEqualTo(VerifyReport.MISSING);
        assertThat(report.ok()).isFalse();
    }

    @Test
    void verify_parkedRun_hasNoManifestToCheck() {
        String id = services.start(request(DEPLOY), null, null).runId();

        VerifyReport report = services.verify(id);

        assertThat(report.manifest()).isEqualTo(VerifyReport.NOT_CHECKED);
        assertThat(report.ledger()).isEqualTo(VerifyReport.NOT_CHECKED);
        assertThat(report.ok()).isTrue();
    }

    @Test
    void render_writesTheViewAgain() {
        String id = services.start(request(BUILD), null, null).runId();
        fx.run(id).writeView("old");

        services.render(id);

        assertThat(fx.run(id).view()).hasValueSatisfying(html -> assertThat(html).contains(id).isNotEqualTo("old"));
    }

    @Test
    void content_readsABlobOfTheRun_andIsEmptyForADeletedOne() {
        String id = services.start(request(BUILD), null, null).runId();
        RecordedEvent output = services.events(id).stream().filter(e -> e.type().equals("step.output"))
                .findFirst().orElseThrow();

        assertThat(services.content(id, output.blob("output").sha256())).hasValueSatisfying(
                bytes -> assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("built"));
        assertThat(services.content(id, "0".repeat(64))).isEmpty();
    }

    @Test
    void render_returnsTheRunFolder() {
        String id = services.start(request(BUILD), null, null).runId();

        assertThat(services.render(id)).isEqualTo(fx.run(id).location());
    }

    @Test
    void events_and_runs_readTheRecords_newestFirst() {
        String first = services.start(request(BUILD), null, null).runId();
        String second = services.start(request(DEPLOY), null, null).runId();
        String third = services.start(request(BUILD), null, null).runId();

        assertThat(services.runs(2)).extracting(RunView::runId).containsExactly(third, second);
        assertThat(services.runs(10)).extracting(RunView::status)
                .containsExactly(RunStatus.COMPLETED, RunStatus.PARKED, RunStatus.COMPLETED);
        assertThat(services.events(first)).extracting(RecordedEvent::type)
                .containsExactlyElementsOf(fx.types(first));
    }

    @Test
    void check_returnsTheAnalyzerFindingsOfThePackage() {
        fx.packages.put(Path.of("/pkg/odd"), """
                {"id": "wf-odd", "name": "Odd", "blocks": [
                  {"type": "shell", "name": "build", "config": {"command": "./build", "colour": "red"}}
                ]}""");

        List<AnalyzerFinding> findings = services.check(Path.of("/pkg/odd"));

        assertThat(findings).extracting(AnalyzerFinding::rule).contains("unknown-config-key");
        assertThatThrownBy(() -> services.check(Path.of("/pkg/missing"))).isInstanceOf(RunRefusedException.class);
    }

    // ---- the agent of an answer ----

    /** A parked run: the answer names its agent beside the actor, and the resumed step takes it over unchanged. */
    @Test
    void answer_byAnAgent_recordsItBesideTheActor_andTheResumedStepKeepsIt() {
        String id = services.start(request(DEPLOY), null, null).runId();

        AnswerResult answered = services.answer(id,
                new AnswerRequest("approval", 1, "yes", null, null, " claude-code "));

        assertThat(answered.accepted()).isTrue();
        assertThat(answered.message()).contains("is recorded through the agent claude-code;");
        RecordedEvent cli = fx.events(id).getLast();
        assertThat(cli.fields().path("actor").asText()).isEqualTo(ACTOR);
        assertThat(cli.fields().path("agent").asText()).isEqualTo("claude-code");
        assertThat(services.view(id).parkedAnswer().agent()).isEqualTo("claude-code");

        // The asker of the resume is another agent: it gave no answer, so its name is nowhere
        RunResult resumed = services.resume(resumeOf(id), null, new QuestionAsker() {
            @Override
            public Optional<String> ask(QuestionView question) {
                return Optional.empty();
            }

            @Override
            public String agent() {
                return "another-harness";
            }
        });

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        List<RecordedEvent> answers = fx.ofType(id, "approval.answered");
        assertThat(answers).extracting(e -> e.fields().path("source").asText()).containsExactly("cli", "resume");
        assertThat(answers).extracting(e -> e.fields().path("agent").asText())
                .containsExactly("claude-code", "claude-code");
        assertThat(fx.ofType(id, "run.resumed").getFirst().fields().has("agent"))
                .as("a resume gets no agent: the actor is enough").isFalse();
        assertThat(fx.ofType(id, "run.started").getFirst().fields().path("records")).extracting(n -> n.asText())
                .contains("answer_agent");
        assertThat(fx.ofType(id, "run.resumed").getFirst().fields().path("records")).extracting(n -> n.asText())
                .contains("answer_agent");
    }

    /** Without an agent the answer has no such field, on the parked run and on the step that takes it over. */
    @Test
    void answer_withoutAnAgent_recordsNoAgent() {
        String id = services.start(request(DEPLOY), null, null).runId();

        services.answer(id, new AnswerRequest("approval", 1, "yes", null, null));
        services.resume(resumeOf(id), null, null);

        assertThat(fx.ofType(id, "approval.answered")).hasSize(2)
                .noneMatch(e -> e.fields().has("agent"));
    }

    /** A name that is none is refused before anything is recorded, and the question still waits for its answer. */
    @Test
    void answer_withABadAgentName_isRefused_recordsNothing_andTheQuestionStillWaits() {
        String id = services.start(request(DEPLOY), null, null).runId();
        int before = fx.events(id).size();

        for (String bad : List.of("", "  ", "two  spaces", "line\nbreak", "x".repeat(65))) {
            assertThatThrownBy(() -> services.answer(id, new AnswerRequest("approval", 1, "yes", null, null, bad)))
                    .as(bad).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("agent");
        }

        assertThat(fx.events(id)).hasSize(before);
        assertThat(services.view(id).status()).isEqualTo(RunStatus.PARKED);
        assertThat(services.view(id).parkedAnswer()).isNull();
        assertThat(services.answer(id, new AnswerRequest("approval", 1, "yes", null, null)).accepted()).isTrue();
    }

    /** A running run: the agent travels through the inbox, and the run's process records it. */
    @Test
    void inboxAnswer_byAnAgent_recordsItBesideTheActor() throws Exception {
        Events events = new Events();
        StartRequest interactive = new StartRequest(DEPLOY, Map.of("version", "1.2"), WORKSPACE, false, true);
        Background<RunResult> running = inBackground(
                () -> services.start(interactive, events, question -> Optional.empty()));
        String id = events.await("approval.requested").run();

        AnswerResult result = services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null, "claude-code"));

        assertThat(result.accepted()).isTrue();
        assertThat(running.join().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.run(id).inbox().posted()).singleElement()
                .satisfies(posted -> assertThat(posted.agent()).isEqualTo("claude-code"));
        assertThat(fx.ofType(id, "approval.answered")).singleElement().satisfies(e -> {
            assertThat(e.fields().path("source").asText()).isEqualTo("inbox");
            assertThat(e.fields().path("actor").asText()).isEqualTo(ACTOR);
            assertThat(e.fields().path("agent").asText()).isEqualTo("claude-code");
        });
    }

    /**
     * The inbox is a folder: a request whose agent is no name is refused by the run's process, nothing is recorded
     * as an answer, and the question stays open for the next one.
     */
    @Test
    void inboxAnswer_withABadAgentName_isRefused_andTheQuestionStaysOpen() throws Exception {
        Events events = new Events();
        StartRequest interactive = new StartRequest(DEPLOY, Map.of("version", "1.2"), WORKSPACE, false, true);
        Background<RunResult> running = inBackground(
                () -> services.start(interactive, events, question -> Optional.empty()));
        RecordedEvent asked = events.await("approval.requested");
        String id = asked.run();

        fx.run(id).inbox().post(new InboxRequest("bad-agent", "approval", id, asked.step(), asked.seq(), "Yes",
                Map.of(), null, "b@box", fx.clock.now(), "two  spaces\u001b[31m"));
        RecordedEvent refused = events.await(e -> refusalOf(e, "bad-agent"));

        assertThat(refused.fields().path("reason").asText()).contains("agent").doesNotContain("\u001b");
        assertThat(fx.ofType(id, "approval.answered")).isEmpty();
        assertThat(services.view(id).steps().get(1).status()).isEqualTo(StepStatus.WAITING_APPROVAL);
        assertThat(services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null)).accepted()).isTrue();
        assertThat(running.join().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.ofType(id, "approval.answered")).singleElement()
                .satisfies(e -> assertThat(e.fields().has("agent")).isFalse());
    }

    /** The prompt of a run in the foreground: its answers are the agent's that the asker names. */
    @Test
    void interactive_withAnAskerOfAnAgent_recordsTheAgentWithSourceTerminal() {
        StartRequest interactive = new StartRequest(DEPLOY, Map.of("version", "1.2"), WORKSPACE, false, true);

        RunResult result = services.start(interactive, null, new QuestionAsker() {
            @Override
            public Optional<String> ask(QuestionView question) {
                return Optional.of("yes");
            }

            @Override
            public String agent() {
                return "claude-code";
            }
        });

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.ofType(result.runId(), "approval.answered")).singleElement().satisfies(e -> {
            assertThat(e.fields().path("source").asText()).isEqualTo("terminal");
            assertThat(e.fields().path("actor").asText()).isEqualTo(ACTOR);
            assertThat(e.fields().path("agent").asText()).isEqualTo("claude-code");
        });
    }

    @Test
    void version_isTheFilteredProjectVersion() {
        assertThat(TraceworksVersion.current()).matches("\\d+\\.\\d+\\.\\d+.*").doesNotContain("${");
    }
}
