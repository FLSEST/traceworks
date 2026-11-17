package traceworks.selfaudit.areas;

import traceworks.cli.RuntimeSource;
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
 * The package's shape through the real engine, as a host run without AI: {@code find}, a shell {@code foreach} over
 * the lines of {@code areas.jsonl} in place of the AI step (one item fails), and {@code report}. It pins what the
 * report relies on: the {@code foreach} output's item markers and what the engine appends after a failed item.
 */
class EngineRoundTripTest {

    @TempDir
    Path temp;

    @Test
    void theReport_readsTheRealForeachOutput() throws IOException {
        Path work = Files.createDirectories(temp.resolve("work/app/src/main/java/p"));
        Files.writeString(work.resolve("A.java"), """
                package p;
                class A {
                    void run(String c) throws Exception { new ProcessBuilder(c).start(); }
                }
                """);
        Files.writeString(work.resolve("B.java"), "package p;\nclass B { String f() { return System.getenv(\"HOME\"); } }\n");
        Path pkg = Files.createDirectories(temp.resolve("pkg"));
        String java = ProcessHandle.current().info().command().orElse("java");
        String tool = "'" + java + "' -cp '" + Path.of("target/classes").toAbsolutePath() + "' " + Main.class.getName();
        String rules = Path.of("../security/rules/security.rules").toAbsolutePath().normalize().toString();
        String workflow = Json.write(Map.of(
                "format", "traceworks-workflow", "format_version", 1L,
                "id", "6f1d1f1e-5d43-4c69-9d3e-0b1f0f2a7c11", "name", "Round trip",
                "metadata", Map.of("gate", Map.of("expected", true)),
                "blocks", List.of(
                        Map.of("type", "shell", "name", "areas", "config", Map.of("command", "set -euo pipefail; "
                                + tool + " find --rules '" + rules + "' --root . --out {{ scratch_dir }}/areas.jsonl"
                                + " --summary {{ scratch_dir }}/areas-summary.json")),
                        Map.of("type", "foreach", "name", "verdicts", "config", Map.of("values", Map.of("AREA_ID", "{{ area.id }}"),
                                
                                "execution_mode", "shell", "items_source", "command",
                                "items_command", "cat {{ scratch_dir }}/areas.jsonl", "variable_name", "area",
                                "shell_escape_variables", true, "continue_on_error", true,
                                "max_consecutive_failures", 0L, "on_failure", "continue",
                                "command", "set -euo pipefail; if [ \"$AREA_ID\" = A002 ]; then echo boom; exit 1; fi;"
                                        + " printf '{\"area\":\"%s\",\"findings\":[]}' \"$AREA_ID\"")),
                        Map.of("type", "shell", "name", "report", "config", Map.of("values", Map.of("VERDICTS", "{{ block.verdicts.output }}"),
                                
                                "shell_escape_variables", true, "command", "set -euo pipefail; printf '%s' "
                                        + "\"$VERDICTS\" | " + tool + " report --areas "
                                        + "{{ scratch_dir }}/areas.jsonl --summary {{ scratch_dir }}/areas-summary.json"
                                        + " --verdicts - --out {{ scratch_dir }}/security-report.md")))));
        Files.writeString(pkg.resolve("workflow.json"), workflow);
        Path home = Files.createDirectories(temp.resolve("home"));
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TRACEWORKS_ACTOR", "tester");

        // --no-check: the workflow names the JVM and the classes of this machine by their absolute paths, which
        // the check before a run refuses (absolute-home-path) wherever they lie below a home directory
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        traceworks.cli.main.Main.run(new String[] {"run", pkg.toString(), "--workspace", temp.resolve("work").toString(), "--home",
                        home.toString(), "--no-input", "--allow-host-execution", "--quiet", "--no-check"},
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                env, temp, null, RuntimeSource.NONE);
        String output = out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);

        Path report;
        try (Stream<Path> walk = Files.walk(home.resolve("runs"))) {
            report = walk.filter(p -> p.getFileName().toString().equals("security-report.md")).findFirst()
                    .orElseThrow(() -> new AssertionError("no report:\n" + output));
        }
        String markdown = Files.readString(report);
        assertThat(markdown).as(output).contains("**Gate:** YELLOW: no verified finding; 1 of 2 areas judged")
                .contains("- A002 `app/src/main/java/p/B.java#B.f()`: no JSON answer with an \"area\": boom");
        assertThat(output).contains("YELLOW (security)");
    }

    /**
     * The whole way into a register: {@code find}, a {@code foreach} that answers for every area (one with a finding
     * whose quote holds), {@code report} with its envelope, and the register of the audit, read back with
     * {@code traceworks findings}. No AI and no container: a shell loop stands in for the verdicts.
     */
    @Test
    void theEnvelopeOfTheReport_feedsTheRegisterOfTheAudit() throws IOException {
        Path work = Files.createDirectories(temp.resolve("work/app/src/main/java/p"));
        Files.writeString(work.resolve("A.java"), """
                package p;
                class A {
                    void run(String c) throws Exception { new ProcessBuilder(c).start(); }
                }
                """);
        Files.writeString(work.resolve("B.java"), "package p;\nclass B { String f() { return System.getenv(\"HOME\"); } }\n");
        Path pkg = Files.createDirectories(temp.resolve("pkg"));
        String java = ProcessHandle.current().info().command().orElse("java");
        String tool = "'" + java + "' -cp '" + Path.of("target/classes").toAbsolutePath() + "' " + Main.class.getName();
        String rules = Path.of("../security/rules/security.rules").toAbsolutePath().normalize().toString();
        String answer = "{\"area\":\"A001\",\"findings\":[{\"rule\":\"process_start\",\"severity\":\"medium\",\"line\":3,"
                + "\"quote\":\"new ProcessBuilder(c).start();\",\"issue\":\"The model's words.\",\"fix\":\"More of them.\"}]}";
        Files.writeString(pkg.resolve("answer-a001.json"), answer);
        String workflow = Json.write(Map.of(
                "format", "traceworks-workflow", "format_version", 1L,
                "id", "6f1d1f1e-5d43-4c69-9d3e-0b1f0f2a7c12", "name", "Round trip into a register",
                "metadata", Map.of("gate", Map.of("expected", true), "audit", Map.of("enabled", true,
                        "key", "roundtrip", "category", "security", "report_block", "report")),
                "blocks", List.of(
                        Map.of("type", "shell", "name", "areas", "config", Map.of("command", "set -euo pipefail; "
                                + tool + " find --rules '" + rules + "' --root . --out {{ scratch_dir }}/areas.jsonl"
                                + " --summary {{ scratch_dir }}/areas-summary.json")),
                        Map.of("type", "foreach", "name", "verdicts", "config", Map.of("values", Map.of("AREA_ID", "{{ area.id }}"),
                                
                                "execution_mode", "shell", "items_source", "command",
                                "items_command", "cat {{ scratch_dir }}/areas.jsonl", "variable_name", "area",
                                "shell_escape_variables", true,
                                "command", "set -euo pipefail; if [ \"$AREA_ID\" = A001 ]; then cat"
                                        + " {{ package_dir }}/answer-a001.json; else"
                                        + " printf '{\"area\":\"%s\",\"findings\":[]}' \"$AREA_ID\"; fi")),
                        Map.of("type", "shell", "name", "report", "config", Map.of("values", Map.of("VERDICTS", "{{ block.verdicts.output }}"),
                                
                                "shell_escape_variables", true, "command", "set -euo pipefail; printf '%s' "
                                        + "\"$VERDICTS\" | " + tool + " report --areas "
                                        + "{{ scratch_dir }}/areas.jsonl --summary {{ scratch_dir }}/areas-summary.json"
                                        + " --verdicts - --out {{ scratch_dir }}/security-report.md")))));
        Files.writeString(pkg.resolve("workflow.json"), workflow);
        Path home = Files.createDirectories(temp.resolve("home"));
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TRACEWORKS_ACTOR", "tester");

        String run = cli(env, "run", pkg.toString(), "--workspace", temp.resolve("work").toString(), "--home",
                home.toString(), "--no-input", "--allow-host-execution", "--quiet", "--no-check");
        String findings = cli(env, "findings", "roundtrip", "--home", home.toString());

        assertThat(findings).as(run).contains("process_start", "app/src/main/java/p/A.java#A.run(String)", "MEDIUM")
                .doesNotContain("The model's words");
        assertThat(home.resolve("registers/roundtrip")).as(run).isDirectory();
    }

    /**
     * The way from the register into the next run's prompt and back, with the package's own catalog, whose rules a
     * model judges: the second run's areas carry the open findings of the first, and a shell loop answers in the
     * model's place. The finding that "holds" stays open and is seen, the one that is "gone" is resolved, and the one
     * the answer does not mention stays open. No AI and no container.
     */
    @Test
    void anOpenFinding_staysOpenWhenItHoldsOrIsNotMentioned_andResolvesOnlyWhenTheAnswerSaysGone() throws IOException {
        Path work = Files.createDirectories(temp.resolve("work/app/src/main/java/p"));
        for (String name : List.of("A", "B", "C")) {
            Files.writeString(work.resolve(name + ".java"), "package p;\nclass " + name + " {\n"
                    + "    void run(String c) throws Exception { new ProcessBuilder(c).start(); }\n}\n");
        }
        Path pkg = Files.createDirectories(temp.resolve("pkg"));
        Files.copy(Path.of("../security/catalog.json"), pkg.resolve("catalog.json"));
        String jvm = ProcessHandle.current().info().command().orElse("java");
        String tool = "'" + jvm + "' -cp '" + Path.of("target/classes").toAbsolutePath() + "' " + Main.class.getName();
        String rules = Path.of("../security/rules/security.rules").toAbsolutePath().normalize().toString();
        String workflow = Json.write(Map.of(
                "format", "traceworks-workflow", "format_version", 1L,
                "id", "6f1d1f1e-5d43-4c69-9d3e-0b1f0f2a7c13", "name", "Round trip through the register",
                "metadata", Map.of("gate", Map.of("expected", true), "audit", Map.of("enabled", true,
                        "key", "roundtrip", "category", "security", "report_block", "report")),
                "blocks", List.of(
                        // As the package's step: what the register knows goes to find, which sorts it to the areas
                        Map.of("type", "shell", "name", "areas", "config", Map.of("values", Map.of("KNOWN_FINDINGS", "{{ audit_findings }}"),
                                "shell_escape_variables", true,
                                "command", "set -euo pipefail; printf '%s' \"$KNOWN_FINDINGS\" >"
                                        + " {{ scratch_dir }}/known-findings.txt; " + tool + " find --rules '" + rules
                                        + "' --root . --known {{ scratch_dir }}/known-findings.txt"
                                        + " --out {{ scratch_dir }}/areas.jsonl"
                                        + " --summary {{ scratch_dir }}/areas-summary.json")),
                        Map.of("type", "foreach", "name", "verdicts", "config", Map.of("values", Map.of("AREA_ID", "{{ area.id }}"),
                                
                                "execution_mode", "shell", "items_source", "command",
                                "items_command", "cat {{ scratch_dir }}/areas.jsonl", "variable_name", "area",
                                "shell_escape_variables", true,
                                "command", "set -euo pipefail; cat {{ package_dir }}/answer-\"$AREA_ID\".json")),
                        Map.of("type", "shell", "name", "report", "config", Map.of("values", Map.of("VERDICTS", "{{ block.verdicts.output }}"),
                                
                                "shell_escape_variables", true, "command", "set -euo pipefail; printf '%s' "
                                        + "\"$VERDICTS\" | " + tool + " report --areas "
                                        + "{{ scratch_dir }}/areas.jsonl --summary {{ scratch_dir }}/areas-summary.json"
                                        + " --verdicts - --out {{ scratch_dir }}/security-report.md")))));
        Files.writeString(pkg.resolve("workflow.json"), workflow);
        Path home = Files.createDirectories(temp.resolve("home"));
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TRACEWORKS_ACTOR", "tester");
        String[] run = {"run", pkg.toString(), "--workspace", temp.resolve("work").toString(), "--home",
                home.toString(), "--no-input", "--allow-host-execution", "--quiet", "--no-check"};
        String found = "{\"rule\":\"process_start\",\"severity\":\"medium\",\"line\":3,"
                + "\"quote\":\"new ProcessBuilder(c).start();\",\"issue\":\"The model's words.\",\"fix\":\"More.\"}";

        // The first run: every area has a finding
        for (String id : List.of("A001", "A002", "A003")) {
            Files.writeString(pkg.resolve("answer-" + id + ".json"),
                    "{\"area\":\"" + id + "\",\"findings\":[" + found + "]}");
        }
        String first = cli(env, run);
        assertThat(first).contains("register roundtrip: 3 new, 0 resolved, 0 reopened, 3 open now");

        // The second run: A001 still holds, A002 is gone, and the answer for A003 says nothing about its finding
        Files.writeString(pkg.resolve("answer-A001.json"), "{\"area\":\"A001\",\"findings\":[],\"known\":[{\"id\":\"K1\","
                + "\"status\":\"holds\"," + found.substring(found.indexOf("\"severity\"")) + "]}");
        Files.writeString(pkg.resolve("answer-A002.json"), "{\"area\":\"A002\",\"findings\":[],\"known\":[{\"id\":\"K1\","
                + "\"status\":\"gone\",\"reason\":\"The command is a fixed list now.\"}]}");
        Files.writeString(pkg.resolve("answer-A003.json"), "{\"area\":\"A003\",\"findings\":[]}");
        String second = cli(env, run);

        assertThat(second).as(first).contains("register roundtrip: 0 new, 1 resolved, 0 reopened, 2 open now, 1 of them"
                + " not mentioned by this report");
        List<String> list = cli(env, "findings", "roundtrip", "list", "--all", "--home", home.toString()).lines().toList();
        assertThat(list).hasSize(3)
                .anySatisfy(l -> assertThat(l).contains("OPEN").contains("A.java#A.run(String)"))
                .anySatisfy(l -> assertThat(l).contains("RESOLVED").contains("B.java#B.run(String)"))
                .anySatisfy(l -> assertThat(l).contains("OPEN").contains("C.java#C.run(String)"));
        String holds = list.stream().filter(l -> l.contains("A.java")).findFirst().orElseThrow().substring(0, 8);
        String silent = list.stream().filter(l -> l.contains("C.java")).findFirst().orElseThrow().substring(0, 8);
        assertThat(cli(env, "findings", "roundtrip", "show", holds, "--home", home.toString()))
                .contains("Occurrences 2").doesNotContain("Silent");
        assertThat(cli(env, "findings", "roundtrip", "show", silent, "--home", home.toString()))
                .contains("Occurrences 1", "Silent    the last 1 applied report did not mention it");

        // What the second run's loop was handed: each area its own open finding, and nothing of the others
        Path areas;
        try (Stream<Path> walk = Files.walk(home.resolve("runs"))) {
            areas = walk.filter(p -> p.getFileName().toString().equals("areas.jsonl"))
                    .max(java.util.Comparator.comparing(p -> p.toFile().lastModified())).orElseThrow();
        }
        List<String> lines = Files.readAllLines(areas);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).contains("K1 | process_start | MEDIUM | report_findings_verified: "
                + "app/src/main/java/p/A.java:3: new ProcessBuilder(c).start();").doesNotContain("B.java:3");

        // Every placeholder of the package's own prompt is a field of an area line
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) Json.parse(lines.get(0));
        java.util.regex.Matcher fields = java.util.regex.Pattern.compile("\\{\\{ area\\.(\\w+) }}")
                .matcher(Files.readString(Path.of("../security/workflow.json")));
        while (fields.find()) {
            assertThat(item).as("{{ area." + fields.group(1) + " }}").containsKey(fields.group(1));
        }
    }

    /**
     * The loop ends: an area judged without a finding is clean and is not asked about again until its code changes;
     * and a call that fails is one area not judged, while the register takes the rest. The register hands the items
     * to the next run as {{ audit_items }}; a shell loop stands in for the verdicts and writes down what it was asked.
     */
    @Test
    void aSecondRun_judgesOnlyTheAreaThatChanged_andAFailedCallIsOneAreaNotJudged() throws IOException {
        Path work = Files.createDirectories(temp.resolve("work/app/src/main/java/p"));
        Files.writeString(work.resolve("A.java"), """
                package p;
                class A {
                    void run(String c) throws Exception { new ProcessBuilder(c).start(); }
                }
                """);
        Files.writeString(work.resolve("B.java"), "package p;\nclass B { String f() { return System.getenv(\"HOME\"); } }\n");
        Path pkg = Files.createDirectories(temp.resolve("pkg-items"));
        Path asked = temp.resolve("asked.txt");
        Path fail = temp.resolve("fail");
        String jvm = ProcessHandle.current().info().command().orElse("java");
        String tool = "'" + jvm + "' -cp '" + Path.of("target/classes").toAbsolutePath() + "' " + Main.class.getName();
        String rules = Path.of("../security/rules/security.rules").toAbsolutePath().normalize().toString();
        Files.writeString(pkg.resolve("workflow.json"), Json.write(Map.of(
                "format", "traceworks-workflow", "format_version", 1L,
                "id", "0c0a4c0e-6d0b-4c56-8a52-1a8f3e1c9d77", "name", "Items round trip",
                "metadata", Map.of("gate", Map.of("expected", true), "audit", Map.of("enabled", true,
                        "key", "items", "category", "security", "report_block", "report")),
                "blocks", List.of(
                        Map.of("type", "shell", "name", "areas", "config", Map.of("values", Map.of("KNOWN_ITEMS", "{{ audit_items }}"),
                                "shell_escape_variables", true,
                                "command", "set -euo pipefail; printf '%s' \"$KNOWN_ITEMS\" > {{ scratch_dir }}/known-items.txt; "
                                        + tool + " find --rules '" + rules + "' --root . --items {{ scratch_dir }}/known-items.txt"
                                        + " --out {{ scratch_dir }}/areas.jsonl --summary {{ scratch_dir }}/areas-summary.json")),
                        Map.of("type", "foreach", "name", "verdicts", "config", Map.of("values", Map.of("AREA_ITEM", "{{ area.item }}", "AREA_ID", "{{ area.id }}"),
                                
                                "execution_mode", "shell", "items_source", "command",
                                "items_command", "cat {{ scratch_dir }}/areas.jsonl", "variable_name", "area",
                                "shell_escape_variables", true, "continue_on_error", true, "on_failure", "continue",
                                "command", "set -euo pipefail; echo \"$AREA_ITEM\" >> '" + asked + "'; if [ -e '" + fail
                                        + "' ]; then echo boom; exit 1; fi; printf '{\"area\":\"%s\",\"findings\":[]}' \"$AREA_ID\"")),
                        Map.of("type", "shell", "name", "report", "config", Map.of("values", Map.of("VERDICTS", "{{ block.verdicts.output }}"),
                                
                                "shell_escape_variables", true, "command", "set -euo pipefail; printf '%s' "
                                        + "\"$VERDICTS\" | " + tool + " report --areas "
                                        + "{{ scratch_dir }}/areas.jsonl --summary {{ scratch_dir }}/areas-summary.json"
                                        + " --verdicts - --out {{ scratch_dir }}/security-report.md"))))));
        Path home = Files.createDirectories(temp.resolve("home-items"));
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TRACEWORKS_ACTOR", "tester");
        String[] start = {"run", pkg.toString(), "--workspace", temp.resolve("work").toString(), "--home",
                home.toString(), "--no-input", "--allow-host-execution", "--quiet", "--no-check"};
        String itemA = "app/src/main/java/p/A.java#A.run(String)";
        String itemB = "app/src/main/java/p/B.java#B.f()";

        String first = cli(env, start);
        assertThat(Files.readAllLines(asked)).as(first).containsExactly(itemA, itemB);
        assertThat(first).contains("register items: 0 new, 0 resolved, 0 reopened, 0 open now; items 2 of 2 settled, "
                + "2 judged in this run");
        Files.delete(asked);

        String second = cli(env, start);
        assertThat(asked).as("nothing changed: no area is judged again").doesNotExist();
        assertThat(second).contains("items 2 of 2 settled, 0 judged in this run");

        Files.writeString(work.resolve("A.java"), Files.readString(work.resolve("A.java"))
                .replace("new ProcessBuilder(c)", "new ProcessBuilder(c, c)"));
        Files.createFile(fail);
        String third = cli(env, start);
        assertThat(Files.readAllLines(asked)).containsExactly(itemA);
        assertThat(third).as("the call failed: one area not judged, and the report is applied")
                .contains("register items: 0 new, 0 resolved, 0 reopened, 0 open now; items 1 of 2 settled, "
                        + "0 judged in this run");
        assertThat(cli(env, "findings", "items", "items", "--home", home.toString()))
                .matches("not judged  [0-9a-f]{16}  " + java.util.regex.Pattern.quote(itemA) + "\n"
                        + "clean       [0-9a-f]{16}  " + java.util.regex.Pattern.quote(itemB) + "\n");
        Files.delete(asked);
        Files.delete(fail);

        String fourth = cli(env, start);
        assertThat(Files.readAllLines(asked)).containsExactly(itemA);
        assertThat(fourth).contains("items 2 of 2 settled, 1 judged in this run");
        assertThat(cli(env, "findings", "items", "verify", "--home", home.toString())).contains("verified")
                .doesNotContain("NOT intact");
    }

    /**
     * "Gone" beside a finding that does not verify is not taken. Were it taken, the register would resolve the open
     * finding, keep the earlier verdict of the area with the digest it still has, and call the area decided: the
     * next run would leave it out, and nobody would ask about what the model saw. Over three runs, through the
     * register.
     */
    @Test
    void aGoneBesideAFindingThatDoesNotVerify_isNotTaken_soTheAreaStaysOpenAndIsAskedAgain() throws IOException {
        Path work = Files.createDirectories(temp.resolve("work/app/src/main/java/p"));
        Files.writeString(work.resolve("A.java"), "package p;\nclass A {\n"
                + "    void run(String c) throws Exception { new ProcessBuilder(c).start(); }\n}\n");
        Path pkg = Files.createDirectories(temp.resolve("pkg-held"));
        Files.copy(Path.of("../security/catalog.json"), pkg.resolve("catalog.json"));
        Path asked = temp.resolve("asked-held.txt");
        String jvm = ProcessHandle.current().info().command().orElse("java");
        String tool = "'" + jvm + "' -cp '" + Path.of("target/classes").toAbsolutePath() + "' " + Main.class.getName();
        String rules = Path.of("../security/rules/security.rules").toAbsolutePath().normalize().toString();
        Files.writeString(pkg.resolve("workflow.json"), Json.write(Map.of(
                "format", "traceworks-workflow", "format_version", 1L,
                "id", "6f1d1f1e-5d43-4c69-9d3e-0b1f0f2a7c14", "name", "A gone that is not taken",
                "metadata", Map.of("gate", Map.of("expected", true), "audit", Map.of("enabled", true,
                        "key", "held", "category", "security", "report_block", "report")),
                "blocks", List.of(
                        // As the package's step: what the register knows of findings and items goes to find
                        Map.of("type", "shell", "name", "areas", "config", Map.of("values", Map.of("KNOWN_FINDINGS", "{{ audit_findings }}", "KNOWN_ITEMS", "{{ audit_items }}"),
                                "shell_escape_variables", true,
                                "command", "set -euo pipefail; printf '%s' \"$KNOWN_FINDINGS\" >"
                                        + " {{ scratch_dir }}/known-findings.txt; printf '%s' \"$KNOWN_ITEMS\" >"
                                        + " {{ scratch_dir }}/known-items.txt; " + tool + " find --rules '" + rules
                                        + "' --root . --known {{ scratch_dir }}/known-findings.txt"
                                        + " --items {{ scratch_dir }}/known-items.txt --out {{ scratch_dir }}/areas.jsonl"
                                        + " --summary {{ scratch_dir }}/areas-summary.json")),
                        Map.of("type", "foreach", "name", "verdicts", "config", Map.of("values", Map.of("AREA_ID", "{{ area.id }}"),
                                
                                "execution_mode", "shell", "items_source", "command",
                                "items_command", "cat {{ scratch_dir }}/areas.jsonl", "variable_name", "area",
                                "shell_escape_variables", true,
                                "command", "set -euo pipefail; echo \"$AREA_ID\" >> '" + asked
                                        + "'; cat {{ package_dir }}/answer.json")),
                        Map.of("type", "shell", "name", "report", "config", Map.of("values", Map.of("VERDICTS", "{{ block.verdicts.output }}"),
                                
                                "shell_escape_variables", true, "command", "set -euo pipefail; printf '%s' "
                                        + "\"$VERDICTS\" | " + tool + " report --areas "
                                        + "{{ scratch_dir }}/areas.jsonl --summary {{ scratch_dir }}/areas-summary.json"
                                        + " --verdicts - --out {{ scratch_dir }}/security-report.md"))))));
        Path home = Files.createDirectories(temp.resolve("home-held"));
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TRACEWORKS_ACTOR", "tester");
        String[] run = {"run", pkg.toString(), "--workspace", temp.resolve("work").toString(), "--home",
                home.toString(), "--no-input", "--allow-host-execution", "--quiet", "--no-check"};

        Files.writeString(pkg.resolve("answer.json"), "{\"area\":\"A001\",\"findings\":[{\"rule\":\"process_start\","
                + "\"severity\":\"medium\",\"line\":3,\"quote\":\"new ProcessBuilder(c).start();\",\"issue\":\"i\","
                + "\"fix\":\"f\"}]}");
        assertThat(cli(env, run)).contains("register held: 1 new, 0 resolved, 0 reopened, 1 open now");

        Files.writeString(pkg.resolve("answer.json"), "{\"area\":\"A001\",\"findings\":[{\"rule\":\"path_check\","
                + "\"severity\":\"medium\",\"line\":3,\"quote\":\"this is not in the line\",\"issue\":\"i\",\"fix\":\"f\"}],"
                + "\"known\":[{\"id\":\"K1\",\"status\":\"gone\",\"reason\":\"fixed\"}]}");
        String second = cli(env, run);

        assertThat(second).contains("register held: 0 new, 0 resolved, 0 reopened, 1 open now");
        Path report;
        try (Stream<Path> walk = Files.walk(home.resolve("runs"))) {
            report = walk.filter(p -> p.getFileName().toString().equals("security-report.md"))
                    .max(java.util.Comparator.comparing(p -> p.toFile().lastModified())).orElseThrow();
        }
        assertThat(Files.readString(report)).contains("## Open findings without an answer")
                .contains("the answer said that it is gone; that is not taken, since the same answer held a finding "
                        + "that did not verify");
        assertThat(cli(env, "findings", "held", "items", "--home", home.toString())).startsWith("open        ");
        Files.delete(asked);
        cli(env, run);
        assertThat(Files.readAllLines(asked)).as("the third run asks about the area again").containsExactly("A001");
    }

    /**
     * The package's own workflow as a host run, with a shell loop in the model's place: the declaration of the model
     * choice, the reference of the verdict step, its {@code items_command} and the commands of the two tool steps
     * stay as they are, and the verdict step writes down the option, the provider and the model it was started with
     * (the variables Traceworks sets for the pick). No AI and no container.
     *
     * @return the directory of the stand-in package; {@code asked} gets one line per item the judge was asked about
     */
    @SuppressWarnings("unchecked")
    private Path standInForThePackage(Path asked) throws IOException {
        Path pkg = Files.createDirectories(temp.resolve("pkg-judges"));
        Files.createDirectories(pkg.resolve("rules"));
        Files.copy(Path.of("../security/rules/security.rules"), pkg.resolve("rules/security.rules"));
        Files.copy(Path.of("../security/rules/security.limits"), pkg.resolve("rules/security.limits"));
        Files.copy(Path.of("../security/catalog.json"), pkg.resolve("catalog.json"));
        String jvm = ProcessHandle.current().info().command().orElse("java");
        String tool = "'" + jvm + "' -cp '" + Path.of("target/classes").toAbsolutePath() + "' " + Main.class.getName();
        Map<String, Object> workflow = new java.util.LinkedHashMap<>(
                (Map<String, Object>) Json.parse(Files.readString(Path.of("../security/workflow.json"))));
        workflow.put("requires", List.of("ai.model_choice"));
        List<Object> blocks = new java.util.ArrayList<>();
        for (Map<String, Object> original : (List<Map<String, Object>>) workflow.get("blocks")) {
            Map<String, Object> block = new java.util.LinkedHashMap<>(original);
            Map<String, Object> config = new java.util.LinkedHashMap<>((Map<String, Object>) original.get("config"));
            config.remove("container_image");
            String command = (String) config.get("command");
            if (original.get("type").equals("foreach")) {
                assertThat(config.remove("execution_mode")).isEqualTo("ai");
                assertThat(config.get("model_choice")).isEqualTo("judge");
                config.put("execution_mode", "shell");
                config.remove("tools");
                config.remove("untrusted_input");
                config.put("values", Map.of("AREA_ITEM", "{{ area.item }}", "AREA_ID", "{{ area.id }}"));
                config.put("command", "set -euo pipefail; echo {{ judge }} {{ judge_provider }} {{ judge_model }}"
                        + " \"$AREA_ITEM\" >> '" + asked + "'; cat {{ package_dir }}/answer-\"$AREA_ID\".json");
            } else {
                assertThat(command).satisfiesAnyOf(c -> assertThat(c).containsOnlyOnce("; areas find "),
                        c -> assertThat(c).containsOnlyOnce("| areas report "));
                config.put("command", command.replace("; areas find ", "; " + tool + " find ")
                        .replace("| areas report ", "| " + tool + " report "));
            }
            block.put("config", config);
            blocks.add(block);
        }
        workflow.put("blocks", blocks);
        Files.writeString(pkg.resolve("workflow.json"), Json.write(workflow));
        return pkg;
    }

    private Path newest(Path home, String file) throws IOException {
        try (Stream<Path> walk = Files.walk(home.resolve("runs"))) {
            return walk.filter(p -> p.getFileName().toString().equals(file))
                    .max(java.util.Comparator.comparing(p -> p.toFile().lastModified())).orElseThrow();
        }
    }

    /**
     * The model is picked at the start of a run, one run per model, and one register for all of them. The first run
     * is judged by the option its input names, the second by the default: the verdict step is started with that
     * option's provider and model, the report and findings.jsonl name them, the record of the run says which option
     * was picked, and the finding the first judge reported is the same finding when the second states it again:
     * seen, not new. An area the first judge found clean is settled for the second, which is not asked about it.
     */
    @Test
    void thePickedOptionJudges_theReportNamesIt_andTwoPicksFeedOneRegisterWithOneFinding() throws IOException {
        Path work = Files.createDirectories(temp.resolve("work/app/src/main/java/p"));
        Files.writeString(work.resolve("A.java"), "package p;\nclass A {\n"
                + "    void run(String c) throws Exception { new ProcessBuilder(c).start(); }\n}\n");
        Files.writeString(work.resolve("B.java"), "package p;\nclass B { String f() { return System.getenv(\"HOME\"); } }\n");
        Path asked = temp.resolve("asked-judges.txt");
        Path pkg = standInForThePackage(asked);
        Path home = Files.createDirectories(temp.resolve("home-judges"));
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TRACEWORKS_ACTOR", "tester");
        String[] run = {"run", pkg.toString(), "--workspace", temp.resolve("work").toString(), "--home",
                home.toString(), "--no-input", "--allow-host-execution", "--quiet", "--no-check"};
        String itemA = "app/src/main/java/p/A.java#A.run(String)";
        String itemB = "app/src/main/java/p/B.java#B.f()";
        String found = "\"severity\":\"medium\",\"line\":3,\"quote\":\"new ProcessBuilder(c).start();\","
                + "\"issue\":\"The model's words.\",\"fix\":\"More.\"}";

        // The first run, judged by Astra through Pi
        Files.writeString(pkg.resolve("answer-A001.json"), "{\"area\":\"A001\",\"findings\":[{\"rule\":\"process_start\","
                + found + "]}");
        Files.writeString(pkg.resolve("answer-A002.json"), "{\"area\":\"A002\",\"findings\":[]}");
        String[] withAstra = java.util.Arrays.copyOf(run, run.length + 2);
        withAstra[run.length] = "--input";
        withAstra[run.length + 1] = "judge=astra";
        String first = cli(env, withAstra);

        assertThat(first).contains("register security: 1 new, 0 resolved, 0 reopened, 1 open now");
        assertThat(Files.readAllLines(asked)).as("the step was started with the picked option, for every area")
                .containsExactly("astra PI openai/gpt-6-astra " + itemA, "astra PI openai/gpt-6-astra " + itemB);
        assertThat(Files.readString(newest(home, "security-report.md"))).contains("**Judged by:** provider `PI`, model"
                + " `openai/gpt-6-astra` (the option `astra` of the model choice `judge`, as this run picked it");
        assertThat(Files.readString(newest(home, "findings.jsonl")))
                .contains("\"judge_provider\":\"PI\",\"judge_model\":\"openai/gpt-6-astra\"");
        String firstRun = newest(home, "security-report.md").toString().replaceAll(".*/runs/[^/]+/([^/]+)/.*", "$1");
        assertThat(cli(env, "show", firstRun, "--home", home.toString()))
                .contains("Model     judge = astra (picked): PI openai/gpt-6-astra");
        String listed = cli(env, "findings", "security", "list", "--all", "--home", home.toString());
        assertThat(listed.lines().toList()).hasSize(1);
        String id = listed.substring(0, 8);
        assertThat(cli(env, "findings", "security", "show", id, "--home", home.toString()))
                .contains("Occurrences 1", "judge_provider PI", "judge_model openai/gpt-6-astra");
        Files.delete(asked);

        // The second run, without the input: the default option, asked about the open finding of the first
        Files.writeString(pkg.resolve("answer-A001.json"), "{\"area\":\"A001\",\"findings\":[],\"known\":[{\"id\":\"K1\","
                + "\"status\":\"holds\"," + found + "]}");
        Files.delete(pkg.resolve("answer-A002.json"));
        String second = cli(env, run);

        assertThat(second).as(first).contains("register security: 0 new, 0 resolved, 0 reopened, 1 open now");
        assertThat(Files.readAllLines(asked)).as("the area the first judge found clean is settled for the second")
                .containsExactly("fable CLAUDE claude-fable-5-2 " + itemA);
        assertThat(Files.readString(newest(home, "security-report.md"))).contains("**Judged by:** provider `CLAUDE`,"
                + " model `claude-fable-5-2` (the option `fable` of the model choice `judge`");
        String secondRun = newest(home, "security-report.md").toString().replaceAll(".*/runs/[^/]+/([^/]+)/.*", "$1");
        assertThat(cli(env, "show", secondRun, "--home", home.toString()))
                .contains("Model     judge = fable (the default): CLAUDE claude-fable-5-2");
        assertThat(cli(env, "diff", firstRun, secondRun, "--home", home.toString()))
                .contains("the inputs differ", "the model choice judge differs: astra (picked): PI openai/gpt-6-astra"
                        + " in run A, fable (the default): CLAUDE claude-fable-5-2 in run B");
        assertThat(cli(env, "findings", "security", "list", "--all", "--home", home.toString()).lines().toList())
                .as("one finding, whoever reported it").hasSize(1).allSatisfy(l -> assertThat(l).startsWith(id));
        assertThat(cli(env, "findings", "security", "show", id, "--home", home.toString()))
                .contains("Occurrences 2", "judge_provider CLAUDE", "judge_model claude-fable-5-2");
        assertThat(cli(env, "findings", "security", "items", "--home", home.toString()))
                .matches("open        [0-9a-f]{16}  " + java.util.regex.Pattern.quote(itemA) + "\n"
                        + "clean       [0-9a-f]{16}  " + java.util.regex.Pattern.quote(itemB) + "\n");
    }

    /**
     * A finding whose code is gone leaves the register by measurement, through the package's own workflow, the engine
     * and the register, over two runs. The first run reports two findings. Then a package is renamed, so the file of
     * the first has another path, and the method of the second gets another name. The second run names both gone
     * without asking anybody (the file is gone, the member is gone), and the register resolves them. The subject of
     * the first is longer than the 300 characters the block of {@code {{ audit_findings }}} shows of one: read from
     * that block, the finding stayed open in every later run, and no run asked about it. The renamed method is not
     * lost: its code is an area of the same run, the judge sees the defect there and reports it under the new name.
     */
    @Test
    void aFindingWhoseFileOrMemberIsGone_isResolvedByTheNextRun_andARenamedMembersDefectIsReportedUnderItsNewName()
            throws IOException {
        Path sources = Files.createDirectories(temp.resolve("work/app/src/main/java"));
        Path before = Files.createDirectories(sources.resolve("io/github/old/traceworks/p"));
        StringBuilder longClass = new StringBuilder("package p;\nclass A {\n");
        for (int i = 1; i <= 8; i++) {
            longClass.append("    void aMethodWithAVeryLongNameNumber").append(i)
                    .append("(String c, String d) throws Exception { new ProcessBuilder(c).start(); }\n");
        }
        Files.writeString(before.resolve("A.java"), longClass + "}\n");
        Path p = Files.createDirectories(sources.resolve("p"));
        Files.writeString(p.resolve("B.java"), "package p;\nclass B { String f() { return System.getenv(\"HOME\"); } }\n");
        Files.writeString(p.resolve("C.java"), "package p;\nclass C {\n"
                + "    void old(String c) throws Exception { new ProcessBuilder(c).start(); }\n}\n");
        Path asked = temp.resolve("asked-gone.txt");
        Path pkg = standInForThePackage(asked);
        Path home = Files.createDirectories(temp.resolve("home-gone"));
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TRACEWORKS_ACTOR", "tester");
        String[] run = {"run", pkg.toString(), "--workspace", temp.resolve("work").toString(), "--home",
                home.toString(), "--no-input", "--allow-host-execution", "--quiet", "--no-check"};
        String found = "{\"rule\":\"process_start\",\"severity\":\"medium\",\"line\":3,"
                + "\"quote\":\"new ProcessBuilder(c).start();\",\"issue\":\"The model's words.\",\"fix\":\"More.\"}";

        // The first run: a finding in the long class and one in C.old
        Files.writeString(pkg.resolve("answer-A001.json"), "{\"area\":\"A001\",\"findings\":[" + found + "]}");
        Files.writeString(pkg.resolve("answer-A002.json"), "{\"area\":\"A002\",\"findings\":[]}");
        Files.writeString(pkg.resolve("answer-A003.json"), "{\"area\":\"A003\",\"findings\":[" + found + "]}");
        String first = cli(env, run);
        assertThat(first).contains("register security: 2 new, 0 resolved, 0 reopened, 2 open now");
        String subjects = cli(env, "findings", "security", "list", "--json", "--home", home.toString());
        String stale = "app/src/main/java/io/github/old/traceworks/p/A.java#A.aMethodWithAVeryLongNameNumber1(String, String)";
        assertThat(subjects).contains(stale + ", A.aMethodWithAVeryLongNameNumber2(String, String)",
                "app/src/main/java/p/C.java#C.old(String)");
        Files.delete(asked);

        // The package is renamed, and so is the method. B is settled; C and the moved class are judged
        Files.move(before.resolve("A.java"), p.resolve("Moved.java"));
        Files.writeString(p.resolve("C.java"), Files.readString(p.resolve("C.java")).replace("void old(", "void renamed("));
        Files.writeString(pkg.resolve("answer-A002.json"), "{\"area\":\"A002\",\"findings\":[" + found + "]}");
        Files.writeString(pkg.resolve("answer-A003.json"), "{\"area\":\"A003\",\"findings\":[]}");
        Files.delete(pkg.resolve("answer-A001.json"));
        String second = cli(env, run);

        assertThat(second).as(first).contains("register security: 1 new, 2 resolved, 0 reopened, 1 open now")
                .doesNotContain("not mentioned by this report");
        assertThat(Files.readAllLines(asked)).as("the renamed method and the moved class are judged in the same run")
                .containsExactly("fable CLAUDE claude-fable-5-2 app/src/main/java/p/C.java#C.renamed(String)",
                        "fable CLAUDE claude-fable-5-2 app/src/main/java/p/Moved.java#A.aMethodWithAVeryLongNameNumber1"
                                + "(String, String)");
        String report = Files.readString(newest(home, "security-report.md"));
        assertThat(report).contains("## Earlier findings that are gone (2)", "| The file is gone | 1 |",
                "| No area has this subject any more | 1 |", "| The judge answered that it is gone | 0 |",
                "- process_start `app/src/main/java/p/C.java#C.old(String)`: no area has this subject any more\n")
                .containsPattern("- process_start `" + java.util.regex.Pattern.quote(stale) + ",[^`]*`: the file is gone\n")
                .doesNotContain("## Open findings that no item carries", "## Open findings without an answer");
        List<String> list = cli(env, "findings", "security", "list", "--all", "--home", home.toString()).lines().toList();
        assertThat(list).hasSize(3)
                .anySatisfy(l -> assertThat(l).contains("RESOLVED").contains("io/github/old/traceworks/p/A.java#"))
                .anySatisfy(l -> assertThat(l).contains("RESOLVED").contains("C.java#C.old(String)"))
                .anySatisfy(l -> assertThat(l).contains("OPEN").contains("C.java#C.renamed(String)"));
        assertThat(cli(env, "findings", "security", "verify", "--home", home.toString())).contains("verified")
                .doesNotContain("NOT intact");
    }

    /**
     * No finding stays open where no run sees it. The files of the register folder list the findings of the rules
     * the catalog holds, so a finding whose rule left the catalog (a rule renamed or removed in the rule file and the
     * catalog) is shown to no run and never asked about. It does not stay open for that: a rule the catalog does not
     * hold is no judged rule, so the register takes the finding as a measurement, and the next applied report, a
     * full snapshot that does not state it, resolves it. No report can state it either: one that names a rule
     * outside the catalog is rejected whole. The defect, if it is still there, is found again under the new rule,
     * whose area the register does not know and the same run judges. The report of the run that applies the change
     * names the rules that left, from the rules the previous report printed; it cannot name their findings, since
     * the register folder lists those of catalogued rules only, resolved ones too.
     */
    @Test
    void aFindingWhoseRuleLeftTheCatalog_isShownToNoRun_isResolvedByTheNextAppliedReport_andTheReportsNameIt()
            throws IOException {
        Path work = Files.createDirectories(temp.resolve("work/app/src/main/java/p"));
        for (String name : List.of("A", "C")) {
            Files.writeString(work.resolve(name + ".java"), "package p;\nclass " + name + " {\n"
                    + "    void run(String c) throws Exception { new ProcessBuilder(c).start(); }\n}\n");
        }
        Path asked = temp.resolve("asked-rule.txt");
        Path pkg = standInForThePackage(asked);
        Path home = Files.createDirectories(temp.resolve("home-rule"));
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TRACEWORKS_ACTOR", "tester");
        String[] run = {"run", pkg.toString(), "--workspace", temp.resolve("work").toString(), "--home",
                home.toString(), "--no-input", "--allow-host-execution", "--quiet", "--no-check"};
        Files.writeString(pkg.resolve("answer-A001.json"), "{\"area\":\"A001\",\"findings\":[{\"rule\":\"process_start\","
                + "\"severity\":\"high\",\"line\":3,\"quote\":\"new ProcessBuilder(c).start();\",\"issue\":\"i\","
                + "\"fix\":\"f\"}]}");
        Files.writeString(pkg.resolve("answer-A002.json"), Files.readString(pkg.resolve("answer-A001.json"))
                .replace("A001", "A002"));
        assertThat(cli(env, run)).contains("register security: 2 new, 0 resolved, 0 reopened, 2 open now");
        String listedFirst = cli(env, "findings", "security", "list", "--home", home.toString());
        String idOfC = listedFirst.lines().filter(l -> l.contains("C.java")).findFirst().orElseThrow().substring(0, 8);
        assertThat(cli(env, "findings", "security", "dismiss", idOfC, "--note", "not ours", "--home", home.toString()))
                .doesNotContain("rror");

        // The rule gets another name, in the rule file and in the catalog
        for (String file : List.of("rules/security.rules", "catalog.json")) {
            String text = Files.readString(pkg.resolve(file));
            assertThat(text).contains("process_start");
            Files.writeString(pkg.resolve(file), text.replace("process_start", "process_begin"));
        }
        Files.writeString(pkg.resolve("answer-A001.json"), "{\"area\":\"A001\",\"findings\":[]}");
        Files.writeString(pkg.resolve("answer-A002.json"), "{\"area\":\"A002\",\"findings\":[]}");
        String second = cli(env, run);

        assertThat(Files.readString(newest(home, "areas.jsonl"))).as("the run was not shown the finding")
                .contains("\"known\":[]").doesNotContain("process_start");
        assertThat(second).contains("register security: 0 new, 2 resolved, 0 reopened, 0 open now")
                .doesNotContain("not mentioned by this report");
        assertThat(cli(env, "findings", "security", "list", "--all", "--home", home.toString()).lines().toList())
                .hasSize(2).allSatisfy(line -> assertThat(line).contains("RESOLVED", "process_start"));
        assertThat(cli(env, "findings", "security", "show", idOfC, "--home", home.toString()))
                .as("the decided one too").contains("RESOLVED");
        assertThat(Files.readString(newest(home, "security-report.md"))).contains("## Rules that left the catalog (1)",
                "- process_start: the register resolves every finding of it when it applies this report, a decided"
                        + " one too. `traceworks findings security list --all` shows them as RESOLVED.");

        // The run after it names no rule again: the previous report holds the new rules
        cli(env, run);
        assertThat(Files.readString(newest(home, "security-report.md"))).doesNotContain("Rules that left the catalog");
        // What no run can name: the register folder lists no finding of a rule outside the catalog, resolved or not
        assertThat(walk(home)).doesNotContain("\"rule_key\":\"process_start\"");
    }

    /** A pick the package does not declare starts no run: no step runs, nobody is asked, nothing is reported. */
    @Test
    void aPickThePackageDoesNotDeclare_startsNoRun_andNoVerdictIsAskedFor() throws IOException {
        Path work = Files.createDirectories(temp.resolve("work/app/src/main/java/p"));
        Files.writeString(work.resolve("A.java"), "package p;\nclass A {\n"
                + "    void run(String c) throws Exception { new ProcessBuilder(c).start(); }\n}\n");
        Path asked = temp.resolve("asked-unknown.txt");
        Path pkg = standInForThePackage(asked);
        Path home = Files.createDirectories(temp.resolve("home-unknown"));
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TRACEWORKS_ACTOR", "tester");

        String output = cli(env, "run", pkg.toString(), "--workspace", temp.resolve("work").toString(), "--home",
                home.toString(), "--no-input", "--allow-host-execution", "--no-check", "--input", "judge=sonnet");

        assertThat(output).contains("The run is refused: the model choice \"judge\" has no option \"sonnet\": the"
                + " input judge picks one of fable, opus, astra (the default is fable)");
        assertThat(asked).doesNotExist();
        assertThat(home.resolve("runs")).as("no run exists").doesNotExist();
        assertThat(home.resolve("registers")).as("no register was fed").doesNotExist();
    }

    private static String walk(Path home) throws IOException {
        StringBuilder out = new StringBuilder();
        try (Stream<Path> files = Files.walk(home.resolve("runs"))) {
            for (Path p : files.filter(f -> f.toString().contains("/register/")).sorted().toList()) {
                out.append("\n== ").append(p).append('\n');
                if (Files.isRegularFile(p)) out.append(Files.readString(p));
            }
        }
        return out.toString();
    }

    private String cli(Map<String, String> env, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        traceworks.cli.main.Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), env, temp, null, RuntimeSource.NONE);
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }
}
