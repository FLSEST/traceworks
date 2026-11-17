package traceworks.cli.command;

import traceworks.cli.Args.Parsed;
import traceworks.cli.Args.Spec;
import traceworks.cli.Command;
import traceworks.cli.Context;
import traceworks.cli.UsageException;
import traceworks.core.app.AnswerRequest;
import traceworks.core.util.Message;

/** {@code traceworks approve <run> --answer OPTION}: answers the approval a run waits for. */
public final class ApproveCommand implements Command {

    private static final Spec SPEC = Answers.agentOption(
            Spec.of("approve", "Answers the approval a run waits for.", "<run>")
                    .option("answer", "OPTION", "one of the offered options (required)")
                    .option("comment", "TEXT", "a comment recorded with the answer"))
            .note(Answers.AGENT_NOTE);

    @Override
    public Spec spec() {
        return SPEC;
    }

    @Override
    public int run(Context context, Parsed args) {
        String answer = args.option("answer");
        if (answer == null) {
            throw new UsageException(Message.of("'approve' needs --answer OPTION"));
        }
        String agent = Answers.agent(context, args);
        return Answers.give(context, args.positional(),
                new AnswerRequest("approval", null, answer, null, args.option("comment"), agent));
    }
}
