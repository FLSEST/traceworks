package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code human-input} (package {@code human-input}): the question parks the run, {@code traceworks answer --value}
 * records the value, and after {@code traceworks resume} the next step reads it from the block's variable.
 */
class HumanInputIT extends IntegrationTest {

    @Test
    void parks_thenAnswerAndResume_handTheValueToTheNextStep() {
        Result run = traceworks.run("human-input");

        assertThat(run.code()).as(run.describe()).isEqualTo(5);
        String id = run.runId();
        assertThat(run.out()).contains("traceworks answer '" + id + "' --value '<text>'");
        RunFolder folder = traceworks.folder(id);
        JsonNode requested = folder.only("approval.requested");
        assertThat(requested.path("kind").asText()).isEqualTo("human-input");
        assertThat(requested.path("variable").asText()).isEqualTo("who");
        assertThat(requested.path("parks").asBoolean()).isTrue();
        assertThat(folder.blob(requested.path("question"))).isEqualTo("Who should be greeted?");
        assertThat(folder.only("run.parked").path("kind").asText()).isEqualTo("human-input");

        Result answer = traceworks.exec("answer", id, "--value", "Anna");
        assertThat(answer.code()).as(answer.describe()).isZero();
        Result resume = traceworks.exec("resume", id, "--no-input");

        assertThat(resume.code()).as(resume.describe()).isZero();
        assertThat(folder.output("ask_name")).isEqualTo("Anna");
        assertThat(folder.output("greet")).isEqualTo("Hello, Anna");
        assertThat(folder.ownEvents("approval.answered")).extracting(e -> e.path("kind").asText())
                .containsOnly("human-input");
        Result show = traceworks.exec("show", id);
        assertThat(show.out()).contains("answered step 0 (human-input) via cli");
    }
}
