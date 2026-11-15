package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate lines in AI output (package {@code ai-gates}, docs/security.md "A gate from an AI step", docs/events.md
 * "gate.reported"): a {@code simple} step can set the run's gate with a {@code gate_status:} line, as a report step
 * delivers its verdict, and a model steered by what it reads could do the same; the output of a wrapper in AI mode
 * ({@code retry}) sets no gate, even with a worse one.
 */
class AiGatesIT extends AiIntegrationTest {

    /** About the gate an AI step sets, not about the network: no proxy and no network per run. */
    @Override
    boolean behindTheEgressProxy() {
        return false;
    }

    @Test
    void aSimpleStepSetsTheGate_aRetryInAiModeDoesNot() {
        Result run = traceworks.run("ai-gates");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.output("wrapped")).contains("gate_status: RED");
        List<JsonNode> reported = folder.ownEvents("gate.reported");
        assertThat(reported).extracting(e -> e.path("step").asInt()).containsExactly(folder.stepIndex("claude_report"),
                folder.stepIndex("pi_report"));
        assertThat(reported).extracting(e -> e.path("status").asText()).containsExactly("YELLOW", "GREEN");
        assertThat(reported).extracting(e -> e.path("category").asText()).containsExactly("fake", "fake");
        assertThat(folder.blob(reported.getFirst().path("description"))).isEqualTo("reported by the fake CLI");
        assertThat(folder.only("run.finished").path("gate").path("status").asText()).isEqualTo("YELLOW");
    }

    @Test
    void failOnGate_followsTheGateOfTheSimpleSteps_notTheWrappersRed() {
        Result yellow = traceworks.run("ai-gates", "--fail-on-gate", "yellow");
        Result red = traceworks.run("ai-gates", "--fail-on-gate", "red");

        assertThat(yellow.code()).as(yellow.describe()).isEqualTo(7);
        assertThat(red.code()).as(red.describe()).isZero();
    }
}
