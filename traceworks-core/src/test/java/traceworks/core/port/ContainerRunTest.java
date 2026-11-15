package traceworks.core.port;

import traceworks.core.port.CommandRunner.Mount;
import traceworks.core.port.ContainerRuntime.ContainerRun;
import traceworks.core.port.ContainerRuntime.RunDirectories;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A container run tells the runtime which of its mounts are the run's own directories (workspace, package, scratch)
 * and which the step declared: the runtime checks the declared ones against the forbidden host paths.
 */
class ContainerRunTest {

    private static final String IMAGE_ID = "sha256:" + "1".repeat(64);
    private static final Mount WORKSPACE = new Mount("/w", "/workspace", true);
    private static final Mount SCRATCH = new Mount("/s", "/scratch", false);
    private static final Mount DATA = new Mount("/data", "/data", true);

    private static ContainerRun run(List<Mount> mounts, RunDirectories directories) {
        return new ContainerRun("traceworks-r-1", "r", IMAGE_ID, null, List.of(), "/workspace", mounts, directories,
                false, Map.of(), null, 10, ContainerLimits.DEFAULTS);
    }

    @Test
    void declaredMounts_areTheMountsAfterTheRunsDirectories() {
        ContainerRun run = run(List.of(WORKSPACE, SCRATCH, DATA), new RunDirectories("/w", null, "/s"));

        assertThat(run.declaredMounts()).containsExactly(DATA);
    }

    /** The package again, read-only, inside a writable workspace is the run's own mount, which the runtime keeps. */
    @Test
    void thePackageAgainInsideTheWorkspace_isNoDeclaredMount() {
        Mount writable = new Mount("/w", "/workspace", false);
        Mount pkg = new Mount("/w/p", "/package", true);
        Mount again = new Mount("/w/p", "/workspace/p", true);
        RunDirectories directories = new RunDirectories("/w", "/w/p", "/s");

        assertThat(run(List.of(writable, pkg, SCRATCH, again, DATA), directories).declaredMounts())
                .containsExactly(DATA);
        assertThat(run(List.of(writable, pkg, SCRATCH, new Mount("/w/p", "/data", true)), directories)
                .declaredMounts()).as("outside the workspace it is a mount of the step's").hasSize(1);
        assertThat(run(List.of(writable, pkg, SCRATCH, new Mount("/w/p", "/workspace/p", false)), directories)
                .declaredMounts()).as("a writable one is a mount of the step's").hasSize(1);
    }

    /** Inside the workspace means below its path: a sibling whose name starts like the workspace's is outside. */
    @Test
    void thePackageAgainBesideTheWorkspace_isADeclaredMount() {
        RunDirectories directories = new RunDirectories("/w", "/w/p", "/s");
        Mount writable = new Mount("/w", "/workspace", false);
        Mount pkg = new Mount("/w/p", "/package", true);
        Mount beside = new Mount("/w/p", "/workspace-old/p", true);

        assertThat(run(List.of(writable, pkg, SCRATCH, beside), directories).declaredMounts()).containsExactly(beside);
        assertThat(run(List.of(new Mount("/w", "/workspace/", false), pkg, SCRATCH, new Mount("/w/p", "/workspace/p",
                true)), directories).declaredMounts()).as("a workspace path with a slash at its end").isEmpty();
    }

    /** What a log line shows of a container: the names of its environment variables, never their values. */
    @Test
    void theTextOfAContainerRun_namesItsVariables_neverTheirValues() {
        ContainerRun run = new ContainerRun("traceworks-r-1", "r", IMAGE_ID, null, List.of("make"), "/workspace",
                List.of(), null, false, Map.of("TOKEN", "s3cr3t-value", "HOME", "/root"), null, 10,
                ContainerLimits.DEFAULTS);

        assertThat(run.toString()).startsWith("ContainerRun[name=traceworks-r-1, runId=r, imageId=" + IMAGE_ID)
                .contains("environment=[HOME, TOKEN]").doesNotContain("s3cr3t-value").doesNotContain("/root");
    }

    @Test
    void withoutDirectories_everyMountIsDeclared() {
        assertThat(run(List.of(DATA), null).declaredMounts()).containsExactly(DATA);
        assertThat(run(List.of(DATA), new RunDirectories(null, " ", null)).declaredMounts()).containsExactly(DATA);
        assertThat(run(List.of(DATA), null).directories()).isEqualTo(RunDirectories.NONE);
    }

    /** A declared mount must never pass for one of the run's directories, which the runtime does not check. */
    @Test
    void mountsThatDoNotBeginWithTheRunsDirectories_areRefused() {
        RunDirectories directories = new RunDirectories("/w", null, "/s");

        assertThatThrownBy(() -> run(List.of(DATA, WORKSPACE, SCRATCH), directories))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("/w");
        assertThatThrownBy(() -> run(List.of(WORKSPACE), directories))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("/s");
    }
}
