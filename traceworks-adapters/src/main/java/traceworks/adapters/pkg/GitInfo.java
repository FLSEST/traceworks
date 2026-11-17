package traceworks.adapters.pkg;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The git state of a package directory. A package may come from an untrusted source, and git runs program code named
 * in a repository's own config: {@code core.fsmonitor}, the hooks (a {@code git status} that refreshes the index
 * runs {@code post-index-change}, from {@code .git/hooks} or from {@code core.hooksPath}), and the filter drivers
 * ({@code filter.<name>.clean}, {@code smudge} and {@code process}) that {@code .gitattributes} applies when
 * {@code git status} compares a file. Every call therefore runs as a {@link GitProcess}: with
 * {@code -c core.fsmonitor=false}, {@code -c core.hooksPath=/dev/null} and {@code --no-optional-locks} (so
 * {@code status} does not write the index at all); {@code status} also empties every configured filter
 * driver (their names are read with {@code git config --name-only}, which runs nothing), makes none required, and
 * ignores submodules. A package that uses filter drivers (git-lfs, for example) may therefore read as dirty.
 * git gets no stdin and at most {@value #TIMEOUT_SECONDS} seconds; a git that does not finish in time is killed and
 * the git state counts as unknown ({@code null} fields), as outside a repository.
 *
 * @param commit the HEAD commit, or {@code null} outside a repository or in one without commits
 * @param dirty  whether the package directory has uncommitted changes, or {@code null} when there is no commit
 */
record GitInfo(String commit, Boolean dirty) {

    static final int TIMEOUT_SECONDS = 10;

    static GitInfo of(Path directory) {
        return of(directory, "git", TIMEOUT_SECONDS * 1000L);
    }

    /** Like {@link #of(Path)} with another git executable and timeout, for tests. */
    static GitInfo of(Path directory, String gitExecutable, long timeoutMillis) {
        return of(directory, gitExecutable, timeoutMillis, Map.of());
    }

    /**
     * Like {@link #of(Path, String, long)} with extra variables in the parent environment, for tests. Every
     * {@code GIT_*} variable is removed before git starts: an inherited {@code GIT_DIR} would report another
     * repository's commit as the package's.
     */
    static GitInfo of(Path directory, String gitExecutable, long timeoutMillis, Map<String, String> parentEnvironment) {
        String head = git(directory, gitExecutable, timeoutMillis, parentEnvironment, "rev-parse", "HEAD");
        if (head == null || head.isBlank()) return new GitInfo(null, null);
        List<String> filters = filterDrivers(directory, gitExecutable, timeoutMillis, parentEnvironment);
        if (filters == null) return new GitInfo(null, null);
        List<String> status = new java.util.ArrayList<>();
        for (String name : filters) {
            status.addAll(List.of("-c", "filter." + name + ".clean=", "-c", "filter." + name + ".smudge=",
                    "-c", "filter." + name + ".process=", "-c", "filter." + name + ".required=false"));
        }
        status.addAll(List.of("status", "--porcelain", "--ignore-submodules=all", "--", "."));
        String changes = git(directory, gitExecutable, timeoutMillis, parentEnvironment, status.toArray(String[]::new));
        if (changes == null) return new GitInfo(null, null);
        return new GitInfo(head.strip(), !changes.isBlank());
    }

    /**
     * The names of the filter drivers git's configuration defines, from {@code git config --name-only}, which reads
     * the configuration and runs nothing; {@code null} when they cannot be told, so the caller runs no
     * {@code status}. A name with {@code =} cannot be passed as {@code -c} and counts as unknown, and so does a name
     * that is not plain ASCII, which need not reach git as the bytes it listed.
     */
    private static List<String> filterDrivers(Path directory, String gitExecutable, long timeoutMillis,
            Map<String, String> parentEnvironment) {
        Output listed = run(directory, gitExecutable, timeoutMillis, parentEnvironment,
                "config", "--name-only", "--get-regexp", "^filter\\.");
        if (listed == null) return null;
        if (listed.exitCode() == 1 && listed.text().isBlank()) return List.of();   // no filter driver configured
        if (listed.exitCode() != 0) return null;
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (String key : listed.text().split("\n")) {
            if (key.isBlank()) continue;
            int last = key.lastIndexOf('.');
            if (!key.startsWith("filter.") || last <= "filter.".length()) return null;
            String name = key.substring("filter.".length(), last);
            if (name.contains("=") || !plainAscii(name)) return null;
            names.add(name);
        }
        return List.copyOf(names);
    }

    /**
     * Whether a driver name is printable ASCII only. To git a name is bytes; here it was decoded as UTF-8 and goes
     * back as an argument in the JVM's encoding. Only ASCII is the same bytes both ways whatever the locale: a byte
     * that is no UTF-8 reads as U+FFFD, and the {@code -c} would then empty a driver of another name.
     */
    private static boolean plainAscii(String name) {
        return name.chars().allMatch(c -> c >= 0x20 && c <= 0x7E);
    }

    /** The standard output of a successful git call, else {@code null}. */
    private static String git(Path directory, String gitExecutable, long timeoutMillis, Map<String, String> parentEnvironment,
            String... args) {
        Output output = run(directory, gitExecutable, timeoutMillis, parentEnvironment, args);
        return output != null && output.exitCode() == 0 ? output.text() : null;
    }

    private record Output(int exitCode, String text) {}

    /**
     * Reading the state of a repository needs no transport. The repository is the package's, so its config is not
     * ours: with every protocol refused, nothing it names as a remote can be reached, whatever git would fetch.
     */
    private static final List<String> NO_TRANSPORT = List.of("-c", "protocol.allow=never");

    /**
     * {@code protocol.allow} is only the default policy, and a {@code protocol.<name>.allow} in the package's config
     * beats it. This variable, set and empty, is the allowlist git reads before any config: no protocol is on it.
     */
    private static final Map<String, String> NO_PROTOCOL = Map.of("GIT_ALLOW_PROTOCOL", "");

    /** The exit code and standard output of a git call; {@code null} when git could not run or timed out. */
    private static Output run(Path directory, String gitExecutable, long timeoutMillis, Map<String, String> parentEnvironment,
            String... args) {
        GitProcess git = new GitProcess(gitExecutable, NO_TRANSPORT, parentEnvironment, NO_PROTOCOL, timeoutMillis);
        try {
            GitProcess.Result result = git.run(directory, List.of(args));
            return new Output(result.exitCode(), result.outputText());
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
