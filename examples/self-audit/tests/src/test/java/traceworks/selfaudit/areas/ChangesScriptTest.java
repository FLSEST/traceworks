package traceworks.selfaudit.areas;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code scripts/changes.sh} of the package {@code security-changes} against a real git repository: how the base is
 * found (a commit, a tag, a date, or the workspace commit of the last run), when the script refuses, and what it
 * writes down for the area finder. It needs {@code bash} and {@code git}; without them the tests are skipped.
 */
class ChangesScriptTest {

    private static final Path SCRIPT = Path.of("../security-changes/scripts/changes.sh").toAbsolutePath().normalize();

    @TempDir
    Path temp;

    private Path repo;
    private Path out;
    private String first;
    private String second;
    private String third;

    private record Ran(int code, String stdout, String stderr) {
    }

    private Ran run(Path directory, Map<String, String> more, String... command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        Map<String, String> env = builder.environment();
        // Nothing of the machine's git configuration, and a name for the commits
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_AUTHOR_NAME", "Tester");
        env.put("GIT_AUTHOR_EMAIL", "tester@example.invalid");
        env.put("GIT_COMMITTER_NAME", "Tester");
        env.put("GIT_COMMITTER_EMAIL", "tester@example.invalid");
        env.put("TZ", "UTC");
        env.putAll(more);
        Path stdout = Files.createTempFile(temp, "out", ".txt");
        Path stderr = Files.createTempFile(temp, "err", ".txt");
        builder.redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        try {
            Process process = builder.start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("did not end: " + String.join(" ", command));
            }
            return new Ran(process.exitValue(), Files.readString(stdout), Files.readString(stderr));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private String git(String... args) throws IOException {
        return git(Map.of(), args);
    }

    private String git(Map<String, String> env, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Ran ran = run(repo, env, command.toArray(String[]::new));
        assertThat(ran.code()).as(String.join(" ", args) + "\n" + ran.stderr()).isZero();
        return ran.stdout().strip();
    }

    private String commit(String file, String content, String date) throws IOException {
        Files.createDirectories(repo.resolve(file).getParent());
        Files.writeString(repo.resolve(file), content);
        git("add", "--", file);
        git(Map.of("GIT_AUTHOR_DATE", date, "GIT_COMMITTER_DATE", date), "commit", "--quiet", "-m", "change " + file);
        return git("rev-parse", "HEAD");
    }

    private Ran script(String since, String previous) throws IOException {
        return run(repo, Map.of(), "bash", SCRIPT.toString(), out.toString(), since, previous);
    }

    private String written(String name) throws IOException {
        return Files.readString(out.resolve(name), StandardCharsets.UTF_8);
    }

    @BeforeEach
    void repository() throws IOException {
        boolean tools;
        try {
            tools = run(temp, Map.of(), "git", "--version").code() == 0
                    && run(temp, Map.of(), "bash", "--version").code() == 0;
        } catch (IOException e) {
            tools = false;
        }
        assumeTrue(tools, "bash and git are needed");
        repo = Files.createDirectories(temp.resolve("repo"));
        out = temp.resolve("scratch/changes");
        git("init", "--quiet", "--initial-branch=main");
        first = commit("src/A.java", "one\ntwo\nthree\n", "2026-01-01T12:00:00+0000");
        second = commit("src/A.java", "one\nTWO\nthree\n", "2026-02-01T12:00:00+0000");
        third = commit("src/B.java", "b\n", "2026-03-01T12:00:00+0000");
        git("tag", "v1", first);
    }

    @Test
    void aCommit_aTag_andADate_eachNameTheBase_andTheBaseIsWrittenDownWithTheHead() throws IOException {
        Ran byCommit = script(second, "");
        assertThat(byCommit.code()).as(byCommit.stderr()).isZero();
        assertThat(written("base")).isEqualTo(second + "\n");
        assertThat(written("head")).isEqualTo(third + "\n");
        assertThat(written("from")).isEqualTo("input\n");
        assertThat(written("since")).isEqualTo(second + "\n");
        assertThat(written("dirty")).isEqualTo("false\n");
        assertThat(byCommit.stdout()).contains("FACT changes_base: " + second + "\n", "FACT changes_head: " + third
                + "\n", "FACT changes_base_from: input\n");

        assertThat(script("v1", "").code()).isZero();
        assertThat(written("base")).as("a tag").isEqualTo(first + "\n");
        assertThat(script(second.substring(0, 10), "").code()).isZero();
        assertThat(written("base")).as("a short id").isEqualTo(second + "\n");

        assertThat(script("2026-02-15", "").code()).isZero();
        assertThat(written("base")).as("the last commit before the date").isEqualTo(second + "\n");
        assertThat(script("2026-02-01", "").code()).isZero();
        assertThat(written("base")).as("a date without a time is its first second").isEqualTo(first + "\n");
        assertThat(script("2026-02-01T13:00:00Z", "").code()).isZero();
        assertThat(written("base")).as("a date with a time").isEqualTo(second + "\n");
    }

    @Test
    void withoutTheInput_theBaseIsTheWorkspaceCommitOfTheLastRun_theFirstTheReportNames() throws IOException {
        String previous = "gate_status GREEN\ngate_description of the changes since x\nFACT changes_base: " + first
                + "\nFACT changes_head: " + second + "\nVERIFIED medium other A001 x:1\nFACT changes_head: " + first
                + "\n";

        Ran ran = script("", previous);

        assertThat(ran.code()).as(ran.stderr()).isZero();
        assertThat(written("base")).isEqualTo(second + "\n");
        assertThat(written("from")).isEqualTo("register\n");
        assertThat(written("since")).isEmpty();
        assertThat(written("previous-head")).isEqualTo(second + "\n");

        assertThat(script("v1", previous).code()).isZero();
        assertThat(written("base")).as("the input goes first").isEqualTo(first + "\n");
        assertThat(written("previous-head")).as("and the report's commit is still written down").isEqualTo(second + "\n");
    }

    @Test
    void withoutABase_theScriptRefuses_andSaysWhatToGive() throws IOException {
        Ran none = script("", "(no previous report)");
        assertThat(none.code()).isEqualTo(1);
        assertThat(none.stderr()).contains("security-changes: no base: this audit has no earlier run that names its"
                + " workspace commit, and the run got no input 'since'", "--input since=<commit|tag|YYYY-MM-DD>");
        assertThat(out.resolve("base")).as("nothing for the finder to read").doesNotExist();

        Ran unknown = script("no-such-ref", "");
        assertThat(unknown.code()).isEqualTo(1);
        assertThat(unknown.stderr()).contains("the base 'no-such-ref' names no commit of this checkout");

        Ran option = script("--output=/tmp/x", "");
        assertThat(option.code()).isEqualTo(1);
        assertThat(option.stderr()).contains("starts with '-'");

        Ran early = script("2020-01-01", "");
        assertThat(early.code()).isEqualTo(1);
        assertThat(early.stderr()).contains("no commit of the workspace is older than 2020-01-01");

        Ran lost = script("", "FACT changes_head: " + "c".repeat(40) + "\n");
        assertThat(lost.code()).isEqualTo(1);
        assertThat(lost.stderr()).contains("the last run of this audit was at commit " + "c".repeat(40)
                + ", which this checkout does not have");

        Path plain = Files.createDirectories(temp.resolve("plain"));
        Ran noRepository = run(plain, Map.of("GIT_CEILING_DIRECTORIES", temp.toString()), "bash", SCRIPT.toString(),
                out.toString(), "v1", "");
        assertThat(noRepository.code()).isEqualTo(1);
        assertThat(noRepository.stderr()).contains("the workspace is no git checkout whose history this step can read");
    }

    @Test
    void theDiffRunsFromTheBaseToTheFilesAsTheyAre_soWhatIsInNoCommitCountsAsChanged() throws IOException {
        Files.writeString(repo.resolve("src/A.java"), "one\nTWO\nthree\nfour\n");
        Files.writeString(repo.resolve("src/New.java"), "n\n");

        Ran ran = script(first, "");

        assertThat(ran.code()).as(ran.stderr()).isZero();
        assertThat(written("dirty")).isEqualTo("true\n");
        assertThat(written("untracked")).isEqualTo("src/New.java\0");
        Map<String, List<Changes.Range>> lines = Changes.read(out).lines();
        assertThat(lines).containsOnlyKeys("src/A.java", "src/B.java", "src/New.java");
        assertThat(lines.get("src/A.java")).as("line 2 from the second commit, line 4 from the workspace")
                .containsExactly(new Changes.Range(2, 2), new Changes.Range(4, 4));
        assertThat(lines.get("src/B.java")).containsExactly(new Changes.Range(1, 1));
        assertThat(ran.stdout()).contains("FACT changes_uncommitted: true\n");
    }

    @Test
    void theGitConfigurationOfTheWorkspace_startsNoProgram() throws IOException {
        Path marker = temp.resolve("ran");
        Path program = Files.writeString(temp.resolve("program.sh"), "#!/bin/sh\ntouch '" + marker + "'\n");
        assertThat(program.toFile().setExecutable(true)).isTrue();
        git("config", "diff.external", program.toString());
        git("config", "core.fsmonitor", program.toString());
        git("config", "core.pager", program.toString());
        Files.writeString(repo.resolve("src/A.java"), "changed\n");

        Ran ran = script(first, "");

        assertThat(ran.code()).as(ran.stderr()).isZero();
        assertThat(written("diff")).contains("+++ b/src/A.java");
        assertThat(marker).doesNotExist();
    }
}
