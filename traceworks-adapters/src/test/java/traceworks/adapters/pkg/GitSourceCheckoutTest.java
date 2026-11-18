package traceworks.adapters.pkg;

import traceworks.core.port.SourceCheckout.Checkout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The checkout of a source, on repositories the test makes. The operator's git configuration is one the test writes
 * ({@code HOME} points at a folder of the test), so a filter driver, hooks and submodule settings there are the
 * test's.
 */
class GitSourceCheckoutTest {

    private static final long TIMEOUT = 60_000L;

    @TempDir
    Path temp;
    /** The operator's home: its {@code .gitconfig} is the operator's git configuration. */
    private Path operatorHome;
    private Path workspaces;
    private GitSourceCheckout checkouts;

    @BeforeEach
    void setUp() throws IOException {
        operatorHome = Files.createDirectories(temp.resolve("operator"));
        Files.writeString(operatorHome.resolve(".gitconfig"), "");
        workspaces = temp.resolve("home/workspaces");
        checkouts = new GitSourceCheckout(workspaces, "git", TIMEOUT, operatorEnvironment());
    }

    private Map<String, String> operatorEnvironment() {
        return Map.of("HOME", operatorHome.toString(), "XDG_CONFIG_HOME", operatorHome.resolve(".config").toString());
    }

    // ---- making repositories ----

    /** git for setting a repository up, with a configuration of its own: nothing of the machine's applies. */
    private String git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString(), "-c", "user.name=t",
                "-c", "user.email=t@t.invalid", "-c", "commit.gpgsign=false", "-c", "init.defaultBranch=main",
                "-c", "protocol.file.allow=always"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().keySet().removeIf(name -> name.startsWith("GIT_"));
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("git " + String.join(" ", args) + ": " + output).isZero();
        return output.strip();
    }

    /** A repository with {@code a.txt} on {@code main}, tagged {@code v1}, and {@code b.txt} on {@code feature}. */
    private Path source() throws Exception {
        Path repo = Files.createDirectories(temp.resolve("source"));
        git(repo, "init", "-q");
        Files.writeString(repo.resolve("a.txt"), "a\n");
        git(repo, "add", "a.txt");
        git(repo, "commit", "-q", "-m", "a");
        git(repo, "tag", "v1");
        git(repo, "checkout", "-q", "-b", "feature");
        Files.writeString(repo.resolve("b.txt"), "b\n");
        git(repo, "add", "b.txt");
        git(repo, "commit", "-q", "-m", "b");
        git(repo, "checkout", "-q", "main");
        return repo;
    }

    private String sha(Path repo, String rev) throws Exception {
        return git(repo, "rev-parse", rev);
    }

    private Path target() {
        return workspaces.resolve("run-1");
    }

    /** A script that stands for git: it leaves {@code marker} when it runs, then fails. */
    private Path fakeGit(Path marker) throws IOException {
        Path script = temp.resolve("fake-git");
        Files.writeString(script, "#!/bin/sh\ntouch '" + marker + "'\nexit 1\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }

    /** A script that stands for git and prints {@code bytes} bytes on {@code stream} (1 or 2), then waits. */
    private Path chattyGit(int stream, int bytes) throws IOException {
        Path script = temp.resolve("chatty-git");
        Files.writeString(script, "#!/bin/sh\nhead -c " + bytes + " /dev/zero | tr '\\0' 'x' >&" + stream
                + "\nsleep 30\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }

    /**
     * git's output is read up to the output limit: a hostile remote streams {@code remote:} messages on stderr, and a
     * call that goes beyond the limit is stopped and fails the checkout, naming the limit, with nothing left behind.
     */
    @Test
    void gitOutput_beyondTheLimit_failsTheCheckoutAndStopsGit() throws Exception {
        String source = source().toString();
        for (int stream : new int[] {1, 2}) {
            GitSourceCheckout chatty = new GitSourceCheckout(workspaces, chattyGit(stream, 200_000).toString(),
                    TIMEOUT, Map.of(), 1024);
            long start = System.nanoTime();

            assertThatThrownBy(() -> chatty.checkout(source, null, target()))
                    .as("stream " + stream).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("output went beyond the limit of 1 KiB");
            assertThat((System.nanoTime() - start) / 1_000_000).as("git is stopped, not waited for").isLessThan(20_000);
            assertThat(target()).doesNotExist();
        }
    }

    @Test
    void gitOutput_withinTheLimit_leavesACheckoutAsItWas() throws Exception {
        Path source = source();
        GitSourceCheckout small = new GitSourceCheckout(workspaces, "git", TIMEOUT, operatorEnvironment(), 4096);

        Checkout checkout = small.checkout(source.toString(), "feature", target());

        assertThat(checkout.sha()).isEqualTo(git(source, "rev-parse", "feature"));
        assertThat(target().resolve("b.txt")).hasContent("b\n");
    }

    /** One call of the recording git: its arguments after the executable, and its environment. */
    private record Call(List<String> args, Map<String, String> env) {

        /** The git command of the call: the first of the four a checkout runs that the arguments hold. */
        String command() {
            for (String name : List.of("clone", "config", "rev-parse", "checkout")) {
                if (args.contains(name)) return name;
            }
            return "?";
        }
    }

    /** A git that writes down its arguments and environment for each call, then runs the real git. */
    private Path recordingGit(Path calls) throws IOException {
        Files.createDirectories(calls);
        Path script = temp.resolve("recording-git");
        Files.writeString(script, """
                #!/bin/sh
                n=$(ls '%1$s' | grep -c args)
                n=$((n + 1))
                printf '%%s\\0' "$@" > '%1$s'/"$n.args"
                env -0 > '%1$s'/"$n.env"
                exec git "$@"
                """.formatted(calls));
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }

    private static List<Call> calls(Path calls) throws IOException {
        List<Call> all = new ArrayList<>();
        for (int n = 1; Files.exists(calls.resolve(n + ".args")); n++) {
            List<String> args = List.of(new String(Files.readAllBytes(calls.resolve(n + ".args")),
                    StandardCharsets.UTF_8).split("\0", -1));
            Map<String, String> env = new java.util.TreeMap<>();
            for (String entry : new String(Files.readAllBytes(calls.resolve(n + ".env")), StandardCharsets.UTF_8)
                    .split("\0")) {
                int eq = entry.indexOf('=');
                if (eq > 0) env.put(entry.substring(0, eq), entry.substring(eq + 1));
            }
            all.add(new Call(args.subList(0, args.size() - 1), env));
        }
        return all;
    }

    /** Every throwable of the chain: the exception, its causes and what they suppressed. */
    private static List<Throwable> chain(Throwable top) {
        List<Throwable> all = new ArrayList<>();
        java.util.Deque<Throwable> todo = new java.util.ArrayDeque<>(List.of(top));
        while (!todo.isEmpty()) {
            Throwable t = todo.pop();
            if (all.contains(t)) continue;
            all.add(t);
            if (t.getCause() != null) todo.push(t.getCause());
            for (Throwable suppressed : t.getSuppressed()) todo.push(suppressed);
        }
        return all;
    }

    private static List<Path> files(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    // ---- checkout ----

    @Test
    void checkout_ofALocalRepository_atARef_isDetachedAtItsSha() throws Exception {
        Path repo = source();

        Checkout checkout = checkouts.checkout(repo.toString(), "feature", target());

        String feature = sha(repo, "feature");
        assertThat(checkout.sha()).isEqualTo(feature).hasSize(40);
        assertThat(checkout.source()).isEqualTo(repo.toString());
        assertThat(sha(target(), "HEAD")).isEqualTo(feature);
        assertThat(git(target(), "status", "--porcelain=v2", "--branch")).as("detached, on no branch")
                .contains("# branch.head (detached)");
        assertThat(target().resolve("b.txt")).hasContent("b");
    }

    @Test
    void checkout_withoutARef_isTheSourcesHead() throws Exception {
        Path repo = source();

        Checkout checkout = checkouts.checkout(repo.toString(), null, target());

        assertThat(checkout.sha()).isEqualTo(sha(repo, "main"));
        assertThat(target().resolve("a.txt")).exists();
        assertThat(target().resolve("b.txt")).doesNotExist();
    }

    @Test
    void checkout_atATag_atAShortSha_andThroughAFileUrl() throws Exception {
        Path repo = source();
        String main = sha(repo, "main");

        assertThat(checkouts.checkout(repo.toString(), "v1", workspaces.resolve("tag")).sha()).isEqualTo(main);
        assertThat(checkouts.checkout(repo.toString(), sha(repo, "feature").substring(0, 10),
                workspaces.resolve("short")).sha()).isEqualTo(sha(repo, "feature"));
        Checkout url = checkouts.checkout("file://" + repo, "feature", workspaces.resolve("url"));
        assertThat(url.sha()).isEqualTo(sha(repo, "feature"));
        assertThat(url.source()).isEqualTo("file://" + repo);
    }

    /**
     * Nothing of the source runs, and nothing of the operator's configuration that would run a program for it: the
     * source's own {@code post-checkout} hook, the operator's hooks and init template, a filter driver the operator
     * defines for an attribute the source names (required, so a skipped one would fail), an fsmonitor, and a
     * submodule that the operator's {@code submodule.recurse} would clone.
     */
    @Test
    void checkout_runsNoHookFilterOrSubmoduleOfTheSource() throws Exception {
        Path marker = temp.resolve("markers");
        Files.createDirectories(marker);
        Path program = temp.resolve("leave-marker");
        Files.writeString(program, "#!/bin/sh\ntouch '" + marker + "'/\"$(basename \"$0\")-$$\"\ncat\n");
        Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwx------"));
        Path hooks = Files.createDirectories(temp.resolve("operator-hooks"));
        Path template = Files.createDirectories(temp.resolve("operator-template/hooks"));
        for (Path dir : List.of(hooks, template)) {
            Files.writeString(dir.resolve("post-checkout"), "#!/bin/sh\ntouch '" + marker + "/hook'\n");
            Files.setPosixFilePermissions(dir.resolve("post-checkout"), PosixFilePermissions.fromString("rwx------"));
        }
        Files.writeString(operatorHome.resolve(".gitconfig"), """
                [filter "evil"]
                \tsmudge = %1$s
                \tclean = %1$s
                \trequired = true
                [filter "lfs"]
                \tsmudge = %1$s
                \tprocess = %1$s
                \trequired = true
                [core]
                \thooksPath = %2$s
                \tfsmonitor = %1$s
                [init]
                \ttemplateDir = %3$s
                [submodule]
                \trecurse = true
                """.formatted(program, hooks, template.getParent()));

        Path module = Files.createDirectories(temp.resolve("module"));
        git(module, "init", "-q");
        Files.writeString(module.resolve("m.txt"), "m\n");
        git(module, "add", "m.txt");
        git(module, "commit", "-q", "-m", "m");
        Path repo = Files.createDirectories(temp.resolve("source"));
        git(repo, "init", "-q");
        Files.writeString(repo.resolve(".gitattributes"), "*.txt filter=evil ident\n*.bin filter=lfs\n");
        Files.writeString(repo.resolve("a.txt"), "id $Id$\n");
        Files.writeString(repo.resolve("big.bin"), "version https://git-lfs.github.com/spec/v1\n");
        git(repo, "add", ".gitattributes", "a.txt", "big.bin");
        git(repo, "submodule", "add", "-q", module.toString(), "sub");
        git(repo, "commit", "-q", "-m", "all");
        Path ownHook = repo.resolve(".git/hooks/post-checkout");
        Files.writeString(ownHook, "#!/bin/sh\ntouch '" + marker + "/own-hook'\n");
        Files.setPosixFilePermissions(ownHook, PosixFilePermissions.fromString("rwx------"));

        Checkout checkout = checkouts.checkout(repo.toString(), null, target());

        assertThat(checkout.sha()).isEqualTo(sha(repo, "HEAD"));
        try (Stream<Path> left = Files.list(marker)) {
            assertThat(left.toList()).as("nothing ran").isEmpty();
        }
        assertThat(target().resolve("a.txt")).as("no filter, no ident").hasContent("id $Id$");
        assertThat(target().resolve("big.bin")).as("an LFS file stays its pointer")
                .hasContent("version https://git-lfs.github.com/spec/v1");
        assertThat(target().resolve("sub")).isEmptyDirectory();
        assertThat(target().resolve(".git/modules")).doesNotExist();
        assertThat(target().resolve(".git/hooks")).as("no init template").doesNotExist();
    }

    /** What the checkout holds is the commit's bytes: no line endings converted, whatever the attributes say. */
    @Test
    void checkout_keepsTheCommitsBytes() throws Exception {
        Files.writeString(operatorHome.resolve(".gitconfig"), "[core]\n\tautocrlf = true\n\teol = crlf\n");
        Path repo = Files.createDirectories(temp.resolve("source"));
        git(repo, "init", "-q");
        Files.writeString(repo.resolve(".gitattributes"), "*.bat eol=crlf\n*.txt text\n");
        Files.writeString(repo.resolve("run.bat"), "echo a\necho b\n");
        Files.writeString(repo.resolve("a.txt"), "one\ntwo\n");
        git(repo, "add", ".");
        git(repo, "commit", "-q", "-m", "lf");

        checkouts.checkout(repo.toString(), null, target());

        assertThat(Files.readString(target().resolve("run.bat"))).isEqualTo("echo a\necho b\n");
        assertThat(Files.readString(target().resolve("a.txt"))).isEqualTo("one\ntwo\n");
    }

    @Test
    void checkout_ofAnUnknownRef_isRefused_andLeavesNothing() throws Exception {
        Path repo = source();

        assertThatThrownBy(() -> checkouts.checkout(repo.toString(), "nope", target()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope").hasMessageContaining(repo.toString());
        assertThat(target()).doesNotExist();
    }

    /**
     * A ref never becomes an option of git: one that starts with '-' is a ref that names no commit, and every
     * {@code rev-parse} gets it after {@code --end-of-options}.
     */
    @Test
    void checkout_aRefThatStartsWithADash_isNoOption() throws Exception {
        Path repo = source();
        Path marker = temp.resolve("upload-pack-ran");
        Path recorded = temp.resolve("calls");
        GitSourceCheckout recording = new GitSourceCheckout(workspaces, recordingGit(recorded).toString(), TIMEOUT,
                operatorEnvironment());

        List<String> refs = List.of("--upload-pack=touch " + marker, "-q", "--output=" + marker);
        for (String ref : refs) {
            assertThatThrownBy(() -> recording.checkout(repo.toString(), ref, target()))
                    .as(ref).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("names no commit");
            assertThat(target()).doesNotExist();
        }
        assertThat(marker).doesNotExist();
        List<Call> resolves = calls(recorded).stream().filter(call -> call.command().equals("rev-parse")).toList();
        assertThat(resolves).hasSize(2 * refs.size());
        for (Call call : resolves) {
            assertThat(call.args().get(call.args().size() - 2)).as(call.args().toString())
                    .isEqualTo("--end-of-options");
        }
    }

    /**
     * Every git call of a checkout carries the options of the package git calls (no optional locks, no fsmonitor, no
     * hooks), no file or ext transport a submodule could use and no submodule recursion, runs without one inherited
     * {@code GIT_*} variable (a {@code GIT_DIR} or a {@code GIT_CONFIG_*} pair would redirect it), and speaks English.
     */
    @Test
    void everyGitCall_carriesTheSafeOptions_andNoInheritedGitVariable() throws Exception {
        Path repo = source();
        Path hooks = Files.createDirectories(temp.resolve("inherited-hooks"));
        Files.writeString(hooks.resolve("post-checkout"), "#!/bin/sh\ntouch '" + temp.resolve("hook-ran") + "'\n");
        Files.setPosixFilePermissions(hooks.resolve("post-checkout"), PosixFilePermissions.fromString("rwx------"));
        Map<String, String> parent = new java.util.HashMap<>(operatorEnvironment());
        parent.putAll(Map.of("GIT_DIR", temp.resolve("elsewhere/.git").toString(), "GIT_CONFIG_COUNT", "1",
                "GIT_CONFIG_KEY_0", "core.hooksPath", "GIT_CONFIG_VALUE_0", hooks.toString(), "LC_ALL", "de_CH.UTF-8"));
        // What is not git's business stays with the operator: a key of an AI provider, a token of another tool
        parent.putAll(Map.of("ANTHROPIC_API_KEY", "sk-not-for-git", "SOME_TOOL_TOKEN", "t0ken",
                "SSH_AUTH_SOCK", "/run/agent.sock", "HTTPS_PROXY", "http://proxy.example:3128"));
        Path recorded = temp.resolve("calls");
        GitSourceCheckout recording = new GitSourceCheckout(workspaces, recordingGit(recorded).toString(), TIMEOUT,
                parent);
        String url = "file://user:s3cr3t-T0KEN@localhost" + repo;

        Checkout checkout = recording.checkout(url, "feature", target());

        List<Call> calls = calls(recorded);
        assertThat(calls).extracting(Call::command).containsExactly("clone", "config", "rev-parse", "rev-parse",
                "checkout");
        List<String> options = List.of("--no-optional-locks", "-c", "core.fsmonitor=false", "-c",
                "core.hooksPath=/dev/null", "-c", "protocol.file.allow=user", "-c", "protocol.ext.allow=never", "-c",
                "submodule.recurse=false");
        for (Call call : calls) {
            assertThat(call.args().subList(0, options.size())).as(call.args().toString()).isEqualTo(options);
            assertThat(call.env()).as("no inherited GIT_ variable").containsEntry("GIT_TERMINAL_PROMPT", "0")
                    .containsEntry("LC_ALL", "C");
            assertThat(call.env().keySet()).filteredOn(name -> name.startsWith("GIT_"))
                    .containsExactlyInAnyOrder("GIT_TERMINAL_PROMPT", "GIT_NO_LAZY_FETCH");
            assertThat(call.env()).containsEntry("GIT_NO_LAZY_FETCH", "1");
            assertThat(call.env()).as("only what git needs of the operator's environment")
                    .doesNotContainKeys("ANTHROPIC_API_KEY", "SOME_TOOL_TOKEN")
                    .containsEntry("SSH_AUTH_SOCK", "/run/agent.sock")
                    .containsEntry("HTTPS_PROXY", "http://proxy.example:3128")
                    .containsEntry("HOME", operatorHome.toString()).containsKey("PATH");
            assertThat(call.env().keySet()).as("nothing of the JVM's own environment beyond the allowlist")
                    .allMatch(name -> GitProcess.inherited(name) || name.equals("GIT_TERMINAL_PROMPT") || name.equals("GIT_NO_LAZY_FETCH")
                            || name.equals("PWD") || name.equals("SHLVL") || name.equals("_"));
        }
        Call clone = calls.get(0);
        assertThat(clone.args()).contains("-c", "init.templateDir=", "--no-checkout", "--no-hardlinks",
                "--no-recurse-submodules");
        assertThat(clone.args().subList(clone.args().size() - 3, clone.args().size()))
                .containsExactly("--", url, target().toString());
        assertThat(calls.get(1).args().subList(calls.get(1).args().size() - 4, calls.get(1).args().size()))
                .containsExactly("config", "--end-of-options", "remote.origin.url", "file://localhost" + repo);
        Call last = calls.get(4);
        assertThat(last.args().subList(last.args().size() - 6, last.args().size()))
                .containsExactly("checkout", "--quiet", "--no-recurse-submodules", "--detach", checkout.sha(), "--");
        assertThat(temp.resolve("hook-ran")).doesNotExist();
    }

    @Test
    void checkout_intoATargetThatExists_isRefused() throws Exception {
        Path repo = source();
        Files.createDirectories(target());
        Files.writeString(target().resolve("keep"), "mine");

        assertThatThrownBy(() -> checkouts.checkout(repo.toString(), null, target()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exists");
        assertThat(target().resolve("keep")).hasContent("mine");
    }

    @Test
    void checkout_ofSomethingThatIsNoRepository_failsWithGitsReason_andLeavesNothing() throws Exception {
        Path plain = Files.createDirectories(temp.resolve("plain"));

        assertThatThrownBy(() -> checkouts.checkout(plain.toString(), null, target()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(plain.toString());
        assertThat(target()).doesNotExist();
    }

    // ---- which sources ----

    /**
     * A source that is no local path and no allowed URL is refused before git runs: a remote helper
     * ({@code <helper>::}) or {@code ext::} runs a program, plain {@code http://} and {@code git://} fetch without
     * TLS with the operator's credentials, and a source or host that starts with '-' would be an option.
     */
    @Test
    void source_thatIsNotAllowed_isRefusedBeforeGitRuns() throws Exception {
        Path marker = temp.resolve("git-ran");
        GitSourceCheckout fake = new GitSourceCheckout(workspaces, fakeGit(marker).toString(), TIMEOUT, Map.of());

        for (String source : List.of("ext::sh -c touch% /tmp/x", "fd::3", "remote-x::https://host/repo",
                "http://host/repo", "git://host/repo", "ftp://host/repo", "HTTPS://host/repo", "-x",
                "--upload-pack=touch /tmp/x", "relative/repo", "host:repo", "-oProxyCommand=x@host:repo",
                "user@-oProxyCommand=x:repo", "ssh://-oProxyCommand=x/repo", "ssh://[-oProxyCommand=x]/repo",
                "git@[-oProxyCommand=x]:repo", "https://", "", " ",
                "/tmp/a\nb")) {
            assertThatThrownBy(() -> fake.checkout(source, null, target()))
                    .as(source).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(marker).doesNotExist();
        assertThat(target()).doesNotExist();
    }

    /**
     * A local source in the runs' checkouts is refused before git runs, as a path, as a {@code file://} URL (also
     * percent-encoded, which git decodes) and through a link: a step may have written that checkout's
     * {@code .git/config}, and the {@code git-upload-pack} of a clone reads it without Traceworks' options.
     */
    @Test
    void source_inTheCheckouts_isRefusedBeforeGitRuns() throws Exception {
        Path other = Files.createDirectories(workspaces.resolve("run-0"));
        git(other, "init", "-q");
        Path link = Files.createSymbolicLink(temp.resolve("linked-source"), other);
        Path marker = temp.resolve("git-ran");
        GitSourceCheckout fake = new GitSourceCheckout(workspaces, fakeGit(marker).toString(), TIMEOUT, Map.of());
        String encoded = other.toString().replace("/workspaces/", "/%77orkspaces/");

        for (String source : List.of(other.toString(), other.resolve("sub/..").toString(), link.toString(),
                "file://" + other, "file://localhost" + other, "file://" + encoded, workspaces.toString())) {
            assertThatThrownBy(() -> fake.checkout(source, null, target()))
                    .as(source).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("checkout");
        }
        assertThat(marker).doesNotExist();
        assertThat(target()).doesNotExist();
    }

    /**
     * A local source in the runs' folders is refused before git runs, as a path, as a {@code file://} URL and through
     * a link: every step writes its run's scratch directory there, so a repository in it is a step's work, and the
     * {@code git-upload-pack} of a clone would read its {@code .git/config} without Traceworks' options.
     */
    @Test
    void source_inTheRunsFolders_isRefusedBeforeGitRuns() throws Exception {
        Path runs = temp.resolve("home/runs");
        Path planted = Files.createDirectories(runs.resolve("run-0/scratch/repo"));
        git(planted, "init", "-q");
        Path link = Files.createSymbolicLink(temp.resolve("linked-scratch"), planted);
        Path marker = temp.resolve("git-ran");
        GitSourceCheckout fake = new GitSourceCheckout(workspaces, runs, fakeGit(marker).toString(), TIMEOUT,
                Map.of(), 1 << 20);
        String encoded = planted.toString().replace("/runs/", "/%72uns/");

        for (String source : List.of(planted.toString(), planted.resolve("sub/..").toString(), link.toString(),
                "file://" + planted, "file://" + encoded, runs.resolve("run-0").toString(), runs.toString())) {
            assertThatThrownBy(() -> fake.checkout(source, null, target()))
                    .as(source).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("scratch directories");
        }
        assertThat(marker).doesNotExist();
        assertThat(target()).doesNotExist();
    }

    /**
     * ssh gets the user of an {@code ssh://} URL as an argument of its own when the URL names a port, so a user
     * that starts with a dash would be read as an option of ssh, like {@code -oProxyCommand}, which runs a program.
     */
    @Test
    void anSshUrlWhoseUserStartsWithADash_isRefusedBeforeGitRuns() throws Exception {
        Path marker = temp.resolve("git-ran");
        GitSourceCheckout fake = new GitSourceCheckout(workspaces, fakeGit(marker).toString(), TIMEOUT, Map.of());

        for (String source : List.of("ssh://-oProxyCommand=x@host/repo", "ssh://-oProxyCommand=x@host:22/repo",
                "ssh://-l:pw@host/repo")) {
            assertThatThrownBy(() -> fake.checkout(source, null, target()))
                    .as(source).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("starts with '-'");
        }
        assertThat(marker).doesNotExist();
    }

    /**
     * git decodes every {@code %xx} of a {@code file://} URL, also one that is the very end of it: a checkout named
     * with its last letter encoded is still that checkout, and refused.
     */
    @Test
    void aFileUrlThatEndsInAnEncodedCharacter_isReadAsGitReadsIt() throws Exception {
        Path other = Files.createDirectories(workspaces.resolve("run-0"));
        git(other, "init", "-q");
        Path marker = temp.resolve("git-ran");
        GitSourceCheckout fake = new GitSourceCheckout(workspaces, fakeGit(marker).toString(), TIMEOUT, Map.of());
        String lastEncoded = "file://" + workspaces + "/run-%30";

        assertThat(GitSourceCheckout.Source.parse("file:///srv/rep%6F").localPath()).isEqualTo("/srv/repo");
        assertThat(GitSourceCheckout.Source.parse("file:///srv/repo%2").localPath())
                .as("a % without two hex digits stays").isEqualTo("/srv/repo%2");
        assertThat(GitSourceCheckout.Source.parse("file:///srv/a%z5").localPath())
                .as("nor with a hex digit after one that is none").isEqualTo("/srv/a%z5");
        assertThat(GitSourceCheckout.Source.parse("file:///srv/tab%09name").localPath())
                .as("a first hex digit 0 is a hex digit").isEqualTo("/srv/tab\tname");
        assertThatThrownBy(() -> fake.checkout(lastEncoded, null, target()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("checkout");
        assertThat(marker).doesNotExist();
    }

    /**
     * The check reads the path of a {@code file://} URL up to its {@code ?} or {@code #}, but git opens the whole
     * rest as the path: {@code file:///srv/d?/../../home/workspaces/run-0} would pass as {@code /srv/d} and be cloned
     * from a run's checkout. So such a URL is refused, and the refusal repeats nothing of it.
     */
    @Test
    void aFileUrlWithAQueryOrAFragment_isRefusedBeforeGitRuns() throws Exception {
        Path other = Files.createDirectories(workspaces.resolve("run-0"));
        git(other, "init", "-q");
        Path marker = temp.resolve("git-ran");
        GitSourceCheckout fake = new GitSourceCheckout(workspaces, fakeGit(marker).toString(), TIMEOUT, Map.of());
        String up = "/.." .repeat(temp.getNameCount() + 1);

        for (String source : List.of("file://" + temp + "/d?" + up + other, "file://" + temp + "/d#" + up + other,
                "file://user:s3cr3t-T0KEN@localhost" + temp + "/d?x=s3cr3t-T0KEN")) {
            assertThatThrownBy(() -> fake.checkout(source, null, target()))
                    .as(source).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("file://").hasMessageContaining("'?' or '#'")
                    .message().doesNotContain("s3cr3t-T0KEN", "run-0");
        }
        assertThat(marker).doesNotExist();
        assertThat(target()).doesNotExist();
    }

    /**
     * git percent-decodes a whole {@code file://} URL before it splits the host from the path, and skips a host in
     * brackets up to its {@code ]}: {@code file://%2Fhome%2Fworkspaces%2Frun-0/.} is that checkout for git, and
     * {@code /.} for a check that splits first. Seen with git 2.43. So the part before the path is nothing or
     * {@code localhost}, with or without a user, and holds no {@code %} and no bracket.
     */
    @Test
    void aFileUrlWhoseHostPartGitWouldReadAsAPath_isRefusedBeforeGitRuns() throws Exception {
        Path other = Files.createDirectories(workspaces.resolve("run-0"));
        git(other, "init", "-q");
        Path marker = temp.resolve("git-ran");
        GitSourceCheckout fake = new GitSourceCheckout(workspaces, fakeGit(marker).toString(), TIMEOUT, Map.of());
        String encoded = other.toString().replace("/", "%2F");

        for (String source : List.of("file://" + encoded + "/.", "file://[/x]" + other, "file://otherhost" + other,
                "file://s3cr3t-T0KEN%2F@localhost" + other, "file://[localhost]" + other,
                // also as the very first character of the part before the path
                "file://%2F@localhost" + other, "file://[@localhost" + other, "file://]@localhost" + other)) {
            assertThatThrownBy(() -> fake.checkout(source, null, target()))
                    .as(source).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("file://").hasMessageContaining("localhost")
                    .message().doesNotContain("s3cr3t-T0KEN", "run-0");
        }
        assertThat(marker).doesNotExist();
        assertThat(GitSourceCheckout.Source.parse("file://localhost/srv/repo").localPath()).isEqualTo("/srv/repo");
        assertThat(GitSourceCheckout.Source.parse("file://user:pw@localhost/srv/repo").localPath())
                .isEqualTo("/srv/repo");
    }

    /**
     * git looks for {@code @[} in the whole of a local source, not only before its path, and reads on after the
     * {@code ]}: {@code file:///x@[y]/home/workspaces/run-0} is that checkout for git ({@code git-upload-pack} ran
     * there, seen with git 2.43), and {@code /x@[y]/home/workspaces/run-0} for a check of the path. So a local source
     * with {@code @[} is refused, written out or encoded, as a URL and as a path; a bracket alone is a name.
     */
    @Test
    void aLocalSourceWithAnAtAndABracket_whichGitReadsAsAHostInThePath_isRefusedBeforeGitRuns() throws Exception {
        Path other = Files.createDirectories(workspaces.resolve("run-0"));
        git(other, "init", "-q");
        Path marker = temp.resolve("git-ran");
        GitSourceCheckout fake = new GitSourceCheckout(workspaces, fakeGit(marker).toString(), TIMEOUT, Map.of());

        for (String source : List.of("file:///x@[y]" + other, "file:///x%40%5By%5D" + other,
                "file:///x@%5By]" + other, "file://localhost/x@[y]" + other,
                "file://user:s3cr3t-T0KEN@localhost/x@[y]" + other, "/x@[y]" + other)) {
            assertThatThrownBy(() -> fake.checkout(source, null, target()))
                    .as(source).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'@['")
                    .message().doesNotContain("s3cr3t-T0KEN", "run-0");
        }
        assertThat(marker).doesNotExist();
        assertThat(target()).doesNotExist();
        assertThat(GitSourceCheckout.Source.parse("file:///srv/[a]/b@c/repo").localPath())
                .isEqualTo("/srv/[a]/b@c/repo");
        assertThat(GitSourceCheckout.Source.parse("/srv/[a]/b@c/repo").localPath()).isEqualTo("/srv/[a]/b@c/repo");
    }

    /**
     * The clone writes no reflog: its first line would be {@code clone: from <URL>} with the URL as git anonymises
     * it, which keeps a query and a fragment, in a folder the steps read.
     */
    @Test
    void checkout_leavesNoReflogThatNamesWhereTheCloneCameFrom() throws Exception {
        Path repo = source();

        checkouts.checkout("file://user:s3cr3t-T0KEN@localhost" + repo, "feature", target());

        Path logs = target().resolve(".git/logs");
        if (Files.exists(logs)) {
            try (Stream<Path> files = Files.walk(logs)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    assertThat(Files.readString(file)).as(file.toString()).doesNotContain("clone: from");
                }
            }
        }
    }

    /**
     * A path that cannot be resolved for another reason than that it does not exist (here a link to itself) cannot
     * be told to lie outside the checkouts, so it counts as inside: refused.
     */
    @Test
    void source_thatCannotBeResolved_countsAsInTheCheckouts() throws Exception {
        Files.createDirectories(workspaces);
        Path loop = Files.createSymbolicLink(temp.resolve("loop"), temp.resolve("loop"));
        Path dangling = Files.createSymbolicLink(temp.resolve("dangling"), workspaces.resolve("run-9"));
        Path marker = temp.resolve("git-ran");
        GitSourceCheckout fake = new GitSourceCheckout(workspaces, fakeGit(marker).toString(), TIMEOUT, Map.of());
        CheckoutsDirectory directory = new CheckoutsDirectory(workspaces);

        assertThat(directory.holds(loop)).isTrue();
        assertThat(directory.holds(loop.resolve("repo"))).isTrue();
        assertThat(directory.holds(dangling)).as("a link whose target does not exist yet").isTrue();
        assertThat(directory.holds(temp.resolve("not/there"))).as("a path that only does not exist").isFalse();
        assertThatThrownBy(() -> fake.checkout(loop.toString(), null, target()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be resolved");
        assertThat(marker).doesNotExist();
    }

    @Test
    void source_inAnAllowedForm_isParsed() {
        assertThat(GitSourceCheckout.Source.parse("/srv/repo").recorded()).isEqualTo("/srv/repo");
        assertThat(GitSourceCheckout.Source.parse("/srv/repo").local()).isTrue();
        assertThat(GitSourceCheckout.Source.parse("https://host/org/repo.git").recorded())
                .isEqualTo("https://host/org/repo.git");
        assertThat(GitSourceCheckout.Source.parse("ssh://git@host:2222/org/repo").recorded())
                .as("an ssh user name is no credential").isEqualTo("ssh://git@host:2222/org/repo");
        assertThat(GitSourceCheckout.Source.parse("ssh://git:pw@host/org/repo").recorded())
                .isEqualTo("ssh://git@host/org/repo");
        assertThat(GitSourceCheckout.Source.parse("git@github.com:org/repo.git").recorded())
                .isEqualTo("git@github.com:org/repo.git");
        assertThat(GitSourceCheckout.Source.parse("git@github.com:org/repo.git").local()).isFalse();
        assertThat(GitSourceCheckout.Source.parse("file:///srv/repo").recorded()).isEqualTo("file:///srv/repo");
    }

    /**
     * A message may hold the user info on its own, without the URL around it. The password is a part of it, so the
     * whole goes first: replacing the password alone would leave the user name of the credential in the message.
     */
    @Test
    void aMessageWithTheUserInfoAlone_keepsNeitherTheUserNorThePassword() {
        GitSourceCheckout.Source source = GitSourceCheckout.Source.parse("https://alice:hunter2x@host.invalid/repo");

        assertThat(source.scrub("credential alice:hunter2x was rejected"))
                .isEqualTo("credential *** was rejected");
        assertThat(source.scrub("the password hunter2x was rejected")).isEqualTo("the password *** was rejected");
    }

    /** A password of four characters is long enough to be replaced on its own: it is no part of other words. */
    @Test
    void aMessageWithAPasswordOfFourCharactersAlone_keepsNoneOfIt() {
        GitSourceCheckout.Source source = GitSourceCheckout.Source.parse("https://alice:pw42@host.invalid/repo");

        assertThat(source.scrub("the password pw42 was rejected")).isEqualTo("the password *** was rejected");
    }

    /**
     * A query or a fragment right after the host leaves the URL without a repository path. It is refused, and so it
     * is never recorded with its query or fragment as the path.
     */
    @Test
    void aUrlWhoseQueryOrFragmentFollowsTheHost_namesNoPath_andIsRefused() {
        for (String source : List.of("https://host.invalid?access=s3cr3t-Q", "https://host.invalid#s3cr3t-F",
                "https://host.invalid#s3cr3t-F?x=s3cr3t-Q")) {
            assertThatThrownBy(() -> GitSourceCheckout.Source.parse(source))
                    .as(source).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("names no repository path").message().doesNotContain("s3cr3t");
        }
    }

    /** A message may hold a part of the query or the fragment on its own, without the URL around it. */
    @Test
    void aMessageWithAQueryOrFragmentPartAlone_keepsNoneOfIt() {
        GitSourceCheckout.Source source = GitSourceCheckout.Source.parse(
                "https://host.invalid/repo?access=s3cr3t-Q#s3cr3t-F");

        assertThat(source.scrub("the parameter access=s3cr3t-Q was refused, anchor s3cr3t-F"))
                .isEqualTo("the parameter *** was refused, anchor ***");
    }

    /** A query part that holds the password goes as a whole, so the user name beside the password goes too. */
    @Test
    void aQueryPartThatHoldsThePassword_isReplacedAsAWhole() {
        GitSourceCheckout.Source source = GitSourceCheckout.Source.parse(
                "https://alice:hunter2x@host.invalid/repo?user=alice&pass=hunter2x");

        assertThat(source.scrub("the query user=alice&pass=hunter2x was refused"))
                .isEqualTo("the query *** was refused");
    }

    /**
     * The record names a URL without its credentials, query and fragment, and so does every message: git echoes the
     * URL it was given in its errors, and the token must reach neither the terminal nor the record.
     */
    @Test
    void sourceUrlCredentials_areNotRecorded() throws Exception {
        String url = "https://user:s3cr3t-T0KEN@host.invalid/org/repo?access=s3cr3t-Q#s3cr3t-F";
        assertThat(GitSourceCheckout.Source.parse(url).recorded()).isEqualTo("https://host.invalid/org/repo");
        assertThat(GitSourceCheckout.Source.parse("https://s3cr3t-T0KEN@host.invalid/repo").recorded())
                .as("a token in the user's place").isEqualTo("https://host.invalid/repo");

        // git prints the URL as given and as it anonymises it itself, which keeps the query and the fragment
        Path script = temp.resolve("echo-git");
        Files.writeString(script, """
                #!/bin/sh
                next=
                for a in "$@"; do
                  if [ -n "$next" ]; then
                    echo "fatal: unable to access '$a/': Could not resolve host" >&2
                    echo "fatal: unable to access '$(echo "$a" | sed 's#//[^/]*@#//#')/'" >&2
                    exit 128
                  fi
                  [ "$a" = "--" ] && next=1
                done
                exit 2
                """);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        GitSourceCheckout echoing = new GitSourceCheckout(workspaces, script.toString(), TIMEOUT, Map.of());

        assertThatThrownBy(() -> echoing.checkout(url, null, target()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https://host.invalid/org/repo")
                .hasMessageContaining("Could not resolve host")
                .hasMessageNotContaining("s3cr3t");
    }

    /** No exception of a failed or timed-out clone, nor any cause or suppressed one, holds the URL's token. */
    @Test
    void noExceptionInTheChain_holdsTheToken() throws Exception {
        String url = "https://user:s3cr3t-T0KEN@host.invalid/org/repo?q=s3cr3t-Q";
        Path slow = temp.resolve("slow-git");
        Files.writeString(slow, "#!/bin/sh\nexec sleep 5\n");
        Files.setPosixFilePermissions(slow, PosixFilePermissions.fromString("rwx------"));
        Path failing = temp.resolve("failing-git");
        Files.writeString(failing, "#!/bin/sh\necho \"fatal: $*\" >&2\nexit 128\n");
        Files.setPosixFilePermissions(failing, PosixFilePermissions.fromString("rwx------"));

        for (Path git : List.of(slow, failing)) {
            GitSourceCheckout checkout = new GitSourceCheckout(workspaces, git.toString(), 300, Map.of());
            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> checkout.checkout(url, null,
                    target()));
            assertThat(thrown).as(git.toString()).isInstanceOf(IllegalStateException.class);
            for (Throwable t : chain(thrown)) {
                assertThat(String.valueOf(t.getMessage()) + t).as(git + ": " + t.getClass()).doesNotContain("s3cr3t");
            }
        }
    }

    /** The checkout holds no credential of the URL it came from: a step reads {@code .git/config}. */
    @Test
    void checkout_keepsNoCredentialOfTheUrl() throws Exception {
        Path repo = source();

        Checkout checkout = checkouts.checkout("file://user:s3cr3t-T0KEN@localhost" + repo, null, target());

        assertThat(checkout.source()).isEqualTo("file://localhost" + repo);
        assertThat(git(target(), "config", "remote.origin.url")).isEqualTo("file://localhost" + repo);
        for (Path file : files(target())) {
            assertThat(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1))
                    .as(file.toString()).doesNotContain("s3cr3t");
        }
    }

    /** The operator's clone.defaultRemoteName must not leave the URL as given under another remote's name. */
    @Test
    void checkout_keepsNoCredentialOfTheUrl_alsoWhenTheOperatorNamesRemotesDifferently() throws Exception {
        Path repo = source();
        Files.writeString(operatorHome.resolve(".gitconfig"), "[clone]\n\tdefaultRemoteName = upstream\n");

        Checkout checkout = checkouts.checkout("file://user:s3cr3t-T0KEN@localhost" + repo, "feature", target());

        assertThat(checkout.sha()).isEqualTo(git(repo, "rev-parse", "feature"));
        assertThat(git(target(), "config", "remote.origin.url")).isEqualTo("file://localhost" + repo);
        for (Path file : files(target())) {
            assertThat(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1))
                    .as(file.toString()).doesNotContain("s3cr3t");
        }
    }

    // ---- the run's directory ----

    @Test
    void directoryOf_isTheRunsFolderBelowTheRoot_forARunIdOnly() {
        assertThat(checkouts.directoryOf("c0c0f5eb-0000-4000-8000-000000000001"))
                .isEqualTo(workspaces.resolve("c0c0f5eb-0000-4000-8000-000000000001"));
        for (String bad : List.of("../x", "a/b", "", ".hidden", "-x")) {
            assertThatThrownBy(() -> checkouts.directoryOf(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void exists_seesADirectoryAndALink() throws IOException {
        assertThat(checkouts.exists(target())).isFalse();
        Files.createDirectories(workspaces);
        Files.createSymbolicLink(target(), temp.resolve("nowhere"));
        assertThat(checkouts.exists(target())).isTrue();
    }

    /** Removing a checkout removes a link in it, never what the link points to. */
    @Test
    void remove_deletesTheCheckout_butNotWhatALinkInItPointsTo() throws Exception {
        Path repo = source();
        checkouts.checkout(repo.toString(), null, target());
        Path outsideFile = Files.writeString(temp.resolve("outside.txt"), "keep me");
        Path outsideDir = Files.createDirectories(temp.resolve("outside-dir"));
        Files.writeString(outsideDir.resolve("inner.txt"), "keep me too");
        Files.createSymbolicLink(target().resolve("to-file"), outsideFile);
        Files.createSymbolicLink(target().resolve("to-dir"), outsideDir);
        Files.createDirectories(target().resolve("nested"));
        Files.createSymbolicLink(target().resolve("nested/up"), temp);

        checkouts.remove(target());

        assertThat(target()).doesNotExist();
        assertThat(outsideFile).hasContent("keep me");
        assertThat(outsideDir.resolve("inner.txt")).hasContent("keep me too");
        assertThat(repo.resolve("a.txt")).exists();
    }

    @Test
    void remove_ofNothing_doesNothing() {
        checkouts.remove(target());
        assertThat(target()).doesNotExist();
    }
}
