package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An {@code agentic} block against the fake CLIs (package {@code ai-agentic}, docs/cli.md "AI steps"): the fake plans one
 * action, the action makes a tool call, and the next reasoning call declares the goal reached. Every call of the block
 * carries the block's {@code tools}, and its tool calls are recorded as {@code ai.tool_call}.
 *
 * <p>The block's approval gate ({@code require_approval}) is not covered: it waits inside the step for up to 30 minutes
 * and never parks the run, so a test needs a second process that answers while the run is running.
 */
class AiAgenticIT extends AiIntegrationTest {

    /** About the loop of an agentic block and the record of its calls, not about the network: no proxy and no network per run. */
    @Override
    boolean behindTheEgressProxy() {
        return false;
    }

    @Test
    void theLoopRunsToItsEnd_andRecordsItsToolCalls() {
        Result run = traceworks.run("ai-agentic");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.status()).isEqualTo("COMPLETED");
        for (String step : List.of("claude_agent", "pi_agent")) {
            boolean claude = step.startsWith("claude");
            int index = folder.stepIndex(step);
            assertThat(folder.stepStatus(step)).as(step).isEqualTo("COMPLETED");
            assertThat(folder.output(step)).as(step).contains("[iteration 1]", "Exit code: 0",
                    "do the one thing", "[iteration 2 - ACHIEVED]: the fake reached the goal");
            assertThat(folder.metadata(step).path("agentic_goal_achieved").asBoolean(true)).isTrue();

            // reason, act, reason: three calls, every one with the block's tools
            List<JsonNode> requests = folder.ofStep("ai.request", index);
            assertThat(requests).as(step).hasSize(3);
            assertThat(requests).allSatisfy(r -> assertThat(r.path("tools").asText()).isEqualTo("read"));
            assertThat(folder.ofStep("step.command", index)).hasSize(3);
            assertThat(folder.ofStep("ai.response", index)).extracting(r -> r.path("exit_code").asInt())
                    .containsExactly(0, 0, 0);

            // the one tool call is the action's
            List<JsonNode> calls = folder.ofStep("ai.tool_call", index);
            assertThat(calls).as(step).hasSize(1);
            JsonNode call = calls.getFirst();
            assertThat(call.path("tool").asText()).isEqualTo(claude ? "Read" : "read");
            assertThat(folder.blob(call.path("arguments"))).contains(claude ? "/workspace/hello.txt" : "hello.txt");
            assertThat(folder.blob(call.path("result"))).isEqualTo("hello from the fake tool");
            assertThat(call.path("error").asBoolean(true)).isFalse();
        }
        // the reasoning calls of Claude take the turn limit and the system prompt file; its action carries neither
        List<String> commands = folder.ofStep("step.command", folder.stepIndex("claude_agent")).stream()
                .map(c -> folder.blob(c.path("command"))).toList();
        assertThat(commands.get(0)).contains("--max-turns", "--system-prompt-file");
        assertThat(commands.get(1)).doesNotContain("--max-turns", "--system-prompt-file", "--append-system-prompt-file");
        assertThat(commands.get(2)).contains("--max-turns");
    }
}
