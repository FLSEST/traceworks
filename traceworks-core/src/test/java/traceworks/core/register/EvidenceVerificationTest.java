package traceworks.core.register;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A finding's evidence must name a fact a step of this run measured; the English and the German markers are accepted.
 */
class EvidenceVerificationTest {

    private static final String SHEET = """
            ## FACT SHEET hello
            FACT files_over_300: 9
            FACT ncloc_total: 27228
            HIT secret_in_config: application.yml:12
            FACT ab: 1
            """;

    @Test
    void factNames_collectsFactAndHitNames_fromAFactSheet() {
        assertThat(EvidenceVerification.factNames(List.of(SHEET)))
                .containsExactly("files_over_300", "ncloc_total", "secret_in_config");
    }

    @Test
    void factNames_acceptsTheGermanMarkers() {
        String sheet = "## FAKTENBLATT\nFAKT zeilen_gesamt: 3\nTREFFER geheimnis: a:1\n";

        assertThat(EvidenceVerification.factNames(List.of(sheet))).containsExactly("zeilen_gesamt", "geheimnis");
    }

    @Test
    void anOutputWithoutTheHeader_contributesNothing() {
        assertThat(EvidenceVerification.factNames(java.util.Arrays.asList("FACT files_over_300: 9\n", null, ""))).isEmpty();
    }

    @Test
    void evidenceThatNamesAFact_isSupported() {
        Set<String> names = EvidenceVerification.factNames(List.of(SHEET));

        assertThat(EvidenceVerification.isSupported("FACT files_over_300: 9 (see the sheet)", names)).isTrue();
        assertThat(EvidenceVerification.isSupported("ncloc_total is high", names)).isTrue();
    }

    @Test
    void inventedOrRecycledEvidence_fails() {
        Set<String> names = EvidenceVerification.factNames(List.of(SHEET));

        assertThat(EvidenceVerification.isSupported("FACT files_over_500: 2", names)).isFalse();
        assertThat(EvidenceVerification.isSupported("", names)).isFalse();
        assertThat(EvidenceVerification.isSupported(null, names)).isFalse();
    }

    @Test
    void withoutAFactSheet_everyFindingIsSupported() {
        assertThat(EvidenceVerification.isSupported("anything", Set.of())).isTrue();
        assertThat(EvidenceVerification.isSupported(null, Set.of())).isTrue();
    }
}
