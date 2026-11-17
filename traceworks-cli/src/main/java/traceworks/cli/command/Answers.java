package traceworks.cli.command;

import traceworks.cli.AnswerCommands;
import traceworks.cli.Args.Parsed;
import traceworks.cli.Args.Spec;
import traceworks.cli.Context;
import traceworks.cli.ExitCodes;
import traceworks.cli.TerminalText;
import traceworks.cli.UsageException;
import traceworks.core.app.AnswerRequest;
import traceworks.core.app.AnswerResult;
import traceworks.core.app.RunServices;
import traceworks.core.record.RunView;
import traceworks.core.register.DecisionAgent;

import java.util.List;

/** Gives an answer and says what became of it. */
final class Answers {

    private Answers() {
    }

    /** The option of {@code approve} and {@code answer} that names the agent, as {@code findings} words it. */
    static Spec agentOption(Spec spec) {
        return spec.option("agent", "NAME", "the agent that answers under your user, recorded beside the actor;"
                + " without it, the environment variable " + DecisionAgent.ENV);
    }

    /** What the usage of {@code approve} and {@code answer} says about who answered. */
    static final String AGENT_NOTE = """
            Who answered is the actor who ran the command, and an agent that answers under that user names
            itself: --agent NAME, or TRACEWORKS_AGENT in the environment of a harness (the option wins). The
            name is at most 64 characters: ASCII letters and digits, . _ - : / @ + and single spaces; a blank
            one is refused (exit 2) and nothing is recorded. It is what the caller says, unsigned: an answer
            without it reads as a person's.""";

    /**
     * The agent that gives the answer under the actor: the option, else the variable of a harness.
     *
     * @return the name; {@code null} for an answer no agent claims
     * @throws UsageException if the one that counts is blank or no name: the answer must not be recorded as a
     *                        person's, so nothing is asked of the run
     */
    static String agent(Context context, Parsed args) {
        try {
            return DecisionAgent.resolve(args.option("agent"), context.env());
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage());
        }
    }

    static int give(Context context, String runRef, AnswerRequest request) {
        AnswerResult result = context.services().answer(runRef, request);
        if (!result.accepted()) {
            // A refusal names the options, a field's name or its pattern: text of the workflow. On one line only
            // where it stays there; else on marked lines, as wherever options are shown
            TerminalText.shown("  ", "traceworks: ", "", "", "the answer is refused:", List.of(result.message()))
                    .forEach(line -> context.err().println(line.startsWith("|") ? "  " + line : line));
            return ExitCodes.REFUSED;
        }
        TerminalText.println(context.out(), TerminalText.safe(result.message()));
        if (result.runParked()) {
            RunServices services = context.services();
            RunView view = services.view(runRef);
            String folder = context.runFolder(runRef);
            context.out().println("go on with: " + AnswerCommands.resume(folder != null ? folder : view.runId(),
                    services.resumeNeedsHostFlag(view)));
        }
        return ExitCodes.OK;
    }
}
