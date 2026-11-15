package traceworks.selfaudit.architecture;

import traceworks.selfaudit.architecture.Report.NotJudged;
import traceworks.selfaudit.architecture.Report.Outcome;
import traceworks.selfaudit.architecture.Report.Verdict;
import traceworks.core.register.AuditResultParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ReportTest {

    private static final String CODE = """
            1| package p;
            2| public class Big {
            3|     public void start() { run(); }
            4|     void helper() { }
            5| }""";

    private static String candidate(String id, boolean measured, String shown) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", id);
        json.put("file", "mod/src/main/java/p/" + id + ".java");
        json.put("reasons", List.of(Map.of("kind", "size", "text", "400 non-comment lines")));
        json.put("measured", measured);
        json.put("shown", shown);
        json.put("members", List.of(Map.of("name", "start", "label", "Big.start()"),
                Map.of("name", "helper", "label", "Big.helper()")));
        json.put("code", CODE);
        json.put("token", "tok");
        return Json.write(json);
    }

    private static Map<String, Object> facts() {
        Map<String, Object> facts = new HashMap<>();
        facts.put("javaFiles", 5L);
        facts.put("limits", Map.of("largeNcloc", 300L));
        facts.put("files", List.of(Map.of("file", "a.java", "ncloc", 400L, "lines", 500L, "maxComplexity", 3L)));
        return facts;
    }

    private static String verdicts(List<String> lines, String... answers) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < answers.length; i++) {
            if (answers[i] != null) out.append('[').append(lines.get(i)).append("]: ").append(answers[i]).append('\n');
        }
        return out.toString();
    }

    private static String fine(String id) {
        return "{\"candidate\":\"" + id + "\",\"verdict\":\"fine\",\"reason\":\"One job.\",\"evidence\":[{\"line\":3,"
                + "\"quote\":\"public void start() { run(); }\"}],\"members\":[],\"target\":\"\",\"purpose\":\"\","
                + "\"change\":\"\"}";
    }

    @Test
    void verdicts_areCheckedAgainstTheCodeAndTheMembers() {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 8; i++) lines.add(candidate(String.format("C%02d", i), i == 6, "whole"));

        Outcome outcome = Report.of(lines, facts(), verdicts(lines,
                "Here it is: ```json\n" + fine("C01") + "\n```",
                "{\"candidate\":\"C02\",\"verdict\":\"split\",\"severity\":\"medium\",\"reason\":\"Two jobs.\",\"evidence\":[{\"line\":\"4\","
                        + "\"quote\":\"void helper() { }\"}],\"members\":[\"Big.helper()\"],\"target\":\"p.Helper\"}",
                "{\"candidate\":\"C03\",\"verdict\":\"split\",\"severity\":\"medium\",\"evidence\":[{\"line\":4,\"quote\":\"void helper() { }\"}],"
                        + "\"members\":[\"gone\"],\"target\":\"p.Helper\"}",
                "{\"candidate\":\"C04\",\"verdict\":\"move\",\"severity\":\"medium\",\"evidence\":[{\"line\":3,\"quote\":\"start() { run();\"}],"
                        + "\"members\":[],\"target\":\"\"}",
                "{\"candidate\":\"C05\",\"verdict\":\"fine\",\"evidence\":[{\"line\":2,\"quote\":\"public class Small\"}]}",
                fine("C06"),
                "{\"candidate\":\"C01\",\"verdict\":\"fine\"}",
                null));

        assertThat(outcome.markdown()).contains("## Files of this run", "| `architecture-report.md` |",
                "| `architecture-facts.json` |", "| `candidates.jsonl` |");
        assertThat(outcome.markdown().indexOf("## Files of this run")).isLessThan(outcome.markdown().indexOf("## Coverage"));
        assertThat(outcome.verified()).extracting(Verdict::id, Verdict::verdict)
                .containsExactly(tuple("C01", "fine"), tuple("C02", "split"));
        assertThat(outcome.unverified()).extracting(Verdict::id, Verdict::problem).containsExactly(
                tuple("C03", "the member 'gone' is not in the file"),
                tuple("C04", "a move names no target"),
                tuple("C05", "the quote does not occur in line 2"),
                tuple("C06", "the file has a measured finding, and the verdict does not say what it is for and the "
                        + "smallest change that removes it"));
        assertThat(outcome.notJudged()).extracting(NotJudged::id, NotJudged::reason).containsExactly(
                tuple("C07", "the answer is for candidate C01"),
                tuple("C08", "no answer in the verdicts: the loop did not reach the candidate"));
        assertThat(outcome.gate()).isEqualTo("YELLOW");
        assertThat(outcome.description()).isEqualTo("no measured finding; 1 split and 0 move verified; 6 of 8 "
                + "candidates judged; 4 unverified verdicts; 1 of 8 items settled (1 clean, 0 decided; 1 open, 6 not judged)");
    }

    /**
     * A measured finding needs both what the dependency is for and the change that removes it, and a split needs a
     * member that moves: a verdict with one of them missing is not verified.
     */
    @Test
    void aMeasuredVerdictWithHalfItsAnswer_andASplitWithoutAMember_areUnverified() {
        List<String> lines = List.of(candidate("C01", true, "whole"), candidate("C02", true, "whole"),
                candidate("C03", false, "whole"));
        String measured = "{\"candidate\":\"%s\",\"verdict\":\"fine\",\"evidence\":[{\"line\":3,"
                + "\"quote\":\"public void start() { run(); }\"}],\"members\":[],\"target\":\"\",%s}";

        Outcome outcome = Report.of(lines, facts(), verdicts(lines,
                measured.formatted("C01", "\"purpose\":\"It logs.\",\"change\":\"\""),
                measured.formatted("C02", "\"purpose\":\"\",\"change\":\"Inline it.\""),
                "{\"candidate\":\"C03\",\"verdict\":\"split\",\"severity\":\"medium\",\"evidence\":[{\"line\":4,"
                        + "\"quote\":\"void helper() { }\"}],\"members\":[],\"target\":\"p.Helper\"}"));

        assertThat(outcome.verified()).isEmpty();
        assertThat(outcome.unverified()).extracting(Verdict::id, Verdict::problem).containsExactly(
                tuple("C01", "the file has a measured finding, and the verdict does not say what it is for and the "
                        + "smallest change that removes it"),
                tuple("C02", "the file has a measured finding, and the verdict does not say what it is for and the "
                        + "smallest change that removes it"),
                tuple("C03", "a split names no member that moves"));
    }

    @Test
    void aShortQuote_aLineOutsideTheCode_andACopiedMarker_doNotCount() {
        List<String> lines = List.of(candidate("C01", false, "whole"), candidate("C02", false, "whole"),
                candidate("C03", false, "whole"), candidate("C04", false, "whole"));
        String copied = "[" + lines.get(3) + "]: ";

        Outcome outcome = Report.of(lines, facts(), verdicts(lines,
                "{\"candidate\":\"C01\",\"verdict\":\"fine\",\"evidence\":[{\"line\":3,\"quote\":\"run();\"}]}",
                "{\"candidate\":\"C02\",\"verdict\":\"fine\",\"evidence\":[{\"line\":9,\"quote\":\"public class Big\"}]}",
                "{\"candidate\":\"C03\",\"verdict\":\"maybe\",\"evidence\":[]} " + copied,
                fine("C04")));

        assertThat(outcome.unverified()).extracting(Verdict::id, Verdict::problem).containsExactly(
                tuple("C01", "the quote for line 3 is shorter than 8 characters and not the whole line"),
                tuple("C02", "line 9 is not in the code the AI saw"),
                tuple("C03", "the verdict 'maybe' is not fine, split or move"));
        assertThat(outcome.notJudged()).extracting(NotJudged::id, NotJudged::reason)
                .containsExactly(tuple("C04", "its marker occurs 2 times in the verdicts: an answer copied it"));
    }

    @Test
    void theGate_isRedForAMeasuredRuleViolation_evenWithoutAnyVerdict() {
        List<String> lines = List.of(candidate("C01", true, "whole"));
        Map<String, Object> facts = facts();
        facts.put("layerViolations", List.of(Map.of("rule", "inner", "from", "port", "to", "app", "sites",
                List.of(Map.of("type", "port.Clock", "file", "Clock.java", "lines",
                        List.of(Map.of("line", 4L, "what", List.of("app.Service"))))))));

        Outcome outcome = Report.of(lines, facts, "the loop failed");

        assertThat(outcome.gate()).isEqualTo("RED");
        assertThat(outcome.description()).startsWith("1 layer violation; 0 split and 0 move verified; 0 of 1");
        assertThat(outcome.markdown()).contains("### Layer violation: `port` -> `app` (rule inner)")
                .contains("- `port.Clock` in `Clock.java`:\n  - line 4: `app.Service`");
    }

    @Test
    void theGate_isRedForForbiddenIo_yellowForACycle_andGreenWhenAllIsFine() {
        List<String> lines = List.of(candidate("C01", false, "whole"));
        String answers = verdicts(lines, fine("C01"));

        Map<String, Object> io = facts();
        io.put("io", List.of(Map.of("rule", "core_io", "type", "core.A", "file", "A.java", "allowed", false,
                "uses", List.of(Map.of("line", 3L, "what", List.of("java.nio.file.Files"))))));
        assertThat(Report.of(lines, io, answers).gate()).isEqualTo("RED");

        Map<String, Object> documented = facts();
        documented.put("io", List.of(Map.of("rule", "core_io", "type", "core.A", "file", "A.java", "allowed", true,
                "reason", "as documented", "uses", List.of())));
        assertThat(Report.of(lines, documented, answers).gate()).isEqualTo("GREEN");

        Map<String, Object> cycle = facts();
        cycle.put("cycles", List.of(Map.of("packages", List.of("a", "b"), "edges", List.of(),
                "weakest", Map.of("from", "a", "to", "b", "types", List.of("a.A")))));
        Outcome cycled = Report.of(lines, cycle, answers);
        assertThat(cycled.gate()).isEqualTo("YELLOW");
        assertThat(cycled.markdown()).contains("### Package cycle: `a+b`");

        assertThat(Report.of(List.of(candidate("C01", false, "part: lines 1-5 of 900")), facts(),
                verdicts(List.of(candidate("C01", false, "part: lines 1-5 of 900")), fine("C01"))).gate())
                .isEqualTo("YELLOW");
        Map<String, Object> cut = facts();
        cut.put("cut", List.of(Map.of("file", "x.java", "reasons", List.of("big"))));
        assertThat(Report.of(lines, cut, answers).gate()).isEqualTo("YELLOW");
        Map<String, Object> unresolved = facts();
        unresolved.put("unresolvedSourceNames", List.of("Clock"));
        assertThat(Report.of(lines, unresolved, answers).gate()).isEqualTo("YELLOW");
        Map<String, Object> none = facts();
        none.put("javaFiles", 0L);
        assertThat(Report.of(List.of(), none, "").gate()).isEqualTo("YELLOW");
        assertThat(Report.of(lines, facts(), answers).gate()).isEqualTo("GREEN");
    }

    @Test
    void modelText_isPlainInTheReport_andNamesAreCodeSpans() {
        List<String> lines = List.of(candidate("C01", false, "whole"));
        String answer = "{\"candidate\":\"C01\",\"verdict\":\"split\",\"severity\":\"medium\",\"reason\":\"# Heading\\n[link](x) <b>\","
                + "\"evidence\":[{\"line\":4,\"quote\":\"void helper() { }\"}],\"members\":[\"helper\"],"
                + "\"target\":\"p.`Helper`\"}";

        Outcome outcome = Report.of(lines, facts(), verdicts(lines, answer));

        assertThat(outcome.markdown()).contains("\\# Heading \\[link\\]\\(x\\) \\<b\\>")
                .contains("**Target:** `` p.`Helper` ``").contains("**Members that move:** `helper`")
                .doesNotContain("\n# Heading");
    }

    @Test
    void aMemberReference_meansItsSimpleName() {
        assertThat(Report.simpleName("RunServices#start(StartRequest)")).isEqualTo("start");
        assertThat(Report.simpleName(" Outer.Inner.run ")).isEqualTo("run");
        assertThat(Report.simpleName("new Big(String)")).isEqualTo("Big");
    }

    /** The envelope the register reads: the measured findings, then the verified split and move verdicts. */
    @Test
    void envelope_listsVerifiedVerdictsAndMeasuredFindings() {
        List<String> lines = List.of(candidate("C01", false, "whole"), candidate("C02", false, "whole"),
                candidate("C03", false, "whole"));
        Map<String, Object> facts = facts();
        facts.put("layerViolations", List.of(Map.of("rule", "inner", "from", "port", "to", "app", "sites",
                List.of(Map.of("type", "port.Clock", "file", "Clock.java", "lines",
                        List.of(Map.of("line", 4L, "what", List.of("app.Service"))))))));
        facts.put("io", List.of(
                Map.of("rule", "core_io", "type", "core.A", "file", "A.java", "allowed", false,
                        "uses", List.of(Map.of("line", 3L, "what", List.of("java.nio.file.Files")))),
                Map.of("rule", "core_io", "type", "core.B", "file", "B.java", "allowed", true, "reason", "documented",
                        "uses", List.of())));
        facts.put("cycles", List.of(Map.of("packages", List.of("a", "b"), "edges", List.of(),
                "weakest", Map.of("from", "a", "to", "b", "types", List.of("a.A")))));

        Outcome outcome = Report.of(lines, facts, verdicts(lines,
                fine("C01"),
                "{\"candidate\":\"C02\",\"verdict\":\"split\",\"severity\":\"medium\",\"reason\":\"Two jobs.\",\"evidence\":[{\"line\":4,"
                        + "\"quote\":\"void helper() { }\"},{\"line\":3,\"quote\":\"public void start() { run(); }\"}],"
                        + "\"members\":[\"Big.helper()\"],\"target\":\"p.Helper\"}",
                "{\"candidate\":\"C03\",\"verdict\":\"move\",\"severity\":\"medium\",\"evidence\":[{\"line\":3,\"quote\":\"start() { run();\"}],"
                        + "\"members\":[],\"target\":\"\"}"));

        assertThat(outcome.envelope()).startsWith("<!-- traceworks:audit-result:v1 {").endsWith("} -->")
                .doesNotContain("Two jobs");
        Map<String, Object> envelope = envelope(outcome);
        assertThat(envelope.get("snapshotComplete")).isEqualTo(Boolean.TRUE);
        assertThat(envelope.get("expectedFindings")).isEqualTo(4L);
        assertThat(envelope.get("findings")).isEqualTo(List.of(
                finding("layer-violation", "port -> app", "HIGH", "Layer violation: port -> app (rule inner)",
                        "port.Clock in Clock.java, line 4: app.Service",
                        "arch_layer_violations: port -> app (rule inner)"),
                finding("forbidden-io", "core.A", "HIGH", "Direct I/O: core.A (rule core_io)",
                        "In A.java, line 3: java.nio.file.Files", "arch_io_forbidden: core.A (rule core_io)"),
                finding("package-cycle", "a+b", "HIGH", "Package cycle: a+b",
                        "The edge with the fewest types, the cheapest to cut: a -> b (a.A)", "arch_cycles: a+b"),
                finding("verdict-split", "mod/src/main/java/p/C02.java", "MEDIUM", "split: helper",
                        "Candidate C02 of the architecture report; the reason and the target are in "
                                + "architecture-report.md",
                        "arch_candidates: C02; line 4: \"void helper() { }\"; line 3: \"public void start() { run(); }\"")));
    }

    /**
     * findings.jsonl: one object per finding of the envelope, with the same rule and subject. A measured finding has
     * its facts, a verdict the model's reason and target, which the envelope leaves out.
     */
    @Test
    @SuppressWarnings("unchecked")
    void findingsJsonl_hasOneObjectPerFindingOfTheEnvelope_measuredAndJudged() {
        List<String> lines = List.of(withItem(candidate("C01", false, "whole")),
                withItem(candidate("C02", false, "whole")));
        Map<String, Object> facts = facts();
        facts.put("layerViolations", List.of(Map.of("rule", "inner", "from", "port", "to", "app", "sites",
                List.of(Map.of("type", "port.Clock", "file", "Clock.java", "lines",
                        List.of(Map.of("line", 4L, "what", List.of("app.Service"))))))));
        facts.put("io", List.of(Map.of("rule", "core_io", "type", "core.A", "file", "A.java", "allowed", false,
                "uses", List.of(Map.of("line", 3L, "what", List.of("java.nio.file.Files"))))));
        facts.put("cycles", List.of(Map.of("packages", List.of("a", "b"), "edges", List.of(),
                "weakest", Map.of("from", "a", "to", "b", "types", List.of("a.A")))));

        Outcome outcome = Report.of(lines, facts, verdicts(lines, fine("C01"),
                "{\"candidate\":\"C02\",\"verdict\":\"split\",\"severity\":\"medium\",\"reason\":\"Two jobs.\",\"evidence\":[{\"line\":4,"
                        + "\"quote\":\"void helper() { }\"},{\"line\":3,\"quote\":\"public void start() { run(); }\"}],"
                        + "\"members\":[\"Big.helper()\"],\"target\":\"p.Helper\"}"));

        List<Map<String, Object>> entries = outcome.findingsJsonl().lines()
                .map(line -> (Map<String, Object>) Json.parse(line)).toList();
        List<Map<String, Object>> inEnvelope = (List<Map<String, Object>>) envelope(outcome).get("findings");
        assertThat(entries).hasSize(4).hasSize(inEnvelope.size());
        for (int i = 0; i < entries.size(); i++) {
            assertThat(entries.get(i)).containsEntry("ruleKey", inEnvelope.get(i).get("ruleKey"))
                    .containsEntry("subject", inEnvelope.get(i).get("subject"))
                    .containsEntry("severity", inEnvelope.get(i).get("severity"));
        }
        assertThat(entries.get(0)).containsEntry("source", "measured").containsEntry("details", List.of(
                Map.of("file", "Clock.java", "line", 4L, "type", "port.Clock", "uses", "app.Service", "rule", "inner")));
        assertThat(entries.get(1)).containsEntry("source", "measured").containsEntry("details", List.of(
                Map.of("file", "A.java", "line", 3L, "uses", "java.nio.file.Files", "rule", "core_io")));
        assertThat(entries.get(2)).containsEntry("source", "measured").containsEntry("details", List.of(
                Map.of("weakest", "a -> b (a.A)")));
        assertThat(entries.get(3)).containsEntry("source", "model")
                .containsEntry("item", "mod/src/main/java/p/C02.java").containsEntry("number", "C02");
        List<Map<String, Object>> details = (List<Map<String, Object>>) entries.get(3).get("details");
        assertThat(details).hasSize(2);
        assertThat(details.get(0)).containsEntry("file", "mod/src/main/java/p/C02.java").containsEntry("line", 4L)
                .containsEntry("quote", "void helper() { }").containsEntry("members", "helper")
                .containsEntry("model", Map.of("reason", "Two jobs.", "target", "p.Helper"));
        assertThat(details.get(1)).containsEntry("line", 3L).doesNotContainKey("model");
    }

    // ---- the open verdicts of earlier runs: the verdict of this run answers each (docs/registers.md) ----

    /** A candidate line as {@code facts} writes it when the register holds an open split verdict of the file. */
    private static String candidateWithAnOpenSplit(String id) {
        @SuppressWarnings("unchecked")
        Map<String, Object> json = new LinkedHashMap<>((Map<String, Object>) Json.parse(candidate(id, false, "whole")));
        json.put("known", List.of(Map.of("id", "K1", "rule", "verdict-split", "subject",
                "mod/src/main/java/p/" + id + ".java", "evidence", "arch_candidates: " + id)));
        return Json.write(json);
    }

    private static final String SPLIT = "{\"candidate\":\"C01\",\"verdict\":\"split\",\"severity\":\"medium\",\"reason\":\"Two jobs.\",\"evidence\":"
            + "[{\"line\":4,\"quote\":\"void helper() { }\"}],\"members\":[\"Big.helper()\"],\"target\":\"p.Helper\"}";
    private static final String MOVE = "{\"candidate\":\"C01\",\"verdict\":\"move\",\"severity\":\"medium\",\"reason\":\"Wrong layer.\",\"evidence\":"
            + "[{\"line\":3,\"quote\":\"public void start()\"}],\"members\":[],\"target\":\"p.elsewhere\"}";

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> gone(Outcome outcome) {
        return (List<Map<String, Object>>) envelope(outcome).getOrDefault("gone", List.of());
    }

    /** The same verdict again states the finding; another verified verdict says that the earlier one is gone. */
    @Test
    void anOpenVerdict_isStatedByTheSameVerdict_andGoneByAnotherOne() {
        List<String> lines = List.of(candidateWithAnOpenSplit("C01"));
        Map<String, Object> open = Map.of("ruleKey", "verdict-split", "subject", "mod/src/main/java/p/C01.java");

        Outcome again = Report.of(lines, facts(), verdicts(lines, SPLIT));
        Outcome fine = Report.of(lines, facts(), verdicts(lines, fine("C01")));
        Outcome moved = Report.of(lines, facts(), verdicts(lines, MOVE));

        assertThat(gone(again)).isEmpty();
        assertThat((List<?>) envelope(again).get("findings")).hasSize(1);
        assertThat(gone(fine)).containsExactly(open);
        assertThat((List<?>) envelope(fine).get("findings")).isEmpty();
        assertThat(fine.markdown()).contains("## Earlier verdicts that are gone")
                .contains("the verdict of this run is fine").contains("One job.");
        assertThat(fine.envelope()).doesNotContain("One job");
        assertThat(gone(moved)).containsExactly(open);
        assertThat(envelope(moved).get("findings").toString()).contains("verdict-move");
        assertThat(again.notAnswered()).isEmpty();
        assertThat(fine.notAnswered()).isEmpty();
    }

    /** A verdict whose evidence does not hold says nothing: the earlier one is neither stated nor gone. */
    @Test
    void anOpenVerdict_staysAsItIs_whenTheVerdictOfThisRunDoesNotHold() {
        List<String> lines = List.of(candidateWithAnOpenSplit("C01"));

        Outcome outcome = Report.of(lines, facts(), verdicts(lines, "{\"candidate\":\"C01\",\"verdict\":\"fine\","
                + "\"evidence\":[{\"line\":2,\"quote\":\"this is not in the line\"}]}"));

        assertThat(outcome.unverified()).hasSize(1);
        assertThat(gone(outcome)).isEmpty();
        assertThat((List<?>) envelope(outcome).get("findings")).isEmpty();
        assertThat(outcome.notAnswered()).containsExactly("C01 K1 verdict-split");
        assertThat(outcome.markdown()).contains("## Open verdicts without an answer");
    }

    /** A file that is no candidate any more, and was not cut by the cap: its open verdict is gone without a model. */
    @Test
    void anOpenVerdictOfAFileThatIsNoCandidateAnyMore_isNamedAsGoneByTheTool() {
        List<String> lines = List.of(candidate("C01", false, "whole"));
        Map<String, Object> facts = facts();
        facts.put("knownGone", List.of(Map.of("ruleKey", "verdict-move", "subject", "mod/src/main/java/p/Old.java")));

        Outcome outcome = Report.of(lines, facts, verdicts(lines, fine("C01")));

        assertThat(gone(outcome)).containsExactly(Map.of("ruleKey", "verdict-move", "subject",
                "mod/src/main/java/p/Old.java"));
        assertThat(outcome.markdown()).contains("the file is no candidate any more");
    }

    // ---- the severity of a split or move, and low verdicts: in the report of the run, and nowhere else ----

    private static String split(String id, String severity) {
        return "{\"candidate\":\"" + id + "\",\"verdict\":\"split\",\"severity\":\"" + severity + "\",\"reason\":\"Two "
                + "jobs.\",\"evidence\":[{\"line\":4,\"quote\":\"void helper() { }\"}],\"members\":[\"Big.helper()\"],"
                + "\"target\":\"p.Helper\"}";
    }

    /** A split or move says how much it matters; the register takes the severity as the verdict gives it. */
    @Test
    void aSplitOrMove_needsASeverity_andTheEnvelopeTakesIt() {
        List<String> lines = List.of(candidate("C01", false, "whole"), candidate("C02", false, "whole"),
                candidate("C03", false, "whole"), candidate("C04", false, "whole"));

        Outcome outcome = Report.of(lines, facts(), verdicts(lines, split("C01", "HIGH"), split("C02", "medium"),
                split("C03", "urgent"), split("C04", "medium").replace("\"severity\":\"medium\",", "")));

        assertThat(outcome.verified()).extracting(Verdict::id, Verdict::severity)
                .containsExactly(tuple("C01", "high"), tuple("C02", "medium"));
        assertThat(outcome.unverified()).extracting(Verdict::id, Verdict::problem).containsExactly(
                tuple("C03", "the severity 'urgent' of the split is not high, medium or low"),
                tuple("C04", "the severity '' of the split is not high, medium or low"));
        assertThat(((List<?>) envelope(outcome).get("findings")).stream()
                .map(f -> String.valueOf(((Map<?, ?>) f).get("severity")))).containsExactly("HIGH", "MEDIUM");
        assertThat(outcome.markdown()).contains("### 1. split, high, C01, `mod/src/main/java/p/C01.java`");
    }

    /**
     * The register and the gate take medium and high. A split or move the verdict rates low passes the same check and
     * is listed in the report, not in the envelope and not in findings.jsonl; its file is judged, without a finding.
     */
    @Test
    void aNewLowVerdict_isListedInTheReport_andIsNotInTheEnvelope_norInTheData_andItsFileIsJudged() {
        List<String> lines = List.of(withItem(candidate("C01", false, "whole")));
        Map<String, Object> facts = facts();
        facts.put("items", List.of(item(file("C01"), true, "", false)));

        Outcome outcome = Report.of(lines, facts, verdicts(lines, split("C01", "low")));

        assertThat(outcome.verified()).extracting(Verdict::id).containsExactly("C01");
        assertThat(outcome.low()).extracting(Verdict::id).containsExactly("C01");
        assertThat((List<?>) envelope(outcome).get("findings")).isEmpty();
        assertThat(outcome.findingsJsonl()).isEmpty();
        assertThat(envelope(outcome).get("items")).isEqualTo(List.of(
                Map.of("name", file("C01"), "digest", "d-C01", "judged", true)));
        assertThat(outcome.gate()).isEqualTo("GREEN");
        assertThat(outcome.description()).isEqualTo("no measured finding; 0 split and 0 move verified; 1 of 1 candidates"
                + " judged; 1 low verdict in this report only; 1 of 1 item settled (1 clean, 0 decided; 0 open, 0 not"
                + " judged)");
        assertThat(outcome.markdown()).contains("### Low, in this report only\n\nThe verdict rated each of them low: "
                + "a matter of reading, with nothing that relies on it. They passed the same check as the others. They "
                + "are not in the register and not in findings.jsonl, they do not count for the gate, and no later run "
                + "is asked about them.\n\n- split, C01 `mod/src/main/java/p/C01.java`: Two jobs.\n"
                + "  - **Members that move:** `Big.helper()`\n  - **Target:** `p.Helper`\n");
    }

    /** An open verdict of the register is answered as before, whatever the severity: the same one again states it. */
    @Test
    void anOpenVerdict_isStatedByTheSameVerdict_alsoWhenItIsRatedLow_andGoneByAnotherLowOne() {
        List<String> lines = List.of(candidateWithAnOpenSplit("C01"));

        Outcome again = Report.of(lines, facts(), verdicts(lines, split("C01", "low")));
        Outcome moved = Report.of(lines, facts(), verdicts(lines, MOVE.replace("\"medium\"", "\"low\"")));

        assertThat(((List<?>) envelope(again).get("findings")).stream().map(f -> ((Map<?, ?>) f).get("ruleKey") + " "
                + ((Map<?, ?>) f).get("severity"))).containsExactly("verdict-split LOW");
        assertThat(again.low()).isEmpty();
        assertThat(gone(again)).isEmpty();
        assertThat(gone(moved)).as("a low move is a verdict that holds: the split is gone").containsExactly(
                Map.of("ruleKey", "verdict-split", "subject", "mod/src/main/java/p/C01.java"));
        assertThat((List<?>) envelope(moved).get("findings")).isEmpty();
        assertThat(moved.low()).extracting(Verdict::verdict).containsExactly("move");
    }

    // ---- the gate follows the items: GREEN when every item is settled, RED for an open high finding ----

    /** A candidate line as {@code facts} writes it when the register holds an open split of the file, rated high. */
    private static String candidateWithAnOpenHighSplit(String id) {
        @SuppressWarnings("unchecked")
        Map<String, Object> json = new LinkedHashMap<>((Map<String, Object>) Json.parse(withItem(candidate(id, false, "whole"))));
        json.put("known", List.of(Map.of("id", "K1", "rule", "verdict-split", "subject", file(id), "severity", "HIGH",
                "evidence", "arch_candidates: " + id)));
        return Json.write(json);
    }

    /** The facts when the register holds an open high verdict of C01, and C02 was never judged. */
    private static Map<String, Object> factsWithAnOpenHighVerdict(boolean firstChosen) {
        Map<String, Object> first = item(file("C01"), firstChosen, "open", false);
        first.put("open", 1L);
        first.put("open_high", 1L);
        Map<String, Object> facts = facts();
        facts.put("items", List.of(first, item(file("C02"), true, "", false)));
        if (!firstChosen) facts.put("cut", List.of(Map.of("file", file("C01"), "reasons", List.of("big"))));
        return facts;
    }

    /**
     * An open high verdict of the register stands until a run gives another verdict that holds: also when the
     * verdict of this run does not hold, when the call for its file failed, and when its file got no place.
     */
    @Test
    void theGate_isRed_whileAnOpenHighVerdictOfTheRegisterStands_andGreenOnceAnotherVerdictHolds() {
        List<String> lines = List.of(candidateWithAnOpenHighSplit("C01"), withItem(candidate("C02", false, "whole")));
        Outcome unproven = Report.of(lines, factsWithAnOpenHighVerdict(true), verdicts(lines, "{\"candidate\":\"C01\","
                + "\"verdict\":\"fine\",\"evidence\":[{\"line\":2,\"quote\":\"this is not in the line\"}]}", fine("C02")));
        Outcome failed = Report.of(lines, factsWithAnOpenHighVerdict(true), verdicts(lines, null, fine("C02")));
        List<String> second = List.of(withItem(candidate("C02", false, "whole")));
        Outcome noPlace = Report.of(second, factsWithAnOpenHighVerdict(false), verdicts(second, fine("C02")));
        Outcome fineNow = Report.of(lines, factsWithAnOpenHighVerdict(true), verdicts(lines, fine("C01"), fine("C02")));

        for (Outcome outcome : List.of(unproven, failed, noPlace)) {
            assertThat(outcome.gate()).isEqualTo("RED");
            assertThat(outcome.description()).endsWith("; 1 of 2 items settled (1 clean, 0 decided; 1 open, 0 not judged)");
        }
        assertThat(fineNow.gate()).isEqualTo("GREEN");
        assertThat(fineNow.description()).isEqualTo("no measured finding; 0 split and 0 move verified; 2 of 2 candidates"
                + " judged; 2 of 2 items settled (1 clean, 1 decided; 0 open, 0 not judged)");
        assertThat(fineNow.markdown()).contains("| Items after this run | 2 of 2 items settled (1 clean, 1 decided; 0 open,"
                + " 0 not judged) |\n");
    }

    /**
     * An open verdict that no item carries (the register cut its subject, so it matches no file and is not named
     * gone) keeps the gate from GREEN, and the report says that a person must decide it.
     */
    @Test
    void anOpenVerdictThatNoItemCarries_keepsTheGateFromGreen_andIsListedForAPerson() {
        List<String> lines = List.of(withItem(candidate("C01", false, "whole")));
        Map<String, Object> facts = facts();
        facts.put("items", List.of(item(file("C01"), true, "", false)));
        facts.put("knownUnowned", List.of(Map.of("ruleKey", "verdict-split", "subject", "mod/src/very/long\u2026",
                "severity", "HIGH")));
        Outcome high = Report.of(lines, facts, verdicts(lines, fine("C01")));
        facts.put("knownUnowned", List.of(Map.of("ruleKey", "verdict-split", "subject", "mod/src/very/long\u2026",
                "severity", "MEDIUM")));
        Outcome medium = Report.of(lines, facts, verdicts(lines, fine("C01")));

        assertThat(high.gate()).isEqualTo("RED");
        assertThat(medium.gate()).isEqualTo("YELLOW");
        assertThat(medium.description()).isEqualTo("no measured finding; 0 split and 0 move verified; 1 of 1 candidates"
                + " judged; 1 open verdict of the register that no item carries; 1 of 1 item settled (1 clean, 0 decided;"
                + " 0 open, 0 not judged)");
        assertThat(medium.markdown()).contains("## Open verdicts that no item carries\n\nOpen in the register, and no "
                + "file of this run is what they are about, nor could the tool tell that it is gone, so no run asks "
                + "about them. A person must decide them: `traceworks findings architecture dismiss <id>` or "
                + "`accept <id>`.\n\n- MEDIUM verdict-split `mod/src/very/long\u2026`\n");
    }

    /** A new split rated high is RED, one rated medium leaves its file open and the gate YELLOW, a low one is clean. */
    @Test
    void aNewHighVerdict_isRed_aMediumOneLeavesItsFileOpen_andALowOneLeavesItClean() {
        List<String> lines = List.of(withItem(candidate("C01", false, "whole")));
        Map<String, Object> facts = facts();
        facts.put("items", List.of(item(file("C01"), true, "", false)));

        assertThat(Report.of(lines, facts, verdicts(lines, split("C01", "high"))).gate()).isEqualTo("RED");
        Outcome medium = Report.of(lines, facts, verdicts(lines, split("C01", "medium")));
        assertThat(medium.gate()).isEqualTo("YELLOW");
        assertThat(medium.description()).endsWith("; 0 of 1 item settled (0 clean, 0 decided; 1 open, 0 not judged)");
        Outcome low = Report.of(lines, facts, verdicts(lines, split("C01", "low")));
        assertThat(low.gate()).isEqualTo("GREEN");
        assertThat(low.description()).endsWith("; 1 of 1 item settled (1 clean, 0 decided; 0 open, 0 not judged)");
    }

    /**
     * A file shown in part got a verdict on a part of it. That keeps the gate from GREEN in the run that judges the
     * file, and in every later run that leaves the file out as settled: the reason has not gone, and the digest holds
     * only what the prompt showed.
     */
    @Test
    void aFileShownInPart_keepsTheGateYellow_inTheRunThatJudgesIt_andInTheRunsThatLeaveItOut() {
        String part = "part: lines 1-5 of 900";
        List<String> lines = List.of(withItem(candidate("C01", false, part)));
        Map<String, Object> judging = facts();
        Map<String, Object> judgedNow = item(file("C01"), true, "", false);
        judgedNow.put("shown_in_part", true);
        judging.put("items", List.of(judgedNow));
        Map<String, Object> later = facts();
        Map<String, Object> leftOut = item(file("C01"), false, "clean", true);
        leftOut.put("shown_in_part", true);
        later.put("items", List.of(leftOut, item(file("Other"), false, "clean", true)));

        Outcome first = Report.of(lines, judging, verdicts(lines, fine("C01")));
        Outcome second = Report.of(List.of(), later, "");

        for (Outcome outcome : List.of(first, second)) {
            assertThat(outcome.gate()).isEqualTo("YELLOW");
            assertThat(outcome.description()).contains("; 1 candidate shown in part; ");
        }
        assertThat(second.description()).endsWith("; 2 of 2 items settled (2 clean, 0 decided; 0 open, 0 not judged)");
        assertThat(second.markdown()).contains("## Files left out, whose last verdict saw a part (1)\n\n")
                .contains("- `" + file("C01") + "`\n");
    }

    /** A candidate line as {@code facts} writes it when a person dismissed a split verdict of the file. */
    private static String candidateWithADismissedSplit(String id) {
        // The candidate had another number and its line another place when the verdict was dismissed
        return candidateWithADismissedSplit(id, "HIGH", "arch_candidates: C07; line 9: \"void helper() { }\"");
    }

    /** As above, with the severity and the evidence the register shows of the dismissed verdict. */
    private static String candidateWithADismissedSplit(String id, String severity, String evidence) {
        @SuppressWarnings("unchecked")
        Map<String, Object> json = new LinkedHashMap<>((Map<String, Object>) Json.parse(withItem(candidate(id, false,
                "whole"))));
        Known.attach(json, file(id), Known.parse("DISMISSED | " + severity + " | verdict-split | " + file(id)
                + " | t | Evidence: " + evidence + " | Disposition: one job, seen from the caller"),
                Report.JUDGED_RULES);
        return Json.write(json);
    }

    /**
     * With open rows among the ones the block left out, a file the register calls open stays open, also when its
     * open verdict that was shown is answered by another verdict. When only decided rows are missing, it settles.
     */
    @Test
    void withOpenRowsMissing_anOpenFileStaysOpen_alsoWhenTheOneShownIsGone() {
        List<String> lines = List.of(withItem(candidateWithAnOpenSplit("C01")));
        Map<String, Object> open = item(file("C01"), true, "open", false);
        open.put("open", 1L);
        Map<String, Object> facts = facts();
        facts.put("items", List.of(open));
        facts.put("knownMissing", 2L);
        Map<String, Object> openMissing = new HashMap<>(facts);
        openMissing.put("knownMissingOpen", 1L);
        Map<String, Object> decidedMissing = new HashMap<>(facts);
        decidedMissing.put("knownMissingOpen", 0L);

        assertThat(Report.of(lines, openMissing, verdicts(lines, fine("C01"))).description())
                .endsWith("; 0 of 1 item settled (0 clean, 0 decided; 1 open, 0 not judged)");
        assertThat(Report.of(lines, decidedMissing, verdicts(lines, fine("C01"))).description())
                .endsWith("; 1 of 1 item settled (0 clean, 1 decided; 0 open, 0 not judged)");
    }

    /**
     * {{ audit_findings }} holds at most 250 rows or 60 000 bytes, and says how many it left out. Open rows can be
     * among them, so nothing is concluded from a row that is not there: the gate is not GREEN, and a file the
     * register calls open stays open, whatever its verdict says.
     */
    @Test
    void aBlockOfFindingsTheRegisterCut_keepsTheGateFromGreen_andAnOpenFileOpen() {
        List<String> lines = List.of(withItem(candidate("C01", false, "whole")));
        Map<String, Object> facts = facts();
        facts.put("items", List.of(item(file("C01"), true, "open", false), item(file("B"), false, "clean", true)));
        Map<String, Object> cut = new HashMap<>(facts);
        cut.put("knownMissing", 3L);
        cut.put("knownMissingOpen", 2L);

        Outcome outcome = Report.of(lines, cut, verdicts(lines, fine("C01")));

        assertThat(outcome.gate()).isEqualTo("YELLOW");
        assertThat(outcome.description()).endsWith("; 3 findings of the register not shown to this run; 1 of 2 items"
                + " settled (1 clean, 0 decided; 1 open, 0 not judged)");
        assertThat(outcome.markdown()).contains("## Findings of the register this run did not see (3)");
        assertThat(Report.of(lines, facts, verdicts(lines, fine("C01"))).gate()).as("a block that is whole")
                .isEqualTo("GREEN");
    }

    // ---- items: every candidate file is in the envelope (docs/registers.md, "Items") ----

    private static String file(String id) {
        return "mod/src/main/java/p/" + id + ".java";
    }

    /** A candidate line as {@code facts} writes it: with the name its file has as an item of the audit. */
    private static String withItem(String line) {
        @SuppressWarnings("unchecked")
        Map<String, Object> json = new LinkedHashMap<>((Map<String, Object>) Json.parse(line));
        json.put("item", json.get("file"));
        json.put("digest", "d-" + json.get("id"));
        return Json.write(json);
    }

    /** An item as {@code facts} lists it in the facts. */
    private static Map<String, Object> item(String name, boolean chosen, String state, boolean settled) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("digest", "d-" + name.replaceAll(".*/|\\.java", ""));
        item.put("chosen", chosen);
        item.put("state", state);
        item.put("settled", settled);
        return item;
    }

    /** Three candidates of this run, one file the register calls clean, and one that got no place. */
    private static Map<String, Object> factsWithItems() {
        Map<String, Object> facts = facts();
        facts.put("items", List.of(item(file("C01"), true, "", false), item(file("C02"), true, "not judged", false),
                item(file("Settled"), false, "clean", true), item(file("C03"), true, "open", false),
                item(file("Waits"), false, "clean", false)));
        facts.put("cut", List.of(Map.of("file", file("Waits"), "reasons", List.of("big"))));
        return facts;
    }

    private static final String MOVE_C01 = "{\"candidate\":\"C01\",\"verdict\":\"move\",\"severity\":\"medium\",\"evidence\":[{\"line\":3,"
            + "\"quote\":\"public void start()\"}],\"members\":[],\"target\":\"p.elsewhere\"}";
    private static final String UNVERIFIED_C02 = "{\"candidate\":\"C02\",\"verdict\":\"split\",\"severity\":\"medium\",\"evidence\":[{\"line\":4,"
            + "\"quote\":\"void helper() { }\"}],\"members\":[\"gone\"],\"target\":\"p.Helper\"}";

    /**
     * A call that failed is one file not judged, not a report the register refuses: the envelope lists every item,
     * the file without a verdict as not judged, and everything else counts. A verdict whose evidence does not hold
     * is no verdict to keep either: the register never sees it, and the file must not read clean because of it.
     */
    @Test
    void aCandidateWithoutAVerdict_isAnItemNotJudged_andTheSnapshotStaysComplete() {
        List<String> lines = List.of(withItem(candidate("C01", false, "whole")),
                withItem(candidate("C02", false, "whole")), withItem(candidate("C03", false, "whole")));

        Outcome outcome = Report.of(lines, factsWithItems(), verdicts(lines, MOVE_C01, UNVERIFIED_C02, null));

        Map<String, Object> envelope = envelope(outcome);
        assertThat(envelope.get("snapshotComplete")).isEqualTo(Boolean.TRUE);
        assertThat(envelope.get("expectedFindings")).isEqualTo(1L);
        Map<String, Object> move = finding("verdict-move", file("C01"), "MEDIUM", "move: the whole file",
                "Candidate C01 of the architecture report; the reason and the target are in architecture-report.md",
                "arch_candidates: C01; line 3: \"public void start()\"");
        move.put("item", file("C01"));
        assertThat(envelope.get("findings")).as("the unverified split is no finding").isEqualTo(List.of(move));
        assertThat(envelope.get("expectedItems")).isEqualTo(5L);
        assertThat(envelope.get("items")).isEqualTo(List.of(
                Map.of("name", file("C01"), "digest", "d-C01", "judged", true),
                Map.of("name", file("C02"), "digest", "d-C02", "judged", false),
                Map.of("name", file("Settled"), "digest", "d-Settled", "judged", false),
                Map.of("name", file("C03"), "digest", "d-C03", "judged", false),
                Map.of("name", file("Waits"), "digest", "d-Waits", "judged", false)));
        AuditResultParser.ParseResult parsed = AuditResultParser.parse(outcome.envelope());
        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.itemsComplete()).isTrue();
        assertThat(parsed.findings()).extracting(f -> f.item()).containsExactly(file("C01"));
        assertThat(outcome.gate()).isEqualTo("YELLOW");
    }

    /** Only what was not measured makes the snapshot partial: a file nobody could read was not searched for findings. */
    @Test
    void aFileThatCouldNotBeRead_makesTheSnapshotPartial_alsoWhenEveryCandidateWasJudged() {
        List<String> lines = List.of(withItem(candidate("C01", false, "whole")));
        Map<String, Object> facts = facts();
        facts.put("items", List.of(item(file("C01"), true, "", false)));
        facts.put("notRead", List.of(Map.of("file", "x/Torn.java", "reason", "cannot be parsed")));

        Outcome outcome = Report.of(lines, facts, verdicts(lines, fine("C01")));

        assertThat(outcome.judged()).isEqualTo(1);
        assertThat(envelope(outcome).get("snapshotComplete")).isEqualTo(Boolean.FALSE);
        assertThat(AuditResultParser.parse(outcome.envelope()).errors())
                .containsExactly("snapshotComplete must be the JSON boolean true");
    }

    /** The report says how many items there are, how many were left out as settled and how many wait. */
    @Test
    void theReport_countsTheItems_theSettledOnes_andTheOnesThatWait() {
        List<String> lines = List.of(withItem(candidate("C01", false, "whole")),
                withItem(candidate("C02", false, "whole")), withItem(candidate("C03", false, "whole")));

        Outcome outcome = Report.of(lines, factsWithItems(), verdicts(lines, fine("C01"), fine("C02"), fine("C03")));

        assertThat(outcome.description()).isEqualTo("no measured finding; 0 split and 0 move verified; 3 of 3 candidates"
                + " judged; 1 candidate cut by the cap; 4 of 5 items settled (4 clean, 0 decided; 0 open, 1 not judged)");
        assertThat(outcome.markdown()).contains("| Items (every file that could be judged) | 5 |\n"
                + "| Items settled and unchanged, not judged again | 1 (1 clean, 0 decided) |\n"
                + "| Items that wait for a later run | 1 |\n");
        assertThat(outcome.gate()).as("a file waits").isEqualTo("YELLOW");
        assertThat(envelope(outcome).get("snapshotComplete")).isEqualTo(Boolean.TRUE);
    }

    /** The loop ends: when every file is settled, no call is made, and the report is green and a full snapshot. */
    @Test
    void whenEveryFileIsSettled_noneIsJudged_andTheReportIsGreen() {
        Map<String, Object> facts = facts();
        facts.put("items", List.of(item(file("A"), false, "clean", true), item(file("B"), false, "decided", true)));

        Outcome outcome = Report.of(List.of(), facts, "");

        assertThat(outcome.gate()).isEqualTo("GREEN");
        assertThat(outcome.description()).isEqualTo("no measured finding; 0 split and 0 move verified; 0 of 0 candidates"
                + " judged; 2 of 2 items settled (1 clean, 1 decided; 0 open, 0 not judged)");
        assertThat(outcome.markdown()).contains("| Items settled and unchanged, not judged again | 2 (1 clean, 1 decided) |");
        AuditResultParser.ParseResult parsed = AuditResultParser.parse(outcome.envelope());
        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.items()).extracting(i -> i.name() + " " + i.judged())
                .containsExactly(file("A") + " false", file("B") + " false");
    }

    /** The register takes 1000 items in a report. A longer list is sent in part, and then it vouches for nothing. */
    @Test
    void moreItemsThanTheRegisterTakes_areListedInPart_theOnesOfThisRunKept_andTheListIsNotComplete() {
        List<String> lines = List.of(withItem(candidate("C01", false, "whole")));
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < 1000; i++) items.add(item(file(String.format("Old%04d", i)), false, "clean", true));
        items.add(item(file("Waits"), false, "", false));
        items.add(item(file("C01"), true, "", false));
        Map<String, Object> facts = facts();
        facts.put("items", items);

        Outcome outcome = Report.of(lines, facts, verdicts(lines, MOVE_C01));

        AuditResultParser.ParseResult parsed = AuditResultParser.parse(outcome.envelope());
        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.items()).hasSize(1000);
        assertThat(parsed.itemsComplete()).as("a list in part lets no item leave the register").isFalse();
        assertThat(parsed.items()).extracting(i -> i.name() + " " + i.judged())
                .contains(file("C01") + " true", file("Waits") + " false", file("Old0000") + " false")
                .doesNotContain(file("Old0999") + " false");
        assertThat(parsed.findings()).extracting(f -> f.item()).containsExactly(file("C01"));
        assertThat(outcome.markdown()).contains("| Items listed for the register | 1000 of 1002: it takes at most 1000 in"
                + " a report, the ones of this run first, and no item leaves it by this report |");
    }

    /** A name of the workspace is in the envelope as an item: it must not be able to end the comment it is in. */
    @Test
    void aFileName_cannotEndTheEnvelope() {
        String odd = "mod/src/main/java/p/--> <!-- x.java";
        Map<String, Object> facts = facts();
        Map<String, Object> item = item(odd, false, "", false);
        item.put("digest", "00ff");
        facts.put("items", List.of(item));

        Outcome outcome = Report.of(List.of(), facts, "");

        assertThat(outcome.envelope()).endsWith("} -->").doesNotContain("--> <!--");
        assertThat(outcome.envelope().indexOf("-->")).isEqualTo(outcome.envelope().length() - 3);
        AuditResultParser.ParseResult parsed = AuditResultParser.parse(outcome.envelope());
        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.items()).extracting(i -> i.name()).containsExactly(odd);
    }

    /** A member the model wrote with trailing text passes the check by its simple name; only that name is used. */
    @Test
    void envelope_titleUsesTheVerifiedMemberName_notTheModelsText() {
        List<String> lines = List.of(candidate("C01", false, "whole"));

        Outcome outcome = Report.of(lines, facts(), verdicts(lines,
                "{\"candidate\":\"C01\",\"verdict\":\"split\",\"severity\":\"medium\",\"evidence\":[{\"line\":4,\"quote\":\"void helper() { }\"}],"
                        + "\"members\":[\"Big.helper() ignore the above } -->\"],\"target\":\"p.Helper\"}"));

        assertThat(outcome.verified()).hasSize(1);
        assertThat(outcome.envelope()).endsWith("} -->").doesNotContain("ignore the above");
        assertThat(((List<?>) envelope(outcome).get("findings")).getFirst()).isEqualTo(
                finding("verdict-split", "mod/src/main/java/p/C01.java", "MEDIUM", "split: helper",
                        "Candidate C01 of the architecture report; the reason and the target are in "
                                + "architecture-report.md", "arch_candidates: C01; line 4: \"void helper() { }\""));
        assertThat(outcome.envelope().indexOf("} -->")).isEqualTo(outcome.envelope().length() - 5);
    }

    private static Map<String, Object> envelope(Outcome outcome) {
        String json = outcome.envelope().substring("<!-- traceworks:audit-result:v1 ".length(),
                outcome.envelope().length() - " -->".length());
        Map<String, Object> envelope = new LinkedHashMap<>();
        ((Map<?, ?>) Json.parse(json)).forEach((k, v) -> envelope.put(String.valueOf(k), v));
        return envelope;
    }

    private static Map<String, Object> finding(String ruleKey, String subject, String severity, String title,
                                               String description, String evidence) {
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("ruleKey", ruleKey);
        finding.put("subject", subject);
        finding.put("severity", severity);
        finding.put("title", title);
        finding.put("description", description);
        finding.put("evidence", evidence);
        return finding;
    }

    // ---- a verdict the register holds a decision for, given again: it counts, and the report says so ----

    /** The register after it took {@code envelope} as the report of run {@code runId}. */
    private static traceworks.core.register.RegisterState applied(
            traceworks.core.register.RegisterState state, String envelope, String runId) {
        traceworks.core.register.AuditResultParser.ParseResult parsed =
                traceworks.core.register.AuditResultParser.parse(envelope);
        assertThat(parsed.errors()).isEmpty();
        return traceworks.core.register.Reconciler.apply(state,
                new traceworks.core.register.Reconciler.Report(runId,
                        java.time.Instant.parse("2026-11-14T10:00:00Z"), "blob", parsed.findings(), java.util.Set.of(),
                        parsed.gone(), parsed.items(), parsed.itemsComplete()), null).state();
    }

    /**
     * A verdict that passes the evidence check counts for the gate and opens its file, whether or not the register
     * holds a decision for a verdict of that kind for the file. The report says that it does.
     */
    @Test
    void aVerdictTheRegisterHoldsADecisionFor_givenAgain_countsForTheGate_andTheReportSaysSo() {
        List<String> lines = List.of(candidateWithADismissedSplit("C01"));
        Map<String, Object> facts = facts();
        Map<String, Object> decided = item(file("C01"), true, "decided", false);
        decided.put("decided", 1L);
        facts.put("items", List.of(decided));

        Outcome outcome = Report.of(lines, facts, verdicts(lines, split("C01", "high")));

        assertThat(AuditResultParser.parse(outcome.envelope()).findings()).extracting(f -> f.ruleKey())
                .containsExactly("verdict-split");
        assertThat(outcome.gate()).isEqualTo("RED");
        assertThat(outcome.description()).endsWith("; 0 of 1 item settled (0 clean, 0 decided; 1 open, 0 not judged)");
        assertThat(outcome.markdown()).contains("## Verdicts the register holds a decision for (1)")
                .contains("- split `" + file("C01") + "` (DISMISSED): decided on HIGH, stated as HIGH, which is not above it: the register keeps the decision")
                .contains("the gate counts each of them in this run")
                .doesNotContain("The register will keep that decision");
    }

    /**
     * Two reports in a row, through the register's own text: a split is reported, a person dismisses it, and the next
     * run gives a split of the same file again, rated higher. The block the second run gets is the one the register
     * renders. The gate of the second run is not GREEN and names it.
     */
    @Test
    void aDismissedVerdict_givenAgainWorse_throughTheRegistersOwnText_keepsTheGateFromGreen() {
        List<String> firstLines = List.of(withItem(candidate("C01", false, "whole")));
        Map<String, Object> facts = facts();
        facts.put("items", List.of(item(file("C01"), true, "", false)));
        Outcome first = Report.of(firstLines, facts, verdicts(firstLines, split("C01", "medium")));
        var state = applied(traceworks.core.register.RegisterState.empty("architecture", "wf",
                "architecture"), first.envelope(), "run-1");
        var reported = state.findings().stream().filter(f -> f.ruleKey().equals("verdict-split")).findFirst()
                .orElseThrow();
        List<traceworks.core.register.Finding> after = new java.util.ArrayList<>();
        for (var f : state.findings()) {
            after.add(f == reported ? traceworks.core.register.Disposition.dismiss(f,
                    "one job, seen from the caller", "tester", java.time.Instant.parse("2026-11-14T11:00:00Z")) : f);
        }
        state = state.with(after);
        String block = traceworks.core.register.FindingsText.render(state, null);
        assertThat(block).contains("DISMISSED | MEDIUM | verdict-split | " + file("C01"));
        @SuppressWarnings("unchecked")
        Map<String, Object> json = new LinkedHashMap<>((Map<String, Object>) Json.parse(firstLines.get(0)));
        Known.attach(json, file("C01"), Known.parse(block), Report.JUDGED_RULES);
        List<String> lines = List.of(Json.write(json));

        Outcome second = Report.of(lines, facts, verdicts(lines, split("C01", "high")));

        assertThat(second.gate()).isEqualTo("RED");
        assertThat(second.markdown()).contains("## Verdicts the register holds a decision for (1)")
                .contains("- split `" + file("C01") + "` (DISMISSED): decided on MEDIUM, stated as HIGH, which is above it: the register opens the finding again");
        // Rated higher than it was dismissed on: the register opens it again, so it agrees with the gate of the run
        assertThat(applied(state, second.envelope(), "run-2").findings().stream()
                .filter(f -> f.ruleKey().equals("verdict-split")).findFirst().orElseThrow().status().name())
                .isEqualTo("OPEN");
    }
}
