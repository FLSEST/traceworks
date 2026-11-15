package traceworks.core.engine;

import traceworks.core.container.RunPaths;
import traceworks.core.context.ChildRuns;
import traceworks.core.model.ContextState;
import traceworks.core.model.RunSnapshot;
import traceworks.core.model.StepResult;
import traceworks.core.context.ExecutionContext;
import traceworks.core.context.OutputBudget;
import traceworks.core.model.TriggerSource;
import traceworks.core.port.AiProviders;
import traceworks.core.port.Clock;
import traceworks.core.port.CommandRunner;
import traceworks.core.port.RunEvents;
import traceworks.core.port.WorkflowResolver;

import traceworks.core.util.TruncationUtil;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds the {@link ExecutionContext} for a run: input variables with their taint, the previous-output seed, the run
 * variables, the workflow's instruction prefix, model, provider and effort, the run's control flags and event sink,
 * and the ports its block executors use (command runner, AI providers, clock, workflow resolver) with the run's child
 * runs. A resumed run gets its context back from the state of its checkpoint instead ({@link #restore}).
 *
 * <p>TODO and goal data ({@code open_todos}, goals), the host variables ({@code home_dir}, {@code user}), project
 * and version variables and a run token are not provided (docs/workflow-format.md); {@code previous_report} and
 * {@code audit_findings} come only from the register of an audit workflow. The platform data names are never taken
 * from an input: see {@link #readsFromInput}.
 */
final class ExecutionContextFactory {

    /** Handed over by a chain from the previous entry's run. */
    static final String CHAIN_OUTPUT = "previous_chain_output";

    /** The marker that tops the workflow's own instructions in the instruction prefix. */
    static final String INSTRUCTIONS_HEADING = "## Workflow-Specific Instructions\n";

    /** Longer workflow instructions are cut. */
    static final int MAX_INSTRUCTION_LENGTH = 10_000;

    /**
     * The names {@link #create} sets on every run itself, after it has read the input context, and {@link #restore}
     * sets again on a resumed run: a value under one of them in an input context never reaches the run. Pinned against
     * {@code create} in its test. {@value #PACKAGE_DIR} is not among them: a run with {@link RunPaths} sets it from
     * them, a run without keeps the input the caller sets it as.
     */
    static final Set<String> RUN_VARIABLES = RunVariables.NAMES;

    /** Where a step finds the package's files: the package directory as the step sees it. */
    static final String PACKAGE_DIR = RunVariables.PACKAGE_DIR;

    /** The name of a step's output variable, {@code step_N_output}, which {@code BlockStepRunner} sets. */

    /** Path-safe timestamp for output directories: no ':' and no zone. */
    private static final DateTimeFormatter RUN_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final Clock clock;
    private final CommandRunner commandRunner;
    private final AiProviders aiProviders;
    private final WorkflowResolver workflowResolver;
    private final ChildRuns childRuns;
    private final Map<String, String> secrets;
    private final OutputBudget outputBudget;

    /** A factory whose contexts have no command runner and no AI provider (see {@link ExecutionContext}). */
    ExecutionContextFactory(Clock clock) {
        this(clock, null, null);
    }

    /** A factory whose contexts hand the run's ports to the block executors; {@code null} keeps the context default. */
    ExecutionContextFactory(Clock clock, CommandRunner commandRunner, AiProviders aiProviders) {
        this(clock, commandRunner, aiProviders, null, null);
    }

    /**
     * A factory whose contexts also find other workflows and start them as children; {@code null} keeps the context
     * default, which refuses them.
     */
    ExecutionContextFactory(Clock clock, CommandRunner commandRunner, AiProviders aiProviders,
                            WorkflowResolver workflowResolver, ChildRuns childRuns) {
        this(clock, commandRunner, aiProviders, workflowResolver, childRuns, Map.of());
    }

    /** A factory whose contexts also hold the values of the run's secrets ({@link ExecutionContext#getSecrets}). */
    ExecutionContextFactory(Clock clock, CommandRunner commandRunner, AiProviders aiProviders,
                            WorkflowResolver workflowResolver, ChildRuns childRuns, Map<String, String> secrets) {
        this(clock, commandRunner, aiProviders, workflowResolver, childRuns, secrets, null);
    }

    /**
     * A factory whose contexts also share the run's output budget ({@link ExecutionContext#getOutputBudget});
     * {@code null} for none.
     */
    ExecutionContextFactory(Clock clock, CommandRunner commandRunner, AiProviders aiProviders,
                            WorkflowResolver workflowResolver, ChildRuns childRuns, Map<String, String> secrets,
                            OutputBudget outputBudget) {
        this.clock = clock;
        this.commandRunner = commandRunner;
        this.aiProviders = aiProviders;
        this.workflowResolver = workflowResolver;
        this.childRuns = childRuns;
        this.secrets = secrets;
        this.outputBudget = outputBudget;
    }

    /**
     * Everything the run knows before its block loop starts.
     *
     * @param workingDirectory     the run's working directory (a host path); {@code project_path} for the workflow,
     *                             as a container sees it when the run has {@code paths}
     * @param resumePreviousOutput a resume's seed for {@code previous_output}; {@code null} for a fresh run
     * @param events               the run's event sink; {@code null} drops the events
     * @param control              the run's control; {@code null} for flags nobody else holds
     * @param paths                where the run's containers see the workspace, the package and the scratch
     *                             directory; {@code null} for a run without them, whose paths are the host paths and
     *                             which has no scratch directory
     * @param workspaceSourceSha   the commit of the run's checkout of its source ({@code --source}),
     *                             {@code workspace_source_sha}; {@code null} for a run without one
     * @param modelVariables       the variables of the run's picked model choices
     *                             ({@link ModelChoices.Resolution#variables}), set like the run variables: after the
     *                             inputs, trusted, and again on a resume
     */
    record ContextSeed(String runId, String workflowId, String workflowName, String workingDirectory,
                       Map<String, Object> inputContext, String instructions, String model, String provider,
                       String effort, String resumePreviousOutput, TriggerSource triggerSource, RunEvents events,
                       RunControl control, RunPaths paths, String workspaceSourceSha,
                       Map<String, String> platformData, Map<String, String> modelVariables) {

        ContextSeed {
            platformData = platformData == null ? Map.of() : Map.copyOf(platformData);
            modelVariables = modelVariables == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(modelVariables));
        }

        /** A seed of a run whose workflow opens no model choice. */
        ContextSeed(String runId, String workflowId, String workflowName, String workingDirectory,
                    Map<String, Object> inputContext, String instructions, String model, String provider,
                    String effort, String resumePreviousOutput, TriggerSource triggerSource, RunEvents events,
                    RunControl control, RunPaths paths, String workspaceSourceSha,
                    Map<String, String> platformData) {
            this(runId, workflowId, workflowName, workingDirectory, inputContext, instructions, model, provider, effort,
                    resumePreviousOutput, triggerSource, events, control, paths, workspaceSourceSha, platformData,
                    Map.of());
        }

        /** A seed without platform data. */
        ContextSeed(String runId, String workflowId, String workflowName, String workingDirectory,
                    Map<String, Object> inputContext, String instructions, String model, String provider,
                    String effort, String resumePreviousOutput, TriggerSource triggerSource, RunEvents events,
                    RunControl control, RunPaths paths, String workspaceSourceSha) {
            this(runId, workflowId, workflowName, workingDirectory, inputContext, instructions, model, provider, effort,
                    resumePreviousOutput, triggerSource, events, control, paths, workspaceSourceSha, Map.of());
        }

        /** A seed without a checkout of a source. */
        ContextSeed(String runId, String workflowId, String workflowName, String workingDirectory,
                    Map<String, Object> inputContext, String instructions, String model, String provider,
                    String effort, String resumePreviousOutput, TriggerSource triggerSource, RunEvents events,
                    RunControl control, RunPaths paths) {
            this(runId, workflowId, workflowName, workingDirectory, inputContext, instructions, model, provider, effort,
                    resumePreviousOutput, triggerSource, events, control, paths, null);
        }

        /** A seed without {@link RunPaths}. */
        ContextSeed(String runId, String workflowId, String workflowName, String workingDirectory,
                    Map<String, Object> inputContext, String instructions, String model, String provider,
                    String effort, String resumePreviousOutput, TriggerSource triggerSource, RunEvents events,
                    RunControl control) {
            this(runId, workflowId, workflowName, workingDirectory, inputContext, instructions, model, provider, effort,
                    resumePreviousOutput, triggerSource, events, control, null);
        }
    }

    ExecutionContext create(ContextSeed seed) {
        Map<String, Object> inputContext = seed.inputContext() != null ? seed.inputContext() : Map.of();
        ExecutionContext context = builder(seed).build();

        Set<String> untrustedKeys = reservedNames(inputContext, ExecutionContext.UNTRUSTED_KEYS_VARIABLE);
        Set<String> modelKeys = reservedNames(inputContext, ExecutionContext.MODEL_KEYS_VARIABLE);
        inputContext.forEach((name, value) -> {
            if (!readsFromInput(name)) return;
            if (untrustedKeys.contains(name)) {
                context.setUntrustedVariable(name, value);
            } else if (modelKeys.contains(name)) {
                // Derived from an output of the parent run after it asked a model: quoted in a shell line here too
                context.setModelVariable(name, value);
            } else if (CHAIN_OUTPUT.equals(name)) {
                // The last output of another run: escaped like platform data and a taint source once it reaches a
                // shell line or a prompt; the chain marks it as untrusted input when that run was tainted.
                context.setUntrustedPlatformVariable(name, value);
            } else {
                context.setVariable(name, value);
            }
        });
        // The register's data for an audit workflow: platform data, untrusted
        seed.platformData().forEach(context::setUntrustedPlatformVariable);
        // Outputs tainted in an earlier segment of this run (resume) or in the parent (sub-workflow)
        reservedNames(inputContext, ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE)
                .forEach(context::markOutputUntrusted);
        // Platform data already reached a shell command line earlier in the run
        if (Boolean.parseBoolean(String.valueOf(inputContext.get(ExecutionContext.PLATFORM_IN_SHELL_VARIABLE)))) {
            context.markPlatformUsedInShell();
        }
        // The parent run asked a model: what it handed over as model text stays that, and so does what this run records
        if (Boolean.parseBoolean(String.valueOf(inputContext.get(ExecutionContext.MODEL_ASKED_VARIABLE)))) {
            context.markModelAsked();
        }
        reservedNames(inputContext, ExecutionContext.MODEL_OUTPUTS_VARIABLE).forEach(context::markOutputModelText);
        if (seed.resumePreviousOutput() != null) {
            context.setPreviousOutput(seed.resumePreviousOutput());
        } else if (inputContext.get("previous_output") != null) {
            context.setPreviousOutput(String.valueOf(inputContext.get("previous_output")));
        }

        // The run variables: set last, so an input never overrides them
        setRunVariables(context, seed);

        applyWorkflowSettings(context, seed);
        // The inputs and the run variables are no text a step made: from here on, what a step sets counts
        context.countVariables(StepOutputs::isStepOutput, name -> false);
        return context;
    }

    /**
     * The context of a resumed run: the run's {@code state} from its checkpoint, with the ports, control and event
     * sink of this process. The state comes back as it was, the trust of every variable included, except the run
     * variables ({@link #RUN_VARIABLES}): the resume sets them from its seed and this factory's clock exactly as
     * {@link #create} does, so they agree with the context's working
     * directory, model, provider and effort, which the seed sets too. {@code trigger_source} is the resume's source,
     * {@code started_at} and {@code run_timestamp} the time of the resume. The seed's resume output is not read; its
     * input context only tells which variables the run's steps set ({@link #setByAStep}), which count against the
     * output budget at once.
     */
    ExecutionContext restore(ContextSeed seed, ContextState state) {
        ExecutionContext context = builder(seed).state(state).build();
        setRunVariables(context, seed);
        applyWorkflowSettings(context, seed);
        Map<String, Object> inputContext = seed.inputContext() != null ? seed.inputContext() : Map.of();
        Map<String, Object> variables = state.variables();
        context.countVariables(StepOutputs::isStepOutput,
                name -> !seed.modelVariables().containsKey(name)
                        && setByAStep(name, variables.get(name), inputContext));
        return context;
    }

    /**
     * Whether a resumed run's variable {@code name}, with {@code value}, was set by one of its steps, so that it counts
     * against the output budget: not a step's output, a run variable or {@value #PACKAGE_DIR}, and not an input that
     * still holds the input's value. An input whose value a step replaced (a foreach {@code variable}, a human input's
     * {@code variable_name}) counts, as it did before the park.
     */
    static boolean setByAStep(String name, Object value, Map<String, Object> inputContext) {
        return !StepOutputs.isStepOutput(name) && !RUN_VARIABLES.contains(name) && !PACKAGE_DIR.equals(name)
                && !(inputContext.containsKey(name) && Objects.equals(inputContext.get(name), value));
    }

    /**
     * What a run resumed from {@code snapshot} holds from its start against its output budget: the input and output
     * of its steps and the values of the variables they set.
     */
    static long heldOnResume(RunSnapshot snapshot) {
        long held = 0;
        for (StepResult step : snapshot.steps()) {
            held += OutputBudget.sizeOf(step);
        }
        for (Map.Entry<String, Object> variable : snapshot.context().variables().entrySet()) {
            if (setByAStep(variable.getKey(), variable.getValue(), snapshot.inputContext())) {
                held += OutputBudget.sizeOf(variable.getValue());
            }
        }
        return held;
    }

    /**
     * Sets every run variable ({@link #RUN_VARIABLES}) from the seed, as trusted values, and with {@link RunPaths}
     * {@value #PACKAGE_DIR}; {@code workspace_source_sha} is empty for a run without a checkout of a source. The paths
     * are those a step sees: in a container run the fixed container paths, in a host run the host paths.
     */
    private void setRunVariables(ExecutionContext context, ContextSeed seed) {
        RunPaths paths = seed.paths();
        String workingDirectory = orEmpty(paths != null ? paths.containerPathOf(seed.workingDirectory())
                : seed.workingDirectory());
        context.setVariable("workflow_run_id", seed.runId());
        context.setVariable("workflow_id", seed.workflowId());
        context.setVariable("workflow_name", seed.workflowName() != null ? seed.workflowName() : "");
        context.setVariable("project_path", workingDirectory);
        context.setVariable("execution_path", workingDirectory);
        context.setVariable("scratch_dir", paths != null ? orEmpty(paths.scratchInContainer()) : "");
        // The commit of an isolated checkout, empty on every other run
        context.setVariable("workspace_source_sha", orEmpty(seed.workspaceSourceSha()));
        if (paths != null && paths.packageInContainer() != null) {
            context.setVariable(PACKAGE_DIR, paths.packageInContainer());
        }
        context.setVariable("model", seed.model() != null ? seed.model() : "");
        context.setVariable("provider", seed.provider() != null ? seed.provider() : "");
        context.setVariable("effort", seed.effort() != null ? seed.effort() : "");
        Instant startedAt = clock.now();
        context.setVariable("started_at", startedAt.toString());
        // Path-safe, in UTC: use together with {{ workflow_run_id }} to version a run's output files
        context.setVariable("run_timestamp", RUN_TIMESTAMP.format(startedAt));
        context.setVariable("trigger_source", seed.triggerSource() != null ? seed.triggerSource().name() : "");
        // The picked model choices: what the package declares, picked by a run input from its declared options
        seed.modelVariables().forEach(context::setVariable);
    }

    private static String orEmpty(String value) {
        return value != null ? value : "";
    }

    /** A context builder with the seed's run id, event sink and control and the ports of this factory. */
    private ExecutionContext.Builder builder(ContextSeed seed) {
        ExecutionContext.Builder builder = ExecutionContext.builder()
                .workflowRunId(seed.runId())
                .events(seed.events())
                .commandRunner(commandRunner)
                .aiProviders(aiProviders)
                .workflowResolver(workflowResolver)
                .childRuns(childRuns)
                .secrets(secrets)
                .outputBudget(outputBudget)
                .clock(clock);
        if (seed.control() != null) {
            seed.control().bind(builder);
        }
        return builder;
    }

    /**
     * The context's settings from the workflow and the run's options: working directory, run paths, instructions,
     * model, provider and effort.
     */
    private static void applyWorkflowSettings(ExecutionContext context, ContextSeed seed) {
        context.setProjectPath(seed.workingDirectory());
        context.setRunPaths(seed.paths());
        String instructionPrefix = instructionPrefix(seed.instructions(), context.secretValues());
        if (!instructionPrefix.isBlank()) {
            context.setInstructionPrefix(instructionPrefix);
        }
        if (seed.model() != null && !seed.model().isBlank()) {
            context.setModel(seed.model());
        }
        if (seed.provider() != null && !seed.provider().isBlank()) {
            context.setProvider(seed.provider());
        }
        if (seed.effort() != null && !seed.effort().isBlank()) {
            context.setEffort(seed.effort());
        }
    }

    /**
     * The prefix AI steps put in front of their system prompt: the workflow's own instructions under
     * {@value #INSTRUCTIONS_HEADING}, cut after {@value #MAX_INSTRUCTION_LENGTH} characters. Empty without
     * instructions.
     */
    static String instructionPrefix(String instructions) {
        return instructionPrefix(instructions, java.util.List.of());
    }

    /** Like {@link #instructionPrefix(String)}, with a cut that keeps each of {@code keepWhole} whole. */
    static String instructionPrefix(String instructions, java.util.Collection<String> keepWhole) {
        if (instructions == null || instructions.isBlank()) {
            return "";
        }
        String trimmed = instructions.strip();
        if (trimmed.length() > MAX_INSTRUCTION_LENGTH) {
            trimmed = trimmed.substring(0, TruncationUtil.cutBefore(trimmed, MAX_INSTRUCTION_LENGTH, keepWhole))
                    + "\n[... truncated]";
        }
        return INSTRUCTIONS_HEADING + trimmed;
    }

    /**
     * Whether {@link #create} takes {@code name} from an input context at all: never one of the
     * {@link PlatformVariables}, so a value handed over by a parent run or passed as input never becomes a trusted
     * variable, unquoted in shell blocks: platform data is untrusted and quoted.
     */
    static boolean readsFromInput(String name) {
        return !PlatformVariables.contains(name);
    }

    /**
     * Names behind a reserved taint key of the input context: {@link ExecutionContext#UNTRUSTED_KEYS_VARIABLE}
     * (untrusted variables, flagged by the trigger) or {@link ExecutionContext#UNTRUSTED_OUTPUTS_VARIABLE} (outputs
     * tainted earlier in the run). Anything but a list of names means "none".
     */
    static Set<String> reservedNames(Map<String, Object> inputContext, String key) {
        Object raw = inputContext.get(key);
        if (raw instanceof Collection<?> names) {
            Set<String> keys = new HashSet<>();
            for (Object name : names) {
                if (name != null) keys.add(String.valueOf(name));
            }
            return keys;
        }
        return Set.of();
    }
}
