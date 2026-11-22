package traceworks.cli.command;

import traceworks.cli.Args.Parsed;
import traceworks.cli.Args.Spec;
import traceworks.cli.Command;
import traceworks.cli.Context;
import traceworks.cli.ExitCodes;
import traceworks.cli.TerminalText;
import traceworks.cli.UsageException;
import traceworks.core.app.LedgerReport;
import traceworks.core.app.VerifyReport;
import traceworks.core.record.ChainReport;
import traceworks.core.util.Message;

import java.io.PrintStream;

/**
 * {@code traceworks verify <run>}: checks the hash chain, the blobs, the manifest, the ledger entry, the start entry
 * of the ledger against the first line of the record, for a run of a sealed package the signature of the seal the
 * record holds, and for the run of an audit workflow the files of its register folder against the digests the record
 * holds.
 *
 * <p>{@code traceworks verify} without a run checks the ledger against the runs folder of the home: which runs the
 * ledger saw start and never end, with what the folder of each says, and which runs it names whose folder is gone;
 * and every register of the home, as {@code traceworks findings <key> verify} checks one.
 */
public final class VerifyCommand implements Command {

    private static final Spec SPEC = Spec.of("verify", "Verifies the record of a run. Exits 8 when it does not hold.",
                    "[<run>]")
            .positionals(0, 1)
            .option("acknowledge", "NAME", "without a run: record that what the check reports under NAME has an"
                    + " honest cause. NAME is the name a problem is listed under (a run id, or the name of a"
                    + " register such as anchors.5.hello.shop); needs --note, is kept in the ledger and listed"
                    + " for good")
            .option("note", "TEXT", "with --acknowledge: why (required)")
            .flag("json", "print the result as one JSON object: the verdict, and for the check of the home the"
                    + " counts and every run of every class; the exit codes are the same")
            .flag("quick", "without a run: do not verify the record of every ended run against its end entry"
                    + " (its chain, its blobs, the manifest it gives) nor any register, which reads every record"
                    + " and every register of the home; only the first line and the manifest of each run are"
                    + " read then")
            .note("Without a run: checks the ledger against the runs folder of the home, both ways. Lists the runs"
                    + " the ledger saw start and never end, with what the folder of each says (RUNNING, PARKED,"
                    + " INTERRUPTED, GONE, UNREADABLE, OTHER RECORD), the runs it names whose folder is gone, the"
                    + " run folders the ledger should know and does not, and what was acknowledged. Checks every"
                    + " register too, by key and project: its chain, its blobs, the project its log names and its"
                    + " log against its anchors, as 'traceworks findings <key> verify --project KEY' does; a"
                    + " register the ledger anchors that is gone is a problem, and each problem of a register is"
                    + " listed under the name of the register. Exits 8 when something does not hold that nobody"
                    + " acknowledged.");

    @Override
    public Spec spec() {
        return SPEC;
    }

    @Override
    public int run(Context context, Parsed args) {
        String acknowledge = args.option("acknowledge");
        if (acknowledge != null) {
            if (args.positional() != null) {
                throw new UsageException(Message.of("--acknowledge names its run itself; give no other run"));
            }
            if (args.option("note") == null || args.option("note").isBlank()) {
                throw new UsageException(Message.of("--acknowledge needs --note \"...\": the honest cause of what the check"
                        + " reports under that name"));
            }
            LedgerReport after = context.services().acknowledge(acknowledge, args.option("note"));
            return args.has("json") ? json(context, after) : ledger(context, after);
        }
        if (args.option("note") != null) {
            throw new UsageException(Message.of("--note goes with --acknowledge <run>"));
        }
        if (args.positional() == null) {
            LedgerReport home = context.services().ledgerReport(!args.has("quick"));
            return args.has("json") ? json(context, home) : ledger(context, home);
        }
        VerifyReport report = context.services().verify(args.positional());
        if (args.has("json")) {
            return json(context, report);
        }
        ChainReport chain = report.chain();
        PrintStream out = context.out();
        // What the check says quotes the record and its folder: text from outside, as the record is
        TerminalText.println(out, "chain     ", chain.intact() ? TerminalText.seq("ok (" + chain.events()
                + " events, head ", TerminalText.safe(chain.head()), ")") : "BROKEN");
        chain.problems().forEach(problem -> TerminalText.println(out, "  ", TerminalText.safe(problem)));
        if (!chain.deletedBlobs().isEmpty()) {
            out.println("blobs     " + chain.deletedBlobs().size() + " deleted (not a failure)");
        }
        TerminalText.println(out, "manifest  ", TerminalText.safe(report.manifest()));
        TerminalText.println(out, "ledger    ", TerminalText.safe(report.ledger()), report.ledgerReason() == null ? ""
                : TerminalText.seq(" (", TerminalText.safe(report.ledgerReason()), ")"));
        TerminalText.println(out, "start     ", TerminalText.safe(report.start()));
        TerminalText.println(out, "seal      ", TerminalText.safe(report.seal()));
        if (!VerifyReport.NOT_CHECKED.equals(report.register())) {
            // Only the run of an audit workflow has a register folder
            TerminalText.println(out, "register  ", TerminalText.safe(report.register()));
        }
        out.println(report.ok() ? "verified" : "verification FAILED");
        return report.ok() ? ExitCodes.OK : ExitCodes.VERIFY_FAILED;
    }

    /**
     * The ledger against the runs folder, both ways: what the ledger saw start and never end, what it names without
     * a folder, what the run folders say the ledger should hold, and what a person acknowledged.
     */
    private static int ledger(Context context, LedgerReport report) {
        PrintStream out = context.out();
        out.println("ledger    " + count(report.started(), "run") + " started, " + report.ended() + " ended; "
                + count(report.earlier(), "run folder") + " from before the ledger");
        out.println("started, not ended (" + report.open().size() + ")");
        for (LedgerReport.Open run : report.open()) {
            TerminalText.println(out, "  ", TerminalText.word(run.runId()), "  ",
                    TerminalText.safe(run.startedAt() == null ? "-" : run.startedAt()), "  ",
                    TerminalText.safe(run.workflowId() == null ? "-" : run.workflowId()), "  folder ",
                    TerminalText.safe(run.folder()));
        }
        out.println("ended, folder gone (" + report.endedGone().size() + ")");
        report.endedGone().forEach(run -> TerminalText.println(out, "  ", TerminalText.word(run.runId()), "  ",
                TerminalText.safe(run.detail())));
        if (!report.earlierOpen().isEmpty()) {
            out.println("from before the ledger, not ended (" + report.earlierOpen().size() + ")");
            report.earlierOpen().forEach(run -> TerminalText.println(out, "  ", TerminalText.word(run.runId()),
                    "  folder ", TerminalText.safe(run.detail())));
        }
        if (!report.inFlight().isEmpty()) {
            out.println("in flight (" + report.inFlight().size() + ")");
            report.inFlight().forEach(run -> TerminalText.println(out, "  ", TerminalText.word(run.runId()), "  ",
                    TerminalText.safe(run.detail()), "; its process is alive"));
        }
        out.println("acknowledged (" + report.acknowledged().size() + ")");
        for (LedgerReport.Acknowledged run : report.acknowledged()) {
            TerminalText.println(out, "  ", TerminalText.word(run.runId()), "  ",
                    TerminalText.safe(String.valueOf(run.said())),
                    run.covers() ? "" : " [no longer what the check finds]");
            TerminalText.println(out, "    by ", TerminalText.safe(String.valueOf(run.actor())), " at ",
                    TerminalText.safe(String.valueOf(run.at())), ": ",
                    TerminalText.oneLine(String.valueOf(run.note())));
        }
        out.println("problems (" + report.problems().size() + ")");
        report.problems().forEach(problem -> TerminalText.println(out, "  ", TerminalText.word(problem.runId()),
                "  ", TerminalText.safe(problem.text())));
        if (!report.records()) {
            out.println("records   not verified (--quick): only traceworks verify <run> checks the record of a run");
            out.println("registers not verified (--quick): only traceworks findings <key> verify checks a register");
        }
        if (report.ok()) {
            out.println("verified");
            return ExitCodes.OK;
        }
        // A problem of a register is listed under the name of the register, which is what is acknowledged
        out.println("verification FAILED: " + count(report.problems().size(), "problem")
                + "; acknowledge one that has an honest cause with traceworks verify --acknowledge <name> --note"
                + " \"...\", where <name> is what the problem is listed under: a run id, or the name of a register");
        return ExitCodes.VERIFY_FAILED;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static int print(Context context, com.fasterxml.jackson.databind.node.ObjectNode root, boolean ok) {
        try {
            // The texts of a report hold what a record, a ledger entry or a note says, and this goes to a terminal.
            // JSON escapes only what is below U+0020; with everything from DEL on escaped too, no control or format
            // character arrives raw, and a reader gets the same strings
            context.out().println(JSON.writerWithDefaultPrettyPrinter()
                    .with(com.fasterxml.jackson.core.json.JsonWriteFeature.ESCAPE_NON_ASCII)
                    .writeValueAsString(root).replace("\u007f", "\\u007f"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return ok ? ExitCodes.OK : ExitCodes.VERIFY_FAILED;
    }

    /** The check of a run as one JSON object. */
    private static int json(Context context, VerifyReport report) {
        var root = JSON.createObjectNode();
        root.put("verified", report.ok());
        var chain = root.putObject("chain");
        chain.put("intact", report.chain().intact());
        chain.put("events", report.chain().events());
        chain.put("head", report.chain().head());
        report.chain().problems().forEach(chain.putArray("problems")::add);
        chain.put("deleted_blobs", report.chain().deletedBlobs().size());
        root.put("manifest", report.manifest());
        root.put("ledger", report.ledger());
        root.put("ledger_reason", report.ledgerReason());
        root.put("start", report.start());
        root.put("seal", report.seal());
        root.put("register", report.register());
        return print(context, root, report.ok());
    }

    /** The check of the home as one JSON object: the counts, and every run of every class. */
    private static int json(Context context, LedgerReport report) {
        var root = JSON.createObjectNode();
        root.put("verified", report.ok());
        root.put("records_verified", report.records());
        root.put("started", report.started());
        root.put("ended", report.ended());
        root.put("before_ledger", report.earlier());
        var open = root.putArray("open");
        report.open().forEach(run -> open.addObject().put("run", run.runId()).put("started_at", run.startedAt())
                .put("workflow_id", run.workflowId()).put("folder", run.folder()));
        var gone = root.putArray("ended_folder_gone");
        report.endedGone().forEach(run -> gone.addObject().put("run", run.runId()).put("status", run.detail()));
        var before = root.putArray("before_ledger_not_ended");
        report.earlierOpen().forEach(run -> before.addObject().put("run", run.runId()).put("folder", run.detail()));
        var flying = root.putArray("in_flight");
        report.inFlight().forEach(run -> flying.addObject().put("run", run.runId()).put("detail", run.detail()));
        var acknowledged = root.putArray("acknowledged");
        report.acknowledged().forEach(ack -> acknowledged.addObject().put("run", ack.runId())
                .put("problem", ack.problem()).put("said", ack.said()).put("actor", ack.actor()).put("at", ack.at())
                .put("note", ack.note()).put("covers", ack.covers()));
        var problems = root.putArray("problems");
        report.problems().forEach(problem -> problems.addObject().put("run", problem.runId())
                .put("code", problem.code()).put("text", problem.text()));
        return print(context, root, report.ok());
    }

    private static String count(int n, String one) {
        return n + " " + (n == 1 ? one : one + "s");
    }
}
