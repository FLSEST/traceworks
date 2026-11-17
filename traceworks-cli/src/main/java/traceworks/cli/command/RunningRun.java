package traceworks.cli.command;

import traceworks.cli.TerminalFormat;
import traceworks.cli.Context;
import traceworks.cli.UsageException;
import traceworks.core.model.RunStatus;
import traceworks.core.record.RunView;
import traceworks.core.util.Message;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The run a command means when it is given none: the one run that is running now, whose process is alive. With none,
 * or with several, nothing is guessed: the usage error names the running runs, or says there is none.
 */
final class RunningRun {

    /** Shown when no run is named: the command takes the one that runs. */
    static final String NOTE = "Without <run> it takes the one run that is running now. When none or several are"
            + " running,\nit names them and exits 2.";

    private RunningRun() {
    }

    /**
     * The run that {@code given} names, or the one that is running now.
     *
     * @throws UsageException when no run is named and not exactly one is running
     */
    static String ref(Context context, String given, String command) {
        if (given != null) {
            return given;
        }
        List<RunView> running = context.services().runs(Integer.MAX_VALUE).stream()
                .filter(run -> run.status() == RunStatus.RUNNING)
                .toList();
        if (running.size() == 1) {
            return running.getFirst().runId();
        }
        if (running.isEmpty()) {
            throw new UsageException(Message.of("no run is running now, so '" + command + "' needs <run>;"
                    + " 'traceworks runs' lists the runs"));
        }
        throw new UsageException(Message.of(running.size() + " runs are running now, so '" + command
                + "' needs one of them: ", Message.join(", ", running.stream().map(RunningRun::named).toList())));
    }

    private static Message named(RunView run) {
        // The id is Traceworks' own; the name is the workflow's, and quoted
        return run.workflowName() == null ? Message.of(TerminalFormat.shortId(run.runId()))
                : Message.of(TerminalFormat.shortId(run.runId()) + " (", Message.quoted(run.workflowName().strip()),
                        ")");
    }
}
