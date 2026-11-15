package traceworks.core.register;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceMetricsTest {

    @Test
    void parsesNumbersWithKnownUnitsIntoCanonicalKeys() {
        assertThat(EvidenceMetrics.parse("340 LOC, 12 deps, 3,5 MB, 80 % coverage, 2 Dateien, 5 Fehler"))
                .containsEntry("LOC", 340.0).containsEntry("deps", 12.0).containsEntry("MB", 3.5)
                .containsEntry("%", 80.0).containsEntry("files", 2.0).containsEntry("errors", 5.0);
    }

    @Test
    void keepsTheLargestValuePerUnitAndMergesSynonyms() {
        assertThat(EvidenceMetrics.parse("120 lines and 340 LOC and 7 Zeilen")).containsEntry("LOC", 340.0);
    }

    @Test
    void ignoresNumbersWithoutUnitAndVersionLikeTokens() {
        assertThat(EvidenceMetrics.parse("version 1.2.3 with 42 and 2026-11-11")).isEmpty();
    }

    @Test
    void parsesNclocAsItsOwnUnitNotAsLoc() {
        Map<String, Double> metrics = EvidenceMetrics.parse("531 NCLOC");

        assertThat(metrics).containsEntry("NCLOC", 531.0).doesNotContainKey("LOC");
    }

    @Test
    void parsesNamedCountsFromFactLinesUnderTheirName() {
        assertThat(EvidenceMetrics.parse("FACT unindexed_findby_columns_count: 9"))
                .containsEntry("unindexed_findby_columns_count", 9.0);
        assertThat(EvidenceMetrics.parse("FACT ncloc_total: 27228")).doesNotContainKey("ncloc_total");
    }

    @Test
    void churnIsReadSeparately_withTheHotspotThreshold() {
        assertThat(EvidenceMetrics.churn("6 Aenderungen in 90 Tagen (Hotspot ab 5)")).hasValue(6);
        assertThat(EvidenceMetrics.churn("backend churn: 30 and 4 changes")).hasValue(30);
        assertThat(EvidenceMetrics.churn("nothing")).isEmpty();
        assertThat(EvidenceMetrics.hotspotThreshold("(Hotspot ab 5)")).hasValue(5);
        assertThat(EvidenceMetrics.hotspotThreshold("hotspot from 5")).hasValue(5);
        assertThat(EvidenceMetrics.hotspotThreshold("x")).isEmpty();
        assertThat(EvidenceMetrics.parse("6 changes")).doesNotContainKey("changes");
    }

    @Test
    void readsCveAndGhsaIdentifiers_canonicalised_inOrderOfFirstAppearance() {
        assertThat(EvidenceMetrics.advisoryIds("ghsa-J9F9-w8pj-32f8, then cve-2026-47890 and CVE-2026-47890"))
                .containsExactly("GHSA-j9f9-w8pj-32f8", "CVE-2026-47890");
        assertThat(EvidenceMetrics.advisoryIds("CVE-20-1 GHSA-abc")).isEmpty();
        assertThat(EvidenceMetrics.parse("CVE-2026-47890")).isEmpty();
    }

    @Test
    void emptyOrNullEvidenceYieldsNothing() {
        assertThat(EvidenceMetrics.parse(null)).isEmpty();
        assertThat(EvidenceMetrics.parse(" ")).isEmpty();
        assertThat(EvidenceMetrics.churn(null)).isEmpty();
        assertThat(EvidenceMetrics.advisoryIds(null)).isEmpty();
    }

    @Test
    void formatsIntegralValuesWithoutDecimals() {
        assertThat(EvidenceMetrics.format(320, "LOC")).isEqualTo("320 LOC");
        assertThat(EvidenceMetrics.format(3.5, "MB")).isEqualTo("3.5 MB");
        assertThat(EvidenceMetrics.format(80, "%")).isEqualTo("80 %");
    }

    /**
     * The units are matched without regard to case, in Unicode: {@code \u017f} (long s) matches an s and
     * {@code \u0130} (dotted capital I) an i, yet neither becomes that letter in lower case, so the match names no
     * unit. Evidence is a model's text: such a match is skipped, it is no reason to fail.
     */
    @Test
    void aUnitThatOnlyMatchesByUnicodeCase_isSkipped() {
        assertThat(EvidenceMetrics.parse("3 te\u017fts, 5 files")).containsOnly(Map.entry("files", 5.0));
        assertThat(EvidenceMetrics.parse("3 f\u0130les")).isEmpty();
    }
}
