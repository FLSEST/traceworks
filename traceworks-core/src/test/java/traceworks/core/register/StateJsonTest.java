package traceworks.core.register;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StateJsonTest {

    private static final Instant T = Instant.parse("2026-11-11T10:00:00Z");

    private static RegisterState sample() {
        Finding a = new Finding("b".repeat(64), "r", "s", Severity.HIGH, "t", "d", "e", List.of("x"),
                FindingStatus.ACCEPTED_RISK, new Finding.Seen("run-1", T), new Finding.Seen("run-2", T.plusSeconds(60)),
                2, 1, true, null, new Finding.DispositionRecord(FindingStatus.ACCEPTED_RISK, "note", "alex@box", T,
                T.plusSeconds(3600), "frozen"));
        Finding b = new Finding("a".repeat(64), "r2", "s2", Severity.LOW, "t2", null, null, List.of(),
                FindingStatus.RESOLVED, new Finding.Seen("run-1", T), new Finding.Seen("run-1", T), 1, 0, false,
                T.plusSeconds(60), null);
        return new RegisterState(1, "arch", "wf-1", "architecture",
                new RegisterState.LastReport("run-2", T.plusSeconds(60), "c".repeat(64), true), List.of(a, b), 2);
    }

    @Test
    void write_isDeterministic_sorted_andReadsBack() {
        byte[] first = StateJson.write(sample());
        byte[] second = StateJson.write(sample());

        assertThat(first).isEqualTo(second);
        String text = new String(first, StandardCharsets.UTF_8);
        assertThat(text).startsWith("{\"category\":\"architecture\",\"findings\":[{\"absent_reports\":0,"
                + "\"description\":null")
                .contains("\"format\":1").contains("\"key\":\"arch\"").endsWith("}\n");
        assertThat(text.indexOf("\"id\":\"aaaa")).isLessThan(text.indexOf("\"id\":\"bbbb"));
        assertThat(StateJson.read(first)).isEqualTo(sample());
    }

    @Test
    void anEmptyState_readsBack() {
        RegisterState empty = RegisterState.empty("k", "wf", "c");

        assertThat(StateJson.read(StateJson.write(empty))).isEqualTo(empty);
        assertThat(new String(StateJson.write(empty), StandardCharsets.UTF_8)).contains("\"last_report\":null");
    }

    /**
     * {@code findings} goes to a terminal ({@code findings <key> list --json}), and its texts are a step's or a
     * model's. JSON by default escapes only what is below U+0020: DEL, the C1 controls (CSI, OSC), direction
     * overrides and the line separators would arrive raw.
     */
    @Test
    void findings_isAsciiOnly_soNoControlOrFormatCharacterReachesATerminal_andReadsBackTheSame() throws Exception {
        String hostile = "a\u007fb\u009b31mc\u009d0;x\u0007d\u202ee\u2028f\u200bg\uDB40\uDC41h é";
        Finding f = new Finding("b".repeat(64), hostile, hostile, Severity.HIGH, hostile, hostile, hostile,
                List.of(hostile), FindingStatus.OPEN, new Finding.Seen("run-1", T), new Finding.Seen("run-1", T), 1, 0,
                false, null, null);

        String json = StateJson.findings(List.of(f));

        assertThat(json.chars().allMatch(c -> c == '\n' || c >= 0x20 && c < 0x7f)).isTrue();
        var read = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).get(0);
        for (String field : List.of("rule_key", "subject", "title", "description", "evidence")) {
            assertThat(read.get(field).asText()).as(field).isEqualTo(hostile);
        }
        assertThat(read.get("tags").get(0).asText()).isEqualTo(hostile);
    }

    /**
     * A register written before items existed must verify as it did: the verifier compares the bytes of every state
     * blob with what the fold writes now. So a state without items has no key for them.
     */
    @Test
    void aStateWithoutItems_hasTheBytesItHadBefore() {
        String text = new String(StateJson.write(sample()), StandardCharsets.UTF_8);

        assertThat(text).doesNotContain("\"items\"").doesNotContain("\"item\"");
        assertThat(new String(StateJson.write(RegisterState.empty("k", "wf", "c")), StandardCharsets.UTF_8))
                .isEqualTo("{\"category\":\"c\",\"findings\":[],\"format\":1,\"key\":\"k\",\"last_report\":null,"
                        + "\"valid_reports\":0,\"workflow_id\":\"wf\"}\n");
        assertThat(StateJson.findings(sample().findings())).doesNotContain("\"item\"");
    }

    @Test
    void items_andAFindingsItem_surviveWriteAndRead() {
        Finding named = new Finding("d".repeat(64), "r", "p.A#1", Severity.LOW, "t", null, null, List.of(),
                FindingStatus.OPEN, new Finding.Seen("run-1", T), new Finding.Seen("run-1", T), 1, 0, false, null, null,
                0, "p.A");
        RegisterState state = new RegisterState(1, "testing", "wf-1", "testing", null, List.of(named), 1,
                List.of(new Item("p.B", "bb", null, null), new Item("p.A", "a2", "aa", new Finding.Seen("run-1", T))));

        byte[] json = StateJson.write(state);

        String text = new String(json, StandardCharsets.UTF_8);
        assertThat(text).contains("\"items\":[{\"digest\":\"a2\",\"name\":\"p.A\",\"verdict\":{\"at\":\"" + T
                + "\",\"run_id\":\"run-1\"},\"verdict_digest\":\"aa\"},{\"digest\":\"bb\",\"name\":\"p.B\","
                + "\"verdict\":null,\"verdict_digest\":null}]").contains("\"item\":\"p.A\"");
        assertThat(StateJson.read(json)).isEqualTo(state);
        assertThat(StateJson.findings(state.findings())).contains("\"item\":\"p.A\"");
    }
}

