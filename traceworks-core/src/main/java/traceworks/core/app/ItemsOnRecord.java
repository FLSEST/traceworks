package traceworks.core.app;

import com.fasterxml.jackson.databind.JsonNode;
import traceworks.core.context.RecordedItems;
import traceworks.core.model.StepStatus;
import traceworks.core.port.BlobRef;
import traceworks.core.port.StoredRun;
import traceworks.core.record.RecordTypes;
import traceworks.core.record.RecordedEvent;
import traceworks.core.record.Redactor;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The items a resumed {@code foreach} takes over, read from the run's record: those of the last pass of the step that
 * runs again. A pass is one run through the loop; a new one begins with the step's {@code step.started} (a resume)
 * and wherever the item index does not go up (the loop ran again in the same process, for an output validation
 * retry). Only the last pass counts, and it is complete on its own, because a pass records again each item it took
 * over.
 *
 * <p>An item is taken over only when nothing about it is in doubt; anything else runs again:
 *
 * <ul>
 *   <li>its {@code foreach.item.finished} says {@code COMPLETED}, and it ran a command, not a child run;</li>
 *   <li>the record names the same item text at the same index, byte for byte (a text the record holds redacted is
 *       another text);</li>
 *   <li>its output is whole: the blob is there, has the content the record names, and holds no redaction marker (a
 *       redacted output is not the output the item produced).</li>
 * </ul>
 *
 * <p>An item whose command output the output limit cut is none of them: such a command never ends with exit code 0,
 * so its item failed.
 *
 * <p>The texts are read when the loop asks for an item, one item at a time.
 */
final class ItemsOnRecord implements RecordedItems {

    private final StoredRun run;
    private final int step;
    private final Map<Integer, Finished> finished;

    /** What the record holds of one finished item: the blobs of its text and its output, and the seq of the event. */
    private record Finished(BlobRef item, BlobRef output, int seq) {}

    private ItemsOnRecord(StoredRun run, int step, Map<Integer, Finished> finished) {
        this.run = run;
        this.step = step;
        this.finished = finished;
    }

    /**
     * The items of the step that runs again.
     *
     * @param events    the run's record
     * @param rerunStep the index of the step the resume runs again; {@code null} when no step was in flight
     * @return {@link RecordedItems#NONE} when no step runs again or the record holds no finished item of it
     */
    static RecordedItems of(StoredRun run, List<RecordedEvent> events, Integer rerunStep) {
        if (rerunStep == null) {
            return NONE;
        }
        Map<Integer, Finished> finished = new HashMap<>();
        Map<Integer, BlobRef> texts = new HashMap<>();
        int last = -1;
        for (RecordedEvent event : events) {
            if (event.parent() != null || !Integer.valueOf(rerunStep).equals(event.step())) {
                continue;
            }
            JsonNode f = event.fields();
            switch (event.type()) {
                case RecordTypes.STEP_STARTED -> {
                    last = newPass(finished, texts);
                }
                case RecordTypes.FOREACH_ITEM_STARTED -> {
                    int index = f.path("item_index").asInt(-1);
                    if (index <= last) {
                        newPass(finished, texts);
                    }
                    last = index;
                    texts.put(index, event.blob("item"));
                }
                case RecordTypes.FOREACH_ITEM_FINISHED -> {
                    int index = f.path("item_index").asInt(-1);
                    boolean takenOver = f.path("resumed_from").isInt();
                    if (takenOver) {   // no foreach.item.started came before it
                        if (index <= last) {
                            newPass(finished, texts);
                        }
                        last = index;
                    }
                    BlobRef item = takenOver ? event.blob("item") : texts.get(index);
                    boolean completed = StepStatus.COMPLETED.name().equals(f.path("status").asText(null));
                    boolean childRun = f.hasNonNull("sub_run");
                    if (index >= 0 && completed && !childRun && item != null && event.blob("output") != null) {
                        finished.put(index, new Finished(item, event.blob("output"), event.seq()));
                    }
                }
                default -> { }
            }
        }
        return finished.isEmpty() ? NONE : new ItemsOnRecord(run, rerunStep, finished);
    }

    /** Forgets the pass before; returns the index no item has reached yet. */
    private static int newPass(Map<Integer, Finished> finished, Map<Integer, BlobRef> texts) {
        finished.clear();
        texts.clear();
        return -1;
    }

    @Override
    public Completed completed(int stepIndex, int itemIndex, String item) {
        Finished recorded = stepIndex == step ? finished.get(itemIndex) : null;
        if (recorded == null || item == null) {
            return null;
        }
        byte[] text = content(recorded.item());
        if (text == null || !Arrays.equals(text, item.getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        byte[] output = content(recorded.output());
        if (output == null) {
            return null;
        }
        String produced = new String(output, StandardCharsets.UTF_8);
        if (produced.contains(Redactor.MARKER)) {
            return null;
        }
        return new Completed(produced, recorded.seq());
    }

    /**
     * The content of a blob, checked against what the record names; {@code null} when it is deleted or has other
     * content. Damage does not stop the run here: the item runs again, and {@code verify} reports the blob.
     */
    private byte[] content(BlobRef ref) {
        try {
            Optional<byte[]> content = RunRecords.checkedContent(run, ref, "foreach item");
            return content.orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
