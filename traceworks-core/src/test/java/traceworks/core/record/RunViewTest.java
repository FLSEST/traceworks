package traceworks.core.record;

import traceworks.core.port.WebAccess;
import traceworks.core.ai.cli.CliAiProvider;
import traceworks.core.ai.ToolCall;
import traceworks.core.ai.ToolPolicy;
import traceworks.core.model.GateStatus;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepStatus;
import traceworks.core.port.AiProvider;
import traceworks.core.port.AiProviders;
import traceworks.core.port.AiNetwork;
import traceworks.core.port.CommandRunner;
import traceworks.core.port.CommandRunner.CommandRequest;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.CommandRunner.Connection;
import traceworks.core.port.Egress;
import traceworks.core.port.RunEvent.*;
import traceworks.core.testing.FakeAiCli;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunViewTest {

    private final RecordFixture fx = new RecordFixture().started();

    private String text(traceworks.core.port.BlobRef ref) {
        return new String(fx.blobs.get(ref.sha256()).orElseThrow(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private RunView view(boolean alive) {
        return RunView.of(fx.events(), alive);
    }

    /**
     * Claude reports the input the prompt cache did not serve (2 of many thousand tokens) and the cached part in
     * fields of their own. The record keeps the three figures apart, and the view counts what was sent: their sum.
     */
    @Test
    void aiCall_theInputTokensAreWhatWasSent_theCachedPartIncluded() throws Exception {
        fx.emit(new StepStarted(0, "build", "simple", 0, true));
        FakeAiCli cli = FakeAiCli.claude();
        cli.then(new traceworks.core.port.AiCli.AiOutcome("fine", 0, false,
                new traceworks.core.ai.AiResponseMetadata(2, 600, 0.02, "claude-fake", false, 40L, 1,
                        null, List.of(), 6000, 900), List.of()));
        AiProviders providers = fx.recorder.recording(AiProviders.of("CLAUDE", List.of(new CliAiProvider(cli,
                fx.recorder.recording(request -> new CommandResult(0, "", "", 1, false)), "traceworks/claude:1",
                Map.of("ANTHROPIC_API_KEY", "sk-ant-test-value-01")))));
        providers.resolve("CLAUDE").executeAction(new AiProvider.ActionRequest("Review", "opus", "sys", "/w", 30,
                null, ToolPolicy.READ, false, "high"));

        com.fasterxml.jackson.databind.JsonNode recorded = fx.events().stream()
                .filter(e -> "ai.response".equals(e.type())).findFirst().orElseThrow().fields();
        assertThat(recorded.path("input_tokens").asInt()).as("the record keeps the CLI's figure").isEqualTo(2);
        assertThat(recorded.path("cache_read_input_tokens").asInt()).isEqualTo(6000);
        assertThat(recorded.path("cache_creation_input_tokens").asInt()).isEqualTo(900);
        AiCallView.Response response = view(true).steps().getFirst().aiCalls().getFirst().response();
        assertThat(response.inputTokens()).as("what was sent: 2 + 6000 + 900").isEqualTo(6902);
        assertThat(response.outputTokens()).isEqualTo(600);
    }

    /** A CLI that reports no cached input: the cache fields are null, and the input is the figure it reported. */
    @Test
    void aiCall_withoutCacheFigures_recordsNullForThem() throws Exception {
        fx.emit(new StepStarted(0, "build", "simple", 0, true));
        FakeAiCli cli = FakeAiCli.claude();
        cli.answers("fine");
        AiProviders providers = fx.recorder.recording(AiProviders.of("CLAUDE", List.of(new CliAiProvider(cli,
                fx.recorder.recording(request -> new CommandResult(0, "", "", 1, false)), "traceworks/claude:1",
                Map.of("ANTHROPIC_API_KEY", "sk-ant-test-value-01")))));
        providers.resolve("CLAUDE").executeAction(new AiProvider.ActionRequest("Review", "opus", "sys", "/w", 30,
                null, ToolPolicy.READ, false, "high"));

        com.fasterxml.jackson.databind.JsonNode recorded = fx.events().stream()
                .filter(e -> "ai.response".equals(e.type())).findFirst().orElseThrow().fields();
        assertThat(recorded.has("cache_read_input_tokens")).isTrue();
        assertThat(recorded.path("cache_read_input_tokens").isNull()).isTrue();
        assertThat(recorded.path("cache_creation_input_tokens").isNull()).isTrue();
        assertThat(view(true).steps().getFirst().aiCalls().getFirst().response().inputTokens()).isEqualTo(100);
    }

    /** An AI call shows on its step: the request, the tool calls in order and the response. */
    @Test
    void aiCall_showsOnItsStep() throws Exception {
        fx.emit(new StepStarted(0, "build", "simple", 0, true));
        FakeAiCli cli = FakeAiCli.claude();
        cli.answersAfter("fine", new ToolCall("Read", "{\"f\":1}", "text", false),
                new ToolCall("Grep", "{}", "boom", true));
        AiProviders providers = fx.recorder.recording(AiProviders.of("CLAUDE", List.of(new CliAiProvider(cli,
                fx.recorder.recording(request -> new CommandResult(0, "", "", 1, false)), "traceworks/claude:1",
                Map.of("ANTHROPIC_API_KEY", "sk-ant-test-value-01")))));
        providers.resolve("CLAUDE").executeAction(new AiProvider.ActionRequest("Review", "opus", "sys", "/w", 30,
                null, ToolPolicy.READ, false, "high"));
        fx.emit(new StepFinished(0, "build", "simple", StepStatus.COMPLETED, "Review", 42, Map.of()));

        StepView step = view(false).steps().getFirst();

        assertThat(step.commands()).hasSize(1);
        AiCallView call = step.aiCalls().getFirst();
        assertThat(call.provider()).isEqualTo("CLAUDE");
        assertThat(call.model()).isEqualTo("opus");
        assertThat(call.effort()).isEqualTo("high");
        assertThat(call.tools()).isEqualTo("read");
        assertThat(call.item()).isNull();
        assertThat(text(call.prompt())).isEqualTo("Review");
        assertThat(text(call.systemPrompt())).isEqualTo("sys");
        assertThat(call.toolCalls()).extracting(AiCallView.ToolCallView::tool).containsExactly("Read", "Grep");
        assertThat(call.toolCalls()).extracting(AiCallView.ToolCallView::error).containsExactly(false, true);
        assertThat(text(call.toolCalls().getFirst().arguments())).isEqualTo("{\"f\":1}");
        assertThat(text(call.toolCalls().get(1).result())).isEqualTo("boom");
        AiCallView.Response response = call.response();
        assertThat(response.model()).isEqualTo("fake-model");
        assertThat(response.inputTokens()).isEqualTo(100);
        assertThat(response.outputTokens()).isEqualTo(20);
        assertThat(response.costUsd()).isEqualTo(0.01);
        assertThat(response.numTurns()).isEqualTo(1);
        assertThat(response.exitCode()).isZero();
        assertThat(text(response.output())).isEqualTo("fine");
        assertThat(response.error()).isNull();
    }

    /** A call that throws shows the error as its response, without an exit code. */
    @Test
    void aiCall_thatThrows_showsItsError() {
        fx.emit(new StepStarted(0, "build", "simple", 0, true));
        FakeAiCli cli = FakeAiCli.claude();
        AiProviders providers = fx.recorder.recording(AiProviders.of("CLAUDE", List.of(new CliAiProvider(cli,
                request -> {
                    throw new IllegalStateException("killed");
                }, "traceworks/claude:1", Map.of("ANTHROPIC_API_KEY", "sk-ant-test-value-01")))));

        assertThatThrownBy(() -> providers.resolve("CLAUDE").executeAction(
                new AiProvider.ActionRequest("Review", null, null, null))).hasMessage("killed");

        AiCallView call = view(true).steps().getFirst().aiCalls().getFirst();
        assertThat(call.response()).isNotNull();
        assertThat(text(call.response().error())).isEqualTo("killed");
        assertThat(call.response().exitCode()).isNull();
    }

    @Test
    void finishedRun() {
        fx.emit(new StepStarted(0, "build", "shell", 0, false));
        fx.emit(new OutputRecorded(0, "build", "built"),
                new GateReported(0, GateStatus.YELLOW, "quality", "two warnings"),
                new StepFinished(0, "build", "shell", StepStatus.COMPLETED, "echo T-1", 42, Map.of("model", "m1")),
                new RunFinished(RunStatus.COMPLETED, Map.of("gate_status", "YELLOW", "gate_category", "quality",
                        "gate_description", "two warnings", "gate_summary", "sum"), null));

        RunView view = view(false);

        assertThat(view.runId()).isEqualTo(RecordFixture.RUN);
        assertThat(view.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(view.workflowId()).isEqualTo("wf");
        assertThat(view.workflowName()).isEqualTo("Wf");
        assertThat(view.packageDir()).isEqualTo("/pkg");
        assertThat(view.packageDigest()).isEqualTo("digest-1");
        assertThat(view.packageCommit()).as("no repository recorded").isNull();
        assertThat(view.packageDirty()).isNull();
        assertThat(view.actor()).isEqualTo("operator@box");
        assertThat(view.trigger()).isEqualTo("MANUAL");
        assertThat(view.startedAt()).isBefore(view.finishedAt());
        assertThat(view.gate().status()).isEqualTo("YELLOW");
        assertThat(view.gate().category()).isEqualTo("quality");
        assertThat(text(view.gate().description())).isEqualTo("two warnings");
        assertThat(text(view.gate().summary())).isEqualTo("sum");
        assertThat(text(view.inputs())).contains("T-1");
        assertThat(view.definition()).isNotNull();
        assertThat(view.eventCount()).isEqualTo(fx.lines().size());
        assertThat(view.head()).isEqualTo(fx.recorder.head());
        StepView step = view.steps().getFirst();
        assertThat(step.name()).isEqualTo("build");
        assertThat(step.status()).isEqualTo(StepStatus.COMPLETED);
        assertThat(step.durationMs()).isEqualTo(42);
        assertThat(step.attempts()).isEqualTo(1);
        assertThat(step.uses()).containsExactly("ticket");
        assertThat(step.ai()).isFalse();
        assertThat(text(step.output())).isEqualTo("built");
        assertThat(text(step.input())).isEqualTo("echo T-1");
        assertThat(text(step.metadata())).contains("m1");
        assertThat(step.gate().status()).isEqualTo("YELLOW");
        assertThat(step.interrupted()).isFalse();
    }

    /** The record says what each image the run may use holds, so a reader need not trust its name. */
    @Test
    void theImagesOfARun_areRecordedWithBaseAndDescription() {
        RecordFixture fx2 = new RecordFixture();
        java.util.List<traceworks.core.container.ImageFacts> images = java.util.List.of(
                new traceworks.core.container.ImageFacts("shell", "debian:bookworm-slim",
                        "bash, coreutils, git, python3, jq"),
                new traceworks.core.container.ImageFacts("./images/architecture",
                        "eclipse-temurin:21-jdk-noble", "the architecture tool on JDK 21"),
                new traceworks.core.container.ImageFacts("alpine:3.20", null, null));
        fx2.recorder.runStarted(new RunStart("wf", "Wf", "/pkg", "digest-1", null, null,
                "{\"id\":\"wf\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8), Map.of(),
                traceworks.core.model.TriggerSource.MANUAL, "operator@box", "0.1.0", "container",
                new RunStart.Runtime("docker", "29.1.3"), 16, Map.of(), "/ws", null, null, 128, null, images));

        RunView view = RunView.of(fx2.log.readAll().stream().map(RecordedEvent::parse).toList(), false);

        assertThat(view.images()).containsExactlyElementsOf(images);
        assertThat(new String(fx2.log.readAll().getFirst(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"images\":[{\"reference\":\"shell\",\"base\":\"debian:bookworm-slim\",\"description\":"
                        + "\"bash, coreutils, git, python3, jq\"}")
                .contains("{\"reference\":\"alpine:3.20\",\"base\":null,\"description\":null}");
    }

    /** A record of an earlier version has no images field: the view has none. */
    @Test
    void aRecordWithoutImages_hasAnEmptyList() {
        assertThat(view(false).images()).isEmpty();
    }

    @Test
    void aPackageInARepository_hasItsCommitAndWhetherItWasDirty() {
        RecordFixture inGit = new RecordFixture();
        inGit.recorder.runStarted(new traceworks.core.record.RunStart("wf", "Wf", "/pkg", "digest-1",
                "abc123", true, "{\"id\":\"wf\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8), Map.of(),
                traceworks.core.model.TriggerSource.MANUAL, "operator@box", "0.1.0", "host", null, "/ws"));

        RunView view = RunView.of(inGit.log.readAll().stream().map(RecordedEvent::parse).toList(), false);

        assertThat(view.packageCommit()).isEqualTo("abc123");
        assertThat(view.packageDirty()).isTrue();
    }

    @Test
    void aPackageInACleanRepository_isNotDirty() {
        RecordFixture inGit = new RecordFixture();
        inGit.recorder.runStarted(new traceworks.core.record.RunStart("wf", "Wf", "/pkg", "digest-1",
                "abc123", false, "{\"id\":\"wf\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8), Map.of(),
                traceworks.core.model.TriggerSource.MANUAL, "operator@box", "0.1.0", "host", null, "/ws"));

        RunView view = RunView.of(inGit.log.readAll().stream().map(RecordedEvent::parse).toList(), false);

        assertThat(view.packageDirty()).isFalse();
    }

    @Test
    void finishedRunWithoutGate_hasNoGate() {
        fx.emit(new RunFinished(RunStatus.FAILED, Map.of(), "boom"));

        RunView view = view(false);

        assertThat(view.gate()).isNull();
        assertThat(text(view.error())).isEqualTo("boom");
    }

    @Test
    void parkedRun_hasThePendingQuestionWithItsOptions() {
        fx.emit(new StepStarted(0, "ask", "approval", 0, false),
                new ApprovalRequested(0, "b1", "approval", "Ship it?", List.of("yes", "no"), List.of("no"), null, null,
                        true, null, null, null),
                new StepWaiting(0, true), new RunParked(0, "approval"));

        RunView view = view(false);

        assertThat(view.status()).isEqualTo(RunStatus.PARKED);
        QuestionView question = view.pendingQuestion();
        assertThat(text(question.question())).isEqualTo("Ship it?");
        assertThat(question.options()).containsExactly("yes", "no");
        assertThat(question.rejectOptions()).containsExactly("no");
        assertThat(question.kind()).isEqualTo("approval");
        assertThat(question.step()).isZero();
        assertThat(view.parkedAnswer()).isNull();
        assertThat(view.steps().getFirst().status()).isEqualTo(StepStatus.WAITING_APPROVAL);
        assertThat(view.steps().getFirst().interrupted()).isFalse();
    }

    @Test
    void parkedRunWithAnAnswer_hasTheParkedAnswer() {
        fx.emit(new StepStarted(0, "ask", "approval", 0, false),
                new ApprovalRequested(0, "b1", "approval", "Ship it?", List.of("yes", "no"), List.of(), null, null,
                        true, null, null, null),
                new RunParked(0, "approval"));
        fx.nextWriter();
        fx.recorder.attributeNextAnswer("operator@box", "cli", null, "go");
        fx.emit(new ApprovalAnswered(0, "approval", "yes"));

        RunView view = view(false);

        assertThat(view.status()).isEqualTo(RunStatus.PARKED);
        assertThat(text(view.parkedAnswer().answer())).isEqualTo("yes");
        assertThat(view.parkedAnswer().actor()).isEqualTo("operator@box");
        assertThat(view.parkedAnswer().source()).isEqualTo("cli");
        assertThat(text(view.parkedAnswer().comment())).isEqualTo("go");
    }

    /** A run parked by a pause waits for no answer; between two blocks its run.parked names no step. */
    @Test
    void aRunParkedByAPause_isParkedWithoutAQuestion() {
        fx.emit(new StepStarted(0, "build", "shell", 0, false),
                new StepFinished(0, "build", "shell", StepStatus.COMPLETED, "./build", 5, Map.of()));
        fx.recorder.record("run.pause_requested", Map.of("actor", "operator@box", "request", "req-7"));
        fx.emit(new RunParked(RunParked.NO_STEP, RunParked.PAUSE));

        RunView view = view(false);

        assertThat(view.status()).isEqualTo(RunStatus.PARKED);
        assertThat(view.parkedBy()).isEqualTo("pause");
        assertThat(view.paused()).isTrue();
        assertThat(view.pendingQuestion()).isNull();
        assertThat(view.interventions()).extracting(RunView.Intervention::description)
                .containsExactly("requested a pause (request req-7)");
    }

    /** Paused inside a loop: the loop's step is the one that is not finished. */
    @Test
    void aRunParkedByAPauseInsideALoop_showsTheLoopUnfinished() {
        fx.emit(new StepStarted(0, "each", "foreach", 0, false),
                new ForEachItemStarted(0, 0, "a", 2),
                new ForEachItemFinished(0, 0, "a", 2, StepStatus.COMPLETED, "out-a", null),
                new RunParked(0, RunParked.PAUSE));

        RunView view = view(false);

        assertThat(view.paused()).isTrue();
        assertThat(view.steps().getFirst().status()).isEqualTo(StepStatus.RUNNING);
        assertThat(view.steps().getFirst().items()).hasSize(1);
        assertThat(view.pendingQuestion()).isNull();
    }

    /** Only a pause parks at no step: a question without one is a damaged record. */
    @Test
    void aRunParkedAtAQuestionWithoutAStep_isRefused() {
        fx.emit(new StepStarted(0, "ask", "approval", 0, false), new RunParked(RunParked.NO_STEP, "approval"));

        assertThatThrownBy(() -> view(false)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("run.parked").hasMessageContaining("lacks the int field step");
    }

    @Test
    void resumedAfterParking_isNoLongerParked() {
        fx.emit(new StepStarted(0, "ask", "approval", 0, false), new RunParked(0, "approval"));
        fx.nextWriter();
        fx.recorder.record("run.resumed", Map.of("actor", "operator@box"));

        assertThat(view(true).status()).isEqualTo(RunStatus.RUNNING);
        assertThat(view(true).parkedAnswer()).isNull();
        assertThat(view(false).status()).isEqualTo(RunStatus.INTERRUPTED);
    }

    /** A run that parks again after a resume is parked: the latest park counts. */
    @Test
    void aRunParkedAgainAfterAResume_isParked() {
        fx.emit(new StepStarted(0, "ask", "approval", 0, false), new RunParked(0, "approval"));
        fx.nextWriter();
        fx.recorder.record("run.resumed", Map.of("actor", "operator@box"));
        fx.emit(new RunParked(RunParked.NO_STEP, RunParked.PAUSE));

        assertThat(view(false).status()).isEqualTo(RunStatus.PARKED);
        assertThat(view(false).parkedBy()).isEqualTo("pause");
    }

    /** An answer that came through the inbox while the run waited is an intervention, as one from the CLI is. */
    @Test
    void anAnswerThroughTheInbox_isAnIntervention() {
        fx.emit(new StepStarted(0, "ask", "approval", 0, false),
                new ApprovalRequested(0, "b1", "approval", "Ship it?", List.of("yes", "no"), List.of(), null, null,
                        true, null, null, null));
        fx.recorder.attributeNextAnswer("operator@box", "inbox", null, null);
        fx.emit(new ApprovalAnswered(0, "approval", "yes"));

        assertThat(view(true).interventions()).extracting(RunView.Intervention::description)
                .containsExactly("answered step 0 (approval) via inbox");
    }

    @Test
    void runningRun() {
        fx.emit(new StepStarted(0, "build", "shell", 0, false));

        RunView view = view(true);

        assertThat(view.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(view.finishedAt()).isNull();
        assertThat(view.steps().getFirst().status()).isEqualTo(StepStatus.RUNNING);
        assertThat(view.steps().getFirst().interrupted()).isFalse();
        assertThat(view.pendingQuestion()).isNull();
    }

    @Test
    void interruptedRun_marksTheOpenStep() {
        fx.emit(new StepStarted(0, "one", "shell", 0, false),
                new StepFinished(0, "one", "shell", StepStatus.COMPLETED, "x", 1, Map.of()),
                new StepStarted(1, "two", "shell", 1, false));

        RunView view = view(false);

        assertThat(view.status()).isEqualTo(RunStatus.INTERRUPTED);
        assertThat(view.steps().get(0).interrupted()).isFalse();
        assertThat(view.steps().get(1).interrupted()).isTrue();
        assertThat(view.steps().get(1).status()).isEqualTo(StepStatus.RUNNING);
    }

    @Test
    void resumedRun_countsAttempts_andTheLatestAttemptWins() {
        fx.emit(new StepStarted(0, "build", "shell", 0, false), new OutputRecorded(0, "build", "first"),
                new Checkpoint(0, snapshot()));
        fx.recorder.record("run.resumed", Map.of("actor", "operator@box", "rerun_step", 0));
        fx.emit(new StepStarted(0, "build", "shell", 0, false), new OutputRecorded(0, "build", "second"),
                new StepFinished(0, "build", "shell", StepStatus.COMPLETED, "i", 5, Map.of()),
                new RunFinished(RunStatus.COMPLETED, Map.of(), null));

        RunView view = view(false);

        assertThat(view.steps()).hasSize(1);
        assertThat(view.steps().getFirst().attempts()).isEqualTo(2);
        assertThat(text(view.steps().getFirst().output())).isEqualTo("second");
        assertThat(view.steps().getFirst().status()).isEqualTo(StepStatus.COMPLETED);
        assertThat(view.lastCheckpoint().nextPosition()).isZero();
        assertThat(view.lastCheckpoint().state()).isNotNull();
    }

    /** The agent of an answer is in the view of the answer, of the answer a resume took over, and in the timeline. */
    @Test
    void anAnswerAnAgentGave_namesItInTheQuestion_inTheAnswerGivenWhileParked_andInTheTimeline() {
        fx.emit(new StepStarted(0, "ask", "approval", 0, false),
                new ApprovalRequested(0, "b1", "approval", "Ship it?", List.of("yes", "no"), List.of(), null, null,
                        true, null, null, null),
                new StepWaiting(0, true), new RunParked(0, "approval"));
        fx.nextWriter();
        fx.recorder.attributeNextAnswer("anna@box", "cli", "req-1", null, "claude-code");
        fx.emit(new ApprovalAnswered(0, "approval", "yes"));
        fx.nextWriter();
        fx.recorder.record("run.resumed", Map.of("actor", "operator@box", "package_changed", false));
        fx.recorder.attributeNextAnswer("anna@box", "resume", null, null, "claude-code");
        fx.emit(new StepStarted(0, "ask", "approval", 0, false),
                new ApprovalRequested(0, "b1", "approval", "Ship it?", List.of("yes", "no"), List.of(), null, null,
                        false, null, null, null),
                new ApprovalAnswered(0, "approval", "yes"),
                new StepStarted(1, "again", "approval", 1, false),
                new ApprovalRequested(1, "b2", "approval", "Sure?", List.of("yes", "no"), List.of(), null, null,
                        false, null, null, null));
        fx.recorder.attributeNextAnswer("operator@box", "inbox", "req-2", null);
        fx.emit(new ApprovalAnswered(1, "approval", "yes"));

        RunView view = view(false);

        QuestionView.Answer replayed = view.steps().getFirst().questions().getFirst().answered();
        assertThat(replayed.agent()).isEqualTo("claude-code");
        assertThat(replayed.given().agent()).isEqualTo("claude-code");
        assertThat(view.steps().get(1).questions().getFirst().answered().agent()).isNull();
        assertThat(view.interventions()).extracting(RunView.Intervention::description).containsExactly(
                "answered step 0 (approval) via cli", "resumed the run", "answered step 1 (approval) via inbox");
        assertThat(view.interventions()).extracting(RunView.Intervention::agent)
                .containsExactly("claude-code", null, null);
    }

    @Test
    void parkApproveResume_keepsTheFirstAttempt_andTheResumedStepShowsTheAnswerGivenWhileParked() {
        fx.emit(new StepStarted(0, "ask", "approval", 0, false),
                new ApprovalRequested(0, "b1", "approval", "Ship it?", List.of("yes", "no"), List.of(), null, null,
                        true, null, null, null),
                new StepWaiting(0, true), new RunParked(0, "approval"));
        fx.nextWriter();
        fx.recorder.attributeNextAnswer("anna@box", "cli", "req-1", "ship it");
        fx.emit(new ApprovalAnswered(0, "approval", "yes"));
        int cli = fx.events().getLast().seq();
        fx.nextWriter();
        Map<String, Object> resumed = new java.util.LinkedHashMap<>();
        resumed.put("actor", "operator@box");
        resumed.put("from_checkpoint", 0);
        resumed.put("next_position", 0);
        resumed.put("rerun_step", null);
        resumed.put("answer", cli);
        resumed.put("package_changed", true);
        resumed.put("package_digest", "digest-2");
        fx.recorder.record("run.resumed", resumed);
        fx.recorder.attributeNextAnswer("anna@box", "resume", null, null);
        fx.emit(new StepStarted(0, "ask", "approval", 0, false),
                new ApprovalRequested(0, "b1", "approval", "Ship it?", List.of("yes", "no"), List.of(), null, null,
                        false, null, null, null),
                new ApprovalAnswered(0, "approval", "yes"),
                new StepFinished(0, "ask", "approval", StepStatus.COMPLETED, "i", 5, Map.of()),
                new RunFinished(RunStatus.COMPLETED, Map.of(), null));

        RunView view = view(false);

        StepView step = view.steps().getFirst();
        assertThat(step.attempts()).isEqualTo(2);
        assertThat(step.earlierAttempts()).singleElement().satisfies(first -> {
            assertThat(first.number()).isEqualTo(1);
            assertThat(first.questions()).singleElement().satisfies(q -> {
                assertThat(q.answered().source()).isEqualTo("cli");
                assertThat(q.answered().actor()).isEqualTo("anna@box");
                assertThat(text(q.answered().comment())).isEqualTo("ship it");
            });
        });
        QuestionView.Answer replayed = step.questions().getFirst().answered();
        assertThat(replayed.source()).isEqualTo("resume");
        assertThat(replayed.given()).isNotNull();
        assertThat(replayed.given().seq()).isEqualTo(cli);
        assertThat(replayed.given().source()).isEqualTo("cli");
        assertThat(replayed.given().actor()).isEqualTo("anna@box");
        assertThat(replayed.given().at()).isNotNull().isBefore(replayed.at());
        assertThat(text(replayed.given().comment())).isEqualTo("ship it");
        assertThat(view.interventions()).extracting(RunView.Intervention::type)
                .containsExactly("approval.answered", "run.resumed");
        assertThat(view.interventions().get(0).actor()).isEqualTo("anna@box");
        assertThat(view.interventions().get(0).description()).contains("step 0", "cli");
        assertThat(view.interventions().get(1).actor()).isEqualTo("operator@box");
        assertThat(view.interventions().get(1).description()).contains("package changed", "digest-2");
    }

    @Test
    void interruptedAttempt_isKept_withItsCommands() {
        traceworks.core.testing.FakeCommandRunner fake =
                new traceworks.core.testing.FakeCommandRunner()
                        .on("make", traceworks.core.testing.FakeCommandRunner.ok("ok"));
        fx.emit(new StepStarted(0, "build", "shell", 0, false));
        fx.recorder.recording(fake).run(request("make"));
        fx.nextWriter();
        fx.recorder.record("run.recovered", Map.of("discarded", "{\"seq\":9".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Map<String, Object> resumed = new java.util.LinkedHashMap<>();
        resumed.put("actor", "operator@box");
        resumed.put("rerun_step", 0);
        resumed.put("package_changed", false);
        fx.recorder.record("run.resumed", resumed);
        fx.emit(new StepStarted(0, "build", "shell", 0, false));
        fx.recorder.recording(fake).run(request("make"));
        fx.emit(new StepFinished(0, "build", "shell", StepStatus.COMPLETED, "make", 5, Map.of()));

        RunView view = view(false);

        StepView step = view.steps().getFirst();
        assertThat(step.commands()).hasSize(1);
        assertThat(step.earlierAttempts()).singleElement().satisfies(first ->
                assertThat(first.commands()).singleElement().satisfies(c -> assertThat(c.result()).isNotNull()));
        assertThat(view.interventions()).extracting(RunView.Intervention::type)
                .containsExactly("run.recovered", "run.resumed");
        assertThat(view.interventions().get(1).description()).contains("step 0 runs again")
                .doesNotContain("package changed");
        assertThat(text(view.interventions().get(0).content())).isEqualTo("{\"seq\":9");
    }

    @Test
    void openStepsOfARunCancelledWhileParkedOrInterrupted_areCancelled() {
        fx.emit(new StepStarted(0, "build", "shell", 0, false),
                new StepFinished(0, "build", "shell", StepStatus.COMPLETED, "i", 5, Map.of()),
                new StepStarted(1, "ask", "approval", 1, false),
                new ApprovalRequested(1, "b1", "approval", "Ship it?", List.of("yes", "no"), List.of(), null, null,
                        false, null, null, null),
                new StepWaiting(1, true), new RunParked(1, "approval"));
        fx.nextWriter();
        fx.recorder.record("run.cancel_requested", Map.of("actor", "operator@box", "request", "r-1"));
        fx.emit(new RunFinished(RunStatus.CANCELLED, Map.of(), null));

        RunView view = view(false);

        assertThat(view.steps()).extracting(StepView::shownStatus).containsExactly("COMPLETED", "CANCELLED");
        assertThat(view.steps()).extracting(StepView::status)
                .containsExactly(StepStatus.COMPLETED, StepStatus.CANCELLED);
        assertThat(view.steps()).extracting(StepView::cancelled).containsExactly(false, true);
        assertThat(view.steps().get(1).interrupted()).isFalse();
    }

    /** A step the cancel ended while it ran is finished CANCELLED in the record, and the view reads it so. */
    @Test
    void aStepFinishedCancelled_isCancelled_andFinished() {
        fx.emit(new StepStarted(0, "build", "shell", 0, false),
                new StepFinished(0, "build", "shell", StepStatus.CANCELLED, "i", 5, Map.of()),
                new RunFinished(RunStatus.CANCELLED, Map.of(), null));

        StepView step = view(false).steps().getFirst();

        assertThat(step.status()).isEqualTo(StepStatus.CANCELLED);
        assertThat(step.shownStatus()).isEqualTo("CANCELLED");
        assertThat(step.cancelled()).as("it finished: it was not open at the cancel").isFalse();
        assertThat(step.durationMs()).isEqualTo(5);
    }

    @Test
    void anOpenStepOfAnInterruptedRunThatWasCancelled_isCancelled() {
        fx.emit(new StepStarted(0, "build", "shell", 0, false));
        fx.nextWriter();
        fx.recorder.record("run.cancel_requested", Map.of("actor", "operator@box", "request", "r-1"));
        fx.emit(new RunFinished(RunStatus.CANCELLED, Map.of(), null));

        assertThat(view(false).steps().getFirst().shownStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void cancelRequestsAndRefusedRequests_areInterventions() {
        fx.emit(new StepStarted(0, "build", "shell", 0, false));
        fx.recorder.record("request.refused", Map.of("kind", "approval", "request", "r-1", "actor", "eve@box",
                "reason", "another answer came first"));
        fx.recorder.record("run.cancel_requested", Map.of("actor", "operator@box", "request", "r-2"));
        fx.emit(new RunFinished(RunStatus.CANCELLED, Map.of(), null));

        List<RunView.Intervention> interventions = view(false).interventions();

        assertThat(interventions).extracting(RunView.Intervention::type)
                .containsExactly("request.refused", "run.cancel_requested");
        assertThat(interventions.get(0).actor()).isEqualTo("eve@box");
        assertThat(interventions.get(0).description()).contains("approval", "another answer came first");
        assertThat(interventions.get(1).actor()).isEqualTo("operator@box");
        assertThat(interventions.get(1).description()).isEqualTo("requested a cancel (request r-2)");
        assertThat(interventions.get(1).at()).isNotNull();
    }

    /** A cancel that came without a request, from the terminal say, names none. */
    @Test
    void aCancelRequestWithoutARequest_namesNone() {
        fx.recorder.record("run.cancel_requested", Map.of("actor", "operator@box"));
        fx.emit(new RunFinished(RunStatus.CANCELLED, Map.of(), null));

        assertThat(view(false).interventions()).extracting(RunView.Intervention::description)
                .containsExactly("requested a cancel");
    }

    /** A resume names where it went on, which step runs again and which answer it uses, as far as the line says. */
    @Test
    void aResume_namesWhereItWentOn_theStepThatRunsAgain_andTheAnswerItUses() {
        Map<String, Object> full = new java.util.LinkedHashMap<>();
        full.put("actor", "operator@box");
        full.put("from_checkpoint", 4);
        full.put("next_position", 1);
        full.put("rerun_step", 1);
        full.put("answer", 7);
        full.put("package_changed", false);
        fx.recorder.record("run.resumed", full);
        fx.recorder.record("run.resumed", Map.of("actor", "operator@box"));

        assertThat(view(true).interventions()).extracting(RunView.Intervention::description).containsExactly(
                "resumed the run from the checkpoint at seq 4, going on at position 1; step 1 runs again; with the"
                        + " answer recorded at seq 7",
                "resumed the run");
    }

    /** What somebody did to the run is said once, at the run itself: its child runs do not repeat it as theirs. */
    @Test
    void interventions_belongToTheRunItself_notToItsChildRuns() {
        fx.emit(new StepStarted(0, "parent-step", "sub-workflow", 0, false),
                new ChildRunEvent("child-1", new StepStarted(0, "inner", "shell", 0, false)));
        fx.recorder.record("run.cancel_requested", Map.of("actor", "operator@box", "request", "r-1"));
        fx.emit(new ChildRunEvent("child-1", new RunFinished(RunStatus.CANCELLED, Map.of(), null)),
                new RunFinished(RunStatus.CANCELLED, Map.of(), null));

        RunView view = view(false);

        assertThat(view.interventions()).extracting(RunView.Intervention::type)
                .containsExactly("run.cancel_requested");
        assertThat(view.children().get("child-1").interventions()).isEmpty();
    }

    private static traceworks.core.model.RunSnapshot snapshot() {
        return new traceworks.core.model.RunSnapshot(
                new traceworks.core.model.ContextState(Map.of("a", "b"), null, Map.of(), null, null,
                        0, Map.of(), Map.of()), List.of(), Map.of());
    }

    @Test
    void childRuns_areNestedUnderTheirStep() {
        fx.emit(new StepStarted(0, "parent-step", "sub-workflow", 0, false),
                new ChildRunEvent("child-1", new StepStarted(0, "inner", "shell", 0, false)),
                new ChildRunEvent("child-1", new ChildRunEvent("grand-1",
                        new StepStarted(0, "deep", "shell", 0, false))),
                new ChildRunEvent("child-1", new StepFinished(0, "inner", "shell", StepStatus.COMPLETED, "i", 1,
                        Map.of())),
                new ChildRunEvent("child-1", new RunFinished(RunStatus.COMPLETED, Map.of(), null)),
                new StepFinished(0, "parent-step", "sub-workflow", StepStatus.COMPLETED, "i", 3,
                        Map.of("subRunId", "child-1")),
                new RunFinished(RunStatus.COMPLETED, Map.of(), null));

        RunView view = view(false);

        assertThat(view.children()).containsOnlyKeys("child-1");
        RunView child = view.children().get("child-1");
        assertThat(child.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(child.steps()).extracting(StepView::name).containsExactly("inner");
        assertThat(child.children()).containsOnlyKeys("grand-1");
        assertThat(child.steps().getFirst().childRunIds()).containsExactly("grand-1");
        assertThat(child.children().get("grand-1").status()).isEqualTo(RunStatus.INTERRUPTED);
        assertThat(view.steps().getFirst().childRunIds()).containsExactly("child-1");
        assertThat(view.steps()).hasSize(1);
    }

    /**
     * The run of the record counts every line of it, its child runs' included, and names the head, as its manifest
     * does; a child run counts its own lines and has no head of its own.
     */
    @Test
    void theRunCountsEveryLineOfTheRecord_aChildRunOnlyItsOwn() {
        fx.emit(new StepStarted(0, "parent-step", "sub-workflow", 0, false),
                new ChildRunEvent("child-1", new StepStarted(0, "inner", "shell", 0, false)),
                new ChildRunEvent("child-1", new StepFinished(0, "inner", "shell", StepStatus.COMPLETED, "i", 1,
                        Map.of())),
                new ChildRunEvent("child-1", new RunFinished(RunStatus.COMPLETED, Map.of(), null)),
                new StepFinished(0, "parent-step", "sub-workflow", StepStatus.COMPLETED, "i", 3, Map.of()),
                new RunFinished(RunStatus.COMPLETED, Map.of(), null));
        long ofTheChild = fx.events().stream().filter(e -> e.run().equals("child-1")).count();

        RunView view = view(false);

        assertThat(ofTheChild).isEqualTo(3);
        assertThat(view.eventCount()).isEqualTo(fx.lines().size()).isGreaterThan((int) ofTheChild);
        assertThat(view.head()).isEqualTo(fx.recorder.head());
        assertThat(view.children().get("child-1").eventCount()).isEqualTo(3);
        assertThat(view.children().get("child-1").head()).isNull();
    }

    /** An item a resumed loop took over has no start event: its finished line names the item and where it is from. */
    @Test
    void anItemTakenOverByAResumedLoop_showsItsTextAndTheEventItComesFrom() {
        fx.emit(new StepStarted(0, "each", "foreach", 0, false),
                new ForEachItemFinished(0, 0, "a", 2, StepStatus.COMPLETED, "out-a", null, 7),
                new ForEachItemStarted(0, 1, "b", 2));

        StepView step = view(true).steps().getFirst();

        assertThat(text(step.items().get(0).item())).isEqualTo("a");
        assertThat(text(step.items().get(0).output())).isEqualTo("out-a");
        assertThat(step.items().get(0).resumedFrom()).isEqualTo(7);
        assertThat(step.items().get(1).resumedFrom()).isNull();
    }

    @Test
    void foreachItems_areListedWithTheirSubRun() {
        fx.emit(new StepStarted(0, "each", "foreach", 0, false),
                new ForEachItemStarted(0, 0, "a", 2),
                new ChildRunEvent("child-a", new StepStarted(0, "inner", "shell", 0, false)),
                new ForEachItemFinished(0, 0, "a", 2, StepStatus.COMPLETED, "out-a", "child-a"),
                new ForEachItemStarted(0, 1, "b", 2));

        StepView step = view(true).steps().getFirst();

        assertThat(step.items()).hasSize(2);
        assertThat(step.items().get(0).status()).isEqualTo("COMPLETED");
        assertThat(step.items().get(0).subRun()).isEqualTo("child-a");
        assertThat(text(step.items().get(0).item())).isEqualTo("a");
        assertThat(text(step.items().get(0).output())).isEqualTo("out-a");
        assertThat(step.items().get(1).status()).isNull();
        assertThat(step.items().get(1).total()).isEqualTo(2);
        assertThat(step.childRunIds()).containsExactly("child-a");
    }

    @Test
    void commands_pairWithTheirResult_andACommandThatThrewHasNone() {
        traceworks.core.testing.FakeCommandRunner fake =
                new traceworks.core.testing.FakeCommandRunner()
                        .on("echo hi", traceworks.core.testing.FakeCommandRunner.ok("hi"));
        fx.emit(new StepStarted(0, "build", "shell", 0, false));
        var runner = fx.recorder.recording(fake);
        runner.run(request("echo hi"));
        try {
            runner.run(request("echo bye"));
        } catch (IllegalStateException expected) {
            // the command threw
        }

        List<CommandView> commands = view(true).steps().getFirst().commands();

        assertThat(commands).hasSize(2);
        assertThat(commands.get(0).result()).isNotNull();
        assertThat(commands.get(0).result().exitCode()).isZero();
        assertThat(commands.get(1).result()).isNull();
        assertThat(commands.get(0).host()).isTrue();
    }

    private static traceworks.core.port.CommandRunner.CommandRequest request(String command) {
        return new traceworks.core.port.CommandRunner.CommandRequest(command, "/work", 30, Map.of(),
                null, List.of(), false);
    }

    @Test
    void pausedRun_isPaused() {
        fx.emit(new StepStarted(0, "build", "shell", 0, false), new RunStatusChanged(RunStatus.PAUSED));

        assertThat(view(true).status()).isEqualTo(RunStatus.PAUSED);

        fx.emit(new RunStatusChanged(RunStatus.RUNNING));
        assertThat(view(true).status()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void theViewIsReadOnly() {
        fx.emit(new StepStarted(0, "build", "shell", 0, false));
        RunView view = view(true);

        assertThatThrownBy(() -> view.steps().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.children().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.steps().getFirst().uses().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(view.startedAt()).isInstanceOf(Instant.class);
    }

    @Test
    void stepFiles_areShownOnTheStep_andOldRecordsHaveNone() {
        traceworks.core.testing.InMemoryRunStore.InMemoryScratch scratch =
                new traceworks.core.testing.InMemoryRunStore.InMemoryScratch();
        scratch.put("a.txt", "one".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        scratch.putLink("l", "/etc/passwd");
        fx.emit(new StepStarted(0, "build", "shell", 0, false),
                new StepFinished(0, "build", "shell", StepStatus.COMPLETED, "i", 5, Map.of()),
                new StepStarted(1, "other", "shell", 0, false));
        assertThat(view(true).steps().get(0).files()).isNull();

        fx.recorder.withScratch(scratch);
        fx.emit(new StepFinished(1, "other", "shell", StepStatus.COMPLETED, "i", 5, Map.of()));

        StepFiles files = view(true).steps().get(1).files();
        assertThat(files.files()).singleElement().satisfies(f -> {
            assertThat(f.path()).isEqualTo("a.txt");
            assertThat(f.size()).isEqualTo(3);
            assertThat(text(f.blob())).isEqualTo("one");
        });
        assertThat(files.links()).singleElement().satisfies(l -> assertThat(l.target()).isEqualTo("/etc/passwd"));
        assertThat(view(true).steps().get(0).files()).isNull();
    }

    private static final Egress PROVIDER = Egress.hosts(List.of("api.example:443"));

    /** A command through the egress proxy: what its container asked for, {@code first} at its first call. */
    private CommandRunner proxied(List<Connection> first) {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        return fx.recorder.recording(request -> new CommandResult(0, "", "", 1, false)
                .withConnections(calls.getAndIncrement() == 0 ? first : List.of()));
    }

    /** A command with listed hosts shows them, and its end every connection its container asked the proxy for. */
    @Test
    void aCommandWithListedHosts_showsThem_andItsConnections() {
        fx.emit(new StepStarted(0, "build", "simple", 0, true));
        CommandRunner runner = proxied(List.of(new Connection("api.example:443", "ALLOW", 3),
                new Connection("evil.example:443", "DENY", 2), new Connection("api.example:443", "FAIL", 1)));
        CommandRequest call = CommandRequest.entrypoint("img", List.of("call"), "/w", 30, null, null, false)
                .withNetwork(PROVIDER);
        runner.run(call);
        runner.run(call);
        fx.recorder.recording(request -> new CommandResult(0, "", "", 1, false))
                .run(new CommandRequest("ls", "/w", 30, null, null, null, true));
        fx.emit(new StepFinished(0, "build", "simple", StepStatus.COMPLETED, "i", 42, Map.of()));

        RunView view = view(false);
        List<CommandView> commands = view.steps().getFirst().commands();

        assertThat(commands).extracting(CommandView::network).containsExactly("hosts", "hosts", "open");
        assertThat(commands.getFirst().networkHosts()).containsExactly("api.example:443");
        assertThat(commands.get(2).networkHosts()).isEmpty();
        assertThat(commands.getFirst().result().egress()).containsExactly(
                new CommandView.Connection("api.example:443", "ALLOW", 3),
                new CommandView.Connection("evil.example:443", "DENY", 2),
                new CommandView.Connection("api.example:443", "FAIL", 1));
        assertThat(commands.getFirst().result().egress()).extracting(CommandView.Connection::denied)
                .containsExactly(false, true, false);
        assertThat(commands.get(1).result().egress()).isEmpty();
        assertThat(view.egress()).isEqualTo(new RunView.Egress(2, 6, 2));
    }

    /**
     * A command whose end names a cut of its output shows it; one whose end has no such field, as every record of an
     * earlier version, has none. The run counts them, earlier attempts included.
     */
    @Test
    void aCommandWhoseOutputWasCut_saysSo_andARecordWithoutTheFieldReadsAsBefore() {
        CommandRunner.OutputCut both = new CommandRunner.OutputCut(List.of("stdout", "stderr"), 2000);
        CommandRequest serve = new CommandRequest("serve &", "/w", 30, null, null, null, false);
        fx.emit(new StepStarted(0, "build", "shell", 0, false));
        fx.recorder.recording(request -> new CommandResult(0, "", "", 1, false).withOutputCut(both)).run(serve);
        fx.emit(new StepStarted(0, "build", "shell", 0, false));
        fx.recorder.recording(request -> new CommandResult(0, "", "", 1, false).withOutputCut(both)).run(serve);
        fx.recorder.recording(request -> new CommandResult(0, "", "", 1, false))
                .run(new CommandRequest("ls", "/w", 30, null, null, null, false));
        fx.emit(new StepFinished(0, "build", "shell", StepStatus.COMPLETED, "i", 42, Map.of()));

        RunView view = view(false);
        List<CommandView> commands = view.steps().getFirst().commands();

        assertThat(commands.getFirst().outputCut()).isEqualTo(both);
        assertThat(commands.get(1).outputCut()).as("no field: read to its end").isNull();
        assertThat(commands.get(1).result().exitCode()).isZero();
        assertThat(view.outputCuts()).as("with the earlier attempt").isEqualTo(2);
        assertThat(view.outputCutsOfLatestAttempts()).as("the ones a step lists").isEqualTo(1);
        assertThat(RunView.of(new RecordFixture().started().events(), false).outputCuts()).isZero();
    }

    /**
     * Whether a record can name a cut at all: its start says so ({@code records}), and so does every resume. A
     * record of an earlier version, or one an earlier version resumed, cannot: no cut in it means nothing.
     */
    @Test
    void aRecordSaysWhetherItNamesOutputCuts_inItsStart_andInEveryResume() {
        assertThat(fx.events().getFirst().fields().path("records")).extracting(n -> n.asText())
                .containsExactly("output_cut", "answer_agent", "called_packages", "resumed_with", "request_agent");
        assertThat(view(false).recordsOutputCut()).isTrue();

        List<RecordedEvent> ofAnEarlierVersion = fx.events();
        ofAnEarlierVersion.getFirst().fields().remove("records");
        assertThat(RunView.of(ofAnEarlierVersion, false).recordsOutputCut()).isFalse();

        fx.recorder.record("run.resumed", Map.of("actor", "operator@box", "records", List.of("output_cut")));
        assertThat(view(false).recordsOutputCut()).as("resumed by this version").isTrue();
        fx.recorder.record("run.resumed", Map.of("actor", "operator@box"));
        assertThat(view(false).recordsOutputCut()).as("resumed by an earlier version").isFalse();
    }

    /**
     * Whether a record names the package of a child of another package: its start says so ({@code records}), and
     * so does every resume. In a record of an earlier version, or one an earlier version resumed, a child run
     * without the line may still have come from another package.
     */
    @Test
    void aRecordSaysWhetherItNamesCalledPackages_inItsStart_andInEveryResume() {
        assertThat(view(false).recordsCalledPackages()).isTrue();

        List<RecordedEvent> ofAnEarlierVersion = fx.events();
        ((com.fasterxml.jackson.databind.node.ArrayNode) ofAnEarlierVersion.getFirst().fields().path("records"))
                .remove(2);
        assertThat(ofAnEarlierVersion.getFirst().fields().path("records")).extracting(n -> n.asText())
                .containsExactly("output_cut", "answer_agent", "resumed_with", "request_agent");
        assertThat(RunView.of(ofAnEarlierVersion, false).recordsCalledPackages()).isFalse();
        assertThat(RunView.of(ofAnEarlierVersion, false).recordsOutputCut()).as("each fact by its own name").isTrue();

        fx.recorder.record("run.resumed", Map.of("actor", "operator@box", "records", RunRecorder.RECORDS));
        assertThat(view(false).recordsCalledPackages()).as("resumed by this version").isTrue();
        fx.recorder.record("run.resumed", Map.of("actor", "operator@box", "records", List.of("output_cut")));
        assertThat(view(false).recordsCalledPackages()).as("resumed by an earlier version").isFalse();
    }

    /**
     * The packages a run was resumed with are read from its {@code run.resumed} lines, of any version; whether its
     * manifest lists them is what the last start or resume says, since that version wrote the manifest.
     */
    @Test
    void theViewNamesThePackagesARunWasResumedWith_andWhetherItsManifestListsThem() {
        assertThat(view(false).resumedWith()).isEmpty();
        assertThat(view(false).recordsResumedWith()).isTrue();

        fx.recorder.record("run.resumed", Map.of("actor", "operator@box", "package_changed", false, "package_digest",
                "a".repeat(64), "records", List.of("output_cut")));
        assertThat(view(false).resumedWith()).as("resumed with the package it started with").isEmpty();
        assertThat(view(false).recordsResumedWith()).as("a version that lists none resumed last").isFalse();

        fx.recorder.record("run.resumed", Map.of("actor", "operator@box", "package_changed", true, "package_digest",
                "b".repeat(64), "records", List.of("output_cut")));
        assertThat(view(false).resumedWith()).as("read from a line of any version").containsExactly("b".repeat(64));
        assertThat(view(false).recordsResumedWith()).isFalse();

        fx.recorder.record("run.resumed", Map.of("actor", "operator@box", "package_changed", true, "package_digest",
                "c".repeat(64), "records", RunRecorder.RECORDS));
        assertThat(view(false).resumedWith()).containsExactly("b".repeat(64), "c".repeat(64));
        assertThat(view(false).recordsResumedWith()).as("the version that resumed last lists them").isTrue();
    }

    /**
     * Reproduced 2026-11-21: a run resumed twice with the same changed package recorded that package twice, and so
     * differed from a run resumed once with it. The list is the packages the run went on with, without repeats:
     * it starts with the package of the start, every resume goes on with its own package when that changed, else
     * with the start's; of equal entries that follow each other one stays, and the start's own at the head is
     * dropped.
     */
    @Test
    void resumedWith_isThePackagesTheRunWentOnWith_andHowOftenItWasResumedIsNoPartOfIt() {
        String start = view(false).packageDigest();
        String other = "b".repeat(64);
        assertThat(start).isNotNull().isNotEqualTo(other);

        resumed(true, other);
        resumed(true, other);
        assertThat(view(false).resumedWith()).as("twice with the same package").containsExactly(other);

        resumed(false, start);
        resumed(false, start);
        assertThat(view(false).resumedWith()).as("back with the package of the start").containsExactly(other, start);

        resumed(true, other);
        assertThat(view(false).resumedWith()).containsExactly(other, start, other);
    }

    @Test
    void resumedWith_isEmpty_forARunThatOnlyWentOnWithThePackageOfItsStart() {
        String start = view(false).packageDigest();

        resumed(false, start);
        resumed(false, start);
        resumed(false, start);

        assertThat(view(false).resumedWith()).isEmpty();
    }

    private void resumed(boolean changed, String digest) {
        fx.recorder.record("run.resumed", Map.of("actor", "operator@box", "package_changed", changed, "package_digest",
                digest, "records", RunRecorder.RECORDS));
    }

    /**
     * A child run of another package starts with {@code run.package}: its view names the package, and the run's
     * view lists the calls of its children at every depth, in log order. A child without the line has none.
     */
    @Test
    void theViewOfAChildRun_namesThePackageItsWorkflowWasLoadedFrom() {
        byte[] json = "{\"id\":\"wf-lib\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        fx.emit(new StepStarted(0, "call", "sub-workflow", 0, false),
                new ChildRunEvent("child-1", new PackageCalled("wf-lib", "Lib", "../lib", "/pkgs/lib", "digest-lib",
                        json)),
                new ChildRunEvent("child-1", new StepStarted(0, "on", "sub-workflow", 0, false)),
                new ChildRunEvent("child-1", new ChildRunEvent("grand-1", new PackageCalled("wf-deep", "Deep",
                        "wf-deep", "/pkgs/deep", "digest-deep", json))),
                new ChildRunEvent("child-1", new ChildRunEvent("grand-1",
                        new RunFinished(RunStatus.COMPLETED, Map.of(), null))),
                new ChildRunEvent("child-1", new RunFinished(RunStatus.COMPLETED, Map.of(), null)),
                new ChildRunEvent("child-2", new RunFinished(RunStatus.COMPLETED, Map.of(), null)));

        RunView view = view(false);
        RunView child = view.children().get("child-1");

        assertThat(view.packageCall()).as("the run itself").isNull();
        assertThat(child.packageCall()).isEqualTo(new PackageCall("wf-lib", "Lib", "../lib", "/pkgs/lib",
                "digest-lib", fx.events().get(2).blob("definition")));
        assertThat(fx.blobs.get(child.packageCall().definition().sha256()).orElseThrow()).isEqualTo(json);
        assertThat(view.steps().getFirst().childRunIds()).as("the line is the child's first: the step knows the run")
                .containsExactly("child-1", "child-2");
        assertThat(view.children().get("child-2").packageCall()).as("a child of the run's own package").isNull();
        assertThat(view.packageCalls()).extracting(PackageCall::workflowId).containsExactly("wf-lib", "wf-deep");
        assertThat(child.packageCalls()).extracting(PackageCall::packageDigest).containsExactly("digest-deep");
    }

    /** A {@code run.package} without its package is a damaged record, as a {@code run.started} without one is. */
    @Test
    void aPackageLineWithoutItsPackage_isADamagedRecord() {
        fx.emit(new ChildRunEvent("child-1", new PackageCalled("wf-lib", "Lib", "../lib", "/pkgs/lib",
                "digest-lib", new byte[0])));
        List<RecordedEvent> events = fx.events();
        events.getLast().fields().remove("package");

        assertThatThrownBy(() -> RunView.of(events, false)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("run.package").hasMessageContaining("lacks the object field package");
    }

    /** The denied connections of a run count those of earlier attempts and of child runs too. */
    @Test
    void theRunsEgress_countsEarlierAttempts_andChildRuns() {
        CommandRequest call = CommandRequest.entrypoint("img", List.of("call"), "/w", 30, null, null, false)
                .withNetwork(PROVIDER);
        fx.emit(new StepStarted(0, "build", "simple", 0, true));
        proxied(List.of(new Connection("a.example:1", "DENY", 1))).run(call);
        fx.emit(new StepStarted(0, "build", "simple", 0, true));
        proxied(List.of(new Connection("*", "LIMIT", 1), new Connection("api.example:443", "ALLOW", 4))).run(call);
        fx.emit(new StepFinished(0, "build", "simple", StepStatus.COMPLETED, "i", 42, Map.of()));

        assertThat(view(false).egress()).isEqualTo(new RunView.Egress(2, 6, 2));
        assertThat(RunView.of(new RecordFixture().started().events(), false).egress())
                .as("a run without such a command").isEqualTo(new RunView.Egress(0, 0, 0));
    }

    /** A record from before the allowlist says {@code network: true} or {@code false}: open or none. */
    @Test
    void theNetworkOfAnOlderRecord_isOpenOrNone() {
        assertThat(RunViewBuilder.network(Blobs.MAPPER.createObjectNode().put("network", true))).isEqualTo("open");
        assertThat(RunViewBuilder.network(Blobs.MAPPER.createObjectNode().put("network", false))).isEqualTo("none");
        assertThat(RunViewBuilder.network(Blobs.MAPPER.createObjectNode().put("network", "hosts"))).isEqualTo("hosts");
        assertThat(RunViewBuilder.network(Blobs.MAPPER.createObjectNode())).isNull();
    }

    /** Where the AI containers can connect, as the run started with it; nothing for a record that does not say. */
    @Test
    void theAiNetwork_isWhatTheRunStartedWith() {
        assertThat(view(false).aiNetwork()).isEqualTo("restricted");
        assertThat(view(false).aiHosts()).isEmpty();

        RecordFixture other = new RecordFixture();
        other.recorder.runStarted(new RunStart("wf", "Wf", "/pkg", "digest-1", null, null,
                "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), Map.of(),
                traceworks.core.model.TriggerSource.MANUAL, "operator", "0.1.0", "container", null, 16,
                Map.of(), "/ws", null, null, 128, null, List.of(),
                new AiNetwork(true, Map.of("claude", List.of("gateway.example:8443")))));
        RunView open = RunView.of(other.events(), false);

        assertThat(open.aiNetwork()).isEqualTo("open");
        assertThat(open.aiHosts()).containsExactly(Map.entry("claude", List.of("gateway.example:8443")));
    }

    /** An AI call shows its web mode and the hosts of a fetch; a record from before web tools shows none. */
    @Test
    void anAiCall_showsItsWebMode_andTheHostsOfAFetch() throws Exception {
        fx.emit(new StepStarted(0, "look", "simple", 0, true));
        FakeAiCli cli = FakeAiCli.claude();
        cli.answers("a", "b");
        AiProviders providers = fx.recorder.recording(AiProviders.of("CLAUDE", List.of(new CliAiProvider(cli,
                fx.recorder.recording(request -> new CommandResult(0, "", "", 1, false)), "traceworks/claude:1",
                Map.of("ANTHROPIC_API_KEY", "sk-ant-test-value-01")))));
        AiProvider.ActionRequest plain = new AiProvider.ActionRequest("Ask", null, null, "/w", 30, null,
                ToolPolicy.NONE, false, null);
        providers.resolve("CLAUDE").executeAction(plain);
        providers.resolve("CLAUDE").executeAction(plain.withWeb(new WebAccess(WebAccess.Mode.FETCH,
                List.of("docs.example.org:443"))));
        fx.emit(new StepFinished(0, "look", "simple", StepStatus.COMPLETED, "Ask", 42, Map.of()));

        RunView view = view(false);
        List<AiCallView> calls = view.steps().getFirst().aiCalls();

        assertThat(calls).extracting(AiCallView::web).containsExactly("none", "fetch");
        assertThat(calls).extracting(AiCallView::hasWeb).containsExactly(false, true);
        assertThat(calls.get(1).webHosts()).containsExactly("docs.example.org:443");
        assertThat(calls.getFirst().webHosts()).isEmpty();
        assertThat(view.webHostsNotEnforced()).as("the AI network is restricted: they are enforced").isFalse();
        assertThat(new AiCallView(1, null, "CLAUDE", null, null, "none", null, null, null, null, List.of(), null)
                .hasWeb()).as("a record from before web tools").isFalse();
    }

    private static RecordedEvent line(int seq, String type, String run, String parent, String more) {
        return RecordedEvent.parse(("{\"seq\":" + seq + ",\"at\":\"2026-11-12T10:00:00Z\",\"type\":\"" + type
                + "\",\"run\":\"" + run + "\"," + (parent == null ? "" : "\"parent\":\"" + parent + "\",")
                + "\"prev\":\"0\"" + more + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static final String STARTED = ",\"workflow\":{\"id\":\"w\",\"name\":\"w\"},\"package\":{},"
            + "\"actor\":\"a\",\"trigger\":\"t\",\"execution\":\"container\"";

    /** The view walks from a run to its children, so a run that is its own parent would be walked without end. */
    @Test
    void aRunThatIsItsOwnParent_isRefused() {
        assertThatThrownBy(() -> RunView.of(List.of(line(0, "run.started", "R", "R", STARTED)), false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("parent");
    }

    /** The first event's run is the root; a parent on any of its events is no record the recorder writes. */
    @Test
    void aRootRunWithAParent_isRefused() {
        assertThatThrownBy(() -> RunView.of(List.of(line(0, "run.started", "R", "X", STARTED)), false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("parent");
        assertThatThrownBy(() -> RunView.of(List.of(line(0, "run.started", "R", null, STARTED),
                line(1, "run.finished", "R", "x", ",\"status\":\"COMPLETED\"")), false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("parent");
    }

    /** A child names a run the record has already shown; two runs can then never be each other's parents. */
    @Test
    void aParentTheRecordHasNotShownYet_isRefused() {
        assertThatThrownBy(() -> RunView.of(List.of(line(0, "run.started", "R", null, STARTED),
                line(1, "run.started", "A", "B", STARTED), line(2, "run.started", "B", "A", STARTED)), false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("parent");
        // A child of the root, and a child of that child, are what the recorder writes
        RunView view = RunView.of(List.of(line(0, "run.started", "R", null, STARTED),
                line(1, "run.started", "A", "R", STARTED), line(2, "run.started", "B", "A", STARTED)), false);
        assertThat(view.children().get("A").children()).containsKey("B");
    }
}
