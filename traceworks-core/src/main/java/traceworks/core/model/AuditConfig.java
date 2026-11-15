package traceworks.core.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What {@code metadata.audit} of a workflow says: the workflow feeds a register. Understood completely, or named
 * as problems: Traceworks refuses a workflow with a broken block, because an audit that silently stops feeding its
 * register is worse than a refused run (docs/workflow-format.md). The keys are the ones {@link WorkflowJson} hands
 * on: {@code report_block} and {@code schema_version}, whichever spelling the file has.
 *
 * @param key         the register's key, a path segment ({@link #KEY})
 * @param category    the audit's category, free text
 * @param reportBlock the name of the top-level block whose output is the report
 */
public record AuditConfig(String key, String category, String reportBlock, int schemaVersion) {

    /** What a register key may look like: a path segment of lower-case letters, digits, {@code .}, {@code _}, {@code -}. */
    public static final String KEY = "[a-z0-9][a-z0-9._-]{0,127}";

    private static final Pattern KEY_PATTERN = Pattern.compile(KEY);
    private static final List<String> REQUIRED = List.of("key", "category", "report_block");

    /** The config of an enabled audit block without problems; empty otherwise. */
    public static Optional<AuditConfig> of(Map<String, Object> metadata) {
        Map<?, ?> audit = enabledBlock(metadata);
        if (audit == null || !problems(metadata).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AuditConfig((String) audit.get("key"), (String) audit.get("category"),
                (String) audit.get("report_block"), 1));
    }

    /**
     * What is wrong with {@code metadata.audit}: nothing without the block or with {@code enabled} absent or false;
     * else each missing, blank or non-string required field, a key that is no path segment, a {@code schema_version}
     * other than the number 1, and an {@code enabled} that is no boolean.
     */
    public static List<String> problems(Map<String, Object> metadata) {
        List<String> problems = new ArrayList<>();
        Object value = metadata == null ? null : metadata.get("audit");
        if (value == null) {
            return problems;
        }
        if (!(value instanceof Map<?, ?> audit)) {
            problems.add("metadata.audit must be an object");
            return problems;
        }
        Object enabled = audit.get("enabled");
        if (enabled != null && !(enabled instanceof Boolean)) {
            problems.add("metadata.audit.enabled must be a boolean, not " + describe(enabled));
            return problems;
        }
        if (!Boolean.TRUE.equals(enabled)) {
            return problems;
        }
        for (String field : REQUIRED) {
            Object text = audit.get(field);
            if (text == null) {
                problems.add("metadata.audit." + field + " is missing");
            } else if (!(text instanceof String s)) {
                problems.add("metadata.audit." + field + " must be a string, not " + describe(text));
            } else if (s.isBlank()) {
                problems.add("metadata.audit." + field + " is blank");
            }
        }
        if (audit.get("key") instanceof String key && !key.isBlank() && !KEY_PATTERN.matcher(key).matches()) {
            problems.add("metadata.audit.key '" + key + "' is no register key: it must match " + KEY);
        }
        Object version = audit.get("schema_version");
        if (version != null && !(version instanceof Number n && n.doubleValue() == 1)) {
            problems.add("metadata.audit.schema_version is " + describe(version) + "; Traceworks reads version 1");
        }
        return problems;
    }

    private static Map<?, ?> enabledBlock(Map<String, Object> metadata) {
        Object value = metadata == null ? null : metadata.get("audit");
        return value instanceof Map<?, ?> audit && Boolean.TRUE.equals(audit.get("enabled")) ? audit : null;
    }

    private static String describe(Object value) {
        return value instanceof String s ? "the string \"" + s + "\"" : value instanceof Number ? "the number " + value
                : value instanceof Boolean ? "the boolean " + value : "a " + value.getClass().getSimpleName();
    }
}
