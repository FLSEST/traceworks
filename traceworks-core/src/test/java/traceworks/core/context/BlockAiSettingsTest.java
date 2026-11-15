package traceworks.core.context;

import traceworks.core.model.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlockAiSettingsTest {

    private static BlockDefinition block(Map<String, Object> config) {
        return BlockDefinition.builder().type("simple").config(new HashMap<>(config)).build();
    }

    @Test
    void providerFor_blockWinsOverWorkflow() {
        assertThat(BlockAiSettings.providerFor(block(Map.of("provider", "PI")), "PI")).isEqualTo("PI");
    }

    @Test
    void providerFor_fallsBackToWorkflow_thenNull() {
        assertThat(BlockAiSettings.providerFor(block(Map.of()), "PI")).isEqualTo("PI");
        assertThat(BlockAiSettings.providerFor(block(Map.of()), null)).isNull();
        assertThat(BlockAiSettings.providerFor(null, "PI")).isEqualTo("PI");
    }

    @Test
    void providerFor_blankValuesAreIgnored_nonStringValuesFail() {
        assertThat(BlockAiSettings.providerFor(block(Map.of("provider", "  ")), "PI")).isEqualTo("PI");
        assertThat(BlockAiSettings.providerFor(block(Map.of("provider", " pi ")), "PI")).isEqualTo("pi");
        assertThatThrownBy(() -> BlockAiSettings.providerFor(block(Map.of("provider", 42)), "PI"))
                .isInstanceOf(traceworks.core.model.InvalidSettingException.class)
                .hasMessageEndingWith("'provider' must be text, found 42");
    }

    @Test
    void modelFor_blockWinsOverWorkflow() {
        assertThat(BlockAiSettings.modelFor(block(Map.of("model", "opus")), "CLAUDE", "sonnet")).isEqualTo("opus");
    }

    @Test
    void modelFor_inheritsWorkflowModel_whenBlockKeepsProvider() {
        assertThat(BlockAiSettings.modelFor(block(Map.of()), "CLAUDE", "sonnet")).isEqualTo("sonnet");
        assertThat(BlockAiSettings.modelFor(block(Map.of("provider", "claude")), "CLAUDE", "sonnet")).isEqualTo("sonnet");
    }

    @Test
    void modelFor_dropsWorkflowModel_whenBlockSwitchesProvider() {
        // model ids are provider-specific: "sonnet" (Claude alias) is meaningless for pi
        assertThat(BlockAiSettings.modelFor(block(Map.of("provider", "PI")), "CLAUDE", "sonnet")).isNull();
        assertThat(BlockAiSettings.modelFor(block(Map.of("provider", "PI")), null, "sonnet")).isNull();
    }

    @Test
    void modelFor_nothingSet_isNull() {
        assertThat(BlockAiSettings.modelFor(block(Map.of()), null, null)).isNull();
        assertThat(BlockAiSettings.modelFor(null, "CLAUDE", null)).isNull();
    }

    @Test
    void effortFor_blockWins_invalidIgnored() {
        assertThat(BlockAiSettings.effortFor(block(Map.of("effort", "XHIGH")), "low")).isEqualTo("xhigh");
        assertThat(BlockAiSettings.effortFor(block(Map.of("effort", "turbo")), "low")).isEqualTo("low");
        assertThat(BlockAiSettings.effortFor(block(Map.of()), null)).isNull();
    }

    /**
     * A package's {@code effort} may hold terminal escapes (or a secret's value), and java.util.logging prints its
     * warnings to stderr as they are, past the terminal's safe output: the warning names no value.
     */
    @Test
    void effortFor_anInvalidValue_isLoggedWithoutTheValue() {
        String hostile = "x\u001b]0;pwned\u0007\u001b[2J";
        Logger logger = Logger.getLogger(BlockAiSettings.class.getName());
        List<LogRecord> logged = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            assertThat(BlockAiSettings.effortFor(block(Map.of("effort", hostile)), "low")).isEqualTo("low");
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
        }

        assertThat(logged).singleElement().satisfies(record -> {
            String shown = new SimpleFormatter().formatMessage(record);
            assertThat(shown).startsWith("Ignoring an invalid block effort").doesNotContain("pwned")
                    .doesNotContain("\u001b");
        });
    }
}
