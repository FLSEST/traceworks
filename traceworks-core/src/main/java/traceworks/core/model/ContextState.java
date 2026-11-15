package traceworks.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Everything of a run's execution context that the run changes while it goes on, as plain data: what a checkpoint
 * keeps so that a resumed run continues exactly where the run stopped. {@code ExecutionContext#state()} takes it; the
 * engine's context factory builds a context from it again, each holder in the context package restoring its own
 * part.
 *
 * <p>The rest of a context is no state: the run's id, working directory, instructions, model, provider and effort come
 * from the workflow and the run's options again, and the ports, the event sink, the control flags, the running process
 * and an open question belong to the process that runs it.
 *
 * <p>A run takes it between two top-level blocks: no question is open then, and no step index reserved for a step
 * ({@code StepIndexCounter#preAllocate}) is needed any more, because the next block reserves its own.
 *
 * @param variables           the run's variables, {@code null} values included
 * @param taint               which variables and block outputs a shell line quotes and whether the run carries
 *                            untrusted input
 * @param blockOutputs        the outputs of the named blocks, for {@code {{ block.<name>.output }}}
 * @param previousOutput      the output of the last block, {@code {{ previous_output }}}; {@code null} before the first
 * @param sourceSteps         the finished steps a compression may name as its source
 * @param nextStepIndex       the index the next step gets
 * @param runMetadata         the run's metadata, such as the gate verdict
 * @param rememberedApprovals the answers given as "Yes always", by question
 */
public record ContextState(Map<String, Object> variables, Taint taint, Map<String, String> blockOutputs,
                           String previousOutput, SourceSteps sourceSteps, int nextStepIndex,
                           Map<String, Object> runMetadata, Map<String, String> rememberedApprovals) {

    public ContextState {
        variables = copy(variables);
        taint = taint != null ? taint : new Taint(null, null, null, false, false, false);
        blockOutputs = blockOutputs == null ? Map.of() : Map.copyOf(blockOutputs);
        sourceSteps = sourceSteps != null ? sourceSteps : new SourceSteps(null, null, null);
        runMetadata = runMetadata == null ? Map.of() : Map.copyOf(runMetadata);
        rememberedApprovals = rememberedApprovals == null ? Map.of() : Map.copyOf(rememberedApprovals);
    }

    /**
     * The taint of a run (see {@code ExecutionContextTaint}).
     *
     * @param untrustedVariables    variables from an untrusted source, such as a webhook payload: quoted, and the run
     *                              carries untrusted input
     * @param platformVariables     platform data: quoted, and a taint source once it reaches a shell line
     * @param taintedOutputs        the named block outputs derived from untrusted input
     * @param previousOutputTainted whether {@code previous_output} is derived from untrusted input
     * @param platformUsedInShell   whether platform data reached a shell line
     * @param platformInPrompt      whether platform data reached the prompt of a block whose output is not recorded yet
     */
    public record Taint(Set<String> untrustedVariables, Set<String> platformVariables, Set<String> taintedOutputs,
                        boolean previousOutputTainted, boolean platformUsedInShell, boolean platformInPrompt) {

        public Taint {
            untrustedVariables = untrustedVariables == null ? Set.of() : Set.copyOf(untrustedVariables);
            platformVariables = platformVariables == null ? Set.of() : Set.copyOf(platformVariables);
            taintedOutputs = taintedOutputs == null ? Set.of() : Set.copyOf(taintedOutputs);
        }
    }

    /**
     * The finished steps a compression may name as its source (see {@code SourceStepIndex}).
     *
     * @param byName    the first step recorded under each name
     * @param ambiguous the names recorded more than once
     * @param last      the step recorded last; {@code null} before the first
     */
    public record SourceSteps(Map<String, SourceStep> byName, Set<String> ambiguous, SourceStep last) {

        public SourceSteps {
            byName = byName == null ? Map.of() : Map.copyOf(byName);
            ambiguous = ambiguous == null ? Set.of() : Set.copyOf(ambiguous);
        }
    }

    /** A read-only copy that keeps {@code null} values, which {@link Map#copyOf} refuses. */
    private static Map<String, Object> copy(Map<String, Object> map) {
        return map == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}
