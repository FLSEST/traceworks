package traceworks.core.context;

import traceworks.core.model.ContextState;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trust label per context variable. Three classes of protected values exist, which a shell line refuses, and they differ
 * in whether they make the run one that carries untrusted input:
 *
 * <ul>
 *   <li><b>external</b> ({@link ExecutionContext#setUntrustedVariable}) — a webhook payload and the
 *       like: always protected <em>and</em> a run-level taint source, so every block output of
 *       the run is protected too ({@link OutputTaint}).</li>
 *   <li><b>platform</b> ({@link ExecutionContext#setUntrustedPlatformVariable}) — {@code previous_report}
 *       and {@code open_todos}: always protected, and a taint source only <em>on use</em>. They are
 *       set in every run whose embedding system provides them, so tainting at injection time would protect every
 *       block output of such a run and change the meaning of long-standing shell commands; instead the run is tainted
 *       the moment such a placeholder is really substituted into a shell command line
 *       ({@link ExecutionContextTaint#substitutedIntoShell}).</li>
 *   <li><b>model text</b> ({@link ExecutionContext#setDerivedVariable} in a run that asked a model) — a step's
 *       output or a loop item that may hold what a model wrote: always protected, and never a reason to count
 *       the run as one with untrusted input ({@link ExecutionContextTaint}).</li>
 * </ul>
 *
 * <p>The classes are exclusive: the last write for a name wins, {@link ExecutionContext#setVariable}
 * clears both. A class of its own, so {@link ExecutionContext} stays readable.
 */
final class VariableTrust {

    private final Set<String> external = ConcurrentHashMap.newKeySet();
    private final Set<String> platform = ConcurrentHashMap.newKeySet();
    private final Set<String> model = ConcurrentHashMap.newKeySet();

    void markUntrusted(String name) {
        platform.remove(name);
        model.remove(name);
        external.add(name);
    }

    void markPlatform(String name) {
        external.remove(name);
        model.remove(name);
        platform.add(name);
    }

    /** The value may hold what a model wrote: protected, no untrusted input. */
    void markModel(String name) {
        external.remove(name);
        platform.remove(name);
        model.add(name);
    }

    /** Back to trusted — the value now comes from the platform itself (workflow default, system value). */
    void clear(String name) {
        external.remove(name);
        platform.remove(name);
        model.remove(name);
    }

    /** True for every class: a value the workflow did not write, which a shell line protects. */
    boolean isUntrusted(String name) {
        return external.contains(name) || platform.contains(name) || model.contains(name);
    }

    /** True for external input only — the class that taints the run from the moment it is set. */
    boolean isExternal(String name) {
        return external.contains(name);
    }

    /** True for a variable that may hold what a model wrote. */
    boolean isModel(String name) {
        return model.contains(name);
    }

    /** True for platform data only — the class that taints the run on use instead of on injection. */
    boolean isPlatform(String name) {
        return platform.contains(name);
    }

    /** Only external input taints a run's outputs from the start; platform data taints it on use. */
    boolean hasExternalInput() {
        return !external.isEmpty();
    }

    /** The protected names — every class. */
    Set<String> escapedNames() {
        if (model.isEmpty()) return untrustedNames();
        Set<String> all = new HashSet<>(untrustedNames());
        all.addAll(model);
        return Set.copyOf(all);
    }

    /** Names of values from outside the run — external input and platform data, not model text. */
    Set<String> untrustedNames() {
        if (platform.isEmpty()) return Set.copyOf(external);
        Set<String> all = new HashSet<>(external);
        all.addAll(platform);
        return Set.copyOf(all);
    }

    /** Names that are persisted as run taint ({@code __untrusted_keys}) — external only. */
    Set<String> externalNames() {
        return Set.copyOf(external);
    }

    /** Names of platform data — part of the run's state ({@link ContextState.Taint}). */
    Set<String> platformNames() {
        return Set.copyOf(platform);
    }

    /** Names of the variables that may hold model text — part of the run's state ({@link ContextState.Taint}). */
    Set<String> modelNames() {
        return Set.copyOf(model);
    }

    /** Replaces every class with those of a run's state ({@link ExecutionContextTaint#restore}). */
    void restore(Set<String> externalNames, Set<String> platformNames, Set<String> modelNames) {
        external.clear();
        external.addAll(externalNames);
        platform.clear();
        platform.addAll(platformNames);
        model.clear();
        model.addAll(modelNames);
    }
}
