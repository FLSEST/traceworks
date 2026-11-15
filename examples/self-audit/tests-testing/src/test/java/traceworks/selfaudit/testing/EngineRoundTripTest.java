package traceworks.selfaudit.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import traceworks.cli.RuntimeSource;
import traceworks.cli.main.Main;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static traceworks.selfaudit.testing.Workspace.logic;
import static traceworks.selfaudit.testing.Workspace.testClass;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The package through the real engine and into a register, as a host run without AI: a copy of the package in which
 * only the {@code verdicts} step differs, a shell {@code foreach} over the same lines that prints an answer in the
 * AI's place, and the rule file has the thresholds of the small fixture. It pins what the report relies on: the item markers of the {@code foreach} output, the shell-escaped
 * verdicts on the report's standard input, the gate line first, the fact sheet the register checks the evidence
 * against, and the envelope with the package's catalog.
 */
class EngineRoundTripTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    private Workspace fixture;

    @Test
    void aRun_feedsTheRegister_andARunWithOneFailedCall_feedsItToo_withThatClassNotJudged() throws IOException {
        Path work = temp.resolve("work");
        fixture = new Workspace(temp).withACommandThatIsRun().main("p/Core.java", logic("p", "Core"))
                .main("p/Plain.java", logic("p", "Plain"))
                .main("p/Untested.java", logic("p", "Untested"))
                .test("p/CoreTest.java", testClass("p", "CoreTest", "assertThat(new Core().pick(1, 2)).isEqualTo(2);"))
                .test("p/PlainTest.java", testClass("p", "PlainTest", "new Plain().pick(1, 2);"));
        Path home = Files.createDirectories(temp.resolve("home"));
        String answers = "printf '{\"class\":\"%s\",\"findings\":[{\"file\":\"%s\",\"line_quote\":\"if (a < 0 && b < 0) {\","
                + "\"mutation\":\"if (a < 0 || b < 0) {\",\"why_uncaught\":\"no test gives it'\\''s two negative numbers\","
                + "\"severity\":\"medium\"}]}' {{ candidate.class }} {{ candidate.file }}";

        String first = run(packageWith("set -euo pipefail; " + answers), work, home);

        String report = Files.readString(scratchFile(home, "testing-report.md"));
        assertThat(first).as(report).contains("YELLOW (testing)")
                .contains("register testing: 4 new, 0 resolved, 0 reopened, 4 open now; items 0 of 2 settled, "
                        + "2 judged in this run");
        assertThat(report).contains("**Gate:** YELLOW: 2 measured findings; 2 uncaught changes verified (0 high), "
                        + "0 unverified; 2 of 2 classes judged; 0 not measured")
                .contains("  - why no test notices: no test gives it's two negative numbers\n")
                .contains("- LOW `p.Untested`: No test names Untested").contains("- MEDIUM `p.PlainTest#works`: ");
        String listed = traceworks(home, work, "findings", "testing", "list");
        assertThat(listed).contains("class-without-test").contains("test-without-assertion")
                .contains("uncaught-mutation  p.Core#").contains("uncaught-mutation  p.Plain#")
                .doesNotContain("unverified");

        String second = run(packageWith("set -euo pipefail; if [ {{ candidate.class }} = p.Plain ]; then echo boom; "
                + "exit 1; fi; " + answers), work, home);

        // The call for p.Plain failed: one class not judged. The register takes the rest, p.Plain keeps its verdict
        // and its finding stays open, since no run said that it is gone
        assertThat(second).contains("register testing: 0 new, 0 resolved, 0 reopened, 4 open now, 1 of them not "
                + "mentioned by this report; items 0 of 2 settled, 1 judged in this run");
        assertThat(traceworks(home, work, "findings", "testing", "list")).isEqualTo(listed);
        assertThat(traceworks(home, work, "findings", "testing", "items")).matches(
                "open        [0-9a-f]{16}  p\\.Core\nopen        [0-9a-f]{16}  p\\.Plain\n");
        assertThat(traceworks(home, work, "findings")).contains("  items 0 of 2 settled: 0 clean, 0 decided; 2 open, "
                + "0 not judged\n");
    }

    /**
     * The loop ends: a class judged without a finding is clean and is not asked about again until it or a test that
     * names it changes. The shell loop that stands for the model writes down which classes it was given.
     */
    @Test
    void aSecondRun_judgesOnlyTheClassThatChanged() throws IOException {
        Path work = temp.resolve("work");
        fixture = new Workspace(temp).withACommandThatIsRun().main("p/Core.java", logic("p", "Core"))
                .main("p/Plain.java", logic("p", "Plain"))
                .test("p/CoreTest.java", testClass("p", "CoreTest", "assertThat(new Core().pick(1, 2)).isEqualTo(2);"))
                .test("p/PlainTest.java", testClass("p", "PlainTest", "assertThat(new Plain().pick(1, 2)).isEqualTo(2);"));
        Path home = Files.createDirectories(temp.resolve("home"));
        Path asked = temp.resolve("asked");
        Files.createDirectories(asked);

        String first = run(packageWith(noFinding(asked.resolve("1"))), work, home);
        String second = run(packageWith(noFinding(asked.resolve("2"))), work, home);
        fixture.main("p/Plain.java", logic("p", "Plain").replace("a > b", "a >= b"));
        String third = run(packageWith(noFinding(asked.resolve("3"))), work, home);

        assertThat(Files.readAllLines(asked.resolve("1"))).containsExactly("p.Core", "p.Plain");
        assertThat(first).contains("GREEN (testing)")
                .contains("register testing: 0 new, 0 resolved, 0 reopened, 0 open now; items 2 of 2 settled, "
                        + "2 judged in this run");
        assertThat(asked.resolve("2")).as("nothing changed: no class is judged again").doesNotExist();
        assertThat(second).contains("GREEN (testing)").contains("items 2 of 2 settled, 0 judged in this run");
        assertThat(Files.readAllLines(asked.resolve("3"))).containsExactly("p.Plain");
        assertThat(third).contains("items 2 of 2 settled, 1 judged in this run");
        assertThat(traceworks(home, work, "findings", "testing", "items")).matches(
                "clean       [0-9a-f]{16}  p\\.Core\nclean       [0-9a-f]{16}  p\\.Plain\n");
        assertThat(traceworks(home, work, "findings", "testing", "verify")).contains("verified").doesNotContain("NOT intact");
    }

    /** A loop command that answers "no finding" for its class and appends the class to {@code asked}. */
    private static String noFinding(Path asked) {
        return "set -euo pipefail; echo {{ candidate.class }} >> " + asked
                + "; printf '{\"class\":\"%s\",\"findings\":[]}' {{ candidate.class }}";
    }

    /**
     * The way from the register into the next run's prompt and back, with the package's own workflow and catalog, in
     * which a model judges uncaught-mutation: its facts step sorts {{ audit_findings }} to the classes, and a shell
     * loop answers in the model's place. The finding that "holds" stays open and is seen, the one that is "gone" is
     * resolved, and the one the answer does not mention stays open. No AI and no container.
     */
    @Test
    void anOpenFinding_staysOpenWhenItHoldsOrIsNotMentioned_andResolvesOnlyWhenTheAnswerSaysGone() throws IOException {
        Path work = temp.resolve("work");
        fixture = new Workspace(temp).withACommandThatIsRun();
        for (String name : List.of("Core", "Plain", "Third")) {
            fixture.main("p/" + name + ".java", logic("p", name)).test("p/" + name + "Test.java",
                    testClass("p", name + "Test", "assertThat(new " + name + "().pick(1, 2)).isEqualTo(2);"));
        }
        Path home = Files.createDirectories(temp.resolve("home"));
        Path pkg = packageWith("set -euo pipefail; cat {{ package_dir }}/answer-{{ candidate.class }}.json");
        String quotes = "\"line_quote\":\"if (a < 0 && b < 0) {\",\"mutation\":\"if (a < 0 || b < 0) {\","
                + "\"why_uncaught\":\"no test gives two negative numbers\",\"severity\":\"medium\"";

        for (String name : List.of("Core", "Plain", "Third")) {
            Files.writeString(pkg.resolve("answer-p." + name + ".json"), "{\"class\":\"p." + name + "\",\"findings\":"
                    + "[{\"file\":\"app/src/main/java/p/" + name + ".java\"," + quotes + "}]}");
        }
        String first = run(pkg, work, home);
        assertThat(first).contains("register testing: 3 new, 0 resolved, 0 reopened, 3 open now");

        // Core still holds, Plain is gone, and the answer for Third says nothing about its finding
        Files.writeString(pkg.resolve("answer-p.Core.json"), "{\"class\":\"p.Core\",\"findings\":[],\"known\":[{\"id\":\"K1\","
                + "\"status\":\"holds\",\"file\":\"app/src/main/java/p/Core.java\"," + quotes + "}]}");
        Files.writeString(pkg.resolve("answer-p.Plain.json"), "{\"class\":\"p.Plain\",\"findings\":[],\"known\":[{\"id\":"
                + "\"K1\",\"status\":\"gone\",\"reason\":\"PlainTest#negatives has it now.\"}]}");
        Files.writeString(pkg.resolve("answer-p.Third.json"), "{\"class\":\"p.Third\",\"findings\":[]}");
        String second = run(pkg, work, home);

        assertThat(second).as(first).contains("register testing: 0 new, 1 resolved, 0 reopened, 2 open now, 1 of them"
                + " not mentioned by this report");
        List<String> list = traceworks(home, work, "findings", "testing", "list", "--all").lines().toList();
        assertThat(list).hasSize(3)
                .anySatisfy(l -> assertThat(l).contains("OPEN").contains("p.Core#"))
                .anySatisfy(l -> assertThat(l).contains("RESOLVED").contains("p.Plain#"))
                .anySatisfy(l -> assertThat(l).contains("OPEN").contains("p.Third#"));
        String silent = list.stream().filter(l -> l.contains("p.Third#")).findFirst().orElseThrow().substring(0, 8);
        assertThat(traceworks(home, work, "findings", "testing", "show", silent))
                .contains("Silent    the last 1 applied report did not mention it");

        // What the second run's loop was handed: each class its own open finding; and the package's prompt names
        // only fields a candidate line has
        Path candidates;
        try (Stream<Path> walk = Files.walk(home.resolve("runs"))) {
            candidates = walk.filter(p -> p.getFileName().toString().equals("candidates.jsonl"))
                    .max(java.util.Comparator.comparing(p -> p.toFile().lastModified())).orElseThrow();
        }
        List<String> lines = Files.readAllLines(candidates);
        assertThat(lines).hasSize(3).allSatisfy(line -> {
            com.fasterxml.jackson.databind.JsonNode item = JSON.readTree(line);
            assertThat(item.get("known")).hasSize(1);
            assertThat(item.get("known_text").asText()).startsWith("K1 | uncaught-mutation | MEDIUM | candidate: "
                    + item.get("file").asText());
        });
        com.fasterxml.jackson.databind.JsonNode item = JSON.readTree(lines.get(0));
        java.util.regex.Matcher fields = java.util.regex.Pattern.compile("\\{\\{ candidate\\.(\\w+) }}")
                .matcher(Files.readString(Workspace.PACKAGE.resolve("workflow.json")));
        while (fields.find()) {
            assertThat(item.has(fields.group(1))).as("{{ candidate." + fields.group(1) + " }}").isTrue();
        }
    }

    /**
     * "Gone" beside a finding that does not verify is not taken. Were it taken, the register would resolve the open
     * finding, keep the earlier verdict of the class with the digest it still has, and call the class decided: the
     * next run would leave it out, and nobody would ask about what the model saw. Over three runs, through the
     * register.
     */
    @Test
    void aGoneBesideAFindingThatDoesNotVerify_isNotTaken_soTheClassStaysOpenAndIsAskedAgain() throws IOException {
        Path work = temp.resolve("work");
        fixture = new Workspace(temp).withACommandThatIsRun().main("p/Core.java", logic("p", "Core"))
                .test("p/CoreTest.java", testClass("p", "CoreTest", "assertThat(new Core().pick(1, 2)).isEqualTo(2);"));
        Path home = Files.createDirectories(temp.resolve("home"));
        Path asked = temp.resolve("asked.txt");
        Path pkg = packageWith("set -euo pipefail; echo {{ candidate.class }} >> '" + asked
                + "'; cat {{ package_dir }}/answer.json");
        String file = "\"file\":\"app/src/main/java/p/Core.java\",";
        Files.writeString(pkg.resolve("answer.json"), "{\"class\":\"p.Core\",\"findings\":[{" + file
                + "\"line_quote\":\"if (a < 0 && b < 0) {\",\"mutation\":\"if (a < 0 || b < 0) {\","
                + "\"why_uncaught\":\"no test\",\"severity\":\"medium\"}]}");
        assertThat(run(pkg, work, home)).contains("register testing: 1 new, 0 resolved, 0 reopened, 1 open now");

        Files.writeString(pkg.resolve("answer.json"), "{\"class\":\"p.Core\",\"findings\":[{" + file
                + "\"line_quote\":\"this line is not in the class\",\"mutation\":\"x\",\"why_uncaught\":\"no test\","
                + "\"severity\":\"medium\"}],\"known\":[{\"id\":\"K1\",\"status\":\"gone\",\"reason\":\"fixed\"}]}");
        String second = run(pkg, work, home);

        assertThat(second).contains("register testing: 0 new, 0 resolved, 0 reopened, 1 open now");
        assertThat(Files.readString(scratchFiles(home, "testing-report.md").getLast()))
                .contains("## Open findings without an answer (1)")
                .contains("the answer said that it is gone; that is not taken, since the same answer held a finding "
                        + "that did not verify");
        assertThat(traceworks(home, work, "findings", "testing", "items")).matches("open        [0-9a-f]{16}  p\\.Core\n");
        Files.delete(asked);
        run(pkg, work, home);
        assertThat(Files.readAllLines(asked)).as("the third run asks about the class again").containsExactly("p.Core");
    }

    /** Every file of that name the runs left, the oldest first. */
    private static List<Path> scratchFiles(Path home, String name) throws IOException {
        try (Stream<Path> walk = Files.walk(home.resolve("runs"))) {
            return walk.filter(p -> p.getFileName().toString().equals(name))
                    .sorted(java.util.Comparator.comparing(p -> p.toFile().lastModified())).toList();
        }
    }

    /** Each package carries the scripts it runs, so known.py is in two: a fix in one copy must be made in the other. */
    @Test
    void knownPy_isTheSameInTheFunctionalityPackage() throws IOException {
        assertThat(Files.readString(Workspace.PACKAGE.resolve("scripts/known.py")))
                .isEqualTo(Files.readString(Workspace.PACKAGE.resolve("../functionality/scripts/known.py")));
    }

    /** A copy of the package whose {@code verdicts} step is a shell loop that runs {@code command} per class. */
    private Path packageWith(String command) throws IOException {
        Path pkg = temp.resolve("pkg-" + Integer.toHexString(command.hashCode()));
        try (Stream<Path> files = Files.walk(Workspace.PACKAGE)) {
            for (Path file : files.toList()) {
                Path target = pkg.resolve(Workspace.PACKAGE.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(file, target);
                }
            }
        }
        Files.copy(fixture.rules(), pkg.resolve("rules/testing.rules"), StandardCopyOption.REPLACE_EXISTING);
        ObjectNode workflow = (ObjectNode) JSON.readTree(Files.readString(pkg.resolve("workflow.json")));
        workflow.remove("requires");
        ObjectNode verdicts = (ObjectNode) workflow.path("blocks").get(1).path("config");
        assertThat(workflow.path("blocks").get(1).path("name").asText()).isEqualTo("verdicts");
        for (String aiOnly : new String[] {"provider", "model", "tools", "untrusted_input"}) {
            verdicts.remove(aiOnly);
        }
        verdicts.put("execution_mode", "shell").put("shell_escape_variables", true).put("max_consecutive_failures", 0)
                .put("command", command);
        Files.writeString(pkg.resolve("workflow.json"), JSON.writeValueAsString(workflow));
        return pkg;
    }

    private String run(Path pkg, Path work, Path home) {
        return traceworks(home, work, "run", pkg.toString(), "--workspace", work.toString(), "--no-input",
                "--allow-host-execution", "--quiet");
    }

    private String traceworks(Path home, Path work, String... args) {
        String[] all = new String[args.length + 2];
        System.arraycopy(args, 0, all, 0, args.length);
        all[args.length] = "--home";
        all[args.length + 1] = home.toString();
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TRACEWORKS_ACTOR", "tester");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Main.run(all, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                env, work, null, RuntimeSource.NONE);
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }

    private static Path scratchFile(Path home, String name) throws IOException {
        try (Stream<Path> walk = Files.walk(home.resolve("runs"))) {
            return walk.filter(p -> p.getFileName().toString().equals(name)).findFirst()
                    .orElseThrow(() -> new AssertionError("no " + name + " below " + home));
        }
    }
}
