package traceworks.core.app;

import traceworks.core.record.QuestionView;

import java.util.Optional;

/**
 * Asks a person a question of a running run, such as on a terminal. Each question is asked once, on a thread of its
 * own, which is interrupted when the question no longer needs an answer from here (it was answered through the inbox,
 * timed out, or the run ended); an asker returns then. An answer the run refuses (not one of the offered options)
 * leaves the question to the inbox, so an asker checks the options itself and asks again before it returns.
 */
public interface QuestionAsker {

    /**
     * @return the answer, encoded as the step receives it: one of the offered options of an approval, the text of a
     *         single human input or the JSON object of a form; empty to leave the question to the inbox
     */
    Optional<String> ask(QuestionView question);

    /**
     * The agent that answers here under the actor of the run's process, when one said so: the name it gave itself,
     * checked ({@link traceworks.core.register.DecisionAgent}). Every answer of this asker is recorded with it.
     *
     * @return the name; {@code null} when a person answers, or nobody said otherwise
     */
    default String agent() {
        return null;
    }
}
