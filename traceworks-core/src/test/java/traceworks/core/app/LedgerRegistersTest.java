package traceworks.core.app;

import org.junit.jupiter.api.Test;
import traceworks.core.port.Ledger;
import traceworks.core.record.EventChain;
import traceworks.core.testing.EarlierRegister;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static traceworks.core.app.AppFixture.request;

/**
 * The check of the whole home ({@code traceworks verify} without a run) checks every register: for each key and
 * project the chain, the blobs, the project its log names and the log against its anchors, as
 * {@code findings <key> verify --project} does. A register the ledger anchors and whose log is gone is a problem,
 * a register from before registers had projects is checked as one, and {@code --quick} skips it all, as it skips
 * the records.
 */
class LedgerRegistersTest {

    private static final Path AUDIT = Path.of("/pkgs/audit");

    private final AppFixture fixture = new AppFixture();
    private final RunServices services = fixture.services();

    LedgerRegistersTest() {
        fixture.packages.put(AUDIT, "{\"id\": \"wf-audit\", \"name\": \"Audit\", \"metadata\": {\"audit\":"
                + " {\"enabled\": true, \"key\": \"hello\", \"category\": \"example\", \"report_block\": \"report\"}},"
                + " \"blocks\": [{\"type\": \"shell\", \"name\": \"report\", \"config\": {\"command\":"
                + " \"./report\"}}]}");
        fixture.commands.on("./report", "# Report\n\n<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,"
                + "\"findings\":[{\"ruleKey\":\"long-file\",\"subject\":\"a.txt\",\"severity\":\"LOW\",\"title\":"
                + "\"a.txt is long\"}]}\n-->\n");
    }

    private void run(String project) {
        assertThat(services.start(request(AUDIT).inProject(project), null, null).register().applied()).isTrue();
    }

    /** The problems of one register, by the name the check lists them under. */
    private static List<String> problemsOf(LedgerReport report, String name, String code) {
        return report.problems().stream().filter(problem -> problem.runId().equals(name)
                && problem.code().equals(code)).map(LedgerReport.Problem::text).toList();
    }

    private static final String A = Ledger.registerName("hello", "project-a");
    private static final String B = Ledger.registerName("hello", "project-b");

    @Test
    void registersThatHold_areNoProblem() {
        run("project-a");
        run("project-b");

        LedgerReport report = services.ledgerReport();

        assertThat(report.problems()).isEmpty();
        assertThat(report.ok()).isTrue();
    }

    /** Reproduced 2026-11-21: verify printed "verified" for a copied folder whose log names the other project. */
    @Test
    void aCopiedFolder_isAProblemOfTheRegisterItWasCopiedTo_andOfNoOther() {
        run("project-a");
        fixture.registers.copy("hello", "project-a", "project-b");

        LedgerReport report = services.ledgerReport();

        assertThat(report.ok()).isFalse();
        assertThat(problemsOf(report, B, "register-broken")).singleElement().asString()
                .contains("names the project project-a").contains("is not the register of project-b")
                .as("a name that says the key and the project needs no such beginning")
                .doesNotStartWith("the register hello of project-b: ");
        assertThat(problemsOf(report, A, "register-broken")).isEmpty();
        assertThat(report.problems()).extracting(LedgerReport.Problem::runId).containsOnly(B);
    }

    /** Reproduced 2026-11-21: the anchor line of project A, appended to the anchors of B by a well-formed commit. */
    @Test
    void anAnchorOfAnotherRegisterAmongTheAnchorsOfARegister_isAProblem() {
        run("project-a");
        run("project-b");
        run("project-a");
        List<byte[]> logOfA = fixture.registers.log("hello", "project-a").readAll();
        fixture.ledger.anchorRegister("hello", "project-b", new traceworks.core.port.RegisterAnchor(logOfA.size() - 1,
                EventChain.sha256Hex(logOfA.getLast()), traceworks.core.register.RegisterAnchors.written(List.of())));

        LedgerReport report = services.ledgerReport();

        assertThat(report.ok()).isFalse();
        assertThat(problemsOf(report, B, "register-broken")).singleElement().asString()
                .contains("seq " + (logOfA.size() - 1) + ": the ledger anchors this line");
        assertThat(problemsOf(report, A, "register-broken")).isEmpty();
    }

    /** Reproduced 2026-11-21: a register whose log was removed while the ledger still anchors it. */
    @Test
    void aRegisterTheLedgerAnchorsWhoseLogIsGone_isAProblem_withItsFolderAndWithoutIt() {
        run("project-a");
        run("project-b");
        var log = fixture.registers.log("hello", "project-a");
        log.dropLast(log.readAll().size());
        fixture.registers.remove("hello", "project-b");

        LedgerReport report = services.ledgerReport();

        assertThat(problemsOf(report, A, "register-broken")).as("the folder is there, the log is not")
                .anySatisfy(problem -> assertThat(problem).contains("the ledger anchors this line")
                        .contains("the log is empty"));
        assertThat(problemsOf(report, B, "register-gone")).as("the folder is gone").singleElement().asString()
                .contains("the ledger anchors the register hello of project-b")
                .contains("this home holds no such register");
        assertThat(report.ok()).isFalse();
    }

    @Test
    void everyProblemOfARegister_isListed_andOneCanBeAcknowledgedLikeOneOfARun() {
        run("project-a");
        var log = fixture.registers.log("hello", "project-a");
        List<byte[]> lines = log.readAll();
        log.dropLast(lines.size());
        log.append(new String(lines.getFirst(), StandardCharsets.UTF_8).replace("\"category\":\"example\"",
                "\"category\":\"other\"").getBytes(StandardCharsets.UTF_8));
        lines.subList(1, lines.size() - 1).forEach(log::append);

        LedgerReport report = services.ledgerReport();

        List<String> problems = problemsOf(report, A, "register-broken");
        assertThat(problems).as("the changed first line, and the end that was cut").hasSizeGreaterThanOrEqualTo(2);
        assertThat(problems).anySatisfy(problem -> assertThat(problem).contains("seq 1: prev does not match"));
        assertThat(problems).anySatisfy(problem -> assertThat(problem).contains("the ledger anchors this line"));

        LedgerReport after = services.acknowledge(A, "restored from a copy, and looked at");

        assertThat(after.ok()).isTrue();
        assertThat(after.acknowledged()).hasSize(problems.size());
    }

    private static byte[] withCategory(byte[] line, String category) {
        return new String(line, StandardCharsets.UTF_8).replaceFirst("\"category\":\"[a-z]*\"",
                "\"category\":\"" + category + "\"").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Reproduced 2026-11-21: the anchored last line was changed, the problem acknowledged, and the same line
     * changed again: verify said "verified", since the check worded the second change as it had worded the first.
     * A problem of a register carries the state of its log, so an acknowledgement covers that state only.
     */
    @Test
    void anAcknowledgementOfARegistersProblem_coversThatStateOfTheRegisterOnly() {
        run("project-a");
        var log = fixture.registers.log("hello", "project-a");
        List<byte[]> lines = log.readAll();
        byte[] last = lines.getLast();
        log.replaceLast((new String(last, StandardCharsets.UTF_8).replaceFirst("}$", ",\"x\":1}"))
                .getBytes(StandardCharsets.UTF_8));
        List<String> first = problemsOf(services.ledgerReport(), A, "register-broken");
        assertThat(first).isNotEmpty().allSatisfy(problem -> assertThat(problem)
                .contains("the register's log ends with the line " + EventChain.sha256Hex(log.readAll().getLast())));

        assertThat(services.acknowledge(A, "looked at, and left as it is").ok()).isTrue();
        assertThat(services.ledgerReport().ok()).as("the same state stays acknowledged").isTrue();

        log.replaceLast((new String(last, StandardCharsets.UTF_8).replaceFirst("}$", ",\"x\":2}"))
                .getBytes(StandardCharsets.UTF_8));

        LedgerReport again = services.ledgerReport();
        assertThat(again.ok()).as("the register changed again").isFalse();
        assertThat(problemsOf(again, A, "register-broken")).isNotEmpty().doesNotContainAnyElementsOf(first);
        assertThat(again.acknowledged()).as("the acknowledgement is listed, and covers nothing now")
                .isNotEmpty().allSatisfy(acknowledged -> assertThat(acknowledged.covers()).isFalse());
    }

    /** A line before the last leaves the head as it is: the state a problem carries is that of every line. */
    @Test
    void aLineBeforeTheLastThatChangesAgain_isReportedAgain_andAnEmptiedLogSaysThatItHasNoLine() {
        run("project-a");
        var log = fixture.registers.log("hello", "project-a");
        List<byte[]> lines = log.readAll();
        for (String category : List.of("other", "another")) {
            log.dropLast(lines.size());
            log.append(withCategory(lines.getFirst(), category));
            lines.subList(1, lines.size()).forEach(log::append);

            LedgerReport changed = services.ledgerReport();
            assertThat(changed.ok()).as(category).isFalse();
            assertThat(services.acknowledge(A, "looked at: " + category).ok()).isTrue();
        }
        log.dropLast(lines.size());

        assertThat(problemsOf(services.ledgerReport(), A, "register-broken")).isNotEmpty()
                .allSatisfy(problem -> assertThat(problem).endsWith("(the register's log has no line)"));
    }

    // ---- what an acknowledgement covers includes what the problem is about ----

    private static String sha(byte[] content) {
        return EventChain.sha256Hex(content);
    }

    /**
     * Reproduced 2026-11-21: {@code state.json} was edited, the problem acknowledged, and the file edited again
     * differently: verify said "verified". The log had not changed, so the sentence was the same. A problem about
     * {@code state.json} carries the SHA-256 of the file as it is.
     */
    @Test
    void anAcknowledgementOfAnEditedStateFile_coversThatContentOfTheFileOnly() {
        run("project-a");
        byte[] once = "{\"edited\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] twice = "{\"edited\":2}".getBytes(StandardCharsets.UTF_8);
        fixture.registers.editState("hello", once);

        assertThat(problemsOf(services.ledgerReport(), A, "register-broken")).singleElement().asString()
                .contains("state.json differs from the log").contains("state.json has the SHA-256 " + sha(once));
        assertThat(services.acknowledge(A, "edited by hand, and looked at").ok()).isTrue();
        assertThat(services.ledgerReport().ok()).as("the same content stays acknowledged").isTrue();

        fixture.registers.editState("hello", twice);

        LedgerReport again = services.ledgerReport();
        assertThat(again.ok()).as("the file changed again").isFalse();
        assertThat(problemsOf(again, A, "register-broken")).singleElement().asString()
                .contains("state.json has the SHA-256 " + sha(twice));
        assertThat(again.acknowledged()).isNotEmpty().allSatisfy(acknowledged ->
                assertThat(acknowledged.covers()).isFalse());
    }

    /** A blob that does not match its name: the sentence carries what it hashes to, so a second edit is another one. */
    @Test
    void anAcknowledgementOfAnEditedBlob_coversThatContentOfTheBlobOnly() {
        run("project-a");
        String blob = traceworks.core.register.RegisterEvent.parse(fixture.registers.log("hello", "project-a")
                .readAll().stream().filter(line -> new String(line, StandardCharsets.UTF_8).contains(
                        "\"report_blob\"")).findFirst().orElseThrow()).text("report_blob");
        byte[] once = "edited once".getBytes(StandardCharsets.UTF_8);
        byte[] twice = "edited twice".getBytes(StandardCharsets.UTF_8);
        fixture.registers.blobs("hello").putRaw(blob, once);

        assertThat(problemsOf(services.ledgerReport(), A, "register-broken")).anySatisfy(problem ->
                assertThat(problem).contains("the blob report_blob " + blob + " does not match its name")
                        .contains("its content has the SHA-256 " + sha(once)));
        assertThat(services.acknowledge(A, "looked at").ok()).isTrue();

        fixture.registers.blobs("hello").putRaw(blob, twice);

        LedgerReport again = services.ledgerReport();
        assertThat(again.ok()).as("the blob changed again").isFalse();
        assertThat(problemsOf(again, A, "register-broken")).anySatisfy(problem -> assertThat(problem)
                .contains("its content has the SHA-256 " + sha(twice)));
    }

    /** Reproduced 2026-11-21: verify listed a register whose log cannot be read twice, in two wordings. */
    @Test
    void aRegisterWhoseLogCannotBeRead_isListedOnce_withTheReason() {
        run("project-a");
        fixture.registers.failReads("hello", "project-b", new IllegalStateException("Cannot read b/events.jsonl"));

        LedgerReport report = services.ledgerReport();

        assertThat(problemsOf(report, B, "register-broken")).singleElement().asString()
                .isEqualTo("the register cannot be read: Cannot read b/events.jsonl");
        assertThat(report.problems()).extracting(LedgerReport.Problem::runId).containsOnly(B);
    }

    @Test
    void theQuickCheck_skipsTheRegisters_asItSkipsTheRecords() {
        run("project-a");
        fixture.registers.copy("hello", "project-a", "project-b");

        LedgerReport quick = services.ledgerReport(false);

        assertThat(quick.problems()).isEmpty();
        assertThat(quick.records()).isFalse();
        assertThat(services.ledgerReport(true).ok()).isFalse();
    }

    // ---- a register from before registers had projects ----

    private void anEarlierRegister() {
        fixture.registers.putFromBefore(EarlierRegister.KEY, EarlierRegister.lines(), EarlierRegister.blobs(),
                EarlierRegister.state());
        fixture.ledger.anchorEarlier(EarlierRegister.KEY, EarlierRegister.lastSeq(), EarlierRegister.head());
    }

    @Test
    void aRegisterFromBefore_isCheckedAsOne_againstTheAnchorsOfTheEarlierShape() {
        anEarlierRegister();

        assertThat(services.ledgerReport().problems()).as("as that version left it").isEmpty();

        fixture.registers.logFromBefore(EarlierRegister.KEY).dropLast(1);

        LedgerReport cut = services.ledgerReport();
        assertThat(problemsOf(cut, Ledger.registerName(EarlierRegister.KEY, null), "register-broken"))
                .anySatisfy(problem -> assertThat(problem).contains("seq 9: the ledger anchors this line")
                        .contains("the log ends at seq 8"));
        assertThat(cut.ok()).isFalse();
    }

    /** The anchors of the earlier shape belong to the register that was assigned: they are not those of a lost one. */
    @Test
    void theAnchorsOfTheEarlierShape_areThoseOfTheRegisterThatWasAssigned_orOfOneThatIsGone() {
        anEarlierRegister();
        services.findings().assign(EarlierRegister.KEY, "shop");

        assertThat(services.ledgerReport().problems()).isEmpty();

        fixture.registers.remove(EarlierRegister.KEY, "shop");

        LedgerReport gone = services.ledgerReport();
        assertThat(problemsOf(gone, Ledger.registerName(EarlierRegister.KEY, "shop"), "register-gone")).hasSize(1);
        assertThat(problemsOf(gone, Ledger.registerName(EarlierRegister.KEY, null), "register-gone"))
                .singleElement().asString().contains("from before registers had projects");
    }

    /** The names the problems are listed under: of the form of a run id, and never the same for two registers. */
    @Test
    void theNameOfARegisterInTheCheck_isOfTheFormOfARunId_andNoTwoRegistersShareOne() {
        assertThat(Ledger.registerName("a.b", "c")).isNotEqualTo(Ledger.registerName("a", "b.c"));
        assertThat(Ledger.registerName("a", "b")).isNotEqualTo(Ledger.registerName("a.b", null));
        assertThat(Ledger.registerName("3.a", "b")).isNotEqualTo(Ledger.registerName("a", "b"));
        for (String name : List.of(Ledger.registerName("a.b", "c"), Ledger.registerName("a", "b.c"),
                Ledger.registerName("hello", null), Ledger.registerName("a_b-c", "d.e_f"))) {
            assertThat(traceworks.core.port.Ids.isRunId(name)).as(name).isTrue();
        }
        assertThat(Ledger.registerName("hello", null)).as("as the earlier shape was always listed")
                .isEqualTo("registers.hello");
        assertThat(Ledger.registerName("hello", "shop")).isEqualTo("anchors.5.hello.shop");
    }

    /**
     * Reproduced 2026-11-21: with a key and a project of 120 characters each, the name was longer than a file name
     * may be, so the ledger could not write {@code acks/<name>.jsonl} and the problem could not be acknowledged.
     */
    @Test
    void theNameOfARegisterWithALongKeyAndProject_fitsAFileName_andIsStillItsOwn() {
        String key = "k".repeat(128);
        String project = "p".repeat(128);

        String name = Ledger.registerName(key, project);

        assertThat((name + ".jsonl").length()).as(name).isLessThanOrEqualTo(255);
        assertThat(traceworks.core.port.Ids.isRunId(name)).as(name).isTrue();
        assertThat(name).isEqualTo(Ledger.registerName(key, project))
                .isNotEqualTo(Ledger.registerName(key, "p".repeat(127) + "q"))
                .isNotEqualTo(Ledger.registerName("k".repeat(127) + "j", project))
                .isNotEqualTo(Ledger.registerName(key + "." + project, null));
        assertThat((Ledger.registerName(key, null) + ".jsonl").length()).isLessThanOrEqualTo(255);
    }

    /** With the longest key and project a problem is listed and acknowledged like any other. */
    @Test
    void aProblemOfARegisterWithTheLongestKeyAndProject_isAcknowledgedLikeAnyOther() {
        String key = "k".repeat(128);
        String project = "p".repeat(128);
        fixture.packages.put(Path.of("/pkgs/long"), "{\"id\": \"wf-long\", \"name\": \"Audit\", \"metadata\":"
                + " {\"audit\": {\"enabled\": true, \"key\": \"" + key + "\", \"category\": \"example\","
                + " \"report_block\": \"report\"}}, \"blocks\": [{\"type\": \"shell\", \"name\": \"report\","
                + " \"config\": {\"command\": \"./report\"}}]}");
        assertThat(services.start(request(Path.of("/pkgs/long")).inProject(project), null, null).register()
                .applied()).isTrue();
        fixture.registers.log(key, project).dropLast(1);
        String name = Ledger.registerName(key, project);

        assertThat(services.ledgerReport().problems()).extracting(LedgerReport.Problem::runId).containsOnly(name);
        assertThat(name).as("the name says neither the key nor the project").startsWith("anchors.sha256.");
        assertThat(services.ledgerReport().problems()).as("so the sentence of each problem does").isNotEmpty()
                .allSatisfy(problem -> assertThat(problem.text()).startsWith("the register " + key + " of "
                        + project + ": "));
        assertThat(problemsOf(services.ledgerReport(), A, "register-broken")).isEmpty();
        assertThat(services.acknowledge(name, "cut by hand, and looked at").ok()).isTrue();
    }
}
