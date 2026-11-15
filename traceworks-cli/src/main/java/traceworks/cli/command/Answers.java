package traceworks.cli.command;

import traceworks.cli.AnswerCommands;
import traceworks.cli.Context;
import traceworks.cli.ExitCodes;
import traceworks.cli.TerminalFormat;
import traceworks.core.app.AnswerRequest;
import traceworks.core.app.AnswerResult;
import traceworks.core.app.RunServices;
import traceworks.core.record.RunView;

/** Gives an answer and says what became of it. */
final class Answers {

    private Answers() {
    }

    static int give(Context context, String runRef, AnswerRequest request) {
        AnswerResult result = context.services().answer(runRef, request);
        if (!result.accepted()) {
            context.err().println("traceworks: " + TerminalFormat.safe(result.message()));
            return ExitCodes.REFUSED;
        }
        context.out().println(TerminalFormat.safe(result.message()));
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
