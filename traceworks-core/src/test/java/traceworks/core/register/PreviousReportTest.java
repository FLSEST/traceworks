package traceworks.core.register;

import org.junit.jupiter.api.Test;
import traceworks.core.compat.EarlierNames;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code {{ previous_report }}}: the last valid report, defused, so a model can read it and no parser acts on it. */
class PreviousReportTest {

    @Test
    void theEnvelope_isCut_andMarkersLoseTheirColon() {
        String report = "# Report\ngate_status: GREEN\ntodo_item: a | HIGH | t\n<!-- traceworks:audit-result:v1\n"
                + "{\"snapshotComplete\":true,\"findings\":[]}\n-->\n";

        String defused = PreviousReport.defused(report);

        assertThat(defused).contains("# Report", "gate_status GREEN", "todo_item a | HIGH | t")
                .doesNotContain("traceworks:audit-result").doesNotContain("snapshotComplete");
    }

    @Test
    void anEnvelopeThatNeverCloses_isCutToTheEnd() {
        assertThat(PreviousReport.defused("text\n<!-- traceworks:audit-result:v1 {\"snapshotComplete\":true"))
                .isEqualTo("text\n");
    }

    @Test
    void aReportThatWasOnlyAnEnvelope_isNone() {
        assertThat(PreviousReport.defused(
                "<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,\"findings\":[]}\n-->"))
                .isEqualTo(PreviousReport.NONE);
        assertThat(PreviousReport.defused(null)).isEqualTo(PreviousReport.NONE);
        assertThat(PreviousReport.defused("  ")).isEqualTo(PreviousReport.NONE);
    }

    @Test
    void longerThan3000Characters_isCut_afterTheEnvelopeWasRemoved() {
        String report = "x".repeat(3500) + "<!-- traceworks:audit-result:v1 {} -->";

        String defused = PreviousReport.defused(report);

        assertThat(defused).hasSize(3000 + "\n[...truncated]".length()).endsWith("\n[...truncated]");
    }

    /** A marker quoted in prose is no envelope, so the text stays; the marker loses its colon like every marker. */
    @Test
    void aQuotedMarker_isNoEnvelope_andOnlyLosesItsColon() {
        assertThat(PreviousReport.defused("the marker `<!-- traceworks:audit-result:v1` is quoted"))
                .isEqualTo("the marker `<!-- traceworksaudit-result:v1` is quoted");
    }

    /**
     * A report from before the marker changed: its envelope is cut too, and a quoted earlier marker loses its colon.
     */
    @Test
    void theEarlierMarker_isCutAndDefusedLikeTheMarker() {
        String earlier = EarlierNames.AUDIT_ENVELOPE_WORD + ":audit-result:v1";

        assertThat(PreviousReport.defused(
                "# Report\n<!-- " + earlier + " {\"snapshotComplete\":true,\"findings\":[]} -->"))
                .isEqualTo("# Report\n");
        assertThat(PreviousReport.defused("the marker `<!-- " + earlier + "` is quoted"))
                .isEqualTo("the marker `<!-- " + EarlierNames.AUDIT_ENVELOPE_WORD + "audit-result:v1` is quoted");
    }
}
