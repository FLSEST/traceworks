package traceworks.core.app;

import traceworks.core.container.IdleScratch;
import traceworks.core.container.RunPaths;
import traceworks.core.engine.PresetAnswer;
import traceworks.core.engine.ResumePoint;
import traceworks.core.record.RunSnapshotJson;
import traceworks.core.engine.RunOptions;
import traceworks.core.engine.RunOutcome;
import traceworks.core.engine.WorkflowEngine;
import traceworks.core.model.RunSnapshot;
import traceworks.core.model.RunStatus;
import traceworks.core.model.TriggerSource;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.AiNetwork;
import traceworks.core.port.LocalModels;
import traceworks.core.port.Clock;
import traceworks.core.port.ContainerRuntime;
import traceworks.core.port.InboxRequest;
import traceworks.core.port.LoadedPackage;
import traceworks.core.port.StoredRun;
import traceworks.core.record.QuestionView;
import traceworks.core.record.RecordTypes;
import traceworks.core.record.RecordedEvent;
import traceworks.core.record.RunRecorder;
import traceworks.core.record.RunView;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The resume use case; {@link RunServices#resume} is its contract. The package the run resumes with comes from
 * {@link StoredRunPackage}.
 */
final class Resuming {

    private final Clock clock;
    private final String actor;
    private final boolean hostConsent;
    private final int outputLimitMib;
    private final int runOutputBudgetMib;
    private final Map<String, String> aiImages;
    private final AiNetwork aiNetwork;
    /** The model servers of the operator's now ({@code local_models}), which {@code run.resumed} names. */
    private final LocalModels localModels;
    private final StoredRunPackage storedPackage;
    private final Supplier<Optional<ContainerRuntime>> findRuntime;
    private final Checkouts checkouts;
    private final RunOutputs outputs;
    private final WorkflowEngine engine;
    private final RunPreparation preparation;
    private final ActiveRuns active;
    private final Cancelling cancelling;
    private final SealAdmission seals;

    /**
     * @param hostConsent    whether the operator config allows host runs; without it, a host run's resume needs
     *                       {@code --allow-host-execution}
     * @param outputLimitMib the output limit the commands of the resumed run get, the operator's now, which
     *                       {@code run.resumed} records
     * @param runOutputBudgetMib the output budget of the resumed run, the operator's now, which {@code run.resumed}
     *                       records
     * @param aiImages       the operator's images for AI CLIs now, which {@code run.resumed} records
     * @param aiNetwork      where the containers of the AI CLIs can connect now ({@code ai_network},
     *                       {@code ai_hosts}), which {@code run.resumed} records
     * @param storedPackage  resolves the package the run runs with, from its record
     * @param findRuntime    finds the container runtime; asked once per resume
     * @param checkouts      restores the checkout of a run's source, and removes it after the run's end
     * @param active         the runs this process executes, which the resumed run joins while it runs
     * @param cancelling     ends the run instead when a cancel waits for it
     * @param seals          the seal check: a run whose package's seal does not hold does not go on
     */
    Resuming(Clock clock, String actor, boolean hostConsent, int outputLimitMib, int runOutputBudgetMib,
             Map<String, String> aiImages, AiNetwork aiNetwork, LocalModels localModels,
             StoredRunPackage storedPackage,
             Supplier<Optional<ContainerRuntime>> findRuntime, Checkouts checkouts, RunOutputs outputs,
             WorkflowEngine engine, RunPreparation preparation, ActiveRuns active, Cancelling cancelling,
             SealAdmission seals) {
        this.seals = seals;
        this.clock = clock;
        this.actor = actor;
        this.hostConsent = hostConsent;
        this.outputLimitMib = outputLimitMib;
        this.runOutputBudgetMib = runOutputBudgetMib;
        this.aiImages = Map.copyOf(aiImages);
        this.aiNetwork = aiNetwork;
        this.localModels = localModels == null ? LocalModels.NONE : localModels;
        this.storedPackage = storedPackage;
        this.findRuntime = findRuntime;
        this.checkouts = checkouts;
        this.outputs = outputs;
        this.engine = engine;
        this.preparation = preparation;
        this.active = active;
        this.cancelling = cancelling;
    }

    /**
     * Resumes {@code run}, which the caller opened, as {@link RunServices#resume} says.
     *
     * @param warnings gets each warning for the operator, before the run goes on
     */
    RunResult resume(StoredRun run, ResumeRequest request, Consumer<RecordedEvent> listener, QuestionAsker asker,
                     Consumer<String> warnings) {
        String runId = run.runId();
        if (!run.tryLockWriter()) {
            throw new RunRefusedException("Another process is writing run " + runId
                    + "; resume it when that process has ended");
        }
        // Nobody runs the run now: a container its last process left behind can still write into its mounts
        ContainerRuntime runtime = findRuntime.get().orElse(null);
        List<RecordedEvent> events = RunRecords.events(run);
        LeftoverContainers.requireRemoved(runtime, runId, LeftoverContainers.mayHaveContainers(events),
                "Run " + runId + " cannot resume");
        RunView view = RunRecords.view(runId, events, false);
        if (!RunRecords.isFinal(view.status()) && cancelRecorded(events)) {
            // A cancel was asked for, and its process ended before the run did: it is carried out, not continued.
            // This runs nothing, so it needs no consent and no start check.
            return cancelling.finish(run, null, List.of());
        }
        RunTarget target = target(runId, view, request.allowHostExecution());
        if (RunRecords.isFinal(view.status())) {
            return completeFinalisation(run, view);
        }
        // A run parked at a question brings its answer. A paused or interrupted one has none of its own, but the
        // step it replays may still be owed the answer an earlier resume went on with
        Given given = view.status() == RunStatus.PARKED && !view.paused() ? parked(run, view)
                : carried(run, events, view.lastCheckpoint());
        PresetAnswer preset = given == null ? null : given.preset();
        RunView.Checkpoint checkpoint = view.lastCheckpoint();
        if (checkpoint == null) {
            throw new RunRefusedException("Run " + runId + " has no checkpoint to resume from");
        }
        StoredRunPackage.Resolved resolved = storedPackage.resolve(run, view);
        LoadedPackage pkg = resolved.runs();
        // The seal before the question of a changed package: a seal that does not hold is no matter of a flag
        checkSeal(runId, view, resolved.current(), request.attended());
        boolean changed = StoredRunPackage.packageChanged(runId, view, pkg, request.allowChangedPackage());
        if (changed) {
            storedPackage.refuseARedactedDefinition(runId, view, pkg);
        }
        refuseFarBeyondTheBudget(run, checkpoint);
        RunSnapshot snapshot = snapshot(run, checkpoint);
        refuseBeyondTheBudget(runId, snapshot);
        // What the record holds redacted is made real only for the names this run redacts
        Supplier<List<String>> redacted = () -> preparation.redactedNames(pkg);
        Path workspace = recordedWorkspace(runId, events, preparation, redacted);
        Checkouts.Recorded source = Checkouts.Recorded.of(RunRecords.started(events));
        // A missing checkout is cloned again from the source as the record names it. A replacement in it is never
        // made real: the value would go to the host the record names, and a record can be rewritten
        if (source != null) {
            preparation.refuseForeignReplacements("The source of run " + runId, source.source(), redacted);
        }
        Checkouts.Restored restored = source == null ? null : checkouts.restore(runId, source, run.inStore());
        if (restored != null) {
            workspace = restored.directory();
        }
        // A resumed run keeps its picked models: the inputs its checkpoint holds, with the option run.started
        // recorded for each choice in place. A run that picked nothing so keeps the default it started with, also
        // when the package changed its default since; a package that lost the option is not resumed
        Map<String, String> recordedPicks = new LinkedHashMap<>();
        view.modelChoices().forEach(pick -> recordedPicks.put(pick.choice(), pick.option()));
        Map<String, Object> pickInputs = new LinkedHashMap<>(snapshot.inputContext());
        pickInputs.putAll(recordedPicks);
        RunPreparation.Prepared prepared = preparation.prepare(target, runtime, pkg, workspace, pickInputs,
                "Run " + runId + " cannot resume");
        // The register folder is the one the run started with, whatever the register holds now: a file that is no
        // longer the one run.started recorded stops the resume, before anything is recorded
        RegisterFolder folder = view.register() == null ? null : new RegisterFolder(run, view.register());
        String folderProblem = folder == null ? null : folder.problem();
        if (folderProblem != null) {
            throw new RunRefusedException("Run " + runId + " cannot resume: " + folderProblem
                    + " ('traceworks verify " + runId + "' says the same)");
        }
        RunPaths paths = preparation.paths(run, prepared.workspace(), prepared,
                folder == null ? null : run.registerDirectory());
        // A cancel sent while this resume prepared waits in the inbox, as one left there earlier does: it is meant for
        // this process, which ends the run without a step. The answers taken with it are refused below as stale.
        List<InboxRequest> waiting = run.inbox().take();
        if (waiting.stream().anyMatch(RunRecords::isCancel)) {
            return cancelling.finish(run, null, waiting);
        }
        changedCredentials(runId, view, events, prepared).ifPresent(warnings);

        try {
            WorkflowDefinition definition = pkg.definition();
            byte[] torn = run.events().discardTornTail();
            RunRecorder recorder = RunRecorder.continueAfter(run.events(), run.blobs(), clock, runId, definition,
                    events).redacting(prepared.redactor()).withScratch(new IdleScratch(run, runtime, runId));
            RunOutcome outcome;
            try (RunSession session = new RunSession(run, recorder, actor, listener, asker)) {
                RunRecords.recordRecovery(recorder, torn);
                RunRecords.refuseStale(recorder, waiting);
                Map<String, Object> resumed = new LinkedHashMap<>();
                resumed.put("actor", actor);
                resumed.put("from_checkpoint", checkpoint.seq());
                resumed.put("next_position", checkpoint.nextPosition());
                resumed.put("rerun_step", rerunStep(events, checkpoint));
                resumed.put("answer", given == null ? null : given.seq());
                resumed.put("package_changed", changed);
                resumed.put("package_digest", pkg.digest());
                resumed.put("execution", target.recorded());
                resumed.put("container_runtime", runtimeOf(runtime));
                resumed.put("output_limit_mib", outputLimitMib);
                resumed.put("records", traceworks.core.record.RunRecorder.RECORDS);
                resumed.put("run_output_budget_mib", runOutputBudgetMib);
                resumed.put("ai_images", new TreeMap<>(aiImages));
                resumed.put("ai_network", aiNetwork.mode());
                resumed.put("ai_hosts", aiNetwork.hosts());
                if (!localModels.isEmpty()) {
                    // Only for a home that declares a model server: every other run.resumed keeps its fields
                    resumed.put("local_models", traceworks.core.record.RunRecorder.localModels(localModels));
                }
                if (restored != null) {
                    resumed.put("workspace_cloned", restored.cloned());
                }
                if (!prepared.modelPicks().isEmpty()) {
                    // Only for a workflow with a model choice: every other run.resumed keeps its fields
                    Map<String, Object> picks = new LinkedHashMap<>();
                    prepared.modelPicks().forEach(pick -> {
                        Map<String, Object> choice = new LinkedHashMap<>();
                        choice.put("option", pick.option());
                        choice.put("default", pick.theDefault());
                        choice.put("provider", pick.provider());
                        choice.put("model", pick.model());
                        picks.put(pick.choice(), choice);
                    });
                    resumed.put("model_choices", picks);
                }
                recorder.record(RecordTypes.RUN_RESUMED, resumed);
                if (preset != null) {
                    session.replay(preset.stepIndex(), given.actor());
                }
                RunOptions options = preparation.options(runId, session, recorder, paths, prepared,
                        TriggerSource.RESUME, asker == null, source == null ? null : source.sha(), folder)
                        .toBuilder().resumedPicks(recordedPicks).build();
                // The loop that was in flight takes over the items the record holds as finished
                ResumePoint point = new ResumePoint(snapshot, checkpoint.nextPosition(), preset,
                        ItemsOnRecord.of(run, events, rerunStep(events, checkpoint)));
                outcome = active.execute(session, runId, runtime, () -> engine.resume(definition, point, options));
            }
            RunResult.KeptCheckout kept = source == null ? null : checkouts.afterRun(run, outcome.status());
            RegisterOutcome register = outputs.finish(run, pkg.catalogJson());
            return prepared.result(runId, run.location(), outcome, kept, register);
        } catch (RuntimeException e) {
            if (source != null) {
                checkouts.noteKept(runId, e);   // the resumed run ended in a failure: no summary will name it
            }
            throw e;
        }
    }

    /**
     * The warning that {@code credentials.json} was modified after the run stopped, when it holds the value of a
     * secret the run declares or of a credential of an AI provider it uses: the resume redacts with the values it read
     * now, so an old value that a later step prints would be recorded in clear. The run stopped at its latest
     * {@code run.parked} when it is parked, else at its last line. Only the file's modification time is compared, and
     * only names are given: nothing derived from a value is kept, so this cannot tell whether a value changed. The
     * environment has no such time.
     *
     * @return empty when no values were read, the file is not there or older than the stop, or holds none of the
     * run's names
     */
    private Optional<String> changedCredentials(String runId, RunView view, List<RecordedEvent> events,
                                                RunPreparation.Prepared prepared) {
        SecretValues values = prepared.values();
        Instant modified = values == null ? null : values.fileModified().orElse(null);
        Instant stopped = stoppedAt(view, events);
        if (modified == null || stopped == null || !modified.isAfter(stopped)) {
            return Optional.empty();
        }
        List<String> names = new TreeSet<>(preparation.redactedNames(prepared.pkg())).stream()
                .filter(values::inFile).toList();
        if (names.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("credentials.json in the Traceworks home was modified after run " + runId + " stopped ("
                + stopped + "), and it holds the values of " + String.join(", ", names) + ". The resume redacts with"
                + " the values it holds now: if one of them changed, an old value that a later step prints is"
                + " recorded in clear (docs/security.md, \"A resume redacts with the values it has at the resume\")");
    }

    /**
     * When the run stopped: its latest {@code run.parked} when it is parked, else its last line; {@code null} for a
     * record without lines.
     */
    private static Instant stoppedAt(RunView view, List<RecordedEvent> events) {
        if (view.status() == RunStatus.PARKED) {
            for (int i = events.size() - 1; i >= 0; i--) {
                RecordedEvent event = events.get(i);
                if (event.parent() == null && RecordTypes.RUN_PARKED.equals(event.type())) {
                    return event.at();
                }
            }
        }
        return events.isEmpty() ? null : events.getLast().at();
    }

    /** Whether the record holds a {@code run.cancel_requested} of the run itself. */
    private static boolean cancelRecorded(List<RecordedEvent> events) {
        return events.stream().anyMatch(e -> e.parent() == null && RecordTypes.RUN_CANCEL_REQUESTED.equals(e.type()));
    }

    /**
     * Refuses a resume whose checkpoint certainly holds more output text than the budget in force, from the sizes its
     * references name and before any text is read: a run parked with a far larger budget must not exhaust the heap of
     * the process that resumes it. The steps' inputs and outputs count against the budget in any case, and a text
     * takes at least half its UTF-8 size in memory, so half their sizes is a bound below what the run holds;
     * {@link #refuseBeyondTheBudget} checks the exact amount once the texts are read. A state that cannot be read is
     * left to that read, which refuses it.
     */
    private void refuseFarBeyondTheBudget(StoredRun run, RunView.Checkpoint checkpoint) {
        if (checkpoint.state() == null) {
            return;
        }
        List<RunSnapshotJson.Reference> references;
        try {
            Optional<byte[]> state = RunRecords.checkedContent(run, checkpoint.state(), "checkpoint state");
            if (state.isEmpty()) {
                return;
            }
            references = RunSnapshotJson.references(state.get());
        } catch (IllegalArgumentException e) {
            return;
        }
        long utf8 = 0;
        for (RunSnapshotJson.Reference reference : references) {
            if (reference.field().startsWith("steps[")) {
                utf8 += reference.blob().size();
            }
        }
        long atLeast = utf8 / 2;
        if (atLeast > runOutputBudgetMib * 1024L * 1024L) {
            throw beyondTheBudget(run.runId(), "at least " + mib(atLeast));
        }
    }

    private RunRefusedException beyondTheBudget(String runId, String holds) {
        return new RunRefusedException("Run " + runId + " cannot resume: its checkpoint holds " + holds
                + " MiB of output text in memory, more than its output budget of " + runOutputBudgetMib + " MiB;"
                + " raise run_output_budget_mib in traceworks.json to resume it");
    }

    private static String mib(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0));
    }

    /**
     * Refuses a resume whose checkpoint holds more output text than the budget in force: the run could not take a
     * step. Nothing is recorded, so a parked answer stays for a resume with a larger budget.
     *
     * @throws RunRefusedException naming what the run holds, the budget and the setting
     */
    private void refuseBeyondTheBudget(String runId, RunSnapshot snapshot) {
        long held = WorkflowEngine.heldOnResume(snapshot);
        long budget = runOutputBudgetMib * 1024L * 1024L;
        if (held > budget) {
            throw beyondTheBudget(runId, mib(held));
        }
    }

    /**
     * The target the run keeps, as its start recorded it.
     *
     * @param allowedForThisResume whether the operator allowed a host run for this resume, in addition to the setting
     * @throws RunRefusedException if the record names a target Traceworks does not know, or a host run lacks the
     *                             consent
     */
    private RunTarget target(String runId, RunView view, boolean allowedForThisResume) {
        RunTarget target = RunTarget.ofRecorded(view.execution());
        if (target == null) {
            throw new RunRefusedException("Run " + runId + " records the execution '" + view.execution()
                    + "', which is neither container nor host; it cannot be resumed");
        }
        if (target == RunTarget.HOST) {
            // Before the package is loaded: a run folder copied to another machine must not run host commands
            // there without that operator's consent
            requireHostConsent(runId, allowedForThisResume);
        }
        return target;
    }

    /**
     * @param allowedForThisResume whether the operator allowed a host run for this resume, in addition to the setting
     * @throws RunRefusedException unless host execution is allowed
     */
    private void requireHostConsent(String runId, boolean allowedForThisResume) {
        if (!allowedForThisResume && !hostConsent) {
            throw new RunRefusedException("Host execution is not allowed: run " + runId + " is a host run, whose"
                    + " shell steps without an image run on the host, and a resume needs the same consent as its"
                    + " start. Allow it for this resume with --allow-host-execution, or on this machine with"
                    + " \"allow_host_execution\": true in traceworks.json");
        }
    }

    /**
     * A finished run: a finalisation that failed (no git, a full disk) is completed here; the log stays as it is.
     *
     * @return what was written, in {@link RunResult#completed()}
     * @throws RunRefusedException if there is nothing to complete
     */
    private RunResult completeFinalisation(StoredRun run, RunView view) {
        byte[] catalogJson;
        try {
            catalogJson = storedPackage.recordedPackage(run, view).catalogJson();
        } catch (RuntimeException e) {
            catalogJson = null;   // the package is gone: the register takes the report as it is, without a catalog
        }
        List<String> completed = outputs.complete(run, catalogJson);
        if (completed.isEmpty()) {
            throw new RunRefusedException("Run " + run.runId() + " has finished (" + view.status()
                    + "); a finished run cannot be resumed");
        }
        return new RunResult(run.runId(), run.location(), view.status(), RunRecords.gateOf(view), null, completed);
    }

    /**
     * The run's state at the checkpoint.
     *
     * @throws RunRefusedException    if the record names no state, or its blob or a text it refers to is deleted
     * @throws RecordDamagedException if the state, or a text it refers to, is changed
     */
    private static RunSnapshot snapshot(StoredRun run, RunView.Checkpoint checkpoint) {
        if (checkpoint.state() == null) {
            throw new RunRefusedException("Run " + run.runId() + " has no checkpoint state in its record");
        }
        return RunRecords.snapshot(run, checkpoint.state()).orElseThrow(() ->
                new RunRefusedException("The checkpoint state of run " + run.runId() + " is deleted (blob "
                        + checkpoint.state().sha256() + ")"));
    }

    /**
     * The workspace {@code run.started} records, at its real path ({@link RunPreparation#realPath}); {@code null} for
     * none.
     */
    private static Path recordedWorkspace(String runId, List<RecordedEvent> events, RunPreparation preparation,
                                          Supplier<List<String>> redacted) {
        String workspace = RunRecords.started(events).fields().path("workspace").asText(null);
        try {
            return workspace == null ? null
                    : Path.of(preparation.realPath("The workspace of run " + runId, workspace, redacted));
        } catch (InvalidPathException e) {
            throw new RunRefusedException("The workspace of run " + runId + " (" + workspace + ") is not a path");
        }
    }

    /**
     * The step that runs again: the one at the checkpoint's next position, when it started after the checkpoint,
     * whether it was in flight, finished without a newer checkpoint, or asked the question the run parked at.
     * {@code null} when that position has not started since the checkpoint.
     */
    private static Integer rerunStep(List<RecordedEvent> events, RunView.Checkpoint checkpoint) {
        for (RecordedEvent event : events) {
            if (event.seq() > checkpoint.seq() && event.parent() == null
                    && RecordTypes.STEP_STARTED.equals(event.type())
                    && event.fields().path("position").isInt()
                    && event.fields().path("position").intValue() == checkpoint.nextPosition()) {
                return event.step();
            }
        }
        return null;
    }

    /**
     * An answer the resumed step receives: the preset, the seq of its {@code approval.answered} and who gave it.
     */
    private record Given(PresetAnswer preset, int seq, String actor) {}

    /** The answer recorded for a parked run, as the resumed step receives it. */
    private static Given parked(StoredRun run, RunView view) {
        QuestionView question = view.pendingQuestion();
        QuestionView.Answer answer = view.parkedAnswer();
        if (question == null) {
            throw new RunRefusedException("The parked question of run " + run.runId() + " is missing from its record");
        }
        if (answer == null) {
            String how = "human-input".equals(question.kind())
                    ? "traceworks answer " + run.runId() + " --value <text>"
                    : "traceworks approve " + run.runId() + " --answer <option>";
            throw new RunRefusedException("Run " + run.runId() + " waits for an answer to step " + question.step()
                    + "; give it with " + how + ", then resume");
        }
        return new Given(new PresetAnswer(answer.step(), question.kind(),
                RunRecords.text(run, question.question(), "parked question"),
                RunRecords.text(run, answer.answer(), "parked answer")), answer.seq(), answer.actor());
    }

    /**
     * The answer an earlier resume went on with, when that resume ended before the run reached a newer checkpoint (its
     * process was killed, its disk filled): the run is interrupted, but the step it replays still takes the answer the
     * record holds, rather than asking again. {@code null} when the last {@code run.resumed} carried no answer, or
     * the run went on past its checkpoint.
     */
    private static Given carried(StoredRun run, List<RecordedEvent> events, RunView.Checkpoint checkpoint) {
        RecordedEvent resumed = null;
        for (RecordedEvent event : events) {
            if (event.parent() == null && RecordTypes.RUN_RESUMED.equals(event.type())) {
                resumed = event;
            }
        }
        if (checkpoint == null || resumed == null || !resumed.fields().path("answer").isInt()
                || resumed.fields().path("from_checkpoint").asInt(-1) != checkpoint.seq()) {
            return null;
        }
        int seq = resumed.fields().path("answer").intValue();
        RecordedEvent answered = null;
        RecordedEvent asked = null;
        for (RecordedEvent event : events) {
            if (event.seq() >= seq) {
                answered = event.seq() == seq ? event : null;
                break;
            }
            if (event.parent() == null && RecordTypes.APPROVAL_REQUESTED.equals(event.type())) {
                asked = event;
            }
        }
        if (answered == null || answered.parent() != null || answered.step() == null
                || !RecordTypes.APPROVAL_ANSWERED.equals(answered.type())
                || asked == null || !answered.step().equals(asked.step())) {
            return null;
        }
        QuestionView question = QuestionView.of(asked);
        return new Given(new PresetAnswer(answered.step(), question.kind(),
                RunRecords.text(run, question.question(), "parked question"),
                RunRecords.text(run, answered.blob("answer"), "parked answer")), seq,
                answered.fields().path("actor").asText(null));
    }

    /** {@code {name, version}} of the runtime as the record names it; {@code null} for none. */
    private static Map<String, Object> runtimeOf(ContainerRuntime runtime) {
        if (runtime == null) {
            return null;
        }
        Map<String, Object> named = new LinkedHashMap<>();
        named.put("name", runtime.name());
        named.put("version", runtime.version());
        return named;
    }

    /**
     * The seal check of a resume. The package as it is now is judged as a start judges it. Beyond that, a run that
     * started with a sealed package goes on only while the package carries a seal that holds with the digest the run
     * started with; and a run of a package that is sealed now, and differs from the package the run started with, does
     * not go on: it would run the definition recorded at its start, which the seal does not cover. No flag changes
     * either. Each refusal is recorded in the seal log.
     *
     * @throws RunRefusedException if the seal check refuses
     */
    private void checkSeal(String runId, RunView view, LoadedPackage current, boolean attended) {
        SealStatus now = seals.admit(current, attended, "resume", runId);
        boolean sealedNow = now.state() == SealStatus.State.SEALED;
        if (view.seal() != null && !(sealedNow && now.seal().digest().equals(view.seal().digest()))) {
            throw seals.refuse(current, SealStatus.refused("seal-differs-from-start", "Run " + runId
                    + " started with a sealed package (seal digest " + view.seal().digest() + "), and the package "
                    + current.directory() + " is " + (sealedNow ? "sealed with the digest " + now.seal().digest()
                    : "not sealed") + " now, so the run cannot go on as the run it started as: restore the package"
                    + " and its seal as they were, or end the run with 'traceworks cancel " + runId
                    + "' and start a new one", now.seal(), now.digest()), "resume", runId);
        }
        if (view.seal() == null && sealedNow && !java.util.Objects.equals(current.digest(), view.packageDigest())) {
            throw seals.refuse(current, SealStatus.refused("sealed-since-start", "The package " + current.directory()
                    + " is sealed now and differs from the package run " + runId + " started with. A resume would run"
                    + " the definition recorded at its start, which the seal does not cover: end the run with"
                    + " 'traceworks cancel " + runId + "' and start a new one", now.seal(), now.digest()),
                    "resume", runId);
        }
    }
}
