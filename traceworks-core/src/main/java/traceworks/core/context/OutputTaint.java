package traceworks.core.context;

import traceworks.core.model.ContextState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Run-level taint over block outputs: which named block outputs and whether {@code previous_output}
 * carry data derived from untrusted input. Owned by {@link ExecutionContext}, which marks an output
 * whenever it is written while the run already holds untrusted input — taint is sticky for the rest
 * of the run, because a block can smuggle attacker-controlled bytes into its output without ever
 * naming a placeholder (reading a repo file, calling an API).
 *
 * <p>A class of its own, so {@link ExecutionContext} stays readable.
 */
final class OutputTaint {

    private final Set<String> outputs = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean previousOutput = new AtomicBoolean(false);

    /** Marks the output just written: always {@code previous_output}, plus the block name if named. */
    void mark(String blockName) {
        previousOutput.set(true);
        markBlockOutput(blockName);
    }

    void markPreviousOutput() {
        previousOutput.set(true);
    }

    void markBlockOutput(String blockName) {
        if (blockName != null && !blockName.isBlank()) outputs.add(blockName);
    }

    /** Whether the placeholder key names an output of this taint: {@code previous_output} or a named block's. */
    boolean names(String key) {
        if (ExecutionContext.PREVIOUS_OUTPUT_NAME.equals(key)) return previousOutput.get();
        return key.startsWith("block.") && key.endsWith(".output")
                && outputs.contains(key.substring("block.".length(), key.length() - ".output".length()));
    }

    boolean isEmpty() {
        return outputs.isEmpty() && !previousOutput.get();
    }

    /** The tainted named block outputs — part of the run's state ({@link ContextState.Taint}). */
    Set<String> outputNames() {
        return Set.copyOf(outputs);
    }

    boolean isPreviousOutputTainted() {
        return previousOutput.get();
    }

    /** Replaces the taint with that of a run's state ({@link ExecutionContextTaint#restore}). */
    void restore(Set<String> outputNames, boolean previousOutputTainted) {
        outputs.clear();
        outputs.addAll(outputNames);
        previousOutput.set(previousOutputTainted);
    }

    /** Snapshot for {@link TemplateResolver#resolveShell} — untrusted variables plus tainted outputs. */
    TemplateResolver.Taint snapshot(Set<String> untrustedVariables) {
        if (untrustedVariables.isEmpty() && isEmpty()) return TemplateResolver.Taint.NONE;
        return new TemplateResolver.Taint(Set.copyOf(untrustedVariables), Set.copyOf(outputs), previousOutput.get());
    }

    /**
     * Taint as reserved input-context keys, so it survives step-delta persistence, resume and
     * sub-workflow hand-over. Empty for a clean run — those stay byte-identical to before.
     */
    Map<String, Object> export(Set<String> untrustedVariables) {
        if (untrustedVariables.isEmpty() && isEmpty()) return Map.of();
        Map<String, Object> exported = new HashMap<>();
        exported.put(ExecutionContext.UNTRUSTED_KEYS_VARIABLE, untrustedVariables.stream().sorted().toList());
        Set<String> names = new TreeSet<>(outputs);
        if (previousOutput.get()) names.add(ExecutionContext.PREVIOUS_OUTPUT_NAME);
        exported.put(ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE, List.copyOf(names));
        return exported;
    }
}
