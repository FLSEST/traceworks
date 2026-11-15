package traceworks.core.blocks;

import traceworks.core.ai.ToolPolicy;
import traceworks.core.port.WebAccess;

/**
 * One AI call of a block step ({@link AiStepCall}). The calls of block executors are always background calls,
 * without the user's MCP servers.
 *
 * @param workingDirectory the directory the provider works in, as a host path; {@code null} (also for a blank one)
 *                         for the run's workspace, where the call's container starts
 * @param prompt           the prompt, already resolved (required, may be blank)
 * @param stepIndex        the index of the step the call makes
 * @param model            the model for the provider CLI; {@code null} for the provider default
 * @param systemPrompt     the system prompt; {@code null} for none
 * @param timeoutSeconds   the timeout of the call; {@code null} for the provider default
 * @param providerId       the provider; {@code null} for the run's default provider
 * @param effort           the reasoning effort; {@code null} for the provider default
 * @param tools            what the model may do besides answering; {@code null} for {@link ToolPolicy#NONE}. Only
 *                         {@code simple} passes a block's {@code tools}; every other step call runs without tools
 * @param writableWorkspace whether the call's container may write the workspace ({@code workspace_writable})
 * @param web              whether the model may use the web ({@code web}); {@code null} for {@link WebAccess#NONE}
 */
record AiStepRequest(String workingDirectory, String prompt, int stepIndex, String model, String systemPrompt,
                     Integer timeoutSeconds, String providerId, String effort, ToolPolicy tools,
                     boolean writableWorkspace, WebAccess web) {

    AiStepRequest {
        if (workingDirectory != null && workingDirectory.isBlank()) {
            workingDirectory = null;
        }
        tools = tools != null ? tools : ToolPolicy.NONE;
        web = web != null ? web : WebAccess.NONE;
        if (prompt == null) {
            throw new IllegalArgumentException("prompt is required");
        }
    }

    static Builder builder() {
        return new Builder();
    }

    /** Plain-Java builder; {@code prompt} is required. */
    static final class Builder {
        private String workingDirectory;
        private String prompt;
        private int stepIndex;
        private String model;
        private String systemPrompt;
        private Integer timeoutSeconds;
        private String providerId;
        private String effort;
        private ToolPolicy tools = ToolPolicy.NONE;
        private boolean writableWorkspace;
        private WebAccess web = WebAccess.NONE;

        private Builder() {}

        Builder workingDirectory(String v) { this.workingDirectory = v; return this; }
        Builder prompt(String v) { this.prompt = v; return this; }
        Builder stepIndex(int v) { this.stepIndex = v; return this; }
        Builder model(String v) { this.model = v; return this; }
        Builder systemPrompt(String v) { this.systemPrompt = v; return this; }
        Builder timeoutSeconds(Integer v) { this.timeoutSeconds = v; return this; }
        Builder providerId(String v) { this.providerId = v; return this; }
        Builder effort(String v) { this.effort = v; return this; }
        Builder tools(ToolPolicy v) { this.tools = v; return this; }
        Builder writableWorkspace(boolean v) { this.writableWorkspace = v; return this; }
        Builder web(WebAccess v) { this.web = v; return this; }

        AiStepRequest build() {
            return new AiStepRequest(workingDirectory, prompt, stepIndex, model, systemPrompt, timeoutSeconds,
                    providerId, effort, tools, writableWorkspace, web);
        }
    }
}
