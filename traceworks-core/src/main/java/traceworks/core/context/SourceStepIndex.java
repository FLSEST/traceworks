package traceworks.core.context;

import traceworks.core.model.ContextState;
import traceworks.core.model.SourceStep;
import traceworks.core.model.StepResult;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Name → facts of the finished steps of a run, for compression sources. Written by
 * {@code BlockStepRunner} next to {@code recordOutput} and rebuilt on resume. Nested steps are in
 * here exactly as far as their output is addressable as {{ block.&lt;name&gt;.output }}: a named
 * switch case block records itself too, so a name a user can write as a placeholder is
 * never rejected as an unknown compression source. A name recorded twice is ambiguous (duplicate
 * block names in one workflow).
 */
public final class SourceStepIndex {

    private final Map<String, SourceStep> byName = new ConcurrentHashMap<>();
    private final Set<String> ambiguous = ConcurrentHashMap.newKeySet();
    private final AtomicReference<SourceStep> last = new AtomicReference<>();

    public void record(String blockName, StepResult step) {
        String name = blockName == null || blockName.isBlank() ? null : blockName;
        SourceStep entry = SourceStep.of(name, step);
        last.set(entry);
        if (name != null && byName.putIfAbsent(name, entry) != null) {
            ambiguous.add(name);
        }
    }

    public Optional<SourceStep> find(String name) {
        return Optional.ofNullable(name == null ? null : byName.get(name));
    }

    public boolean isAmbiguous(String name) {
        return ambiguous.contains(name);
    }

    public Optional<SourceStep> last() {
        return Optional.ofNullable(last.get());
    }

    /** The index as part of the run's state ({@link ContextState}). */
    ContextState.SourceSteps state() {
        return new ContextState.SourceSteps(Map.copyOf(byName), Set.copyOf(ambiguous), last.get());
    }

    /** Replaces the index with the one of a run's state. */
    void restore(ContextState.SourceSteps state) {
        byName.clear();
        byName.putAll(state.byName());
        ambiguous.clear();
        ambiguous.addAll(state.ambiguous());
        last.set(state.last());
    }
}
