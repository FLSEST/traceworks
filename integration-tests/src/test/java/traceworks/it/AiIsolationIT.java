package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an AI container sees (package {@code ai-isolation}, docs/cli.md "AI steps", docs/events.md "step.command"): the
 * workspace read-only unless the block sets {@code workspace_writable: true}, the package read-only, the scratch
 * directory writable, and the network of its provider's host only ({@code AiEgressIT} shows what that means). The
 * fake CLI tries to write {@code /workspace/fake-wrote.txt} and says what happened.
 */
class AiIsolationIT extends AiIntegrationTest {

    @Test
    void theWorkspaceIsReadOnly_unlessTheBlockSaysOtherwise() {
        Result run = traceworks.run("ai-isolation");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        for (String step : new String[] {"claude_readonly", "pi_readonly"}) {
            assertThat(folder.output(step)).as(step).contains("write: failed").doesNotContain("write: ok");
            assertThat(mount(folder, step, "/workspace").path("read_only").asBoolean()).as(step).isTrue();
        }
        for (String step : new String[] {"claude_writable", "pi_writable"}) {
            assertThat(folder.output(step)).as(step).contains("write: ok");
            assertThat(mount(folder, step, "/workspace").path("read_only").asBoolean(true)).as(step).isFalse();
        }
        for (String step : new String[] {"claude_readonly", "pi_readonly", "claude_writable", "pi_writable"}) {
            JsonNode command = folder.ofStep("step.command", folder.stepIndex(step)).getFirst();
            assertThat(mount(folder, step, "/package").path("read_only").asBoolean()).as(step).isTrue();
            assertThat(mount(folder, step, "/scratch").path("read_only").asBoolean(true)).as(step).isFalse();
            assertThat(command.path("network").asText()).as("an AI call reaches listed hosts only").isEqualTo("hosts");
            assertThat(command.path("network_hosts")).as(step).extracting(JsonNode::asText)
                    .containsExactly("api.anthropic.com:443");
            assertThat(command.path("working_dir").asText()).isEqualTo("/workspace");
        }
        assertThat(traceworks.workspace().resolve("fake-wrote.txt")).as("only the writable steps wrote it")
                .exists().hasContent("written\n");
    }

    private static JsonNode mount(RunFolder folder, String step, String container) {
        for (JsonNode mount : folder.ofStep("step.command", folder.stepIndex(step)).getFirst().path("mounts")) {
            if (container.equals(mount.path("container").asText())) {
                return mount;
            }
        }
        throw new AssertionError("Step " + step + " has no mount at " + container);
    }
}
