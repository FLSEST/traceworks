package traceworks.core.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Which blocks make an AI call that reads the provider, and which settings a shell step reads. */
class RunRequirementsTest {

    /**
     * A foreach in AI mode makes an AI call per item unless it runs a sub-workflow per item. A blank
     * {@code workflow_id} names no workflow, so the loop still runs its command with the AI.
     */
    @Test
    void aForeachInAiMode_callsTheAi_unlessItNamesAWorkflowToRunPerItem() {
        assertThat(RunRequirements.callsAi("foreach", Map.of("execution_mode", "ai", "command", "Judge"), false))
                .isTrue();
        assertThat(RunRequirements.callsAi("foreach", Map.of("execution_mode", "ai", "workflow_id", "wf-child"),
                false)).isFalse();
        assertThat(RunRequirements.callsAi("foreach", Map.of("execution_mode", "ai", "command", "Judge",
                "workflow_id", "  "), false)).as("a blank workflow_id").isTrue();
        assertThat(RunRequirements.callsAi("foreach", Map.of("execution_mode", "shell", "command", "ls"), false))
                .isFalse();
    }

    /** Only {@code network: true} uses the network requirement: a step that says {@code false} asks for nothing. */
    @Test
    void networkFalse_isNoUseOfTheNetworkRequirement() {
        var definition = new traceworks.core.model.WorkflowDefinition("wf", "Wf", null,
                List.of(traceworks.core.model.BlockDefinition.builder().type("shell").name("build")
                        .config(new java.util.HashMap<>(Map.of("command", "make", "network", false))).build()),
                null, null, null, null, Map.of(), Map.of(), null, null, null);

        assertThat(RunRequirements.check(definition)).isEmpty();
    }

    /** A package image in a switch case is read from that case, so it is refused as a top-level one is. */
    @Test
    void aPackageImageOfAnotherPackage_inASwitchCase_isAProblemAtTheSwitch() {
        Map<String, Object> caseShell = Map.of("type", "shell", "name", "lint",
                "config", Map.of("command", "lint", "container_image", "./images/lint"));
        var definition = new traceworks.core.model.WorkflowDefinition("wf", "Wf", null,
                List.of(traceworks.core.model.BlockDefinition.builder().type("switch").name("pick")
                        .config(new java.util.HashMap<>(Map.of("value", "a",
                                "cases", List.of(Map.of("value", "a", "blocks", List.of(caseShell)))))).build()),
                null, null, null, null, Map.of(), Map.of(), null, null, null);

        assertThat(RunRequirements.packageImagesOfAnotherPackage(definition)).singleElement().satisfies(problem -> {
            assertThat(problem.rule()).isEqualTo("child-package-image");
            assertThat(problem.blockIndex()).isZero();
            assertThat(problem.nested().name()).isEqualTo("lint");
            assertThat(problem.message()).contains("./images/lint");
        });
    }
}
