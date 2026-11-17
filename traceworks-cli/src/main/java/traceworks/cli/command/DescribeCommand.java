package traceworks.cli.command;

import traceworks.cli.Args.Parsed;
import traceworks.cli.Args.Spec;
import traceworks.cli.Command;
import traceworks.cli.Context;
import traceworks.cli.ExitCodes;
import traceworks.cli.TerminalText;
import traceworks.cli.UsageException;
import traceworks.core.app.TraceworksVersion;
import traceworks.core.describe.DescriptionText;
import traceworks.core.describe.WorkflowDescription;
import traceworks.core.util.Message;
import traceworks.core.view.DescribeHtml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code traceworks describe <package>}: what a workflow will do, read from its package before any run, as text; with
 * {@code --html} as one self-contained page. It starts nothing, needs no container runtime and writes nothing but
 * that page. The page goes to the working directory unless a file is named, and never into the package unasked: a
 * file there changes the package's digest.
 */
public final class DescribeCommand implements Command {

    /** What the default page is called after the package's directory name. */
    static final String PAGE_SUFFIX = ".describe.html";

    private static final Spec SPEC = Spec.of("describe", "Shows what a workflow package will do, without running it.",
                    "<package> [<file>]")
            .positionals(1, 2)
            .flag("html", "write one self-contained HTML page instead: to <file>, else to <package name>"
                    + PAGE_SUFFIX + " in the working directory")
            .note("It reads the package only: nothing is started or checked ('traceworks check' does that), and the"
                    + " operator's\ntraceworks.json is read for its package_path alone, to find a sub-workflow by id.");

    @Override
    public Spec spec() {
        return SPEC;
    }

    @Override
    public int run(Context context, Parsed args) {
        List<String> positionals = args.positionals();
        boolean html = args.has("html");
        if (positionals.size() > 1 && !html) {
            throw new UsageException(Message.of("'describe' takes a file only with --html, got '",
                    Message.typed(positionals.get(1)), "'"));
        }
        Path pkg = context.resolve(positionals.getFirst());
        if (!Files.exists(pkg)) {
            throw new UsageException(Message.of("no such package: ", Message.typed(pkg)));
        }
        WorkflowDescription described = context.services().describe(pkg);
        if (!html) {
            // Each line is laid out by the core, with the package's names and texts in it
            DescriptionText.lines(described)
                    .forEach(line -> TerminalText.println(context.out(), TerminalText.composed(line)));
            return ExitCodes.OK;
        }
        Path packageDir = Path.of(described.packageDir());
        boolean named = positionals.size() > 1;
        Path page = named ? context.resolve(positionals.get(1))
                : context.resolve(packageDir.getFileName() + PAGE_SUFFIX);
        boolean inside = page.startsWith(packageDir);
        if (inside && !named) {
            throw new UsageException(Message.of("the working directory is inside the package, and a page there would change the"
                    + " package's digest: name the file to write, as in 'traceworks describe <package> --html"
                    + " <file>'"));
        }
        try {
            // The working directory may be a checkout nobody checked, with a link under the page's name: the page
            // is written beside it and moved into place, which replaces the link and never writes through it
            Path directory = page.toAbsolutePath().getParent();
            Path draft = Files.createTempFile(directory, ".describe-", ".tmp");
            try {
                Files.writeString(draft, DescribeHtml.render(described, TraceworksVersion.current()),
                        StandardCharsets.UTF_8);
                Files.move(draft, page, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(draft);
            }
        } catch (IOException e) {
            throw new UsageException(Message.of("cannot write ", Message.typed(page), ": "
                    + e.getClass().getSimpleName(), e.getMessage() == null ? ""
                    : Message.of(" (", Message.quoted(e.getMessage()), ")")));
        }
        if (inside) {
            TerminalText.println(context.err(), "traceworks: note: ", TerminalText.whole(page.toString()),
                    " is inside the package, so the package's digest is no longer the one the page shows");
        }
        // The page is where the operator asked for it
        TerminalText.println(context.out(), TerminalText.whole(page.toString()));
        return ExitCodes.OK;
    }
}
