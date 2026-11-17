package traceworks.cli;

import traceworks.core.util.Message;

/** The command line is wrong: an unknown flag, a missing value, the wrong number of arguments. Exit code 2. */
public final class UsageException extends RuntimeException implements Message.Carrier {

    private final transient Message parts;

    /** A message that says which of its parts quote a value from outside; the exception's message is its text. */
    public UsageException(Message message) {
        super(message.text());
        this.parts = message;
    }

    @Override
    public Message parts() {
        return parts;
    }

    public UsageException(String message) {
        super(message);
        this.parts = null;
    }
}
