package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code foreach} over static {@code items} and over the lines of {@code items_command}, {@code while} with a counter
 * in the scratch directory, and {@code retry} of a step that fails twice and then passes (package {@code loops}).
 */
class LoopsIT extends IntegrationTest {

    @Test
    void everyLoopRunsItsCommandAsOftenAsItShould() {
        Result run = traceworks.run("loops");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);

        assertThat(folder.output("static_items")).isEqualTo("[alpha]: item 0=alpha\n[beta]: item 1=beta\n"
                + "[gamma]: item 2=gamma");
        int staticStep = folder.stepIndex("static_items");
        List<JsonNode> started = folder.ofStep("foreach.item.started", staticStep);
        assertThat(started).extracting(e -> e.path("item_index").asInt()).containsExactly(0, 1, 2);
        assertThat(started).allSatisfy(e -> assertThat(e.path("total").asInt()).isEqualTo(3));
        assertThat(started).extracting(e -> folder.blob(e.path("item"))).containsExactly("alpha", "beta", "gamma");
        assertThat(folder.ofStep("foreach.item.finished", staticStep))
                .allSatisfy(e -> assertThat(e.path("status").asText()).isEqualTo("COMPLETED"));

        // items_command: the items are its output lines; the listing and each item are a command
        assertThat(folder.output("listed_items")).isEqualTo("[one]: listed one\n[two]: listed two");
        assertThat(folder.ofStep("foreach.item.started", folder.stepIndex("listed_items"))).hasSize(2);
        assertThat(folder.ofStep("step.command", folder.stepIndex("listed_items"))).hasSize(3);

        // while: the condition holds three times, then stops the loop
        assertThat(folder.output("count_up")).isEqualTo("[iteration 1]: pass 1\n[iteration 2]: pass 2\n"
                + "[iteration 3]: pass 3");
        assertThat(folder.metadata("count_up").path("iterationCount").asInt()).isEqualTo(3);

        // retry: two failed attempts, then one that passes
        assertThat(folder.output("flaky")).isEqualTo("attempt 3 passes");
        assertThat(folder.stepStatus("flaky")).isEqualTo("COMPLETED");
        assertThat(folder.metadata("flaky").path("attempts").asInt()).isEqualTo(3);
        assertThat(folder.metadata("flaky").path("maxAttempts").asInt()).isEqualTo(4);
        assertThat(folder.ofStep("step.command.finished", folder.stepIndex("flaky")))
                .extracting(e -> e.path("exit_code").asInt()).containsExactly(1, 1, 0);
    }
}
