package traceworks.cli.command;

import traceworks.cli.Args.Parsed;
import traceworks.cli.Args.Spec;
import traceworks.cli.Command;
import traceworks.cli.Context;
import traceworks.cli.UsageException;
import traceworks.core.app.AnswerRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/** {@code traceworks answer <run>}: answers the human input a run waits for. */
public final class AnswerCommand implements Command {

    private static final Spec SPEC = Spec.of("answer", "Answers the human input a run waits for.", "<run>")
            .option("value", "TEXT", "the answer of a single input")
            .repeated("field", "NAME=VALUE", "the value of one field of a form (repeatable)");

    @Override
    public Spec spec() {
        return SPEC;
    }

    @Override
    public int run(Context context, Parsed args) {
        String value = args.option("value");
        Map<String, String> fields = new LinkedHashMap<>();
        for (String field : args.options("field")) {
            int eq = field.indexOf('=');
            if (eq <= 0) {
                throw new UsageException("--field takes NAME=VALUE, got '" + field + "'");
            }
            fields.put(field.substring(0, eq), field.substring(eq + 1));
        }
        if ((value == null) == fields.isEmpty()) {
            throw new UsageException("'answer' needs either --value TEXT or one or more --field NAME=VALUE");
        }
        return Answers.give(context, args.positional(), new AnswerRequest("human-input", null, value, fields, null));
    }
}
