package traceworks.core.blocks;

import traceworks.core.ai.ToolPolicy;
import traceworks.core.engine.BlockTypeRegistry;
import traceworks.core.engine.RunControl;
import traceworks.core.engine.RunOptions;
import traceworks.core.engine.RunOutcome;
import traceworks.core.engine.WorkflowEngine;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.AiProvider;
import traceworks.core.port.AiProviders;
import traceworks.core.port.CommandRunner;
import traceworks.core.port.CommandRunner.CommandRequest;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.RunEvent;
import traceworks.core.port.RunEvent.ForEachItemFinished;
import traceworks.core.port.RunEvent.ForEachItemStarted;
import traceworks.core.port.RunEvent.RunFinished;
import traceworks.core.port.RunEvent.RunStatusChanged;
import traceworks.core.port.RunEvent.StepFinished;
import traceworks.core.port.RunEvent.StepStarted;
import traceworks.core.testing.FakeAiProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The registry of the core block types, and the engine running them. The engine tests that need real block executors
 * live here, because {@code core.engine} does not depend on {@code core.blocks}: moved from {@code WorkflowEngineTest}.
 */
class CoreBlocksTest {

    private static final Instant NOW = Instant.parse("2026-11-02T08:15:30Z");

    /** The run's events, in order. */
    private final List<RunEvent> recorded = new CopyOnWriteArrayList<>();
    private final RunControl control = new RunControl();
    private List<BlockDefinition> blocks = new ArrayList<>();

    private WorkflowDefinition workflow() {
        return new WorkflowDefinition("wf-1", "Test Workflow", null, blocks, null, null, null, null, Map.of(),
                Map.of(), null, null, null);
    }

    private RunOptions.Builder options() {
        return RunOptions.builder().runId("run-1").events(recorded::add).runControl(control).clock(() -> NOW)
                .workingDirectory("/workspace/test");
    }

    /** Exactly the thirteen core block types, each with its own executor. */
    @Test
    void registry_registersExactlyTheThirteenCoreBlockTypes() {
        BlockTypeRegistry registry = CoreBlocks.registry(Path.of("/tool-cache"));

        assertThat(registry.knownTypes()).containsExactlyInAnyOrder("shell", "simple", "agentic", "retry",
                "conditional", "switch", "while", "foreach", "approval", "human-input", "sub-workflow", "compress",
                "compress-ai");
        for (String type : registry.knownTypes()) {
            assertThat(registry.executorFor(type).supportedTypes()).as(type).containsExactly(type);
        }
    }

    /**
     * The core blocks reach the ports of the run they run in: a shell step's command goes to the run's
     * {@link CommandRunner}, a simple step's prompt to the run's AI provider, and the output of the one reaches the
     * other.
     */
    @Test
    void run_coreBlocksUseTheRunsCommandRunnerAndAiProviders() throws Exception {
        List<CommandRequest> commands = new ArrayList<>();
        CommandRunner runner = request -> {
            commands.add(request);
            return new CommandResult(0, "build ok", "", 3, false);
        };
        AiProvider claude = mock(AiProvider.class);
        when(claude.getId()).thenReturn("CLAUDE");
        when(claude.executeAction(any())).thenReturn(new AiProvider.ActionResult("looks fine", 0, null));
        blocks = new ArrayList<>(List.of(
                BlockDefinition.builder().type("shell").name("build").config(new HashMap<>(Map.of("command", "make"))).build(),
                BlockDefinition.builder().type("simple").name("review")
                        .config(new HashMap<>(Map.of("command", "Review: {{ block.build.output }}"))).build()));

        RunOutcome outcome = new WorkflowEngine(CoreBlocks.registry(Path.of("/tool-cache")))
                .run(workflow(), null, options().commandRunner(runner)
                        .aiProviders(AiProviders.of("CLAUDE", List.of(claude))).build());

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(commands).extracting(CommandRequest::command).containsExactly("make");
        assertThat(commands.getFirst().workingDirectory()).isEqualTo("/workspace/test");
        ArgumentCaptor<AiProvider.ActionRequest> prompt = ArgumentCaptor.forClass(AiProvider.ActionRequest.class);
        verify(claude).executeAction(prompt.capture());
        assertThat(prompt.getValue().prompt()).isEqualTo("Review: build ok");
        assertThat(outcome.steps()).extracting(StepResult::getOutput).containsExactly("build ok", "looks fine");
    }

    /**
     * An AI condition, {@code compress-ai} and an AI actor always run without tools, whatever their config says: they
     * answer, they do not work. The branch of the conditional is its AI step and carries the block's {@code tools}.
     */
    @Test
    void aiConditionAndActorsAndCompressAi_alwaysRunWithNone_theBranchCarriesTheBlocksTools() {
        CommandRunner runner = request -> new CommandResult(0, "build ok", "", 3, false);
        FakeAiProvider claude = new FakeAiProvider("CLAUDE").answers("TRUE", "branch done", "the summary", "Yes",
                "reviewed");
        blocks = new ArrayList<>(List.of(
                BlockDefinition.builder().type("shell").name("log").config(new HashMap<>(Map.of("command", "make")))
                        .build(),
                BlockDefinition.builder().type("conditional").name("check").config(new HashMap<>(Map.of(
                        "execution_mode", "ai", "condition", "Is the build ok?", "then_command", "Say so",
                        "tools", "shell"))).build(),
                BlockDefinition.builder().type("compress-ai").name("short").config(new HashMap<>(Map.of(
                        "source", "log", "tools", "read"))).build(),
                BlockDefinition.builder().type("approval").name("go").config(new HashMap<>(Map.of(
                        "question", "Ship it?", "options", List.of("Yes", "No"), "actor", "ai", "tools", "write")))
                        .build(),
                BlockDefinition.builder().type("simple").name("review").config(new HashMap<>(Map.of(
                        "command", "Review it", "tools", "read"))).build()));

        WorkflowDefinition withTools = new WorkflowDefinition("wf-1", "Test Workflow", null, blocks, null, null, null,
                null, Map.of(), Map.of(), List.of("ai.tools"), null, null);

        RunOutcome outcome = new WorkflowEngine(CoreBlocks.registry(Path.of("/tool-cache")))
                .run(withTools, null, options().commandRunner(runner).aiProviders(claude.asProviders()).build());

        assertThat(outcome.status()).as(outcome.error()).isEqualTo(RunStatus.COMPLETED);
        assertThat(claude.actionRequests()).extracting(AiProvider.ActionRequest::tools).containsExactly(
                ToolPolicy.NONE, ToolPolicy.SHELL, ToolPolicy.NONE, ToolPolicy.NONE, ToolPolicy.READ);
        assertThat(claude.actionRequests()).extracting(AiProvider.ActionRequest::writableWorkspace)
                .containsOnly(false);
    }

    /** A control-flow block without execution_mode is refused before the run starts, instead of asking a model. */
    @Test
    void run_controlFlowBlockWithoutExecutionMode_isRefusedWithoutACall() throws Exception {
        CommandRunner runner = mock(CommandRunner.class);
        AiProvider claude = mock(AiProvider.class);
        lenient().when(claude.getId()).thenReturn("CLAUDE");
        blocks = new ArrayList<>(List.of(BlockDefinition.builder().type("retry")
                .config(new HashMap<>(Map.of("command", "npm test", "max_retries", 1, "delay", 0))).build()));

        RunOutcome outcome = new WorkflowEngine(CoreBlocks.registry(Path.of("/tool-cache")))
                .run(workflow(), null, options().commandRunner(runner)
                        .aiProviders(AiProviders.of("CLAUDE", List.of(claude))).build());

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).isEqualTo("workflow refused: Block 1: execution_mode is required (shell or ai)");
        assertThat(outcome.steps()).isEmpty();
        verify(claude, never()).executeAction(any());
        verify(runner, never()).run(any());
    }

    /** A foreach reports each item between the start and the finish of its step. */
    @Test
    void run_foreachReportsItsItemsBetweenTheStepEvents() {
        CommandRunner runner = request -> new CommandResult(0, "linted " + request.command(), "", 1, false);
        blocks = new ArrayList<>(List.of(BlockDefinition.builder().type("foreach").name("lint")
                .config(new HashMap<>(Map.of("items", List.of("a.js", "b.js"), "command", "eslint {{ item }}",
                        "execution_mode", "shell"))).build()));

        RunOutcome outcome = new WorkflowEngine(CoreBlocks.registry(Path.of("/tool-cache")))
                .run(workflow(), null, options().commandRunner(runner).build());

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(outcome.steps()).singleElement().extracting(StepResult::getOutput)
                .isEqualTo("[a.js]: linted eslint a.js\n[b.js]: linted eslint b.js");
        List<RunEvent> stepEvents = recorded.stream()
                .filter(e -> !(e instanceof RunStatusChanged) && !(e instanceof RunFinished)).toList();
        assertThat(stepEvents).first().isInstanceOf(StepStarted.class);
        assertThat(stepEvents).filteredOn(e -> e instanceof ForEachItemStarted || e instanceof ForEachItemFinished)
                .containsExactly(
                        new ForEachItemStarted(0, 0, "a.js", 2),
                        new ForEachItemFinished(0, 0, "a.js", 2, StepStatus.COMPLETED, "linted eslint a.js", null),
                        new ForEachItemStarted(0, 1, "b.js", 2),
                        new ForEachItemFinished(0, 1, "b.js", 2, StepStatus.COMPLETED, "linted eslint b.js", null));
        assertThat(stepEvents).last().isInstanceOf(StepFinished.class);
    }

    /**
     * A typo in an AI condition's provider fails the run, even with {@code on_condition_error: false}: it must not end
     * a while COMPLETED after 0 iterations (an unknown provider is refused, never settled as an answer).
     */
    @Test
    void run_aiConditionWithAnUnknownProvider_failsWhateverOnConditionError() throws Exception {
        AiProvider claude = mock(AiProvider.class);
        lenient().when(claude.getId()).thenReturn("CLAUDE");
        blocks = new ArrayList<>(List.of(BlockDefinition.builder().type("while")
                .config(new HashMap<>(Map.of("command", "shorten it", "condition_command", "too long?",
                        "execution_mode", "ai", "provider", "NOPE", "on_condition_error", "false"))).build()));

        RunOutcome outcome = new WorkflowEngine(CoreBlocks.registry(Path.of("/tool-cache")))
                .run(workflow(), null, options().aiProviders(AiProviders.of("CLAUDE", List.of(claude))).build());

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).isEqualTo("Step 0 (while) failed: "
                + "Condition evaluation failed (unknown AI provider 'NOPE'; configured: CLAUDE)");
        verify(claude, never()).executeAction(any());
    }
}
