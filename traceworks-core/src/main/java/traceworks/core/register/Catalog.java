package traceworks.core.register;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * {@code catalog.json} of a package: the rule keys its reports may use, each with an optional severity range, an
 * optional fixed subject, and whether a model judges it ({@code "judged": true}; a rule is measured otherwise). A report that names an unknown rule key or a severity out of range is invalid for the
 * register and is rejected as a whole; without a catalog, every rule key is allowed.
 *
 * <pre>{"format":1,"rules":{"dep.outdated":{"severity":{"min":"LOW","max":"HIGH"},"subject":"pom.xml"}}}</pre>
 *
 * @param rules by rule key, sorted
 */
public record Catalog(Map<String, Rule> rules) {

    /** The format a catalog declares. */
    public static final int FORMAT = 1;

    private static final ObjectMapper JSON = new ObjectMapper();

    public Catalog {
        rules = Collections.unmodifiableMap(new TreeMap<>(rules));
    }

    /**
     * One rule of the catalog.
     *
     * @param min     the least severe severity a finding of the rule may have; {@code null} for no bound
     * @param max     the most severe; {@code null} for no bound
     * @param subject the subject every finding of the rule gets, replacing the reported one; {@code null} for none
     * @param judged  whether a finding of the rule is a judgement of a model. A measured finding that a full snapshot
     *                does not state is gone; a judged one may only not have been named again, so it is resolved only
     *                when the report names it as gone
     */
    public record Rule(Severity min, Severity max, String subject, boolean judged) {

        /** A measured rule. */
        public Rule(Severity min, Severity max, String subject) {
            this(min, max, subject, false);
        }
    }

    /**
     * The catalog {@code json} describes.
     *
     * @throws IllegalArgumentException naming what is wrong, starting with {@code catalog.json}
     */
    public static Catalog parse(byte[] json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("catalog.json is no JSON: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("catalog.json must be a JSON object");
        }
        JsonNode format = root.get("format");
        if (format == null || !format.isIntegralNumber() || format.asInt() != FORMAT) {
            throw new IllegalArgumentException("catalog.json must declare \"format\": " + FORMAT);
        }
        JsonNode rules = root.get("rules");
        if (rules == null || !rules.isObject()) {
            throw new IllegalArgumentException("catalog.json must hold \"rules\", an object by rule key");
        }
        Map<String, Rule> parsed = new TreeMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = rules.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            if (entry.getKey().isBlank()) {
                throw new IllegalArgumentException("catalog.json has a rule with a blank key");
            }
            parsed.put(entry.getKey(), rule(entry.getKey(), entry.getValue()));
        }
        return new Catalog(parsed);
    }

    private static Rule rule(String key, JsonNode node) {
        String where = "catalog.json rule '" + key + "'";
        if (!node.isObject()) {
            throw new IllegalArgumentException(where + " must be an object");
        }
        Severity min = null;
        Severity max = null;
        String subject = null;
        boolean judged = false;
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            switch (field) {
                case "severity" -> {
                    JsonNode range = node.get("severity");
                    if (!range.isObject()) {
                        throw new IllegalArgumentException(where + ": severity must be an object with min and max");
                    }
                    min = bound(where, range, "min");
                    max = bound(where, range, "max");
                    if (min != null && max != null && min.compareTo(max) < 0) {
                        throw new IllegalArgumentException(where + ": severity min " + min
                                + " is more severe than max " + max);
                    }
                }
                case "subject" -> {
                    JsonNode value = node.get("subject");
                    if (!value.isTextual() || value.asText().isBlank()) {
                        throw new IllegalArgumentException(where + ": subject must be a non-blank string");
                    }
                    subject = value.asText();
                }
                case "judged" -> {
                    JsonNode value = node.get("judged");
                    if (!value.isBoolean()) {
                        throw new IllegalArgumentException(where + ": judged must be true or false");
                    }
                    judged = value.booleanValue();
                }
                default -> throw new IllegalArgumentException(where + ": unknown key '" + field
                        + "' (the keys are severity, subject and judged)");
            }
        }
        return new Rule(min, max, subject, judged);
    }

    private static Severity bound(String where, JsonNode range, String field) {
        JsonNode value = range.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return Severity.parse(value.isTextual() ? value.asText() : "").orElseThrow(() ->
                new IllegalArgumentException(where + ": severity." + field + " must be one of CRITICAL, HIGH, MEDIUM,"
                        + " LOW"));
    }

    /** The subject the catalog fixes for {@code ruleKey}; empty when it fixes none or does not know the rule. */
    public Optional<String> fixedSubject(String ruleKey) {
        Rule rule = rules.get(ruleKey);
        return Optional.ofNullable(rule == null ? null : rule.subject());
    }

    /** Whether a model judges {@code ruleKey}; a rule the catalog does not know or does not mark is measured. */
    public boolean judged(String ruleKey) {
        Rule rule = rules.get(ruleKey);
        return rule != null && rule.judged();
    }

    /** Why the catalog refuses what a report names as gone: a rule key it does not know. */
    public List<String> problemsOfGone(List<GoneFinding> gone) {
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < gone.size(); i++) {
            if (!rules.containsKey(gone.get(i).ruleKey())) {
                problems.add("gone[" + i + "]: rule key '" + gone.get(i).ruleKey() + "' is not in catalog.json");
            }
        }
        return problems;
    }

    /** Why the catalog refuses {@code findings}: an unknown rule key, or a severity outside the rule's range. */
    public List<String> problems(List<ReportedFinding> findings) {
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < findings.size(); i++) {
            ReportedFinding finding = findings.get(i);
            Rule rule = rules.get(finding.ruleKey());
            if (rule == null) {
                problems.add("findings[" + i + "]: rule key '" + finding.ruleKey() + "' is not in catalog.json");
                continue;
            }
            // CRITICAL is first in the enum: "more severe" means a smaller ordinal
            if (rule.min() != null && finding.severity().compareTo(rule.min()) > 0
                    || rule.max() != null && finding.severity().compareTo(rule.max()) < 0) {
                problems.add("findings[" + i + "]: severity " + finding.severity() + " of rule '" + finding.ruleKey()
                        + "' is outside the catalog's range " + (rule.min() == null ? "any" : rule.min()) + " to "
                        + (rule.max() == null ? "any" : rule.max()));
            }
        }
        return problems;
    }
}
