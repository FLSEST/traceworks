package traceworks.core.blocks;

import traceworks.core.context.ChildRuns.ChildRun;
import traceworks.core.context.ExecutionContext;
import traceworks.core.context.PlaceholderSyntax;
import traceworks.core.engine.EventLogException;
import traceworks.core.engine.PlatformVariables;
import traceworks.core.engine.WorkflowExecutionException;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepResult;
import traceworks.core.model.TriggerSource;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.util.TruncationUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Starts one child run of another workflow and returns once it has ended: the mechanism behind the
 * {@code sub-workflow} block and {@code foreach} with {@code workflow_id}. One place for the guards both share: depth
 * limit, cycle check, the parent's variables without its platform data, the parent's taint and effective origin.
 *
 * <p>The reference is resolved through the run's {@link traceworks.core.port.WorkflowResolver} (a
 * package-relative path or a workflow id), and the child runs through the run's
 * {@link traceworks.core.context.ChildRuns}: in-process, with its own run id and the parent's run
 * control. The child is no separate run that the parent polls, and there is no limit on its duration besides the
 * child's own block timeouts.
 */
class SubWorkflowLauncher {

    static final int MAX_DEPTH = 5;

    /** The chain of workflow ids above a child, kept in its variables. */
    static final String PARENT_IDS = "__parent_workflow_ids";

    /**
     * Values every run gets from the engine or its own start, plus the ones an embedding system sets on every run: a
     * {@code foreach} {@code context} entry may not forge any of them. Pinned against the engine in
     * {@code SubWorkflowReservedNamesTest}.
     */
    static final Set<String> RUN_VARIABLES = Set.of("previous_output", "workflow_run_id", "workflow_id",
            "workflow_name", "project_id", "project_path", "execution_path", "package_dir", "scratch_dir", "register_dir",
            "workspace_source_sha", "model", "provider", "effort", "started_at", "run_timestamp", "workflow_version",
            "workflow_changed_since_last_run", "workflow_definition_hash", "trigger_source", "platform_scripts");

    /**
     * What one child run produced. {@code workflowName} and {@code subRunId} are null when the run never started
     * (guard, unknown workflow); {@code output} then carries the reason.
     *
     * @param workflowId the child workflow's id once it is resolved, else the reference as configured
     */
    record Outcome(String workflowId, String workflowName, String subRunId, RunStatus subRunStatus,
                   String output, boolean success) {

        static Outcome refused(String workflowId, String reason) {
            return new Outcome(workflowId, null, null, null, reason, false);
        }

        /** True once a child run exists: its output is a copy of the child's, see {@code derivedOutput}. */
        boolean started() {
            return subRunId != null;
        }

        /**
         * A child that ended COMPLETED_WITH_ERRORS (a {@code checked_command} failed in it) counts as success with
         * this warning; null for every other end.
         */
        String warning() {
            return subRunStatus == RunStatus.COMPLETED_WITH_ERRORS
                    ? "Sub-workflow run ended COMPLETED_WITH_ERRORS: a checked step failed in it, see the child run"
                    : null;
        }
    }

    /**
     * Why foreach's {@code context} may not set {@code name} in the child, or null: reserved keys ({@code __...}:
     * parent chain, taint, origin), the {@link PlatformVariables} (the child would drop the value), the run's own
     * values, and the loop's item variables. A mapping must not forge any of them.
     */
    static String reservedReason(String name, String itemVariable, Set<String> platformNames) {
        if (name.startsWith("__")) return "context may not set the reserved key " + name;
        if (platformNames.contains(name) || PlatformVariables.contains(name) || RUN_VARIABLES.contains(name)) {
            return "context may not set " + name + " — the platform sets it for every run";
        }
        if (name.equals(itemVariable) || name.equals(itemVariable + "_index") || name.startsWith(itemVariable + ".")) {
            return "context may not set " + name + " — the loop sets it per item";
        }
        return null;
    }

    /** The platform data names a value handed to a child must not stand in for: {@link PlatformVariables#NAMES}. */
    Set<String> platformVariableNames() {
        return PlatformVariables.NAMES;
    }

    /**
     * Resolves {@code {{ ... }}} in a configured workflow id against the parent's variables. Only trusted variables may
     * choose the child: an id taken from foreign input or a block output would let outside input pick any workflow
     * the run can reach. Returns null with the reason in {@code refusal}.
     */
    static String resolveWorkflowId(String configured, ExecutionContext context, StringBuilder refusal) {
        for (String key : PlaceholderSyntax.keysIn(configured)) {
            if ("previous_output".equals(key) || key.startsWith("block.") || context.isUntrusted(key)) {
                refusal.append("workflow_id may only use trusted context variables, not {{ ").append(key).append(" }}");
                return null;
            }
        }
        String resolved = context.resolveTemplate(configured).strip();
        if (resolved.isEmpty() || !PlaceholderSyntax.keysIn(resolved).isEmpty()) {
            refusal.append("workflow_id '").append(configured).append("' does not resolve to a workflow id");
            return null;
        }
        return resolved;
    }

    /**
     * Runs workflow {@code workflowRef} (already resolved from its placeholders) as a child of {@code context} and
     * returns once it has ended. Every failure (guard, unknown workflow, refused child) comes back as an unsuccessful
     * {@link Outcome}, except a failed write of the run's events: that {@link EventLogException} propagates, so the
     * engine stops the parent.
     */
    Outcome run(String workflowRef, ExecutionContext context) {
        String workflowId = workflowRef;
        String name = null;
        try {
            List<String> parentIds = parentIds(context);
            if (parentIds.size() >= MAX_DEPTH) {
                return Outcome.refused(workflowRef, "Maximum sub-workflow depth (" + MAX_DEPTH + ") exceeded");
            }
            if (parentIds.contains(workflowRef)) {
                return Outcome.refused(workflowRef, "Circular sub-workflow reference detected: " + workflowRef);
            }
            WorkflowDefinition child = context.getWorkflowResolver().resolve(workflowRef);
            // The chain holds ids: a relative path and the id of the same workflow are one cycle
            workflowId = child.id() != null && !child.id().isBlank() ? child.id() : workflowRef;
            if (parentIds.contains(workflowId)) {
                return Outcome.refused(workflowId, "Circular sub-workflow reference detected: " + workflowId);
            }
            name = child.name();
            ChildRun run = context.getChildRuns().run(child, childContext(context, parentIds, workflowId));
            if (run.modelAsked()) {
                // The child's output, and what it left in the workspace, may hold text a model wrote
                context.markModelAsked();
            }
            RunStatus end = run.status();
            return new Outcome(workflowId, name, run.runId(), end, buildOutput(run, context.secretValues()),
                    end == RunStatus.COMPLETED || end == RunStatus.COMPLETED_WITH_ERRORS);
        } catch (EventLogException e) {
            throw e;
        } catch (Exception e) {
            return new Outcome(workflowId, name, null, null, "Sub-workflow execution failed: " + e.getMessage(), false);
        }
    }

    /** {@value #PARENT_IDS} as a list of ids; anything else is no chain the guards can trust. */
    private static List<String> parentIds(ExecutionContext context) {
        Object raw = context.getVariables().get(PARENT_IDS);
        if (raw == null) return new ArrayList<>();
        if (!(raw instanceof List<?> list) || !list.stream().allMatch(String.class::isInstance)) {
            throw new WorkflowExecutionException(PARENT_IDS + " is not a list of workflow ids");
        }
        return list.stream().map(String.class::cast).toList();
    }

    /**
     * The parent's variables, without the {@link PlatformVariables}: the child would ignore them as inputs anyway. Their
     * taint still travels, so the child's shell blocks escape the same values.
     */
    private Map<String, Object> childContext(ExecutionContext context, List<String> parentIds, String workflowId) {
        Map<String, Object> childContext = new HashMap<>(context.getVariables());
        childContext.keySet().removeIf(PlatformVariables::contains);
        childContext.put("previous_output", context.getPreviousOutput() != null ? context.getPreviousOutput() : "");
        List<String> newParentIds = new ArrayList<>(parentIds);
        newParentIds.add(workflowId);
        childContext.put(PARENT_IDS, newParentIds);
        TriggerSource origin = TriggerSource.effectiveOfRun(context.getVariables());
        if (origin != null) {
            childContext.put(TriggerSource.PARENT_CONTEXT_KEY, origin.name());
        } else {
            childContext.remove(TriggerSource.PARENT_CONTEXT_KEY); // unknown origin counts as unattended
        }
        childContext.putAll(context.exportTaint());
        // What a step built from platform data stays platform data in the child; the register's own data is not
        // handed over (the child would not take it from its input)
        List<String> derivedFromPlatformData = context.platformVariableNames().stream()
                .filter(childContext::containsKey).sorted().toList();
        if (!derivedFromPlatformData.isEmpty()) {
            childContext.put(ExecutionContext.PLATFORM_KEYS_VARIABLE, derivedFromPlatformData);
        }
        return childContext;
    }

    /**
     * The child's last step output, or without one a status line per step; then the child's error on a line of its
     * own ({@code Error: ...}) unless the output already contains it. An earlier step's output must not hide why the
     * child failed: it may have failed outside its steps (it could not start, a block type was unknown, an executor
     * threw), and its error names the failed step otherwise.
     */
    private static String buildOutput(ChildRun run, java.util.Collection<String> keepWhole) {
        List<StepResult> steps = run.steps();
        StringBuilder sb = new StringBuilder();
        if (!steps.isEmpty() && steps.getLast().getOutput() != null) {
            sb.append(steps.getLast().getOutput());
        } else {
            sb.append("Sub-workflow completed with status: ").append(run.status()).append("\n");
            for (StepResult step : steps) {
                sb.append("[Step ").append(step.getStepIndex()).append("] ")
                        .append(step.getStatus())
                        .append(": ").append(TruncationUtil.head(step.getOutput(), 500, keepWhole)).append("\n");
            }
        }
        String error = run.error();
        if (error != null && !error.isBlank() && sb.indexOf(error) < 0) {
            if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') {
                sb.append('\n');
            }
            sb.append("Error: ").append(error).append("\n");
        }
        return sb.toString();
    }
}
