package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code simple} AI step with Claude and with Pi, against the fake CLIs (package {@code ai-simple}, docs/cli.md "AI
 * steps", docs/events.md "ai.request"): the prompt reaches the CLI, its answer is the step's output, the {@code ai.*}
 * events are in the record, {@code show} and {@code run.html} show prompt and answer, and {@code verify} is ok.
 */
class AiSimpleIT extends AiIntegrationTest {

    @Test
    void theAnswerOfEachFakeCli_isTheStepsOutput_andTheRecordHoldsTheCall() {
        Result run = traceworks.run("ai-simple", "--input", "name=Anna");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.status()).isEqualTo("COMPLETED");
        String claude = folder.output("claude_say");
        String pi = folder.output("pi_say");
        assertThat(claude).startsWith("fake-claude answered").contains("prompt: Say hello to Anna");
        assertThat(pi).startsWith("fake-pi answered").contains("prompt: Say hello to Anna");

        for (String step : new String[] {"claude_say", "pi_say"}) {
            int index = folder.stepIndex(step);
            String provider = step.startsWith("claude") ? "CLAUDE" : "PI";
            assertThat(folder.ofStep("step.command", index)).hasSize(1);
            JsonNode request = folder.ofStep("ai.request", index).getFirst();
            assertThat(request.path("provider").asText()).isEqualTo(provider);
            assertThat(request.path("tools").asText()).isEqualTo("none");
            assertThat(folder.blob(request.path("prompt"))).isEqualTo("Say hello to Anna");
            JsonNode response = folder.ofStep("ai.response", index).getFirst();
            assertThat(response.path("exit_code").asInt()).isZero();
            assertThat(response.path("input_tokens").asInt()).isEqualTo(12);
            assertThat(response.path("output_tokens").asInt()).isEqualTo(7);
            assertThat(folder.blob(response.path("output"))).isEqualTo(folder.output(step));
            assertThat(response.path("error").isNull()).isTrue();
            assertThat(folder.types().subList(folder.types().indexOf("ai.request"), folder.types().size()))
                    .containsSubsequence("ai.request", "step.command", "step.command.finished", "ai.response");
        }

        Result show = traceworks.exec("show", folder.id(), "--full");
        assertThat(show.code()).as(show.describe()).isZero();
        assertThat(show.out()).contains("Say hello to Anna", "fake-claude answered", "fake-pi answered");
        assertThat(folder.html()).contains("Say hello to Anna", "fake-claude answered", "fake-pi answered");
        Result verify = traceworks.exec("verify", folder.id());
        assertThat(verify.code()).as(verify.describe()).isZero();
        assertThat(verify.out()).contains("verified");
    }
}
