package traceworks.selfaudit.architecture;

import traceworks.cli.RuntimeSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The package's shape through the real engine, as a host run without AI: {@code facts}, a shell {@code foreach} over
 * the lines of {@code candidates.jsonl} in place of the AI step (one item fails), and {@code report}. It pins what the
 * report relies on: the {@code foreach} output's item markers and what the engine appends after a failed item.
 */
class EngineRoundTripTest {

    @TempDir
    Path temp;

    @Test
    void theReport_readsTheRealForeachOutput() throws IOException {
        Path work = Files.createDirectories(temp.resolve("work/app/src/main/java/p"));
        Files.writeString(work.resolve("A.java"), "package p;\nclass A { String home() { return System.getenv(\"HOME\"); } }\n");
        Files.writeString(work.resolve("B.java"), "package p;\nclass B { int f() { return 1; } }\n");
        Path pkg = Files.createDirectories(temp.resolve("pkg"));
        Path rules = Files.writeString(pkg.resolve("architecture.rules"), """
                io env
                  why No environment here.
                  in   p
                  call java.lang.System#getenv
                """);
        String java = ProcessHandle.current().info().command().orElse("java");
        String tool = "'" + java + "' -cp '" + Path.of("target/classes").toAbsolutePath() + "' " + Main.class.getName();
        String workflow = Json.write(Map.of(
                "format", "traceworks-workflow", "format_version", 1L,
                "id", "0b7d6c2e-3f41-4a8e-9a57-2c1d5e8f9a10", "name", "Round trip",
                "metadata", Map.of("gate", Map.of("expected", true)),
                "blocks", List.of(
                        Map.of("type", "shell", "name", "facts", "config", Map.of("command", "set -euo pipefail; "
                                + tool + " facts --rules '" + rules + "' --root . --large-ncloc 1"
                                + " --facts {{ scratch_dir }}/architecture-facts.json"
                                + " --candidates {{ scratch_dir }}/candidates.jsonl")),
                        Map.of("type", "foreach", "name", "verdicts", "config", Map.of(
                                "execution_mode", "shell", "items_source", "command",
                                "items_command", "cat {{ scratch_dir }}/candidates.jsonl", "variable_name", "candidate",
                                "shell_escape_variables", true, "continue_on_error", true,
                                "max_consecutive_failures", 0L, "on_failure", "continue",
                                "command", "set -euo pipefail; if [ {{ candidate.id }} = C02 ]; then echo boom; exit 1;"
                                        + " fi; printf '{\"candidate\":\"%s\",\"verdict\":\"fine\",\"evidence\":"
                                        + "[{\"line\":1,\"quote\":\"package p;\"}],\"purpose\":\"the home\","
                                        + "\"change\":\"a port\"}' {{ candidate.id }}")),
                        Map.of("type", "shell", "name", "report", "config", Map.of(
                                "shell_escape_variables", true, "command", "set -euo pipefail; printf '%s' "
                                        + "{{ block.verdicts.output }} | " + tool + " report --facts "
                                        + "{{ scratch_dir }}/architecture-facts.json --candidates "
                                        + "{{ scratch_dir }}/candidates.jsonl --verdicts - --out "
                                        + "{{ scratch_dir }}/architecture-report.md")))));
        Files.writeString(pkg.resolve("workflow.json"), workflow);
        Path home = Files.createDirectories(temp.resolve("home"));
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TRACEWORKS_ACTOR", "tester");

        // --no-check: the workflow names the JVM and the classes of this machine by their absolute paths, which
        // the check before a run refuses (absolute-home-path) wherever they lie below a home directory
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        traceworks.cli.main.Main.run(new String[] {"run", pkg.toString(), "--workspace",
                        temp.resolve("work").toString(), "--home", home.toString(), "--no-input",
                        "--allow-host-execution", "--quiet", "--no-check"},
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                env, temp, null, RuntimeSource.NONE);
        String output = out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);

        Path report;
        try (Stream<Path> walk = Files.walk(home.resolve("runs"))) {
            report = walk.filter(p -> p.getFileName().toString().equals("architecture-report.md")).findFirst()
                    .orElseThrow(() -> new AssertionError("no report:\n" + output));
        }
        String markdown = Files.readString(report);
        assertThat(markdown).as(output).contains("**Gate:** RED: 1 type with I/O the rule file forbids; 0 split and 0 "
                        + "move verified; 1 of 2 candidates judged")
                .contains("- C02 `app/src/main/java/p/B.java`: no JSON answer with a \"candidate\": boom")
                .contains("- C01 `app/src/main/java/p/A.java`\n  - **What the measured finding is for:** the home");
        assertThat(output).contains("RED (architecture)");
    }
    /**
     * The way from the register into the next run's prompt and back, with the package's own catalog, in which a
     * model judges the split and move verdicts: the second run's candidates carry the open verdicts of the first, and
     * a shell loop answers in the model's place. The same verdict again keeps the finding open, "fine" resolves it,
     * and a verdict whose evidence does not hold leaves it open. No AI and no container.
     */
    @Test
    void anOpenVerdict_staysOpenWhenItIsGivenAgainOrNotAnswered_andResolvesWhenTheFileIsFineNow() throws IOException {
        Path work = Files.createDirectories(temp.resolve("work/app/src/main/java/p"));
        for (String name : List.of("A", "B", "C")) {
            Files.writeString(work.resolve(name + ".java"), "package p;\nclass " + name + " {\n    int f() { return 1; }\n"
                    + "    int g() { return 2; }\n}\n");
        }
        Path pkg = Files.createDirectories(temp.resolve("pkg"));
        Files.copy(Path.of("../architecture/catalog.json"), pkg.resolve("catalog.json"));
        Path rules = Files.writeString(pkg.resolve("architecture.rules"), Fixture.NO_RULES);
        String jvm = ProcessHandle.current().info().command().orElse("java");
        String tool = "'" + jvm + "' -cp '" + Path.of("target/classes").toAbsolutePath() + "' " + Main.class.getName();
        String workflow = Json.write(Map.of(
                "format", "traceworks-workflow", "format_version", 1L,
                "id", "0b7d6c2e-3f41-4a8e-9a57-2c1d5e8f9a11", "name", "Round trip through the register",
                "metadata", Map.of("gate", Map.of("expected", true), "audit", Map.of("enabled", true,
                        "key", "roundtrip", "category", "architecture", "report_block", "report")),
                "blocks", List.of(
                        // As the package's step: what the register knows goes to facts, which sorts it to the files
                        Map.of("type", "shell", "name", "facts", "config", Map.of("shell_escape_variables", true,
                                "command", "set -euo pipefail; printf '%s' {{ audit_findings }} >"
                                        + " {{ scratch_dir }}/known-findings.txt; " + tool + " facts --rules '" + rules
                                        + "' --root . --large-ncloc 1 --known {{ scratch_dir }}/known-findings.txt"
                                        + " --facts {{ scratch_dir }}/architecture-facts.json"
                                        + " --candidates {{ scratch_dir }}/candidates.jsonl")),
                        Map.of("type", "foreach", "name", "verdicts", "config", Map.of(
                                "execution_mode", "shell", "items_source", "command",
                                "items_command", "cat {{ scratch_dir }}/candidates.jsonl", "variable_name", "candidate",
                                "shell_escape_variables", true,
                                "command", "set -euo pipefail; cat {{ package_dir }}/answer-{{ candidate.id }}.json")),
                        Map.of("type", "shell", "name", "report", "config", Map.of(
                                "shell_escape_variables", true, "command", "set -euo pipefail; printf '%s' "
                                        + "{{ block.verdicts.output }} | " + tool + " report --facts "
                                        + "{{ scratch_dir }}/architecture-facts.json --candidates "
                                        + "{{ scratch_dir }}/candidates.jsonl --verdicts - --out "
                                        + "{{ scratch_dir }}/architecture-report.md")))));
        Files.writeString(pkg.resolve("workflow.json"), workflow);
        Path home = Files.createDirectories(temp.resolve("home"));
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TRACEWORKS_ACTOR", "tester");
        String[] run = {"run", pkg.toString(), "--workspace", temp.resolve("work").toString(), "--home",
                home.toString(), "--no-input", "--allow-host-execution", "--quiet", "--no-check"};

        // The first run: every file gets the verdict split
        for (String id : List.of("C01", "C02", "C03")) {
            Files.writeString(pkg.resolve("answer-" + id + ".json"), split(id));
        }
        String first = cli(env, run);
        assertThat(first).contains("register roundtrip: 3 new, 0 resolved, 0 reopened, 3 open now");

        // The second run: split again, fine now, and a verdict whose quote is in no line
        Files.writeString(pkg.resolve("answer-C02.json"), "{\"candidate\":\"C02\",\"verdict\":\"fine\",\"reason\":\"One job"
                + " now.\",\"evidence\":[{\"line\":1,\"quote\":\"package p;\"}],\"members\":[],\"target\":\"\"}");
        Files.writeString(pkg.resolve("answer-C03.json"), "{\"candidate\":\"C03\",\"verdict\":\"fine\",\"reason\":\"r\","
                + "\"evidence\":[{\"line\":1,\"quote\":\"this is in no line\"}],\"members\":[],\"target\":\"\"}");
        String second = cli(env, run);

        assertThat(second).as(first).contains("register roundtrip: 0 new, 1 resolved, 0 reopened, 2 open now, 1 of them"
                + " not mentioned by this report");
        List<String> list = cli(env, "findings", "roundtrip", "list", "--all", "--home", home.toString()).lines().toList();
        assertThat(list).hasSize(3)
                .anySatisfy(l -> assertThat(l).contains("OPEN").contains("p/A.java"))
                .anySatisfy(l -> assertThat(l).contains("RESOLVED").contains("p/B.java"))
                .anySatisfy(l -> assertThat(l).contains("OPEN").contains("p/C.java"));

        // What the second run's loop was handed: each file its own open verdict; and the package's prompt names
        // only fields a candidate line has
        Path candidates;
        try (Stream<Path> walk = Files.walk(home.resolve("runs"))) {
            candidates = walk.filter(p -> p.getFileName().toString().equals("candidates.jsonl"))
                    .max(Comparator.comparing(p -> p.toFile().lastModified())).orElseThrow();
        }
        List<String> lines = Files.readAllLines(candidates);
        assertThat(lines.get(0)).contains("K1 | verdict-split | MEDIUM | arch_candidates: C01").doesNotContain("C02;");
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) Json.parse(lines.get(0));
        Matcher fields = Pattern.compile("\\{\\{ candidate\\.(\\w+) }}")
                .matcher(Files.readString(Path.of("../architecture/workflow.json")));
        while (fields.find()) {
            assertThat(item).as("{{ candidate." + fields.group(1) + " }}").containsKey(fields.group(1));
        }
    }

    /**
     * The loop ends: a file judged as fine is clean and is not asked about again until it changes; and a call that
     * fails is one file not judged, while the register takes the rest. The register hands the items to the next run
     * as {{ audit_items }}; a shell loop stands in for the verdicts and writes down what it was asked.
     */
    @Test
    void aSecondRun_judgesOnlyTheFileThatChanged_andAFailedCallIsOneFileNotJudged() throws IOException {
        Path work = Files.createDirectories(temp.resolve("work/app/src/main/java/p"));
        for (String name : List.of("A", "B")) {
            Files.writeString(work.resolve(name + ".java"), "package p;\nclass " + name + " {\n    int f() { return 1; }\n"
                    + "    int g() { return 2; }\n}\n");
        }
        Path pkg = Files.createDirectories(temp.resolve("pkg-items"));
        Files.copy(Path.of("../architecture/catalog.json"), pkg.resolve("catalog.json"));
        Path rules = Files.writeString(pkg.resolve("architecture.rules"), Fixture.NO_RULES);
        Path asked = temp.resolve("asked.txt");
        Path fail = temp.resolve("fail");
        String jvm = ProcessHandle.current().info().command().orElse("java");
        String tool = "'" + jvm + "' -cp '" + Path.of("target/classes").toAbsolutePath() + "' " + Main.class.getName();
        Files.writeString(pkg.resolve("workflow.json"), Json.write(Map.of(
                "format", "traceworks-workflow", "format_version", 1L,
                "id", "0b7d6c2e-3f41-4a8e-9a57-2c1d5e8f9a12", "name", "Items round trip",
                "metadata", Map.of("gate", Map.of("expected", true), "audit", Map.of("enabled", true,
                        "key", "items", "category", "architecture", "report_block", "report")),
                "blocks", List.of(
                        // As the package's step: what the register knows of the items goes to facts
                        Map.of("type", "shell", "name", "facts", "config", Map.of("shell_escape_variables", true,
                                "command", "set -euo pipefail; printf '%s' {{ audit_findings }} >"
                                        + " {{ scratch_dir }}/known-findings.txt; printf '%s' {{ audit_items }} >"
                                        + " {{ scratch_dir }}/known-items.txt; " + tool + " facts --rules '" + rules
                                        + "' --root . --large-ncloc 1 --known {{ scratch_dir }}/known-findings.txt"
                                        + " --items {{ scratch_dir }}/known-items.txt --verdict-version 1"
                                        + " --facts {{ scratch_dir }}/architecture-facts.json"
                                        + " --candidates {{ scratch_dir }}/candidates.jsonl")),
                        Map.of("type", "foreach", "name", "verdicts", "config", Map.of(
                                "execution_mode", "shell", "items_source", "command",
                                "items_command", "cat {{ scratch_dir }}/candidates.jsonl", "variable_name", "candidate",
                                "shell_escape_variables", true, "continue_on_error", true, "on_failure", "continue",
                                "command", "set -euo pipefail; echo {{ candidate.item }} >> '" + asked + "'; if [ -e '"
                                        + fail + "' ]; then echo boom; exit 1; fi; printf '{\"candidate\":\"%s\","
                                        + "\"verdict\":\"fine\",\"reason\":\"One job.\",\"evidence\":[{\"line\":1,"
                                        + "\"quote\":\"package p;\"}],\"members\":[],\"target\":\"\"}' {{ candidate.id }}")),
                        Map.of("type", "shell", "name", "report", "config", Map.of(
                                "shell_escape_variables", true, "command", "set -euo pipefail; printf '%s' "
                                        + "{{ block.verdicts.output }} | " + tool + " report --facts "
                                        + "{{ scratch_dir }}/architecture-facts.json --candidates "
                                        + "{{ scratch_dir }}/candidates.jsonl --verdicts - --out "
                                        + "{{ scratch_dir }}/architecture-report.md"))))));
        Path home = Files.createDirectories(temp.resolve("home-items"));
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TRACEWORKS_ACTOR", "tester");
        String[] start = {"run", pkg.toString(), "--workspace", temp.resolve("work").toString(), "--home",
                home.toString(), "--no-input", "--allow-host-execution", "--quiet", "--no-check"};
        String itemA = "app/src/main/java/p/A.java";
        String itemB = "app/src/main/java/p/B.java";

        String first = cli(env, start);
        assertThat(Files.readAllLines(asked)).as(first).containsExactly(itemA, itemB);
        assertThat(first).contains("register items: 0 new, 0 resolved, 0 reopened, 0 open now; items 2 of 2 settled, "
                + "2 judged in this run");
        Files.delete(asked);

        String second = cli(env, start);
        assertThat(asked).as("nothing changed: no file is judged again").doesNotExist();
        assertThat(second).contains("GREEN (architecture)").contains("items 2 of 2 settled, 0 judged in this run");

        Files.writeString(work.resolve("A.java"), Files.readString(work.resolve("A.java"))
                .replace("return 1;", "return 3;"));
        Files.createFile(fail);
        String third = cli(env, start);
        assertThat(Files.readAllLines(asked)).containsExactly(itemA);
        assertThat(third).as("the call failed: one file not judged, and the report is applied")
                .contains("register items: 0 new, 0 resolved, 0 reopened, 0 open now; items 1 of 2 settled, "
                        + "0 judged in this run");
        assertThat(cli(env, "findings", "items", "items", "--home", home.toString()))
                .matches("not judged  [0-9a-f]{16}  " + Pattern.quote(itemA) + "\n"
                        + "clean       [0-9a-f]{16}  " + Pattern.quote(itemB) + "\n");
        Files.delete(asked);
        Files.delete(fail);

        String fourth = cli(env, start);
        assertThat(Files.readAllLines(asked)).containsExactly(itemA);
        assertThat(fourth).contains("items 2 of 2 settled, 1 judged in this run");
        assertThat(cli(env, "findings", "items", "verify", "--home", home.toString())).contains("verified")
                .doesNotContain("NOT intact");
    }

    /**
     * The round trips above run the tool as a host process, not the package's own workflow, which needs its image.
     * So the workflow itself is held against the check a run starts with: its facts step reads the items.
     */
    @Test
    void thePackagesOwnWorkflow_passesTheCheck() {
        Path pkg = Path.of("../architecture").toAbsolutePath().normalize();

        String output = cli(Map.of("TRACEWORKS_ACTOR", "tester"), "check", pkg.toString(), "--home",
                temp.resolve("home-check").toString());

        assertThat(output).as("the one note: the model is pinned").contains("INFO", "dated-model-id")
                .doesNotContain("ERROR").doesNotContain("WARN").doesNotContain("audit_items");
    }

    private static String split(String id) {
        return "{\"candidate\":\"" + id + "\",\"verdict\":\"split\",\"severity\":\"medium\",\"reason\":\"Two jobs.\",\"evidence\":[{\"line\":4,"
                + "\"quote\":\"int g() { return 2; }\"}],\"members\":[\"g\"],\"target\":\"p.G\"}";
    }

    private String cli(Map<String, String> env, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        traceworks.cli.main.Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), env, temp, null, RuntimeSource.NONE);
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }
}
