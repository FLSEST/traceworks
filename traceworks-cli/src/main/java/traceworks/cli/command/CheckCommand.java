package traceworks.cli.command;

import traceworks.cli.Args.Parsed;
import traceworks.cli.Args.Spec;
import traceworks.cli.Command;
import traceworks.cli.Context;
import traceworks.cli.ExitCodes;
import traceworks.cli.FindingLines;
import traceworks.cli.UsageException;
import traceworks.core.analyzer.AnalyzerFinding;
import traceworks.core.analyzer.AnalyzerFinding.Severity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** {@code traceworks check <package>}: the analyzer's findings on a package. */
public final class CheckCommand implements Command {

    private static final Spec SPEC = Spec.of("check", "Checks a package without running it. Exits 6 on any error.",
            "<package>");

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
        List<AnalyzerFinding> findings = context.services().check(pkg);
        if (findings.isEmpty()) {
            context.out().println("no findings");
        }
        findings.forEach(finding -> context.out().println(FindingLines.line(finding)));
        return findings.stream().anyMatch(f -> f.severity() == Severity.ERROR) ? ExitCodes.REFUSED : ExitCodes.OK;
    }
}
