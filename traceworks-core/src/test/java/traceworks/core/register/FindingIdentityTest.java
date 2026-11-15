package traceworks.core.register;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The identity of a finding: the register key, the rule key and the subject, hashed; no normalisation. */
class FindingIdentityTest {

    @Test
    void identity_isSha256OfKeyRuleAndSubject_joinedWithAPipe() {
        assertThat(FindingIdentity.of("arch", "verdict-split", "a/b.java"))
                .isEqualTo(VECTOR).hasSize(64);
    }

    static final String VECTOR = "9d3692a814c587bb77b96c70e0fd346bdc66254274cce551a2f324135023afea";

    /** {@code a|b} + {@code c} and {@code a} + {@code b|c} are different findings: the separator is escaped. */
    @Test
    void identity_escapesTheSeparator() {
        assertThat(FindingIdentity.of("k", "a|b", "c")).isNotEqualTo(FindingIdentity.of("k", "a", "b|c"));
        assertThat(FindingIdentity.of("k", "a\\", "|c")).isNotEqualTo(FindingIdentity.of("k", "a", "\\|c"));
    }

    @Test
    void identity_isStable_andCaseSensitive_withoutNormalisation() {
        assertThat(FindingIdentity.of("k", "r", "S.java")).isEqualTo(FindingIdentity.of("k", "r", "S.java"))
                .isNotEqualTo(FindingIdentity.of("k", "r", "s.java"))
                .isNotEqualTo(FindingIdentity.of("k", "r", "src/S.java"));
    }
}
