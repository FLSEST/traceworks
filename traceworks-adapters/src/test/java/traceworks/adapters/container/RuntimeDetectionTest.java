package traceworks.adapters.container;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The configured runtime, or the first of podman and docker whose version probe succeeds. Fake clients
 * stand in for both.
 */
class RuntimeDetectionTest {

    @TempDir
    Path dir;

    private Path client(String name, String answer) throws IOException {
        Path script = Files.writeString(Files.createDirectories(dir.resolve(name + "-bin")).resolve(name),
                "#!/bin/sh\n" + answer + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }

    @Test
    void detect_takesTheFirstWhoseProbeSucceeds_withItsVersion() throws IOException {
        Path podman = client("podman", "echo 'cannot connect to Podman' >&2; exit 125");
        Path docker = client("docker", "echo 29.1.3");

        CliContainerRuntime runtime = RuntimeDetection.detectAmong(List.of(podman.toString(), docker.toString()), dir)
                .orElseThrow();

        assertThat(runtime.name()).isEqualTo("docker");
        assertThat(runtime.version()).isEqualTo("29.1.3");
    }

    /** Docker first: it is the runtime this release is tested with (Flo, 2026-11-11). */
    @Test
    void autoDetection_triesDockerBeforePodman() {
        assertThat(RuntimeDetection.RUNTIMES).containsExactly("docker", "podman");
    }

    @Test
    void detect_prefersTheFirstThatAnswers() throws IOException {
        Path podman = client("podman", "echo 5.4.0");
        Path docker = client("docker", "echo 29.1.3");

        assertThat(RuntimeDetection.detectAmong(List.of(podman.toString(), docker.toString()), dir))
                .hasValueSatisfying(runtime -> assertThat(runtime.name()).isEqualTo("podman"));
    }

    @Test
    void detect_noneAnswers_isEmpty() throws IOException {
        Path docker = client("docker", "exit 1");
        Path blank = client("podman", "echo");

        assertThat(RuntimeDetection.detectAmong(List.of(dir.resolve("missing").toString(), docker.toString(),
                blank.toString()), dir)).isEmpty();
    }

    @Test
    void detect_ofAnUnknownRuntime_isRefused() {
        assertThatThrownBy(() -> RuntimeDetection.detect("lxc", dir)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("podman").hasMessageContaining("docker");
        assertThatThrownBy(() -> RuntimeDetection.detect("/usr/bin/docker", dir))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
