package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An AI step fails as the docs say (docs/cli.md "AI steps" and "Output limit", docs/events.md "ai.response"): a CLI that
 * ends with a non-zero exit code, one that runs past the step's {@code timeout_seconds} (exit code 124) and one whose
 * stream goes beyond the operator's {@code output_limit_mib} (exit code 153). Packages {@code ai-marker} and
 * {@code ai-timeout}; every step has {@code on_failure: continue}, so Claude and Pi are both checked in one run.
 */
class AiFailuresIT extends AiIntegrationTest {

    private JsonNode response(RunFolder folder, String step) {
        return folder.ofStep("ai.response", folder.stepIndex(step)).getFirst();
    }

    @Test
    void aNonZeroExit_failsTheCall_withItsExitCodeAndMessage() {
        Result run = traceworks.run("ai-marker", "--input", "prompt=[fake:exit=3] please fail");

        assertThat(run.code()).as("on_failure: continue\n" + run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        for (String step : new String[] {"claude", "pi"}) {
            assertThat(folder.stepStatus(step)).as(step).isEqualTo("FAILED");
            JsonNode response = response(folder, step);
            assertThat(response.path("exit_code").asInt()).as(step).isEqualTo(3);
            assertThat(response.path("output").isNull()).isTrue();
            assertThat(folder.blob(response.path("error"))).as(step)
                    .contains(step.equals("claude") ? "Claude CLI wrote no result line" : "Pi CLI wrote no assistant"
                            + " message").contains("exiting with 3 on request");
            assertThat(folder.ofStep("step.command.finished", folder.stepIndex(step)).getFirst().path("exit_code")
                    .asInt()).isEqualTo(3);
        }
    }

    @Test
    void aCallPastItsTimeout_failsWithExitCode124() {
        Result run = traceworks.run("ai-timeout");

        assertThat(run.code()).as("on_failure: continue\n" + run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        for (String step : new String[] {"claude", "pi"}) {
            assertThat(folder.stepStatus(step)).as(step).isEqualTo("FAILED");
            JsonNode response = response(folder, step);
            assertThat(response.path("exit_code").asInt()).as(step).isEqualTo(124);
            assertThat(folder.blob(response.path("error"))).as(step).contains("timed out after 3 s");
            JsonNode finished = folder.ofStep("step.command.finished", folder.stepIndex(step)).getFirst();
            assertThat(finished.path("timed_out").asBoolean()).isTrue();
        }
    }

    @Test
    void aStreamBeyondTheOutputLimit_failsWithExitCode153() {
        traceworks.configure(Map.of("output_limit_mib", 1));

        Result run = traceworks.run("ai-marker", "--input", "prompt=[fake:flood] more than the limit");

        assertThat(run.code()).as("on_failure: continue\n" + run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.only("run.started").path("output_limit_mib").asInt()).isEqualTo(1);
        for (String step : new String[] {"claude", "pi"}) {
            assertThat(folder.stepStatus(step)).as(step).isEqualTo("FAILED");
            JsonNode response = response(folder, step);
            assertThat(response.path("exit_code").asInt()).as(step).isEqualTo(153);
            assertThat(folder.blob(response.path("error"))).as(step).contains("exceeded 1 MiB");
            JsonNode finished = folder.ofStep("step.command.finished", folder.stepIndex(step)).getFirst();
            assertThat(finished.path("truncated")).extracting(JsonNode::asText).containsExactly("stdout");
        }
    }
}
