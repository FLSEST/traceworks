package traceworks.cli.main;

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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code traceworks findings} end to end: audit runs on the host feed a register in a temporary home. */
class FindingsCommandTest {

    @TempDir
    Path temp;
    private Path home;
    private Path work;

    private record Result(int code, String out, String err) {
    }

    @BeforeEach
    void directories() throws IOException {
        home = Files.createDirectories(temp.resolve("home"));
        work = Files.createDirectories(temp.resolve("work"));
    }

    private Result exec(String... args) {
        return execWith(Map.of("TRACEWORKS_ACTOR", "tester"), args);
    }

    /** As {@link #exec}, in the environment of a harness that names its agent. */
    private Result execAs(String agentVariable, String... args) {
        return execWith(Map.of("TRACEWORKS_ACTOR", "tester", "TRACEWORKS_AGENT", agentVariable), args);
    }

    private Result execWith(Map<String, String> env, String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--home");
        all.add(home.toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(all.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), env, work, null, RuntimeSource.NONE);
        return new Result(code, traceworks.cli.Unbroken.of(out.toString(StandardCharsets.UTF_8)),
                traceworks.cli.Unbroken.of(err.toString(StandardCharsets.UTF_8)));
    }

    /** An audit package whose report step prints an envelope with one finding per subject. */
    private Path auditPackage(String... subjects) throws IOException {
        StringBuilder findings = new StringBuilder();
        for (String subject : subjects) {
            if (!findings.isEmpty()) findings.append(",");
            findings.append("{\"ruleKey\":\"size\",\"subject\":\"").append(subject)
                    .append("\",\"severity\":\"MEDIUM\",\"title\":\"Big ").append(subject).append("\"}");
        }
        String command = "printf '%s\\n' '# Report' '<!-- traceworks:audit-result:v1' "
                + "'{\"snapshotComplete\":true,\"findings\":[" + findings + "]}' '-->'";
        Path dir = Files.createDirectories(temp.resolve("audit"));
        Files.writeString(dir.resolve("workflow.json"), "{\"id\":\"audit\",\"name\":\"Audit\",\"metadata\":{\"audit\":"
                + "{\"enabled\":true,\"key\":\"hello\",\"category\":\"example\",\"report_block\":\"report\"}},"
                + "\"blocks\":[{\"type\":\"shell\",\"name\":\"report\",\"config\":{\"command\":"
                + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(command) + "}}]}");
        return dir;
    }

    private Result run(Path pkg) {
        return exec("run", pkg.toString(), "--allow-host-execution", "--no-input", "--quiet");
    }

    @Test
    void theSummary_namesTheRegisterDiff_andListShowsTheOpenFindings() throws IOException {
        Result first = run(auditPackage("A.java", "B.java"));
        Result second = run(auditPackage("B.java", "C.java"));

        assertThat(first.code()).as(first.err()).isZero();
        assertThat(first.out()).contains("  register hello: 2 new, 0 resolved, 0 reopened, 2 open now");
        assertThat(second.out()).contains("  register hello: 1 new, 1 resolved, 0 reopened, 2 open now");
        Result list = exec("findings", "hello", "list");
        assertThat(list.code()).as(list.err()).isZero();
        assertThat(list.out().lines()).hasSize(2).allSatisfy(line -> assertThat(line).matches(
                "[0-9a-f]{8}  OPEN       MEDIUM    size  [BC]\\.java  Big [BC]\\.java"));
        Result all = exec("findings", "hello", "list", "--all");
        assertThat(all.out().lines()).hasSize(3).anySatisfy(line -> assertThat(line).contains("RESOLVED").contains("A.java"));
        Result resolved = exec("findings", "hello", "list", "--status", "resolved");
        assertThat(resolved.out().lines()).hasSize(1);
        Result byDefault = exec("findings", "hello");
        assertThat(byDefault.code()).as("a key alone lists: " + byDefault.err()).isZero();
        assertThat(byDefault.out()).isEqualTo(list.out());
    }

    @Test
    void show_printsEveryField_byAPrefixOfTheId() throws IOException {
        run(auditPackage("A.java"));
        String id = exec("findings", "hello", "list").out().substring(0, 8);

        Result show = exec("findings", "hello", "show", id);

        assertThat(show.code()).as(show.err()).isZero();
        assertThat(show.out()).contains("Finding   " + id, "Register  hello", "Status    OPEN", "Severity  MEDIUM",
                "Rule      size", "Subject   A.java", "Title     Big A.java", "First     run ", "Last      run ",
                "Occurrences 1", "Regressions 0");
        assertThat(show.out()).as("a finding without them has no such lines")
                .doesNotContain("About", "Evidence", "Tags", "Resolved", "Decision");
        assertThat(exec("findings", "hello", "show", "0000000").code()).isEqualTo(2);
    }

    /** A finding's description, tags and the time it was resolved each have a line of their own. */
    @Test
    void show_printsTheDescription_theTags_andWhenTheFindingWasResolved() throws IOException {
        run(auditPackageWith("hello", "{\"snapshotComplete\":true,\"findings\":[{\"ruleKey\":\"size\",\"subject\":"
                + "\"A.java\",\"severity\":\"MEDIUM\",\"title\":\"Big A.java\",\"description\":\"over the limit\","
                + "\"tags\":[\"size\",\"legacy\"]}]}"));
        run(auditPackageWith("hello", "{\"snapshotComplete\":true,\"findings\":[]}"));
        String id = exec("findings", "hello", "list", "--all").out().substring(0, 8);

        Result show = exec("findings", "hello", "show", id);

        assertThat(show.code()).as(show.err()).isZero();
        assertThat(show.out()).contains("Status    RESOLVED", "About     over the limit", "Tags      size, legacy")
                .containsPattern("(?m)^Resolved  \\d{4}-\\d\\d-\\d\\dT[0-9:.]+Z$");
    }

    /**
     * The evidence of a finding is checked against the facts the other steps of the run measured: one that names
     * none of them is marked as unverified, so nobody reads it as checked, and one that names a fact is not.
     */
    @Test
    void show_marksEvidenceThatNamesNoMeasuredFact_asUnverified() throws IOException {
        String facts = "printf '%s\\n' '## FACT SHEET sizes' 'FACT lines_total: 900'";
        String report = "printf '%s\\n' '# Report' '<!-- traceworks:audit-result:v1' "
                + "'{\"snapshotComplete\":true,\"findings\":["
                + "{\"ruleKey\":\"size\",\"subject\":\"A.java\",\"severity\":\"MEDIUM\",\"title\":\"Big A.java\","
                + "\"evidence\":\"lines_total: 900\"},"
                + "{\"ruleKey\":\"size\",\"subject\":\"B.java\",\"severity\":\"MEDIUM\",\"title\":\"Big B.java\","
                + "\"evidence\":\"about a thousand lines\"}]}' '-->'";
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        Path dir = Files.createDirectories(temp.resolve("audit-facts"));
        Files.writeString(dir.resolve("workflow.json"), "{\"id\":\"audit-facts\",\"name\":\"Audit\",\"metadata\":{"
                + "\"audit\":{\"enabled\":true,\"key\":\"hello\",\"category\":\"example\",\"report_block\":\"report\"}},"
                + "\"blocks\":[{\"type\":\"shell\",\"name\":\"facts\",\"config\":{\"command\":"
                + json.writeValueAsString(facts) + "}},{\"type\":\"shell\",\"name\":\"report\",\"config\":{\"command\":"
                + json.writeValueAsString(report) + "}}]}");
        Result ran = run(dir);
        assertThat(ran.code()).as(ran.out() + ran.err()).isZero();
        List<String> lines = exec("findings", "hello", "list").out().lines().toList();
        String measured = lines.stream().filter(l -> l.contains("A.java")).findFirst().orElseThrow().substring(0, 8);
        String invented = lines.stream().filter(l -> l.contains("B.java")).findFirst().orElseThrow().substring(0, 8);

        assertThat(exec("findings", "hello", "show", measured).out())
                .containsPattern("(?m)^Evidence  lines_total: 900$");
        assertThat(exec("findings", "hello", "show", invented).out())
                .containsPattern("(?m)^Evidence  about a thousand lines \\(unverified\\)$");
    }

    private static final String ISSUE = "a path from the workflow reaches the mount unchecked";

    /**
     * An audit package as a self-audit is shaped: its report step leaves {@code findings.jsonl} in the scratch
     * directory, with what a model said about the one finding of its envelope, and prints what the register hands it.
     */
    private Path auditPackageWithDetail() throws IOException {
        Path dir = Files.createDirectories(temp.resolve("audit-detail"));
        Files.writeString(dir.resolve("findings.jsonl"), "{\"ruleKey\":\"size\",\"subject\":\"B.java\",\"severity\":"
                + "\"LOW\",\"item\":\"A002\",\"source\":\"model\",\"details\":[{\"file\":\"B.java\",\"line\":3,\"quote\":"
                + "\"other()\",\"model\":{\"issue\":\"not this one\"}}]}\n"
                + "not json at all\n"
                + "{\"ruleKey\":\"size\",\"subject\":\"A.java\",\"severity\":\"MEDIUM\",\"item\":\"A001\",\"source\":"
                + "\"model\",\"details\":[{\"file\":\"src/A.java\",\"line\":12,\"quote\":\"mount(path);\",\"severity\":\"medium\","
                + "\"model\":{\"issue\":\"" + ISSUE + " \\u001b[2J\\nsecond line\",\"fix\":\"check the real path first\"}}]}\n");
        String command = "cp {{ package_dir }}/findings.jsonl {{ scratch_dir }}/findings.jsonl; "
                + "printf 'KNOWN %s\\n' \"$KNOWN\"; "
                + "printf '%s\\n' '# Report' '<!-- traceworks:audit-result:v1' "
                + "'{\"snapshotComplete\":true,\"findings\":[{\"ruleKey\":\"size\",\"subject\":\"A.java\",\"severity\":"
                + "\"MEDIUM\",\"title\":\"Big A.java\",\"evidence\":\"src/A.java:12: mount(path);\"}]}' '-->'";
        Files.writeString(dir.resolve("workflow.json"), "{\"id\":\"audit\",\"name\":\"Audit\",\"metadata\":{\"audit\":"
                + "{\"enabled\":true,\"key\":\"hello\",\"category\":\"example\",\"report_block\":\"report\"}},"
                + "\"blocks\":[{\"type\":\"shell\",\"name\":\"report\",\"config\":{\"shell_escape_variables\":true,"
                + "\"values\":{\"KNOWN\":\"{{ audit_findings }}\"},"
                + "\"command\":" + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(command) + "}}]}");
        return dir;
    }

    /** What is wrong and how to fix it are in the record of the run that last reported the finding: show reads them there. */
    @Test
    void show_addsTheDetail_fromTheRunThatLastReportedTheFinding() throws IOException {
        Result run = run(auditPackageWithDetail());
        assertThat(run.code()).as(run.out() + run.err()).isZero();
        String id = exec("findings", "hello", "list").out().substring(0, 8);

        Result show = exec("findings", "hello", "show", id);

        assertThat(show.code()).as(show.err()).isZero();
        assertThat(show.out()).contains("Title     Big A.java", "Occurrences 1")
                .containsPattern("Detail    from findings.jsonl of run [0-9a-f-]{36}")
                .contains("  item      A001", "  file      src/A.java:12", "  quote     mount(path);",
                        "  severity  medium",
                        "  the model's words, not checked:", "    issue   " + ISSUE + " ?[2J second line",
                        "    fix     check the real path first")
                .doesNotContain("\u001b").doesNotContain("not this one").doesNotContain("other()");
    }

    /** The places of a finding are a step's: however many it wrote, show prints a bounded number of them. */
    @Test
    void show_printsABoundedNumberOfPlaces_howeverManyTheRunWrote() throws IOException {
        Path pkg = auditPackageWithDetail();
        StringBuilder places = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            places.append(i == 0 ? "" : ",").append("{\"file\":\"P").append(i).append(".java\"}");
        }
        Files.writeString(pkg.resolve("findings.jsonl"), "{\"ruleKey\":\"size\",\"subject\":\"A.java\",\"severity\":"
                + "\"MEDIUM\",\"source\":\"model\",\"details\":[" + places + "]}\n");
        Result run = run(pkg);
        assertThat(run.code()).as(run.out() + run.err()).isZero();
        String id = exec("findings", "hello", "list").out().substring(0, 8);

        Result show = exec("findings", "hello", "show", id);

        assertThat(show.code()).as(show.err()).isZero();
        assertThat(show.out()).contains("  file      P0.java").doesNotContain("P299.java");
        assertThat(show.out().lines().filter(line -> line.startsWith("  file      ")).count())
                .isEqualTo(traceworks.core.app.FindingDetails.MAX_ENTRIES);
    }

    /** A run that is gone, or one that left no findings.jsonl, costs the detail and nothing else. */
    @Test
    void show_saysInOneLineWhyThereIsNoDetail_andShowsTheRest() throws IOException {
        run(auditPackage("A.java"));
        String id = exec("findings", "hello", "list").out().substring(0, 8);

        Result noFile = exec("findings", "hello", "show", id);

        assertThat(noFile.code()).as(noFile.err()).isZero();
        assertThat(noFile.out()).contains("Occurrences 1").containsPattern(
                "Detail    none: run [0-9a-f-]{36} recorded no findings.jsonl\\n");

        try (var runs = Files.list(home.resolve("runs"))) {
            for (Path folder : runs.toList()) {
                try (var files = Files.walk(folder)) {
                    files.sorted(java.util.Comparator.reverseOrder()).forEach(file -> file.toFile().delete());
                }
            }
        }
        Result gone = exec("findings", "hello", "show", id);

        assertThat(gone.code()).as(gone.err()).isZero();
        assertThat(gone.out()).contains("Occurrences 1").containsPattern(
                "Detail    none: run [0-9a-f-]{36} is not in this home\\n");
    }

    /** {@code list --json} is the listed findings as state.json has them, for a script. */
    @Test
    void listJson_printsTheListedFindings_asAnArrayWithTheFieldsOfTheState() throws IOException {
        run(auditPackage("A.java", "B.java"));
        run(auditPackage("B.java"));

        Result open = exec("findings", "hello", "list", "--json");
        Result all = exec("findings", "hello", "list", "--all", "--json");

        assertThat(open.code()).as(open.err()).isZero();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var array = mapper.readTree(open.out());
        assertThat(array.isArray()).isTrue();
        assertThat(array).hasSize(1);
        var state = mapper.readTree(Files.readString(home.resolve("registers").resolve("hello").resolve("state.json")));
        var inState = java.util.stream.StreamSupport.stream(state.get("findings").spliterator(), false)
                .filter(f -> f.get("subject").asText().equals("B.java")).findFirst().orElseThrow();
        assertThat(array.get(0)).isEqualTo(inState);
        assertThat(array.get(0).get("status").asText()).isEqualTo("OPEN");
        assertThat(mapper.readTree(all.out())).hasSize(2);
        assertThat(open.out()).endsWith("]\n");
    }

    /**
     * The detail is a model's text. It stays in the record of its run: the next run's prompt
     * ({@code {{ audit_findings }}}) never gets it, and no file of the register holds it.
     */
    @Test
    void theDetail_neverEntersAuditFindings_norAFileOfTheRegister() throws IOException {
        Path pkg = auditPackageWithDetail();
        run(pkg);
        Result second = exec("run", pkg.toString(), "--allow-host-execution", "--no-input");
        assertThat(second.code()).as(second.err()).isZero();
        String id = exec("findings", "hello", "list").out().substring(0, 8);
        exec("findings", "hello", "show", id);
        exec("findings", "hello", "render");

        String secondRun = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(exec("findings", "hello", "list", "--json").out()).get(0).get("last_seen").get("run_id").asText();
        String handed = exec("show", secondRun, "--full").out();
        assertThat(handed).as("the second run was handed the register").contains("KNOWN").contains("Big A.java")
                .doesNotContain(ISSUE).doesNotContain("check the real path first");
        try (var files = Files.walk(home.resolve("registers"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                assertThat(Files.readString(file, StandardCharsets.ISO_8859_1)).as(file.toString())
                        .doesNotContain(ISSUE).doesNotContain("check the real path first");
            }
        }
    }

    /** An audit package whose rule "size" a model judges: its report states the subjects and names others as gone. */
    private Path judgedPackage(List<String> stated, List<String> gone) throws IOException {
        StringBuilder findings = new StringBuilder();
        for (String subject : stated) {
            if (!findings.isEmpty()) findings.append(",");
            findings.append("{\"ruleKey\":\"size\",\"subject\":\"").append(subject)
                    .append("\",\"severity\":\"MEDIUM\",\"title\":\"Big ").append(subject).append("\"}");
        }
        StringBuilder goneJson = new StringBuilder();
        for (String subject : gone) {
            if (!goneJson.isEmpty()) goneJson.append(",");
            goneJson.append("{\"ruleKey\":\"size\",\"subject\":\"").append(subject).append("\"}");
        }
        String command = "printf '%s\\n' '# Report' '<!-- traceworks:audit-result:v1' "
                + "'{\"snapshotComplete\":true,\"findings\":[" + findings + "],\"gone\":[" + goneJson + "]}' '-->'";
        Path dir = Files.createDirectories(temp.resolve("judged"));
        Files.writeString(dir.resolve("catalog.json"), "{\"format\":1,\"rules\":{\"size\":{\"judged\":true}}}");
        Files.writeString(dir.resolve("workflow.json"), "{\"id\":\"audit\",\"name\":\"Audit\",\"metadata\":{\"audit\":"
                + "{\"enabled\":true,\"key\":\"hello\",\"category\":\"example\",\"report_block\":\"report\"}},"
                + "\"blocks\":[{\"type\":\"shell\",\"name\":\"report\",\"config\":{\"command\":"
                + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(command) + "}}]}");
        return dir;
    }

    /**
     * A finding of a judged rule is resolved only when the report names it as gone. One the report does not mention
     * stays open, the summary counts it, and show says how long no report has mentioned it.
     */
    @Test
    void aJudgedFinding_resolvesOnlyWhenNamedAsGone_andTheSilentOneStaysOpen() throws IOException {
        Result first = run(judgedPackage(List.of("A.java", "B.java", "C.java"), List.of()));
        Result second = run(judgedPackage(List.of("C.java"), List.of("A.java")));

        assertThat(first.out()).contains("  register hello: 3 new, 0 resolved, 0 reopened, 3 open now");
        assertThat(second.code()).as(second.err()).isZero();
        assertThat(second.out()).contains("  register hello: 0 new, 1 resolved, 0 reopened, 2 open now, 1 of them not"
                + " mentioned by this report");
        Result list = exec("findings", "hello", "list", "--all");
        assertThat(list.out().lines()).hasSize(3)
                .anySatisfy(l -> assertThat(l).contains("RESOLVED").contains("A.java"))
                .anySatisfy(l -> assertThat(l).contains("OPEN").contains("B.java"))
                .anySatisfy(l -> assertThat(l).contains("OPEN").contains("C.java"));
        String silent = list.out().lines().filter(l -> l.contains("B.java")).findFirst().orElseThrow().substring(0, 8);
        String stated = list.out().lines().filter(l -> l.contains("C.java")).findFirst().orElseThrow().substring(0, 8);
        assertThat(exec("findings", "hello", "show", silent).out())
                .contains("Silent    the last 1 applied report did not mention it");
        assertThat(exec("findings", "hello", "show", stated).out()).doesNotContain("Silent");
        assertThat(exec("findings", "hello", "verify").code()).isZero();
    }

    @Test
    void findingsAlone_listsTheRegisters_withTheirCounts_orSaysThereIsNone() throws IOException {
        Result none = exec("findings");
        assertThat(none.code()).isZero();
        assertThat(none.out()).contains("no registers; no audit run has fed one");

        run(auditPackage("A.java"));
        Result keys = exec("findings");
        assertThat(keys.out()).contains("hello  example  OPEN 1");
    }

    @Test
    void verify_exits8OnATamperedLog_andAnUnknownKeyExits2() throws IOException {
        run(auditPackage("A.java"));
        assertThat(exec("findings", "hello", "verify").code()).isZero();
        Path log = home.resolve("registers").resolve("hello").resolve("events.jsonl");
        Files.writeString(log, Files.readString(log).replace("\"category\":\"example\"", "\"category\":\"other\""));

        Result verify = exec("findings", "hello", "verify");

        assertThat(verify.code()).isEqualTo(8);
        assertThat(verify.out() + verify.err()).contains("prev does not match");
        Result unknown = exec("findings", "nope", "list");
        assertThat(unknown.code()).isEqualTo(2);
        assertThat(unknown.err()).contains("No register 'nope'");
    }

    @Test
    void list_readsTheLog_notTheStateFile() throws IOException {
        run(auditPackage("A.java"));
        Files.writeString(home.resolve("registers").resolve("hello").resolve("state.json"),
                "{\"format\":1,\"key\":\"hello\",\"findings\":[]}\n");

        assertThat(exec("findings", "hello", "list").out().lines()).hasSize(1);
    }

    @Test
    void help_listsFindings() {
        assertThat(exec("--help").out()).containsPattern("(?m)^  findings ");
        Result help = exec("findings", "--help");
        assertThat(help.code()).isZero();
        assertThat(help.out()).contains("list", "show", "verify");
    }

    @Test
    void accept_dismiss_andReopen_fromTheCommandLine() throws IOException {
        run(auditPackage("A.java", "B.java"));
        List<String> ids = exec("findings", "hello", "list").out().lines().map(l -> l.substring(0, 8)).toList();

        Result accept = exec("findings", "hello", "accept", ids.get(0), "--note", "bounded", "--until", "2027-01-31");
        Result dismiss = exec("findings", "hello", "dismiss", ids.get(1), "--note", "not ours");

        assertThat(accept.code()).as(accept.err()).isZero();
        assertThat(accept.out()).contains("accepted " + ids.get(0)).contains("review by 2027-01-31");
        assertThat(dismiss.code()).as(dismiss.err()).isZero();
        assertThat(exec("findings", "hello", "list").out().lines()).hasSize(2)
                .anySatisfy(l -> assertThat(l).contains("ACCEPTED_RISK")).anySatisfy(l -> assertThat(l).contains("DISMISSED"));
        Result show = exec("findings", "hello", "show", ids.get(0));
        assertThat(show.out()).contains("Decision  ACCEPTED_RISK by tester", "review by 2027-01-31", "Note      bounded");

        Result reopen = exec("findings", "hello", "reopen", ids.get(0));
        assertThat(reopen.code()).as(reopen.err()).isZero();
        assertThat(exec("findings", "hello", "show", ids.get(0)).out()).contains("Status    OPEN").doesNotContain("Decision");
        assertThat(exec("findings", "hello", "verify").code()).isZero();
    }

    /** The list shows an accepted finding before a dismissed one, whatever their ids. */
    @Test
    void list_showsTheStatusesInTheirOrder_acceptedBeforeDismissed() throws IOException {
        run(auditPackage("A.java", "B.java"));
        List<String> ids = exec("findings", "hello", "list").out().lines().map(l -> l.substring(0, 8)).toList();
        exec("findings", "hello", "dismiss", ids.get(0), "--note", "not ours");
        exec("findings", "hello", "accept", ids.get(1), "--note", "bounded");

        assertThat(exec("findings", "hello", "list").out().lines()).extracting(l -> l.substring(0, 8) + " "
                + l.substring(10).split(" +")[0]).containsExactly(ids.get(1) + " ACCEPTED_RISK", ids.get(0) + " DISMISSED");
    }

    /** Findings of one status are listed by severity, the highest first. */
    @Test
    void list_ofOneStatus_isSortedBySeverity_theHighestFirst() throws IOException {
        run(auditPackageWith("sev", "{\"snapshotComplete\":true,\"findings\":["
                + "{\"ruleKey\":\"size\",\"subject\":\"A.java\",\"severity\":\"CRITICAL\",\"title\":\"a\"},"
                + "{\"ruleKey\":\"size\",\"subject\":\"B.java\",\"severity\":\"LOW\",\"title\":\"b\"},"
                + "{\"ruleKey\":\"size\",\"subject\":\"C.java\",\"severity\":\"MEDIUM\",\"title\":\"c\"},"
                + "{\"ruleKey\":\"size\",\"subject\":\"D.java\",\"severity\":\"HIGH\",\"title\":\"d\"}]}"));

        assertThat(exec("findings", "sev", "list").out().lines()).extracting(l -> l.substring(10).split(" +")[1])
                .containsExactly("CRITICAL", "HIGH", "MEDIUM", "LOW");
    }

    @Test
    void accept_needsANote_andDismissRefusesUntil_andAWrongTransitionExits6() throws IOException {
        run(auditPackage("A.java"));
        String id = exec("findings", "hello", "list").out().substring(0, 8);

        Result noNote = exec("findings", "hello", "accept", id);
        assertThat(noNote.code()).isEqualTo(2);
        assertThat(noNote.err()).contains("--note");
        Result until = exec("findings", "hello", "dismiss", id, "--note", "n", "--until", "2027-01-01");
        assertThat(until.code()).isEqualTo(2);
        assertThat(until.err()).contains("--until");
        Result tooFar = exec("findings", "hello", "accept", id, "--note", "n", "--until", "2099-01-01");
        assertThat(tooFar.code()).isEqualTo(6);
        assertThat(tooFar.err()).contains("180 days");
        Result badDate = exec("findings", "hello", "accept", id, "--note", "n", "--until", "soon");
        assertThat(badDate.code()).isEqualTo(2);
        Result reopenOpen = exec("findings", "hello", "reopen", id);
        assertThat(reopenOpen.code()).isEqualTo(6);
        assertThat(reopenOpen.err()).contains("OPEN");
    }

    @Test
    void render_writesThePageAgain_andPrintsItsPath() throws IOException {
        run(auditPackage("A.java"));
        Path page = home.resolve("registers").resolve("hello").resolve("report.html");
        assertThat(page).exists();
        Files.delete(page);

        Result render = exec("findings", "hello", "render");

        assertThat(render.code()).as(render.err()).isZero();
        assertThat(render.out().strip()).isEqualTo(page.toString());
        assertThat(Files.readString(page)).contains("<title>Register hello</title>");
    }

    // ---- a decision covers the severity it was made on (docs/registers.md, "Decisions")

    private Path sized(String severity, String evidence) throws IOException {
        return auditPackageWith("hello", "{\"snapshotComplete\":true,\"findings\":[{\"ruleKey\":\"size\",\"subject\":"
                + "\"A.java\",\"severity\":\"" + severity + "\",\"title\":\"Big A.java\",\"evidence\":\"" + evidence
                + "\"}]}");
    }

    @Test
    void aDismissedFindingStatedHigher_isOpenAgain_andShowSaysWhatTheDismissalWasMadeOn() throws IOException {
        run(sized("LOW", "310 lines"));
        String id = exec("findings", "hello", "list").out().substring(0, 8);
        exec("findings", "hello", "dismiss", id, "--note", "false positive");

        Result second = exec("run", sized("CRITICAL", "900 lines").toString(), "--allow-host-execution", "--no-input");

        assertThat(second.out() + second.err()).contains("0 new, 0 resolved, 1 reopened, 1 open now");
        assertThat(exec("findings", "hello", "list").out()).contains(id + "  OPEN       CRITICAL")
                .contains("(dismissed on LOW before, not in force)").doesNotContain("(decided on");
        Result show = exec("findings", "hello", "show", id);
        assertThat(show.out()).contains("Status    OPEN", "Severity  CRITICAL", "Evidence  900 lines",
                "Decision  DISMISSED by tester at ", ", made on severity LOW, not on the CRITICAL the finding has now;"
                        + " not in force: the finding is OPEN",
                "Made on   evidence: 310 lines", "Note      false positive");
        assertThat(exec("findings", "hello", "list", "--json").out())
                .contains("\"note\":\"false positive\",\"severity\":\"LOW\"").contains("\"status\":\"OPEN\"")
                .contains("\"in_force\":false");
        assertThat(exec("findings", "hello", "verify").code()).isZero();

        // Dismissed again, now on CRITICAL: nothing to add to the row, and the decision is in force
        exec("findings", "hello", "dismiss", id, "--note", "checked again");
        assertThat(exec("findings", "hello", "list").out()).contains("DISMISSED").doesNotContain("(decided on")
                .doesNotContain("not in force");
        assertThat(exec("findings", "hello", "list", "--json").out()).doesNotContain("in_force");

        assertThat(exec("findings", "hello", "show", id).out())
                .contains(", made on severity CRITICAL\n").doesNotContain("Made on   evidence").doesNotContain("not in force");
        // Three runs that no longer state it retire the dismissal: resolved, and the decision is not in force
        for (int i = 0; i < 3; i++) {
            run(auditPackageWith("hello", "{\"snapshotComplete\":true,\"findings\":[]}"));
        }
        assertThat(exec("findings", "hello", "list", "--all").out()).contains(id + "  RESOLVED   CRITICAL")
                .contains("(dismissed on CRITICAL before, not in force)");
        assertThat(exec("findings", "hello", "list", "--all", "--json").out()).contains("\"status\":\"RESOLVED\"")
                .contains("\"in_force\":false");
    }

    @Test
    void withKeepInTheConfig_theDismissalStays_andEveryViewSaysWhichSeverityItWasMadeOn() throws IOException {
        Files.writeString(home.resolve("traceworks.json"), "{\"decision_on_higher_severity\": \"keep\"}");
        run(sized("LOW", "310 lines"));
        String id = exec("findings", "hello", "list").out().substring(0, 8);
        exec("findings", "hello", "dismiss", id, "--note", "false positive");

        Result second = exec("run", sized("CRITICAL", "900 lines").toString(), "--allow-host-execution", "--no-input");

        assertThat(second.out() + second.err()).contains("0 new, 0 resolved, 0 reopened");
        assertThat(exec("findings", "hello", "list").out()).contains(id + "  DISMISSED  CRITICAL")
                .contains("(decided on LOW)");
        assertThat(exec("findings", "hello", "show", id).out()).contains("Status    DISMISSED", "Severity  CRITICAL",
                        ", made on severity LOW, not on the CRITICAL the finding has now\n", "Made on   evidence: 310 lines")
                .doesNotContain("not in force");
        assertThat(Files.readString(home.resolve("registers/hello/report.html")))
                .contains(", made on severity LOW, not on the CRITICAL the finding has now: false positive");
        assertThat(Files.readString(home.resolve("registers/hello/state.json")))
                .contains("\"note\":\"false positive\",\"severity\":\"LOW\"");
        assertThat(exec("findings", "hello", "verify").code()).isZero();
    }

    // ---- who decided: the actor, and the agent that acted under the actor (docs/registers.md, "Who decided")

    private List<String> logLines() throws IOException {
        return Files.readAllLines(home.resolve("registers").resolve("hello").resolve("events.jsonl"));
    }

    /** The findings the runs of this home got of their register: the lines of every run's register folder. */
    private String findingsHandedToRuns() throws IOException {
        StringBuilder all = new StringBuilder();
        try (java.util.stream.Stream<Path> files = Files.walk(home.resolve("runs"))) {
            for (Path file : files.filter(p -> p.endsWith(Path.of("register", "findings.jsonl"))).toList()) {
                all.append(Files.readString(file));
            }
        }
        return all.toString();
    }

    @Test
    void aDecisionOfAnAgent_isSaidByEveryView_andOneOfAPersonReadsAsItAlwaysDid() throws IOException {
        run(auditPackage("A.java", "B.java"));
        List<String> ids = exec("findings", "hello", "list").out().lines().map(l -> l.substring(0, 8)).toList();

        Result dismiss = exec("findings", "hello", "dismiss", ids.get(0), "--note", "not ours", "--agent",
                "claude-code");
        Result accept = exec("findings", "hello", "accept", ids.get(1), "--note", "bounded", "--until", "2027-01-31");

        assertThat(dismiss.code()).as(dismiss.err()).isZero();
        assertThat(dismiss.out()).isEqualTo("dismissed " + ids.get(0) + " through the agent claude-code\n");
        assertThat(accept.out()).isEqualTo("accepted " + ids.get(1) + " as a risk, review by 2027-01-31\n");
        // list: the row of the agent's decision says so, the row of the person's is the row it always was
        List<String> rows = exec("findings", "hello", "list").out().lines().toList();
        assertThat(rows).filteredOn(r -> r.startsWith(ids.get(0))).singleElement().asString()
                .startsWith(ids.get(0) + "  DISMISSED  MEDIUM").endsWith("  (through the agent claude-code)");
        assertThat(rows).filteredOn(r -> r.startsWith(ids.get(1))).singleElement().asString()
                .doesNotContain("(").doesNotContain("agent");
        // show: the actor, then that it was through the agent
        assertThat(exec("findings", "hello", "show", ids.get(0)).out())
                .contains("Decision  DISMISSED by tester through the agent claude-code at ");
        assertThat(exec("findings", "hello", "show", ids.get(1)).out()).contains("Decision  ACCEPTED_RISK by tester at ")
                .doesNotContain("agent");
        // list --json, state.json and report.html
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String json : List.of(exec("findings", "hello", "list", "--json").out(), mapper.readTree(
                Files.readString(home.resolve("registers/hello/state.json"))).get("findings").toString())) {
            for (var finding : mapper.readTree(json)) {
                var disposition = finding.get("disposition");
                assertThat(disposition.get("actor").asText()).isEqualTo("tester");
                if (finding.get("id").asText().startsWith(ids.get(0))) {
                    assertThat(disposition.get("agent").asText()).isEqualTo("claude-code");
                } else {
                    assertThat(disposition.has("agent")).as("no key for a decision without an agent").isFalse();
                }
            }
        }
        assertThat(Files.readString(home.resolve("registers/hello/report.html")))
                .contains("dismissed by tester through the agent claude-code at ")
                .contains("accepted by tester at ");
        // the log: the agent beside the actor, after the one line that says the register holds such a decision
        List<String> log = logLines();
        assertThat(log.get(log.size() - 3)).contains("\"type\":\"register.requires\"")
                .contains("\"feature\":\"decision_agent\"");
        assertThat(log.get(log.size() - 2)).contains("\"type\":\"finding.dismissed\"")
                .contains("\"actor\":\"tester\",\"agent\":\"claude-code\"");
        assertThat(log.getLast()).contains("\"type\":\"finding.accepted\"").contains("\"actor\":\"tester\"")
                .doesNotContain("agent");
        assertThat(exec("findings", "hello", "verify").code()).isZero();
        // the register folder of the next run: the first run got an empty file, the second the two decisions
        assertThat(findingsHandedToRuns()).isEmpty();
        run(auditPackage("A.java", "B.java"));
        assertThat(findingsHandedToRuns()).contains("\"actor\":\"tester\",\"agent\":\"claude-code\"")
                .containsOnlyOnce("\"agent\"").contains("\"kind\":\"ACCEPTED_RISK\"");
    }

    /** A harness sets the variable once; what the command line says wins. */
    @Test
    void theVariableNamesTheAgent_theOptionWins_andAReopeningNamesItInItsLine() throws IOException {
        run(auditPackage("A.java", "B.java"));
        List<String> ids = exec("findings", "hello", "list").out().lines().map(l -> l.substring(0, 8)).toList();

        Result byVariable = execAs("harness 2.1", "findings", "hello", "dismiss", ids.get(0), "--note", "not ours");
        Result byOption = execAs("harness 2.1", "findings", "hello", "dismiss", ids.get(1), "--note", "not ours",
                "--agent", "claude-code");

        assertThat(byVariable.code()).as(byVariable.err()).isZero();
        assertThat(byVariable.out()).isEqualTo("dismissed " + ids.get(0) + " through the agent harness 2.1\n");
        assertThat(byOption.out()).isEqualTo("dismissed " + ids.get(1) + " through the agent claude-code\n");
        assertThat(exec("findings", "hello", "show", ids.get(0)).out())
                .contains("Decision  DISMISSED by tester through the agent harness 2.1 at ");
        assertThat(exec("findings", "hello", "show", ids.get(1)).out())
                .contains("Decision  DISMISSED by tester through the agent claude-code at ");
        assertThat(logLines()).filteredOn(l -> l.contains("\"type\":\"register.requires\"")
                && l.contains("decision_agent")).as("one line says it for the register").hasSize(1);

        Result reopen = execAs("harness 2.1", "findings", "hello", "reopen", ids.get(0));

        assertThat(reopen.out()).isEqualTo("reopened " + ids.get(0) + " through the agent harness 2.1\n");
        assertThat(logLines().getLast()).contains("\"type\":\"finding.reopened\"")
                .contains("\"actor\":\"tester\",\"agent\":\"harness 2.1\"").contains("\"reason\":\"manual\"");
        Result accept = execAs("harness 2.1", "findings", "hello", "accept", ids.get(0), "--note", "bounded",
                "--until", "2027-01-31");
        assertThat(accept.out()).isEqualTo("accepted " + ids.get(0) + " as a risk through the agent harness 2.1,"
                + " review by 2027-01-31\n");
        assertThat(exec("findings", "hello", "verify").code()).isZero();
    }

    /** Who sets the option or the variable means to name an agent: nothing is recorded as a person's instead. */
    @Test
    void aBlankOrBadNameOfAnAgent_exits2_andRecordsNothing_andReadingNeedsNoName() throws IOException {
        run(auditPackage("A.java"));
        String id = exec("findings", "hello", "list").out().substring(0, 8);
        int lines = logLines().size();

        Result emptyOption = exec("findings", "hello", "dismiss", id, "--note", "n", "--agent", "");
        Result blankOption = execAs("harness", "findings", "hello", "dismiss", id, "--note", "n", "--agent=  ");
        Result emptyVariable = execAs("", "findings", "hello", "dismiss", id, "--note", "n");
        Result blankVariable = execAs(" \t", "findings", "hello", "accept", id, "--note", "n");
        Result control = exec("findings", "hello", "dismiss", id, "--note", "n", "--agent", "claude\u001b[2Jcode");
        Result twoLines = execAs("claude\ncode", "findings", "hello", "reopen", id);
        Result tooLong = exec("findings", "hello", "dismiss", id, "--note", "n", "--agent", "a".repeat(65));

        for (Result refused : List.of(emptyOption, blankOption, emptyVariable, blankVariable, control, twoLines,
                tooLong)) {
            assertThat(refused.code()).as(refused.err()).isEqualTo(2);
            assertThat(refused.out()).isEmpty();
        }
        assertThat(emptyOption.err()).contains("--agent is blank: name the agent, or leave it out for a decision a"
                + " person makes");
        assertThat(blankOption.err()).contains("--agent is blank");
        assertThat(emptyVariable.err()).contains("TRACEWORKS_AGENT is blank");
        assertThat(blankVariable.err()).contains("TRACEWORKS_AGENT is blank");
        assertThat(control.err()).contains("--agent takes a name of letters, digits, the characters . _ - : / @ +"
                + " and single spaces, all ASCII; got U+001B at 7").doesNotContain("\u001b");
        assertThat(twoLines.err()).contains("TRACEWORKS_AGENT takes a name of").contains("got U+000A at 7");
        assertThat(tooLong.err()).contains("--agent takes a name of at most 64 characters, got 65");
        assertThat(logLines()).as("nothing was decided").hasSize(lines);
        assertThat(exec("findings", "hello", "list").out()).contains(id + "  OPEN");
        // Reading decides nothing: a list, a show and a verify do not look at the variable
        assertThat(execAs("", "findings", "hello", "list").code()).isZero();
        assertThat(execAs("", "findings", "hello", "show", id).code()).isZero();
        assertThat(execAs("", "findings", "hello", "verify").code()).isZero();
        Result elsewhere = exec("findings", "hello", "list", "--agent", "claude-code");
        assertThat(elsewhere.code()).isEqualTo(2);
        assertThat(elsewhere.err()).contains("--agent belongs to accept, dismiss and reopen");
    }

    /** The row of a decision that is not in force, or was made on another severity, names the agent in its note. */
    @Test
    void theListNamesTheAgent_alsoOfADecisionNotInForce_andOfOneMadeOnAnotherSeverity() throws IOException {
        run(sized("LOW", "310 lines"));
        String id = exec("findings", "hello", "list").out().substring(0, 8);
        exec("findings", "hello", "dismiss", id, "--note", "false positive", "--agent", "claude-code");

        run(sized("CRITICAL", "900 lines"));

        assertThat(exec("findings", "hello", "list").out()).contains(id + "  OPEN       CRITICAL")
                .contains("  (dismissed on LOW through the agent claude-code before, not in force)\n");
        assertThat(exec("findings", "hello", "show", id).out()).contains("Decision  DISMISSED by tester through the"
                + " agent claude-code at ").contains("; not in force: the finding is OPEN");
        assertThat(exec("findings", "hello", "list", "--json").out()).contains("\"agent\":\"claude-code\"")
                .contains("\"in_force\":false");

        Files.writeString(home.resolve("traceworks.json"), "{\"decision_on_higher_severity\": \"keep\"}");
        run(sized("LOW", "310 lines"));
        exec("findings", "hello", "dismiss", id, "--note", "still not", "--agent", "claude-code");
        run(sized("HIGH", "700 lines"));

        assertThat(exec("findings", "hello", "list").out()).contains(id + "  DISMISSED  HIGH")
                .contains("  (decided on LOW through the agent claude-code)\n");
        assertThat(exec("findings", "hello", "verify").code()).isZero();
    }

    // ---- items (docs/registers.md, "Items")

    /** An audit package whose report prints this envelope body; {@code key} names its register. */
    private Path auditPackageWith(String key, String envelopeBody) throws IOException {
        String command = "printf '%s\\n' '# Report' '<!-- traceworks:audit-result:v1' '" + envelopeBody + "' '-->'";
        Path dir = Files.createDirectories(temp.resolve("audit-" + key));
        Files.writeString(dir.resolve("workflow.json"), "{\"id\":\"audit-" + key + "\",\"name\":\"Audit\",\"metadata\":"
                + "{\"audit\":{\"enabled\":true,\"key\":\"" + key + "\",\"category\":\"example\",\"report_block\":"
                + "\"report\"}},\"blocks\":[{\"type\":\"shell\",\"name\":\"report\",\"config\":{\"command\":"
                + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(command) + "}}]}");
        return dir;
    }

    private static final String WITH_ITEMS = "{\"snapshotComplete\":true,\"findings\":[{\"ruleKey\":\"gap\","
            + "\"subject\":\"p.Open#1\",\"severity\":\"MEDIUM\",\"title\":\"Gap\",\"item\":\"p.Open\"}],"
            + "\"expectedItems\":4,\"items\":[{\"name\":\"p.Open\",\"digest\":\"aa\",\"judged\":true},"
            + "{\"name\":\"p.Clean\",\"digest\":\"bb\",\"judged\":true},{\"name\":\"p.Other\",\"digest\":\"cc\","
            + "\"judged\":true},{\"name\":\"p.Never \\u00e9\\u202e\",\"digest\":\"dd\",\"judged\":false}]}";

    @Test
    void registers_showTheItemsLine_onlyForARegisterWithItems_andTheRunNamesThem() throws IOException {
        run(auditPackage("A.java"));
        Result fed = run(auditPackageWith("testing", WITH_ITEMS));

        assertThat(fed.out()).contains("  register testing: 1 new, 0 resolved, 0 reopened, 1 open now; "
                + "items 2 of 4 settled, 3 judged in this run");
        assertThat(exec("findings").out()).isEqualTo("hello  example  OPEN 1\ntesting  example  OPEN 1\n"
                + "  items 2 of 4 settled: 2 clean, 0 decided; 1 open, 1 not judged\n");
    }

    @Test
    void items_listsEachItemWithItsState_whatNeedsALookFirst() throws IOException {
        run(auditPackageWith("testing", WITH_ITEMS));
        String id = exec("findings", "testing", "list").out().substring(0, 8);
        exec("findings", "testing", "dismiss", id, "--note", "no concern");
        run(auditPackageWith("testing", WITH_ITEMS.replace("\"digest\":\"cc\",\"judged\":true",
                "\"digest\":\"c2\",\"judged\":false")));

        Result items = exec("findings", "testing", "items");

        assertThat(items.code()).as(items.err()).isZero();
        assertThat(items.out()).isEqualTo("""
                not judged  -   p.Never é?
                not judged  cc  p.Other
                decided     aa  p.Open
                clean       bb  p.Clean
                """);
    }

    @Test
    void items_json_isAsciiOnly_withTheStateAndTheFieldsOfTheStateFile() throws IOException {
        run(auditPackageWith("testing", WITH_ITEMS));

        Result json = exec("findings", "testing", "items", "--json");

        assertThat(json.out().chars().allMatch(c -> c == '\n' || c >= 0x20 && c < 0x7f)).isTrue();
        var array = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json.out());
        assertThat(array).hasSize(4);
        assertThat(array.get(0).get("name").asText()).isEqualTo("p.Clean");
        assertThat(array.get(0).get("state").asText()).isEqualTo("clean");
        assertThat(array.get(0).get("verdict_digest").asText()).isEqualTo("bb");
        assertThat(array.get(0).get("verdict").get("run_id").asText()).isNotBlank();
        assertThat(array.get(1).get("name").asText()).isEqualTo("p.Never é‮");
        assertThat(array.get(1).get("state").asText()).isEqualTo("not judged");
        assertThat(array.get(1).get("verdict").isNull()).isTrue();
    }

    @Test
    void items_ofARegisterWithout_saysSo() throws IOException {
        run(auditPackage("A.java"));

        Result items = exec("findings", "hello", "items");

        assertThat(items.code()).isZero();
        assertThat(items.out()).isEqualTo("no items; the audit of this register lists none\n");
        assertThat(exec("findings", "hello", "items", "--json").out()).isEqualTo("[]\n");
    }

    private Path registerLog() {
        return home.resolve("registers").resolve("hello").resolve("events.jsonl");
    }

    private String firstId() {
        return exec("findings", "hello", "list", "--all").out().lines().findFirst().orElseThrow().substring(0, 8);
    }

    /** The last line of a fresh register names no state, so only the anchor in the ledger finds a change of it. */
    @Test
    void verify_namesTheAnchor_andExits8OnAChangedLastLine() throws IOException {
        run(auditPackage("A.java"));
        Result holds = exec("findings", "hello", "verify");
        assertThat(holds.code()).as(holds.out()).isZero();
        assertThat(holds.out()).contains("anchor    ok (seq 3 is the head the ledger holds").endsWith("verified\n");
        String log = Files.readString(registerLog());
        assertThat(log.lines().reduce((a, b) -> b).orElseThrow()).contains("\"type\":\"finding.new\"");

        Files.writeString(registerLog(), log.replace("\"title\":\"Big A.java\"", "\"title\":\"Fine\""));
        Result verify = exec("findings", "hello", "verify");

        assertThat(verify.code()).isEqualTo(8);
        assertThat(verify.out()).contains("chain     ok").contains("anchor    NOT ok")
                .contains("seq 3: the line is not the one the ledger anchors").contains("does not verify");
    }

    @Test
    void verify_exits8OnACutEnd_alsoAfterARender() throws IOException {
        run(auditPackage("A.java"));
        Result dismissed = exec("findings", "hello", "dismiss", firstId(), "--note", "not ours");
        assertThat(dismissed.code()).as(dismissed.err()).isZero();
        assertThat(dismissed.err()).isEmpty();
        List<String> lines = Files.readAllLines(registerLog());

        Files.writeString(registerLog(), String.join("\n", lines.subList(0, lines.size() - 1)) + "\n");
        assertThat(exec("findings", "hello", "render").code()).isZero();
        Result verify = exec("findings", "hello", "verify");

        assertThat(verify.code()).isEqualTo(8);
        assertThat(verify.out()).contains("anchor    NOT ok").contains("seq 4: the ledger anchors this line")
                .contains("lines were cut from its end");
    }

    /** A register from before anchors existed has none in the ledger: verify says so and holds. */
    @Test
    void verify_ofARegisterTheLedgerDoesNotAnchor_holdsAndSaysSo_andTheNextWriteAnchorsIt() throws Exception {
        run(auditPackage("A.java"));
        // A ledger that never anchored the register: the one of the run is gone, and the next write makes a new one.
        // (Taking the anchors out of a ledger with a commit is no way to get there: the ledger finds that.)
        try (java.util.stream.Stream<Path> files = Files.walk(home.resolve("ledger"))) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }

        Result verify = exec("findings", "hello", "verify");

        assertThat(verify.code()).as(verify.out()).isZero();
        assertThat(verify.out()).contains("anchor    none: the ledger holds no anchor of this register")
                .contains("verified");

        assertThat(exec("findings", "hello", "dismiss", firstId(), "--note", "not ours").code()).isZero();
        assertThat(exec("findings", "hello", "verify").out()).contains("anchor    ok (seq 4");
    }

    /** The decision is kept when the ledger cannot take its anchor; the command says so and verify names the line. */
    @Test
    void aDecisionTheLedgerCannotAnchor_isKept_andSaysSo() throws IOException {
        run(auditPackage("A.java"));
        String id = firstId();
        Path ledger = home.resolve("ledger");
        Files.move(ledger, home.resolve("ledger-away"));
        Files.writeString(ledger, "a file where the ledger was");

        Result dismissed = exec("findings", "hello", "dismiss", id, "--note", "not ours");

        assertThat(dismissed.code()).as(dismissed.err()).isZero();
        assertThat(dismissed.out()).contains("dismissed " + id);
        assertThat(dismissed.err()).contains("is not anchored in the ledger").contains("findings hello render");
        Files.delete(ledger);
        Files.move(home.resolve("ledger-away"), ledger);
        Result verify = exec("findings", "hello", "verify");
        assertThat(verify.code()).as(verify.out()).isZero();
        assertThat(verify.out()).contains("anchor    ok up to seq 3; seq 4 is not anchored");

        Result render = exec("findings", "hello", "render");
        assertThat(render.err()).contains("traceworks: note: seq 4 was not anchored before this write")
                .doesNotContain("is not anchored in the ledger");
        assertThat(Files.readString(registerLog()).lines().reduce((x, y) -> y).orElseThrow())
                .contains("\"type\":\"register.unanchored\"").contains("\"first_seq\":4,\"last_seq\":4");
        assertThat(exec("findings", "hello", "verify").out()).contains("anchor    ok (seq 5");
    }

    /**
     * A ledger that exists and cannot be read is not a ledger without anchors: verify fails (exit 8) and says that
     * the comparison was not made, and a decision is kept and says that it is not anchored.
     */
    @Test
    void aLedgerThatCannotBeRead_failsVerify_andADecisionIsKeptAndNotAnchored() throws IOException {
        run(auditPackage("A.java"));
        String id = firstId();
        Path head = home.resolve("ledger/.git/HEAD");
        String good = Files.readString(head);
        Files.writeString(head, "garbage\n");

        Result verify = exec("findings", "hello", "verify");
        Result dismissed = exec("findings", "hello", "dismiss", id, "--note", "not ours");

        assertThat(verify.code()).as(verify.out()).isEqualTo(8);
        assertThat(verify.out()).contains("chain     ok").contains("anchor    NOT checked")
                .contains("the ledger could not be read").doesNotContain("anchor    none");
        assertThat(dismissed.code()).as(dismissed.err()).isZero();
        assertThat(dismissed.err()).contains("is not anchored in the ledger");
        Files.writeString(head, good);
        assertThat(exec("findings", "hello", "verify").out()).contains("anchor    ok up to seq 3; seq 4 is not anchored");
    }

    /**
     * Someone who can write the register's folder alone can append a line that chains. verify holds and calls the
     * line unproven; the next decision takes it, anchors, records which line it found, and says so.
     */
    @Test
    void anAppendedLine_isCalledUnprovenByVerify_andTheNextDecisionRecordsAndSaysIt() throws Exception {
        run(auditPackage("A.java"));
        String log = Files.readString(registerLog());
        String last = log.lines().reduce((a, b) -> b).orElseThrow();
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(last.getBytes(StandardCharsets.UTF_8)));
        String forged = "{\"seq\":4,\"at\":\"2026-11-14T00:00:00.000Z\",\"type\":\"hook.ran\",\"register\":\"hello\","
                + "\"prev\":\"" + hash + "\",\"run_id\":\"x\",\"exit_code\":0,\"duration_ms\":1,\"stderr_tail\":\"\"}\n";
        Files.writeString(registerLog(), log + forged);

        Result verify = exec("findings", "hello", "verify");
        Result dismissed = exec("findings", "hello", "dismiss", firstId(), "--note", "not ours");

        assertThat(verify.code()).as(verify.out()).isZero();
        assertThat(verify.out()).contains("chain     ok (5 events")
                .contains("anchor    ok up to seq 3; seq 4 is not anchored, so it is unproven");
        assertThat(dismissed.code()).as(dismissed.err()).isZero();
        assertThat(dismissed.err()).contains("traceworks: note: seq 4 was not anchored before this write and is taken"
                + " unproven").contains("(recorded as register.unanchored)");
        assertThat(exec("findings", "hello", "verify").out()).contains("anchor    ok (seq 6");
    }

    /**
     * Whether the chain holds is decided by what the chain's own check found, not by the words of a problem: a
     * register whose key is "ledger" has that word in a problem of its chain.
     */
    @Test
    void verify_ofARegisterNamedLedger_doesNotCallABrokenChainOk() throws IOException {
        String command = "printf '%s\\n' '<!-- traceworks:audit-result:v1' '{\"snapshotComplete\":true,\"findings\":"
                + "[{\"ruleKey\":\"size\",\"subject\":\"A.java\",\"severity\":\"LOW\",\"title\":\"t\"}]}' '-->'";
        Path dir = Files.createDirectories(temp.resolve("named-ledger"));
        Files.writeString(dir.resolve("workflow.json"), "{\"id\":\"audit-l\",\"name\":\"Audit\",\"metadata\":{\"audit\":"
                + "{\"enabled\":true,\"key\":\"ledger\",\"category\":\"example\",\"report_block\":\"report\"}},"
                + "\"blocks\":[{\"type\":\"shell\",\"name\":\"report\",\"config\":{\"command\":"
                + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(command) + "}}]}");
        assertThat(run(dir).code()).isZero();
        Path log = home.resolve("registers/ledger/events.jsonl");
        List<String> lines = Files.readAllLines(log);
        lines.set(lines.size() - 1, lines.getLast().replace("\"register\":\"ledger\"", "\"register\":\"other\""));
        Files.writeString(log, String.join("\n", lines) + "\n");

        Result verify = exec("findings", "ledger", "verify");

        assertThat(verify.code()).isEqualTo(8);
        assertThat(verify.out()).contains("chain     NOT intact").doesNotContain("chain     ok")
                .contains("the line belongs to the register other, not ledger");
    }
}
