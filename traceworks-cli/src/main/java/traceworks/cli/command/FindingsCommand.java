package traceworks.cli.command;

import traceworks.cli.Args.Parsed;
import traceworks.cli.Args.Spec;
import traceworks.cli.Command;
import traceworks.cli.Context;
import traceworks.cli.ExitCodes;
import traceworks.cli.ProjectOption;
import traceworks.cli.TerminalFormat;
import traceworks.cli.TerminalText;
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
import traceworks.core.util.Message;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code traceworks findings [<key> list|show|items|verify] [--project KEY]}: what a register holds
 * (docs/registers.md). Without a key it lists the registers, each with its project. {@code list} prints the findings
 * that are not resolved, one per line; {@code show} one
 * finding with every field; {@code items} the items the audit judges, each with its state; {@code verify} checks the register's chain, blobs and state file, and the log against its anchors in the
 * ledger (exit 8 when it does not hold).
 *
 * <p>A register is that of a key and a project. Without {@code --project} a command means the declared project the
 * working directory lies in ({@code projects} in {@code traceworks.json}); when the key has no register of that
 * project it is refused (exit 2) and names the projects the key has. In a folder of no declared project it means
 * the only project the key has; with more than one it is refused (exit 2) and names them. The option wins, and is
 * not checked against the working directory: a decision is about a register, not about a folder. Every
 * confirmation line names the key and the project of the register the command acted on.
 */
public final class FindingsCommand implements Command {

    private static final Spec SPEC = Spec.of("findings", "Lists, decides and checks the findings of a register:"
                    + " 'findings <key> list|show|items|accept|dismiss|reopen|verify|assign'.",
                    "[<key> [list | show <finding> | items | accept <finding> | dismiss <finding> | reopen <finding> | render | verify | assign]]")
            .positionals(0, 3)
            .flag("all", "list: every finding, the resolved ones too")
            .flag("json", "list, items: as a JSON array, each entry with the fields it has in state.json")
            .option("status", "S", "list: only findings with this status, such as OPEN or ACCEPTED_RISK")
            .option("note", "TEXT", "accept, dismiss: why (required); reopen: optional")
            .option("until", "YYYY-MM-DD", "accept: the review date, at most 180 days ahead (the default)")
            .option("agent", "NAME", "accept, dismiss, reopen: the agent that decides under your user, recorded"
                    + " beside the actor; without it, the environment variable " + DecisionAgent.ENV)
            .option(ProjectOption.NAME, ProjectOption.VALUE, "the project whose register of <key> is meant; without"
                    + " it, the project \"projects\" in traceworks.json binds to the working directory, and in a"
                    + " folder of no project the only project the key has. assign: the project the register"
                    + " is given (required)")
            .note("""
                    findings                    the registers of this home, each with its key, its project and
                                                its counts by status, and for an audit that lists its items how
                                                many of them are settled
                    findings <key> list         the findings of the register that are not resolved: id, status,
                                                severity, rule key, subject and title (no title that only repeats
                                                the rule key and the subject); sorted by status, severity, rule
                                                key and subject; --json prints them as a JSON array
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
                                                the anchors in the ledger; exit 8 when it does not hold. Without
                                                --project it also checks a register from before registers had
                                                projects, as it lies
                    findings <key> assign       --project KEY: gives a register from before registers had
                                                projects its project, and moves it to its place; one that does
                                                not verify is refused (exit 8) and left as it is
                    A register is kept per key and project: one audit run on two projects (run --project KEY)
                    feeds two registers. Without --project a command means the project that "projects" in
                    traceworks.json binds to the working directory or to a folder above it; when its key has no
                    register of that project, the command is refused (exit 2) and names the projects the key
                    has. In a folder of no project it means the only project its key has; where the key has
                    more than one, name the project with --project KEY, else the command is refused (exit 2).
                    The option wins whatever the working directory says, and assign always needs it. accept,
                    dismiss, reopen, render and assign say which register they acted on: its key and project.
                    A register an earlier version left under its key alone is the register of no project, and
                    nothing assigns it by itself: until 'findings <key> assign --project KEY' does, every run
                    with that key and every other findings command on it but verify is refused (exit 6). The
                    overview lists it as not assigned.
                    A register that cannot be used (its log cannot be read or replayed, names another project,
                    or is gone while the ledger holds anchors of it) is refused with the reason (exit 6); the
                    overview lists it as refused, goes on, and exits 6. A register with a blob that was changed
                    ends a command with exit 8 and the verify command of that register; the overview lists it as
                    damaged, goes on, and exits 8. A ledger that is there and cannot be listed does not say
                    which projects a key has: a command without --project is refused then (exit 6), and the
                    overview says so for each key. A decision whose line is recorded and
                    whose state.json, report.html or anchor could not be written says so and exits 6 too.
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
        String named = ProjectOption.of(args);
        if (words.isEmpty()) {
            if (named != null) {
                throw new UsageException(Message.of("--project names the project of one register: give the key of"
                        + " the register too, as in 'findings <key> list --project ", Message.typed(named), "'"));
            }
            return overview(context.out(), context.findings(), null);
        }
        String key = words.get(0);
        if (!context.findings().keys().contains(key)) {
            List<String> keys = context.findings().keys();
            // A key no folder has may be one the ledger anchors: a ledger that cannot say is not "no register"
            context.findings().ledgerUnlisted().ifPresent(why -> context.findings().projectOf(key));
            throw new UsageException(Message.of("No register '", Message.typed(key), "'", keys.isEmpty()
                    ? "; no audit run has fed one" : Message.of("; the registers are ",
                    Message.join(", ", keys.stream().map(known -> Message.of(Message.quoted(known))).toList()))));
        }
        String action = words.size() > 1 ? words.get(1) : "list";
        if (args.option("agent") != null && !List.of("accept", "dismiss", "reopen").contains(action)) {
            throw new UsageException(Message.of("--agent belongs to accept, dismiss and reopen: it names who decides"));
        }
        if (action.equals("assign")) {
            return assign(context, key, named, words);
        }
        if (context.findings().unassigned(key)) {
            if (action.equals("verify") && named == null) {
                // The one command besides assign that works on a register from before: it says what is wrong
                // with one that assign refuses
                return verify(context.out(), key, null, context.findings().verified(key));
            }
            // Refused by the use case, with the command that assigns the register: before any project is asked for
            context.findings().projectOf(key);
        }
        String project = project(context.findings(), key, named, named != null ? null
                : ProjectOption.ofFolder(context.projects(), context.workingDirectory()));
        Findings findings = context.findings().ofProject(project);
        return switch (action) {
            case "list" -> list(context.out(), findings.state(key), args);
            case "show" -> {
                if (words.size() < 3) {
                    throw new UsageException(Message.of("findings ", Message.typed(key), " show needs the id of a finding"));
                }
                yield show(context, findings.state(key), findings.events(key), words.get(2));
            }
            case "items" -> items(context.out(), findings.state(key), args);
            case "accept", "dismiss", "reopen" -> {
                int code = decide(context.out(), findings, key, project, action, words, args, context.env());
                yield unanchored(context.err(), findings, key, project, "the decision is recorded, but it") ? code
                        : ExitCodes.REFUSED;
            }
            case "render" -> {
                TerminalText.println(context.out(), "rendered the register ", TerminalText.whole(key), " of ",
                        TerminalText.whole(project), ": ", TerminalText.safe(findings.render(key) + "/report.html"));
                yield unanchored(context.err(), findings, key, project, "the head of the register") ? ExitCodes.OK
                        : ExitCodes.REFUSED;
            }
            case "verify" -> verify(context.out(), key, project, findings.verified(key));
            default -> throw new UsageException(Message.of("unknown findings command '", Message.typed(action),
                    "'; the commands are 'list', 'show', 'items', 'accept', 'dismiss', 'reopen', 'render',"
                    + " 'verify' and 'assign'"));
        };
    }

    /**
     * {@code findings <key> assign --project KEY}: gives the register from before registers had projects that lies
     * at the key its project. The project is always named: nothing else says which project the register was kept
     * for.
     */
    private static int assign(Context context, String key, String project, List<String> words) {
        if (words.size() > 2) {
            throw new UsageException(Message.of("findings ", Message.typed(key), " assign takes no finding: it"
                    + " assigns the whole register"));
        }
        if (project == null) {
            throw new UsageException(Message.of("findings ", Message.typed(key), " assign needs --project KEY: the"
                    + " project the register was kept for"));
        }
        Findings findings = context.findings();
        String location = findings.assign(key, project);
        TerminalText.println(context.out(), "assigned the register ", TerminalText.whole(key), " to the project ",
                TerminalText.whole(project), ": ", TerminalText.safe(location));
        // What the assignment found of the anchors (a log that does not match them is assigned all the same)
        // stands where the assignment is said, as a paragraph of its own: it is part of what was done
        findings.anchorNote().ifPresent(note -> {
            context.out().println();
            TerminalText.println(context.out(), "note: ", TerminalText.safe(note));
        });
        return written(context.err(), findings, key, project, "the register is assigned, but its head")
                ? ExitCodes.OK : ExitCodes.REFUSED;
    }

    /**
     * The project of the register a command on {@code key} means: the one {@code --project} names, which must be
     * one the key has; without the option the declared project the working directory lies in, which must be one
     * the key has too; and in a folder of no declared project the only project the key has. The folder of a
     * project the key has no register of is no silent default for another project's register.
     *
     * @param here the declared project the working directory lies in; {@code null} when it lies in none, and when
     *             the option names the project, which is not checked against the working directory
     * @throws UsageException if the key has no such project, or has several and neither the command nor the
     *                        working directory names one of them
     */
    private static String project(Findings findings, String key, String named, String here) {
        List<String> projects = findings.projects(key);
        Message known = Message.join(", ", projects.stream().map(project -> Message.of(Message.quoted(project)))
                .toList());
        if (named == null) {
            // A ledger that is there and cannot be listed does not shrink the projects of the key to its folders:
            // refused by the use case, which asks for the project
            findings.ledgerUnlisted().ifPresent(why -> findings.projectOf(key));
        }
        if (named != null) {
            if (!projects.contains(named)) {
                // Refuses with the reason when the register the ledger anchors under the key alone is gone, and
                // when the ledger cannot say whether it anchors a register of that project
                findings.earlierGone(key).ifPresent(gone -> findings.ofProject(named).projectOf(key));
                findings.ledgerUnlisted().ifPresent(why -> findings.ofProject(named).projectOf(key));
                throw new UsageException(Message.of("No register '", Message.typed(key), "' of the project '",
                        Message.typed(named), "'", projects.isEmpty() ? "; no audit run has fed one"
                        : Message.of("; its projects are ", known)));
            }
            return named;
        }
        if (projects.isEmpty()) {
            findings.projectOf(key);   // refuses with the reason when the register the ledger anchors is gone
            throw new UsageException(Message.of("No register '", Message.typed(key), "'; no audit run has fed one"));
        }
        if (here != null && projects.contains(here)) {
            return here;
        }
        if (here != null) {
            // The folder says which project is meant, and the key has no register of it: the only project the
            // key has would be another one, and a decision would reach a register nobody named
            throw new UsageException(Message.of("The working directory lies in the project '", Message.quoted(here),
                    "' (\"" + traceworks.adapters.config.Projects.SETTING + "\" in traceworks.json), and the key '",
                    Message.typed(key), "' has no register of that project: its projects are ", known,
                    "; name one with --project KEY"));
        }
        if (projects.size() > 1) {
            throw new UsageException(Message.of("The register '", Message.typed(key), "' has " + projects.size()
                    + " projects (", known, "): name one with --project KEY"));
        }
        return projects.getFirst();
    }

    private static int decide(PrintStream out, Findings findings, String key, String project, String action,
                              List<String> words, Parsed args, Map<String, String> env) {
        if (words.size() < 3) {
            throw new UsageException(Message.of("findings ", Message.typed(key), " " + action
                    + " needs the id of a finding"));
        }
        // Who decides under the actor: the option, else the variable of a harness. A blank or bad name is refused
        // here, before anything is asked of the register: it must not be recorded as a person's decision
        String agent;
        try {
            agent = DecisionAgent.resolve(args.option("agent"), env);
        } catch (IllegalArgumentException e) {
            throw new UsageException(Message.of(e));
        }
        String note = args.option("note");
        String untilText = args.option("until");
        if (!action.equals("reopen") && (note == null || note.isBlank())) {
            throw new UsageException(Message.of("findings ", Message.typed(key), " " + action
                    + " needs --note \"why\""));
        }
        if (!action.equals("accept") && untilText != null) {
            throw new UsageException(Message.of("--until belongs to accept only"));
        }
        java.time.Instant until = null;
        if (untilText != null) {
            try {
                until = java.time.LocalDate.parse(untilText).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            } catch (java.time.format.DateTimeParseException e) {
                throw new UsageException(Message.of("--until takes a date as YYYY-MM-DD, got '",
                        Message.typed(untilText), "'"));
            }
        }
        Finding finding = switch (action) {
            case "accept" -> findings.accept(key, words.get(2), note, until, agent);
            case "dismiss" -> findings.dismiss(key, words.get(2), note, agent);
            default -> findings.reopen(key, words.get(2), note, agent);
        };
        String id = finding.id().substring(0, 8);
        // The agent as it was recorded, so a harness sees that its name arrived; nothing more for a person
        Object through = TerminalFormat.through(agent);
        // Which register the command acted on: the project may have come from the working directory, or be the
        // only one the key has, so the line says it
        Object in = TerminalText.seq(" in the register ", TerminalText.whole(key), " of ",
                TerminalText.whole(project));
        switch (action) {
            case "accept" -> TerminalText.println(out, "accepted " + id + " as a risk", in, through, ", review by "
                    + finding.disposition().until().atZone(java.time.ZoneOffset.UTC).toLocalDate());
            case "dismiss" -> TerminalText.println(out, "dismissed " + id, in, through);
            default -> TerminalText.println(out, "reopened " + id, in, through);
        }
        return ExitCodes.OK;
    }

    /**
     * The overview of the registers of a home, as {@code traceworks findings} prints it.
     *
     * @param only the one project whose registers are listed, for a front end that shows the registers of one
     *             project and no other (the MCP server): a key without a register of it gets no line, and neither
     *             does a register from before registers had projects, which is of no project; {@code null} for
     *             every register of the home
     * @return the exit code: 8 when a listed register has a changed blob, else 6 when one was refused, else 0
     */
    public static int overview(PrintStream out, Findings findings, String only) {
        List<String> keys = findings.keys();
        // A ledger that is there and cannot be listed: the folders are listed, and each key says that the ledger
        // cannot say which projects it has, since one whose folder is gone is known to the ledger alone
        java.util.Optional<String> unlisted = findings.ledgerUnlisted();
        if (keys.isEmpty()) {
            if (unlisted.isPresent()) {
                TerminalText.println(out, "refused: this home holds no register, and the ledger cannot say which"
                        + " registers it anchors (", TerminalText.oneLine(unlisted.get()), ")");
                return ExitCodes.REFUSED;
            }
            if (only == null) {
                out.println("no registers; no audit run has fed one");
                return ExitCodes.OK;
            }
        }
        // The list is finished whatever one register is; a register that was refused, or that has a changed blob,
        // is the exit code at its end: 8 when any had a changed blob, else 6 when any was refused
        int code = ExitCodes.OK;
        boolean any = false;
        for (String key : keys) {
            Integer ofKey = listed(out, findings, key, only);
            if (ofKey == null) {
                continue;   // no register of the one project that is listed
            }
            any = true;
            code = worse(code, ofKey);
            if (unlisted.isPresent()) {
                TerminalText.println(out, TerminalText.whole(key), "  refused: the ledger cannot say which projects"
                        + " the key has (", TerminalText.oneLine(unlisted.get()), "): a project whose folder is gone"
                        + " is not listed here, and a command on the key needs --project KEY");
                code = worse(code, ExitCodes.REFUSED);
            }
        }
        if (!any && only != null) {
            TerminalText.println(out, "no registers of the project ", TerminalText.whole(only),
                    "; no audit run of it has fed one");
        }
        return code;
    }

    /** The exit code of an overview that has both: a changed blob before a refusal, a refusal before none. */
    private static int worse(int one, int other) {
        return one == ExitCodes.VERIFY_FAILED || other == ExitCodes.VERIFY_FAILED ? ExitCodes.VERIFY_FAILED
                : one == ExitCodes.REFUSED || other == ExitCodes.REFUSED ? ExitCodes.REFUSED : ExitCodes.OK;
    }

    /**
     * The lines of one key in the overview: one per register, with its project and its counts by status, or with
     * why it is refused, or that a blob of it was changed and the command that checks it.
     *
     * @param only the one project whose register is listed; {@code null} for every project of the key
     * @return {@link ExitCodes#OK} when every register of the key was listed, {@link ExitCodes#VERIFY_FAILED} when
     *         one had a changed blob, else {@link ExitCodes#REFUSED} when one was refused; {@code null} when only
     *         one project is listed and the key has no register of it, so nothing was printed
     */
    private static Integer listed(PrintStream out, Findings findings, String key, String only) {
        int all = ExitCodes.OK;
        List<String> projects;
        try {
            if (only != null && !findings.projects(key).contains(only)) {
                return null;
            }
            if (only == null && findings.unassigned(key)) {
                java.util.Optional<String> begun = findings.assignmentBegun(key);
                if (begun.isPresent()) {
                    // An assignment that was cut off after its line: said as that, with the command that ends it
                    TerminalText.println(out, TerminalText.whole(key), "  not assigned to a project: its assignment"
                            + " to ", TerminalText.oneLine(begun.get()), " was begun and not finished; finish it"
                            + " with 'traceworks findings ", TerminalText.whole(key), " assign --project ",
                            TerminalText.oneLine(begun.get()), "'");
                } else {
                    TerminalText.println(out, TerminalText.whole(key), "  not assigned to a project: a register"
                            + " from before registers had projects; assign it with 'traceworks findings ",
                            TerminalText.whole(key), " assign --project KEY'");
                }
            }
            java.util.Optional<String> earlier = findings.earlierGone(key);
            if (earlier.isPresent()) {
                // The register the ledger anchors under the key alone is gone: no register of the key is used
                TerminalText.println(out, TerminalText.whole(key), "  refused: ",
                        earlier.get().substring(0, 1).toUpperCase(Locale.ROOT) + earlier.get().substring(1));
                return ExitCodes.REFUSED;
            }
            projects = only == null ? findings.projects(key) : List.of(only);
        } catch (traceworks.core.app.RunRefusedException e) {
            // What lies at the key cannot be listed: one line for the key, with the reason
            TerminalText.println(out, TerminalText.whole(key), "  refused: ", TerminalText.of(e));
            return ExitCodes.REFUSED;
        }
        for (String project : projects) {
            RegisterState state;
            try {
                state = findings.ofProject(project).state(key);
            } catch (traceworks.core.port.BlobStore.ChangedBlobException e) {
                // One line for a register with a blob that was changed: the reason, which ends with the command
                // that checks this register, and on to the others
                TerminalText.println(out, TerminalText.whole(key), "  ", TerminalText.whole(project),
                        "  damaged: ", TerminalText.safe(e.getMessage().replaceAll("\\s+", " ")));
                all = worse(all, ExitCodes.VERIFY_FAILED);
                continue;
            } catch (traceworks.core.app.RunRefusedException e) {
                // One line for a register that cannot be used, with the reason, and on to the others
                TerminalText.println(out, TerminalText.whole(key), "  ", TerminalText.whole(project),
                        "  refused: ", TerminalText.of(e));
                all = worse(all, ExitCodes.REFUSED);
                continue;
            }
            Map<FindingStatus, Integer> counts = new EnumMap<>(FindingStatus.class);
            state.findings().forEach(f -> counts.merge(f.status(), 1, Integer::sum));
            // The key and the project are names of folders of the home; the category is the workflow's word
            List<Object> line = new ArrayList<>(List.of(TerminalText.whole(key), "  ",
                    TerminalText.whole(project), "  ",
                    state.category() == null ? "-" : TerminalText.oneLine(state.category())));
            for (FindingStatus status : ORDER) {
                if (counts.containsKey(status)) {
                    line.add("  " + status + ' ' + counts.get(status));
                }
            }
            TerminalText.println(out, line);
            if (!state.items().isEmpty()) {
                TerminalText.println(out, "  items ", ItemsText.summary(state));
            }
        }
        return all;
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
            TerminalText.println(out, String.format("%-10s  ", ItemState.of(item, state.findings()).word()),
                    TerminalText.safe(item.verdictDigest() == null ? "-" : item.verdictDigest()).padded(width), "  ",
                    TerminalText.safe(item.name()));
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
        listed.forEach(f -> row(out, f));
        return ExitCodes.OK;
    }

    /**
     * One row of the list. The rule, the subject and the title are a report's text: a row too long goes on on
     * marked lines. A title that says nothing the row does not say ({@link #repeats}) is left out; and under a
     * subject that did not fit its line the title begins a line of its own, marked as a text that begins one, so
     * that a reader sees where the subject ends.
     */
    private static void row(PrintStream out, Finding f) {
        List<Object> row = new ArrayList<>(List.of(String.format("%s  %-9s  %-8s  ", f.id().substring(0, 8),
                f.status(), f.severity()), TerminalText.oneLine(f.ruleKey()), "  ", TerminalText.oneLine(f.subject())));
        if (repeats(f.title(), f.ruleKey(), f.subject())) {
            TerminalText.println(out, row, decisionNote(f));
        } else if (TerminalText.lines(row).size() > 1) {
            TerminalText.println(out, row);
            TerminalText.println(out, "    ", TerminalText.oneLine(f.title()), decisionNote(f));
        } else {
            TerminalText.println(out, row, "  ", TerminalText.oneLine(f.title()), decisionNote(f));
        }
    }

    /** What a title may hold beside the rule and the subject and still say nothing of its own. */
    private static final Set<String> FILLER = Set.of("finding", "findings", "in", "of", "for", "at", "on", "the", "a",
            "an");
    /** Where a rule key, a subject and a title are taken apart: white space and the separators between parts. */
    private static final Pattern PARTS = Pattern.compile("[\\s#,/:_-]+");

    /**
     * Whether a title only repeats its row. The title, the rule key and the subject are each put in lower case
     * and taken apart at white space and at {@code # , / : _ -}; the title repeats the row when each of its parts
     * is a part of the rule key or of the subject, or one of the filler words {@code finding}, {@code findings},
     * {@code in}, {@code of}, {@code for}, {@code at}, {@code on}, {@code the}, {@code a} and {@code an}, in any
     * order. So {@code path_check finding in a/Mount.java, Mount.check(Path)} repeats the subject
     * {@code a/Mount.java#Mount.check(Path)}, as {@code Package cycle: core.x} repeats the rule
     * {@code package-cycle} and the subject {@code core.x}, and an empty title repeats any row. Any other part
     * keeps the title: a word of its own, and a number too ({@code 2 path_check findings in ...}), since the row
     * says the count nowhere else. The comparison reads the texts and prints nothing.
     */
    static boolean repeats(String title, String ruleKey, String subject) {
        Set<String> known = new HashSet<>(FILLER);
        known.addAll(parts(ruleKey));
        known.addAll(parts(subject));
        return known.containsAll(parts(title));
    }

    private static List<String> parts(String text) {
        return text == null ? List.of() : PARTS.splitAsStream(text.toLowerCase(Locale.ROOT))
                .filter(part -> !part.isEmpty()).toList();
    }

    /**
     * What a row says about the decision on its finding, when the row alone would mislead. A decision covers the
     * severity it was made on, so a decision in force on a finding that has another severity now names the one it
     * was made on; and a decision that is no longer in force (the finding was opened again, came up for review or
     * was resolved) says so, so that nobody reads an open finding as decided. And a decision an agent made names
     * the agent, so that nobody reads the status as a person's decision: {@code (through the agent claude-code)},
     * or inside one of the two notes above. Empty otherwise.
     */
    private static Object decisionNote(Finding f) {
        if (f.disposition() == null) {
            return "";
        }
        String kind = f.disposition().kind() == FindingStatus.ACCEPTED_RISK ? "accepted" : "dismissed";
        String on = f.disposition().severity() == null ? "" : " on " + f.disposition().severity();
        // The name is bounded where it is given; a log that was edited can hold any, so it is made safe here
        String name = f.disposition().agent();
        Object through = TerminalFormat.through(name);
        if (!f.decisionInForce()) {
            return TerminalText.seq("  (" + kind + on, through, " before, not in force)");
        }
        if (f.decidedOnOtherSeverity() != null) {
            return TerminalText.seq("  (decided on " + f.decidedOnOtherSeverity(), through, ")");
        }
        return name == null ? "" : TerminalText.seq("  (" + DecisionAgent.through("").strip() + " ",
                TerminalText.oneLine(name), ")");
    }

    private static FindingStatus status(String value) {
        if (value == null) {
            return null;
        }
        try {
            return FindingStatus.valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new UsageException(Message.of("--status takes one of " + List.of(FindingStatus.values())
                    + ", got '", Message.typed(value), "'"));
        }
    }

    private static int show(Context context, RegisterState state, List<RegisterEvent> events, String reference) {
        PrintStream out = context.out();
        if (reference.length() < 8) {
            throw new UsageException(Message.of("a finding is named by its id or a prefix of at least 8 characters"));
        }
        List<Finding> matches = state.findings().stream().filter(f -> f.id().startsWith(reference)).toList();
        if (matches.size() != 1) {
            throw new UsageException(Message.of(matches.isEmpty() ? "No finding of " : matches.size()
                    + " findings of ", Message.typed(state.key()), matches.isEmpty() ? " starts with "
                    : " start with ", Message.typed(reference)));
        }
        Finding f = matches.getFirst();
        out.println("Finding   " + f.id());
        TerminalText.println(out, "Register  ", TerminalText.whole(state.key()),
                state.project() == null ? "" : TerminalText.seq(" of ", TerminalText.whole(state.project())));
        out.println("Status    " + f.status());
        out.println("Severity  " + f.severity());
        TerminalText.println(out, "Rule      ", TerminalText.oneLine(f.ruleKey()));
        TerminalText.println(out, "Subject   ", TerminalText.oneLine(f.subject()));
        TerminalText.println(out, "Title     ", TerminalText.oneLine(f.title()));
        if (f.description() != null) {
            TerminalText.println(out, "About     ", TerminalText.oneLine(f.description()));
        }
        if (f.evidence() != null) {
            TerminalText.println(out, "Evidence  ", TerminalText.oneLine(f.evidence()),
                    f.unverifiedEvidence() ? " (unverified)" : "");
        }
        if (!f.tags().isEmpty()) {
            TerminalText.println(out, "Tags      ", TerminalText.oneLine(String.join(", ", f.tags())));
        }
        TerminalText.println(out, "First     run ", TerminalText.safe(f.firstSeen().runId()),
                " at " + f.firstSeen().at());
        TerminalText.println(out, "Last      run ", TerminalText.safe(f.lastSeen().runId()),
                " at " + f.lastSeen().at());
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
            TerminalText.println(out, "Decision  " + d.kind() + " by ", TerminalText.safe(d.actor()),
                    TerminalFormat.through(d.agent()), " at " + d.at()
                    + (d.until() == null ? "" : ", review by " + d.until())
                    + (madeOn == null ? "" : ", " + madeOn)
                    + (f.status() == d.kind() ? "" : "; not in force: the finding is " + f.status()));
            String evidence = decidedEvidence(events, f);
            if (evidence != null && !evidence.equals(f.evidence())) {
                TerminalText.println(out, "Made on   evidence: ", TerminalText.oneLine(evidence));
            }
            TerminalText.println(out, "Note      ", TerminalText.oneLine(d.note() == null ? "(no note)" : d.note()));
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
            TerminalText.println(out, "Detail    none: ", TerminalText.oneLine(detail.missing()));
            return;
        }
        TerminalText.println(out, "Detail    from " + FindingDetails.FILE + " of run ",
                TerminalText.safe(detail.runId()));
        for (JsonNode entry : detail.entries()) {
            if (entry.hasNonNull("item")) {
                TerminalText.println(out, "  item      ", TerminalText.oneLine(entry.get("item").asText()));
            }
            int shown = 0;
            for (JsonNode place : entry.path("details")) {
                if (!place.isObject() || shown++ >= FindingDetails.MAX_ENTRIES) {
                    continue;
                }
                if (place.hasNonNull("file")) {
                    TerminalText.println(out, "  file      ", TerminalText.oneLine(place.get("file").asText()
                            + (place.hasNonNull("line") ? ":" + place.get("line").asText() : "")));
                }
                if (place.hasNonNull("quote")) {
                    TerminalText.println(out, "  quote     ", TerminalText.oneLine(place.get("quote").asText()));
                }
                fields(place, "  ", 9).forEach(line -> TerminalText.println(out, line));
                List<List<Object>> words = fields(place.path("model"), "    ", 7);
                if (!words.isEmpty()) {
                    out.println("  the model's words, not checked:");
                    words.forEach(line -> TerminalText.println(out, line));
                }
            }
        }
    }

    /** The scalar fields of {@code object} that say something, one line each: the name, padded, and the value. */
    private static List<List<Object>> fields(JsonNode object, String indent, int width) {
        List<List<Object>> lines = new ArrayList<>();
        object.fieldNames().forEachRemaining(name -> {
            JsonNode value = object.get(name);
            if (!PLACE.contains(name) && !value.isNull() && !value.isContainerNode() && !value.asText().isBlank()) {
                lines.add(List.of(indent, TerminalText.oneLine(name).padded(width), " ",
                        TerminalText.oneLine(value.asText())));
            }
        });
        return lines;
    }

    /**
     * Says on standard error what the write just made found of the anchors before it wrote (lines without an anchor,
     * which it took and recorded), and what failed after its line was appended: the files derived from the log
     * could not be written, or the ledger did not take its anchor. The line stays either way, and the command says
     * exactly that and does not end as one that did all it was for.
     *
     * @return whether everything after the line was written; {@code false} for a command that exits
     *         {@link ExitCodes#REFUSED}
     */
    private static boolean unanchored(PrintStream err, Findings findings, String key, String project, String what) {
        findings.anchorNote().ifPresent(note -> TerminalText.println(err, "traceworks: note: ",
                TerminalText.safe(note)));
        return written(err, findings, key, project, what);
    }

    /** The part of {@link #unanchored} about what failed after the line was appended; it prints no note. */
    private static boolean written(PrintStream err, Findings findings, String key, String project, String what) {
        findings.notWritten().ifPresent(why -> TerminalText.println(err, "traceworks: warning: the decision is"
                + " recorded in the log of the register, but ", TerminalText.oneLine(why), "; 'traceworks findings ",
                TerminalText.whole(key), " render --project ", TerminalText.whole(project),
                "' writes them once they can be written"));
        findings.unanchored().ifPresent(why -> TerminalText.println(err, "traceworks: warning: " + what
                + " is not anchored in the ledger (", TerminalText.oneLine(why), "); 'traceworks findings ",
                TerminalText.whole(key), " render --project ", TerminalText.whole(project),
                "' anchors it once the ledger can be written"));
        return findings.notWritten().isEmpty() && findings.unanchored().isEmpty();
    }

    /**
     * The chain line says whether the log, its blobs and its states hold by themselves; the anchor line whether the
     * log still ends where the ledger says it ended after every write.
     */
    private static int verify(PrintStream out, String key, String project, Findings.Verified verified) {
        ChainReport report = verified.report();
        Object anchor = verified.anchor() == null ? null
                : TerminalText.seq("anchor    ", TerminalText.safe(verified.anchor()));
        if (report.intact()) {
            TerminalText.println(out, "chain     ok (" + report.events() + " events, head ",
                    TerminalText.safe(report.head()), ")");
            if (anchor != null) {
                TerminalText.println(out, anchor);
            }
            out.println("verified");
            return ExitCodes.OK;
        }
        // By what the chain's own check found, never by the words of a problem: a key may be any word
        TerminalText.println(out, verified.chainHolds() ? TerminalText.seq("chain     ok (" + report.events()
                + " events, head ", TerminalText.safe(report.head()), ")") : "chain     NOT intact (" + report.events()
                + " events read)");
        if (anchor != null) {
            TerminalText.println(out, anchor);
        }
        report.problems().forEach(problem -> TerminalText.println(out, "  ", TerminalText.safe(problem)));
        // A register from before registers had projects is the register of no project
        TerminalText.println(out, "register ", TerminalText.whole(key),
                project == null ? "" : TerminalText.seq(" of ", TerminalText.whole(project)), " does not verify");
        return ExitCodes.VERIFY_FAILED;
    }
}
