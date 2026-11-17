package traceworks.cli.command;

import traceworks.cli.Args.Parsed;
import traceworks.cli.Args.Spec;
import traceworks.cli.Command;
import traceworks.cli.Context;
import traceworks.cli.ExitCodes;
import traceworks.cli.TerminalText;
import traceworks.cli.UsageException;
import traceworks.core.app.Seals;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code traceworks seal <package>}: signs the seal digest of a package with the operator's SSH key, writes
 * {@code traceworks.seal} into the package and appends {@code SEALED} to the seal log of the home. From then on a run
 * of the package is refused once the package, or a package it calls, changed. A refusal exits 6 and writes nothing.
 */
public final class SealCommand implements Command {

    private static final Spec SPEC = Spec.of("seal", "Seals a package: signs its digest, so a run refuses the package"
                    + " once it changed.", "<package>")
            .option("key", "FILE", "the SSH key to sign with (default: signing_key in traceworks.json)")
            .note("The seal covers every file of the package and of the packages it calls. It is signed with"
                    + " 'ssh-keygen -Y sign';\nthe key must be an Ed25519 key that the allowed signers file of the"
                    + " home names (allowed_signers in the\nTraceworks home, in the format of git). More:"
                    + " docs/cli.md, \"seal, unseal and seals\".");

    @Override
    public Spec spec() {
        return SPEC;
    }

    @Override
    public int run(Context context, Parsed args) {
        Path pkg = context.resolve(args.positional());
        if (!Files.exists(pkg)) {
            throw new UsageException("no such package: " + pkg);
        }
        String key = args.option("key");
        if (key != null && key.isBlank()) {
            throw new UsageException("--key needs the path of an SSH key");
        }
        Seals.Sealed sealed = context.seals().seal(pkg, key == null ? null : context.resolve(key).toString());
        PrintStream out = context.out();
        // The names are the package's text; its folder is the one the operator named
        TerminalText.println(out, "sealed ", TerminalText.safe(sealed.workflowName()), " (",
                TerminalText.safe(sealed.seal().workflowId()), ")");
        TerminalText.println(out, "  package  ", TerminalText.whole(sealed.directory()));
        out.println("  digest   " + sealed.seal().digest());
        if (!sealed.children().isEmpty()) {
            TerminalText.println(out, "  covers   the packages it calls: ",
                    TerminalText.safe(String.join(", ", sealed.children())));
        }
        TerminalText.println(out, "  signer   ", TerminalText.safe(String.join(", ", sealed.principals())), " (key ",
                TerminalText.safe(sealed.key()), ")");
        TerminalText.println(out, "  seal     ",
                TerminalText.whole(Path.of(sealed.directory()).resolve("traceworks.seal").toString()));
        return ExitCodes.OK;
    }
}
