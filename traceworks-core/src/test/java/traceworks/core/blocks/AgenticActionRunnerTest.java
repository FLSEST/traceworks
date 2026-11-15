package traceworks.core.blocks;

import traceworks.core.ai.CallPriority;
import traceworks.core.ai.ToolPolicy;
import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.EventLogException;
import traceworks.core.port.AiProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static traceworks.core.blocks.AgenticActionRunner.COMPRESS_THRESHOLD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgenticActionRunnerTest {

    @Mock private AgenticPromptBuilder promptBuilder;
    @Mock private AiProvider provider;

    private AgenticActionRunner runner;
    private ExecutionContext context;
    private AgenticBlockConfig cfg;

    @BeforeEach
    void setUp() {
        runner = new AgenticActionRunner(promptBuilder);
        context = ExecutionContext.builder()
                .containerId("c").workflowRunId("run-1").projectPath("/proj").build();
        cfg = AgenticBlockConfig.from(Map.of("goal", "g", "tools", "write"), context);
    }

    private Map<String, Object> iterData() {
        return new LinkedHashMap<>();
    }

    @Test
    void run_providerThrows_returnsExecutionErrorWithExitCodeMinusOne() throws IOException {
        when(provider.executeAction(any())).thenThrow(new IOException("boom"));

        AgenticActionRunner.ActOutcome outcome = runner.run(context, 0, 1, "do it",
                provider, null, null, cfg, new AgenticTokenTracker(), iterData());

        assertThat(outcome.output()).isEqualTo("Execution error: boom");
        assertThat(outcome.exitCode()).isEqualTo(-1);
        assertThat(outcome.action()).isEqualTo("do it");
        // No compression attempted for a short action, and the failed call must not blow up captureActMeta.
        verifyNoInteractions(promptBuilder);
    }

    /** A record of the ACT call, or of the compression of its action, that cannot be written ends the run. */
    @Test
    void run_aRecordWriteThatFails_propagates() throws IOException {
        when(provider.executeAction(any())).thenThrow(new EventLogException(new IOException("disk full")));
        when(promptBuilder.compressAction(anyString(), any(), any(), any(), anyBoolean()))
                .thenThrow(new EventLogException(new IOException("disk full")));

        assertThatThrownBy(() -> runner.run(context, 0, 1, "do it", provider, null, null, cfg,
                new AgenticTokenTracker(), iterData())).isInstanceOf(EventLogException.class);
        assertThatThrownBy(() -> runner.run(context, 0, 1, "z".repeat(COMPRESS_THRESHOLD + 1), provider, null, null,
                cfg, new AgenticTokenTracker(), iterData())).isInstanceOf(EventLogException.class);
    }

    /** ACT runs with the block's tools and workspace setting, as an unattended call. */
    @Test
    void run_actRequest_carriesTheBlocksToolsAsABackgroundCall() throws IOException {
        when(provider.executeAction(any())).thenReturn(new AiProvider.ActionResult("done", 0, null));

        runner.run(context, 0, 1, "edit the file", provider, "sonnet", "high", cfg,
                new AgenticTokenTracker(), iterData());

        ArgumentCaptor<AiProvider.ActionRequest> captor = ArgumentCaptor.forClass(AiProvider.ActionRequest.class);
        verify(provider).executeAction(captor.capture());
        AiProvider.ActionRequest request = captor.getValue();
        assertThat(request.tools()).isEqualTo(ToolPolicy.WRITE);
        assertThat(request.writableWorkspace()).as("read-only unless the block says so").isFalse();
        assertThat(request.priority()).isEqualTo(CallPriority.BACKGROUND);
        assertThat(request.model()).isEqualTo("sonnet");
        assertThat(request.effort()).isEqualTo("high");
        assertThat(request.workingDirectory()).isEqualTo("/proj");
    }

    /** {@code workspace_writable: true} reaches the ACT request (fix round 2). */
    @Test
    void run_writableWorkspace_reachesTheActRequest() throws IOException {
        when(provider.executeAction(any())).thenReturn(new AiProvider.ActionResult("done", 0, null));
        AgenticBlockConfig writable = AgenticBlockConfig.from(Map.of("goal", "g", "tools", "write",
                "workspace_writable", true), context);

        runner.run(context, 0, 1, "edit the file", provider, null, null, writable, new AgenticTokenTracker(),
                iterData());

        ArgumentCaptor<AiProvider.ActionRequest> captor = ArgumentCaptor.forClass(AiProvider.ActionRequest.class);
        verify(provider).executeAction(captor.capture());
        assertThat(captor.getValue().writableWorkspace()).isTrue();
    }

    @Test
    void run_oversizedAction_compressesViaPromptBuilder_andExecutesCompressed() throws IOException {
        String oversized = "x".repeat(COMPRESS_THRESHOLD + 1);
        when(promptBuilder.compressAction(eq(oversized), eq(provider), eq(context.getModel()), eq(ToolPolicy.WRITE),
                eq(false)))
                .thenReturn("compressed action");
        when(provider.executeAction(any()))
                .thenReturn(new AiProvider.ActionResult("done", 0, null));

        AgenticActionRunner.ActOutcome outcome = runner.run(context, 0, 1, oversized,
                provider, null, null, cfg, new AgenticTokenTracker(), iterData());

        verify(promptBuilder).compressAction(oversized, provider, context.getModel(), ToolPolicy.WRITE, false);
        assertThat(outcome.action()).isEqualTo("compressed action");
        assertThat(outcome.output()).isEqualTo("done");
        assertThat(outcome.exitCode()).isEqualTo(0);

        ArgumentCaptor<AiProvider.ActionRequest> captor = ArgumentCaptor.forClass(AiProvider.ActionRequest.class);
        verify(provider).executeAction(captor.capture());
        assertThat(captor.getValue().prompt()).isEqualTo("compressed action");
    }

    @Test
    void run_compressionFails_fallsBackToTruncatedAction() throws IOException {
        String oversized = "y".repeat(COMPRESS_THRESHOLD + 500);
        when(promptBuilder.compressAction(anyString(), any(), any(), any(), anyBoolean()))
                .thenThrow(new IOException("compression failed"));
        when(provider.executeAction(any()))
                .thenReturn(new AiProvider.ActionResult("done", 0, null));

        AgenticActionRunner.ActOutcome outcome = runner.run(context, 0, 1, oversized,
                provider, null, null, cfg, new AgenticTokenTracker(), iterData());

        assertThat(outcome.action()).startsWith("y".repeat(COMPRESS_THRESHOLD));
        assertThat(outcome.action()).contains("[TRUNCATED: original was " + oversized.length() + " chars]");
    }
}
