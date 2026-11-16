package traceworks.core.blocks;

import traceworks.core.ai.ToolPolicy;
import traceworks.core.port.WebAccess;
import traceworks.core.context.ExecutionContext;
import traceworks.core.context.UntrustedInput;
import traceworks.core.engine.ShellValues;
import traceworks.core.engine.CancelledSteps;
import traceworks.core.engine.SecretNames;
import traceworks.core.engine.WorkflowExecutionException;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.BlockSettings;
import traceworks.core.model.StepResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The inner command of a wrapper block (foreach, while, conditional, retry and a switch case) and how it runs: as a
 * shell step or as an AI step of its own, with the settings of the block it belongs to. The one copy of this call, so
 * a change to it (provider and model per block, execution mode, prompt taint) reaches all five wrappers.
 *
 * <p>A shell step carries the block's {@code container_image}, {@code mounts}, {@code network},
 * {@code workspace_writable} and {@code secrets}, and runs like a shell block: a cancel ends it, because its process
 * registers with the run. {@code mount_docker_socket: true} is refused when the step is made, before the wrapper runs
 * anything. An AI step gets the run's instruction prefix and the block's provider, model and effort, and
 * the block's {@code tools} and {@code workspace_writable} as a simple block does: without {@code tools} it has none,
 * and it sees the workspace read-only unless the block says otherwise. A step whose work the run's cancel ended is {@code CANCELLED}
 * ({@link CancelledSteps}), so no wrapper counts it as a failure.
 *
 * @param block           the block whose config and AI settings apply: the wrapper itself, or a switch case
 * @param shell           a shell step instead of an AI step
 * @param escapeVariables {@code shell_escape_variables}: quote every value in a shell command, not only untrusted ones
 * @param shellTimeout    timeout of a shell step in seconds
 * @param aiTimeout       timeout of an AI step in seconds, {@code null} for the provider's default
 * @param name            the name a shell step carries as its block name ({@code null}: unnamed); a switch case
 *                        passes its own
 */
record InnerStep(BlockDefinition block, boolean shell, boolean escapeVariables, int shellTimeout, Integer aiTimeout,
                 String name) {

    /** Timeout of a shell step without {@code timeout_seconds}. */
    static final int DEFAULT_SHELL_TIMEOUT_SECONDS = ShellBlockExecutor.DEFAULT_TIMEOUT_SECONDS;

    /**
     * The settings a shell step takes from the block are read here, so a wrong one fails the wrapper before it runs
     * anything.
     *
     * @throws WorkflowExecutionException for a shell step whose block asks for {@code mount_docker_socket: true}
     * @throws traceworks.core.model.InvalidSettingException for a setting of the wrong type
     */
    InnerStep {
        if (shell) {
            BlockSettings settings = BlockSettings.of(block);
            MountSpecs.refuseDockerSocket(settings.flag("mount_docker_socket"));
            settings.flag("network");
            settings.flag("workspace_writable");
            settings.text("container_image");
            settings.texts("mounts");
        }
    }

    /** The unnamed step of a wrapper's own command. */
    InnerStep(BlockDefinition block, boolean shell, boolean escapeVariables, int shellTimeout, Integer aiTimeout) {
        this(block, shell, escapeVariables, shellTimeout, aiTimeout, null);
    }

    /**
     * The step with the settings of {@code block}: {@code shell_escape_variables}, and {@code timeout_seconds} for
     * both kinds of step (a shell step defaults to {@value #DEFAULT_SHELL_TIMEOUT_SECONDS} seconds, an AI step to the
     * provider's default).
     */
    static InnerStep of(BlockDefinition block, boolean shell, String name) {
        BlockSettings settings = BlockSettings.of(block);
        boolean escapeVariables = settings.flag("shell_escape_variables");
        Integer timeout = settings.number("timeout_seconds");
        int shellTimeout = timeout != null ? timeout : DEFAULT_SHELL_TIMEOUT_SECONDS;
        return new InnerStep(block, shell, escapeVariables, shellTimeout, timeout, name);
    }

    /** {@code command} with its placeholders filled in: quoted for a shell step, as prompt text for an AI step. */
    String resolve(ExecutionContext context, String command) {
        if (shell) {
            traceworks.core.engine.RegisterFolderUse.inCommand(command, context);
        } else {
            traceworks.core.engine.RegisterFolderUse.inPrompt(command);
        }
        return shell
                ? context.resolveShellTemplate(command, escapeVariables)
                : context.resolvePromptTemplate(command);
    }

    /** Fills in {@code command} and runs it. */
    StepResult run(WrapperCommands commands, ExecutionContext context, String command) {
        return runResolved(commands, context, resolve(context, command));
    }

    /**
     * Runs a command that {@link #resolve} already filled in; retry resolves once and runs it per attempt.
     *
     * @throws WorkflowExecutionException when there is no command
     */
    StepResult runResolved(WrapperCommands commands, ExecutionContext context, String resolvedCommand) {
        if (resolvedCommand == null) {
            throw new WorkflowExecutionException("Block has no 'command' in config");
        }
        if (shell) {
            BlockDefinition shellBlock = shellBlock(block.getConfig(), resolvedCommand, context);
            shellBlock.setName(name);
            return CancelledSteps.mark(commands.shellStep(shellBlock, context.nextStepIndex(), shellTimeout, context),
                    context);
        }
        ToolPolicy tools = SimpleBlockExecutor.tools(block.getConfig());
        WebAccess web = SimpleBlockExecutor.web(block.getConfig());
        return CancelledSteps.mark(commands.aiStep(AiStepRequest.builder()
                .workingDirectory(context.getProjectPath())
                .prompt(resolvedCommand)
                .stepIndex(context.nextStepIndex())
                .systemPrompt(UntrustedInput.systemPrompt(context.getInstructionPrefix(), block.getConfig()))
                .model(context.modelFor(block))
                .timeoutSeconds(aiTimeout)
                .providerId(context.providerFor(block))
                .effort(context.effortFor(block))
                .tools(tools)
                .web(web)
                .writableWorkspace(BlockSettings.of(block).flag("workspace_writable"))
                .build(), context), context);
    }

    /**
     * The shell block an inner shell step runs: the resolved command plus the wrapper's {@code container_image},
     * {@code mount_docker_socket}, {@code network}, {@code workspace_writable}, {@code secrets} and {@code mounts},
     * with placeholders in the mounts resolved against the run, so a wrapper's command keeps its container
     * instead of silently running on the host.
     *
     * @throws WorkflowExecutionException for a mount that names an untrusted value
     */
    static BlockDefinition shellBlock(Map<String, Object> originalConfig, String resolvedCommand,
                                      ExecutionContext context) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("command", resolvedCommand);
        if (originalConfig.get("container_image") instanceof String image && !image.isBlank()) {
            config.put("container_image", image);
        }
        for (String key : List.of("mount_docker_socket", "network", "workspace_writable", SecretNames.KEY)) {
            Object value = originalConfig.get(key);
            if (value != null) {
                config.put(key, value);
            }
        }
        List<String> mounts = ShellBlockExecutor.resolveMounts(
                BlockSettings.of("shell", originalConfig).texts("mounts"), context);
        if (!mounts.isEmpty()) {
            config.put("mounts", mounts);
        }
        // Filled in now, for this step: a value may name the loop's item
        Map<String, String> values = ShellValues.resolve(originalConfig, context);
        if (!values.isEmpty()) {
            config.put(ShellValues.KEY, values);
        }
        return BlockDefinition.builder().type("shell").config(config).build();
    }
}
