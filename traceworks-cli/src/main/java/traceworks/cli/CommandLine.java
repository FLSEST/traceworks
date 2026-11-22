package traceworks.cli;

import traceworks.core.app.KeptCheckoutNotice;
import traceworks.core.app.RecordDamagedException;
import traceworks.core.app.RequestQueuedException;
import traceworks.core.app.RunRefusedException;
import traceworks.core.port.AmbiguousRunException;
import traceworks.core.port.BlobStore;
import traceworks.core.port.NoSuchRunException;
import traceworks.core.port.RunReferenceTooShortException;

import java.io.PrintStream;
import java.util.function.Supplier;

/**
 * What a command that fails comes to: one message on standard error and an exit code. The one place that says which
 * failure is which code, for the command line ({@code Main}) and for every other front end that runs a command (the
 * MCP server), so both refuse in the same words.
 */
public final class CommandLine {

    /** What runs under the guard: the parsing of a command line and the command. */
    @FunctionalInterface
    public interface Body {
        int run() throws Exception;
    }

    private CommandLine() {
    }

    /**
     * Runs {@code body} and returns its exit code; a failure becomes its message on {@code err} and its code.
     *
     * @param command the name of the command for the hint of a usage error, asked when one happens; empty before
     *                a command is known
     */
    public static int guarded(PrintStream err, Supplier<String> command, Body body) {
        try {
            return body.run();
        } catch (UsageException e) {
            // A message quotes what it refuses: an argument, a name of a workflow, a text of a record. It is a
            // text from outside as a whole, so a long one goes on on marked lines and none starts a line unmarked
            TerminalText.println(err, "traceworks: ", TerminalText.of(e));
            String name = command.get();
            err.println("see 'traceworks " + (name.isEmpty() ? "" : name + " ") + "--help'");
            return ExitCodes.USAGE;
        } catch (RequestQueuedException e) {
            TerminalText.println(err, "traceworks: ", TerminalText.of(e));
            return ExitCodes.QUEUED;
        } catch (RecordDamagedException | BlobStore.ChangedBlobException e) {
            // One line, not cut: it names the blob or line and ends with the verify command
            TerminalText.println(err, "traceworks: ", TerminalText.safe(e.getMessage().replaceAll("\\s+", " ")));
            keptCheckouts(err, e);
            return ExitCodes.VERIFY_FAILED;
        } catch (RunRefusedException e) {
            TerminalText.println(err, "traceworks: ", TerminalText.of(e));
            keptCheckouts(err, e);
            return ExitCodes.REFUSED;
        } catch (NoSuchRunException | AmbiguousRunException | RunReferenceTooShortException e) {
            TerminalText.println(err, "traceworks: ", TerminalText.of(e));
            return ExitCodes.USAGE;
        } catch (Exception | Error e) {
            TerminalText.println(err, "traceworks: internal error: " + e.getClass().getSimpleName(),
                    e.getMessage() == null ? "" : TerminalText.seq(": ", TerminalText.oneLine(e.getMessage())));
            keptCheckouts(err, e);
            return ExitCodes.INTERNAL;
        }
    }

    /** The checkouts of runs' sources that the failure {@code e} left behind, one line each. */
    private static void keptCheckouts(PrintStream err, Throwable e) {
        for (Throwable suppressed : e.getSuppressed()) {
            if (suppressed instanceof KeptCheckoutNotice kept) {
                TerminalText.println(err, "traceworks: ", TerminalText.of(kept));
            }
        }
    }
}
