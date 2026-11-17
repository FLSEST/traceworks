package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a step may write on the host beyond its workspace and its scratch directory (docs/security.md, "Writable
 * mounts" and "A writable workspace"; packages {@code writable-mounts}, {@code mounts} and
 * {@code writable-workspace-git}):
 *
 * <ul>
 *   <li>a writable mount ({@code :rw}) is refused unless the operator's {@code writable_mounts} in
 *       {@code traceworks.json} lists a folder that holds it: before the first step for a mount the workflow writes
 *       out, when its step runs for one a placeholder names;</li>
 *   <li>in a listed folder a step writes it;</li>
 *   <li>a step with {@code workspace_writable: true} cannot write the hooks or the config of the workspace's
 *       {@code .git}, nor move them or the {@code .git} away, and can commit.</li>
 * </ul>
 */
class WritableMountsIT extends IntegrationTest {

    /** The folder the package {@code writable-mounts} mounts writable: {@code ~/exports/reports}. */
    private Path reports() throws IOException {
        return Files.createDirectories(traceworks.userHome().resolve("exports/reports"));
    }

    @Test
    void aWritableMountOutsideTheListedFolders_isRefusedBeforeTheFirstStep() throws Exception {
        Path reports = reports();
        Path elsewhere = Files.createDirectories(sandbox.resolve("listed-but-elsewhere"));

        for (Map<String, Object> settings : List.<Map<String, Object>>of(Map.of(),
                Map.of("writable_mounts", List.of(elsewhere.toString())))) {
            traceworks.configure(settings);

            Result run = traceworks.run("writable-mounts");

            assertThat(run.code()).as(run.describe()).isEqualTo(6);
            assertThat(run.both()).as(run.describe()).contains("Block 2 \"export\"",
                    "the mount ~/exports/reports:/out:rw is refused", "writable_mounts",
                    traceworks.home().resolve("traceworks.json").toString(), "\"" + reports.toRealPath() + "\"]");
            assertThat(traceworks.folders()).as("no run was created, so no step ran").isEmpty();
            assertThat(reports.resolve("report.txt")).doesNotExist();
        }

        Result described = traceworks.exec("describe", traceworks.packageDir("writable-mounts").toString());
        assertThat(described.code()).as(described.describe()).isZero();
        assertThat(described.out()).contains("~/exports/reports:/out:rw (writable, in no folder of writable_mounts");
    }

    /** A mount that a placeholder names is known when its step runs: the step is refused, and nothing starts. */
    @Test
    void aWritableMountAPlaceholderNames_isRefusedWhenItsStepRuns_andStartsNothing() throws Exception {
        Path readOnly = Files.createDirectories(sandbox.resolve("ro-data"));
        Files.writeString(readOnly.resolve("given.txt"), "given-content\n");
        Path writable = Files.createDirectories(sandbox.resolve("rw-data"));

        Result run = traceworks.run("mounts", "--input", "ro_dir=" + readOnly, "--input", "rw_dir=" + writable);

        assertThat(run.code()).as(run.describe()).isEqualTo(3);
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.output("read_ro")).as("a read-only mount needs no listed folder").isEqualTo("given-content");
        assertThat(folder.output("write_rw")).contains(":/data-rw:rw is refused", "writable_mounts",
                "\"writable_mounts\": [\"" + writable.toRealPath() + "\"]");
        JsonNode finished = folder.ofStep("step.command.finished", folder.stepIndex("write_rw")).getFirst();
        assertThat(finished.path("exit_code").asInt()).as("nothing was started").isEqualTo(125);
        assertThat(writable.resolve("written.txt")).doesNotExist();
    }

    @Test
    void aWritableMountInAListedFolder_isWrittenByAStep() throws Exception {
        Path reports = reports();
        Path writable = Files.createDirectories(sandbox.resolve("rw-data"));
        Path readOnly = Files.createDirectories(sandbox.resolve("ro-data"));
        Files.writeString(readOnly.resolve("given.txt"), "given-content\n");
        // One folder below the home directory, written with ~/, and one elsewhere, by its whole path
        traceworks.configure(Map.of("writable_mounts", List.of("~/exports", writable.toString())));

        Result literal = traceworks.run("writable-mounts");
        Result named = traceworks.run("mounts", "--input", "ro_dir=" + readOnly, "--input", "rw_dir=" + writable);

        assertThat(literal.code()).as(literal.describe()).isZero();
        assertThat(traceworks.folder(literal).output("export")).isEqualTo("from-the-container");
        assertThat(reports.resolve("report.txt")).hasContent("from-the-container\n");
        assertThat(named.code()).as(named.describe()).isZero();
        assertThat(writable.resolve("written.txt")).hasContent("from-the-container\n");

        Result described = traceworks.exec("describe", traceworks.packageDir("writable-mounts").toString());
        assertThat(described.code()).as(described.describe()).isZero();
        assertThat(described.out()).contains("~/exports/reports:/out:rw").doesNotContain("in no folder of");
    }

    /** A listed folder lifts no other refusal: a login below it stays refused. */
    @Test
    void aListedFolder_isNoWayAroundTheRefusalOfALogin() throws Exception {
        Path ssh = Files.createDirectories(traceworks.userHome().resolve("exports/.ssh"));
        Files.writeString(ssh.resolve("id_it"), "not a real key\n");
        Path readOnly = Files.createDirectories(sandbox.resolve("ro-data"));
        Files.writeString(readOnly.resolve("given.txt"), "given-content\n");
        Path copyOfAHome = traceworks.userHome().resolve("exports");
        traceworks.configure(Map.of("writable_mounts", List.of("~/exports")));

        Result run = traceworks.run("mounts", "--input", "ro_dir=" + readOnly, "--input", "rw_dir=" + copyOfAHome);

        assertThat(run.code()).as(run.describe()).isEqualTo(3);
        assertThat(traceworks.folder(run).output("write_rw")).contains(":/data-rw:rw is refused")
                .doesNotContain("writable_mounts").doesNotContain("id_it");
        assertThat(copyOfAHome.resolve("written.txt")).doesNotExist();
    }

    /** A setting that would list every folder, or the home directory, is refused when the file is read. */
    @Test
    void aListedFolderThatIsTheHomeDirectory_orTheRootOfTheFileSystem_isRefusedAsASetting() throws Exception {
        reports();
        for (String root : List.of("/", "~", traceworks.userHome().toString(), "exports")) {
            traceworks.configure(Map.of("writable_mounts", List.of(root)));

            Result run = traceworks.run("writable-mounts");

            assertThat(run.code()).as(run.describe()).isNotZero();
            assertThat(run.both()).as(run.describe()).contains("'writable_mounts'", "\"" + root + "\"");
            assertThat(traceworks.folders()).as(root).isEmpty();
        }
    }

    // --- the .git of a workspace a step may write ---

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

    /** The workspace as a repository with one commit, a hooks folder and a config, whatever git's template holds. */
    private Path repository() throws IOException {
        git("init", "--quiet", "--initial-branch=main");
        Files.writeString(traceworks.workspace().resolve("tracked.txt"), "first\n");
        git("add", "tracked.txt");
        git("commit", "--quiet", "-m", "on the host");
        Path dotGit = traceworks.workspace().resolve(".git");
        Files.createDirectories(dotGit.resolve("hooks"));
        return dotGit;
    }

    @Test
    void aStepInAWritableWorkspace_cannotWriteTheHooksOrTheConfigOfItsGit_andCanCommit() throws Exception {
        Path dotGit = repository();
        String config = Files.readString(dotGit.resolve("config"));
        List<String> hooks;
        try (var entries = Files.list(dotGit.resolve("hooks"))) {
            hooks = entries.map(entry -> entry.getFileName().toString()).sorted().toList();
        }

        Result run = traceworks.run("writable-workspace-git");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        for (String refused : List.of("write_hook", "write_config", "set_config", "move_git", "move_hooks")) {
            assertThat(folder.stepStatus(refused)).as(refused + ": " + folder.output(refused)).isEqualTo("FAILED");
        }
        assertThat(folder.output("write_hook")).contains("Read-only file system");
        assertThat(folder.output("write_config")).contains("Read-only file system");
        assertThat(folder.stepStatus("commit")).isEqualTo("COMPLETED");
        assertThat(folder.output("commit")).isEqualTo("from the container");

        assertThat(dotGit).isDirectory();
        assertThat(traceworks.workspace().resolve(".git-moved")).doesNotExist();
        assertThat(dotGit.resolve("hooks/pre-commit")).doesNotExist();
        assertThat(dotGit.resolve("hooks-moved")).doesNotExist();
        try (var entries = Files.list(dotGit.resolve("hooks"))) {
            assertThat(entries.map(entry -> entry.getFileName().toString()).sorted().toList()).isEqualTo(hooks);
        }
        assertThat(dotGit.resolve("config")).as("the config, byte for byte").hasContent(config);
        assertThat(git("log", "-1", "--format=%s")).as("the commit of the step, read on the host")
                .isEqualTo("from the container");
        assertThat(git("status", "--porcelain")).isEmpty();
    }

    /**
     * A mount needs a folder to lie on, and Traceworks writes nothing into the operator's repository: without a
     * hooks folder the step is refused, with the command that makes one, and nothing starts.
     */
    @Test
    void aWritableWorkspaceWhoseGitHasNoHooksFolder_isRefusedWhenTheStepRuns_withTheCommandThatMakesOne()
            throws Exception {
        Path dotGit = repository();
        try (var entries = Files.list(dotGit.resolve("hooks"))) {
            for (Path entry : entries.toList()) {
                Files.delete(entry);
            }
        }
        Files.delete(dotGit.resolve("hooks"));

        Result run = traceworks.run("writable-workspace-git");

        assertThat(run.code()).as(run.describe()).isEqualTo(3);
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.output("write_hook")).contains("mkdir " + dotGit.toRealPath().resolve("hooks"));
        assertThat(folder.output("commit")).contains("mkdir " + dotGit.toRealPath().resolve("hooks"));
        assertThat(dotGit.resolve("hooks")).as("Traceworks made none").doesNotExist();
        assertThat(git("log", "-1", "--format=%s")).isEqualTo("on the host");
    }
}
