package traceworks.core.record;

import traceworks.core.port.BlobRef;

/**
 * One item of a foreach step.
 *
 * @param status the status the item ended with; {@code null} while it runs
 * @param output what the item produced; {@code null} when nothing or not yet
 * @param subRun the run the item started; {@code null} when it ran a command
 * @param resumedFrom the seq of the earlier {@code foreach.item.finished} a resumed loop took the item over from;
 *               {@code null} for an item that ran in this pass
 */
public record ItemView(int index, int total, BlobRef item, String status, BlobRef output, String subRun,
                       Integer resumedFrom) {

    /** An item that ran. */
    public ItemView(int index, int total, BlobRef item, String status, BlobRef output, String subRun) {
        this(index, total, item, status, output, subRun, null);
    }
}
