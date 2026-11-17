package traceworks.cli.command;

import traceworks.cli.Args.Parsed;
import traceworks.cli.Args.Spec;
import traceworks.cli.Command;
import traceworks.cli.Context;
import traceworks.cli.ExitCodes;
import traceworks.cli.TerminalText;
import traceworks.cli.UsageException;
import traceworks.core.app.Seals;
import traceworks.core.util.Message;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code traceworks unseal <package>}: removes the seal of a package and appends {@code UNSEALED} to the seal log of
 * the home. Refused (exit 6) when the operator set {@code seal_immutable}, and for a package that is not sealed.
 */
public final class UnsealCommand implements Command {

    private static final Spec SPEC = Spec.of("unseal", "Removes the seal of a package.", "<package>")
            .option("note", "TEXT", "why, for the seal log");

    @Override
    public Spec spec() {
        return SPEC;
    }

    @Override
    public int run(Context context, Parsed args) {
        Path pkg = context.resolve(args.positional());
        if (!Files.exists(pkg)) {
            throw new UsageException(Message.of("no such package: ", Message.typed(pkg)));
        }
        Seals.Unsealed unsealed = context.seals().unseal(pkg, args.option("note"));
        TerminalText.println(context.out(), "unsealed ", TerminalText.safe(unsealed.workflowId()), " (",
                TerminalText.whole(unsealed.directory()), ")"
                + (unsealed.fileRemoved() ? "" : "; the package carried no seal file"));
        return ExitCodes.OK;
    }
}
