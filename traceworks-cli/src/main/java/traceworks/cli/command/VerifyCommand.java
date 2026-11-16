package traceworks.cli.command;

import traceworks.cli.Args.Parsed;
import traceworks.cli.Args.Spec;
import traceworks.cli.Command;
import traceworks.cli.Context;
import traceworks.cli.ExitCodes;
import traceworks.cli.TerminalFormat;
import traceworks.core.app.LedgerReport;
import traceworks.core.app.VerifyReport;
import traceworks.core.record.ChainReport;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

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
            .note("Without a run: checks the ledger against the runs folder of the home. Lists the runs the ledger"
                    + " saw start and never end, with what the folder of each says (RUNNING, PARKED, INTERRUPTED,"
                    + " GONE), and the runs it names whose folder is gone. Exits 8 when a run started, never ended"
                    + " and its folder is gone, or an entry was removed from the ledger.");

    @Override
    public Spec spec() {
        return SPEC;
    }

    @Override
    public int run(Context context, Parsed args) {
        if (args.positional() == null) {
            return ledger(context);
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

    /** The ledger against the runs folder: what started and never ended, and what the ledger names without a folder. */
    private static int ledger(Context context) {
        LedgerReport report = context.services().ledgerReport();
        PrintStream out = context.out();
        out.println("ledger    " + count(report.started(), "run") + " started, " + report.ended() + " ended");
        out.println("started, not ended (" + report.open().size() + ")");
        for (LedgerReport.Open run : report.open()) {
            out.println("  " + TerminalFormat.safe(run.runId()) + "  "
                    + (run.startedAt() == null ? "-" : TerminalFormat.safe(run.startedAt())) + "  "
                    + (run.workflowId() == null ? "-" : TerminalFormat.safe(run.workflowId())) + "  folder "
                    + TerminalFormat.safe(run.folder()));
        }
        out.println("ended, folder gone (" + report.endedGone().size() + ")");
        report.endedGone().forEach(id -> out.println("  " + TerminalFormat.safe(id)));
        if (!report.removed().isEmpty()) {
            out.println("removed from the ledger by a later commit (" + report.removed().size() + ")");
            report.removed().forEach(path -> out.println("  " + TerminalFormat.safe(path)));
        }
        if (report.ok()) {
            out.println("verified");
            return ExitCodes.OK;
        }
        List<String> why = new ArrayList<>();
        if (!report.lost().isEmpty()) {
            why.add(count(report.lost().size(), "run") + " started, never ended, and the folder is gone");
        }
        if (!report.removed().isEmpty()) {
            why.add(count(report.removed().size(), "entry", "entries") + " removed from the ledger");
        }
        out.println("verification FAILED: " + String.join("; ", why));
        return ExitCodes.VERIFY_FAILED;
    }

    private static String count(int n, String one) {
        return count(n, one, one + "s");
    }

    private static String count(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }
}
