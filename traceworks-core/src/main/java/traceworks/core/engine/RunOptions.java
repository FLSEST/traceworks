package traceworks.core.engine;

import traceworks.core.container.RunPaths;
import traceworks.core.context.OutputBudget;
import traceworks.core.model.TriggerSource;
import traceworks.core.port.AiProviders;
import traceworks.core.port.Clock;
import traceworks.core.port.CommandRunner;
import traceworks.core.port.RunEvents;
import traceworks.core.port.WorkflowResolver;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Everything one run needs besides the workflow and its inputs: its id, the ports it talks through, its control and
 * its working directory. Build it with {@link #builder()}; only {@code runId} is required.
 *
 * @param runId            the run's id, {@code workflow_run_id} for the workflow
 * @param events           where the run's events go; by default they are dropped. A sink that throws stops the run
 *                         ({@link EventLogException}, see {@link WorkflowEngine}).
 * @param commandRunner    runs the run's shell commands ({@code run_if} and the shell blocks); by default every command
 *                         is refused
 * @param aiProviders      the AI providers of the run's AI blocks; by default none is configured
 * @param workflowResolver finds the workflows a block refers to; by default every reference is refused
 * @param runControl       pause and cancel from outside; by default a control nobody else holds
 * @param clock            the time of the run's timestamps, step timestamps of the blocks included; by default the
 *                         system clock
 * @param workingDirectory the directory the run works in (a host path), {@code project_path} for the workflow; may be
 *                         {@code null}
 * @param triggerSource    what started the run, {@code trigger_source} for the workflow; by default {@code MANUAL}
 * @param checkpoints      whether the run reports a {@link traceworks.core.port.RunEvent.Checkpoint}
 *                         before its first block and after every top-level step that lets it go on, so it can be
 *                         resumed; off by default, and always off in a child run
 * @param parkAtQuestions  whether a top-level {@code approval} or {@code human-input} that a person answers alone
 *                         parks the run instead of waiting: the question is recorded, the step is not finished and
 *                         the run ends {@code PARKED}, to be resumed with the answer. A question cast with an AI actor
 *                         ({@code shadow}, or {@code ai} that asks the person) waits in the process. For a run nobody
 *                         can answer in the process (no terminal); off by default, and always off in a child run,
 *                         whose questions wait
 * @param parkOnPause      what {@link RunControl#pause()} does to the run. Off, the default: the run waits in the
 *                         process between two top-level blocks until it is resumed or cancelled. On, for a run whose
 *                         process may end while it is paused (the command line's): the run finishes the step, or the
 *                         item of a top-level {@code foreach}, in flight and ends {@code PARKED}, to be resumed from
 *                         its last checkpoint; it needs {@code checkpoints}. A child run keeps the setting, never
 *                         parks and never waits: it runs to its end, and its parent parks
 * @param runPaths         where the run's containers see the workspace, the package and the scratch directory, and
 *                         so the paths a step sees in {@code project_path}, {@code execution_path},
 *                         {@code package_dir} and {@code scratch_dir}: the fixed container paths in a container run,
 *                         the host paths in a host run. By default none: the paths are the host paths, there is no
 *                         scratch directory, and {@code package_dir} is whatever input the caller sets
 * @param secrets          the values of the run's secrets by name, which a {@code shell} step that declares one gets
 *                         as an environment variable; its child runs get them too. They are no variable and no
 *                         checkpoint holds them. A key is the secret's name, or ends in {@code :<NAME>} for a value
 *                         no step can declare (a credential of an AI provider): a step that replaces the values in
 *                         a text names the replacement after that name. By default none; {@link #toString()} names
 *                         them, never their values
 * @param workspaceSourceSha the commit the run's workspace is a checkout of ({@code --source}), which the run and its
 *                         child runs see as {@code workspace_source_sha}; by default none, and the variable is empty
 * @param platformData      the platform data of the run ({@code audit_findings}, {@code previous_report}), set as
 *                          untrusted platform variables; empty for a workflow without a register
 * @param modelInputs       the inputs the model choices of the run are picked from ({@link ModelChoices}):
 *                          {@code null} for a run that picks from its own inputs; the engine gives a child run those
 *                          of the run that started first
 * @param resumedPicks      for a resumed run: the option its start recorded for each model choice, by the choice's
 *                          name ({@code run.started.model_choices}). The resumed run picks these, whatever the
 *                          definition's default is by now; empty for a fresh run, and never applied to a child run
 * @param allowedModels     the models that may run ({@link AllowedModels}): a run with an AI step outside them is
 *                          refused before its first block; by default {@link AllowedModels#ANY}
 * @param outputBudgetBytes how much memory the output text of the run, its child runs included, may take, in bytes
 *                         ({@link OutputBudget}): the step that would take it beyond fails and ends the run. By
 *                         default {@link OutputBudget#NONE}, no budget; a child run shares its parent's
 */
public record RunOptions(String runId, RunEvents events, CommandRunner commandRunner, AiProviders aiProviders,
                         WorkflowResolver workflowResolver, RunControl runControl, Clock clock,
                         String workingDirectory, TriggerSource triggerSource, boolean checkpoints,
                         boolean parkAtQuestions, RunPaths runPaths, Map<String, String> secrets,
                         String workspaceSourceSha, long outputBudgetBytes, Map<String, String> platformData,
                         boolean parkOnPause, Map<String, Object> modelInputs, AllowedModels allowedModels,
                         Map<String, String> resumedPicks) {

    public RunOptions {
        resumedPicks = resumedPicks == null ? Map.of() : Map.copyOf(resumedPicks);
        modelInputs = modelInputs == null ? null
                : java.util.Collections.unmodifiableMap(new java.util.HashMap<>(modelInputs));
        allowedModels = allowedModels == null ? AllowedModels.ANY : allowedModels;
        platformData = platformData == null ? Map.of() : Map.copyOf(platformData);
        Objects.requireNonNull(runId, "runId");
        events = events != null ? events : event -> {
        };
        commandRunner = commandRunner != null ? commandRunner : request -> {
            throw new IllegalStateException("No CommandRunner configured for this run");
        };
        aiProviders = aiProviders != null ? aiProviders : AiProviders.of(null, List.of());
        workflowResolver = workflowResolver != null ? workflowResolver : ref -> {
            throw new IllegalArgumentException("No WorkflowResolver configured for this run; cannot resolve '"
                    + ref + "'");
        };
        runControl = runControl != null ? runControl : new RunControl();
        clock = clock != null ? clock : Instant::now;
        triggerSource = triggerSource != null ? triggerSource : TriggerSource.MANUAL;
        secrets = secrets != null ? Map.copyOf(secrets) : Map.of();
        if (outputBudgetBytes < 1) {
            throw new IllegalArgumentException("An output budget is at least 1 byte: " + outputBudgetBytes);
        }
    }

    /** The options without the values of the secrets, which only their names stand for. */
    @Override
    public String toString() {
        return "RunOptions[runId=" + runId + ", workingDirectory=" + workingDirectory + ", triggerSource="
                + triggerSource + ", checkpoints=" + checkpoints + ", parkAtQuestions=" + parkAtQuestions
                + ", parkOnPause=" + parkOnPause
                + ", runPaths=" + runPaths + ", secrets=" + new TreeSet<>(secrets.keySet()) + ", workspaceSourceSha="
                + workspaceSourceSha + ", outputBudgetBytes=" + outputBudgetBytes + "]";
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A builder with every value of these options, for a run that differs in a few, such as a child run. */
    public Builder toBuilder() {
        return new Builder().runId(runId).events(events).commandRunner(commandRunner).aiProviders(aiProviders)
                .workflowResolver(workflowResolver).runControl(runControl).clock(clock)
                .workingDirectory(workingDirectory).triggerSource(triggerSource).checkpoints(checkpoints)
                .parkAtQuestions(parkAtQuestions).runPaths(runPaths).secrets(secrets)
                .workspaceSourceSha(workspaceSourceSha).outputBudgetBytes(outputBudgetBytes).platformData(platformData)
                .parkOnPause(parkOnPause).modelInputs(modelInputs).allowedModels(allowedModels)
                .resumedPicks(resumedPicks);
    }

    /** Plain-Java builder; every value but {@code runId} is optional. */
    public static final class Builder {
        private String runId;
        private RunEvents events;
        private CommandRunner commandRunner;
        private AiProviders aiProviders;
        private WorkflowResolver workflowResolver;
        private RunControl runControl;
        private Clock clock;
        private String workingDirectory;
        private TriggerSource triggerSource;
        private boolean checkpoints;
        private boolean parkAtQuestions;
        private RunPaths runPaths;
        private Map<String, String> secrets;
        private Map<String, String> platformData = Map.of();
        private String workspaceSourceSha;
        private long outputBudgetBytes = OutputBudget.NONE;
        private boolean parkOnPause;
        private Map<String, Object> modelInputs;
        private AllowedModels allowedModels;
        private Map<String, String> resumedPicks;

        private Builder() {}

        public Builder runId(String v) { this.runId = v; return this; }
        public Builder events(RunEvents v) { this.events = v; return this; }
        public Builder commandRunner(CommandRunner v) { this.commandRunner = v; return this; }
        public Builder aiProviders(AiProviders v) { this.aiProviders = v; return this; }
        public Builder workflowResolver(WorkflowResolver v) { this.workflowResolver = v; return this; }
        public Builder runControl(RunControl v) { this.runControl = v; return this; }
        public Builder clock(Clock v) { this.clock = v; return this; }
        public Builder workingDirectory(String v) { this.workingDirectory = v; return this; }
        public Builder triggerSource(TriggerSource v) { this.triggerSource = v; return this; }
        public Builder checkpoints(boolean v) { this.checkpoints = v; return this; }
        public Builder parkAtQuestions(boolean v) { this.parkAtQuestions = v; return this; }
        public Builder runPaths(RunPaths v) { this.runPaths = v; return this; }
        public Builder secrets(Map<String, String> v) { this.secrets = v; return this; }
        public Builder workspaceSourceSha(String v) { this.workspaceSourceSha = v; return this; }
        public Builder outputBudgetBytes(long v) { this.outputBudgetBytes = v; return this; }
        public Builder platformData(Map<String, String> v) { this.platformData = v; return this; }
        public Builder parkOnPause(boolean v) { this.parkOnPause = v; return this; }
        public Builder modelInputs(Map<String, Object> v) { this.modelInputs = v; return this; }
        public Builder allowedModels(AllowedModels v) { this.allowedModels = v; return this; }
        public Builder resumedPicks(Map<String, String> v) { this.resumedPicks = v; return this; }

        public RunOptions build() {
            return new RunOptions(runId, events, commandRunner, aiProviders, workflowResolver, runControl, clock,
                    workingDirectory, triggerSource, checkpoints, parkAtQuestions, runPaths, secrets,
                    workspaceSourceSha, outputBudgetBytes, platformData, parkOnPause, modelInputs, allowedModels, resumedPicks);
        }
    }
}
