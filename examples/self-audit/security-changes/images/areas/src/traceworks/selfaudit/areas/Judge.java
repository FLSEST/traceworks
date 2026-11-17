package traceworks.selfaudit.areas;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Who judges a run: the option the run picked of the workflow's model choice {@code judge}, with its provider and
 * its model. The workflow declares the options and Traceworks picks one before the first step; it hands the step
 * the three as variables ({@code judge}, {@code judge_provider}, {@code judge_model}), and the step passes them on.
 * So the tool reads no declaration itself: what it is told is what the verdicts run with.
 *
 * <p>The judge is for a reader: it goes to the head of the report and to {@code findings.jsonl}. It is in no digest
 * of an item and in nothing a register reads, so a finding is the same finding whichever model reported it.
 *
 * @param name     the picked option, such as {@code fable}
 * @param provider the option's AI provider, such as {@code CLAUDE}
 * @param model    the option's model, such as {@code claude-fable-5-2}
 */
record Judge(String name, String provider, String model) {

    private static final int MAX_LENGTH = 200;

    /**
     * The judge a run names.
     *
     * @throws IllegalArgumentException when one of the three is blank, longer than {@value #MAX_LENGTH} characters or
     *                                  holds a control character; the value is not shown then
     */
    static Judge of(String name, String provider, String model) {
        return new Judge(checked("the judge", name), checked("the judge's provider", provider),
                checked("the judge's model", model));
    }

    private static String checked(String what, String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty() || text.length() > MAX_LENGTH || text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(what + " must be a text of at most " + MAX_LENGTH
                    + " characters without control characters");
        }
        return text;
    }

    /** As the summary holds it. */
    Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", name);
        json.put("provider", provider);
        json.put("model", model);
        return json;
    }

    /** From the summary; {@code null} when it names no judge, as a summary of a run that was told none does. */
    static Judge fromJson(Object value) {
        if (!(value instanceof Map<?, ?> json)) return null;
        return new Judge(text(json.get("name")), text(json.get("provider")), text(json.get("model")));
    }

    private static String text(Object value) {
        return value instanceof String text ? text.trim() : "";
    }
}
