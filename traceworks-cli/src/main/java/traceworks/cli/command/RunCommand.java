package traceworks.cli.command;

import traceworks.cli.Args.Parsed;
import traceworks.cli.Args.Spec;
import traceworks.cli.Command;
import traceworks.cli.Context;
import traceworks.cli.ExitCodes;
import traceworks.cli.FindingLines;
import traceworks.cli.TerminalFormat;
import traceworks.cli.UsageException;
import traceworks.core.app.RunCheck;
import traceworks.core.app.RunResult;
import traceworks.core.app.RunServices;
import traceworks.core.app.StartRequest;
import traceworks.core.port.AiCli;
import traceworks.core.port.SourceCheckout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * {@code traceworks run <package>}: starts a run and follows it. A run is a container run wherever a container runtime
 * works; where none works, {@code --allow-host-execution} or the operator config allows a host run. With
 * {@code --source} it works in a new checkout of a git repository below the home instead of a workspace; a source
 * path counts from the working directory.
 *
 * <p>Before anything starts, the package is checked as {@code traceworks check} checks it, for the inputs of this run:
 * an {@code ERROR} refuses the run (exit 6, no run exists), a {@code WARNING} is printed and the run starts. Both go
 * to standard error in the lines of {@code check}. {@code --no-check} skips the analyzer. An input that no block
 * reads is named in a warning either way.
 *
 * <p>The seal of the package is checked when the run starts, whatever {@code --no-check} says: a sealed package
 * whose seal does not hold starts no run (exit 6). {@code --allow-unsealed} is the operator's flag for an unsealed
 * package under {@code require_seal: unattended}.
 */
public final class RunCommand implements Command {

    private static final Spec SPEC = Spec.of("run", "Runs a workflow package and prints what happens.", "<package>")
            .repeated("input", "K=V", "an input of the run (repeatable; split at the first '=')")
            .option("inputs-from", "RUN", "take the inputs an earlier run was started with; an --input overrides")
            .option("workspace", "DIR", "the directory the steps work in (default: the working directory)")
            .option("source", "PATH|URL", "work in a new checkout of this git repository, not in a workspace")
            .option("ref", "REF", "the branch, tag or commit of --source to check out (default: its HEAD)")
            .flag("allow-host-execution", "allow a host run where no container runtime works: shell steps without"
                    + " an image then run on the host")
            .flag("no-input", "never ask on the terminal; a question parks the run")
            .flag("no-check", "start the run although 'traceworks check' reports an error for the package")
            .flag("allow-unsealed", "run an unsealed package without a terminal although require_seal is"
                    + " \"unattended\"")
            .option("fail-on-gate", "red|yellow", "exit 7 when the run completes with a gate at or above this")
            .flag("quiet", "print the summary only, not each event")
            .note(aiCredentials());

    /**
     * What a run with an AI step needs, for the usage text: the credential names come from the one table of them
     * ({@link AiCli#CREDENTIALS}), so the help and the start check cannot disagree.
     */
    private static String aiCredentials() {
        StringBuilder text = new StringBuilder("AI steps: a run that uses an AI provider needs one of its credentials,"
                + " stored with 'traceworks credentials\nset <NAME>' (in credentials.json in the Traceworks home) or"
                + " set as an environment variable of Traceworks:\n");
        new TreeMap<>(AiCli.CREDENTIALS).forEach((provider, names) -> text.append("  ").append(provider).append(": ")
                .append(String.join(", ", names)).append('\n'));
        text.append("A login of a CLI on the host never reaches a container. For a Claude subscription,"
                + " 'claude setup-token' on the\nhost prints a token for ");
        text.append(new TreeMap<>(AiCli.SUBSCRIPTION_TOKENS).entrySet().stream()
                .map(token -> token.getValue() + " (" + token.getKey() + ")").collect(Collectors.joining(" and ")));
        return text.append(".\nMore: docs/cli.md, \"AI steps\".").toString();
    }

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
        Map<String, String> inputs = inputs(args);
        String threshold = threshold(args.option("fail-on-gate"));
        String source = source(context, args);
        String ref = args.option("ref");
        if (ref != null && source == null) {
            throw new UsageException("--ref needs --source: it names what to check out of the source");
        }
        Path workspace = source != null ? null : args.option("workspace") == null ? context.workingDirectory()
                : context.resolve(args.option("workspace"));
        boolean interactive = context.interactive(args.has("no-input"));
        StartRequest request = new StartRequest(pkg, inputs, workspace, args.has("allow-host-execution"), interactive,
                source, ref, args.option("inputs-from"), context.attended(args.has("allow-unsealed")));

        RunServices services = context.services();
        if (!checked(context, services.checkBeforeRun(request, !args.has("no-check")))) {
            return ExitCodes.REFUSED;
        }
        RunResult result = Following.run(context, services, args.has("quiet"), interactive, null,
                (listener, asker) -> services.start(request, listener, asker));
        return ExitCodes.of(result, threshold);
    }

    /**
     * Prints what the check before the run found, on standard error: the errors and warnings in the lines of
     * {@code check}, each input no block reads, and the refusal when there is an error.
     *
     * @return whether the run may start: the check found no error
     */
    private static boolean checked(Context context, RunCheck check) {
        check.errorsAndWarnings().forEach(finding -> context.err().println(FindingLines.line(finding)));
        check.inputWarnings().forEach(warning ->
                context.err().println("traceworks: warning: " + TerminalFormat.safe(warning)));
        int errors = check.errors().size();
        if (errors > 0) {
            context.err().println("traceworks: the run is refused: check reports " + errors
                    + (errors == 1 ? " error; fix it" : " errors; fix them") + ", or start the run with --no-check");
        }
        return errors == 0;
    }

    /**
     * The {@code --source} as the run gets it: a path relative to the working directory made absolute, a URL as
     * given. One that starts with '-' stays as it is, so the start refuses it.
     */
    private static String source(Context context, Parsed args) {
        String source = args.option("source");
        if (source == null) {
            return null;
        }
        if (args.option("workspace") != null) {
            throw new UsageException("--source and --workspace exclude each other: a run with --source works in its"
                    + " own checkout of the source");
        }
        if (source.isBlank()) {
            throw new UsageException("--source needs a path or a URL");
        }
        return !source.startsWith("-") && SourceCheckout.isLocalPath(source) ? context.resolve(source).toString()
                : source;
    }

    private static Map<String, String> inputs(Parsed args) {
        Map<String, String> inputs = new LinkedHashMap<>();
        for (String input : args.options("input")) {
            int eq = input.indexOf('=');
            if (eq <= 0) {
                throw new UsageException("--input takes NAME=VALUE, got '" + input + "'");
            }
            inputs.put(input.substring(0, eq), input.substring(eq + 1));
        }
        return inputs;
    }

    private static String threshold(String value) {
        if (value == null) {
            return null;
        }
        String threshold = value.toLowerCase(Locale.ROOT);
        if (!threshold.equals("red") && !threshold.equals("yellow")) {
            throw new UsageException("--fail-on-gate takes red or yellow, got '" + value + "'");
        }
        return threshold;
    }
}
