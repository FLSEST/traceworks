package traceworks.core.blocks;

import traceworks.core.context.ExecutionContext;
import traceworks.core.context.OutputBudget;
import traceworks.core.context.PlaceholderSyntax;
import traceworks.core.engine.BlockExecutor;
import traceworks.core.engine.CancelledSteps;
import traceworks.core.engine.ExecutionMode;
import traceworks.core.engine.GateSources;
import traceworks.core.engine.StepAiMetadata;
import traceworks.core.engine.WorkflowExecutionException;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.BlockSettings;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code switch}: resolves its {@code expression} and runs the blocks of the first case whose {@code value} matches
 * (alternatives separated by {@code |}), or the {@code default} blocks. Case blocks are {@code shell} or
 * {@code simple} blocks; each runs as an {@link InnerStep} with its own settings, moves {@code previous_output}, and a
 * named one is addressable downstream like a top-level block. A case block the run's cancel ended is
 * {@code CANCELLED} ({@link CancelledSteps}), no failed one, and so is the switch when the cancel cut its case short
 * (a case block cancelled, or case blocks left unrun): {@code on_failure: continue} goes on past a failure, never past
 * a cancel.
 *
 * <p>The expression takes the usual {@code {{ var }}} placeholders. Traceworks does not resolve the second syntax
 * {@code ${var}} and refuses an expression that uses it instead of matching no case. The run
 * admission refuses such a workflow before it starts; this check stays for a block that gets here anyway.
 *
 * <p>The case outputs it gathers count against the run's {@link OutputBudget}: a case block whose output would take
 * the run beyond it stays out of the step's output, no further case block runs, and the step fails with the budget's
 * note after what it gathered; its output then sets no gate.
 */
public class SwitchBlockExecutor implements BlockExecutor {

    private final WrapperCommands commands;

    public SwitchBlockExecutor() {
        this(new WrapperCommands());
    }

    SwitchBlockExecutor(WrapperCommands commands) {
        this.commands = commands;
    }

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        BlockSettings settings = BlockSettings.of(block);
        String expression = settings.text("expression");
        String unresolvable = PlaceholderSyntax.dollarPlaceholderProblem(expression);
        if (unresolvable != null) {
            throw new WorkflowExecutionException("The switch expression " + unresolvable);
        }
        List<Map<String, Object>> cases = settings.objects("cases");
        // Every case is read before any runs, so a malformed one fails the block whichever case matches
        List<String> caseValues = new ArrayList<>();
        List<List<BlockDefinition>> caseBlocks = new ArrayList<>();
        for (int i = 0; cases != null && i < cases.size(); i++) {
            BlockSettings entry = settings.entry("cases", i);
            caseValues.add(entry.text("value"));
            caseBlocks.add(nestedBlocks(entry, "blocks"));
        }
        List<BlockDefinition> defaults = nestedBlocks(settings, "default");

        StepResult result = new StepResult();
        result.setStepIndex(context.nextStepIndex());
        result.setStartedAt(context.getClock().now());

        String resolvedExpression = resolveExpression(expression, context);
        result.setInput("switch (" + resolvedExpression + ")");

        String matchedCase = null;
        List<BlockDefinition> matchedBlocks = null;
        for (int i = 0; i < caseValues.size(); i++) {
            if (matchesValue(resolvedExpression, caseValues.get(i))) {
                matchedCase = caseValues.get(i);
                matchedBlocks = caseBlocks.get(i);
                break;
            }
        }

        // The default when no case matched
        if (matchedBlocks == null && defaults != null) {
            matchedCase = "default";
            matchedBlocks = defaults;
        }

        if (matchedBlocks == null || matchedBlocks.isEmpty()) {
            result.setOutput("No matching case for: " + resolvedExpression);
            result.setStatus(StepStatus.COMPLETED);
            StepAiMetadata.put(result, "expression", resolvedExpression);
            StepAiMetadata.put(result, "matchedCase", "none");
            return finish(result, context);
        }

        OutputBudget budget = context.getOutputBudget();
        GatheredOutput outputs = new GatheredOutput(budget);
        boolean anyFailed = false;
        int ranBlocks = 0;
        boolean blockCancelled = false;
        for (BlockDefinition nestedBlock : matchedBlocks) {
            if (context.shouldStop()) break;

            StepResult blockResult = executeNestedBlock(nestedBlock, context);
            ranBlocks++;
            blockCancelled |= blockResult.getStatus() == StepStatus.CANCELLED;
            // The first nested AI block wins: provider and model of the case that ran
            StepAiMetadata.carryAi(result, blockResult);
            // A case output the limit cut is in the switch's output too
            GateSources.carry(result, blockResult);

            if (blockResult.getOutput() != null) {
                boolean fits = outputs.isEmpty() ? outputs.add(blockResult.getOutput())
                        : outputs.add("\n", blockResult.getOutput());
                if (!fits) {
                    // The case block's output would take the run beyond its output budget: it stays out, and the
                    // switch's output, which no longer holds every case output, sets no gate
                    GateSources.noGateSource(result);
                    break;
                }
                // A named case block is addressable as {{ block.<name>.output }} downstream, like a top-level block;
                // unnamed ones only move previous_output. In a tainted run the case output is shell-escaped
                // downstream, as in while.
                context.recordOutput(nestedBlock.getName(), blockResult.getOutput());
                // ...and usable as the source of a compression block, with the case step's own facts (status, exit
                // code, duration) in the header. The switch's own step is recorded afterwards by the engine, so
                // "previous" still means the switch.
                context.getSourceSteps().record(nestedBlock.getName(), blockResult);
            }
            if (blockResult.getStatus() == StepStatus.FAILED) {
                anyFailed = true;
                if (nestedBlock.shouldStopOnFailure()) break;
            }
        }

        result.setOutput(outputs.text());
        boolean cutShortByCancel = context.shouldStop() && (blockCancelled || ranBlocks < matchedBlocks.size());
        result.setStatus(cutShortByCancel ? StepStatus.CANCELLED
                : anyFailed ? StepStatus.FAILED : StepStatus.COMPLETED);
        if (budget.exceeded()) {
            budget.fail(result);
        }
        StepAiMetadata.put(result, "expression", resolvedExpression);
        StepAiMetadata.put(result, "matchedCase", matchedCase);
        return finish(result, context);
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("switch");
    }

    /** The expression with its {@code {{ var }}} placeholders resolved; {@code ${var}} stays literal. */
    String resolveExpression(String expression, ExecutionContext context) {
        return expression == null ? "" : context.resolveTemplate(expression);
    }

    /**
     * Whether the expression matches the case value, which may list alternatives separated by {@code |}
     * ({@code "error|critical"}). Both sides are compared trimmed: an AI block typically ends its answer with a
     * newline, and "GREEN\n" must hit the case GREEN instead of silently falling into the default. Only the
     * comparison trims; input and metadata of the step keep the raw value.
     */
    boolean matchesValue(String expression, String caseValue) {
        if (caseValue == null || expression == null) return false;
        String value = expression.trim();
        for (String alternative : caseValue.split("\\|")) {
            if (alternative.trim().equals(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The nested blocks in the list of objects {@code key} of {@code settings} (a case's or the default's);
     * {@code null} when absent.
     */
    private static List<BlockDefinition> nestedBlocks(BlockSettings settings, String key) {
        List<Map<String, Object>> maps = settings.objects(key);
        if (maps == null) return null;
        List<BlockDefinition> blocks = new ArrayList<>();
        for (int i = 0; i < maps.size(); i++) {
            blocks.add(deserializeBlock(settings.entry(key, i)));
        }
        return blocks;
    }

    /** A nested block from its config map. */
    BlockDefinition deserializeBlock(Map<String, Object> blockMap) {
        return deserializeBlock(BlockSettings.of("switch", blockMap));
    }

    private static BlockDefinition deserializeBlock(BlockSettings block) {
        Map<String, Object> blockConfig = block.object("config");
        return BlockDefinition.builder()
                .type(block.text("type"))
                .name(block.text("name"))
                .config(blockConfig != null ? new HashMap<>(blockConfig) : new HashMap<>())
                .build();
    }

    /**
     * Runs a nested block as an {@link InnerStep}, like the other wrappers run their command, but with the case
     * block's own settings, as a shell step also by its type, and named after it.
     */
    private StepResult executeNestedBlock(BlockDefinition nestedBlock, ExecutionContext context) {
        String type = nestedBlock.getType();
        // A missing or empty type is refused too: it would otherwise run as an AI prompt from config.command
        if (!"shell".equals(type) && !"simple".equals(type)) {
            Instant now = context.getClock().now();
            String typeLabel = type == null || type.isBlank() ? "(no type)" : type;
            String label = nestedBlock.getName() != null && !nestedBlock.getName().isBlank()
                    ? nestedBlock.getName() + " (" + typeLabel + ")"
                    : typeLabel;
            return StepResult.builder().stepIndex(context.nextStepIndex()).status(StepStatus.FAILED)
                    .input("switch: " + label)
                    .output("Block type '" + typeLabel + "' is not supported in switch, only shell and simple"
                            + " (block: " + label + ")")
                    .startedAt(now).completedAt(now).durationMs(0L).build();
        }
        Map<String, Object> nestedConfig = nestedBlock.getConfig();
        boolean isShell = ExecutionMode.isShell(nestedConfig) || "shell".equals(type);
        // Named after the case block, so its output is recorded under that name
        InnerStep step = InnerStep.of(nestedBlock, isShell, nestedBlock.getName());
        return step.run(commands, context, BlockSettings.of(nestedBlock).text("command"));
    }

    private static StepResult finish(StepResult result, ExecutionContext context) {
        result.setCompletedAt(context.getClock().now());
        result.setDurationMs(result.getCompletedAt().toEpochMilli() - result.getStartedAt().toEpochMilli());
        return result;
    }
}
