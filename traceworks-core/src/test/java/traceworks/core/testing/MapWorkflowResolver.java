package traceworks.core.testing;

import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.WorkflowResolver;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link WorkflowResolver} for tests: the workflows it knows, keyed by their id or by a reference of the test's
 * choice. It records every reference it was asked for; an unknown one is refused as the port demands.
 */
public final class MapWorkflowResolver implements WorkflowResolver {

    private final Map<String, WorkflowDefinition> workflows = new ConcurrentHashMap<>();
    private final List<String> requested = new CopyOnWriteArrayList<>();
    /** The workflows that come from another package than the run's, by identity. */
    private final java.util.Set<WorkflowDefinition> otherPackage =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** Knows {@code workflow} under its {@link WorkflowDefinition#id() id}. */
    public MapWorkflowResolver add(WorkflowDefinition workflow) {
        return add(workflow.id(), workflow);
    }

    /** Knows {@code workflow} under {@code ref}, such as a package-relative path. */
    public MapWorkflowResolver add(String ref, WorkflowDefinition workflow) {
        workflows.put(ref, workflow);
        return this;
    }

    /** Knows {@code workflow} under {@code ref} as a workflow of another package than the run's. */
    public synchronized MapWorkflowResolver addFromAnotherPackage(String ref, WorkflowDefinition workflow) {
        otherPackage.add(workflow);
        return add(ref, workflow);
    }

    @Override
    public synchronized boolean fromAnotherPackage(WorkflowDefinition child) {
        return otherPackage.contains(child);
    }

    @Override
    public WorkflowDefinition resolve(String workflowRef) {
        requested.add(workflowRef);
        WorkflowDefinition workflow = workflowRef == null ? null : workflows.get(workflowRef);
        if (workflow == null) {
            throw new IllegalArgumentException("No workflow '" + workflowRef + "'; known: " + workflows.keySet());
        }
        return workflow;
    }

    /** Every reference resolved so far, in order, unknown ones included. */
    public List<String> requested() {
        return List.copyOf(requested);
    }
}
