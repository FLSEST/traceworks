package traceworks.core.gate;

import traceworks.core.model.WorkflowDefinition;

import java.util.Map;

/**
 * Says whether a workflow <b>expects</b> a gate verdict: whether a run that delivers none is suspicious.
 *
 * <p>Without this question, a check workflow whose AI step fails silently cannot be told apart from a workflow
 * without a gate: both run through and look fine. Gates stay voluntary; a verdict is expected only where the
 * workflow says so in its metadata:
 *
 * <pre>{@code "metadata": { "gate": { "expected": true } }}</pre>
 *
 * <p>Only the JSON boolean {@code true} counts. The expectation is never inferred from the text
 * {@code gate_status} somewhere in a block's config, because a command that merely prints the word would change what
 * the run means (docs/workflow-format.md).
 */
public final class GateExpectation {

    /** The key in the workflow metadata that holds the gate settings. */
    public static final String METADATA_KEY = "gate";

    /** The key inside {@link #METADATA_KEY} that says whether a verdict is expected. */
    public static final String EXPECTED_KEY = "expected";

    private GateExpectation() {}

    /** Whether the workflow expects a gate verdict; {@code false} for {@code null}. */
    public static boolean isExpected(WorkflowDefinition definition) {
        return definition != null && isExpected(definition.metadata());
    }

    /** Whether workflow metadata expects a gate verdict ({@code gate.expected: true}); {@code false} for {@code null}. */
    public static boolean isExpected(Map<String, Object> workflowMetadata) {
        if (workflowMetadata == null) {
            return false;
        }
        return workflowMetadata.get(METADATA_KEY) instanceof Map<?, ?> gate
                && Boolean.TRUE.equals(gate.get(EXPECTED_KEY));
    }
}
