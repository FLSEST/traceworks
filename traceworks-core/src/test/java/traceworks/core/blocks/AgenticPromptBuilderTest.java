package traceworks.core.blocks;

import traceworks.core.ai.ConversationPrompt.Turn;
import traceworks.core.ai.ToolPolicy;
import traceworks.core.port.AiProvider;
import traceworks.core.util.TruncationUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Locks the prompt and context construction of the agentic loop. */
@ExtendWith(MockitoExtension.class)
class AgenticPromptBuilderTest {

    @Mock private AiProvider provider;

    private final AgenticPromptBuilder builder = new AgenticPromptBuilder();

    @Test
    void buildSystemPrompt_embedsIdentityGoalAndCriteria_noInstructionsSectionWhenBlank() {
        String p = builder.buildSystemPrompt("Fix the build", "Tests green", "  ");

        assertThat(p).startsWith("You are the reasoning engine of Traceworks, a workflow engine.");
        assertThat(p).contains("## Your Goal\nFix the build\n\n## Success Criteria\nTests green\n## Response Format");
        assertThat(p).doesNotContain("## Workflow Instructions");
        assertThat(p).doesNotContain("Previously Tried Strategies");
        // Model-agnostic, and true to how ACT runs: through the run's AI provider in the run's working directory
        assertThat(p).doesNotContain("Claude", "Docker", "container")
                .contains("- The ACTION is sent as a task description to the executing AI agent, which works in the "
                        + "run's working directory.\n- The executing agent can run commands, edit files, and work with "
                        + "the files of the working directory.\n");
        assertThat(p).endsWith("- After each observation, assess progress towards the goal.\n");
    }

    @Test
    void buildSystemPrompt_withInstructions_insertsSectionBeforeResponseFormat() {
        String p = builder.buildSystemPrompt("G", "C", "Never touch prod.");

        assertThat(p).contains("## Success Criteria\nC\n\n## Workflow Instructions\n"
                + "The following instructions apply to this workflow and should guide your planning:\nNever touch prod.\n## Response Format");
    }

    @Test
    void compressHistory_sevenOrFewerTurns_unchanged() {
        List<Turn> history = new ArrayList<>();
        for (int i = 0; i < 7; i++) history.add(new Turn(i % 2 == 0 ? "user" : "assistant", "t" + i));

        builder.compressHistory(history, java.util.List.of());

        assertThat(history).hasSize(7);
        assertThat(history.get(0).content()).isEqualTo("t0");
    }

    @Test
    void compressHistory_eightTurns_keepsFirstAndLastFour_collapsesMiddle() {
        List<Turn> history = new ArrayList<>();
        for (int i = 0; i < 8; i++) history.add(new Turn(i % 2 == 0 ? "user" : "assistant", "t" + i));

        builder.compressHistory(history, java.util.List.of());

        assertThat(history).hasSize(6);
        assertThat(history.get(0).content()).isEqualTo("t0");
        assertThat(history.get(1).role()).isEqualTo("user");
        assertThat(history.get(1).content()).isEqualTo("[Compressed history of 3 turns]\n- ASSISTANT: t1\n- USER: t2\n- ASSISTANT: t3\n");
        assertThat(history.subList(2, 6)).extracting(Turn::content).containsExactly("t4", "t5", "t6", "t7");
    }

    @Test
    void compressAction_returnsProviderText_withPromptPrefix() throws Exception {
        when(provider.reason(any())).thenReturn(new AiProvider.ReasoningResult("short action", null));

        String result = builder.compressAction("very long action", provider, "model-x", ToolPolicy.READ, true);

        assertThat(result).isEqualTo("short action");
        ArgumentCaptor<AiProvider.ReasoningRequest> req = ArgumentCaptor.forClass(AiProvider.ReasoningRequest.class);
        verify(provider).reason(req.capture());
        assertThat(req.getValue().prompt()).startsWith("You are a task compression assistant.").endsWith("=== OVERSIZED ACTION ===\nvery long action");
        assertThat(req.getValue().model()).isEqualTo("model-x");
        assertThat(req.getValue().tools()).as("a call of the block carries its tools")
                .isEqualTo(ToolPolicy.READ);
        assertThat(req.getValue().writableWorkspace()).as("and its workspace_writable").isTrue();
    }

    @Test
    void compressAction_blankResult_throwsIOException() throws Exception {
        when(provider.reason(any())).thenReturn(new AiProvider.ReasoningResult("  ", null));

        assertThatThrownBy(() -> builder.compressAction("x", provider, null, ToolPolicy.NONE, false))
                .isInstanceOf(IOException.class)
                .hasMessage("Action compression returned empty result");
    }

    /** The loop cuts its texts with {@link TruncationUtil#head}. */
    @Test
    void truncate_nullIsEmpty_cutAppendsEllipsis_shortUnchanged() {
        assertThat(TruncationUtil.head(null, 5)).isEmpty();
        assertThat(TruncationUtil.head("abcdef", 3)).isEqualTo("abc...");
        assertThat(TruncationUtil.head("abc", 3)).isEqualTo("abc");
    }
}
