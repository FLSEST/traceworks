package traceworks.core.app;

import traceworks.core.container.IdleScratch;
import traceworks.core.container.ImageCatalog;
import traceworks.core.container.ImageFacts;
import traceworks.core.container.RunPaths;
import traceworks.core.engine.RunOptions;
import traceworks.core.engine.RunOutcome;
import traceworks.core.engine.WorkflowEngine;
import traceworks.core.model.ModelPick;
import traceworks.core.model.RunStatus;
import traceworks.core.model.TriggerSource;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.BlobRef;
import traceworks.core.port.Clock;
import traceworks.core.port.ContainerRuntime;
import traceworks.core.port.Ids;
import traceworks.core.port.Ledger;
import traceworks.core.port.LoadedPackage;
import traceworks.core.port.PackageImage;
import traceworks.core.port.RunEvent.RunFinished;
import traceworks.core.port.RunStore;
import traceworks.core.port.StoredRun;
import traceworks.core.record.RecordTypes;
import traceworks.core.record.RecordedEvent;
import traceworks.core.record.RegisterHandover;
import traceworks.core.record.RunRecorder;
import traceworks.core.record.RunStart;
import traceworks.core.record.SealView;
import traceworks.core.record.StartEntry;
import traceworks.core.seal.Seal;
import traceworks.core.util.Message;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The start use case; {@link RunServices#start} is its contract. It holds the whole start of a run of a loaded
 * package: the refusals before a run exists (a reserved input, the start check, a path or source the record could not
 * tell from a redacted one, a ledger that cannot take an entry), the inputs of an earlier run, the checkout of a
 * source, the run's creation, what {@code run.started} records, the start entry of the ledger without which no step
 * runs, the run in the engine, and what is written at its end.
 */
final class Starting {

    /** The run variable the engine sets to the package directory as a step sees it; no input may set it. */
    static final String PACKAGE_DIR = "package_dir";

    /** The run variable the engine sets to the register folder of an audit run; no input may set it. */
    static final String REGISTER_DIR = traceworks.core.engine.RunVariables.REGISTER_DIR;

    private final RunStore store;
    private final Ledger ledger;
    private final Clock clock;
    private final Ids ids;
    private final String actor;
    private final OperatorSettings settings;
    private final ContainerSupport containers;
    private final Checkouts checkouts;
    private final RunOutputs outputs;
    private final WorkflowEngine engine;
    private final RunPreparation preparation;
    private final ActiveRuns active;
    private final SealAdmission seals;

    /**
     * @param store      creates the run, and opens the earlier run whose inputs the new one takes
     * @param ledger     must be able to take an entry before a run is started
     * @param settings   the operator's settings, which {@code run.started} records
     * @param containers finds the container runtime, asked once per start, and names the built-in images
     * @param checkouts  makes the checkout of a run's source, and removes it after the run's end
     * @param active     the runs this process executes, which the started run joins while it runs
     * @param seals      the seal check: a sealed package whose seal does not hold starts no run
     */
    Starting(RunStore store, Ledger ledger, Clock clock, Ids ids, String actor, OperatorSettings settings,
             ContainerSupport containers, Checkouts checkouts, RunOutputs outputs, WorkflowEngine engine,
             RunPreparation preparation, ActiveRuns active, SealAdmission seals) {
        this.seals = seals;
        this.store = store;
        this.ledger = ledger;
        this.clock = clock;
        this.ids = ids;
        this.actor = actor;
        this.settings = settings;
        this.containers = containers;
        this.checkouts = checkouts;
        this.outputs = outputs;
        this.engine = engine;
        this.preparation = preparation;
        this.active = active;
    }

    /**
     * Starts a run of {@code pkg}, which the caller loaded from the request's location, as
     * {@link RunServices#start} says.
     */
    RunResult start(LoadedPackage pkg, StartRequest request, Consumer<RecordedEvent> listener, QuestionAsker asker) {
        if (request.inputs().containsKey(PACKAGE_DIR)) {
            throw new RunRefusedException(Message.of("The input " + PACKAGE_DIR + " is reserved: Traceworks sets it to the"
                    + " package directory"));
        }
        if (request.inputs().containsKey(REGISTER_DIR)) {
            throw new RunRefusedException(Message.of("The input " + REGISTER_DIR + " is reserved: Traceworks sets it to the"
                    + " register folder of the run of an audit workflow"));
        }
        // The seal first: a sealed package that changed is refused whatever else this machine has or lacks, before
        // anything of the package is used, cloned or created. The refusal is recorded in the seal log
        SealStatus seal = seals.admit(pkg, request.attended(), "run", null);
        ContainerRuntime runtime = containers.runtime().get().orElse(null);
        RunTarget target = RunTarget.of(request.allowHostExecution() || settings.allowHostExecution(),
                runtime != null);
        // The inputs before the start check: they pick the options of the workflow's model choices
        Map<String, Object> inputs = new LinkedHashMap<>();
        String inputsFrom = null;
        if (request.inputsFrom() != null) {
            try (StoredRun earlier = store.open(request.inputsFrom())) {
                inputsFrom = earlier.runId();
                // The inputs come from the earlier record's first line, and the new record names that run as their
                // origin: a record that does not verify (its chain, its blobs, its manifest, its ledger entries)
                // vouches for no inputs
                VerifyReport verified = Verification.verify(earlier, ledger);
                if (!verified.ok()) {
                    throw new RecordDamagedException("Run " + earlier.runId() + " does not verify, so --inputs-from"
                            + " cannot take its inputs" + (verified.chain().problems().isEmpty() ? ""
                            : " (" + verified.chain().problems().getFirst() + ")")
                            + RecordDamagedException.seeVerify(earlier.runId()));
                }
                inputs.putAll(recordedInputs(earlier));
            }
        }
        inputs.putAll(request.inputs());
        RunPreparation.Prepared prepared = preparation.prepare(target, runtime, pkg, request.workspace(), inputs,
                "The run is refused");
        // Before anything is cloned or created: a resume makes real again what the record holds redacted
        RunPreparation.refuseAReplacement("package directory", pkg.directory().toString());
        RunPreparation.refuseAReplacement("package directory", prepared.packageDirectory());
        RunPreparation.refuseAReplacement("workspace", prepared.workspace());
        RunPreparation.refuseAReplacement("source", request.source());
        prepareLedger();
        WorkflowDefinition definition = pkg.definition();
        String runId = ids.newRunId();
        // After the start check, so a refused run clones nothing; before the run exists, so a refused clone leaves none
        Checkouts.Started checkout = request.source() == null ? null
                : checkouts.start(request.source(), request.ref(), runId);
        String workspace = checkout == null ? prepared.workspace() : checkout.workspace();
        if (checkout != null) {
            try {
                // The checkout names the source its own way, and it is a directory of this home
                RunPreparation.refuseAReplacement("source", checkout.checkout().source());
                RunPreparation.refuseAReplacement("checkout", checkout.workspace());
            } catch (RunRefusedException e) {
                checkouts.discard(runId, e);
                throw e;
            }
        }
        // Outside the try below: a run that cannot be created discards its checkout, it does not keep it
        StoredRun created = create(runId, checkout);
        try (StoredRun run = created) {
            RunPaths paths = paths(run, workspace, prepared, checkout);
            RegisterHandover handover = prepared.register() == null ? null
                    : RegisterFolder.handover(prepared.register());
            RegisterFolder folder = handover == null ? null : new RegisterFolder(run, handover);
            RunRecorder recorder = RunRecorder.start(run.events(), run.blobs(), clock, runId, definition)
                    .redacting(prepared.redactor()).withScratch(new IdleScratch(run, runtime, runId))
                    .notingTheStartInTheLedger();
            RunOutcome outcome = null;
            String unnoted;
            try (RunSession session = new RunSession(run, recorder, actor, listener, asker)) {
                recorder.runStarted(runStart(pkg, inputs, target, runtime, workspace, checkout, inputsFrom, seal,
                        prepared.modelPicks(), handover));
                // The ledger first, then the first step: a run that began is named outside its own folder
                unnoted = noteStart(run);
                if (unnoted == null) {
                    RunOptions options = preparation.options(runId, session, recorder, paths, prepared,
                            TriggerSource.MANUAL, !request.interactive(),
                            checkout == null ? null : checkout.checkout().sha(), folder);
                    outcome = active.execute(session, runId, runtime, () -> engine.run(definition, inputs, options));
                } else {
                    // A record with events is never removed: the run ends here, FAILED, without a step
                    recorder.emit(new RunFinished(RunStatus.FAILED, Map.of(), unnoted));
                }
            }
            if (unnoted != null) {
                RunRefusedException refusal = new RunRefusedException(unnoted + "; its record is " + run.location()
                        + ", and traceworks resume " + runId + " writes its entries once the ledger can be written");
                try {
                    if (checkout != null) {
                        checkouts.afterRun(run, RunStatus.FAILED);
                    }
                    outputs.finish(run, pkg.catalogJson());
                } catch (RuntimeException e) {
                    refusal.addSuppressed(e);   // the ledger that took no start will hardly take the end
                }
                throw refusal;
            }
            RunResult.KeptCheckout kept = checkout == null ? null : checkouts.afterRun(run, outcome.status());
            RegisterOutcome register = outputs.finish(run, pkg.catalogJson());
            return prepared.result(runId, run.location(), outcome, kept, register);
        } catch (RuntimeException e) {
            if (checkout != null) {
                checkouts.noteKept(runId, e);   // no summary will name it
            }
            throw e;
        }
    }

    /** @throws RunRefusedException if the ledger cannot take an entry, so no run is started */
    private void prepareLedger() {
        try {
            ledger.prepare();
        } catch (RuntimeException e) {
            throw new RunRefusedException(Message.of("The ledger cannot take an entry, so the run is not started: ",
                    Message.of(e)));
        }
    }

    /**
     * Notes the start of the run in the ledger, from the {@code run.started} line its record now holds: the run id,
     * the time, the hash of the line, the workflow, the package digest and, for an audit, the register's head the
     * run read ({@link StartEntry}).
     *
     * @return {@code null} when the ledger took the entry; else why it did not: the run must not go on
     */
    private String noteStart(StoredRun run) {
        try {
            ledger.recordStart(run.runId(), StartEntry.of(run.events().readAll().getFirst()).toJson());
            return null;
        } catch (RuntimeException e) {
            return "The ledger could not note the start of run " + run.runId() + ", so no step ran and the run ended"
                    + " FAILED: " + e.getMessage() + (e.getCause() == null ? "" : " (" + e.getCause() + ")");
        }
    }

    /**
     * Creates the run; when that fails, its checkout is discarded: no run names it.
     *
     * @throws RunRefusedException if the store cannot make the run's folder (a runs folder that cannot be written),
     *                             so no run is started
     */
    private StoredRun create(String runId, Checkouts.Started checkout) {
        try {
            return store.create(runId, clock.now());
        } catch (RuntimeException e) {
            if (checkout != null) {
                checkouts.discard(runId, e);
            }
            if (e instanceof UncheckedIOException io) {
                throw new RunRefusedException("The record of the run cannot be created, so the run is not started: "
                        + io.getMessage() + " (" + io.getCause() + ")");
            }
            throw e;
        }
    }

    /**
     * The run's directories as its containers see them. A refusal discards the run, which has recorded nothing yet,
     * and its checkout.
     *
     * @param workspace the real path of the directory the steps work in, or the run's checkout; {@code null} for none
     */
    private RunPaths paths(StoredRun run, String workspace, RunPreparation.Prepared prepared,
                           Checkouts.Started checkout) {
        try {
            // The register folder first: what the register knew when the run started, before any step
            return preparation.paths(run, workspace, prepared, preparation.writeRegisterFolder(run, prepared));
        } catch (RunRefusedException e) {
            try {
                run.discard();   // refused before anything was recorded: no run is left behind
            } catch (RuntimeException discardFailed) {
                e.addSuppressed(discardFailed);   // the refusal stays what the caller learns
            }
            if (checkout != null) {
                checkouts.discard(run.runId(), e);
            }
            throw e;
        }
    }

    /** What {@code run.started} records of a new run. */
    /**
     * The inputs an earlier run was started with, from its {@code run.started}.
     *
     * @throws RunRefusedException if the record holds no readable inputs (the blob is deleted)
     */
    static Map<String, Object> recordedInputs(StoredRun earlier) {
        List<RecordedEvent> events = RunRecords.events(earlier);
        if (events.isEmpty() || !RecordTypes.RUN_STARTED.equals(events.getFirst().type())) {
            throw new RunRefusedException(Message.of("Run " + earlier.runId() + " has no run.started, so its inputs are not known"));
        }
        BlobRef ref = events.getFirst().blob("inputs");
        // Checked against the name and the size the record gives: the new run names this run as the origin of its
        // inputs, so they must be what its record says, as for a resume
        byte[] bytes = ref == null ? null : RunRecords.checkedContent(earlier, ref, "inputs").orElse(null);
        if (bytes == null) {
            throw new RunRefusedException(Message.of("The inputs of run " + earlier.runId() + " are deleted from its record, so"
                    + " --inputs-from cannot take them"));
        }
        return RunRecords.inputs(earlier.runId(), bytes);
    }

    private RunStart runStart(LoadedPackage pkg, Map<String, Object> inputs, RunTarget target,
                              ContainerRuntime runtime, String workspace, Checkouts.Started checkout,
                              String inputsFrom, SealStatus seal, List<ModelPick> modelPicks,
                              RegisterHandover handover) {
        WorkflowDefinition definition = pkg.definition();
        return new RunStart(definition.id(), definition.name(), pkg.directory().toString(), pkg.digest(),
                pkg.gitCommit(), pkg.gitDirty(), pkg.workflowJson(), inputs, TriggerSource.MANUAL, actor,
                TraceworksVersion.current(), target.recorded(),
                runtime == null ? null : new RunStart.Runtime(runtime.name(), runtime.version()),
                settings.outputLimitMib(), settings.aiImages(), workspace, checkout == null ? null : checkout.checkout().source(),
                checkout == null ? null : checkout.checkout().sha(), settings.runOutputBudgetMib(), inputsFrom,
                imageFacts(pkg), settings.aiNetwork(), sealView(seal), modelPicks, handover, settings.localModels());
    }

    /** The seal as {@code run.started} records it; {@code null} for a package that is not sealed. */
    static SealView sealView(SealStatus status) {
        if (status.state() != SealStatus.State.SEALED) {
            return null;
        }
        Seal seal = status.seal();
        return new SealView(seal.workflowId(), seal.digest(), seal.signer(), seal.sealedAt().toString(), status.key(),
                status.principals(), seal.signature());
    }

    /**
     * What each image the run may use holds, for the record: the built-in images of this distribution, by name, and
     * the package's own, as {@code ./images/<name>}, each from its Dockerfile.
     */
    private List<ImageFacts> imageFacts(LoadedPackage pkg) {
        List<ImageFacts> facts = new ArrayList<>();
        for (PackageImage image : containers.builtIns().all()) {
            facts.add(ImageFacts.of(image.name(), image.dockerfile()));
        }
        for (PackageImage image : pkg.images().values()) {
            facts.add(ImageFacts.of(ImageCatalog.PACKAGE_IMAGE_PREFIX + image.name(), image.dockerfile()));
        }
        return facts;
    }
}
