package traceworks.core.engine;

import traceworks.core.model.BlockDefinition;
import traceworks.core.model.InvalidSettingException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlockOutputValidatorTest {

    private final BlockOutputValidator validator = new BlockOutputValidator();

    private BlockDefinition blockWithValidation(Map<String, Object> validation) {
        BlockDefinition block = new BlockDefinition();
        Map<String, Object> config = new HashMap<>();
        if (validation != null) config.put("validation", validation);
        block.setConfig(config);
        return block;
    }

    @Test
    void validate_noValidationConfig_passes() {
        BlockDefinition block = blockWithValidation(null);
        assertThat(validator.validate(block, "anything").passed()).isTrue();
    }

    @Test
    void validate_containsRequirementMet_passes() {
        BlockDefinition block = blockWithValidation(Map.of("contains", "```json"));
        assertThat(validator.validate(block, "Here is data:\n```json\n{}\n```").passed()).isTrue();
    }

    @Test
    void validate_containsRequirementMissing_fails() {
        BlockDefinition block = blockWithValidation(Map.of("contains", "```json"));
        var result = validator.validate(block, "no markers here");
        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("missing required substring: ```json");
    }

    @Test
    void validate_maxLengthExceeded_fails() {
        BlockDefinition block = blockWithValidation(Map.of("max_length", 10));
        var result = validator.validate(block, "this string is definitely longer than ten chars");
        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("exceeds max_length 10");
    }

    @Test
    void validate_minLengthBelow_fails() {
        BlockDefinition block = blockWithValidation(Map.of("min_length", 50));
        var result = validator.validate(block, "short");
        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("below min_length 50");
    }

    @Test
    void validate_combinedRules_allMustHold() {
        BlockDefinition block = blockWithValidation(Map.of(
                "contains", "json",
                "max_length", 100,
                "min_length", 5
        ));
        assertThat(validator.validate(block, "json: ok").passed()).isTrue();
        assertThat(validator.validate(block, "no markers").passed()).isFalse();
    }

    @Test
    void validate_nullOutput_treatedAsEmpty() {
        BlockDefinition block = blockWithValidation(Map.of("contains", "anything"));
        assertThat(validator.validate(block, null).passed()).isFalse();
    }

    @Test
    void shouldRetry_defaultsToFalse() {
        BlockDefinition block = blockWithValidation(Map.of("contains", "x"));
        assertThat(validator.shouldRetry(block)).isFalse();
    }

    @Test
    void shouldRetry_explicitTrue_returnsTrue() {
        BlockDefinition block = blockWithValidation(Map.of(
                "contains", "x",
                "retry_on_fail", true
        ));
        assertThat(validator.shouldRetry(block)).isTrue();
    }

    @Test
    void maxRetries_defaultsToOneWhenRetryConfigured() {
        BlockDefinition block = blockWithValidation(Map.of(
                "contains", "x",
                "retry_on_fail", true
        ));
        assertThat(validator.maxRetries(block)).isEqualTo(1);
    }

    @Test
    void maxRetries_explicitValue_returnsValue() {
        BlockDefinition block = blockWithValidation(Map.of(
                "contains", "x",
                "retry_on_fail", true,
                "max_retries", 3
        ));
        assertThat(validator.maxRetries(block)).isEqualTo(3);
    }

    @Test
    void maxRetries_noConfig_returnsZero() {
        BlockDefinition block = blockWithValidation(null);
        assertThat(validator.maxRetries(block)).isEqualTo(0);
    }

    /** B2: a value of the wrong type validated nothing; it fails the block now, with the key it names. */
    @Test
    void readSettings_aWrongTypeOrANonObject_fails() {
        assertThatThrownBy(() -> validator.readSettings(blockWithValidation(Map.of("min_length", "100"))))
                .isInstanceOf(InvalidSettingException.class)
                .hasMessage("block: 'validation.min_length' must be a whole number between 0 and 2,147,483,647,"
                        + " found the text \"100\"");
        assertThatThrownBy(() -> validator.readSettings(blockWithValidation(Map.of("contains", 5))))
                .hasMessage("block: 'validation.contains' must be text, found 5");
        assertThatThrownBy(() -> validator.readSettings(blockWithValidation(Map.of("retry_on_fail", "true"))))
                .hasMessage("block: 'validation.retry_on_fail' must be true or false, found the text \"true\"");
        BlockDefinition text = new BlockDefinition();
        text.setType("shell");
        text.setConfig(new HashMap<>(Map.of("validation", "OK")));
        assertThatThrownBy(() -> validator.readSettings(text))
                .hasMessage("shell: 'validation' must be an object, found the text \"OK\"");
    }

    @Test
    void readSettings_rightValues_pass_andMaxRetriesIsInRange() {
        validator.readSettings(blockWithValidation(Map.of("contains", "OK", "min_length", 1, "max_length", 9,
                "retry_on_fail", true, "max_retries", 2)));
        validator.readSettings(blockWithValidation(null));
        assertThat(validator.maxRetries(blockWithValidation(Map.of("max_retries", 2)))).isEqualTo(2);
        assertThat(validator.maxRetries(blockWithValidation(Map.of()))).isEqualTo(1);
        assertThat(validator.maxRetries(blockWithValidation(null))).isZero();
        assertThatThrownBy(() -> validator.maxRetries(blockWithValidation(Map.of("max_retries", -1))))
                .isInstanceOf(InvalidSettingException.class);
    }
}
