package traceworks.core.record;

import traceworks.core.model.StepStatus;
import traceworks.core.port.BlobRef;

import java.time.Instant;
import java.util.List;

/**
 * A step of a run as the record shows it: the latest attempt.
 *
 * @param name        the block name; {@code null} for a block without one ({@link #label()} shows it)
 * @param status      {@code RUNNING} (or {@code WAITING_APPROVAL}) while the step is open; {@code CANCELLED} for a
 *                    step that finished so ({@code step.finished}) and for one that was still open when the run ended
 *                    {@code CANCELLED} ({@link #cancelled})
 * @param interrupted whether the step was open when the run was interrupted
 * @param cancelled   whether the step was open when the run was cancelled (parked or interrupted): it never
 *                    finished, and its status is {@code CANCELLED}, derived from {@code run.finished}
 * @param attempts    how often the step started; more than one after a resume that ran it again
 * @param uses        the variables the block's configuration refers to
 * @param input       what the step ran; {@code null} while open
 * @param output      the last output the step recorded
 * @param metadata    the metadata blob of the finished step
 * @param gate        the verdict the step reported; {@code null} when none
 * @param files       the scratch directory after the step; {@code null} when the record has no {@code step.files}
 *                    for it
 * @param aiCalls     the AI calls the step made, in order
 * @param childRunIds     the runs this step started, in the order they began
 * @param earlierAttempts the attempts before the latest one, oldest first: a step that parked or was in flight
 *                        when its run was interrupted starts again on resume, and what it did before stays visible
 */
public record StepView(int stepIndex, String name, String blockType, int position, boolean ai, StepStatus status,
                       boolean interrupted, boolean cancelled, long durationMs, int attempts, List<String> uses,
                       List<CommandView> commands, BlobRef input, BlobRef output, BlobRef metadata, GateView gate,
                       List<QuestionView> questions, List<ItemView> items, List<String> childRunIds,
                       List<Attempt> earlierAttempts, StepFiles files, List<AiCallView> aiCalls) {

    public StepView {
        aiCalls = List.copyOf(aiCalls);
        uses = List.copyOf(uses);
        commands = List.copyOf(commands);
        questions = List.copyOf(questions);
        items = List.copyOf(items);
        childRunIds = List.copyOf(childRunIds);
        earlierAttempts = List.copyOf(earlierAttempts);
    }

    /**
     * An attempt of the step before its latest: the commands it ran, the AI calls it made and the questions it asked,
     * with their answers.
     *
     * @param number    1 for the first attempt
     * @param startedAt when its {@code step.started} was written
     */
    public record Attempt(int number, Instant startedAt, List<CommandView> commands, List<QuestionView> questions,
                          List<AiCallView> aiCalls) {

        public Attempt {
            commands = List.copyOf(commands);
            questions = List.copyOf(questions);
            aiCalls = List.copyOf(aiCalls);
        }
    }

    /** What a step without a name is shown as. */
    public static final String UNNAMED = "(unnamed)";

    /**
     * The status to show: the status's name; {@code CANCELLED} also for a step that was open when its run was
     * cancelled.
     */
    public String shownStatus() {
        return status.name();
    }

    /** The name to show: the block name, or {@value #UNNAMED} for a block without one. */
    public String label() {
        return name == null ? UNNAMED : name;
    }
}
