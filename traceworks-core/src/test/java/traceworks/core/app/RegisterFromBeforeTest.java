package traceworks.core.app;

import org.junit.jupiter.api.Test;
import traceworks.core.model.RunStatus;
import traceworks.core.port.RegisterAnchor;
import traceworks.core.port.RegisterStore;
import traceworks.core.record.EventChain;
import traceworks.core.register.Change;
import traceworks.core.register.FindingStatus;
import traceworks.core.register.RegisterEvent;
import traceworks.core.register.RegisterEvents;
import traceworks.core.register.RegisterLog;
import traceworks.core.register.RegisterState;
import traceworks.core.testing.EarlierRegister;
import traceworks.core.testing.FakeCommandRunner;
import traceworks.core.testing.InMemoryEventLog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static traceworks.core.app.AppFixture.request;

/**
 * A register from before registers had projects: a version that kept one register per key left its log in the
 * folder of the key itself. Nothing assigns it to a project by itself. While it lies there, every run with its key
 * and every use of its findings is refused with the command that assigns it; {@code findings <key> assign} gives it
 * its project, and from then on it is the register of that project, with the log and the anchors it had.
 */
class RegisterFromBeforeTest {

    private static final String KEY = EarlierRegister.KEY;
    private static final Path AUDIT = Path.of("/pkgs/arch");
    private static final String HINT = "'traceworks findings arch assign --project KEY'";

    private final AppFixture fixture = new AppFixture();

    RegisterFromBeforeTest() {
        fixture.packages.put(AUDIT, "{\"id\": \"" + EarlierRegister.WORKFLOW + "\", \"name\": \"Arch\", \"metadata\":"
                + " {\"audit\": {\"enabled\": true, \"key\": \"arch\", \"category\": \"architecture\","
                + " \"report_block\": \"report\"}}, \"blocks\": ["
                + "{\"type\": \"shell\", \"name\": \"known\", \"config\": {\"values\": {\"KNOWN\":"
                + " \"{{ audit_findings }}\"}, \"command\": \"./known\"}},"
                + "{\"type\": \"shell\", \"name\": \"report\", \"config\": {\"command\": \"./report\"}}]}");
        fixture.commands.on("./known", "read");
        fixture.commands.on("./report", "# Report\n\n<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,"
                + "\"findings\":[{\"ruleKey\":\"size\",\"subject\":\"C.java\",\"severity\":\"HIGH\",\"title\":"
                + "\"C is long\",\"evidence\":\"E1\"}]}\n-->\n");
    }

    /** The register as an earlier version left it in this home, with the anchor of its head in the ledger. */
    private void anEarlierRegister() {
        fixture.registers.putFromBefore(KEY, EarlierRegister.lines(), EarlierRegister.blobs(), EarlierRegister.state());
        fixture.ledger.anchorEarlier(KEY, 2, EventChain.sha256Hex(EarlierRegister.lines().get(2)));
        fixture.ledger.anchorEarlier(KEY, EarlierRegister.lastSeq(), EarlierRegister.head());
    }

    private Findings findings() {
        return fixture.services().findings();
    }

    private List<RegisterEvent> events(String project) {
        try (RegisterStore.Opened opened = fixture.registers.open(KEY, project)) {
            return new RegisterLog(opened, fixture.clock).read();
        }
    }

    private RunResult run(String project) {
        return fixture.services().start(request(AUDIT).inProject(project), null, null);
    }

    // ---- nothing assigns it by itself ----

    @Test
    void whileARegisterFromBeforeLiesThere_everyRunWithItsKeyIsRefused_andNamesTheCommand() {
        anEarlierRegister();

        assertThatThrownBy(() -> run("shop")).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("the register arch is from before registers had projects")
                .hasMessageContaining(HINT);

        assertThat(fixture.store.runIds()).as("refused before the run exists").isEmpty();
        assertThat(fixture.registers.unassigned(KEY)).isTrue();
        assertThat(fixture.registers.projects(KEY)).as("nothing was assigned, and no register was made").isEmpty();
        assertThat(fixture.registers.logFromBefore(KEY).readAll()).hasSize(EarlierRegister.lines().size());
    }

    /** The register appears while a run of its key runs: the run feeds nothing, and says how to assign it. */
    @Test
    void aRunThatEndsWhileARegisterFromBeforeLiesThere_feedsNothing() {
        Path racing = Path.of("/pkgs/racing");
        fixture.packages.put(racing, "{\"id\": \"wf-1\", \"name\": \"Arch\", \"metadata\": {\"audit\": {\"enabled\":"
                + " true, \"key\": \"arch\", \"category\": \"architecture\", \"report_block\": \"report\"}},"
                + " \"blocks\": [{\"type\": \"shell\", \"name\": \"meanwhile\", \"config\": {\"command\":"
                + " \"./meanwhile\"}}, {\"type\": \"shell\", \"name\": \"report\", \"config\": {\"command\":"
                + " \"./report\"}}]}");
        fixture.commands.on("./meanwhile", req -> {
            anEarlierRegister();
            return FakeCommandRunner.ok("done");
        });

        RunResult result = fixture.services().start(request(racing).inProject("shop"), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.register().applied()).isFalse();
        assertThat(result.register().reason()).contains("from before registers had projects").contains(HINT);
        assertThat(fixture.registers.projects(KEY)).isEmpty();
        assertThat(fixture.registers.logFromBefore(KEY).readAll()).hasSize(EarlierRegister.lines().size());
    }

    @Test
    void whileARegisterFromBeforeLiesThere_everyUseOfItsFindingsIsRefused_andNamesTheCommand() {
        anEarlierRegister();
        String id = "9b9a750d";

        for (Findings findings : List.of(findings(), findings().ofProject("shop"))) {
            assertRefused(() -> findings.state(KEY));
            assertRefused(() -> findings.events(KEY));
            assertRefused(() -> findings.dismiss(KEY, id, "no"));
            assertRefused(() -> findings.accept(KEY, id, "no", null));
            assertRefused(() -> findings.reopen(KEY, id, "again"));
            assertRefused(() -> findings.render(KEY));
            assertRefused(() -> findings.projectOf(KEY));
        }
        assertRefused(() -> findings().ofProject("shop").verified(KEY));
        assertThat(findings().unassigned(KEY)).isTrue();
        assertThat(findings().keys()).contains(KEY);
        assertThat(findings().projects(KEY)).isEmpty();
        assertThat(fixture.registers.logFromBefore(KEY).readAll()).as("nothing was written")
                .hasSize(EarlierRegister.lines().size());
    }

    private static void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("is from before registers had projects").hasMessageContaining(HINT);
    }

    // ---- assign ----

    @Test
    void assign_givesTheRegisterItsProject_withTheLogTheStateAndTheAnchorsItHad() {
        anEarlierRegister();

        String location = findings().assign(KEY, "shop");

        assertThat(location).isEqualTo("memory:arch/shop");
        assertThat(fixture.registers.unassigned(KEY)).isFalse();
        assertThat(fixture.registers.projects(KEY)).containsExactly("shop");
        List<RegisterEvent> events = events("shop");
        assertThat(events).hasSize(EarlierRegister.lines().size() + 1);
        for (int i = 0; i < EarlierRegister.lines().size(); i++) {
            assertThat(events.get(i).raw()).as("line " + i + " is the line it was").isEqualTo(EarlierRegister.lines().get(i));
        }
        RegisterEvent assigned = events.getLast();
        assertThat(assigned.type()).isEqualTo(RegisterEvents.ASSIGNED);
        assertThat(assigned.text("project")).isEqualTo("shop");
        assertThat(assigned.text("actor")).isEqualTo(AppFixture.ACTOR);
        assertThat(assigned.prev()).as("the same chain").isEqualTo(EarlierRegister.head());
        RegisterState state = findings().state(KEY);
        assertThat(state.project()).isEqualTo("shop");
        assertThat(state.findings()).extracting(f -> f.subject() + ":" + f.status()).containsExactlyInAnyOrder(
                "A.java:DISMISSED", "B.java:ACCEPTED_RISK", "C.java:OPEN");
        assertThat(new String(fixture.registers.stateFile(KEY, "shop"), StandardCharsets.UTF_8))
                .contains("\"project\":\"shop\"");
        assertThat(fixture.registers.viewFile(KEY)).as("the page was written again").isNotNull();
        // The anchors: what the ledger held under the key alone stays, and the new head is under key and project
        assertThat(fixture.ledger.earlierRegisterAnchors(KEY)).hasSize(2);
        assertThat(fixture.ledger.registerAnchors(KEY, "shop"))
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields("written").containsExactly(
                new RegisterAnchor(assigned.seq(), EventChain.sha256Hex(assigned.raw())));
        Findings.Verified verified = findings().verified(KEY);
        assertThat(verified.report().intact()).as(verified.report().problems().toString()).isTrue();
        assertThat(verified.anchor()).isEqualTo("ok (seq 10 is the head the ledger holds, 3 anchors)");
        assertThat(findings().unanchored()).isEmpty();
    }

    @Test
    void afterTheAssignment_theProjectRunsAndDecidesAsOnAnyRegister() {
        anEarlierRegister();
        findings().assign(KEY, "shop");

        RunResult result = run("shop");

        assertThat(result.register().applied()).as(String.valueOf(result.register().reason())).isTrue();
        assertThat(result.register().counts()).as("C.java was open, and is seen again").containsEntry(
                Change.Kind.SEEN, 1).doesNotContainKey(Change.Kind.NEW);
        String handed = String.join("\n", fixture.commands.requests().getFirst().values().values());
        assertThat(handed).contains("DISMISSED | LOW | size | A.java", "ACCEPTED_RISK | MEDIUM | size | B.java",
                "OPEN | HIGH | size | C.java");
        String open = findings().state(KEY).findings().stream().filter(f -> f.status() == FindingStatus.OPEN)
                .findFirst().orElseThrow().id();
        findings().dismiss(KEY, open, "not ours");
        assertThat(findings().verified(KEY).report().intact()).isTrue();
        assertThat(findings().verified(KEY).anchor()).startsWith("ok (seq ");
    }

    /** What the ledger said of the log before the assignment still holds it: a line that was changed is found. */
    @Test
    void afterTheAssignment_aChangedLineFromBeforeIt_isStillFoundByTheEarlierAnchors() {
        anEarlierRegister();
        findings().assign(KEY, "shop");
        InMemoryEventLog log = fixture.registers.log(KEY, "shop");
        List<byte[]> lines = log.readAll();
        log.dropLast(2);
        log.append(new String(lines.get(9), StandardCharsets.UTF_8).replace("fix it after all", "never mind")
                .getBytes(StandardCharsets.UTF_8));
        log.append(lines.get(10));

        Findings.Verified verified = findings().verified(KEY);

        assertThat(verified.report().intact()).isFalse();
        assertThat(verified.anchor()).isEqualTo("NOT ok");
        assertThat(verified.report().problems()).anySatisfy(problem -> assertThat(problem)
                .contains("seq 9: the line is not the one the ledger anchors"));
    }

    /** The assignment itself is anchored: a log cut back to before it no longer matches the ledger. */
    @Test
    void afterTheAssignment_aLogCutBackToBeforeIt_isFound() {
        anEarlierRegister();
        findings().assign(KEY, "shop");
        fixture.registers.log(KEY, "shop").dropLast(1);

        Findings.Verified verified = findings().verified(KEY);

        assertThat(verified.report().intact()).isFalse();
        assertThat(verified.report().problems()).anySatisfy(problem -> assertThat(problem)
                .contains("seq 10: the ledger anchors this line").contains("the log ends at seq 9"));
    }

    /** Lines the ledger never anchored are taken as they are, and the assignment records that, as every write does. */
    @Test
    void assign_recordsTheLinesNoAnchorCoveredBeforeIt() {
        fixture.registers.putFromBefore(KEY, EarlierRegister.lines(), EarlierRegister.blobs(), EarlierRegister.state());
        fixture.ledger.anchorEarlier(KEY, 5, EventChain.sha256Hex(EarlierRegister.lines().get(5)));

        findings().assign(KEY, "shop");

        assertThat(events("shop")).extracting(RegisterEvent::type).endsWith(RegisterEvents.UNANCHORED,
                RegisterEvents.ASSIGNED);
        assertThat(findings().verified(KEY).report().intact()).isTrue();
    }

    // ---- what is refused ----

    @Test
    void assign_isRefused_forARegisterThatIsNotFromBefore_andForAKeyWithoutARegister() {
        fixture.commands.on("./report", "# Report\n\n<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,"
                + "\"findings\":[]}\n-->\n");
        run("shop");
        int lines = events("shop").size();

        assertThatThrownBy(() -> findings().assign(KEY, "shop")).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("is not from before registers had projects");
        assertThatThrownBy(() -> findings().assign(KEY, "web")).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("is not from before registers had projects");
        assertThatThrownBy(() -> findings().assign("nothing", "shop")).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("No register 'nothing'");

        assertThat(events("shop")).hasSize(lines);
        assertThat(fixture.registers.projects(KEY)).containsExactly("shop");
        assertThat(fixture.registers.keys()).containsExactly(KEY);
    }

    /**
     * Reproduced 2026-11-21: an assign on a project's register whose log ended in a fragment was refused ("nothing
     * to assign"), and had appended {@code register.repaired} before it decided so. That there is nothing to
     * assign is decided from a read: a refused assign writes nothing into a project's register.
     */
    @Test
    void aRefusedAssign_onAProjectsRegisterWithATornTail_writesNothingIntoIt() {
        fixture.commands.on("./report", "# Report\n\n<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,"
                + "\"findings\":[]}\n-->\n");
        run("shop");
        InMemoryEventLog log = fixture.registers.log(KEY, "shop");
        byte[] fragment = "{\"seq\":9,\"type\":\"fin".getBytes(StandardCharsets.UTF_8);
        log.tearTail(fragment);
        List<byte[]> lines = log.readAll();
        int anchors = fixture.ledger.registerAnchors(KEY, "shop").size();

        for (String project : List.of("shop", "web")) {
            assertThatThrownBy(() -> findings().assign(KEY, project)).as(project)
                    .isInstanceOf(RunRefusedException.class)
                    .hasMessageContaining("is not from before registers had projects, so there is nothing to"
                            + " assign: it is kept for shop");
        }

        assertThat(log.readAll()).as("no line, also no register.repaired")
                .usingElementComparator(java.util.Arrays::compare).isEqualTo(lines);
        assertThat(log.tail()).as("the fragment is where it was").isEqualTo(fragment);
        assertThat(fixture.ledger.registerAnchors(KEY, "shop")).hasSize(anchors);
        assertThat(fixture.registers.projects(KEY)).containsExactly("shop");
    }

    @Test
    void assign_isRefused_whenTheProjectHasAFolderAlready_andChangesNothing() {
        fixture.commands.on("./report", "# Report\n\n<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,"
                + "\"findings\":[]}\n-->\n");
        run("shop");
        anEarlierRegister();

        assertThatThrownBy(() -> findings().assign(KEY, "shop")).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("the project shop has a register of arch already");

        assertThat(fixture.registers.unassigned(KEY)).isTrue();
        assertThat(fixture.registers.logFromBefore(KEY).readAll()).as("no line was added")
                .hasSize(EarlierRegister.lines().size());
        assertThat(fixture.ledger.registerAnchors(KEY, "web")).isEmpty();
        findings().assign(KEY, "web");
        assertThat(fixture.registers.projects(KEY)).containsExactly("shop", "web");
    }

    @Test
    void assign_isRefused_forAKeyThatIsNoProjectKey() {
        anEarlierRegister();

        for (String bad : List.of("..", "a/b", "Shop", "")) {
            assertThatThrownBy(() -> findings().assign(KEY, bad)).as(bad).isInstanceOf(RunRefusedException.class)
                    .hasMessageContaining("project key");
        }
        assertThatThrownBy(() -> findings().assign(KEY, null)).isInstanceOf(RunRefusedException.class);
        assertThat(fixture.registers.logFromBefore(KEY).readAll()).hasSize(EarlierRegister.lines().size());
    }

    /** A project may have any valid key, also the name of something a register folder holds. */
    @Test
    void assign_takesAnyProjectKey_alsoTheNameOfWhatARegisterFolderHolds() {
        for (String project : List.of("blobs", "events.jsonl", "state.json", "report.html", "register.lock")) {
            AppFixture each = new AppFixture();
            each.registers.putFromBefore(KEY, EarlierRegister.lines(), EarlierRegister.blobs(), EarlierRegister.state());

            each.services().findings().assign(KEY, project);

            assertThat(each.registers.projects(KEY)).as(project).containsExactly(project);
            assertThat(each.services().findings().state(KEY).project()).isEqualTo(project);
        }
    }

    // ---- an assignment that was interrupted is finished by the same command ----

    private long assignedLines(List<byte[]> lines) {
        return lines.stream().map(RegisterEvent::parse).filter(e -> RegisterEvents.ASSIGNED.equals(e.type())).count();
    }

    @Test
    void anAssignmentCutOffAfterItsLine_isFinishedByTheSameCommand_andTheLineIsThereOnce() {
        anEarlierRegister();
        fixture.registers.failStateWrites(new IllegalStateException("disk full"));

        assertThatThrownBy(() -> findings().assign(KEY, "shop")).hasMessageContaining("disk full");

        assertThat(fixture.registers.unassigned(KEY)).as("until it is finished, it is a register from before").isTrue();
        assertThat(assignedLines(fixture.registers.logFromBefore(KEY).readAll())).isEqualTo(1);
        assertRefused(() -> findings().state(KEY));
        assertThatThrownBy(() -> run("shop")).isInstanceOf(RunRefusedException.class).hasMessageContaining(HINT);
        assertThatThrownBy(() -> findings().assign(KEY, "web")).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("was begun for the project shop")
                .hasMessageContaining("'traceworks findings arch assign --project shop'");

        fixture.registers.failStateWrites(null);
        findings().assign(KEY, "shop");

        assertThat(fixture.registers.unassigned(KEY)).isFalse();
        assertThat(assignedLines(fixture.registers.log(KEY, "shop").readAll())).as("nothing twice").isEqualTo(1);
        assertThat(events("shop")).hasSize(EarlierRegister.lines().size() + 1);
        assertThat(findings().verified(KEY).report().intact()).isTrue();
        assertThat(findings().verified(KEY).anchor()).isEqualTo("ok (seq 10 is the head the ledger holds, 3 anchors)");
    }

    @Test
    void anAssignmentCutOffBeforeTheMove_isFinishedByTheSameCommand() {
        anEarlierRegister();
        fixture.registers.failMoves(new IllegalStateException("cannot move"));

        assertThatThrownBy(() -> findings().assign(KEY, "shop")).hasMessageContaining("cannot move");

        assertThat(fixture.registers.unassigned(KEY)).isTrue();
        assertThat(fixture.registers.projects(KEY)).isEmpty();
        assertThat(fixture.ledger.registerAnchors(KEY, "shop")).as("no anchor before the register is in its place")
                .isEmpty();
        assertRefused(() -> findings().state(KEY));

        fixture.registers.failMoves(null);
        findings().assign(KEY, "shop");

        assertThat(fixture.registers.projects(KEY)).containsExactly("shop");
        assertThat(assignedLines(fixture.registers.log(KEY, "shop").readAll())).isEqualTo(1);
        assertThat(findings().verified(KEY).anchor()).isEqualTo("ok (seq 10 is the head the ledger holds, 3 anchors)");
    }

    /** The register is in its place and the ledger could not take the anchor: the same command anchors it. */
    @Test
    void anAssignmentTheLedgerCouldNotAnchor_isKept_andTheSameCommandAnchorsIt_once() {
        anEarlierRegister();
        fixture.ledger.failAnchors(new IllegalStateException("git commit failed"));
        Findings first = findings();

        first.assign(KEY, "shop");

        assertThat(first.unanchored()).contains("git commit failed");
        assertThat(fixture.registers.unassigned(KEY)).isFalse();
        assertThat(fixture.registers.projects(KEY)).containsExactly("shop");
        assertThat(findings().verified(KEY).anchor()).startsWith("ok up to seq 9; seq 10 is not anchored");

        fixture.ledger.failAnchors(null);
        Findings second = findings();
        second.assign(KEY, "shop");

        assertThat(second.unanchored()).isEmpty();
        assertThat(events("shop")).as("no line was added to finish it").hasSize(EarlierRegister.lines().size() + 1);
        assertThat(findings().verified(KEY).anchor()).isEqualTo("ok (seq 10 is the head the ledger holds, 3 anchors)");
        assertThatThrownBy(() -> findings().assign(KEY, "shop")).as("finished: nothing is left to do")
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("is not from before registers had projects");
        assertThat(fixture.ledger.registerAnchors(KEY, "shop")).hasSize(1);
    }

    /**
     * After the move of an assignment and before its anchor, the register is that of its project and usable: it
     * does not stay refused. A decision is taken, and says that it found the line of the assignment without an
     * anchor ({@code register.unanchored}).
     */
    @Test
    void afterTheMoveAndBeforeTheAnchor_theRegisterIsUsable_andItsNextWriteSaysUnanchored() {
        anEarlierRegister();
        fixture.ledger.failAnchors(new IllegalStateException("git commit failed"));
        findings().assign(KEY, "shop");
        fixture.ledger.failAnchors(null);
        assertThat(fixture.ledger.registerAnchors(KEY, "shop")).as("moved, and not anchored").isEmpty();
        String open = findings().state(KEY).findings().stream().filter(f -> f.status() == FindingStatus.OPEN)
                .findFirst().orElseThrow().id();
        Findings deciding = findings();

        deciding.dismiss(KEY, open, "not ours");

        assertThat(events("shop")).extracting(RegisterEvent::type).endsWith(RegisterEvents.ASSIGNED,
                RegisterEvents.UNANCHORED, RegisterEvents.FINDING_DISMISSED);
        RegisterEvent unanchored = events("shop").get(EarlierRegister.lines().size() + 1);
        assertThat(unanchored.fields().path("first_seq").asInt()).as("the line of the assignment").isEqualTo(10);
        assertThat(unanchored.fields().path("last_seq").asInt()).isEqualTo(10);
        assertThat(deciding.anchorNote()).isPresent();
        assertThat(deciding.anchorNote().get()).contains("seq 10 was not anchored before this write")
                .contains(RegisterEvents.UNANCHORED);
        assertThat(deciding.unanchored()).as("the decision itself is anchored").isEmpty();
        assertThat(findings().state(KEY).finding(open).status()).isEqualTo(FindingStatus.DISMISSED);
        Findings.Verified verified = findings().verified(KEY);
        assertThat(verified.report().intact()).as(verified.report().problems().toString()).isTrue();
        assertThat(verified.anchor()).startsWith("ok (seq 12 is the head the ledger holds");
        assertThat(run("shop").register().applied()).as("and a run of the project is not refused").isTrue();
    }

    // ---- verify works on a register from before, and assign judges the register before it stamps it ----

    /** The one command besides assign that works on a register from before: it says what is wrong with it. */
    @Test
    void verify_worksOnARegisterFromBefore_againstTheAnchorsOfTheEarlierShape_andWritesNothing() {
        anEarlierRegister();

        Findings.Verified verified = findings().verified(KEY);

        assertThat(verified.report().intact()).as(verified.report().problems().toString()).isTrue();
        assertThat(verified.report().events()).isEqualTo(EarlierRegister.lines().size());
        assertThat(verified.anchor()).isEqualTo("ok (seq 9 is the head the ledger holds, 2 anchors)");
        assertThat(fixture.registers.unassigned(KEY)).isTrue();
        assertThat(fixture.registers.logFromBefore(KEY).readAll()).hasSize(EarlierRegister.lines().size());
    }

    /** What is wrong with the register from before, in the three ways that were found. */
    private String damage(String how) {
        InMemoryEventLog log = fixture.registers.logFromBefore(KEY);
        List<byte[]> lines = log.readAll();
        switch (how) {
            case "a changed line" -> {
                log.dropLast(lines.size() - 4);
                log.append(new String(lines.get(4), StandardCharsets.UTF_8).replace("\"seq\":4", "\"seq\":4,\"x\":1")
                        .getBytes(StandardCharsets.UTF_8));
                lines.subList(5, lines.size()).forEach(log::append);
                return "seq 5: prev does not match the hash of seq 4";
            }
            case "a line that is none" -> {
                log.dropLast(1);
                log.append("not a line".getBytes(StandardCharsets.UTF_8));
                return "line 9 (after seq 8)";
            }
            default -> {
                String blob = EarlierRegister.blobs().keySet().iterator().next();
                fixture.registers.blobsFromBefore(KEY).delete(blob);
                return blob + " is deleted";
            }
        }
    }

    @Test
    void assign_isRefused_forARegisterThatDoesNotVerify_saysWhatIsWrong_andWritesNothing() {
        for (String how : List.of("a changed line", "a line that is none", "a missing blob")) {
            fixture.registers.putFromBefore(KEY, EarlierRegister.lines(), EarlierRegister.blobs(),
                    EarlierRegister.state());
            String problem = damage(how);
            List<byte[]> before = fixture.registers.logFromBefore(KEY).readAll();
            // A torn tail too: the writer that is refused must not even record that it cut it
            fixture.registers.logFromBefore(KEY).tearTail("{\"seq\":".getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(() -> findings().assign(KEY, "shop")).as(how)
                    .isInstanceOf(RecordDamagedException.class)
                    .hasMessageContaining("The register 'arch' does not verify, so it is not assigned")
                    .hasMessageContaining(problem)
                    .hasMessageContaining("'traceworks findings arch verify' says what is wrong");

            assertThat(fixture.registers.unassigned(KEY)).as(how).isTrue();
            assertThat(fixture.registers.projects(KEY)).as(how).isEmpty();
            assertThat(fixture.registers.logFromBefore(KEY).readAll()).as(how + ": no line, also no"
                    + " register.repaired").usingElementComparator(java.util.Arrays::compare).isEqualTo(before);
            assertThat(fixture.registers.logFromBefore(KEY).tail()).as(how + ": the torn tail is as it was")
                    .isNotEmpty();
            assertThat(fixture.registers.stateFromBefore(KEY)).as(how).isEqualTo(EarlierRegister.state());
            assertThat(fixture.ledger.registerAnchors(KEY, "shop")).as(how).isEmpty();
            Findings.Verified verified = findings().verified(KEY);
            assertThat(verified.report().intact()).as(how + ": verify is the command that says it").isFalse();
            assertThat(verified.report().problems()).as(how).anySatisfy(said -> assertThat(said).contains(problem));
        }
    }

    /** The log of a project's register, put where a register from before lies: it names a project already. */
    @Test
    void assign_isRefused_forALogInTheFolderOfTheKeyThatNamesAProject() {
        fixture.commands.on("./report", "# Report\n\n<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,"
                + "\"findings\":[]}\n-->\n");
        run("shop");
        List<byte[]> ofShop = fixture.registers.log(KEY, "shop").readAll();
        java.util.Map<String, byte[]> blobs = new java.util.LinkedHashMap<>();
        fixture.registers.blobs(KEY).all().forEach(blob -> blobs.put(EventChain.sha256Hex(blob), blob));
        fixture.registers.putFromBefore(KEY, ofShop, blobs, fixture.registers.stateFile(KEY, "shop"));

        assertThatThrownBy(() -> findings().assign(KEY, "web")).isInstanceOf(RecordDamagedException.class)
                .hasMessageContaining("The register 'arch' does not verify, so it is not assigned")
                .hasMessageContaining("the log of the register arch names the project shop")
                .hasMessageContaining("is no register from before registers had projects");

        assertThat(fixture.registers.logFromBefore(KEY).readAll()).as("no line was added").hasSize(ofShop.size());
        assertThat(fixture.registers.projects(KEY)).containsExactly("shop");
        assertThat(findings().verified(KEY).report().problems()).anySatisfy(problem -> assertThat(problem)
                .contains("names the project shop"));
    }

    /** Any assignment that is refused leaves the log as it was, also on a log with a torn tail. */
    @Test
    void aRefusedAssign_onALogWithATornTail_recordsNoRepair() {
        fixture.commands.on("./report", "# Report\n\n<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,"
                + "\"findings\":[]}\n-->\n");
        run("shop");
        anEarlierRegister();
        fixture.registers.logFromBefore(KEY).tearTail("{\"seq\":".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> findings().assign(KEY, "shop")).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("the project shop has a register of arch already");

        assertThat(fixture.registers.logFromBefore(KEY).readAll()).as("no register.repaired")
                .hasSize(EarlierRegister.lines().size());
        assertThat(fixture.registers.logFromBefore(KEY).tail()).isNotEmpty();

        findings().assign(KEY, "web");

        assertThat(events("web")).as("the assignment that is made cuts the tail and says so, and that no anchor"
                + " covers that note").extracting(RegisterEvent::type).endsWith(RegisterEvents.REPAIRED,
                RegisterEvents.UNANCHORED, RegisterEvents.ASSIGNED);
        assertThat(findings().ofProject("web").verified(KEY).report().intact()).isTrue();
    }

    /** The note of an assignment is printed when the register has its project: the hint must name it. */
    @Test
    void theHintOfAnAssignment_namesTheProject_inAFormThatWorks() {
        fixture.registers.putFromBefore(KEY, EarlierRegister.lines(), EarlierRegister.blobs(), EarlierRegister.state());
        fixture.ledger.anchorEarlier(KEY, EarlierRegister.lastSeq(), "0".repeat(64));
        Findings findings = findings();

        findings.assign(KEY, "shop");

        assertThat(findings.anchorNote()).isPresent();
        assertThat(findings.anchorNote().get()).contains("the register does not match its anchors in the ledger,"
                + " and it is assigned as it is: 'traceworks findings arch verify --project shop' says where, and"
                + " goes on reporting it");
        assertThat(findings.verified(KEY).report().intact()).as("it does go on reporting it").isFalse();
    }

    /** An assignment that was cut off after its line is verified as what it is, and finished all the same. */
    @Test
    void anAssignmentCutOffAfterItsLine_verifiesAsARegisterFromBefore_andIsFinished() {
        anEarlierRegister();
        fixture.registers.failStateWrites(new IllegalStateException("disk full"));
        assertThatThrownBy(() -> findings().assign(KEY, "shop")).hasMessageContaining("disk full");
        fixture.registers.failStateWrites(null);

        Findings.Verified verified = findings().verified(KEY);

        assertThat(verified.report().problems()).as("said as what it is, with the command that finishes it, and not"
                + " as a state file that was edited").singleElement().asString()
                .isEqualTo("the assignment of the register arch to the project shop was begun (register.assigned at"
                        + " seq 10) and not finished: 'traceworks findings arch assign --project shop' finishes it");
        assertThat(verified.chainHolds()).isTrue();
        assertThat(findings().assignmentBegun(KEY)).contains("shop");
        assertThat(fixture.services().ledgerReport().problems()).as("the check of the home says the same")
                .singleElement().satisfies(problem -> assertThat(problem.text()).startsWith("the assignment of the"
                        + " register arch to the project shop was begun"));
        findings().assign(KEY, "shop");
        assertThat(findings().verified(KEY).report().intact()).isTrue();
        assertThat(findings().assignmentBegun(KEY)).isEmpty();
    }

    /** The state file of an assignment that was cut off is the one from before its line, or it was edited. */
    @Test
    void anAssignmentCutOffAfterItsLine_stillSaysAStateFileThatWasEdited() {
        anEarlierRegister();
        assertThat(findings().assignmentBegun(KEY)).as("none was begun").isEmpty();
        fixture.registers.failStateWrites(new IllegalStateException("disk full"));
        assertThatThrownBy(() -> findings().assign(KEY, "shop")).hasMessageContaining("disk full");
        fixture.registers.failStateWrites(null);
        List<byte[]> withItsLine = fixture.registers.logFromBefore(KEY).readAll();
        fixture.registers.putFromBefore(KEY, withItsLine, EarlierRegister.blobs(),
                "{\"edited\":1}".getBytes(StandardCharsets.UTF_8));

        Findings.Verified verified = findings().verified(KEY);

        assertThat(verified.report().problems()).hasSize(2)
                .anySatisfy(problem -> assertThat(problem).startsWith("state.json differs from the log"))
                .anySatisfy(problem -> assertThat(problem).startsWith("the assignment of the register arch to the"
                        + " project shop was begun"));
    }
}
