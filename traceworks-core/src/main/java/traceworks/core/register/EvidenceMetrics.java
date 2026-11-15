package traceworks.core.register;

import traceworks.core.compat.GermanWords;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The measurable values in an evidence text, for the comparison of an accepted risk with its snapshot: numbers with a
 * known unit ({@code 340 LOC}, {@code 3.5 MB}, {@code 80 %}) and named counts of fact lines
 * ({@code FACT foo_count: 9}), the churn ({@code 6 changes}, {@code churn: 30}) with its hotspot threshold, and the
 * advisory ids ({@code CVE-…}, {@code GHSA-…}). English and German words ({@link GermanWords}) are read.
 */
public final class EvidenceMetrics {

    private static final Map<String, String> CANONICAL = new HashMap<>();

    static {
        CANONICAL.put("loc", "LOC");
        CANONICAL.put("lines", "LOC");
        CANONICAL.put("ncloc", "NCLOC");
        CANONICAL.put("deps", "deps");
        CANONICAL.put("dependencies", "deps");
        CANONICAL.put("kb", "KB");
        CANONICAL.put("mb", "MB");
        CANONICAL.put("gb", "GB");
        CANONICAL.put("ms", "ms");
        CANONICAL.put("%", "%");
        CANONICAL.put("files", "files");
        CANONICAL.put("classes", "classes");
        CANONICAL.put("methods", "methods");
        CANONICAL.put("tests", "tests");
        CANONICAL.put("warnings", "warnings");
        CANONICAL.put("errors", "errors");
        CANONICAL.put("issues", "issues");
        CANONICAL.put("vulnerabilities", "vulnerabilities");
        CANONICAL.putAll(GermanWords.METRIC_UNITS);
    }

    private static final Pattern METRIC = Pattern.compile(
            "(?<![\\w.,])(\\d+(?:[.,]\\d+)?)\\s*(%|(?:" + String.join("|", CANONICAL.keySet().stream()
                    .filter(k -> !k.equals("%")).map(Pattern::quote).sorted().toList()) + ")\\b)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern NAMED_COUNT = Pattern.compile(
            "(?<![A-Za-z0-9_])([a-z0-9_]+_count):\\s*(\\d+)\\b(?![.,]\\d)");
    private static final Pattern CHURN = Pattern.compile(
            "(?<![\\w.,])(\\d+)\\s+(?:" + GermanWords.CHURN_WORDS + "|changes?)\\b|\\bchurn\\s*:?\\s*(\\d+)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern HOTSPOT_THRESHOLD = Pattern.compile(
            "\\b(?:hotspot\\s+from|" + GermanWords.HOTSPOT_PHRASE + ")\\s+(\\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ADVISORY_ID = Pattern.compile(
            "\\b(?:CVE-\\d{4}-\\d{4,}|GHSA(?:-[0-9a-z]{4}){3})\\b", Pattern.CASE_INSENSITIVE);
    private static final String GHSA_PREFIX = "GHSA-";

    private EvidenceMetrics() {
    }

    /** The largest value per canonical unit or count name; empty without a measurable value. */
    public static Map<String, Double> parse(String evidence) {
        Map<String, Double> metrics = new TreeMap<>();
        if (evidence == null || evidence.isBlank()) {
            return metrics;
        }
        Matcher matcher = METRIC.matcher(evidence);
        while (matcher.find()) {
            String key = CANONICAL.get(matcher.group(2).toLowerCase(Locale.ROOT));
            if (key == null) {
                // The pattern folds case in Unicode (a long s matches s), lower case does not: no unit we know
                continue;
            }
            metrics.merge(key, Double.parseDouble(matcher.group(1).replace(',', '.')), Math::max);
        }
        Matcher count = NAMED_COUNT.matcher(evidence);
        while (count.find()) {
            metrics.merge(count.group(1), Double.parseDouble(count.group(2)), Math::max);
        }
        return metrics;
    }

    /** The largest churn the text states; empty when it states none. */
    public static OptionalInt churn(String evidence) {
        if (evidence == null || evidence.isBlank()) {
            return OptionalInt.empty();
        }
        Matcher matcher = CHURN.matcher(evidence);
        OptionalInt result = OptionalInt.empty();
        while (matcher.find()) {
            int value = Integer.parseInt(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
            result = OptionalInt.of(Math.max(value, result.orElse(value)));
        }
        return result;
    }

    /** The hotspot threshold the text states ({@code hotspot from 5}); empty when it states none. */
    public static OptionalInt hotspotThreshold(String evidence) {
        if (evidence == null) {
            return OptionalInt.empty();
        }
        Matcher matcher = HOTSPOT_THRESHOLD.matcher(evidence);
        return matcher.find() ? OptionalInt.of(Integer.parseInt(matcher.group(1))) : OptionalInt.empty();
    }

    /** The advisory ids in order of first appearance, canonical (CVE upper case, GHSA lower case after the prefix). */
    public static Set<String> advisoryIds(String evidence) {
        Set<String> ids = new LinkedHashSet<>();
        if (evidence == null || evidence.isBlank()) {
            return ids;
        }
        Matcher matcher = ADVISORY_ID.matcher(evidence);
        while (matcher.find()) {
            String id = matcher.group();
            ids.add(id.regionMatches(true, 0, GHSA_PREFIX, 0, GHSA_PREFIX.length())
                    ? GHSA_PREFIX + id.substring(GHSA_PREFIX.length()).toLowerCase(Locale.ROOT)
                    : id.toUpperCase(Locale.ROOT));
        }
        return ids;
    }

    /** {@code 320 LOC}, {@code 3.5 MB}, {@code 80 %}. */
    public static String format(double value, String unit) {
        String number = value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
        return number + " " + unit;
    }
}
