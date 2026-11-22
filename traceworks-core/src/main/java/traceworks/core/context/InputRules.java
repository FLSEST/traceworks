package traceworks.core.context;

import traceworks.core.compat.GermanWords;
import traceworks.core.util.TimedRegex;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The rules of one input of a {@code human-input} block, and the one check every answer to it passes: a person's at
 * the console, one given with {@code traceworks answer} to a parked or a running run, the answer a resumed step takes
 * over, and an AI actor's. The rules are the input's {@code required}, its {@code input_type} (number, checkbox, date;
 * a {@code select} takes one of its {@code options}) and its {@code validation_pattern}, which must match the whole
 * value. A checkbox also takes the German "yes" and "no" ({@link GermanWords#CHECKBOX_YES}).
 */
public final class InputRules {

    private InputRules() {}

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    /** How long checking one value against a workflow's {@code validation_pattern} may take. */
    public static final Duration PATTERN_TIME_LIMIT = Duration.ofSeconds(2);

    /** The rule a value broke, named by the key of the block that sets it. */
    public static final String REQUIRED = "required";
    public static final String OPTIONS = "options";
    public static final String INPUT_TYPE = "input_type";
    public static final String VALIDATION_PATTERN = "validation_pattern";

    /**
     * The rules of a single input or of one field of a form.
     *
     * @param inputType {@code null} for text
     * @param options   the options of a {@code select}; a value of another type is not checked against them
     * @param pattern   {@code null} or blank for none
     */
    public record Rules(String inputType, List<String> options, boolean required, String pattern) {

        public Rules {
            options = options == null ? List.of() : List.copyOf(options);
        }

        /** The rules of one field of a form, as the block's {@code fields} write them: required unless it says not. */
        public static Rules ofField(Map<String, ?> field) {
            List<String> options = new ArrayList<>();
            if (field.get("options") instanceof List<?> list) {
                list.forEach(option -> { if (option != null) options.add(String.valueOf(option)); });
            }
            return new Rules(field.get("input_type") instanceof String type ? type : null, options,
                    !Boolean.FALSE.equals(field.get("required")),
                    field.get("validation_pattern") instanceof String pattern ? pattern : null);
        }
    }

    /**
     * The value as the step stores it, or why it is refused.
     *
     * @param value   the value in the input's format: a select's option in its spelling, a checkbox's {@code true} or
     *                {@code false}, an optional value left blank as empty text, any other as it was given;
     *                {@code null} when refused
     * @param rule    the rule it broke ({@link #REQUIRED}, {@link #OPTIONS}, {@link #INPUT_TYPE},
     *                {@link #VALIDATION_PATTERN}); {@code null} when it holds
     * @param problem what is wrong with it, quoting the value; {@code null} when it holds
     */
    public record Checked(String value, String rule, String problem) {

        public boolean ok() {
            return problem == null;
        }

        static Checked holds(String value) {
            return new Checked(value, null, null);
        }

        static Checked broke(String rule, String problem) {
            return new Checked(null, rule, problem);
        }
    }

    /**
     * Checks one value against the rules. The value is checked as it is given: what is stored is what was checked,
     * so a pattern or a date sees blanks around the value. A select and a checkbox compare without the blanks and
     * without regard to case, and store their own spelling; a pattern is not asked of them.
     */
    public static Checked check(String value, Rules rules) {
        String given = value == null ? "" : value;
        String type = rules.inputType() == null ? "text" : rules.inputType();
        if (given.isBlank()) {
            return rules.required() ? Checked.broke(REQUIRED, "a value is required") : Checked.holds("");
        }
        String stripped = given.strip();
        switch (type) {
            case "select" -> {
                // One of the workflow's own options: no pattern is asked of it
                return rules.options().stream().filter(o -> o.strip().equalsIgnoreCase(stripped)).findFirst()
                        .map(Checked::holds)
                        .orElse(Checked.broke(OPTIONS, "'" + given + "' is none of the options " + rules.options()));
            }
            case "checkbox" -> {
                return switch (stripped.toLowerCase(Locale.ROOT)) {
                    case "true", "yes", GermanWords.CHECKBOX_YES -> Checked.holds("true");
                    case "false", "no", GermanWords.CHECKBOX_NO -> Checked.holds("false");
                    default -> Checked.broke(INPUT_TYPE, "'" + given + "' is neither true nor false");
                };
            }
            case "number" -> {
                try {
                    Double.parseDouble(given);
                } catch (NumberFormatException e) {
                    return Checked.broke(INPUT_TYPE, "'" + given + "' is not a number");
                }
            }
            case "date" -> {
                if (!DATE.matcher(given).matches()) {
                    return Checked.broke(INPUT_TYPE, "'" + given + "' is not a date in the form YYYY-MM-DD");
                }
            }
            default -> { }
        }
        String pattern = rules.pattern();
        if (pattern != null && !pattern.isBlank()) {
            String problem = TimedRegex.repetitionProblem(pattern);
            if (problem != null) {
                // Such a pattern repeats without reading a character, so the time limit would never end the check
                return Checked.broke(VALIDATION_PATTERN, "validation_pattern cannot be used: " + problem);
            }
            try {
                if (!TimedRegex.matches(Pattern.compile(pattern), given, PATTERN_TIME_LIMIT)) {
                    return Checked.broke(VALIDATION_PATTERN, "'" + given + "' does not match the pattern " + pattern);
                }
            } catch (TimedRegex.TimedOut e) {
                // The pattern is the workflow's and the answer may be a model's: neither may keep the run busy
                return Checked.broke(VALIDATION_PATTERN, "the answer could not be checked within "
                        + PATTERN_TIME_LIMIT.toSeconds() + " seconds against validation_pattern " + pattern
                        + " (" + e.getMessage() + ")");
            } catch (PatternSyntaxException e) {
                return Checked.broke(VALIDATION_PATTERN,
                        "validation_pattern is not a valid regular expression: " + e.getDescription());
            }
        }
        return Checked.holds(given);
    }
}
