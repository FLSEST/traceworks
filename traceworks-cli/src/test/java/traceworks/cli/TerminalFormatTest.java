package traceworks.cli;

import traceworks.core.record.RecordedEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
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

    private String line(RecordedEvent e) {
        return format.line(e).orElseThrow();
    }

    @Test
    void runStarted_namesWorkflowRunAndActor() {
        assertThat(line(event("run.started", "\"workflow\":{\"id\":\"w\",\"name\":\"Deploy\"},\"actor\":\"flo@box\"")))
                .isEqualTo("12:34:56 run started: Deploy (run 11111111) by flo@box");
    }

    /** The start line says where the run runs: in containers, with the runtime, or on the host without one. */
    @Test
    void runStartedAndResumed_nameWhereTheRunRuns() {
        String workflow = "\"workflow\":{\"id\":\"w\",\"name\":\"Deploy\"},\"actor\":\"flo@box\",";
        String docker = "\"container_runtime\":{\"name\":\"docker\",\"version\":\"29.1.3\"}";

        assertThat(line(event("run.started", workflow + "\"execution\":\"container\"," + docker)))
                .isEqualTo("12:34:56 run started: Deploy (run 11111111) by flo@box in containers (docker 29.1.3)");
        assertThat(line(event("run.started", workflow + "\"execution\":\"host\",\"container_runtime\":null")))
                .isEqualTo("12:34:56 run started: Deploy (run 11111111) by flo@box on the host (no container runtime"
                        + " works)");
        assertThat(line(event("run.resumed", "\"actor\":\"flo@box\",\"rerun_step\":2,\"execution\":\"container\","
                + docker))).isEqualTo("12:34:56 run resumed by flo@box in containers (docker 29.1.3), running step 2"
                + " again");
        assertThat(line(event("run.resumed", "\"actor\":\"flo@box\",\"rerun_step\":null,\"execution\":\"host\","
                + docker))).as("a host run stays one where a runtime works now")
                .isEqualTo("12:34:56 run resumed by flo@box on the host, where it started");
        assertThat(line(event("run.resumed", "\"actor\":\"flo@box\",\"rerun_step\":null,\"execution\":\"host\","
                + "\"container_runtime\":null")))
                .isEqualTo("12:34:56 run resumed by flo@box on the host (no container runtime works)");
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
        line(event("run.resumed", "\"actor\":\"flo@box\",\"output_limit_mib\":64"));
        assertThat(line(event("step.command.finished", cut))).endsWith("(stdout beyond 64 MiB)");
        line(event("run.resumed", "\"actor\":\"flo@box\""));
        assertThat(line(event("step.command.finished", cut))).as("a record of an earlier version: the default")
                .endsWith("(stdout beyond 16 MiB)");
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
                + ",\"actor\":\"flo@box\"")))
                .isEqualTo("12:34:56 step 1  answered: Yes by flo@box");
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
        assertThat(line(event("run.resumed", "\"actor\":\"flo@box\",\"rerun_step\":2")))
                .isEqualTo("12:34:56 run resumed by flo@box, running step 2 again");
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
                + "\"actor\":\"flo\\u001b[2J\""));

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
