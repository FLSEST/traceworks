package traceworks.core.analyzer;

import traceworks.core.ai.ToolPolicy;
import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.context.UntrustedInput;
import traceworks.core.engine.ExecutionMode;
import traceworks.core.engine.RunRequirements;
import traceworks.core.engine.SecretNames;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.BlockSettings;

import traceworks.core.port.WebAccess;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Config keys against the {@link BlockConfigSchema}, top-level and nested (switch cases, {@code default},
 * {@code blocks}).
 *
 * <ul>
 *   <li>{@code unknown-config-key} ({@code WARNING}): no executor reads the key, the setting has no effect:
 *       {@code command} instead of {@code goal} in the agentic block, {@code container_image} of a retry block
 *       without {@code execution_mode: shell}. With a suggestion for a near spelling.</li>
 *   <li>{@code missing-required-config-key} ({@code ERROR}): without it the block cannot run.</li>
 *   <li>{@code invalid-config-value} ({@code ERROR} for {@code on_failure} and for {@code execution_mode} on every block type, otherwise
 *       {@code WARNING}): value
 *       outside the key's fixed values. Values with a placeholder only become known at run time and stay
 *       unchecked.</li>
 *   <li>{@code legacy-config-key}: the legacy name of a key ({@code legacyFor} in the schema, {@code workflowId}
 *       on sub-workflow). Traceworks does not read it: {@code ERROR} when the block has nothing else and fails,
 *       {@code WARNING} when the current name is set as well and wins.</li>
 * </ul>
 *
 * <p>Block types without a schema stay unchecked ({@code unsupported-block-type} reports them). Two values are
 * left to the rule that reports them more precisely, so the same error does not appear twice: {@code items_source} of
 * a foreach with {@code items_command} ({@link ForEachRules}) and {@code provider} where the block reads it
 * ({@code unknown-provider} in {@link TraceworksRules}).
 */
final class ConfigKeyRules {

    private ConfigKeyRules() {}

    /** Keys that belong on the block level next to {@code config}. */
    private static final Set<String> BLOCK_LEVEL_KEYS = Set.of("name", "type");

    /**
     * Keys whose invalid value changes what the run does, so the value is an {@code ERROR}: an invalid
     * {@code on_failure} stops the run like {@code stop}, an invalid {@code execution_mode} fails the step.
     */
    private static final Set<String> FAILING_VALUES = Set.of("on_failure", ExecutionMode.KEY);

    static void apply(List<BlockDefinition> blocks, List<AnalyzerFinding> out) {
        BlockConfigSchema schema = BlockConfigSchema.get();
        BlockConfigs.walk(blocks, config -> check(schema, config.type(), config.config(),
                new Where(config.index(), config.name(), config.path()), out));
    }

    /** Where a finding hangs: index and name of the outer block, for nested ones the path to it. */
    private record Where(int index, String name, String path) {
        String prefix() {
            return path == null ? "" : path + ": ";
        }
    }

    private static void check(BlockConfigSchema schema, String type, Map<String, Object> config, Where where,
                              List<AnalyzerFinding> out) {
        if (!schema.knowsType(type)) return;
        Map<String, BlockConfigSchema.Field> fields = schema.fieldsFor(type);
        Map<String, Object> cfg = config != null ? config : Map.of();

        for (Map.Entry<String, Object> entry : cfg.entrySet()) {
            String key = entry.getKey();
            BlockConfigSchema.Field field = fields.get(key);
            if (ExecutionMode.KEY.equals(key) && ExecutionMode.unknownValue(cfg) != null) {
                // The run is refused whatever the block type, so no type gets the milder "has no effect" hint
                out.add(invalidExecutionMode(entry.getValue(), where));
            } else if (field == null) {
                // config.blocks is reported more precisely by BlockShapeRules (dead-nested-blocks, ERROR)
                if ("blocks".equals(key)) continue;
                out.add(AnalyzerFinding.of("unknown-config-key", Severity.WARNING, where.index(), where.name(),
                        where.prefix() + "unknown config key " + key + " — the " + type
                                + " block does not read it, the setting has no effect" + hint(key, suggestable(fields))));
            } else if (field.topLevelOnly() && where.path() != null) {
                out.add(AnalyzerFinding.of("unknown-config-key", Severity.WARNING, where.index(), where.name(),
                        where.prefix() + key + " only works on a top-level block, not in a nested " + type
                                + " block — nothing reads it here"));
            } else if (field.legacyFor() != null) {
                out.add(legacyKey(field, cfg, where));
            } else if (!onlyWithMet(field, cfg)) {
                out.add(AnalyzerFinding.of("unknown-config-key", Severity.WARNING, where.index(), where.name(),
                        where.prefix() + key + " only takes effect with " + condition(field) + " — as it is, the "
                                + type + " block does not read it, the setting has no effect"));
            } else if (typeChecked(schema, type, key, cfg, where.path() != null)
                    && field.typeProblem(entry.getValue()) != null) {
                out.add(invalidType(type, key, field.typeProblem(entry.getValue()), where));
            } else {
                if (!skipValueCheck(type, key, cfg, where.path() != null)) {
                    checkValue(field, entry.getValue(), where, out);
                }
                if (!field.fields().isEmpty() && entry.getValue() instanceof Map<?, ?> object) {
                    checkObject(type, field, object, where, out);
                }
            }
        }

        if ("shell".equals(type) && where.path() == null) checkToolMode(cfg, where, out);

        // When nested, topLevelOnly keys drop out of requiredOneOf; if one remains, it is required
        List<String> oneOf = schema.requiredOneOfKeys(type);
        List<String> requiredKeys = new ArrayList<>(schema.requiredKeys(type));
        if (where.path() != null) {
            List<String> nestedOneOf = oneOf.stream()
                    .filter(key -> fields.get(key) == null || !fields.get(key).topLevelOnly()).toList();
            if (nestedOneOf.size() == 1 && oneOf.size() > 1) {
                requiredKeys.add(nestedOneOf.get(0));
                oneOf = List.of();
            }
        }
        for (String required : requiredKeys) {
            if (cfg.get(required) != null || legacyKeyPresent(fields, required, cfg)) continue;
            String instead = presentInstead(cfg, fields, required);
            out.add(AnalyzerFinding.of("missing-required-config-key", Severity.ERROR, where.index(), where.name(),
                    where.prefix() + "required key " + required + " is missing — without it the " + type
                            + " block cannot run" + (instead == null ? "" : " (" + instead + " is set instead)")));
        }
        checkOneOf(oneOf, type, cfg, fields, where, out);
    }

    /**
     * The tool mode of a shell block ({@code container_args}) fails when its step is reached if it has no
     * {@code container_image} or if {@code container_args} is no list: both are errors here.
     */
    private static void checkToolMode(Map<String, Object> cfg, Where where, List<AnalyzerFinding> out) {
        if (!cfg.containsKey("container_args")) return;
        Object args = cfg.get("container_args");
        if (!(args instanceof List<?>)) {
            String shown = args instanceof String ? "\"" + args + "\"" : String.valueOf(args);
            out.add(AnalyzerFinding.of("invalid-config-value", Severity.ERROR, where.index(), where.name(),
                    "container_args: " + shown + " is not a valid value, it must be a list of strings — the step "
                            + "fails when it is reached"));
        }
        if (!(cfg.get("container_image") instanceof String image) || image.isBlank()) {
            out.add(AnalyzerFinding.of("missing-required-config-key", Severity.ERROR, where.index(), where.name(),
                    "required key container_image is missing — the tool mode (container_args) runs the image's "
                            + "entrypoint and cannot run without one"));
        }
    }

    /** {@code requiredOneOf}: exactly one of the keys; none or several make the block abort. */
    private static void checkOneOf(List<String> oneOf, String type, Map<String, Object> cfg,
                                   Map<String, BlockConfigSchema.Field> fields, Where where, List<AnalyzerFinding> out) {
        // An empty text counts as not set, as in the executor
        long present = oneOf.stream().filter(key -> cfg.get(key) != null
                && !(cfg.get(key) instanceof String text && text.isBlank())).count();
        if (oneOf.isEmpty() || present == 1) return;
        String keys = String.join(" or ", oneOf);
        String instead = present > 0 ? null : oneOf.stream().map(key -> presentInstead(cfg, fields, key))
                .filter(Objects::nonNull).findFirst().orElse(null);
        out.add(AnalyzerFinding.of("missing-required-config-key", Severity.ERROR, where.index(), where.name(),
                where.prefix() + (present == 0
                        ? "required key " + keys + " is missing — without one of them the " + type + " block cannot run"
                                + (instead == null ? "" : " (" + instead + " is set instead)")
                        : keys + " exclude each other — set only one, otherwise the " + type + " block fails")));
    }

    /**
     * The keys of an object setting ({@code validation}) at the same level as a block's: an unknown key is
     * {@code unknown-config-key}, a wrong type {@code invalid-config-value}.
     */
    private static void checkObject(String type, BlockConfigSchema.Field object, Map<?, ?> value, Where where,
                                    List<AnalyzerFinding> out) {
        Map<String, BlockConfigSchema.Field> inner = object.fields();
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            String key = String.valueOf(entry.getKey());
            BlockConfigSchema.Field field = inner.get(key);
            if (field == null) {
                out.add(AnalyzerFinding.of("unknown-config-key", Severity.WARNING, where.index(), where.name(),
                        where.prefix() + "unknown key " + key + " in " + object.key() + " — the " + type
                                + " block does not read it, the setting has no effect"
                                + hint(key, suggestable(inner))));
            } else if (field.typeProblem(entry.getValue()) != null) {
                out.add(invalidType(type, object.key() + "." + key, field.typeProblem(entry.getValue()), where));
            }
        }
    }

    /**
     * Keys whose wrong type another rule reports in its own words, or that the run reads in its own way:
     * {@code on_failure} (a value that is not {@code continue} stops, see {@link #checkValue}), {@code secrets}
     * ({@link SecretRules}), {@code container_args} ({@link #checkToolMode}), {@code tools} and {@code mcp_servers}
     * ({@link RequirementRules}), {@code provider} where a block reads it ({@code unknown-provider}), and the
     * settings of {@code compress} and {@code compress-ai} that their executors check ({@link CompressRules}). The AI
     * keys of a block that makes no AI call are not read.
     */
    private static boolean typeChecked(BlockConfigSchema schema, String type, String key, Map<String, Object> cfg,
                                       boolean nested) {
        if (TYPE_REPORTED_ELSEWHERE.contains(key)) return false;
        if (("compress".equals(type) || "compress-ai".equals(type))
                && ("max_chars".equals(key) || "timeout_seconds".equals(key) || "stages".equals(key))) {
            return false;
        }
        return !schema.aiKeys().contains(key) || RunRequirements.callsAi(type, cfg, nested)
                && !TraceworksRules.PROVIDER_KEY.equals(key);
    }

    private static final Set<String> TYPE_REPORTED_ELSEWHERE = Set.of("on_failure", ExecutionMode.KEY,
            SecretNames.KEY, "container_args", ToolPolicy.KEY, "mcp_servers", WebAccess.KEY,
            WebAccess.HOSTS_KEY);

    /**
     * Settings the run does not read as a setting of the step, so a wrong value has no effect there: a label, a note,
     * the {@code workspace} setting (only {@code mode: isolated} is refused), and {@code user_mcp_servers} (only {@code
     * true}
     * is refused).
     */
    private static final Set<String> NOT_READ_BY_THE_RUN = Set.of("label", "description", "workspace",
            "user_mcp_servers");

    /**
     * A value of the wrong type or out of range, in the run's words ({@link BlockSettings#problem}). The run fails
     * the step on it; {@code untrusted_input} still takes the text {@code "true"}, so the finding says so.
     */
    private static AnalyzerFinding invalidType(String type, String key, String problem, Where where) {
        String effect = UntrustedInput.CONFIG_KEY.equals(key)
                ? " — the run takes the text \"true\" (any case) as true and any other value as false; write true or"
                        + " false"
                : NOT_READ_BY_THE_RUN.contains(key) ? " — the run does not read it"
                : " — the step fails when it is reached";
        return AnalyzerFinding.of("invalid-config-value", Severity.ERROR, where.index(), where.name(),
                where.prefix() + BlockSettings.of(type, null).message(key, problem) + effect);
    }

    private static void checkValue(BlockConfigSchema.Field field, Object value, Where where, List<AnalyzerFinding> out) {
        if (field.values().isEmpty() || value == null) return;
        if ("on_failure".equals(field.key())) {
            // Exactly what the run accepts: no placeholder, trimmed as the run trims
            if (value instanceof String text && BlockDefinition.knownOnFailure(text)) return;
        } else if (value instanceof String text && (text.contains("{{") || field.allows(text))) {
            return;
        }
        String shown = value instanceof String ? "\"" + value + "\"" : String.valueOf(value);
        Severity severity = FAILING_VALUES.contains(field.key()) ? Severity.ERROR : Severity.WARNING;
        out.add(AnalyzerFinding.of("invalid-config-value", severity, where.index(), where.name(),
                where.prefix() + field.key() + ": " + shown + " is not a valid value, allowed: "
                        + String.join(", ", field.values())));
    }

    private static AnalyzerFinding invalidExecutionMode(Object value, Where where) {
        String shown = value instanceof String ? "\"" + value + "\"" : String.valueOf(value);
        return AnalyzerFinding.of("invalid-config-value", Severity.ERROR, where.index(), where.name(),
                where.prefix() + ExecutionMode.KEY + ": " + shown + " is not a valid value, allowed: "
                        + ExecutionMode.AI + ", " + ExecutionMode.SHELL);
    }

    /** {@code onlyWith}: every named key has the value; {@code "*"} means: is set. */
    private static boolean onlyWithMet(BlockConfigSchema.Field field, Map<String, Object> cfg) {
        return field.onlyWith().entrySet().stream().allMatch(e -> ANY.equals(e.getValue())
                ? cfg.get(e.getKey()) != null && !(cfg.get(e.getKey()) instanceof String s && s.isBlank())
                : e.getValue().equals(cfg.get(e.getKey())));
    }

    private static String condition(BlockConfigSchema.Field field) {
        return String.join(", ", field.onlyWith().entrySet().stream()
                .map(e -> ANY.equals(e.getValue()) ? e.getKey() : e.getKey() + ": " + e.getValue()).toList());
    }

    private static final String ANY = "*";

    /**
     * Values another rule reports more precisely: items_source of a foreach with items_command
     * ({@link ForEachRules}), provider where the block reads it ({@code unknown-provider}), and tools and
     * web ({@link RequirementRules}).
     */
    private static boolean skipValueCheck(String type, String key, Map<String, Object> cfg, boolean nested) {
        if (TraceworksRules.PROVIDER_KEY.equals(key)) return TraceworksRules.readsProvider(type, cfg, nested);
        if (ToolPolicy.KEY.equals(key)) return true;   // RequirementRules reports it as the admission's error
        if (WebAccess.KEY.equals(key)) return true;    // and web, the same way
        return "foreach".equals(type) && "items_source".equals(key)
                && cfg.get("items_command") instanceof String command && !command.isBlank();
    }

    /** The legacy name of a key: Traceworks does not read it; the message names the key that replaces it. */
    private static AnalyzerFinding legacyKey(BlockConfigSchema.Field field, Map<String, Object> cfg, Where where) {
        String current = field.legacyFor();
        boolean both = cfg.get(current) != null;
        return AnalyzerFinding.of("legacy-config-key", both ? Severity.WARNING : Severity.ERROR, where.index(),
                where.name(), where.prefix() + field.key() + " is the legacy spelling of " + current
                        + " — Traceworks does not read it" + (both
                        ? "; " + current + " is set as well and wins, remove " + field.key()
                        : ", so the block fails; rename the key to " + current));
    }

    /**
     * Whether an old name of the required key is set: then {@code legacy-config-key} reports the block, so the
     * missing key is not reported a second time.
     */
    private static boolean legacyKeyPresent(Map<String, BlockConfigSchema.Field> fields, String required,
                                            Map<String, Object> cfg) {
        return fields.values().stream()
                .anyMatch(f -> required.equals(f.legacyFor()) && cfg.get(f.key()) != null);
    }

    /** If an unknown key that resembles it stands in place of the required key, the message names it; else null. */
    private static String presentInstead(Map<String, Object> cfg, Map<String, BlockConfigSchema.Field> fields,
                                         String required) {
        for (String key : cfg.keySet()) {
            if (!fields.containsKey(key) && required.equals(FindingText.suggestion(key, suggestable(fields)))) {
                return key;
            }
        }
        return null;
    }

    /** Only current names are suggested, never the old spelling of a key. */
    private static Set<String> suggestable(Map<String, BlockConfigSchema.Field> fields) {
        Set<String> keys = new LinkedHashSet<>();
        fields.values().forEach(f -> {
            if (f.legacyFor() == null) keys.add(f.key());
        });
        return keys;
    }

    private static String hint(String key, Set<String> known) {
        if (BLOCK_LEVEL_KEYS.contains(key)) {
            return "; " + key + " belongs on the block level, next to config";
        }
        String suggestion = FindingText.suggestion(key, known);
        return suggestion == null ? "" : "; did you mean " + suggestion + "?";
    }

}
