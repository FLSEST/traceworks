package traceworks.core.engine;

import traceworks.core.model.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StepModesTest {

    private static BlockDefinition block(String type, String key, String value) {
        Map<String, Object> config = new HashMap<>();
        if (key != null) config.put(key, value);
        return BlockDefinition.builder().type(type).config(config).build();
    }

    @Test
    void agentic_plain_usesAi() {
        assertThat(StepModes.usesAi(block("agentic", null, null))).isEqualTo(true);
    }

    @Test
    void compress_ai_plain_usesAi() {
        assertThat(StepModes.usesAi(block("compress-ai", null, null))).isEqualTo(true);
    }

    @Test
    void simple_plain_usesAi() {
        assertThat(StepModes.usesAi(block("simple", null, null))).isEqualTo(true);
    }

    @Test
    void simple_execution_mode_shell_isDeterministic() {
        assertThat(StepModes.usesAi(block("simple", "execution_mode", "shell"))).isEqualTo(false);
    }

    @Test
    void conditional_execution_mode_ai_usesAi() {
        assertThat(StepModes.usesAi(block("conditional", "execution_mode", "ai"))).isEqualTo(true);
    }

    @Test
    void conditional_execution_mode_shell_isDeterministic() {
        assertThat(StepModes.usesAi(block("conditional", "execution_mode", "shell"))).isEqualTo(false);
    }

    @Test
    void retry_execution_mode_ai_usesAi() {
        assertThat(StepModes.usesAi(block("retry", "execution_mode", "ai"))).isEqualTo(true);
    }

    @Test
    void retry_execution_mode_shell_isDeterministic() {
        assertThat(StepModes.usesAi(block("retry", "execution_mode", "shell"))).isEqualTo(false);
    }

    @Test
    void while_execution_mode_ai_usesAi() {
        assertThat(StepModes.usesAi(block("while", "execution_mode", "ai"))).isEqualTo(true);
    }

    @Test
    void while_execution_mode_shell_isDeterministic() {
        assertThat(StepModes.usesAi(block("while", "execution_mode", "shell"))).isEqualTo(false);
    }

    @Test
    void foreach_execution_mode_ai_usesAi() {
        assertThat(StepModes.usesAi(block("foreach", "execution_mode", "ai"))).isEqualTo(true);
    }

    @Test
    void foreach_execution_mode_shell_isDeterministic() {
        assertThat(StepModes.usesAi(block("foreach", "execution_mode", "shell"))).isEqualTo(false);
    }

    @Test
    void approval_actor_ai_usesAi() {
        assertThat(StepModes.usesAi(block("approval", "actor", "ai"))).isEqualTo(true);
    }

    @Test
    void approval_actor_shadow_usesAi() {
        assertThat(StepModes.usesAi(block("approval", "actor", "shadow"))).isEqualTo(true);
    }

    @Test
    void approval_plain_isDeterministic() {
        assertThat(StepModes.usesAi(block("approval", null, null))).isEqualTo(false);
    }

    @Test
    void approval_actor_human_isDeterministic() {
        assertThat(StepModes.usesAi(block("approval", "actor", "human"))).isEqualTo(false);
    }

    @Test
    void human_input_actor_ai_usesAi() {
        assertThat(StepModes.usesAi(block("human-input", "actor", "ai"))).isEqualTo(true);
    }

    @Test
    void shell_plain_isDeterministic() {
        assertThat(StepModes.usesAi(block("shell", null, null))).isEqualTo(false);
    }

    @Test
    void compress_plain_isDeterministic() {
        assertThat(StepModes.usesAi(block("compress", null, null))).isEqualTo(false);
    }

    @Test
    void switch_plain_isDeterministic() {
        assertThat(StepModes.usesAi(block("switch", null, null))).isEqualTo(false);
    }

    @Test
    void sub_workflow_plain_isDeterministic() {
        assertThat(StepModes.usesAi(block("sub-workflow", null, null))).isEqualTo(false);
    }

    @Test
    void foreach_withWorkflowId_andNoExecutionMode_isDeterministic() {
        assertThat(StepModes.usesAi(block("foreach", "workflow_id", "./child"))).isEqualTo(false);
    }

    @Test
    void foreach_withABlankWorkflowId_isStillAnAiStep() {
        assertThat(StepModes.usesAi(block("foreach", "workflow_id", " "))).isEqualTo(true);
    }

    private static BlockDefinition switchOf(List<Map<String, Object>> caseBlocks, List<Map<String, Object>> fallback) {
        Map<String, Object> config = new HashMap<>();
        config.put("expression", "{{ kind }}");
        config.put("cases", List.of(Map.of("value", "a", "blocks", caseBlocks)));
        if (fallback != null) config.put("default", fallback);
        return BlockDefinition.builder().type("switch").config(config).build();
    }

    private static Map<String, Object> caseBlock(String type, Map<String, Object> config) {
        return Map.of("type", type, "name", "c", "config", config);
    }

    @Test
    void switch_withAnAiCaseBlock_usesAi() {
        assertThat(StepModes.usesAi(switchOf(List.of(caseBlock("shell", Map.of("command", "true")),
                caseBlock("simple", Map.of("prompt", "p"))), null))).isEqualTo(true);
    }

    @Test
    void switch_withAnAiDefaultBlock_usesAi() {
        assertThat(StepModes.usesAi(switchOf(List.of(caseBlock("shell", Map.of("command", "true"))),
                List.of(caseBlock("simple", Map.of("prompt", "p")))))).isEqualTo(true);
    }

    @Test
    void switch_whoseBlocksAreAllDeterministic_isDeterministic() {
        assertThat(StepModes.usesAi(switchOf(List.of(caseBlock("shell", Map.of("command", "true")),
                caseBlock("simple", Map.of("command", "true", "execution_mode", "shell"))),
                List.of(caseBlock("shell", Map.of("command", "false")))))).isEqualTo(false);
    }

    @Test
    void approval_withAnActorPlaceholder_countsAsAi() {
        assertThat(StepModes.usesAi(block("approval", "actor", "{{ who_decides }}"))).isEqualTo(true);
        assertThat(StepModes.usesAi(block("human-input", "actor", "{{who}}"))).isEqualTo(true);
    }

    @Test
    void conditional_execution_mode_bash_isDeterministic() {
        assertThat(StepModes.usesAi(block("conditional", "execution_mode", "bash"))).isEqualTo(false);
    }
}
