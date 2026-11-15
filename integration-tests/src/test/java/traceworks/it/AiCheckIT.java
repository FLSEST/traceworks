package traceworks.it;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code traceworks check} on the packages of the AI tests: no error, and no warning but {@code unwired-output}, which
 * every AI step whose answer no later step reads earns. ({@code ai-codex} is the one package that must fail its check:
 * {@link AiImagesIT} covers it.) The package {@code ai-compress} also has an info finding about its shell step.
 */
class AiCheckIT extends IntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"ai-simple", "ai-tools", "ai-wrapper-tools", "ai-agentic", "ai-compress", "ai-branches", "ai-isolation",
            "ai-marker", "ai-timeout", "ai-gates", "ai-egress", "ai-egress-slow", "ai-web"})
    void everyAiPackage_checksWithoutAnError(String name) {
        Result check = traceworks.exec("check", traceworks.packageDir(name).toString());

        assertThat(check.code()).as(check.describe()).isZero();
        assertThat(check.out()).doesNotContainPattern("(?m)^ERROR ");
        assertThat(check.out()).doesNotContainPattern("(?m)^WARNING +(?!unwired-output)");
    }
}
