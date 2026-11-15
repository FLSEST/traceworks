package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The run's output budget, {@code run_output_budget_mib} in {@code traceworks.json} (docs/cli.md "Output limit",
 * package {@code output-budget}): a {@code foreach} whose four items print 768 KiB each fits the default budget, and
 * with a budget of 2 MiB its third item does not. The loop then stops, the step fails with a line that names the
 * budget, and the run ends there although the step says {@code on_failure: continue}. A budget below twice the output
 * limit is refused before anything runs.
 */
class OutputBudgetIT extends IntegrationTest {

    private static final int ITEM_BYTES = 768 * 1024;
    private static final String NOTE = "The output this run holds in memory would exceed its budget of 2 MiB;"
            + " Traceworks failed this step and ended the run";

    @Test
    void withinTheDefaultBudget_theRunCompletes() {
        Result run = traceworks.run("output-budget");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        JsonNode started = folder.only("run.started");
        assertThat(started.path("output_limit_mib").asInt()).isEqualTo(16);
        assertThat(started.path("run_output_budget_mib").asInt()).as("the default").isEqualTo(128);
        assertThat(folder.stepStatus("items")).isEqualTo("COMPLETED");
        assertThat(folder.output("items")).hasSize(4 * ("[a]: ".length() + ITEM_BYTES + 1) - 1);
        assertThat(folder.output("after")).isEqualTo("after");
    }

    @Test
    void anOperatorsBudget_failsTheStepThatWouldGoBeyondIt_andEndsTheRun() {
        traceworks.configure(Map.of("output_limit_mib", 1, "run_output_budget_mib", 2));

        Result run = traceworks.run("output-budget");

        assertThat(run.code()).as(run.describe()).isEqualTo(3);
        assertThat(run.out() + run.err()).contains("budget of 2 MiB");
        RunFolder folder = traceworks.folder(run);
        JsonNode started = folder.only("run.started");
        assertThat(started.path("output_limit_mib").asInt()).isEqualTo(1);
        assertThat(started.path("run_output_budget_mib").asInt()).isEqualTo(2);

        assertThat(folder.stepStatus("items")).isEqualTo("FAILED");
        String item = "x".repeat(ITEM_BYTES);
        assertThat(folder.output("items")).as("the two items that fit, then the note")
                .isEqualTo("[a]: " + item + "\n[b]: " + item + "\n" + NOTE);
        assertThat(folder.metadata("items").path("gateMarkers").asBoolean(true)).isFalse();
        List<JsonNode> items = folder.ofStep("foreach.item.finished", folder.stepIndex("items"));
        assertThat(items).extracting(e -> e.path("item_index").asInt()).as("d never runs").containsExactly(0, 1, 2);
        assertThat(items.getLast().path("output").path("size").asLong()).as("c, recorded whole in its own event")
                .isEqualTo(ITEM_BYTES);

        assertThat(folder.ownEvents("step.started")).extracting(e -> e.path("name").asText())
                .as("on_failure: continue does not keep the run going").containsExactly("items");
        JsonNode finished = folder.only("run.finished");
        assertThat(finished.path("status").asText()).isEqualTo("FAILED");
        assertThat(folder.blob(finished.path("error"))).isEqualTo("Step 0 (foreach) failed: " + NOTE);
    }

    @Test
    void aBudgetBelowTwiceTheOutputLimit_isRefusedWhenTheConfigLoads() {
        traceworks.configure(Map.of("run_output_budget_mib", 20));

        Result run = traceworks.run("output-budget");

        assertThat(run.code()).as(run.describe()).isEqualTo(6);
        assertThat(run.err()).contains("'run_output_budget_mib'", "at least twice 'output_limit_mib' (2 x 16 = 32 MiB)",
                "not 20");
        assertThat(traceworks.folders()).isEmpty();
    }
}
