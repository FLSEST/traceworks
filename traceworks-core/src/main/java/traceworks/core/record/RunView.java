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
 * not carry them; what a child was loaded from, when that is not the package the run started with, is its
 * {@code packageCall}.
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
 * @param ledgerStart     whether {@code run.started} says that the ledger noted the start of the run
 *                        ({@code ledger_start}); not for a record of a version that noted no start, and not for a
 *                        child run, which has no entry of its own
 * @param runStarted      the SHA-256 of the {@code run.started} line, which the start entry of the ledger and the
 *                        manifest name; {@code null} for a child run
 * @param recordsOutputCut whether the record can name a cut of an output ({@code output_cut}): its
 *                        {@code run.started} and every {@code run.resumed} say so in {@code records}. Not for a
 *                        record an earlier version wrote or resumed, whose silence about a cut says nothing
 * @param project         the key of the project the run works on, as {@code run.started} recorded it; {@code null}
 *                        for a run that named none, a record of an earlier version and a child's view
 * @param recordsCalledPackages whether the record names the package of every child run that was not loaded
 *                        from the package the run started with ({@code run.package}): its {@code run.started} and every
 *                        {@code run.resumed} say so in {@code records}. Not for a record an earlier version wrote
 *                        or resumed, whose silence about a called package says nothing
 * @param packageCall     in a child's view: the package the child's workflow was loaded from, when that is
 *                        not what the run started with (another package, or the run's own with another digest);
 *                        {@code null} for the run itself, for a child of the run's own package as it started and
 *                        in a record of an earlier version
 * @param resumedWith     the packages the run went on with after its start, without repeats: each
 *                        {@code run.resumed} goes on with its {@code package_digest} when it says
 *                        {@code package_changed}, else with the package of the start; of entries that follow each
 *                        other and are equal one stays, and the start's own at the head is dropped. So a run
 *                        resumed twice with one changed package names it once, and one that went back to the
 *                        package of its start names that after the other. Empty for a run that only ever went on
 *                        with the package of its start, and in a child's view. The two fields of
 *                        {@code run.resumed} are older than the manifest's list, so this is read from a record
 *                        of any version
 * @param recordsResumedWith whether the version that wrote the run's last {@code run.started} or
 *                        {@code run.resumed}, which is the one that wrote its manifest, lists {@code resumedWith}
 *                        in the manifest ({@code resumed_with} in {@code records}). A manifest of a version that
 *                        did not has no such field, whatever the record's resumes say
 */
public record RunView(String runId, String workflowId, String workflowName, String packageDir, String packageDigest,
                      String packageCommit, Boolean packageDirty,
                      String actor, String trigger, String execution, Instant startedAt, Instant finishedAt,
                      RunStatus status, BlobRef error, GateView gate, List<StepView> steps, QuestionView pendingQuestion,
                      QuestionView.Answer parkedAnswer, Checkpoint lastCheckpoint, BlobRef definition,
                      BlobRef inputs, Map<String, RunView> children, int eventCount, String head,
                      List<Intervention> interventions, List<ImageFacts> images, String aiNetwork,
                      Map<String, List<String>> aiHosts, String parkedBy, SealView seal,
                      List<ModelPick> modelChoices, RegisterHandover register, boolean ledgerStart,
                      String runStarted, boolean recordsOutputCut, String project,
                      boolean recordsCalledPackages, PackageCall packageCall, List<String> resumedWith,
                      boolean recordsResumedWith) {

    /** A view of a run that was resumed with no other package than the one it started with. */
    public RunView(String runId, String workflowId, String workflowName, String packageDir, String packageDigest,
                   String packageCommit, Boolean packageDirty,
                   String actor, String trigger, String execution, Instant startedAt, Instant finishedAt,
                   RunStatus status, BlobRef error, GateView gate, List<StepView> steps, QuestionView pendingQuestion,
                   QuestionView.Answer parkedAnswer, Checkpoint lastCheckpoint, BlobRef definition,
                   BlobRef inputs, Map<String, RunView> children, int eventCount, String head,
                   List<Intervention> interventions, List<ImageFacts> images, String aiNetwork,
                   Map<String, List<String>> aiHosts, String parkedBy, SealView seal,
                   List<ModelPick> modelChoices, RegisterHandover register, boolean ledgerStart,
                   String runStarted, boolean recordsOutputCut, String project,
                   boolean recordsCalledPackages, PackageCall packageCall) {
        this(runId, workflowId, workflowName, packageDir, packageDigest, packageCommit, packageDirty, actor, trigger,
                execution, startedAt, finishedAt, status, error, gate, steps, pendingQuestion, parkedAnswer,
                lastCheckpoint, definition, inputs, children, eventCount, head, interventions, images, aiNetwork,
                aiHosts, parkedBy, seal, modelChoices, register, ledgerStart, runStarted, recordsOutputCut, project,
                recordsCalledPackages, packageCall, List.of(), false);
    }

    /** A view of a run whose record names no called package, as an earlier version wrote it. */
    public RunView(String runId, String workflowId, String workflowName, String packageDir, String packageDigest,
                   String packageCommit, Boolean packageDirty,
                   String actor, String trigger, String execution, Instant startedAt, Instant finishedAt,
                   RunStatus status, BlobRef error, GateView gate, List<StepView> steps, QuestionView pendingQuestion,
                   QuestionView.Answer parkedAnswer, Checkpoint lastCheckpoint, BlobRef definition,
                   BlobRef inputs, Map<String, RunView> children, int eventCount, String head,
                   List<Intervention> interventions, List<ImageFacts> images, String aiNetwork,
                   Map<String, List<String>> aiHosts, String parkedBy, SealView seal,
                   List<ModelPick> modelChoices, RegisterHandover register, boolean ledgerStart,
                   String runStarted, boolean recordsOutputCut, String project) {
        this(runId, workflowId, workflowName, packageDir, packageDigest, packageCommit, packageDirty, actor, trigger,
                execution, startedAt, finishedAt, status, error, gate, steps, pendingQuestion, parkedAnswer,
                lastCheckpoint, definition, inputs, children, eventCount, head, interventions, images, aiNetwork,
                aiHosts, parkedBy, seal, modelChoices, register, ledgerStart, runStarted, recordsOutputCut, project,
                false, null);
    }

    /** A view of a run that names no project. */
    public RunView(String runId, String workflowId, String workflowName, String packageDir, String packageDigest,
                   String packageCommit, Boolean packageDirty,
                   String actor, String trigger, String execution, Instant startedAt, Instant finishedAt,
                   RunStatus status, BlobRef error, GateView gate, List<StepView> steps, QuestionView pendingQuestion,
                   QuestionView.Answer parkedAnswer, Checkpoint lastCheckpoint, BlobRef definition,
                   BlobRef inputs, Map<String, RunView> children, int eventCount, String head,
                   List<Intervention> interventions, List<ImageFacts> images, String aiNetwork,
                   Map<String, List<String>> aiHosts, String parkedBy, SealView seal,
                   List<ModelPick> modelChoices, RegisterHandover register, boolean ledgerStart,
                   String runStarted, boolean recordsOutputCut) {
        this(runId, workflowId, workflowName, packageDir, packageDigest, packageCommit, packageDirty, actor, trigger,
                execution, startedAt, finishedAt, status, error, gate, steps, pendingQuestion, parkedAnswer,
                lastCheckpoint, definition, inputs, children, eventCount, head, interventions, images, aiNetwork,
                aiHosts, parkedBy, seal, modelChoices, register, ledgerStart, runStarted, recordsOutputCut, null);
    }

    /**
     * A view of a run whose record does not say that the ledger noted its start, nor whether it names a cut of an
     * output: an earlier version wrote it.
     */
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
                aiHosts, parkedBy, seal, modelChoices, register, false, null, false);
    }

    /** One line about the start entry: whether the record says the ledger noted the start of the run. */
    public String ledgerStartLine() {
        return ledgerStart ? "start noted in the ledger: yes (traceworks verify compares the entry)"
                : "start noted in the ledger: no (recorded by a version that noted no start)";
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
        resumedWith = List.copyOf(resumedWith);
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
     * @param agent       for an answer: the agent that gave it under the actor, as it named itself; {@code null}
     *                    for none, and for everything that is no answer. A view names it after the description
     * @param description what happened, in one line of plain text
     * @param content     the blob that goes with it: the answer given, or the line set aside; {@code null} for none
     */
    public record Intervention(int seq, Instant at, String type, String actor, String agent, String description,
                               BlobRef content) {

        /** An intervention no agent claims. */
        public Intervention(int seq, Instant at, String type, String actor, String description, BlobRef content) {
            this(seq, at, type, actor, null, description, content);
        }
    }

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

    /**
     * The packages the child runs of this run were loaded from, at every depth, in the order the record names
     * them: one per child run that was not loaded from the package the run started with, so a package called
     * twice is here twice.
     */
    public List<PackageCall> packageCalls() {
        List<PackageCall> calls = new java.util.ArrayList<>();
        for (RunView child : children.values()) {
            if (child.packageCall() != null) {
                calls.add(child.packageCall());
            }
            calls.addAll(child.packageCalls());
        }
        return calls;
    }

    /**
     * {@link #packageCalls()} with each package once, for a view that names them: a loop that runs the workflow of
     * one package per item is one line. Calls are the same when workflow, digest and directory are; a package that
     * changed between two calls is there with each digest.
     */
    public List<PackageCall> distinctPackageCalls() {
        Map<List<String>, PackageCall> distinct = new java.util.LinkedHashMap<>();
        for (PackageCall call : packageCalls()) {
            distinct.putIfAbsent(java.util.Arrays.asList(call.workflowId(), call.packageDigest(), call.packageDir()),
                    call);
        }
        return List.copyOf(distinct.values());
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
