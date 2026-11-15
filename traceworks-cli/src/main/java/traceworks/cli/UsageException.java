package traceworks.cli;

/** The command line is wrong: an unknown flag, a missing value, the wrong number of arguments. Exit code 2. */
public final class UsageException extends RuntimeException {

    public UsageException(String message) {
        super(message);
    }
}
