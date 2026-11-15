package traceworks.core.engine;

import traceworks.core.model.BlockDefinition;
import traceworks.core.model.BlockSettings;
import traceworks.core.model.InvalidSettingException;

/**
 * Validates a block's output against the rules declared in {@code block.config.validation}. The step runner uses it
 * to decide whether a block needs to run again.
 *
 * <p>Supported rules (all optional, all combined with AND):
 * <ul>
 *   <li>{@code contains}: the output must contain this substring</li>
 *   <li>{@code max_length}: the output length must be at most this value</li>
 *   <li>{@code min_length}: the output length must be at least this value</li>
 * </ul>
 * {@code retry_on_fail} (boolean, default false) and {@code max_retries} (0 to 100, default 1) say whether and how
 * often a rejected output runs the block again. Every value is read through {@link BlockSettings}: a {@code validation}
 * that is no object, or a key of the wrong type, fails the block instead of validating nothing.
 */
final class BlockOutputValidator {

    record ValidationResult(boolean passed, String reason) {
        static ValidationResult pass() {
            return new ValidationResult(true, null);
        }

        static ValidationResult fail(String reason) {
            return new ValidationResult(false, reason);
        }
    }

    /**
     * The block's {@code validation} settings, all of them read, so a wrong one fails the block before it runs
     * instead of validating nothing.
     *
     * @throws InvalidSettingException for a {@code validation} that is no object or a key of the wrong type
     */
    void readSettings(BlockDefinition block) {
        BlockSettings validation = validation(block);
        validation.text("contains");
        validation.number("max_length");
        validation.number("min_length");
        validation.flag("retry_on_fail");
        validation.number("max_retries");
    }

    private static BlockSettings validation(BlockDefinition block) {
        return BlockSettings.of(block).within("validation");
    }

    ValidationResult validate(BlockDefinition block, String output) {
        BlockSettings config = validation(block);
        String safeOutput = output == null ? "" : output;

        String contains = config.text("contains");
        if (contains != null && !contains.isEmpty() && !safeOutput.contains(contains)) {
            return ValidationResult.fail("output missing required substring: " + contains);
        }

        Integer maxLength = config.number("max_length");
        if (maxLength != null && safeOutput.length() > maxLength) {
            return ValidationResult.fail("output length " + safeOutput.length() + " exceeds max_length " + maxLength);
        }

        Integer minLength = config.number("min_length");
        if (minLength != null && safeOutput.length() < minLength) {
            return ValidationResult.fail("output length " + safeOutput.length() + " below min_length " + minLength);
        }

        return ValidationResult.pass();
    }

    boolean shouldRetry(BlockDefinition block) {
        return validation(block).flag("retry_on_fail");
    }

    /** {@code max_retries}: 0 to 100, default 1; 0 without a {@code validation}. */
    int maxRetries(BlockDefinition block) {
        boolean declared = block != null && block.getConfig() != null && block.getConfig().get("validation") != null;
        return declared ? validation(block).number("max_retries", 1) : 0;
    }
}
