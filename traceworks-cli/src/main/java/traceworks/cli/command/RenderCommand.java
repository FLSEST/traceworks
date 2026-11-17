package traceworks.cli.command;

import traceworks.cli.Args.Parsed;
import traceworks.cli.Args.Spec;
import traceworks.cli.Command;
import traceworks.cli.Context;
import traceworks.cli.TerminalText;

/** {@code traceworks render <run>}: writes {@code run.html} again from the record. */
public final class RenderCommand implements Command {

    private static final Spec SPEC = Spec.of("render", "Writes run.html of a run again and prints its path.", "<run>");

    @Override
    public Spec spec() {
        return SPEC;
    }

    @Override
    public int run(Context context, Parsed args) {
        String folder = context.services().render(args.positional());
        // The folder of a run the operator named, to be copied whole
        TerminalText.println(context.out(), TerminalText.whole(folder), "/run.html");
        return 0;
    }
}
