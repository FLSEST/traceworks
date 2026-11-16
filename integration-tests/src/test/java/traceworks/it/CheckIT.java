package traceworks.it;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code traceworks check} (docs/cli.md "check"): a broken package reports each error with its rule and exits 6, and a
 * run of it is refused before it exists, by the check before a run, or with {@code --no-check} before its first block,
 * by the engine; every other package of the suite checks without an error.
 */
class CheckIT extends IntegrationTest {

    @Test
    void aBrokenPackage_reportsItsErrors_andExits6() {
        Result check = traceworks.exec("check", traceworks.packageDir("broken").toString());

        assertThat(check.code()).as(check.describe()).isEqualTo(6);
        assertThat(check.out())
                .containsPattern("(?m)^ERROR +unknown-requirement +requires names \"time\\.travel\"")
                .containsPattern("(?m)^ERROR +unsupported-block-type +unknown_type \\(block 0\\): .*telepathy")
                .containsPattern("(?m)^ERROR +explicit-execution-mode +no_mode \\(block 1\\): ")
                .containsPattern("(?m)^ERROR +mount-mode-required +no_mode_mount \\(block 2\\): ")
                .containsPattern("(?m)^ERROR +invalid-config-value +bad_stage \\(block 3\\): Stage 1 \\(lines\\.head\\): ")
                .as("a finding on the workflow as a whole names no block").doesNotContain("null (block", "(block -1)");
        assertThat(traceworks.folders()).as("check runs nothing").isEmpty();
    }

    /** docs/cli.md "run": an error of the check refuses the run with the lines of check, and no run exists. */
    @Test
    void aRunOfTheBrokenPackage_isRefusedByTheCheck_andNoRunExists() {
        Result run = traceworks.run("broken");

        assertThat(run.code()).as(run.describe()).isEqualTo(6);
        assertThat(run.out()).as(run.describe()).isEmpty();
        assertThat(run.err())
                .containsPattern("(?m)^ERROR +unknown-requirement +requires names \"time\\.travel\"")
                .containsPattern("(?m)^ERROR +unsupported-block-type +unknown_type \\(block 0\\): .*telepathy")
                .containsPattern("(?m)^traceworks: the run is refused: check reports \\d+ errors; fix them, or start"
                        + " the run with --no-check$")
                .doesNotContainPattern("(?m)^INFO ");
        assertThat(traceworks.folders()).as("refused before a run exists").isEmpty();
    }

    /**
     * The commonest typos (docs/cli.md "check"): each is a finding of its own, the errors refuse the run, and with
     * {@code --no-check} the run is green with every placeholder as text, which is what the check is there to stop.
     */
    @Test
    void theCommonestTypos_areFindings_refuseTheRun_andReachTheStepAsTextWithoutTheCheck() {
        Result check = traceworks.exec("check", traceworks.packageDir("typos").toString());
        Result run = traceworks.run("typos");
        Result unchecked = traceworks.run("typos", "--no-check");

        assertThat(check.code()).as(check.describe()).isEqualTo(6);
        assertThat(check.out())
                .containsPattern("(?m)^ERROR +unknown-block-reference +greet \\(block 1\\): .*block\\.gret\\.output")
                .containsPattern("(?m)^ERROR +unknown-block-property +greet \\(block 1\\): .*\"outptu\"")
                .containsPattern("(?m)^ERROR +step-output-out-of-range +greet \\(block 1\\): .*step_7_output"
                        + ".*2 blocks")
                .containsPattern("(?m)^ERROR +duplicate-block-name +greet \\(block 1\\): ")
                .containsPattern("(?m)^WARNING +unknown-variable +greet \\(block 1\\): \\{\\{ nme }} — nothing sets it"
                        + " \\(did you mean \\{\\{ name }} for \\{\\{ nme }}\\?\\)");
        assertThat(check.out().lines().filter(line -> line.startsWith("ERROR"))).hasSize(4);
        assertThat(run.code()).as(run.describe()).isEqualTo(6);
        assertThat(run.err()).contains("unknown-block-property", "check reports 4 errors");
        assertThat(traceworks.folders()).as("only the unchecked run exists").hasSize(1);
        assertThat(unchecked.code()).as(unchecked.describe()).isZero();
        assertThat(traceworks.folder(unchecked).output("greet")).isEqualTo(
                "{{ block.gret.output }} {{ block.greet.outptu }} {{ step_7_output }} {{ nme }}");
    }

    /** An input no block reads (docs/cli.md "run"): named on standard error, and the run starts all the same. */
    @Test
    void anInputNoBlockReads_isNamed_andTheRunStarts() {
        Result run = traceworks.run("limits", "--input", "nme=Anna");

        assertThat(run.code()).as(run.describe()).isZero();
        assertThat(run.err()).contains("traceworks: warning: input \"nme\" is read by no block and is not in"
                + " default_context, so it changes nothing in this run");
    }

    @Test
    void aRunOfTheBrokenPackage_withoutTheCheck_isRefusedBeforeItsFirstBlock() {
        Result run = traceworks.run("broken", "--no-check");

        assertThat(run.code()).as(run.describe()).isEqualTo(6);
        assertThat(run.out()).contains("workflow refused", "time.travel", "telepathy");
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.ownEvents("step.started")).isEmpty();
        assertThat(folder.only("run.finished").path("status").asText()).isEqualTo("FAILED");
    }

    /**
     * Settings of the wrong type (docs/cli.md "check"): {@code check} reports each one in the run's words, and a run
     * fails the block that reads the first one before it runs anything. With the text {@code "true"} as
     * {@code shell_escape_variables}, the previous output {@code x; echo INJECTED} once ran as a command.
     */
    @Test
    void settingsOfTheWrongType_areErrors_andFailTheBlockThatReadsThem() {
        Result check = traceworks.exec("check", traceworks.packageDir("wrong-types").toString());

        assertThat(check.code()).as(check.describe()).isEqualTo(6);
        assertThat(check.out())
                .containsPattern("(?m)^ERROR +invalid-config-value +escape_as_text \\(block 1\\): shell: "
                        + "'shell_escape_variables' must be true or false, found the text \"true\"")
                .containsPattern("(?m)^ERROR +invalid-config-value +never_reached \\(block 2\\): shell: "
                        + "'timeout_seconds' must be a whole number between 1 and 86,400, found the text \"5\"")
                .containsPattern("(?m)^WARNING +unknown-config-key +never_reached \\(block 2\\): unknown key "
                        + "min_lenght in validation");

        Result run = traceworks.run("wrong-types", "--no-check");

        assertThat(run.code()).as(run.describe()).isEqualTo(3);
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.stepStatus("make_output")).isEqualTo("COMPLETED");
        assertThat(folder.stepStatus("escape_as_text")).as("ended, not left RUNNING").isEqualTo("FAILED");
        String error = "InvalidSettingException: shell: 'shell_escape_variables' must be true or false, found the"
                + " text \"true\"";
        assertThat(folder.output("escape_as_text")).isEqualTo(error);
        assertThat(folder.blob(folder.only("run.finished").path("error"))).isEqualTo(error);
        assertThat(folder.ownEvents("step.started")).hasSize(2);
        assertThat(folder.ownEvents("step.command")).as("only the first block ran a command").hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"placeholders", "branches", "loops", "children", "approval", "human-input", "compress",
            "gates", "isolation", "secrets", "secret-astral", "secret-broken-off", "package-image",
            "missing-image", "limits", "timeout", "output-limit", "output-limit-setting", "items-timeout",
            "output-budget", "retry-cancel", "register-files", "combo-pipeline", "combo-approval",
            "combo-failures", "combo-register", "pause-child"})
    void everyOtherPackage_checksWithoutAnError(String name) {
        Result check = traceworks.exec("check", traceworks.packageDir(name).toString());

        assertThat(check.code()).as(check.describe()).isZero();
        assertThat(check.out()).doesNotContainPattern("(?m)^(ERROR|WARNING) ");
    }

    /**
     * A package whose variables only an input sets (the host directories of {@code mounts}): {@code check}, which
     * knows no inputs, warns about each; a run that gets them warns about nothing.
     */
    @Test
    void aVariableOnlyAnInputSets_isAWarningOfCheck_andNoneOfARunThatGetsIt() throws IOException {
        Path readOnly = Files.createDirectories(sandbox.resolve("ro-data"));
        Files.writeString(readOnly.resolve("given.txt"), "given-content\n");
        Path writable = Files.createDirectories(sandbox.resolve("rw-data"));
        Result check = traceworks.exec("check", traceworks.packageDir("mounts").toString());
        Result run = traceworks.run("mounts", "--input", "ro_dir=" + readOnly, "--input", "rw_dir=" + writable);

        assertThat(check.code()).as(check.describe()).isZero();
        assertThat(check.out().lines().filter(line -> line.startsWith("WARNING")))
                .hasSize(3).allMatch(line -> line.contains("unknown-variable"));
        assertThat(check.out()).doesNotContainPattern("(?m)^ERROR ");
        assertThat(run.code()).as(run.describe()).isZero();
        assertThat(run.err()).as(run.describe()).doesNotContain("unknown-variable", "WARNING");
    }
}
