package traceworks.core.app;

import traceworks.core.util.Message;


/**
 * What became of an answer.
 *
 * @param accepted  whether the answer is recorded
 * @param runParked whether the run is parked, so it goes on only with {@code resume}
 * @param message   what happened, for the person who answered
 * @param parts     the message with its parts, where it says which of them quote a value from outside; {@code null}
 *                  for a message that is quoted as a whole (a refusal names the workflow's options and fields)
 */
public record AnswerResult(boolean accepted, boolean runParked, String message, Message parts) {

    /** A result whose message says nothing about its parts. */
    public AnswerResult(boolean accepted, boolean runParked, String message) {
        this(accepted, runParked, message, null);
    }

    /** A result whose message says its parts. */
    public AnswerResult(boolean accepted, boolean runParked, Message parts) {
        this(accepted, runParked, parts.text(), parts);
    }
}
