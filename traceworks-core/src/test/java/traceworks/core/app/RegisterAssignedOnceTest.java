package traceworks.core.app;

import org.junit.jupiter.api.Test;
import traceworks.core.port.Ledger;
import traceworks.core.port.RegisterAnchor;
import traceworks.core.port.RegisterStore;
import traceworks.core.record.EventChain;
import traceworks.core.register.FindingStatus;
import traceworks.core.register.RegisterEvent;
import traceworks.core.register.RegisterEvents;
import traceworks.core.register.RegisterLog;
import traceworks.core.testing.EarlierRegister;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One register from before registers had projects is assigned once. Reproduced 2026-11-21: the register was
 * assigned to one project, a backup of the earlier folder was put back at the key and assigned to a second one;
 * two registers held the same findings and decisions, and every {@code verify} held. A second assignment is
 * refused, by the log of the project that has it and by the anchors of the ledger; the one exception is the
 * project it was assigned to, once that project's folder is lost; and both {@code verify} commands report a key of
 * which two project logs say that they were assigned.
 */
class RegisterAssignedOnceTest {

    private static final String KEY = EarlierRegister.KEY;

    private final AppFixture fixture = new AppFixture();

    /** The register as an earlier version left it at the key, with the anchors of that version in the ledger. */
    private void anEarlierRegister() {
        fixture.registers.putFromBefore(KEY, EarlierRegister.lines(), EarlierRegister.blobs(), EarlierRegister.state());
        if (fixture.ledger.earlierRegisterAnchors(KEY).isEmpty()) {
            fixture.ledger.anchorEarlier(KEY, EarlierRegister.lastSeq(), EarlierRegister.head());
        }
    }

    private Findings findings() {
        return fixture.services().findings();
    }

    private List<byte[]> lines(String project) {
        try (RegisterStore.Opened opened = fixture.registers.open(KEY, project)) {
            return opened.events().readAll();
        }
    }

    private void assertNothingWasAssigned(String project) {
        assertThat(fixture.registers.unassigned(KEY)).as("the folder at the key is as it was").isTrue();
        assertThat(fixture.registers.logFromBefore(KEY).readAll()).as("no line was added")
                .usingElementComparator(java.util.Arrays::compare).isEqualTo(EarlierRegister.lines());
        assertThat(fixture.registers.stateFromBefore(KEY)).isEqualTo(EarlierRegister.state());
        assertThat(fixture.registers.projects(KEY)).doesNotContain(project);
        assertThat(fixture.ledger.registerAnchors(KEY, project)).isEmpty();
    }

    // ---- refused ----

    /** The earlier folder put back and assigned to a second project: refused by the log of the first. */
    @Test
    void theEarlierFolderPutBack_isNotAssignedToASecondProject_whileTheLogOfTheFirstSaysItWasAssigned() {
        anEarlierRegister();
        findings().assign(KEY, "shop");
        List<byte[]> ofShop = lines("shop");
        anEarlierRegister();

        assertThatThrownBy(() -> findings().assign(KEY, "portal")).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("The register 'arch' cannot be assigned to portal")
                .hasMessageContaining("was assigned to the project shop already")
                .hasMessageContaining("the log of the register arch of shop holds register.assigned")
                .hasMessageContaining("what lies at the key is a copy of what was assigned: remove it");

        assertNothingWasAssigned("portal");
        assertThat(lines("shop")).as("the register of shop is as it was")
                .usingElementComparator(java.util.Arrays::compare).isEqualTo(ofShop);
        assertThat(fixture.ledger.registerAnchors(KEY, "shop")).hasSize(1);
    }

    /** The folder of the first project is lost too: the anchors of the ledger still say that it was assigned. */
    @Test
    void theEarlierFolderPutBack_isNotAssignedToASecondProject_whileTheLedgerAnchorsTheFirstAssignment() {
        anEarlierRegister();
        findings().assign(KEY, "shop");
        fixture.registers.remove(KEY, "shop");
        anEarlierRegister();

        assertThatThrownBy(() -> findings().assign(KEY, "portal")).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("The register 'arch' cannot be assigned to portal")
                .hasMessageContaining("was assigned to the project shop already")
                .hasMessageContaining("the ledger anchors that assignment")
                .hasMessageContaining("what lies at the key is a copy of what was assigned: remove it");

        assertNothingWasAssigned("portal");
    }

    /** The same project again, while its register is there: what lies at the key is the copy. */
    @Test
    void theEarlierFolderPutBack_isNotAssignedToTheSameProject_whileThatProjectHasItsRegister() {
        anEarlierRegister();
        findings().assign(KEY, "shop");
        int lines = lines("shop").size();
        anEarlierRegister();

        assertThatThrownBy(() -> findings().assign(KEY, "shop")).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("was assigned to the project shop already")
                .hasMessageContaining("remove it");

        assertThat(fixture.registers.unassigned(KEY)).isTrue();
        assertThat(fixture.registers.logFromBefore(KEY).readAll()).hasSize(EarlierRegister.lines().size());
        assertThat(lines("shop")).as("the register of shop is as it was").hasSize(lines);
        assertThat(fixture.ledger.registerAnchors(KEY, "shop")).hasSize(1);
    }

    /** What cannot be asked is not taken as "not assigned": a sibling whose log cannot be read, a ledger likewise. */
    @Test
    void anAssignmentIsRefused_whileASiblingOrTheLedgerCannotSayWhetherTheRegisterWasAssigned() {
        anEarlierRegister();
        fixture.registers.failReads(KEY, "zz", new IllegalStateException("Cannot read the log of zz"));

        assertThatThrownBy(() -> findings().assign(KEY, "portal")).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("The register 'arch' cannot be assigned to portal")
                .hasMessageContaining("the log of the register arch of zz cannot be read (Cannot read the log of zz)")
                .hasMessageContaining("whether the register from before registers had projects was assigned");
        assertNothingWasAssigned("portal");

        fixture.registers.remove(KEY, "zz");
        fixture.ledger.failAnchorReads(new IllegalStateException("its HEAD cannot be read"));

        assertThatThrownBy(() -> findings().assign(KEY, "portal")).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("The register 'arch' cannot be assigned to portal")
                .hasMessageContaining("the ledger cannot say (its HEAD cannot be read)")
                .hasMessageContaining("whether the register from before registers had projects was assigned");
        fixture.ledger.failAnchorReads(null);
        assertNothingWasAssigned("portal");
    }

    // ---- the one exception ----

    /**
     * Reproduced 2026-11-21: a backup put back after the project's folder was lost, assigned to the same project
     * again. That assignment is taken; the log then lacks what the ledger anchors for the project, and the
     * assignment says so. (It said nothing, and its anchor said {@code "lines":{}}.)
     */
    @Test
    void theEarlierFolderPutBack_isAssignedToTheSameProjectAgain_onceThatProjectsFolderIsLost_andSaysTheMismatch() {
        anEarlierRegister();
        findings().assign(KEY, "shop");
        String open = findings().state(KEY).findings().stream().filter(f -> f.status() == FindingStatus.OPEN)
                .findFirst().orElseThrow().id();
        findings().dismiss(KEY, open, "not ours");
        assertThat(fixture.ledger.registerAnchors(KEY, "shop")).hasSize(2);
        fixture.registers.remove(KEY, "shop");
        anEarlierRegister();
        Findings again = findings();

        again.assign(KEY, "shop");

        assertThat(fixture.registers.unassigned(KEY)).isFalse();
        assertThat(fixture.registers.projects(KEY)).containsExactly("shop");
        assertThat(again.anchorNote()).as("the note of an assignment whose log does not match its anchors")
                .isPresent();
        assertThat(again.anchorNote().get()).contains("the register does not match its anchors in the ledger, and it"
                + " is assigned as it is: 'traceworks findings arch verify --project shop' says where, and goes on"
                + " reporting it");
        List<byte[]> lines = lines("shop");
        RegisterEvent assigned = RegisterEvent.parse(lines.getLast());
        assertThat(assigned.type()).isEqualTo(RegisterEvents.ASSIGNED);
        RegisterAnchor last = fixture.ledger.registerAnchors(KEY, "shop").getLast();
        assertThat(fixture.ledger.registerAnchors(KEY, "shop")).hasSize(3);
        assertThat(last.seq()).isEqualTo(assigned.seq());
        assertThat(last.head()).isEqualTo(EventChain.sha256Hex(lines.getLast()));
        assertThat(last.written().lines()).as("the anchor says what was written").containsExactly(
                Map.entry("register.assigned", 1));
        assertThat(last.written().actors()).containsExactly(AppFixture.ACTOR);
        Findings.Verified verified = findings().verified(KEY);
        assertThat(verified.report().intact()).as("verify goes on reporting it").isFalse();
        assertThat(verified.report().problems()).anySatisfy(problem -> assertThat(problem)
                .contains("seq " + assigned.seq() + ": the line is not the one the ledger anchors"))
                .anySatisfy(problem -> assertThat(problem).contains("seq " + (assigned.seq() + 1)
                        + ": the ledger anchors this line").contains("lines were cut from its end"));
    }

    // ---- verify ----

    /** Appends {@code register.assigned} to the register at the key and moves it, as an earlier build let a second assignment. */
    private void assignedByHand(String project) {
        try (RegisterStore.Opened opened = fixture.registers.openUnassignedForWrite(KEY)) {
            Map<String, Object> fields = new java.util.LinkedHashMap<>();
            fields.put("project", project);
            fields.put("actor", AppFixture.ACTOR);
            RegisterLog log = RegisterLog.forWriting(opened, fixture.clock);
            log.append(RegisterEvents.ASSIGNED, fields);
            RegisterWrites.writeDerived(opened, log, traceworks.core.register.RegisterReplay.replay(KEY, project,
                    log.read(), opened.blobs()));
            opened.assignTo(project);
            RegisterWrites.anchor(fixture.ledger, opened, log);
        }
    }

    /** Two project logs of one key that both say they were assigned: reported at both, each naming the other. */
    @Test
    void twoProjectLogsThatBothHoldRegisterAssigned_areReportedAtBothProjects_namingTheOther() {
        anEarlierRegister();
        findings().assign(KEY, "shop");
        anEarlierRegister();
        assignedByHand("portal");

        Findings.Verified shop = findings().ofProject("shop").verified(KEY);
        Findings.Verified portal = findings().ofProject("portal").verified(KEY);

        assertThat(shop.report().intact()).isFalse();
        assertThat(shop.report().problems()).singleElement().asString()
                .contains("the log of the register arch of shop holds register.assigned, and so does that of the"
                        + " project portal")
                .contains("a register from before registers had projects is assigned once")
                .contains("'traceworks findings arch verify --project portal'");
        assertThat(shop.chainHolds()).as("the register holds by itself").isTrue();
        assertThat(portal.report().problems()).singleElement().asString()
                .contains("the log of the register arch of portal holds register.assigned, and so does that of the"
                        + " project shop");

        LedgerReport home = fixture.services().ledgerReport();
        assertThat(home.ok()).isFalse();
        for (String[] pair : new String[][] {{"shop", "portal"}, {"portal", "shop"}}) {
            assertThat(home.problems().stream().filter(problem -> problem.runId().equals(
                    Ledger.registerName(KEY, pair[0]))).map(LedgerReport.Problem::text).toList()).as(pair[0])
                    .singleElement().asString().contains("and so does that of the project " + pair[1]);
        }
    }

    /** One assigned register beside a register a run began is nothing to report. */
    @Test
    void oneAssignedRegister_isNoProblem() {
        anEarlierRegister();
        findings().assign(KEY, "shop");

        assertThat(findings().verified(KEY).report().problems()).isEmpty();
        assertThat(fixture.services().ledgerReport().problems()).isEmpty();
    }
}
