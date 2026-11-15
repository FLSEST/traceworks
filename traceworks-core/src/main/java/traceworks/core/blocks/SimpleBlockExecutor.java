package traceworks.core.blocks;

import traceworks.core.ai.ToolPolicy;
import traceworks.core.port.WebAccess;
import traceworks.core.context.ExecutionContext;
import traceworks.core.context.UntrustedInput;
import traceworks.core.engine.BlockExecutor;
import traceworks.core.engine.WorkflowExecutionException;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.BlockSettings;
import traceworks.core.model.StepResult;

import java.util.Map;
import java.util.Set;

/**
 * {@code simple}: one AI call with the block's {@code command} as prompt, in the run's project, through the run's AI
 * providers. Provider, model and effort come from the block, else from the workflow; the system prompt is the
 * workflow's instruction prefix, with the foreign-content preamble for {@code untrusted_input: true}. The call has the
 * block's {@code tools} ({@code none} when absent) and sees the workspace read-only unless
 * {@code workspace_writable: true}.
 */
public class SimpleBlockExecutor implements BlockExecutor {

    private final AiStepCall aiCall;

    public SimpleBlockExecutor() {
        this(new AiStepCall());
    }

    SimpleBlockExecutor(AiStepCall aiCall) {
        this.aiCall = aiCall;
    }

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        BlockSettings settings = BlockSettings.of(block);
        String rawCommand = settings.text("command");
        if (rawCommand == null) {
            throw new WorkflowExecutionException("Simple block requires a 'command' in config");
        }
        Integer timeout = settings.number("timeout_seconds");
        boolean writableWorkspace = settings.flag("workspace_writable");

        String command = context.resolvePromptTemplate(rawCommand);
        Map<String, Object> config = block.getConfig();

        ToolPolicy tools = tools(config);
        WebAccess web = web(config);

        return aiCall.run(AiStepRequest.builder()
                .workingDirectory(context.getProjectPath())
                .prompt(command)
                .stepIndex(context.nextStepIndex())
                .systemPrompt(UntrustedInput.systemPrompt(context.getInstructionPrefix(), config))
                .model(context.modelFor(block))
                .timeoutSeconds(timeout)
                .providerId(context.providerFor(block))
                .effort(context.effortFor(block))
                .tools(tools)
                .web(web)
                .writableWorkspace(writableWorkspace)
                .build(), context);
    }

    /**
     * The block's {@code tools}; {@code none} when absent.
     *
     * @throws WorkflowExecutionException for a value the policy does not know, before any call
     */
    static ToolPolicy tools(Map<String, Object> config) {
        try {
            return ToolPolicy.of(config);
        } catch (IllegalArgumentException e) {
            throw new WorkflowExecutionException(e.getMessage());
        }
    }

    /**
     * The block's {@code web} and {@code web_hosts}; no web when absent.
     *
     * @throws WorkflowExecutionException for a value the switch does not know, before any call
     */
    static WebAccess web(Map<String, Object> config) {
        try {
            return WebAccess.of(config);
        } catch (IllegalArgumentException e) {
            throw new WorkflowExecutionException(e.getMessage());
        }
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("simple");
    }
}
