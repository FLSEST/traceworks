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

/**
 * {@code scripts/changes.sh} of the package {@code security-changes} against a real git repository, with the area
 * tool deciding what changed from what the script hands over: how the base is
 * found (a commit, a tag, a date, or the workspace commit of the last run), when the script refuses, and what it
 * writes down for the area finder; and what a workspace that is not trusted can neither hide from it nor make it
 * start. It needs {@code bash} and {@code git}, as the script tests of this build do: without them the tests fail.
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

    private Ran script(String since, String previous, String... skip) throws IOException {
        List<String> command = new ArrayList<>(List.of("bash", SCRIPT.toString(), "base", out.toString(), since,
                previous));
        command.addAll(List.of(skip));
        return run(repo, Map.of(), command.toArray(String[]::new));
    }

    private Changes.Compared compared;

    /**
     * What the area tool makes of the folder, the way the steps of the package do it: it compares the workspace with
     * the trees, asks for the base blobs it wants by their ids, gets them from the script, and compares the lines of
     * the Java files that differ, which stand in for the files a finder read.
     */
    private Changes.Base read() throws IOException {
        Changes.Handed handed = Changes.read(out);
        compared = Changes.compare(handed, repo);
        List<String> wanted = Changes.wanted(compared);
        Files.writeString(out.resolve("wanted"), wanted.isEmpty() ? "" : String.join("\n", wanted) + "\n");
        Ran blobs = run(repo, Map.of(), "bash", SCRIPT.toString(), "blobs", out.toString());
        assertThat(blobs.code()).as(blobs.stderr()).isZero();
        List<String> javaFiles = compared.status().entrySet().stream()
                .filter(e -> e.getKey().endsWith(".java") && Files.isRegularFile(repo.resolve(e.getKey()),
                        java.nio.file.LinkOption.NOFOLLOW_LINKS) && !e.getValue().equals("U"))
                .map(Map.Entry::getKey).toList();
        return Changes.of(Changes.read(out), compared, Handover.read(repo, javaFiles.toArray(String[]::new)));
    }

    private String written(String name) throws IOException {
        return Files.readString(out.resolve(name), StandardCharsets.UTF_8);
    }

    @BeforeEach
    void repository() throws IOException {
        repo = Files.createDirectories(temp.resolve("repo"));
        out = temp.resolve("scratch/changes");
        git("init", "--quiet", "--initial-branch=main");
        first = commit("src/A.java", "one\ntwo\nthree\n", "2026-01-01T12:00:00+0000");
        second = commit("src/A.java", "one\nTWO\nthree\n", "2026-02-01T12:00:00+0000");
        third = commit("src/B.java", "b\n", "2026-03-01T12:00:00+0000");
        git("tag", "v1", first);
    }

    @Test
    void aCommit_aTag_andADate_eachNameTheBase_andTheBaseIsWrittenDownWithTheHeadAndTheTrees() throws IOException {
        Ran byCommit = script(second, "");
        assertThat(byCommit.code()).as(byCommit.stderr()).isZero();
        assertThat(written("base-commit")).isEqualTo(second + "\n");
        assertThat(written("head")).isEqualTo(third + "\n");
        assertThat(written("from")).isEqualTo("input\n");
        assertThat(written("since")).isEqualTo(second + "\n");
        assertThat(written("base-date")).isEqualTo("2026-02-01T12:00:00+00:00\n");
        assertThat(Changes.read(out).baseTree()).containsOnlyKeys("src/A.java");
        assertThat(Changes.read(out).headTree()).containsOnlyKeys("src/A.java", "src/B.java");
        assertThat(byCommit.stdout()).contains("FACT changes_base: " + second + "\n", "FACT changes_head: " + third
                + "\n", "FACT changes_base_from: input\n", "FACT changes_base_date: 2026-02-01T12:00:00+00:00\n");

        assertThat(script("v1", "").code()).isZero();
        assertThat(written("base-commit")).as("a tag").isEqualTo(first + "\n");
        assertThat(script(second.substring(0, 10), "").code()).isZero();
        assertThat(written("base-commit")).as("a short id").isEqualTo(second + "\n");

        assertThat(script("2026-02-15", "").code()).isZero();
        assertThat(written("base-commit")).as("the last commit before the date").isEqualTo(second + "\n");
        assertThat(script("2026-02-01", "").code()).isZero();
        assertThat(written("base-commit")).as("a date without a time is its first second").isEqualTo(first + "\n");
        assertThat(script("2026-02-01T13:00:00Z", "").code()).isZero();
        assertThat(written("base-commit")).as("a date with a time").isEqualTo(second + "\n");
    }

    @Test
    void withoutTheInput_theBaseIsTheWorkspaceCommitOfTheLastRun_theFirstTheReportNames() throws IOException {
        String previous = "gate_status GREEN\ngate_description of the changes since x\nFACT changes_base: " + first
                + "\nFACT changes_head: " + second + "\nVERIFIED medium other A001 x:1\nFACT changes_head: " + first
                + "\n";

        Ran ran = script("", previous);

        assertThat(ran.code()).as(ran.stderr()).isZero();
        assertThat(written("base-commit")).isEqualTo(second + "\n");
        assertThat(written("from")).isEqualTo("register\n");
        assertThat(written("since")).isEmpty();
        assertThat(written("previous-head")).isEqualTo(second + "\n");
        assertThat(written("skipped")).isEqualTo("0\n");

        assertThat(script("v1", previous).code()).isZero();
        assertThat(written("base-commit")).as("the input goes first").isEqualTo(first + "\n");
        assertThat(written("previous-head")).as("and the report's commit is still written down").isEqualTo(second + "\n");
        assertThat(written("skipped")).as("an older base skips nothing").isEqualTo("0\n");

        // Nothing happened since the last run: the base may be the workspace commit then
        Ran again = script("", "FACT changes_head: " + third + "\n");
        assertThat(again.code()).as(again.stderr()).isZero();
        assertThat(read().files()).isZero();
        assertThat(compared.dirty()).as("a workspace that is its commit").isFalse();
    }

    @Test
    void withoutABase_theScriptRefuses_andSaysWhatToGive() throws IOException {
        Ran none = script("", "(no previous report)");
        assertThat(none.code()).isEqualTo(1);
        assertThat(none.stderr()).contains("security-changes: no base: this audit has no earlier run that names its"
                + " workspace commit, and the run got no input 'since'", "--input since=<commit|tag|YYYY-MM-DD>");
        assertThat(out.resolve("base-commit")).as("nothing for the finder to read").doesNotExist();

        Ran unknown = script("no-such-ref", "");
        assertThat(unknown.code()).isEqualTo(1);
        assertThat(unknown.stderr()).contains("the base 'no-such-ref' names no commit of this checkout");

        Ran option = script("--output=/tmp/x", "");
        assertThat(option.code()).isEqualTo(1);
        assertThat(option.stderr()).contains("starts with '-'");

        Ran early = script("2020-01-01", "");
        assertThat(early.code()).isEqualTo(1);
        assertThat(early.stderr()).contains("no commit of the workspace is older than 2020-01-01");

        git("checkout", "--quiet", "-b", "side", first);
        String side = commit("src/Side.java", "s\n", "2026-01-15T12:00:00+0000");
        git("checkout", "--quiet", "main");
        Ran otherLine = script(side, "");
        assertThat(otherLine.code()).isEqualTo(1);
        assertThat(otherLine.stderr()).contains("the base " + side + " is no ancestor of the workspace commit " + third);
        Ran rewritten = script("", "FACT changes_head: " + side + "\n");
        assertThat(rewritten.code()).isEqualTo(1);
        assertThat(rewritten.stderr()).contains("is no ancestor of the workspace commit", "the history was rewritten");

        Ran lost = script("", "FACT changes_head: " + "c".repeat(40) + "\n");
        assertThat(lost.code()).isEqualTo(1);
        assertThat(lost.stderr()).contains("the last run of this audit was at commit " + "c".repeat(40)
                + ", which this checkout does not have");

        Path plain = Files.createDirectories(temp.resolve("plain"));
        Ran noRepository = run(plain, Map.of("GIT_CEILING_DIRECTORIES", temp.toString()), "bash", SCRIPT.toString(),
                "base", out.toString(), "v1", "");
        assertThat(noRepository.code()).isEqualTo(1);
        assertThat(noRepository.stderr()).contains("the workspace is no git checkout whose history this step can read");
    }

    @Test
    void whatDiffersIsDecidedByContent_soWhatIsInNoCommitCountsAsChanged_andARemovedFileIsNamed() throws IOException {
        Files.writeString(repo.resolve("src/A.java"), "one\nTWO\nthree\nfour\n");
        Files.writeString(repo.resolve("src/New.java"), "n\n");
        Files.writeString(repo.resolve("notes.txt"), "no Java file, and in no commit: not listed\n");
        Files.delete(repo.resolve("src/B.java"));

        assertThat(script(first, "").code()).isZero();
        Changes.Base base = read();

        assertThat(compared.dirty()).isTrue();
        assertThat(base.status()).containsOnly(Map.entry("src/A.java", "M"), Map.entry("src/New.java", "A"));
        assertThat(Files.readAllLines(out.resolve("wanted"))).as("one base blob was asked for, by its id")
                .containsExactly(git("rev-parse", first + ":src/A.java"));
        assertThat(base.lines()).as("line 2 from the second commit, line 4 from the workspace")
                .containsOnly(Map.entry("src/A.java", List.of(new Changes.Range(2, 2), new Changes.Range(4, 4))),
                        Map.entry("src/New.java", List.of(new Changes.Range(1, Integer.MAX_VALUE))));

        // Against the third commit, which has B.java: it is gone from the workspace
        assertThat(script(third, "").code()).isZero();
        assertThat(read().removed()).containsExactly("src/B.java");
    }

    @Test
    void aCommittedFileOfAnyKind_isCounted_newChangedOrRemoved_andACleanWorkspaceIsNotDirty() throws IOException {
        commit("run.sh", "echo 1\n", "2026-04-01T12:00:00+0000");
        commit("pom.xml", "<project/>\n", "2026-04-02T12:00:00+0000");
        String fifth = commit("META-INF/services/p.Port", "p.One\n", "2026-04-03T12:00:00+0000");
        commit("run.sh", "echo 2\n", "2026-04-04T12:00:00+0000");
        commit("Dockerfile", "FROM x\n", "2026-04-05T12:00:00+0000");
        git("rm", "--quiet", "pom.xml");
        git("commit", "--quiet", "-m", "no pom");

        assertThat(script(fifth, "").code()).isZero();
        Changes.Base base = read();

        assertThat(base.status()).containsOnly(Map.entry("run.sh", "M"), Map.entry("Dockerfile", "A"),
                Map.entry("pom.xml", "D"));
        assertThat(compared.dirty()).isFalse();
        assertThat(Files.readString(out.resolve("wanted"))).as("no bytes are asked for a file that is no Java file")
                .isEmpty();
        assertThat(Changes.kinds(base, Handover.read(repo), repo).cannotJudge()).containsExactly("Dockerfile", "pom.xml", "run.sh");
    }

    // --- what a workspace that is not trusted must neither hide nor start ---

    /** A program the git configuration or the attributes of the workspace could name: it leaves a mark when it runs. */
    private Path program(String mark) throws IOException {
        Path program = Files.writeString(temp.resolve(mark + ".sh"), "#!/bin/sh\ntouch '" + temp.resolve(mark)
                + "'\ncat\n");
        assertThat(program.toFile().setExecutable(true)).isTrue();
        return program;
    }

    @Test
    void theGitConfigurationAndTheAttributesOfTheWorkspace_startNoProgram() throws IOException {
        for (String key : List.of("diff.external", "core.fsmonitor", "core.pager", "filter.evil.clean",
                "filter.evil.smudge", "filter.evil.process", "diff.evil.textconv", "diff.evil.command",
                "core.sshCommand", "core.alternateRefsCommand", "core.editor", "credential.helper",
                "uploadpack.packObjectsHook")) {
            git("config", key, program("ran").toString());
        }
        Files.createDirectories(repo.resolve(".git/hooks"));
        for (String hook : List.of("post-index-change", "reference-transaction", "post-checkout", "pre-auto-gc")) {
            Files.copy(program("ran"), repo.resolve(".git/hooks").resolve(hook),
                    java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
        }
        Files.writeString(repo.resolve(".gitattributes"), "*.java filter=evil diff=evil\n");
        Files.writeString(repo.resolve("src/A.java"), "changed\n");
        Files.writeString(repo.resolve("src/New.java"), "new\n");

        Ran ran = script(first, "");

        assertThat(ran.code()).as(ran.stderr()).isZero();
        assertThat(read().lines()).containsKeys("src/A.java", "src/B.java", "src/New.java");
        assertThat(temp.resolve("ran")).doesNotExist();
    }

    @Test
    void aFileGitWouldCallBinary_isStillChanged_withItsLines() throws IOException {
        Files.writeString(repo.resolve(".gitattributes"), "*.java -diff\n* binary\n");
        git("config", "core.bigFileThreshold", "1");
        Files.writeString(repo.resolve("src/A.java"), "one\nTWO\nthree\nfour\n");

        assertThat(script(second, "").code()).isZero();

        assertThat(read().lines()).containsEntry("src/A.java", List.of(new Changes.Range(4, 4)));
    }

    @Test
    void aFileTheIndexHides_andAnIgnoredFile_areStillChanged() throws IOException {
        git("update-index", "--skip-worktree", "src/A.java");
        git("update-index", "--assume-unchanged", "src/B.java");
        Files.writeString(repo.resolve("src/A.java"), "one\nTWO\nthree\nfour\n");
        Files.writeString(repo.resolve("src/B.java"), "b\nb2\n");
        Files.writeString(repo.resolve(".git/info/exclude"), "Ignored.java\n");
        Files.writeString(repo.resolve("src/Ignored.java"), "class Ignored { }\n");

        assertThat(script(third, "").code()).isZero();

        assertThat(read().lines()).containsOnlyKeys("src/A.java", "src/B.java", "src/Ignored.java");
        assertThat(compared.dirty()).isTrue();
    }

    @Test
    void aFileMovedBelowADirectoryCalledBuild_andEdited_isANewFileWithItsLines_notOnlyARemoval() throws IOException {
        Files.createDirectories(repo.resolve("src/main/java/build"));
        git("mv", "src/A.java", "src/main/java/build/A.java");
        Files.writeString(repo.resolve("src/main/java/build/A.java"), "one\nTWO\nthree\nedited\n");
        Files.createDirectories(repo.resolve("src/main/java/.hidden/target/node_modules"));
        Files.writeString(repo.resolve("src/main/java/.hidden/target/node_modules/Deep.java"), "d\n");

        assertThat(script(third, "").code()).isZero();
        Changes.Base base = read();

        assertThat(base.status()).containsOnly(Map.entry("src/A.java", "D"), Map.entry("src/main/java/build/A.java", "A"),
                Map.entry("src/main/java/.hidden/target/node_modules/Deep.java", "A"));
        assertThat(base.lines()).containsKeys("src/main/java/build/A.java", "src/main/java/.hidden/target/node_modules/Deep.java");
    }

    @Test
    void aPathGitWouldReadAsQuoted_orWithALineBreak_isThePathItIs_andADecoyDoesNotStandInForIt() throws IOException {
        commit("\"A.java\"", "old\n", "2026-04-01T12:00:00+0000");
        commit("src/odd\nname.java", "x\n", "2026-04-02T12:00:00+0000");
        String fifth = commit("src/Link.java", "l\n", "2026-04-03T12:00:00+0000");
        // The file whose name is in quotes changes; a file of the name without the quotes holds the old bytes
        Files.writeString(repo.resolve("\"A.java\""), "new\n");
        Files.writeString(repo.resolve("A.java"), "old\n");
        Files.writeString(repo.resolve("src/odd\nname.java"), "y\n");
        Files.delete(repo.resolve("src/Link.java"));
        Files.createSymbolicLink(repo.resolve("src/Link.java"), Path.of("A.java"));

        Ran ran = script(fifth, "");
        assertThat(ran.code()).as(ran.stderr()).isZero();
        Changes.Base base = read();

        assertThat(base.status()).containsOnly(Map.entry("\"A.java\"", "M"), Map.entry("A.java", "A"),
                Map.entry("src/odd\nname.java", "M"), Map.entry("src/Link.java", "M"));
        assertThat(base.lines().get("src/odd\nname.java")).containsExactly(new Changes.Range(1, 1));
        assertThat(Changes.kinds(base, Handover.read(repo), repo).uncompiled()).as("a link where a file was is a"
                + " changed file that nothing compiles").contains("src/Link.java");
    }

    @Test
    void theScriptHandsOverBlobsByIdOnly_andRefusesAListThatHoldsAnythingElse() throws IOException {
        assertThat(script(first, "").code()).isZero();
        Files.writeString(out.resolve("wanted"), git("rev-parse", first + ":src/A.java") + "\n" + "c".repeat(40) + "\n");
        Ran blobs = run(repo, Map.of(), "bash", SCRIPT.toString(), "blobs", out.toString());
        assertThat(blobs.code()).as(blobs.stderr()).isZero();
        assertThat(Changes.read(out).blobs()).as("a blob git does not have is missing, and nothing else")
                .containsOnlyKeys(git("rev-parse", first + ":src/A.java"));

        for (String line : List.of("HEAD:src/A.java", "--batch-all-objects", "\"src/A.java\"", "")) {
            Files.writeString(out.resolve("wanted"), line + "\n");
            Ran refused = run(repo, Map.of(), "bash", SCRIPT.toString(), "blobs", out.toString());
            assertThat(refused.code()).as(line).isEqualTo(1);
            assertThat(refused.stderr()).contains("the list of blobs holds a line that is no blob id");
        }
    }

    @Test
    void aReplacedObject_aPartialClone_andASubmodule_doNotGetPast() throws IOException {
        // refs/replace would show the tree of another commit in place of the base's
        git("replace", second, first);
        assertThat(script(second, "").code()).isZero();
        assertThat(read().status()).as("the base is the commit named, not what a replace ref puts in its place")
                .containsOnlyKeys("src/B.java");
        git("replace", "-d", second);

        git("config", "remote.origin.promisor", "true");
        Ran partial = script(first, "");
        assertThat(partial.code()).isEqualTo(1);
        assertThat(partial.stderr()).contains("the workspace is a partial clone");
        git("config", "--unset", "remote.origin.promisor");

        git("update-index", "--add", "--cacheinfo", "160000," + first + ",sub");
        git(Map.of("GIT_AUTHOR_DATE", "2026-05-01T12:00:00+0000", "GIT_COMMITTER_DATE", "2026-05-01T12:00:00+0000"),
                "commit", "--quiet", "-m", "a submodule");
        assertThat(script(first, "").code()).isZero();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Changes.read(out))
                .hasMessageContaining("the workspace has a submodule (sub)");
    }

    // --- a base that would make the run say more than it looked at ---

    @Test
    void aDateThatResolvesToTheWorkspaceCommit_isWrittenDownAsThat_andTheToolRefusesItWhenNothingDiffers()
            throws IOException {
        assertThat(script("2027-01-01", "").code()).isZero();
        assertThat(written("base-commit")).isEqualTo(third + "\n");
        assertThat(written("base-date")).isEqualTo("2026-03-01T12:00:00+00:00\n");

        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        int code = Main.run(new String[] {"changed", "--changes", out.toString(), "--root", repo.toString()},
                java.io.InputStream.nullInputStream(), new java.io.PrintStream(new java.io.ByteArrayOutputStream()),
                new java.io.PrintStream(err, true, StandardCharsets.UTF_8));

        assertThat(code).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("nothing to check: the base '2027-01-01' is the"
                + " workspace commit itself (" + third + ", committed 2026-03-01T12:00:00+00:00)");

        // With a change in no commit there is something to check
        Files.writeString(repo.resolve("src/B.java"), "b\nb2\n");
        assertThat(Main.run(new String[] {"changed", "--changes", out.toString(), "--root", repo.toString()},
                java.io.InputStream.nullInputStream(), new java.io.PrintStream(new java.io.ByteArrayOutputStream()),
                new java.io.PrintStream(err, true, StandardCharsets.UTF_8))).isZero();
    }

    @Test
    void aBackdatedCommit_isNoBaseForADate_unlessBothItsDatesAreOlder() throws IOException {
        // Written long ago, committed now: git's --before would not take it either
        Files.writeString(repo.resolve("src/B.java"), "b\nlate\n");
        git("add", "-A");
        git(Map.of("GIT_AUTHOR_DATE", "2020-01-01T12:00:00+0000", "GIT_COMMITTER_DATE", "2026-06-01T12:00:00+0000"),
                "commit", "--quiet", "-m", "written early");
        // Committed long ago by its own word, written now: git's --before would take it, as the tip
        Files.writeString(repo.resolve("src/B.java"), "b\nlate\nlater\n");
        git("add", "-A");
        git(Map.of("GIT_AUTHOR_DATE", "2026-06-02T12:00:00+0000", "GIT_COMMITTER_DATE", "2020-01-01T12:00:00+0000"),
                "commit", "--quiet", "-m", "committed early");

        Ran ran = script("2026-03-15", "");

        assertThat(ran.code()).as(ran.stderr()).isZero();
        assertThat(written("base-commit")).as("neither of the two: the last commit before the date in both dates")
                .isEqualTo(third + "\n");
        assertThat(read().lines()).containsEntry("src/B.java", List.of(new Changes.Range(2, 3)));
    }

    @Test
    void aBaseLaterThanTheLastRun_isRefusedUnlessTheOperatorSkipsKnowingly_andTheCountIsWrittenDown()
            throws IOException {
        String previous = "FACT changes_head: " + first + "\n";

        Ran refused = script(third, previous);
        assertThat(refused.code()).isEqualTo(1);
        assertThat(refused.stderr()).contains("the base " + third + " lies 2 commits after the commit of the last run"
                + " of this audit (" + first + ")", "--input skip=yes");
        assertThat(out.resolve("base-commit")).doesNotExist();

        Ran skipped = script(third, previous, "yes");
        assertThat(skipped.code()).as(skipped.stderr()).isZero();
        assertThat(written("skipped")).isEqualTo("2\n");
        assertThat(skipped.stdout()).contains("FACT changes_commits_skipped: 2\n");
        assertThat(Changes.read(out).skipped()).isEqualTo("2");

        Ran unknown = script(second, "FACT changes_head: " + "c".repeat(40) + "\n");
        assertThat(unknown.code()).isEqualTo(1);
        assertThat(unknown.stderr()).contains("nothing can tell whether the base", "--input skip=yes");
        assertThat(script(second, "FACT changes_head: " + "c".repeat(40) + "\n", "yes").code()).isZero();
        assertThat(written("skipped")).isEqualTo("unknown\n");
    }

    @Test
    void aRepositoryWithLongIds_namesItsBaseLikeAnyOther_andTheToolComputesItsIds() throws IOException {
        repo = Files.createDirectories(temp.resolve("sha256"));
        git("init", "--quiet", "--initial-branch=main", "--object-format=sha256");
        String one = commit("src/A.java", "one\n", "2026-01-01T12:00:00+0000");
        String two = commit("src/A.java", "one\ntwo\n", "2026-02-01T12:00:00+0000");
        commit("src/Same.java", "same\n", "2026-02-02T12:00:00+0000");
        assertThat(one).hasSize(64);

        Ran ran = script("", "FACT changes_head: " + two + "\n");
        assertThat(ran.code()).as(ran.stderr()).isZero();
        assertThat(read().status()).containsOnly(Map.entry("src/Same.java", "A"));
        assertThat(compared.dirty()).isFalse();

        assertThat(script(one, "", "yes").code()).isZero();
        assertThat(read().lines()).containsEntry("src/A.java", List.of(new Changes.Range(2, 2)));
    }

    @Test
    void theScriptCountsTheCommitsOfTheRange_andResolvesTheFullAuditThatWasNamed() throws IOException {
        Ran ran = run(repo, Map.of(), "bash", SCRIPT.toString(), "base", out.toString(), first, "", "", "v1");
        assertThat(ran.code()).as(ran.stderr()).isZero();
        assertThat(written("commits")).isEqualTo("2\n");
        assertThat(written("full-audit")).isEqualTo(first + "\n");
        assertThat(written("full-audit-behind")).isEqualTo("2\n");

        assertThat(script(second, "").code()).isZero();
        assertThat(written("commits")).isEqualTo("1\n");
        assertThat(written("full-audit")).as("none was given").isBlank();

        Ran unknown = run(repo, Map.of(), "bash", SCRIPT.toString(), "base", out.toString(), first, "", "", "nope");
        assertThat(unknown.code()).isEqualTo(1);
        assertThat(unknown.stderr()).contains("the full audit 'nope' names no commit of this checkout");
    }
}
