package traceworks.core.app;

import traceworks.core.model.RunStatus;
import traceworks.core.model.StepStatus;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.BlobRef;
import traceworks.core.port.Clock;
import traceworks.core.register.DecisionOnHigherSeverity;
import traceworks.core.register.RegisterDiff;
import traceworks.core.port.HookRunner;
import traceworks.core.port.Ledger;
import traceworks.core.port.RegisterStore;
import traceworks.core.port.StoredRun;
import traceworks.core.record.RunView;
import traceworks.core.record.StepView;
import traceworks.core.model.AuditConfig;
import traceworks.core.register.AuditResultParser;
import traceworks.core.register.Catalog;
import traceworks.core.register.Change;
import traceworks.core.register.Reconciler;
import traceworks.core.register.RegisterEvent;
import traceworks.core.register.RegisterEvents;
import traceworks.core.register.RegisterLog;
import traceworks.core.register.RegisterReplay;
import traceworks.core.register.RegisterState;
import traceworks.core.register.RegisterWriter;
import traceworks.core.register.GoneFinding;
import traceworks.core.register.ReportedFinding;
import traceworks.core.register.TodoItemLines;
import traceworks.core.util.TimedRegex;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Feeds a register from a finished run: the use case behind the end of every audit run. Only a run that ended
 * {@code COMPLETED} or {@code COMPLETED_WITH_ERRORS}, whose chain verifies and whose report step completed, applies
 * its report; everything else is recorded as rejected and changes no finding. A run is applied once. A register
 * the ledger anchors whose log is gone takes no report: it is not begun anew ({@link RegisterWrites#logGone}). A
 * failure of the register is returned, never thrown: the run's exit code is the run's.
 */
public final class Registers {

    private final RegisterStore store;
    private final Clock clock;
    private final HookRunner hookRunner;
    private final OperatorSettings.Hooks hooks;
    private final Ledger ledger;
    private final DecisionOnHigherSeverity higherSeverity;

    /** Without a hook. */
    public Registers(RegisterStore store, Clock clock) {
        this(store, clock, null, OperatorSettings.Hooks.NONE);
    }

    /** Without a ledger: no write is anchored. */
    public Registers(RegisterStore store, Clock clock, HookRunner hookRunner, OperatorSettings.Hooks hooks) {
        this(store, clock, hookRunner, hooks, null);
    }

    /**
     * @param hookRunner runs the operator's hook; {@code null} when there is none
     * @param hooks      the operator's hooks
     * @param ledger     anchors the register's head after every write; {@code null} anchors nothing
     */
    public Registers(RegisterStore store, Clock clock, HookRunner hookRunner, OperatorSettings.Hooks hooks,
                     Ledger ledger) {
        this(store, clock, hookRunner, hooks, ledger, DecisionOnHigherSeverity.DEFAULT);
    }

    /**
     * @param higherSeverity what happens to a finding a person accepted or dismissed when a report states it with a
     *                       severity above the one decided on: the operator's {@code decision_on_higher_severity}
     */
    public Registers(RegisterStore store, Clock clock, HookRunner hookRunner, OperatorSettings.Hooks hooks,
                     Ledger ledger, DecisionOnHigherSeverity higherSeverity) {
        this.higherSeverity = java.util.Objects.requireNonNull(higherSeverity, "higherSeverity");
        this.ledger = ledger;
        this.store = store;
        this.clock = clock;
        this.hookRunner = hookRunner;
        this.hooks = hooks == null ? OperatorSettings.Hooks.NONE : hooks;
    }

    /**
     * Feeds the register the run's definition names, if any: the register of the run's project, as its
     * {@code run.started} names it. A run that names no project (a run of a version before registers had projects
     * that is ended now: cancelled, or completed where only its finalisation was missing) feeds nothing, and no
     * register is written; a resume that would run its steps is refused before that.
     *
     * @param runHead     the head of the run's record
     * @param catalogJson the package's {@code catalog.json}; {@code null} for none
     * @return empty when the workflow has no audit block
     */
    public Optional<RegisterOutcome> feed(StoredRun run, RunView view, String runHead, byte[] catalogJson) {
        WorkflowDefinition definition;
        try {
            definition = RunRecords.definition(run.runId(), RunRecords.content(run, view.definition(),
                    "workflow definition"));
        } catch (RuntimeException e) {
            return Optional.empty();   // a record without a readable definition fed nothing, and says so elsewhere
        }
        AuditConfig config = AuditConfig.of(definition.metadata()).orElse(null);
        if (config == null) {
            return Optional.empty();
        }
        String project = view.project();
        if (project == null) {
            return Optional.of(new RegisterOutcome(config.key(), false, "the run names no project, and a register"
                    + " is kept per project: it was started before registers had projects, so it feeds none",
                    Map.of()));
        }
        try {
            Fed fed = feed(config, project, definition, run, view, runHead, catalogJson);
            return Optional.of(hook(config, project, definition, run, fed).ofProject(project));
        } catch (RuntimeException e) {
            return Optional.of(new RegisterOutcome(config.key(), false, "the register "
                    + RegisterOutcome.named(config.key(), project) + " could not be written: " + e.getMessage(),
                    Map.of()).ofProject(project));
        }
    }

    /**
     * The workflow another project's register of {@code key} belongs to, when that is not {@code workflowId}: a key
     * belongs to one workflow across all its projects.
     *
     * @return why the key is not the workflow's, for a refusal; {@code null} when every register of the key is its
     *         own
     */
    static String ownerOtherThan(RegisterStore store, Clock clock, String key, String workflowId) {
        for (String project : store.projects(key)) {
            String owner = owner(store, key, project);
            if (owner != null && !owner.equals(workflowId)) {
                return "the register " + key + " belongs to the workflow " + owner + ", not to " + workflowId
                        + ": a key belongs to one workflow across all its projects, and " + owner
                        + " feeds its register of " + project;
            }
        }
        return null;
    }

    /**
     * The workflow the register of {@code key} and {@code project} says it belongs to, from the line it begins with
     * ({@code register.created}); {@code null} when it says none. Only as much of the log is read as it takes to
     * find that line. A log that does not begin with it, whose first line is no line, or that cannot be opened or
     * read makes no claim: a damaged register of one project must not stop the runs of another, nor put its
     * trouble into their messages. Its own runs are refused by their start check, by its own name.
     */
    private static String owner(RegisterStore store, String key, String project) {
        try (RegisterStore.Opened opened = store.open(key, project)) {
            byte[] first = opened.events().first().orElse(null);
            if (first == null) {
                return null;
            }
            RegisterEvent begins = RegisterEvent.parse(first);
            if (RegisterReplay.note(begins.type())) {
                // A writer that was cut off left its note before the first line: the line the log begins with
                // comes after it
                begins = opened.events().readAll().stream().map(RegisterEvent::parse)
                        .filter(event -> !RegisterReplay.note(event.type())).findFirst().orElse(null);
            }
            // Lines of a register with none before them that says whose make no claim
            return begins == null || !RegisterEvents.CREATED.equals(begins.type()) ? null
                    : begins.text("workflow_id");
        } catch (RuntimeException e) {
            return null;   // a line that is none (IllegalArgumentException), a file that cannot be read
        }
    }

    /** What the feed left for the hook: the outcome, and the states before and after with the changes. */
    private record Fed(RegisterOutcome outcome, RegisterState before, RegisterState after, List<Change> changes,
                       boolean earlier) {
    }

    /**
     * Runs the operator's hook after the lock is released, when the report changed something or was rejected, and
     * records how it ended under the lock afterwards; a run the register took in before gets no hook again.
     */
    private RegisterOutcome hook(AuditConfig config, String project, WorkflowDefinition definition, StoredRun run,
                                 Fed fed) {
        if (hookRunner == null || !hooks.onRegisterChanged() || fed.earlier()
                || (fed.outcome().applied() && !RegisterDiff.changesAnything(fed.changes()))) {
            return fed.outcome();
        }
        byte[] payload = RegisterDiff.of(config.key(), project, definition.id(), run.runId(),
                fed.outcome().applied(), fed.before(), fed.after(), fed.changes());
        String location;
        try (RegisterStore.Opened opened = store.open(config.key(), project)) {
            location = opened.location();
        }
        HookRunner.Outcome ran = hookRunner.run(hooks.registerChanged(), payload, location,
                java.time.Duration.ofSeconds(hooks.timeoutSeconds()));
        String unanchored;
        String found;
        try (RegisterStore.Opened opened = store.openForWrite(config.key(), project)) {
            RegisterLog log = RegisterLog.forWriting(opened, clock);
            // Not after a report whose own anchor just failed: its lines are the ones without an anchor, and that
            // was reported with the report
            found = fed.outcome().unanchored() != null ? null : RegisterWrites.anchorsBefore(ledger, opened, log);
            Map<String, Object> fields = new java.util.LinkedHashMap<>();
            fields.put("run_id", run.runId());
            fields.put("exit_code", ran.exitCode());
            fields.put("duration_ms", ran.durationMs());
            fields.put("stderr_tail", ran.stderrTail());
            log.append(RegisterEvents.HOOK_RAN, fields);
            RegisterWrites.writeDerived(opened, log, RegisterReplay.replay(config.key(), project, log.read(),
                    opened.blobs()));
            unanchored = RegisterWrites.anchor(ledger, opened, log);
        }
        return fed.outcome().withHook(ran.exitCode()).withUnanchored(unanchored).withAnchorNote(found);
    }

    private Fed feed(AuditConfig config, String project, WorkflowDefinition definition, StoredRun run, RunView view,
                     String runHead, byte[] catalogJson) {
        if (store.keys().contains(config.key()) && store.unassigned(config.key())) {
            // A register from before projects lies at the key: nothing is fed until someone assigns it
            return new Fed(new RegisterOutcome(config.key(), false, RegisterWrites.fromBefore(config.key()),
                    Map.of()), null, null, List.of(), true);
        }
        String gone = RegisterWrites.logGone(ledger, store, config.key(), project);
        if (gone != null) {
            // The log the ledger anchors went while the run ran: its report begins no new one in its place, and
            // nothing is written, so there is nothing to tell a hook
            return new Fed(new RegisterOutcome(config.key(), false, gone, Map.of()), null, null, List.of(), true);
        }
        if (!store.projects(config.key()).contains(project)) {
            // The first report of this project: the key may have been taken for another project while the run
            // ran, by another workflow. Then this project gets no register, not one with a rejection in it
            String foreign = ownerOtherThan(store, clock, config.key(), definition.id());
            if (foreign != null) {
                // Nothing was written, so there is nothing to tell a hook: as for a run taken in before
                return new Fed(new RegisterOutcome(config.key(), false, foreign, Map.of()), null, null, List.of(),
                        true);
            }
        }
        try (RegisterStore.Opened opened = store.openForWrite(config.key(), project)) {
            RegisterLog log = RegisterLog.forWriting(opened, clock);
            List<RegisterEvent> events = log.read();
            RegisterWriter writer = new RegisterWriter(log, opened.blobs());
            Optional<RegisterOutcome> earlier = alreadyTaken(events, run.runId(), config.key());
            if (earlier.isPresent()) {
                return new Fed(earlier.get(), null, null, List.of(), true);
            }
            RegisterState state = RegisterReplay.replay(config.key(), project, events, opened.blobs());
            // After the replay, so a register that cannot be read gets no line; the line itself changes no state
            String found = RegisterWrites.anchorsBefore(ledger, opened, log);
            if (!RegisterReplay.begun(events)) {
                // Also after the note of a writer that was cut off in this very line: the register must say whose
                // it is
                writer.created(definition.id(), config.category(), project);
                state = RegisterState.empty(config.key(), definition.id(), config.category()).ofProject(project);
            }
            String rejection = rejection(state, definition, view, config);
            Report report = rejection == null ? report(run, view, config, catalogJson, opened) : null;
            if (rejection == null && report.errors != null) {
                rejection = report.errors.getFirst();
            }
            if (rejection != null) {
                writer.rejected(run.runId(), rejection, report == null || report.errors == null ? List.of()
                        : report.errors);
                RegisterState after = RegisterReplay.replay(config.key(), project, log.read(), opened.blobs());
                RegisterWrites.writeDerived(opened, log, after);
                return new Fed(new RegisterOutcome(config.key(), false, rejection, Map.of())
                        .withUnanchored(RegisterWrites.anchor(ledger, opened, log)).withAnchorNote(found), state, after,
                        List.of(), false);
            }
            Reconciler.Applied applied = writer.applied(state, new Reconciler.Report(run.runId(), view.finishedAt(),
                    report.reportBlob, report.findings, factNames(run, view, config), report.gone, report.items,
                    report.itemsComplete), report.catalog,
                    catalogJson,
                    runHead, higherSeverity);
            RegisterWrites.writeDerived(opened, log, applied.state());
            Map<Change.Kind, Integer> counts = new EnumMap<>(Change.Kind.class);
            applied.changes().forEach(change -> counts.merge(change.kind(), 1, Integer::sum));
            RegisterOutcome outcome = new RegisterOutcome(config.key(), true, null, counts)
                    .withUnanchored(RegisterWrites.anchor(ledger, opened, log)).withAnchorNote(found);
            if (report.items != null) {
                Map<traceworks.core.register.ItemState, Integer> states = applied.state().itemCounts();
                outcome = outcome.withItems(new RegisterOutcome.ItemTally(report.items.size(),
                        (int) report.items.stream().filter(traceworks.core.register.ReportedItem::judged)
                                .count(),
                        states.get(traceworks.core.register.ItemState.CLEAN)
                                + states.get(traceworks.core.register.ItemState.DECIDED)));
            }
            return new Fed(outcome, state, applied.state(), applied.changes(), false);
        }
    }

    /**
     * The facts the run measured: the names of the fact lines of every top-level step except the report block, which
     * cannot vouch for its own findings (decided 2026-11-11).
     */
    private static Set<String> factNames(StoredRun run, RunView view, AuditConfig config) {
        List<String> outputs = new java.util.ArrayList<>();
        for (StepView step : view.steps()) {
            if (config.reportBlock().equals(step.name()) || step.output() == null) {
                continue;
            }
            run.blobs().get(step.output().sha256()).ifPresent(bytes ->
                    outputs.add(new String(bytes, StandardCharsets.UTF_8)));
        }
        return traceworks.core.register.EvidenceVerification.factNames(outputs);
    }

    /** The outcome recorded for this run already, if the register took it in before (a resume, a completion). */
    private static Optional<RegisterOutcome> alreadyTaken(List<RegisterEvent> events, String runId, String key) {
        for (RegisterEvent event : events) {
            if (!runId.equals(event.text("run_id"))) {
                continue;
            }
            if (RegisterEvents.REPORT_APPLIED.equals(event.type())) {
                Map<Change.Kind, Integer> counts = new EnumMap<>(Change.Kind.class);
                event.fields().path("counts").fields().forEachRemaining(entry -> counts.put(
                        Change.Kind.valueOf(entry.getKey().toUpperCase(java.util.Locale.ROOT)), entry.getValue().asInt()));
                return Optional.of(new RegisterOutcome(key, true, null, counts));
            }
            if (RegisterEvents.REPORT_REJECTED.equals(event.type())) {
                return Optional.of(new RegisterOutcome(key, false, event.text("reason"), Map.of()));
            }
        }
        return Optional.empty();
    }

    private static String rejection(RegisterState state, WorkflowDefinition definition, RunView view,
                                    AuditConfig config) {
        if (state.workflowId() != null && !state.workflowId().equals(definition.id())) {
            return "the register " + config.key() + " belongs to the workflow " + state.workflowId()
                    + ", not to " + definition.id();
        }
        if (view.status() != RunStatus.COMPLETED && view.status() != RunStatus.COMPLETED_WITH_ERRORS) {
            return "the run ended " + view.status() + ", so its report does not count";
        }
        StepView report = reportStep(view, config);
        if (report == null) {
            return "the report block '" + config.reportBlock() + "' ran no step";
        }
        if (report.status() != StepStatus.COMPLETED) {
            return "the report step '" + config.reportBlock() + "' ended " + report.status();
        }
        if (report.output() == null) {
            return "the report step '" + config.reportBlock() + "' wrote no output";
        }
        return null;
    }

    private static StepView reportStep(RunView view, AuditConfig config) {
        for (StepView step : view.steps()) {
            if (config.reportBlock().equals(step.name())) {
                return step;
            }
        }
        return null;
    }

    /** The parsed report: the findings, what it names as gone, its items ({@code null} for none) and the blobs, or the errors. */
    private record Report(String reportBlob, List<ReportedFinding> findings, List<GoneFinding> gone,
                          List<traceworks.core.register.ReportedItem> items, boolean itemsComplete,
                          Catalog catalog, List<String> errors) {

        /** A report that was not read: why. */
        static Report unread(Catalog catalog, List<String> errors) {
            return new Report(null, List.of(), List.of(), null, false, catalog, errors);
        }
    }

    /** Reads and parses the report; its text goes into the register's own blobs, so the register stays self-contained. */
    private static Report report(StoredRun run, RunView view, AuditConfig config, byte[] catalogJson,
                                 RegisterStore.Opened opened) {
        StepView step = reportStep(view, config);
        BlobRef output = step.output();
        byte[] bytes = run.blobs().get(output.sha256()).orElse(null);
        if (bytes == null) {
            return Report.unread(null, List.of("the output of the report step is deleted"));
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        String reportBlob = opened.blobs().put(bytes).sha256();
        Catalog catalog = null;
        if (catalogJson != null) {
            try {
                catalog = Catalog.parse(catalogJson);
            } catch (IllegalArgumentException e) {
                return Report.unread(null, List.of(e.getMessage()));
            }
        }
        List<ReportedFinding> findings;
        List<GoneFinding> gone = List.of();   // only an envelope can name a finding as gone, or list items
        List<traceworks.core.register.ReportedItem> items = null;
        boolean itemsComplete = false;
        if (AuditResultParser.hasEnvelopeStart(text)) {
            AuditResultParser.ParseResult parsed = AuditResultParser.parse(text);
            if (!parsed.valid()) {
                return Report.unread(catalog, parsed.errors());
            }
            findings = parsed.findings();
            gone = parsed.gone();
            items = parsed.items();
            itemsComplete = parsed.itemsComplete();
        } else {
            try {
                findings = TodoItemLines.parse(text);
            } catch (TimedRegex.TimedOut e) {
                return Report.unread(catalog, List.of("the todo_item lines of the report could not be"
                        + " read within " + TodoItemLines.TIME_LIMIT.toSeconds() + " seconds (" + e.getMessage()
                        + "): a line that costs its pattern this much is no finding"));
            } catch (IllegalArgumentException e) {
                return Report.unread(catalog, List.of(e.getMessage()));
            }
        }
        if (catalog != null) {
            List<String> problems = new ArrayList<>(catalog.problems(findings));
            problems.addAll(catalog.problemsOfGone(gone));
            if (!problems.isEmpty()) {
                return Report.unread(catalog, problems);
            }
        }
        return new Report(reportBlob, findings, gone, items, itemsComplete, catalog, null);
    }
}
