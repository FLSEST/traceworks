package traceworks.core.record;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import traceworks.core.model.ContextState;
import traceworks.core.model.RunSnapshot;
import traceworks.core.model.SourceStep;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.port.BlobRef;
import traceworks.core.testing.SnapshotTexts;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunSnapshotJsonTest {

    private static final Instant STARTED = Instant.parse("2026-11-03T09:00:00.123456789Z");
    private static final Instant COMPLETED = Instant.parse("2026-11-03T09:00:01.5Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The texts of the snapshots a test writes. */
    private final SnapshotTexts texts = new SnapshotTexts();

    @Test
    void roundTrip_isStable() {
        byte[] written = RunSnapshotJson.write(withStepOutputVariables(), texts);

        assertThat(RunSnapshotJson.write(RunSnapshotJson.read(written, texts), texts)).isEqualTo(written);
    }

    @Test
    void read_givesBackEveryValue() {
        RunSnapshot snapshot = withStepOutputVariables();

        RunSnapshot read = RunSnapshotJson.read(RunSnapshotJson.write(snapshot, texts), texts);

        assertThat(read.context()).isEqualTo(snapshot.context());
        assertThat(read.inputContext()).isEqualTo(snapshot.inputContext());
        assertThat(read.steps()).usingRecursiveFieldByFieldElementComparator().isEqualTo(snapshot.steps());
        assertThat(read.steps().getFirst().getStartedAt()).isEqualTo(STARTED);
    }

    /** Each flag of the taint comes back as it was written, also when the flags differ from one another. */
    @Test
    void read_givesBackEachTaintFlagOnItsOwn() {
        for (boolean[] flags : new boolean[][] {{true, false, false}, {false, true, false}, {false, false, true}}) {
            ContextState.Taint taint = new ContextState.Taint(Set.of(), Set.of(), Set.of(), flags[0], flags[1],
                    flags[2]);
            ContextState c = context(Map.of("x", "1"));
            RunSnapshot snapshot = new RunSnapshot(new ContextState(c.variables(), taint, c.blockOutputs(),
                    c.previousOutput(), c.sourceSteps(), c.nextStepIndex(), c.runMetadata(), c.rememberedApprovals()),
                    List.of(), Map.of());

            RunSnapshot read = RunSnapshotJson.read(RunSnapshotJson.write(snapshot, texts), texts);

            assertThat(read.context().taint()).as(java.util.Arrays.toString(flags)).isEqualTo(taint);
        }
    }

    private static RunSnapshot withTaint(ContextState.Taint taint) {
        ContextState c = context(Map.of("x", "1"));
        return new RunSnapshot(new ContextState(c.variables(), taint, c.blockOutputs(), c.previousOutput(),
                c.sourceSteps(), c.nextStepIndex(), c.runMetadata(), c.rememberedApprovals()), List.of(), Map.of());
    }

    /** What may hold text a model wrote comes back; a run that asked no model writes nothing about it. */
    @Test
    void read_givesBackTheModelText_andARunWithoutAModelWritesNone() throws Exception {
        ContextState.Taint asked = new ContextState.Taint(Set.of(), Set.of(), Set.of(), false, false, false,
                new ContextState.ModelText(true, Set.of("step_1_output", "item"), Set.of("ask"), true));
        byte[] written = RunSnapshotJson.write(withTaint(asked), texts);

        assertThat(RunSnapshotJson.read(written, texts).context().taint()).isEqualTo(asked);
        assertThat(JSON.readTree(written).at("/context/taint/model_text").toString())
                .isEqualTo("{\"variables\":[\"item\",\"step_1_output\"],\"outputs\":[\"ask\"],"
                        + "\"previous_output\":true}");

        ContextState.Taint none = new ContextState.Taint(Set.of(), Set.of(), Set.of(), false, false, false);
        byte[] without = RunSnapshotJson.write(withTaint(none), texts);
        assertThat(JSON.readTree(without).at("/context/taint").has("model_text")).isFalse();
        assertThat(RunSnapshotJson.read(without, texts).context().taint().modelText())
                .isEqualTo(ContextState.ModelText.NONE);
    }

    @Test
    void read_keepsTheOrderOfAValue() {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("zeta", 1);
        form.put("alpha", 2);
        RunSnapshot snapshot = new RunSnapshot(context(Map.of("form", form)), List.of(), Map.of());

        RunSnapshot read = RunSnapshotJson.read(RunSnapshotJson.write(snapshot, texts), texts);

        assertThat(String.valueOf(read.context().variables().get("form")))
                .as("a map value renders into a template as it did before")
                .isEqualTo("{zeta=1, alpha=2}");
    }

    @Test
    void write_isTheSameForEqualSnapshots_whateverTheOrderOfTheirMaps() {
        Map<String, Object> ab = new LinkedHashMap<>();
        ab.put("a", "1");
        ab.put("b", "2");
        ab.put("step_1_output", "one");
        Map<String, Object> ba = new LinkedHashMap<>();
        ba.put("step_1_output", "one");
        ba.put("b", "2");
        ba.put("a", "1");

        assertThat(RunSnapshotJson.write(new RunSnapshot(context(ab), List.of(), ba), texts))
                .isEqualTo(RunSnapshotJson.write(new RunSnapshot(context(ba), List.of(), ab), texts));
    }

    /**
     * Each text of the steps and each variable that holds a text is a blob the snapshot refers to, in fields that
     * always hold a reference; equal texts are one blob. A variable that holds no text, a {@code step_<n>_output} of
     * the workflow's own among them, stays inline, and no step carries a context delta.
     */
    @Test
    void write_refersToEachTextOfTheSteps_andToEachTextVariable_byItsBlob() throws Exception {
        JsonNode root = JSON.readTree(RunSnapshotJson.write(withStepOutputVariables(), texts));

        assertThat(root.get("format").asInt()).isEqualTo(RunSnapshotJson.FORMAT).isEqualTo(3);
        JsonNode output = root.at("/steps/0/output");
        assertThat(text(output)).isEqualTo("built\nok");
        assertThat(text(root.at("/steps/0/input"))).isEqualTo("make build");
        assertThat(root.at("/steps/1/output").isNull()).as("a step without output").isTrue();
        assertThat(root.at("/context/block_outputs/build")).isEqualTo(output);
        assertThat(text(root.at("/context/block_outputs/short"))).isEqualTo("ok");
        assertThat(text(root.at("/context/previous_output"))).isEqualTo("ok");
        assertThat(root.at("/context/text_variables").properties()).extracting(Map.Entry::getKey)
                .containsExactly("step_0_output", "step_1_output", "ticket");
        assertThat(root.at("/context/text_variables/step_0_output")).isEqualTo(output);
        assertThat(text(root.at("/context/text_variables/ticket"))).isEqualTo("fix it\nplease");
        assertThat(root.at("/context/variables").properties()).extracting(Map.Entry::getKey)
                .containsExactly("big", "count", "flag", "form", "items", "nothing", "ratio", "step_9_output");
        assertThat(root.at("/context/variables/step_9_output").asInt()).as("no text: inline").isEqualTo(9);
        assertThat(root.at("/context/variables/form/name").asText()).as("a map: inline").isEqualTo("Flo");
        assertThat(root.at("/context/variables/nothing").isNull()).as("null: inline").isTrue();
        assertThat(root.at("/steps/0").has("context_delta")).as("no delta").isFalse();
        assertThat(root.at("/context").has("step_output_variables")).isFalse();
        assertThat(root.at("/input_context/ticket").asText()).as("the input context: inline, as before")
                .isEqualTo("fix it\nplease");
        assertThat(new String(RunSnapshotJson.write(withStepOutputVariables(), texts), StandardCharsets.UTF_8))
                .doesNotContain("built");
    }

    /**
     * A large text in a variable is one blob, whatever else names it, and the state holds only its reference: so a
     * checkpoint grows with the number of variables, not with their texts.
     */
    @Test
    void write_holdsALargeTextVariableOnce_asABlob() throws Exception {
        String large = "a line of a large variable\n".repeat(40_000);
        Map<String, Object> variables = new HashMap<>();
        variables.put("report", large);
        variables.put("copy", large);
        variables.put("count", 3);
        RunSnapshot snapshot = new RunSnapshot(context(variables), List.of(), Map.of());
        int stored = texts.blobs().size();

        byte[] written = RunSnapshotJson.write(snapshot, texts);

        assertThat(written.length).isLessThan(1024);
        JsonNode root = JSON.readTree(written);
        assertThat(root.at("/context/text_variables/report")).isEqualTo(root.at("/context/text_variables/copy"));
        assertThat(text(root.at("/context/text_variables/report"))).isEqualTo(large);
        assertThat(root.at("/context/variables/count").asInt()).isEqualTo(3);
        assertThat(texts.blobs().size()).as("the large text, once").isEqualTo(stored + 1);
        assertThat(RunSnapshotJson.read(written, texts).context().variables()).containsEntry("report", large)
                .containsEntry("copy", large).containsEntry("count", 3);
    }

    /**
     * A value of the workflow's own that looks like a blob reference is a value: it stays inline, is read back as the
     * map it is, and names no text.
     */
    @Test
    void aValueThatLooksLikeAReference_staysAValue() throws Exception {
        Map<String, Object> lookalike = new LinkedHashMap<>();
        lookalike.put("sha256", "a".repeat(64));
        lookalike.put("size", 3);
        RunSnapshot snapshot = new RunSnapshot(context(Map.of("lookalike", lookalike)), List.of(), Map.of());

        byte[] written = RunSnapshotJson.write(snapshot, texts);

        assertThat(JSON.readTree(written).at("/context/variables/lookalike/size").asInt()).isEqualTo(3);
        assertThat(RunSnapshotJson.references(written)).isEmpty();
        assertThat(RunSnapshotJson.read(written, reference -> {
            throw new AssertionError("asked for " + reference);
        }).context().variables()).containsEntry("lookalike", lookalike);
    }

    @Test
    void references_nameEveryTextInOrder_eachBlobAsOftenAsTheSnapshotNamesIt() {
        List<RunSnapshotJson.Reference> references =
                RunSnapshotJson.references(RunSnapshotJson.write(withStepOutputVariables(), texts));

        assertThat(references).extracting(RunSnapshotJson.Reference::field).containsExactly(
                "steps[0].input", "steps[0].output", "context.block_outputs.build", "context.block_outputs.short",
                "context.previous_output", "context.text_variables.step_0_output",
                "context.text_variables.step_1_output", "context.text_variables.ticket");
        assertThat(references.get(1).blob()).isEqualTo(references.get(2).blob());
    }

    /** A text named in four places is asked for once. */
    @Test
    void read_asksForEachBlobOnce() {
        byte[] written = RunSnapshotJson.write(withStepOutputVariables(), texts);
        List<String> asked = new ArrayList<>();

        RunSnapshotJson.read(written, reference -> {
            asked.add(reference.field());
            return texts.text(reference);
        });

        assertThat(asked).containsExactly("steps[0].input", "steps[0].output", "context.block_outputs.short",
                "context.text_variables.step_1_output", "context.text_variables.ticket");
    }

    /** A text that cannot be had stops the read with what the source threw, not as a snapshot that is none. */
    @Test
    void read_passesOnWhatTheSourceThrows() {
        byte[] written = RunSnapshotJson.write(withStepOutputVariables(), texts);
        IllegalStateException gone = new IllegalStateException("blob gone");

        assertThatThrownBy(() -> RunSnapshotJson.read(written, reference -> {
            throw gone;
        })).isSameAs(gone);
    }

    /**
     * A snapshot as the first format wrote it, with its texts inside ({@code every-field-v1.json}, written by the code
     * before this format), is still read, asks for no text, and is written again in this format.
     */
    @Test
    void read_ofTheFirstFormat_givesBackEveryValue() throws Exception {
        byte[] first = fixture("/snapshots/every-field-v1.json");
        RunSnapshotJson.TextSource none = reference -> {
            throw new AssertionError("the first format refers to no text: " + reference);
        };
        RunSnapshot expected = everyFieldPopulated();

        RunSnapshot read = RunSnapshotJson.read(first, none);

        assertThat(read.context()).isEqualTo(expected.context());
        assertThat(read.inputContext()).isEqualTo(expected.inputContext());
        assertThat(read.steps()).usingRecursiveFieldByFieldElementComparator().isEqualTo(expected.steps());
        assertThat(RunSnapshotJson.references(first)).isEmpty();
        byte[] again = RunSnapshotJson.write(read, texts);
        assertThat(JSON.readTree(again).get("format").asInt()).isEqualTo(RunSnapshotJson.FORMAT);
        assertThat(RunSnapshotJson.read(again, texts).context()).isEqualTo(expected.context());
        assertThat(RunSnapshotJson.write(RunSnapshotJson.read(again, texts), texts)).isEqualTo(again);
    }

    /**
     * A snapshot as the second format wrote it ({@code every-field-v2.json}, written by the code before this format):
     * its variables {@code step_<n>_output} in {@code step_output_variables}, every other variable inline, and a
     * {@code context_delta} in each step, which is no state and is not read. It is still read, from the blobs of its
     * texts, and written again in this format.
     */
    @Test
    void read_ofTheSecondFormat_givesBackEveryValue() throws Exception {
        byte[] second = fixture("/snapshots/every-field-v2.json");
        List.of("make build", "built\nok", "ok", "").forEach(texts::store);
        RunSnapshot expected = withStepOutputVariables();

        RunSnapshot read = RunSnapshotJson.read(second, texts);

        assertThat(read.context()).isEqualTo(expected.context());
        assertThat(read.inputContext()).isEqualTo(expected.inputContext());
        assertThat(read.steps()).usingRecursiveFieldByFieldElementComparator().isEqualTo(expected.steps());
        assertThat(RunSnapshotJson.references(second)).extracting(RunSnapshotJson.Reference::field).containsExactly(
                "steps[0].input", "steps[0].output", "context.block_outputs.build", "context.block_outputs.short",
                "context.previous_output", "context.step_output_variables.step_0_output",
                "context.step_output_variables.step_1_output");
        byte[] again = RunSnapshotJson.write(read, texts);
        assertThat(JSON.readTree(again).get("format").asInt()).isEqualTo(RunSnapshotJson.FORMAT);
        assertThat(RunSnapshotJson.read(again, texts).context()).isEqualTo(expected.context());
        assertThat(RunSnapshotJson.write(RunSnapshotJson.read(again, texts), texts)).isEqualTo(again);
    }

    /** The second format is read as strictly as before: its step output variables are what their name says. */
    @Test
    void read_ofTheSecondFormat_isStrict() throws Exception {
        byte[] second = fixture("/snapshots/every-field-v2.json");
        List.of("make build", "built\nok", "ok", "").forEach(texts::store);
        Map<String, java.util.function.Consumer<ObjectNode>> damages = new LinkedHashMap<>();
        damages.put("step output variables missing", root -> context(root).remove("step_output_variables"));
        damages.put("a step output variable of another name", root -> stepOutputs(root)
                .set("ticket", stepOutputs(root).get("step_0_output")));
        damages.put("a variable twice", root -> ((ObjectNode) context(root).get("variables"))
                .put("step_0_output", "inline"));
        damages.put("a step output variable as text", root -> stepOutputs(root).put("step_0_output", "built"));
        for (var damage : damages.entrySet()) {
            ObjectNode root = (ObjectNode) JSON.readTree(second);
            damage.getValue().accept(root);

            assertThatThrownBy(() -> RunSnapshotJson.read(JSON.writeValueAsBytes(root), texts)).as(damage.getKey())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("Not a run snapshot");
        }
    }

    @Test
    void read_ofSomethingElse_isRefused() {
        assertThatThrownBy(() -> RunSnapshotJson.read("{\"steps\": []}".getBytes(StandardCharsets.UTF_8), texts))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RunSnapshotJson.read("not json".getBytes(StandardCharsets.UTF_8), texts))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RunSnapshotJson.references("[]".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void read_ofAnotherFormat_isRefused() throws Exception {
        for (JsonNode format : List.of(JSON.getNodeFactory().numberNode(0), JSON.getNodeFactory().numberNode(1),
                JSON.getNodeFactory().numberNode(4), JSON.getNodeFactory().textNode("3"),
                JSON.getNodeFactory().numberNode((double) RunSnapshotJson.FORMAT))) {
            ObjectNode root = (ObjectNode) JSON.readTree(RunSnapshotJson.write(everyFieldPopulated(), texts));
            root.set("format", format);
            byte[] json = JSON.writeValueAsBytes(root);

            assertThatThrownBy(() -> RunSnapshotJson.read(json, texts)).as(format.toString())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("Not a run snapshot")
                    .hasMessageContaining("format " + format);
            assertThatThrownBy(() -> RunSnapshotJson.references(json)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void read_isStrict_aValueOfTheWrongTypeIsRefused_notReadAsADefault() throws Exception {
        Map<String, java.util.function.Consumer<ObjectNode>> damages = new LinkedHashMap<>();
        damages.put("a taint flag as text", root -> taint(root).put("previous_output_tainted", "yes"));
        damages.put("a taint flag as a number", root -> taint(root).put("platform_in_prompt", 1));
        damages.put("a taint name as a number", root -> taint(root).putArray("untrusted_variables").add(1));
        damages.put("taint names as an object", root -> taint(root).putObject("tainted_outputs"));
        damages.put("the model text as a flag", root -> taint(root).put("model_text", true));
        damages.put("the model text without its outputs",
                root -> taint(root).putObject("model_text").put("previous_output", true).putArray("variables"));
        damages.put("the step counter as text", root -> context(root).put("next_step_index", "7"));
        damages.put("the step counter as a fraction", root -> context(root).put("next_step_index", 7.5));
        damages.put("a block output as a number", root -> context(root).putObject("block_outputs").put("b", 5));
        damages.put("a block output as text", root -> context(root).putObject("block_outputs").put("b", "out"));
        damages.put("the previous output as an object", root -> context(root).putObject("previous_output"));
        damages.put("the previous output as text", root -> context(root).put("previous_output", "ok"));
        damages.put("an output as text", root -> step(root).put("output", "built"));
        damages.put("an input as a number", root -> step(root).put("input", 1));
        damages.put("a reference without its size", root -> ((ObjectNode) step(root).get("output")).remove("size"));
        damages.put("a reference with a negative size", root -> ((ObjectNode) step(root).get("output"))
                .put("size", -1));
        damages.put("a reference with a short hash", root -> ((ObjectNode) step(root).get("output"))
                .put("sha256", "abc"));
        damages.put("a reference with a field more", root -> ((ObjectNode) step(root).get("output"))
                .put("text", "built"));
        damages.put("text variables missing", root -> context(root).remove("text_variables"));
        damages.put("text variables as a list", root -> context(root).putArray("text_variables"));
        damages.put("a text variable as text", root -> textVariables(root).put("ticket", "fix it"));
        damages.put("a text variable as null", root -> textVariables(root).putNull("ticket"));
        damages.put("a variable twice", root -> ((ObjectNode) context(root).get("variables"))
                .put("ticket", "inline"));
        damages.put("a variable twice, as null inline", root -> ((ObjectNode) context(root).get("variables"))
                .putNull("step_0_output"));
        damages.put("a remembered approval as a list", root -> context(root).putObject("remembered_approvals")
                .putArray("Deploy?"));
        damages.put("a step index as text", root -> step(root).put("step_index", "0"));
        damages.put("a block name as a number", root -> step(root).put("block_name", 5));
        damages.put("a status as a number", root -> step(root).put("status", 3));
        damages.put("a duration as text", root -> step(root).put("duration_ms", "1500"));
        damages.put("a time as a number", root -> step(root).put("started_at", 12));
        damages.put("an exit code as text", root -> ((ObjectNode) context(root).path("source_steps").path("last")
                .path("root")).put("exit_code", "0"));
        damages.put("a source step as text", root -> ((ObjectNode) context(root).path("source_steps"))
                .put("last", "build"));
        damages.put("the steps as an object", root -> root.putObject("steps"));
        damages.put("the variables as a list", root -> context(root).putArray("variables"));
        for (var damage : damages.entrySet()) {
            ObjectNode root = (ObjectNode) JSON.readTree(RunSnapshotJson.write(withStepOutputVariables(), texts));
            damage.getValue().accept(root);

            assertThatThrownBy(() -> RunSnapshotJson.read(JSON.writeValueAsBytes(root), texts)).as(damage.getKey())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("Not a run snapshot");
        }
    }

    /** The text of a reference the snapshot holds. */
    private String text(JsonNode reference) {
        return texts.text(new RunSnapshotJson.Reference("test", new BlobRef(reference.get("sha256").asText(),
                reference.get("size").asLong())));
    }

    private static byte[] fixture(String resource) throws Exception {
        try (InputStream in = RunSnapshotJsonTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return in.readAllBytes();
        }
    }

    private static ObjectNode stepOutputs(ObjectNode root) {
        return (ObjectNode) context(root).path("step_output_variables");
    }

    private static ObjectNode textVariables(ObjectNode root) {
        return (ObjectNode) context(root).path("text_variables");
    }

    private static ObjectNode context(ObjectNode root) {
        return (ObjectNode) root.path("context");
    }

    private static ObjectNode taint(ObjectNode root) {
        return (ObjectNode) context(root).path("taint");
    }

    private static ObjectNode step(ObjectNode root) {
        return (ObjectNode) root.path("steps").path(0);
    }

    private static RunSnapshot everyFieldPopulated() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("ticket", "fix it\nplease");
        variables.put("count", 42);
        variables.put("big", 9_000_000_000L);
        variables.put("ratio", 0.25);
        variables.put("flag", true);
        variables.put("nothing", null);
        variables.put("items", List.of("a", Map.of("nested", List.of(1, 2))));
        variables.put("form", Map.of("name", "Flo", "tags", List.of("x")));

        SourceStep original = new SourceStep("build", "step-0", 0, "shell", StepStatus.COMPLETED, 0, 1500L, null);
        SourceStep compressed = new SourceStep("short", "step-1", 1, "compress", StepStatus.COMPLETED, null, null,
                original);
        ContextState context = new ContextState(
                variables,
                new ContextState.Taint(Set.of("ticket"), Set.of("previous_chain_output"), Set.of("build", "short"),
                        true, true, true),
                Map.of("build", "built\nok", "short", "ok"),
                "ok",
                new ContextState.SourceSteps(Map.of("build", original, "short", compressed), Set.of("short"),
                        compressed),
                7,
                Map.of("gate_status", "RED", "gate_summary", Map.of("highest", "RED", "counts", List.of(1, 2))),
                Map.of("Deploy?", "Yes always"));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("blockIndex", 0);
        metadata.put("exitCode", 0);
        metadata.put("untrusted_instructions", List.of(Map.of("where", "output", "line", 3)));
        metadata.put("none", null);
        StepResult build = StepResult.builder()
                .id("step-0").stepIndex(0).blockName("build").blockType("shell")
                .status(StepStatus.COMPLETED).input("make build").output("built\nok")
                .startedAt(STARTED).completedAt(COMPLETED).durationMs(1500L)
                .metadata(metadata)
                .build();
        StepResult running = StepResult.builder().stepIndex(1).status(StepStatus.FAILED).metadata(null).build();

        return new RunSnapshot(context, List.of(build, running), Map.of("ticket", "fix it\nplease", "count", 42,
                "__untrusted_keys", List.of("ticket")));
    }

    /**
     * {@link #everyFieldPopulated} with the variables a run sets from its steps' outputs: {@code step_0_output} (the
     * text of a step), {@code step_1_output} (one more) and {@code step_9_output}, a number of the workflow's own.
     */
    private static RunSnapshot withStepOutputVariables() {
        RunSnapshot snapshot = everyFieldPopulated();
        Map<String, Object> variables = new HashMap<>(snapshot.context().variables());
        variables.put("step_0_output", "built\nok");
        variables.put("step_1_output", "");
        variables.put("step_9_output", 9);
        ContextState c = snapshot.context();
        return new RunSnapshot(new ContextState(variables, c.taint(), c.blockOutputs(), c.previousOutput(),
                c.sourceSteps(), c.nextStepIndex(), c.runMetadata(), c.rememberedApprovals()), snapshot.steps(),
                snapshot.inputContext());
    }

    private static ContextState context(Map<String, Object> variables) {
        return new ContextState(variables,
                new ContextState.Taint(Set.of(), Set.of(), Set.of(), false, false, false),
                Map.of(), null, new ContextState.SourceSteps(Map.of(), Set.of(), null), 0, Map.of(), Map.of());
    }

    /** A checkpoint's bytes are hashed into the record: the source steps are written by name, in one order. */
    @Test
    void write_listsTheSourceStepsSortedByName() throws Exception {
        Map<String, SourceStep> byName = new LinkedHashMap<>();
        for (String name : List.of("zeta", "mid", "alpha", "yak", "beta", "xi", "gamma", "nu", "delta", "omega")) {
            byName.put(name, new SourceStep(name, "step-" + name, 0, "shell", StepStatus.COMPLETED, 0, 1L, null));
        }
        ContextState context = new ContextState(Map.of(),
                new ContextState.Taint(Set.of(), Set.of(), Set.of(), false, false, false), Map.of(), null,
                new ContextState.SourceSteps(byName, Set.of(), null), 0, Map.of(), Map.of());

        JsonNode root = JSON.readTree(RunSnapshotJson.write(new RunSnapshot(context, List.of(), Map.of()), texts));

        List<String> written = new java.util.ArrayList<>();
        root.path("context").path("source_steps").path("by_name").fieldNames().forEachRemaining(written::add);
        assertThat(written).containsExactly("alpha", "beta", "delta", "gamma", "mid", "nu", "omega", "xi", "yak",
                "zeta");
    }

    /** A format number is compared as the number it is: one beyond the int range is no known format. */
    @Test
    void read_ofAFormatNumberBeyondTheIntRange_isRefused_notReadByItsLowBits() throws Exception {
        ObjectNode root = (ObjectNode) JSON.readTree(RunSnapshotJson.write(everyFieldPopulated(), texts));
        root.put("format", (1L << 32) + RunSnapshotJson.FORMAT);
        byte[] json = JSON.writeValueAsBytes(root);

        assertThatThrownBy(() -> RunSnapshotJson.read(json, texts)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Not a run snapshot").hasMessageContaining("is not known");
        assertThatThrownBy(() -> RunSnapshotJson.references(json)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void read_ofAReferenceWhoseSizeIsAFraction_isRefused() throws Exception {
        ObjectNode root = (ObjectNode) JSON.readTree(RunSnapshotJson.write(withStepOutputVariables(), texts));
        ((ObjectNode) step(root).get("output")).put("size", 8.5);
        byte[] json = JSON.writeValueAsBytes(root);

        assertThatThrownBy(() -> RunSnapshotJson.read(json, texts)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Not a run snapshot");
        assertThatThrownBy(() -> RunSnapshotJson.references(json)).isInstanceOf(IllegalArgumentException.class);
    }
}
