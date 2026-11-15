package traceworks.core.app;

import traceworks.core.port.CommandRunner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperatorSettingsTest {

    @Test
    void seals_needNoSealAndCanBeRemovedByDefault_andKeepWhatTheOperatorSets() {
        assertThat(OperatorSettings.defaults().seals()).isEqualTo(SealSettings.DEFAULTS);
        assertThat(SealSettings.DEFAULTS.require()).isEqualTo(SealSettings.Require.NEVER);
        assertThat(SealSettings.DEFAULTS.immutable()).isFalse();
        SealSettings strict = new SealSettings(SealSettings.Require.ALWAYS, true, null, null);

        assertThat(OperatorSettings.defaults().withSeals(strict).seals()).isEqualTo(strict);
        assertThat(OperatorSettings.defaults().withSeals(null).seals()).isEqualTo(SealSettings.DEFAULTS);
        assertThat(SealSettings.Require.of("unattended")).isEqualTo(SealSettings.Require.UNATTENDED);
        assertThat(SealSettings.Require.of("Always")).isNull();
        assertThat(SealSettings.Require.ALWAYS.key()).isEqualTo("always");
    }

    @Test
    void outputLimit_is16MiBByDefault_theRunnersDefault() {
        assertThat(OperatorSettings.defaults().outputLimitMib()).isEqualTo(16);
        assertThat(OperatorSettings.defaults().outputLimitBytes()).isEqualTo(CommandRunner.OUTPUT_LIMIT_BYTES);
        assertThat(new OperatorSettings(false, null, List.of(), "docker", null).outputLimitMib()).isEqualTo(16);
    }

    @Test
    void aiImages_areEmptyByDefault_andKeepValidReferences() {
        assertThat(OperatorSettings.defaults().aiImages()).isEmpty();
        assertThat(new OperatorSettings(false, null, List.of(), null, null, 16, null).aiImages()).isEmpty();
        assertThat(new OperatorSettings(false, null, List.of(), null, null, 16,
                Map.of("claude", "registry.example:5000/ops/claude@sha256:" + "a".repeat(64), "pi", "my-pi")).aiImages())
                .containsOnlyKeys("claude", "pi");
    }

    @Test
    void aiImages_refuseAnUnknownCli_andAnImageThatCannotBeOne() {
        assertThatThrownBy(() -> new OperatorSettings(false, null, List.of(), null, null, 16, Map.of("codex", "x")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'codex'")
                .hasMessageContaining("claude, pi");
        for (String bad : new String[] {"", "  ", "a b", "a\tb", "a\nb", "-x", "--rm", "./images/x", "a\u0000b"}) {
            assertThatThrownBy(() -> new OperatorSettings(false, null, List.of(), null, null, 16,
                    Map.of("claude", bad))).as(bad).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("claude");
            assertThat(OperatorSettings.aiImageProblem(bad)).as(bad).isNotNull();
        }
        assertThat(OperatorSettings.aiImageProblem(null)).isNotNull();
        assertThat(OperatorSettings.aiImageProblem("alpine:3")).isNull();
    }

    /** The reason is what the operator reads in the refusal of {@code ai_images}. */
    @Test
    void aiImageProblem_saysWhatIsWrongWithTheImage() {
        assertThat(OperatorSettings.aiImageProblem(" ")).isEqualTo("must not be blank");
        assertThat(OperatorSettings.aiImageProblem("a b")).isEqualTo("must not hold whitespace or control characters");
        assertThat(OperatorSettings.aiImageProblem("--rm")).isEqualTo("must not start with '-'");
        assertThat(OperatorSettings.aiImageProblem("./images/claude"))
                .isEqualTo("must not be a package image (./images/...)");
    }

    @Test
    void hooks_takeATimeoutOfOneSecondOrMore() {
        assertThat(new OperatorSettings.Hooks(List.of("notify"), 1).timeoutSeconds()).isEqualTo(1);
        assertThatThrownBy(() -> new OperatorSettings.Hooks(List.of("notify"), 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 1 second");
    }

    @Test
    void runOutputBudget_is128MiBByDefault_orEightTimesTheOutputLimit() {
        assertThat(OperatorSettings.defaults().runOutputBudgetMib()).isEqualTo(128);
        assertThat(OperatorSettings.defaults().runOutputBudgetBytes()).isEqualTo(128L * 1024 * 1024);
        assertThat(new OperatorSettings(false, null, List.of(), null, null, 64).runOutputBudgetMib()).isEqualTo(512);
        assertThat(new OperatorSettings(false, null, List.of(), null, null, 1024).runOutputBudgetBytes())
                .isEqualTo(8192L * 1024 * 1024);
        assertThat(OperatorSettings.defaultRunOutputBudgetMib(16)).isEqualTo(128);
    }

    @Test
    void runOutputBudget_isFrom1To65536MiB_andAtLeastTwiceTheOutputLimit() {
        assertThat(new OperatorSettings(false, null, List.of(), null, null, 1, Map.of(), 2).runOutputBudgetBytes())
                .isEqualTo(2L * 1024 * 1024);
        assertThat(new OperatorSettings(false, null, List.of(), null, null, 16, Map.of(), 65536).runOutputBudgetMib())
                .isEqualTo(65536);
        for (int outside : new int[] {0, -1, 65537, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> new OperatorSettings(false, null, List.of(), null, null, 1, Map.of(), outside))
                    .as("%d MiB", outside).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("from 1 to 65536");
        }
        assertThatThrownBy(() -> new OperatorSettings(false, null, List.of(), null, null, 16, Map.of(), 31))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("run output budget 31 MiB must be at least twice the output limit of 16 MiB");
    }

    @Test
    void outputLimit_isFrom1To1024MiB() {
        assertThat(new OperatorSettings(false, null, List.of(), null, null, 1).outputLimitBytes())
                .isEqualTo(1024 * 1024);
        assertThat(new OperatorSettings(false, null, List.of(), null, null, 1024).outputLimitBytes())
                .isEqualTo(1024 * 1024 * 1024);
        for (int outside : new int[] {0, -1, 1025, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> new OperatorSettings(false, null, List.of(), null, null, outside))
                    .as("%d MiB", outside).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("from 1 to 1024");
        }
    }
}
