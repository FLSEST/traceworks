package traceworks.it;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The script of the short security check ({@code examples/self-audit/security-changes/scripts/changes.sh}) as it
 * ships, in the built-in shell image: git reads the history of the workspace through the read-only mount, without
 * network, as the person who runs Traceworks, with plumbing commands only; and the checkout of a run started with {@code --source} carries the
 * history the script needs. The package here is the script's step alone: the area finder and the verdicts are
 * tested without a container (examples/self-audit/tests). The workspace is made a git repository with the git of
 * the machine that runs the suite.
 */
class SecurityChangesScriptIT extends IntegrationTest {

    private static final String WORKFLOW = """
            {
              "format": "traceworks-workflow",
              "format_version": 1,
              "id": "0b6f4f0a-5f0e-4b43-8a55-7d0f6c1e2a90",
              "name": "IT security changes script",
              "default_context": {"since": "", "previous": ""},
              "blocks": [
                {
                  "type": "shell",
                  "name": "changes",
                  "config": {
                    "values": {"SINCE": "{{ since }}", "PREVIOUS_REPORT": "{{ previous }}"},
                    "command": "set -euo pipefail; bash {{ package_dir }}/scripts/changes.sh {{ scratch_dir }}/changes \\"$SINCE\\" \\"$PREVIOUS_REPORT\\"; tr '\\\\0' ' ' < {{ scratch_dir }}/changes/manifest; cat {{ scratch_dir }}/changes/base/1",
                    "shell_escape_variables": true
                  }
                }
              ]
            }
            """;

    private Path pkg;
    private String first;
    private String second;

    private String git(String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(traceworks.workspace().toFile())
                .redirectErrorStream(true);
        builder.environment().putAll(Map.of("GIT_CONFIG_GLOBAL", "/dev/null", "GIT_CONFIG_NOSYSTEM", "1",
                "GIT_AUTHOR_NAME", "Tester", "GIT_AUTHOR_EMAIL", "tester@example.invalid",
                "GIT_COMMITTER_NAME", "Tester", "GIT_COMMITTER_EMAIL", "tester@example.invalid"));
        Path output = Files.createTempFile(sandbox, "git", ".txt");
        builder.redirectOutput(output.toFile());
        try {
            Process process = builder.start();
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).as(String.join(" ", args) + "\n" + Files.readString(output)).isZero();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        return Files.readString(output).strip();
    }

    private String commit(String content) throws IOException {
        Files.writeString(traceworks.workspace().resolve("A.java"), content, StandardCharsets.UTF_8);
        git("add", "-A");
        git("commit", "--quiet", "-m", "change");
        return git("rev-parse", "HEAD");
    }

    @BeforeEach
    void repositoryAndPackage() throws IOException {
        git("init", "--quiet", "--initial-branch=main");
        first = commit("one\ntwo\n");
        second = commit("one\nTWO\n");
        pkg = Files.createDirectories(sandbox.resolve("changes-script"));
        Files.createDirectories(pkg.resolve("scripts"));
        Files.copy(traceworks.examplePackage("self-audit/security-changes").resolve("scripts/changes.sh"),
                pkg.resolve("scripts/changes.sh"));
        Files.writeString(pkg.resolve("workflow.json"), WORKFLOW, StandardCharsets.UTF_8);
    }

    @Test
    void inTheShellImage_gitReadsTheHistoryOfTheReadOnlyWorkspace_andWhatIsInNoCommitCounts() throws IOException {
        Files.writeString(traceworks.workspace().resolve("A.java"), "one\nTWO\nthree\n", StandardCharsets.UTF_8);

        Result run = traceworks.exec("run", pkg.toString(), "--workspace", traceworks.workspace().toString(),
                "--no-input", "--input", "since=" + first);

        assertThat(run.code()).as(run.describe()).isZero();
        assertThat(traceworks.folder(run).output("changes")).contains("FACT changes_base: " + first + "\n",
                "FACT changes_head: " + second + "\n", "FACT changes_uncommitted: true\n", "M A.java 1 ",
                "one\ntwo\n");
        assertThat(traceworks.workspace().resolve(".git/index.lock")).doesNotExist();
    }

    @Test
    void withoutABase_theStepFails_andSaysWhatToGive() {
        Result run = traceworks.exec("run", pkg.toString(), "--workspace", traceworks.workspace().toString(),
                "--no-input");

        assertThat(run.code()).as(run.describe()).isNotZero();
        assertThat(run.out() + run.err()).contains("no base: this audit has no earlier run that names its workspace"
                + " commit");
    }

    @Test
    void theCheckoutOfSource_carriesTheHistory_soTheBaseOfAnEarlierRunIsThere() {
        Result run = traceworks.exec("run", pkg.toString(), "--source", traceworks.workspace().toString(),
                "--no-input", "--input", "previous=FACT changes_head: " + first);

        assertThat(run.code()).as(run.describe()).isZero();
        assertThat(traceworks.folder(run).output("changes")).contains("FACT changes_base: " + first + "\n",
                "FACT changes_head: " + second + "\n", "FACT changes_base_from: register\n",
                "FACT changes_uncommitted: false\n", "M A.java 1 ", "one\ntwo\n");
    }
}
