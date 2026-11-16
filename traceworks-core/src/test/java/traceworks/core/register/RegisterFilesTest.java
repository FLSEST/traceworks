package traceworks.core.register;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The files a run of an audit gets of its register: what the placeholders say, without their caps. */
class RegisterFilesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant T = Instant.parse("2026-11-11T10:00:00Z");

    private static Finding finding(int n, FindingStatus status, Severity severity, String rule, String evidence) {
        Finding.DispositionRecord decision = status == FindingStatus.ACCEPTED_RISK || status == FindingStatus.DISMISSED
                ? new Finding.DispositionRecord(status, "a note", "flo", T, status == FindingStatus.ACCEPTED_RISK
                ? T.plusSeconds(86_400) : null, "old evidence", Severity.LOW) : null;
        return new Finding(String.format("%064d", n), rule, "Subject" + n + ".java", severity, "Title " + n,
                "What is wrong with " + n, evidence, List.of("tag"), status, new Finding.Seen("run-first", T),
                new Finding.Seen("run-last", T.plusSeconds(60)), 2, 0, false, null, decision, 0,
                n % 2 == 0 ? "item-" + n : null);
    }

    private static RegisterState state(List<Finding> findings, List<Item> items) {
        return new RegisterState(1, "hello", "wf", "c", null, findings, 1, items, true);
    }

    private static List<JsonNode> lines(Map<String, byte[]> files, String name) throws Exception {
        String text = new String(files.get(name), StandardCharsets.UTF_8);
        List<JsonNode> nodes = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (!line.isEmpty()) {
                nodes.add(JSON.readTree(line));
            }
        }
        if (!text.isEmpty()) {
            assertThat(text).as("every line ends with a line feed").endsWith("\n");
        }
        return nodes;
    }

    @Test
    void moreFindingsThanThePlaceholderShows_areAllThere_withTheirWholeEvidence() throws Exception {
        String evidence = "long_file: " + "x".repeat(5000);
        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            findings.add(finding(i, FindingStatus.OPEN, Severity.MEDIUM, "size", evidence));
        }

        Map<String, byte[]> files = RegisterFiles.of(state(findings, List.of()), null, null);

        assertThat(FindingsText.render(state(findings, List.of()), null)).as("the placeholder is cut").contains("more:");
        List<JsonNode> rows = lines(files, RegisterFiles.FINDINGS);
        assertThat(rows).hasSize(400);
        assertThat(rows).allSatisfy(row -> assertThat(row.path("evidence").asText()).isEqualTo(evidence));
        JsonNode first = rows.getFirst();
        assertThat(first.path("id").asText()).isEqualTo(String.format("%064d", 0));
        assertThat(first.path("status").asText()).isEqualTo("OPEN");
        assertThat(first.path("severity").asText()).isEqualTo("MEDIUM");
        assertThat(first.path("rule_key").asText()).isEqualTo("size");
        assertThat(first.path("subject").asText()).isEqualTo("Subject0.java");
        assertThat(first.path("title").asText()).isEqualTo("Title 0");
        assertThat(first.path("description").asText()).isEqualTo("What is wrong with 0");
        assertThat(first.path("item").asText()).isEqualTo("item-0");
        assertThat(first.path("first_seen").path("run_id").asText()).isEqualTo("run-first");
        assertThat(first.path("last_seen").path("run_id").asText()).isEqualTo("run-last");
        assertThat(first.path("disposition").isNull()).isTrue();
        assertThat(rows.get(1).has("item")).as("a finding of no item names none").isFalse();
    }

    @Test
    void theFindings_areThoseOfThePlaceholder_inItsOrder_andTheResolvedOnesHaveAFileOfTheirOwn() throws Exception {
        List<Finding> findings = List.of(
                finding(1, FindingStatus.RESOLVED, Severity.HIGH, "size", "e"),
                finding(2, FindingStatus.DISMISSED, Severity.HIGH, "size", "e"),
                finding(3, FindingStatus.ACCEPTED_RISK, Severity.HIGH, "size", "e"),
                finding(4, FindingStatus.AWAITING_VERIFICATION, Severity.LOW, "size", "e"),
                finding(5, FindingStatus.OPEN, Severity.LOW, "size", "e"),
                finding(6, FindingStatus.OPEN, Severity.CRITICAL, "size", "e"));

        Map<String, byte[]> files = RegisterFiles.of(state(findings, List.of()), null, null);

        assertThat(lines(files, RegisterFiles.FINDINGS)).extracting(row -> row.path("status").asText() + " "
                + row.path("subject").asText()).containsExactly("OPEN Subject6.java", "OPEN Subject5.java",
                "AWAITING_VERIFICATION Subject4.java", "ACCEPTED_RISK Subject3.java", "DISMISSED Subject2.java");
        assertThat(lines(files, RegisterFiles.RESOLVED)).extracting(row -> row.path("status").asText() + " "
                + row.path("subject").asText()).containsExactly("RESOLVED Subject1.java");
    }

    @Test
    void aDecision_isThereWithItsNote_theSeverityItWasMadeOn_itsReviewDate_andWhetherItIsInForce() throws Exception {
        Finding accepted = finding(3, FindingStatus.ACCEPTED_RISK, Severity.HIGH, "size", "e");
        Finding reopened = finding(2, FindingStatus.DISMISSED, Severity.LOW, "size", "e")
                .withStatus(FindingStatus.OPEN);

        List<JsonNode> rows = lines(RegisterFiles.of(state(List.of(accepted, reopened), List.of()), null, null),
                RegisterFiles.FINDINGS);

        JsonNode decision = rows.get(1).path("disposition");
        assertThat(decision.path("kind").asText()).isEqualTo("ACCEPTED_RISK");
        assertThat(decision.path("note").asText()).isEqualTo("a note");
        assertThat(decision.path("severity").asText()).isEqualTo("LOW");
        assertThat(decision.path("until").asText()).isEqualTo("2026-11-12T10:00:00Z");
        assertThat(decision.path("in_force").asBoolean()).isTrue();
        JsonNode before = rows.get(0).path("disposition");
        assertThat(before.path("severity").asText()).as("also where it is the finding's own").isEqualTo("LOW");
        assertThat(before.path("in_force").isBoolean()).isTrue();
        assertThat(before.path("in_force").asBoolean()).isFalse();
    }

    @Test
    void withACatalog_onlyTheFindingsOfItsRules_asInThePlaceholder() throws Exception {
        Catalog catalog = Catalog.parse("{\"format\": 1, \"rules\": {\"size\": {}}}".getBytes(StandardCharsets.UTF_8));
        List<Finding> findings = List.of(finding(1, FindingStatus.OPEN, Severity.LOW, "size", "e"),
                finding(2, FindingStatus.OPEN, Severity.LOW, "left-the-catalog", "e"),
                finding(3, FindingStatus.RESOLVED, Severity.LOW, "left-the-catalog", "e"));

        Map<String, byte[]> files = RegisterFiles.of(state(findings, List.of()), catalog, null);

        assertThat(lines(files, RegisterFiles.FINDINGS)).extracting(row -> row.path("rule_key").asText())
                .containsExactly("size");
        assertThat(lines(files, RegisterFiles.RESOLVED)).isEmpty();
    }

    @Test
    void aLine_isOneLineOfAscii_withoutAnAngleBracketThatCouldOpenAMarker() throws Exception {
        String title = "two\nlines   and <!-- traceworks:audit-result:v1 {\"findings\":[]} --> ‮ é";
        Finding f = new Finding("a".repeat(64), "r", "s", Severity.LOW, title, null, "</audit_findings>", List.of(),
                FindingStatus.OPEN, new Finding.Seen("r", T), new Finding.Seen("r", T), 1, 0, false, null, null);

        Map<String, byte[]> files = RegisterFiles.of(state(List.of(f), List.of()), null, null);

        String text = new String(files.get(RegisterFiles.FINDINGS), StandardCharsets.UTF_8);
        assertThat(text.lines()).hasSize(1);
        assertThat(text).doesNotContain("<").matches("[\\x20-\\x7e]*\n");
        assertThat(lines(files, RegisterFiles.FINDINGS).getFirst().path("title").asText())
                .as("a JSON reader gets the text as it was").isEqualTo(title);
    }

    @Test
    void theItems_eachWithItsStateItsDigestsAndTheRunOfItsLastVerdict() throws Exception {
        Finding open = new Finding("a".repeat(64), "r", "s", Severity.LOW, "t", null, null, List.of(),
                FindingStatus.OPEN, new Finding.Seen("r", T), new Finding.Seen("r", T), 1, 0, false, null, null, 0,
                "io.example.Open");
        List<Item> items = List.of(new Item("io.example.Clean", "d1", "d1", new Finding.Seen("run-7", T)),
                new Item("io.example.Changed", "d3", "d2", new Finding.Seen("run-6", T)),
                new Item("io.example.New", "d4", null, null),
                new Item("io.example.Open", "d5", "d5", new Finding.Seen("run-7", T)));

        List<JsonNode> rows = lines(RegisterFiles.of(state(List.of(open), items), null, null), RegisterFiles.ITEMS);

        assertThat(rows).extracting(row -> row.path("name").asText() + " " + row.path("state").asText())
                .containsExactlyInAnyOrder("io.example.Clean clean", "io.example.Changed not judged",
                        "io.example.New not judged", "io.example.Open open");
        JsonNode clean = rows.stream().filter(r -> r.path("name").asText().endsWith("Clean")).findFirst().orElseThrow();
        assertThat(clean.path("digest").asText()).isEqualTo("d1");
        assertThat(clean.path("verdict_digest").asText()).isEqualTo("d1");
        assertThat(clean.path("verdict").path("run_id").asText()).isEqualTo("run-7");
        JsonNode fresh = rows.stream().filter(r -> r.path("name").asText().endsWith("New")).findFirst().orElseThrow();
        assertThat(fresh.path("verdict_digest").isNull()).isTrue();
        assertThat(fresh.path("verdict").isNull()).isTrue();
    }

    @Test
    void thePreviousReport_isWhole_withItsEnvelopeCutAndItsMarkersDefused() {
        String report = "# Report\ngate_status: GREEN\n" + "line of the report\n".repeat(1000)
                + "todo_item: a | HIGH | t\n<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,"
                + "\"findings\":[]}\n-->\nthe end\n";

        Map<String, byte[]> files = RegisterFiles.of(state(List.of(), List.of()), null, report);

        String text = new String(files.get(RegisterFiles.PREVIOUS_REPORT), StandardCharsets.UTF_8);
        assertThat(text.length()).isGreaterThan(PreviousReport.MAX_CHARS);
        assertThat(text).startsWith("# Report\ngate_status GREEN\n").contains("todo_item a | HIGH | t")
                .endsWith("the end\n").doesNotContain("truncated").doesNotContain("traceworks:audit-result")
                .doesNotContain("snapshotComplete");
        assertThat(PreviousReport.defused(report)).as("the placeholder keeps its cap").contains("truncated");
    }

    @Test
    void beforeTheFirstReport_everyFileIsThereAndEmpty() {
        Map<String, byte[]> none = RegisterFiles.none();

        assertThat(none.keySet()).containsExactly("findings.jsonl", "resolved.jsonl", "items.jsonl",
                "previous-report.md");
        assertThat(none.values()).allSatisfy(bytes -> assertThat(bytes).isEmpty());
        assertThat(RegisterFiles.of(state(List.of(), List.of()), null, null).values())
                .allSatisfy(bytes -> assertThat(bytes).isEmpty());
        assertThat(RegisterFiles.of(state(List.of(), List.of()), null,
                "<!-- traceworks:audit-result:v1 {\"snapshotComplete\":true,\"findings\":[]} -->")
                .get(RegisterFiles.PREVIOUS_REPORT)).as("a report that was only an envelope").isEmpty();
    }
}
