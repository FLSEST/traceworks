package traceworks.selfaudit.functionality;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The package's shape through the real engine and the real register, as a host run without AI: {@code facts}, a shell
 * {@code foreach} over the lines of {@code sections.jsonl} in place of the AI step, and {@code report}, with the
 * package's own scripts and catalog. It pins what the report relies on (the item markers of the {@code foreach}
 * output, the output as one quoted shell word) and what the loop relies on: the register takes the envelope, and the
 * next run gets a person's decision as {@code {{ audit_findings }}}.
 */
class EngineRoundTripTest {

    @TempDir
    Path temp;

    private Path home;
    private Path pkg;
    private Fixture fixture;

    @Test
    void theRegister_takesTheReport_andTheNextRunSeesTheDecision() throws IOException {
        fixture = new Fixture(temp);
        fixture.replace("docs/cli.md", "| 2 | usage error |", "| 2 | usage error |\n| 5 | parked |");
        home = Files.createDirectories(temp.resolve("home"));
        pkg = Files.createDirectories(temp.resolve("pkg"));
        Files.copy(Fixture.PACKAGE.resolve("catalog.json"), pkg.resolve("catalog.json"));
        // The stand-in for the model: a contradiction with quotes that hold for the section "run", nothing for the rest
        String answer = Fixture.answer("cli.md#run", "`--quiet` prints the summary only.",
                Fixture.SOURCES + "RunCommand.java", "return args.has(\"quiet\") ? 0 : 2;", "it's \"wrong\"", "medium");
        Path answers = Files.writeString(pkg.resolve("answer.sh"), "if [ \"$1\" = 'cli.md#run' ]; then cat <<'ANSWER'\n"
                + answer + "\nANSWER\nelse printf '{\"section\":\"%s\",\"findings\":[]}' \"$1\"; fi\n");
        String scripts = "'" + Fixture.PACKAGE.resolve("scripts") + "'";
        String workflow = Fixture.JSON.writeValueAsString(Map.of(
                "format", "traceworks-workflow", "format_version", 1,
                "id", "5e2c7b1a-90d4-4f3b-8a6e-1d2c3b4a5f60", "name", "Round trip",
                "metadata", Map.of("gate", Map.of("expected", true), "audit", Map.of("enabled", true,
                        "key", "functionality", "category", "functionality", "report_block", "report")),
                "blocks", List.of(
                        Map.of("type", "shell", "name", "known", "config", Map.of("shell_escape_variables", true,
                                "command", "printf '%s\\n' {{ audit_findings }}")),
                        Map.of("type", "shell", "name", "facts", "config", Map.of("command", "set -euo pipefail; "
                                + "python3 -B " + scripts + "/facts.py --root . --rules '" + fixture.rules
                                + "' --out {{ scratch_dir }}")),
                        Map.of("type", "foreach", "name", "verdicts", "config", Map.of(
                                "execution_mode", "shell", "items_source", "command",
                                "items_command", "cat {{ scratch_dir }}/sections.jsonl", "variable_name", "section",
                                "shell_escape_variables", true, "continue_on_error", true,
                                "max_consecutive_failures", 0, "on_failure", "continue",
                                "command", "sh '" + answers + "' {{ section.id }}")),
                        Map.of("type", "shell", "name", "report", "config", Map.of(
                                "shell_escape_variables", true, "command", "set -euo pipefail; printf '%s' "
                                        + "{{ block.verdicts.output }} | python3 -B " + scripts + "/report.py --root ."
                                        + " --facts {{ scratch_dir }}/facts.json --sections"
                                        + " {{ scratch_dir }}/sections.jsonl --verdicts - --out"
                                        + " {{ scratch_dir }}/functionality-report.md")))));
        Files.writeString(pkg.resolve("workflow.json"), workflow);

        String first = run();

        assertThat(first).contains("YELLOW (functionality)", "1 measured findings, 1 verified contradictions (0 high),"
                + " 0 unverified, 5 of 5 sections judged, 0 not measured",
                "register functionality: 2 new, 0 resolved, 0 reopened, 2 open now");
        String report = Files.readString(file("functionality-report.md"));
        assertThat(report).contains("- **exit-code-mismatch** `exit 5` (MEDIUM)",
                "  - what the model says is wrong (its words, not checked): it's \"wrong\"");

        String list = traceworks("findings", "functionality", "list");
        assertThat(list).contains("docs-contradict-code  docs/cli.md#run:", "exit-code-mismatch").doesNotContain("unverified");
        String id = list.lines().filter(line -> line.contains("docs-contradict-code")).findFirst().orElseThrow()
                .substring(0, 8);
        assertThat(traceworks("findings", "functionality", "dismiss", id, "--note", "quiet is checked elsewhere"))
                .contains("dismissed " + id);
        // The measured finding is fixed; the model's stand-in reports its contradiction again
        fixture.replace("docs/cli.md", "| 5 | parked |\n", "");

        String second = run();

        assertThat(second).contains("register functionality: 0 new, 1 resolved, 0 reopened, 0 open now");
        assertThat(second).contains("DISMISSED | MEDIUM | docs-contradict-code | docs/cli.md#run:",
                "the code contradicts \"`--quiet` prints the summary only.\" | Evidence: func_section: cli.md#run; the"
                        + " document: \"`--quiet` prints the summary only.\"; the code, " + Fixture.SOURCES
                        + "RunCommand.java: \"return args.has(\"quiet\") ? 0 : 2;\" | Disposition: quiet is checked"
                        + " elsewhere");
        assertThat(traceworks("findings", "functionality", "verify")).contains("chain     ok", "verified");
    }

    /**
     * The way from the register into the next run's prompt and back, with the package's catalog, in which a model
     * judges docs-contradict-code: the fact step sorts {{ audit_findings }} to the sections, and a shell loop answers
     * in the model's place. The contradiction that "holds" stays open and is seen, one the answer does not mention
     * stays open, and only "gone" resolves it. No AI and no container.
     */
    @Test
    void anOpenContradiction_staysOpenWhenItHoldsOrIsNotMentioned_andResolvesOnlyWhenTheAnswerSaysGone()
            throws IOException {
        fixture = new Fixture(temp);
        home = Files.createDirectories(temp.resolve("home"));
        pkg = Files.createDirectories(temp.resolve("pkg"));
        Files.copy(Fixture.PACKAGE.resolve("catalog.json"), pkg.resolve("catalog.json"));
        Path answers = pkg.resolve("answer.sh");
        String scripts = "'" + Fixture.PACKAGE.resolve("scripts") + "'";
        String workflow = Fixture.JSON.writeValueAsString(Map.of(
                "format", "traceworks-workflow", "format_version", 1,
                "id", "5e2c7b1a-90d4-4f3b-8a6e-1d2c3b4a5f61", "name", "Round trip through the register",
                "metadata", Map.of("gate", Map.of("expected", true), "audit", Map.of("enabled", true,
                        "key", "functionality", "category", "functionality", "report_block", "report")),
                "blocks", List.of(
                        // As the package's step: what the register knows goes to the fact step
                        Map.of("type", "shell", "name", "facts", "config", Map.of("shell_escape_variables", true,
                                "command", "set -euo pipefail; printf '%s' {{ audit_findings }} >"
                                        + " {{ scratch_dir }}/known-findings.txt; python3 -B " + scripts
                                        + "/facts.py --root . --rules '" + fixture.rules
                                        + "' --known {{ scratch_dir }}/known-findings.txt --out {{ scratch_dir }}")),
                        Map.of("type", "foreach", "name", "verdicts", "config", Map.of(
                                "execution_mode", "shell", "items_source", "command",
                                "items_command", "cat {{ scratch_dir }}/sections.jsonl", "variable_name", "section",
                                "shell_escape_variables", true,
                                "command", "sh '" + answers + "' {{ section.id }}")),
                        Map.of("type", "shell", "name", "report", "config", Map.of(
                                "shell_escape_variables", true, "command", "set -euo pipefail; printf '%s' "
                                        + "{{ block.verdicts.output }} | python3 -B " + scripts + "/report.py --root ."
                                        + " --facts {{ scratch_dir }}/facts.json --sections"
                                        + " {{ scratch_dir }}/sections.jsonl --verdicts - --out"
                                        + " {{ scratch_dir }}/functionality-report.md")))));
        Files.writeString(pkg.resolve("workflow.json"), workflow);
        String quotes = "\"doc_quote\":\"`--quiet` prints the summary only.\",\"file\":\"" + Fixture.SOURCES
                + "RunCommand.java\",\"code_quote\":\"return args.has(\\\"quiet\\\") ? 0 : 2;\",\"issue\":\"The model's"
                + " words.\",\"severity\":\"medium\"";

        answerForRun(answers, "\"findings\":[{" + quotes + "}]");
        String first = run();
        assertThat(first).contains("register functionality: 1 new, 0 resolved, 0 reopened, 1 open now");

        answerForRun(answers, "\"findings\":[],\"known\":[{\"id\":\"K1\",\"status\":\"holds\"," + quotes + "}]");
        String holds = run();
        assertThat(holds).as("it holds: seen again").contains("register functionality: 0 new, 0 resolved, 0 reopened,"
                + " 1 open now; items 4 of 5 settled, 5 judged in this run\n");
        assertThat(Fixture.read(newest("sections.jsonl"))).as("the section was handed its own open finding")
                .contains("K1 | docs-contradict-code | MEDIUM | func_section: cli.md#run; the document: ");

        answerForRun(answers, "\"findings\":[]");
        String silent = run();
        assertThat(silent).as("not mentioned: still open").contains("register functionality: 0 new, 0 resolved,"
                + " 0 reopened, 1 open now, 1 of them not mentioned by this report");

        answerForRun(answers, "\"findings\":[],\"known\":[{\"id\":\"K1\",\"status\":\"gone\",\"reason\":\"fixed\"}]");
        String gone = run();
        assertThat(gone).as("gone: resolved").contains("register functionality: 0 new, 1 resolved, 0 reopened,"
                + " 0 open now");
        assertThat(traceworks("findings", "functionality", "verify")).contains("chain     ok", "verified");

        // Every placeholder of the package's own prompt is a field of a section line
        com.fasterxml.jackson.databind.JsonNode item = Fixture.JSON.readTree(
                Fixture.read(newest("sections.jsonl")).lines().findFirst().orElseThrow());
        java.util.regex.Matcher fields = java.util.regex.Pattern.compile("\\{\\{ section\\.(\\w+) }}")
                .matcher(Fixture.read(Fixture.PACKAGE.resolve("workflow.json")));
        while (fields.find()) {
            assertThat(item.has(fields.group(1))).as("{{ section." + fields.group(1) + " }}").isTrue();
        }
    }

    // ---- items: the package's own workflow, with a shell loop in the model's place (docs/registers.md, "Items") ----

    /**
     * A call that failed is one section not judged: the register takes the report, the verdicts of the other
     * sections count, and the section whose call failed keeps its open finding and is asked about again.
     */
    @Test
    void aRun_feedsTheRegister_andARunWithOneFailedCall_feedsItToo_withThatSectionNotJudged() throws IOException {
        fixture = new Fixture(temp);
        home = Files.createDirectories(temp.resolve("home"));
        String contradiction = Fixture.answer("cli.md#run", "`--quiet` prints the summary only.",
                Fixture.SOURCES + "RunCommand.java", "return args.has(\"quiet\") ? 0 : 2;", "it's wrong", "medium");
        Path answer = Files.writeString(temp.resolve("answer.json"), contradiction);
        String answers = "if [ {{ section.id }} = 'cli.md#run' ]; then cat '" + answer + "'; else printf "
                + "'{\"section\":\"%s\",\"findings\":[]}' {{ section.id }}; fi";

        String first = runPackage(packageWith("set -euo pipefail; " + answers));

        assertThat(first).contains("YELLOW (functionality)").contains("register functionality: 1 new, 0 resolved,"
                + " 0 reopened, 1 open now; items 4 of 5 settled, 5 judged in this run");
        String listed = traceworks("findings", "functionality", "list");
        assertThat(listed).contains("docs-contradict-code  docs/cli.md#run:").doesNotContain("unverified");

        // Two sections need a verdict now: "Blocks" changed, and "run" is open. The call for "run" fails
        fixture.replace("docs/cli.md", "A `shell` block runs a command.", "A `shell` block runs one command.");
        String second = runPackage(packageWith("set -euo pipefail; if [ {{ section.id }} = 'cli.md#run' ]; then echo boom;"
                + " exit 1; fi; " + answers));

        assertThat(second).contains("register functionality: 0 new, 0 resolved, 0 reopened, 1 open now, 1 of them not"
                + " mentioned by this report; items 4 of 5 settled, 1 judged in this run");
        assertThat(Fixture.read(newest("functionality-report.md"))).contains("1 of 2 sections judged, 0 not measured;"
                + " 4 of 5 items settled (4 clean, 0 decided; 1 open, 0 not judged)", "## Sections not judged (1)", "- cli.md#run: no JSON answer");
        assertThat(traceworks("findings", "functionality", "list")).isEqualTo(listed);
        assertThat(traceworks("findings", "functionality", "items")).matches("open        [0-9a-f]{16}  docs/cli\\.md#run\n"
                + "(clean       [0-9a-f]{16}  docs/cli\\.md#[A-Za-z ]+\n){4}");
        assertThat(traceworks("findings")).contains("  items 4 of 5 settled: 4 clean, 0 decided; 1 open, 0 not judged\n");
        assertThat(traceworks("findings", "functionality", "verify")).contains("chain     ok", "verified");
    }

    /**
     * The loop ends: a section judged without a finding is clean and is not asked about again until it or the file
     * of its command changes. The shell loop that stands for the model writes down which sections it was given.
     */
    @Test
    void aSecondRun_judgesOnlyTheSectionThatChanged() throws IOException {
        fixture = new Fixture(temp);
        home = Files.createDirectories(temp.resolve("home"));
        Path asked = Files.createDirectories(temp.resolve("asked"));

        String first = runPackage(packageWith(noFinding(asked.resolve("1"))));
        String second = runPackage(packageWith(noFinding(asked.resolve("2"))));
        fixture.replace(Fixture.SOURCES + "RunCommand.java", "? 0 : 2;", "? 0 : 1;");
        String third = runPackage(packageWith(noFinding(asked.resolve("3"))));

        assertThat(Files.readAllLines(asked.resolve("1"))).containsExactly("cli.md#a-first-run", "cli.md#run",
                "cli.md#show-and-watch", "cli.md#exit-codes", "cli.md#blocks");
        assertThat(first).contains("GREEN (functionality)").contains("register functionality: 0 new, 0 resolved,"
                + " 0 reopened, 0 open now; items 5 of 5 settled, 5 judged in this run");
        assertThat(asked.resolve("2")).as("nothing changed: no section is judged again").doesNotExist();
        assertThat(second).contains("GREEN (functionality)").contains("items 5 of 5 settled, 0 judged in this run");
        assertThat(Files.readAllLines(asked.resolve("3"))).containsExactly("cli.md#run");
        assertThat(third).contains("items 5 of 5 settled, 1 judged in this run");
        assertThat(traceworks("findings", "functionality", "verify")).contains("chain     ok", "verified");
    }

    /**
     * "Gone" beside a finding that does not verify is not taken. Were it taken, the register would resolve the open
     * finding, keep the earlier verdict of the section with the digest it still has, and call the section decided:
     * the next run would leave it out, and nobody would ask about what the model saw. Over three runs, through the
     * register.
     */
    @Test
    void aGoneBesideAFindingThatDoesNotVerify_isNotTaken_soTheSectionStaysOpenAndIsAskedAgain() throws IOException {
        fixture = new Fixture(temp);
        home = Files.createDirectories(temp.resolve("home"));
        Path asked = temp.resolve("asked.txt");
        Path answers = temp.resolve("answer.sh");
        Path copy = packageWith("set -euo pipefail; echo {{ section.id }} >> '" + asked + "'; sh '" + answers
                + "' {{ section.id }}");
        String quotes = "\"file\":\"" + Fixture.SOURCES + "RunCommand.java\",\"code_quote\":\"return args.has(\\\"quiet\\\")"
                + " ? 0 : 2;\",\"issue\":\"i\",\"severity\":\"medium\"";
        answerForRun(answers, "\"findings\":[{\"doc_quote\":\"`--quiet` prints the summary only.\"," + quotes + "}]");
        assertThat(runPackage(copy)).contains("register functionality: 1 new, 0 resolved, 0 reopened, 1 open now");

        answerForRun(answers, "\"findings\":[{\"doc_quote\":\"a sentence that is not in the section\"," + quotes
                + "}],\"known\":[{\"id\":\"K1\",\"status\":\"gone\",\"reason\":\"fixed\"}]");
        Files.delete(asked);
        String second = runPackage(copy);

        assertThat(Files.readAllLines(asked)).as("the open section is the one asked").containsExactly("cli.md#run");
        assertThat(second).contains("register functionality: 0 new, 0 resolved, 0 reopened, 1 open now");
        assertThat(Fixture.read(newest("functionality-report.md"))).contains("## Open findings without an answer (1)")
                .contains("the answer said that it is gone; that is not taken, since the same answer held a finding"
                        + " that did not verify");
        assertThat(traceworks("findings", "functionality", "items")).startsWith("open        ")
                .contains("  docs/cli.md#run\n");
        Files.delete(asked);
        runPackage(copy);
        assertThat(Files.readAllLines(asked)).as("the third run asks about the section again")
                .containsExactly("cli.md#run");
    }

    /** The package as it is, with its AI step, held against the check a run starts with. */
    @Test
    void thePackagesOwnWorkflow_passesTheCheck() throws IOException {
        home = Files.createDirectories(temp.resolve("home"));

        String output = traceworks("check", Fixture.PACKAGE.toString());

        assertThat(output).as("the one note: the model is pinned").contains("INFO", "dated-model-id")
                .doesNotContain("ERROR").doesNotContain("WARN").doesNotContain("audit_items");
    }

    /** A loop command that answers "no finding" for its section and appends the section to {@code asked}. */
    private static String noFinding(Path asked) {
        return "set -euo pipefail; echo {{ section.id }} >> '" + asked
                + "'; printf '{\"section\":\"%s\",\"findings\":[]}' {{ section.id }}";
    }

    /**
     * A copy of the package whose {@code verdicts} step is a shell loop that runs {@code command} per section, with
     * the rule file of the fixture. Everything else is the package's own: its workflow, its scripts, its catalog.
     */
    private Path packageWith(String command) throws IOException {
        Path copy = temp.resolve("pkg-" + Integer.toHexString(command.hashCode()));
        try (Stream<Path> files = Files.walk(Fixture.PACKAGE)) {
            for (Path file : files.toList()) {
                Path target = copy.resolve(Fixture.PACKAGE.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(file, target);
                }
            }
        }
        Files.copy(fixture.rules, copy.resolve("rules/functionality.rules"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        com.fasterxml.jackson.databind.node.ObjectNode workflow = (com.fasterxml.jackson.databind.node.ObjectNode)
                Fixture.JSON.readTree(Fixture.read(copy.resolve("workflow.json")));
        workflow.remove("requires");
        assertThat(workflow.path("blocks").get(1).path("name").asText()).isEqualTo("verdicts");
        com.fasterxml.jackson.databind.node.ObjectNode verdicts = (com.fasterxml.jackson.databind.node.ObjectNode)
                workflow.path("blocks").get(1).path("config");
        for (String aiOnly : new String[] {"provider", "model", "tools", "untrusted_input"}) {
            verdicts.remove(aiOnly);
        }
        verdicts.put("execution_mode", "shell").put("shell_escape_variables", true).put("max_consecutive_failures", 0)
                .put("command", command);
        Files.writeString(copy.resolve("workflow.json"), Fixture.JSON.writeValueAsString(workflow));
        return copy;
    }

    /** Runs a copy of the package on the fixture tree, as a host run, and returns the summary it printed. */
    private String runPackage(Path copy) {
        return traceworks("run", copy.toString(), "--workspace", fixture.root.toString(), "--no-input",
                "--allow-host-execution", "--quiet");
    }

    /** The stand-in for the model: {@code body} for the section "run", an empty answer for every other one. */
    private static void answerForRun(Path script, String body) throws IOException {
        Files.writeString(script, "if [ \"$1\" = 'cli.md#run' ]; then cat <<'ANSWER'\n{\"section\":\"cli.md#run\"," + body
                + "}\nANSWER\nelse printf '{\"section\":\"%s\",\"findings\":[]}' \"$1\"; fi\n");
    }

    private Path newest(String name) throws IOException {
        try (Stream<Path> walk = Files.walk(home.resolve("runs"))) {
            return walk.filter(p -> p.getFileName().toString().equals(name))
                    .max(java.util.Comparator.comparing(p -> p.toFile().lastModified())).orElseThrow();
        }
    }

    /** Runs the package on the fixture tree and returns everything it printed, with the output of {@code known}. */
    private String run() throws IOException {
        // --no-check: the workflow names the scripts of this checkout by their absolute paths, which the check
        // before a run refuses (absolute-home-path) wherever they lie below a home directory
        String printed = traceworks("run", pkg.toString(), "--workspace", fixture.root.toString(), "--no-input",
                "--allow-host-execution", "--no-check");
        java.util.regex.Matcher id = java.util.regex.Pattern.compile("\\(run ([0-9a-f]{8})\\)").matcher(printed);
        assertThat(id.find()).as(printed).isTrue();
        return printed + traceworks("show", "--full", id.group(1));
    }

    private String traceworks(String... args) {
        String[] all = Stream.concat(Stream.of(args), Stream.of("--home", home.toString())).toArray(String[]::new);
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TRACEWORKS_ACTOR", "tester");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Main.run(all, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true,
                StandardCharsets.UTF_8), env, temp, null, RuntimeSource.NONE);
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }

    private Path file(String name) throws IOException {
        try (Stream<Path> walk = Files.walk(home.resolve("runs"))) {
            return walk.filter(p -> p.getFileName().toString().equals(name)).findFirst()
                    .orElseThrow(() -> new AssertionError("no " + name));
        }
    }
}
