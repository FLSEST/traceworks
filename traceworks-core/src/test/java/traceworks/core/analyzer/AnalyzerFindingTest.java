package traceworks.core.analyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.analyzer.AnalyzerFinding.Severity;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyzerFindingTest {

    @Test
    void of_takesEveryField() {
        AnalyzerFinding f = AnalyzerFinding.of("r", Severity.INFO, -1, null, "d");
        assertThat(f).isEqualTo(new AnalyzerFinding("r", Severity.INFO, -1, null, "d"));
    }

    /** The analyzer sorts by ordinal: ERROR is the most severe. */
    @Test
    void severityOrder_isFromMostToLeastSevere() {
        assertThat(Severity.values()).containsExactly(Severity.ERROR, Severity.WARNING, Severity.INFO);
        List<Severity> sorted = Arrays.stream(new Severity[]{Severity.INFO, Severity.ERROR, Severity.WARNING})
                .sorted().toList();
        assertThat(sorted).containsExactly(Severity.ERROR, Severity.WARNING, Severity.INFO);
    }

    @Test
    void jsonRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AnalyzerFinding f = new AnalyzerFinding("unwired-output", Severity.WARNING, 2, "report", "detail");

        String json = mapper.writeValueAsString(f);

        assertThat(json).contains("\"severity\":\"WARNING\"").contains("\"blockIndex\":2")
                .contains("\"detail\":\"detail\"").doesNotContain("detailEn");
        assertThat(mapper.readValue(json, AnalyzerFinding.class)).isEqualTo(f);
    }
}
