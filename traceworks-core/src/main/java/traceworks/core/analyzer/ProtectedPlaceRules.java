package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.context.PlaceholderSyntax;
import traceworks.core.context.ShellLineRefusedException;
import traceworks.core.engine.ChildWorkflows;
import traceworks.core.model.BlockDefinition;
import traceworks.core.port.WorkflowResolver;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code protected-value-outside-shell} ({@code ERROR}): a placeholder the workflow shows to be protected
 * ({@link ProtectedValues}) stands in one of the places besides a shell line that take no protected value. The run
 * refuses each of them when the step is reached, so the check refuses the workflow before the start, as
 * {@code protected-value-in-shell} does for a shell line.
 *
 * <ul>
 *   <li>{@code container_args} of a {@code shell} block in tool mode: the value would choose what the tool does
 *       ({@code ContainerToolStep.resolveArgs}).</li>
 *   <li>{@code mounts}, the whole entry, of every block whose step reads them: a {@code shell} block (also one in a
 *       {@code switch} case, and a tool step), a wrapper in shell mode ({@code retry}, {@code while},
 *       {@code conditional}, a {@code foreach} with a command) and a {@code simple} block in shell mode in a
 *       {@code switch} case ({@code ShellBlockExecutor.resolveMounts}).</li>
 *   <li>{@code workflow_id} of a {@code sub-workflow} or {@code foreach}: the value would choose which workflow runs
 *       ({@code SubWorkflowLauncher.resolveWorkflowId}). The run takes no {@code previous_output} and no
 *       {@code block.<name>.output} there at all, protected or not, so each of them is reported in every
 *       workflow.</li>
 * </ul>
 *
 * <p>The run's refusal stays the guard: what the block list does not tell (a loop item, a variable, an untrusted
 * input, what a parent hands to a child) only the run knows. Settings no run reads are not judged:
 * {@code container_args} and {@code workflow_id} of a nested block, {@code mounts} of a wrapper in AI mode.
 */
final class ProtectedPlaceRules {

    private ProtectedPlaceRules() {
    }

    private static final String NEVER = " — text a model wrote and text from outside the run never go into ";
    private static final String FROM_A_VARIABLE = ", or take it from a variable that default_context or an input of"
            + " the run sets";
    /** Wrappers that run their command as a shell step, with the block's mounts, when in shell mode. */
    private static final Set<String> SHELL_SWITCHABLE = Set.of("retry", "foreach", "while", "conditional");

    /** The protected placeholders of one place of a block, and what each stands for, in order of occurrence. */
    private record Hits(Set<String> keys, Set<String> sources) {

        Hits() {
            this(new LinkedHashSet<>(), new LinkedHashSet<>());
        }

        void add(String key, String source) {
            keys.add(key);
            sources.add(source);
        }

        boolean isEmpty() {
            return keys.isEmpty();
        }

        String first() {
            return keys.iterator().next();
        }

        /** {@code {{ a }}, {{ b }}}. */
        String shown() {
            return keys.stream().map(key -> "{{ " + key + " }}").collect(Collectors.joining(", "));
        }

        String said() {
            return String.join(", ", sources);
        }
    }

    /**
     * @param children finds the workflows of {@code sub-workflow} and {@code foreach} blocks, to see whether one
     *                 asks a model; {@code null} looks into none
     */
    static void apply(List<BlockDefinition> blocks, WorkflowResolver children, List<AnalyzerFinding> out) {
        ProtectedValues values = ProtectedValues.of(blocks, children);
        BlockConfigs.walk(blocks, block -> {
            if (block.config() == null) return;
            if (!block.nested()) {
                toolArguments(values, block, out);
                workflowId(values, block, out);
            }
            mounts(values, block, out);
        });
    }

    private static void toolArguments(ProtectedValues values, BlockConfigs.Config block, List<AnalyzerFinding> out) {
        if (!"shell".equals(block.type()) || !(block.config().get("container_args") instanceof List<?> args)) return;
        Hits hits = new Hits();
        for (Object arg : args) {
            if (arg instanceof String text) collect(values, block, text, hits);
        }
        if (hits.isEmpty()) return;
        String variable = ShellLineRefusedException.variableName(hits.first());
        out.add(AnalyzerFinding.of("protected-value-outside-shell",Severity.ERROR, block.index(), block.name(),
                "puts " + hits.shown() + " into container_args, " + hits.said() + NEVER + "the arguments of a tool:"
                        + " the value would choose what the tool does; a run refuses the step before the tool"
                        + " starts, whatever on_failure says, so Traceworks refuses the workflow. Correct: a tool"
                        + " step takes no values, so have a shell step before it write the value to a file"
                        + " (\"values\": {\"" + variable + "\": \"{{ " + hits.first() + " }}\"} and printf '%s' \"$"
                        + variable + "\" > \"{{ scratch_dir }}/value.txt\"), and name the file in the arguments"));
    }

    private static void mounts(ProtectedValues values, BlockConfigs.Config block, List<AnalyzerFinding> out) {
        if (!readsMounts(block) || !(block.config().get("mounts") instanceof List<?> mounts)) return;
        Hits hits = new Hits();
        Set<String> entries = new LinkedHashSet<>();
        for (Object mount : mounts) {
            if (!(mount instanceof String entry) || entry.isBlank()) continue;
            if (collect(values, block, entry, hits)) entries.add("\"" + entry + "\"");
        }
        if (hits.isEmpty()) return;
        out.add(AnalyzerFinding.of("protected-value-outside-shell",Severity.ERROR, block.index(), block.name(),
                block.where() + "names " + hits.shown() + " in mounts (" + String.join(", ", entries) + "), "
                        + hits.said() + NEVER + "a mount: the value would choose what the container sees; a run"
                        + " refuses the step before anything starts, whatever on_failure says, so Traceworks"
                        + " refuses the workflow. Correct: write the path out" + FROM_A_VARIABLE));
    }

    /**
     * Whether a step of the block reads its {@code mounts}: a {@code shell} block, a wrapper whose command runs as a
     * shell step, and a {@code simple} block in shell mode in a {@code switch} case. A {@code foreach} of child
     * workflows runs no step of its own.
     */
    private static boolean readsMounts(BlockConfigs.Config block) {
        if ("shell".equals(block.type())) return true;
        if (!BlockText.runsAsShell(block.config())) return false;
        if (block.nested()) return "simple".equals(block.type());
        return SHELL_SWITCHABLE.contains(block.type()) && !ChildWorkflows.runsAChild(block.type(), block.config());
    }

    private static void workflowId(ProtectedValues values, BlockConfigs.Config block, List<AnalyzerFinding> out) {
        if (!ChildWorkflows.runsAChild(block.type(), block.config())) return;
        Hits hits = new Hits();
        for (String key : PlaceholderSyntax.keysIn((String) block.config().get("workflow_id"))) {
            String name = key.strip();
            String source = values.sourceOf(block.index(), name, true);
            // The run refuses these two kinds of key there in every run, whatever the value is
            if (source == null && ("previous_output".equals(name) || name.startsWith("block."))) {
                source = "a step output, which a workflow_id takes in no run";
            }
            if (source != null) hits.add(name, source);
        }
        if (hits.isEmpty()) return;
        out.add(AnalyzerFinding.of("protected-value-outside-shell",Severity.ERROR, block.index(), block.name(),
                "names " + hits.shown() + " in workflow_id, " + hits.said() + NEVER + "a workflow_id: the value"
                        + " would choose which workflow runs, with the run's workspace and secrets; a run fails the"
                        + " step before any workflow starts, so Traceworks refuses the workflow. Correct: write the"
                        + " workflow out" + FROM_A_VARIABLE));
    }

    /**
     * Adds the placeholders of {@code text} that the workflow shows to be protected in {@code block}.
     *
     * @return whether the text names one
     */
    private static boolean collect(ProtectedValues values, BlockConfigs.Config block, String text, Hits hits) {
        boolean found = false;
        for (String key : PlaceholderSyntax.keysIn(text)) {
            String source = values.sourceOf(block.index(), key, !block.nested());
            if (source == null) continue;
            hits.add(key.strip(), source);
            found = true;
        }
        return found;
    }
}
