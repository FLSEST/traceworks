package traceworks.cli.command;

import traceworks.cli.Args.Parsed;
import traceworks.cli.Args.Spec;
import traceworks.cli.Command;
import traceworks.cli.Context;
import traceworks.cli.ExitCodes;
import traceworks.cli.TerminalFormat;
import traceworks.cli.UsageException;
import traceworks.core.app.LedgerReport;
import traceworks.core.app.VerifyReport;
import traceworks.core.record.ChainReport;

import java.io.PrintStream;

/**
 * {@code traceworks verify <run>}: checks the hash chain, the blobs, the manifest, the ledger entry, the start entry
 * of the ledger against the first line of the record, for a run of a sealed package the signature of the seal the
 * record holds, and for the run of an audit workflow the files of its register folder against the digests the record
 * holds.
 *
 * <p>{@code traceworks verify} without a run checks the ledger against the runs folder of the home: which runs the
 * ledger saw start and never end, with what the folder of each says, and which runs it names whose folder is gone.
 */
public final class VerifyCommand implements Command {

    private static final Spec SPEC = Spec.of("verify", "Verifies the record of a run. Exits 8 when it does not hold.",
                    "[<run>]")
            .positionals(0, 1)
            .option("acknowledge", "RUN", "without a run: record that one problem the check reports of RUN has an"
                    + " honest cause; needs --note, is kept in the ledger and listed for good")
            .option("note", "TEXT", "with --acknowledge: why (required)")
            .flag("json", "print the result as one JSON object: the verdict, and for the check of the home the"
                    + " counts and every run of every class; the exit codes are the same")
            .flag("quick", "without a run: do not verify the record of every ended run against its end entry"
                    + " (its chain, its blobs, the manifest it gives), which reads every record of the home;"
                    + " only the first line and the manifest of each are read then")
            .note("Without a run: checks the ledger against the runs folder of the home, both ways. Lists the runs"
                    + " the ledger saw start and never end, with what the folder of each says (RUNNING, PARKED,"
                    + " INTERRUPTED, GONE, UNREADABLE, OTHER RECORD), the runs it names whose folder is gone, the"
                    + " run folders the ledger should know and does not, and what was acknowledged. Exits 8 when"
                    + " something does not hold that nobody acknowledged.");

    @Override
    public Spec spec() {
        return SPEC;
    }

    @Override
    public int run(Context context, Parsed args) {
        String acknowledge = args.option("acknowledge");
        if (acknowledge != null) {
            if (args.positional() != null) {
                throw new UsageException("--acknowledge names its run itself; give no other run");
            }
            if (args.option("note") == null || args.option("note").isBlank()) {
                throw new UsageException("--acknowledge needs --note \"...\": why this run is no lost run");
            }
            LedgerReport after = context.services().acknowledge(acknowledge, args.option("note"));
            return args.has("json") ? json(context, after) : ledger(context, after);
        }
        if (args.option("note") != null) {
            throw new UsageException("--note goes with --acknowledge <run>");
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
        out.println("chain     " + (chain.intact() ? "ok (" + chain.events() + " events, head " + TerminalFormat.safe(chain.head()) + ")"
                : "BROKEN"));
        chain.problems().forEach(problem -> out.println("  " + TerminalFormat.safe(problem)));
        if (!chain.deletedBlobs().isEmpty()) {
            out.println("blobs     " + chain.deletedBlobs().size() + " deleted (not a failure)");
        }
        out.println("manifest  " + TerminalFormat.safe(report.manifest()));
        out.println("ledger    " + TerminalFormat.safe(report.ledger())
                + (report.ledgerReason() == null ? "" : " (" + TerminalFormat.safe(report.ledgerReason()) + ")"));
        out.println("start     " + TerminalFormat.safe(report.start()));
        out.println("seal      " + TerminalFormat.safe(report.seal()));
        if (!VerifyReport.NOT_CHECKED.equals(report.register())) {
            // Only the run of an audit workflow has a register folder
            out.println("register  " + TerminalFormat.safe(report.register()));
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
            out.println("  " + TerminalFormat.safe(run.runId()) + "  "
                    + (run.startedAt() == null ? "-" : TerminalFormat.safe(run.startedAt())) + "  "
                    + (run.workflowId() == null ? "-" : TerminalFormat.safe(run.workflowId())) + "  folder "
                    + TerminalFormat.safe(run.folder()));
        }
        out.println("ended, folder gone (" + report.endedGone().size() + ")");
        report.endedGone().forEach(run -> out.println("  " + TerminalFormat.safe(run.runId()) + "  "
                + TerminalFormat.safe(run.detail())));
        if (!report.earlierOpen().isEmpty()) {
            out.println("from before the ledger, not ended (" + report.earlierOpen().size() + ")");
            report.earlierOpen().forEach(run -> out.println("  " + TerminalFormat.safe(run.runId()) + "  folder "
                    + TerminalFormat.safe(run.detail())));
        }
        if (!report.inFlight().isEmpty()) {
            out.println("in flight (" + report.inFlight().size() + ")");
            report.inFlight().forEach(run -> out.println("  " + TerminalFormat.safe(run.runId()) + "  "
                    + TerminalFormat.safe(run.detail()) + "; its process is alive"));
        }
        out.println("acknowledged (" + report.acknowledged().size() + ")");
        for (LedgerReport.Acknowledged run : report.acknowledged()) {
            out.println("  " + TerminalFormat.safe(run.runId()) + "  " + TerminalFormat.safe(String.valueOf(run.said()))
                    + (run.covers() ? "" : " [no longer what the check finds]"));
            out.println("    by " + TerminalFormat.safe(String.valueOf(run.actor())) + " at "
                    + TerminalFormat.safe(String.valueOf(run.at())) + ": "
                    + TerminalFormat.oneLine(String.valueOf(run.note())));
        }
        out.println("problems (" + report.problems().size() + ")");
        report.problems().forEach(problem -> out.println("  " + TerminalFormat.safe(problem.runId()) + "  "
                + TerminalFormat.safe(problem.text())));
        if (!report.records()) {
            out.println("records   not verified (--quick): only traceworks verify <run> checks the record of a run");
        }
        if (report.ok()) {
            out.println("verified");
            return ExitCodes.OK;
        }
        out.println("verification FAILED: " + count(report.problems().size(), "problem")
                + "; acknowledge one that has an honest cause with traceworks verify --acknowledge <run> --note"
                + " \"...\"");
        return ExitCodes.VERIFY_FAILED;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static int print(Context context, com.fasterxml.jackson.databind.node.ObjectNode root, boolean ok) {
        try {
            context.out().println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));
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
