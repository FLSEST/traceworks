package traceworks.selfaudit.areas;

import traceworks.cli.RuntimeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The package {@code security-changes} through the real engine and into its register, across runs with different
 * bases: a host run on a git repository, the package's own workflow and script, and a shell loop in the model's
 * place. No AI and no container. It needs {@code bash} and {@code git}; without them the tests fail.
 */
class ChangesRoundTripTest {

    private static final String SRC = "app/src/main/java/p/";
    private static final String RUNNER = SRC + "Runner.java#Runner.run(String)";
    private static final String CALLER = SRC + "Caller.java#Caller.call(Runner)";
    private static final String LONE = SRC + "Lone.java#Lone.home()";

    @TempDir
    Path temp;

    private Path repo;
    private Path pkg;
    private Path home;
    private Path asked;
    private final Map<String, String> env = new HashMap<>(System.getenv());

    private String git(String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true);
        builder.environment().putAll(Map.of("GIT_CONFIG_GLOBAL", "/dev/null", "GIT_CONFIG_NOSYSTEM", "1",
                "GIT_AUTHOR_NAME", "Tester", "GIT_AUTHOR_EMAIL", "tester@example.invalid",
                "GIT_COMMITTER_NAME", "Tester", "GIT_COMMITTER_EMAIL", "tester@example.invalid"));
        Path output = Files.createTempFile(temp, "git", ".txt");
        builder.redirectOutput(output.toFile());
        try {
            Process process = builder.start();
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).as(String.join(" ", args) + "\n" + Files.readString(output)).isZero();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        return Files.readString(output).strip();
    }

    private String commit(String file, String content) throws IOException {
        Files.writeString(repo.resolve(SRC + file), content);
        git("add", "-A");
        git("commit", "--quiet", "-m", "change " + file);
        return git("rev-parse", "HEAD");
    }

    @BeforeEach
    void repositoryAndPackage() throws IOException {
        repo = Files.createDirectories(temp.resolve("work"));
        Files.createDirectories(repo.resolve(SRC));
        git("init", "--quiet", "--initial-branch=main");
        Files.writeString(repo.resolve(SRC + "Caller.java"), "package p;\nclass Caller {\n"
                + "    void call(Runner runner) throws Exception {\n        runner.run(System.getenv(\"CMD\"));\n    }\n}\n");
        Files.writeString(repo.resolve(SRC + "Far.java"), "package p;\nclass Far {\n"
                + "    void far(Caller caller) throws Exception {\n        System.getenv(\"X\");\n"
                + "        caller.call(new Runner());\n    }\n}\n");
        Files.writeString(repo.resolve(SRC + "Lone.java"), "package p;\nclass Lone {\n"
                + "    String home() {\n        return System.getenv(\"HOME\");\n    }\n}\n");
        commit("Runner.java", runner("c"));
        home = Files.createDirectories(temp.resolve("home"));
        asked = temp.resolve("asked.txt");
        pkg = standInForThePackage();
        env.put("TRACEWORKS_ACTOR", "tester");
    }

    private static String runner(String argument) {
        return "package p;\nclass Runner {\n    void run(String c) throws Exception {\n"
                + "        new ProcessBuilder(" + argument + ").start();\n    }\n}\n";
    }

    /**
     * The package as it ships, as a host run: its script, its rules and its catalog, the commands of its steps with
     * the tool of this build in place of the image's, and a shell step in place of the model that writes down which
     * area it was asked about and why, and answers with the file of the area's number, or with no finding.
     */
    @SuppressWarnings("unchecked")
    private Path standInForThePackage() throws IOException {
        Path source = Path.of("../security-changes");
        Path stand = Files.createDirectories(temp.resolve("pkg"));
        Files.createDirectories(stand.resolve("rules"));
        Files.createDirectories(stand.resolve("scripts"));
        Files.copy(source.resolve("rules/security.rules"), stand.resolve("rules/security.rules"));
        Files.copy(source.resolve("rules/security.limits"), stand.resolve("rules/security.limits"));
        Files.copy(source.resolve("catalog.json"), stand.resolve("catalog.json"));
        Files.copy(source.resolve("scripts/changes.sh"), stand.resolve("scripts/changes.sh"));
        String jvm = ProcessHandle.current().info().command().orElse("java");
        String tool = "'" + jvm + "' -cp '" + Path.of("target/classes").toAbsolutePath() + "' " + Main.class.getName();
        Map<String, Object> workflow = new LinkedHashMap<>(
                (Map<String, Object>) Json.parse(Files.readString(source.resolve("workflow.json"))));
        workflow.put("requires", List.of("ai.model_choice"));
        List<Object> blocks = new ArrayList<>();
        for (Map<String, Object> original : (List<Map<String, Object>>) workflow.get("blocks")) {
            Map<String, Object> block = new LinkedHashMap<>(original);
            Map<String, Object> config = new LinkedHashMap<>((Map<String, Object>) original.get("config"));
            config.remove("container_image");
            String command = (String) config.get("command");
            if (original.get("type").equals("foreach")) {
                assertThat(config.remove("execution_mode")).isEqualTo("ai");
                config.put("execution_mode", "shell");
                config.remove("tools");
                config.remove("untrusted_input");
                config.put("values", Map.of("AREA_ITEM", "{{ area.item }}", "AREA_ID", "{{ area.id }}",
                        "AREA_REASON", "{{ area.reason }}"));
                config.put("command", "set -euo pipefail; printf '%s: %s\\n' \"$AREA_ITEM\" \"$AREA_REASON\" >> '"
                        + asked + "'; if [ -f {{ package_dir }}/answer-\"$AREA_ID\".json ]; then cat"
                        + " {{ package_dir }}/answer-\"$AREA_ID\".json; else"
                        + " printf '{\"area\":\"%s\",\"findings\":[]}' \"$AREA_ID\"; fi");
            } else if (!command.contains("scripts/changes.sh")) {
                assertThat(command).satisfiesAnyOf(c -> assertThat(c).containsOnlyOnce("; areas find "),
                        c -> assertThat(c).containsOnlyOnce("; areas changed "),
                        c -> assertThat(c).containsOnlyOnce("| areas report "));
                config.put("command", command.replace("; areas find ", "; " + tool + " find ")
                        .replace("; areas changed ", "; " + tool + " changed ")
                        .replace("| areas report ", "| " + tool + " report "));
            }
            block.put("config", config);
            blocks.add(block);
        }
        workflow.put("blocks", blocks);
        Files.writeString(stand.resolve("workflow.json"), Json.write(workflow));
        return stand;
    }

    private String run(String... more) {
        List<String> args = new ArrayList<>(List.of("run", pkg.toString(), "--workspace", repo.toString(), "--home",
                home.toString(), "--no-input", "--allow-host-execution", "--quiet", "--no-check"));
        args.addAll(List.of(more));
        return cli(args.toArray(String[]::new));
    }

    private String cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        traceworks.cli.main.Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), env, temp, null, RuntimeSource.NONE);
        // As it reads: a line Traceworks broke for an 80 column terminal goes on behind "|+"
        return (out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8))
                .replaceAll("\\R *\\|\\+", "");
    }

    private String newest(String file) throws IOException {
        try (Stream<Path> walk = Files.walk(home.resolve("runs"))) {
            // The gate line of a report holds the scope; these tests read the details in its place
            return Files.readString(walk.filter(p -> p.getFileName().toString().equals(file))
                    .max(java.util.Comparator.comparing(p -> p.toFile().lastModified())).orElseThrow())
                    .replaceFirst("(\\*\\*Gate:\\*\\* [A-Z]+: ).*\n\n\\*\\*Details:\\*\\* ", "$1");
        }
    }

    private List<String> askedAndForgotten() throws IOException {
        List<String> lines = Files.exists(asked) ? Files.readAllLines(asked) : List.of();
        Files.deleteIfExists(asked);
        return lines;
    }

    private String items() {
        return cli("findings", "security-changes", "items", "--home", home.toString());
    }

    @Test
    void aRunWithoutABase_isRefusedByItsFirstStep_andNothingIsJudgedOrReported() throws IOException {
        String output = run();

        assertThat(output).contains("no base: this audit has no earlier run that names its workspace commit, and the"
                + " run got no input 'since'");
        assertThat(asked).as("no verdict was asked for").doesNotExist();
        assertThat(cli("findings", "security-changes", "list", "--all", "--home", home.toString()))
                .doesNotContain("OPEN");
    }

    @Test
    void runsWithDifferentBases_feedOneRegister_anOpenFindingIsAskedAboutUntilItIsGone_andTheGateEndsGreen()
            throws IOException {
        String first = git("rev-parse", "HEAD");
        String second = commit("Runner.java", runner("c, c"));
        String found = "\"severity\":\"medium\",\"line\":4,\"quote\":\"new ProcessBuilder(c, c).start();\","
                + "\"issue\":\"The model's words.\",\"fix\":\"More.\"}";

        // The first run names its base: the changed area and its neighbour are judged, and nothing else
        Files.writeString(pkg.resolve("answer-A004.json"), "{\"area\":\"A004\",\"findings\":[{\"rule\":"
                + "\"process_start\"," + found + "]}");
        String one = run("--input", "since=" + first);

        assertThat(one).contains("register security-changes: 1 new, 0 resolved, 0 reopened, 1 open now");
        assertThat(askedAndForgotten()).containsExactly(CALLER + ": neighbour of Runner.run(String)",
                RUNNER + ": changed");
        String report = newest("security-changes-report.md");
        assertThat(report).startsWith("# Security check of the changes\n\n**Gate:** YELLOW: of the changes since "
                        + first.substring(0, 12) + ": 1 verified finding (0 high, 1 medium, 0 low); 2 of 2 areas judged;"
                        + " 1 of 2 items settled (1 clean, 0 decided; 1 open, 0 not judged); 2 of 4 areas of the"
                        + " project not looked at\n")
                .contains("| Base: the commit the changes are measured from | `" + first + "`, committed ",
                        "| Where the base is from | the input `since`: `" + first + "` |",
                        "| Workspace commit | `" + second + "`, with nothing uncommitted |");
        assertThat(items()).as("the two areas nothing looked at are no items")
                .matches("open        [0-9a-f]{16}  " + Pattern.quote(RUNNER) + "\n"
                        + "clean       [0-9a-f]{16}  " + Pattern.quote(CALLER) + "\n");

        // The second run has no input: its base is the workspace commit of the first, which that run's report
        // names. Another area changed. The open finding of the first run is asked about, its clean neighbour is not
        String third = commit("Lone.java", "package p;\nclass Lone {\n    String home() {\n"
                + "        return System.getenv(\"HOME\") + \"/\";\n    }\n}\n");
        Files.writeString(pkg.resolve("answer-A004.json"), "{\"area\":\"A004\",\"findings\":[],\"known\":[{\"id\":"
                + "\"K1\",\"status\":\"holds\"," + found + "]}");
        String two = run();

        assertThat(two).as(one).contains("register security-changes: 0 new, 0 resolved, 0 reopened, 1 open now");
        assertThat(askedAndForgotten()).containsExactly(LONE + ": changed",
                RUNNER + ": an open finding of an earlier run");
        assertThat(newest("security-changes-report.md"))
                .contains("| Base: the commit the changes are measured from | `" + second + "`, committed ",
                        "| Where the base is from | the workspace commit of the last run of this audit, as its report"
                                + " names it |",
                        "| Workspace commit | `" + third + "`, with nothing uncommitted |",
                        "| Areas with a changed line | 1 |", "| Areas an earlier run left open or without a verdict | 1 |",
                        "2 of 3 items settled");
        assertThat(items()).matches("open        [0-9a-f]{16}  " + Pattern.quote(RUNNER) + "\n"
                + "clean       [0-9a-f]{16}  " + Pattern.quote(CALLER) + "\n"
                + "clean       [0-9a-f]{16}  " + Pattern.quote(LONE) + "\n");

        // The third run: nothing changed since the second. Only the open finding is asked about, and the answer
        // says that it is gone: the register resolves it, every item is settled, and the gate is YELLOW (this check never says GREEN)
        Files.writeString(pkg.resolve("answer-A004.json"), "{\"area\":\"A004\",\"findings\":[],\"known\":[{\"id\":"
                + "\"K1\",\"status\":\"gone\",\"reason\":\"it never held\"}]}");
        String three = run();

        assertThat(three).as(two).contains("register security-changes: 0 new, 1 resolved, 0 reopened, 0 open now");
        assertThat(askedAndForgotten()).containsExactly(RUNNER + ": an open finding of an earlier run");
        assertThat(newest("security-changes-report.md")).startsWith("# Security check of the changes\n\n**Gate:**"
                + " YELLOW: nothing changed since " + third.substring(0, 12) + ": no verified finding; 1 of 1 areas"
                + " judged; 3 of 3 items settled (2 clean, 1 decided; 0 open, 0 not judged); 3 of 4 areas of the"
                + " project not looked at\n");
        assertThat(items()).doesNotContain("open ", "not judged");

        // The fourth run: nothing changed and nothing is open, so nobody is asked, and the gate stays YELLOW
        String four = run();

        assertThat(four).contains("register security-changes: 0 new, 0 resolved, 0 reopened, 0 open now");
        assertThat(askedAndForgotten()).isEmpty();
        assertThat(newest("security-changes-report.md")).contains("**Gate:** YELLOW: nothing changed since ",
                "3 of 3 items settled", "**Nothing changed.** No file of the workspace differs from the base, the"
                        + " workspace commit of the last run.");
    }

    @Test
    void anAreaWhoseCallFailed_isJudgedByTheNextRun_andAnAreaThatIsGoneLeavesTheRegister() throws IOException {
        String first = git("rev-parse", "HEAD");
        commit("Runner.java", runner("c, c"));
        // The neighbour's answer is no JSON: one item not judged
        Files.writeString(pkg.resolve("answer-A001.json"), "no answer");
        String one = run("--input", "since=" + first);

        assertThat(one).contains("register security-changes: 0 new, 0 resolved, 0 reopened, 0 open now");
        assertThat(askedAndForgotten()).hasSize(2);
        assertThat(items()).startsWith("not judged  ").contains(CALLER, RUNNER);

        // Nothing changed since; the area without a verdict is in the next run all the same. The changed area of
        // the first run is removed from the sources meanwhile, and with it its item
        Files.delete(pkg.resolve("answer-A001.json"));
        Files.writeString(repo.resolve(SRC + "Runner.java"), "package p;\nclass Runner {\n"
                + "    void run(String c) throws Exception {\n    }\n}\n");
        git("add", "-A");
        git("commit", "--quiet", "-m", "no process");
        String two = run();

        assertThat(two).as(one).contains("register security-changes:");
        assertThat(askedAndForgotten()).containsExactly(CALLER + ": neighbour of Runner.run(String)");
        assertThat(items()).matches("clean       [0-9a-f]{16}  " + Pattern.quote(CALLER) + "\n");
    }

    /**
     * A class with an open finding moves to a directory the finder does not read. Its area is gone, and that must
     * not read as fixed: the finding stays open in the register, the report names the file, and the gate is not GREEN.
     */
    @Test
    void aClassWithAnOpenFinding_movedWhereTheFinderDoesNotRead_keepsItsFindingOpen_andTheGateIsNotGreen()
            throws IOException {
        String first = git("rev-parse", "HEAD");
        commit("Runner.java", runner("c, c"));
        Files.writeString(pkg.resolve("answer-A004.json"), "{\"area\":\"A004\",\"findings\":[{\"rule\":"
                + "\"process_start\",\"severity\":\"high\",\"line\":4,\"quote\":\"new ProcessBuilder(c, c).start();\","
                + "\"issue\":\"i\",\"fix\":\"f\"}]}");
        String one = run("--input", "since=" + first);
        assertThat(one).contains("register security-changes: 1 new, 0 resolved, 0 reopened, 1 open now");
        askedAndForgotten();
        Files.delete(pkg.resolve("answer-A004.json"));

        Files.createDirectories(repo.resolve("extra/src/p"));
        git("mv", SRC + "Runner.java", "extra/src/p/Runner.java");
        git("commit", "--quiet", "-m", "move");
        String two = run();

        assertThat(two).as(one).contains("register security-changes: 0 new, 0 resolved, 0 reopened, 1 open now");
        String report = newest("security-changes-report.md");
        assertThat(report).startsWith("# Security check of the changes\n\n**Gate:** RED: of the changes since ")
                .contains("1 changed Java file that no compiled source root holds",
                        "`extra/src/p/Runner.java`", "## Open findings that no item carries",
                        "- HIGH process_start `" + RUNNER + "`");
        assertThat(cli("findings", "security-changes", "list", "--home", home.toString())).contains("OPEN");
    }

    /** A committed change of a script is no code this check can judge: the run says so, and is not GREEN over it. */
    @Test
    void aChangedScript_keepsTheGateFromGreen_andIsNamed() throws IOException {
        String first = git("rev-parse", "HEAD");
        Files.writeString(repo.resolve("run.sh"), "echo 1\n");
        git("add", "-A");
        git("commit", "--quiet", "-m", "a script");

        String one = run("--input", "since=" + first);

        assertThat(one).contains("register security-changes: 0 new, 0 resolved, 0 reopened, 0 open now");
        assertThat(newest("security-changes-report.md")).startsWith("# Security check of the changes\n\n**Gate:**"
                + " YELLOW: of the changes since " + first.substring(0, 12) + ": 1 changed file this check cannot"
                + " judge; ").contains("so the gate is not GREEN: `run.sh`");
        assertThat(askedAndForgotten()).isEmpty();
    }

    /**
     * The workspace commit holds a change that the files on disk do not have. The run judges the disk, so it must
     * not count the commit as judged: the next run, on a clean workspace, starts from the same base and judges it.
     */
    @Test
    void aRunOnAWorkspaceThatDiffersFromItsCommit_doesNotMoveTheBase_soTheNextRunJudgesTheCommit() throws IOException {
        String first = git("rev-parse", "HEAD");
        commit("Runner.java", runner("c, c"));
        Files.writeString(repo.resolve(SRC + "Runner.java"), runner("c"));

        String one = run("--input", "since=" + first);

        assertThat(one).contains("register security-changes: 0 new, 0 resolved, 0 reopened, 0 open now");
        assertThat(askedAndForgotten()).as("the disk is the base: nothing to judge").isEmpty();
        assertThat(newest("security-changes-report.md")).contains("**Gate:** YELLOW: nothing changed since ",
                "| The next run without `since` starts from | `" + first + "`");

        git("checkout", "--quiet", "--", ".");
        String two = run();

        assertThat(two).as(one).contains("register security-changes:");
        assertThat(askedAndForgotten()).as("the commit the first run did not see is judged now")
                .containsExactly(CALLER + ": neighbour of Runner.run(String)", RUNNER + ": changed");
        assertThat(newest("security-changes-report.md"))
                .contains("| Base: the commit the changes are measured from | `" + first + "`");
    }

    /**
     * One commit changes an area and breaks its file, so the file is not read; the next commit only mends the file.
     * The first run must not count the first commit as judged, or the changed area would never be looked at.
     */
    @Test
    void aRunThatCouldNotReadAFile_doesNotMoveTheBase_soTheChangeInItIsJudgedOnceTheFileReads() throws IOException {
        String first = git("rev-parse", "HEAD");
        commit("Runner.java", runner("\"sh\", \"-c\", c").replace("    }\n}\n", "    }\n} }\n"));

        String one = run("--input", "since=" + first);
        assertThat(askedAndForgotten()).as("the area is in a file that does not parse").doesNotContain(RUNNER + ": changed");

        commit("Runner.java", runner("\"sh\", \"-c\", c"));
        String two = run();
        if (two.contains("no base")) {
            // The register did not take the first report, so no run names a base: the operator names it again
            two = run("--input", "since=" + first);
        }

        assertThat(two).as(one).contains("register security-changes:");
        assertThat(askedAndForgotten()).contains(RUNNER + ": changed");
        assertThat(newest("security-changes-report.md"))
                .contains("| Base: the commit the changes are measured from | `" + first + "`");
    }
}
