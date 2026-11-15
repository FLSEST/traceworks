package traceworks.core.engine;

import traceworks.core.ai.AiProviderCapabilities;
import traceworks.core.blocks.CoreBlocks;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.RunStatus;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.AiProvider;
import traceworks.core.port.AiProviders;
import traceworks.core.port.RunEvent.RunFinished;
import traceworks.core.testing.FakeCommandRunner;
import traceworks.core.testing.MapWorkflowResolver;
import traceworks.core.testing.RecordingRunEvents;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A record write that fails inside a child run (an AI call's {@code ai.*} events bypass the run's sink and throw an
 * {@link EventLogException}) stops the parent as an event-log failure, as a failed emitted event of the child
 * does, also where the parent's step would continue on failure. A child that fails for any other reason only fails
 * its step.
 */
class ChildRunLogFailureTest {

    private static final String LOG_ERROR = "event log write failed: IOException: disk full";

    private final FakeCommandRunner commands = new FakeCommandRunner();
    private final MapWorkflowResolver workflows = new MapWorkflowResolver();
    private final RecordingRunEvents events = new RecordingRunEvents();
    private final WorkflowEngine engine = new WorkflowEngine(CoreBlocks.registry(null));
    private final AiProvider claude = mock(AiProvider.class);

    private RunOutcome run(WorkflowDefinition workflow) {
        when(claude.getId()).thenReturn("CLAUDE");
        when(claude.getCapabilities()).thenReturn(AiProviderCapabilities.full());
        return engine.run(workflow, Map.of(), RunOptions.builder().runId("run-1").events(events)
                .commandRunner(commands).aiProviders(AiProviders.of("CLAUDE", List.of(claude)))
                .workflowResolver(workflows).runControl(new RunControl()).clock(() -> Instant.EPOCH)
                .workingDirectory("/work").build());
    }

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        return BlockDefinition.builder().type(type).name(name).config(new HashMap<>(config)).build();
    }

    private static WorkflowDefinition workflow(String id, BlockDefinition... blocks) {
        return new WorkflowDefinition(id, "Workflow " + id, null, List.of(blocks), null, null, null, null, Map.of(),
                Map.of(), null, null, null);
    }

    /** A child of one AI step. */
    private void childWithAiStep() {
        workflows.add(workflow("wf-child", block("simple", "ask", Map.of("command", "question"))));
    }

    private BlockDefinition subWorkflow(boolean continueOnFailure) {
        Map<String, Object> config = new HashMap<>(Map.of("workflow_id", "wf-child"));
        if (continueOnFailure) {
            config.put("on_failure", "continue");
        }
        return block("sub-workflow", "child", config);
    }

    private BlockDefinition foreachChild(boolean continueOnFailure) {
        Map<String, Object> config = new HashMap<>(Map.of("items", List.of("a", "b"), "workflow_id", "wf-child"));
        if (continueOnFailure) {
            config.put("on_failure", "continue");
        }
        return block("foreach", "each", config);
    }

    private void assertStoppedAsEventLogFailure(RunOutcome outcome) {
        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).isEqualTo(LOG_ERROR);
        assertThat(events.last()).isEqualTo(new RunFinished(RunStatus.FAILED, Map.of(), LOG_ERROR));
        assertThat(commands.commands()).as("no block after the child ran").isEmpty();
    }

    private void recordWriteFails() throws IOException {
        when(claude.reason(any())).thenThrow(new EventLogException(new IOException("disk full")));
        when(claude.executeAction(any())).thenThrow(new EventLogException(new IOException("disk full")));
    }

    @Test
    void subWorkflow_childRecordWriteFails_stopsTheParent() throws IOException {
        childWithAiStep();
        recordWriteFails();

        assertStoppedAsEventLogFailure(run(workflow("wf-parent", subWorkflow(false),
                block("shell", "after", Map.of("command", "echo after")))));
    }

    @Test
    void subWorkflow_childRecordWriteFails_stopsTheParentAlsoWithOnFailureContinue() throws IOException {
        childWithAiStep();
        recordWriteFails();

        assertStoppedAsEventLogFailure(run(workflow("wf-parent", subWorkflow(true),
                block("shell", "after", Map.of("command", "echo after")))));
    }

    @Test
    void foreachWorkflow_childRecordWriteFails_stopsTheParent() throws IOException {
        childWithAiStep();
        recordWriteFails();

        assertStoppedAsEventLogFailure(run(workflow("wf-parent", foreachChild(false),
                block("shell", "after", Map.of("command", "echo after")))));
    }

    @Test
    void foreachWorkflow_childRecordWriteFails_stopsTheParentAlsoWithOnFailureContinue() throws IOException {
        childWithAiStep();
        recordWriteFails();

        assertStoppedAsEventLogFailure(run(workflow("wf-parent", foreachChild(true),
                block("shell", "after", Map.of("command", "echo after")))));
    }

    /** The child's own end is still recorded, then the parent's. */
    @Test
    void subWorkflow_childRecordWriteFails_childEndIsRecordedBeforeTheParents() throws IOException {
        childWithAiStep();
        recordWriteFails();

        run(workflow("wf-parent", subWorkflow(false)));

        var childEvents = events.of(traceworks.core.port.RunEvent.ChildRunEvent.class);
        assertThat(childEvents).isNotEmpty();
        assertThat(childEvents.getLast().event()).isEqualTo(new RunFinished(RunStatus.FAILED, Map.of(), LOG_ERROR));
    }

    /** A child that fails for any other reason only fails its step: the parent's policy decides. */
    @Test
    void subWorkflow_childFailsForAnotherReason_onlyFailsTheStep() throws IOException {
        childWithAiStep();
        when(claude.reason(any())).thenThrow(new IllegalStateException("model unavailable"));
        when(claude.executeAction(any())).thenThrow(new IllegalStateException("model unavailable"));
        commands.otherwise(FakeCommandRunner.ok("ok"));

        RunOutcome stopped = run(workflow("wf-parent", subWorkflow(false),
                block("shell", "after", Map.of("command", "echo after"))));
        assertThat(stopped.status()).isEqualTo(RunStatus.FAILED);
        assertThat(stopped.error()).doesNotContain("event log write failed");
        assertThat(commands.commands()).isEmpty();

        RunOutcome continued = run(workflow("wf-parent", subWorkflow(true),
                block("shell", "after", Map.of("command", "echo after"))));
        assertThat(continued.status()).isNotEqualTo(RunStatus.FAILED);
        assertThat(commands.commands()).containsExactly("echo after");
    }
}
