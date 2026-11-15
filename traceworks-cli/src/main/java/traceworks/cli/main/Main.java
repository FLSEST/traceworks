package traceworks.cli.main;

import traceworks.cli.Args;
import traceworks.cli.Args.Parsed;
import traceworks.cli.Command;
import traceworks.cli.Context;
import traceworks.cli.ExitCodes;
import traceworks.cli.RuntimeSource;
import traceworks.cli.SecretReader;
import traceworks.cli.TerminalFormat;
import traceworks.cli.UsageException;
import traceworks.cli.command.AnswerCommand;
import traceworks.cli.command.ApproveCommand;
import traceworks.cli.command.CancelCommand;
import traceworks.cli.command.CheckCommand;
import traceworks.cli.command.CredentialsCommand;
import traceworks.cli.command.DescribeCommand;
import traceworks.cli.command.DiffCommand;
import traceworks.cli.command.FindingsCommand;
import traceworks.cli.command.ImagesCommand;
import traceworks.cli.command.PauseCommand;
import traceworks.cli.command.RenderCommand;
import traceworks.cli.command.ResumeCommand;
import traceworks.cli.command.RunCommand;
import traceworks.cli.command.RunsCommand;
import traceworks.cli.command.SealCommand;
import traceworks.cli.command.SealsCommand;
import traceworks.cli.command.ShowCommand;
import traceworks.cli.command.UnsealCommand;
import traceworks.cli.command.VerifyCommand;
import traceworks.cli.command.WatchCommand;
import traceworks.core.app.KeptCheckoutNotice;
import traceworks.core.app.RecordDamagedException;
import traceworks.core.app.RequestQueuedException;
import traceworks.core.app.RunRefusedException;
import traceworks.core.app.TraceworksVersion;
import traceworks.core.port.AmbiguousRunException;
import traceworks.core.port.NoSuchRunException;
import traceworks.core.port.RunReferenceTooShortException;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The command line of Traceworks. {@link #run} parses the arguments, runs the command and returns the exit code; it
 * never exits the process and reads neither the real environment nor the console, so tests call it as they like.
 */
public final class Main {

    private static final List<Command> COMMANDS = List.of(new RunCommand(), new ResumeCommand(), new ApproveCommand(),
            new AnswerCommand(), new PauseCommand(), new CancelCommand(), new RunsCommand(), new ShowCommand(), new WatchCommand(),
            new VerifyCommand(), new RenderCommand(), new CheckCommand(), new DescribeCommand(), new ImagesCommand(),
            new CredentialsCommand(), new FindingsCommand(), new DiffCommand(), new SealCommand(), new UnsealCommand(),
            new SealsCommand());

    private Main() {
    }

    /**
     * @param console the standard input of the terminal a person types on; {@code null} when there is none, so no
     *                question is asked and a question parks the run
     * @return the exit code
     */
    public static int run(String[] args, PrintStream out, PrintStream err, Map<String, String> env,
                          Path workingDirectory, InputStream console) {
        return run(args, out, err, env, workingDirectory, console, RuntimeSource.MACHINE);
    }

    /**
     * Like {@link #run(String[], PrintStream, PrintStream, Map, Path, InputStream)} with the container runtime the
     * commands use; for tests. {@code null} finds the runtime on the machine.
     */
    public static int run(String[] args, PrintStream out, PrintStream err, Map<String, String> env,
                          Path workingDirectory, InputStream console, RuntimeSource runtimes) {
        return run(args, out, err, env, workingDirectory, console, runtimes, null);
    }

    /**
     * Like {@link #run(String[], PrintStream, PrintStream, Map, Path, InputStream, RuntimeSource)} with the reader of
     * a secret value a person gives ({@code credentials set}); {@code null} when there is no input to read one from.
     */
    public static int run(String[] args, PrintStream out, PrintStream err, Map<String, String> env,
                          Path workingDirectory, InputStream console, RuntimeSource runtimes, SecretReader secrets) {
        String command = "";
        try {
            String[] argv = args;
            String leadingHome = null;
            while (argv.length > 0 && (argv[0].equals("--home") || argv[0].startsWith("--home="))) {
                if (argv[0].equals("--home")) {
                    if (argv.length < 2) {
                        throw new UsageException("--home needs a value");
                    }
                    leadingHome = argv[1];
                    argv = java.util.Arrays.copyOfRange(argv, 2, argv.length);
                } else {
                    leadingHome = argv[0].substring("--home=".length());
                    argv = java.util.Arrays.copyOfRange(argv, 1, argv.length);
                }
            }
            final String[] cmd = argv;
            if (cmd.length == 0) {
                throw new UsageException("no command given");
            }
            switch (cmd[0]) {
                case "--help", "-h", "help" -> {
                    out.print(help());
                    return ExitCodes.OK;
                }
                case "--version" -> {
                    out.println("traceworks " + TraceworksVersion.current());
                    return ExitCodes.OK;
                }
                default -> { }
            }
            Command chosen = COMMANDS.stream().filter(c -> c.spec().name().equals(cmd[0])).findFirst()
                    .orElseThrow(() -> new UsageException(cmd[0].startsWith("-")
                            ? "unknown option " + cmd[0] : "unknown command '" + cmd[0] + "'"));
            command = chosen.spec().name();
            Parsed parsed = Args.parse(chosen.spec(), List.of(cmd).subList(1, cmd.length));
            if (parsed.has("help")) {
                out.print(chosen.spec().usage());
                return ExitCodes.OK;
            }
            String home = parsed.has("home") ? parsed.option("home") : leadingHome;
            return chosen.run(new Context(out, err, env, workingDirectory, console, home, runtimes,
                    secrets), parsed);
        } catch (UsageException e) {
            err.println("traceworks: " + TerminalFormat.safe(e.getMessage()));
            err.println("see 'traceworks " + (command.isEmpty() ? "" : command + " ") + "--help'");
            return ExitCodes.USAGE;
        } catch (RequestQueuedException e) {
            err.println("traceworks: " + TerminalFormat.safe(e.getMessage()));
            return ExitCodes.QUEUED;
        } catch (RecordDamagedException e) {
            // One line, not cut: it names the blob or line and ends with the verify command
            err.println("traceworks: " + TerminalFormat.safe(e.getMessage().replaceAll("\\s+", " ")));
            keptCheckouts(err, e);
            return ExitCodes.VERIFY_FAILED;
        } catch (RunRefusedException e) {
            err.println("traceworks: " + TerminalFormat.safe(e.getMessage()));
            keptCheckouts(err, e);
            return ExitCodes.REFUSED;
        } catch (NoSuchRunException | AmbiguousRunException | RunReferenceTooShortException e) {
            err.println("traceworks: " + TerminalFormat.safe(e.getMessage()));
            return ExitCodes.USAGE;
        } catch (Exception | Error e) {
            String message = e.getMessage() == null ? "" : ": " + TerminalFormat.oneLine(e.getMessage());
            err.println("traceworks: internal error: " + e.getClass().getSimpleName() + message);
            keptCheckouts(err, e);
            return ExitCodes.INTERNAL;
        }
    }

    /** The checkouts of runs' sources that the failure {@code e} left behind, one line each. */
    private static void keptCheckouts(PrintStream err, Throwable e) {
        for (Throwable suppressed : e.getSuppressed()) {
            if (suppressed instanceof KeptCheckoutNotice kept) {
                err.println("traceworks: " + TerminalFormat.safe(kept.getMessage()));
            }
        }
    }

    /** Without a terminal. */
    public static int run(String[] args, PrintStream out, PrintStream err, Map<String, String> env,
                          Path workingDirectory) {
        return run(args, out, err, env, workingDirectory, null);
    }

    static String help() {
        StringBuilder text = new StringBuilder("usage: traceworks <command> [options]\n\ncommands:\n");
        Map<String, String> lines = new LinkedHashMap<>();
        for (Command command : COMMANDS) {
            lines.put(command.spec().name(), command.spec().summary());
        }
        lines.forEach((name, summary) -> text.append(String.format("  %-11s %s%n", name, summary)));
        text.append("\nglobal options:\n  --home DIR   the Traceworks home (default: $TRACEWORKS_HOME, else ~/.traceworks)\n")
                .append("  --help       show this text, or the usage of a command\n")
                .append("  --version    print the version\n\n")
                .append("exit codes: 0 completed, 1 completed with errors, 2 usage error or unknown run,\n")
                .append("3 failed, 4 cancelled (a run that ends so), 5 parked (a question or a pause), 6 refused, 7 gate threshold reached, 8 verification\n")
                .append("failed, 9 interrupted, 10 request queued, not yet recorded, 11 the two runs differ (diff), 70 internal error\n");
        return text.toString();
    }

    /**
     * Whether a person types on a terminal: there is a console and, where the JDK can tell (22 and later), it is a
     * terminal, so a redirected standard input in CI does not wait for a prompt. Asked by reflection because the build
     * targets 21.
     */
    static boolean isTerminal(java.io.Console console) {
        if (console == null) {
            return false;
        }
        try {
            return (boolean) java.io.Console.class.getMethod("isTerminal").invoke(console);
        } catch (NoSuchMethodException beforeJdk22) {
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** A secret value typed on the terminal, which does not show it; {@code null} when the input ended. */
    private static String typed(String prompt) {
        char[] typed = System.console().readPassword("%s", prompt);
        return typed == null || typed.length == 0 ? null : new String(typed);
    }

    public static void main(String[] args) {
        boolean terminal = isTerminal(System.console());
        int code = run(args, System.out, System.err, System.getenv(), Path.of("").toAbsolutePath(),
                terminal ? System.in : null, RuntimeSource.MACHINE,
                terminal ? Main::typed : SecretReader.piped(System.in));
        System.out.flush();
        System.exit(code);
    }
}
