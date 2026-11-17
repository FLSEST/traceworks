package traceworks.cli.command;

import traceworks.cli.ConsoleAsker;
import traceworks.cli.UsageException;
import traceworks.cli.Context;
import traceworks.cli.Interrupts;
import traceworks.cli.RunFollower;
import traceworks.cli.RunReport;
import traceworks.cli.TerminalFormat;
import traceworks.core.app.QuestionAsker;
import traceworks.core.register.DecisionAgent;
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
     * The agent that answers at the prompt of a run in the foreground: {@code TRACEWORKS_AGENT} of this process, the
     * variable {@code approve} and {@code answer} fall back to. There is no option for it: a start and a resume name
     * no agent, only the answers typed here do.
     *
     * @return the name; {@code null} when the variable is not set
     * @throws UsageException if the variable is blank or no name: an answer typed here must not be recorded as a
     *                        person's, so the run does not start
     */
    private static String agentAtThePrompt(Context context) {
        try {
            return DecisionAgent.resolve(null, context.env());
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage() + " (it names who answers at the prompt of this run; unset it,"
                    + " or run with --no-input)");
        }
    }

    /**
     * @param folder the run's folder when the command was given one as a path ({@link Context#runFolder}); {@code
     *               null} for a new run and for a run of the home
     */
    static RunResult run(Context context, RunServices services, boolean quiet, boolean interactive, String folder,
                         Execution execution) {
        // Who types at the prompt of this process: the agent a harness names in its environment, checked before the
        // run starts. A run that asks nobody (--no-input, no terminal) does not look at the variable
        String agent = interactive ? agentAtThePrompt(context) : null;
        services.aiImageNotes().forEach(note ->
                context.err().println("traceworks: note: " + TerminalFormat.printable(note)));
        try (RunFollower follower = new RunFollower(services, context.out(), quiet, interactive, folder)) {
            QuestionAsker asker = interactive
                    ? new ConsoleAsker(context.console(), context.out(),
                    follower::blobText, agent) : null;
            RunResult result = Interrupts.guard(services, context.err(),
                    () -> execution.execute(follower, asker));
            RunReport.summary(context.out(), services, result, folder);
            return result;
        }
    }
}
