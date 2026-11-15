package traceworks.core.blocks.compress;

import traceworks.core.model.StepStatus;
import traceworks.core.model.SourceStep;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompressionOutputTest {

    private static CompressionSources.Resolved source(StepStatus status, Integer exit, Long durationMs, String text) {
        SourceStep step = new SourceStep("tests", "s1", 3, "shell", status, exit, durationMs, null);
        return new CompressionSources.Resolved("tests", step, text);
    }

    @Test
    void assemble_fits_headerDetailBlankLineBody() {
        var out = CompressionOutput.assemble(source(StepStatus.FAILED, 1, 2500L, "x".repeat(12_345)),
                "Stages: text.normalize", "abcd", 10_000);

        assertThat(out.text()).isEqualTo("## tests — FAILED · exit 1 · 3 s · 12,345 → 4 chars\nStages: text.normalize\n\nabcd");
        assertThat(out.inChars()).isEqualTo(12_345);
        assertThat(out.outChars()).isEqualTo(4);
        assertThat(out.capped()).isFalse();
    }

    @Test
    void assemble_emptyBody_showsMarker_butCountsZero_andHeaderWithoutFacts() {
        var out = CompressionOutput.assemble(source(null, null, null, ""), "KI", "", 10_000);

        assertThat(out.text()).isEqualTo("## tests — UNKNOWN · 0 → 0 chars\nKI\n\n(empty)");
        assertThat(out.outChars()).isZero();
        assertThat(CompressionOutput.bodyOf(out.text())).as("the marker is no content").isEmpty();
    }

    @Test
    void assemble_overMax_cutsBody_andCapNoteNamesSameNumberAsNotice() {
        var out = CompressionOutput.assemble(source(StepStatus.COMPLETED, 0, 1000L, "y".repeat(8000)),
                "Stages: lines.dedupe", "x".repeat(5000), 1000);

        assertThat(out.capped()).isTrue();
        assertThat(out.text().length()).isLessThanOrEqualTo(1000);
        Matcher cap = Pattern.compile("· cap \\(−([\\d,]+) chars\\)").matcher(out.text());
        Matcher notice = Pattern.compile("\\[… ([\\d,]+) chars omitted …]").matcher(out.text());
        assertThat(cap.find()).isTrue();
        assertThat(notice.find()).isTrue();
        assertThat(cap.group(1)).isEqualTo(notice.group(1));
        assertThat(out.outChars()).isEqualTo(CompressionOutput.bodyOf(out.text()).length());
        assertThat(out.text()).startsWith("## tests — COMPLETED · exit 0 · 1 s · 8,000 → " + CompressionNumbers.fmt(out.outChars()) + " chars\n");
    }

    @Test
    void assemble_maxTooSmallForHeadersAndMinimumBody_fails() {
        assertThatThrownBy(() -> CompressionOutput.assemble(source(StepStatus.COMPLETED, null, null, "s"),
                "Stages: text.normalize", "x".repeat(5000), 250))
                .isInstanceOf(CompressionException.class)
                .hasMessageStartingWith("'max_chars' = 250 is too small")
                .hasMessageContaining(CompressionOutput.MIN_BODY_CHARS + " chars of content");
    }

    @Test
    void bodyOf_isEverythingAfterFirstBlankLine() {
        assertThat(CompressionOutput.bodyOf("## h\nd\n\nbody\n\nmore")).isEqualTo("body\n\nmore");
        assertThat(CompressionOutput.bodyOf("without a blank line")).isEmpty();
        assertThat(CompressionOutput.bodyOf("## h\nd\n\n")).isEmpty();
    }

    /** The empty marker is no content, whichever detail line (stages or AI) precedes it. */
    @Test
    void bodyOf_readsTheEmptyMarkerAsEmpty() {
        assertThat(CompressionOutput.bodyOf("## h\nSummary: AI (CLAUDE)\n\n(empty)")).isEmpty();
        assertThat(CompressionOutput.bodyOf("## h\nStages: x\n\n(empty)")).isEmpty();
    }

    @Test
    void metadata_marksDerivedOutput_andOmitsStagesWhenNull() {
        var src = source(StepStatus.FAILED, 2, null, "abc");
        var out = CompressionOutput.assemble(src, "KI", "a", 10_000);

        Map<String, Object> ai = CompressionOutput.metadata(src, out, 10_000, null);
        Map<String, Object> det = CompressionOutput.metadata(src, out, 10_000, List.of(Map.of("op", "text.normalize")));

        assertThat(ai).containsEntry("derivedOutput", true);
        @SuppressWarnings("unchecked") Map<String, Object> c = (Map<String, Object>) ai.get("compression");
        assertThat(c).containsEntry("label", "tests").containsEntry("inChars", 3).containsEntry("outChars", 1)
                .containsEntry("maxChars", 10_000).containsEntry("capped", false).doesNotContainKey("stages");
        assertThat(c.get("source")).isEqualTo(src.origin().toMetadata());
        assertThat(((Map<?, ?>) det.get("compression")).containsKey("stages")).isTrue();
        // the header can be rebuilt from stored metadata: SourceStep reads the source facts back
        assertThat(SourceStep.fromMetadata((Map<?, ?>) c.get("source")).status()).isEqualTo(StepStatus.FAILED);
    }
}
