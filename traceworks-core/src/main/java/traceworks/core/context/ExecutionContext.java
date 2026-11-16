package traceworks.core.context;

import traceworks.core.container.RunPaths;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.ContextState;
import traceworks.core.port.AiProviders;
import traceworks.core.port.Clock;
import traceworks.core.port.CommandRunner;
import traceworks.core.port.RunEvents;
import traceworks.core.port.WorkflowResolver;
import traceworks.core.util.ProcessTracking;
import traceworks.core.util.SecretReplacer;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class ExecutionContext {

    private final String containerId;
    private final String workflowRunId;

    private String projectPath;

    /** Where the run's containers see its directories; {@code null} for a run without them (host paths only). */
    private RunPaths runPaths;

    /** taint-neutral - use {@link #recordOutput} for block results, {@link #setDerivedVariable} for variables derived from output; only for resume seeding/tests. */
    private String previousOutput;

    private String instructionPrefix;

    private String model;

    private String provider;

    /** Workflow-level reasoning-effort (low/medium/high/xhigh); null = provider default. */
    private String effort;

    private final Map<String, Object> variables;

    /**
     * Reserved input-context key: a list of variable names that were supplied by an untrusted
     * external source (e.g. a webhook payload). Set by the trigger, read by the context factory,
     * persisted with the run's input context so it survives resume and sub-workflow hand-over.
     */
    public static final String UNTRUSTED_KEYS_VARIABLE = "__untrusted_keys";

    /**
     * Reserved input-context key: names of block outputs derived from untrusted input, plus the
     * pseudo-name {@link #PREVIOUS_OUTPUT_NAME}. Handed to a child run in its input context
     * ({@link #exportTaint}), so the taint survives the sub-workflow hand-over; a resumed run gets
     * its taint back from its checkpoint.
     */
    public static final String UNTRUSTED_OUTPUTS_VARIABLE = "__untrusted_outputs";

    /** Pseudo block name for the run's {@code previous_output} inside {@link #UNTRUSTED_OUTPUTS_VARIABLE}. */
    public static final String PREVIOUS_OUTPUT_NAME = "previous_output";

    /** Reserved input-context key: {@code true} once platform data reached a shell command line, so that taint survives resume and hand-over even before an output is recorded. */
    public static final String PLATFORM_IN_SHELL_VARIABLE = "__platform_in_shell";

    /** Reserved input-context key: {@code true} once the run asked a model, so a child workflow records its outputs as model text too. */
    public static final String MODEL_ASKED_VARIABLE = "__model_asked";

    /** Reserved input-context key: names of the variables that may hold text a model wrote, handed to a child run. */
    public static final String MODEL_KEYS_VARIABLE = "__model_keys";

    /**
     * Reserved input-context key: the names of the variables a parent run hands over that it built from platform
     * data (a foreach {@code context} entry that names {@code audit_findings}, say). The child holds them as
     * platform data too. The register's own data is not among them: a child never takes it from its input.
     */
    public static final String PLATFORM_KEYS_VARIABLE = "__platform_keys";

    /** Reserved input-context key: names of the block outputs that may hold text a model wrote, plus {@link #PREVIOUS_OUTPUT_NAME}. */
    public static final String MODEL_OUTPUTS_VARIABLE = "__model_outputs";

    /** The step index of no step: {@link #parkQuestionOf} with it lets no question park the run. */
    public static final int NO_STEP = -1;

    /** Trust label per variable plus the run's output taint. See {@link ExecutionContextTaint}. */
    private final ExecutionContextTaint taint = new ExecutionContextTaint();

    /** Named block outputs keyed by block name. Resolved via {{ block.<name>.output }}. */
    private final Map<String, String> blockOutputs;

    private final SourceStepIndex sourceSteps; // compress sources, see SourceStepIndex

    private final StepIndexCounter stepIndices;

    private final AtomicBoolean paused;

    private final AtomicBoolean cancelled;

    /** Open promise, running question and remembered answers. Only via the methods below - a
     * public getter would make the state reachable around the gate. See {@link ApprovalGate}. */
    private final ApprovalGate approvals;
    /** What a resumed loop takes over ({@link #recordedItems}); read and written on the run's thread only. */
    private RecordedItems recordedItems = RecordedItems.NONE;
    /** The step whose loop a pause may park between two items; read and written on the run's thread only. */
    private int pauseParksItemsOf = NO_STEP;
    /** The step inside which a pause parked the run; read and written on the run's thread only. */
    private int pausedInStep = NO_STEP;

    private final Map<String, Object> runMetadata;

    /** How much output text the run may hold in memory, shared with its child runs; see {@link OutputBudget}. */
    private final OutputBudget outputBudget;

    /**
     * What the values of the variables the run's steps set take against the {@link #outputBudget}, by name;
     * {@code null} until {@link #countVariables}.
     */
    private Map<String, Long> countedVariables;

    /** The names whose values never count: the steps' outputs, which count as the steps' own text. */
    private Predicate<String> neverCounted = name -> true;

    /** The stop handles of what the run's steps are running; see {@link #processTracker()}. */
    private final RunProcessRegistration processes = new RunProcessRegistration();

    /** Where the run's events go; blocks that report something themselves (approvals, foreach items) emit here. */
    private final RunEvents events;

    /** The sink of a context built without one: it drops every event. */
    private static final RunEvents NO_EVENTS = event -> {
    };

    /**
     * The run's ports the block executors use: the shell blocks run their commands through {@link #commandRunner},
     * the AI blocks call models through {@link #aiProviders}, and step timestamps come from {@link #clock}. The
     * engine hands over the ones of the run's {@code RunOptions}.
     */
    private final CommandRunner commandRunner;
    private final AiProviders aiProviders;
    private final Clock clock;

    /**
     * The values of the run's secrets by name, read-only: a {@code shell} step passes the ones it declares to its
     * command as environment variables. They are no variable, so no placeholder resolves them, and {@link #state()}
     * does not hold them, so no checkpoint does.
     */
    private final Map<String, String> secrets;
    /** Replaces those values in a text, as the record does. */
    private final SecretReplacer secretReplacer;

    /** The runner of a context built without one: it refuses every command. */
    private static final CommandRunner NO_COMMANDS = request -> {
        throw new IllegalStateException("No CommandRunner configured for this run");
    };

    /** The providers of a context built without them: none is configured, so every AI call is refused. */
    private static final AiProviders NO_AI_PROVIDERS = AiProviders.of(null, List.of());

    /**
     * The other workflows a {@code sub-workflow} or {@code foreach} block refers to, and how the run starts them as
     * children. The engine hands over the run's resolver and its own child runs.
     */
    private final WorkflowResolver workflowResolver;
    private final ChildRuns childRuns;

    /** The resolver of a context built without one: it refuses every reference. */
    private static final WorkflowResolver NO_WORKFLOWS = ref -> {
        throw new IllegalArgumentException("No WorkflowResolver configured for this run; cannot resolve '" + ref + "'");
    };

    /** The child runs of a context built without them: every child is refused. */
    private static final ChildRuns NO_CHILD_RUNS = (definition, inputs) -> {
        throw new IllegalStateException("No child runs configured for this run");
    };

    private ExecutionContext(Builder b) {
        this.containerId = b.containerId;
        this.workflowRunId = b.workflowRunId;
        this.projectPath = b.projectPath;
        this.runPaths = b.runPaths;
        this.previousOutput = b.previousOutput;
        this.instructionPrefix = b.instructionPrefix;
        this.model = b.model;
        this.provider = b.provider;
        this.effort = b.effort;
        this.variables = b.variables != null ? b.variables : new HashMap<>();
        this.blockOutputs = b.blockOutputs != null ? b.blockOutputs : new ConcurrentHashMap<>();
        this.sourceSteps = b.sourceSteps != null ? b.sourceSteps : new SourceStepIndex();
        this.stepIndices = b.stepIndices != null ? b.stepIndices : new StepIndexCounter();
        this.paused = b.paused != null ? b.paused : new AtomicBoolean(false);
        this.cancelled = b.cancelled != null ? b.cancelled : new AtomicBoolean(false);
        this.approvals = b.approvals != null ? b.approvals : new ApprovalGate();
        this.runMetadata = b.runMetadata != null ? b.runMetadata : new ConcurrentHashMap<>();
        this.outputBudget = b.outputBudget != null ? b.outputBudget : OutputBudget.none();
        this.events = b.events != null ? b.events : NO_EVENTS;
        this.commandRunner = b.commandRunner != null ? b.commandRunner : NO_COMMANDS;
        this.aiProviders = b.aiProviders != null ? b.aiProviders : NO_AI_PROVIDERS;
        this.clock = b.clock != null ? b.clock : Instant::now;
        this.secrets = b.secrets != null ? Map.copyOf(b.secrets) : Map.of();
        this.secretReplacer = SecretReplacer.ofKeys(this.secrets);
        this.workflowResolver = b.workflowResolver != null ? b.workflowResolver : NO_WORKFLOWS;
        this.childRuns = b.childRuns != null ? b.childRuns : NO_CHILD_RUNS;
        if (b.state != null) {
            restore(b.state);
        }
    }

    /** Puts the run back into {@code state}; each holder restores its own part. */
    private void restore(ContextState state) {
        variables.putAll(state.variables());
        taint.restore(state.taint());
        blockOutputs.putAll(state.blockOutputs());
        previousOutput = state.previousOutput();
        sourceSteps.restore(state.sourceSteps());
        stepIndices.restore(state.nextStepIndex());
        runMetadata.putAll(state.runMetadata());
        approvals.restore(state.rememberedApprovals());
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getContainerId() { return containerId; }
    public String getWorkflowRunId() { return workflowRunId; }
    public String getProjectPath() { return projectPath; }
    /** Where the run's containers see the workspace, the package and the scratch directory; may be {@code null}. */
    public RunPaths getRunPaths() { return runPaths; }

    /**
     * Whether this is a container run, in which every command runs in a container and a step without
     * {@code container_image} runs in the {@code shell} image; otherwise (a host run, or a run without
     * {@link RunPaths}) such a step runs on the host.
     */
    public boolean isContainerRun() { return runPaths != null && runPaths.fixedContainerPaths(); }
    public String getPreviousOutput() { return previousOutput; }
    public String getInstructionPrefix() { return instructionPrefix; }
    public String getModel() { return model; }
    public String getProvider() { return provider; }
    public String getEffort() { return effort; }
    public Map<String, Object> getVariables() { return variables; }
    public ExecutionContextTaint getTaint() { return taint; }
    public Map<String, String> getBlockOutputs() { return blockOutputs; }
    public SourceStepIndex getSourceSteps() { return sourceSteps; }
    public StepIndexCounter getStepIndices() { return stepIndices; }
    public AtomicBoolean getPaused() { return paused; }
    public AtomicBoolean getCancelled() { return cancelled; }
    public Map<String, Object> getRunMetadata() { return runMetadata; }
    /** How much output text the run may hold in memory; a context built without one has no budget. */
    public OutputBudget getOutputBudget() { return outputBudget; }
    /** The run's event sink; a context built without one drops every event. */
    public RunEvents getEvents() { return events; }
    /** Runs the commands of the run's shell blocks; a context built without one refuses every command. */
    public CommandRunner getCommandRunner() { return commandRunner; }
    /** The run's AI providers; a context built without them has none, so every AI call is refused. */
    public AiProviders getAiProviders() { return aiProviders; }
    /** The time of the run's step timestamps; a context built without one reads the system clock. */
    public Clock getClock() { return clock; }
    /** The values of the run's secrets by name, read-only; a context built without them has none. */
    public Map<String, String> getSecrets() { return secrets; }
    /**
     * The values of the run's secrets, read-only: what a cut of the run's text must keep whole
     * ({@link traceworks.core.util.TruncationUtil}), so the record can redact them.
     */
    public java.util.Collection<String> secretValues() { return secrets.values(); }
    /**
     * Replaces the values of the run's secrets in a text by {@code [redacted:<NAME>]}, as the record does. For a
     * step that rewrites text before the record sees it (the cleaning of a tool's text): a value that the rewriting
     * changed would no longer be found, so the step replaces first.
     */
    public SecretReplacer secretReplacer() { return secretReplacer; }
    /** Finds the workflows a block refers to; a context built without one refuses every reference. */
    public WorkflowResolver getWorkflowResolver() { return workflowResolver; }
    /** Starts workflows as children of this run; a context built without them refuses every child. */
    public ChildRuns getChildRuns() { return childRuns; }

    public void setProjectPath(String projectPath) { this.projectPath = projectPath; }
    public void setRunPaths(RunPaths runPaths) { this.runPaths = runPaths; }
    public void setPreviousOutput(String previousOutput) { this.previousOutput = previousOutput; }
    public void setInstructionPrefix(String instructionPrefix) { this.instructionPrefix = instructionPrefix; }
    public void setModel(String model) { this.model = model; }
    public void setProvider(String provider) { this.provider = provider; }
    public void setEffort(String effort) { this.effort = effort; }

    /** Reserves the next step index for a RUNNING placeholder; see {@link StepIndexCounter}. */
    public int preAllocateStepIndex() {
        return stepIndices.preAllocate();
    }

    public int nextStepIndex() {
        return stepIndices.next();
    }

    /** Resume: starts this segment's step indices above the ones already persisted. */
    public void startStepIndicesAt(int firstIndex) {
        stepIndices.startAt(firstIndex);
    }

    public void setRunMetadata(String key, Object value) { runMetadata.put(key, value); }

    /**
     * What the run has changed in this context so far, as a copy: the state a checkpoint keeps. A context built with
     * it ({@link Builder#state}) continues the run as this one would.
     */
    public ContextState state() {
        return new ContextState(variables, taint.state(), blockOutputs, previousOutput, sourceSteps.state(),
                stepIndices.state(), runMetadata, approvals.state());
    }

    /** Sets a trusted variable (workflow defaults, system values, block-internal values). Clears any taint. */
    public void setVariable(String name, Object value) {
        put(name, value);
        taint.markTrusted(name);
    }

    /** Sets a variable that came from an untrusted external source (webhook payload etc.). */
    public void setUntrustedVariable(String name, Object value) {
        put(name, value);
        taint.markUntrusted(name);
    }

    /** Platform data the embedding system provides, when it does ({@code previous_report}, {@code open_todos}): always shell-escaped, and a run-level taint source only once it is really substituted into a shell command line. See {@link ExecutionContextTaint}. */
    public void setUntrustedPlatformVariable(String name, Object value) {
        put(name, value);
        taint.markPlatform(name);
    }

    /**
     * Sets a variable derived from block output ({@code step_N_output}, foreach items): untrusted iff tainted, model
     * text in a run that asked a model, trusted otherwise.
     */
    public void setDerivedVariable(String name, Object value) {
        if (hasUntrustedInput()) setUntrustedVariable(name, value);
        else if (taint.modelAsked()) setModelVariable(name, value);
        else setVariable(name, value);
    }

    /**
     * Sets a variable to a value that was built from {@code template}: it is protected when any placeholder that
     * went into it is, and as that one is, whatever the state of the run. Untrusted input or a tainted output makes
     * it untrusted input, platform data makes it platform data (a shell line refuses it, and it taints the run when
     * it reaches a shell as a value), model text makes it model text. With no protected part it is a derived
     * variable ({@link #setDerivedVariable}).
     */
    public void setTemplateVariable(String name, Object value, String template) {
        switch (taint.inheritedBy(template)) {
            case EXTERNAL_INPUT -> setUntrustedVariable(name, value);
            case PLATFORM_DATA -> setUntrustedPlatformVariable(name, value);
            case MODEL_TEXT -> setModelVariable(name, value);
            case NOTHING -> setDerivedVariable(name, value);
        }
    }

    /** The names of the variables that are platform data: the register's, and what a step built from one. */
    public Set<String> platformVariableNames() { return taint.platformVariables(); }

    /** Sets a variable that may hold text a model wrote: a shell line protects it, the run stays without untrusted input. */
    public void setModelVariable(String name, Object value) {
        put(name, value);
        taint.markModelVariable(name);
    }

    /**
     * The run asks a model now, or a child workflow of it did. Every output recorded from here on, and every
     * variable derived from one, may hold text a model wrote: a shell line protects it. See {@link ExecutionContextTaint}.
     */
    public void markModelAsked() { taint.markModelAsked(); }

    /** Whether the run asked a model, itself or through a child workflow. */
    public boolean modelAsked() { return taint.modelAsked(); }

    /** Re-applies one exported name of an output that is model text ({@link #PREVIOUS_OUTPUT_NAME} or a block name). */
    public void markOutputModelText(String name) { taint.markModelOutputName(name); }

    /** Replaces a variable's value and leaves its trust label untouched (workspace path remapping). */
    public void replaceValue(String name, Object value) {
        put(name, value);
    }

    /** Removes a variable, such as the field of a {@code foreach} item that the next item does not have. */
    public void removeVariable(String name) {
        variables.remove(name);
        count(name, null);
    }

    /**
     * Counts the values of the run's variables against its {@link OutputBudget} from now on: a value a step sets
     * replaces the one counted for its name, and a removed one is given back; the values of the names
     * {@code neverCounted} matches (the steps' outputs, which count as the steps' own text) never count. Of the values
     * set already, the inputs and the run variables, only those {@code heldNow} matches count, at once and without a
     * check: the variables the steps of a resumed run set before. A value the budget cannot take is set all the same:
     * the budget is exceeded then, and the run ends after its current step.
     */
    public void countVariables(Predicate<String> neverCounted, Predicate<String> heldNow) {
        this.neverCounted = neverCounted;
        countedVariables = new HashMap<>();
        long held = 0;
        for (Map.Entry<String, Object> variable : variables.entrySet()) {
            if (!neverCounted.test(variable.getKey()) && heldNow.test(variable.getKey())) {
                long size = OutputBudget.sizeOf(variable.getValue());
                countedVariables.put(variable.getKey(), size);
                held += size;
            }
        }
        outputBudget.hold(held);
    }

    private void put(String name, Object value) {
        variables.put(name, value);
        count(name, value);
    }

    /** Gives back what the variable's old value took and takes what its new one takes, once the run counts them. */
    private void count(String name, Object value) {
        if (countedVariables == null || neverCounted.test(name)) {
            return;
        }
        Long before = countedVariables.remove(name);
        if (before != null) {
            outputBudget.release(before);
        }
        long size = OutputBudget.sizeOf(value);
        if (size > 0 && outputBudget.take(size)) {
            countedVariables.put(name, size);
        }
    }

    public boolean isUntrusted(String name) { return taint.isUntrusted(name); }

    /** True as soon as the run carries untrusted input — an external variable, a tainted output, or platform data that reached a shell command line. */
    public boolean hasUntrustedInput() {
        return taint.hasUntrustedInput();
    }

    /**
     * The single write path for a block result: {@code previous_output} plus, for a named block,
     * {{ block.&lt;name&gt;.output }}. Untrusted iff the run already carries untrusted input (taint is
     * sticky for the rest of the run) or this block's prompt carried platform data; model text, which a shell line
     * quotes just the same, in a run that asked a model.
     */
    public void recordOutput(String blockName, String output) {
        ExecutionContextTaint.Inherited into = taint.intoTheBlock();
        boolean tainted = taint.consumePlatformPrompt() | hasUntrustedInput() // | : always consume the flag
                | into.compareTo(ExecutionContextTaint.Inherited.PLATFORM_DATA) >= 0;
        if (into == ExecutionContextTaint.Inherited.MODEL_TEXT) {
            // Model text a parent handed over went into the block: from here on this run holds model text
            taint.markModelAsked();
        }
        setPreviousOutput(output);
        setBlockOutput(blockName, output);
        if (tainted) taint.markOutput(blockName);
        else if (taint.modelAsked()) taint.markModelOutput(blockName);
    }

    /**
     * Sets the variable {@code step_N_output} of the output just recorded ({@link #recordOutput}): protected as that
     * output is.
     */
    public void setStepOutputVariable(String name, Object value) {
        setTemplateVariable(name, value, "{{ " + PREVIOUS_OUTPUT_NAME + " }}");
    }

    /**
     * The block's step ended, with or without an output: what went into it ({@link #recordOutput}) no longer counts
     * for the next block.
     */
    public void blockEnded() { taint.blockEnded(); }

    /**
     * A child workflow the block ran ended with untrusted input or a tainted output: its output, which the block
     * hands on, is that too, so the output this block records is tainted.
     */
    public void childWasTainted() { taint.wentIntoTheBlock(ExecutionContextTaint.Inherited.EXTERNAL_INPUT); }

    /** Re-applies the exported {@link #PLATFORM_IN_SHELL_VARIABLE} flag of an earlier segment or the parent run. */
    public void markPlatformUsedInShell() { taint.markPlatformUsedInShell(); }

    /** Re-applies one exported taint name ({@link #PREVIOUS_OUTPUT_NAME} or a block name). */
    public void markOutputUntrusted(String name) {
        if (PREVIOUS_OUTPUT_NAME.equals(name)) taint.markPreviousOutput();
        else taint.markBlockOutput(name);
    }

    /** Taint as reserved input-context keys for persistence and hand-over; empty for a clean run. */
    public Map<String, Object> exportTaint() { return taint.export(); }

    public Set<String> getUntrustedVariables() { return taint.untrustedVariables(); }

    public Object getVariable(String name) {
        return variables.get(name);
    }

    /** AI provider for a block: block config {@code "provider"} > workflow provider > null (system default). See {@link BlockAiSettings}. */
    public String providerFor(BlockDefinition block) {
        return BlockAiSettings.providerFor(block, provider);
    }

    /** Model for a block: block config {@code "model"} > workflow model (same provider only) > null. See {@link BlockAiSettings}. */
    public String modelFor(BlockDefinition block) {
        return BlockAiSettings.modelFor(block, provider, model);
    }

    /** Effort for a block: block config {@code "effort"} > workflow effort > null; invalid block values are ignored. See {@link BlockAiSettings}. */
    public String effortFor(BlockDefinition block) {
        return BlockAiSettings.effortFor(block, effort);
    }

    /** Stores a block's output under its declared name for {{ block.<name>.output }} resolution. Taint-neutral - use {@link #recordOutput} for block results, {@link #setDerivedVariable} for variables derived from output; only for resume seeding/tests. */
    public void setBlockOutput(String blockName, String output) {
        if (blockName == null || blockName.isBlank()) return;
        blockOutputs.put(blockName, output == null ? "" : output);
    }

    public String getBlockOutput(String blockName) {
        return blockOutputs.get(blockName);
    }

    /**
     * Fills in a template. Like every resolution, it notes what went into the block that runs now: the output the
     * block records is protected when a placeholder that resolved here was ({@link #recordOutput}).
     */
    public String resolveTemplate(String template) {
        return TemplateResolver.resolvePrompt(template, previousOutput, blockOutputs, variables, taint::resolved);
    }

    /**
     * {@link #resolveTemplate} for text that names a host path, such as the host side of a mount: the path variables
     * ({@code project_path}, {@code execution_path}, {@code package_dir}, {@code scratch_dir}, {@code register_dir})
     * take the host paths
     * of the run's directories, which in a container run differ from the paths the step sees. Without
     * {@link RunPaths} the variables are host paths already.
     */
    public String resolveHostTemplate(String template) {
        if (runPaths == null) {
            return resolveTemplate(template);
        }
        Map<String, Object> hostVariables = new HashMap<>(variables);
        String workspace = projectPath != null ? projectPath : "";
        hostVariables.put("project_path", workspace);
        hostVariables.put("execution_path", workspace);
        if (runPaths.packageDirectory() != null) hostVariables.put("package_dir", runPaths.packageDirectory());
        hostVariables.put("scratch_dir", runPaths.scratch() != null ? runPaths.scratch() : "");
        if (runPaths.register() != null) hostVariables.put("register_dir", runPaths.register());
        return TemplateResolver.resolvePrompt(template, previousOutput, blockOutputs, hostVariables,
                taint::resolved);
    }

    /** Whether a {{ key }} placeholder would resolve to a value (possibly blank) rather than stay literal — the same lookup {@link #resolveTemplate} performs, for a caller that must decide before substituting. */
    public boolean resolvesPlaceholder(String key) { return TemplateResolver.resolves(key, previousOutput, blockOutputs, variables); }

    /** Prompt path: like {@link #resolveTemplate}, but platform data here marks this block's output untrusted. */
    public String resolvePromptTemplate(String template) {
        return TemplateResolver.resolvePrompt(template, previousOutput, blockOutputs, variables, key -> {
            taint.substitutedIntoPrompt(key);
            taint.resolved(key);
        });
    }

    /** A shell command line of a block with {@code shell_escape_variables}: {@link #resolveShellTemplate} with {@code true}. */
    public String resolveTemplateShellSafe(String template) {
        return resolveShellTemplate(template, true);
    }

    /**
     * Shell command line: every value is inserted as it is, or with {@code escapeAll} in single quotes. A protected
     * value (an untrusted or platform variable, a tainted block output, what may hold text a model wrote) never goes
     * into a shell line: a line that names one is refused. See {@link TemplateResolver#shellLine}.
     *
     * @throws ShellLineRefusedException when the line names a protected value, wherever it stands and with
     *                                   {@code escapeAll} too; nothing is filled in then
     */
    public String resolveShellTemplate(String template, boolean escapeAll) {
        return TemplateResolver.shellLine(template, previousOutput, blockOutputs, variables, taint.snapshot(),
                escapeAll);
    }

    /**
     * The value of a shell variable a block names under {@code values}: the template with every placeholder filled
     * in as it is, a protected one too, because the result reaches the shell as a variable's value and never as
     * shell text. Platform data in it taints the run from here on, as it did in a shell line
     * ({@link ExecutionContextTaint#substitutedIntoShell}).
     */
    public String resolveShellValue(String template) {
        return TemplateResolver.resolvePrompt(template, previousOutput, blockOutputs, variables, key -> {
            taint.resolved(key);   // before the run is tainted by it: what the key was, not what the run is now
            taint.substitutedIntoShell(key);
        });
    }

    /**
     * Whether the template names a protected value (an untrusted or platform variable, a tainted output, what may
     * hold text a model wrote). The places that take none refuse it: the arguments of a tool step, the host path of
     * a mount.
     */
    public boolean namesEscapedValue(String template) {
        return TemplateResolver.namesEscapedValue(template, taint.snapshot());
    }

    static String shellEscape(String value) { return TemplateResolver.shellEscape(value); }

    public boolean shouldStop() {
        return cancelled.get();
    }

    public boolean isPaused() {
        return paused.get();
    }

    public void pause() {
        paused.set(true);
    }

    public void resume() {
        paused.set(false);
    }

    public void cancel() {
        cancelled.set(true);
        // End whatever is running (shell block, Claude CLI, etc.) through the stop handles its runners reported
        processes.cancel();
        approvals.cancel();
    }

    /** How long {@link #sleepUnlessStopped} waits at most before it looks at the stop flag again. */
    private static final long STOP_POLL_MILLIS = 1000;

    /**
     * Waits {@code millis}, unless the run is asked to stop first: a cancel wakes the wait at once, and a stop flag
     * set without a cancel (a child run's shared flag) ends it within {@value #STOP_POLL_MILLIS} ms. A wait of 0 or
     * less returns at once.
     *
     * @return whether the wait passed in full; {@code false} when the run was asked to stop, before or during the
     *         wait, or the thread was interrupted, whose flag stays set
     */
    public boolean sleepUnlessStopped(long millis) {
        return sleepUnlessStopped(millis, STOP_POLL_MILLIS);
    }

    /**
     * {@link #sleepUnlessStopped(long)} with another time between two looks at the stop flag. For a test that must
     * tell a wait the cancel woke from one that only looked again: with a long time between two looks, only the
     * wake ends the wait early, and no measured duration decides the test.
     */
    boolean sleepUnlessStopped(long millis, long pollMillis) {
        CountDownLatch stopped = new CountDownLatch(1);
        ProcessTracking.StopHandle wake = stopped::countDown;
        processes.register(wake, cancelled);
        try {
            long start = System.nanoTime();
            long total = TimeUnit.MILLISECONDS.toNanos(Math.max(0, millis));
            while (!shouldStop()) {
                long left = total - (System.nanoTime() - start);
                if (left <= 0) {
                    return true;
                }
                stopped.await(Math.min(left, TimeUnit.MILLISECONDS.toNanos(pollMillis)), TimeUnit.NANOSECONDS);
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            processes.unregister(wake);
        }
    }

    /**
     * Registers the stop handles of what a step starts via {@link ProcessTracking} (commands, AI CLIs, container
     * clients), so {@link #cancel()} ends it.
     */
    public ProcessTracking.Tracker processTracker() {
        return new ProcessTracking.Tracker() {
            @Override public void started(ProcessTracking.StopHandle stop) { processes.register(stop, cancelled); }
            @Override public void finished(ProcessTracking.StopHandle stop) { processes.unregister(stop); }
            @Override public boolean cancelled() { return shouldStop(); }
        };
    }

    /** The question this run is currently waiting on; {@code null} if none is open. */
    public ApprovalRequest getCurrentApprovalRequest() { return approvals.currentRequest(); }

    /**
     * Opens {@code request} and returns the promise of its answer. A question opened after the run was cancelled (the
     * cancel came while the step got ready, such as during an AI actor's call) is cancelled at once, so it never waits
     * for its timeout.
     */
    public CompletableFuture<String> requestApproval(ApprovalRequest request) {
        CompletableFuture<String> answer = approvals.request(request);
        if (shouldStop()) {
            // cancel() sets the flag before it cancels the open question: a question it missed is cancelled here
            approvals.cancel();
        }
        return answer;
    }

    /**
     * Waits for the answer to {@link #requestApproval} - instead of {@code future.get}, so that the step
     * counts as running again after waiting (see {@link #onApprovalWait}).
     *
     * @throws PresetAnswerException if a preset answer was given to another question or is not an offered option
     *                               ({@link #presetAnswer})
     */
    public String awaitApproval(CompletableFuture<String> future, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return approvals.await(future, timeout, unit);
    }

    /** Reports when the running step is waiting for an approval or input ({@code null} = nobody). */
    public void onApprovalWait(Consumer<Boolean> listener) { approvals.onWait(listener); }

    /**
     * Answers {@code question} if it is still the one this run waits on; an answer meant for a question that was
     * answered, timed out or cancelled meanwhile never reaches the next one.
     *
     * @return whether the answer reached the waiting block
     */
    public boolean resolveApproval(ApprovalRequest question, String answer) {
        return approvals.resolve(question, answer);
    }

    /**
     * As {@link #resolveApproval(ApprovalRequest, String)}, with what goes with the answer: {@code whenResolved} runs
     * only when the answer reaches the question, before the waiting block can take it, and never for an answer that
     * came after the question's wait ended.
     *
     * @param whenResolved must not wait for the run; {@code null} for nothing
     */
    public boolean resolveApproval(ApprovalRequest question, String answer, Runnable whenResolved) {
        return approvals.resolve(question, answer, whenResolved);
    }

    /**
     * Lets the question of step {@code stepIndex} park the run instead of waiting, when a person answers it alone (not
     * a {@link ApprovalRequest#isShadow() shadow} question) and no preset answers it: the question is recorded as the
     * {@link #parkedRequest()}, and its future fails with a {@link RunParkedException}, so the step unwinds. The
     * engine calls it before every top-level block, with {@link #NO_STEP} for a block that must not park.
     */
    public void parkQuestionOf(int stepIndex) { approvals.parkQuestionOf(stepIndex); }

    /** The question the run parked at; {@code null} while the run has not parked. */
    public ApprovalRequest parkedRequest() { return approvals.parked(); }

    /** Whether {@code request} took the answer given while the run was parked. */
    public boolean answeredFromPreset(ApprovalRequest request) { return approvals.answeredFromPreset(request); }

    /**
     * The answer given while the run was parked: when step {@code stepIndex} asks its question of type {@code type}
     * ({@code approval} or {@code human-input}) again, it receives {@code answer} at once, encoded as the step receives
     * an answer, without anyone waiting; an approval receives it in the spelling of the option it names. If the step
     * now asks something other than {@code question} ({@link QuestionChangedException}), or an approval does not offer
     * the answer, its future fails with a {@link PresetAnswerException}, which {@link #awaitApproval} throws as it is.
     * The answer is used once.
     */
    public void presetAnswer(int stepIndex, String type, String question, String answer) {
        approvals.preset(stepIndex, type, question, answer, secretValues());
    }

    /**
     * Lets a pause park the run between two items of the loop of step {@code stepIndex}: the engine calls it before
     * every top-level block, with {@link #NO_STEP} for a block inside which a pause must not park (any block but a
     * {@code foreach}, and every block of a run whose pause waits in the process).
     */
    public void pauseParksItemsOf(int stepIndex) {
        this.pauseParksItemsOf = stepIndex;
        this.pausedInStep = NO_STEP;
    }

    /**
     * Asked by a loop between two of its items: whether the run parks here because it was asked to pause. If so, the
     * loop of step {@code stepIndex} starts no further item, and its step is not finished: the engine ends the run
     * {@code PARKED}, and a resume runs the loop again with the items it finished. A cancel wins over a pause.
     */
    public boolean parkForPause(int stepIndex) {
        if (stepIndex != pauseParksItemsOf || stepIndex == NO_STEP || !isPaused() || shouldStop()) {
            return false;
        }
        pausedInStep = stepIndex;
        return true;
    }

    /** The step inside which a pause parked the run ({@link #parkForPause}); {@link #NO_STEP} when none did. */
    public int pausedInStep() {
        return pausedInStep;
    }

    /**
     * A resumed run: the items its record holds as finished for the {@code foreach} step that runs again. The engine
     * sets them before the first block.
     */
    public void recordedItems(RecordedItems items) {
        this.recordedItems = items == null ? RecordedItems.NONE : items;
    }

    /**
     * The recorded items, handed out once: to the first loop that runs after the resume, which is the one that was in
     * flight if any was (the items name its step). A later pass of that loop (an output validation retry) gets none,
     * so it runs every item again, as a retry means to.
     */
    public RecordedItems takeRecordedItems() {
        RecordedItems items = recordedItems;
        recordedItems = RecordedItems.NONE;
        return items;
    }

    public void rememberApproval(String question, String answer) { approvals.remember(question, answer); }

    public String getRememberedApproval(String question) { return approvals.remembered(question); }

    /** Plain-Java builder; every field is optional and defaults as the field declarations above. */
    public static final class Builder {
        private String containerId;
        private String workflowRunId;
        private String projectPath;
        private RunPaths runPaths;
        private String previousOutput;
        private String instructionPrefix;
        private String model;
        private String provider;
        private String effort;
        private Map<String, Object> variables;
        private Map<String, String> blockOutputs;
        private SourceStepIndex sourceSteps;
        private StepIndexCounter stepIndices;
        private AtomicBoolean paused;
        private AtomicBoolean cancelled;
        private ApprovalGate approvals;
        private Map<String, Object> runMetadata;
        private OutputBudget outputBudget;
        private RunEvents events;
        private CommandRunner commandRunner;
        private AiProviders aiProviders;
        private Clock clock;
        private Map<String, String> secrets;
        private WorkflowResolver workflowResolver;
        private ChildRuns childRuns;
        private ContextState state;

        private Builder() {}

        public Builder containerId(String v) { this.containerId = v; return this; }
        public Builder workflowRunId(String v) { this.workflowRunId = v; return this; }
        public Builder projectPath(String v) { this.projectPath = v; return this; }
        public Builder runPaths(RunPaths v) { this.runPaths = v; return this; }
        public Builder previousOutput(String v) { this.previousOutput = v; return this; }
        public Builder instructionPrefix(String v) { this.instructionPrefix = v; return this; }
        public Builder model(String v) { this.model = v; return this; }
        public Builder provider(String v) { this.provider = v; return this; }
        public Builder effort(String v) { this.effort = v; return this; }
        public Builder variables(Map<String, Object> v) { this.variables = v; return this; }
        public Builder blockOutputs(Map<String, String> v) { this.blockOutputs = v; return this; }
        public Builder sourceSteps(SourceStepIndex v) { this.sourceSteps = v; return this; }
        public Builder stepIndices(StepIndexCounter v) { this.stepIndices = v; return this; }
        public Builder paused(AtomicBoolean v) { this.paused = v; return this; }
        public Builder cancelled(AtomicBoolean v) { this.cancelled = v; return this; }
        public Builder runMetadata(Map<String, Object> v) { this.runMetadata = v; return this; }
        /** The run's output budget, which its child runs share; by default none. */
        public Builder outputBudget(OutputBudget v) { this.outputBudget = v; return this; }
        public Builder events(RunEvents v) { this.events = v; return this; }
        public Builder commandRunner(CommandRunner v) { this.commandRunner = v; return this; }
        public Builder aiProviders(AiProviders v) { this.aiProviders = v; return this; }
        public Builder clock(Clock v) { this.clock = v; return this; }
        /** The values of the run's secrets by name; the context keeps a read-only copy. */
        public Builder secrets(Map<String, String> v) { this.secrets = v; return this; }
        public Builder workflowResolver(WorkflowResolver v) { this.workflowResolver = v; return this; }
        public Builder childRuns(ChildRuns v) { this.childRuns = v; return this; }
        /** The state of a run to continue ({@link #state()}); it is applied over the other values of this builder. */
        public Builder state(ContextState v) { this.state = v; return this; }

        public ExecutionContext build() {
            return new ExecutionContext(this);
        }
    }
}
