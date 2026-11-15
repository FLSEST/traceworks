package traceworks.adapters.host;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * How a shell command reaches bash: on stdin, never as an argument.
 *
 * <p><b>Why stdin</b>: the command line of a process is visible to every process on the machine
 * ({@code ps}, {@code /proc/<pid>/cmdline}), and a step's resolved command can carry a secret value that an earlier
 * step printed; stdin is not. Linux also caps every single argument at 128 KiB ({@code MAX_ARG_STRLEN}), so a
 * {@code bash -c <script>} whose placeholders had inserted big block outputs would fail before bash even started.
 *
 * <p>The argument of {@code bash -c} is the fixed {@link #RUNNER}, which reads the whole script up to its end and runs
 * it with {@code eval}, so the script's commands find stdin already at its end. {@code bash -s} would not do: it reads
 * the script line by line from that same stdin, and a command reading stdin would swallow the rest of it.
 *
 * <p><b>Whole or nothing.</b> The script goes in followed by {@link #END}. The runner checks that marker and takes it
 * off again, so the script's own trailing newlines survive the command substitution, which would drop them (a last
 * line ending in {@code \} and a newline, an unterminated here-document); a stdin without it (the client lost the
 * script, or cut it) and an empty script run nothing and fail with {@link #UNREADABLE_STDIN_EXIT}, as a stdin bash
 * cannot read does.
 *
 * <p><b>SIGTERM.</b> Bash no longer replaces itself with the script's last command, so that command
 * is a child of the runner. The runner traps SIGTERM and, once its running command has ended, exits with 143, the
 * status of a shell that SIGTERM ended (never the status of the last command, which for a script that
 * sends itself the signal would be that of {@code kill}, 0); the signal itself reaches the command too, from the init
 * of a container ({@code TINI_KILL_PROCESS_GROUP}, ContainerArguments) and from {@code ProcessTree} on the host. So the
 * command's own TERM trap runs to its end, and nothing after it runs, unless the script sets its own TERM trap, which
 * then decides, as under {@code bash -c}. A script's EXIT trap still runs.
 *
 * <p>{@code eval} runs the text the way {@code bash -c} does: {@code $0} stays {@code /bin/bash}, there are no
 * positional parameters, line numbers and most messages are the same ({@code /bin/bash: line 2: ...}). What differs:
 * <ul>
 *   <li>a syntax error names {@code eval} instead of {@code -c};</li>
 *   <li>{@code set -x} traces with {@code ++}, one level deeper;</li>
 *   <li>{@code $BASH_COMMAND} in an EXIT trap set at the top of the script shows the runner's {@code eval} line, and
 *       {@code BASH_EXECUTION_STRING} holds the runner;</li>
 *   <li>{@code $_} starts as {@code TRACEWORKS_SCRIPT}, the runner's variable, not the path of bash;</li>
 *   <li>{@code trap -p} lists the runner's TERM trap until the script sets its own;</li>
 *   <li>a command killed on timeout or cancel may leave bash's "Terminated" in the output.</li>
 * </ul>
 * The helper variable is unset before the script's first command.
 *
 * <p>The container runtime adapter hands a script to the container's bash the same way. A container request that
 * brings its own stdin keeps the script as the argument, which must then fit ({@link #viaStdin}).
 */
public final class BashScript {

    /** One argument may hold this many bytes minus its terminating NUL (Linux, 4 KiB pages; more with larger ones). */
    public static final int ARGUMENT_LIMIT_BYTES = 128 * 1024;

    static final String VARIABLE = "TRACEWORKS_SCRIPT";

    /** The marker after the script on stdin: the runner runs a script only when it arrived up to this marker. */
    public static final String END = "#TRACEWORKS_SCRIPT_END";

    /**
     * Exit code of the runner when bash cannot read its stdin, or the script did not arrive whole: {@code EX_IOERR}
     * from sysexits.h. Not 126, which the shell block's recovery hint reads as a permission problem
     * ({@code chmod +x}).
     */
    static final int UNREADABLE_STDIN_EXIT = 74;

    /** What the runner prints on stderr when the script did not arrive whole. */
    static final String NOT_WHOLE = "traceworks: the command did not arrive whole on stdin";

    /** Why a script that did not arrive whole did not run, as {@code CommandResult#notRunReason} says it. */
    public static final String NOT_WHOLE_REASON = "the command did not arrive whole on stdin";

    /**
     * The {@code bash -c} argument for a script on stdin: traps SIGTERM so that it waits for its running command and
     * then ends with 143, reads stdin to its end, checks and takes off {@link #END}, then runs the script in this
     * shell.
     */
    public static final String RUNNER = "trap 'exit 143' TERM; "
            + VARIABLE + "=$(</dev/stdin) || exit " + UNREADABLE_STDIN_EXIT + "; "
            + "[[ $" + VARIABLE + " == *'" + END + "' && ${#" + VARIABLE + "} -gt " + END.length() + " ]]"
            + " || { echo '" + NOT_WHOLE + "' >&2; exit " + UNREADABLE_STDIN_EXIT + "; }; "
            + VARIABLE + "=${" + VARIABLE + "%'" + END + "'}; "
            + "eval \"unset -v " + VARIABLE + "; $" + VARIABLE + "\"";

    private BashScript() {
    }

    /**
     * Whether the runner reports that the script did not arrive whole, so none of it ran: exit code
     * {@value #UNREADABLE_STDIN_EXIT} with {@link #NOT_WHOLE} as the last line of {@code output}, the stream the
     * runner's stderr went to. The runner prints that line before the script's first command, so only a script that
     * prints that very line itself and exits {@value #UNREADABLE_STDIN_EXIT} looks the same.
     */
    public static boolean notWhole(int exitCode, String output) {
        if (exitCode != UNREADABLE_STDIN_EXIT || output == null) {
            return false;
        }
        String text = output.stripTrailing();
        return text.endsWith(NOT_WHOLE) && (text.length() == NOT_WHOLE.length()
                || text.charAt(text.length() - NOT_WHOLE.length() - 1) == '\n');
    }

    /** Whether {@code script} is too large for one argument, so it can go in on stdin only. */
    public static boolean viaStdin(String script) {
        // UTF-8 needs at most three bytes per char; the platform encoding of arguments never needs more
        return script.length() >= ARGUMENT_LIMIT_BYTES / 3
                && script.getBytes(StandardCharsets.UTF_8).length >= ARGUMENT_LIMIT_BYTES;
    }

    /** The argument of {@code bash -c}: always {@link #RUNNER}, whatever the script. */
    static String argument(String script) {
        return RUNNER;
    }

    /** What the process gets on stdin: the script, followed by {@link #END}. */
    public static String stdin(String script) {
        return script + END;
    }

    /**
     * Gives a just started process its stdin and closes it: {@code stdin} is written on a thread of its own (a script
     * beyond the pipe buffer blocks until bash reads it, and a process that ends without reading it must not hold up
     * the caller's timeout); {@code null} closes stdin at once. Either way a command that reads stdin ({@code cat},
     * {@code read}) gets its end at once instead of waiting for the timeout.
     */
    public static void supply(Process process, String stdin) {
        if (stdin == null) {
            closeQuietly(process.getOutputStream());
            return;
        }
        Thread.ofVirtual().name("shell-script-stdin").start(() -> {
            try (OutputStream in = process.getOutputStream()) {
                in.write(stdin.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // The process ended before it read its script; its own output and exit code say why.
            }
        });
    }

    private static void closeQuietly(OutputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // Only a process that is gone already has no open stdin left to close.
        }
    }
}
