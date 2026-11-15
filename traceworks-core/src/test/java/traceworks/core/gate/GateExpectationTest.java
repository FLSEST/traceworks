package traceworks.core.gate;

import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gates stay voluntary: a verdict is expected only where the workflow says so with {@code metadata.gate.expected:
 * true}. The text {@code gate_status} somewhere in a block's config expects nothing (docs/workflow-format.md).
 */
class GateExpectationTest {

    private static BlockDefinition block(Map<String, Object> config) {
        BlockDefinition block = new BlockDefinition();
        block.setType("simple");
        block.setConfig(config);
        return block;
    }

    private static WorkflowDefinition workflow(Map<String, Object> metadata, BlockDefinition... blocks) {
        return new WorkflowDefinition("wf-1", "Check", null, List.of(blocks), null, null, null, null, metadata,
                null, null, null, null);
    }

    private static Map<String, Object> gate(Object expected) {
        Map<String, Object> gate = new HashMap<>();
        gate.put("expected", expected);
        return Map.of("gate", gate);
    }

    @Test
    void theExplicitFlag_ordersAVerdict() {
        assertThat(GateExpectation.isExpected(workflow(gate(true), block(Map.of("command", "mvn test")))))
                .isTrue();
        assertThat(GateExpectation.isExpected(gate(true))).isTrue();
    }

    @Test
    void theFlagSetToFalse_ordersNothing() {
        assertThat(GateExpectation.isExpected(workflow(gate(false),
                block(Map.of("prompt", "End with gate_status: GREEN or RED"))))).isFalse();
    }

    @Test
    void mentioningGateStatusInACommand_noLongerCreatesAnExpectation() {
        assertThat(GateExpectation.isExpected(workflow(Map.of(),
                block(Map.of("command", "echo gate_status: GREEN"))))).isFalse();
        assertThat(GateExpectation.isExpected(workflow(Map.of(),
                block(Map.of("prompt", "At the end: gate_status: GREEN or RED"))))).isFalse();
    }

    @Test
    void hyphenAndUpperCaseSpellingInAPrompt_orderNothingEither() {
        assertThat(GateExpectation.isExpected(workflow(Map.of(),
                block(Map.of("prompt", "Close with **Gate-Status:** ..."))))).isFalse();
    }

    @Test
    void aMarkerInANestedBlock_ordersNothingEither() {
        Map<String, Object> inner = Map.of("prompt", "gate_status: GREEN");
        Map<String, Object> foreach = Map.of("items", List.of("a", "b"),
                "blocks", List.of(Map.of("type", "simple", "config", inner)));

        assertThat(GateExpectation.isExpected(workflow(Map.of(), block(foreach)))).isFalse();
    }

    @Test
    void anOrdinaryWorkflow_ordersNothing() {
        assertThat(GateExpectation.isExpected(workflow(Map.of(), block(Map.of("command", "mvn test"))))).isFalse();
    }

    @Test
    void emptyNullAndMalformedMetadata_orderNothing() {
        assertThat(GateExpectation.isExpected((WorkflowDefinition) null)).isFalse();
        assertThat(GateExpectation.isExpected((Map<String, Object>) null)).isFalse();
        assertThat(GateExpectation.isExpected(workflow(null))).isFalse();
        assertThat(GateExpectation.isExpected(Map.of("gate", "expected"))).isFalse();
        assertThat(GateExpectation.isExpected(gate(null))).isFalse();
        assertThat(GateExpectation.isExpected(gate("true"))).as("only a JSON boolean counts").isFalse();
        assertThat(GateExpectation.isExpected(gate(1))).isFalse();
    }
}
