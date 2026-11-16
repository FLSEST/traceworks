package traceworks.core.blocks;

import traceworks.core.testing.FakeCommandRunner;
import traceworks.core.ai.ToolPolicy;
import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.WorkflowExecutionException;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.testing.FakeAiProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The one inner step call of the wrapper blocks (foreach, while, conditional, retry, switch). */
@ExtendWith(MockitoExtension.class)
class InnerStepTest {

    @Mock
    private WrapperCommands commands;

    private ExecutionContext context;
    private final StepResult done = StepResult.builder().status(StepStatus.COMPLETED).output("ok").build();

    @BeforeEach
    void setUp() {
        context = ExecutionContext.builder().workflowRunId("run-1").projectPath("/tmp/project").build();
        context.setInstructionPrefix("house rules");
        context.setProvider("CLAUDE");
        context.setModel("sonnet");
        context.setEffort("low");
        context.setVariable("target", "auth");
    }

    private static BlockDefinition block(Map<String, Object> config) {
        return BlockDefinition.builder().type("foreach").config(new HashMap<>(config)).build();
    }

    @Test
    void aiStep_carriesTheResolvedPromptInstructionsBlockSettingsAndTimeout() {
        when(commands.aiStep(any(AiStepRequest.class), eq(context))).thenReturn(done);
        BlockDefinition wrapper = block(Map.of("provider", "PI", "model", "anthropic/opus", "effort", "high"));

        StepResult result = new InnerStep(wrapper, false, false, 300, 42)
                .run(commands, context, "review {{ target }}");

        assertThat(result).isSameAs(done);
        ArgumentCaptor<AiStepRequest> captor = ArgumentCaptor.forClass(AiStepRequest.class);
        verify(commands).aiStep(captor.capture(), eq(context));
        AiStepRequest request = captor.getValue();
        assertThat(request.workingDirectory()).isEqualTo("/tmp/project");
        assertThat(request.prompt()).isEqualTo("review auth");
        assertThat(request.stepIndex()).isZero();
        assertThat(request.systemPrompt()).isEqualTo("house rules");
        assertThat(request.providerId()).isEqualTo("PI");
        assertThat(request.model()).isEqualTo("anthropic/opus");
        assertThat(request.effort()).isEqualTo("high");
        assertThat(request.timeoutSeconds()).isEqualTo(42);
    }

    @Test
    void aiStep_withoutBlockSettings_fallsBackToTheWorkflowAndTheProviderTimeout() {
        when(commands.aiStep(any(AiStepRequest.class), eq(context))).thenReturn(done);

        new InnerStep(block(Map.of()), false, false, 300, null).run(commands, context, "go");

        ArgumentCaptor<AiStepRequest> captor = ArgumentCaptor.forClass(AiStepRequest.class);
        verify(commands).aiStep(captor.capture(), eq(context));
        assertThat(captor.getValue().providerId()).isEqualTo("CLAUDE");
        assertThat(captor.getValue().model()).isEqualTo("sonnet");
        assertThat(captor.getValue().effort()).isEqualTo("low");
        assertThat(captor.getValue().timeoutSeconds()).isNull();
    }

    /** The AI step of a wrapper carries the wrapper's {@code tools} and workspace setting, as a simple block does. */
    @Test
    void aiStep_ofAWrapper_carriesItsTools() {
        when(commands.aiStep(any(AiStepRequest.class), eq(context))).thenReturn(done);

        new InnerStep(block(Map.of("tools", "read", "workspace_writable", true)), false, false, 300, null)
                .run(commands, context, "go");

        ArgumentCaptor<AiStepRequest> captor = ArgumentCaptor.forClass(AiStepRequest.class);
        verify(commands).aiStep(captor.capture(), eq(context));
        assertThat(captor.getValue().tools()).isEqualTo(ToolPolicy.READ);
        assertThat(captor.getValue().writableWorkspace()).isTrue();
    }

    /** The AI step of a wrapper carries the wrapper's {@code web}, and with it the notice of untrusted input. */
    @Test
    void aiStep_ofAWrapper_carriesItsWeb() {
        when(commands.aiStep(any(AiStepRequest.class), eq(context))).thenReturn(done);

        new InnerStep(block(Map.of("web", "fetch", "web_hosts", List.of("*"))), false, false, 300, null)
                .run(commands, context, "go");

        ArgumentCaptor<AiStepRequest> captor = ArgumentCaptor.forClass(AiStepRequest.class);
        verify(commands).aiStep(captor.capture(), eq(context));
        assertThat(captor.getValue().web()).isEqualTo(new traceworks.core.port.WebAccess(
                traceworks.core.port.WebAccess.Mode.FETCH, List.of("*")));
        assertThat(captor.getValue().tools()).isEqualTo(ToolPolicy.NONE);
        assertThat(captor.getValue().systemPrompt()).contains("## Foreign content");
    }

    /** Without {@code tools} the AI step of a wrapper has none: nothing is allowed that the workflow does not name. */
    @Test
    void aiStep_ofAWrapper_withoutTools_runsWithNone() {
        when(commands.aiStep(any(AiStepRequest.class), eq(context))).thenReturn(done);

        new InnerStep(block(Map.of()), false, false, 300, null).run(commands, context, "go");

        ArgumentCaptor<AiStepRequest> captor = ArgumentCaptor.forClass(AiStepRequest.class);
        verify(commands).aiStep(captor.capture(), eq(context));
        assertThat(captor.getValue().tools()).isEqualTo(ToolPolicy.NONE);
        assertThat(captor.getValue().writableWorkspace()).isFalse();
    }

    /** A value the policy does not know fails the step before any call. */
    @Test
    void aiStep_ofAWrapper_withUnknownTools_isRefusedWithoutACall() {
        InnerStep step = new InnerStep(block(Map.of("tools", "all")), false, false, 300, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> step.run(commands, context, "go"))
                .isInstanceOf(traceworks.core.engine.WorkflowExecutionException.class)
                .hasMessageStartingWith("tools: \"all\" is not one of none, read, write or shell");
        org.mockito.Mockito.verifyNoInteractions(commands);
    }

    /** A {@code simple} block in a switch case is a simple block: it carries its own tools and workspace setting. */
    @Test
    void aiStep_ofASimpleSwitchCase_carriesItsTools() {
        when(commands.aiStep(any(AiStepRequest.class), eq(context))).thenReturn(done);
        BlockDefinition caseBlock = BlockDefinition.builder().type("simple").name("review")
                .config(new HashMap<>(Map.of("command", "go", "tools", "read", "workspace_writable", true))).build();

        InnerStep.of(caseBlock, false, "review").run(commands, context, "go");

        ArgumentCaptor<AiStepRequest> captor = ArgumentCaptor.forClass(AiStepRequest.class);
        verify(commands).aiStep(captor.capture(), eq(context));
        assertThat(captor.getValue().tools()).isEqualTo(ToolPolicy.READ);
        assertThat(captor.getValue().writableWorkspace()).isTrue();
    }

    @Test
    void shellStep_quotesUntrustedValues_andCarriesTheContainerSettingsAndShellTimeout() {
        context.setUntrustedVariable("target", "x; id");
        when(commands.shellStep(any(), anyInt(), eq(77), eq(context))).thenReturn(done);
        BlockDefinition wrapper = block(Map.of("container_image", "alpine:3", "mount_docker_socket", false,
                "mounts", List.of("/data:/data:ro"), "network", true, "workspace_writable", true));

        new InnerStep(wrapper, true, false, 77, null).run(commands, context, "build {{ target }}");

        ArgumentCaptor<BlockDefinition> captor = ArgumentCaptor.forClass(BlockDefinition.class);
        verify(commands).shellStep(captor.capture(), eq(0), eq(77), eq(context));
        BlockDefinition shellBlock = captor.getValue();
        assertThat(shellBlock.getType()).isEqualTo("shell");
        assertThat(shellBlock.getName()).isNull();
        assertThat(shellBlock.getConfig())
                .containsEntry("command", FakeCommandRunner.value(1, "x; id") + "build \"${TRACEWORKS_VALUE_1}\"")
                .containsEntry("container_image", "alpine:3")
                .containsEntry("mount_docker_socket", false)
                .containsEntry("mounts", List.of("/data:/data:ro"))
                .containsEntry("network", true)
                .containsEntry("workspace_writable", true);
    }

    @Test
    void shellStep_resolvesPlaceholdersInTheMounts() {
        context.setVariable("data_dir", "/srv/data");
        when(commands.shellStep(any(), anyInt(), anyInt(), eq(context))).thenReturn(done);
        BlockDefinition wrapper = block(Map.of("container_image", "alpine:3",
                "mounts", List.of("{{ data_dir }}:/data:ro", " ")));

        new InnerStep(wrapper, true, false, 60, null).run(commands, context, "ls /data");

        ArgumentCaptor<BlockDefinition> captor = ArgumentCaptor.forClass(BlockDefinition.class);
        verify(commands).shellStep(captor.capture(), anyInt(), anyInt(), eq(context));
        assertThat(captor.getValue().getConfig()).containsEntry("mounts", List.of("/srv/data:/data:ro"));
    }

    @Test
    void shellStep_untrustedValueInAMount_isRefusedBeforeAnythingRuns() {
        context.setUntrustedVariable("data_dir", "/home/agent/.ssh");
        BlockDefinition wrapper = block(Map.of("container_image", "alpine:3",
                "mounts", List.of("{{ data_dir }}:/data:ro")));

        assertThatThrownBy(() -> new InnerStep(wrapper, true, false, 60, null).run(commands, context, "ls /data"))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageStartingWith("mounts must not contain untrusted values");
        verifyNoInteractions(commands);
    }

    @Test
    void shellStep_dockerSocket_isRefusedBeforeAnythingRuns() {
        BlockDefinition wrapper = block(Map.of("container_image", "docker:cli", "mount_docker_socket", true));

        assertThatThrownBy(() -> new InnerStep(wrapper, true, false, 60, null))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("mount_docker_socket");
        verifyNoInteractions(commands);
    }

    @Test
    void aiStep_ignoresTheShellOnlyDockerSocketSetting() {
        when(commands.aiStep(any(AiStepRequest.class), eq(context))).thenReturn(done);

        new InnerStep(block(Map.of("mount_docker_socket", true)), false, false, 60, null).run(commands, context, "go");

        verify(commands).aiStep(any(AiStepRequest.class), eq(context));
    }

    @Test
    void shellStep_escapeVariables_quotesTrustedValuesToo() {
        when(commands.shellStep(any(), anyInt(), anyInt(), eq(context))).thenReturn(done);

        new InnerStep(block(Map.of()), true, true, 60, null).run(commands, context, "build {{ target }}");

        ArgumentCaptor<BlockDefinition> captor = ArgumentCaptor.forClass(BlockDefinition.class);
        verify(commands).shellStep(captor.capture(), anyInt(), anyInt(), eq(context));
        assertThat(captor.getValue().getConfig()).containsEntry("command", "build 'auth'");
    }

    /** An AI step request has no name: the name only reaches the shell step, as a block name. */
    @Test
    void namedStep_passesItsNameToTheShellStep() {
        when(commands.shellStep(any(), anyInt(), anyInt(), eq(context))).thenReturn(done);
        when(commands.aiStep(any(AiStepRequest.class), eq(context))).thenReturn(done);
        BlockDefinition caseBlock = BlockDefinition.builder().type("simple").name("deploy").config(Map.of()).build();

        new InnerStep(caseBlock, true, false, 60, null, "deploy").run(commands, context, "echo");
        new InnerStep(caseBlock, false, false, 60, null, "deploy").run(commands, context, "go");

        ArgumentCaptor<BlockDefinition> shell = ArgumentCaptor.forClass(BlockDefinition.class);
        verify(commands).shellStep(shell.capture(), anyInt(), anyInt(), eq(context));
        assertThat(shell.getValue().getName()).isEqualTo("deploy");
        ArgumentCaptor<AiStepRequest> ai = ArgumentCaptor.forClass(AiStepRequest.class);
        verify(commands).aiStep(ai.capture(), eq(context));
        assertThat(ai.getValue().prompt()).isEqualTo("go");
    }

    @Test
    void runResolved_doesNotFillInPlaceholdersAgain() {
        when(commands.aiStep(any(AiStepRequest.class), eq(context))).thenReturn(done);
        InnerStep step = new InnerStep(block(Map.of()), false, false, 60, null);

        String resolved = step.resolve(context, "review {{ target }}");
        step.runResolved(commands, context, "literal {{ target }}");

        assertThat(resolved).isEqualTo("review auth");
        ArgumentCaptor<AiStepRequest> captor = ArgumentCaptor.forClass(AiStepRequest.class);
        verify(commands).aiStep(captor.capture(), eq(context));
        assertThat(captor.getValue().prompt()).isEqualTo("literal {{ target }}");
    }

    @Test
    void missingCommand_isRefusedWithoutACall() {
        InnerStep step = new InnerStep(block(Map.of()), false, false, 60, null);

        assertThatThrownBy(() -> step.run(commands, context, null))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessage("Block has no 'command' in config");
        verifyNoInteractions(commands);
    }

    /**
     * A provider the run does not know is a failed step that names it, not an exception that fails the whole run:
     * the wrapper's on_failure then applies as for any failure. The run never falls back to another provider.
     */
    @Test
    void aiStep_withAProviderTheRunDoesNotKnow_isAFailedStepNamingIt() {
        FakeAiProvider claude = new FakeAiProvider("CLAUDE");
        ExecutionContext run = ExecutionContext.builder().workflowRunId("run-1").projectPath("/tmp/project")
                .aiProviders(claude.asProviders()).build();

        StepResult result = new InnerStep(block(Map.of("provider", "NOPE")), false, false, 300, null)
                .run(new WrapperCommands(), run, "summarize");

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).isEqualTo("Error: unknown AI provider 'NOPE'; configured: CLAUDE");
        assertThat(result.getMetadata()).containsEntry("error", "unknown AI provider 'NOPE'; configured: CLAUDE");
        assertThat(claude.prompts()).isEmpty();
    }

    @Test
    void of_readsTheTimeoutsAndTheEscapeOptionFromTheBlock() {
        InnerStep defaults = InnerStep.of(block(Map.of()), true, null);
        InnerStep configured = InnerStep.of(
                block(Map.of("timeout_seconds", 42, "shell_escape_variables", true)), false, "case");

        assertThat(defaults.shellTimeout()).isEqualTo(300);
        assertThat(defaults.aiTimeout()).isNull();
        assertThat(defaults.escapeVariables()).isFalse();
        assertThat(configured.shellTimeout()).isEqualTo(42);
        assertThat(configured.aiTimeout()).isEqualTo(42);
        assertThat(configured.escapeVariables()).isTrue();
        assertThat(configured.name()).isEqualTo("case");
    }
}
