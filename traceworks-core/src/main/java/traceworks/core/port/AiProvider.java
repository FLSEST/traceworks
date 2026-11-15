package traceworks.core.port;

import traceworks.core.ai.AiProviderCapabilities;
import traceworks.core.ai.AiResponseMetadata;
import traceworks.core.ai.CallPriority;
import traceworks.core.ai.ConversationPrompt;
import traceworks.core.ai.ToolCall;
import traceworks.core.ai.ToolPolicy;

import java.io.IOException;
import java.util.List;

/**
 * Port to one AI backend (Claude, Pi). Each implementation wraps a provider CLI and normalizes its
 * interface; the AI blocks of the core talk to models only through this port.
 */
public interface AiProvider {

    /** Unique provider identifier, for example {@code CLAUDE} or {@code PI}. */
    String getId();

    /** Declares what features this provider supports. */
    AiProviderCapabilities getCapabilities();

    /**
     * Reasoning phase: "think about this prompt and respond". Used by the agentic REASON phase and other calls
     * that only need an answer. A returned result is always the model's answer: when the call fails (timeout,
     * or the CLI ends with a non-zero exit code because of a limit, a missing login or a crash) every provider
     * throws, because a caller that only gets text back would take the CLI's error message for the answer. A provider
     * that knows the outcome of the failed call throws an {@link AiCallFailedException} with it.
     */
    ReasoningResult reason(ReasoningRequest request) throws IOException;

    /**
     * Action phase: "execute this task in the working directory". Used by the agentic ACT phase and the AI
     * blocks that work on files.
     */
    ActionResult executeAction(ActionRequest request) throws IOException;

    /**
     * Builds a flat conversation prompt from system prompt and turn history. By default the system prompt travels
     * separately; a provider without a system prompt flag, or with native multi-turn, overrides it.
     */
    default String buildConversationPrompt(String systemPrompt, List<ConversationPrompt.Turn> history) {
        return ConversationPrompt.build(history);
    }

    // --- Request records ---

    /**
     * One reasoning call.
     *
     * @param tools             what the model may do besides answering; {@code null} means {@link ToolPolicy#NONE}.
     *                          An agentic block's reasoning calls carry the block's {@code tools}
     * @param writableWorkspace whether the call's container may write the workspace ({@code workspace_writable})
     * @param web               whether the model may use the web; {@code null} means {@link WebAccess#NONE}
     */
    record ReasoningRequest(
            String prompt,
            String systemPrompt,
            String model,
            String workingDirectory,
            Integer maxTurns,
            Integer timeoutSeconds,
            String effort,
            ToolPolicy tools,
            boolean writableWorkspace,
            WebAccess web
    ) {
        public ReasoningRequest {
            tools = tools != null ? tools : ToolPolicy.NONE;
            web = web != null ? web : WebAccess.NONE;
        }

        /** Request without web. */
        public ReasoningRequest(String prompt, String systemPrompt, String model, String workingDirectory,
                                Integer maxTurns, Integer timeoutSeconds, String effort, ToolPolicy tools,
                                boolean writableWorkspace) {
            this(prompt, systemPrompt, model, workingDirectory, maxTurns, timeoutSeconds, effort, tools,
                    writableWorkspace, WebAccess.NONE);
        }

        /** The same request, where the model may use the web as {@code web} says. */
        public ReasoningRequest withWeb(WebAccess web) {
            return new ReasoningRequest(prompt, systemPrompt, model, workingDirectory, maxTurns, timeoutSeconds,
                    effort, tools, writableWorkspace, web);
        }

        /** Request without tools, on a read-only workspace. */
        public ReasoningRequest(String prompt, String systemPrompt, String model, String workingDirectory,
                                Integer maxTurns, Integer timeoutSeconds, String effort) {
            this(prompt, systemPrompt, model, workingDirectory, maxTurns, timeoutSeconds, effort, ToolPolicy.NONE,
                    false);
        }

        public ReasoningRequest(String prompt) {
            this(prompt, null, null, null, null, null, null);
        }

        public ReasoningRequest(String prompt, String systemPrompt) {
            this(prompt, systemPrompt, null, null, null, null, null);
        }

        /** Request without model, effort and tools. */
        public ReasoningRequest(String prompt, String systemPrompt, String workingDirectory,
                                Integer maxTurns, Integer timeoutSeconds) {
            this(prompt, systemPrompt, null, workingDirectory, maxTurns, timeoutSeconds, null);
        }

        /** Request without model and tools. */
        public ReasoningRequest(String prompt, String systemPrompt, String workingDirectory,
                                Integer maxTurns, Integer timeoutSeconds, String effort) {
            this(prompt, systemPrompt, null, workingDirectory, maxTurns, timeoutSeconds, effort);
        }

        /** The same request with another system prompt (for example, completed by the run context). */
        public ReasoningRequest withSystemPrompt(String newSystemPrompt) {
            return new ReasoningRequest(prompt, newSystemPrompt, model, workingDirectory, maxTurns, timeoutSeconds,
                    effort, tools, writableWorkspace, web);
        }
    }

    /**
     * One action call. The priority is an enum, not a {@code boolean}, so that it cannot be swapped silently with the
     * flag next to it. A {@code null} priority or policy means the safe side ({@link CallPriority#BACKGROUND},
     * {@link ToolPolicy#NONE}).
     *
     * @param tools             what the model may do besides answering: one policy instead of tool lists and
     *                          a write switch per call
     * @param writableWorkspace whether the call's container may write the workspace ({@code workspace_writable});
     *                          by default it sees it read-only
     * @param web               whether the model may use the web; {@code null} means {@link WebAccess#NONE}
     */
    record ActionRequest(
            String prompt,
            String model,
            String systemPrompt,
            String workingDirectory,
            Integer timeoutSeconds,
            CallPriority priority,
            ToolPolicy tools,
            boolean writableWorkspace,
            String effort,
            WebAccess web
    ) {
        public ActionRequest {
            priority = priority != null ? priority : CallPriority.BACKGROUND;
            tools = tools != null ? tools : ToolPolicy.NONE;
            web = web != null ? web : WebAccess.NONE;
        }

        /** Call without web. */
        public ActionRequest(String prompt, String model, String systemPrompt, String workingDirectory,
                             Integer timeoutSeconds, CallPriority priority, ToolPolicy tools,
                             boolean writableWorkspace, String effort) {
            this(prompt, model, systemPrompt, workingDirectory, timeoutSeconds, priority, tools, writableWorkspace,
                    effort, WebAccess.NONE);
        }

        /** The same request, where the model may use the web as {@code web} says. */
        public ActionRequest withWeb(WebAccess web) {
            return new ActionRequest(prompt, model, systemPrompt, workingDirectory, timeoutSeconds, priority, tools,
                    writableWorkspace, effort, web);
        }

        /** Background call without tools, timeout or effort, on a read-only workspace (analysis). */
        public ActionRequest(String prompt, String model, String systemPrompt, String workingDirectory) {
            this(prompt, model, systemPrompt, workingDirectory, null, CallPriority.BACKGROUND, ToolPolicy.NONE, false,
                    null);
        }

        /** The same request with another system prompt (for example, completed by the run context). */
        public ActionRequest withSystemPrompt(String newSystemPrompt) {
            return new ActionRequest(prompt, model, newSystemPrompt, workingDirectory, timeoutSeconds, priority,
                    tools, writableWorkspace, effort, web);
        }

        /** The call may use the CLI slots reserved for interactive use. */
        public boolean interactive() {
            return priority == CallPriority.INTERACTIVE;
        }
    }

    // --- Response records ---

    /**
     * @param thinkingLevel the thinking level the provider chose for this call on its own (pi's {@code --thinking},
     *                      from the effort or the maxTurns heuristic); {@code null} for providers that pass nothing
     *                      of their own
     * @param toolCalls     the tool calls the model made, in order; empty for none
     */
    record ReasoningResult(
            String text,
            AiResponseMetadata metadata,
            String thinkingLevel,
            List<ToolCall> toolCalls
    ) {
        public ReasoningResult {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        public ReasoningResult(String text, AiResponseMetadata metadata) {
            this(text, metadata, null, List.of());
        }

        public ReasoningResult(String text, AiResponseMetadata metadata, String thinkingLevel) {
            this(text, metadata, thinkingLevel, List.of());
        }
    }

    /** @param toolCalls the tool calls the model made, in order; empty for none */
    record ActionResult(
            String text,
            int exitCode,
            AiResponseMetadata metadata,
            List<ToolCall> toolCalls
    ) {
        public ActionResult {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        public ActionResult(String text, int exitCode, AiResponseMetadata metadata) {
            this(text, exitCode, metadata, List.of());
        }
    }
}
