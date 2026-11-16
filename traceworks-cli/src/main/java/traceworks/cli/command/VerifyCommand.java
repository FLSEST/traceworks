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
            return ledger(context, context.services().acknowledge(acknowledge, args.option("note")));
        }
        if (args.option("note") != null) {
            throw new UsageException("--note goes with --acknowledge <run>");
        }
        if (args.positional() == null) {
            return ledger(context, context.services().ledgerReport());
        }
        VerifyReport report = context.services().verify(args.positional());
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
                + count(report.earlier(), "run folder") + " of an earlier version");
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
            out.println("earlier version, not ended, not in the ledger (" + report.earlierOpen().size() + ")");
            report.earlierOpen().forEach(run -> out.println("  " + TerminalFormat.safe(run.runId()) + "  folder "
                    + TerminalFormat.safe(run.detail())));
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
        if (report.ok()) {
            out.println("verified");
            return ExitCodes.OK;
        }
        out.println("verification FAILED: " + count(report.problems().size(), "problem")
                + "; acknowledge one that has an honest cause with traceworks verify --acknowledge <run> --note"
                + " \"...\"");
        return ExitCodes.VERIFY_FAILED;
    }

    private static String count(int n, String one) {
        return n + " " + (n == 1 ? one : one + "s");
    }
}
