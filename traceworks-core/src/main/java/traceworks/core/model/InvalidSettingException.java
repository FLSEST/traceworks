package traceworks.core.model;

/**
 * A block setting has the wrong type or is out of range ({@link BlockSettings}). Thrown before the block does
 * anything with it, so the block fails instead of reading a default the author did not write; the message names the
 * key, what it must be and what was found.
 */
public class InvalidSettingException extends RuntimeException {

    public InvalidSettingException(String message) {
        super(message);
    }
}
