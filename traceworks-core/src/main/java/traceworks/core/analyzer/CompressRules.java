package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.blocks.CompressAiBlockExecutor;
import traceworks.core.blocks.CompressBlockExecutor;
import traceworks.core.blocks.compress.CompressionException;
import traceworks.core.blocks.compress.CompressionStage;
import traceworks.core.blocks.compress.CompressionStages;
import traceworks.core.blocks.compress.StageChain;
import traceworks.core.engine.NestedBlocks;
import traceworks.core.model.BlockDefinition;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The settings of a {@code compress} block and its stages, and the {@code max_chars} and {@code timeout_seconds} of a
 * {@code compress-ai} block, top-level and nested (switch cases, {@code default}, {@code blocks}). The check asks the stages themselves ({@link StageChain#problems}), so it says what the run does:
 *
 * <ul>
 *   <li>{@code invalid-config-value} ({@code ERROR}): {@code stages} is no non-empty list, a stage is no object, has
 *       an unknown or missing {@code op}, a stage expecting JSON follows a stage that yields text, a stage setting is
 *       missing or has an invalid value, or {@code max_chars} / {@code timeout_seconds} are out of range. The run
 *       validates the whole chain before any stage runs and fails the step on each of these.</li>
 *   <li>{@code unknown-config-key} ({@code WARNING}): a stage names a setting its {@code op} does not read; the run
 *       ignores it. With a suggestion for a near spelling.</li>
 *   <li>{@code invalid-config-value} ({@code WARNING}): a flag ({@code count}, {@code consecutive_only} of
 *       {@code lines.dedupe}) that is not {@code true} or {@code false}; the run counts anything but {@code true} as
 *       off.</li>
 * </ul>
 *
 * <p>The run does not resolve placeholders in a compress block's config, so a {@code {{...}}} in a stage is taken
 * literally and checked as it stands. A missing {@code stages} key is reported by {@link ConfigKeyRules}.
 */
final class CompressRules {

    private CompressRules() {}

    static void apply(List<BlockDefinition> blocks, List<AnalyzerFinding> out) {
        for (int i = 0; i < blocks.size(); i++) {
            BlockDefinition block = blocks.get(i);
            if (block == null) continue;
            int index = i;
            String outer = block.getName();
            if ("compress".equals(block.getType())) check(block.getConfig(), index, outer, "", out);
            if ("compress-ai".equals(block.getType())) checkAi(block.getConfig(), index, outer, "", out);
            NestedBlocks.visit(block.getConfig(), nested -> {
                String prefix = FindingText.nestedPath(index, nested) + ": ";
                if ("compress".equals(nested.type())) check(nested.config(), index, outer, prefix, out);
                if ("compress-ai".equals(nested.type())) checkAi(nested.config(), index, outer, prefix, out);
            });
        }
    }

    private static void check(Map<String, Object> config, int index, String name, String prefix,
                              List<AnalyzerFinding> out) {
        Map<String, Object> cfg = config != null ? config : Map.of();
        settingError(() -> CompressBlockExecutor.maxChars(cfg), index, name, prefix, out);
        settingError(() -> CompressBlockExecutor.timeoutSeconds(cfg), index, name, prefix, out);
        if (cfg.get("stages") == null) return;   // missing-required-config-key says it
        for (CompressionException problem : StageChain.problems(cfg.get("stages"))) {
            out.add(AnalyzerFinding.of("invalid-config-value", Severity.ERROR, index, name,
                    prefix + problem.getMessage() + " — the step fails when it is reached"));
        }
        if (cfg.get("stages") instanceof List<?> stages) {
            for (int i = 0; i < stages.size(); i++) {
                if (stages.get(i) instanceof Map<?, ?> stage) ignoredSettings(i + 1, stage, index, name, prefix, out);
            }
        }
    }

    /** {@code compress-ai}: {@code max_chars} and {@code timeout_seconds} as its executor checks them. */
    private static void checkAi(Map<String, Object> config, int index, String name, String prefix,
                                List<AnalyzerFinding> out) {
        Map<String, Object> cfg = config != null ? config : Map.of();
        settingError(() -> CompressAiBlockExecutor.maxChars(cfg), index, name, prefix, out);
        settingError(() -> CompressAiBlockExecutor.timeoutSeconds(cfg), index, name, prefix, out);
    }

    private static void settingError(Supplier<Integer> read, int index, String name, String prefix,
                                     List<AnalyzerFinding> out) {
        try {
            read.get();
        } catch (CompressionException e) {
            out.add(AnalyzerFinding.of("invalid-config-value", Severity.ERROR, index, name,
                    prefix + e.getMessage() + " — the step fails when it is reached"));
        }
    }

    /** Settings the stage's op does not read, and flags that are not a boolean: the run takes no notice of them. */
    private static void ignoredSettings(int position, Map<?, ?> stage, int index, String name, String prefix,
                                        List<AnalyzerFinding> out) {
        if (!(stage.get("op") instanceof String op)) return;
        CompressionStage known = CompressionStages.find(op).orElse(null);
        if (known == null) return;   // the unknown op is an error already
        String where = prefix + "Stage " + position + " (" + op + "): ";
        Set<String> settings = new HashSet<>(known.settings());
        for (Object key : stage.keySet()) {
            String setting = String.valueOf(key);
            if ("op".equals(setting) || settings.contains(setting)) continue;
            String suggestion = FindingText.suggestion(setting, settings);
            out.add(AnalyzerFinding.of("unknown-config-key", Severity.WARNING, index, name,
                    where + "unknown setting " + setting + " — the " + op + " stage does not read it, the setting has "
                            + "no effect" + (suggestion == null ? "" : "; did you mean " + suggestion + "?")));
        }
        // In the order the stage names them, not the set's, so the findings come out the same every time
        for (Object key : stage.keySet()) {
            String flag = String.valueOf(key);
            if (!known.flags().contains(flag)) continue;
            Object value = stage.get(key);
            if (value == null || value instanceof Boolean) continue;
            String shown = value instanceof String ? "\"" + value + "\"" : String.valueOf(value);
            out.add(AnalyzerFinding.of("invalid-config-value", Severity.WARNING, index, name,
                    where + flag + ": " + shown + " is not a valid value, allowed: true, false — the run counts "
                            + "anything but true as off"));
        }
    }
}
