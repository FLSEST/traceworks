package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Images (packages {@code package-image} and {@code missing-image}, docs/cli.md "images"): a step runs in the package's
 * own image {@code ./images/probe}, which the suite built with {@code traceworks images build <package>}; a run never
 * builds or pulls, so a run that names an image that is not there is refused before it exists.
 */
class ImagesIT extends IntegrationTest {

    @Test
    void aStepRunsInThePackagesOwnImage() {
        Result run = traceworks.run("package-image");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.output("in_package_image")).isEqualTo("built from the package image probe");
        assertThat(folder.output("in_shell_image")).isEqualTo("no marker in the shell image");
        JsonNode own = folder.ofStep("step.command", folder.stepIndex("in_package_image")).getFirst();
        JsonNode shell = folder.ofStep("step.command", folder.stepIndex("in_shell_image")).getFirst();
        assertThat(own.path("image").asText()).isEqualTo("./images/probe");
        assertThat(shell.path("image").asText()).isEqualTo("shell");
        assertThat(own.path("image_id").asText()).startsWith("sha256:")
                .isNotEqualTo(shell.path("image_id").asText());

        List<JsonNode> images = List.copyOf(folder.manifest().path("images").valueStream().toList());
        assertThat(images).extracting(i -> i.path("reference").asText()).containsExactly("./images/probe", "shell");
        assertThat(images.get(0).path("id").asText()).isEqualTo(own.path("image_id").asText());
        assertThat(images.get(1).path("id").asText()).isEqualTo(shell.path("image_id").asText());
    }

    @Test
    void anImageThatIsNotThere_refusesTheRunBeforeItExists() {
        Result run = traceworks.run("missing-image");

        assertThat(run.code()).as(run.describe()).isEqualTo(6);
        assertThat(run.err()).contains("localhost/traceworks-it/never-built:none is missing",
                "traceworks images build");
        assertThat(traceworks.folders()).isEmpty();
    }
}
