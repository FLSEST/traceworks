package traceworks.core.port;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The resource limits of every container: set by the operator, never removed. */
class ContainerLimitsTest {

    @Test
    void defaults() {
        assertThat(ContainerLimits.DEFAULTS).isEqualTo(new ContainerLimits("4g", 1024, "2"));
    }

    @Test
    void positiveLimits_areAccepted() {
        assertThat(new ContainerLimits("512m", 1, "0.5")).extracting(ContainerLimits::memory, ContainerLimits::pids,
                ContainerLimits::cpus).containsExactly("512m", 1, "0.5");
        assertThat(new ContainerLimits("8G", 4096, "16").memory()).isEqualTo("8G");
        assertThat(new ContainerLimits("1073741824", 64, "1.25").cpus()).isEqualTo("1.25");
    }

    /** A limit of zero or below would lift the limit; anything else is no limit the runtime reads. */
    @Test
    void whatLiftsOrIsNoLimit_isRefused() {
        assertThatThrownBy(() -> new ContainerLimits("0", 1024, "2")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memory");
        assertThatThrownBy(() -> new ContainerLimits("4g --privileged", 1024, "2"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContainerLimits(null, 1024, "2")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContainerLimits("4g", 0, "2")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pids");
        assertThatThrownBy(() -> new ContainerLimits("4g", -1, "2")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContainerLimits("4g", 1024, "0")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cpus");
        assertThatThrownBy(() -> new ContainerLimits("4g", 1024, "0.0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContainerLimits("4g", 1024, "two")).isInstanceOf(IllegalArgumentException.class);
    }
}
