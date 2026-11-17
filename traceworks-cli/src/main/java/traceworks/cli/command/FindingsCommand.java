package traceworks.cli.command;

import traceworks.cli.Args.Parsed;
import traceworks.cli.Args.Spec;
import traceworks.cli.Command;
import traceworks.cli.Context;
import traceworks.cli.ExitCodes;
import traceworks.cli.TerminalFormat;
import traceworks.cli.UsageException;
import com.fasterxml.jackson.databind.JsonNode;
import traceworks.core.app.FindingDetails;
import traceworks.core.app.Findings;
import traceworks.core.record.ChainReport;
import traceworks.core.register.DecisionAgent;
import traceworks.core.register.Disposition;
import traceworks.core.register.Finding;
import traceworks.core.register.RegisterEvent;
import traceworks.core.register.RegisterEvents;
import traceworks.core.register.Item;
import traceworks.core.register.ItemState;
import traceworks.core.register.ItemsText;
import traceworks.core.register.FindingStatus;
import traceworks.core.register.RegisterState;
import traceworks.core.register.StateJson;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code traceworks findings [<key> list|show|items|verify]}: what a register holds (docs/registers.md). Without a
 * key it lists the registers. {@code list} prints the findings that are not resolved, one per line; {@code show} one
 * finding with every field; {@code items} the items the audit judges, each with its state; {@code verify} checks the register's chain, blobs and state file, and the log against its anchors in the
 * ledger (exit 8 when it does not hold).
 */
public final class FindingsCommand implements Command {

    private static final Spec SPEC = Spec.of("findings", "Lists, decides and checks the findings of a register:"
                    + " 'findings <key> list|show|items|accept|dismiss|reopen|verify'.",
                    "[<key> [list | show <finding> | items | accept <finding> | dismiss <finding> | reopen <finding> | render | verify]]")
            .positionals(0, 3)
            .flag("all", "list: every finding, the resolved ones too")
            .flag("json", "list, items: as a JSON array, each entry with the fields it has in state.json")
            .option("status", "S", "list: only findings with this status, such as OPEN or ACCEPTED_RISK")
            .option("note", "TEXT", "accept, dismiss: why (required); reopen: optional")
            .option("until", "YYYY-MM-DD", "accept: the review date, at most 180 days ahead (the default)")
            .option("agent", "NAME", "accept, dismiss, reopen: the agent that decides under your user, recorded"
                    + " beside the actor; without it, the environment variable " + DecisionAgent.ENV)
            .note("""
                    findings                    the registers of this home, each with its counts by status, and
                                                for an audit that lists its items how many of them are settled
                    findings <key> list         the findings of the register that are not resolved: id, status,
                                                severity, rule key, subject and title; sorted by status, severity,
                                                rule key and subject; --json prints them as a JSON array
                    findings <key> show <id>    one finding, by its id or a prefix of at least 8 characters, and
                                                its detail (what is wrong, the fix, the quoted line) from
                                                findings.jsonl of the run that last reported it
                    findings <key> items        the items the audit judges (classes, areas of the code), each with
                                                its state (open, not judged, decided, clean) and the digest of
                                                its last verdict; --json prints them as a JSON array
                    findings <key> accept <id>  --note "why" [--until YYYY-MM-DD]: accept the finding as a risk;
                                                it comes up for review at the date, 180 days ahead by default
                    findings <key> dismiss <id> --note "why": the finding is no concern; it comes up again only
                                                when a report states it with a higher severity
                    findings <key> reopen <id>  [--note "why"]: an accepted, dismissed or resolved finding is open
                    findings <key> render       writes state.json and report.html of the register again
                    findings <key> verify       the register's chain, its blobs and state.json, and its log against
                                                the anchors in the ledger; exit 8 when it does not hold
                    Every decision is recorded in the register with who decided and when; a wrong transition or
                    a date out of range is refused (exit 6). Who decided is the actor who ran the command, and an
                    agent that decides under that user names itself: --agent NAME, or TRACEWORKS_AGENT in the
                    environment of a harness (the option wins). The name is at most 64 characters: ASCII letters
                    and digits, . _ - : / @ + and single spaces; a blank one is refused (exit 2). It is what the
                    caller says, unsigned: a decision without it reads as a person's.
                    A decision covers the severity it was made on: a
                    report that states the finding with a higher one opens it again, unless traceworks.json
                    says "decision_on_higher_severity": "keep".""");

    /** The order {@code list} shows the statuses in. */
    private static final List<FindingStatus> ORDER = List.of(FindingStatus.OPEN, FindingStatus.AWAITING_VERIFICATION,
            FindingStatus.ACCEPTED_RISK, FindingStatus.DISMISSED, FindingStatus.RESOLVED);

    @Override
    public Spec spec() {
        return SPEC;
    }

    @Override
    public int run(Context context, Parsed args) {
        List<String> words = args.positionals();
        Findings findings = context.findings();
        if (words.isEmpty()) {
            return registers(context.out(), findings);
        }
        String key = words.get(0);
        if (!findings.keys().contains(key)) {
            throw new UsageException("No register '" + key + "'" + (findings.keys().isEmpty()
                    ? "; no audit run has fed one" : "; the registers are " + String.join(", ", findings.keys())));
        }
        String action = words.size() > 1 ? words.get(1) : "list";
        if (args.option("agent") != null && !List.of("accept", "dismiss", "reopen").contains(action)) {
            throw new UsageException("--agent belongs to accept, dismiss and reopen: it names who decides");
        }
        return switch (action) {
            case "list" -> list(context.out(), findings.state(key), args);
            case "show" -> {
                if (words.size() < 3) {
                    throw new UsageException("findings " + key + " show needs the id of a finding");
                }
                yield show(context, findings.state(key), findings.events(key), words.get(2));
            }
            case "items" -> items(context.out(), findings.state(key), args);
            case "accept", "dismiss", "reopen" -> {
                int code = decide(context.out(), findings, key, action, words, args, context.env());
                unanchored(context.err(), findings, key, "the decision is recorded, but it");
                yield code;
            }
            case "render" -> {
                context.out().println(findings.render(key) + "/report.html");
                unanchored(context.err(), findings, key, "the head of the register");
                yield ExitCodes.OK;
            }
            case "verify" -> verify(context.out(), key, findings.verified(key));
            default -> throw new UsageException("unknown findings command '" + action
                    + "'; the commands are 'list', 'show', 'items', 'accept', 'dismiss', 'reopen', 'render' and"
                    + " 'verify'");
        };
    }

    private static int decide(PrintStream out, Findings findings, String key, String action, List<String> words,
                              Parsed args, Map<String, String> env) {
        if (words.size() < 3) {
            throw new UsageException("findings " + key + " " + action + " needs the id of a finding");
        }
        // Who decides under the actor: the option, else the variable of a harness. A blank or bad name is refused
        // here, before anything is asked of the register: it must not be recorded as a person's decision
        String agent;
        try {
            agent = DecisionAgent.resolve(args.option("agent"), env);
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage());
        }
        String note = args.option("note");
        String untilText = args.option("until");
        if (!action.equals("reopen") && (note == null || note.isBlank())) {
            throw new UsageException("findings " + key + " " + action + " needs --note \"why\"");
        }
        if (!action.equals("accept") && untilText != null) {
            throw new UsageException("--until belongs to accept only");
        }
        java.time.Instant until = null;
        if (untilText != null) {
            try {
                until = java.time.LocalDate.parse(untilText).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            } catch (java.time.format.DateTimeParseException e) {
                throw new UsageException("--until takes a date as YYYY-MM-DD, got '" + untilText + "'");
            }
        }
        Finding finding = switch (action) {
            case "accept" -> findings.accept(key, words.get(2), note, until, agent);
            case "dismiss" -> findings.dismiss(key, words.get(2), note, agent);
            default -> findings.reopen(key, words.get(2), note, agent);
        };
        String id = finding.id().substring(0, 8);
        // The agent as it was recorded, so a harness sees that its name arrived; nothing more for a person
        String through = TerminalFormat.safe(DecisionAgent.through(agent));
        out.println(switch (action) {
            case "accept" -> "accepted " + id + " as a risk" + through + ", review by "
                    + finding.disposition().until().atZone(java.time.ZoneOffset.UTC).toLocalDate();
            case "dismiss" -> "dismissed " + id + through;
            default -> "reopened " + id + through;
        });
        return ExitCodes.OK;
    }

    private static int registers(PrintStream out, Findings findings) {
        List<String> keys = findings.keys();
        if (keys.isEmpty()) {
            out.println("no registers; no audit run has fed one");
            return ExitCodes.OK;
        }
        for (String key : keys) {
            RegisterState state = findings.state(key);
            Map<FindingStatus, Integer> counts = new EnumMap<>(FindingStatus.class);
            state.findings().forEach(f -> counts.merge(f.status(), 1, Integer::sum));
            StringBuilder line = new StringBuilder(TerminalFormat.safe(key)).append("  ")
                    .append(state.category() == null ? "-" : TerminalFormat.oneLine(state.category()));
            for (FindingStatus status : ORDER) {
                if (counts.containsKey(status)) {
                    line.append("  ").append(status).append(' ').append(counts.get(status));
                }
            }
            out.println(line);
            if (!state.items().isEmpty()) {
                out.println("  items " + ItemsText.summary(state));
            }
        }
        return ExitCodes.OK;
    }

    /** The items of a register, what needs a look first: state, the digest of the last verdict, name. */
    private static int items(PrintStream out, RegisterState state, Parsed args) {
        if (args.has("json")) {
            // ASCII only, with every control and format character escaped (StateJson.items)
            out.print(StateJson.items(state));
            out.flush();
            return ExitCodes.OK;
        }
        if (state.items().isEmpty()) {
            out.println("no items; the audit of this register lists none");
            return ExitCodes.OK;
        }
        List<Item> listed = ItemsText.listed(state);
        int width = listed.stream().mapToInt(i -> i.verdictDigest() == null ? 1 : i.verdictDigest().length()).max()
                .orElse(1);
        for (Item item : listed) {
            out.println(String.format("%-10s  %-" + width + "s  %s", ItemState.of(item, state.findings()).word(),
                    item.verdictDigest() == null ? "-" : item.verdictDigest(), TerminalFormat.safe(item.name())));
        }
        return ExitCodes.OK;
    }

    private static int list(PrintStream out, RegisterState state, Parsed args) {
        FindingStatus only = status(args.option("status"));
        List<Finding> listed = state.findings().stream()
                .filter(f -> only != null ? f.status() == only : args.has("all") || f.status() != FindingStatus.RESOLVED)
                .sorted(Comparator.comparing((Finding f) -> ORDER.indexOf(f.status())).thenComparing(Finding::severity)
                        .thenComparing(Finding::ruleKey).thenComparing(Finding::subject))
                .toList();
        if (args.has("json")) {
            // ASCII only, with every control and format character escaped (StateJson.findings)
            out.print(StateJson.findings(listed));
            out.flush();
            return ExitCodes.OK;
        }
        listed.forEach(f -> out.println(String.format("%s  %-9s  %-8s  %s  %s  %s%s", f.id().substring(0, 8),
                f.status(), f.severity(), TerminalFormat.oneLine(f.ruleKey()),
                TerminalFormat.oneLine(f.subject()), TerminalFormat.oneLine(f.title()), decisionNote(f))));
        return ExitCodes.OK;
    }

    /**
     * What a row says about the decision on its finding, when the row alone would mislead. A decision covers the
     * severity it was made on, so a decision in force on a finding that has another severity now names the one it
     * was made on; and a decision that is no longer in force (the finding was opened again, came up for review or
     * was resolved) says so, so that nobody reads an open finding as decided. And a decision an agent made names
     * the agent, so that nobody reads the status as a person's decision: {@code (through the agent claude-code)},
     * or inside one of the two notes above. Empty otherwise.
     */
    private static String decisionNote(Finding f) {
        if (f.disposition() == null) {
            return "";
        }
        String kind = f.disposition().kind() == FindingStatus.ACCEPTED_RISK ? "accepted" : "dismissed";
        String on = f.disposition().severity() == null ? "" : " on " + f.disposition().severity();
        // The name is bounded where it is given; a log that was edited can hold any, so it is made safe here
        String agent = TerminalFormat.oneLine(DecisionAgent.through(f.disposition().agent()));
        String through = agent.isEmpty() ? "" : " " + agent;
        if (!f.decisionInForce()) {
            return "  (" + kind + on + through + " before, not in force)";
        }
        if (f.decidedOnOtherSeverity() != null) {
            return "  (decided on " + f.decidedOnOtherSeverity() + through + ")";
        }
        return agent.isEmpty() ? "" : "  (" + agent + ")";
    }

    private static FindingStatus status(String value) {
        if (value == null) {
            return null;
        }
        try {
            return FindingStatus.valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new UsageException("--status takes one of " + List.of(FindingStatus.values()) + ", got '" + value + "'");
        }
    }

    private static int show(Context context, RegisterState state, List<RegisterEvent> events, String reference) {
        PrintStream out = context.out();
        if (reference.length() < 8) {
            throw new UsageException("a finding is named by its id or a prefix of at least 8 characters");
        }
        List<Finding> matches = state.findings().stream().filter(f -> f.id().startsWith(reference)).toList();
        if (matches.size() != 1) {
            throw new UsageException(matches.isEmpty() ? "No finding of " + state.key() + " starts with " + reference
                    : matches.size() + " findings of " + state.key() + " start with " + reference);
        }
        Finding f = matches.getFirst();
        out.println("Finding   " + f.id());
        out.println("Register  " + TerminalFormat.safe(state.key()));
        out.println("Status    " + f.status());
        out.println("Severity  " + f.severity());
        out.println("Rule      " + TerminalFormat.oneLine(f.ruleKey()));
        out.println("Subject   " + TerminalFormat.oneLine(f.subject()));
        out.println("Title     " + TerminalFormat.oneLine(f.title()));
        if (f.description() != null) {
            out.println("About     " + TerminalFormat.oneLine(f.description()));
        }
        if (f.evidence() != null) {
            out.println("Evidence  " + TerminalFormat.oneLine(f.evidence()) + (f.unverifiedEvidence() ? " (unverified)" : ""));
        }
        if (!f.tags().isEmpty()) {
            out.println("Tags      " + TerminalFormat.oneLine(String.join(", ", f.tags())));
        }
        out.println("First     run " + TerminalFormat.safe(f.firstSeen().runId()) + " at " + f.firstSeen().at());
        out.println("Last      run " + TerminalFormat.safe(f.lastSeen().runId()) + " at " + f.lastSeen().at());
        out.println("Occurrences " + f.occurrences());
        out.println("Regressions " + f.regressions());
        if (f.absentReports() > 0 && (f.status() == FindingStatus.OPEN
                || f.status() == FindingStatus.AWAITING_VERIFICATION)) {
            // Only a finding of a judged rule gets here: silence resolves a measured one
            out.println("Silent    the last " + f.absentReports() + " applied " + (f.absentReports() == 1 ? "report"
                    : "reports") + " did not mention it");
        }
        if (f.resolvedAt() != null) {
            out.println("Resolved  " + f.resolvedAt());
        }
        if (f.disposition() != null) {
            Finding.DispositionRecord d = f.disposition();
            String madeOn = Disposition.madeOn(f);
            out.println("Decision  " + d.kind() + " by " + TerminalFormat.safe(d.actor())
                    + TerminalFormat.safe(DecisionAgent.through(d.agent())) + " at " + d.at()
                    + (d.until() == null ? "" : ", review by " + d.until())
                    + (madeOn == null ? "" : ", " + madeOn)
                    + (f.status() == d.kind() ? "" : "; not in force: the finding is " + f.status()));
            String evidence = decidedEvidence(events, f);
            if (evidence != null && !evidence.equals(f.evidence())) {
                out.println("Made on   evidence: " + TerminalFormat.oneLine(evidence));
            }
            out.println("Note      " + TerminalFormat.oneLine(d.note() == null ? "(no note)" : d.note()));
        }
        detail(out, FindingDetails.of(context.services(), f));
        return ExitCodes.OK;
    }

    /**
     * The evidence the finding had when the person decided: what an acceptance froze, else what the line of the
     * decision in the log names; {@code null} when neither does (a dismissal an earlier version recorded).
     */
    private static String decidedEvidence(List<RegisterEvent> events, Finding f) {
        if (f.disposition().evidence() != null) {
            return f.disposition().evidence();
        }
        String type = f.disposition().kind() == FindingStatus.ACCEPTED_RISK ? RegisterEvents.FINDING_ACCEPTED
                : RegisterEvents.FINDING_DISMISSED;
        for (RegisterEvent event : events.reversed()) {
            if (type.equals(event.type()) && f.id().equals(event.text("id"))) {
                return event.text("evidence");
            }
        }
        return null;
    }

    /** The fields of a place that have a line of their own form; every other one is printed by its name. */
    private static final List<String> PLACE = List.of("file", "line", "quote", "model");

    /**
     * What the run that last reported the finding wrote about it into findings.jsonl: the item, and per place the
     * file, the line and the quoted line, then every other field the audit's tool wrote, by its name. What is under
     * {@code model} are a model's words: they are marked as such, since nothing checked them. Like everything from a
     * run, each value is printed on one line, made harmless for a terminal and cut.
     */
    private static void detail(PrintStream out, FindingDetails.Detail detail) {
        if (detail.missing() != null) {
            out.println("Detail    none: " + TerminalFormat.oneLine(detail.missing()));
            return;
        }
        out.println("Detail    from " + FindingDetails.FILE + " of run " + TerminalFormat.safe(detail.runId()));
        for (JsonNode entry : detail.entries()) {
            if (entry.hasNonNull("item")) {
                out.println("  item      " + TerminalFormat.oneLine(entry.get("item").asText()));
            }
            int shown = 0;
            for (JsonNode place : entry.path("details")) {
                if (!place.isObject() || shown++ >= FindingDetails.MAX_ENTRIES) {
                    continue;
                }
                if (place.hasNonNull("file")) {
                    out.println("  file      " + TerminalFormat.oneLine(place.get("file").asText()
                            + (place.hasNonNull("line") ? ":" + place.get("line").asText() : "")));
                }
                if (place.hasNonNull("quote")) {
                    out.println("  quote     " + TerminalFormat.oneLine(place.get("quote").asText()));
                }
                fields(place, "  ", 9).forEach(out::println);
                List<String> words = fields(place.path("model"), "    ", 7);
                if (!words.isEmpty()) {
                    out.println("  the model's words, not checked:");
                    words.forEach(out::println);
                }
            }
        }
    }

    /** The scalar fields of {@code object} that say something, one line each: the name, padded, and the value. */
    private static List<String> fields(JsonNode object, String indent, int width) {
        List<String> lines = new ArrayList<>();
        object.fieldNames().forEachRemaining(name -> {
            JsonNode value = object.get(name);
            if (!PLACE.contains(name) && !value.isNull() && !value.isContainerNode() && !value.asText().isBlank()) {
                lines.add(indent + String.format("%-" + width + "s", TerminalFormat.oneLine(name)) + " "
                        + TerminalFormat.oneLine(value.asText()));
            }
        });
        return lines;
    }

    /**
     * Says on standard error what the write just made found of the anchors before it wrote (lines without an anchor,
     * which it took and recorded), and that the ledger did not take its own anchor; the write stays either way.
     */
    private static void unanchored(PrintStream err, Findings findings, String key, String what) {
        findings.anchorNote().ifPresent(note -> err.println("traceworks: note: " + TerminalFormat.safe(note)));
        findings.unanchored().ifPresent(why -> err.println("traceworks: warning: " + what + " is not anchored in the"
                + " ledger (" + TerminalFormat.oneLine(why) + "); 'traceworks findings " + TerminalFormat.safe(key)
                + " render' anchors it once the ledger can be written"));
    }

    /**
     * The chain line says whether the log, its blobs and its states hold by themselves; the anchor line whether the
     * log still ends where the ledger says it ended after every write.
     */
    private static int verify(PrintStream out, String key, Findings.Verified verified) {
        ChainReport report = verified.report();
        String anchor = verified.anchor() == null ? null : "anchor    " + TerminalFormat.safe(verified.anchor());
        if (report.intact()) {
            out.println("chain     ok (" + report.events() + " events, head " + TerminalFormat.safe(report.head()) + ")");
            if (anchor != null) {
                out.println(anchor);
            }
            out.println("verified");
            return ExitCodes.OK;
        }
        // By what the chain's own check found, never by the words of a problem: a key may be any word
        out.println(verified.chainHolds() ? "chain     ok (" + report.events() + " events, head "
                + TerminalFormat.safe(report.head()) + ")" : "chain     NOT intact (" + report.events()
                + " events read)");
        if (anchor != null) {
            out.println(anchor);
        }
        report.problems().forEach(problem -> out.println("  " + TerminalFormat.safe(problem)));
        out.println("register " + TerminalFormat.safe(key) + " does not verify");
        return ExitCodes.VERIFY_FAILED;
    }
}
