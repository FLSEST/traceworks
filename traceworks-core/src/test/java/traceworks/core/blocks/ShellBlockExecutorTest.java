package traceworks.core.blocks;

import traceworks.core.context.ShellLineRefusedException;
import traceworks.core.port.Egress;
import traceworks.core.container.RunPaths;
import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.WorkflowExecutionException;
import traceworks.core.gate.GateStatusOutputParser;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.GateStatus;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.port.CommandRunner;
import traceworks.core.port.CommandRunner.CommandRequest;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.CommandRunner.Mount;
import traceworks.core.testing.ProcessCommandRunner;
import traceworks.core.util.ProcessTracking;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShellBlockExecutorTest {

    @Mock
    private CommandRunner commands;

    @Mock
    private ContainerToolStep containerToolStep;

    private ShellBlockExecutor executor;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        executor = new ShellBlockExecutor(containerToolStep);
        context = ExecutionContext.builder()
                .containerId("container-1")
                .workflowRunId("run-1")
                .commandRunner(commands)
                .build();
        context.setProjectPath("/test/project");
    }

    /** A command on the host, in the run's working directory. */
    private static CommandRequest onHost(String command, int timeoutSeconds) {
        return new CommandRequest(command, "/test/project", timeoutSeconds, null, null, null, false);
    }

    /**
     * A command in a container: it starts from the project, with the block's own mounts; the run's command runner adds
     * the workspace, package and scratch mounts.
     */
    private static CommandRequest inContainer(String command, String image, List<Mount> extraMounts) {
        return new CommandRequest(command, "/test/project", 300, null, image, extraMounts, false);
    }

    private static CommandResult result(String stdout, int exitCode, long durationMs) {
        return new CommandResult(exitCode, stdout, "", durationMs, false);
    }

    @Test
    void supports_shellType() {
        assertThat(executor.supports("shell")).isTrue();
        assertThat(executor.supports("simple")).isFalse();
        assertThat(executor.supports("retry")).isFalse();
    }

    @Test
    void execute_simpleCommand_success() {
        when(commands.run(onHost("echo hello", 300))).thenReturn(result("hello", 0, 50));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "echo hello")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("hello");
        assertThat(result.getDurationMs()).isEqualTo(50);
    }

    @Test
    void execute_failingCommand_returnsFailed() {
        when(commands.run(onHost("exit 1", 300))).thenReturn(result("error", 1, 10));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "exit 1")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
    }

    @Test
    void execute_templateResolution_success() {
        context.setVariable("name", "world");
        when(commands.run(onHost("echo world", 300))).thenReturn(result("world", 0, 10));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "echo {{ name }}")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("world");
    }

    @Test
    void execute_missingCommand_throwsException() {
        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>())
                .build();

        assertThatThrownBy(() -> executor.execute(block, context))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("command");
    }

    @Test
    void execute_customTimeout_respected() {
        when(commands.run(onHost("sleep 1", 60))).thenReturn(result("", 0, 1000));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "sleep 1", "timeout_seconds", 60)))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
    }

    @Test
    void execute_setsExecutionModeMetadata() {
        when(commands.run(onHost("echo test", 300))).thenReturn(result("test", 0, 5));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "echo test")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getMetadata()).containsEntry("executionMode", "shell");
        assertThat(result.getMetadata()).containsEntry("exitCode", 0);
    }

    @Test
    void execute_shellEscapeVariables_escapesValues() {
        context.setVariable("name", "it's a \"test\"");
        when(commands.run(onHost("echo 'it'\\''s a \"test\"'", 300))).thenReturn(result("ok", 0, 10));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "echo {{ name }}", "shell_escape_variables", true)))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
    }

    @Test
    void execute_shellEscapeVariables_default_doesNotEscape() {
        context.setVariable("name", "it's a \"test\"");
        when(commands.run(onHost("echo it's a \"test\"", 300))).thenReturn(result("ok", 0, 10));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "echo {{ name }}")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
    }

    @Test
    void execute_shellEscapeVariables_preventsInjection() {
        context.setVariable("input", "; rm -rf /");
        when(commands.run(onHost("process '; rm -rf /'", 300))).thenReturn(result("safe", 0, 10));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "process {{ input }}", "shell_escape_variables", true)))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
    }

    @Test
    void execute_shellEscapeVariables_envPrefixBeforeHeredoc_quotesOnlyTheShellLine() {
        // The value travels via an env assignment on the shell line; the quoted heredoc body must stay byte-identical.
        context.setVariable("project_id", "abc");
        when(commands.run(onHost("PROJECT_ID='abc' python3 - <<'PY'\nimport os\nprint(os.environ['PROJECT_ID'])\nPY",
                300))).thenReturn(result("abc", 0, 10));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of(
                        "command", "PROJECT_ID={{ project_id }} python3 - <<'PY'\nimport os\nprint(os.environ['PROJECT_ID'])\nPY",
                        "shell_escape_variables", true)))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
    }

    @Test
    void execute_containerImageProvided_runsInThatImage_fromTheProject() {
        when(commands.run(inContainer("echo hi", "python:3.12", List.of()))).thenReturn(result("hi", 0, 20));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "echo hi", "container_image", "python:3.12")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("executionMode", "container:python:3.12");
        verify(commands).run(isA(CommandRequest.class));
    }

    /** 74 is what a large script's runner exits with when bash cannot read its stdin. */
    @Test
    void execute_unreadableScriptExitCode_getsNoPermissionHint() {
        when(commands.run(onHost("echo hi", 300))).thenReturn(result("", 74, 5));

        StepResult result = executor.execute(BlockDefinition.builder().type("shell")
                .config(new HashMap<>(Map.of("command", "echo hi"))).build(), context);

        assertThat(result.getOutput()).contains("[RECOVERY] Exit 74").doesNotContain("chmod", "Permission denied");
    }

    /** The refusal holds for a command on the host as well, not only for one in a container. */
    @Test
    void execute_mountDockerSocket_isRefusedOnTheHostToo() {
        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "docker ps", "mount_docker_socket", true)))
                .build();

        assertThatThrownBy(() -> executor.execute(block, context))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("mount_docker_socket");
        verifyNoInteractions(commands);
    }

    @Test
    void execute_containerImageBlank_fallsBackToShell() {
        when(commands.run(onHost("echo hi", 300))).thenReturn(result("hi", 0, 10));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "echo hi", "container_image", "  ")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("executionMode", "shell");
    }

    /** Traceworks never mounts the Docker socket, and no setting allows it. */
    @Test
    void execute_mountDockerSocket_isRefused_beforeAnythingRuns() {
        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of(
                        "command", "docker ps",
                        "container_image", "automation-agent-devops:latest",
                        "mount_docker_socket", true
                )))
                .build();

        assertThatThrownBy(() -> executor.execute(block, context))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("mount_docker_socket");
        verifyNoInteractions(commands);
    }

    @Test
    void execute_mountsList_resolvedAndPassedToContainer() {
        context.setVariable("home", "/test/user");
        List<Mount> expectedMounts = List.of(new Mount("/test/user/data", "/data", true), new Mount("/tmp", "/tmp", false));
        when(commands.run(inContainer("ls /data", "python:3.12", expectedMounts))).thenReturn(result("files", 0, 15));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of(
                        "command", "ls /data",
                        "container_image", "python:3.12",
                        "mounts", List.of("{{ home }}/data:/data:ro", "/tmp:/tmp:rw")
                )))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
    }

    /**
     * A mount passes no shell that could quote a value: an untrusted value in its text (a webhook payload naming a host
     * path) is refused before anything runs, as in a tool step's arguments.
     */
    @Test
    void execute_untrustedValueInAMount_isRefused_beforeAnythingRuns() {
        context.setUntrustedVariable("data_dir", "/home/agent/.ssh");
        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of(
                        "command", "ls /data",
                        "container_image", "python:3.12",
                        "mounts", List.of("/tmp:/tmp:rw", "{{ data_dir }}:/data:ro")
                )))
                .build();

        assertThatThrownBy(() -> executor.execute(block, context))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessage("mounts must not contain untrusted values (untrusted input, tainted block output, platform"
                        + " data, text a model wrote): {{ data_dir }}:/data:ro");
        verifyNoInteractions(commands);
    }

    @Test
    void execute_nonZeroExit_outputContainsRecoveryHint() {
        when(commands.run(onHost("bad-cmd", 300))).thenReturn(result("error msg", 1, 10));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "bad-cmd")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("[RECOVERY]");
    }

    /** The runner registers the block's process with the run's tracker, so a cancel ends the block. */
    @Test
    void execute_endsWhenTheRunIsCancelled(@TempDir Path dir) throws Exception {
        assertEndsOnCancel(dir, Map.of());
    }

    private void assertEndsOnCancel(Path dir, Map<String, Object> extraConfig) throws Exception {
        ExecutionContext running = ExecutionContext.builder().workflowRunId("run-1")
                .commandRunner(new ProcessCommandRunner()).build();
        running.setProjectPath(dir.toString());
        Path started = dir.resolve("started");
        Map<String, Object> config = new HashMap<>(extraConfig);
        config.put("command", "touch '" + started + "'; sleep 30");
        config.put("timeout_seconds", 20);
        BlockDefinition block = BlockDefinition.builder().type("shell").config(config).build();
        AtomicReference<StepResult> result = new AtomicReference<>();

        Thread run = Thread.ofVirtual().start(() -> result.set(
                ProcessTracking.bind(running.processTracker(), () -> executor.execute(block, running))));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(started) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(started).as("command started").exists();
        running.cancel();
        run.join(10_000);

        assertThat(run.isAlive()).as("shell block still running after the cancel").isFalse();
        assertThat(result.get().getStatus()).isEqualTo(StepStatus.FAILED);
    }

    @Test
    void execute_untrustedVariableInTheCommand_isRefused() {
        context.setUntrustedVariable("branch", "main; rm -rf /");

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "git checkout {{ branch }}")))
                .build();

        assertThatThrownBy(() -> executor.execute(block, context))
                .isInstanceOf(ShellLineRefusedException.class);
    }

    @Test
    void execute_platformDataInTheCommand_isRefused() {
        // platform data carries text from earlier runs
        context.setUntrustedPlatformVariable("previous_report", "x; rm -rf /");

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "echo {{ previous_report }}")))
                .build();

        assertThatThrownBy(() -> executor.execute(block, context))
                .isInstanceOf(ShellLineRefusedException.class);
    }

    @Test
    void execute_trustedVariable_staysRawWithoutOptIn() {
        context.setVariable("branch", "main");
        when(commands.run(onHost("git checkout main", 300))).thenReturn(result("ok", 0, 10));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "git checkout {{ branch }}")))
                .build();

        executor.execute(block, context);

        verify(commands).run(onHost("git checkout main", 300));
    }

    /** The block's values reach the runner by name, next to the command, which holds none of them. */
    @Test
    void execute_values_areFilledIn_andHandedToTheRunnerNextToTheCommand() {
        context.setUntrustedVariable("branch", "main; rm -rf /");
        context.setVariable("repo", "tools");
        when(commands.run(any())).thenReturn(result("ok", 0, 10));
        BlockDefinition block = BlockDefinition.builder().type("shell").config(new HashMap<>(Map.of(
                "values", Map.of("BRANCH", "{{ branch }}", "NOTE", "of {{ repo }}: {{ branch }}\u0000"),
                "command", "git -C {{ repo }} checkout \"$BRANCH\""))).build();

        StepResult result = executor.execute(block, context);

        ArgumentCaptor<CommandRequest> request = ArgumentCaptor.forClass(CommandRequest.class);
        verify(commands).run(request.capture());
        assertThat(request.getValue().command()).isEqualTo("git -C tools checkout \"$BRANCH\"");
        assertThat(request.getValue().values()).containsOnly(Map.entry("BRANCH", "main; rm -rf /"),
                Map.entry("NOTE", "of tools: main; rm -rf /"));
        assertThat(result.getInput()).isEqualTo("git -C tools checkout \"$BRANCH\"");
    }

    @Test
    void execute_taintedPreviousOutputInTheCommand_isRefused() {
        context.setUntrustedVariable("branch", "main");   // webhook payload: the run is tainted
        context.recordOutput("scan", "x; id");

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "echo {{ previous_output }}")))
                .build();

        assertThatThrownBy(() -> executor.execute(block, context))
                .isInstanceOf(ShellLineRefusedException.class);
    }

    private StepResult runCheckedCommand(String scriptOutput, int exitCode) {
        when(commands.run(any())).thenReturn(result(scriptOutput, exitCode, 10));
        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of(
                        "command", "run-the-checks.sh", "checked_command", true,
                        "check_category", "build-check")))
                .build();
        return executor.execute(block, context);
    }

    @Test
    void execute_checkedCommandSucceedingWithoutOwnGate_prependsTheGreenGate() {
        assertThat(runCheckedCommand("all good", 0).getOutput())
                .startsWith("gate_status: GREEN\ngate_category: build-check\n"
                        + "gate_description: Command exit 0\n\n");
    }

    /**
     * A checked command the runner refused never ran: the gate is RED and says so, and nothing in the refusal counts
     * as the command's own verdict. A command that exits 125 by itself keeps the description of its exit code.
     */
    @Test
    void execute_checkedCommandThatDidNotRun_getsARedGateThatSaysSo() {
        String refusal = "image traceworks/shell:0123456789ab is not present; run traceworks images build";
        when(commands.run(any())).thenReturn(CommandResult.notRun(125, refusal, CommandRunner.OUTPUT_LIMIT_BYTES));
        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of(
                        "command", "run-the-checks.sh", "checked_command", true,
                        "check_category", "build-check")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).startsWith("gate_status: RED\ngate_category: build-check\n"
                + "gate_description: Command did not run (exit 125)\n\n" + refusal);
        assertThat(runCheckedCommand("docker: Error", 125).getOutput())
                .startsWith("gate_status: RED\ngate_category: build-check\ngate_description: Command exit 125\n");
    }

    @Test
    void execute_checkedCommandFailingWithoutOwnGate_prependsTheRedGate() {
        assertThat(runCheckedCommand("boom", 3).getOutput())
                .startsWith("gate_status: RED\ngate_category: build-check\n"
                        + "gate_description: Command exit 3\n\n");
    }

    /**
     * {@code GateStatusOutputParser} takes the first marker in the output, so a synthetic GREEN header used to
     * shadow the script's own worse verdict: a script that reports {@code gate_status: RED} and still exits 0 was
     * silently downgraded.
     */
    @Test
    void execute_checkedCommandExitZero_doesNotShadowTheScriptsOwnWorseGate() {
        String script = "gate_status: RED\ngate_category: public-claims\n"
                + "gate_description: working directory missing\n";

        StepResult result = runCheckedCommand(script, 0);

        var gate = GateStatusOutputParser.parse(result.getOutput()).orElseThrow();
        assertThat(gate.status()).isEqualTo(GateStatus.RED);
        assertThat(gate.category()).isEqualTo("public-claims");
        assertThat(result.getOutput()).doesNotContain("gate_status: GREEN");
        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("checkedCommand", true);
    }

    @Test
    void execute_checkedCommandFailing_stillEscalatesAMilderScriptGate() {
        StepResult result = runCheckedCommand("gate_status: YELLOW\ngate_category: lint\n", 2);

        var gate = GateStatusOutputParser.parse(result.getOutput()).orElseThrow();
        assertThat(gate.status()).isEqualTo(GateStatus.RED);
        assertThat(result.getOutput()).startsWith("gate_status: RED");
        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
    }

    @Test
    void execute_checkedCommandFailingWithItsOwnRed_keepsTheScriptsCategoryAndDescription() {
        StepResult result = runCheckedCommand(
                "gate_status: RED\ngate_category: tests\ngate_description: 3 tests red\n", 1);

        var gate = GateStatusOutputParser.parse(result.getOutput()).orElseThrow();
        assertThat(gate.status()).isEqualTo(GateStatus.RED);
        assertThat(gate.category()).isEqualTo("tests");
        assertThat(gate.description()).isEqualTo("3 tests red");
    }

    /**
     * A bare {@code gate_status:} line (for example one that travelled in from text the command echoed) keeps the
     * verdict but must not leave the step without a category: the block's own {@code check_category} fills the gaps.
     */
    @Test
    void execute_checkedCommandWithABareScriptGate_fillsInCategoryAndDescriptionFromTheBlock() {
        StepResult result = runCheckedCommand("Passed through:\ngate_status: YELLOW\nEnd\n", 0);

        var gate = GateStatusOutputParser.parse(result.getOutput()).orElseThrow();
        assertThat(gate.status()).isEqualTo(GateStatus.YELLOW);
        assertThat(gate.category()).isEqualTo("build-check");
        assertThat(gate.description()).isEqualTo("Command exit 0");
        assertThat(result.getOutput()).doesNotContain("gate_status: GREEN");
    }

    @Test
    void execute_checkedCommandWithScriptGateAndCategoryOnly_keepsTheCategoryAndAddsTheDescription() {
        StepResult result = runCheckedCommand("gate_status: RED\ngate_category: tests\n", 0);

        var gate = GateStatusOutputParser.parse(result.getOutput()).orElseThrow();
        assertThat(gate.status()).isEqualTo(GateStatus.RED);
        assertThat(gate.category()).isEqualTo("tests");
        assertThat(gate.description()).isEqualTo("Command exit 0");
    }

    /** {@code checked_command} runs with {@code set -e -o pipefail}: a failing stage of a pipeline fails the step. */
    @Test
    void execute_checkedCommandPipelineFailure_remainsAFailure(@TempDir Path dir) {
        ExecutionContext real = ExecutionContext.builder().workflowRunId("run").commandRunner(new ProcessCommandRunner())
                .build();
        real.setProjectPath(dir.toString());
        BlockDefinition block = BlockDefinition.builder().type("shell").config(Map.of(
                "command", "(echo compiler-failure; exit 7) | tail -10", "checked_command", true)).build();

        StepResult step = executor.execute(block, real);

        assertThat(step.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(step.getMetadata()).containsEntry("exitCode", 7);
        assertThat(step.getOutput()).contains("compiler-failure").startsWith("gate_status: RED");
    }

    @Test
    void execute_cleanRun_blockOutputStaysRaw() {
        context.recordOutput("scan", "a | b");
        when(commands.run(onHost("echo a | b", 300))).thenReturn(result("ok", 0, 10));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "echo {{ block.scan.output }}")))
                .build();

        assertThat(executor.execute(block, context).getInput()).isEqualTo("echo a | b");
    }

    /** A command that ran out of time exits 124, says so, and gets the timeout hint. */
    @Test
    void execute_timedOutCommand_exitsWith124_andGetsTheTimeoutHint() {
        when(commands.run(onHost("sleep 99", 5))).thenReturn(new CommandResult(137, "partial", "", 5000, true));

        StepResult result = executor.execute(BlockDefinition.builder().type("shell")
                .config(new HashMap<>(Map.of("command", "sleep 99", "timeout_seconds", 5))).build(), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getMetadata()).containsEntry("exitCode", 124);
        assertThat(result.getOutput()).startsWith("partial\nCommand timed out after 5 seconds")
                .contains("[RECOVERY] Timeout.");
    }

    /** A command whose output went beyond the limit fails, says so after what was kept, and gets the limit's hint. */
    @Test
    void execute_outputBeyondTheLimit_failsWithTheNote_andGetsTheLimitHint() {
        when(commands.run(onHost("yes", 300))).thenReturn(new CommandResult(153, "y\ny\n", "", 900, false,
                List.of("stdout")));

        StepResult result = executor.execute(BlockDefinition.builder().type("shell")
                .config(new HashMap<>(Map.of("command", "yes"))).build(), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getMetadata()).containsEntry("exitCode", 153);
        assertThat(result.getOutput()).startsWith("y\ny\n\nThe command's standard output exceeded 16 MiB;"
                + " Traceworks stopped it and kept the first 16 MiB\n\n[RECOVERY] Output limit")
                .contains("scratch directory");
    }

    /** The note and the hint name the limit the runner had, the operator's {@code output_limit_mib}. */
    @Test
    void execute_outputBeyondAnOperatorsLimit_namesThatLimit() {
        when(commands.run(onHost("yes", 300))).thenReturn(new CommandResult(153, "y\n", "", 900, false,
                List.of("stdout"), 4 * 1024 * 1024));

        StepResult result = executor.execute(BlockDefinition.builder().type("shell")
                .config(new HashMap<>(Map.of("command", "yes"))).build(), context);

        assertThat(result.getOutput()).startsWith("y\n\nThe command's standard output exceeded 4 MiB; Traceworks"
                + " stopped it and kept the first 4 MiB\n\n[RECOVERY] Output limit: a stream of the command went"
                + " beyond 4 MiB.").doesNotContain("16 MiB");
    }

    /** The container gets network only when the block asks for it ({@code network: true}). */
    @Test
    void execute_network_isPassedOnlyWhenTheBlockAsksForIt() {
        ArgumentCaptor<CommandRequest> captor = ArgumentCaptor.forClass(CommandRequest.class);
        when(commands.run(captor.capture())).thenReturn(result("ok", 0, 1));

        executor.execute(BlockDefinition.builder().type("shell").config(new HashMap<>(Map.of(
                "command", "curl x", "container_image", "img:1", "network", true))).build(), context);
        executor.execute(BlockDefinition.builder().type("shell").config(new HashMap<>(Map.of(
                "command", "curl x", "container_image", "img:1"))).build(), context);

        assertThat(captor.getAllValues()).extracting(CommandRequest::network).containsExactly(Egress.OPEN, Egress.NONE);
    }

    /** A container run as the engine sets it up: the step sees the fixed paths, the runner mounts the host ones. */
    private void inARun(boolean container) {
        context.setRunPaths(new RunPaths("/test/project", "/test/pkg", "/test/scratch", container));
        context.setVariable("project_path", container ? "/workspace" : "/test/project");
        context.setVariable("execution_path", container ? "/workspace" : "/test/project");
        context.setVariable("package_dir", container ? "/package" : "/test/pkg");
        context.setVariable("scratch_dir", container ? "/scratch" : "/test/scratch");
    }

    /**
     * The host side of a mount names a host directory, so path variables take their host values there; the
     * container side is where the step sees it, so they take the step's.
     */
    @Test
    void execute_mountHostSide_takesTheHostPaths_andTheContainerSide_theStepsPaths() {
        List<CommandRequest> seen = new ArrayList<>();
        when(commands.run(any())).thenAnswer(invocation -> {
            seen.add(invocation.getArgument(0));
            return result("ok", 0, 1);
        });
        for (boolean container : List.of(true, false)) {
            inARun(container);

            executor.execute(BlockDefinition.builder().type("shell").config(new HashMap<>(Map.of(
                    "command", "mvn package", "container_image", "maven:3",
                    "mounts", List.of("{{ project_path }}/target:/target:rw", "{{ package_dir }}/data:/data:ro",
                            "/srv/tools:{{ scratch_dir }}-tools:ro")))).build(), context);

            assertThat(seen.getLast().mounts()).as(container ? "container run" : "host run").containsExactly(
                    new Mount("/test/project/target", "/target", false), new Mount("/test/pkg/data", "/data", true),
                    new Mount("/srv/tools", container ? "/scratch-tools" : "/test/scratch-tools", true));
        }
    }

    /** In a container run a step without image runs in the shell image: its mounts apply, with their mode. */
    @Test
    void execute_withoutImage_inAContainerRun_passesItsMounts_andRefusesOneWithoutMode() {
        inARun(true);
        when(commands.run(new CommandRequest("ls /data", "/test/project", 300, null, null,
                List.of(new Mount("/srv", "/data", true)), false))).thenReturn(result("files", 0, 5));

        StepResult result = executor.execute(BlockDefinition.builder().type("shell").config(new HashMap<>(Map.of(
                "command", "ls /data", "mounts", List.of("/srv:/data:ro")))).build(), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        BlockDefinition withoutMode = BlockDefinition.builder().type("shell").config(new HashMap<>(Map.of(
                "command", "ls /data", "mounts", List.of("/srv:/data")))).build();
        assertThatThrownBy(() -> executor.execute(withoutMode, context))
                .isInstanceOf(WorkflowExecutionException.class).hasMessageContaining(":ro");
    }

    /** On the host a command has no mounts: a host run and a run without paths ignore them. */
    @Test
    void execute_withoutImage_onTheHost_ignoresItsMounts() {
        when(commands.run(onHost("ls /data", 300))).thenReturn(result("files", 0, 5));
        BlockDefinition block = BlockDefinition.builder().type("shell").config(new HashMap<>(Map.of(
                "command", "ls /data", "mounts", List.of("/srv:/data")))).build();

        assertThat(executor.execute(block, context).getStatus()).as("no run paths").isEqualTo(StepStatus.COMPLETED);
        inARun(false);
        assertThat(executor.execute(block, context).getStatus()).as("host run").isEqualTo(StepStatus.COMPLETED);
    }

    /** The workspace is read-only unless the block says {@code workspace_writable: true}, with or without image. */
    @Test
    void execute_workspaceWritable_isPassedOnlyWhenTheBlockAsksForIt() {
        ArgumentCaptor<CommandRequest> captor = ArgumentCaptor.forClass(CommandRequest.class);
        when(commands.run(captor.capture())).thenReturn(result("ok", 0, 1));

        executor.execute(BlockDefinition.builder().type("shell").config(new HashMap<>(Map.of(
                "command", "make", "container_image", "img:1", "workspace_writable", true))).build(), context);
        executor.execute(BlockDefinition.builder().type("shell").config(new HashMap<>(Map.of(
                "command", "make", "workspace_writable", true))).build(), context);
        executor.execute(BlockDefinition.builder().type("shell").config(new HashMap<>(Map.of(
                "command", "make", "container_image", "img:1"))).build(), context);

        assertThat(captor.getAllValues()).extracting(CommandRequest::writableWorkspace)
                .containsExactly(true, true, false);
    }

    @Test
    void toolMode_delegatesToContainerToolStep() {
        BlockDefinition block = BlockDefinition.builder().type("shell").name("trivy")
                .config(new HashMap<>(Map.of("container_image", "img:1", "container_args", List.of("fs"))))
                .build();
        StepResult expected = new StepResult();
        when(containerToolStep.execute(eq(block), isA(ContainerToolSpec.class), eq(context))).thenReturn(expected);

        assertThat(executor.execute(block, context)).isSameAs(expected);
        verifyNoInteractions(commands);
    }

    /** The editor's default shell config carries command "": that counts as unset, as in the analyzer. */
    @Test
    void toolMode_withBlankCommandFromTheEditorDefault_runsTheTool() {
        BlockDefinition block = BlockDefinition.builder().type("shell").name("trivy")
                .config(new HashMap<>(Map.of("command", "", "container_image", "img:1", "container_args", List.of("fs"))))
                .build();
        StepResult expected = new StepResult();
        when(containerToolStep.execute(eq(block), isA(ContainerToolSpec.class), eq(context))).thenReturn(expected);

        assertThat(executor.execute(block, context)).isSameAs(expected);
    }

    @Test
    void toolMode_withCommand_isRefused() {
        BlockDefinition block = BlockDefinition.builder().type("shell")
                .config(new HashMap<>(Map.of("command", "echo", "container_image", "img", "container_args", List.of())))
                .build();

        assertThatThrownBy(() -> executor.execute(block, context))
                .isInstanceOf(WorkflowExecutionException.class).hasMessageContaining("either 'command' or 'container_args'");
        verify(containerToolStep, never()).execute(any(), any(), any());
    }
}
