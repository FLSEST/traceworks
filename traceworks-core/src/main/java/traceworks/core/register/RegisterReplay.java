package traceworks.core.register;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.port.BlobStore;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The state of a register as a fold over its log: every applied report is applied again, from the findings its line
 * points to, so the state needs no file of its own to be known, and {@code state.json} can be checked against it.
 */
public final class RegisterReplay {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RegisterReplay() {
    }

    /**
     * The state of the register of {@code key} and {@code project}: the fold of its log, which must name that
     * project. A log that names another one, or none, is not the register of the project: its folder was copied or
     * renamed, or the log lost its beginning.
     *
     * @throws IllegalStateException if a line cannot be replayed, or the log is not that of the project
     */
    public static RegisterState replay(String key, String project, List<RegisterEvent> events, BlobStore blobs) {
        RegisterState state = replay(key, events, blobs);
        String other = otherProject(key, project, state.project(), begins(events));
        if (other != null) {
            throw new IllegalStateException(other);
        }
        return state;
    }

    /**
     * Whether the log has begun: it holds a line that is no note of the tool ({@code register.repaired},
     * {@code register.unanchored}). A log without one is no register yet: it has no line, or only what a writer
     * that was cut off before the first line left. A log that has begun must say whose it is ({@link #begins}).
     */
    public static boolean begun(List<RegisterEvent> events) {
        return begins(events) != null;
    }

    /**
     * As {@link #begun(List)}, of the complete lines of a log as they lie: it holds a line that is no note of the
     * tool. A line that is no line of a register counts: such a log is damaged, not gone, and is refused for what
     * it is.
     */
    public static boolean begunLines(List<byte[]> lines) {
        for (byte[] line : lines) {
            try {
                if (!note(RegisterEvent.parse(line).type())) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                return true;
            }
        }
        return false;
    }

    /**
     * The line the log begins with: its first line that is no note of the tool; {@code null} for a log that has
     * not begun. It is the line that says whose the register is ({@code register.created}), or the log is that of
     * no project.
     */
    static RegisterEvent begins(List<RegisterEvent> events) {
        return events.stream().filter(event -> !note(event.type())).findFirst().orElse(null);
    }

    /** Whether a line of this type is a note of the tool, which holds no state and may stand before the first line. */
    public static boolean note(String type) {
        return RegisterEvents.REPAIRED.equals(type) || RegisterEvents.UNANCHORED.equals(type);
    }

    /**
     * The project a register from before registers had projects was assigned to, as its log says
     * ({@code register.assigned}); {@code null} for a log without that line.
     */
    public static String assignedTo(List<RegisterEvent> events) {
        return events.stream().filter(event -> RegisterEvents.ASSIGNED.equals(event.type()))
                .map(event -> event.text("project")).findFirst().orElse(null);
    }

    /**
     * What is wrong with a log as the log of the register of {@code key} and {@code project}; {@code null} for
     * nothing. A log that has not begun is the register of any project yet. One that has must begin with the line
     * that says whose it is ({@code register.created}), and name this project: lines of a register without that
     * line before them name no project, whatever is appended to them later.
     *
     * @param project the project the register is of; {@code null} for a register from before registers had
     *                projects, whose log names none
     * @param named   the project the log names
     * @param begins  the line the log begins with ({@link #begins}); {@code null} for a log that has not begun
     */
    static String otherProject(String key, String project, String named, RegisterEvent begins) {
        if (begins == null) {
            return null;
        }
        String register = "the register " + key + (project == null ? "" : " of " + project);
        String not = project == null ? "no register from before registers had projects"
                : "not the register of " + project;
        if (!RegisterEvents.CREATED.equals(begins.type())) {
            return "the log of " + register + " does not begin with the line that says whose it is ("
                    + RegisterEvents.CREATED + "): its first line is seq " + begins.seq() + ", " + begins.type()
                    + ". A folder that was copied or renamed, or a log that lost its beginning, is " + not;
        }
        if (java.util.Objects.equals(project, named)) {
            return null;
        }
        return "the log of " + register + " names " + (named == null ? "no project" : "the project " + named)
                + ": a folder that was copied or renamed is " + not;
    }

    /**
     * The fold of a log, whatever project it names.
     *
     * @throws IllegalStateException if a line cannot be replayed (an unknown type, a blob the fold needs is gone)
     */
    public static RegisterState replay(String key, List<RegisterEvent> events, BlobStore blobs) {
        RegisterState state = RegisterState.empty(key, null, null);
        RegisterEvent begins = begins(events);
        for (RegisterEvent event : events) {
            String again = saidAgain(begins, event);
            if (again != null) {
                throw new IllegalStateException(again);
            }
            state = apply(state, event, blobs);
        }
        return state;
    }

    /**
     * Why {@code event} may not say whose the register is; {@code null} when it may, or does not. A log says it
     * once: {@code register.created} is the line the log begins with, and there is one. A further line of that
     * type is not folded: it would give the lines before it, a copied folder's decisions among them, to another
     * project or another workflow. ({@code register.assigned}, the one other line that names a project, is refused
     * by the fold itself where the register names one already.)
     *
     * @param begins the line the log begins with ({@link #begins}); the same object as {@code event} for that line
     */
    static String saidAgain(RegisterEvent begins, RegisterEvent event) {
        if (!RegisterEvents.CREATED.equals(event.type()) || event == begins) {
            return null;
        }
        return "seq " + event.seq() + ": " + RegisterEvents.CREATED + " stands after the line the log begins with"
                + " (seq " + begins.seq() + ", " + begins.type() + "): " + (RegisterEvents.CREATED.equals(begins.type())
                ? "a log says once whose it is, in its first line, and a later line that says it again is not taken"
                : "the log does not begin with the line that says whose it is, and a later line that says it is not"
                + " taken");
    }

    /** The state after {@code event}. */
    static RegisterState apply(RegisterState state, RegisterEvent event, BlobStore blobs) {
        switch (event.type()) {
            case RegisterEvents.CREATED -> {
                // A line of a version before registers had projects names none
                return state.ownedBy(event.text("workflow_id"), event.text("category"))
                        .ofProject(event.text("project"));
            }
            case RegisterEvents.REPORT_APPLIED -> {
                String sha = event.text("findings_blob");
                byte[] bytes = blobs.get(sha).orElseThrow(() -> new IllegalStateException("seq " + event.seq()
                        + ": the findings blob " + sha + " is deleted, so the log cannot be replayed"));
                List<ReportedFinding> findings = new ArrayList<>();
                try {
                    for (JsonNode node : JSON.readTree(bytes)) {
                        findings.add(RegisterWriter.reported(node));
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("seq " + event.seq() + ": the findings blob cannot be read", e);
                }
                List<GoneFinding> gone = new ArrayList<>();
                String goneSha = event.text("gone_blob");   // absent in a line of an earlier version, and when none
                if (goneSha != null) {
                    byte[] goneBytes = blobs.get(goneSha).orElseThrow(() -> new IllegalStateException("seq "
                            + event.seq() + ": the gone blob " + goneSha + " is deleted, so the log cannot be replayed"));
                    try {
                        for (JsonNode node : JSON.readTree(goneBytes)) {
                            gone.add(new GoneFinding(node.path("rule_key").asText(), node.path("subject").asText()));
                        }
                    } catch (IOException e) {
                        throw new IllegalStateException("seq " + event.seq() + ": the gone blob cannot be read", e);
                    }
                }
                List<ReportedItem> items = null;   // absent in a line of an earlier version, and without a list
                String itemsSha = event.text("items_blob");
                if (itemsSha != null) {
                    byte[] itemBytes = blobs.get(itemsSha).orElseThrow(() -> new IllegalStateException("seq "
                            + event.seq() + ": the items blob " + itemsSha + " is deleted, so the log cannot be replayed"));
                    items = new ArrayList<>();
                    try {
                        for (JsonNode node : JSON.readTree(itemBytes)) {
                            items.add(new ReportedItem(node.path("name").asText(), node.path("digest").asText(),
                                    node.path("judged").asBoolean()));
                        }
                    } catch (IOException e) {
                        throw new IllegalStateException("seq " + event.seq() + ": the items blob cannot be read", e);
                    }
                }
                Set<String> factNames = new TreeSet<>();
                event.fields().path("fact_names").forEach(name -> factNames.add(name.asText()));
                String finishedAt = event.text("finished_at");
                Reconciler.Report report = new Reconciler.Report(event.text("run_id"),
                        finishedAt == null ? null : Instant.parse(finishedAt), event.text("report_blob"), findings,
                        factNames, gone, items, event.fields().path("items_complete").asBoolean(false));
                // A line without the setting is of a version that kept every decision and named no severity; a
                // value nobody knows is a broken line
                String setting = event.text("decision_on_higher_severity");
                DecisionOnHigherSeverity higherSeverity = setting == null ? DecisionOnHigherSeverity.UNCHECKED
                        : DecisionOnHigherSeverity.of(setting).orElseThrow(() -> new IllegalStateException("seq "
                        + event.seq() + ": unknown decision_on_higher_severity '" + setting + "'"));
                return Reconciler.apply(state, report, catalog(event, blobs), higherSeverity).state();
            }
            case RegisterEvents.REQUIRES -> {
                String feature = event.text("feature");
                if (RegisterEvents.DECISION_AGENT.equals(feature)) {
                    return state;   // lines after it may name an agent, which the fold of a decision reads
                }
                if (!RegisterEvents.DECISION_SEVERITY.equals(feature)) {
                    throw new IllegalStateException("seq " + event.seq() + ": the register requires '" + feature
                            + "', which this version of Traceworks does not know: a newer version wrote it");
                }
                return state.namingDecidedSeverity();   // from here on the states name the severity decided on
            }
            case RegisterEvents.ASSIGNED -> {
                String project = event.text("project");
                if (state.project() != null) {
                    throw new IllegalStateException("seq " + event.seq() + ": register.assigned names the project "
                            + project + ", and the register is that of " + state.project() + " already");
                }
                if (!traceworks.core.model.ProjectKey.matches(project)) {
                    throw new IllegalStateException("seq " + event.seq() + ": register.assigned names '" + project
                            + "', which is no project key");
                }
                return state.ofProject(project);   // from here on the states name the project
            }
            case RegisterEvents.REPAIRED, RegisterEvents.UNANCHORED -> {
                return state;   // a torn tail was cut, or lines without an anchor were found: no state is in it
            }
            case RegisterEvents.REPORT_REJECTED -> {
                return state.withLastReport(new RegisterState.LastReport(event.text("run_id"), null, null, false));
            }
            case RegisterEvents.FINDING_ACCEPTED, RegisterEvents.FINDING_DISMISSED -> {
                Finding finding = requireFinding(state, event);
                Finding after = RegisterEvents.FINDING_ACCEPTED.equals(event.type())
                        ? Disposition.accept(finding, event.text("note"), event.text("actor"), event.at(),
                                event.text("until") == null ? null : Instant.parse(event.text("until")),
                                event.text("agent"))
                        : Disposition.dismiss(finding, event.text("note"), event.text("actor"), event.at(),
                                event.text("agent"));   // absent in a line without an agent, and of an earlier version
                // The severity the decision covers is the one the fold holds at this line, for a line of any version
                return state.with(replacing(state, after));
            }
            case RegisterEvents.FINDING_REOPENED -> {
                if (!"manual".equals(event.text("reason"))) {
                    return state;   // the fold of the applied report already holds it
                }
                Finding after = Disposition.reopen(requireFinding(state, event), event.text("note"),
                        event.text("actor"), event.at());
                return state.with(replacing(state, after));
            }
            case RegisterEvents.FINDING_NEW, RegisterEvents.FINDING_RESOLVED, RegisterEvents.FINDING_REVIEW_DUE,
                 RegisterEvents.HOOK_RAN -> {
                return state;   // derived from, or beside, an applied report: the fold already holds it
            }
            default -> throw new IllegalStateException("seq " + event.seq() + ": unknown register event "
                    + event.type());
        }
    }

    /** The catalog the report was applied with, from its blob; {@code null} when the run had none. */
    private static Catalog catalog(RegisterEvent event, BlobStore blobs) {
        String sha = event.text("catalog_blob");
        if (sha == null) {
            return null;
        }
        byte[] bytes = blobs.get(sha).orElseThrow(() -> new IllegalStateException("seq " + event.seq()
                + ": the catalog blob " + sha + " is deleted, so the log cannot be replayed"));
        try {
            return Catalog.parse(bytes);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("seq " + event.seq() + ": the catalog blob cannot be read: "
                    + e.getMessage(), e);
        }
    }

    private static Finding requireFinding(RegisterState state, RegisterEvent event) {
        Finding finding = state.finding(event.text("id"));
        if (finding == null) {
            throw new IllegalStateException("seq " + event.seq() + ": " + event.type() + " names the finding "
                    + event.text("id") + ", which the register does not hold");
        }
        return finding;
    }

    private static List<Finding> replacing(RegisterState state, Finding after) {
        List<Finding> findings = new ArrayList<>();
        for (Finding f : state.findings()) {
            findings.add(f.id().equals(after.id()) ? after : f);
        }
        return findings;
    }
}
