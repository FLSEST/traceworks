package traceworks.core.app;

import org.junit.jupiter.api.Test;
import traceworks.core.model.RunStatus;
import traceworks.core.port.RegisterStore;
import traceworks.core.register.RegisterEvents;
import traceworks.core.register.RegisterLog;
import traceworks.core.testing.FakeCommandRunner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static traceworks.core.app.AppFixture.request;

/**
 * A project whose log is gone is not begun anew. When the ledger holds anchors of a key and a project, and the
 * register's log is missing or empty, a run on that project is refused at its start, and the feed refuses the
 * same way. Found 2026-11-21: such a run was handed nothing and started a new log at seq 0 under the old anchors,
 * and only {@code verify} said so afterwards. A register the ledger never anchored starts as before.
 */
class RegisterLogGoneTest {

    private static final Path AUDIT = Path.of("/pkgs/audit");
    private static final String METADATA = "\"metadata\": {\"audit\": {\"enabled\": true, \"key\": \"hello\","
            + " \"category\": \"example\", \"report_block\": \"report\"}}";
    private static final String REPORT = "# Report\n\n<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,"
            + "\"findings\":[{\"ruleKey\":\"long-file\",\"subject\":\"a.txt\",\"severity\":\"LOW\",\"title\":"
            + "\"a.txt is long\"}]}\n-->\n";

    private final AppFixture fixture = new AppFixture();

    RegisterLogGoneTest() {
        fixture.packages.put(AUDIT, "{\"id\": \"wf-audit\", \"name\": \"Audit\", " + METADATA + ", \"blocks\": ["
                + "{\"type\": \"shell\", \"name\": \"report\", \"config\": {\"command\": \"./report\"}}]}");
        fixture.commands.on("./report", REPORT);
    }

    private RunResult run(String project) {
        return fixture.services().start(request(AUDIT).inProject(project), null, null);
    }

    private void emptyTheLog(String project) {
        var log = fixture.registers.log("hello", project);
        log.dropLast(log.readAll().size());
    }

    private void refusedAtItsStart(String project) {
        int runs = fixture.store.runIds().size();
        int anchors = fixture.ledger.registerAnchors("hello", project).size();

        assertThatThrownBy(() -> run(project)).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("the ledger holds anchors of the register hello of " + project)
                .hasMessageContaining("its log is gone")
                .hasMessageContaining("'traceworks verify'");

        assertThat(fixture.store.runIds()).as("refused before the run exists").hasSize(runs);
        assertThat(fixture.ledger.registerAnchors("hello", project)).as("no anchor").hasSize(anchors);
    }

    @Test
    void aRunOnAProjectWhoseLogIsEmpty_whileTheLedgerAnchorsIt_isRefusedAtItsStart_andWritesNothing() {
        run("shop");
        emptyTheLog("shop");

        refusedAtItsStart("shop");

        assertThat(fixture.registers.log("hello", "shop").readAll()).as("no new log at seq 0").isEmpty();
    }

    @Test
    void aRunOnAProjectWhoseFolderIsGone_whileTheLedgerAnchorsIt_isRefusedAtItsStart() {
        run("shop");
        run("web");
        fixture.registers.remove("hello", "shop");

        refusedAtItsStart("shop");

        assertThat(fixture.registers.projects("hello")).as("no folder was made for it").containsExactly("web");
        assertThat(run("web").register().applied()).as("the other project goes on").isTrue();
    }

    /** The log goes while the run runs: its report feeds nothing, and no log is begun under the old anchors. */
    @Test
    void theFeed_refusesTheSameWay_whenTheLogWentWhileTheRunRan() {
        Path racing = Path.of("/pkgs/racing");
        fixture.packages.put(racing, "{\"id\": \"wf-audit\", \"name\": \"Audit\", " + METADATA + ", \"blocks\": ["
                + "{\"type\": \"shell\", \"name\": \"meanwhile\", \"config\": {\"command\": \"./meanwhile\"}},"
                + "{\"type\": \"shell\", \"name\": \"report\", \"config\": {\"command\": \"./report\"}}]}");
        run("shop");
        int anchors = fixture.ledger.registerAnchors("hello", "shop").size();
        fixture.commands.on("./meanwhile", req -> {
            emptyTheLog("shop");
            return FakeCommandRunner.ok("done");
        });

        RunResult result = fixture.services().start(request(racing).inProject("shop"), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.register().applied()).isFalse();
        assertThat(result.register().reason()).contains("the ledger holds anchors of the register hello of shop")
                .contains("its log is gone").contains("'traceworks verify'");
        assertThat(fixture.registers.log("hello", "shop").readAll()).as("no line").isEmpty();
        assertThat(fixture.ledger.registerAnchors("hello", "shop")).as("and no anchor").hasSize(anchors);
    }

    /** A home without a ledger entry for the register: nothing says that a log was there, so one is begun. */
    @Test
    void aRegisterTheLedgerNeverAnchored_startsAsBefore_alsoWithAnEmptyFolder() {
        run("shop");
        emptyTheLog("shop");
        fixture.ledger.forgetAnchors("hello", "shop");

        RunResult result = run("shop");

        assertThat(result.register().applied()).isTrue();
        assertThat(result.register().summary()).contains("1 new");
        assertThat(fixture.ledger.registerAnchors("hello", "shop")).hasSize(1);
        assertThat(run("web").register().applied()).as("a project no run has fed").isTrue();
    }

    // ---- gone for every command, and in every form a log that is not there has ----

    private static final byte[] FRAGMENT = "{\"seq\":0,\"at\":\"2026".getBytes(StandardCharsets.UTF_8);

    private String findingOf(String project) {
        return fixture.services().findings().ofProject(project).state("hello").findings().getFirst().id();
    }

    private static void assertGone(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String project) {
        assertThatThrownBy(call).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("he ledger holds anchors of the register hello of " + project)
                .hasMessageContaining("its log is gone")
                .hasMessageContaining("Put the register's folder back, or feed another project key")
                .hasMessageContaining("'traceworks verify'");
    }

    /** Reproduced 2026-11-21: a log cut to a fragment refused the run, and render then began a log at seq 0. */
    @Test
    void aLogThatIsAFragment_orHoldsNotesOfTheToolAlone_isGoneAsAnEmptyOneIs() {
        run("shop");
        emptyTheLog("shop");
        fixture.registers.log("hello", "shop").tearTail(FRAGMENT);

        refusedAtItsStart("shop");

        fixture.registers.log("hello", "shop").discardTornTail();
        try (RegisterStore.Opened opened = fixture.registers.openForWrite("hello", "shop")) {
            new RegisterLog(opened, fixture.clock).append(RegisterEvents.REPAIRED, Map.of("discarded_bytes", 21));
        }

        refusedAtItsStart("shop");
        assertThat(fixture.registers.log("hello", "shop").readAll()).as("the note, and no line after it").hasSize(1);
    }

    /**
     * Reproduced 2026-11-21: render on an emptied, anchored log overwrote state.json and report.html, and on a
     * fragment it wrote register.repaired at seq 0 and anchored it, so the next run began anew at seq 1.
     */
    @Test
    void everyFindingsCommand_onARegisterWhoseLogIsGone_isRefused_andWritesNothing() {
        run("shop");
        String id = findingOf("shop");
        byte[] state = fixture.registers.stateFile("hello", "shop");
        byte[] view = fixture.registers.viewFile("hello");
        emptyTheLog("shop");
        fixture.registers.log("hello", "shop").tearTail(FRAGMENT);
        int anchors = fixture.ledger.registerAnchors("hello", "shop").size();
        Findings findings = fixture.services().findings().ofProject("shop");

        assertGone(() -> findings.state("hello"), "shop");
        assertGone(() -> findings.events("hello"), "shop");
        assertGone(() -> findings.accept("hello", id, "a risk we take", null), "shop");
        assertGone(() -> findings.dismiss("hello", id, "not ours"), "shop");
        assertGone(() -> findings.reopen("hello", id, "again"), "shop");
        assertGone(() -> findings.render("hello"), "shop");

        assertThat(fixture.registers.log("hello", "shop").readAll()).as("no line, also no note").isEmpty();
        assertThat(fixture.registers.log("hello", "shop").tail()).as("the fragment is where it was")
                .isEqualTo(FRAGMENT);
        assertThat(fixture.registers.stateFile("hello", "shop")).as("state.json is not overwritten").isEqualTo(state);
        assertThat(fixture.registers.viewFile("hello")).as("nor report.html").isEqualTo(view);
        assertThat(fixture.ledger.registerAnchors("hello", "shop")).as("and no anchor").hasSize(anchors);
        assertThat(findings.verified("hello").report().problems()).as("verify is the command that says it")
                .anySatisfy(problem -> assertThat(problem).contains("the ledger anchors this line"));
    }

    /**
     * Reproduced 2026-11-21: with the whole folder of one project removed, {@code findings hello list} without a
     * project meant the other project and said nothing. The projects of a key are its folders and the projects
     * the ledger anchors for it.
     */
    @Test
    void aProjectWhoseWholeFolderWasRemoved_stillCounts_andIsGoneForEveryCommand() {
        run("shop");
        run("web");
        String id = findingOf("shop");
        fixture.registers.remove("hello", "shop");
        Findings findings = fixture.services().findings();

        assertThat(findings.projects("hello")).containsExactly("shop", "web");
        assertThatThrownBy(() -> findings.state("hello")).as("the only folder is not the only project")
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("has 2 projects");
        assertGone(() -> findings.ofProject("shop").state("hello"), "shop");
        assertGone(() -> findings.ofProject("shop").dismiss("hello", id, "not ours"), "shop");
        assertGone(() -> findings.ofProject("shop").render("hello"), "shop");
        assertThat(fixture.registers.projects("hello")).as("no folder was made for it").containsExactly("web");
        assertThat(findings.ofProject("web").state("hello").findings()).hasSize(1);

        fixture.registers.remove("hello", "web");

        assertThat(fixture.registers.projects("hello")).isEmpty();
        assertThat(findings.projects("hello")).as("the ledger still names them").containsExactly("shop", "web");
        assertGone(() -> findings.ofProject("web").state("hello"), "web");
    }

    /** What cannot be asked is not taken as "no anchors": a register is not begun while the ledger cannot say. */
    @Test
    void anchorsThatCannotBeRead_refuseARunOnARegisterWithoutALog_andNoneOnOneThatHasItsLog() {
        run("shop");
        fixture.ledger.failAnchorReads(new IllegalStateException("its HEAD cannot be read"));
        int runs = fixture.store.runIds().size();

        assertThatThrownBy(() -> run("web")).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("the log of the register hello of web is not there")
                .hasMessageContaining("the ledger cannot say whether it holds anchors of it")
                .hasMessageContaining("its HEAD cannot be read");
        assertThat(fixture.store.runIds()).as("refused before the run exists").hasSize(runs);
        assertThat(fixture.registers.projects("hello")).containsExactly("shop");

        assertThat(run("shop").status()).as("a register that has its log needs no answer").isEqualTo(
                RunStatus.COMPLETED);
        assertThat(fixture.services().findings().ofProject("shop").state("hello").findings()).hasSize(1);
    }

    /**
     * The same for every findings command, on a folder that exists: its log is empty, and the ledger cannot say
     * whether it anchors the register. Nothing is shown, and nothing is written.
     */
    @Test
    void anchorsThatCannotBeRead_refuseEveryFindingsCommand_onAnExistingFolderWithAnEmptyLog_andNothingIsWritten() {
        run("shop");
        String id = findingOf("shop");
        byte[] state = fixture.registers.stateFile("hello", "shop");
        byte[] view = fixture.registers.viewFile("hello");
        emptyTheLog("shop");
        fixture.ledger.failAnchorReads(new IllegalStateException("its HEAD cannot be read"));
        Findings findings = fixture.services().findings().ofProject("shop");

        for (org.assertj.core.api.ThrowableAssert.ThrowingCallable command : java.util.List.<
                org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(() -> findings.state("hello"),
                () -> findings.events("hello"), () -> findings.accept("hello", id, "a risk we take", null),
                () -> findings.dismiss("hello", id, "not ours"), () -> findings.reopen("hello", id, "again"),
                () -> findings.render("hello"))) {
            assertThatThrownBy(command).isInstanceOf(RunRefusedException.class)
                    .hasMessageContaining("he log of the register hello of shop is not there")
                    .hasMessageContaining("the ledger cannot say whether it holds anchors of it")
                    .hasMessageContaining("its HEAD cannot be read")
                    .hasMessageContaining("a register is not begun while that cannot be asked");
        }

        assertThat(fixture.registers.log("hello", "shop").readAll()).as("no line, also no note").isEmpty();
        assertThat(fixture.registers.stateFile("hello", "shop")).as("state.json is not overwritten").isEqualTo(state);
        assertThat(fixture.registers.viewFile("hello")).as("nor report.html").isEqualTo(view);
        fixture.ledger.failAnchorReads(null);
        assertThat(fixture.ledger.registerAnchors("hello", "shop")).as("and no anchor").hasSize(1);
    }

    // ---- a ledger that is there and cannot be listed ----

    /**
     * Reproduced 2026-11-21: with the folder of one project removed and the ledger damaged, a {@code dismiss}
     * without a project went to the other project. A ledger that cannot be listed does not shrink the projects of
     * a key: a command that names no project is refused and asks for one.
     */
    @Test
    void aLedgerThatCannotBeListed_doesNotShrinkTheProjectsOfAKey_soACommandWithoutAProjectIsRefused() {
        run("shop");
        run("web");
        String ofWeb = findingOf("web");
        int lines = fixture.registers.log("hello", "web").readAll().size();
        fixture.registers.remove("hello", "shop");
        fixture.ledger.failAnchorReads(new IllegalStateException("its own configuration cannot be listed"));
        Findings findings = fixture.services().findings();

        assertThat(findings.ledgerUnlisted()).contains("its own configuration cannot be listed");
        for (org.assertj.core.api.ThrowableAssert.ThrowingCallable command : java.util.List.<
                org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(() -> findings.state("hello"),
                () -> findings.events("hello"), () -> findings.dismiss("hello", ofWeb, "not ours"),
                () -> findings.accept("hello", ofWeb, "a risk", null), () -> findings.render("hello"),
                () -> findings.projectOf("hello"))) {
            assertThatThrownBy(command).isInstanceOf(RunRefusedException.class)
                    .hasMessageContaining("The ledger cannot say which projects the register 'hello' has (its own"
                            + " configuration cannot be listed)")
                    .hasMessageContaining("name the project with --project KEY");
        }
        assertThat(fixture.registers.log("hello", "web").readAll()).as("nothing went to the other project")
                .hasSize(lines);

        assertThat(findings.ofProject("web").state("hello").findings()).as("with the project named, a register"
                + " whose log has begun is used as before").hasSize(1);
        assertThatThrownBy(() -> findings.ofProject("shop").state("hello")).as("and one that is not there is not"
                + " said to have never been").isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("the ledger cannot say");

        fixture.ledger.failAnchorReads(null);
        assertThat(findings.ledgerUnlisted()).isEmpty();
        assertThatThrownBy(() -> findings.state("hello")).hasMessageContaining("has 2 projects");
    }

    /** An acknowledgement in verify silences the check of the home; it begins nothing anew. */
    @Test
    void anAcknowledgementOfARegisterThatIsGone_silencesTheCheck_andBeginsNothingAnew() {
        run("shop");
        fixture.registers.remove("hello", "shop");
        String name = traceworks.core.port.Ledger.registerName("hello", "shop");
        assertThat(fixture.services().ledgerReport().ok()).isFalse();

        assertThat(fixture.services().acknowledge(name, "the folder was lost with the disk").ok()).isTrue();

        refusedAtItsStart("shop");
    }

    // ---- a register from before registers had projects whose log is gone ----

    private static void assertEarlierGone(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("he ledger holds anchors of the register hello from before registers had"
                        + " projects")
                .hasMessageContaining("this home holds neither that register nor one that was assigned from it")
                .hasMessageContaining("Put the register's folder back, or feed a register of another key")
                .hasMessageContaining("'traceworks verify'");
    }

    /**
     * Reproduced 2026-11-21: the log of a register from before was gone while the ledger still anchored it under
     * the key alone, and a run with {@code --project a} began a new register.
     */
    @Test
    void earlierAnchorsNoRegisterAccountsFor_refuseEveryRunAndCommandOnTheKey() {
        fixture.ledger.anchorEarlier("hello", 3, "a".repeat(64));

        assertEarlierGone(() -> run("shop"));
        assertThat(fixture.store.runIds()).as("refused before the run exists").isEmpty();
        assertThat(fixture.registers.keys()).as("and no register is begun").isEmpty();
        Findings findings = fixture.services().findings();
        assertThat(findings.keys()).containsExactly("hello");
        assertThat(findings.earlierGone("hello")).isPresent();
        assertEarlierGone(() -> findings.state("hello"));
        assertEarlierGone(() -> findings.ofProject("shop").state("hello"));
        assertEarlierGone(() -> findings.ofProject("shop").render("hello"));
        assertEarlierGone(() -> findings.assign("hello", "shop"));
        assertThat(fixture.registers.keys()).isEmpty();
    }

    /**
     * Reproduced 2026-11-21: a folder {@code registers/hello/zz/events.jsonl/} lifted the refusal, and a run began
     * a register beside the anchors of the one that was gone. A sibling whose log cannot be read accounts for
     * nothing: the key stays refused, and the message names the project that cannot be read.
     */
    @Test
    void aSiblingWhoseLogCannotBeRead_accountsForNothing_andTheKeyIsRefused_namingThatProject() {
        fixture.ledger.anchorEarlier("hello", 3, "a".repeat(64));
        fixture.registers.failReads("hello", "zz", new IllegalStateException("Cannot read zz/events.jsonl"));
        Findings findings = fixture.services().findings();

        assertEarlierGone(() -> run("shop"));
        assertThatThrownBy(() -> run("shop")).hasMessageContaining("the log of the register hello of zz cannot be"
                + " read (Cannot read zz/events.jsonl), so it accounts for nothing");
        assertEarlierGone(() -> findings.ofProject("shop").state("hello"));
        assertEarlierGone(() -> findings.ofProject("zz").state("hello"));
        assertThat(findings.earlierGone("hello")).isPresent();
        assertThat(findings.earlierGone("hello").get()).contains("hello of zz cannot be read");
        assertThat(fixture.store.runIds()).as("refused before the run exists").isEmpty();
        assertThat(fixture.registers.projects("hello")).as("no register is begun beside it").containsExactly("zz");
    }

    /** A sibling that can be read and was assigned still accounts for them, whatever another one is. */
    @Test
    void aSiblingWhoseLogCannotBeRead_doesNotRefuseTheKey_whileAnotherProjectAccountsForTheEarlierAnchors() {
        String key = traceworks.core.testing.EarlierRegister.KEY;
        fixture.registers.putFromBefore(key, traceworks.core.testing.EarlierRegister.lines(),
                traceworks.core.testing.EarlierRegister.blobs(), traceworks.core.testing.EarlierRegister.state());
        fixture.ledger.anchorEarlier(key, traceworks.core.testing.EarlierRegister.lastSeq(),
                traceworks.core.testing.EarlierRegister.head());
        Findings findings = fixture.services().findings();
        findings.assign(key, "shop");
        fixture.registers.failReads(key, "aa", new IllegalStateException("Cannot read aa/events.jsonl"));
        fixture.registers.failReads(key, "zz", new IllegalStateException("Cannot read zz/events.jsonl"));

        assertThat(findings.earlierGone(key)).isEmpty();
        assertThat(findings.ofProject("shop").state(key).findings()).isNotEmpty();
    }

    /** The register that was assigned from it accounts for the earlier anchors, and so does the one at the key. */
    @Test
    void earlierAnchors_areAccountedForByTheRegisterAtTheKey_andByTheOneThatWasAssignedFromIt() {
        String key = traceworks.core.testing.EarlierRegister.KEY;
        fixture.registers.putFromBefore(key, traceworks.core.testing.EarlierRegister.lines(),
                traceworks.core.testing.EarlierRegister.blobs(), traceworks.core.testing.EarlierRegister.state());
        fixture.ledger.anchorEarlier(key, traceworks.core.testing.EarlierRegister.lastSeq(),
                traceworks.core.testing.EarlierRegister.head());
        Findings findings = fixture.services().findings();

        assertThat(findings.earlierGone(key)).as("it lies at the key").isEmpty();
        findings.assign(key, "shop");
        assertThat(findings.earlierGone(key)).as("it was assigned").isEmpty();
        assertThat(findings.ofProject("shop").state(key).findings()).isNotEmpty();

        fixture.registers.remove(key, "shop");

        assertThat(findings.earlierGone(key)).as("neither is there any more").isPresent();
        assertThatThrownBy(() -> findings.ofProject("web").state(key)).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("from before registers had projects");
    }
}
