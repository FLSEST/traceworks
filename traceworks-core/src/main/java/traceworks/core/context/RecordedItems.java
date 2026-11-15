package traceworks.core.context;

/**
 * The items of a {@code foreach} step that the run's record holds as finished, for a resumed run: the step that was
 * in flight runs again, and takes these over instead of running them. Whoever resumes the run reads them from its
 * record; the loop asks per item and runs every item it gets no answer for.
 */
@FunctionalInterface
public interface RecordedItems {

    /** No item is taken over: a fresh run, a child run, a record without finished items. */
    RecordedItems NONE = (stepIndex, itemIndex, item) -> null;

    /**
     * The item the record holds as finished for the loop of step {@code stepIndex}, when it is the item the loop is
     * about to run: recorded as completed at {@code itemIndex}, with the text {@code item} byte for byte, and with
     * its whole output.
     *
     * @return {@code null} when the record holds no such item, or anything about it is in doubt: the item runs
     */
    Completed completed(int stepIndex, int itemIndex, String item);

    /**
     * An item the record holds as completed.
     *
     * @param output     what the item produced, as the step gathered it then
     * @param recordedAt the seq of the {@code foreach.item.finished} that holds it
     */
    record Completed(String output, int recordedAt) {
        public Completed {
            output = output == null ? "" : output;
        }
    }
}
