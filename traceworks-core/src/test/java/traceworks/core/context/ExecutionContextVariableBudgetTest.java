package traceworks.core.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The values of the variables a run's steps set count against its output budget
 * ({@link ExecutionContext#countVariables}): the current value of each, replaced when it changes.
 */
class ExecutionContextVariableBudgetTest {

    private static final Predicate<String> STEP_OUTPUTS = name -> name.matches("step_\\d+_output");

    private final OutputBudget budget = new OutputBudget(100);
    private final ExecutionContext context = ExecutionContext.builder().outputBudget(budget).build();

    @Test
    void valuesSetBeforeTheCountingStarts_doNotCount() {
        context.setVariable("input", "x".repeat(60));

        context.countVariables(STEP_OUTPUTS, name -> false);

        assertThat(budget.heldBytes()).isZero();
    }

    @Test
    void aValueAStepSets_counts_andItsReplacementReplacesIt() {
        context.countVariables(STEP_OUTPUTS, name -> false);

        context.setVariable("report", "x".repeat(40));
        assertThat(budget.heldBytes()).isEqualTo(40);
        context.setDerivedVariable("report", "y".repeat(10));
        assertThat(budget.heldBytes()).isEqualTo(10);
        context.setUntrustedVariable("other", "✓✓");
        assertThat(budget.heldBytes()).isEqualTo(14);
        context.removeVariable("report");
        assertThat(budget.heldBytes()).isEqualTo(4);
        context.setVariable("other", 7);
        assertThat(budget.heldBytes()).as("a number holds no text").isZero();
    }

    @Test
    void anInputThatAStepOverwrites_countsWithItsNewValue() {
        context.setVariable("ticket", "T-1");
        context.countVariables(STEP_OUTPUTS, name -> false);

        context.setVariable("ticket", "x".repeat(30));

        assertThat(budget.heldBytes()).isEqualTo(30);
    }

    @Test
    void aStepsOutput_neverCountsAsAVariable_sinceTheStepCountsIt() {
        context.countVariables(STEP_OUTPUTS, name -> false);

        context.setDerivedVariable("step_3_output", "x".repeat(90));

        assertThat(budget.heldBytes()).isZero();
    }

    @Test
    void theTextsOfAListOrAMap_count() {
        context.countVariables(STEP_OUTPUTS, name -> false);

        context.setVariable("form", Map.of("name", "flo"));
        context.setVariable("tags", List.of("a", "bc"));

        assertThat(budget.heldBytes()).isEqualTo(4 + 3 + 3);
    }

    @Test
    void aValueBeyondTheBudget_isSetAllTheSame_andTheBudgetIsExceeded() {
        context.countVariables(STEP_OUTPUTS, name -> false);
        context.setVariable("a", "x".repeat(80));

        context.setVariable("b", "y".repeat(30));

        assertThat(context.getVariable("b")).isEqualTo("y".repeat(30));
        assertThat(budget.exceeded()).isTrue();
        assertThat(budget.heldBytes()).isEqualTo(80);
        context.setVariable("b", "z");
        assertThat(budget.heldBytes()).as("its later value counts again").isEqualTo(81);
    }

    @Test
    void aResumedRun_countsTheVariablesItsStepsSet_atOnce_andReplacesThemLater() {
        context.setVariable("ticket", "T-1");
        context.setVariable("report", "x".repeat(40));
        context.setVariable("step_0_output", "x".repeat(50));

        context.countVariables(STEP_OUTPUTS, name -> !name.equals("ticket"));

        assertThat(budget.heldBytes()).isEqualTo(40);
        context.setVariable("report", "y");
        assertThat(budget.heldBytes()).isEqualTo(1);
    }
}
