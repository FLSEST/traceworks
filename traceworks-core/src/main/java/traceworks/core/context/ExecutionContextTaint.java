package traceworks.core.context;

import traceworks.core.model.ContextState;


import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The taint bookkeeping of one run, in one place: the trust label per variable ({@link VariableTrust}), the taint over
 * block outputs ({@link OutputTaint}) and the moment platform data first reaches a shell command line.
 * {@link ExecutionContext} only delegates — a class of its own, so the context stays readable.
 *
 * <p><b>Platform data becomes a taint source on use, not on injection.</b> Platform data is what a run receives from
 * outside the workflow without it being an input: the output a chain hands over ({@code previous_chain_output}) and
 * what a register gives an audit workflow ({@code previous_report}, {@code audit_findings}). Treating it as untrusted
 * input from the start would protect every block output of every run that receives it and refuse
 * long-standing shell commands. It is therefore always protected itself, but it only taints the run once
 * {@link TemplateResolver} reports back that such a placeholder was really handed to a shell, as the value of a variable
 * ({@link #substitutedIntoShell}). From that moment the run behaves exactly like one carrying external untrusted input:
 * block outputs and derived variables (foreach items, {@code step_N_output}) are protected too, so platform bytes cannot
 * re-enter a command line raw.
 *
 * <p><b>Platform data in an AI prompt colours that block's output, not the run.</b> The laundering
 * path platform data → AI prompt → shell block used to bypass the protection entirely: the prompt path
 * reported nothing and {@link ExecutionContext#recordOutput} filed the answer as untrusted-free.
 * Now {@link TemplateResolver#resolvePrompt} reports its substitutions too
 * ({@link #substitutedIntoPrompt}); the flag is consumed by the next recorded output
 * ({@link #consumePlatformPrompt}), which is therefore protected in later shell command lines. The run
 * itself stays clean until an output is really recorded — a prompt alone changes nothing.
 *
 * <p><b>Text a model wrote is protected, and is no untrusted input.</b> A model reads the workspace, which may hold
 * text that steers its answer, so its answer never reaches a shell line as code. From the moment a run asks a model
 * ({@link #markModelAsked}, set where a block resolves its provider, and for a child workflow that asked one) every
 * output recorded and every variable derived from one (a {@code step_N_output}, a loop item, an answer an AI actor
 * gave) is kept as model text, and {@link #snapshot} names it next to the untrusted values, so no shell line
 * takes it as code. It stays for the rest of the run, as untrusted input does and for the same reason: a later shell step
 * can print what the model wrote, or read a file the model wrote, without naming a placeholder. What was recorded
 * before the first call stays as it was, and so does a run that asks no model. Model text is not untrusted input:
 * {@link #hasUntrustedInput} does not count it, so an approval's {@code actor} and {@code mode} read as before. It
 * travels in the checkpoint ({@link #state}) and, to a child workflow, in reserved keys ({@link #export}).
 *
 * <p>The platform flag itself is exported as {@link ExecutionContext#PLATFORM_IN_SHELL_VARIABLE}, so a segment resumed before
 * any output was recorded starts tainted instead of clean — {@code ExecutionContextFactory} re-raises it via
 * {@link ExecutionContext#markPlatformUsedInShell()}.
 */
final class ExecutionContextTaint {

    private final VariableTrust variables = new VariableTrust();
    private final OutputTaint outputs = new OutputTaint();
    private final AtomicBoolean platformUsedInShell = new AtomicBoolean(false);
    private final AtomicBoolean platformInPrompt = new AtomicBoolean(false);
    /** The outputs recorded since the run asked a model; kept apart, because they are no untrusted input. */
    private final OutputTaint modelOutputs = new OutputTaint();
    private final AtomicBoolean modelAsked = new AtomicBoolean(false);

    /** Back to trusted — the value now comes from the platform itself (workflow default, system value). */
    void markTrusted(String name) {
        variables.clear(name);
    }

    void markUntrusted(String name) {
        variables.markUntrusted(name);
    }

    void markPlatform(String name) {
        variables.markPlatform(name);
    }

    boolean isUntrusted(String name) {
        return variables.isUntrusted(name);
    }

    /** The run asks a model now, or a child workflow of it did: what it records from here on is model text. */
    void markModelAsked() {
        modelAsked.set(true);
    }

    /** Whether the run asked a model, so that its outputs may hold text a model wrote. */
    boolean modelAsked() {
        return modelAsked.get();
    }

    /** A variable derived from an output of a run that asked a model. */
    void markModelVariable(String name) {
        variables.markModel(name);
    }

    /** The output just written by a run that asked a model: {@code previous_output}, plus the block name if named. */
    void markModelOutput(String blockName) {
        modelOutputs.mark(blockName);
    }

    /** Re-applies one exported name of an output that is model text ({@code previous_output} or a block name). */
    void markModelOutputName(String name) {
        if (ExecutionContext.PREVIOUS_OUTPUT_NAME.equals(name)) modelOutputs.markPreviousOutput();
        else modelOutputs.markBlockOutput(name);
    }

    /** What a value built from a template inherits from the placeholders that went into it, weakest first. */
    enum Inherited { NOTHING, MODEL_TEXT, PLATFORM_DATA, EXTERNAL_INPUT }

    /**
     * The strongest mark among the placeholders of {@code template}: external input (an untrusted variable, a
     * tainted output), then platform data, then model text (a variable or an output that may hold what a model
     * wrote). A value built from the template is protected as that part of it is, whatever the state of the run.
     */
    Inherited inheritedBy(String template) {
        Inherited strongest = Inherited.NOTHING;
        if (template == null) return strongest;
        for (String key : PlaceholderSyntax.keysIn(template)) {
            Inherited of = markOf(key);
            if (of.compareTo(strongest) > 0) strongest = of;
        }
        return strongest;
    }

    /** The mark of the value one placeholder key names. */
    private Inherited markOf(String key) {
        return variables.isExternal(key) || outputs.names(key) ? Inherited.EXTERNAL_INPUT
                : variables.isPlatform(key) ? Inherited.PLATFORM_DATA
                : variables.isModel(key) || modelOutputs.names(key) ? Inherited.MODEL_TEXT
                : Inherited.NOTHING;
    }

    /**
     * The strongest mark among the values that went into the block that runs now: every placeholder a template of
     * it resolved, for any purpose, and what a child workflow it ran handed back ({@link #wentIntoTheBlock}). The
     * output the block records is protected as that ({@link ExecutionContext#recordOutput}); the engine clears it
     * when the block's step ends ({@link #blockEnded}).
     */
    private final java.util.concurrent.atomic.AtomicReference<Inherited> intoTheBlock =
            new java.util.concurrent.atomic.AtomicReference<>(Inherited.NOTHING);

    /** Callback of every resolution: the value of this placeholder key went into the block that runs now. */
    void resolved(String key) {
        wentIntoTheBlock(markOf(key));
    }

    /** A value with this mark went into the block that runs now. */
    void wentIntoTheBlock(Inherited mark) {
        if (mark != Inherited.NOTHING) {
            intoTheBlock.accumulateAndGet(mark, (held, more) -> more.compareTo(held) > 0 ? more : held);
        }
    }

    /** The strongest mark of what went into the block that runs now. */
    Inherited intoTheBlock() {
        return intoTheBlock.get();
    }

    /** The block's step ended: the next block starts with nothing. */
    void blockEnded() {
        intoTheBlock.set(Inherited.NOTHING);
    }

    /** The names of the platform data: the register's and what a step derived from it. */
    Set<String> platformVariables() {
        return variables.platformNames();
    }

    /** Names persisted as run taint ({@code __untrusted_keys}) — external input only. */
    Set<String> untrustedVariables() {
        return variables.externalNames();
    }

    /** True as soon as the run carries untrusted external input, used platform data in a shell path, or holds a tainted output. */
    boolean hasUntrustedInput() {
        return variables.hasExternalInput() || platformUsedInShell.get() || !outputs.isEmpty();
    }

    void markOutput(String blockName) {
        outputs.mark(blockName);
    }

    void markPreviousOutput() {
        outputs.markPreviousOutput();
    }

    void markBlockOutput(String blockName) {
        outputs.markBlockOutput(blockName);
    }

    /**
     * Snapshot for {@link TemplateResolver#shellLine} — the protected variable names plus the protected outputs,
     * the tainted ones and those that are model text.
     */
    TemplateResolver.Taint snapshot() {
        Set<String> escaped = variables.escapedNames();
        if (modelOutputs.isEmpty()) return outputs.snapshot(escaped);
        Set<String> names = new HashSet<>(outputs.outputNames());
        names.addAll(modelOutputs.outputNames());
        return new TemplateResolver.Taint(escaped, Set.copyOf(names),
                outputs.isPreviousOutputTainted() || modelOutputs.isPreviousOutputTainted());
    }

    /** Taint as reserved input-context keys for persistence and hand-over; empty for a clean run. */
    Map<String, Object> export() {
        Map<String, Object> exported = outputs.export(variables.externalNames());
        Set<String> modelNames = variables.modelNames();
        if (!platformUsedInShell.get() && !modelAsked.get() && modelNames.isEmpty()) return exported;
        Map<String, Object> withFlags = new HashMap<>(exported);
        if (platformUsedInShell.get()) withFlags.put(ExecutionContext.PLATFORM_IN_SHELL_VARIABLE, true);
        if (!modelNames.isEmpty() && !modelAsked.get()) {
            // A variable built from model text a parent handed over, in a run that asked no model itself
            withFlags.put(ExecutionContext.MODEL_KEYS_VARIABLE, modelNames.stream().sorted().toList());
        }
        if (modelAsked.get()) {
            withFlags.put(ExecutionContext.MODEL_ASKED_VARIABLE, true);
            withFlags.put(ExecutionContext.MODEL_KEYS_VARIABLE, modelNames.stream().sorted().toList());
            Set<String> names = new TreeSet<>(modelOutputs.outputNames());
            if (modelOutputs.isPreviousOutputTainted()) names.add(ExecutionContext.PREVIOUS_OUTPUT_NAME);
            withFlags.put(ExecutionContext.MODEL_OUTPUTS_VARIABLE, List.copyOf(names));
        }
        return withFlags;
    }

    /** Re-applies an exported {@link ExecutionContext#PLATFORM_IN_SHELL_VARIABLE} flag (resume, sub-workflow hand-over). */
    void markPlatformUsedInShell() {
        platformUsedInShell.set(true);
    }

    /** The whole taint, for a checkpoint: unlike {@link #export}, it keeps the platform data and both flags. */
    ContextState.Taint state() {
        return new ContextState.Taint(variables.externalNames(), variables.platformNames(), outputs.outputNames(),
                outputs.isPreviousOutputTainted(), platformUsedInShell.get(),
                // A block parked in its middle keeps that something protected went into it: its output is tainted
                platformInPrompt.get() || intoTheBlock.get() != Inherited.NOTHING,
                new ContextState.ModelText(modelAsked.get(), variables.modelNames(), modelOutputs.outputNames(),
                        modelOutputs.isPreviousOutputTainted()));
    }

    /** Replaces the whole taint with the one of a checkpoint. */
    void restore(ContextState.Taint state) {
        ContextState.ModelText model = state.modelText();
        variables.restore(state.untrustedVariables(), state.platformVariables(), model.variables());
        outputs.restore(state.taintedOutputs(), state.previousOutputTainted());
        modelOutputs.restore(model.outputs(), model.previousOutput());
        modelAsked.set(model.asked());
        platformUsedInShell.set(state.platformUsedInShell());
        platformInPrompt.set(state.platformInPrompt());
    }

    /**
     * Callback from {@link TemplateResolver}: this placeholder key was substituted into a shell
     * command line. Only platform data changes state here — external input already taints the run
     * from the moment it is set, and trusted values never do.
     */
    void substitutedIntoShell(String key) {
        if (variables.isPlatform(key)) platformUsedInShell.set(true);
    }

    /**
     * Callback from {@link TemplateResolver#resolvePrompt}: this placeholder key was substituted into
     * an AI prompt. Platform data here does <em>not</em> taint the run — a prompt is no command line —
     * it only marks the output of the block that consumed it.
     */
    void substitutedIntoPrompt(String key) {
        if (variables.isPlatform(key)) platformInPrompt.set(true);
    }

    /**
     * Reads and clears the prompt flag: the block that is recording its result now is the one whose
     * prompt carried platform data, so its output is recorded as untrusted. Clearing keeps the flag
     * scoped to that block — a later block with a clean prompt records a clean output again (unless
     * the run is tainted by then, which is sticky by design).
     *
     * <p>Deliberately <em>not</em> in {@link #export}, unlike {@link ExecutionContext#PLATFORM_IN_SHELL_VARIABLE}:
     * a block whose prompt set this flag but whose output was never recorded is executed again from the top — it
     * resolves its prompt once more and raises the flag again. A checkpoint ({@link #state}) keeps it anyway, so a
     * resumed run starts with exactly the taint the run stopped with.
     */
    boolean consumePlatformPrompt() {
        return platformInPrompt.getAndSet(false);
    }
}
