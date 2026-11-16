package traceworks.core.blocks;

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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * How the wrapper blocks run their commands: an inner shell step, a command whose result the block reads, an inner
 * AI step and an AI question.
 */
@ExtendWith(MockitoExtension.class)
class WrapperCommandsTest {

    private static final Instant T0 = Instant.parse("2026-11-03T09:00:00Z");

    @Mock
    private CommandRunner runner;

    @Mock
    private AiStepCall aiCall;

    private WrapperCommands commands;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        commands = new WrapperCommands(aiCall);
        context = ExecutionContext.builder().workflowRunId("run-1").projectPath("/test/project")
                .commandRunner(runner).clock(() -> T0).build();
    }

    private static BlockDefinition shellBlock(Map<String, Object> config) {
        return BlockDefinition.builder().type("shell").name("inner").config(new HashMap<>(config)).build();
    }

    private static CommandResult result(String stdout, int exitCode) {
        return new CommandResult(exitCode, stdout, "", 12, false);
    }

    @Test
    void shellStep_onTheHost_runsInTheProjectWithTheTimeout() {
        CommandRequest expected = new CommandRequest("echo hi", "/test/project", 77, null, null, null, false);
        when(runner.run(expected)).thenReturn(result("hi\n", 0));

        StepResult step = commands.shellStep(shellBlock(Map.of("command", "echo hi")), 3, 77, context);

        assertThat(step.getStepIndex()).isEqualTo(3);
        assertThat(step.getInput()).isEqualTo("echo hi");
        assertThat(step.getOutput()).isEqualTo("hi");
        assertThat(step.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(step.getStartedAt()).isEqualTo(T0);
        assertThat(step.getCompletedAt()).isEqualTo(T0);
        assertThat(step.getDurationMs()).isEqualTo(12L);
        assertThat(step.getMetadata()).containsExactlyInAnyOrderEntriesOf(Map.of("exitCode", 0, "executionMode", "shell"));
    }

    /** Unlike a shell block, an inner step adds no recovery hint and no checked-command gate. */
    @Test
    void shellStep_failure_keepsTheOutputAsItIs() {
        when(runner.run(new CommandRequest("false", "/test/project", 60, null, null, null, false)))
                .thenReturn(result("nope", 1));

        StepResult step = commands.shellStep(shellBlock(Map.of("command", "false", "checked_command", true)), 0, 60,
                context);

        assertThat(step.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(step.getOutput()).isEqualTo("nope");
        assertThat(step.getMetadata()).containsEntry("exitCode", 1);
    }

    @Test
    void shellStep_inAContainer_startsFromTheProjectWithTheExtraMountsNetworkAndWritableWorkspace() {
        CommandRequest expected = new CommandRequest("node --version", "/test/project", 300, null, "node:22",
                List.of(new Mount("/data", "/data", true)), true).withWritableWorkspace(true);
        when(runner.run(expected)).thenReturn(result("v22", 0));

        StepResult step = commands.shellStep(shellBlock(Map.of("command", "node --version",
                "container_image", "node:22", "mounts", List.of("/data:/data:ro"), "network", true,
                "workspace_writable", true)), 0, 300, context);

        assertThat(step.getOutput()).isEqualTo("v22");
        assertThat(step.getMetadata()).containsEntry("executionMode", "container:node:22");
    }

    /** In a container run an inner shell step without image runs in the shell image: its mounts apply. */
    @Test
    void shellStep_withoutImage_inAContainerRun_passesItsMounts() {
        context.setRunPaths(new RunPaths("/test/project", null, null, true));
        CommandRequest expected = new CommandRequest("ls /data", "/test/project", 60, null, null,
                List.of(new Mount("/data", "/data", true)), false);
        when(runner.run(expected)).thenReturn(result("files", 0));

        StepResult step = commands.shellStep(shellBlock(Map.of("command", "ls /data",
                "mounts", List.of("/data:/data:ro"))), 0, 60, context);

        assertThat(step.getOutput()).isEqualTo("files");
    }

    @Test
    void shellStep_timedOut_exitsWith124() {
        when(runner.run(new CommandRequest("sleep 99", "/test/project", 5, null, null, null, false)))
                .thenReturn(new CommandResult(137, "partial", "", 5000, true));

        StepResult step = commands.shellStep(shellBlock(Map.of("command", "sleep 99")), 0, 5, context);

        assertThat(step.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(step.getMetadata()).containsEntry("exitCode", 124);
        assertThat(step.getOutput()).endsWith("Command timed out after 5 seconds");
    }

    @Test
    void shellStep_malformedMount_isRefusedBeforeAnythingRuns() {
        BlockDefinition block = shellBlock(Map.of("command", "ls", "container_image", "alpine:3",
                "mounts", List.of("/data")));

        assertThatThrownBy(() -> commands.shellStep(block, 0, 60, context))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("/data");
        verifyNoInteractions(runner);
    }

    @Test
    void shellStep_dockerSocket_isRefusedBeforeAnythingRuns() {
        BlockDefinition block = shellBlock(Map.of("command", "docker ps", "container_image", "docker:cli",
                "mount_docker_socket", true));

        assertThatThrownBy(() -> commands.shellStep(block, 0, 60, context))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("mount_docker_socket");
        verifyNoInteractions(runner);
    }

    @Test
    void shellStep_withoutCommand_isRefused() {
        assertThatThrownBy(() -> commands.shellStep(shellBlock(Map.of()), 0, 60, context))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessage("Block has no 'command' in config");
        verifyNoInteractions(runner);
    }

    /** A condition or an items_command runs without an image, whatever the block's own image. */
    @Test
    void runCommand_runsInTheProjectWithoutAnImage() {
        when(runner.run(new CommandRequest("test -f x", "/test/project", 30, null, null, null, false)))
                .thenReturn(new CommandResult(1, "", "no such file", 4, false));

        ShellResult result = commands.runCommand("test -f x", 30, context, Map.of());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).isEqualTo("no such file");
    }

    @Test
    void aiStep_isTheAiCallOfAStep() {
        AiStepRequest request = AiStepRequest.builder().workingDirectory("/test/project").prompt("go").build();
        StepResult done = StepResult.builder().status(StepStatus.COMPLETED).output("went").build();
        when(aiCall.run(request, context)).thenReturn(done);

        assertThat(commands.aiStep(request, context)).isSameAs(done);
    }

    @Test
    void ask_returnsTheAnswerOfTheAiCall() {
        AiStepRequest request = AiStepRequest.builder().workingDirectory("/test/project").prompt("true?").build();
        AiStepCall.Answer answer = new AiStepCall.Answer("TRUE", 0, Map.of());
        when(aiCall.ask(request, context)).thenReturn(answer);

        assertThat(commands.ask(request, context)).isSameAs(answer);
        verify(aiCall).ask(request, context);
    }
}
