package traceworks.core.compat;

import org.junit.jupiter.api.Test;
import traceworks.core.container.HostSecrets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The earlier names are read from machines and reports that exist, so none of them may change by accident. This is
 * the one test that spells them out; every other test takes them from {@link EarlierNames}. The earlier marker of
 * the audit envelope has its reading test in {@code AuditResultParserTest}.
 */
class EarlierNamesTest {

    @Test
    void theEarlierMarkerWord_isWhatReportsOfThatTimeHold() {
        assertThat(EarlierNames.AUDIT_ENVELOPE_WORD).isEqualTo("mysuricates");
    }

    @Test
    void theServiceEnvironment_isTheKeyThePasswordAndTheRunVariables() {
        assertThat(EarlierNames.SERVICE_ENVIRONMENT).containsExactly("AUTOMATION_API_KEY", "AUTOMATION_SSL_PASSWORD",
                "MYSURICATES_RUN_TOKEN", "MYSURICATES_API", "MYSURICATES_RUN_ID");
    }

    @Test
    void theServiceDirectories_areHomeSecrets() {
        assertThat(EarlierNames.SERVICE_DATA_DIRECTORY).isEqualTo(".mysuricates");
        assertThat(EarlierNames.SERVICE_HOME_SECRETS)
                .containsExactly(".mysuricates", ".autotrigger", ".config/mysuricates");
        assertThat(HostSecrets.HOME_SECRETS).containsAll(EarlierNames.SERVICE_HOME_SECRETS);
    }
}
