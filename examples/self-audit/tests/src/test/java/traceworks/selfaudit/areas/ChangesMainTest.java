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
    private static final String BASE = "a".repeat(40);
    private static final String HEAD = "b".repeat(40);

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
     * The folder the step before writes: each named file differs from the base, a Java file of the project in its
     * line 4.
     */
    private Path changes(String... changedFiles) throws IOException {
        Path dir = Files.createDirectories(work.resolve("changes/base")).getParent();
        Files.writeString(dir.resolve("base-commit"), BASE + "\n");
        Files.writeString(dir.resolve("head"), HEAD + "\n");
        Files.writeString(dir.resolve("from"), "input\n");
        Files.writeString(dir.resolve("since"), "v1\n");
        Files.writeString(dir.resolve("previous-head"), "\n");
        Files.writeString(dir.resolve("dirty"), "false\n");
        Files.writeString(dir.resolve("base-date"), "2026-11-10T09:00:00Z\n");
        Files.writeString(dir.resolve("skipped"), "\n");
        StringBuilder manifest = new StringBuilder();
        int n = 0;
        for (String file : changedFiles) {
            if (!file.endsWith(".java") || !Files.exists(root.resolve(file))) {
                manifest.append(file.startsWith("-") ? "D\0" + file.substring(1) : file.startsWith("?")
                        ? "U\0" + file.substring(1) : "M\0" + file).append("\0\0");
                continue;
            }
            n++;
            List<String> lines = new ArrayList<>(Files.readAllLines(root.resolve(file)));
            lines.set(3, "old");
            Files.writeString(dir.resolve("base/" + n), String.join("\n", lines) + "\n");
            manifest.append("M\0").append(file).append("\0").append(n).append("\0");
        }
        Files.writeString(dir.resolve("manifest"), manifest.toString());
        return dir;
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
        changes(SRC + "Runner.java", "pom.xml");

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
                Map.entry("dirty", false), Map.entry("changedFiles", 2L), Map.entry("unmappedFiles", 1L),
                Map.entry("baseDate", "2026-11-10T09:00:00Z"), Map.entry("skipped", ""),
                Map.entry("removedFiles", 0L), Map.entry("unmappable", List.of()),
                Map.entry("outsideMembers", 0L), Map.entry("outside", List.of()),
                Map.entry("unmapped", List.of("pom.xml")), Map.entry("changedMembers", 1L),
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
        changes(SRC + "Runner.java", "pom.xml");
        run("", find());
        List<String> lines = Files.readAllLines(work.resolve("areas.jsonl"));
        String verdicts = "[" + lines.get(0) + "]: {\"area\":\"A001\",\"findings\":[]}\n"
                + "[" + lines.get(1) + "]: {\"area\":\"A003\",\"findings\":[]}\n";

        assertThat(run(verdicts, "report", "--areas", work.resolve("areas.jsonl").toString(), "--summary",
                work.resolve("areas-summary.json").toString(), "--verdicts", "-", "--out",
                work.resolve("report.md").toString())).as(err.toString()).isZero();

        String described = "of the changes since aaaaaaaaaaaa: no verified finding; 2 of 2 areas judged; 2 of 2 items"
                + " settled (2 clean, 0 decided; 0 open, 0 not judged); 1 of 3 areas of the project not looked at";
        assertThat(out()).startsWith("gate_status: GREEN\ngate_category: security\ngate_description: " + described
                + "\nFACT changes_base: " + BASE + "\nFACT changes_head: " + HEAD + "\nFACT changes_areas_changed: 1\n"
                + "FACT changes_areas_neighbours: 1\nFACT changes_areas_carried: 0\n"
                + "FACT changes_areas_not_looked_at: 1\n");
        assertThat(out()).contains("\"expectedItems\":2,\"items\":[{\"name\":\"" + CALLER + "\"");
        assertThat(Files.readString(work.resolve("report.md"))).startsWith("# Security check of the changes\n\n"
                        + "**Gate:** GREEN: " + described + "\n\n**This gate speaks about the changes only.** It says"
                        + " nothing about the 1 of 3 areas of the project that this run did not look at; the long"
                        + " audit judges every area.\n\n## What was looked at\n")
                .contains("| Base: the commit the changes are measured from | `" + BASE
                                + "`, committed 2026-11-10T09:00:00Z |\n",
                        "| Where the base is from | the input `since`: `v1` |\n",
                        "| Workspace commit | `" + HEAD + "`, with nothing uncommitted |\n",
                        "| Files that differ from the base | 2 (0 removed; 1 changed or new that are no Java"
                                + " source the tool read, not mapped to an area) |\n",
                        "| Changed files that could not be compared | 0 |\n",
                        "| Areas of the project | 3 |\n", "| Areas with a changed line | 1 |\n",
                        "| Neighbours: areas one call from a changed member | 1 |\n",
                        "| Areas an earlier run left open or without a verdict | 0 |\n",
                        "| Areas of the project not looked at | 1 (0 of them changed or a neighbour, and judged by an"
                                + " earlier run as they are now) |\n",
                        "Changed files that are no Java source the tool read: `pom.xml`\n",
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

        assertThat(out()).startsWith("gate_status: GREEN\ngate_category: security\ngate_description: nothing"
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

        assertThat(out()).startsWith("gate_status: YELLOW\ngate_category: security\ngate_description: of the changes"
                + " since aaaaaaaaaaaa: 1 changed file could not be compared; no verified finding; ");
        assertThat(Files.readString(work.resolve("report.md")))
                .contains("| Files that differ from the base | 2 (1 removed; 0 changed or new",
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

        assertThat(out()).startsWith("gate_status: YELLOW\ngate_category: security\ngate_description: of the changes"
                + " since aaaaaaaaaaaa: 4 commits after the last run skipped and not judged; no verified finding; 2 of"
                + " 2 areas judged; 2 of 2 items settled");
        assertThat(Files.readString(work.resolve("report.md"))).contains("| **Commits skipped** | **4**: the commits"
                + " after `" + previous + "` up to `" + BASE + "` (`git log cccccccccccc..aaaaaaaaaaaa`) are judged by"
                + " no run of this check. The run was started with `skip=yes`.");

        Files.writeString(dir.resolve("skipped"), "unknown\n");
        run("", find());
        assertThat(report()).isZero();
        assertThat(out()).startsWith("gate_status: YELLOW\n").contains("the commit of the last run is not in this"
                + " checkout, so commits may have been skipped");
    }

    @Test
    void aChangedImport_isCountedAndNamed_andNoAreaComesFromIt() throws IOException {
        Path dir = changes(SRC + "Runner.java");
        // The base differs in line 1, not in line 4
        List<String> now = Files.readAllLines(root.resolve(SRC + "Runner.java"));
        now.set(0, "package q;");
        Files.writeString(dir.resolve("base/1"), String.join("\n", now) + "\n");

        assertThat(run("", find())).as(err.toString()).isZero();
        assertThat(areas()).isEmpty();
        assertThat(report()).isZero();

        assertThat(Files.readString(work.resolve("report.md")))
                .contains("| Changed places in no member (an import, the head of a class): no area comes from them | 1 |",
                        "Changed places that lie in no member: `" + SRC + "Runner.java:1-1`");
    }
}
