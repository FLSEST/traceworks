package traceworks.core.port;

import traceworks.core.model.WorkflowDefinition;

/**
 * Port to other workflows: finds the definition a {@code sub-workflow} or {@code foreach} block refers to with
 * its {@code workflow_id}.
 */
public interface WorkflowResolver {

    /**
     * The definition a reference names.
     *
     * @param workflowRef a package-relative path (such as {@code ../other-package}) or a workflow {@code id}
     *                    looked up on the operator's package search path
     * @return the definition, never {@code null}
     * @throws IllegalArgumentException if the reference names no workflow
     */
    WorkflowDefinition resolve(String workflowRef);

    /**
     * The resolver for the references inside {@code child}, a workflow this resolver returned and a run now starts as a
     * child. A package-relative path in a child's {@code workflow_id} is relative to the child's package, not to the
     * parent's; an adapter that resolves such paths returns a resolver based on the child's package here. The default
     * is this resolver, which is right for a resolver that only knows ids.
     */
    default WorkflowResolver forChild(WorkflowDefinition child) {
        return this;
    }

    /**
     * Whether {@code child}, a workflow this resolver returned, comes from another package than the run's, the one the
     * run was started with. A run resolves a {@code container_image} {@code ./images/<name>} against its own package
     * only, so such a child may not name one: it would run the run's image of that name, not its own package's. The
     * default is {@code false}, right for a resolver that knows no packages.
     *
     * @throws IllegalArgumentException if this resolver did not return {@code child}
     */
    default boolean fromAnotherPackage(WorkflowDefinition child) {
        return false;
    }

    /**
     * The package {@code child}, a workflow this resolver returned, was loaded from, for the record of the run that
     * starts it as a child: the reference this resolver was asked for and the package as loaded for that call.
     * {@code null} for a child of the run's own package as the run started with it, which the run's package digest
     * covers, and, the default, for a resolver that knows no packages. A child of the run's own package that was
     * loaded with another digest (the package changed while the run ran) is named like one of another package.
     *
     * @throws IllegalArgumentException if this resolver did not return {@code child}
     */
    default CalledPackage calledPackage(WorkflowDefinition child) {
        return null;
    }
}
