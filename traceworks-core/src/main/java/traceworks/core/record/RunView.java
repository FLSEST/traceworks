package traceworks.core.record;

import traceworks.core.container.ImageFacts;
import traceworks.core.model.ModelPick;
import traceworks.core.model.RunStatus;
import traceworks.core.port.BlobRef;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What the events of a run say about the run: the projection that {@code show}, {@code watch}, {@code run.html} and
 * resume read. A child run's view has no workflow, package, actor or trigger fields, because the child's events do
 * not carry them.
 *
 * @param packageCommit the commit of the package's repository when the run started; {@code null} when there is none
 * @param packageDirty  whether that repository had uncommitted changes; {@code null} without a commit
 * @param execution   where the run runs its steps, as {@code run.started} records it: {@code container} or
 *                    {@code host}; {@code null} in a child's view
 * @param writerAlive whether the process that writes the record is alive; it decides between {@code RUNNING} and
 *                    {@code INTERRUPTED} for a run that did not end
 * @param pendingQuestion the open question of a {@code PARKED} run; {@code null} otherwise
 * @param parkedAnswer    the {@code approval.answered} recorded after {@code run.parked} for the parked step; {@code null} when
 *                        there is none
 * @param head            the hash of the last line
 * @param images          what each image the run may use holds, as {@code run.started} recorded it; empty for a
 *                        record of an earlier version
 * @param aiNetwork       where the containers of the AI CLIs could connect, as {@code run.started} recorded it:
 *                        {@code restricted} or {@code open}; {@code null} for a record of an earlier version and in
 *                        a child's view
 * @param aiHosts         the operator's hosts for the containers of each AI CLI, by CLI id; empty without any
 * @param parkedBy        why a {@code PARKED} run parked, the {@code kind} of its {@code run.parked}: {@code approval}
 *                        or {@code human-input} (it waits for an answer, see {@code pendingQuestion}), or
 *                        {@code pause} (it was asked to pause and waits for nothing but its resume); {@code null}
 *                        for a run that is not parked
 * @param seal            the seal the run's package carried when the run started, as {@code run.started} recorded
 *                        it; {@code null} for a package that was not sealed, a record of an earlier version and a
 *                        child's view
 * @param interventions   what people and processes did to the run from outside its steps, in log order: resumes,
 *                        cancel requests, recoveries of a torn line, refused requests and answers given outside the
 *                        run's process ({@code cli}, {@code inbox}); the whole record's in the root run's view,
 *                        empty in a child's
 * @param modelChoices    the option the run picked of each model choice its workflow declares, as
 *                        {@code run.started} recorded it; empty for a workflow without one and for a child run,
 *                        whose calls name their provider and model
 * @param register        what the register of an audit workflow handed the run at its start, as {@code run.started}
 *                        recorded it: the head of the register's log and the files of the run's register folder;
 *                        {@code null} for a workflow that is no audit and a record of an earlier version
 */
public record RunView(String runId, String workflowId, String workflowName, String packageDir, String packageDigest,
                      String packageCommit, Boolean packageDirty,
                      String actor, String trigger, String execution, Instant startedAt, Instant finishedAt,
                      RunStatus status, BlobRef error, GateView gate, List<StepView> steps, QuestionView pendingQuestion,
                      QuestionView.Answer parkedAnswer, Checkpoint lastCheckpoint, BlobRef definition,
                      BlobRef inputs, Map<String, RunView> children, int eventCount, String head,
                      List<Intervention> interventions, List<ImageFacts> images, String aiNetwork,
                      Map<String, List<String>> aiHosts, String parkedBy, SealView seal,
                      List<ModelPick> modelChoices, RegisterHandover register, boolean recordsOutputCut) {

    /** A view of a run whose record does not say whether it names a cut of an output: an earlier version wrote it. */
    public RunView(String runId, String workflowId, String workflowName, String packageDir, String packageDigest,
                   String packageCommit, Boolean packageDirty,
                   String actor, String trigger, String execution, Instant startedAt, Instant finishedAt,
                   RunStatus status, BlobRef error, GateView gate, List<StepView> steps, QuestionView pendingQuestion,
                   QuestionView.Answer parkedAnswer, Checkpoint lastCheckpoint, BlobRef definition,
                   BlobRef inputs, Map<String, RunView> children, int eventCount, String head,
                   List<Intervention> interventions, List<ImageFacts> images, String aiNetwork,
                   Map<String, List<String>> aiHosts, String parkedBy, SealView seal,
                   List<ModelPick> modelChoices, RegisterHandover register) {
        this(runId, workflowId, workflowName, packageDir, packageDigest, packageCommit, packageDirty, actor, trigger,
                execution, startedAt, finishedAt, status, error, gate, steps, pendingQuestion, parkedAnswer,
                lastCheckpoint, definition, inputs, children, eventCount, head, interventions, images, aiNetwork,
                aiHosts, parkedBy, seal, modelChoices, register, false);
    }

    /** A view of a run of a workflow that is no audit. */
    public RunView(String runId, String workflowId, String workflowName, String packageDir, String packageDigest,
                   String packageCommit, Boolean packageDirty,
                   String actor, String trigger, String execution, Instant startedAt, Instant finishedAt,
                   RunStatus status, BlobRef error, GateView gate, List<StepView> steps, QuestionView pendingQuestion,
                   QuestionView.Answer parkedAnswer, Checkpoint lastCheckpoint, BlobRef definition,
                   BlobRef inputs, Map<String, RunView> children, int eventCount, String head,
                   List<Intervention> interventions, List<ImageFacts> images, String aiNetwork,
                   Map<String, List<String>> aiHosts, String parkedBy, SealView seal,
                   List<ModelPick> modelChoices) {
        this(runId, workflowId, workflowName, packageDir, packageDigest, packageCommit, packageDirty, actor, trigger,
                execution, startedAt, finishedAt, status, error, gate, steps, pendingQuestion, parkedAnswer,
                lastCheckpoint, definition, inputs, children, eventCount, head, interventions, images, aiNetwork,
                aiHosts, parkedBy, seal, modelChoices, null);
    }

    /** A view of a run whose workflow declares no model choice. */
    public RunView(String runId, String workflowId, String workflowName, String packageDir, String packageDigest,
                   String packageCommit, Boolean packageDirty,
                   String actor, String trigger, String execution, Instant startedAt, Instant finishedAt,
                   RunStatus status, BlobRef error, GateView gate, List<StepView> steps, QuestionView pendingQuestion,
                   QuestionView.Answer parkedAnswer, Checkpoint lastCheckpoint, BlobRef definition,
                   BlobRef inputs, Map<String, RunView> children, int eventCount, String head,
                   List<Intervention> interventions, List<ImageFacts> images, String aiNetwork,
                   Map<String, List<String>> aiHosts, String parkedBy, SealView seal) {
        this(runId, workflowId, workflowName, packageDir, packageDigest, packageCommit, packageDirty, actor, trigger,
                execution, startedAt, finishedAt, status, error, gate, steps, pendingQuestion, parkedAnswer,
                lastCheckpoint, definition, inputs, children, eventCount, head, interventions, images, aiNetwork,
                aiHosts, parkedBy, seal, List.of());
    }

    /** A view of a run whose package was not sealed. */
    public RunView(String runId, String workflowId, String workflowName, String packageDir, String packageDigest,
                   String packageCommit, Boolean packageDirty,
                   String actor, String trigger, String execution, Instant startedAt, Instant finishedAt,
                   RunStatus status, BlobRef error, GateView gate, List<StepView> steps, QuestionView pendingQuestion,
                   QuestionView.Answer parkedAnswer, Checkpoint lastCheckpoint, BlobRef definition,
                   BlobRef inputs, Map<String, RunView> children, int eventCount, String head,
                   List<Intervention> interventions, List<ImageFacts> images, String aiNetwork,
                   Map<String, List<String>> aiHosts, String parkedBy) {
        this(runId, workflowId, workflowName, packageDir, packageDigest, packageCommit, packageDirty, actor, trigger,
                execution, startedAt, finishedAt, status, error, gate, steps, pendingQuestion, parkedAnswer,
                lastCheckpoint, definition, inputs, children, eventCount, head, interventions, images, aiNetwork,
                aiHosts, parkedBy, null);
    }

    /** A view of a run that is not parked. */
    public RunView(String runId, String workflowId, String workflowName, String packageDir, String packageDigest,
                   String packageCommit, Boolean packageDirty,
                   String actor, String trigger, String execution, Instant startedAt, Instant finishedAt,
                   RunStatus status, BlobRef error, GateView gate, List<StepView> steps, QuestionView pendingQuestion,
                   QuestionView.Answer parkedAnswer, Checkpoint lastCheckpoint, BlobRef definition,
                   BlobRef inputs, Map<String, RunView> children, int eventCount, String head,
                   List<Intervention> interventions, List<ImageFacts> images, String aiNetwork,
                   Map<String, List<String>> aiHosts) {
        this(runId, workflowId, workflowName, packageDir, packageDigest, packageCommit, packageDirty, actor, trigger,
                execution, startedAt, finishedAt, status, error, gate, steps, pendingQuestion, parkedAnswer,
                lastCheckpoint, definition, inputs, children, eventCount, head, interventions, images, aiNetwork,
                aiHosts, null);
    }

    /** Whether the run is parked because it was asked to pause: it waits for no answer, only for its resume. */
    public boolean paused() {
        return status == RunStatus.PARKED && "pause".equals(parkedBy);
    }

    public RunView {
        aiHosts = aiHosts == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(aiHosts));
        steps = List.copyOf(steps);
        children = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(children));
        interventions = List.copyOf(interventions);
        images = images == null ? List.of() : List.copyOf(images);
        modelChoices = modelChoices == null ? List.of() : List.copyOf(modelChoices);
    }

    /** The newest {@code run.checkpoint}: its seq, the position that runs next and the state blob. */
    public record Checkpoint(int seq, int nextPosition, BlobRef state) {}

    /**
     * Something done to the run from outside its steps.
     *
     * @param type        the event type, such as {@code run.resumed}
     * @param actor       who did it; {@code null} when the event names nobody ({@code run.recovered})
     * @param description what happened, in one line of plain text
     * @param content     the blob that goes with it: the answer given, or the line set aside; {@code null} for none
     */
    public record Intervention(int seq, Instant at, String type, String actor, String description, BlobRef content) {}

    /**
     * @param events in log order; the first is the {@code run.started} of the root run
     * @throws IllegalArgumentException if there are no events, or an event lacks a field or has a value it needs
     *                                  (a {@code status} that is missing or unknown)
     */
    /**
     * What the run's containers asked the egress proxy for, child runs and earlier attempts of a step included.
     *
     * @param commands    the commands that could connect to listed hosts only (the AI calls)
     * @param connections the connections their containers asked for
     * @param denied      those that were denied: an observation, which fails no step and changes no gate
     */
    public record Egress(int commands, int connections, int denied) {}

    /**
     * How many commands of the run have an output whose reading ended at its bound, child runs and earlier attempts
     * of a step included: an observation, which fails no step and changes no gate.
     */
    public int outputCuts() {
        int cuts = 0;
        for (StepView step : steps) {
            cuts += cuts(step.commands());
            for (var attempt : step.earlierAttempts()) {
                cuts += cuts(attempt.commands());
            }
        }
        for (RunView child : children.values()) {
            cuts += child.outputCuts();
        }
        return cuts;
    }

    private static int cuts(List<CommandView> commands) {
        return (int) commands.stream().filter(command -> command.outputCut() != null).count();
    }

    /**
     * How many of {@link #outputCuts()} are commands of the latest attempt of a step of this run: the ones
     * {@code show} marks under their step. The others are of earlier attempts and of child runs.
     */
    public int outputCutsOfLatestAttempts() {
        int cuts = 0;
        for (StepView step : steps) {
            cuts += cuts(step.commands());
        }
        return cuts;
    }

    /** The egress of the whole run. */
    public Egress egress() {
        int[] sum = new int[3];
        for (StepView step : steps) {
            count(step.commands(), sum);
            step.earlierAttempts().forEach(attempt -> count(attempt.commands(), sum));
        }
        for (RunView child : children.values()) {
            Egress ofChild = child.egress();
            sum[0] += ofChild.commands();
            sum[1] += ofChild.connections();
            sum[2] += ofChild.denied();
        }
        return new Egress(sum[0], sum[1], sum[2]);
    }

    /**
     * Whether the run has an AI call that may fetch pages while its AI network is open: its {@code web_hosts} are
     * recorded, but nothing enforces them, since the container can connect anywhere. Child runs count.
     */
    public boolean webHostsNotEnforced() {
        return "open".equals(aiNetwork) && fetches();
    }

    private boolean fetches() {
        for (StepView step : steps) {
            if (step.aiCalls().stream().anyMatch(call -> AiCallView.FETCH.equals(call.web()))) {
                return true;
            }
            for (StepView.Attempt attempt : step.earlierAttempts()) {
                if (attempt.aiCalls().stream().anyMatch(call -> AiCallView.FETCH.equals(call.web()))) {
                    return true;
                }
            }
        }
        return children.values().stream().anyMatch(RunView::fetches);
    }

    private static void count(List<CommandView> commands, int[] sum) {
        for (CommandView command : commands) {
            if (command.throughProxy()) {
                sum[0]++;
            }
            int[] connections = command.connections();
            sum[1] += connections[0];
            sum[2] += connections[1];
        }
    }

    public static RunView of(List<RecordedEvent> events, boolean writerAlive) {
        return RunViewBuilder.build(events, writerAlive);
    }
}
