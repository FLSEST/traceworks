package traceworks.core.blocks;

import traceworks.core.ai.ToolPolicy;
import traceworks.core.blocks.compress.CompressAiPrompt;
import traceworks.core.blocks.compress.CompressionException;
import traceworks.core.blocks.compress.CompressionNumbers;
import traceworks.core.blocks.compress.CompressionOutput;
import traceworks.core.blocks.compress.CompressionSources;
import traceworks.core.blocks.compress.StageConfig;
import traceworks.core.blocks.compress.TextTruncateStage;
import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.BlockExecutor;
import traceworks.core.engine.EventLogException;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.BlockSettings;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.port.AiProvider;
import traceworks.core.port.Clock;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * {@code compress-ai}: condenses one earlier block output with a single AI call that has no tools, gets only the source
 * text and a literal instruction, and is bounded on input ({@code maxAiInputChars}) and output ({@code max_chars},
 * deterministic cut). Like every AI call it runs in its CLI's container, which sees the workspace read-only; an
 * empty temporary directory on the host is no alternative, because a container would not see it. It supports only the
 * provider CLAUDE.
 */
public class CompressAiBlockExecutor implements BlockExecutor {

    static final int DEFAULT_MAX_CHARS = 4_000;
    /** The largest source text in chars sent to the model; above it the step fails without an AI call. */
    static final int DEFAULT_MAX_AI_INPUT_CHARS = 60_000;
    private static final int HEADER_RESERVE = 300;
    private static final int MAX_AI_ERROR_CHARS = 2_000;

    private final AiStepCall aiCall;
    private final int maxAiInputChars;

    public CompressAiBlockExecutor() {
        this(new AiStepCall(), DEFAULT_MAX_AI_INPUT_CHARS);
    }

    CompressAiBlockExecutor(AiStepCall aiCall, int maxAiInputChars) {
        this.aiCall = aiCall;
        this.maxAiInputChars = maxAiInputChars;
    }

    /** The block's {@code max_chars}: 500 to 200,000, default 4,000. @throws CompressionException if invalid */
    public static int maxChars(Map<String, Object> config) {
        return StageConfig.intValue(config, "max_chars", DEFAULT_MAX_CHARS, 500, 200_000, "compress-ai");
    }

    /**
     * The block's {@code timeout_seconds}: 1 to 86,400, the range of every AI call's timeout; {@code null} when absent,
     * for the provider's default. @throws CompressionException if invalid
     */
    public static Integer timeoutSeconds(Map<String, Object> config) {
        BlockSettings.Range range = BlockSettings.range("timeout_seconds");
        return config == null || config.get("timeout_seconds") == null ? null
                : StageConfig.intValue(config, "timeout_seconds", null, (int) range.min(), (int) range.max(),
                        "compress-ai");
    }

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        Map<String, Object> config = block.getConfig() != null ? block.getConfig() : Map.of();
        Clock clock = context.getClock();
        int stepIndex = context.nextStepIndex();
        Instant started = clock.now();
        StepResult result;
        Map<String, Object> aiMetadata = Map.of();
        try {
            int maxChars = maxChars(config);
            String instruction = StageConfig.string(config, "instruction", "", "compress-ai");
            Integer timeout = timeoutSeconds(config);
            CompressionSources.Resolved src = CompressionSources.resolve(context, config.get("source"));
            if (src.text().length() > maxAiInputChars) {
                throw new CompressionException("Input " + CompressionNumbers.fmt(src.text().length()) + " chars > limit "
                        + CompressionNumbers.fmt(maxAiInputChars) + " — put a compress block before this one");
            }
            String providerId = context.providerFor(block);
            AiProvider provider = context.getAiProviders().resolve(providerId);
            if (!"CLAUDE".equals(provider.getId())) {
                throw new CompressionException("Provider " + provider.getId() + ": compress-ai supports only the"
                        + " provider CLAUDE — set \"provider\": \"CLAUDE\" in the block");
            }
            String prompt = CompressAiPrompt.build(instruction,
                    Math.max(CompressionOutput.MIN_BODY_CHARS, maxChars - HEADER_RESERVE), src.text());
            StepResult ai = aiCall.run(AiStepRequest.builder()
                    .workingDirectory(context.getProjectPath())
                    .prompt(prompt)
                    .stepIndex(stepIndex)
                    .systemPrompt(CompressAiPrompt.SYSTEM)
                    .model(context.modelFor(block))
                    .timeoutSeconds(timeout)
                    .providerId(providerId)
                    .effort(context.effortFor(block))
                    .tools(ToolPolicy.NONE)
                    .build(), context);
            if (ai.getMetadata() != null) aiMetadata = ai.getMetadata();
            Map<String, Object> metadata = new HashMap<>(aiMetadata);
            String input = "compress-ai " + src.label() + " (" + CompressionNumbers.fmt(src.text().length()) + " chars): "
                    + (instruction.isBlank() ? "(default summary)" : instruction);
            String answer = ai.getOutput() == null ? "" : ai.getOutput().strip();
            if (ai.getStatus() != StepStatus.COMPLETED || answer.isEmpty()) {
                metadata.put("derivedOutput", true);
                result = finished(stepIndex, started, clock, input, aiFailureOutput(ai, context.secretValues()),
                        StepStatus.FAILED, metadata);
            } else {
                CompressionOutput.Assembled out = CompressionOutput.assemble(src,
                        "Summary: AI (" + provider.getId() + ")", answer, maxChars, context.secretValues());
                metadata.putAll(CompressionOutput.metadata(src, out, maxChars, null));
                result = finished(stepIndex, started, clock, input, out.text(), StepStatus.COMPLETED, metadata);
            }
        } catch (CompressionException e) {
            result = failed(stepIndex, started, clock, config, e.getMessage(), aiMetadata);
        } catch (EventLogException e) {
            throw e;   // the record of the call could not be written: the run stops
        } catch (RuntimeException e) {
            result = failed(stepIndex, started, clock, config, e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName()
                    : e.getClass().getSimpleName() + ": " + e.getMessage(), aiMetadata);
        }
        return result;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("compress-ai");
    }

    /** Failed call: its (tail-capped) error text; a completed call without text is a failure too. */
    private static String aiFailureOutput(StepResult ai, java.util.Collection<String> keepWhole) {
        String prefix = "AI compression failed: ";
        if (ai.getStatus() == StepStatus.COMPLETED) return prefix + "AI returned no answer";
        String output = ai.getOutput() == null || ai.getOutput().isBlank() ? "no output" : ai.getOutput();
        return prefix + TextTruncateStage.truncate(output, MAX_AI_ERROR_CHARS, TextTruncateStage.Keep.TAIL, keepWhole);
    }

    /** {@code error} may be null (an exception without a message): the step must still say something readable. */
    private static StepResult failed(int stepIndex, Instant started, Clock clock, Map<String, Object> config,
                                     String error, Map<String, Object> aiMetadata) {
        String message = error == null || error.isBlank() ? "unknown error" : error;
        Map<String, Object> metadata = new HashMap<>(aiMetadata);
        metadata.put("derivedOutput", true);
        metadata.put("error", message);
        return finished(stepIndex, started, clock, "compress-ai: " + config.get("source"),
                "Compression failed: " + message, StepStatus.FAILED, metadata);
    }

    private static StepResult finished(int stepIndex, Instant started, Clock clock, String input, String output,
                                       StepStatus status, Map<String, Object> metadata) {
        Instant completed = clock.now();
        return StepResult.builder().stepIndex(stepIndex).startedAt(started).completedAt(completed)
                .durationMs(Duration.between(started, completed).toMillis())
                .input(input).output(output).status(status).metadata(metadata).build();
    }
}
