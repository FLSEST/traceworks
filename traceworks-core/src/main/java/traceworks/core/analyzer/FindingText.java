package traceworks.core.analyzer;

import traceworks.core.engine.NestedBlocks;

import java.util.Locale;
import java.util.Set;

/**
 * Text that findings of several rules share: the suggestion of a near spelling for an unknown key, and the path of a
 * nested block.
 */
final class FindingText {

    private FindingText() {
    }

    /** {@code Block 2 › cases[0] › Block 1 "report"}: where a nested block sits, with its name if it has one. */
    static String nestedPath(int index, NestedBlocks.Nested nested) {
        boolean named = nested.name() != null && !nested.name().isBlank();
        return "Block " + (index + 1) + " › " + nested.path() + (named ? " \"" + nested.name() + "\"" : "");
    }

    /**
     * The closest known key: equal up to camelCase/snake_case, otherwise a Levenshtein distance of at most 2,
     * stricter for short keys, so that {@code mode} does not become a suggestion for {@code name}.
     */
    static String suggestion(String key, Set<String> known) {
        String normalized = normalize(key);
        for (String candidate : known) {
            if (normalize(candidate).equals(normalized)) return candidate;
        }
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : known) {
            int distance = levenshtein(key.toLowerCase(Locale.ROOT), candidate);
            int limit = Math.min(2, (Math.min(key.length(), candidate.length()) - 1) / 2);
            if (distance <= limit && distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static String normalize(String key) {
        return key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }


    static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
