package traceworks.core.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ApprovalRequest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String blockId;
    private final String question;
    private final List<String> options;
    private final int stepIndex;

    /** The {@link #options} that are a rejection (the rejecting answers of an approval) - for the UI. */
    private final List<String> rejectOptions;

    /** Since when the run has been waiting - the request is created immediately before waiting. */
    private final Instant requestedAt;

    /** After how many minutes the waiting block gives up; {@code null} if unknown. */
    private final Integer timeoutMinutes;

    // Human-input specific fields
    private final String type;
    private final String inputType;
    private final String defaultValue;
    private final String variableName;
    private final boolean required;
    private final String validationPattern;

    /**
     * Multi-field human-input forms. When non-null and non-empty the dialog renders
     * a form with one input per entry; the answer is expected as a JSON object map
     * "{ "<variable_name>": "<value>", ... }". Each entry's keys are the same as
     * config keys: variable_name (required), label, input_type (text/textarea/number/
     * select/checkbox/date), required, default_value, options (for select), placeholder,
     * description, validation_pattern.
     */
    private final List<Map<String, Object>> fields;

    /**
     * The person answers with an AI actor's proposal below the question (actor {@code shadow}). Only a question a
     * person answers alone parks the run; this one waits in the process, because a resume would ask the AI again.
     */
    private final boolean shadow;

    private ApprovalRequest(Builder b) {
        this.blockId = b.blockId;
        this.question = b.question;
        this.options = b.options;
        this.stepIndex = b.stepIndex;
        this.rejectOptions = b.rejectOptions != null ? b.rejectOptions : List.of();
        this.requestedAt = b.requestedAt != null ? b.requestedAt : Instant.now();
        this.timeoutMinutes = b.timeoutMinutes;
        this.type = b.type != null ? b.type : "approval";
        this.inputType = b.inputType;
        this.defaultValue = b.defaultValue;
        this.variableName = b.variableName;
        this.required = b.required;
        this.validationPattern = b.validationPattern;
        this.fields = b.fields;
        this.shadow = b.shadow;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder pre-filled with this request, for changing single fields. */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.blockId = blockId;
        b.question = question;
        b.options = options;
        b.stepIndex = stepIndex;
        b.rejectOptions = rejectOptions;
        b.requestedAt = requestedAt;
        b.timeoutMinutes = timeoutMinutes;
        b.type = type;
        b.inputType = inputType;
        b.defaultValue = defaultValue;
        b.variableName = variableName;
        b.required = required;
        b.validationPattern = validationPattern;
        b.fields = fields;
        b.shadow = shadow;
        return b;
    }

    public String getBlockId() { return blockId; }
    public String getQuestion() { return question; }
    public List<String> getOptions() { return options; }
    public int getStepIndex() { return stepIndex; }
    public List<String> getRejectOptions() { return rejectOptions; }
    public Instant getRequestedAt() { return requestedAt; }
    public Integer getTimeoutMinutes() { return timeoutMinutes; }
    public String getType() { return type; }
    public String getInputType() { return inputType; }
    public String getDefaultValue() { return defaultValue; }
    public String getVariableName() { return variableName; }
    public boolean isRequired() { return required; }
    public String getValidationPattern() { return validationPattern; }
    public List<Map<String, Object>> getFields() { return fields; }
    public boolean isShadow() { return shadow; }

    /** Plain-Java builder; unset fields default as in the field documentation. */
    public static final class Builder {
        private String blockId;
        private String question;
        private List<String> options;
        private int stepIndex;
        private List<String> rejectOptions;
        private Instant requestedAt;
        private Integer timeoutMinutes;
        private String type;
        private String inputType;
        private String defaultValue;
        private String variableName;
        private boolean required;
        private String validationPattern;
        private List<Map<String, Object>> fields;
        private boolean shadow;

        private Builder() {}

        public Builder blockId(String v) { this.blockId = v; return this; }
        public Builder question(String v) { this.question = v; return this; }
        public Builder options(List<String> v) { this.options = v; return this; }
        public Builder stepIndex(int v) { this.stepIndex = v; return this; }
        public Builder rejectOptions(List<String> v) { this.rejectOptions = v; return this; }
        public Builder requestedAt(Instant v) { this.requestedAt = v; return this; }
        public Builder timeoutMinutes(Integer v) { this.timeoutMinutes = v; return this; }
        public Builder type(String v) { this.type = v; return this; }
        public Builder inputType(String v) { this.inputType = v; return this; }
        public Builder defaultValue(String v) { this.defaultValue = v; return this; }
        public Builder variableName(String v) { this.variableName = v; return this; }
        public Builder required(boolean v) { this.required = v; return this; }
        public Builder validationPattern(String v) { this.validationPattern = v; return this; }
        public Builder fields(List<Map<String, Object>> v) { this.fields = v; return this; }
        public Builder shadow(boolean v) { this.shadow = v; return this; }

        public ApprovalRequest build() {
            return new ApprovalRequest(this);
        }
    }

    /**
     * The answer as the waiting block receives it. An approval accepts only one of its options
     * - compared case-insensitively, passed on in the option's spelling. Without
     * this check any free text silently became an approval. Human input
     * stays free: it expects text, numbers or a JSON form.
     *
     * @throws IllegalArgumentException if an approval does not offer the answer
     */
    public String acceptedAnswer(String answer) {
        if (!"approval".equals(type) || options == null || options.isEmpty() || answer == null) {
            return answer;
        }
        String trimmed = answer.strip();
        return options.stream()
                .filter(o -> o.strip().equalsIgnoreCase(trimmed))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Answer '" + answer + "' is not one of the offered options " + options));
    }

    /**
     * The answer to a human input from the values of its fields, as an input dialog sends it: a single
     * input answers with the value of its {@code variable_name}; a form ({@link #getFields() fields}) answers with a
     * JSON object of every field's value, an absent field as empty text. Values are sent as text.
     *
     * @param values the value per field, keyed by the field's variable name
     * @throws IllegalStateException    if this request is an approval, which takes one of its options instead
     * @throws IllegalArgumentException if {@code values} names a field the input does not have, or a single input
     *                                  gets no value
     */
    public String inputAnswer(Map<String, ?> values) {
        if (!"human-input".equals(type)) {
            throw new IllegalStateException("An approval takes one of its options, not input values");
        }
        List<String> names = fieldNames();
        for (String name : values.keySet()) {
            if (!names.contains(name)) {
                throw new IllegalArgumentException(
                        "The human input has no field '" + name + "'; its fields: " + names);
            }
        }
        if (fields == null || fields.isEmpty()) {
            if (!values.containsKey(variableName)) {
                throw new IllegalArgumentException("The human input needs a value for '" + variableName + "'");
            }
            return text(values.get(variableName));
        }
        Map<String, String> form = new LinkedHashMap<>();
        names.forEach(name -> form.put(name, text(values.get(name))));
        try {
            return JSON.writeValueAsString(form);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write the form answer as JSON", e);
        }
    }

    /** The variable names of a form's fields in their order, or the single input's variable name. */
    private List<String> fieldNames() {
        if (fields == null || fields.isEmpty()) {
            return variableName == null ? List.of() : List.of(variableName);
        }
        List<String> names = new ArrayList<>();
        for (Map<String, Object> field : fields) {
            if (field.get("variable_name") instanceof String name && !name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
