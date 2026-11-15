package traceworks.core.blocks;

import traceworks.core.engine.WorkflowExecutionException;
import traceworks.core.port.CommandRunner.CommandRequest;
import traceworks.core.port.CommandRunner.Mount;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Where a shell command runs: the one place that builds the request of a shell block and of a wrapper's shell step. */
class ShellTargetTest {

    /** Mounts that must not be read: a block without an image never resolves its mounts. */
    private static final Supplier<List<String>> UNREAD = () -> {
        throw new AssertionError("the mounts were read");
    };

    private static Map<String, Object> config(Map<String, Object> entries) {
        return new HashMap<>(entries);
    }

    @Test
    void onTheHost_runsInTheProject_withTheBlocksNetwork() {
        ShellTarget target = ShellTarget.of(config(Map.of("network", true, "mounts", List.of("/data:/data"))), UNREAD, false);

        assertThat(target.request("make", "/project", 60))
                .isEqualTo(new CommandRequest("make", "/project", 60, null, null, null, true));
        assertThat(target.executionMode()).isEqualTo("shell");
    }

    @Test
    void blankImage_runsOnTheHost() {
        ShellTarget target = ShellTarget.of(config(Map.of("container_image", "  ")), UNREAD, false);

        assertThat(target.request("make", "/project", 60))
                .isEqualTo(new CommandRequest("make", "/project", 60, null, null, null, false));
        assertThat(target.executionMode()).isEqualTo("shell");
    }

    /** The run's command runner adds the workspace, package and scratch mounts; the target names only the block's. */
    @Test
    void inAContainer_startsFromTheProject_withTheExtraMounts() {
        ShellTarget target = ShellTarget.of(config(Map.of("container_image", "node:22")),
                () -> List.of("/data:/data:ro", "/cache:/root/.npm:rw"), false);

        assertThat(target.request("npm test", "/project", 300)).isEqualTo(new CommandRequest("npm test", "/project",
                300, null, "node:22", List.of(new Mount("/data", "/data", true),
                new Mount("/cache", "/root/.npm", false)), false));
        assertThat(target.executionMode()).isEqualTo("container:node:22");
    }

    @Test
    void inAContainer_withoutAProjectDirectory_hasOnlyTheExtraMounts() {
        ShellTarget target = ShellTarget.of(config(Map.of("container_image", "alpine:3")),
                () -> List.of("/d:/d:rw"), false);

        assertThat(target.request("ls", null, 10).mounts()).containsExactly(new Mount("/d", "/d", false));
    }

    @Test
    void workspaceWritable_reachesTheRequest_withAndWithoutImage() {
        ShellTarget inImage = ShellTarget.of(config(Map.of("container_image", "node:22", "workspace_writable", true)),
                List::of, false);
        ShellTarget noImage = ShellTarget.of(config(Map.of("workspace_writable", true)), UNREAD, false);
        ShellTarget readOnly = ShellTarget.of(config(Map.of("container_image", "node:22")), List::of, false);

        assertThat(inImage.request("npm ci", "/project", 60).writableWorkspace()).isTrue();
        assertThat(noImage.request("make", "/project", 60).writableWorkspace()).isTrue();
        assertThat(readOnly.request("npm ci", "/project", 60).writableWorkspace()).as("read-only by default")
                .isFalse();
    }

    /** In a container run a step without image runs in the shell image, so its mounts apply. */
    @Test
    void withoutImage_inAContainerRun_readsTheMounts() {
        ShellTarget target = ShellTarget.of(config(Map.of()), () -> List.of("/data:/data:ro"), true);

        assertThat(target.request("ls", "/project", 10)).isEqualTo(new CommandRequest("ls", "/project", 10, null,
                null, List.of(new Mount("/data", "/data", true)), false));
        assertThatThrownBy(() -> ShellTarget.of(config(Map.of()), () -> List.of("/data:/data"), true))
                .isInstanceOf(WorkflowExecutionException.class).hasMessageContaining(":ro");
    }

    /** A step gets the values of the secrets it declares as environment variables; nothing else of the run's. */
    @Test
    void secrets_reachTheRequestAsEnvironment() {
        Map<String, String> runSecrets = Map.of("DEPLOY_TOKEN", "deploy-token-value", "OTHER", "other-value-1");
        ShellTarget target = ShellTarget.of(config(Map.of("secrets", List.of("DEPLOY_TOKEN"))), UNREAD, false,
                runSecrets);

        CommandRequest request = target.request("./deploy", "/project", 60);

        assertThat(request.environment()).containsExactly(Map.entry("DEPLOY_TOKEN", "deploy-token-value"));
        assertThat(target.toString()).contains("DEPLOY_TOKEN").doesNotContain("deploy-token-value");
        assertThat(request.toString()).contains("DEPLOY_TOKEN").doesNotContain("deploy-token-value");
    }

    @Test
    void aSecretTheRunHasNoValueFor_isRefusedBeforeAnythingRuns() {
        assertThatThrownBy(() -> ShellTarget.of(config(Map.of("secrets", List.of("DEPLOY_TOKEN"))), UNREAD, false,
                Map.of()))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("DEPLOY_TOKEN");
        assertThatThrownBy(() -> ShellTarget.of(config(Map.of("secrets", List.of("PATH"))), UNREAD, false,
                Map.of("PATH", "/usr/bin:/bin:/x")))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void dockerSocket_isRefusedBeforeTheMountsAreRead() {
        Map<String, Object> config = config(Map.of("container_image", "docker:cli", "mount_docker_socket", true));

        assertThatThrownBy(() -> ShellTarget.of(config, UNREAD, false))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("mount_docker_socket");
    }

    @Test
    void malformedMount_isRefused() {
        Map<String, Object> config = config(Map.of("container_image", "alpine:3"));

        assertThatThrownBy(() -> ShellTarget.of(config, () -> List.of("/data"), false))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("'/data'");
    }
}
