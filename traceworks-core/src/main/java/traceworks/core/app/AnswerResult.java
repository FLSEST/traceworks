package traceworks.core.app;

/**
 * What became of an answer.
 *
 * @param accepted  whether the answer is recorded
 * @param runParked whether the run is parked, so it goes on only with {@code resume}
 * @param message   what happened, for the person who answered
 */
public record AnswerResult(boolean accepted, boolean runParked, String message) {
}
