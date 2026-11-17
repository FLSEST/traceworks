package traceworks.core.blocks;

import traceworks.core.compat.GermanWords;
import traceworks.core.context.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The field formats an AI actor's answer is checked against. */
class HumanInputAnswersTest {

    private static HumanInputBlockExecutor.Settings single(String inputType, Map<String, Object> extra) {
        Map<String, Object> config = new HashMap<>(Map.of("prompt", "p", "input_type", inputType));
        config.putAll(extra);
        return HumanInputBlockExecutor.Settings.from(config,
                ExecutionContext.builder().containerId("c").workflowRunId("r").build());
    }

    /** A form of two fields. */
    private static HumanInputBlockExecutor.Settings form(Map<String, Object> field, Map<String, Object> other) {
        Map<String, Object> config = new HashMap<>(Map.of("prompt", "p", "variable_name", "f",
                "fields", List.of(field, other)));
        return HumanInputBlockExecutor.Settings.from(config,
                ExecutionContext.builder().containerId("c").workflowRunId("r").build());
    }

    @Test
    void number_date_checkbox_andPattern_areChecked() {
        assertThat(HumanInputAnswers.validate("12.5", single("number", Map.of())).answer()).isEqualTo("12.5");
        assertThat(HumanInputAnswers.validate("twelve", single("number", Map.of())).error()).contains("not a number");
        assertThat(HumanInputAnswers.validate("2026-10-12", single("date", Map.of())).answer()).isEqualTo("2026-10-12");
        assertThat(HumanInputAnswers.validate("12.10.2026", single("date", Map.of())).error()).contains("YYYY-MM-DD");
        assertThat(HumanInputAnswers.validate("Yes", single("checkbox", Map.of())).answer()).isEqualTo("true");
        assertThat(HumanInputAnswers.validate("maybe", single("checkbox", Map.of())).error()).contains("neither true nor false");
        var pattern = single("text", Map.of("validation_pattern", "^PO-\\d+$"));
        assertThat(HumanInputAnswers.validate("PO-7", pattern).answer()).isEqualTo("PO-7");
        assertThat(HumanInputAnswers.validate("7", pattern).error()).contains("does not match the pattern");
    }

    @Test
    void required_blankAnswer_isRejected_optionalIsKeptEmpty() {
        assertThat(HumanInputAnswers.validate("  ", single("text", Map.of())).error()).contains("a value is required");
        assertThat(HumanInputAnswers.validate("", single("text", Map.of("required", false))).answer()).isEmpty();
    }

    @Test
    void task_describesTheSingleField() {
        String task = HumanInputAnswers.task("How many?", single("number", Map.of("required", false)));

        assertThat(task).contains("## Input requested", "How many?", "One value (a number; optional, empty allowed)");
    }

    @Test
    void same_comparesSelectIgnoringCase_andTextExactly() {
        assertThat(HumanInputAnswers.same("English", "english", single("select", Map.of("options", List.of("English"))))).isTrue();
        assertThat(HumanInputAnswers.same("Alice", "alice", single("text", Map.of()))).isFalse();
        assertThat(HumanInputAnswers.same(" Alice ", "Alice", single("text", Map.of()))).isTrue();
    }

    @Test
    void same_normalizesTheProposalFirst() {
        assertThat(HumanInputAnswers.same("true", "yes", single("checkbox", Map.of()))).isTrue();
        assertThat(HumanInputAnswers.same("false", GermanWords.CHECKBOX_NO, single("checkbox", Map.of())))
                .as("the German no of existing forms is still read").isTrue();
        assertThat(HumanInputAnswers.same("true", "maybe", single("checkbox", Map.of())))
                .as("a proposal that fails the checks never agrees").isFalse();
    }

    @Test
    void same_formSelectIgnoresCase_likeTheSingleField() {
        Map<String, Object> config = new HashMap<>(Map.of("prompt", "p", "variable_name", "f", "fields", List.of(
                Map.of("variable_name", "size", "input_type", "select", "options", List.of("S", "M")),
                Map.of("variable_name", "done", "input_type", "checkbox"))));
        var settings = HumanInputBlockExecutor.Settings.from(config,
                ExecutionContext.builder().containerId("c").workflowRunId("r").build());
        String proposal = "{\"size\":\"m\",\"done\":\"" + GermanWords.CHECKBOX_YES + "\"}";

        assertThat(HumanInputAnswers.same("{\"size\":\"M\",\"done\":\"true\"}", proposal, settings)).isTrue();
        assertThat(HumanInputAnswers.same("{\"size\":\"S\",\"done\":\"true\"}", proposal, settings)).isFalse();
    }


    @Test
    void aPatternThatCostsTooMuch_isGivenUpAtTheTimeLimit_andTheAnswerIsRefused() {
        // A back reference keeps the JDK from remembering failed positions: the match time doubles with every letter
        var pattern = single("text", Map.of("validation_pattern", "((a+)\\2?)+b"));

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(20), () ->
                assertThat(HumanInputAnswers.validate("a".repeat(60), pattern).error())
                        .contains("validation_pattern", "could not be checked within"));
    }

    /** Such a pattern reads no character, so the time limit alone would never end the check. */
    @Test
    void aPatternWhoseCountedRepetitionsMultiply_refusesTheAnswerAtOnce() {
        var pattern = single("text", Map.of("validation_pattern", "(?:(?:){2147483647}){2147483647}"));

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () ->
                assertThat(HumanInputAnswers.validate("a", pattern).error())
                        .contains("validation_pattern", "repetition"));
        assertThat(HumanInputAnswers.validate("2026-11-12",
                single("text", Map.of("validation_pattern", "^\\d{4}-\\d{2}-\\d{2}$"))).answer()).isEqualTo("2026-11-12");
    }

    @Test
    void checkbox_takesTrueAndFalse_yesAndNo_andTheGermanWords_inAnyCase() {
        var checkbox = single("checkbox", Map.of());

        for (String yes : List.of("true", "TRUE", "yes", "Yes", GermanWords.CHECKBOX_YES)) {
            assertThat(HumanInputAnswers.validate(yes, checkbox).answer()).as(yes).isEqualTo("true");
        }
        for (String no : List.of("false", "False", "no", "NO", GermanWords.CHECKBOX_NO)) {
            assertThat(HumanInputAnswers.validate(no, checkbox).answer()).as(no).isEqualTo("false");
        }
    }

    /** An optional field of a form may be left empty; a required one may not. */
    @Test
    void form_anOptionalFieldLeftEmpty_isValid() {
        var settings = form(Map.of("variable_name", "name", "input_type", "text"),
                Map.of("variable_name", "note", "input_type", "text", "required", false));

        assertThat(HumanInputAnswers.validate("{\"name\":\"Anna\",\"note\":\"\"}", settings).answer())
                .isEqualTo("{\"name\":\"Anna\",\"note\":\"\"}");
        assertThat(HumanInputAnswers.validate("{\"name\":\"\",\"note\":\"x\"}", settings).error())
                .contains("a value is required");
    }

    /** The task of a form names each field with its pattern and its description. */
    @Test
    void task_ofAForm_namesThePatternAndTheDescriptionOfAField() {
        String task = HumanInputAnswers.task("Order?", form(Map.of("variable_name", "order", "input_type", "text",
                "validation_pattern", "^PO-\\d+$", "description", "as on the invoice"),
                Map.of("variable_name", "note", "input_type", "text")));

        assertThat(task).contains("- `order` (free text; must match the regular expression ^PO-\\d+$); as on the"
                + " invoice\n");
    }

    /** A checkbox answer compares without its case, as a select does. */
    @Test
    void same_comparesACheckboxIgnoringCase() {
        assertThat(HumanInputAnswers.same("True", "yes", single("checkbox", Map.of()))).isTrue();
    }

    /** The AI actor is told the pattern its answer is checked against, and nothing about one when there is none. */
    @Test
    void task_namesTheValidationPattern_onlyWhenThereIsOne() {
        String withPattern = HumanInputAnswers.task("Which order?",
                single("text", Map.of("validation_pattern", "^PO-\\d+$")));
        String without = HumanInputAnswers.task("Which order?", single("text", Map.of()));

        assertThat(withPattern).contains("One value (free text; must match the regular expression ^PO-\\d+$)");
        assertThat(without).contains("One value (free text)").doesNotContain("must match");
    }

    @Test
    void date_isTheWholeAnswer_notADateSomewhereInIt() {
        for (String answer : List.of("on 2026-10-12", "2026-10-12T08:00", "2026-10-123", "12026-10-12")) {
            assertThat(HumanInputAnswers.validate(answer, single("date", Map.of())).error()).as(answer)
                    .contains("is not a date in the form YYYY-MM-DD");
        }
    }

    /**
     * The engine reads groups by recursion, and a pattern nested deeply enough overflows the stack while it is
     * compiled. A pattern that long is refused for its length before the engine sees it, so the answer is refused
     * and the run goes on, and the message does not repeat the pattern.
     */
    @Test
    void aPatternNestedTooDeeplyToCompile_refusesTheAnswer_insteadOfEndingTheRun() {
        var pattern = single("text", Map.of("validation_pattern", "(".repeat(200_000) + "a" + ")".repeat(200_000)));

        String error = HumanInputAnswers.validate("a", pattern).error();

        assertThat(error).contains("validation_pattern cannot be used", "longer than 4,096 characters",
                "shorten the pattern");
        assertThat(error.length()).isLessThan(400);
    }
}
