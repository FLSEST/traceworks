package traceworks.cli.command;

import traceworks.cli.Args.Parsed;
import traceworks.cli.Args.Spec;
import traceworks.cli.Command;
import traceworks.cli.Context;
import traceworks.cli.ExitCodes;
import traceworks.cli.TerminalText;
import traceworks.cli.UsageException;
import traceworks.core.app.Credentials;
import traceworks.core.util.Message;

import java.io.UncheckedIOException;
import java.util.List;

/**
 * {@code traceworks credentials set|list|remove}: the entries of {@code <home>/credentials.json}, the values of secrets
 * and of the AI providers' credentials. {@code set} reads the value from the terminal without showing it, or from a
 * piped standard input, never from an argument; it creates the home and the file for their owner alone. Nothing here
 * prints a value. A file that cannot be trusted or written, or a value no run would take, exits 6.
 */
public final class CredentialsCommand implements Command {

    private static final Spec SPEC = Spec.of("credentials", "Stores, lists and removes the values of secrets and AI"
                    + " credentials: 'credentials set <NAME>'.", "set <NAME> | list | remove <NAME>")
            .positionals(1, 2)
            .note("""
                    set <NAME>     stores a value in credentials.json in the Traceworks home; it replaces the one the
                                   name has. The value is typed on the terminal, which does not show it, or piped in:
                                   'traceworks credentials set NAME < file'. It is never an argument. The home and the
                                   file are created for their owner alone (chmod 700 and 600).
                    list           prints the stored names, never a value.
                    remove <NAME>  removes the name and its value.""")
            .note("The file takes precedence over the environment. A run reads it when it starts or resumes.");

    @Override
    public Spec spec() {
        return SPEC;
    }

    @Override
    public int run(Context context, Parsed args) {
        List<String> words = args.positionals();
        String action = words.getFirst();
        try {
            return switch (action) {
                case "list" -> list(context, words);
                case "set" -> set(context, name(words, action));
                case "remove" -> remove(context, name(words, action));
                default -> throw new UsageException(Message.of("unknown credentials command '", Message.typed(action),
                        "'; the commands are 'set', 'list' and 'remove'"));
            };
        } catch (IllegalArgumentException | UncheckedIOException e) {
            // No such message shows a value: the store names paths and positions, the use case names and rules
            TerminalText.println(context.err(), "traceworks: ", TerminalText.of(e));
            return ExitCodes.REFUSED;
        }
    }

    private static String name(List<String> words, String action) {
        if (words.size() < 2) {
            throw new UsageException(Message.of("credentials " + action
                    + " needs a name, such as ANTHROPIC_API_KEY"));
        }
        String name = words.get(1);
        if (!Credentials.isName(name)) {
            throw new UsageException(Message.of("'", Message.typed(name), "' is not a credential name: use capital"
                    + " letters, digits and _, not starting with a digit. A value is never an argument"));
        }
        return name;
    }

    private static int list(Context context, List<String> words) {
        if (words.size() > 1) {
            throw new UsageException(Message.of("credentials list takes no name"));
        }
        Credentials credentials = context.credentials();
        List<String> names = credentials.names();
        if (names.isEmpty()) {
            TerminalText.println(context.out(), "no credentials in ", TerminalText.whole(credentials.location()));
        }
        // A name is checked where it is stored; a file written by hand can hold any
        // A name has the shape the store checks; one that has not is marked as a text from outside
        names.forEach(name -> TerminalText.println(context.out(), TerminalText.word(name)));
        return ExitCodes.OK;
    }

    private static int set(Context context, String name) {
        if (context.secrets() == null) {
            throw new UsageException(Message.of("credentials set has no input to read the value from: type it on a terminal, or"
                    + " pipe it into the standard input"));
        }
        Credentials credentials = context.credentials();
        credentials.names();   // a file that cannot be trusted refuses before the person types a value
        String value = context.secrets().read("Value of " + name + " (not shown): ");
        if (value == null) {
            TerminalText.println(context.err(), "traceworks: no value given for ", TerminalText.whole(name),
                    "; nothing was stored");
            return ExitCodes.REFUSED;
        }
        boolean replaced = credentials.set(name, value);
        // The name is the operator's own argument, and the file is one of their home
        TerminalText.println(context.out(), (replaced ? "replaced " : "stored "), TerminalText.whole(name), " in ",
                TerminalText.whole(credentials.location()));
        return ExitCodes.OK;
    }

    private static int remove(Context context, String name) {
        Credentials credentials = context.credentials();
        if (!credentials.remove(name)) {
            TerminalText.println(context.err(), "traceworks: no ", TerminalText.whole(name), " in ",
                    TerminalText.whole(credentials.location()));
            return ExitCodes.REFUSED;
        }
        TerminalText.println(context.out(), "removed ", TerminalText.whole(name), " from ",
                TerminalText.whole(credentials.location()));
        return ExitCodes.OK;
    }
}
