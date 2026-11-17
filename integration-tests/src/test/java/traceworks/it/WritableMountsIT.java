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
 * mounts" and "A writable workspace"; packages {@code writable-mounts}, {@code mounts},
 * {@code writable-workspace-git} and {@code writable-workspace-commit}):
 *
 * <ul>
 *   <li>a writable mount ({@code :rw}) is refused unless the operator's {@code writable_mounts} in
 *       {@code traceworks.json} lists a folder that holds it: before the first step for a mount the workflow writes
 *       out, when its step runs for one a placeholder names;</li>
 *   <li>in a listed folder a step writes it;</li>
 *   <li>a step with {@code workspace_writable: true} writes the files of the workspace and reads its repository,
 *       and writes nothing under the workspace's {@code .git}: no hook, no config, no {@code commondir}, no commit,
 *       and it cannot move the {@code .git} away;</li>
 *   <li>with the operator's {@code workspace_git_writable} it commits.</li>
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

    /** The workspace as a repository with one commit. */
    private Path repository() throws IOException {
        git("init", "--quiet", "--initial-branch=main");
        Files.writeString(traceworks.workspace().resolve("tracked.txt"), "first\n");
        git("add", "tracked.txt");
        git("commit", "--quiet", "-m", "on the host");
        return traceworks.workspace().resolve(".git");
    }

    /** Every file below {@code folder} with its bytes as text, by its path in the folder. */
    private static Map<String, String> files(Path folder) throws IOException {
        Map<String, String> found = new java.util.TreeMap<>();
        try (var paths = Files.walk(folder)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                found.put(folder.relativize(file).toString(),
                        new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1));
            }
        }
        return found;
    }

    /**
     * By default the {@code .git} of a writable workspace is read-only for the step, as a whole: it writes a file
     * of the workspace and reads the repository, and can leave no hook, no config, no {@code commondir} and no
     * commit, nor move the {@code .git} away. The repository has no hooks folder here: nothing is asked of what a
     * {@code .git} holds, and Traceworks makes nothing in it.
     */
    @Test
    void aStepInAWritableWorkspace_writesItsFiles_andNothingUnderItsGit() throws Exception {
        Path dotGit = repository();
        Path hooks = dotGit.resolve("hooks");
        if (Files.isDirectory(hooks)) {
            try (var entries = Files.list(hooks)) {
                for (Path entry : entries.toList()) {
                    Files.delete(entry);
                }
            }
            Files.delete(hooks);
        }
        Map<String, String> before = files(dotGit);

        Result run = traceworks.run("writable-workspace-git");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.stepStatus("write_file")).as(folder.output("write_file")).isEqualTo("COMPLETED");
        assertThat(folder.output("write_file")).isEqualTo("changed");
        for (String refused : List.of("write_hook", "write_config", "write_commondir", "move_git", "commit")) {
            assertThat(folder.stepStatus(refused)).as(refused + ": " + folder.output(refused)).isEqualTo("FAILED");
        }
        for (String readOnly : List.of("write_hook", "write_config", "write_commondir", "commit")) {
            assertThat(folder.output(readOnly)).as(readOnly).contains("Read-only file system");
        }
        assertThat(folder.stepStatus("read_git")).as(folder.output("read_git")).isEqualTo("COMPLETED");
        assertThat(folder.output("read_git")).as("the step reads the repository").isEqualTo("on the host");

        assertThat(traceworks.workspace().resolve("tracked.txt")).hasContent("changed\n");
        assertThat(dotGit).isDirectory();
        assertThat(traceworks.workspace().resolve(".git-moved")).doesNotExist();
        assertThat(hooks).as("neither the step nor Traceworks made one").doesNotExist();
        assertThat(dotGit.resolve("commondir")).doesNotExist();
        assertThat(files(dotGit)).as("the repository, file for file and byte for byte").isEqualTo(before);
        assertThat(git("log", "-1", "--format=%s")).isEqualTo("on the host");
        assertThat(git("status", "--porcelain")).as("the change of the step, not committed")
                .isEqualTo("M tracked.txt");
    }

    /** Without the operator's word the step that commits fails, and the repository keeps its commit. */
    @Test
    void aStepInAWritableWorkspace_cannotCommit_withoutTheOperatorsSetting() throws Exception {
        repository();

        Result run = traceworks.run("writable-workspace-commit");

        assertThat(run.code()).as(run.describe()).isEqualTo(3);
        assertThat(traceworks.folder(run).output("commit")).contains("Read-only file system");
        assertThat(git("log", "-1", "--format=%s")).isEqualTo("on the host");
    }

    /**
     * {@code workspace_git_writable} in {@code traceworks.json} is the operator's trust in the packages it runs
     * there: the {@code .git} is writable like the rest of the workspace, and the step commits.
     */
    @Test
    void aStepInAWritableWorkspace_commits_whereTheOperatorOpenedItsGit() throws Exception {
        repository();
        traceworks.configure(Map.of("workspace_git_writable", true));

        Result run = traceworks.run("writable-workspace-commit");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.stepStatus("commit")).isEqualTo("COMPLETED");
        assertThat(folder.output("commit")).isEqualTo("from the container");
        assertThat(git("log", "-1", "--format=%s")).as("the commit of the step, read on the host")
                .isEqualTo("from the container");
        assertThat(git("status", "--porcelain")).isEmpty();

        Result described = traceworks.exec("describe", traceworks.packageDir("writable-workspace-commit").toString());
        assertThat(described.code()).as(described.describe()).isZero();
        assertThat(described.out()).contains("workspace writable, with its .git (workspace_git_writable)");
    }
}
