package traceworks.core.blocks;

import traceworks.core.context.ExecutionContext;
import traceworks.core.context.OutputBudget;
import traceworks.core.context.RecordedItems;
import traceworks.core.engine.BlockExecutor;
import traceworks.core.engine.CancelledSteps;
import traceworks.core.engine.ExecutionMode;
import traceworks.core.engine.GateSources;
import traceworks.core.engine.StepAiMetadata;
import traceworks.core.engine.StepFailureSummary;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.BlockSettings;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.port.RunEvent.ForEachItemFinished;
import traceworks.core.port.RunEvent.ForEachItemStarted;
import traceworks.core.util.TruncationUtil;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code foreach}: runs its {@code command} once per item, with the item under {@code variable_name} (default
 * {@code item}), its index under {@code <variable_name>_index} and, for a JSON object item, its top-level fields under
 * {@code <variable_name>.<field>}. The items are the static {@code items} (a list or newline-separated text) or, with
 * {@code items_source: command}, the output lines of {@code items_command}; blank ones never count. Items from a
 * command carry the run's taint; static items are trusted. An {@code items_command} whose output went beyond the
 * runner's limit, that ran out of time, or that did not run (the runner's mark: a refusal, a start failure), fails
 * the block before the first item: its lines are only the first part of the list, or none of it, and the line that
 * reports the timeout or the refusal is no item.
 *
 * <p>A failed item fails the block unless {@code continue_on_error} is set; after {@code max_consecutive_failures}
 * failed items in a row (default 3, 0 for off) the loop stops and fails ({@link ForEachFailures}). Each item is
 * reported to the run as a {@link ForEachItemStarted} and a {@link ForEachItemFinished} event. An item the run's
 * cancel ended is {@code CANCELLED} ({@link CancelledSteps}), no failed item, and so is a loop the cancel cut short
 * (an item cancelled, or items left unrun); a loop whose every item ran before the cancel came keeps its status.
 *
 * <p>The outputs it gathers count against the run's {@link OutputBudget}: an item whose output would take the run
 * beyond it stays out of the step's output (its own event records it whole), the loop stops, and the step fails with
 * the budget's note after what it gathered; its output then sets no gate. The loop stops the same way after an item
 * whose child run went beyond the budget, and before an item whose variables (the item, its fields) do not fit; its
 * {@code context} mapping counts as the variables it sets. The item list of an {@code items_command} counts while
 * the loop runs: one that does not fit fails the step before the first item.
 *
 * <p>In a resumed run, the loop that was in flight takes over the items the record holds as finished
 * ({@link RecordedItems}) instead of running them: each is reported as a {@link ForEachItemFinished} that names the
 * earlier event ({@code resumedFrom}), without a {@link ForEachItemStarted}, and counts as a completed item with the
 * recorded output, so the step's output is the one of a single pass. The step's metadata says how many
 * ({@code itemsResumed}) and marks each in {@code items} ({@code resumed}).
 *
 * <p>A pause that parks the run ({@link ExecutionContext#parkForPause}) takes effect between two items: the loop
 * starts no further item and returns what it has. Its step is not finished, so the result is not reported; the
 * resume runs the loop again. A loop that runs a workflow per item does not park between its items.
 *
 * <p>With {@code workflow_id} instead of a {@code command}, each item runs that workflow as a child run
 * ({@link SubWorkflowLauncher}): the child sees the item variables and, per {@code context} entry, the resolved value
 * under its own name. Such a block runs no command per item, so it needs no {@code execution_mode}; every other
 * foreach must write it out ({@link ExecutionMode#isMissing}).
 */
public class ForEachBlockExecutor implements BlockExecutor {

    /** Upper bound for the per-item summary kept in the step metadata (keeps the record small). */
    static final int MAX_RECORDED_ITEMS = 500;

    private final WrapperCommands commands;
    private final SubWorkflowLauncher subWorkflows;

    public ForEachBlockExecutor() {
        this(new WrapperCommands());
    }

    ForEachBlockExecutor(WrapperCommands commands) {
        this(commands, new SubWorkflowLauncher());
    }

    ForEachBlockExecutor(WrapperCommands commands, SubWorkflowLauncher subWorkflows) {
        this.commands = commands;
        this.subWorkflows = subWorkflows;
    }

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        // A sub-workflow per item runs no command, so no hidden AI default can apply: it needs no execution_mode
        StepResult missingMode = RequiredExecutionMode.missing(block, context);
        if (missingMode != null) {
            return missingMode;
        }
        Settings settings = Settings.from(block);
        StringBuilder itemsFailure = new StringBuilder();
        List<String> items = collectItems(block.getConfig(), settings, context, itemsFailure);
        int totalItems = items == null ? 0 : items.size();

        StepResult aggregateResult = new StepResult();
        aggregateResult.setStepIndex(context.nextStepIndex());
        aggregateResult.setStartedAt(context.getClock().now());
        aggregateResult.setInput("foreach " + settings.variableName() + " in [" + totalItems + " items]");
        if (items == null) return refused(aggregateResult, itemsFailure.toString(), 0, context);

        String workflowId = null;
        if (settings.workflowId() != null) {
            StringBuilder refusal = new StringBuilder();
            workflowId = resolveSubWorkflow(settings, context, refusal);
            if (workflowId == null) return refused(aggregateResult, refusal.toString(), totalItems, context);
            // The id of the child definition replaces the reference once a child has started
            StepAiMetadata.put(aggregateResult, "subWorkflowId", workflowId);
            StepAiMetadata.put(aggregateResult, "subWorkflowRef", workflowId);
            // The output copies the child runs' outputs; their markers count in the child runs only
            StepAiMetadata.put(aggregateResult, "derivedOutput", true);
        }

        OutputBudget budget = context.getOutputBudget();
        // The list of an items_command is the run's own text while the loop runs: it counts until the loop ends
        long listHeld = settings.itemsFromCommand() ? listSize(items) : 0;
        if (!budget.take(listHeld)) {
            StepAiMetadata.put(aggregateResult, "totalItems", totalItems);
            StepAiMetadata.put(aggregateResult, "items", List.of());
            aggregateResult.setOutput("");
            budget.fail(aggregateResult);   // no item starts
            return completed(aggregateResult, context);
        }
        GatheredOutput outputs = new GatheredOutput(budget);
        List<Map<String, Object>> recordedItems = new ArrayList<>();
        ForEachFailures failures = new ForEachFailures(
                totalItems, settings.maxConsecutiveFailures(), settings.continueOnError(), context.secretValues());
        int stepIndex = aggregateResult.getStepIndex();
        Set<String> itemFields = Set.of();

        // A resumed run: what the record holds as finished of this loop. A child run is resumed with its parent's
        // step, so a loop of child runs takes nothing over.
        RecordedItems recorded = workflowId != null ? RecordedItems.NONE : context.takeRecordedItems();
        boolean childIdKnown = false;
        int finishedItems = 0;
        int resumedItems = 0;
        boolean itemCancelled = false;
        for (int i = 0; i < totalItems && !context.shouldStop(); i++) {
            // A pause parks the run between two items. Not in a loop of child runs: a resume would run every one of
            // them again, so that loop finishes and the run parks after it.
            if (workflowId == null && context.parkForPause(stepIndex)) break;
            String item = items.get(i);
            itemFields = setItemVariables(settings, context, item, i, itemFields);
            if (budget.exceeded()) break;   // the item's variables went beyond the budget: the item does not start
            RecordedItems.Completed taken = recorded.completed(stepIndex, i, item);
            StepResult itemResult;
            if (taken != null) {
                itemResult = takenOver(settings, context, taken);
                context.getEvents().emit(new ForEachItemFinished(stepIndex, i, item, totalItems,
                        StepStatus.COMPLETED, taken.output(), null, taken.recordedAt()));
                resumedItems++;
            } else {
                context.getEvents().emit(new ForEachItemStarted(stepIndex, i, item, totalItems));
                itemResult = workflowId != null
                        ? runWorkflow(workflowId, settings.childMapping(), context)
                        : settings.itemStep().run(commands, context, settings.command());
                context.getEvents().emit(new ForEachItemFinished(stepIndex, i, item, totalItems,
                        itemResult.getStatus(), itemResult.getOutput() != null ? itemResult.getOutput() : "",
                        subRunId(itemResult)));
            }
            finishedItems++;
            itemCancelled |= itemResult.getStatus() == StepStatus.CANCELLED;

            boolean fits = outputs.add("[", item, "]: ", itemResult.getOutput(), "\n");
            StepAiMetadata.carryAi(aggregateResult, itemResult);
            GateSources.carry(aggregateResult, itemResult);
            if (workflowId != null && itemResult.getMetadata() != null && !childIdKnown
                    && itemResult.getMetadata().get("subWorkflowId") instanceof String childId) {
                StepAiMetadata.put(aggregateResult, "subWorkflowId", childId);
                childIdKnown = true;
            }
            if (recordedItems.size() < MAX_RECORDED_ITEMS) {
                Map<String, Object> summary = recordedItem(i, item, itemResult, context.secretValues());
                if (taken != null) summary.put("resumed", true);
                recordedItems.add(summary);
            }
            failures.record(i, item, itemResult);
            if (!fits) {
                // The item's output is not in the step's output, so its gate lines can no longer be read whole
                GateSources.noGateSource(aggregateResult);
                break;
            }
            if (failures.aborted() || budget.exceeded()) break;   // exceeded: the item's child run went beyond it
        }
        budget.release(listHeld);

        StringBuilder tally = new StringBuilder();
        failures.report(aggregateResult, tally);
        if (!tally.isEmpty()) {
            outputs.note(tally.toString(), context.secretValues());   // why the loop ended, kept beside the budget's
        }
        StepAiMetadata.put(aggregateResult, "totalItems", totalItems);
        StepAiMetadata.put(aggregateResult, "items", List.copyOf(recordedItems));
        if (resumedItems > 0) {
            // Their commands, AI calls and token counts are in the record of the pass that ran them
            StepAiMetadata.put(aggregateResult, "itemsResumed", resumedItems);
        }
        aggregateResult.setOutput(outputs.text());
        boolean cutShortByCancel = context.shouldStop() && (itemCancelled || finishedItems < totalItems);
        aggregateResult.setStatus(cutShortByCancel ? StepStatus.CANCELLED
                : failures.failsTheBlock() ? StepStatus.FAILED : StepStatus.COMPLETED);
        if (budget.exceeded()) {
            budget.fail(aggregateResult);
        }
        return completed(aggregateResult, context);
    }

    /**
     * The result of an item a resumed loop takes over from the record instead of running it: completed, with the
     * recorded output. The loop treats it as it treats an item that ran, so its step ends as one pass would: the item
     * draws its step index, and its command is filled in although it does not run, because filling it in is what
     * marks the run as carrying untrusted input (platform data that reaches a command line or a prompt).
     */
    private static StepResult takenOver(Settings settings, ExecutionContext context, RecordedItems.Completed taken) {
        settings.itemStep().resolve(context, settings.command());
        Instant now = context.getClock().now();
        return StepResult.builder().stepIndex(context.nextStepIndex()).status(StepStatus.COMPLETED)
                .output(taken.output()).startedAt(now).completedAt(now).durationMs(0L).build();
    }

    /** The memory the items take ({@link OutputBudget#sizeOf(CharSequence)}). */
    private static long listSize(List<String> items) {
        long size = 0;
        for (String item : items) {
            size += OutputBudget.sizeOf(item);
        }
        return size;
    }

    /** {@code workflow_id} when the block sets one (not blank), else {@code null}. */
    private static String workflowIdOf(Map<String, Object> config) {
        String id = BlockSettings.of("foreach", config).text("workflow_id");
        return id != null && !id.isBlank() ? id : null;
    }

    /**
     * The settings of one foreach block, read once from its config.
     *
     * @param workflowId   {@code workflow_id}: one sub-workflow run per item instead of the command
     * @param childMapping {@code context}: child variable name to template, resolved per item
     * @param itemStep     the command as each item runs it (shell or AI)
     */
    private record Settings(String variableName, String command, boolean itemsFromCommand, boolean continueOnError,
                            int maxConsecutiveFailures, String workflowId, Map<String, Object> childMapping,
                            InnerStep itemStep) {

        static Settings from(BlockDefinition block) {
            Map<String, Object> config = block.getConfig();
            BlockSettings settings = BlockSettings.of(block);
            String variableName = settings.text("variable_name", "item");
            String command = settings.text("command");
            boolean continueOnError = settings.flag("continue_on_error");
            int maxConsecutiveFailures = settings.number("max_consecutive_failures",
                    ForEachFailures.DEFAULT_MAX_CONSECUTIVE_FAILURES);
            String workflowId = workflowIdOf(config);
            Map<String, Object> mapping = settings.object("context");
            Map<String, Object> childMapping = mapping != null ? mapping : Map.of();
            boolean itemsFromCommand = "command".equals(settings.text("items_source", "list"));
            // Read here, so a wrong one fails the block before an item runs
            settings.value("items", BlockSettings.ARRAY, BlockSettings.STRING);
            settings.text("items_command");
            return new Settings(variableName, command, itemsFromCommand, continueOnError, maxConsecutiveFailures,
                    workflowId, childMapping, InnerStep.of(block, ExecutionMode.isShell(config), null));
        }
    }

    /**
     * The items: the output lines of {@code items_command}, or the static {@code items}; {@code null} with the reason
     * in {@code failure} for an {@code items_command} whose output went beyond the limit, that timed out or that did
     * not run.
     */
    private List<String> collectItems(Map<String, Object> config, Settings settings, ExecutionContext context,
                                      StringBuilder failure) {
        if (!settings.itemsFromCommand()) return ForEachItems.staticItems(config.get("items"));
        traceworks.core.engine.RegisterFolderUse.inCommand(BlockSettings.of("foreach", config).text("items_command"),
                context);
        String itemsCommand = context.resolveShellTemplate(
                BlockSettings.of("foreach", config).text("items_command"), settings.itemStep().escapeVariables());
        // items_command always runs as a shell command: it collects the iteration targets, it is no AI work
        ShellResult listed = commands.runCommand(itemsCommand, settings.itemStep().shellTimeout(), context);
        if (context.shouldStop()) {
            // a list the cancel ended is only its first part, or none of it: the loop is cancelled, not empty
            failure.append(CancelledSteps.WHILE_RUNNING);
            return null;
        }
        if (listed.timedOut()) {
            failure.append("items_command failed: it timed out after ")
                    .append(settings.itemStep().shellTimeout()).append(" seconds");
            return null;
        }
        if (listed.truncated()) {
            failure.append("items_command failed: ").append(listed.truncationNote());
            return null;
        }
        if (listed.didNotRun()) {
            // the runner's refusal is no list: its text must not become an item
            failure.append("items_command failed: it did not run: ").append(listed.notRunReason());
            return null;
        }
        return ForEachItems.splitIntoItems(listed.output());
    }

    /**
     * The workflow each item runs, or {@code null} with the reason in {@code refusal}: a command excludes
     * {@code workflow_id}, a {@code context} entry must not name a reserved variable, and the id may only use trusted
     * variables.
     */
    private String resolveSubWorkflow(Settings settings, ExecutionContext context, StringBuilder refusal) {
        Set<String> platformNames = subWorkflows.platformVariableNames();
        String reserved = settings.childMapping().keySet().stream()
                .map(name -> SubWorkflowLauncher.reservedReason(name, settings.variableName(), platformNames))
                .filter(Objects::nonNull).findFirst().orElse(null);
        String command = settings.command();
        if (command != null && !command.isBlank()) refusal.append("command and workflow_id exclude each other");
        else if (reserved != null) refusal.append(reserved);
        else return SubWorkflowLauncher.resolveWorkflowId(settings.workflowId(), context, refusal);
        return null;
    }

    /**
     * One item as a run of workflow {@code workflowId}, through the same {@link SubWorkflowLauncher} as the
     * sub-workflow block. The child sees the item variables and, per {@code context} entry, the resolved value under
     * its own name; those derived values carry the run's taint like the item itself.
     */
    private StepResult runWorkflow(String workflowId, Map<String, Object> mapping, ExecutionContext context) {
        Instant start = context.getClock().now();
        mapping.forEach((name, template) ->
                context.setDerivedVariable(name, context.resolvePromptTemplate(String.valueOf(template))));
        SubWorkflowLauncher.Outcome outcome = subWorkflows.run(workflowId, context);
        Instant end = context.getClock().now();
        StepResult result = CancelledSteps.mark(StepResult.builder()
                .output(outcome.output())
                .status(outcome.success() ? StepStatus.COMPLETED : StepStatus.FAILED)
                .startedAt(start).completedAt(end).durationMs(end.toEpochMilli() - start.toEpochMilli())
                .build(), context);
        if (outcome.started()) {
            StepAiMetadata.put(result, "subRunId", outcome.subRunId());
            StepAiMetadata.put(result, "subWorkflowId", outcome.workflowId());
        }
        if (outcome.warning() != null) StepAiMetadata.put(result, "subRunWarning", outcome.warning());
        return result;
    }

    /**
     * Sets the item, its index and, for a JSON object item, its fields as variables; returns the field names, which
     * the next item clears unless it has them too. Items collected by {@code items_command} inherit the run's taint;
     * static items are trusted.
     */
    private static Set<String> setItemVariables(Settings settings, ExecutionContext context, String item, int index,
                                                Set<String> previousFields) {
        String variableName = settings.variableName();
        Map<String, Object> itemVariables = new LinkedHashMap<>();
        itemVariables.put(variableName, item);
        itemVariables.put(variableName + "_index", index);
        Map<String, String> fields = ForEachItems.fields(variableName, item);
        itemVariables.putAll(fields);
        // The fields of the previous item must not stand in for missing ones of this item
        previousFields.stream().filter(name -> !fields.containsKey(name)).forEach(context::removeVariable);
        itemVariables.forEach((name, value) -> {
            if (settings.itemsFromCommand()) context.setDerivedVariable(name, value);
            else context.setVariable(name, value);
        });
        return fields.keySet();
    }

    /** One item in the step metadata's {@code items} list. */
    private static Map<String, Object> recordedItem(int index, String item, StepResult itemResult,
                                                    java.util.Collection<String> keepWhole) {
        Map<String, Object> recorded = new LinkedHashMap<>(Map.of(
                "index", index,
                "item", TruncationUtil.head(item, ForEachFailures.ITEM_LABEL_MAX_CHARS, keepWhole),
                "status", itemResult.getStatus().name(),
                "durationMs", itemResult.getDurationMs() != null ? itemResult.getDurationMs() : 0L));
        String subRunId = subRunId(itemResult);
        if (subRunId != null) recorded.put("subRunId", subRunId);
        if (itemResult.getMetadata() != null && itemResult.getMetadata().get("subRunWarning") instanceof String warning) {
            recorded.put("subRunWarning", warning);
        }
        return recorded;
    }

    /** The run an item started as a sub-workflow; {@code null} for an item that ran the command. */
    private static String subRunId(StepResult itemResult) {
        return itemResult.getMetadata() != null && itemResult.getMetadata().get("subRunId") instanceof String id
                && !id.isEmpty() ? id : null;
    }

    /**
     * A {@code workflow_id} that names no workflow the run may start, or an {@code items_command} beyond the output
     * limit or out of time: the block fails before the first item. An {@code items_command} the run's cancel ended
     * cancels it ({@link CancelledSteps}).
     */
    private static StepResult refused(StepResult aggregateResult, String reason, int totalItems,
                                      ExecutionContext context) {
        aggregateResult.setOutput(reason);
        StepFailureSummary.put(aggregateResult, reason);
        StepAiMetadata.put(aggregateResult, "totalItems", totalItems);
        StepAiMetadata.put(aggregateResult, "items", List.of());
        aggregateResult.setStatus(StepStatus.FAILED);
        CancelledSteps.mark(aggregateResult, context);
        return completed(aggregateResult, context);
    }

    private static StepResult completed(StepResult aggregateResult, ExecutionContext context) {
        aggregateResult.setCompletedAt(context.getClock().now());
        aggregateResult.setDurationMs(
                aggregateResult.getCompletedAt().toEpochMilli() - aggregateResult.getStartedAt().toEpochMilli());
        return aggregateResult;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("foreach");
    }
}
