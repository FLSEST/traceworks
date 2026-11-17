package traceworks.core.app;

import traceworks.core.ai.cli.CliAiProvider;
import traceworks.core.analyzer.AnalyzerFinding;
import traceworks.core.analyzer.WorkflowAnalyzer;
import traceworks.core.container.ContainerCommandRunner;
import traceworks.core.describe.WorkflowDescriber;
import traceworks.core.describe.WorkflowDescription;
import traceworks.core.engine.BlockTypeRegistry;
import traceworks.core.engine.EventLogException;
import traceworks.core.engine.WorkflowEngine;
import traceworks.core.port.AiCli;
import traceworks.core.port.BlobStore;
import traceworks.core.port.LocalModels;
import traceworks.core.port.AiProviders;
import traceworks.core.port.Clock;
import traceworks.core.port.CommandRunner;
import traceworks.core.port.ContainerRuntime;
import traceworks.core.port.Ids;
import traceworks.core.port.Ledger;
import traceworks.core.port.LoadedPackage;
import traceworks.core.port.PackageLoader;
import traceworks.core.port.HookRunner;
import traceworks.core.port.RegisterStore;
import traceworks.core.port.RunStore;
import traceworks.core.port.SourceCheckout;
import traceworks.core.port.StoredRun;
import traceworks.core.record.Manifest;
import traceworks.core.record.RecordedEvent;
import traceworks.core.record.RunView;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The use cases of Traceworks on top of the ports, for the command line now and an MCP server later: start a run of a
 * package, resume a parked or interrupted run, answer its question, cancel, verify, render, list and check.
 *
 * <p>Where a run runs its steps ({@link RunTarget}): a container run wherever a container runtime works, a host run
 * only where none works and the operator consents; a resume keeps the target recorded at the start. Before a run is
 * created, and before a resume records anything, the {@link Preflight} checks the container runtime, the images and
 * the run's directories, and refuses with every reason at once. Every command of a run goes through its
 * {@link ContainerCommandRunner}, which hands the image-less commands of a host run to the host runner.
 *
 * <p>The secrets a run declares are part of the start check: each needs a value, from
 * {@code credentials.json} or the environment. The run passes those values to the steps that declare them, and its
 * recorder redacts every one of them from the record; so does the {@link RunResult} a use case returns.
 *
 * <p>AI steps: each run builds its {@link AiProviders} from the configured {@link AiCli}s, one
 * {@link CliAiProvider} per CLI, which runs the CLI in its built-in image through the run's command runner, so an AI
 * call is a container command of the run like any other, in a host run too. The start check needs a credential for
 * every provider the run uses ({@link AiCredentials}); a provider's container gets its credentials, and the recorder
 * redacts them like secrets. The record wraps the providers, so every call is recorded.
 *
 * <p>A run started with a source ({@link StartRequest#source()}) works in a checkout of it in a directory of its own,
 * which {@link Checkouts} makes after the start check, restores for a resume and removes after the run's end.
 *
 * <p>Only the holder of a run's writer lock appends to its record. {@code start} and {@code resume} hold it while
 * the run executes in this process; another process reaches that run through its inbox. A run nobody writes (parked
 * or interrupted) is answered or cancelled by taking the lock for the moment of the write. A run that reaches a final
 * status gets its manifest, its ledger entry and {@code run.html}; a parked run gets {@code run.html} only.
 *
 * <p>This class is the one entry point of the use cases: it hands a start, a resume, an answer and a cancel to a
 * class of their own ({@link Starting}, {@link Resuming}, {@link Answering}, {@link Cancelling}). What a start and a
 * resume share is the start check and the run's options ({@link RunPreparation}), and the runs this process executes
 * ({@link ActiveRuns}).
 */
public final class RunServices {

    private final RunStore store;
    private final PackageLoader packages;
    private final Ledger ledger;
    private final Clock clock;
    private final BlockTypeRegistry blocks;
    private final ContainerSupport containers;
    private final RegisterStore registers;
    private final OperatorSettings settings;
    /** The AI CLIs of a run, which the analyzer asks about the models a package names. */
    private final List<AiCli> aiClis;
    private final String actor;
    /** The runs this process executes now, for {@link #cancelActiveRuns}. */
    private final ActiveRuns active;
    private final Starting starting;
    private final Resuming resuming;
    private final Answering answering;
    private final Cancelling cancelling;
    private final Pausing pausing;
    private final SealAdmission sealAdmission;
    private final Seals seals;

    /**
     * @param hostRunner runs the commands of a host run that name no image, on the host
     * @param containers the container runtime, the built-in images and the run's directories on this machine
     * @param sources    the checkouts of the runs started with a source
     * @param aiClis     the AI CLIs a run's AI steps can use, one per provider id
     * @param secrets    where the values of secrets and AI credentials come from: {@code credentials.json} and the
     *                   environment. Asked at most once at the start and the resume of a run that declares a secret or
     *                   uses an AI provider, and once more for each path a resume or an answer finds redacted in the
     *                   record, to work at its real path; it throws {@link IllegalArgumentException} or
     *                   {@link UncheckedIOException} for a credentials file that cannot be used, which refuses that
     *                   run
     * @param actor      who uses Traceworks here; runs, answers and cancels are attributed to them
     */
    public RunServices(RunStore store, PackageLoader packages, Ledger ledger, Clock clock, Ids ids,
                       BlockTypeRegistry blocks, CommandRunner hostRunner, ContainerSupport containers,
                       SourceCheckout sources, List<AiCli> aiClis, Supplier<SecretValues> secrets,
                       OperatorSettings settings, RegisterStore registers, HookRunner hookRunner, String actor) {
        this(store, packages, ledger, clock, ids, blocks, hostRunner, containers, sources, aiClis, secrets, settings,
                registers, hookRunner, actor, SealSupport.none());
    }

    /**
     * With the seals of the home.
     *
     * @param seals where the seals of the home live (the seal log, the allowed signers, the seal files of packages),
     *              and what signs for the operator
     */
    public RunServices(RunStore store, PackageLoader packages, Ledger ledger, Clock clock, Ids ids,
                       BlockTypeRegistry blocks, CommandRunner hostRunner, ContainerSupport containers,
                       SourceCheckout sources, List<AiCli> aiClis, Supplier<SecretValues> secrets,
                       OperatorSettings settings, RegisterStore registers, HookRunner hookRunner, String actor,
                       SealSupport seals) {
        this(store, packages, ledger, clock, ids, blocks, hostRunner, containers, sources, aiClis, secrets, settings,
                registers, hookRunner, actor, seals, Answering.WAIT, Cancelling.WAIT);
    }

    /**
     * With other waits for a request in a running run's inbox to be recorded, for tests.
     *
     * @param answerWait how long {@code answer} waits
     * @param cancelWait how long {@code cancel} waits
     */
    RunServices(RunStore store, PackageLoader packages, Ledger ledger, Clock clock, Ids ids,
                BlockTypeRegistry blocks, CommandRunner hostRunner, ContainerSupport containers, SourceCheckout sources,
                List<AiCli> aiClis, Supplier<SecretValues> secrets, OperatorSettings settings,
                RegisterStore registers, HookRunner hookRunner, String actor, SealSupport seals,
                Duration answerWait, Duration cancelWait) {
        this.store = Objects.requireNonNull(store, "store");
        this.packages = Objects.requireNonNull(packages, "packages");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(ids, "ids");
        this.blocks = Objects.requireNonNull(blocks, "blocks");
        Objects.requireNonNull(hostRunner, "hostRunner");
        this.containers = Objects.requireNonNull(containers, "containers");
        Checkouts checkouts = new Checkouts(Objects.requireNonNull(sources, "sources"), containers.directories());
        List<AiCli> clis = List.copyOf(Objects.requireNonNull(aiClis, "aiClis"));
        this.aiClis = clis;
        Objects.requireNonNull(secrets, "secrets");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.actor = Objects.requireNonNull(actor, "actor");
        WorkflowEngine engine = new WorkflowEngine(blocks);
        this.registers = Objects.requireNonNull(registers, "registers");
        RunOutputs outputs = new RunOutputs(ledger, new Registers(registers, clock, hookRunner, settings.hooks(), ledger,
                settings.decisionOnHigherSeverity()));
        RunPreparation preparation = new RunPreparation(containers, packages, settings, clis, secrets, hostRunner, clock,
                registers);
        this.active = new ActiveRuns(actor, ids);
        Objects.requireNonNull(seals, "seals");
        this.sealAdmission = new SealAdmission(seals.store(), settings.seals(), settings.packagePath(), packages,
                clock, actor);
        this.seals = new Seals(seals, settings.seals(), packages, settings.packagePath(), clock, actor,
                sealAdmission);
        this.starting = new Starting(store, ledger, clock, ids, actor, settings, containers, checkouts, outputs,
                engine, preparation, active, sealAdmission);
        this.cancelling = new Cancelling(clock, ids, actor, outputs, cancelWait,
                (runId, events) -> LeftoverContainers.requireRemoved(runtime().orElse(null), runId,
                        LeftoverContainers.mayHaveContainers(events), "Run " + runId + " cannot be cancelled"),
                checkouts);
        StoredRunPackage storedPackage = new StoredRunPackage(packages, preparation);
        this.resuming = new Resuming(clock, actor, settings.allowHostExecution(), settings.outputLimitMib(),
                settings.runOutputBudgetMib(), settings.aiImages(), settings.aiNetwork(), settings.localModels(),
                storedPackage,
                containers.runtime(), checkouts, outputs, engine, preparation, active, cancelling, sealAdmission);
        this.pausing = new Pausing(clock, ids, actor, answerWait);
        this.answering = new Answering(clock, ids, actor, answerWait, settings.allowHostExecution(),
                (run, view) -> preparation.redactor(storedPackage.recordedPackage(run, view)));
    }

    /**
     * Starts a run of a package and returns when it has ended or parked. Without a terminal
     * ({@link StartRequest#interactive()} off), a top-level question parks the run; with one, each question is asked
     * through {@code asker} and can be answered through the inbox as well.
     *
     * @param listener gets every event of the run in order, on a thread of its own; {@code null} for none
     * @param asker    asks the questions on a terminal; {@code null} for none
     * @throws RunRefusedException before any run exists: the package is invalid, an input is reserved, the start
     *                             check fails (no container runtime for a container run, a missing image, a
     *                             directory that cannot be mounted, a secret or a credential without a value), the
     *                             ledger cannot take an entry, or the source cannot be checked out
     * @throws EventLogException   if the record could not be written: the run ends without manifest and ledger
     *                             entry
     */
    public RunResult start(StartRequest request, Consumer<RecordedEvent> listener, QuestionAsker asker) {
        return starting.start(load(request.packageLocation()), request, listener, asker);
    }

    /**
     * Resumes a parked or interrupted run from its last checkpoint, with the definition it was started with, and
     * returns when it has ended or parked again. A parked run continues with the answer recorded for it; an
     * interrupted one runs the step that was in flight again. Without {@code asker}, a top-level question parks the
     * run again. A finished run whose manifest or ledger entry is missing, because its finalisation failed, gets them
     * now: nothing is written to the log, and {@link RunResult#completed()} says what was written. A run whose cancel
     * the record holds, or that a cancel waits for in its inbox, is ended {@code CANCELLED} instead, without a step.
     *
     * <p>The run keeps the target recorded at its start: a container run stays one, and a host run stays one also where
     * a container runtime works now, with the same consent as its start. A run started with a source works in its
     * checkout as it is; a checkout that is missing is cloned again at the recorded commit ({@link Checkouts#restore}).
     * What is left of the run's containers is removed before anything else, and the resume is refused unless that is
     * confirmed. {@code run.resumed} records the target it keeps and the container runtime of this process.
     *
     * @param listener gets every event the resume records, in order, on a thread of its own; {@code null} for none
     * @param asker    asks the questions on a terminal; {@code null} for none
     * @throws RunRefusedException if another process writes the run, its leftover containers cannot be confirmed
     *                             removed, a host run lacks the consent, the run has
     *                             finished, a parked run has no answer yet, the package changed and that is not
     *                             allowed, its missing checkout cannot be cloned again, its checkpoint holds more
     *                             output text than the output budget in force, or the start check fails (a secret
     *                             without a value among its reasons)
     */
    public RunResult resume(ResumeRequest request, Consumer<RecordedEvent> listener, QuestionAsker asker) {
        return resume(request, listener, asker, warning -> { });
    }

    /**
     * Resumes a run as {@link #resume(ResumeRequest, Consumer, QuestionAsker)} does, and hands each warning for the
     * operator to {@code warnings} before the run goes on: when {@code credentials.json} was modified after the run
     * stopped (its {@code run.parked}, or its last line for an interrupted run) and holds the value of a secret the run
     * declares or of a credential of an AI provider it uses, the warning names the file and those names, never a
     * value. The resume redacts with the values it reads now, so an old value that a later step prints would be
     * recorded in clear. The warning is not recorded.
     *
     * @param warnings gets each warning, on the calling thread
     */
    public RunResult resume(ResumeRequest request, Consumer<RecordedEvent> listener, QuestionAsker asker,
                            Consumer<String> warnings) {
        Objects.requireNonNull(warnings, "warnings");
        try (StoredRun run = store.open(request.runRef())) {
            return resuming.resume(run, request, listener, asker, warnings);
        }
    }

    /**
     * Answers the question a run waits for. A parked run's answer is checked against the parked question and recorded
     * for {@code resume}; a running run gets it through its inbox, and this call waits up to ten seconds for the
     * record to show what became of it.
     */
    public AnswerResult answer(String runRef, AnswerRequest request) {
        try (StoredRun run = store.open(runRef)) {
            return answering.answer(run, request);
        }
    }

    /**
     * Cancels a run. A parked or interrupted run is finished here: {@code run.cancel_requested}, then
     * {@code run.finished} with the status {@code CANCELLED} and the gate of its last checkpoint's steps, then its
     * manifest, ledger entry and {@code run.html}. A running run gets the cancel through its inbox; this call waits
     * up to thirty seconds for it to end.
     *
     * @throws RunRefusedException if the run has finished
     */
    public RunResult cancel(String runRef) {
        try (StoredRun run = store.open(runRef)) {
            return cancelling.cancel(run);
        }
    }

    /**
     * Asks a running run of another process to pause, through its inbox: the run finishes the step, or the item of a
     * loop, in flight, then parks ({@code run.parked} with the kind {@code pause}), and {@link #resume} goes on from
     * there. This call waits up to ten seconds for the run's process to record the request
     * ({@code run.pause_requested}), not for the run to park, which takes as long as the step in flight.
     *
     * @return the run as it is when the request is recorded: still {@code RUNNING}, with what was asked in
     *         {@link RunResult#error()}, or already parked or ended
     * @throws RunRefusedException    if no process runs the run (it is parked, interrupted or finished), or the run
     *                                refused the request (it was already asked to pause)
     * @throws RequestQueuedException if the run's process has not recorded the request within the wait
     */
    public RunResult pause(String runRef) {
        try (StoredRun run = store.open(runRef)) {
            return pausing.pause(run);
        }
    }

    /**
     * Whether the command that resumes the run needs {@code --allow-host-execution}: it is a host run, and the
     * operator config gives no consent, so its start had the consent from the flag. The hints that say how to go on
     * name the flag then.
     */
    public boolean resumeNeedsHostFlag(RunView view) {
        return RunTarget.resumeNeedsHostFlag(view.execution(), settings.allowHostExecution());
    }

    /** Checks the chain, the blobs, the manifest and the ledger entry of a run. */
    public VerifyReport verify(String runRef) {
        try (StoredRun run = store.open(runRef)) {
            return Verification.verify(run, ledger);
        }
    }

    /**
     * The ledger against the runs folder of this home: the runs the ledger saw start that it never saw end, each with
     * what its folder says, and the runs the ledger names whose folder is gone ({@link LedgerReport}). Read-only.
     *
     * @throws RecordDamagedException if the ledger exists and cannot be read
     */
    public LedgerReport ledgerReport() {
        return ledgerReport(true);
    }

    /**
     * {@link #ledgerReport()}, with or without verifying the record of every ended run against its end entry.
     *
     * @param records whether to read and verify every record and every blob of the home
     */
    public LedgerReport ledgerReport(boolean records) {
        return LedgerReport.of(ledger, store, clock, records);
    }

    /**
     * Acknowledges the problem {@link #ledgerReport} names of one run, on the record: one more entry of that run in
     * the ledger with this process's actor, the time, what the check said and the note. The check then lists the
     * run as acknowledged, for good, and no longer fails for that problem; a problem of another kind fails again.
     *
     * @param runRef the run id, or a prefix of at least four characters that one reported run has
     * @param note   why this is no lost run; required
     * @return the report after it
     * @throws RunRefusedException    if the note is empty, the check reports nothing of that run, or the ledger
     *                                cannot take the entry
     * @throws RecordDamagedException if the ledger cannot be read
     */
    public LedgerReport acknowledge(String runRef, String note) {
        return LedgerReport.acknowledge(ledger, store, clock, actor, runRef, note);
    }

    /**
     * Compares two finished runs from their records, read-only: header, inputs, steps and AI calls, and for two audit
     * runs the findings of their register between them ({@link traceworks.core.compare.RunComparer}
     * says what is matched and what counts).
     *
     * @throws RunRefusedException    if a run has not finished
     * @throws RecordDamagedException if a run's record cannot be read or does not verify
     */
    public traceworks.core.compare.RunComparison compare(String runRefA, String runRefB) {
        try (StoredRun a = store.open(runRefA); StoredRun b = store.open(runRefB)) {
            return Comparing.compare(a, b, ledger, registers, clock);
        }
    }

    /**
     * Writes {@code run.html} again from the record as it is now.
     *
     * @return the run's folder, where {@code run.html} now is
     */
    public String render(String runRef) {
        try (StoredRun run = store.open(runRef)) {
            List<byte[]> lines = run.events().readAll();
            RunOutputs.writeView(run, RunRecords.view(run.runId(), RunRecords.parse(run.runId(), lines),
                    run.writerAlive()), lines);
            return run.location();
        }
    }

    /**
     * The content of a blob of a run, such as an output or a question, for a view; empty when the blob was deleted, a
     * mark in its place when it does not match its name ({@link BlobStore#getForDisplay}).
     */
    public Optional<byte[]> content(String runRef, String sha256) {
        try (StoredRun run = store.open(runRef)) {
            return run.blobs().getForDisplay(sha256);
        }
    }

    /** What the record of a run says now. */
    public RunView view(String runRef) {
        try (StoredRun run = store.open(runRef)) {
            return RunRecords.view(run.runId(), RunRecords.events(run), run.writerAlive());
        }
    }

    /** The events of a run's record, in order. */
    public List<RecordedEvent> events(String runRef) {
        try (StoredRun run = store.open(runRef)) {
            return RunRecords.events(run);
        }
    }

    /** The newest runs, newest first; a run whose record cannot be read is left out. */
    public List<RunView> runs(int limit) {
        return listing(limit, false).stream().map(RunListing::view).toList();
    }

    /** The newest runs with the models their steps used, newest first; {@link #runs} without the models. */
    public List<RunListing> listing(int limit) {
        return listing(limit, true);
    }

    private List<RunListing> listing(int limit, boolean withModels) {
        List<RunListing> listed = new ArrayList<>();
        for (String runId : store.runIds()) {
            if (listed.size() >= limit) {
                break;
            }
            try (StoredRun run = store.open(runId)) {
                List<RecordedEvent> events = RunRecords.events(run);
                if (!events.isEmpty()) {
                    RunView view = RunView.of(events, run.writerAlive());
                    listed.add(new RunListing(view, withModels ? Manifest.models(view, run.blobs()) : List.of()));
                }
            } catch (RuntimeException e) {
                // A record that cannot be read is left out of the list; verify names what is wrong with it
            }
        }
        return listed;
    }

    /**
     * The analyzer's findings on a package, most severe first. The workflows its {@code sub-workflow} and
     * {@code foreach} blocks run are looked into for questions that never park. A package that is sealed and whose
     * seal does not hold gets a finding for it: {@code sealed-package-changed} when the package changed since it was
     * sealed, {@code sealed-package-not-sealable} when a seal cannot cover it, {@code seal-invalid} for any other
     * reason a run of it would be refused for. Nothing is recorded.
     *
     * @throws RunRefusedException if the package cannot be loaded
     */
    public List<AnalyzerFinding> check(Path packageLocation) {
        LoadedPackage pkg = load(packageLocation);
        List<AnalyzerFinding> findings = new ArrayList<>(analyze(pkg, new WorkflowAnalyzer(blocks).withAiClis(aiClis)));
        SealStatus seal = sealAdmission.judge(pkg);
        if (seal.state() == SealStatus.State.REFUSED) {
            String rule = switch (seal.reason()) {
                case "changed" -> "sealed-package-changed";
                case "not-sealable" -> "sealed-package-not-sealable";
                default -> "seal-invalid";
            };
            AnalyzerFinding.Severity severity = rule.equals("sealed-package-not-sealable")
                    ? AnalyzerFinding.Severity.ERROR : AnalyzerFinding.Severity.WARNING;
            findings.add(new AnalyzerFinding(rule, severity, -1, null, seal.detail()
                    + " — a run of the package is refused until then"));
            findings.sort(java.util.Comparator.comparing(AnalyzerFinding::severity));
        }
        return findings;
    }

    /** The seal use case: seal and unseal a package, and read the seal log of this home. */
    public Seals seals() {
        return seals;
    }

    /**
     * The check before a run of {@code request} starts: the analyzer's findings on its package, as {@link #check}
     * reports them but for the inputs this run gets (its own and those of {@link StartRequest#inputsFrom()}), so a
     * variable an input sets is no unknown one; and the inputs of the request that no block reads. It starts nothing
     * and writes nothing. The caller decides what a finding means: {@code traceworks run} starts no run of a package
     * with an {@code ERROR}.
     *
     * @param analyze whether to run the analyzer; without, only the inputs are judged
     * @throws RunRefusedException if the package cannot be loaded, or the inputs of the earlier run are not known
     */
    public RunCheck checkBeforeRun(StartRequest request, boolean analyze) {
        LoadedPackage pkg = load(request.packageLocation());
        List<String> inputWarnings = WorkflowAnalyzer.unreadInputs(pkg.definition(), request.inputs().keySet());
        if (!analyze) {
            return new RunCheck(List.of(), inputWarnings);
        }
        Set<String> inputNames = new LinkedHashSet<>(request.inputs().keySet());
        if (request.inputsFrom() != null) {
            try (StoredRun earlier = store.open(request.inputsFrom())) {
                inputNames.addAll(Starting.recordedInputs(earlier).keySet());
            }
        }
        return new RunCheck(analyze(pkg, new WorkflowAnalyzer(blocks).withAiClis(aiClis).withInputs(inputNames)), inputWarnings);
    }

    private List<AnalyzerFinding> analyze(LoadedPackage pkg, WorkflowAnalyzer analyzer) {
        return analyzer.analyze(pkg.definition(),
                new PackageWorkflowResolver(pkg, settings.packagePath(), packages), pkg.images().values(),
                pkg.invalidImageDirectories(), pkg.catalogJson());
    }

    /**
     * What a package will do, read from the package alone: nothing is started, checked or written. The workflows its
     * {@code sub-workflow} and {@code foreach} blocks name are found as a run of the package finds them, on the
     * operator's package path too.
     *
     * @throws RunRefusedException if the package cannot be loaded
     */
    public WorkflowDescription describe(Path packageLocation) {
        LoadedPackage pkg = load(packageLocation);
        return WorkflowDescriber.describe(pkg, new PackageWorkflowResolver(pkg, settings.packagePath(), packages),
                this::localServer, host -> containers.directories().writableMountRefusal(host).isPresent(),
                containers.directories().workspaceGitWritable());
    }

    /**
     * The model server of the operator's that a step of {@code provider} with {@code model} runs against, as the
     * provider's CLI tells it; {@code null} for a step that goes to the provider.
     */
    private LocalModels.Use localServer(String provider, String model) {
        for (AiCli cli : aiClis) {
            if (cli.id().equalsIgnoreCase(provider)) {
                return cli.local(new AiCli.AiCall(AiCli.AiCall.Kind.ACTION, "", null, model, null, null, null, 1))
                        .orElse(null);
            }
        }
        return null;
    }

    /** The findings use case: what the registers of this home hold. */
    public Findings findings() {
        return new Findings(registers, clock, actor, ledger);
    }

    /**
     * Asks every run this process executes to stop, as a cancel from this process's actor, and then removes what is
     * left of its containers; for Ctrl-C. It cannot refuse: what the runtime cannot confirm gone counts as a container
     * that may still run, so the run does not list its scratch directory while it may.
     */
    public void cancelActiveRuns() {
        active.cancelAll();
    }

    /**
     * Leaves every run this process executes interrupted; for a process that is told to end (SIGTERM, a closed
     * terminal, a shutdown) and cannot wait for a step. Each run's record stops where it is, the step in flight is
     * ended and what is left of its containers is removed. Nothing more is recorded, no cancel and no end, so each
     * run is {@code INTERRUPTED} once the process is gone, and {@link #resume} goes on with it: it runs again the
     * step that was in flight, a loop only its unfinished items. The call that runs such a run ({@link #start},
     * {@link #resume}) ends with an {@link traceworks.core.engine.EventLogException}.
     */
    public void interruptActiveRuns() {
        active.interruptAll();
    }

    /**
     * Asks every run this process executes to pause, as a request from this process's actor; for the first Ctrl-C.
     * Each run finishes the step or the loop item in flight and parks, so {@code resume} goes on later.
     *
     * @return how many runs this process executes now; 0 when there is nothing to pause
     */
    public int pauseActiveRuns() {
        return active.pauseAll();
    }

    /**
     * One line for each AI CLI that the operator's {@code ai_images} runs in an image of its own, and for what the
     * operator's {@code ai_network} and {@code ai_hosts} open beyond the provider's hosts, for the caller to
     * print when a run starts or resumes; empty when the built-in images and the restricted network are in use.
     */
    public List<String> aiImageNotes() {
        List<String> notes = new ArrayList<>();
        new TreeMap<>(settings.aiImages()).forEach((cli, image) -> notes.add("ai_images: " + cli + " runs in "
                + image + " instead of the built-in image"));
        if (settings.aiNetwork().open()) {
            notes.add("ai_network: open: the container of an AI step can connect anywhere, not only to its"
                    + " provider");
        } else {
            settings.aiNetwork().hosts().forEach((cli, hosts) -> notes.add("ai_hosts: the containers of " + cli
                    + " also reach " + String.join(", ", hosts)));
        }
        settings.localModels().list().forEach(backend -> notes.add("local_models: a step with the model "
                + backend.id() + "/<model> runs against " + backend.url() + " and reaches nothing else"));
        return notes;
    }

    /** The container runtime of this machine, looked for now. */
    private Optional<ContainerRuntime> runtime() {
        return containers.runtime().get();
    }

    private LoadedPackage load(Path location) {
        try {
            return packages.load(location);
        } catch (IllegalArgumentException e) {
            throw new RunRefusedException(e.getMessage());
        }
    }
}
