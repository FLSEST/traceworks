package traceworks.adapters.pkg;

import traceworks.adapters.host.ChildProcessEnv;
import traceworks.adapters.host.BoundedOutput;
import traceworks.core.port.CommandRunner;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * One git call as Traceworks makes every one. git runs program code that a repository's own config or the environment
 * names: {@code core.fsmonitor}, the hooks (a {@code git status} that refreshes the index runs
 * {@code post-index-change}, from {@code .git/hooks} or from {@code core.hooksPath}), and an inherited
 * {@code GIT_DIR}, {@code GIT_CONFIG_*} or {@code GIT_SSH_COMMAND} would point git at another repository or program.
 * Every call therefore passes {@link #SAFE_OPTIONS} ({@code --no-optional-locks}, so a read does not write the index
 * either), runs with every inherited {@code GIT_*} variable removed, reads nothing on stdin, and is killed when it does
 * not finish in time.
 *
 * <p>The output of a call is kept up to a limit, each of stdout and stderr ({@link BoundedOutput}): a caller that
 * fetches from a source it does not trust gives the operator's {@code output_limit_mib}, and a call that goes beyond it
 * is killed and ends with {@link OutputLimitExceeded}. The other callers run git on a repository Traceworks made and
 * read its output whole.
 *
 * <p>Every call also runs with {@code LC_ALL=C}: git's messages reach the operator in refusals and errors, and
 * everything Traceworks shows is English. No caller reads a message of git, only exit codes and plumbing output
 * ({@code rev-parse}, {@code status --porcelain}, {@code show}), which the locale does not change, and git's
 * {@code core.quotePath} quotes a non-ASCII path in either locale.
 */
public final class GitProcess {

    /** The options before every git command. */
    public static final List<String> SAFE_OPTIONS = List.of("--no-optional-locks", "-c", "core.fsmonitor=false",
            "-c", "core.hooksPath=/dev/null");

    /**
     * What git gets of the inherited environment besides the operator variables and the locale categories of
     * {@link ChildProcessEnv}: where its configuration is, the SSH agent and the session bus a credential helper
     * asks, and the proxy and certificate settings a clone needs. No {@code GIT_*} variable is among them: a
     * {@code GIT_DIR} or a {@code GIT_CONFIG_*} pair would redirect git.
     */
    static final Set<String> GIT_ENVIRONMENT = Set.of("XDG_CONFIG_HOME", "XDG_RUNTIME_DIR", "SSH_AUTH_SOCK",
            "DBUS_SESSION_BUS_ADDRESS", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY", "http_proxy",
            "https_proxy", "all_proxy", "no_proxy", "SSL_CERT_FILE", "SSL_CERT_DIR");

    /** Whether git gets the variable {@code name} of the inherited environment. */
    static boolean inherited(String name) {
        return ChildProcessEnv.OPERATOR_ENVIRONMENT.contains(name) || ChildProcessEnv.LOCALE_CATEGORIES.contains(name)
                || GIT_ENVIRONMENT.contains(name);
    }

    /** How often the wait for git looks at its output. */
    private static final long POLL_MILLIS = 100;

    private final String executable;
    private final List<String> options;
    private final Map<String, String> parentEnvironment;
    private final Map<String, String> environment;
    private final long timeoutMillis;
    private final int outputLimit;

    /**
     * @param executable        {@code git}, or another one for tests
     * @param options           more options before the command, after {@link #SAFE_OPTIONS}: {@code -c} pairs
     * @param parentEnvironment variables added to the inherited environment, for tests; one that git does not get
     *                          ({@link #inherited}), a {@code GIT_*} one among them, is removed like the inherited
     *                          ones
     * @param environment       variables git gets as they are, set after the inherited ones are filtered
     * @param timeoutMillis     how long one call may take
     */
    public GitProcess(String executable, List<String> options, Map<String, String> parentEnvironment,
                      Map<String, String> environment, long timeoutMillis) {
        this(executable, options, parentEnvironment, environment, timeoutMillis, Integer.MAX_VALUE);
    }

    /**
     * @param outputLimit how many bytes of stdout and of stderr a call keeps at most; more ends the call with
     *                    {@link OutputLimitExceeded}
     */
    public GitProcess(String executable, List<String> options, Map<String, String> parentEnvironment,
                      Map<String, String> environment, long timeoutMillis, int outputLimit) {
        this.executable = Objects.requireNonNull(executable, "executable");
        this.options = List.copyOf(options);
        this.parentEnvironment = Map.copyOf(parentEnvironment);
        this.environment = Map.copyOf(environment);
        this.timeoutMillis = timeoutMillis;
        this.outputLimit = outputLimit;
    }

    /** How long one call may take, in milliseconds. */
    public long timeoutMillis() {
        return timeoutMillis;
    }

    /**
     * What a git call gave: its exit code, its standard output and, apart, its standard error, so a warning never
     * ends up in what a caller reads from the output.
     */
    public record Result(int exitCode, byte[] output, byte[] error) {

        public String outputText() {
            return new String(output, StandardCharsets.UTF_8);
        }

        public String errorText() {
            return new String(error, StandardCharsets.UTF_8);
        }
    }

    /** git did not finish within its time; it was killed. */
    public static final class TimedOut extends IOException {

        TimedOut(String message) {
            super(message);
        }
    }

    /** A stream of git went beyond the output limit; git was killed. */
    public static final class OutputLimitExceeded extends IOException {

        private final int limit;

        OutputLimitExceeded(int limit) {
            super("its output went beyond the limit of " + CommandRunner.sizeText(limit));
            this.limit = limit;
        }

        /** The limit in force, in bytes. */
        public int limit() {
            return limit;
        }
    }

    /**
     * Runs {@code git <options> [-C directory] <args>} and waits for it.
     *
     * @param directory where git runs ({@code -C}); {@code null} for no {@code -C}
     * @throws TimedOut             if git does not finish in time; its message names no argument
     * @throws OutputLimitExceeded  if stdout or stderr goes beyond the output limit; git is killed
     * @throws IOException          if git cannot start or its output cannot be read
     * @throws InterruptedException if the calling thread is interrupted while it waits; git is killed
     */
    public Result run(Path directory, List<String> args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(SAFE_OPTIONS);
        command.addAll(options);
        if (directory != null) {
            command.addAll(List.of("-C", directory.toString()));
        }
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        Map<String, String> env = builder.environment();
        env.putAll(parentEnvironment);
        // git runs on the host against a source or a package nobody checked. It gets what it needs to find its
        // tools, its configuration and a way to the remote, and no key or token of the operator: a helper git
        // starts (ssh, a credential helper) would get them too
        env.keySet().removeIf(name -> !inherited(name));
        env.put("LC_ALL", "C");
        // A repository's own config can name a promisor remote whose transport is a program (core.sshCommand,
        // remote.<name>.uploadpack). With a missing object, any command that reads the repository would fetch it
        // lazily and so start that program on the host. No call here needs a lazy fetch
        env.put("GIT_NO_LAZY_FETCH", "1");
        env.putAll(environment);
        Process process = builder.start();
        try {
            // Both on threads of their own: a full stderr pipe would block git while this one reads stdout, and the
            // wait must end on the timeout or the limit when git hangs or never stops printing
            BoundedOutput out = new BoundedOutput(outputLimit);
            BoundedOutput err = new BoundedOutput(outputLimit);
            // git is not held back until the readers hold its streams: of a git that ended within the instant of its
            // start, what a helper it left behind writes afterwards is lost (BoundedOutput#drain). No caller reads
            // such output: they read exit codes and plumbing output, which git writes itself
            out.drain(process.getInputStream(), "git-output");
            err.drain(process.getErrorStream(), "git-error");
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                if (out.exceeded() || err.exceeded()) {
                    throw new OutputLimitExceeded(outputLimit);
                }
                if (System.nanoTime() >= deadline) {
                    // Without the arguments: they may hold a credential, and a caller words its own message
                    throw new TimedOut("git did not finish within " + timeoutMillis / 1000 + " seconds");
                }
            }
            out.end(timeoutMillis);
            err.end(timeoutMillis);
            if (out.exceeded() || err.exceeded()) {
                throw new OutputLimitExceeded(outputLimit);
            }
            return new Result(process.exitValue(), out.bytes(),
                    err.bytes());
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
