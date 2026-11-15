package traceworks.cli.command;

import traceworks.cli.ConsoleAsker;
import traceworks.cli.Context;
import traceworks.cli.Interrupts;
import traceworks.cli.RunFollower;
import traceworks.cli.RunReport;
import traceworks.cli.TerminalFormat;
import traceworks.core.app.QuestionAsker;
import traceworks.core.app.RunResult;
import traceworks.core.app.RunServices;
import traceworks.core.record.RecordedEvent;

import java.util.function.Consumer;

/**
 * What {@code run} and {@code resume} share: note the AI images the operator replaced, print the events while the run
 * executes, then its summary.
 */
final class Following {

    /** The use case to execute, with the listener and the asker this command provides. */
    @FunctionalInterface
    interface Execution {
        RunResult execute(Consumer<RecordedEvent> listener, QuestionAsker asker);
    }

    private Following() {
    }

    /**
     * @param folder the run's folder when the command was given one as a path ({@link Context#runFolder}); {@code
     *               null} for a new run and for a run of the home
     */
    static RunResult run(Context context, RunServices services, boolean quiet, boolean interactive, String folder,
                         Execution execution) {
        services.aiImageNotes().forEach(note ->
                context.err().println("traceworks: note: " + TerminalFormat.printable(note)));
        try (RunFollower follower = new RunFollower(services, context.out(), quiet, interactive, folder)) {
            QuestionAsker asker = interactive
                    ? new ConsoleAsker(context.console(), context.out(),
                    follower::blobText) : null;
            RunResult result = Interrupts.guard(services, context.err(),
                    () -> execution.execute(follower, asker));
            RunReport.summary(context.out(), services, result, folder);
            return result;
        }
    }
}
