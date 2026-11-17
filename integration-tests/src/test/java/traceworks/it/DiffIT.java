package traceworks.it;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code traceworks diff} and {@code traceworks runs --long} on real runs of {@code examples/hello} (docs/cli.md,
 * "diff"): the same inputs in the same workspace reproduce a run (exit 0); a changed input, or a changed workspace,
 * does not (exit 11), and the command names what differs.
 */
class DiffIT extends IntegrationTest {

    /** Runs {@code examples/hello} to its end: it parks at its approval, which is answered {@code Yes}, and resumes. */
    private String hello(String... more) {
        Result run = traceworks.runExample("hello", more);
        assertThat(run.code()).as(run.describe()).isEqualTo(5);
        String id = run.runId();
        Result approve = traceworks.exec("approve", id, "--answer", "Yes");
        assertThat(approve.code()).as(approve.describe()).isZero();
        Result resume = traceworks.exec("resume", id, "--no-input");
        assertThat(resume.code()).as(resume.describe()).isZero();
        return id;
    }

    @Test
    void theSameInputs_reproduceTheRun_andAChangedInputDoesNot() {
        String first = hello("--input", "name=Anna");
        String second = hello("--input", "name=Anna");
        String third = hello("--input", "name=Bert");

        Result same = traceworks.exec("diff", first.substring(0, 8), second.substring(0, 8));
        Result differs = traceworks.exec("diff", second, third, "--outputs");

        assertThat(same.code()).as(same.describe()).isZero();
        assertThat(same.out()).contains("Run A     " + first, "Run B     " + second)
                .containsPattern("(?m)^  same: workflow id, workflow name, package digest, .*image shell.*gate$")
                .contains("  same: name")
                .containsPattern("(?m)^\\| greet +COMPLETED +same output$")
                .containsPattern("(?m)^\\| count +COMPLETED +same output$")
                .containsPattern("(?m)^\\| publish +COMPLETED +same output$")
                .containsPattern("(?m)^\\| done +COMPLETED +same output$")
                .contains("same: run B reproduced run A");
        assertThat(same.err()).isEmpty();

        assertThat(differs.code()).as(differs.describe()).isEqualTo(11);
        assertThat(differs.out())
                .containsPattern("(?m)^  inputs digest +A [0-9a-f]{64}\\R +B [0-9a-f]{64}$")
                .containsPattern("(?m)^\\| name +A \"Anna\"\\R +B \"Bert\"$")
                .containsPattern("(?m)^\\| greet +COMPLETED +output differs$")
                .contains("      | - Hello, Anna\n      | + Hello, Bert\n")
                .containsPattern("(?m)^\\| count +COMPLETED +same output$")
                .containsPattern("(?m)^\\| done +COMPLETED +output differs$")
                .contains("differs: run B did not reproduce run A", "| the inputs differ",
                        "| step greet: the output differs", "| step done: the output differs");

        List<String> runs = traceworks.exec("runs", "--long").out().lines().toList();
        assertThat(runs).hasSize(3);
        assertThat(runs.get(0)).startsWith(third.substring(0, 8)).contains("COMPLETED", "Hello")
                .containsPattern("  gate GREEN  \\S+  pkg [0-9a-f]{8}  inputs [0-9a-f]{8}\\*  models -$");
        assertThat(runs.get(1)).startsWith(second.substring(0, 8))
                .containsPattern("  pkg [0-9a-f]{8}  inputs [0-9a-f]{8}  models -$");
    }

    /** The same package and inputs, but another workspace content: a deterministic step's output differs, exit 11. */
    @Test
    void aChangedWorkspace_isADifferentOutput_withTheSameInputs() throws IOException {
        String first = hello();
        Files.writeString(traceworks.workspace().resolve("one-more.txt"), "x\n");
        String second = hello();

        Result diff = traceworks.exec("diff", first, second);

        assertThat(diff.code()).as(diff.describe()).isEqualTo(11);
        assertThat(diff.out()).containsPattern("(?m)^\\| count +COMPLETED +output differs$")
                .contains("differs: run B did not reproduce run A", "| step count: the output differs")
                .doesNotContain("the inputs differ").doesNotContain("the package digest differs");
    }

    /** A run that has not finished is an error with its own exit code and message, not a difference. */
    @Test
    void aParkedRun_isRefused() {
        String finished = hello();
        Result parked = traceworks.runExample("hello");
        assertThat(parked.code()).as(parked.describe()).isEqualTo(5);

        Result diff = traceworks.exec("diff", finished, parked.runId());

        assertThat(diff.code()).as(diff.describe()).isEqualTo(6);
        assertThat(diff.err()).contains(parked.runId(), "has not finished (PARKED)");
        Result cancel = traceworks.exec("cancel", parked.runId());
        assertThat(cancel.code()).as(cancel.describe()).isZero();
    }
}
