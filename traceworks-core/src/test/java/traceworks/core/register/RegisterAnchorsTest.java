package traceworks.core.register;

import traceworks.core.port.RegisterAnchor;
import traceworks.core.record.EventChain;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The comparison of a register's log with the anchors the ledger holds: each anchor must still name its line, and
 * the lines after the last one are unproven.
 */
class RegisterAnchorsTest {

    private static final List<byte[]> LOG = List.of(line(0), line(1), line(2), line(3));

    private static byte[] line(int seq) {
        return ("{\"seq\":" + seq + "}").getBytes(StandardCharsets.UTF_8);
    }

    private static RegisterAnchor anchorOf(int seq) {
        return new RegisterAnchor(seq, EventChain.sha256Hex(line(seq)));
    }

    @Test
    void anAnchorOnTheFirstLine_ofALogOfOneLine_holds() {
        RegisterAnchors.Check check = RegisterAnchors.check(List.of(line(0)), List.of(anchorOf(0)), List.of());

        assertThat(check.problems()).isEmpty();
        assertThat(check.summary()).isEqualTo("ok (seq 0 is the head the ledger holds, 1 anchors)");
    }

    @Test
    void anAnchorBeyondTheEnd_namesTheCutAndTheHashItHolds() {
        RegisterAnchor beyond = anchorOf(5);

        assertThat(RegisterAnchors.check(LOG, List.of(anchorOf(1), beyond), List.of()).problems()).containsExactly(
                "seq 5: the ledger anchors this line (" + beyond.head().substring(0, 12) + "), but the log ends at"
                        + " seq 3, so lines were cut from its end");
        assertThat(RegisterAnchors.check(List.of(line(0)), List.of(anchorOf(1)), List.of()).problems()).containsExactly(
                "seq 1: the ledger anchors this line (" + anchorOf(1).head().substring(0, 12) + "), but the log ends at"
                        + " seq 0, so lines were cut from its end");
        assertThat(RegisterAnchors.check(List.of(), List.of(anchorOf(0)), List.of()).problems()).containsExactly(
                "seq 0: the ledger anchors this line (" + anchorOf(0).head().substring(0, 12) + "), but the log is"
                        + " empty, so lines were cut from its end");
        assertThat(RegisterAnchors.check(LOG, List.of(beyond), List.of()).summary()).isEqualTo("NOT ok");
    }

    @Test
    void anAnchorWithoutAHead_isAProblem_notACrash() {
        assertThat(RegisterAnchors.check(LOG, List.of(new RegisterAnchor(2, null)), List.of()).problems()).containsExactly(
                "seq 2: the line is not the one the ledger anchors (?), so it was changed after it was written, or the"
                        + " log was cut and written on");
    }

    @Test
    void oneLineAfterTheLastAnchor_isNamedInTheSingular() {
        assertThat(RegisterAnchors.check(LOG, List.of(anchorOf(1), anchorOf(2)), List.of()).summary()).isEqualTo(
                "ok up to seq 2; seq 3 is not anchored, so it is unproven: the ledger could not be written when it"
                        + " was, or it was appended without Traceworks, and nothing tells the two apart. The next"
                        + " write takes it as it is, anchors the head and records which lines it found without an"
                        + " anchor (register.unanchored)");
    }

    @Test
    void severalLinesAfterTheLastAnchor_areNamedInThePlural() {
        assertThat(RegisterAnchors.check(LOG, List.of(anchorOf(1)), List.of()).summary()).isEqualTo(
                "ok up to seq 1; seq 2 to 3 are not anchored, so they are unproven: the ledger could not be written"
                        + " when they were, or they were appended without Traceworks, and nothing tells the two apart."
                        + " The next write takes them as they are, anchors the head and records which lines it found"
                        + " without an anchor (register.unanchored)");
    }

    // ---- what an anchor says was written: derived from the lines it covers, and from nothing else ----

    private static RegisterEvent event(int seq, String type, String fields) {
        return RegisterEvent.parse(("{\"seq\":" + seq + ",\"at\":\"2026-11-20T10:00:00.000Z\",\"type\":\"" + type
                + "\",\"register\":\"hello\",\"prev\":\"" + "0".repeat(64) + "\"" + (fields.isEmpty() ? "" : "," + fields)
                + "}").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theLinesOfAReport_giveTheCountOfEachType_sorted_andTheRun_andNoActor() {
        RegisterAnchor.Written written = RegisterAnchors.written(List.of(
                event(0, "register.created", "\"workflow_id\":\"wf\",\"category\":\"c\",\"project\":\"shop\""),
                event(1, "register.requires", "\"feature\":\"decision_severity\""),
                event(2, "report.applied", "\"run_id\":\"run-1\",\"run_head\":\"abc\""),
                event(3, "finding.new", "\"id\":\"f-1\",\"run_id\":\"run-1\",\"title\":\"Big\",\"severity\":\"LOW\""),
                event(4, "finding.new", "\"id\":\"f-2\",\"run_id\":\"run-1\",\"title\":\"Bigger\""),
                event(5, "finding.reopened", "\"id\":\"f-0\",\"run_id\":\"run-1\",\"reason\":\"reported again\"")));

        assertThat(written.lines()).containsExactly(java.util.Map.entry("finding.new", 2),
                java.util.Map.entry("finding.reopened", 1), java.util.Map.entry("register.created", 1),
                java.util.Map.entry("register.requires", 1), java.util.Map.entry("report.applied", 1));
        assertThat(written.runs()).containsExactly("run-1");
        assertThat(written.actors()).isEmpty();
        assertThat(written.agents()).isEmpty();
        assertThat(written.decided()).as("a report that reopens a finding is no decision of a person").isEmpty();
    }

    private static final String F1 = "1".repeat(64);
    private static final String F2 = "2".repeat(64);

    @Test
    void theLinesOfDecisions_giveTheActors_theAgents_andTheFindingsDecided_eachSortedAndOnce() {
        RegisterAnchor.Written written = RegisterAnchors.written(List.of(
                event(7, "finding.dismissed", "\"id\":\"" + F2 + "\",\"note\":\"n\",\"actor\":\"operator@host\","
                        + "\"agent\":\"claude-code\",\"severity\":\"LOW\",\"evidence\":\"e\""),
                event(8, "finding.accepted", "\"id\":\"" + F1 + "\",\"note\":\"n\",\"actor\":\"ann@host\""),
                event(9, "finding.reopened", "\"id\":\"" + F2 + "\",\"note\":null,\"actor\":\"operator@host\","
                        + "\"agent\":\"codex\",\"reason\":\"manual\""),
                event(10, "register.assigned", "\"project\":\"shop\",\"actor\":\"zoe@host\"")));

        assertThat(written.lines()).containsExactly(java.util.Map.entry("finding.accepted", 1),
                java.util.Map.entry("finding.dismissed", 1), java.util.Map.entry("finding.reopened", 1),
                java.util.Map.entry("register.assigned", 1));
        assertThat(written.runs()).isEmpty();
        assertThat(written.actors()).containsExactly("ann@host", "operator@host", "zoe@host");
        assertThat(written.agents()).containsExactly("claude-code", "codex");
        assertThat(written.decided()).containsExactly(F1, F2);
        assertThat(RegisterAnchors.shaped(written)).isTrue();
    }

    /**
     * Reproduced 2026-11-21: a hand-appended {@code report.rejected} with a free text as its {@code run_id}, and a
     * {@code finding.dismissed} with free texts as actor and agent, put those texts into the anchor the ledger
     * committed. A value in an anchor has a shape, and one of another shape gives a fixed marker in its place.
     */
    @Test
    void aValueOfAnotherShape_givesTheMarkerInItsPlace_soNoFreeTextReachesAnAnchor() {
        String text = "Mallory was here, and says so in a line nobody else wrote";
        RegisterAnchor.Written written = RegisterAnchors.written(List.of(
                event(3, "report.rejected", "\"run_id\":\"" + text + "\",\"reason\":\"r\""),
                event(4, "report.applied", "\"run_id\":\"20261120-101500-ab12\""),
                event(5, "finding.dismissed", "\"id\":\"" + F1 + "\",\"actor\":\"" + text + "\\u0007\",\"agent\":\""
                        + "<b>" + text + "</b>\""),
                event(6, "finding.dismissed", "\"id\":\"" + text + "\",\"actor\":\"operator@host\",\"agent\":"
                        + "\"claude-code\""),
                event(7, "finding.accepted", "\"id\":\"" + F2 + "\",\"actor\":\"" + "a".repeat(256) + "\""),
                event(8, text, "\"run_id\":\"run\\nwith a line break\"")));

        assertThat(written.runs()).containsExactly(RegisterAnchors.MALFORMED, "20261120-101500-ab12");
        assertThat(written.actors()).containsExactly(RegisterAnchors.MALFORMED, "operator@host");
        assertThat(written.agents()).containsExactly(RegisterAnchors.MALFORMED, "claude-code");
        assertThat(written.decided()).containsExactly(RegisterAnchors.MALFORMED, F1, F2);
        assertThat(written.lines()).containsExactly(java.util.Map.entry(RegisterAnchors.MALFORMED, 1),
                java.util.Map.entry("finding.accepted", 1), java.util.Map.entry("finding.dismissed", 2),
                java.util.Map.entry("report.applied", 1), java.util.Map.entry("report.rejected", 1));
        assertThat(written.toString()).doesNotContain("Mallory");
        assertThat(RegisterAnchors.shaped(written)).as("the marker is a value an anchor may hold").isTrue();
    }

    /** What the reader of the ledger asks: whether every value of what an anchor says has the shape of its field. */
    @Test
    void whatAnAnchorSays_isShaped_onlyWhenEveryValueHasTheShapeOfItsField() {
        java.util.SortedMap<String, Integer> lines = new java.util.TreeMap<>(java.util.Map.of("report.applied", 1));
        assertThat(RegisterAnchors.shaped(new RegisterAnchor.Written(lines, List.of("run-1"), List.of("operator@host"),
                List.of("claude-code"), List.of(F1)))).isTrue();
        for (RegisterAnchor.Written other : List.of(
                new RegisterAnchor.Written(lines, List.of("a run"), List.of(), List.of(), List.of()),
                new RegisterAnchor.Written(lines, List.of(), List.of("operator\nthe host"), List.of(), List.of()),
                new RegisterAnchor.Written(lines, List.of(), List.of(""), List.of(), List.of()),
                new RegisterAnchor.Written(lines, List.of(), List.of("a".repeat(256)), List.of(), List.of()),
                new RegisterAnchor.Written(lines, List.of(), List.of(), List.of("Zoë"), List.of()),
                new RegisterAnchor.Written(lines, List.of(), List.of(), List.of("two  spaces"), List.of()),
                new RegisterAnchor.Written(lines, List.of(), List.of(), List.of(), List.of("f-1")),
                new RegisterAnchor.Written(new java.util.TreeMap<>(java.util.Map.of("some text", 1)), List.of(),
                        List.of(), List.of(), List.of()))) {
            assertThat(RegisterAnchors.shaped(other)).as(other.toString()).isFalse();
        }
    }

    @Test
    void linesOfSeveralRuns_giveEveryRun_sorted_andNoLine_givesNothing() {
        assertThat(RegisterAnchors.written(List.of(
                event(3, "report.rejected", "\"run_id\":\"run-b\",\"reason\":\"r\""),
                event(4, "hook.ran", "\"run_id\":\"run-b\",\"exit_code\":0"),
                event(5, "report.applied", "\"run_id\":\"run-a\""))).runs()).containsExactly("run-a", "run-b");

        RegisterAnchor.Written nothing = RegisterAnchors.written(List.of());
        assertThat(nothing.lines()).isEmpty();
        assertThat(nothing.runs()).isEmpty();
        assertThat(nothing.actors()).isEmpty();
        assertThat(nothing.agents()).isEmpty();
        assertThat(nothing.decided()).isEmpty();
    }

    // ---- the check of what an anchor of a project says ----

    private static final List<RegisterEvent> EVENTS = List.of(
            event(0, "register.created", "\"workflow_id\":\"wf\",\"category\":\"c\""),
            event(1, "report.applied", "\"run_id\":\"run-1\""),
            event(2, "register.assigned", "\"project\":\"shop\",\"actor\":\"operator@host\""),
            event(3, "finding.dismissed", "\"id\":\"f-1\",\"note\":\"n\",\"actor\":\"operator@host\""));
    private static final List<byte[]> EVENT_LINES = EVENTS.stream().map(RegisterEvent::raw).toList();

    /** The anchor of the line {@code seq} that says what the lines from {@code from} on are. */
    private static RegisterAnchor saying(int from, int seq) {
        return new RegisterAnchor(seq, EventChain.sha256Hex(EVENT_LINES.get(seq)),
                RegisterAnchors.written(EVENTS.subList(from, seq + 1)));
    }

    /** The anchors an earlier version wrote come before those of the project: they are the anchor before the first. */
    @Test
    void theFirstAnchorOfAProject_coversTheLinesSinceTheLastAnchorOfTheEarlierShape_orAllLinesWithoutOne() {
        RegisterAnchor earlier = new RegisterAnchor(1, EventChain.sha256Hex(EVENT_LINES.get(1)));

        RegisterAnchors.Check check = RegisterAnchors.check(EVENT_LINES, List.of(earlier),
                List.of(saying(2, 2), saying(3, 3)));

        assertThat(check.problems()).isEmpty();
        assertThat(check.summary()).isEqualTo("ok (seq 3 is the head the ledger holds, 3 anchors)");

        assertThat(RegisterAnchors.check(EVENT_LINES, List.of(), List.of(saying(2, 2), saying(3, 3))).problems())
                .containsExactly("seq 2: the anchor in the ledger says lines register.assigned 1, and the lines it"
                                + " covers (seq 0 to 2) give register.assigned 1, register.created 1, report.applied 1",
                        "seq 2: the anchor in the ledger says run none, and the lines it covers (seq 0 to 2) give"
                                + " run run-1");
        assertThat(RegisterAnchors.check(EVENT_LINES, List.of(), List.of(saying(0, 2), saying(3, 3))).problems())
                .isEmpty();
    }

    /** An anchor of the earlier shape says nothing of what was written, and that is no problem where it lies. */
    @Test
    void anAnchorOfTheEarlierShape_isCheckedByItsLineAlone() {
        RegisterAnchor earlier = new RegisterAnchor(3, EventChain.sha256Hex(EVENT_LINES.get(3)));

        assertThat(RegisterAnchors.check(EVENT_LINES, List.of(earlier), List.of()).problems()).isEmpty();
        assertThat(RegisterAnchors.check(EVENT_LINES, List.of(), List.of(earlier)).problems()).containsExactly(
                "seq 3: the anchor in the ledger does not say what was written (it has no lines), and this version"
                        + " writes no such anchor for the register of a project");
        // also when its line is not the one in the log, or lies beyond its end: it is no anchor of this version
        assertThat(RegisterAnchors.check(EVENT_LINES, List.of(), List.of(new RegisterAnchor(3, "f".repeat(64))))
                .problems()).hasSize(2).anySatisfy(problem -> assertThat(problem)
                .startsWith("seq 3: the line is not the one the ledger anchors"))
                .anySatisfy(problem -> assertThat(problem).contains("does not say what was written"));
        assertThat(RegisterAnchors.check(EVENT_LINES, List.of(), List.of(new RegisterAnchor(9, "f".repeat(64))))
                .problems()).hasSize(2).anySatisfy(problem -> assertThat(problem)
                .contains("does not say what was written"));
    }

    /** The lines an anchor covers are those of the log only while its own line is: else that is what is reported. */
    @Test
    void anAnchorWhoseLineIsNotTheOneInTheLog_isReportedForThat_andNotAlsoForWhatItSays() {
        RegisterAnchor other = new RegisterAnchor(3, "f".repeat(64), RegisterAnchors.written(List.of()));
        RegisterAnchor beyond = new RegisterAnchor(9, "f".repeat(64), RegisterAnchors.written(List.of()));

        assertThat(RegisterAnchors.check(EVENT_LINES, List.of(), List.of(saying(0, 2), other)).problems())
                .singleElement().asString().startsWith("seq 3: the line is not the one the ledger anchors");
        assertThat(RegisterAnchors.check(EVENT_LINES, List.of(), List.of(saying(0, 2), beyond)).problems())
                .singleElement().asString().startsWith("seq 9: the ledger anchors this line");
    }

    /** An anchor at or before the one before it covers no line: a log that was cut and written on. */
    @Test
    void anAnchorThatDoesNotReachBeyondTheOneBeforeIt_coversNoLine() {
        RegisterAnchor again = new RegisterAnchor(2, EventChain.sha256Hex(EVENT_LINES.get(2)),
                RegisterAnchors.written(List.of()));

        assertThat(RegisterAnchors.check(EVENT_LINES, List.of(), List.of(saying(0, 3), again)).problems()).isEmpty();
        assertThat(RegisterAnchors.check(EVENT_LINES, List.of(), List.of(saying(0, 3), saying(2, 2))).problems())
                .containsExactly("seq 2: the anchor in the ledger says lines register.assigned 1, and the lines it"
                                + " covers (no line) give none",
                        "seq 2: the anchor in the ledger says actor operator@host, and the lines it covers (no line) give"
                                + " actor none");
    }

    /**
     * Reproduced 2026-11-21: the actor {@code Zoë Brontë <zoe@example.org>} stood in the anchor as the marker.
     * An actor is the operator's own text, as in the start entry of a run: 1 to 255 characters, none a control
     * character. An agent keeps the bounds of an agent name.
     */
    @Test
    void anActor_hasTheShapeOfAnActor_oneTo255CharactersAndNoControlCharacter_andAnAgentKeepsItsBounds() {
        String zoe = "Zoë Brontë <zoe@example.org>";
        RegisterAnchor.Written written = RegisterAnchors.written(List.of(
                event(5, "finding.dismissed", "\"id\":\"" + F1 + "\",\"actor\":\"" + zoe + "\""),
                event(6, "finding.dismissed", "\"id\":\"" + F2 + "\",\"actor\":\"" + "a".repeat(255) + "\","
                        + "\"agent\":\"Zoë\"")));

        assertThat(written.actors()).containsExactly(zoe, "a".repeat(255));
        assertThat(written.agents()).as("an agent is a name within its bounds").containsExactly(
                RegisterAnchors.MALFORMED);
        assertThat(RegisterAnchors.shaped(written)).isTrue();

        for (String none : List.of("", "a".repeat(256), "operator\\nhost", "operator\\u0007", "\\u009b[2J")) {
            assertThat(RegisterAnchors.written(List.of(event(5, "finding.dismissed", "\"id\":\"" + F1
                    + "\",\"actor\":\"" + none + "\""))).actors()).as(none).containsExactly(RegisterAnchors.MALFORMED);
        }
    }

    // ---- an anchor covers the lines since the last anchor the log still agrees with ----

    /**
     * Reproduced 2026-11-21: a log that was cut and written on, or replaced, was anchored with no line at all
     * ({@code "lines":{}}), since the anchor before reached as far as the new one. An anchor the log no longer
     * agrees with says nothing of the lines that are there: the lines since the last one it agrees with are covered.
     */
    @Test
    void theLastAnchorTheLogAgreesWith_isTheLastOneWhoseLineIsStillInTheLog() {
        RegisterAnchor one = new RegisterAnchor(1, EventChain.sha256Hex(EVENT_LINES.get(1)));
        RegisterAnchor other = new RegisterAnchor(2, "f".repeat(64));
        RegisterAnchor beyond = new RegisterAnchor(9, "e".repeat(64));

        assertThat(RegisterAnchors.agreed(EVENT_LINES, List.of(one, other, beyond))).isEqualTo(one);
        assertThat(RegisterAnchors.agreed(EVENT_LINES, List.of(other, beyond))).as("none: all lines").isNull();
        assertThat(RegisterAnchors.agreed(EVENT_LINES, List.of())).isNull();
        assertThat(RegisterAnchors.agreed(EVENT_LINES, List.of(one, saying(2, 2), other))).isEqualTo(saying(2, 2));
    }

    @Test
    void anAnchorAfterOneTheLogNoLongerAgreesWith_coversTheLinesSinceTheLastOneItAgreesWith() {
        RegisterAnchor earlier = new RegisterAnchor(1, EventChain.sha256Hex(EVENT_LINES.get(1)));
        // The log was cut after seq 1 and written on: the ledger still holds the anchors of the lines that were
        RegisterAnchor was2 = new RegisterAnchor(2, "f".repeat(64));
        RegisterAnchor was3 = new RegisterAnchor(3, "e".repeat(64), RegisterAnchors.written(List.of()));

        RegisterAnchors.Check check = RegisterAnchors.check(EVENT_LINES, List.of(earlier, was2),
                List.of(was3, saying(2, 3)));

        assertThat(check.problems()).as("the anchors of the lines that are gone, and nothing about what the last"
                + " one says").hasSize(2).allSatisfy(problem -> assertThat(problem)
                .contains("the line is not the one the ledger anchors"));
        assertThat(RegisterAnchors.check(EVENT_LINES, List.of(earlier, was2), List.of(was3, saying(3, 3)))
                .problems()).as("an anchor that says only the last line leaves out what it covers").anySatisfy(
                problem -> assertThat(problem).contains("seq 3: the anchor in the ledger says lines"
                        + " finding.dismissed 1, and the lines it covers (seq 2 to 3) give finding.dismissed 1,"
                        + " register.assigned 1"));
    }

    /** A covered line that is no line of a register cannot be counted: said once, and the chain names the line. */
    @Test
    void aCoveredLineThatIsNoLine_isSaid_notACrash() {
        List<byte[]> lines = new java.util.ArrayList<>(EVENT_LINES);
        lines.set(1, "not a line".getBytes(StandardCharsets.UTF_8));

        assertThat(RegisterAnchors.check(lines, List.of(), List.of(saying(0, 2))).problems()).containsExactly(
                "seq 2: what the anchor in the ledger says was written cannot be compared: a line it covers (seq 0 to"
                        + " 2) is no line of a register");
    }
}
