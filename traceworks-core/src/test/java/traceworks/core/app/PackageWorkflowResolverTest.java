package traceworks.core.app;

import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.LoadedPackage;
import traceworks.core.port.PackageLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The resolver on a fake loader that reads the id from a one-line {@code workflow.json}. */
class PackageWorkflowResolverTest {

    @TempDir
    Path tmp;

    private final java.util.function.Function<Path, LoadedPackage> rawLoad = location -> {
        Path dir = location.toAbsolutePath().normalize();
        try {
            String id = Files.readString(dir.resolve("workflow.json")).strip();
            return new LoadedPackage(dir, new WorkflowDefinition(id, id, null, List.of(), null, null, null, null,
                    null, null, null, null, null), new byte[0], "digest", null, null);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot load " + dir, e);
        }
    };

    private final PackageLoader fake = new PackageLoader() {
        @Override
        public LoadedPackage load(Path location) {
            return rawLoad.apply(location);
        }

        @Override
        public List<Path> packagesIn(Path directory) {
            try (var children = Files.list(directory)) {
                return children.filter(c -> Files.isRegularFile(c.resolve("workflow.json"))).sorted().toList();
            } catch (IOException e) {
                return List.of();
            }
        }

        @Override
        public Optional<String> idOf(Path packageDirectory) {
            try {
                String id = Files.readString(packageDirectory.resolve("workflow.json")).strip();
                return id.equals("UNREADABLE") ? Optional.empty() : Optional.of(id);
            } catch (IOException e) {
                return Optional.empty();
            }
        }
    };

    private Path pkg(Path parent, String dirName, String id) throws IOException {
        Path dir = Files.createDirectories(parent.resolve(dirName));
        Files.writeString(dir.resolve("workflow.json"), id);
        return dir;
    }

    private PackageWorkflowResolver resolverAt(Path rootDir, Path... searchPath) {
        return new PackageWorkflowResolver(rawLoad.apply(rootDir), List.of(searchPath), fake);
    }

    private Path root;

    @BeforeEach
    void setUp() throws IOException {
        root = pkg(tmp, "root", "root-id");
    }

    @Test
    void relativePath_resolvesAgainstTheCurrentPackage() throws IOException {
        pkg(tmp, "sibling", "sibling-id");

        assertThat(resolverAt(root).resolve("../sibling").id()).isEqualTo("sibling-id");
    }

    @Test
    void id_isFoundOnTheSearchPath() throws IOException {
        Path lib = Files.createDirectories(tmp.resolve("lib"));
        pkg(lib, "a", "id-a");
        pkg(lib, "b", "id-b");

        assertThat(resolverAt(root, lib).resolve("id-b").id()).isEqualTo("id-b");
    }

    @Test
    void unknownReference_namesWhereItLooked() throws IOException {
        Path lib = Files.createDirectories(tmp.resolve("lib"));
        PackageWorkflowResolver resolver = resolverAt(root, lib);

        assertThatThrownBy(() -> resolver.resolve("nope"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'nope'")
                .hasMessageContaining(lib.toString());
        assertThatThrownBy(() -> resolver.resolve("../missing"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'../missing'")
                .hasMessageContaining(root.toString());
    }

    @Test
    void duplicateId_namesBothPackages() throws IOException {
        Path lib = Files.createDirectories(tmp.resolve("lib"));
        Path one = pkg(lib, "one", "same");
        Path two = pkg(lib, "two", "same");

        assertThatThrownBy(() -> resolverAt(root, lib).resolve("same"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(one.toString()).hasMessageContaining(two.toString());
    }

    @Test
    void child_resolvesItsRelativePathsAgainstItsOwnPackage() throws IOException {
        Path sub = pkg(tmp.resolve("tree"), "child", "child-id");
        pkg(sub, "inner", "inner-id");
        PackageWorkflowResolver resolver = resolverAt(root);
        WorkflowDefinition child = resolver.resolve("../tree/child");

        WorkflowDefinition inner = resolver.forChild(child).resolve("./inner");

        assertThat(inner.id()).isEqualTo("inner-id");
        assertThatThrownBy(() -> resolver.resolve("./inner")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unreadableSibling_doesNotBlockALookup_andIsNamedWhenNothingMatches() throws IOException {
        Path lib = Files.createDirectories(tmp.resolve("lib"));
        Path broken = pkg(lib, "broken", "UNREADABLE");
        pkg(lib, "good", "id-good");
        PackageWorkflowResolver resolver = resolverAt(root, lib);

        assertThat(resolver.resolve("id-good").id()).isEqualTo("id-good");
        assertThatThrownBy(() -> resolver.resolve("other")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skipped unreadable packages").hasMessageContaining(broken.toString());
    }

    @Test
    void forChild_ofAForeignDefinition_isRefused() {
        WorkflowDefinition foreign = new WorkflowDefinition("x", "x", null, List.of(), null, null, null, null,
                null, null, null, null, null);

        assertThatThrownBy(() -> resolverAt(root).forChild(foreign)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolverAt(root).fromAnotherPackage(foreign))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A child in a directory of its own is another package than the run's, at any depth; one whose path leads back to
     * the run's package directory is the run's own.
     */
    @Test
    void fromAnotherPackage_comparesTheChildsPackageWithTheRunsPackage() throws IOException {
        Path lib = Files.createDirectories(tmp.resolve("lib"));
        pkg(root, "child", "child-id");
        pkg(lib, "shared", "shared-id");
        PackageWorkflowResolver resolver = resolverAt(root, lib);

        WorkflowDefinition child = resolver.resolve("./child");
        WorkflowDefinition byId = resolver.resolve("shared-id");
        WorkflowDefinition self = resolver.resolve(".");
        WorkflowDefinition back = resolver.forChild(child).resolve("..");

        assertThat(resolver.fromAnotherPackage(child)).as("./child, a package below the run's").isTrue();
        assertThat(resolver.fromAnotherPackage(byId)).as("a package on the search path").isTrue();
        assertThat(resolver.fromAnotherPackage(self)).as("the run's own package").isFalse();
        assertThat(resolver.forChild(child).fromAnotherPackage(back)).as("back in the run's package").isFalse();
    }

    /**
     * What a run's record names of a child of another package: the reference as asked for and the package as loaded
     * for that call. A child of the run's own package has none: the run's package digest covers it.
     */
    @Test
    void calledPackage_namesTheReferenceAndThePackageOfAChildOfAnotherPackage() throws IOException {
        Path lib = Files.createDirectories(tmp.resolve("lib"));
        Path childDir = pkg(root, "child", "child-id");
        Path sharedDir = pkg(lib, "shared", "shared-id");
        PackageWorkflowResolver resolver = resolverAt(root, lib);

        WorkflowDefinition child = resolver.resolve("./child");
        WorkflowDefinition again = resolver.resolve("child/../child");
        WorkflowDefinition byId = resolver.resolve("shared-id");
        WorkflowDefinition self = resolver.resolve(".");
        WorkflowDefinition back = resolver.forChild(child).resolve("..");
        WorkflowDefinition deeper = resolver.forChild(child).resolve("../../lib/shared");

        assertThat(resolver.calledPackage(child).reference()).isEqualTo("./child");
        assertThat(resolver.calledPackage(child).directory()).isEqualTo(childDir.toString());
        assertThat(resolver.calledPackage(child).digest()).isEqualTo(resolver.packageOf(child).digest());
        assertThat(resolver.calledPackage(child).workflowJson()).isSameAs(resolver.packageOf(child).workflowJson());
        assertThat(resolver.calledPackage(again).reference()).as("each call by its own reference")
                .isEqualTo("child/../child");
        assertThat(resolver.calledPackage(byId).reference()).isEqualTo("shared-id");
        assertThat(resolver.calledPackage(byId).directory()).isEqualTo(sharedDir.toString());
        assertThat(resolver.calledPackage(self)).as("the run's own package").isNull();
        assertThat(resolver.forChild(child).calledPackage(back)).as("back in the run's package").isNull();
        assertThat(resolver.calledPackage(deeper).reference()).as("known to the resolver of every run of the tree")
                .isEqualTo("../../lib/shared");
        WorkflowDefinition foreign = new WorkflowDefinition("x", "x", null, List.of(), null, null, null, null,
                null, null, null, null, null);
        assertThatThrownBy(() -> resolver.calledPackage(foreign)).isInstanceOf(IllegalArgumentException.class);
    }
}
