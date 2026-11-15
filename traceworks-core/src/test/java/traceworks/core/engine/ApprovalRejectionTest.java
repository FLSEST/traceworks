package traceworks.core.engine;

import traceworks.core.compat.GermanWords;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalRejectionTest {

    /** The German "no" existing workflows offer as an option. */
    private static final String GERMAN_NO = GermanWords.APPROVAL_REJECT_ANSWER;

    @Test
    void withoutConfig_noAndTheGermanNoAreRejections_caseAndPaddingIgnored() {
        List<String> rejects = ApprovalRejection.rejectAnswers(null);

        assertThat(ApprovalRejection.isRejection("No", rejects)).isTrue();
        assertThat(ApprovalRejection.isRejection(" " + GERMAN_NO.toLowerCase(Locale.ROOT) + " ", rejects)).isTrue();
        assertThat(ApprovalRejection.isRejection(GERMAN_NO.toUpperCase(Locale.ROOT), rejects)).isTrue();
        assertThat(ApprovalRejection.isRejection("Yes", rejects)).isFalse();
        assertThat(ApprovalRejection.isRejection("Yes always", rejects)).isFalse();
        assertThat(ApprovalRejection.isRejection(null, rejects)).isFalse();
    }

    @Test
    void configuredRejectOptions_replaceTheDefault() {
        List<String> rejects = ApprovalRejection.rejectAnswers(List.of("Discard", "Stop"));

        assertThat(ApprovalRejection.isRejection("stop", rejects)).isTrue();
        assertThat(ApprovalRejection.isRejection("Discard", rejects)).isTrue();
        assertThat(ApprovalRejection.isRejection("No", rejects)).isFalse();
    }

    @Test
    void unusableConfig_fallsBackToDefault() {
        assertThat(ApprovalRejection.rejectAnswers("No")).isEqualTo(ApprovalRejection.DEFAULT_REJECT_ANSWERS);
        assertThat(ApprovalRejection.rejectAnswers(List.of())).isEqualTo(ApprovalRejection.DEFAULT_REJECT_ANSWERS);
        assertThat(ApprovalRejection.rejectAnswers(List.of(" ", 3))).isEqualTo(ApprovalRejection.DEFAULT_REJECT_ANSWERS);
    }

    @Test
    void rejectingOptions_keepsOptionSpelling() {
        List<String> rejects = ApprovalRejection.rejectAnswers(null);

        assertThat(ApprovalRejection.rejectingOptions(List.of("Yes", GERMAN_NO), rejects)).containsExactly(GERMAN_NO);
        assertThat(ApprovalRejection.rejectingOptions(List.of("Yes", "Yes always", "No"), rejects)).containsExactly("No");
        assertThat(ApprovalRejection.rejectingOptions(List.of("Continue", "Cancel"), rejects)).isEmpty();
        assertThat(ApprovalRejection.rejectingOptions(null, rejects)).isEmpty();
    }
}
