package traceworks.core.blocks;

import traceworks.core.context.ExecutionContext;
import traceworks.core.context.PlaceholderSyntax;
import traceworks.core.engine.BlockExecutor;
import traceworks.core.engine.GateSources;
import traceworks.core.engine.WorkflowExecutionException;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.BlockSettings;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.port.CommandRunner;
import traceworks.core.port.CommandRunner.CommandResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * {@code shell}: runs one command through the run's {@link traceworks.core.port.CommandRunner}, in
 * the image of {@code container_image}, or without one where the runner runs image-less commands ({@link ShellTarget}).
 * The workspace is read-only unless {@code workspace_writable: true}. The secrets the block names in {@code secrets}
 * reach the command as environment variables. With
 * {@code container_args} instead of {@code command} the image's own entrypoint runs as a tool
 * ({@link ContainerToolStep}). {@code checked_command} runs the command with {@code set -e -o pipefail} and puts a
 * gate derived from the exit code in front of its output ({@link CheckedCommandGate}).
 *
 * <p>Placeholders are resolved for a shell line: untrusted values are always single-quoted, with
 * {@code shell_escape_variables} every value. A cancel of the run kills the command: the runner registers its process
 * with the run's tracker.
 */
public class ShellBlockExecutor implements BlockExecutor {

    /** Timeout of a command without {@code timeout_seconds}. */
    static final int DEFAULT_TIMEOUT_SECONDS = 300;

    private final ContainerToolStep containerToolStep;

    /**
     * @param toolCacheDirectory where the tool mode keeps a persistent cache directory per image ({@code cache_dir});
     *                           {@code null} refuses the blocks that ask for one
     */
    public ShellBlockExecutor(Path toolCacheDirectory) {
        this(new ContainerToolStep(toolCacheDirectory));
    }

    ShellBlockExecutor(ContainerToolStep containerToolStep) {
        this.containerToolStep = containerToolStep;
    }

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        Map<String, Object> config = block.getConfig();
        BlockSettings settings = BlockSettings.of(block);
        String rawCommand = settings.text("command");
        if (config.containsKey("container_args")) {
            // a blank command (editor default) counts as unset, as in the analyzer's requiredOneOf
            if (rawCommand != null && !rawCommand.isBlank()) {
                throw new WorkflowExecutionException("Shell block takes either 'command' or 'container_args', not both");
            }
            return containerToolStep.execute(block, ContainerToolSpec.from(config, block.getName()), context);
        }
        if (rawCommand == null) {
            throw new WorkflowExecutionException("Shell block requires a 'command' in config");
        }
        // Every setting is read before anything runs: a wrong value fails the block (BlockSettings)
        boolean escapeVariables = settings.flag("shell_escape_variables");
        boolean checked = settings.flag("checked_command");
        int timeout = settings.number("timeout_seconds", DEFAULT_TIMEOUT_SECONDS);
        String checkCategory = settings.text("check_category");
        List<String> mounts = settings.texts("mounts");
        // Refuses the Docker socket, malformed mounts and secrets without a value before the command is resolved
        ShellTarget target = ShellTarget.of(config, () -> resolveMounts(mounts, context),
                context.isContainerRun(), context.getSecrets());

        // Untrusted variables (webhook payloads) are always escaped; shell_escape_variables escapes everything.
        String command = context.resolveShellTemplate(rawCommand, escapeVariables);
        if (checked) {
            command = "set -e -o pipefail\n" + command;
        }

        StepResult step = new StepResult();
        step.setStepIndex(context.nextStepIndex());
        step.setStartedAt(context.getClock().now());
        step.setInput(command);
        step.setStatus(StepStatus.RUNNING);

        CommandResult ran = context.getCommandRunner().run(target.request(command, context.getProjectPath(), timeout));
        ShellResult result = ShellResult.of(ran, timeout, context.secretValues());

        String output = result.exitCode() != 0
                ? result.output() + "\n\n[RECOVERY] " + buildRecoveryHint(result.exitCode(), command, ran)
                : result.output();
        if (checked) {
            output = CheckedCommandGate.decorate(output, result.exitCode(), checkCategory,
                    result.truncated(), result.didNotRun());
        }
        step.setOutput(output);
        step.setStatus(result.exitCode() == 0 ? StepStatus.COMPLETED : StepStatus.FAILED);
        step.setCompletedAt(context.getClock().now());
        step.setDurationMs(result.durationMs());
        step.setMetadata(Map.of(
                "exitCode", result.exitCode(),
                "checkedCommand", checked,
                "executionMode", target.executionMode()
        ));
        // A cut output's markers are no verdict; a checked command's header states the one of its exit code
        if (result.truncated() && !checked) {
            GateSources.noGateSource(step);
        }
        return step;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("shell");
    }

    /**
     * Takes the optional {@code mounts} config (a list of strings {@code host:container:ro|rw}, read through
     * {@link BlockSettings#texts}; {@code null} for none) and resolves
     * placeholders in them against the current execution context ({@link #resolveMount}). A mount passes no shell that
     * could take a reference, so an entry that names an untrusted value (untrusted input, a tainted block output, platform
     * data, text a model wrote) is refused, as in the arguments of a tool step: such a value could choose which host path the container
     * sees.
     *
     * @throws WorkflowExecutionException for an entry that names an untrusted value
     */
    static List<String> resolveMounts(List<String> list, ExecutionContext context) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<String> resolved = new ArrayList<>(list.size());
        for (String s : list) {
            if (s.isBlank()) continue;
            if (context.namesEscapedValue(s)) {
                throw new WorkflowExecutionException("mounts must not contain untrusted values (untrusted input,"
                        + " tainted block output, platform data, text a model wrote): " + s);
            }
            resolved.add(resolveMount(s, context));
        }
        return resolved;
    }

    /**
     * One {@code mounts} entry with its placeholders filled in. The host side names a host directory, so the path
     * variables take their host values there ({@link ExecutionContext#resolveHostTemplate}); the container side and the
     * mode are where the step sees it, so they take the step's values. The entry is split at the colons outside
     * placeholders; an entry that is not {@code host:container[:mode]} before it is resolved (a single placeholder)
     * resolves as a whole, with the step's values.
     */
    static String resolveMount(String entry, ExecutionContext context) {
        List<String> parts = colonParts(entry);
        if (parts.size() < 2 || parts.size() > 3) {
            return context.resolveTemplate(entry);
        }
        StringBuilder resolved = new StringBuilder(context.resolveHostTemplate(parts.getFirst()));
        for (String part : parts.subList(1, parts.size())) {
            resolved.append(':').append(context.resolveTemplate(part));
        }
        return resolved.toString();
    }

    /** {@code text} split at every colon outside a {@code {{ ... }}} placeholder. */
    private static List<String> colonParts(String text) {
        List<int[]> placeholders = new ArrayList<>();
        Matcher placeholder = PlaceholderSyntax.PLACEHOLDER.matcher(text);
        while (placeholder.find()) {
            placeholders.add(new int[] {placeholder.start(), placeholder.end()});
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            int at = i;
            if (text.charAt(i) == ':' && placeholders.stream().noneMatch(p -> p[0] < at && at < p[1])) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    /** @param ran how the command ended, as the runner reports it: the output limit it had */
    private String buildRecoveryHint(int exitCode, String command, CommandResult ran) {
        String firstToken = command.trim().split("\\s+")[0];
        return switch (exitCode) {
            case 124 -> "Timeout. Consider: (1) optimizing the command, (2) increasing `timeout_seconds` in block config, (3) splitting the task into smaller steps.";
            case 127 -> "Command not found. Verify the tool is installed and available in PATH. Try: `which " + firstToken + "`.";
            case 126 -> "Permission denied. Check file permissions: `chmod +x <path>`.";
            case 130 -> "Interrupted. Retry the command.";
            case CommandRunner.OUTPUT_LIMIT_EXIT_CODE -> "Output limit: a stream of the command went beyond "
                    + ran.outputLimit() + ". Write large output to a file in the scratch directory and print only what"
                    + " later steps need.";
            case 125 -> "The container did not start (Docker unreachable or the run was refused). See the message above.";
            case 1   -> "General failure (exit 1). See stderr above. Try running the command manually to reproduce.";
            default  -> "Exit " + exitCode + ": Command failed. See output above for details.";
        };
    }
}
