package traceworks.cli.command;

import traceworks.cli.Args.Parsed;
import traceworks.cli.Args.Spec;
import traceworks.cli.Command;
import traceworks.cli.Context;
import traceworks.cli.ExitCodes;
import traceworks.cli.TerminalFormat;
import traceworks.core.app.VerifyReport;
import traceworks.core.record.ChainReport;

import java.io.PrintStream;

/**
 * {@code traceworks verify <run>}: checks the hash chain, the blobs, the manifest, the ledger entry, for a run of
 * a sealed package the signature of the seal the record holds, and for the run of an audit workflow the files of its
 * register folder against the digests the record holds.
 */
public final class VerifyCommand implements Command {

    private static final Spec SPEC = Spec.of("verify", "Verifies the record of a run. Exits 8 when it does not hold.",
            "<run>");

    @Override
    public Spec spec() {
        return SPEC;
    }

    @Override
    public int run(Context context, Parsed args) {
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
        out.println("seal      " + TerminalFormat.safe(report.seal()));
        if (!VerifyReport.NOT_CHECKED.equals(report.register())) {
            // Only the run of an audit workflow has a register folder
            out.println("register  " + TerminalFormat.safe(report.register()));
        }
        out.println(report.ok() ? "verified" : "verification FAILED");
        return report.ok() ? ExitCodes.OK : ExitCodes.VERIFY_FAILED;
    }
}
