package traceworks.core.engine;

import traceworks.core.model.BlockDefinition;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;

import java.util.Map;
import java.util.Set;

/**
 * Which step outputs may set the run's gate. A step's {@code gate_status} markers count unless
 *
 * <ul>
 *   <li>its output is derived: a compression or a sub-workflow hand-up copies another step's text, whose markers
 *       belong to that step only ({@code derivedOutput});</li>
 *   <li>its block is a wrapper in AI mode ({@code foreach}, {@code retry}, {@code while}, {@code conditional}): the
 *       output is what a model wrote for each item or attempt, and a model steered by what it reads (a workspace file,
 *       say) could write a marker there that fails the run or names its own cause;</li>
 *   <li>its output holds a command output that the output limit cut ({@code CommandResult#truncated}): its markers
 *       are those the command printed before the cut, and one it printed after, a worse one say, is lost. The block
 *       that ran the command marks its step ({@link #noGateSource}): a shell step, a tool step and an inner shell
 *       step, and a wrapper whose output holds such an inner step's ({@link #carry}). A {@code checked_command} step
 *       keeps the gate of its exit code, which its header states whole in front of the cut output.</li>
 *   <li>the run's cancel ended the step ({@link StepStatus#CANCELLED}): its output is no completed result, and a
 *       command the cancel killed would otherwise report the gate of its exit code.</li>
 * </ul>
 *
 * <p>{@code simple} and {@code agentic} steps stay gate sources: a report step delivers its verdict that way on
 * purpose. A wrapper in shell mode runs commands, whose markers count as a shell step's do.
 */
public final class GateSources {

    /** Step metadata: {@code false} on a step whose output is no gate source. */
    public static final String METADATA_KEY = "gateMarkers";

    private static final Set<String> WRAPPERS = Set.of("foreach", "retry", "while", "conditional");

    private GateSources() {
    }

    /** Whether the markers in the output of {@code block}'s step are ignored: a wrapper in AI mode. */
    static boolean ignoresMarkers(BlockDefinition block) {
        return block != null && WRAPPERS.contains(block.getType()) && StepModes.usesAi(block);
    }

    /** Makes {@code step}'s output no gate source: it holds a command output that the output limit cut. */
    public static void noGateSource(StepResult step) {
        StepAiMetadata.put(step, METADATA_KEY, false);
    }

    /**
     * Makes {@code wrapper}'s output no gate source when it holds the output of {@code inner} and that is none: a
     * wrapper's output is made of its inner steps' outputs, the cut ones among them.
     */
    public static void carry(StepResult wrapper, StepResult inner) {
        if (inner != null && inner.getMetadata() != null
                && Boolean.FALSE.equals(inner.getMetadata().get(METADATA_KEY))) {
            noGateSource(wrapper);
        }
    }

    /** Whether the markers in {@code step}'s output count for the run's gate. */
    static boolean counts(StepResult step) {
        if (step.getOutput() == null || step.getStatus() == StepStatus.CANCELLED) {
            return false;
        }
        Map<String, Object> metadata = step.getMetadata();
        return metadata == null || (!Boolean.TRUE.equals(metadata.get("derivedOutput"))
                && !Boolean.FALSE.equals(metadata.get(METADATA_KEY)));
    }
}
