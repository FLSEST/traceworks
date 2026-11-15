package traceworks.core.blocks;

import traceworks.core.ai.ConversationPrompt.Turn;
import traceworks.core.ai.ToolPolicy;
import traceworks.core.port.AiProvider;
import traceworks.core.util.TruncationUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Prompt and context construction for the agentic Reason-Act-Observe loop: the reasoning system prompt,
 * conversation-history compression and compression of oversized actions. Runs no loop and holds no state.
 *
 * <p>The system prompt lists no strategies of the workflow's past runs: Traceworks has no learning across runs. It
 * names no CLI as the executor: Traceworks is model-agnostic, and ACT runs through the run's AI provider in the
 * run's working directory, so the prompt says that.
 */
class AgenticPromptBuilder {

    /** Turn contents in a compressed history are cut to this length. */
    static final int TRUNCATE_HISTORY = 150;

    /** Timeout of the call that compresses an oversized action, in seconds. */
    static final int ACTION_COMPRESS_TIMEOUT_SECONDS = 60;

    private static final String ACTION_COMPRESS_PROMPT = """
            You are a task compression assistant. The following is an oversized action description \
            from a workflow automation system. It contains actionable instructions mixed with tool output, \
            file contents, and diagnostic data.

            Extract ONLY the actionable instructions — what needs to be done, where (file paths, \
            commands), and specific details (values, patterns, names). Discard all tool output, \
            file contents, stack traces, and verbose diagnostic text.

            Output a clean, concise action description under 50000 characters. Preserve all file paths, \
            command names, variable values, and specific technical details needed to execute the action.

            === OVERSIZED ACTION ===
            """;

    /**
     * Compresses the conversation history to manage the context window. Keeps the first turn (initial prompt) and the
     * most recent 4 turns in full; the turns in between are collapsed into a single summary turn.
     *
     * @param keepWhole the run's secret values, which the cut of a collapsed turn keeps whole
     */
    void compressHistory(List<Turn> history, java.util.Collection<String> keepWhole) {
        int keepRecentCount = 4;
        if (history.size() <= keepRecentCount + 3) {
            return;
        }

        int compressEnd = history.size() - keepRecentCount;

        StringBuilder summary = new StringBuilder();
        summary.append("[Compressed history of ").append(compressEnd - 1).append(" turns]\n");
        for (int i = 1; i < compressEnd; i++) {
            Turn turn = history.get(i);
            String label = "assistant".equals(turn.role()) ? "ASSISTANT" : "USER";
            summary.append("- ").append(label).append(": ")
                    .append(TruncationUtil.head(turn.content(), TRUNCATE_HISTORY, keepWhole)).append("\n");
        }

        List<Turn> compressed = new ArrayList<>();
        compressed.add(history.get(0));
        compressed.add(new Turn("user", summary.toString()));
        compressed.addAll(history.subList(compressEnd, history.size()));

        history.clear();
        history.addAll(compressed);
    }

    /**
     * @param tools             the block's tools: this is a reasoning call of the block, which carries them
     * @param writableWorkspace the block's {@code workspace_writable}, which every call of the block carries too
     */
    String compressAction(String oversizedAction, AiProvider provider, String model, ToolPolicy tools,
                          boolean writableWorkspace) throws IOException {
        String prompt = ACTION_COMPRESS_PROMPT + oversizedAction;
        AiProvider.ReasoningResult result = provider.reason(new AiProvider.ReasoningRequest(
                prompt, null, model, null, 1, ACTION_COMPRESS_TIMEOUT_SECONDS, null, tools, writableWorkspace));
        String compressed = result.text();
        if (compressed == null || compressed.isBlank()) {
            throw new IOException("Action compression returned empty result");
        }
        return compressed;
    }

    String buildSystemPrompt(String goal, String successCriteria, String workflowInstructions) {
        String instructionsSection = "";
        if (workflowInstructions != null && !workflowInstructions.isBlank()) {
            instructionsSection = "\n## Workflow Instructions\n" +
                    "The following instructions apply to this workflow and should guide your planning:\n" +
                    workflowInstructions + "\n";
        }

        return """
                You are the reasoning engine of Traceworks, a workflow engine. \
                You analyze situations, plan actions, and evaluate results.
                You operate in a Reason-Act-Observe loop. The actions you plan will be executed by another AI agent \
                in the run's working directory, with access to its files and tools.

                ## Your Goal
                %s

                ## Success Criteria
                %s
                %s\
                ## Response Format
                You MUST respond in this exact format:

                GOAL_STATUS: IN_PROGRESS
                RATIONALE: <your reasoning about the current state and what to do next>
                ACTION: <the exact instruction/task to give to the executor agent>
                SUMMARY: <brief summary of what this action will accomplish>

                When the goal is achieved, respond with:
                GOAL_STATUS: ACHIEVED
                RATIONALE: <why the goal is now met>
                ACTION: <empty or final verification>
                SUMMARY: <what was accomplished>

                If the goal is impossible, respond with:
                GOAL_STATUS: IMPOSSIBLE
                RATIONALE: <why the goal cannot be achieved>
                ACTION:
                SUMMARY: <what was tried and why it failed>

                ## Guidelines
                - The ACTION is sent as a task description to the executing AI agent, which works in the run's \
                working directory.
                - The executing agent can run commands, edit files, and work with the files of the working directory.
                - Be specific in your ACTION -- describe exactly what needs to be done.
                - If a previous action failed, analyze the error and try a different approach.
                - Do NOT repeat the same action if it failed -- try a different approach.
                - Prefer small, incremental steps over large changes.
                - After each observation, assess progress towards the goal.
                """.formatted(goal, successCriteria, instructionsSection);
    }
}
