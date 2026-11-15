package traceworks.selfaudit.build;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VersionsTest {

    @Test
    void compare_ordersNumbersAsNumbersAndReleasesAfterQualifiers() {
        assertThat(Versions.compare("2.10.0", "2.9.9")).isPositive();
        assertThat(Versions.compare("2.22", "2.22.0")).isZero();
        assertThat(Versions.compare("1.18.17", "1.18.17-jdk5")).isPositive();
        assertThat(Versions.compare("2.0", "2.0-rc1")).isPositive();
        assertThat(Versions.compare("3.0.0-M5", "2.22.2")).isPositive();
        assertThat(Versions.compare("010", "9")).isPositive();
        assertThat(Versions.compare("1.0", "1.0")).isZero();
    }

    @Test
    void jump_isTheFirstNumberThatDiffers() {
        assertThat(Versions.jump("0.74.2", "1.5.2")).isEqualTo("major");
        assertThat(Versions.jump("2.22.4", "2.23.0")).isEqualTo("minor");
        assertThat(Versions.jump("2.1.288", "2.1.322")).isEqualTo("patch");
        assertThat(Versions.jump("2.22", "2.22.1")).isEqualTo("patch");
    }

    @Test
    void newer_keepsOnlyNewerReleasesOfTheRuleFile() {
        RuleFile rules = RuleFile.parse("");

        assertThat(Versions.newer("3.27.7", List.of("3.27.6", "3.27.7", "4.0.0-M1", "3.28.0", "3.27.10"), rules))
                .containsExactly("3.27.10", "3.28.0");
        assertThat(Versions.newer("1.18.17", List.of("1.18.17-jdk5", "1.18.17"), rules)).isEmpty();
    }
}
