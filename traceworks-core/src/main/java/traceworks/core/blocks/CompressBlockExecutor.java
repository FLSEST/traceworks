package traceworks.core.blocks;

import traceworks.core.blocks.compress.CompressionException;
import traceworks.core.blocks.compress.CompressionNumbers;
import traceworks.core.blocks.compress.CompressionOutput;
import traceworks.core.blocks.compress.CompressionSources;
import traceworks.core.blocks.compress.RegexGuard;
import traceworks.core.util.TimedRegex;
import traceworks.core.blocks.compress.StageChain;
import traceworks.core.blocks.compress.StageConfig;
import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.BlockExecutor;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code compress}: deterministic compression of one earlier block output through a stage chain. No AI, no process.
 * The original output stays untouched; this step's output is the compressed text.
 */
public class CompressBlockExecutor implements BlockExecutor {

    static final int DEFAULT_MAX_CHARS = 20_000;
    static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** The block's {@code max_chars}: 300 to 1,000,000, default 20,000. @throws CompressionException if invalid */
    public static int maxChars(Map<String, Object> config) {
        return StageConfig.intValue(config, "max_chars", DEFAULT_MAX_CHARS, 300, 1_000_000, "compress");
    }

    /** The block's {@code timeout_seconds}: 1 to 600, default 30. @throws CompressionException if invalid */
    public static int timeoutSeconds(Map<String, Object> config) {
        return StageConfig.intValue(config, "timeout_seconds", DEFAULT_TIMEOUT_SECONDS, 1, 600, "compress");
    }

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        Map<String, Object> config = block.getConfig() != null ? block.getConfig() : Map.of();
        StepResult result = new StepResult();
        result.setStepIndex(context.nextStepIndex());
        result.setStartedAt(context.getClock().now());
        try {
            List<StageChain.Step> steps = StageChain.parse(config.get("stages"));
            int maxChars = maxChars(config);
            int timeout = timeoutSeconds(config);
            CompressionSources.Resolved src = CompressionSources.resolve(context, config.get("source"));
            // On a thread with a hard limit: a stage's pattern is the workflow's, and the regex engine can spend
            // any time without reading a character, which is where the guard's own clock is read
            RegexGuard guard = RegexGuard.startingNow(Duration.ofSeconds(timeout), context.secretValues());
            StageChain.Outcome outcome;
            try {
                outcome = TimedRegex.within(Duration.ofSeconds(timeout), () -> StageChain.run(steps, src.text(), guard));
            } catch (TimedRegex.TimedOut e) {
                throw new CompressionException("Time limit exceeded — regular expression too costly or input too"
                        + " large");
            }
            CompressionOutput.Assembled out = CompressionOutput.assemble(src,
                    "Stages: " + String.join(" · ", outcome.notes()), outcome.text(), maxChars,
                    context.secretValues());
            result.setInput("compress " + src.label() + " (" + CompressionNumbers.fmt(src.text().length()) + " chars): "
                    + steps.stream().map(s -> s.stage().op()).collect(Collectors.joining(" › ")));
            result.setOutput(out.text());
            result.setStatus(StepStatus.COMPLETED);
            result.setMetadata(CompressionOutput.metadata(src, out, maxChars, outcome.stageMetadata()));
        } catch (CompressionException e) {
            fail(result, config, e.getMessage());
        } catch (RuntimeException e) {
            fail(result, config, e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName()
                    : e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        result.setCompletedAt(context.getClock().now());
        result.setDurationMs(Duration.between(result.getStartedAt(), result.getCompletedAt()).toMillis());
        return result;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("compress");
    }

    /** {@code error} may be null (an exception without a message): the step must still say something readable. */
    private static void fail(StepResult result, Map<String, Object> config, String error) {
        String message = error == null || error.isBlank() ? "unknown error" : error;
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("derivedOutput", true);
        metadata.put("error", message);
        result.setInput("compress: " + config.get("source"));
        result.setOutput("Compression failed: " + message);
        result.setStatus(StepStatus.FAILED);
        result.setMetadata(metadata);
    }
}
