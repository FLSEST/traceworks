package traceworks.core.blocks;

import traceworks.core.engine.WorkflowExecutionException;
import traceworks.core.port.CommandRunner.Mount;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The {@code mounts} entries of a block, {@code host:container:ro|rw}: the mode is always written out. */
class MountSpecsTest {

    @Test
    void parse_hostContainerAndMode() {
        assertThat(MountSpecs.parse(List.of("/a:/b:ro", "/e:/f:rw"))).containsExactly(
                new Mount("/a", "/b", true), new Mount("/e", "/f", false));
    }

    @Test
    void parse_blankEntriesAreSkipped() {
        assertThat(MountSpecs.parse(List.of(" ", ""))).isEmpty();
        assertThat(MountSpecs.parse(null)).isEmpty();
    }

    /** Docker makes a mount without a mode writable; Traceworks does not guess. */
    @Test
    void mountWithoutMode_isRefused() {
        assertThatThrownBy(() -> MountSpecs.parse(List.of("/a:/b")))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("'/a:/b'").hasMessageContaining(":ro").hasMessageContaining(":rw");
    }

    @Test
    void parse_malformedEntry_isRefused() {
        for (String bad : List.of("/a", ":/b:ro", "/a::rw", "/a:/b:z", "/a:/b:ro:x")) {
            assertThatThrownBy(() -> MountSpecs.parse(List.of(bad))).as(bad)
                    .isInstanceOf(WorkflowExecutionException.class)
                    .hasMessageContaining(bad).hasMessageContaining("host:container:ro|rw");
        }
    }

    @Test
    void dockerSocket_isRefusedWhenRequested() {
        MountSpecs.refuseDockerSocket(false);
        assertThatThrownBy(() -> MountSpecs.refuseDockerSocket(true))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("mount_docker_socket").hasMessageContaining("never mounts the Docker socket");
    }
}
