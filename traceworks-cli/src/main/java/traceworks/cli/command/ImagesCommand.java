package traceworks.cli.command;

import traceworks.cli.Args.Parsed;
import traceworks.cli.Args.Spec;
import traceworks.cli.Command;
import traceworks.cli.Context;
import traceworks.cli.ExitCodes;
import traceworks.cli.TerminalFormat;
import traceworks.cli.UsageException;
import traceworks.core.app.Images;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code traceworks images build [<package>]}: builds the built-in images, or those a package uses and its own. One
 * line per image built or pulled on standard output, one per failure on standard error; a failure does not stop the
 * other images, and exits 6.
 */
public final class ImagesCommand implements Command {

    private static final Spec SPEC = Spec.of("images", "Builds the images steps run in: 'images build [<package>]'.",
            "build [<package>]").positionals(1, 2);

    @Override
    public Spec spec() {
        return SPEC;
    }

    @Override
    public int run(Context context, Parsed args) {
        List<String> words = args.positionals();
        if (!words.getFirst().equals("build")) {
            throw new UsageException("unknown images command '" + words.getFirst() + "'; the command is 'build'");
        }
        Path pkg = null;
        if (words.size() > 1) {
            pkg = context.resolve(words.get(1));
            if (!Files.exists(pkg)) {
                throw new UsageException("no such package: " + pkg);
            }
        }
        if (pkg != null) {
            // Before a container runtime is looked for: a sealed package that changed is refused wherever it is asked
            context.seals().admitBuild(pkg);
        }
        Images.Built built = context.images().build(pkg);
        if (built.lines().isEmpty() && built.failures().isEmpty()) {
            context.out().println("nothing to build");
        }
        built.lines().forEach(line -> context.out().println(TerminalFormat.oneLine(line)));
        built.failures().forEach(failure -> failure(context.err(), failure));
        return built.failures().isEmpty() ? ExitCodes.OK : ExitCodes.REFUSED;
    }

    /**
     * Prints one failure. It names the image as the package wrote it and carries the runtime's output, so it may
     * have several lines: each after the first is marked, and none of them can pass for a line of Traceworks.
     */
    private static void failure(PrintStream err, String failure) {
        List<String> lines = TerminalFormat.printable(failure).strip().lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            err.println((i == 0 ? "traceworks: " : "traceworks:   | ") + lines.get(i));
        }
    }
}
