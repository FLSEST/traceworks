package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A block's own {@code mounts} (packages {@code mounts} and {@code login-mount}): {@code :ro} can be read but not
 * written, {@code :rw} can be written, and a login folder below the home directory ({@code ~/.ssh} of the sandbox's
 * fake home) is refused before anything starts.
 */
class MountsIT extends IntegrationTest {

    @Test
    void aMountBehavesAsItsModeSays() throws Exception {
        Path readOnly = Files.createDirectories(sandbox.resolve("ro-data"));
        Files.writeString(readOnly.resolve("given.txt"), "given-content\n");
        Path writable = Files.createDirectories(sandbox.resolve("rw-data"));

        Result run = traceworks.run("mounts", "--input", "ro_dir=" + readOnly, "--input", "rw_dir=" + writable);

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.output("read_ro")).isEqualTo("given-content");
        assertThat(folder.stepStatus("write_ro")).isEqualTo("FAILED");
        assertThat(folder.output("write_ro")).contains("Read-only file system");
        assertThat(readOnly.resolve("written.txt")).doesNotExist();
        assertThat(folder.output("write_rw")).isEqualTo("from-the-container");
        assertThat(writable.resolve("written.txt")).hasContent("from-the-container");

        // The record names each declared mount after the standard ones, with its real host path and its mode
        JsonNode ro = folder.ofStep("step.command", folder.stepIndex("read_ro")).getFirst().path("mounts").get(3);
        assertThat(ro.path("host").asText()).isEqualTo(readOnly.toRealPath().toString());
        assertThat(ro.path("container").asText()).isEqualTo("/data-ro");
        assertThat(ro.path("read_only").asBoolean()).isTrue();
        JsonNode rw = folder.ofStep("step.command", folder.stepIndex("write_rw")).getFirst().path("mounts").get(3);
        assertThat(rw.path("container").asText()).isEqualTo("/data-rw");
        assertThat(rw.path("read_only").asBoolean()).isFalse();
    }

    @Test
    void aMountOfALoginFolder_isRefused_andStartsNothing() throws Exception {
        Path ssh = Files.createDirectories(traceworks.userHome().resolve(".ssh"));
        Files.writeString(ssh.resolve("id_it"), "not a real key\n");

        Result run = traceworks.run("login-mount", "--no-check");   // the step itself refuses the mount

        assertThat(run.code()).as(run.describe()).isEqualTo(3);
        assertThat(run.status()).isEqualTo("FAILED");
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.output("read_login")).contains("~/.ssh:/login:ro is refused: it lies in ~/.ssh (credentials)")
                .doesNotContain("id_it");
        JsonNode finished = folder.ofStep("step.command.finished", folder.stepIndex("read_login")).getFirst();
        assertThat(finished.path("exit_code").asInt()).as("nothing was started").isEqualTo(125);
        // No container started from the image, so the record names none (docs/events.md, "step.command")
        JsonNode command = folder.ofStep("step.command", folder.stepIndex("read_login")).getFirst();
        assertThat(command.path("image").asText()).isEqualTo("shell");
        assertThat(command.path("image_id").isNull()).as(command.toString()).isTrue();
        assertThat(folder.manifest().path("images")).as("the manifest lists no image").isEmpty();

        Result check = traceworks.exec("check", traceworks.packageDir("login-mount").toString());
        assertThat(check.code()).as(check.describe()).isEqualTo(6);
        assertThat(check.out()).containsPattern("(?m)^ERROR +secret-mount +read_login");
    }
}
