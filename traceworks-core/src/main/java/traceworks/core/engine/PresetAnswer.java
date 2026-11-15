package traceworks.core.engine;

/**
 * An answer given while the run waited outside the process, for the question the run waited at: the resumed step asks
 * that question again and receives this answer at once.
 *
 * @param stepIndex the index of the step that asked
 * @param type      {@code approval} or {@code human-input}
 * @param question  the question as it was asked; the step fails when it now asks another one
 * @param answer    the answer, encoded as the step receives it
 */
public record PresetAnswer(int stepIndex, String type, String question, String answer) {
}
