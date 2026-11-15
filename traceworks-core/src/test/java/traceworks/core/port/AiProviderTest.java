package traceworks.core.port;

import traceworks.core.ai.AiResponseMetadata;
import traceworks.core.ai.CallPriority;
import traceworks.core.ai.ConversationPrompt;
import traceworks.core.ai.ToolCall;
import traceworks.core.ai.ToolPolicy;
import traceworks.core.port.AiProvider.ActionRequest;
import traceworks.core.port.AiProvider.ActionResult;
import traceworks.core.port.AiProvider.ReasoningRequest;
import traceworks.core.port.AiProvider.ReasoningResult;
import traceworks.core.testing.FakeAiProvider;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the AiProvider interface — specifically the inner record types
 * (ReasoningRequest, ActionRequest, ReasoningResult, ActionResult) and their
 * backward-compatible convenience constructors.
 */
class AiProviderTest {

    @Nested
    class ReasoningRequestTests {

        @Test
        void should_default_optional_fields_to_null_when_constructed_with_prompt_only() {
            ReasoningRequest req = new ReasoningRequest("hello");

            assertThat(req.prompt()).isEqualTo("hello");
            assertThat(req.systemPrompt()).isNull();
            assertThat(req.workingDirectory()).isNull();
            assertThat(req.maxTurns()).isNull();
            assertThat(req.timeoutSeconds()).isNull();
        }

        @Test
        void should_set_systemPrompt_and_default_other_fields_when_two_arg_constructor_used() {
            ReasoningRequest req = new ReasoningRequest("p", "sys");

            assertThat(req.prompt()).isEqualTo("p");
            assertThat(req.systemPrompt()).isEqualTo("sys");
            assertThat(req.workingDirectory()).isNull();
            assertThat(req.maxTurns()).isNull();
            assertThat(req.timeoutSeconds()).isNull();
        }

        @Test
        void should_assign_all_fields_when_full_constructor_used() {
            ReasoningRequest req = new ReasoningRequest("p", "s", "/work", 3, 60);

            assertThat(req.prompt()).isEqualTo("p");
            assertThat(req.systemPrompt()).isEqualTo("s");
            assertThat(req.workingDirectory()).isEqualTo("/work");
            assertThat(req.maxTurns()).isEqualTo(3);
            assertThat(req.timeoutSeconds()).isEqualTo(60);
        }

        @Test
        void should_be_equal_when_records_have_same_values() {
            ReasoningRequest a = new ReasoningRequest("p", "s", "/w", 1, 10);
            ReasoningRequest b = new ReasoningRequest("p", "s", "/w", 1, 10);

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        void should_assign_model_when_canonical_constructor_used() {
            ReasoningRequest req = new ReasoningRequest("p", "s", "openai/gpt-5.5", "/work", 3, 60, "high");

            assertThat(req.prompt()).isEqualTo("p");
            assertThat(req.systemPrompt()).isEqualTo("s");
            assertThat(req.model()).isEqualTo("openai/gpt-5.5");
            assertThat(req.workingDirectory()).isEqualTo("/work");
            assertThat(req.maxTurns()).isEqualTo(3);
            assertThat(req.timeoutSeconds()).isEqualTo(60);
            assertThat(req.effort()).isEqualTo("high");
        }

        @Test
        void should_default_model_to_null_in_all_compat_constructors() {
            assertThat(new ReasoningRequest("p").model()).isNull();
            assertThat(new ReasoningRequest("p", "s").model()).isNull();
            assertThat(new ReasoningRequest("p", "s", "/w", 1, 10).model()).isNull();
            assertThat(new ReasoningRequest("p", "s", "/w", 1, 10, "low").model()).isNull();
        }

        /** Every convenience constructor gives a call without tools on a read-only workspace. */
        @Test
        void should_default_to_no_tools_and_a_read_only_workspace() {
            for (ReasoningRequest req : List.of(new ReasoningRequest("p"), new ReasoningRequest("p", "s"),
                    new ReasoningRequest("p", "s", "/w", 1, 10), new ReasoningRequest("p", "s", "/w", 1, 10, "low"),
                    new ReasoningRequest("p", "s", "m", "/w", 1, 10, "low"))) {
                assertThat(req.tools()).isEqualTo(ToolPolicy.NONE);
                assertThat(req.writableWorkspace()).isFalse();
            }
        }

        @Test
        void should_keep_tools_and_workspace_when_system_prompt_is_replaced() {
            ReasoningRequest req = new ReasoningRequest("p", "old", "m", null, null, 60, "high", ToolPolicy.READ,
                    true);

            assertThat(req.withSystemPrompt("new")).isEqualTo(new ReasoningRequest("p", "new", "m", null, null, 60,
                    "high", ToolPolicy.READ, true));
        }

        @Test
        void should_fall_back_to_no_tools_when_tools_are_null() {
            assertThat(new ReasoningRequest("p", null, null, null, null, null, null, null, false).tools())
                    .isEqualTo(ToolPolicy.NONE);
        }
    }

    @Nested
    class ActionRequestTests {

        @Test
        void should_default_to_a_background_call_without_tools_when_4_arg_constructor_used() {
            ActionRequest req = new ActionRequest("p", "claude-sonnet-4-6", "sys", "/work");

            assertThat(req.prompt()).isEqualTo("p");
            assertThat(req.model()).isEqualTo("claude-sonnet-4-6");
            assertThat(req.systemPrompt()).isEqualTo("sys");
            assertThat(req.workingDirectory()).isEqualTo("/work");
            assertThat(req.timeoutSeconds()).isNull();
            assertThat(req.priority()).isEqualTo(CallPriority.BACKGROUND);
            assertThat(req.interactive()).isFalse();
            assertThat(req.tools()).isEqualTo(ToolPolicy.NONE);
            assertThat(req.writableWorkspace()).isFalse();
            assertThat(req.effort()).isNull();
        }

        @Test
        void should_assign_all_fields_when_full_constructor_used() {
            ActionRequest req = new ActionRequest(
                    "prompt", "model", "sys", "/work", 600, CallPriority.INTERACTIVE, ToolPolicy.SHELL, true, "high");

            assertThat(req.prompt()).isEqualTo("prompt");
            assertThat(req.model()).isEqualTo("model");
            assertThat(req.systemPrompt()).isEqualTo("sys");
            assertThat(req.workingDirectory()).isEqualTo("/work");
            assertThat(req.timeoutSeconds()).isEqualTo(600);
            assertThat(req.priority()).isEqualTo(CallPriority.INTERACTIVE);
            assertThat(req.tools()).isEqualTo(ToolPolicy.SHELL);
            assertThat(req.writableWorkspace()).isTrue();
            assertThat(req.effort()).isEqualTo("high");
        }

        @Test
        void should_be_interactive_only_when_priority_is_INTERACTIVE() {
            ActionRequest interactive = new ActionRequest("p", null, null, null, null,
                    CallPriority.INTERACTIVE, ToolPolicy.NONE, false, null);
            ActionRequest background = new ActionRequest("p", null, null, null, null,
                    CallPriority.BACKGROUND, ToolPolicy.NONE, false, null);

            assertThat(interactive.interactive()).isTrue();
            assertThat(background.interactive()).isFalse();
        }

        /** A missing priority or tool policy means the safe side: a background call without tools. */
        @Test
        void should_fall_back_to_safe_side_when_priority_and_tools_are_null() {
            ActionRequest req = new ActionRequest("p", null, null, null, null, null, null, false, null);

            assertThat(req.priority()).isEqualTo(CallPriority.BACKGROUND);
            assertThat(req.tools()).isEqualTo(ToolPolicy.NONE);
            assertThat(req.model()).isNull();
            assertThat(req.systemPrompt()).isNull();
            assertThat(req.workingDirectory()).isNull();
            assertThat(req.timeoutSeconds()).isNull();
        }

        @Test
        void should_keep_priority_and_tools_when_system_prompt_is_replaced() {
            ActionRequest req = new ActionRequest("p", "m", "old", "/w", 30,
                    CallPriority.INTERACTIVE, ToolPolicy.WRITE, true, "low");

            ActionRequest replaced = req.withSystemPrompt("new");

            assertThat(replaced.systemPrompt()).isEqualTo("new");
            assertThat(replaced).isEqualTo(new ActionRequest("p", "m", "new", "/w", 30,
                    CallPriority.INTERACTIVE, ToolPolicy.WRITE, true, "low"));
        }

        @Test
        void should_map_interactive_flag_to_call_priority() {
            assertThat(CallPriority.of(true)).isEqualTo(CallPriority.INTERACTIVE);
            assertThat(CallPriority.of(false)).isEqualTo(CallPriority.BACKGROUND);
        }

        @Test
        void should_be_equal_when_records_have_same_values() {
            ActionRequest a = new ActionRequest("p", "m", "s", "/w");
            ActionRequest b = new ActionRequest("p", "m", "s", "/w");

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }
    }

    @Nested
    class ResultRecordsTests {

        @Test
        void reasoningResult_should_expose_text_and_metadata() {
            AiResponseMetadata meta = AiResponseMetadata.estimated(100, 50);

            ReasoningResult result = new ReasoningResult("answer", meta);

            assertThat(result.text()).isEqualTo("answer");
            assertThat(result.metadata()).isSameAs(meta);
        }

        @Test
        void reasoningResult_should_allow_null_metadata() {
            ReasoningResult result = new ReasoningResult("answer", null);

            assertThat(result.text()).isEqualTo("answer");
            assertThat(result.metadata()).isNull();
        }

        @Test
        void actionResult_should_expose_text_exitCode_and_metadata() {
            AiResponseMetadata meta = AiResponseMetadata.estimated(200, 80);

            ActionResult result = new ActionResult("done", 0, meta);

            assertThat(result.text()).isEqualTo("done");
            assertThat(result.exitCode()).isZero();
            assertThat(result.metadata()).isSameAs(meta);
        }

        @Test
        void actionResult_should_capture_nonZero_exitCode() {
            ActionResult result = new ActionResult("error", 1, null);

            assertThat(result.text()).isEqualTo("error");
            assertThat(result.exitCode()).isEqualTo(1);
            assertThat(result.metadata()).isNull();
        }

        /** The short constructors report no tool calls; the full ones keep them in order. */
        @Test
        void results_carry_their_tool_calls() {
            ToolCall read = new ToolCall("Read", "{\"file_path\":\"a\"}", "text", false);
            ToolCall grep = new ToolCall("Grep", "{}", "boom", true);

            assertThat(new ActionResult("t", 0, null).toolCalls()).isEmpty();
            assertThat(new ReasoningResult("t", null).toolCalls()).isEmpty();
            assertThat(new ReasoningResult("t", null, "high").toolCalls()).isEmpty();
            assertThat(new ActionResult("t", 0, null, List.of(read, grep)).toolCalls()).containsExactly(read, grep);
            assertThat(new ReasoningResult("t", null, null, List.of(grep)).toolCalls()).containsExactly(grep);
            assertThat(new ActionResult("t", 0, null, null).toolCalls()).isEmpty();
        }

        @Test
        void resultRecords_should_be_equal_when_values_match() {
            ActionResult a = new ActionResult("t", 0, null);
            ActionResult b = new ActionResult("t", 0, null);

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }
    }

    /**
     * A provider that keeps the default (the CLI providers do) sends the system prompt separately and the turns as
     * one flat text: the history the agentic block hands over, then the request for the answer.
     */
    @Test
    void theDefaultConversationPrompt_isTheFlatHistory() {
        List<ConversationPrompt.Turn> history = List.of(new ConversationPrompt.Turn("user", "Fix the build"),
                new ConversationPrompt.Turn("assistant", "ACTION: run the tests"));

        String prompt = new FakeAiProvider("CLAUDE").buildConversationPrompt("Be brief.", history);

        assertThat(prompt).isEqualTo(ConversationPrompt.build(history))
                .contains("Fix the build", "ACTION: run the tests").doesNotContain("Be brief.");
    }
}
