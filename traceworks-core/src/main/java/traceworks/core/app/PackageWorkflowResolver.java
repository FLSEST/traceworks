package traceworks.core.app;

import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.CalledPackage;
import traceworks.core.port.LoadedPackage;
import traceworks.core.port.PackageLoader;
import traceworks.core.port.WorkflowResolver;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves {@code workflow_id} references to workflow packages. A reference that starts with {@code .} or contains
 * {@code /} is a path relative to the current package; anything else is a workflow {@code id} searched in the
 * immediate subdirectories of the search path (those that hold a {@code workflow.json}).
 *
 * <p>The resolver remembers which package each definition it returned came from, so {@link #forChild} can root the
 * child's references at the child's own package, and {@link #fromAnotherPackage} can tell a child of the run's own
 * package (the root's directory) from one of another package. It remembers the reference it was asked for too, so
 * the record of a run can name the package of a child as it was called ({@link #calledPackage}) whenever that is
 * not the package the run started with.
 */
public final class PackageWorkflowResolver implements WorkflowResolver {

    private final LoadedPackage current;
    /** The package the run was started with, whose images {@code ./images/<name>} names. */
    private final LoadedPackage root;
    private final List<Path> searchPath;
    private final PackageLoader loader;
    private final Map<WorkflowDefinition, LoadedPackage> known;
    /** The reference each definition this resolver returned was asked for with. */
    private final Map<WorkflowDefinition, String> references;

    public PackageWorkflowResolver(LoadedPackage root, List<Path> searchPath, PackageLoader loader) {
        this(root, root, List.copyOf(searchPath), loader, new IdentityHashMap<>(), new IdentityHashMap<>());
    }

    private PackageWorkflowResolver(LoadedPackage current, LoadedPackage root, List<Path> searchPath,
                                    PackageLoader loader, Map<WorkflowDefinition, LoadedPackage> known,
                                    Map<WorkflowDefinition, String> references) {
        this.current = current;
        this.root = root;
        this.searchPath = searchPath;
        this.loader = loader;
        this.known = known;
        this.references = references;
        known.put(current.definition(), current);
    }

    @Override
    public WorkflowDefinition resolve(String workflowRef) {
        if (workflowRef == null || workflowRef.isBlank()) {
            throw new IllegalArgumentException("The workflow reference is empty");
        }
        LoadedPackage found = isPath(workflowRef) ? byPath(workflowRef) : byId(workflowRef);
        known.put(found.definition(), found);
        references.put(found.definition(), workflowRef);
        return found.definition();
    }

    @Override
    public WorkflowResolver forChild(WorkflowDefinition child) {
        return new PackageWorkflowResolver(owner(child), root, searchPath, loader, known, references);
    }

    /** Whether the child's package directory is another than the run's; both are absolute and normalised. */
    @Override
    public boolean fromAnotherPackage(WorkflowDefinition child) {
        return !owner(child).directory().equals(root.directory());
    }

    /**
     * The package of a child, as loaded for the call that returned {@code child}, whenever that is not what the
     * run's {@code run.started} names: a package of another directory, and also the run's own package when its
     * digest is no longer the one the run started with. Every call loads the package anew, so one that changed
     * between two calls, or while the run ran, is named as each child ran it.
     */
    @Override
    public CalledPackage calledPackage(WorkflowDefinition child) {
        LoadedPackage owner = owner(child);
        if (owner.directory().equals(root.directory()) && java.util.Objects.equals(owner.digest(), root.digest())) {
            return null;
        }
        return new CalledPackage(references.get(child), owner.directory().toString(), owner.digest(),
                owner.workflowJson());
    }

    /**
     * The package a definition this resolver returned was loaded from.
     *
     * @throws IllegalArgumentException for a definition this resolver did not return
     */
    public LoadedPackage packageOf(WorkflowDefinition child) {
        return owner(child);
    }

    private LoadedPackage owner(WorkflowDefinition child) {
        LoadedPackage owner = known.get(child);
        if (owner == null) {
            throw new IllegalArgumentException("Not a workflow this resolver returned: " + child.id());
        }
        return owner;
    }

    private static boolean isPath(String ref) {
        return ref.startsWith(".") || ref.contains("/");
    }

    private LoadedPackage byPath(String ref) {
        Path target = current.directory().resolve(ref).normalize();
        try {
            return loader.load(target);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Workflow '" + ref + "' cannot be loaded from " + target
                    + " (relative to " + current.directory() + "): " + e.getMessage(), e);
        }
    }

    private LoadedPackage byId(String id) {
        List<Path> matches = new ArrayList<>();
        List<Path> unreadable = new ArrayList<>();
        for (Path dir : searchPath) {
            for (Path candidate : loader.packagesIn(dir)) {
                Optional<String> candidateId = loader.idOf(candidate);
                if (candidateId.isEmpty()) unreadable.add(candidate);
                else if (candidateId.get().equals(id)) matches.add(candidate);
            }
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("Workflow '" + id + "' not found; looked in the package path "
                    + (searchPath.isEmpty() ? "(empty)" : searchPath.toString())
                    + (unreadable.isEmpty() ? "" : "; skipped unreadable packages: " + unreadable));
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("Workflow id '" + id + "' is ambiguous: found in "
                    + matches.get(0) + " and " + matches.get(1));
        }
        return loader.load(matches.get(0));
    }
}
