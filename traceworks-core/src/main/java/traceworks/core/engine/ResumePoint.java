package traceworks.core.engine;

import traceworks.core.context.RecordedItems;
import traceworks.core.model.RunSnapshot;

import java.util.Objects;

/**
 * Where {@link WorkflowEngine#resume} continues a run: the snapshot of a checkpoint and the block position it was taken
 * before.
 *
 * @param snapshot     the run as the checkpoint kept it
 * @param nextPosition the position, in the workflow's {@code blocks}, of the block that runs next; the number of blocks
 *                     when every block has run
 * @param answer       the answer to the question the run waited at; {@code null} when it waited at none
 * @param items        the items the run's record holds as finished for the {@code foreach} step that was in flight:
 *                     the loop takes them over instead of running them. {@link RecordedItems#NONE} runs every item.
 */
public record ResumePoint(RunSnapshot snapshot, int nextPosition, PresetAnswer answer, RecordedItems items) {

    public ResumePoint {
        Objects.requireNonNull(snapshot, "snapshot");
        items = items == null ? RecordedItems.NONE : items;
    }

    /** A point whose loop, if one was in flight, runs every item again. */
    public ResumePoint(RunSnapshot snapshot, int nextPosition, PresetAnswer answer) {
        this(snapshot, nextPosition, answer, RecordedItems.NONE);
    }
}
