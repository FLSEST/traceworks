package traceworks.core.context;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The spelling of the {@code {{ ... }}} placeholders - one place, so that runtime resolution
 * ({@link TemplateResolver}) and static analyses (the analyzer) see exactly the same occurrences.
 */
public final class PlaceholderSyntax {

    /**
     * {@code {{x}}} or {@code {{ x }}} - exactly the two spellings the resolver has always
     * supported. Keys may contain spaces (block names like "Build Report"); an
     * asymmetric spelling yields a key with leading/trailing space
     * that matches nothing and stays literal.
     */
    public static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{( ?)([^{}]+?)\\1\\}\\}");

    /**
     * A second placeholder syntax, {@code ${name}}, that existing workflows use in a {@code switch} expression.
     * Traceworks resolves only {@link #PLACEHOLDER}; this one is recognized so that a switch expression using it is
     * refused instead of silently matching no case.
     */
    public static final Pattern DOLLAR_PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    private PlaceholderSyntax() {}

    /**
     * What is wrong with a text that uses {@link #DOLLAR_PLACEHOLDER}s where a workflow may expect them resolved (a
     * switch expression): {@code "uses ${status}, which Traceworks does not resolve; write {{ status }}"};
     * {@code null} for a text without one.
     */
    public static String dollarPlaceholderProblem(String text) {
        if (text == null) {
            return null;
        }
        Set<String> used = new LinkedHashSet<>();
        Set<String> replacements = new LinkedHashSet<>();
        Matcher matcher = DOLLAR_PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            used.add(matcher.group());
            replacements.add("{{ " + matcher.group(1).strip() + " }}");
        }
        return used.isEmpty() ? null : "uses " + String.join(", ", used)
                + ", which Traceworks does not resolve; write " + String.join(", ", replacements);
    }

    /** The keys of all placeholders of a text, in order of occurrence (with repetitions). */
    public static List<String> keysIn(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            keys.add(matcher.group(2));
        }
        return keys;
    }
}
