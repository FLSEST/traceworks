package traceworks.adapters.pkg;

import traceworks.core.port.LoadedPackage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilePackageLoaderTest {

    /** A {@code modelChoices} that is no object is refused with words of Traceworks that name the key. */
    @org.junit.jupiter.api.Test
    void aModelChoicesThatIsNoObject_isRefused_namingTheKey(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws java.io.IOException {
        for (String wrong : java.util.List.of("[]", "\"judge\"", "5", "true")) {
            java.nio.file.Files.writeString(dir.resolve("workflow.json"), "{\"id\": \"wf\", \"name\": \"n\","
                    + " \"blocks\": [], \"modelChoices\": " + wrong + "}");

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new FilePackageLoader().load(dir)).as(wrong)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Cannot read " + dir.resolve("workflow.json") + ": modelChoices must be an object"
                            + " that maps the name of each model choice to its default and options"
                            + " (invalid-model-choice; docs/cli.md, \"Model choices\")");
        }
        // What a key this build does not know gives: the parser's words, as an older build says of modelChoices
        java.nio.file.Files.writeString(dir.resolve("workflow.json"), "{\"id\": \"wf\", \"blocks\": [], \"nope\": {}}");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new FilePackageLoader().load(dir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Cannot read " + dir.resolve("workflow.json") + ": Unrecognized field \"nope\"");
    }

    private static final String WORKFLOW = "{\"id\":\"wf-1\",\"name\":\"fixture\",\"blocks\":[]}\n";

    @TempDir
    Path dir;

    private final FilePackageLoader loader = new FilePackageLoader();

    private static void git(Path dir, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-c", "core.fsmonitor=false",
                "-c", "user.name=t", "-c", "user.email=t@example.org", "-c", "commit.gpgsign=false", "-C", dir.toString()));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null"))).start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(String.join(" ", args)).isZero();
    }

    private Path pkg() throws IOException {
        Files.writeString(dir.resolve("workflow.json"), WORKFLOW);
        return dir;
    }

    @Test
    void load_directoryOrWorkflowJson() throws IOException {
        pkg();

        LoadedPackage byDirectory = loader.load(dir);
        LoadedPackage byFile = loader.load(dir.resolve("workflow.json"));

        assertThat(byDirectory.definition().id()).isEqualTo("wf-1");
        assertThat(byDirectory.directory()).isEqualTo(dir.toAbsolutePath().normalize());
        assertThat(byDirectory.workflowJson()).isEqualTo(WORKFLOW.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(byDirectory.digest()).isEqualTo(PackageDigest.of(dir)).hasSize(64);
        assertThat(byFile.directory()).isEqualTo(byDirectory.directory());
        assertThat(byFile.digest()).isEqualTo(byDirectory.digest());
    }

    @Test
    void catalogJson_isReadWhenPresent_elseNull() throws IOException {
        Path pkg = Files.createDirectories(dir.resolve("audited"));
        Files.writeString(pkg.resolve("workflow.json"), "{\"id\":\"a\",\"name\":\"A\",\"blocks\":[]}");

        assertThat(loader.load(pkg).catalogJson()).isNull();

        Files.writeString(pkg.resolve("catalog.json"), "{\"format\":1,\"rules\":{}}");
        assertThat(new String(loader.load(pkg).catalogJson(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("{\"format\":1,\"rules\":{}}");
    }

    @Test
    void missingWorkflowJson_isRefused() {
        assertThatThrownBy(() -> loader.load(dir)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workflow.json").hasMessageContaining(dir.toString());
    }

    @Test
    void aSymlinkedWorkflowJson_isRefusedBeforeItIsRead(@TempDir Path other) throws IOException {
        // The target is not JSON: reading it first would quote its first token in the parse error
        Path target = Files.writeString(other.resolve("credentials"), "aws_access_key_id = AKIAEXAMPLE\n");
        Files.createSymbolicLink(dir.resolve("workflow.json"), target);

        assertThatThrownBy(() -> loader.load(dir)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbolic link").hasMessageContaining("workflow.json")
                .hasMessageNotContaining("aws_access_key_id");
    }

    @Test
    void unknownTopLevelKey_isRefused_andNamed() throws IOException {
        Files.writeString(dir.resolve("workflow.json"), "{\"name\":\"x\",\"blocks\":[],\"surprise\":1}");

        assertThatThrownBy(() -> loader.load(dir)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("surprise");
    }

    @Test
    void gitInfo_cleanAndDirty() throws Exception {
        pkg();
        git(dir, "init", "-q");
        git(dir, "add", ".");
        git(dir, "commit", "-q", "-m", "init");

        LoadedPackage clean = loader.load(dir);
        Files.writeString(dir.resolve("workflow.json"), WORKFLOW + " ");
        LoadedPackage dirty = loader.load(dir);

        assertThat(clean.gitCommit()).hasSize(40);
        assertThat(clean.gitDirty()).isFalse();
        assertThat(dirty.gitCommit()).isEqualTo(clean.gitCommit());
        assertThat(dirty.gitDirty()).isTrue();
    }

    @Test
    void outsideGit_hasNoGitInfo() throws IOException {
        pkg();

        LoadedPackage loaded = loader.load(dir);

        assertThat(loaded.gitCommit()).isNull();
        assertThat(loaded.gitDirty()).isNull();
    }

    @Test
    void repositoryWithoutCommits_hasNoGitInfo() throws Exception {
        pkg();
        git(dir, "init", "-q");

        LoadedPackage loaded = loader.load(dir);

        assertThat(loaded.gitCommit()).isNull();
        assertThat(loaded.gitDirty()).isNull();
    }

    @Test
    void gitIsCalledWithoutFsmonitor() throws Exception {
        pkg();
        git(dir, "init", "-q");
        git(dir, "add", ".");
        git(dir, "commit", "-q", "-m", "init");
        Path marker = dir.resolve("..").resolve(dir.getFileName() + ".marker").normalize();
        Path hook = dir.resolve(".git/fsmonitor-hook.sh");
        Files.writeString(hook, "#!/bin/sh\ntouch '" + marker + "'\n");
        hook.toFile().setExecutable(true);
        Files.writeString(dir.resolve(".git/config"), Files.readString(dir.resolve(".git/config"))
                + "[core]\n\tfsmonitor = " + hook + "\n");

        loader.load(dir);

        assertThat(marker).doesNotExist();
    }

    /**
     * A package's own {@code .git/config} names filter drivers that {@code .gitattributes} applies; {@code git status}
     * would run them to compare a file whose stat data changed. The marker a driver would touch lies in {@code .git/},
     * inside the test's directory, where neither the digest nor {@code git status} sees it.
     */
    static Path withFilterDrivers(Path dir) throws Exception {
        Files.writeString(dir.resolve("workflow.json"), WORKFLOW);
        Files.writeString(dir.resolve("notes.txt"), "notes\n");
        git(dir, "init", "-q");
        git(dir, "add", ".");
        git(dir, "commit", "-q", "-m", "init");
        Path marker = dir.resolve(".git/filter-ran");
        Files.writeString(dir.resolve(".gitattributes"), "*.json filter=evil\n*.txt filter=Dotted.Name\n");
        Files.writeString(dir.resolve(".git/config"), Files.readString(dir.resolve(".git/config"))
                + "[filter \"evil\"]\n\tclean = touch '" + marker + "'; cat\n\tsmudge = cat\n\trequired = true\n"
                + "[filter \"Dotted.Name\"]\n\tprocess = touch '" + marker + "'\n\trequired = true\n");
        java.nio.file.attribute.FileTime later = java.nio.file.attribute.FileTime.from(
                java.time.Instant.now().plusSeconds(60));
        Files.setLastModifiedTime(dir.resolve("workflow.json"), later);
        Files.setLastModifiedTime(dir.resolve("notes.txt"), later);
        return marker;
    }

    @Test
    void filterDriversOfThePackage_doNotRun_andTheDirtyFlagIsStillRead() throws Exception {
        Path marker = withFilterDrivers(dir);

        LoadedPackage loaded = loader.load(dir);

        assertThat(marker).doesNotExist();
        assertThat(loaded.gitCommit()).isNotNull();
        assertThat(loaded.gitDirty()).as("git status ran").isNotNull();
    }

    /**
     * {@code git status} refreshes the index when a file's stat data changed (a package unpacked from an archive, a
     * touched file), and writing the index runs the {@code post-index-change} hook, from {@code .git/hooks} or from
     * the directory a repository's {@code core.hooksPath} names.
     */
    @Test
    void hooksOfThePackage_doNotRun_andTheDirtyFlagIsStillRead() throws Exception {
        pkg();
        git(dir, "init", "-q");
        git(dir, "add", ".");
        git(dir, "commit", "-q", "-m", "init");
        Path marker = dir.resolve(".git/hook-ran");
        String hook = "#!/bin/sh\ntouch '" + marker + "'\n";
        Path standard = dir.resolve(".git/hooks/post-index-change");
        Files.createDirectories(standard.getParent());
        Files.writeString(standard, hook);
        standard.toFile().setExecutable(true);
        Path configured = Files.createDirectories(dir.resolve(".git/other-hooks")).resolve("post-index-change");
        Files.writeString(configured, hook);
        configured.toFile().setExecutable(true);

        for (String hooksPath : new String[] {null, configured.getParent().toString()}) {
            if (hooksPath != null) git(dir, "config", "core.hooksPath", hooksPath);
            Files.setLastModifiedTime(dir.resolve("workflow.json"),
                    java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 3_600_000L));

            LoadedPackage loaded = loader.load(dir);

            assertThat(marker).as("hooksPath " + hooksPath).doesNotExist();
            assertThat(loaded.gitDirty()).isFalse();
        }
    }

    @Test
    void hungGit_isKilledAtTheTimeout_andTheGitInfoIsUnknown() throws IOException {
        pkg();
        Path fakeGit = dir.resolveSibling(dir.getFileName() + "-fake-git.sh");
        Files.writeString(fakeGit, "#!/bin/sh\nexec sleep 30\n");
        fakeGit.toFile().setExecutable(true);
        long start = System.nanoTime();

        LoadedPackage loaded = new FilePackageLoader(fakeGit.toString(), 300).load(dir);

        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(10));
        assertThat(loaded.gitCommit()).isNull();
        assertThat(loaded.gitDirty()).isNull();
        Files.delete(fakeGit);
    }

    /**
     * A package in the runs' folders ({@code <home>/runs/}) is refused before git runs: a step writes its run's
     * scratch directory, a workflow can name it as a child workflow, and {@code git status} on the host would read a
     * {@code .git/config} the step put there.
     */
    @Test
    void load_ofAPackageInTheRunFolders_isRefusedBeforeGitRuns(@TempDir Path other) throws IOException {
        Path home = other.resolve("home");
        Path runs = Files.createDirectories(home.resolve("runs"));
        Path inside = Files.createDirectories(runs.resolve("2026-11-12/run-0/scratch/pkg"));
        Files.writeString(inside.resolve("workflow.json"), WORKFLOW);
        Path link = Files.createSymbolicLink(other.resolve("linked-pkg"), inside);
        Path marker = other.resolve("git-ran");
        Path fakeGit = other.resolve("marking-git.sh");
        Files.writeString(fakeGit, "#!/bin/sh\ntouch '" + marker + "'\nexit 1\n");
        fakeGit.toFile().setExecutable(true);
        FilePackageLoader guarded = new FilePackageLoader(home.resolve("workspaces"), runs, fakeGit.toString(),
                10_000);

        for (Path location : List.of(inside, inside.resolve("workflow.json"), link)) {
            assertThatThrownBy(() -> guarded.load(location)).as(location.toString())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(runs.toString()).hasMessageContaining("scratch");
        }
        assertThat(marker).doesNotExist();
        pkg();
        assertThat(guarded.load(dir).definition().id()).as("a package elsewhere loads").isEqualTo("wf-1");
        assertThat(marker).as("and its git state is read").exists();
    }

    /**
     * A package in the runs' checkouts ({@code <home>/workspaces/}) is refused before git runs: a step may have written
     * the checkout's {@code .git/config}, and {@code git status} on the host would read it. Also through a link.
     */
    @Test
    void load_ofAPackageInTheCheckouts_isRefusedBeforeGitRuns(@TempDir Path other) throws IOException {
        Path workspaces = Files.createDirectories(other.resolve("home").resolve("workspaces"));
        Path inside = Files.createDirectories(workspaces.resolve("run-0").resolve("pkg"));
        Files.writeString(inside.resolve("workflow.json"), WORKFLOW);
        Path link = Files.createSymbolicLink(other.resolve("linked-pkg"), inside);
        Path marker = other.resolve("git-ran");
        Path fakeGit = other.resolve("marking-git.sh");
        Files.writeString(fakeGit, "#!/bin/sh\ntouch '" + marker + "'\nexit 1\n");
        fakeGit.toFile().setExecutable(true);
        FilePackageLoader guarded = new FilePackageLoader(workspaces, fakeGit.toString(), 10_000);

        for (Path location : List.of(inside, inside.resolve("workflow.json"), link)) {
            assertThatThrownBy(() -> guarded.load(location)).as(location.toString())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(workspaces.toString()).hasMessageContaining("checkout");
        }
        assertThat(marker).doesNotExist();
        pkg();
        assertThat(guarded.load(dir).definition().id()).as("a package elsewhere loads").isEqualTo("wf-1");
        assertThat(marker).as("and its git state is read").exists();
    }

    @Test
    void packagesIn_listsSortedDirectoriesWithWorkflowJson_andIdOfReadsOnlyTheId() throws IOException {
        for (String name : List.of("b", "a")) {
            Files.createDirectories(dir.resolve(name));
            Files.writeString(dir.resolve(name).resolve("workflow.json"), "{\"id\":\"id-" + name + "\"}");
        }
        Files.createDirectories(dir.resolve("empty"));
        Files.writeString(dir.resolve("file.txt"), "x");

        assertThat(loader.packagesIn(dir)).containsExactly(dir.resolve("a"), dir.resolve("b"));
        assertThat(loader.idOf(dir.resolve("a"))).contains("id-a");
        assertThat(loader.idOf(dir.resolve("empty"))).isEmpty();
    }

    /** As {@code load} refuses it: the package path search does not read what a linked {@code workflow.json} names. */
    @Test
    void idOf_aSymlinkedWorkflowJson_hasNoId(@TempDir Path other) throws IOException {
        Path target = Files.writeString(other.resolve("elsewhere.json"), "{\"id\":\"id-elsewhere\"}");
        Files.createDirectories(dir.resolve("linked"));
        Files.createSymbolicLink(dir.resolve("linked").resolve("workflow.json"), target);

        assertThat(loader.idOf(dir.resolve("linked"))).isEmpty();
    }

    @Test
    void images_areTheDirectoriesWithADockerfile_withTheirDigestAndText() throws IOException {
        pkg();
        Files.createDirectories(dir.resolve("images/tools"));
        Files.writeString(dir.resolve("images/tools/Dockerfile"), "FROM scratch\n");
        Files.writeString(dir.resolve("images/tools/run.sh"), "echo\n");
        Files.createDirectories(dir.resolve("images/empty"));
        Files.createDirectories(dir.resolve("images/Bad_Name"));
        Files.writeString(dir.resolve("images/Bad_Name/Dockerfile"), "FROM scratch\n");
        Files.writeString(dir.resolve("images/stray.txt"), "x");

        LoadedPackage loaded = loader.load(dir);

        assertThat(loaded.images()).containsOnlyKeys("tools");
        var tools = loaded.images().get("tools");
        assertThat(tools.name()).isEqualTo("tools");
        assertThat(tools.directory()).isEqualTo(dir.resolve("images/tools").toAbsolutePath().normalize().toString());
        assertThat(tools.digest()).isEqualTo(PackageDigest.of(dir.resolve("images/tools"))).hasSize(64);
        assertThat(tools.dockerfile()).isEqualTo("FROM scratch\n");
        assertThat(loaded.invalidImageDirectories()).as("a Dockerfile under a name no ./images/ reference can name")
                .containsExactly("Bad_Name");
    }

    @Test
    void images_changeWhenAFileInTheImageDirectoryChanges() throws IOException {
        pkg();
        Files.createDirectories(dir.resolve("images/tools"));
        Files.writeString(dir.resolve("images/tools/Dockerfile"), "FROM scratch\n");
        String before = loader.load(dir).images().get("tools").digest();

        Files.writeString(dir.resolve("images/tools/Dockerfile"), "FROM scratch\nRUN true\n");

        assertThat(loader.load(dir).images().get("tools").digest()).isNotEqualTo(before);
    }

    @Test
    void aPackageWithoutImages_hasNone() throws IOException {
        assertThat(loader.load(pkg()).images()).isEmpty();
    }


    /** A package is untrusted and its workflow.json is read into memory whole: a huge one is refused before that. */
    @Test
    void aWorkflowJsonBeyondTheSizeLimit_isRefusedBeforeItIsRead() throws IOException {
        Files.writeString(dir.resolve("workflow.json"),
                "{\"id\":\"wf-1\",\"name\":\"" + "x".repeat(FilePackageLoader.MAX_JSON_BYTES) + "\",\"blocks\":[]}\n");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> loader.load(dir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workflow.json").hasMessageContaining("larger than the limit of 8 MiB");
        assertThat(loader.idOf(dir)).isEmpty();
    }

    @Test
    void aCatalogJsonBeyondTheSizeLimit_isRefused() throws IOException {
        pkg();
        Files.writeString(dir.resolve("catalog.json"), " ".repeat(FilePackageLoader.MAX_JSON_BYTES + 1));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> loader.load(dir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("catalog.json").hasMessageContaining("larger than the limit of 8 MiB");
    }

    /**
     * A package may ship its own {@code .git}. Its config can name a promisor remote whose transport is a program
     * ({@code remote.<name>.uploadpack}); when an object is missing, {@code git status} would fetch it lazily and so
     * run that program on the host. Reading a package's commit must start nothing the package names.
     */
    @Test
    void aPromisorRemoteOfThePackage_isNeverFetchedFrom_soItsTransportProgramDoesNotRun(@TempDir Path other)
            throws Exception {
        pkg();
        git(dir, "init", "-q");
        git(dir, "add", ".");
        git(dir, "commit", "-q", "-m", "init");
        Path marker = other.resolve("transport-ran");
        Process tree = new ProcessBuilder("git", "-C", dir.toString(), "rev-parse", "HEAD^{tree}").start();
        String sha = new String(tree.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        assertThat(tree.waitFor(30, TimeUnit.SECONDS)).isTrue();
        git(dir, "config", "extensions.partialClone", "origin");
        git(dir, "config", "remote.origin.url", Files.createDirectories(other.resolve("remote")).toString());
        git(dir, "config", "remote.origin.promisor", "true");
        git(dir, "config", "remote.origin.uploadpack", "touch '" + marker + "'; false");
        Files.delete(dir.resolve(".git/objects").resolve(sha.substring(0, 2)).resolve(sha.substring(2)));

        loader.load(dir);

        assertThat(marker).doesNotExist();
    }

    /**
     * {@code protocol.allow=never} is only git's default policy: a {@code protocol.<name>.allow} in the package's own
     * config beats it. The refusal must be one the repository cannot answer back.
     */
    @Test
    void aPromisorRemoteOfThePackage_isNotFetchedFrom_evenWhenItsConfigAllowsTheProtocol(@TempDir Path other)
            throws Exception {
        pkg();
        git(dir, "init", "-q");
        git(dir, "add", ".");
        git(dir, "commit", "-q", "-m", "init");
        Path marker = other.resolve("transport-ran");
        Process tree = new ProcessBuilder("git", "-C", dir.toString(), "rev-parse", "HEAD^{tree}").start();
        String sha = new String(tree.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        assertThat(tree.waitFor(30, TimeUnit.SECONDS)).isTrue();
        git(dir, "config", "extensions.partialClone", "origin");
        git(dir, "config", "remote.origin.url", Files.createDirectories(other.resolve("remote")).toString());
        git(dir, "config", "remote.origin.promisor", "true");
        git(dir, "config", "remote.origin.uploadpack", "touch '" + marker + "'; false");
        git(dir, "config", "protocol.file.allow", "always");
        Files.delete(dir.resolve(".git/objects").resolve(sha.substring(0, 2)).resolve(sha.substring(2)));

        loader.load(dir);

        assertThat(marker).doesNotExist();
    }

    /** A Dockerfile of a package is read into memory on every load, also by check and describe. */
    @Test
    void aDockerfileBeyondTheSizeLimit_isRefused() throws IOException {
        pkg();
        Path image = Files.createDirectories(dir.resolve("images/tool"));
        Files.writeString(image.resolve("Dockerfile"), "FROM scratch\n" + "#".repeat(1024 * 1024));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> loader.load(dir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Dockerfile").hasMessageContaining("larger than the limit of 1 MiB");
    }

    @Test
    void theSealFile_isReadWhenPresent_elseNull_andDoesNotChangeTheDigest() throws IOException {
        pkg();
        LoadedPackage unsealed = loader.load(dir);

        Files.writeString(dir.resolve("traceworks.seal"), "{\"version\": 1}\n");
        LoadedPackage sealed = loader.load(dir);

        assertThat(unsealed.sealJson()).isNull();
        assertThat(new String(sealed.sealJson(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("{\"version\": 1}\n");
        assertThat(sealed.digest()).isEqualTo(unsealed.digest());
    }

    @Test
    void aSealFileBeyondTheSizeLimit_isRefused_notTakenForNoSeal() throws IOException {
        pkg();
        Files.writeString(dir.resolve("traceworks.seal"), " ".repeat(64 * 1024 + 1));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> loader.load(dir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traceworks.seal").hasMessageContaining("larger than the limit of 64 KiB");
    }

    @Test
    void anEmptySealFile_isHandedOnAsItIs() throws IOException {
        pkg();
        Files.writeString(dir.resolve("traceworks.seal"), "");

        assertThat(loader.load(dir).sealJson()).isEmpty();
    }
}
