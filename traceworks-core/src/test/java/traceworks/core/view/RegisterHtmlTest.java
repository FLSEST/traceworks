package traceworks.core.view;

import traceworks.core.record.ChainReport;
import traceworks.core.register.Finding;
import traceworks.core.register.FindingStatus;
import traceworks.core.register.RegisterEvent;
import traceworks.core.register.RegisterState;
import traceworks.core.register.Severity;
import traceworks.core.testing.InMemoryBlobStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** report.html of a register: the findings by status, the history, the verification line; escaped, deterministic. */
class RegisterHtmlTest {

    private static final Instant T = Instant.parse("2026-11-11T10:00:00Z");

    private final InMemoryBlobStore blobs = new InMemoryBlobStore();

    private static Finding finding(String id, String subject, FindingStatus status, Finding.DispositionRecord d) {
        return new Finding(id.repeat(64), "size.class", subject, Severity.HIGH, "Big <b>" + subject, "desc", "340 LOC",
                List.of("arch"), status, new Finding.Seen("run-1", T), new Finding.Seen("run-2", T.plusSeconds(60)), 2,
                1, status == FindingStatus.OPEN, status == FindingStatus.RESOLVED ? T.plusSeconds(60) : null, d);
    }

    private RegisterState state() {
        Finding.DispositionRecord accepted = new Finding.DispositionRecord(FindingStatus.ACCEPTED_RISK, "known & bounded",
                "flo@box", T, T.plusSeconds(3600), "340 LOC");
        return new RegisterState(1, "arch", "wf-1", "architecture",
                new RegisterState.LastReport("run-2", T.plusSeconds(60), "c".repeat(64), true), List.of(
                finding("a", "A.java", FindingStatus.OPEN, null),
                finding("b", "B|C.java", FindingStatus.ACCEPTED_RISK, accepted),
                finding("c", "Old.java", FindingStatus.RESOLVED, null)), 2);
    }

    private static RegisterEvent event(int seq, String type, String json) {
        return RegisterEvent.parse(("{\"seq\":" + seq + ",\"at\":\"2026-11-11T10:00:00.000Z\",\"type\":\"" + type
                + "\",\"register\":\"arch\",\"prev\":\"p\"," + json + "}").getBytes(StandardCharsets.UTF_8));
    }

    private String render() {
        List<RegisterEvent> history = List.of(event(0, "register.created", "\"workflow_id\":\"wf-1\""),
                event(1, "report.applied", "\"run_id\":\"run-1\",\"counts\":{\"new\":2}"),
                event(2, "finding.accepted", "\"id\":\"" + "b".repeat(64) + "\",\"actor\":\"flo@box\",\"note\":\"known & bounded\""));
        return RegisterHtml.render(state(), history, blobs, new ChainReport(true, 3, "h".repeat(64), List.of(), List.of()),
                "1.2.3");
    }

    @Test
    void page_isDeterministic_andNamesTheRegister() {
        String page = render();

        assertThat(page).isEqualTo(render()).contains("<title>Register arch</title>", "<h1>arch <small>architecture</small></h1>",
                "<th>workflow</th><td>wf-1</td>", "<th>last report</th><td>run run-2 at 2026-11-11T10:01:00Z (applied)</td>",
                "OPEN 1", "ACCEPTED_RISK 1", "RESOLVED 1", "1.2.3");
    }

    @Test
    void header_namesTheReportsApplied_andCountsTheFindingsByStatus() {
        assertThat(render()).contains("<tr><th>reports applied</th><td>2</td></tr>",
                "<tr><th>findings</th><td>OPEN 1 · ACCEPTED_RISK 1 · RESOLVED 1</td></tr>");
    }

    /**
     * Each finding says what is true of it alone: only the finding whose evidence names no measured fact is marked
     * unverified, only a resolved one names when, and each shows its own counts and tags.
     */
    @Test
    void eachFinding_showsItsOwnMarks() {
        String page = render();
        int a = page.indexOf("id=\"" + "a".repeat(64) + "\"");
        int b = page.indexOf("id=\"" + "b".repeat(64) + "\"");
        int c = page.indexOf("id=\"" + "c".repeat(64) + "\"");

        assertThat(page).containsOnlyOnce("<span class=\"unverified\">");
        assertThat(page.indexOf("<span class=\"unverified\">")).isBetween(a, b);
        assertThat(page).containsOnlyOnce(", resolved at ");
        assertThat(page.indexOf(", resolved at 2026-11-11T10:01:00Z")).isGreaterThan(c);
        assertThat(page).contains("2 occurrences, 1 regression, tags arch</p>");
        assertThat(page).containsOnlyOnce("</details>");
        assertThat(page.indexOf("</details>")).isGreaterThan(page.indexOf("<details><summary>RESOLVED (1)</summary>"));
    }

    @Test
    void findings_areGroupedByStatus_withTheirFields_andResolvedCollapsed() {
        String page = render();

        assertThat(page.indexOf("<h2>OPEN (1)</h2>")).isLessThan(page.indexOf("<h2>ACCEPTED_RISK (1)</h2>"));
        assertThat(page).contains("<details><summary>RESOLVED (1)</summary>");
        assertThat(page).contains("<code>size.class</code>", "A.java", "Big &lt;b&gt;A.java", "340 LOC",
                "<span class=\"unverified\">unverified evidence</span>", "first seen run run-1", "last seen run run-2",
                "2 occurrences, 1 regression", "accepted by flo@box at 2026-11-11T10:00:00Z, review by 2026-11-11T11:00:00Z",
                "known &amp; bounded");
        assertThat(page).contains("B|C.java");
    }

    @Test
    void history_listsTheLastEvents_andTheVerificationLine() {
        String page = render();

        assertThat(page).contains("<h2>History</h2>", "report.applied", "run-1", "finding.accepted", "flo@box",
                "chain intact, 3 events, head " + "h".repeat(64));
    }

    /** A history line names the fields of its event that say what happened; an id is cut to its first 8 characters. */
    @Test
    void history_namesTheFieldsOfEachEvent_newestFirst_theOldestIncluded() {
        String page = render();

        assertThat(page).contains(
                "<tr><td>2</td><td>2026-11-11T10:00:00Z</td><td>finding.accepted</td><td>id bbbbbbbb · actor flo@box"
                        + " · note known &amp; bounded</td></tr>",
                "<tr><td>1</td><td>2026-11-11T10:00:00Z</td><td>report.applied</td><td>run_id run-1 · counts"
                        + " {&quot;new&quot;:2}</td></tr>",
                "<tr><td>0</td><td>2026-11-11T10:00:00Z</td><td>register.created</td><td>workflow_id wf-1</td></tr>");
        assertThat(page.indexOf("<tr><td>2</td>")).isLessThan(page.indexOf("<tr><td>0</td>"));
    }

    @Test
    void page_loadsNothingExternal_andEscapesHostileText() {
        String page = render();

        assertThat(page).contains("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\">")
                .doesNotContain("<b>A.java").doesNotContain("src=").doesNotContain("href=\"//");
    }

    @Test
    void anEmptyRegister_rendersWithoutFindings() {
        String page = RegisterHtml.render(RegisterState.empty("k", null, null), List.of(), blobs,
                new ChainReport(true, 0, "g", List.of(), List.of()), "1.2.3");

        assertThat(page).contains("<h1>k", "no findings yet");
    }

    @Test
    void aBrokenChain_isShownAsAProblem() {
        String page = RegisterHtml.render(state(), List.of(), blobs,
                new ChainReport(false, 2, "h", List.of("seq 1: prev does not match"), List.of()), "1.2.3");

        assertThat(page).contains("chain NOT intact", "seq 1: prev does not match");
    }

    @Test
    void items_areCountedAndListed_andAPageWithoutItemsSaysNothingAboutThem() {
        Finding open = new Finding("d".repeat(64), "gap", "p.Open#1", Severity.HIGH, "Gap", null, null, List.of(),
                FindingStatus.OPEN, new Finding.Seen("run-1", T), new Finding.Seen("run-1", T), 1, 0, false, null, null,
                0, "p.Open");
        RegisterState state = new RegisterState(1, "testing", "wf-1", "testing", null, List.of(open), 1, List.of(
                new traceworks.core.register.Item("p.Open", "aa", "aa", new Finding.Seen("run-1", T)),
                new traceworks.core.register.Item("p.<b>Clean", "bb", "bb", new Finding.Seen("run-1", T)),
                new traceworks.core.register.Item("p.Never", "cc", null, null)));

        String page = RegisterHtml.render(state, List.of(), blobs,
                new ChainReport(true, 0, "h".repeat(64), List.of(), List.of()), "1.2.3");

        assertThat(page).contains("<th>items</th><td>1 of 3 settled: 1 clean, 0 decided; 1 open, 1 not judged</td>",
                "<details><summary>Items (3)</summary>",
                "<tr><td>open</td><td>aa</td><td>p.Open</td><td>run-1</td></tr>",
                "<tr><td>clean</td><td>bb</td><td>p.&lt;b&gt;Clean</td><td>run-1</td></tr>",
                "<tr><td>not judged</td><td>-</td><td>p.Never</td><td>-</td></tr>");
        assertThat(page.indexOf(">p.Open<")).isLessThan(page.indexOf(">p.Never<"));
        assertThat(render()).doesNotContain("<th>items</th>").doesNotContain("Items (");
    }

    /** Who decided: the actor, then that it was through an agent. A decision without one reads as it always did. */
    @Test
    void aDecisionOfAnAgent_namesItAfterTheActor_inTheFindingAndInTheHistory() {
        Finding.DispositionRecord byAgent = new Finding.DispositionRecord(FindingStatus.DISMISSED, "not ours",
                "flo@box", T, null, null, Severity.HIGH, "claude-code");
        Finding.DispositionRecord byPerson = new Finding.DispositionRecord(FindingStatus.DISMISSED, "checked",
                "flo@box", T, null, null, Severity.HIGH);
        RegisterState state = new RegisterState(1, "arch", "wf-1", "architecture", null, List.of(
                finding("a", "A.java", FindingStatus.DISMISSED, byAgent),
                finding("b", "B.java", FindingStatus.DISMISSED, byPerson)), 2);
        List<RegisterEvent> history = List.of(event(0, "finding.dismissed", "\"id\":\"" + "a".repeat(64)
                + "\",\"actor\":\"flo@box\",\"agent\":\"claude-code\",\"note\":\"not ours\""));

        String page = RegisterHtml.render(state, history, blobs, new ChainReport(true, 1, "h".repeat(64), List.of(),
                List.of()), "1.2.3");

        assertThat(page).contains(
                "dismissed by flo@box through the agent claude-code at 2026-11-11T10:00:00Z, made on severity HIGH:"
                        + " not ours",
                "dismissed by flo@box at 2026-11-11T10:00:00Z, made on severity HIGH: checked",
                "<td>finding.dismissed</td><td>id aaaaaaaa · actor flo@box · agent claude-code · note"
                        + " not ours</td>");
    }

    /** The name is checked where it is given; a state that was edited can hold any, and the page shows it as text. */
    @Test
    void theNameOfAnAgentFromAnEditedState_isEscaped_andItsDirectionMarkIsShownByItsCodePoint() {
        Finding.DispositionRecord hostile = new Finding.DispositionRecord(FindingStatus.DISMISSED, "n", "flo@box", T,
                null, null, Severity.HIGH, "<script>x</script>‮");
        RegisterState state = new RegisterState(1, "arch", "wf-1", "architecture", null, List.of(
                finding("a", "A.java", FindingStatus.DISMISSED, hostile)), 1);

        String page = RegisterHtml.render(state, List.of(), blobs, new ChainReport(true, 0, "h".repeat(64), List.of(),
                List.of()), "1.2.3");

        assertThat(page).contains("through the agent &lt;script&gt;x&lt;/script&gt;[U+202E] at ")
                .doesNotContain("<script>x").doesNotContain("‮");
    }

    /** A decision covers the severity it was made on; where the finding has another one, the page says so. */
    @Test
    void aDecisionMadeOnAnotherSeverity_saysSo_andOneMadeOnTheSameNamesIt() {
        Finding.DispositionRecord onLow = new Finding.DispositionRecord(FindingStatus.DISMISSED, "false positive",
                "flo@box", T, null, null, Severity.LOW);
        Finding.DispositionRecord onHigh = new Finding.DispositionRecord(FindingStatus.DISMISSED, "not ours",
                "flo@box", T, null, null, Severity.HIGH);
        RegisterState state = new RegisterState(1, "arch", "wf-1", "architecture", null, List.of(
                finding("a", "A.java", FindingStatus.DISMISSED, onLow),
                finding("b", "B.java", FindingStatus.DISMISSED, onHigh)), 2);

        String page = RegisterHtml.render(state, List.of(), blobs, new ChainReport(true, 0, "h".repeat(64), List.of(),
                List.of()), "1.2.3");

        assertThat(page).contains("dismissed by flo@box at 2026-11-11T10:00:00Z, made on severity LOW, not on the HIGH"
                        + " the finding has now: false positive",
                "dismissed by flo@box at 2026-11-11T10:00:00Z, made on severity HIGH: not ours");
    }
}
