package traceworks.core.context;

import traceworks.core.util.TruncationUtil;

/**
 * A resumed step asks another question than the one the run parked at, so the answer given meanwhile does not answer
 * it. The step fails with this message as its output: {@value #REASON}, followed by the question the answer was given
 * to.
 */
public final class QuestionChangedException extends PresetAnswerException {

    /** The start of the message. */
    public static final String REASON = "the question changed while the run was parked";

    /** How much of the earlier question the message quotes. */
    private static final int QUOTED = 120;

    /**
     * @param answeredQuestion the question the answer was given to
     * @param keepWhole        the run's secret values, which the cut of a long question keeps whole
     */
    public QuestionChangedException(String answeredQuestion, java.util.Collection<String> keepWhole) {
        super(REASON + ": the answer was given to \"" + TruncationUtil.head(answeredQuestion, QUOTED, keepWhole)
                + "\"");
    }
}
