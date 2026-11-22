package traceworks.core.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalRequestTest {

    // --- builder happy-path tests ---

    @Test
    void builder_allFieldsSet_returnsRequestWithValues() {
        List<String> options = List.of("Yes", "No");

        ApprovalRequest request = ApprovalRequest.builder()
                .blockId("block-1")
                .question("Proceed?")
                .options(options)
                .stepIndex(3)
                .type("human-input")
                .inputType("text")
                .defaultValue("default")
                .variableName("answer")
                .required(true)
                .validationPattern("^[a-z]+$")
                .build();

        assertThat(request.getBlockId()).isEqualTo("block-1");
        assertThat(request.getQuestion()).isEqualTo("Proceed?");
        assertThat(request.getOptions()).containsExactly("Yes", "No");
        assertThat(request.getStepIndex()).isEqualTo(3);
        assertThat(request.getType()).isEqualTo("human-input");
        assertThat(request.getInputType()).isEqualTo("text");
        assertThat(request.getDefaultValue()).isEqualTo("default");
        assertThat(request.getVariableName()).isEqualTo("answer");
        assertThat(request.isRequired()).isTrue();
        assertThat(request.getValidationPattern()).isEqualTo("^[a-z]+$");
    }

    // --- type default tests ---

    @Test
    void builder_typeNotSet_defaultsToApproval() {
        ApprovalRequest request = ApprovalRequest.builder()
                .blockId("block-1")
                .question("Proceed?")
                .build();

        assertThat(request.getType()).isEqualTo("approval");
    }

    @Test
    void builder_typeExplicitlySet_overridesDefault() {
        ApprovalRequest request = ApprovalRequest.builder()
                .type("human-input")
                .build();

        assertThat(request.getType()).isEqualTo("human-input");
    }

    // --- minimal builder tests ---

    @Test
    void builder_noFieldsSet_returnsRequestWithNullsAndDefaults() {
        ApprovalRequest request = ApprovalRequest.builder().build();

        assertThat(request.getBlockId()).isNull();
        assertThat(request.getQuestion()).isNull();
        assertThat(request.getOptions()).isNull();
        assertThat(request.getStepIndex()).isZero();
        assertThat(request.getType()).isEqualTo("approval");
        assertThat(request.getInputType()).isNull();
        assertThat(request.getDefaultValue()).isNull();
        assertThat(request.getVariableName()).isNull();
        assertThat(request.isRequired()).isFalse();
        assertThat(request.getValidationPattern()).isNull();
    }

    // --- options list tests ---

    @Test
    void builder_optionsEmptyList_returnsEmptyOptions() {
        ApprovalRequest request = ApprovalRequest.builder()
                .options(Collections.emptyList())
                .build();

        assertThat(request.getOptions()).isNotNull();
        assertThat(request.getOptions()).isEmpty();
    }

    @Test
    void builder_optionsSingleEntry_returnsOneOption() {
        ApprovalRequest request = ApprovalRequest.builder()
                .options(List.of("OnlyOption"))
                .build();

        assertThat(request.getOptions()).containsExactly("OnlyOption");
    }

    @Test
    void builder_optionsMultipleEntries_preservesOrder() {
        ApprovalRequest request = ApprovalRequest.builder()
                .options(List.of("Approve", "Reject", "Defer"))
                .build();

        assertThat(request.getOptions()).containsExactly("Approve", "Reject", "Defer");
    }

    // --- required flag tests ---

    @Test
    void builder_requiredTrue_returnsTrue() {
        ApprovalRequest request = ApprovalRequest.builder()
                .required(true)
                .build();

        assertThat(request.isRequired()).isTrue();
    }

    @Test
    void builder_requiredFalse_returnsFalse() {
        ApprovalRequest request = ApprovalRequest.builder()
                .required(false)
                .build();

        assertThat(request.isRequired()).isFalse();
    }

    // --- stepIndex tests ---

    @Test
    void builder_stepIndexNegative_returnsNegativeValue() {
        ApprovalRequest request = ApprovalRequest.builder()
                .stepIndex(-1)
                .build();

        assertThat(request.getStepIndex()).isEqualTo(-1);
    }

    @Test
    void builder_stepIndexLarge_returnsValue() {
        ApprovalRequest request = ApprovalRequest.builder()
                .stepIndex(Integer.MAX_VALUE)
                .build();

        assertThat(request.getStepIndex()).isEqualTo(Integer.MAX_VALUE);
    }

    // --- human-input variant tests ---

    @Test
    void builder_humanInputFields_returnsHumanInputRequest() {
        ApprovalRequest request = ApprovalRequest.builder()
                .blockId("input-1")
                .question("Enter your name")
                .type("human-input")
                .inputType("text")
                .variableName("user_name")
                .required(true)
                .validationPattern("^[A-Za-z ]+$")
                .build();

        assertThat(request.getType()).isEqualTo("human-input");
        assertThat(request.getInputType()).isEqualTo("text");
        assertThat(request.getVariableName()).isEqualTo("user_name");
        assertThat(request.isRequired()).isTrue();
        assertThat(request.getValidationPattern()).isEqualTo("^[A-Za-z ]+$");
    }

    @Test
    void builder_emptyStrings_preservesEmptyStrings() {
        ApprovalRequest request = ApprovalRequest.builder()
                .blockId("")
                .question("")
                .defaultValue("")
                .build();

        assertThat(request.getBlockId()).isEmpty();
        assertThat(request.getQuestion()).isEmpty();
        assertThat(request.getDefaultValue()).isEmpty();
    }

    // --- inputAnswer: the answer to a human input from named values ---

    private static ApprovalRequest singleInput() {
        return ApprovalRequest.builder().type("human-input").variableName("branch").build();
    }

    private static ApprovalRequest form() {
        return ApprovalRequest.builder().type("human-input").variableName("user_input")
                .fields(List.of(Map.of("variable_name", "name"), Map.of("variable_name", "count"))).build();
    }

    @Test
    void inputAnswer_singleInput_isTheValueOfItsVariable() {
        assertThat(singleInput().inputAnswer(Map.of("branch", "main"))).isEqualTo("main");
        assertThat(singleInput().inputAnswer(Map.of("branch", 42))).isEqualTo("42");
    }

    @Test
    void inputAnswer_singleInputWithoutItsVariable_isRefused() {
        assertThatThrownBy(() -> singleInput().inputAnswer(Map.of("brnach", "main")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The human input has no field 'brnach'; its fields: [branch]");
        assertThatThrownBy(() -> singleInput().inputAnswer(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The human input needs a value for 'branch'");
    }

    /** As an input dialog sends a form: a JSON object of the field values, each as text. */
    @Test
    void inputAnswer_form_isAJsonObjectOfTheFieldValuesAsText() throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("count", 3);
        values.put("name", "Ada");

        String answer = form().inputAnswer(values);

        assertThat(new ObjectMapper().readValue(answer, Map.class)).isEqualTo(Map.of("name", "Ada", "count", "3"));
    }

    @Test
    void inputAnswer_formWithAFieldItDoesNotHave_isRefused() {
        assertThatThrownBy(() -> form().inputAnswer(Map.of("name", "Ada", "age", 7)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The human input has no field 'age'; its fields: [name, count]");
    }

    @Test
    void inputAnswer_formWithAMissingOptionalField_leavesItEmpty() throws Exception {
        ApprovalRequest optional = ApprovalRequest.builder().type("human-input").variableName("user_input")
                .fields(List.of(Map.of("variable_name", "name"),
                        Map.of("variable_name", "count", "required", false))).build();

        String answer = optional.inputAnswer(Map.of("name", "Ada"));

        assertThat(new ObjectMapper().readValue(answer, Map.class)).isEqualTo(Map.of("name", "Ada", "count", ""));
    }

    // --- the rules of a field: every answer of a person or an agent is checked against them ---

    private static ApprovalRequest guarded() {
        return ApprovalRequest.builder().type("human-input").variableName("n").inputType("text").required(true)
                .validationPattern("[0-9]+").build();
    }

    /** The defect of TODO 342a71a5: a value the pattern refuses reached a later shell step unchanged. */
    @Test
    void inputAnswer_aValueThePatternDoesNotMatch_isRefused_namingTheFieldAndTheRule() {
        assertThatThrownBy(() -> guarded().inputAnswer(Map.of("n", "abc; rm -rf x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The human input refuses the value for 'n' (validation_pattern):"
                        + " 'abc; rm -rf x' does not match the pattern [0-9]+");
        assertThat(guarded().inputAnswer(Map.of("n", "42"))).isEqualTo("42");
    }

    /** The pattern holds the value as it is stored: blanks around a match are no part of it. */
    @Test
    void inputAnswer_aMatchWithBlanksAroundIt_isRefused() {
        assertThatThrownBy(() -> guarded().inputAnswer(Map.of("n", " 42")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("(validation_pattern)");
    }

    @Test
    void inputAnswer_aRequiredValueLeftBlank_isRefused_andAnOptionalOneIsEmpty() {
        assertThatThrownBy(() -> guarded().inputAnswer(Map.of("n", "  ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The human input refuses the value for 'n' (required): a value is required");
        ApprovalRequest optional = guarded().toBuilder().required(false).build();
        assertThat(optional.inputAnswer(Map.of("n", ""))).isEmpty();
    }

    @Test
    void inputAnswer_aSelect_takesOnlyItsOptions_inTheirSpelling() {
        ApprovalRequest select = ApprovalRequest.builder().type("human-input").variableName("channel")
                .inputType("select").options(List.of("stable", "beta")).required(true).build();

        assertThatThrownBy(() -> select.inputAnswer(Map.of("channel", "nightly")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The human input refuses the value for 'channel' (options):"
                        + " 'nightly' is none of the options [stable, beta]");
        assertThat(select.inputAnswer(Map.of("channel", "Beta"))).isEqualTo("beta");
    }

    @Test
    void inputAnswer_theInputType_isChecked() {
        ApprovalRequest number = guarded().toBuilder().inputType("number").validationPattern(null).build();
        ApprovalRequest date = number.toBuilder().inputType("date").build();
        ApprovalRequest checkbox = number.toBuilder().inputType("checkbox").build();

        assertThatThrownBy(() -> number.inputAnswer(Map.of("n", "many")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The human input refuses the value for 'n' (input_type): 'many' is not a number");
        assertThatThrownBy(() -> date.inputAnswer(Map.of("n", "30.11.2026")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("(input_type)").hasMessageContaining("YYYY-MM-DD");
        assertThatThrownBy(() -> checkbox.inputAnswer(Map.of("n", "maybe")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("(input_type)").hasMessageContaining("neither true nor false");
        assertThat(number.inputAnswer(Map.of("n", "12.5"))).isEqualTo("12.5");
        assertThat(date.inputAnswer(Map.of("n", "2026-11-30"))).isEqualTo("2026-11-30");
        assertThat(checkbox.inputAnswer(Map.of("n", "Yes"))).isEqualTo("true");
    }

    /** A form checks each field against its own rules, a missing field included. */
    @Test
    void inputAnswer_aFormField_isCheckedAgainstItsOwnRules() {
        ApprovalRequest release = ApprovalRequest.builder().type("human-input").variableName("user_input")
                .fields(List.of(Map.of("variable_name", "version", "validation_pattern", "[0-9]+\\.[0-9]+\\.[0-9]+"),
                        Map.of("variable_name", "channel", "input_type", "select",
                                "options", List.of("stable", "beta")))).build();

        assertThatThrownBy(() -> release.inputAnswer(Map.of("version", "1.4.0; rm -rf x", "channel", "beta")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The human input refuses the value for 'version' (validation_pattern):"
                        + " '1.4.0; rm -rf x' does not match the pattern [0-9]+\\.[0-9]+\\.[0-9]+");
        assertThatThrownBy(() -> release.inputAnswer(Map.of("version", "1.4.0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The human input refuses the value for 'channel' (required): a value is required");
        assertThat(release.inputAnswer(Map.of("version", "1.4.0", "channel", "STABLE")))
                .isEqualTo("{\"version\":\"1.4.0\",\"channel\":\"stable\"}");
    }

    /** The answer as the waiting block receives it (a preset of a parked run): a human input's is checked too. */
    @Test
    void acceptedAnswer_ofAHumanInput_isCheckedAgainstTheRules_aSingleValueAndAForm() {
        ApprovalRequest release = ApprovalRequest.builder().type("human-input").variableName("user_input")
                .fields(List.of(Map.of("variable_name", "version", "validation_pattern", "[0-9]+"))).build();

        assertThatThrownBy(() -> guarded().acceptedAnswer("abc; rm -rf x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'n' (validation_pattern)");
        assertThat(guarded().acceptedAnswer("42")).isEqualTo("42");
        assertThatThrownBy(() -> release.acceptedAnswer("{\"version\":\"x\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'version' (validation_pattern)");
        assertThatThrownBy(() -> release.acceptedAnswer("not a form"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON object");
        assertThat(release.acceptedAnswer("{\"version\":\"7\"}")).isEqualTo("{\"version\":\"7\"}");
    }

    @Test
    void inputAnswer_toAnApproval_isRefused() {
        ApprovalRequest approval = ApprovalRequest.builder().options(List.of("Yes", "No")).build();

        assertThatThrownBy(() -> approval.inputAnswer(Map.of("x", "y")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("An approval takes one of its options, not input values");
    }
}
