package traceworks.core.app;

import traceworks.core.analyzer.PackageImageRules;
import traceworks.core.container.ImageCatalog;
import traceworks.core.engine.NestedBlocks;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.LoadedPackage;
import traceworks.core.port.PackageImage;
import traceworks.core.port.PackageLoader;
import traceworks.core.record.EventChain;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a seal covers of a package: the seal digest, and why the package cannot be sealed.
 *
 * <p>The seal digest is the closure of the package: its own digest (every file of the package directory but the seal
 * file) and the digest of every package in another directory that its {@code sub-workflow} and {@code foreach}
 * blocks reach through a literal {@code workflow_id}, at any depth and in nested blocks too. A child inside the
 * package directory is part of the package's own digest. The children count by workflow id and digest, sorted, never
 * by path, so the digest is the same wherever the packages lie. The seal file a child package carries counts as a
 * file of the child: the child's own digest leaves it out, so it is hashed here.
 *
 * <p>A seal can pin only what the digest reaches. So these are violations: a {@code workflow_id} with a placeholder,
 * a literal one that names no loadable package, a {@code container_image} with a placeholder, a registry image
 * without a digest ({@code @sha256:} and 64 hex characters at its end), a Dockerfile of the package that names an
 * image without a digest (in {@code FROM}, {@code COPY --from=} or {@code RUN --mount=...,from=}), and a child workflow
 * under the package's top-level {@code .git/}, which the package digest leaves out. The built-in
 * images are not judged: they belong to the Traceworks version that runs the package.
 *
 * @param digest     the seal digest, lowercase hex
 * @param children   the workflow ids of the other packages the digest covers, sorted
 * @param violations why the package cannot be sealed, one text each; empty when it can
 * @param childPackages every package in another directory than the package's own that the walk reached, each once,
 *                      those inside the package directory too: the packages whose own seals a run must respect
 */
record SealClosure(String digest, List<String> children, List<String> violations,
                   List<LoadedPackage> childPackages) {

    private static final String PREFIX = "traceworks-closure-v1\n";

    SealClosure {
        children = List.copyOf(children);
        violations = List.copyOf(violations);
        childPackages = List.copyOf(childPackages);
    }

    /**
     * The closure of {@code pkg}.
     *
     * @param packagePath where a {@code workflow_id} that is an id is searched, as a run searches it
     */
    static SealClosure of(LoadedPackage pkg, List<Path> packagePath, PackageLoader loader) {
        Walk walk = new Walk(pkg);
        PackageWorkflowResolver resolver = new PackageWorkflowResolver(pkg, packagePath, loader);
        walk.visit(pkg.definition(), resolver, pkg);
        for (PackageImage image : pkg.images().values()) {
            for (String reference : PackageImageRules.unpinnedReferences(image.dockerfile())) {
                walk.violations.add("images/" + image.name() + "/Dockerfile names an image without a digest ("
                        + reference + "): name it as <image>@sha256:<digest>");
            }
        }
        StringBuilder text = new StringBuilder(PREFIX).append(pkg.digest()).append('\n');
        List<String> ids = new ArrayList<>();
        for (String child : walk.children) {
            text.append(child).append('\n');
            ids.add(child.substring(0, child.indexOf('\u0000')));
        }
        return new SealClosure(EventChain.sha256Hex(text.toString().getBytes(StandardCharsets.UTF_8)), ids,
                walk.violations, List.copyOf(walk.packages.values()));
    }

    /** The walk over the workflows the package reaches. */
    private static final class Walk {
        private final LoadedPackage root;
        /**
         * {@code <workflow id> NUL <package digest> NUL <hash of its seal file, or -} of every other package reached,
         * sorted. A child's digest leaves its own seal file out, and nothing judges that file for the caller, so the
         * caller's seal covers it like any other file of the child.
         */
        private final Set<String> children = new TreeSet<>();
        /** Every package reached that lies in another directory than the root's, by directory. */
        private final Map<java.nio.file.Path, LoadedPackage> packages = new java.util.LinkedHashMap<>();
        private final List<String> violations = new ArrayList<>();
        /** The package directories and workflow files already walked, so a cycle ends. */
        private final Set<String> seen = new HashSet<>();

        Walk(LoadedPackage root) {
            this.root = root;
        }

        void visit(WorkflowDefinition definition, PackageWorkflowResolver resolver, LoadedPackage owner) {
            if (!seen.add(owner.directory() + "\u0000" + definition.id())) {
                return;
            }
            String where = owner == root ? "" : " of the workflow " + definition.id() + " (" + owner.directory() + ")";
            for (BlockDefinition block : definition.blocks()) {
                if (block == null || block.getConfig() == null) {
                    continue;
                }
                block(block.getName(), block.getType(), block.getConfig(), where, resolver);
                NestedBlocks.visit(block.getConfig(), nested ->
                        block(nested.label(), nested.type(), nested.config(), where, resolver));
            }
        }

        private void block(String name, String type, Map<String, Object> config, String where,
                           PackageWorkflowResolver resolver) {
            if (config == null) {
                return;
            }
            String label = "the block '" + name + "'" + where;
            if (config.get("container_image") instanceof String image && !image.isBlank()) {
                if (image.contains("{{")) {
                    violations.add(label + " takes its container_image from a placeholder (" + image + "): a seal"
                            + " cannot pin an image that is chosen when the run runs");
                } else if (!image.startsWith(ImageCatalog.PACKAGE_IMAGE_PREFIX)
                        && !PackageImageRules.pinnedByDigest(image)) {
                    violations.add(label + " names the image " + image + " without a digest: name it as "
                            + image + "@sha256:<digest>");
                }
            }
            if (!"sub-workflow".equals(type) && !"foreach".equals(type)
                    || !(config.get("workflow_id") instanceof String ref) || ref.isBlank()) {
                return;
            }
            if (ref.contains("{{")) {
                violations.add(label + " takes its workflow_id from a placeholder (" + ref + "): a seal cannot"
                        + " cover a workflow that is chosen when the run runs");
                return;
            }
            WorkflowDefinition child;
            LoadedPackage owner;
            try {
                child = resolver.resolve(ref.strip());
                owner = resolver.packageOf(child);
            } catch (RuntimeException e) {
                violations.add(label + " names the workflow " + ref.strip() + ", which cannot be loaded, so a seal"
                        + " cannot cover it: " + e.getMessage());
                return;
            }
            if (!owner.directory().startsWith(root.directory())) {
                children.add((child.id() == null ? "" : child.id()) + "\u0000" + owner.digest() + "\u0000"
                        + (owner.sealJson() == null ? "-" : EventChain.sha256Hex(owner.sealJson())));
            } else if (!owner.directory().equals(root.directory())
                    && root.directory().relativize(owner.directory()).getName(0).toString().equals(".git")) {
                violations.add(label + " names the workflow " + ref.strip() + " under the package's .git directory,"
                        + " which the package digest leaves out, so a seal cannot cover it: move the workflow out"
                        + " of .git");
                return;
            }
            if (!owner.directory().equals(root.directory())) {
                packages.putIfAbsent(owner.directory(), owner);
            }
            visit(child, (PackageWorkflowResolver) resolver.forChild(child), owner);
        }
    }
}
