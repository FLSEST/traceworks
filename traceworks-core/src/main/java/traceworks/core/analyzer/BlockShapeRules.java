package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.model.BlockDefinition;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What is on the shape of a block but never runs, top-level and nested (switch cases, {@code default},
 * {@code blocks}).
 *
 * <ul>
 *   <li>{@code dead-nested-blocks} ({@code ERROR}): {@code config.blocks}. No executor runs a {@code blocks} list, on
 *       no block type: foreach, retry, conditional and while run their {@code command}, switch only
 *       {@code cases[].blocks} and {@code default}. The nested blocks never run, the block does something other than
 *       the definition shows. {@link ConfigKeyRules} therefore does not report the key again as unknown.</li>
 *   <li>{@code unknown-block-field}: a field next to {@code type}, {@code name} and {@code config}. It has no
 *       effect; {@link BlockDefinition#unknownFields()} keeps it so it is reported here. {@code ERROR} if it is a
 *       config key of the block type ({@code "on_failure": "continue"} next to instead of inside {@code config}: the
 *       run stops anyway), {@code WARNING} for a near spelling of a block field and for
 *       a non-empty {@code requiredCapabilities}, which Traceworks does not read, otherwise {@code INFO}.
 *       {@code skillIds} with skills in them refuse the workflow; {@code unsupported-skills} reports them, not this
 *       rule.</li>
 * </ul>
 */
final class BlockShapeRules {

    private BlockShapeRules() {}

    /** The fields a block may have: {@link BlockDefinition#FIELDS}. */
    static final Set<String> BLOCK_FIELDS = BlockDefinition.FIELDS;

    private static final String DEAD_LIST = "blocks";

    /**
     * A block's list of the capabilities it needs; Traceworks does not read it, so nothing checks it when the step
     * runs.
     */
    private static final String REQUIRED_CAPABILITIES = "requiredCapabilities";

    static void apply(List<BlockDefinition> blocks, List<AnalyzerFinding> out) {
        BlockConfigSchema schema = BlockConfigSchema.get();
        BlockConfigs.walk(blocks, config -> check(schema, config.type(), config.config(), unknownFields(config),
                new Where(config.index(), config.name(), config.prefix()), out));
    }

    /** The fields next to {@code config} that no run reads, without those another rule reports. */
    private static Set<String> unknownFields(BlockConfigs.Config config) {
        if (config.nested()) {
            Set<String> unknown = new LinkedHashSet<>(config.inner().fields());
            unknown.removeAll(BLOCK_FIELDS);
            return unknown;
        }
        BlockDefinition block = config.block();
        Set<String> fields = new LinkedHashSet<>(block.unknownFields().keySet());
        if (block.requestsSkills()) {
            fields.remove(BlockDefinition.SKILL_IDS);   // unsupported-skills reports them
        }
        if (block.unknownFields().get(REQUIRED_CAPABILITIES) instanceof List<?> none && none.isEmpty()) {
            fields.remove(REQUIRED_CAPABILITIES);   // nothing required, nothing to warn about
        }
        return fields;
    }

    /** Index and name of the outer block, for nested ones the path to it as a prefix. */
    private record Where(int index, String name, String prefix) {}

    private static void check(BlockConfigSchema schema, String type, Map<String, Object> config,
                              Set<String> unknownFields, Where where, List<AnalyzerFinding> out) {
        if (config != null && config.get(DEAD_LIST) != null) {
            String what = config.get(DEAD_LIST) instanceof List<?> list ? list.size() + " " : "";
            String label = type == null ? "" : " (" + type + ")";
            out.add(AnalyzerFinding.of("dead-nested-blocks", Severity.ERROR, where.index(), where.name(),
                    where.prefix() + "config.blocks" + label + " with " + what + "block entries never runs — no "
                            + "block type runs a blocks list (switch only cases[].blocks and default); put the task "
                            + "into command or make the steps blocks of their own"));
        }
        Map<String, BlockConfigSchema.Field> configKeys = schema.fieldsFor(type);
        for (String field : unknownFields) {
            if (configKeys.containsKey(field)) {
                out.add(AnalyzerFinding.of("unknown-block-field", Severity.ERROR, where.index(), where.name(),
                        where.prefix() + field + " sits next to config instead of inside it — as a block field no "
                                + "run reads it, the setting has no effect; move it into config"));
                continue;
            }
            if (field.equals(REQUIRED_CAPABILITIES)) {
                out.add(AnalyzerFinding.of("unknown-block-field", Severity.WARNING, where.index(), where.name(),
                        where.prefix() + field + " is not read by Traceworks — the step runs without checking that"
                                + " the capabilities are there; remove it"));
                continue;
            }
            String suggestion = FindingText.suggestion(field, BLOCK_FIELDS);
            if (suggestion != null) {
                out.add(AnalyzerFinding.of("unknown-block-field", Severity.WARNING, where.index(), where.name(),
                        where.prefix() + "unknown block field " + field + " — no run reads it; did you mean "
                                + suggestion + "?"));
                continue;
            }
            out.add(AnalyzerFinding.of("unknown-block-field", Severity.INFO, where.index(), where.name(),
                    where.prefix() + "unknown block field " + field + " — no run reads it (allowed next to config: "
                            + "type, name)"));
        }
    }
}
