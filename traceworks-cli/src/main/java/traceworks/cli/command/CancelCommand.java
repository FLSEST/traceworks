package traceworks.cli.command;

import traceworks.cli.Args.Parsed;
import traceworks.cli.Args.Spec;
import traceworks.cli.Command;
import traceworks.cli.Context;
import traceworks.cli.ExitCodes;
import traceworks.cli.RunReport;
import traceworks.core.app.RunResult;
import traceworks.core.model.RunStatus;
import traceworks.core.app.RunServices;

/** {@code traceworks cancel <run>}: stops a run for good. */
public final class CancelCommand implements Command {

    private static final Spec SPEC = Spec.of("cancel", "Cancels a run. A cancelled run cannot be resumed. Exits 0 when the run ends cancelled.", "<run>");

    @Override
    public Spec spec() {
        return SPEC;
    }

    @Override
    public int run(Context context, Parsed args) {
        RunServices services = context.services();
        RunResult result = services.cancel(args.positional());
        RunReport.summary(context.out(), services, result, context.runFolder(args.positional()));
        // A cancel that took effect succeeded; anything else keeps the status mapping
        return result.status() == RunStatus.CANCELLED ? ExitCodes.OK : ExitCodes.of(result);
    }
}
