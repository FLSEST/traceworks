package traceworks.core.record;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import traceworks.core.context.PlaceholderSyntax;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.RunEvent;
import traceworks.core.port.RunEvent.*;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Maps an engine event to the fields of its record line, in the fixed order of the event table. Every value that
 * comes from run data becomes a blob; the mapping reads the event completely, so nothing of it is kept afterwards.
 */
final class EventFields {

    /** The type and the fields of one line. */
    record Mapped(String type, ObjectNode fields) {}

    private final Blobs blobs;
    private final WorkflowDefinition definition;

    EventFields(Blobs blobs, WorkflowDefinition definition) {
        this.blobs = blobs;
        this.definition = definition;
    }

    /**
     * @param topLevel whether the event belongs to the root run, whose definition the mapper knows
     * @param answer   who answered, for an {@link ApprovalAnswered}; ignored for other events
     */
    Mapped map(RunEvent event, boolean topLevel, Answer answer) {
        ObjectNode f = Blobs.MAPPER.createObjectNode();
        return switch (event) {
            case StepStarted e -> {
                f.put("step", e.stepIndex());
                f.put("name", e.blockName());
                f.put("block_type", e.blockType());
                f.put("position", e.position());
                f.put("mode", e.ai() ? "ai" : "deterministic");
                f.set("uses", uses(topLevel ? e.position() : -1));
                yield new Mapped(RecordTypes.STEP_STARTED, f);
            }
            case PackageCalled e -> {
                // What run.started says of the run's own package, in the same fields, for the package of a child
                ObjectNode workflow = f.putObject("workflow");
                workflow.put("id", e.workflowId());
                workflow.put("name", e.workflowName());
                f.put("reference", e.reference());
                ObjectNode pkg = f.putObject("package");
                pkg.put("dir", e.packageDir());
                pkg.put("digest", e.packageDigest());
                f.set("definition", blobs.of(e.workflowJson()));
                yield new Mapped(RecordTypes.RUN_PACKAGE, f);
            }
            case StepWaiting e -> {
                f.put("step", e.stepIndex());
                f.put("waiting", e.waiting());
                yield new Mapped(RecordTypes.STEP_WAITING, f);
            }
            case OutputRecorded e -> {
                f.put("step", e.stepIndex());
                f.put("name", e.blockName());
                f.set("output", blobs.of(e.output()));
                yield new Mapped(RecordTypes.STEP_OUTPUT, f);
            }
            case GateReported e -> {
                f.put("step", e.stepIndex());
                f.put("status", e.status() == null ? null : e.status().name());
                f.put("category", e.category());
                f.set("description", blobs.of(e.description()));
                yield new Mapped(RecordTypes.GATE_REPORTED, f);
            }
            case ApprovalRequested e -> {
                f.put("step", e.stepIndex());
                f.put("kind", e.type());
                f.put("block_id", e.blockId());
                f.set("question", blobs.of(e.question()));
                f.set("options", Blobs.MAPPER.valueToTree(e.options()));
                f.set("reject_options", Blobs.MAPPER.valueToTree(e.rejectOptions()));
                f.put("input_type", e.inputType());
                f.set("default", blobs.of(e.defaultValue()));
                f.put("required", e.required());
                f.put("pattern", e.validationPattern());
                f.put("variable", e.variableName());
                f.set("fields", Blobs.MAPPER.valueToTree(e.fields()));
                f.put("parks", e.parks());
                f.put("preset", e.preset());
                yield new Mapped(RecordTypes.APPROVAL_REQUESTED, f);
            }
            case ApprovalAnswered e -> {
                Answer who = answer != null ? answer : Answer.NONE;
                f.put("step", e.stepIndex());
                f.put("kind", e.type());
                f.set("answer", blobs.of(e.answer()));
                f.put("actor", who.actor());
                if (who.agent() != null) {
                    // Only when an agent said so: a record that names answer_agent means "none" by leaving it out
                    f.put("agent", who.agent());
                }
                f.put("source", who.source());
                f.put("request", who.requestId());
                f.set("comment", blobs.of(who.comment()));
                yield new Mapped(RecordTypes.APPROVAL_ANSWERED, f);
            }
            case ForEachItemStarted e -> {
                f.put("step", e.stepIndex());
                f.put("item_index", e.itemIndex());
                f.put("total", e.totalItems());
                f.set("item", blobs.of(e.item()));
                yield new Mapped(RecordTypes.FOREACH_ITEM_STARTED, f);
            }
            case ForEachItemFinished e -> {
                f.put("step", e.stepIndex());
                f.put("item_index", e.itemIndex());
                f.put("total", e.totalItems());
                f.put("status", e.status() == null ? null : e.status().name());
                f.set("output", blobs.of(e.output()));
                f.put("sub_run", e.subRunId());
                if (e.resumedFrom() != null) {
                    // Taken over, not run: no foreach.item.started names the item, so this line does
                    f.put("resumed_from", e.resumedFrom());
                    f.set("item", blobs.of(e.item()));
                }
                yield new Mapped(RecordTypes.FOREACH_ITEM_FINISHED, f);
            }
            case AgenticProgress e -> {
                f.put("step", e.stepIndex());
                f.put("phase", e.type());
                f.set("data", blobs.of(e.data()));
                yield new Mapped(RecordTypes.AGENTIC_PROGRESS, f);
            }
            case RunStatusChanged e -> {
                f.put("status", e.status().name());
                yield new Mapped(RecordTypes.RUN_STATUS, f);
            }
            case Checkpoint e -> {
                f.put("next_position", e.nextPosition());
                // Each text of the state is a blob of its own, the same one as where the run recorded it before; a
                // text the checkpoint before stored is not stored again
                f.set("state", blobs.of(blobs.checkpointTexts(
                        texts -> RunSnapshotJson.write(e.snapshot(), texts::apply))));
                yield new Mapped(RecordTypes.RUN_CHECKPOINT, f);
            }
            case StepFinished e -> {
                f.put("step", e.stepIndex());
                f.put("name", e.blockName());
                f.put("block_type", e.blockType());
                f.put("status", e.status() == null ? null : e.status().name());
                f.put("duration_ms", e.durationMs());
                f.set("input", blobs.of(e.input()));
                f.set("metadata", blobs.of(e.metadata()));
                yield new Mapped(RecordTypes.STEP_FINISHED, f);
            }
            case RunParked e -> {
                if (e.stepIndex() == RunParked.NO_STEP) {
                    f.putNull("step");   // paused between two blocks: no step is unfinished
                } else {
                    f.put("step", e.stepIndex());
                }
                f.put("kind", e.type());
                yield new Mapped(RecordTypes.RUN_PARKED, f);
            }
            case RunFinished e -> {
                f.put("status", e.status().name());
                f.set("gate", gate(e.gate()));
                f.set("gate_description", blobs.of(e.gate().get("gate_description")));
                f.set("gate_summary", blobs.of(e.gate().get("gate_summary")));
                f.set("error", blobs.of(e.error()));
                yield new Mapped(RecordTypes.RUN_FINISHED, f);
            }
            case ChildRunEvent e -> throw new IllegalArgumentException("A child run event must be unwrapped first");
        };
    }

    /** The fields of a use-case event: bytes become blobs, {@code null} stays JSON {@code null}. */
    ObjectNode custom(Map<String, Object> values) {
        ObjectNode f = Blobs.MAPPER.createObjectNode();
        values.forEach((key, value) -> {
            if (value instanceof byte[]) {
                f.set(key, blobs.of(value));
            } else {
                f.set(key, Blobs.MAPPER.valueToTree(value));
            }
        });
        return f;
    }

    /** {@code {status, category}} of the gate verdict; JSON {@code null} when the run has none. */
    private JsonNode gate(Map<String, Object> gate) {
        if (gate.get("gate_status") == null) {
            return Blobs.MAPPER.nullNode();
        }
        ObjectNode node = Blobs.MAPPER.createObjectNode();
        node.put("status", String.valueOf(gate.get("gate_status")));
        Object category = gate.get("gate_category");
        node.put("category", category == null ? null : category.toString());
        return node;
    }

    private ArrayNode uses(int position) {
        TreeSet<String> keys = new TreeSet<>();
        List<BlockDefinition> blocks = definition.blocks();
        if (position >= 0 && position < blocks.size() && blocks.get(position) != null) {
            collect(blocks.get(position).getConfig(), keys);
        }
        ArrayNode array = Blobs.MAPPER.createArrayNode();
        keys.forEach(array::add);
        return array;
    }

    private static void collect(Object value, TreeSet<String> keys) {
        if (value instanceof String text) {
            keys.addAll(PlaceholderSyntax.keysIn(text));
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(v -> collect(v, keys));
        } else if (value instanceof Iterable<?> items) {
            items.forEach(v -> collect(v, keys));
        }
    }

    /** Who answered, as set by {@code attributeNextAnswer}. */
    record Answer(String actor, String source, String requestId, String comment, String agent) {
        static final Answer NONE = new Answer(null, null, null, null, null);
    }
}
