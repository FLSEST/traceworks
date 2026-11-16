package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mark of an output that was not read to its end ({@code output_cut} in {@code step.command.finished},
 * docs/events.md), with a real container (package {@code output-cut}): a command that ends while a job it started
 * still writes. On the host that job holds the output past the bound, and the record marks the cut (docs/cli.md,
 * "Host execution"). A container ends with its command and everything the command started with it, so the client's
 * output ends, the step does not wait for the bound, and the record names no cut.
 */
class OutputCutIT extends IntegrationTest {

    @Test
    void aJobTheCommandLeavesBehind_endsWithTheContainer_soNoOutputIsCut() {
        Result run = traceworks.run("output-cut");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        List<JsonNode> ends = folder.events("step.command.finished");
        assertThat(ends).hasSize(2);
        assertThat(ends).as("every output was read to its end").noneMatch(end -> end.has("output_cut"));
        assertThat(folder.output("serve")).startsWith("early");
        assertThat(folder.output("plain")).isEqualTo("fine");
        Result show = traceworks.exec("show", run.runId());
        assertThat(show.out()).doesNotContain("output CUT");
    }
}
