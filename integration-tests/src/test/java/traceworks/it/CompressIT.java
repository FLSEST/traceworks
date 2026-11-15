package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deterministic {@code compress} block (package {@code compress}): it runs no command and asks no model, filters
 * one earlier block's output through its stages, and a later step reads the compressed text by name.
 */
class CompressIT extends IntegrationTest {

    @Test
    void compressesAnEarlierOutput_withoutACommand() {
        Result run = traceworks.run("compress");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        String compressed = folder.output("errors");
        assertThat(compressed).startsWith("## log — COMPLETED").contains("Stages: lines.filter", "ERROR disk full")
                .doesNotContain("INFO", "still");
        int step = folder.stepIndex("errors");
        assertThat(folder.ofStep("step.command", step)).as("compress runs no command").isEmpty();
        assertThat(folder.ownEvents("step.started").stream().filter(e -> e.path("step").asInt() == step)
                .map(e -> e.path("mode").asText())).containsExactly("deterministic");
        JsonNode compression = folder.metadata("errors").path("compression");
        assertThat(compression.path("label").asText()).isEqualTo("log");
        assertThat(compression.path("inChars").asInt()).isGreaterThan(compression.path("outChars").asInt());
        // The source's own output stays as it was
        assertThat(folder.output("log")).contains("INFO start", "ERROR disk still full");
        assertThat(folder.output("count")).isEqualTo("1");
    }
}
