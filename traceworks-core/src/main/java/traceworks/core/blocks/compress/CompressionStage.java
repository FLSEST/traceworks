package traceworks.core.blocks.compress;

import java.util.Map;
import java.util.Set;

/** One deterministic compression operation. Implementations are stateless and thread-safe. */
public interface CompressionStage {

    String op();

    /** The settings this stage reads besides {@code op}; any other key in a stage is ignored by the run. */
    Set<String> settings();

    /** The settings that are on/off flags: only {@code true} switches them on, any other value counts as off. */
    default Set<String> flags() {
        return Set.of();
    }

    StageKind inputKind();

    StageKind outputKind();

    /** Checks the stage config before anything runs. @throws CompressionException with a user-facing message */
    void validate(Map<String, Object> config);

    StageResult apply(String input, Map<String, Object> config, RegexGuard guard);
}
