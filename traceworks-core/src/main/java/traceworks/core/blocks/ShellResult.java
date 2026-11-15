package traceworks.core.blocks;

import traceworks.core.port.CommandRunner;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.util.TruncationUtil;

import java.util.Collection;

/**
 * How a command ended, as the shell blocks read it: one output text, the exit code and the duration. The port reports
 * stdout and stderr apart; the shell blocks show them as one text, stderr after stdout. A runner that merges the
 * streams into stdout keeps the lines of the two in the order they were written.
 *
 * @param truncationNote {@link CommandResult#truncationNote()}: which stream of the command went beyond the output
 *                       limit, which it names, so the output holds only its first part and what reads it must not
 *                       take it for the whole one; empty when the output is whole
 * @param timedOut       {@link CommandResult#timedOut()}: the runner stopped the command for running out of time. Its
 *                       exit code is {@value #TIMEOUT_EXIT_CODE} and its output ends with a line that says so, but a
 *                       command may exit {@value #TIMEOUT_EXIT_CODE} by itself, so what reads the result asks this
 * @param notRunReason   {@link CommandResult#notRunReason()}: why the command did not run, as the runner knows it;
 *                       empty when it ran. A command may end with the exit code of a refusal by itself, so what reads
 *                       the result as an answer asks this
 */
record ShellResult(String output, int exitCode, long durationMs, String truncationNote, boolean timedOut,
                   String notRunReason) {

    /** The exit code of a command that ran out of time, whatever the runner reports. */
    static final int TIMEOUT_EXIT_CODE = 124;

    ShellResult {
        truncationNote = truncationNote == null ? "" : truncationNote;
        notRunReason = notRunReason == null ? "" : notRunReason;
    }

    /** A result of a command that ran. */
    ShellResult(String output, int exitCode, long durationMs, String truncationNote, boolean timedOut) {
        this(output, exitCode, durationMs, truncationNote, timedOut, "");
    }

    /** A result that did not time out. */
    ShellResult(String output, int exitCode, long durationMs, String truncationNote) {
        this(output, exitCode, durationMs, truncationNote, false);
    }

    /** A result whose output is whole. */
    ShellResult(String output, int exitCode, long durationMs) {
        this(output, exitCode, durationMs, "");
    }

    /** Whether a stream went beyond the limit, so the output is not whole. */
    boolean truncated() {
        return !truncationNote.isEmpty();
    }

    /** Whether the runner knows that the command did not run ({@link #notRunReason()}). */
    boolean didNotRun() {
        return !notRunReason.isEmpty();
    }

    /**
     * The result of {@code result}; a timeout exits {@value #TIMEOUT_EXIT_CODE} and says so in the output. An output
     * beyond the limit is followed by {@link CommandResult#truncationNote()}, and its exit code is never 0
     * ({@value CommandRunner#OUTPUT_LIMIT_EXIT_CODE} for a runner that reports 0). It is kept as it came, untrimmed.
     *
     * <p>A stream that did not end by itself ends before the first characters of a value of {@code keepWhole} that it
     * may have broken off in ({@link TruncationUtil#cutBeforeOpenEnd}): the record redacts a value only where it is
     * whole. That is each stream of a command that went beyond the limit, timed out, or exited with any other code
     * than 0, such as a cancel (143) or a kill (137): nothing tells where such a command stopped writing. A command
     * that exited 0 ended its streams itself and keeps them whole; so does, in effect, any stream that ends with a line
     * feed, which no value holds.
     *
     * @param keepWhole the run's secret values ({@code ExecutionContext#secretValues})
     */
    static ShellResult of(CommandResult result, int timeoutSeconds, Collection<String> keepWhole) {
        String output = joined(kept(result, CommandResult.STDOUT, result.stdout(), keepWhole),
                kept(result, CommandResult.STDERR, result.stderr(), keepWhole));
        String note = result.truncationNote();
        String noteLine = note.isEmpty() ? "" : "\n" + note;
        if (result.timedOut()) {
            return new ShellResult(output + "\nCommand timed out after " + timeoutSeconds + " seconds" + noteLine,
                    TIMEOUT_EXIT_CODE, result.durationMs(), note, true);
        }
        if (result.truncated()) {
            return new ShellResult(output + noteLine,
                    result.exitCode() == 0 ? CommandRunner.OUTPUT_LIMIT_EXIT_CODE : result.exitCode(),
                    result.durationMs(), note);
        }
        return new ShellResult(output.trim(), result.exitCode(), result.durationMs(), "", false,
                result.notRunReason());
    }

    /** {@code text} of {@code stream}, which ends before a part of a secret value when it did not end by itself. */
    private static String kept(CommandResult result, String stream, String text, Collection<String> keepWhole) {
        if (text == null || endedByItself(result) && !result.truncatedStreams().contains(stream)) {
            return text;
        }
        return text.substring(0, TruncationUtil.cutBeforeOpenEnd(text, keepWhole));
    }

    /** Whether the command ended its streams itself: it exited 0, in time and within the limit. */
    private static boolean endedByItself(CommandResult result) {
        return result.exitCode() == 0 && !result.timedOut() && !result.truncated();
    }

    private static String joined(String stdout, String stderr) {
        String out = stdout == null ? "" : stdout;
        String err = stderr == null ? "" : stderr;
        if (err.isEmpty()) return out;
        if (out.isEmpty()) return err;
        return out.endsWith("\n") ? out + err : out + "\n" + err;
    }
}
