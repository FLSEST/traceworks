package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code shell} steps and the values between them (package {@code placeholders}): an input from {@code --input}, the
 * output of an earlier step by index ({@code {{ step_1_output }}}) and by name ({@code {{ block.greet.output }}}),
 * {@code shell_escape_variables} and {@code run_if}.
 */
class PlaceholdersIT extends IntegrationTest {

    @Test
    void valuesReachLaterSteps_byIndexAndByName() {
        Result run = traceworks.run("placeholders", "--input", "name=Anna");

        assertThat(run.code()).as(run.describe()).isZero();
        assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(run.out()).as(run.describe()).contains("in containers (" + traceworks.environment().runtime());
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.output("greet")).isEqualTo("Hello, Anna");
        assertThat(folder.output("by_index")).isEqualTo("by index: second-output");
        assertThat(folder.output("by_name")).isEqualTo("by name: Hello, Anna");
        assertThat(folder.blob(folder.only("run.started").path("inputs"))).contains("\"name\"", "\"Anna\"");
        JsonNode greet = folder.ownEvents("step.started").getFirst();
        assertThat(greet.path("name").asText()).isEqualTo("greet");
        assertThat(greet.path("mode").asText()).isEqualTo("deterministic");
        assertThat(greet.path("uses")).extracting(JsonNode::asText).containsExactly("name");
    }

    @Test
    void theDefaultContextApplies_withoutAnInput() {
        Result run = traceworks.run("placeholders");

        assertThat(run.code()).as(run.describe()).isZero();
        assertThat(traceworks.folder(run).output("greet")).isEqualTo("Hello, world");
    }

    @Test
    void anInputIsInsertedAsItIs_unlessTheBlockEscapesVariables() {
        Result run = traceworks.run("placeholders", "--input", "payload=$(echo injected)");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        // An input from the command line is trusted: the shell runs what it says
        assertThat(folder.output("raw")).isEqualTo("raw: injected");
        // shell_escape_variables: true quotes every value, so the shell prints it
        assertThat(folder.output("escaped")).isEqualTo("escaped: $(echo injected)");
        assertThat(folder.blob(folder.ofStep("step.command", folder.stepIndex("escaped")).getFirst().path("command")))
                .isEqualTo("echo escaped: '$(echo injected)'");
    }

    @Test
    void runIf_skipsABlockWhoseTestFails_andRunsOneWhoseTestHolds() {
        Result run = traceworks.run("placeholders");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.stepStatus("skipped")).isEqualTo("SKIPPED");
        assertThat(folder.output("skipped")).isEqualTo("Skipped (run_if: exit 1)");
        // The test ran in a container, the block's command never did
        List<JsonNode> skippedCommands = folder.ofStep("step.command", folder.stepIndex("skipped"));
        assertThat(skippedCommands).hasSize(1);
        assertThat(folder.blob(skippedCommands.getFirst().path("command"))).isEqualTo("test \"world\" = nobody");
        assertThat(run.out()).doesNotContain("must-not-run\n");
        assertThat(folder.stepStatus("ran")).isEqualTo("COMPLETED");
        assertThat(folder.output("ran")).isEqualTo("run_if held");
        assertThat(folder.ofStep("step.command", folder.stepIndex("ran"))).hasSize(2);
    }
}
