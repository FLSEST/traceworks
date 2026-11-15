package traceworks.cli.command;

import traceworks.cli.Args.Parsed;
import traceworks.cli.Args.Spec;
import traceworks.cli.Command;
import traceworks.cli.Context;
import traceworks.cli.ExitCodes;
import traceworks.cli.TerminalFormat;
import traceworks.core.app.ResumeRequest;
import traceworks.core.app.RunResult;
import traceworks.core.app.RunServices;

/** {@code traceworks resume <run>}: goes on with a parked or interrupted run. */
public final class ResumeCommand implements Command {

    private static final Spec SPEC = Spec.of("resume", "Resumes a parked or interrupted run from its last checkpoint.",
                    "<run>")
            .flag("allow-host-execution", "the consent a host run needs to resume; a container run stays one")
            .flag("allow-changed-package", "resume although the package changed since the run started")
            .flag("allow-unsealed", "resume a run of an unsealed package without a terminal although require_seal is"
                    + " \"unattended\"")
            .flag("no-input", "never ask on the terminal; a question parks the run again")
            .flag("quiet", "print the summary only, not each event");

    @Override
    public Spec spec() {
        return SPEC;
    }

    @Override
    public int run(Context context, Parsed args) {
        boolean interactive = context.interactive(args.has("no-input"));
        RunServices services = context.services();
        RunResult result = Following.run(context, services, args.has("quiet"), interactive,
                context.runFolder(args.positional()),
                (listener, asker) -> services.resume(new ResumeRequest(args.positional(),
                        args.has("allow-host-execution"), args.has("allow-changed-package"),
                        context.attended(args.has("allow-unsealed"))), listener, asker,
                        warning -> context.err().println("traceworks: warning: " + TerminalFormat.printable(warning))));
        return ExitCodes.of(result);
    }
}
