package traceworks.it;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The limits and flags every container gets (package {@code limits}, docs/security.md "What the defaults protect"), as
 * the container itself sees them: its cgroup's memory, process and CPU limits (cgroup v2), the uid and gid of the
 * owner of the Traceworks home, no capabilities, no new privileges, a read-only root file system and a writable
 * {@code /tmp}. {@code container_limits} in {@code traceworks.json} changes the limits.
 */
class LimitsIT extends IntegrationTest {

    @Test
    void theDefaults_are4GiB_1024Processes_and2Cpus() {
        Result run = traceworks.run("limits");

        assertThat(run.code()).as(run.describe()).isZero();
        assertThat(traceworks.folder(run).output("cgroup")).isEqualTo("memory=4294967296 pids=1024 cpu=200000/100000");
    }

    @Test
    void containerLimits_inTraceworksJson_setTheLimits() {
        traceworks.configure(Map.of("container_limits", Map.of("memory", "256m", "pids", 64, "cpus", "0.5")));

        Result run = traceworks.run("limits");

        assertThat(run.code()).as(run.describe()).isZero();
        assertThat(traceworks.folder(run).output("cgroup")).isEqualTo("memory=268435456 pids=64 cpu=50000/100000");
    }

    @Test
    void theProcess_runsAsTheHomesOwner_withoutCapabilities_onAReadOnlyRoot() throws Exception {
        Result run = traceworks.run("limits");

        assertThat(run.code()).as(run.describe()).isZero();
        Object uid = Files.getAttribute(traceworks.home(), "unix:uid");
        Object gid = Files.getAttribute(traceworks.home(), "unix:gid");
        assertThat(traceworks.folder(run).output("process")).isEqualTo("uid=" + uid + " gid=" + gid
                + " caps=0000000000000000 nnp=1\nroot-read-only\ntmp-writable");
    }
}
