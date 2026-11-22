package traceworks.core.register;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The payload a hook gets: what a report changed, as JSON, the same bytes for the same change. */
class RegisterDiffTest {

    private static final Instant T1 = Instant.parse("2026-11-11T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-12T10:00:00Z");

    private static ReportedFinding finding(String subject) {
        return new ReportedFinding("size", subject, Severity.HIGH, "Big " + subject, null, "340 LOC", List.of("arch"));
    }

    @Test
    void diff_namesEveryChange_withTheFindings_andIsDeterministic() throws Exception {
        RegisterState empty = RegisterState.empty("arch", "wf-1", "c");
        Reconciler.Applied first = Reconciler.apply(empty, new Reconciler.Report("run-1", T1, "b1",
                List.of(finding("A"), finding("B")), Set.of()), null);
        Reconciler.Applied second = Reconciler.apply(first.state(), new Reconciler.Report("run-2", T2, "b2",
                List.of(finding("B"), finding("C")), Set.of()), null);

        byte[] json = RegisterDiff.of("arch", "shop", "wf-1", "run-2", true, first.state(), second.state(), second.changes());
        byte[] again = RegisterDiff.of("arch", "shop", "wf-1", "run-2", true, first.state(), second.state(), second.changes());

        assertThat(json).isEqualTo(again);
        JsonNode root = new ObjectMapper().readTree(json);
        assertThat(root.path("format").asInt()).isEqualTo(1);
        assertThat(root.path("register").asText()).isEqualTo("arch");
        assertThat(root.path("workflow_id").asText()).isEqualTo("wf-1");
        assertThat(root.path("run_id").asText()).isEqualTo("run-2");
        assertThat(root.path("report_valid").asBoolean()).isTrue();
        assertThat(root.path("new")).hasSize(1);
        assertThat(root.path("new").get(0).path("subject").asText()).isEqualTo("C");
        assertThat(root.path("new").get(0).path("status").asText()).isEqualTo("OPEN");
        assertThat(root.path("new").get(0).path("rule_key").asText()).isEqualTo("size");
        assertThat(root.path("new").get(0).path("severity").asText()).isEqualTo("HIGH");
        assertThat(root.path("new").get(0).path("evidence").asText()).isEqualTo("340 LOC");
        assertThat(root.path("new").get(0).path("id").asText()).hasSize(64);
        assertThat(root.path("resolved")).hasSize(1);
        assertThat(root.path("resolved").get(0).path("subject").asText()).isEqualTo("A");
        assertThat(root.path("reopened")).isEmpty();
        assertThat(root.path("still_open")).hasSize(1);
        assertThat(root.path("still_open").get(0).path("subject").asText()).isEqualTo("B");
        assertThat(root.path("counts").path("new").asInt()).isEqualTo(1);
        assertThat(root.path("counts").path("resolved").asInt()).isEqualTo(1);
        assertThat(new String(json, StandardCharsets.UTF_8)).endsWith("}\n");
    }

    @Test
    void aRejectedReport_hasNoChanges_butSaysSo() throws Exception {
        RegisterState state = RegisterState.empty("arch", "wf-1", "c");

        JsonNode root = new ObjectMapper().readTree(RegisterDiff.of("arch", "shop", "wf-1", "run-9", false, state, state,
                List.of()));

        assertThat(root.path("report_valid").asBoolean()).isFalse();
        assertThat(root.path("new")).isEmpty();
        assertThat(RegisterDiff.changesAnything(List.of())).isFalse();
        assertThat(RegisterDiff.changesAnything(List.of(new Change(Change.Kind.SEEN, "x", null)))).isFalse();
        assertThat(RegisterDiff.changesAnything(List.of(new Change(Change.Kind.REOPENED, "x", "regression")))).isTrue();
    }
}
