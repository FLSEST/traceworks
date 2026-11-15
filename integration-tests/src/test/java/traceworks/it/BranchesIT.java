package traceworks.it;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code conditional} in shell mode and {@code switch} (package {@code branches}): the input {@code mode} decides
 * which branch and which case run, and a later step reads both outputs by name.
 */
class BranchesIT extends IntegrationTest {

    @ParameterizedTest(name = "mode {0}: {1}, {2}")
    @CsvSource({
            "fast,  then-branch, case-fast,    true,  fast|quick",
            "quick, else-branch, case-fast,    false, fast|quick",
            "slow,  else-branch, case-slow,    false, slow",
            "other, else-branch, case-default, false, default"
    })
    void theConditionPicksABranch_andTheExpressionACase(String mode, String branch, String caseOutput,
                                                         boolean conditionMet, String matchedCase) {
        Result run = traceworks.run("branches", "--input", "mode=" + mode);

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.output("pick")).isEqualTo(branch);
        assertThat(folder.metadata("pick").path("conditionMet").asBoolean()).isEqualTo(conditionMet);
        assertThat(folder.output("route")).isEqualTo(caseOutput);
        assertThat(folder.metadata("route").path("matchedCase").asText()).isEqualTo(matchedCase);
        assertThat(folder.output("after")).isEqualTo("after: " + branch + " " + caseOutput);
        // The condition and the one branch that ran are commands of the conditional's step; no other branch ran
        assertThat(folder.ofStep("step.command", folder.stepIndex("pick"))).hasSize(2);
        assertThat(run.out()).doesNotContain(branch.equals("then-branch") ? "else-branch" : "then-branch");
    }
}
