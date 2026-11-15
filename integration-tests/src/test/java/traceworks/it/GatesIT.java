package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gates (package {@code gates}): {@code gate_status:} lines in a step's output, {@code checked_command}, which derives
 * a gate from the exit code, {@code on_failure: continue}, and {@code --fail-on-gate}.
 */
class GatesIT extends IntegrationTest {

    @Test
    void markersAndCheckedCommands_reportGates_andTheWorstIsTheRunsVerdict() {
        Result run = traceworks.run("gates");

        assertThat(run.code()).as(run.describe()).isZero();
        assertThat(run.out()).contains("gate    YELLOW (widgets)");
        RunFolder folder = traceworks.folder(run);
        List<JsonNode> gates = folder.ownEvents("gate.reported");
        assertThat(gates).extracting(e -> e.path("status").asText() + " " + e.path("category").asText())
                .containsExactly("YELLOW widgets", "GREEN build", "GREEN build");
        assertThat(folder.blob(gates.getFirst().path("description"))).isEqualTo("two widgets are old");
        JsonNode finished = folder.only("run.finished");
        assertThat(finished.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(finished.path("gate").path("status").asText()).isEqualTo("YELLOW");
        assertThat(folder.manifest().path("gate").path("status").asText()).isEqualTo("YELLOW");
    }

    @Test
    void aFailedCheckedCommand_isRed_andWithOnFailureContinueTheRunGoesOn() {
        Result run = traceworks.run("gates", "--input", "broken_exit=3");

        assertThat(run.code()).as(run.describe()).isEqualTo(1);
        assertThat(run.status()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(run.out()).contains("gate    RED (build)");
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.stepStatus("build_broken")).isEqualTo("FAILED");
        assertThat(folder.metadata("build_broken").path("checkedCommand").asBoolean()).isTrue();
        assertThat(folder.metadata("build_broken").path("exitCode").asInt()).isEqualTo(3);
        // on_failure: continue: the step after the failed one still runs
        assertThat(folder.output("after")).isEqualTo("still running");
        assertThat(folder.ofStep("gate.reported", folder.stepIndex("build_broken")))
                .extracting(e -> e.path("status").asText()).containsExactly("RED");
        assertThat(folder.only("run.finished").path("gate").path("status").asText()).isEqualTo("RED");
    }

    @Test
    void failOnGate_exits7_whenTheGateReachesTheLevel() {
        Result yellow = traceworks.run("gates", "--fail-on-gate", "yellow");
        assertThat(yellow.code()).as(yellow.describe()).isEqualTo(7);
        assertThat(yellow.status()).as("the gate does not change the run's status").isEqualTo("COMPLETED");

        Result red = traceworks.run("gates", "--fail-on-gate", "red");
        assertThat(red.code()).as("a yellow gate is below red: " + red.describe()).isZero();
    }
}
