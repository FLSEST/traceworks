package traceworks.core.blocks;

import traceworks.core.port.Egress;
import traceworks.core.container.RunPaths;
import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.WorkflowExecutionException;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.port.CommandRunner;
import traceworks.core.port.CommandRunner.CommandRequest;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.CommandRunner.Mount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContainerToolStepTest {

    private static final String ONE_HIGH = "{\"version\":\"2.1.0\",\"runs\":[{\"tool\":{\"driver\":{\"name\":\"Trivy\"}},"
            + "\"results\":[{\"ruleId\":\"CVE-1\",\"level\":\"error\",\"message\":{\"text\":\"Package: vite\"},"
            + "\"locations\":[{\"physicalLocation\":{\"artifactLocation\":{\"uri\":\"package-lock.json\"}}}]}]}]}";

    @Mock private CommandRunner commands;
    @TempDir Path cacheRoot;

    private ContainerToolStep step;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        step = new ContainerToolStep(cacheRoot);
        context = ExecutionContext.builder().containerId("c-1").workflowRunId("run-1").commandRunner(commands).build();
        context.setProjectPath("/test/project");
        lenient().when(commands.imageReference(any())).thenAnswer(call -> call.getArgument(0));
    }

    private StepResult run(Map<String, Object> config) {
        BlockDefinition block = BlockDefinition.builder().type("shell").name("trivy").config(new HashMap<>(config)).build();
        return step.execute(block, ContainerToolSpec.from(block.getConfig(), block.getName()), context);
    }

    private static Map<String, Object> sarifConfig() {
        return Map.of("container_image", "img:1", "container_args", List.of("fs", "/workspace"), "output_format", "sarif");
    }

    /** The mount at {@code containerPath}; null when the request has none. */
    private static Mount mountAt(CommandRequest request, String containerPath) {
        return request.mounts().stream().filter(m -> m.containerPath().equals(containerPath)).findFirst().orElse(null);
    }

    /** Fake container: writes {@code sarif} (if not null) into the mounted output directory and exits with {@code exit}. */
    private AtomicReference<CommandRequest> containerWrites(String sarif, int exit) {
        AtomicReference<CommandRequest> seen = new AtomicReference<>();
        when(commands.run(any())).thenAnswer(invocation -> {
            CommandRequest request = invocation.getArgument(0);
            seen.set(request);
            if (sarif != null) Files.writeString(Path.of(mountAt(request, "/out").hostPath()).resolve("trivy.sarif"), sarif);
            return new CommandResult(exit, "tool log", "", 42, false);
        });
        return seen;
    }

    @Test
    void rawMode_passesArgsWithoutShell_noOutputDir() {
        when(commands.run(any())).thenReturn(new CommandResult(0, "hello", "", 5, false));

        StepResult result = run(Map.of("container_image", "img:1", "container_args", List.of("fs", "/workspace")));

        ArgumentCaptor<CommandRequest> captor = ArgumentCaptor.forClass(CommandRequest.class);
        verify(commands).run(captor.capture());
        CommandRequest request = captor.getValue();
        assertThat(request.image()).isEqualTo("img:1");
        assertThat(request.command()).isNull();
        assertThat(request.entrypointArgs()).containsExactly("fs", "/workspace");
        assertThat(request.workingDirectory()).as("the project; the runner maps it into the container")
                .isEqualTo("/test/project");
        assertThat(request.mounts()).as("the runner adds the standard mounts").isEmpty();
        assertThat(request.writableWorkspace()).isFalse();
        assertThat(request.timeoutSeconds()).isEqualTo(300);
        assertThat(request.network()).isEqualTo(Egress.NONE);
        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("hello");
        assertThat(result.getInput()).isEqualTo("img:1 fs /workspace");
        assertThat(result.getMetadata()).containsEntry("executionMode", "container-tool:img:1")
                .containsEntry("outputFormat", "raw").containsEntry("exitCode", 0);
    }

    /**
     * The run's command runner mounts the workspace, the package and the scratch directory where the run's paths say;
     * the tool step names only what is its own: the output directory, the cache and the block's mounts.
     */
    @Test
    void toolMode_usesTheRunPaths() {
        AtomicReference<CommandRequest> seen = containerWrites(ONE_HIGH, 0);

        run(Map.of("container_image", "img:1", "container_args", List.of("fs", "."), "output_format", "sarif",
                "cache_dir", "/cache", "mounts", List.of("/srv/data:/data:ro"), "workspace_writable", true));

        CommandRequest request = seen.get();
        assertThat(request.mounts()).extracting(Mount::containerPath).containsExactly("/out", "/cache", "/data");
        assertThat(request.mounts()).noneMatch(m -> m.hostPath().equals("/test/project"));
        assertThat(request.workingDirectory()).isEqualTo("/test/project");
        assertThat(request.writableWorkspace()).isTrue();
    }

    /** A tool's mounts resolve their host side against the host paths, as a shell step's do. */
    @Test
    void mounts_takeTheHostPaths_onTheirHostSide() {
        context.setRunPaths(new RunPaths("/test/project", null, null, true));
        context.setVariable("project_path", "/workspace");
        when(commands.run(any())).thenReturn(new CommandResult(0, "", "", 1, false));

        run(Map.of("container_image", "img:1", "container_args", List.of("fs", "{{ project_path }}"),
                "mounts", List.of("{{ project_path }}/reports:/reports:rw")));

        ArgumentCaptor<CommandRequest> captor = ArgumentCaptor.forClass(CommandRequest.class);
        verify(commands).run(captor.capture());
        assertThat(captor.getValue().mounts()).containsExactly(new Mount("/test/project/reports", "/reports", false));
        assertThat(captor.getValue().entrypointArgs()).as("the tool sees the step's paths")
                .containsExactly("fs", "/workspace");
    }

    @Test
    void rawMode_nonZeroExit_isFailed() {
        when(commands.run(any())).thenReturn(new CommandResult(3, "boom", "", 5, false));

        assertThat(run(Map.of("container_image", "img:1", "container_args", List.of())).getStatus())
                .isEqualTo(StepStatus.FAILED);
    }

    @Test
    void sarifMode_validSarifWithExitOne_isCompleted() {
        containerWrites(ONE_HIGH, 1);

        StepResult result = run(sarifConfig());

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("FACT trivy_high: 1", "FACT trivy_exit_code: 1");
        assertThat(result.getMetadata()).containsEntry("measured", true).containsEntry("findings", 1)
                .containsEntry("factPrefix", "trivy").containsEntry("outputFormat", "sarif");
    }

    /**
     * A host run mounts the workspace at its host path, so a tool reports its files below that path: they are made
     * relative to it, as they are to {@code /workspace} in a container run.
     */
    @Test
    void sarifMode_inAHostRun_cutsOffTheWorkspacesHostPath() {
        context.setRunPaths(new RunPaths("/test/project", null, null, false));
        containerWrites(ONE_HIGH.replace("package-lock.json", "file:///test/project/web/package-lock.json"), 1);

        StepResult result = run(sarifConfig());

        assertThat(result.getOutput()).contains("web/package-lock.json").doesNotContain("/test/project/web");
    }

    @Test
    void sarifMode_inAContainerRun_cutsOffWorkspace() {
        context.setRunPaths(new RunPaths("/test/project", null, null, true));
        containerWrites(ONE_HIGH.replace("package-lock.json", "file:///workspace/web/package-lock.json"), 1);

        StepResult result = run(sarifConfig());

        assertThat(result.getOutput()).contains("web/package-lock.json").doesNotContain("/workspace/web");
    }

    @Test
    void sarifMode_noSarif_isFailedNotMeasured() {
        containerWrites(null, 0);

        StepResult result = run(sarifConfig());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("NOT MEASURED: trivy_findings (no SARIF file in /out");
        assertThat(result.getMetadata()).containsEntry("measured", false);
    }

    @Test
    void sarifMode_outputDirMountedAtOutputDir_andRemovedAfterwards() {
        AtomicReference<CommandRequest> seen = containerWrites(ONE_HIGH, 0);

        run(sarifConfig());

        Mount output = mountAt(seen.get(), "/out");
        assertThat(output.readOnly()).isFalse();
        assertThat(Path.of(output.hostPath()).getFileName().toString()).startsWith("traceworks-tool-");
        assertThat(Files.exists(Path.of(output.hostPath()))).isFalse();
    }

    /** A link the tool leaves in the output directory is removed, never its target. */
    @Test
    void cleanupRemovesALinkToAHostDirectory_notItsTarget(@TempDir Path host) throws Exception {
        Path keep = Files.writeString(host.resolve("keep.txt"), "host data");
        AtomicReference<Path> dir = new AtomicReference<>();
        when(commands.run(any())).thenAnswer(invocation -> {
            Path out = Path.of(mountAt(invocation.getArgument(0), "/out").hostPath());
            dir.set(out);
            Files.createSymbolicLink(out.resolve("escape"), host);
            Files.writeString(out.resolve("trivy.sarif"), ONE_HIGH);
            return new CommandResult(0, "", "", 1, false);
        });

        run(sarifConfig());

        assertThat(Files.exists(dir.get())).isFalse();
        assertThat(keep).hasContent("host data");
    }

    @Test
    void outputDirRemovedEvenWhenTheRunThrows() {
        AtomicReference<Path> dir = new AtomicReference<>();
        when(commands.run(any())).thenAnswer(invocation -> {
            dir.set(Path.of(mountAt(invocation.getArgument(0), "/out").hostPath()));
            throw new IllegalStateException("container runtime gone");
        });

        assertThatThrownBy(() -> run(sarifConfig())).isInstanceOf(IllegalStateException.class);
        assertThat(Files.exists(dir.get())).isFalse();
    }

    @Test
    void cacheDir_isPersistentPerImage() {
        when(commands.run(any())).thenReturn(new CommandResult(0, "", "", 1, false));

        run(Map.of("container_image", "registry:5000/aquasec/trivy:0.70.0", "container_args", List.of(), "cache_dir", "/cache"));

        ArgumentCaptor<CommandRequest> captor = ArgumentCaptor.forClass(CommandRequest.class);
        verify(commands).run(captor.capture());
        Mount cache = mountAt(captor.getValue(), "/cache");
        String folder = ContainerToolStep.cacheSlug("registry:5000/aquasec/trivy:0.70.0");
        assertThat(folder).startsWith("registry_5000_aquasec_trivy__");
        assertThat(cache).isEqualTo(new Mount(cacheRoot.resolve(folder).toString(), "/cache", false, true));
        assertThat(cacheRoot.resolve(folder)).isDirectory();
    }

    /**
     * A folder belongs to one image name. The slug turns "/" and ":" into "_", which a name may hold itself, so two
     * names could join to one text: a_b/c and a/b_c, host:5000/x and host_5000/x, a registry image named like the
     * slug of a package image. A name that holds anything but letters, digits, "." and "-" between its slashes gets
     * "__" and 12 hex digits of its hash at the end; a name without such a character keeps its folder.
     */
    @Test
    void cacheSlug_isOneFolderPerName_alsoWhereTheNameHoldsWhatTheSlugReplaces() {
        String one = ContainerToolStep.cacheSlug("traceworks-pkg/a_b/c:0123456789ab");
        String other = ContainerToolStep.cacheSlug("traceworks-pkg/a/b_c:0123456789ab");
        assertThat(one).isNotEqualTo(other).matches("traceworks-pkg_a_b_c__[0-9a-f]{12}");
        assertThat(other).matches("traceworks-pkg_a_b_c__[0-9a-f]{12}");
        assertThat(ContainerToolStep.cacheSlug("traceworks-pkg/a_b/c:ffffffffffff")).as("a rebuilt image").isEqualTo(one);

        java.util.Set<String> slugs = new java.util.HashSet<>();
        List<String> names = List.of("r/a_b/c", "r/a/b_c", "host:5000/x", "host_5000/x", "host/5000/x", "r/a/b/c",
                "traceworks-pkg/a/b", "traceworks-pkg_a_b", one, "traceworks-pkg/a/b/c__" + one.substring(one.length() - 12),
                "a//b", "a/b");
        for (String name : names) {
            assertThat(slugs.add(ContainerToolStep.cacheSlug(name + ":1"))).as(name).isTrue();
        }

        // What needs no suffix keeps the folder it had
        assertThat(ContainerToolStep.cacheSlug("traceworks-pkg/self-audit.build/facts:0123456789ab"))
                .isEqualTo("traceworks-pkg_self-audit.build_facts");
        assertThat(ContainerToolStep.cacheSlug("aquasec/trivy:0.70.0")).isEqualTo("aquasec_trivy");
        assertThat(ContainerToolStep.cacheSlug("ghcr.io/aquasecurity/trivy:0.70.0")).isEqualTo("ghcr.io_aquasecurity_trivy");
        assertThat(ContainerToolStep.cacheSlug("registry:5000/aquasec/trivy:0.70.0"))
                .as("a port is a character the slug replaces").matches("registry_5000_aquasec_trivy__[0-9a-f]{12}");
    }

    /** Without a configured tool cache directory a block that asks for one is refused, not run without its cache. */
    @Test
    void cacheDir_withoutAToolCache_isRefused() {
        ContainerToolStep withoutCache = new ContainerToolStep(null);
        BlockDefinition block = BlockDefinition.builder().type("shell").name("trivy").config(new HashMap<>(Map.of(
                "container_image", "img:1", "container_args", List.of(), "cache_dir", "/cache"))).build();

        assertThatThrownBy(() -> withoutCache.execute(block, ContainerToolSpec.from(block.getConfig(), "trivy"), context))
                .isInstanceOf(WorkflowExecutionException.class).hasMessageContaining("cache_dir");
        verify(commands, never()).run(any());
    }

    /** The folder is named after the reference the runner runs: a package image has a cache of its package. */
    @Test
    void cacheDir_ofAPackageImage_isNamedAfterTheResolvedReference() {
        when(commands.imageReference("./images/tool")).thenReturn("traceworks-pkg/9e3938e6-1/tool:f531a96629fd");
        when(commands.run(any())).thenReturn(new CommandResult(0, "", "", 1, false));

        run(Map.of("container_image", "./images/tool", "container_args", List.of(), "cache_dir", "/cache"));

        ArgumentCaptor<CommandRequest> captor = ArgumentCaptor.forClass(CommandRequest.class);
        verify(commands).run(captor.capture());
        Path dir = cacheRoot.resolve("traceworks-pkg_9e3938e6-1_tool");
        assertThat(mountAt(captor.getValue(), "/cache")).isEqualTo(new Mount(dir.toString(), "/cache", false, true));
        assertThat(dir).isDirectory();
    }

    @Test
    void cacheDir_ofAnImageThatCannotBeResolved_createsNoFolder() {
        when(commands.imageReference("./images/gone")).thenReturn(null);
        when(commands.run(any())).thenReturn(new CommandResult(125, "", "missing", 0, false));

        run(Map.of("container_image", "./images/gone", "container_args", List.of(), "cache_dir", "/cache"));

        assertThat(cacheRoot).isEmptyDirectory();
    }

    @Test
    void cacheSlug_ofAResolvedPackageReference_dropsTheTag() {
        assertThat(ContainerToolStep.cacheSlug("traceworks-pkg/9e3938e6-67dd/tool:f531a96629fd"))
                .isEqualTo("traceworks-pkg_9e3938e6-67dd_tool");
        assertThat(ContainerToolStep.cacheSlug("traceworks-pkg/9e3938e6-67dd/tool:aaaaaaaaaaaa"))
                .isEqualTo("traceworks-pkg_9e3938e6-67dd_tool");
    }

    @Test
    void cacheSlug_cannotLeaveTheCacheRoot() {
        assertThat(ContainerToolStep.cacheSlug("aquasec/trivy:0.70.0")).isEqualTo("aquasec_trivy");
        assertThat(ContainerToolStep.cacheSlug("img@sha256:abc")).isEqualTo("img");
        assertThat(ContainerToolStep.cacheSlug("..")).isEqualTo("_..");
    }

    @Test
    void untrustedPlaceholder_isRefused_beforeAnyContainerStarts() {
        context.setUntrustedVariable("branch", "--config=/etc/x");

        assertThatThrownBy(() -> run(Map.of("container_image", "img:1", "container_args", List.of("fs", "{{ branch }}"))))
                .isInstanceOf(WorkflowExecutionException.class).hasMessageContaining("untrusted");
        verify(commands, never()).run(any());
    }

    @Test
    void untrustedValueInAMount_isRefused_beforeAnyContainerStarts() {
        context.setUntrustedVariable("data_dir", "/home/agent/.ssh");

        assertThatThrownBy(() -> run(Map.of("container_image", "img:1", "container_args", List.of("fs", "."),
                "mounts", List.of("{{ data_dir }}:/data:ro"))))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageStartingWith("mounts must not contain untrusted values");
        verify(commands, never()).run(any());
    }

    @Test
    void platformData_isRefused() {
        context.setUntrustedPlatformVariable("previous_report", "x");

        assertThatThrownBy(() -> run(Map.of("container_image", "img:1", "container_args", List.of("{{ previous_report }}"))))
                .isInstanceOf(WorkflowExecutionException.class);
    }

    @Test
    void trustedPlaceholder_isResolvedVerbatim() {
        context.setVariable("target", "/test/project/sub");
        when(commands.run(any())).thenReturn(new CommandResult(0, "", "", 1, false));

        run(Map.of("container_image", "img:1", "container_args", List.of("fs", "{{ target }}")));

        ArgumentCaptor<CommandRequest> captor = ArgumentCaptor.forClass(CommandRequest.class);
        verify(commands).run(captor.capture());
        assertThat(captor.getValue().entrypointArgs()).containsExactly("fs", "/test/project/sub");
    }

    @Test
    void missingImage_orArgsNoList_isRefused() {
        assertThatThrownBy(() -> run(Map.of("container_args", List.of("x"))))
                .isInstanceOf(WorkflowExecutionException.class).hasMessageContaining("container_image");
        assertThatThrownBy(() -> run(Map.of("container_image", "img", "container_args", "x y")))
                .isInstanceOf(WorkflowExecutionException.class).hasMessageContaining("list");
    }

    /** Traceworks never mounts the Docker socket, in tool mode either. */
    @Test
    void dockerSocket_isRefused_beforeAnyContainerStarts() {
        assertThatThrownBy(() -> run(Map.of("container_image", "img:1", "container_args", List.of(),
                "mount_docker_socket", true)))
                .isInstanceOf(WorkflowExecutionException.class).hasMessageContaining("mount_docker_socket");
        verify(commands, never()).run(any());
    }

    @Test
    void extraMountsAndNetwork_reachTheRequest() {
        when(commands.run(any())).thenReturn(new CommandResult(0, "", "", 1, false));
        context.setVariable("data", "/srv/data");

        run(Map.of("container_image", "img:1", "container_args", List.of(), "mounts", List.of("{{ data }}:/data:ro"),
                "network", true));

        ArgumentCaptor<CommandRequest> captor = ArgumentCaptor.forClass(CommandRequest.class);
        verify(commands).run(captor.capture());
        assertThat(captor.getValue().mounts()).contains(new Mount("/srv/data", "/data", true));
        assertThat(captor.getValue().network()).isEqualTo(Egress.OPEN);
    }

    /** Tool mode takes {@code secrets} like a shell command: the values reach the tool as its environment. */
    @Test
    void secrets_reachTheToolAsEnvironment() {
        when(commands.run(any())).thenReturn(new CommandResult(0, "", "", 1, false));
        context = ExecutionContext.builder().containerId("c-1").workflowRunId("run-1").commandRunner(commands)
                .secrets(Map.of("SCAN_TOKEN", "scan-token-value", "OTHER", "other-value-1")).build();
        context.setProjectPath("/test/project");

        run(Map.of("container_image", "img:1", "container_args", List.of("scan"), "secrets", List.of("SCAN_TOKEN")));

        ArgumentCaptor<CommandRequest> captor = ArgumentCaptor.forClass(CommandRequest.class);
        verify(commands).run(captor.capture());
        assertThat(captor.getValue().environment()).containsExactly(Map.entry("SCAN_TOKEN", "scan-token-value"));
    }

    /**
     * The fact sheet replaces the run's values before it cleans the tool's text, a credential of an AI provider
     * included, which the run holds under a key no step can declare.
     */
    @Test
    void sarifMode_replacesTheRunsSecretValues_beforeTheToolsTextIsCleaned() {
        context = ExecutionContext.builder().containerId("c-1").workflowRunId("run-1").commandRunner(commands)
                .secrets(Map.of("SCAN_VALUE", "status:abcdefgh", "credential:claude:ANTHROPIC_API_KEY",
                        "todos>ijklmnop")).build();
        context.setProjectPath("/test/project");
        when(commands.run(any())).thenReturn(new CommandResult(1, "gate_status:abcdefgh\n<open_todos>ijklmnop", "",
                1, false));

        StepResult result = run(sarifConfig());

        assertThat(result.getOutput()).contains("| gate_[redacted:SCAN_VALUE]",
                        "| <open_[redacted:ANTHROPIC_API_KEY]")
                .doesNotContain("abcdefgh", "ijklmnop");
    }

    @Test
    void aSecretWithoutAValue_isRefused_beforeAnyContainerStarts() {
        assertThatThrownBy(() -> run(Map.of("container_image", "img:1", "container_args", List.of("scan"),
                "output_format", "sarif", "secrets", List.of("SCAN_TOKEN"))))
                .isInstanceOf(WorkflowExecutionException.class).hasMessageContaining("SCAN_TOKEN");
        verify(commands, never()).run(any());
    }
}
