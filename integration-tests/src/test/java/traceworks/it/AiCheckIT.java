package traceworks.it;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code traceworks check} on the packages of the AI tests: no error, and no warning but {@code unwired-output}, which
 * every AI step whose answer no later step reads earns. ({@code ai-codex} is the one package that must fail its check:
 * {@link AiImagesIT} covers it.) The package {@code ai-compress} also has an info finding about its shell step.
 * {@code combo-model-text} carries one error on purpose: what a model wrote in the arguments of a tool.
 */
class AiCheckIT extends IntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"ai-simple", "ai-tools", "ai-wrapper-tools", "ai-agentic", "ai-compress", "ai-branches", "ai-isolation",
            "ai-marker", "ai-timeout", "ai-gates", "ai-egress", "ai-egress-slow", "ai-web", "ai-interrupt-loop",
            "combo-ai-children", "combo-model-choice"})
    void everyAiPackage_checksWithoutAnError(String name) {
        Result check = traceworks.exec("check", traceworks.packageDir(name).toString());

        assertThat(check.code()).as(check.describe()).isZero();
        assertThat(check.out()).doesNotContainPattern("(?m)^ERROR ");
        assertThat(check.out()).doesNotContainPattern("(?m)^WARNING +(?!unwired-output)");
    }

    /**
     * The package of {@link AiModelTextIT} names what a model wrote in the arguments of a tool, so that the run
     * refuses it: {@code check} reports that one place before a run (docs/cli.md, "check"), and nothing else. Its
     * children take what the parent hands over, which only the run knows.
     */
    @Test
    void thePackageWithModelTextInAToolArgument_checksWithThatOneError() {
        Result check = traceworks.exec("check", traceworks.packageDir("combo-model-text").toString());

        assertThat(check.code()).as(check.describe()).isEqualTo(6);
        assertThat(check.out().lines().filter(line -> line.startsWith("ERROR"))).singleElement().asString()
                .containsPattern("^ERROR +protected-value-outside-shell +in_args \\(block 7\\): puts"
                        + " \\{\\{ block\\.ask\\.output }} into container_args, the output of the AI step ask");
        assertThat(check.out()).doesNotContainPattern("(?m)^WARNING +(?!unwired-output)");
        for (String child : new String[] {"receive", "in-line", "in-mount"}) {
            Result childCheck = traceworks.exec("check",
                    traceworks.packageDir("combo-model-text").resolve(child).toString());

            assertThat(childCheck.code()).as(childCheck.describe()).isZero();
        }
    }
}
