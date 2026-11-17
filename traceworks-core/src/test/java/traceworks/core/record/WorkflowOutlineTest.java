package traceworks.core.record;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowOutlineTest {

    private static Optional<WorkflowOutline> of(String json) {
        return WorkflowOutline.of(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void outline_hasTheDescriptionAndOneLinePerBlock() {
        WorkflowOutline outline = of("""
                {"id":"wf","description":"Checks things.","blocks":[
                  {"type":"shell","name":"facts","config":{"command":"architecture facts --root .\\nsecond line"}},
                  {"type":"foreach","name":"verdicts","config":{"provider":"claude","model":"claude-opus-5-5",
                    "items_source":"command","items_command":"cat x","command":"judge"}},
                  {"type":"approval","config":{"question":"Go?"}}]}""").orElseThrow();

        assertThat(outline.description()).isEqualTo("Checks things.");
        assertThat(outline.blocks()).extracting(WorkflowOutline.Block::position, WorkflowOutline.Block::name,
                        WorkflowOutline.Block::type, WorkflowOutline.Block::provider, WorkflowOutline.Block::model,
                        WorkflowOutline.Block::what)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0, "facts", "shell", null, null, "architecture facts --root ."),
                        org.assertj.core.groups.Tuple.tuple(1, "verdicts", "foreach", "claude", "claude-opus-5-5", "judge"),
                        org.assertj.core.groups.Tuple.tuple(2, null, "approval", null, null, "Go?"));
    }

    @Test
    void what_isCutAtTheLimit() {
        String what = of("{\"blocks\":[{\"type\":\"shell\",\"config\":{\"command\":\"" + "x".repeat(200) + "\"}}]}")
                .orElseThrow().blocks().getFirst().what();

        assertThat(what).hasSize(WorkflowOutline.WHAT_LIMIT).endsWith("…");
    }

    /** A definition the engine would refuse still shows its shape: the outline is for reading, not for running. */
    @Test
    void aLenientRead_takesWhatIsThere() {
        assertThat(of("{}").orElseThrow().blocks()).isEmpty();
        assertThat(of("{\"blocks\":[{\"name\":\"odd\"}],\"description\":\"\"}").orElseThrow())
                .satisfies(outline -> {
                    assertThat(outline.description()).isNull();
                    assertThat(outline.blocks()).singleElement().satisfies(block -> {
                        assertThat(block.name()).isEqualTo("odd");
                        assertThat(block.type()).isNull();
                    });
                });
    }

    @Test
    void noJsonObject_isNoOutline() {
        assertThat(of("not json")).isEmpty();
        assertThat(of("[1]")).isEmpty();
    }

    /** A block that is no top-level block of a recorded definition, such as a case block, gets the same line. */
    @Test
    void aBlockHeldAsAMap_getsTheSameLineAsOneOfARecordedDefinition() {
        WorkflowOutline.Block block = WorkflowOutline.block(3, "judge", "simple", java.util.Map.of("provider", "pi",
                "model", "big", "command", "Judge this.\nAnd that.", "web", "Search"));

        assertThat(block).isEqualTo(of("""
                {"blocks":[0,1,2,{"type":"simple","name":"judge","config":{"provider":"pi","model":"big",
                  "command":"Judge this.\\nAnd that.","web":"Search"}}]}""").orElseThrow().blocks().get(3));
        assertThat(block.what()).isEqualTo("Judge this.");
        assertThat(WorkflowOutline.block(-1, null, null, null))
                .isEqualTo(new WorkflowOutline.Block(-1, null, null, null, null, null, null));
    }

    /** The type as {@code show} and the description print it: with the provider and model it names, and its web. */
    @Test
    void theShownType_namesProviderModelAndWeb() {
        assertThat(WorkflowOutline.block(0, "a", "simple", java.util.Map.of("provider", "pi", "model", "big",
                "web", "search")).shownType()).isEqualTo("simple (pi big) web search");
        assertThat(WorkflowOutline.block(0, "a", "simple", java.util.Map.of("model", "big")).shownType())
                .isEqualTo("simple (big)");
        assertThat(WorkflowOutline.block(0, "a", "shell", java.util.Map.of()).shownType()).isEqualTo("shell");
        assertThat(WorkflowOutline.block(0, "a", null, null).shownType()).isEqualTo("?");
    }

    /** The blocks of the case a switch step ran, by the case its metadata names: what show prints under the step. */
    @Test
    void caseBlocks_areTheBlocksOfTheCaseThatRan_orOfTheDefault() {
        byte[] workflow = """
                {"blocks":[
                  {"type":"shell","name":"first","config":{"command":"true"}},
                  {"type":"switch","name":"route","config":{"expression":"{{ mode }}","cases":[
                    {"value":"fast|quick","blocks":[
                      {"type":"shell","name":"case_fast","config":{"command":"echo fast"}},
                      {"type":"simple","name":"case_ask","config":{"command":"Say","provider":"PI"}}]},
                    {"value":"slow","blocks":[{"type":"shell","config":{"command":"echo slow"}}]}],
                    "default":[{"type":"shell","name":"case_default","config":{"command":"echo d"}}]}}]}
                """.getBytes(StandardCharsets.UTF_8);

        assertThat(WorkflowOutline.caseBlocks(workflow, 1, "fast|quick"))
                .extracting(WorkflowOutline.Block::name, WorkflowOutline.Block::shownType)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("case_fast", "shell"),
                        org.assertj.core.groups.Tuple.tuple("case_ask", "simple (PI)"));
        assertThat(WorkflowOutline.caseBlocks(workflow, 1, "slow")).extracting(WorkflowOutline.Block::name)
                .containsExactly((String) null);
        assertThat(WorkflowOutline.caseBlocks(workflow, 1, "default")).extracting(WorkflowOutline.Block::name)
                .containsExactly("case_default");
        // No case of that value, no switch at that position, no such position, no JSON: nothing to name
        assertThat(WorkflowOutline.caseBlocks(workflow, 1, "none")).isEmpty();
        assertThat(WorkflowOutline.caseBlocks(workflow, 0, "fast|quick")).isEmpty();
        assertThat(WorkflowOutline.caseBlocks(workflow, 7, "slow")).isEmpty();
        assertThat(WorkflowOutline.caseBlocks("no json".getBytes(StandardCharsets.UTF_8), 1, "slow")).isEmpty();
    }
}
