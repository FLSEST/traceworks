package traceworks.core.context;

/**
 * A resumed step cannot take the answer given while the run was parked: the step fails with this message as its
 * output, and no answer is recorded. {@link QuestionChangedException} is the case of a step that now asks another
 * question; an approval answer that is not one of the offered options is the other.
 */
public class PresetAnswerException extends IllegalStateException {

    public PresetAnswerException(String message) {
        super(message);
    }
}
