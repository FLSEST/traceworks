package traceworks.core.context;

import traceworks.core.model.ContextState;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hands out the step indices of one run. The block loop pre-allocates an index for the RUNNING
 * placeholder it shows immediately; the executor then picks up that same index. Wrapper blocks
 * (foreach, while, retry, …) draw one further index per item or iteration, which is why a block's
 * index runs ahead of its position in the block list.
 */
public class StepIndexCounter {

    private final AtomicInteger counter = new AtomicInteger(0);
    private final AtomicInteger preAllocated = new AtomicInteger(-1);

    /** Reserves the next index; the following {@link #next()} returns this same value. */
    public int preAllocate() {
        int index = counter.getAndIncrement();
        preAllocated.set(index);
        return index;
    }

    public int next() {
        int reserved = preAllocated.getAndSet(-1);
        return reserved >= 0 ? reserved : counter.getAndIncrement();
    }

    /**
     * Runs the block holding {@code index} once more (output validation retry): the attempt
     * gets {@code index} again and draws its further indices from just after it, as the first attempt
     * did — so the retry itself shifts no later index. An attempt of a wrapper that draws a different
     * number of inner indices (foreach with {@code items_command}, while, retry) still moves later blocks
     * by that difference, as a different input would without a retry.
     */
    public void rewindTo(int index) {
        counter.set(index + 1);
        preAllocated.set(index);
    }

    /**
     * Resume: numbers this segment's steps from {@code firstIndex} on, so they neither collide with
     * nor sort before the steps the earlier segment persisted.
     */
    public void startAt(int firstIndex) {
        if (firstIndex > 0) {
            counter.set(firstIndex);
        }
    }

    /**
     * The index the next step gets, as part of the run's state ({@link ContextState}). A reservation is not part of
     * it: the state is taken between two top-level blocks, where the next call is always {@link #preAllocate}, which
     * reserves anew.
     */
    int state() {
        return counter.get();
    }

    /** Hands out indices from {@code nextIndex} on, as the counter a run's state was taken from. */
    void restore(int nextIndex) {
        counter.set(nextIndex);
        preAllocated.set(-1);
    }
}
