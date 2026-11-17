package traceworks.cli.command;

import traceworks.cli.TerminalFormat;
import traceworks.cli.AnswerCommands;
import traceworks.cli.Args.Parsed;
import traceworks.cli.Args.Spec;
import traceworks.cli.Command;
import traceworks.cli.Context;
import traceworks.cli.ExitCodes;
import traceworks.cli.RunReport;
import traceworks.cli.TerminalText;
import traceworks.core.app.RunResult;
import traceworks.core.app.RunServices;
import traceworks.core.model.RunStatus;

/**
 * {@code traceworks pause [<run>]}: asks a running run to park after the step, or the loop item, in flight, so that
 * {@code resume} goes on later. What Ctrl-C does once in the terminal of the run, from another terminal.
 */
public final class PauseCommand implements Command {

    private static final Spec SPEC = Spec.of("pause", "Pauses a running run: it finishes the step or the loop item in"
                    + " flight and parks; resume goes on. Exits 0 when the pause is recorded.", "[<run>]")
            .positionals(0, 1).note(RunningRun.NOTE);

    @Override
    public Spec spec() {
        return SPEC;
    }

    @Override
    public int run(Context context, Parsed args) {
        RunServices services = context.services();
        String ref = RunningRun.ref(context, args.positional(), "pause");
        RunResult result = services.pause(ref);
        if (result.status() != RunStatus.RUNNING) {
            // The run parked or ended while the pause was on its way: its summary says which, and how to go on
            RunReport.summary(context.out(), services, result, context.runFolder(ref));
            return result.status() == RunStatus.PARKED ? ExitCodes.OK : ExitCodes.of(result);
        }
        String named = context.runFolder(ref) != null ? context.runFolder(ref) : result.runId();
        TerminalText.println(context.out(), "pause requested: run ",
                TerminalText.safe(TerminalFormat.shortId(result.runId())),
                " finishes the step or the loop item in flight, then parks (exit 5 in its terminal).");
        context.out().println("go on later with:");
        context.out().println("  " + AnswerCommands.resume(named,
                services.resumeNeedsHostFlag(services.view(result.location()))));
        return ExitCodes.OK;
    }
}
