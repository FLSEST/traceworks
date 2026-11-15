package traceworks.core;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The packages of the core depend on each other in one direction: {@code blocks} on {@code engine}, {@code engine} on
 * {@code context}, never back. The block registry factory lives in {@code blocks} ({@code CoreBlocks}) for that reason.
 * The ports depend on neither {@code engine}, {@code context} nor the container policy ({@code container}), the
 * container policy on neither {@code engine}, {@code context} nor {@code blocks}, and the {@code model} on no other
 * part of the core,
 * so what an adapter sees of a run (its events, a run snapshot) is plain data.
 * The rule is about the main code, so only the main sources are read; a test may combine packages, as the engine's
 * end-to-end test runs the engine with the real blocks. The sources are found from this class's compiled location
 * ({@code <module>/target/test-classes}), not from the working directory, and a scan that finds no source file fails
 * instead of passing empty.
 */
class PackageDependencyTest {

    private static final String BASE = "traceworks/core/";

    @Test
    void engineDoesNotDependOnBlocks() {
        assertThat(referencesTo("engine", "blocks")).isEmpty();
    }

    @Test
    void contextDependsOnNeitherEngineNorBlocks() {
        assertThat(referencesTo("context", "engine")).isEmpty();
        assertThat(referencesTo("context", "blocks")).isEmpty();
    }

    @Test
    void portDependsOnNeitherEngineNorContextNorContainer() {
        assertThat(referencesTo("port", "engine")).isEmpty();
        assertThat(referencesTo("port", "context")).isEmpty();
        assertThat(referencesTo("port", "container")).isEmpty();
    }

    /** The container policy sees ports only: the engine hands it requests, never its context. */
    @Test
    void containerDependsOnNeitherEngineNorContextNorBlocks() {
        for (String other : List.of("engine", "context", "blocks")) {
            assertThat(referencesTo("container", other)).as("container -> %s", other).isEmpty();
        }
    }

    @Test
    void modelDependsOnNoOtherPartOfTheCore() {
        for (String other : List.of("context", "engine", "port", "blocks")) {
            assertThat(referencesTo("model", other)).as("model -> %s", other).isEmpty();
        }
    }

    /**
     * No two packages of the core depend on each other: a mutual dependency fails with both package
     * names. A package is the classes directly in its directory ({@code ai} and {@code ai.cli} are two), and it depends
     * on another when one of its source lines names a class there, in code or in a Javadoc link.
     */
    @Test
    void noTwoPackagesDependOnEachOther() {
        Map<String, Set<String>> graph = packageGraph();
        assertThat(graph).as("packages found; the scan must see them").containsKeys("ai", "port", "engine", "blocks");

        List<String> mutual = new ArrayList<>();
        graph.forEach((from, targets) -> targets.forEach(to -> {
            if (from.compareTo(to) < 0 && graph.getOrDefault(to, Set.of()).contains(from)) {
                mutual.add(from + " <-> " + to);
            }
        }));
        assertThat(mutual).as("packages that depend on each other").isEmpty();
    }

    /** Each main package of the core with the core packages its source lines name. */
    private static Map<String, Set<String>> packageGraph() {
        Path root = moduleDirectory().resolve("src/main/java").resolve(BASE);
        Map<String, Set<String>> graph = new TreeMap<>();
        for (Path file : javaFiles(root)) {
            Path relative = root.relativize(file.getParent());
            if (relative.toString().isEmpty()) {
                continue;   // the core's own package-info
            }
            String from = relative.toString().replace(File.separatorChar, '.');
            Set<String> targets = graph.computeIfAbsent(from, k -> new TreeSet<>());
            for (String line : lines(file)) {
                Matcher reference = CLASS_REFERENCE.matcher(line);
                while (reference.find()) {
                    if (!reference.group(1).equals(from)) {
                        targets.add(reference.group(1));
                    }
                }
            }
        }
        return graph;
    }

    /** A class of a core package: the package is every lowercase segment before the class name. */
    private static final Pattern CLASS_REFERENCE = Pattern.compile(
            "traceworks\\.core\\.((?:[a-z0-9_]+\\.)*?[a-z0-9_]+)\\.[A-Z]");

    /** The main source lines of package {@code from} that name package {@code to}. */
    private static List<String> referencesTo(String from, String to) {
        Path mainSources = moduleDirectory().resolve("src/main/java").resolve(BASE + from);
        assertThat(mainSources).as("main sources of package %s", from).isDirectory();
        List<Path> files = javaFiles(mainSources);
        assertThat(files).as("source files of package %s", from).isNotEmpty();

        String target = "traceworks.core." + to + ".";
        return files.stream()
                .flatMap(file -> lines(file).stream()
                        .filter(line -> line.contains(target))
                        .map(line -> file.getFileName() + ": " + line.strip()))
                .toList();
    }

    /** The module directory: two levels above the test classes directory this class was loaded from. */
    private static Path moduleDirectory() {
        try {
            Path testClasses = Path.of(PackageDependencyTest.class.getProtectionDomain().getCodeSource().getLocation()
                    .toURI());
            return testClasses.getParent().getParent();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("cannot locate the test classes", e);
        }
    }

    private static List<Path> javaFiles(Path directory) {
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
