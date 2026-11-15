package traceworks.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Containers start only on purpose: {@code mvn verify} starts none, {@code mvn -Pcontainers verify} and
 * {@code mvn -Pit verify} do. The build keeps that with one rule, the JUnit tag {@code container}, which Surefire
 * leaves out unless one of the two profiles is on (the root {@code pom.xml}). This test keeps the rule from being
 * forgotten: it reads the test sources of every module and fails when a file reaches the container runtime of the
 * machine without the tag, when a tagged class can skip instead of fail, and when the rule is gone from the build.
 *
 * <p>It reads source text, so it knows the ways this code base reaches a runtime ({@link #runtimeUses}), not every
 * way there could be. The module {@code integration-tests} is not read: all of it starts containers, and all of it
 * is skipped unless {@code -Pit} is on.
 */
class ContainerTagGuardTest {

    /** The repository: the module directory is the working directory of Surefire. */
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    private static final String TAG = "@Tag(\"container\")";

    /**
     * Files that name the runtime and are no container tests, each with its reason. A helper is no test class: the
     * classes that call it are found by its name.
     */
    private static final Map<String, String> NOT_A_CONTAINER_TEST = Map.of(
            "ContainerTests.java", "the helper of the adapters' container tests",
            "ContainerRuns.java", "the helper of the command line's container tests",
            "ContainerTagGuardTest.java", "this test, which names what it looks for",
            "RuntimeDetectionTest.java", "detect() only with names it refuses before any client is started");

    private static final Pattern CLIENT_PROCESS = Pattern.compile("new ProcessBuilder\\([^;]*\"(?:docker|podman)\"");
    private static final Pattern HELPER = Pattern.compile("(?<![A-Za-z0-9_])(ContainerTests|ContainerRuns)\\.");
    private static final Pattern ASSUMPTION = Pattern.compile("(?<![A-Za-z0-9_.])assume[A-Z][A-Za-z]*\\(|Assumptions\\.");
    private static final String CLI_MAIN = "traceworks.cli.main.Main";

    // ---- what counts as reaching the runtime ----

    @Test
    void detectingTheRuntimeOfTheMachine_isAUse() {
        assertThat(runtimeUses("runtime = RuntimeDetection.detect(null, home);")).hasSize(1);
        assertThat(runtimeUses("int code = run(args, RuntimeSource.MACHINE);")).hasSize(1);
        assertThat(runtimeUses("RuntimeDetection.detectAmong(List.of(fake.toString()), dir);")).isEmpty();
        assertThat(runtimeUses("run(args, RuntimeSource.NONE); run(args, RuntimeSource.of(fake));")).isEmpty();
    }

    @Test
    void startingTheClient_isAUse() {
        assertThat(runtimeUses("new ProcessBuilder(\"docker\", \"ps\").start();")).hasSize(1);
        assertThat(runtimeUses("new ProcessBuilder(List.of(\n    \"podman\", \"version\")).start();")).hasSize(1);
        assertThat(runtimeUses("new ProcessBuilder(\"git\", \"init\").start(); String name = \"docker\";")).isEmpty();
    }

    @Test
    void callingAHelperOfTheContainerTests_isAUse() {
        assertThat(runtimeUses("runtime = ContainerTests.runtime(home);")).hasSize(1);
        assertThat(runtimeUses("Result run = ContainerRuns.exec(home, work, \"run\");")).hasSize(1);
        assertThat(runtimeUses("import static traceworks.cli.ContainerRuns.exec;")).hasSize(1);
        assertThat(runtimeUses("MyContainerRuns.exec(); FakeContainerTests.all();")).isEmpty();
    }

    /** {@code Main.run} without a runtime source, or with {@code null} as one, finds the runtime of the machine. */
    @Test
    void theCommandLineWithoutARuntimeSource_isAUse() {
        String imported = "import " + CLI_MAIN + ";\n";
        assertThat(runtimeUses(imported + "Main.run(args, out, err, Map.of(\"A\", \"b,c\"), work);")).hasSize(1);
        assertThat(runtimeUses(imported + "Main.run(args, out, err, env, work, null);")).hasSize(1);
        assertThat(runtimeUses(imported + "Main.run(args, out, err, env, work, null, null);")).hasSize(1);
        assertThat(runtimeUses(imported + "Main.main(new String[] {\"runs\"});")).hasSize(1);
        assertThat(runtimeUses(imported + "Main.run(new String[] {\"run\", pkg}, out, err, Map.of(\"A\", \"b\"),\n"
                + "        work, null, RuntimeSource.NONE);")).isEmpty();
        assertThat(runtimeUses(imported + "Main.run(args, out, err, env, work, console(in, 1), runtime, reader);"))
                .isEmpty();
        assertThat(runtimeUses(CLI_MAIN + ".run(args, out, err, env, temp);")).hasSize(1);
        assertThat(runtimeUses(CLI_MAIN + ".run(args, out, err, env, temp, null, RuntimeSource.NONE);")).isEmpty();
        assertThat(runtimeUses("package traceworks.cli.main;\nMain.run(args, out, err, env, work);"))
                .hasSize(1);
    }

    /** Another module's own {@code Main} takes other arguments and starts nothing. */
    @Test
    void aMainOfAnotherPackage_isNoUse() {
        assertThat(runtimeUses("import traceworks.selfaudit.build.Main;\nMain.run(args, out, err);"))
                .isEmpty();
        assertThat(runtimeUses("import x.Main;\nMain.run(args, in, out, err);\n" + CLI_MAIN
                + ".run(a, out, err, env, temp, null, RuntimeSource.NONE);")).isEmpty();
    }

    @Test
    void aClassIsTaggedByTheAnnotationOnly() {
        assertThat(tagged("@Tag(\"container\")\nclass ATest {")).isTrue();
        assertThat(tagged("@Tag(\"slow\")\nclass ATest { // container")).isFalse();
    }

    @Test
    void anAssumptionIsFound_aRequirementIsNot() {
        assertThat(assumes("assumeTrue(id.isPresent(), \"not built\");")).isTrue();
        assertThat(assumes("Assumptions.assumeFalse(broken);")).isTrue();
        assertThat(assumes("Assumptions.abort(\"no\");")).isTrue();
        assertThat(assumes("ContainerPrecondition.require(id.isPresent(), \"not built\", \"build it\");")).isFalse();
        assertThat(assumes("\"a line with assumeTrue(docker()) in a string of a.assumeTrue(x)\"")).isTrue();
    }

    // ---- the repository ----

    @Test
    void everyTestClassThatReachesTheRuntime_carriesTheTag() throws IOException {
        Map<String, String> sources = testSources();
        Map<String, List<String>> untagged = new TreeMap<>();
        sources.forEach((file, source) -> {
            List<String> uses = runtimeUses(source);
            String name = Path.of(file).getFileName().toString();
            if (!uses.isEmpty() && !tagged(source) && !NOT_A_CONTAINER_TEST.containsKey(name)) {
                untagged.put(file, uses);
            }
        });

        assertThat(sources).as("the test sources below %s", ROOT).hasSizeGreaterThan(300);
        assertThat(untagged).as("These test classes reach the container runtime of the machine, so mvn verify would"
                + " start containers. Add @Tag(\"container\") to each (it then runs with -Pcontainers and -Pit only),"
                + " or give it a fake runtime (RuntimeSource.NONE, RuntimeSource.of(new FakeContainerRuntime()))")
                .isEmpty();
    }

    /** A container test that skips passes a build that asked for it, and nobody sees what was missing. */
    @Test
    void noTaggedClassCanSkip() throws IOException {
        Map<String, String> sources = testSources();
        List<String> taggedFiles = sources.keySet().stream().filter(file -> tagged(sources.get(file))
                && !Path.of(file).getFileName().toString().equals("ContainerTagGuardTest.java")).toList();
        List<String> helpers = sources.keySet().stream().filter(file -> Set.of("ContainerTests.java",
                "ContainerRuns.java").contains(Path.of(file).getFileName().toString())).toList();

        assertThat(taggedFiles).as("the container tests").hasSizeGreaterThanOrEqualTo(6);
        assertThat(helpers).as("the helpers of the container tests").hasSize(2);
        assertThat(taggedFiles).as("A container test uses ContainerPrecondition.require, which fails in a build that"
                + " asked for the container tests, and never an assumption, which skips").noneMatch(
                file -> assumes(sources.get(file)));
        assertThat(helpers).noneMatch(file -> assumes(sources.get(file)));
    }

    @Test
    void theBuildLeavesTheTagOut_unlessAProfileAsksForIt() throws IOException {
        String root = Files.readString(ROOT.resolve("pom.xml"), StandardCharsets.UTF_8);
        String suite = Files.readString(ROOT.resolve("integration-tests/pom.xml"), StandardCharsets.UTF_8);

        assertThat(root).contains("<traceworks.test.excludedGroups>container</traceworks.test.excludedGroups>",
                "<excludedGroups>${traceworks.test.excludedGroups}</excludedGroups>",
                "<traceworks.containers.required>false</traceworks.containers.required>",
                "<traceworks.containers.required>${traceworks.containers.required}</traceworks.containers.required>");
        for (String profile : List.of("containers", "it")) {
            assertThat(root).as("profile %s", profile).containsPattern("<id>" + profile + "</id>\\s*<properties>\\s*"
                    + "<traceworks.test.excludedGroups/>\\s*"
                    + "<traceworks.containers.required>true</traceworks.containers.required>");
        }
        assertThat(suite).contains("<traceworks.it.skip>true</traceworks.it.skip>",
                "<skip>${traceworks.it.skip}</skip>");
        assertThat(suite).doesNotContain("excludedGroups");
    }

    // ---- reading ----

    /** The Java test sources of every module but {@code integration-tests}, by path below the repository. */
    private static Map<String, String> testSources() throws IOException {
        Map<String, String> sources = new TreeMap<>();
        Set<String> skipped = Set.of("target", ".git", ".claude", "node_modules", "integration-tests");
        Files.walkFileTree(ROOT, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return !dir.equals(ROOT) && skipped.contains(dir.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String relative = ROOT.relativize(file).toString();
                if (relative.endsWith(".java") && relative.contains("src/test/java/")) {
                    sources.put(relative, Files.readString(file, StandardCharsets.UTF_8));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return sources;
    }

    static boolean tagged(String source) {
        return source.contains(TAG);
    }

    static boolean assumes(String source) {
        return ASSUMPTION.matcher(source).find();
    }

    /** The ways {@code source} reaches the container runtime of the machine, as sentences; empty when it has none. */
    static List<String> runtimeUses(String source) {
        List<String> uses = new ArrayList<>();
        if (source.contains("RuntimeDetection.detect(")) {
            uses.add("RuntimeDetection.detect finds the runtime of the machine");
        }
        if (source.contains("RuntimeSource.MACHINE")) {
            uses.add("RuntimeSource.MACHINE is the runtime of the machine");
        }
        if (CLIENT_PROCESS.matcher(source).find()) {
            uses.add("a ProcessBuilder starts docker or podman");
        }
        Matcher helper = HELPER.matcher(source);
        if (helper.find()) {
            uses.add(helper.group(1) + " is a helper of the container tests");
        }
        boolean cliMainByName = source.contains("import " + CLI_MAIN + ";")
                || source.contains("package traceworks.cli.main;");
        Matcher call = Pattern.compile("(?<![A-Za-z0-9_.])(" + Pattern.quote(CLI_MAIN) + "|Main)\\.(run|main)\\(")
                .matcher(source);
        while (call.find()) {
            if (call.group(1).equals("Main") && !cliMainByName) {
                continue;
            }
            List<String> arguments = arguments(source, call.end());
            if (call.group(2).equals("main")) {
                uses.add("Main.main runs a command with the runtime of the machine");
            } else if (arguments.size() < 7 || arguments.get(6).equals("null")) {
                uses.add("Main.run with " + arguments.size() + " arguments" + (arguments.size() < 7 ? "" : " and null"
                        + " as its runtime source") + " finds the runtime of the machine: pass a RuntimeSource");
            }
        }
        return uses;
    }

    /** The arguments of a call, from the character after its opening parenthesis, each stripped. */
    private static List<String> arguments(String source, int from) {
        List<String> arguments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = from; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                int end = i + 1;
                while (end < source.length() && source.charAt(end) != c) {
                    end += source.charAt(end) == '\\' ? 2 : 1;
                }
                current.append(source, i, Math.min(end + 1, source.length()));
                i = end;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
                current.append(c);
            } else if ((c == ')' || c == ']' || c == '}') && depth > 0) {
                depth--;
                current.append(c);
            } else if (c == ')') {
                break;
            } else if (c == ',' && depth == 0) {
                arguments.add(current.toString().strip());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.toString().isBlank()) {
            arguments.add(current.toString().strip());
        }
        return arguments;
    }
}
