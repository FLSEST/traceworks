package traceworks.core.blocks;

import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.BlockExecutor;
import traceworks.core.engine.WorkflowExecutionException;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.BlockSettings;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * {@code sub-workflow}: runs another workflow as one step. {@code workflow_id} is a package-relative path or a workflow
 * id, and may use trusted variables ({@code {{ var }}}); the child run is {@link SubWorkflowLauncher}'s job. The step
 * output is a copy of the child's last output ({@code derivedOutput}); a child that ended COMPLETED_WITH_ERRORS
 * completes the step with a warning.
 */
public class SubWorkflowBlockExecutor implements BlockExecutor {

    /** The legacy spelling of {@code workflow_id}; refused, with an error that names it (docs/workflow-format.md). */
    static final String LEGACY_KEY = "workflowId";

    private final SubWorkflowLauncher launcher;

    public SubWorkflowBlockExecutor() {
        this(new SubWorkflowLauncher());
    }

    SubWorkflowBlockExecutor(SubWorkflowLauncher launcher) {
        this.launcher = launcher;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("sub-workflow");
    }

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        int stepIndex = context.nextStepIndex();
        Instant start = context.getClock().now();

        Map<String, Object> config = block.getConfig();
        String own = BlockSettings.of(block).text("workflow_id");
        String configured = own != null && !own.isBlank() ? own : null;
        if (configured == null) {
            throw new WorkflowExecutionException("Sub-workflow block requires 'workflow_id' in config"
                    + (config.containsKey(LEGACY_KEY)
                    ? " (the legacy key '" + LEGACY_KEY + "' is not accepted; write 'workflow_id')"
                    : ""));
        }

        StringBuilder refusal = new StringBuilder();
        String workflowRef = SubWorkflowLauncher.resolveWorkflowId(configured, context, refusal);
        SubWorkflowLauncher.Outcome outcome = workflowRef != null
                ? launcher.run(workflowRef, context)
                : SubWorkflowLauncher.Outcome.refused(configured, refusal.toString());
        Instant end = context.getClock().now();

        StepResult.Builder result = StepResult.builder()
                .stepIndex(stepIndex)
                .input("[Sub-Workflow] " + (outcome.workflowName() != null ? outcome.workflowName() : outcome.workflowId()))
                .output(outcome.output())
                .startedAt(start)
                .completedAt(end)
                .durationMs(end.toEpochMilli() - start.toEpochMilli())
                .status(outcome.success() ? StepStatus.COMPLETED : StepStatus.FAILED);
        if (outcome.started()) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("subWorkflowId", outcome.workflowId());
            metadata.put("subWorkflowRef", workflowRef);
            metadata.put("subWorkflowName", outcome.workflowName());
            metadata.put("subRunId", outcome.subRunId());
            metadata.put("subRunStatus", outcome.subRunStatus().name());
            // The output is a copy of the child's; its gate_status markers count in the child run only
            metadata.put("derivedOutput", true);
            // COMPLETED_WITH_ERRORS: the step completes, the warning says why the child is not clean
            if (outcome.warning() != null) metadata.put("subRunWarning", outcome.warning());
            result.metadata(metadata);
        }
        return result.build();
    }
}
