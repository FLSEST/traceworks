package traceworks.selfaudit.areas;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code find --changes} and the report of a check of the changes: which areas are written, with which reason, what
 * the summary counts, what the register is told of the items, and what the report says about the rest of the
 * project. The folder with git's word is written by the test; {@link ChangesScriptTest} asks git.
 */
class ChangesMainTest {

    private static final String SRC = "app/src/main/java/p/";
    private static final String BASE = Handover.BASE;
    private static final String HEAD = Handover.HEAD;

    @TempDir
    Path root;
    @TempDir
    Path work;

    private ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String stdin, String... args) {
        out = new ByteArrayOutputStream();
        return Main.run(args, new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    /** The output of {@code report} with the details in the place of the scope line: what the tests of the details read. */
    private String details() {
        return out().replaceFirst("(?m)^(gate_description: ).*\nDETAILS: ", "$1");
    }

    private String scope() {
        return out().lines().filter(line -> line.startsWith("gate_description: ")).findFirst().orElseThrow()
                .substring("gate_description: ".length());
    }

    private String reportText() throws IOException {
        return Files.readString(work.resolve("report.md")).replaceFirst("(\\*\\*Gate:\\*\\* [A-Z]+: ).*\n\n\\*\\*Details:\\*\\* ", "$1");
    }

    /** Runner.run starts a process; Caller.call calls it; Far.far calls Caller.call. Three areas. */
    @BeforeEach
    void project() throws IOException {
        Path sources = Files.createDirectories(root.resolve(SRC));
        Files.writeString(sources.resolve("Runner.java"), """
                package p;
                class Runner {
                    void run(String c) throws Exception {
                        new ProcessBuilder(c).start();
                    }
                }
                """);
        Files.writeString(sources.resolve("Caller.java"), """
                package p;
                class Caller {
                    void call(Runner runner) throws Exception {
                        runner.run(System.getenv("CMD"));
                    }
                }
                """);
        Files.writeString(sources.resolve("Far.java"), """
                package p;
                class Far {
                    void far(Caller caller) throws Exception {
                        System.getenv("X");
                        caller.call(new Runner());
                    }
                }
                """);
        Files.writeString(work.resolve("security.rules"), """
                rule process_start
                  why Starts a process.
                  new java.lang.ProcessBuilder
                rule env_pass
                  why Reads the environment.
                  call java.lang.System#getenv
                """);
    }

    private static final String RUNNER = SRC + "Runner.java#Runner.run(String)";
    private static final String CALLER = SRC + "Caller.java#Caller.call(Runner)";
    private static final String FAR = SRC + "Far.java#Far.far(Caller)";

    /**
     * The folder the git step hands over, for a workspace that is its own commit. Each named file differs from the
     * base: a Java file of the project in its line 4 (or, written {@code 1:<file>}, in its line 1); a file the
     * workspace does not have yet is written and is older in the base; {@code -<file>} is gone from the workspace,
     * and {@code ?<file>} is a directory there now.
     */
    private Path changes(String... changedFiles) throws IOException {
        Map<String, String> changedInBase = new java.util.TreeMap<>();
        for (String spec : changedFiles) {
            String file = spec.replaceFirst("^(-|\\?|1:)", "");
            if (spec.startsWith("-")) {
                changedInBase.put(file, "gone\n");
            } else if (spec.startsWith("?")) {
                Files.createDirectories(root.resolve(file));
                changedInBase.put(file, "a file once\n");
            } else if (Files.exists(root.resolve(file)) && Files.readAllLines(root.resolve(file)).size() >= 4) {
                List<String> lines = new ArrayList<>(Files.readAllLines(root.resolve(file)));
                lines.set(spec.startsWith("1:") ? 0 : 3, "old");
                changedInBase.put(file, String.join("\n", lines) + "\n");
            } else {
                Files.createDirectories(root.resolve(file).getParent());
                Files.writeString(root.resolve(file), "new\n");
                changedInBase.put(file, "old\n");
            }
        }
        Map<String, String> head = Handover.files(root);
        Map<String, String> base = new java.util.TreeMap<>(head);
        base.putAll(changedInBase);
        return Handover.write(work.resolve("changes"), base, head);
    }

    private String[] find(String... more) {
        List<String> args = new ArrayList<>(List.of("find", "--rules", work.resolve("security.rules").toString(),
                "--root", root.toString(), "--out", work.resolve("areas.jsonl").toString(),
                "--summary", work.resolve("areas-summary.json").toString(),
                "--changes", work.resolve("changes").toString(), "--register", "security-changes"));
        args.addAll(List.of(more));
        return args.toArray(String[]::new);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summary() throws IOException {
        return (Map<String, Object>) Json.parse(Files.readString(work.resolve("areas-summary.json")));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> areas() throws IOException {
        List<Map<String, Object>> areas = new ArrayList<>();
        for (String line : Files.readAllLines(work.resolve("areas.jsonl"))) {
            areas.add((Map<String, Object>) Json.parse(line));
        }
        return areas;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items() throws IOException {
        return (List<Map<String, Object>>) summary().get("items");
    }

    @Test
    void find_writesTheChangedAreaAndItsNeighbour_eachWithItsReason_andCountsWhatItDidNotLookAt() throws IOException {
        changes(SRC + "Runner.java", "README.md");

        assertThat(run("", find())).as(err.toString()).isZero();

        assertThat(areas()).extracting(a -> a.get("item") + ": " + a.get("reason"))
                .containsExactly(CALLER + ": neighbour of Runner.run(String)", RUNNER + ": changed");
        Map<String, Object> neighbour = areas().getFirst();
        assertThat((String) neighbour.get("connected")).as("the changed code the neighbour is connected with")
                .startsWith(SRC + "Runner.java, Runner.run(String):\n3|     void run(String c) throws Exception {")
                .contains("4|         new ProcessBuilder(c).start();");
        assertThat(areas().get(1).get("connected")).isEqualTo("(none)");
        assertThat(summary().get("changes")).isEqualTo(Map.ofEntries(Map.entry("base", BASE), Map.entry("head", HEAD),
                Map.entry("from", "input"), Map.entry("since", "v1"), Map.entry("previousHead", ""),
                Map.entry("dirty", false), Map.entry("changedFiles", 2L), Map.entry("testFiles", 0L),
                Map.entry("docFiles", 1L), Map.entry("uncompiledFiles", 0L), Map.entry("uncompiled", List.of()),
                Map.entry("cannotJudgeFiles", 0L), Map.entry("cannotJudge", List.of()),
                Map.entry("removedJavaFiles", 0L), Map.entry("removedJava", List.of()),
                Map.entry("commits", ""), Map.entry("fullAudit", ""), Map.entry("fullAuditBehind", ""),
                Map.entry("baseDate", "2026-11-10T09:00:00Z"), Map.entry("skipped", ""),
                Map.entry("removedFiles", 0L), Map.entry("unmappable", List.of()),
                Map.entry("outsideMembers", 0L), Map.entry("outside", List.of()),
                Map.entry("changedMembers", 1L),
                Map.entry("areasOfProject", 3L), Map.entry("changedAreas", 1L), Map.entry("neighbourAreas", 1L),
                Map.entry("carriedAreas", 0L), Map.entry("judgedAsTheyAre", 0L), Map.entry("notLookedAt", 1L)));
        assertThat(items()).as("an area nothing looked at and the register does not know is no item")
                .extracting(i -> i.get("name")).containsExactly(CALLER, RUNNER);
        assertThat(out()).contains("FACT changes_base: " + BASE + "\n", "FACT changes_head: " + HEAD + "\n",
                "FACT changes_base_from: the input since\n", "FACT changes_areas_changed: 1\n",
                "FACT changes_areas_neighbours: 1\n", "FACT changes_areas_not_looked_at: 1\n", "FACT areas: 2\n",
                "[env_pass] because: neighbour of Runner.run(String)\n");
    }

    @Test
    void theDigestOfANeighbour_holdsTheChangedCodeItIsConnectedWith_soItDiffersFromTheAreasOwn() throws IOException {
        changes(SRC + "Runner.java");
        run("", find());
        String asNeighbour = (String) areas().getFirst().get("digest");
        changes(SRC + "Caller.java");
        run("", find());
        Map<String, Object> caller = areas().stream().filter(a -> CALLER.equals(a.get("item"))).findFirst().orElseThrow();

        assertThat(caller.get("reason")).isEqualTo("changed");
        assertThat(caller.get("digest")).isNotEqualTo(asNeighbour);
    }

    @Test
    void whatAnEarlierRunLeftOpenOrWithoutAVerdict_isInTheRun_andWhatItSettledStaysListedAsItWas() throws IOException {
        changes();
        Path items = Files.writeString(work.resolve("items.jsonl"), """
                {"digest":"1111111111111111","name":"%s","state":"not judged","verdict":null,"verdict_digest":null}
                {"digest":"2222222222222222","name":"%s","state":"clean","verdict":{"at":"t","run_id":"r"},"verdict_digest":"2222222222222222"}
                {"digest":"3333333333333333","name":"app/src/main/java/p/Gone.java#Gone.x()","state":"decided","verdict":{"at":"t","run_id":"r"},"verdict_digest":"3333333333333333"}
                """.formatted(CALLER, FAR));
        Path known = Files.writeString(work.resolve("known.txt"), "<audit_findings>\nOPEN | MEDIUM | process_start | "
                + RUNNER + " | t | Evidence: e\n</audit_findings>");

        assertThat(run("", find("--items-file", items.toString(), "--known", known.toString()))).as(err.toString())
                .isZero();

        assertThat(areas()).extracting(a -> a.get("item") + ": " + a.get("reason")).containsExactly(
                CALLER + ": not judged by an earlier run", RUNNER + ": an open finding of an earlier run");
        assertThat(areas().get(1).get("known_text")).isEqualTo("K1 | process_start | MEDIUM | e");
        assertThat(items()).as("the settled item keeps the digest of its verdict; the area that is gone is not listed,"
                        + " so it leaves the register")
                .extracting(i -> i.get("name") + " " + i.get("digest") + " " + i.get("chosen") + " " + i.get("settled"))
                .contains(FAR + " 2222222222222222 false true").hasSize(3)
                .noneMatch(line -> line.contains("Gone.java"));
        Map<?, ?> changes = (Map<?, ?>) summary().get("changes");
        assertThat(changes.get("carriedAreas")).isEqualTo(2L);
        assertThat(changes.get("notLookedAt")).isEqualTo(1L);
    }

    @Test
    void aChangedAreaAnEarlierRunJudgedAsItIsNow_isNotJudgedAgain() throws IOException {
        changes(SRC + "Runner.java");
        run("", find());
        String digest = (String) areas().get(1).get("digest");
        Path items = Files.writeString(work.resolve("items.jsonl"), "{\"digest\":\"" + digest + "\",\"name\":\""
                + RUNNER + "\",\"state\":\"clean\",\"verdict\":{\"at\":\"t\",\"run_id\":\"r\"},\"verdict_digest\":\""
                + digest + "\"}\n");

        assertThat(run("", find("--items-file", items.toString()))).isZero();

        assertThat(areas()).extracting(a -> a.get("item")).containsExactly(CALLER);
        Map<?, ?> changes = (Map<?, ?>) summary().get("changes");
        assertThat(changes.get("changedAreas")).isEqualTo(0L);
        assertThat(changes.get("judgedAsTheyAre")).isEqualTo(1L);
        assertThat(changes.get("notLookedAt")).isEqualTo(2L);
    }

    @Test
    void theReport_namesTheBase_countsChangedNeighboursAndWhatWasNotLookedAt_andSaysWhyEachAreaWasIn()
            throws IOException {
        changes(SRC + "Runner.java", "README.md");
        run("", find());
        List<String> lines = Files.readAllLines(work.resolve("areas.jsonl"));
        String verdicts = "[" + lines.get(0) + "]: {\"area\":\"A001\",\"findings\":[]}\n"
                + "[" + lines.get(1) + "]: {\"area\":\"A003\",\"findings\":[]}\n";

        assertThat(run(verdicts, "report", "--areas", work.resolve("areas.jsonl").toString(), "--summary",
                work.resolve("areas-summary.json").toString(), "--verdicts", "-", "--out",
                work.resolve("report.md").toString())).as(err.toString()).isZero();

        String described = "of the changes since aaaaaaaaaaaa: no verified finding; 2 of 2 areas judged; 2 of 2 items"
                + " settled (2 clean, 0 decided; 0 open, 0 not judged); 1 of 3 areas of the project not looked at";
        assertThat(details()).startsWith("gate_status: YELLOW\ngate_category: changes\ngate_description: " + described
                + "\nFACT changes_base: " + BASE + "\nFACT changes_head: " + HEAD + "\nFACT changes_areas_changed: 1\n"
                + "FACT changes_areas_neighbours: 1\nFACT changes_areas_carried: 0\n"
                + "FACT changes_areas_not_looked_at: 1\n");
        assertThat(out()).contains("\"expectedItems\":2,\"items\":[{\"name\":\"" + CALLER + "\"");
        assertThat(reportText()).startsWith("# Security check of the changes\n\n"
                        + "**Gate:** YELLOW: " + described + "\n\n**This gate speaks about the changes only.** It says"
                        + " nothing about the 1 of 3 areas of the project that this run did not look at; the long"
                        + " audit judges every area.\n\n## What was looked at\n")
                .contains("| Base: the commit the changes are measured from | `" + BASE
                                + "`, committed 2026-11-10T09:00:00Z |\n",
                        "| Where the base is from | the input `since`: `v1` |\n",
                        "| Workspace commit | `" + HEAD + "`, with nothing uncommitted |\n",
                        "| Files that differ from the base | 2 (0 of them removed) |\n",
                        "| Of them tests (`src/test/java`), not judged by this check | 0 |\n",
                        "| Of them documentation (`*.md`, `docs/`) | 1 |\n",
                        "| Of them Java files that no compiled source root holds | 0 |\n",
                        "| Of them files this check cannot judge (a script, a workflow, a build file, a resource)"
                                + " | 0 |\n",
                        "| Changed files that could not be compared | 0 |\n",
                        "| Areas of the project | 3 |\n", "| Areas with a changed line | 1 |\n",
                        "| Neighbours: areas one call from a changed member | 1 |\n",
                        "| Areas an earlier run left open or without a verdict | 0 |\n",
                        "| Areas of the project not looked at | 1 (0 of them changed or a neighbour, and judged by an"
                                + " earlier run as they are now) |\n",
                        "## Why each area was in\n\n- A001 `" + CALLER + "`: neighbour of Runner.run\\(String\\)\n"
                                + "- A003 `" + RUNNER + "`: changed\n");
    }

    @Test
    void theReportOfARunWithNothingChanged_isGreenOverNothing_andStillNamesItsHeadForTheNextRun() throws IOException {
        changes();
        run("", find());

        assertThat(run("", "report", "--areas", work.resolve("areas.jsonl").toString(), "--summary",
                work.resolve("areas-summary.json").toString(), "--verdicts", "-", "--out",
                work.resolve("report.md").toString())).isZero();

        assertThat(details()).startsWith("gate_status: YELLOW\ngate_category: changes\ngate_description: nothing"
                + " changed since aaaaaaaaaaaa: ").contains("\nFACT changes_head: " + HEAD + "\n",
                "3 of 3 areas of the project not looked at");
        assertThat(Files.readString(work.resolve("report.md"))).contains("No area: nothing changed in an area or next"
                + " to one, and nothing was left open.", "**Nothing changed.** No file of the workspace differs from"
                + " the base.");
    }

    @Test
    void theItemsOfTheRegisterFolder_areReadWhole_andALineThatIsNoItemIsRefused() throws IOException {
        assertThat(Items.parseJsonl("")).isEmpty();
        assertThat(Items.parseJsonl("{\"name\":\"a | b\",\"state\":\"open\",\"verdict_digest\":\"d1\"}\n"
                + "{\"name\":\"c\",\"state\":\"not judged\",\"verdict_digest\":null}\n"))
                .containsExactly(Map.entry("a ¦ b", new Items.Row("open", "d1")),
                        Map.entry("c", new Items.Row("not judged", "")));
        changes();
        Path items = Files.writeString(work.resolve("items.jsonl"), "{\"name\":\"x\",\"state\":\"settled\"}\n");

        assertThat(run("", find("--items-file", items.toString()))).isEqualTo(1);
        assertThat(err.toString()).contains("a line of the register's items is no item");
    }

    private int report() {
        return run("", "report", "--areas", work.resolve("areas.jsonl").toString(), "--summary",
                work.resolve("areas-summary.json").toString(), "--verdicts", "-", "--out",
                work.resolve("report.md").toString());
    }

    @Test
    void aChangedFileThatCouldNotBeCompared_isNamed_andTheGateIsNotGreen() throws IOException {
        changes("?" + SRC + "Odd.java", "-" + SRC + "Gone.java");
        assertThat(run("", find())).as(err.toString()).isZero();
        assertThat(out()).contains("FACT changes_files: 2\n", "FACT changes_files_not_compared: 1\n",
                "NOT MEASURED: changes_file " + SRC + "Odd.java (not a readable regular file)\n");

        assertThat(report()).isZero();

        assertThat(details()).startsWith("gate_status: YELLOW\ngate_category: changes\ngate_description: of the changes"
                + " since aaaaaaaaaaaa: 1 changed file could not be compared; 1 removed Java file; no verified finding; ");
        assertThat(Files.readString(work.resolve("report.md")))
                .contains("| Files that differ from the base | 2 (1 of them removed) |",
                        "| Changed files that could not be compared | 1 |",
                        "Changed files that could not be compared. Nothing mapped them to an area, so the gate is not"
                                + " GREEN: `" + SRC + "Odd.java (not a readable regular file)`");
    }

    @Test
    void commitsTheBaseSkipped_areSaidWithTheirRange_andTheGateIsNotGreen() throws IOException {
        Path dir = changes(SRC + "Runner.java");
        String previous = "c".repeat(40);
        Files.writeString(dir.resolve("previous-head"), previous + "\n");
        Files.writeString(dir.resolve("skipped"), "4\n");
        run("", find());
        List<String> lines = Files.readAllLines(work.resolve("areas.jsonl"));
        String verdicts = "[" + lines.get(0) + "]: {\"area\":\"A001\",\"findings\":[]}\n"
                + "[" + lines.get(1) + "]: {\"area\":\"A003\",\"findings\":[]}\n";

        assertThat(run(verdicts, "report", "--areas", work.resolve("areas.jsonl").toString(), "--summary",
                work.resolve("areas-summary.json").toString(), "--verdicts", "-", "--out",
                work.resolve("report.md").toString())).isZero();

        assertThat(details()).startsWith("gate_status: YELLOW\ngate_category: changes\ngate_description: of the changes"
                + " since aaaaaaaaaaaa: 4 commits after the last run skipped and not judged; no verified finding; 2 of"
                + " 2 areas judged; 2 of 2 items settled");
        assertThat(Files.readString(work.resolve("report.md"))).contains("| **Commits skipped** | **4**: the commits"
                + " after `" + previous + "` up to `" + BASE + "` (`git log cccccccccccc..aaaaaaaaaaaa`) are judged by"
                + " no run of this check. The run was started with `skip=yes`.");

        Files.writeString(dir.resolve("skipped"), "unknown\n");
        run("", find());
        assertThat(report()).isZero();
        assertThat(details()).startsWith("gate_status: YELLOW\n").contains("the commit of the last run is not in this"
                + " checkout, so commits may have been skipped");
    }

    @Test
    void aChangedLineInTheHeadOfAFile_isCountedAndNamed_andEveryAreaOfTheFileIsJudged() throws IOException {
        changes("1:" + SRC + "Runner.java");

        assertThat(run("", find())).as(err.toString()).isZero();
        assertThat(areas()).extracting(a -> a.get("item") + ": " + a.get("reason"))
                .as("every area of the file, and the areas that call into it").containsExactly(
                        CALLER + ": neighbour of Runner.run(String)",
                        FAR + ": neighbour of Runner (the head of its class)",
                        RUNNER + ": changed: the head of its file (outside every member)");
        assertThat(report()).isZero();

        assertThat(Files.readString(work.resolve("report.md")))
                .contains("| Changed places in no member (an import, the head of a class): every area of their file"
                                + " is judged | 1 |",
                        "Changed places that lie in no member: `" + SRC + "Runner.java:1-1`");
    }

    @Test
    void aChangedFileThisCheckCannotJudge_isNamed_andTheGateIsNotGreen_butATestOrADocumentIsOnlyCounted()
            throws IOException {
        changes("run.sh", "app/pom.xml", "-old.sh", "README.md", "docs/notes.txt", "app/src/test/java/p/RunnerTest.java");
        assertThat(run("", find())).as(err.toString()).isZero();
        assertThat(out()).contains("FACT changes_files: 6\n", "FACT changes_files_cannot_judge: 3\n",
                "FACT changes_files_tests: 1\n", "FACT changes_files_docs: 2\n",
                "FACT changes_files_java_not_compiled: 0\n");

        assertThat(report()).isZero();

        assertThat(details()).startsWith("gate_status: YELLOW\ngate_category: changes\ngate_description: of the changes"
                + " since aaaaaaaaaaaa: 3 changed files this check cannot judge; no verified finding; ");
        assertThat(Files.readString(work.resolve("report.md")))
                .contains("| Of them tests (`src/test/java`), not judged by this check | 1 |",
                        "| Of them documentation (`*.md`, `docs/`) | 2 |",
                        "| Of them files this check cannot judge (a script, a workflow, a build file, a resource) | 3 |",
                        "Changed, new or removed files this check cannot judge. They can change what the project"
                                + " does, so the gate is not GREEN: `app/pom.xml`, `old.sh`, `run.sh`");

        // Tests and documents alone leave the gate as the items have it
        Files.delete(root.resolve("run.sh"));
        Files.delete(root.resolve("app/pom.xml"));
        changes("README.md", "app/src/test/java/p/RunnerTest.java");
        run("", find());
        assertThat(report()).isZero();
        assertThat(details()).startsWith("gate_status: YELLOW\n");
    }

    /**
     * The check of the changes reads the register's findings as the file of the register folder, like the full
     * audit: a finding of a path that no source file has any more is named gone with that reason, whatever the
     * length of its subject. Its own rule stands above that: when Java outside the compiled roots changed in the
     * run, no finding is named gone, since its member may have moved there.
     */
    @Test
    void aKnownFindingOfTheRegistersFile_whoseFileIsGone_isNamedGone_butNotWhenJavaOutsideTheRootsChanged()
            throws IOException {
        String stale = "app/src/main/java/io/github/old/p/Runner.java#" + "Runner.aLongMember(String, String), ".repeat(9)
                + "Runner.run(String)";
        assertThat(stale.length()).isGreaterThan(300);
        // Not under the name findings.jsonl: report writes its own file of that name beside the report
        Path known = Files.writeString(work.resolve("register-findings.jsonl"), "{\"status\":\"OPEN\",\"severity\":\"HIGH\","
                + "\"rule_key\":\"process_start\",\"subject\":\"" + stale + "\",\"title\":\"t\",\"evidence\":\"e\","
                + "\"disposition\":null}\n{\"status\":\"OPEN\",\"severity\":\"MEDIUM\",\"rule_key\":\"env_pass\","
                + "\"subject\":\"" + SRC + "Caller.java#Caller.old()\",\"title\":\"t\",\"evidence\":\"e\","
                + "\"disposition\":null}\n");
        changes("README.md");

        assertThat(run("", find("--known-file", known.toString()))).as(err.toString()).isZero();

        assertThat(summary().get("knownGone")).isEqualTo(List.of(
                Map.of("ruleKey", "process_start", "subject", stale, "reason", "the file is gone"),
                Map.of("ruleKey", "env_pass", "subject", SRC + "Caller.java#Caller.old()", "reason",
                        "no area has this subject any more")));
        assertThat(summary().get("knownUnowned")).isEqualTo(List.of());
        assertThat(report()).isZero();
        assertThat(out()).contains("FACT report_known_gone: 2\n", "FACT report_known_gone_file: 1\n",
                "FACT report_known_gone_subject: 1\n", "FACT report_known_gone_answered: 0\n");

        // Java changed where the finder does not read: the absence of the member says nothing
        Files.createDirectories(root.resolve("extra/src/p"));
        Files.writeString(root.resolve("extra/src/p/Moved.java"), "package p;\nclass Moved {}\n");
        Map<String, String> head = Handover.files(root);
        Map<String, String> base = new java.util.TreeMap<>(head);
        base.remove("extra/src/p/Moved.java");
        Handover.write(work.resolve("changes"), base, head);

        assertThat(run("", find("--known-file", known.toString()))).as(err.toString()).isZero();

        assertThat(summary().get("knownGone")).isEqualTo(List.of());
        assertThat(summary().get("knownUnowned")).isEqualTo(List.of(
                Map.of("ruleKey", "process_start", "subject", stale, "severity", "HIGH"),
                Map.of("ruleKey", "env_pass", "subject", SRC + "Caller.java#Caller.old()", "severity", "MEDIUM")));
    }

    @Test
    void aClassMovedWhereTheFinderDoesNotRead_isNamed_theGateIsNotGreen_andItsOpenFindingIsNotCalledGone()
            throws IOException {
        // Runner moves to a directory no source root holds; the base has it in the compiled root
        String runner = Files.readString(root.resolve(SRC + "Runner.java"));
        Path moved = Files.createDirectories(root.resolve("extra/src/p"));
        Files.move(root.resolve(SRC + "Runner.java"), moved.resolve("Runner.java"));
        Map<String, String> head = Handover.files(root);
        Map<String, String> base = new java.util.TreeMap<>(head);
        base.remove("extra/src/p/Runner.java");
        base.put(SRC + "Runner.java", runner);
        Handover.write(work.resolve("changes"), base, head);
        Path known = Files.writeString(work.resolve("known.txt"), "<audit_findings>\nOPEN | HIGH | process_start | "
                + RUNNER + " | t | Evidence: e\n</audit_findings>");

        assertThat(run("", find("--known", known.toString()))).as(err.toString()).isZero();

        assertThat(summary().get("knownGone")).as("its member is no area any more, and that says nothing here")
                .isEqualTo(List.of());
        assertThat(summary().get("knownUnowned")).isEqualTo(List.of(Map.of("ruleKey", "process_start",
                "subject", RUNNER, "severity", "HIGH")));
        assertThat(report()).isZero();
        assertThat(details()).startsWith("gate_status: RED\ngate_category: changes\ngate_description: of the changes"
                + " since aaaaaaaaaaaa: 1 changed Java file that no compiled source root holds; ")
                .doesNotContain("\"gone\"");
        assertThat(Files.readString(work.resolve("report.md")))
                .contains("| Of them Java files that no compiled source root holds | 1 |",
                        "Changed or new Java files that no compiled source root holds. Nothing looked at their code,"
                                + " so the gate is not GREEN, and no finding is named as gone because its member is no"
                                + " area any more: it may have moved there: `extra/src/p/Runner.java`",
                        "## Open findings that no item carries");

        // A changed test does not keep a finding open: tests change with nearly every commit, and a member that
        // was removed must be able to take its finding with it
        Path test = Files.createDirectories(root.resolve("app/src/test/java/p"));
        Files.move(moved.resolve("Runner.java"), test.resolve("RunnerTest.java"));
        Map<String, String> asTest = Handover.files(root);
        Handover.write(work.resolve("changes"), base, asTest);
        assertThat(run("", find("--known", known.toString()))).isZero();
        assertThat(summary().get("knownGone")).isEqualTo(List.of(Map.of("ruleKey", "process_start", "subject", RUNNER,
                "reason", "the file is gone")));
        Files.move(test.resolve("RunnerTest.java"), moved.resolve("Runner.java"));

        // Without such a file, a member that is gone takes its finding with it
        Files.delete(moved.resolve("Runner.java"));
        Map<String, String> without = Handover.files(root);
        Handover.write(work.resolve("changes"), base, without);
        assertThat(run("", find("--known", known.toString()))).isZero();
        assertThat(summary().get("knownGone")).isEqualTo(List.of(Map.of("ruleKey", "process_start", "subject", RUNNER,
                "reason", "the file is gone")));
        assertThat(report()).isZero();
        assertThat(details()).as("its file is removed, and that is said").startsWith("gate_status: YELLOW\ngate_category:"
                + " changes\ngate_description: of the changes since aaaaaaaaaaaa: 1 removed Java file; ");
    }

    @Test
    void theDigestOfAnArea_holdsEveryLineOfTheChangedCodeItIsConnectedWith_alsoBeyondWhatThePromptShows()
            throws IOException {
        // Runner.run grows to 70 lines: the prompt of its neighbour shows 60 of them, from its first changed line
        StringBuilder body = new StringBuilder("package p;\nclass Runner {\n    void run(String c) throws Exception {\n"
                + "        new ProcessBuilder(c).start();\n");
        for (int i = 0; i < 66; i++) body.append("        int v").append(i).append(" = ").append(i).append(";\n");
        Files.writeString(root.resolve(SRC + "Runner.java"), body + "    }\n}\n");
        changes(SRC + "Runner.java");
        run("", find());
        Map<String, Object> first = areas().getFirst();
        assertThat((String) first.get("connected")).contains("Runner.run(String) (cut: 60 of its 69 lines; read the"
                + " rest in the workspace):").doesNotContain("int v65 = 65;");

        // The same base again, and a change in the last lines, which the prompt does not show
        Files.writeString(root.resolve(SRC + "Runner.java"), body.toString().replace("int v65 = 65;", "int v65 = 0;")
                + "    }\n}\n");
        Map<String, String> head = Handover.files(root);
        Map<String, String> base = new java.util.TreeMap<>(head);
        base.put(SRC + "Runner.java", body.toString().replace("new ProcessBuilder(c).start();", "old") + "    }\n}\n");
        Handover.write(work.resolve("changes"), base, head);
        run("", find());
        Map<String, Object> second = areas().getFirst();

        assertThat(second.get("item")).isEqualTo(first.get("item")).isEqualTo(CALLER);
        assertThat(second.get("connected")).as("what the prompt shows is the same").isEqualTo(first.get("connected"));
        assertThat(second.get("digest")).as("and the area is not judged as it is now").isNotEqualTo(first.get("digest"));
    }

    @Test
    void changed_writesTheBlobsItWants_andRefusesABaseThatIsTheUnchangedWorkspaceCommit() throws IOException {
        Path dir = changes(SRC + "Runner.java", "run.sh");
        Files.delete(dir.resolve("blobs"));

        assertThat(run("", "changed", "--changes", dir.toString(), "--root", root.toString())).as(err.toString()).isZero();

        List<String> wanted = Files.readAllLines(dir.resolve("wanted"));
        assertThat(wanted).as("the base blob of the changed Java file, by its id, and no path").hasSize(1)
                .allMatch(id -> id.matches("[0-9a-f]{40}"));
        assertThat(out()).contains("FACT changes_files: 2\n", "FACT changes_uncommitted: false\n",
                "FACT changes_base_blobs_wanted: 1\n");

        // The run named a base that is the workspace commit, and nothing differs
        Map<String, String> head = Handover.files(root);
        Handover.write(dir, head, head);
        Files.writeString(dir.resolve("base-commit"), HEAD + "\n");
        Files.writeString(dir.resolve("since"), "2027-01-01\n");
        assertThat(run("", "changed", "--changes", dir.toString(), "--root", root.toString())).isEqualTo(1);
        assertThat(err.toString()).contains("nothing to check: the base '2027-01-01' is the workspace commit itself ("
                + HEAD + ", committed 2026-11-10T09:00:00Z), and the workspace holds no other change");

        // The same from the register: the run goes on from the last one
        Files.writeString(dir.resolve("from"), "register\n");
        assertThat(run("", "changed", "--changes", dir.toString(), "--root", root.toString())).isZero();
        assertThat(Files.readString(dir.resolve("wanted"))).isEmpty();
    }

    /**
     * The import of a caller is pointed at another class of the same name, whose check does nothing. No line of
     * a member changed, and the call in the area now reaches other code.
     */
    @Test
    void anImportThatRePointsACall_marksTheAreaOfTheCaller_soItIsJudged() throws IOException {
        Path sources = root.resolve("app/src/main/java");
        Files.createDirectories(sources.resolve("q"));
        Files.writeString(sources.resolve("p/Checks.java"), "package p;\npublic class Checks {\n"
                + "    public static void verify(String c) {\n        if (c.isEmpty()) throw new IllegalArgumentException();\n"
                + "    }\n}\n");
        Files.writeString(sources.resolve("q/Checks.java"), "package q;\npublic class Checks {\n"
                + "    public static void verify(String c) {\n    }\n}\n");
        String guarded = "package p;\nimport %s.Checks;\nclass Guarded {\n    void start(String c) throws Exception {\n"
                + "        Checks.verify(c);\n        new ProcessBuilder(c).start();\n    }\n}\n";
        Files.writeString(sources.resolve("p/Guarded.java"), guarded.formatted("q"));
        Map<String, String> head = Handover.files(root);
        Map<String, String> base = new java.util.TreeMap<>(head);
        base.put(SRC + "Guarded.java", guarded.formatted("p"));
        Handover.write(work.resolve("changes"), base, head);

        assertThat(run("", find())).as(err.toString()).isZero();

        assertThat(areas()).extracting(a -> a.get("item") + ": " + a.get("reason")).containsExactly(
                SRC + "Guarded.java#Guarded.start(String): changed: the head of its file (outside every member)");
    }

    @Test
    void aWorkspaceThatDiffersFromItsCommit_doesNotMoveTheBase_andTheReportSaysSo() throws IOException {
        // The workspace commit holds a change of Runner that the files on disk do not have
        Map<String, String> disk = Handover.files(root);
        Map<String, String> head = new java.util.TreeMap<>(disk);
        head.put(SRC + "Runner.java", disk.get(SRC + "Runner.java").replace("ProcessBuilder(c)", "ProcessBuilder(c, c)"));
        Handover.write(work.resolve("changes"), disk, head);
        Files.writeString(work.resolve("changes/from"), "register\n");

        assertThat(run("", find())).as(err.toString()).isZero();
        assertThat(report()).isZero();

        assertThat(out()).as("what was judged is the disk, so the next run must start where this one did")
                .contains("\nFACT changes_base: " + BASE + "\nFACT changes_head: " + BASE + "\n")
                .doesNotContain("FACT changes_head: " + HEAD);
        assertThat(Files.readString(work.resolve("report.md"))).contains("with changes that are in no commit",
                "| The next run without `since` starts from | `" + BASE + "`: **the base of this run again**, because"
                        + " the files that were judged are not the workspace commit |");
    }

    // --- what an area means, not only its text ---

    private void source(String path, String content) throws IOException {
        Path file = root.resolve("app/src/main/java").resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** The folder for a workspace that is its commit, whose base has {@code file} with {@code before} as its content. */
    private void baseHas(String file, String before) throws IOException {
        Map<String, String> head = Handover.files(root);
        Map<String, String> base = new java.util.TreeMap<>(head);
        base.put("app/src/main/java/" + file, before);
        Handover.write(work.resolve("changes"), base, head);
    }

    /** The register's word that every area written by the last {@code find} is clean as it was judged. */
    private Path settled() throws IOException {
        StringBuilder items = new StringBuilder();
        for (Map<String, Object> item : items()) {
            items.append("{\"digest\":\"").append(item.get("digest")).append("\",\"name\":\"").append(item.get("name"))
                    .append("\",\"state\":\"clean\",\"verdict\":{\"at\":\"t\",\"run_id\":\"r\"},\"verdict_digest\":\"")
                    .append(item.get("digest")).append("\"}\n");
        }
        return Files.writeString(work.resolve("items.jsonl"), items.toString());
    }

    private static final String GUARDED = "package p;\nimport %s.Checks;\nclass Guarded {\n"
            + "    void start(String c) throws Exception {\n        Checks.verify(c);\n"
            + "        new ProcessBuilder(c).start();\n    }\n}\n";

    @Test
    void anAreaWhoseCallIsRePointedByAnImport_isJudgedAgain_alsoWhenTheRegisterCallsItsTextSettled() throws IOException {
        source("safe/Checks.java", "package safe;\npublic class Checks {\n    public static void verify(String c) {\n"
                + "        if (c.isEmpty()) throw new IllegalArgumentException();\n    }\n}\n");
        source("lax/Checks.java", "package lax;\npublic class Checks {\n    public static void verify(String c) {\n"
                + "    }\n}\n");
        // An earlier run judged the area while it called safe.Checks: the register calls it clean with that digest
        source("p/Guarded.java", GUARDED.formatted("safe"));
        baseHas("p/Guarded.java", GUARDED.formatted("safe").replace("ProcessBuilder(c)", "ProcessBuilder(c, c)"));
        assertThat(run("", find())).as(err.toString()).isZero();
        assertThat(areas()).extracting(a -> a.get("item")).containsExactly(SRC + "Guarded.java#Guarded.start(String)");
        Path items = settled();
        // The same run again leaves the area out: it was judged as it is
        assertThat(run("", find("--items-file", items.toString()))).isZero();
        assertThat(areas()).isEmpty();

        // Now only the import changes: the text of the member is the same, and it calls other code
        source("p/Guarded.java", GUARDED.formatted("lax"));
        baseHas("p/Guarded.java", GUARDED.formatted("safe"));
        assertThat(run("", find("--items-file", items.toString()))).as(err.toString()).isZero();

        assertThat(areas()).extracting(a -> a.get("item") + ": " + a.get("reason")).containsExactly(
                SRC + "Guarded.java#Guarded.start(String): changed: the head of its file (outside every member)");
    }

    private static final String USER = "package p;\nclass User {\n    void start(String c) throws Exception {\n"
            + "        new Guard().verify(c);\n        new ProcessBuilder(c).start();\n    }\n}\n";

    @Test
    void aChangedSupertypeInAFileWithoutAreas_makesTheAreaThatCallsThroughItANeighbour_onAFirstRunAndASettledOne()
            throws IOException {
        source("p/Safe.java", "package p;\nclass Safe {\n    void verify(String c) {\n"
                + "        if (c.isEmpty()) throw new IllegalArgumentException();\n    }\n}\n");
        source("p/Lax.java", "package p;\nclass Lax {\n    void verify(String c) {\n    }\n}\n");
        source("p/User.java", USER);
        // An earlier run judged User while Guard was a Safe
        source("p/Guard.java", "package p;\nclass Guard extends Safe {\n}\n");
        baseHas("p/User.java", USER.replace("ProcessBuilder(c)", "ProcessBuilder(c, c)"));
        run("", find());
        Path items = settled();

        source("p/Guard.java", "package p;\nclass Guard extends Lax {\n}\n");
        baseHas("p/Guard.java", "package p;\nclass Guard extends Safe {\n}\n");

        // A first run, with a register that knows nothing
        assertThat(run("", find())).as(err.toString()).isZero();
        assertThat(areas()).extracting(a -> a.get("item") + ": " + a.get("reason")).containsExactly(
                SRC + "User.java#User.start(String): neighbour of Guard (the head of its class); neighbour of Lax.verify(String)");
        // And with the register calling the area settled by its earlier text and targets
        assertThat(run("", find("--items-file", items.toString()))).isZero();
        assertThat(areas()).extracting(a -> a.get("item")).containsExactly(SRC + "User.java#User.start(String)");
    }

    @Test
    void aChangedRecordHeader_makesTheAreaThatCreatesTheRecordANeighbour() throws IOException {
        source("p/Command.java", "package p;\nrecord Command(String name, boolean shell) {\n}\n");
        source("p/Starter.java", "package p;\nclass Starter {\n    void start(String c) throws Exception {\n"
                + "        Command command = new Command(c, false);\n"
                + "        new ProcessBuilder(command.name()).start();\n    }\n}\n");
        baseHas("p/Command.java", "package p;\nrecord Command(String name) {\n}\n");

        assertThat(run("", find())).as(err.toString()).isZero();

        assertThat(areas()).extracting(a -> a.get("item") + ": " + a.get("reason")).containsExactly(
                SRC + "Starter.java#Starter.start(String): neighbour of Command (the head of its class)");
    }

    // --- the base moves only past what was judged ---

    private String headLine() {
        return out().lines().filter(line -> line.startsWith("FACT changes_head: ")).findFirst().orElseThrow();
    }

    @Test
    void theBaseStays_whenAFileWasNotRead_anAreaGotNoVerdict_orAFileWasNotCompared_andTheReportSaysWhy()
            throws IOException {
        // A file of a source root that does not parse
        source("p/Broken.java", "package p;\nclass Broken {\n    void f() { new ProcessBuilder(\"sh\") }\n}\n");
        changes(SRC + "Runner.java");
        run("", find());
        List<String> lines = Files.readAllLines(work.resolve("areas.jsonl"));
        String all = "[" + lines.get(0) + "]: {\"area\":\"A001\",\"findings\":[]}\n"
                + "[" + lines.get(1) + "]: {\"area\":\"A003\",\"findings\":[]}\n";
        assertThat(run(all, "report", "--areas", work.resolve("areas.jsonl").toString(), "--summary",
                work.resolve("areas-summary.json").toString(), "--verdicts", "-", "--out",
                work.resolve("report.md").toString())).isZero();
        assertThat(headLine()).isEqualTo("FACT changes_head: " + BASE);
        assertThat(Files.readString(work.resolve("report.md"))).contains("| The next run without `since` starts from | `"
                + BASE + "`: **the base of this run again**, because 1 file of a source root was not read |");
        Files.delete(root.resolve("app/src/main/java/p/Broken.java"));

        // An area without a verdict
        changes(SRC + "Runner.java");
        run("", find());
        lines = Files.readAllLines(work.resolve("areas.jsonl"));
        assertThat(run("[" + lines.get(0) + "]: {\"area\":\"A001\",\"findings\":[]}\n", "report", "--areas",
                work.resolve("areas.jsonl").toString(), "--summary", work.resolve("areas-summary.json").toString(),
                "--verdicts", "-", "--out", work.resolve("report.md").toString())).isZero();
        assertThat(headLine()).isEqualTo("FACT changes_head: " + BASE);
        assertThat(Files.readString(work.resolve("report.md"))).contains("**the base of this run again**, because 1"
                + " area of this run got no verdict");

        // The same with every verdict: the base moves
        all = "[" + lines.get(0) + "]: {\"area\":\"A001\",\"findings\":[]}\n"
                + "[" + lines.get(1) + "]: {\"area\":\"A003\",\"findings\":[]}\n";
        assertThat(run(all, "report", "--areas", work.resolve("areas.jsonl").toString(), "--summary",
                work.resolve("areas-summary.json").toString(), "--verdicts", "-", "--out",
                work.resolve("report.md").toString())).isZero();
        assertThat(headLine()).isEqualTo("FACT changes_head: " + HEAD);
        assertThat(Files.readString(work.resolve("report.md"))).contains("| The next run without `since` starts from | `"
                + HEAD + "`: the workspace commit |");
    }

    @Test
    void aFileThisCheckCanNeverJudge_doesNotHoldTheBase_itIsPassedUnjudged_andTheReportSaysSo() throws IOException {
        changes(SRC + "Runner.java", "run.sh");
        run("", find());
        List<String> lines = Files.readAllLines(work.resolve("areas.jsonl"));
        String all = "[" + lines.get(0) + "]: {\"area\":\"A001\",\"findings\":[]}\n"
                + "[" + lines.get(1) + "]: {\"area\":\"A003\",\"findings\":[]}\n";

        assertThat(run(all, "report", "--areas", work.resolve("areas.jsonl").toString(), "--summary",
                work.resolve("areas-summary.json").toString(), "--verdicts", "-", "--out",
                work.resolve("report.md").toString())).isZero();

        assertThat(details()).startsWith("gate_status: YELLOW\n");
        assertThat(headLine()).isEqualTo("FACT changes_head: " + HEAD);
        assertThat(Files.readString(work.resolve("report.md"))).contains("| The next run without `since` starts from | `"
                + HEAD + "`: the workspace commit. **Passed unjudged**: 1 changed file this check cannot judge. No"
                + " later run of this check comes back to it |");
    }

    // --- what the gate says in words ---

    private String verdicts(String... answers) throws IOException {
        List<String> lines = Files.readAllLines(work.resolve("areas.jsonl"));
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < lines.size() && i < answers.length; i++) {
            String id = String.valueOf(((Map<?, ?>) Json.parse(lines.get(i))).get("id"));
            all.append("[").append(lines.get(i)).append("]: ").append(answers[i].replace("ID", id)).append("\n");
        }
        return all.toString();
    }

    private int report(String verdicts) {
        return run(verdicts, "report", "--areas", work.resolve("areas.jsonl").toString(), "--summary",
                work.resolve("areas-summary.json").toString(), "--verdicts", "-", "--out",
                work.resolve("report.md").toString());
    }

    private static final String CLEAN = "{\"area\":\"ID\",\"findings\":[]}";

    @Test
    void theGateSaysInOneLineWhatItsColourCovers_forEveryColour_andItsCategoryIsChanges() throws IOException {
        Path dir = changes(SRC + "Runner.java");
        Files.writeString(dir.resolve("commits"), "3\n");

        run("", find());
        assertThat(report(verdicts(CLEAN, CLEAN))).isZero();
        assertThat(out()).startsWith("gate_status: YELLOW\ngate_category: changes\ngate_description: "
                + "nothing found in what changed (this check does not say GREEN): 2 areas"
                + " picked since aaaaaaaaaaaa (3 commits), all judged, nothing found\nDETAILS: of the changes since ");
        assertThat(Files.readString(work.resolve("report.md"))).startsWith("# Security check of the changes\n\n"
                + "**Gate:** YELLOW: nothing found in what changed (this check does not say GREEN): 2 areas picked since aaaaaaaaaaaa (3 commits), all judged, nothing found\n\n"
                + "**Details:** of the changes since aaaaaaaaaaaa: no verified finding; ");

        assertThat(report(verdicts(CLEAN))).isZero();
        assertThat(out()).startsWith("gate_status: YELLOW\n");
        assertThat(scope()).isEqualTo("2 areas picked since aaaaaaaaaaaa (3 commits), 1 not judged, nothing found;"
                + " the base does not move");

        assertThat(report(verdicts(CLEAN, "{\"area\":\"ID\",\"findings\":[{\"rule\":\"process_start\",\"severity\":"
                + "\"high\",\"line\":4,\"quote\":\"new ProcessBuilder(c).start();\",\"issue\":\"i\",\"fix\":\"f\"}]}")))
                .isZero();
        assertThat(out()).startsWith("gate_status: RED\n");
        assertThat(scope()).isEqualTo("2 areas picked since aaaaaaaaaaaa (3 commits), all judged, 1 finding (1 high)");

        // Files the check cannot judge are passed, and that keeps the gate from GREEN
        Files.writeString(changes(SRC + "Runner.java", "run.sh", "Dockerfile").resolve("commits"), "1\n");
        run("", find());
        assertThat(report(verdicts(CLEAN, CLEAN))).isZero();
        assertThat(out()).startsWith("gate_status: YELLOW\n");
        assertThat(scope()).isEqualTo("2 areas picked since aaaaaaaaaaaa (1 commit), all judged, nothing found;"
                + " 2 files passed unjudged");
        Files.delete(root.resolve("run.sh"));
        Files.delete(root.resolve("Dockerfile"));

        // Nothing changed
        Files.delete(changes().resolve("commits"));
        run("", find());
        assertThat(report("")).isZero();
        assertThat(scope()).isEqualTo("nothing found in what changed (this check does not say GREEN): nothing changed since aaaaaaaaaaaa, no area"
                + " picked");
    }

    @Test
    void aRunThatFindsNothingIsYellow_andSaysThatThisCheckDoesNotSayGreen() throws IOException {
        changes(SRC + "Runner.java");

        assertThat(run("", find())).as(err.toString()).isZero();
        assertThat(report(verdicts(CLEAN, CLEAN))).isZero();
        assertThat(out()).startsWith("gate_status: YELLOW\ngate_category: changes\ngate_description: nothing found in"
                + " what changed (this check does not say GREEN): 2 areas picked since aaaaaaaaaaaa, all judged,"
                + " nothing found\n");

        // What is not GREEN anyway keeps its words
        assertThat(report(verdicts(CLEAN))).isZero();
        assertThat(scope()).isEqualTo("2 areas picked since aaaaaaaaaaaa, 1 not judged, nothing found; the base does"
                + " not move");
    }

    @Test
    void theOptionGreenIsGone_andIsRefusedLikeAnyUnknownOption() throws IOException {
        changes(SRC + "Runner.java");

        assertThat(run("", find("--green", "no"))).isEqualTo(2);
        assertThat(err.toString()).contains("unknown option '--green'");
        assertThat(run("", find("--green", "yes"))).isEqualTo(2);
    }

    @Test
    void theReportNamesTheLastFullAudit_asItWasGiven_orSaysThatNoneWas() throws IOException {
        Path dir = changes(SRC + "Runner.java");
        run("", find());
        report(verdicts(CLEAN, CLEAN));
        assertThat(Files.readString(work.resolve("report.md"))).contains("| Last full audit (`examples/self-audit/"
                + "security`) | not given (the input `full_audit`) |");

        Files.writeString(dir.resolve("full-audit"), "d".repeat(40) + "\n");
        Files.writeString(dir.resolve("full-audit-behind"), "17\n");
        run("", find());
        report(verdicts(CLEAN, CLEAN));
        assertThat(Files.readString(work.resolve("report.md"))).contains("| Last full audit (`examples/self-audit/"
                + "security`) | `" + "d".repeat(40) + "`, as the input `full_audit` names it: 17 commits before the"
                + " workspace commit |");
    }

    @Test
    void aBuiltCheckout_canBeGreen_andItsBaseMoves_whateverTheBuildLeftOutsideTheSourceRoots() throws IOException {
        Path built = Files.createDirectories(root.resolve("app/target/classes/traceworks-images/egress/src"));
        Files.writeString(built.resolve("Egress.java"), "class Egress { }\n");
        Path copy = Files.createDirectories(root.resolve(".claude/worktrees/agent/app/src/main/java/p"));
        Files.writeString(copy.resolve("Runner.java"), "package p;\nclass Runner { }\n");
        // Only the tracked files are in the trees
        Map<String, String> head = new java.util.TreeMap<>(Handover.files(root));
        head.keySet().removeIf(path -> path.startsWith("app/target/") || path.startsWith(".claude/"));
        Map<String, String> base = new java.util.TreeMap<>(head);
        base.put(SRC + "Runner.java", head.get(SRC + "Runner.java").replace("ProcessBuilder(c)", "ProcessBuilder(c, c)"));
        Handover.write(work.resolve("changes"), base, head);

        assertThat(run("", find())).as(err.toString()).isZero();
        assertThat(out()).contains("FACT changes_files: 1\n", "FACT changes_uncommitted: false\n",
                "FACT changes_files_java_not_compiled: 0\n");
        assertThat(report(verdicts(CLEAN, CLEAN))).isZero();

        assertThat(out()).startsWith("gate_status: YELLOW\n");
        assertThat(headLine()).isEqualTo("FACT changes_head: " + HEAD);
    }

    @Test
    void aChangedConstant_picksTheAreasOfItsFile_alsoWhenTheRegisterCallsTheirTextSettled() throws IOException {
        String limits = "package p;\nclass Limits {\n    static final int MIN = %s;\n"
                + "    void run(String c) throws Exception {\n        if (c.length() >= MIN) new ProcessBuilder(c).start();\n"
                + "    }\n}\n";
        source("p/Limits.java", limits.formatted("8"));
        baseHas("p/Limits.java", limits.formatted("8").replace("ProcessBuilder(c)", "ProcessBuilder(c, c)"));
        run("", find());
        Path items = settled();

        source("p/Limits.java", limits.formatted("9"));
        baseHas("p/Limits.java", limits.formatted("8"));

        assertThat(run("", find("--items-file", items.toString()))).as(err.toString()).isZero();
        assertThat(areas()).extracting(a -> a.get("item") + ": " + a.get("reason")).containsExactly(
                SRC + "Limits.java#Limits.run(String): changed: a field of its file");
    }

    @Test
    void lowFindingsInTheReportOnly_areNotCalledNothingFound() throws IOException {
        changes(SRC + "Runner.java");
        run("", find());

        assertThat(report(verdicts(CLEAN, "{\"area\":\"ID\",\"findings\":[{\"rule\":\"process_start\",\"severity\":"
                + "\"low\",\"line\":4,\"quote\":\"new ProcessBuilder(c).start();\",\"issue\":\"i\",\"fix\":\"f\"}]}")))
                .isZero();

        assertThat(out()).startsWith("gate_status: YELLOW\n");
        assertThat(scope()).isEqualTo("nothing found in what changed (this check does not say GREEN): 2 areas picked since aaaaaaaaaaaa, all"
                + " judged, nothing for the register (1 low finding in the report only)");
    }
}
