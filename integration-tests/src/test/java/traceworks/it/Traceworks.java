package traceworks.it;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Runs the real {@code traceworks.jar} as a process, the way a user or a CI runner does, in a sandbox of its own: a
 * Traceworks home ({@code TRACEWORKS_HOME}), a workspace that is the working directory of every command, and a fake
 * home directory of the user ({@code user.home}), so no test depends on, or sees, the home of whoever runs it.
 *
 * <p>The home's {@code traceworks.json} names the container runtime of the suite ({@code container_runtime}), so the
 * suite never depends on the order in which Traceworks detects one. Each command gets the environment of the test JVM
 * without any {@code TRACEWORKS_*} variable and without any credential of an AI provider ({@link #credentialLike}),
 * plus {@code TRACEWORKS_HOME}, {@code TRACEWORKS_ACTOR} and a UTF-8 locale, no stdin, and a time limit after which it
 * is killed and the test fails.
 *
 * <p><b>No real credential ever reaches a test.</b> Traceworks takes the credentials of an AI provider from two
 * places only: {@code <home>/credentials.json} and the environment of its own process. The first is in the sandbox, and
 * a test writes only obvious fakes into it ({@link #credentials}); the second is stripped of every name that
 * Traceworks or the AI CLIs read, so what the test JVM's user has set cannot reach a command, a container or a log.
 * A test that needs one in the environment sets a fake one itself ({@link #environmentVariable}). Nothing else is read:
 * {@code user.home} points into the sandbox, and a container sees no directory of the real home.
 */
final class Traceworks {

    /** The actor every command of the suite records. */
    static final String ACTOR = "it-tester";

    /** How long one command may take unless the test says otherwise. */
    static final long TIMEOUT_SECONDS = 300;

    /**
     * The names of the credentials of the AI providers that Traceworks passes to a CLI (docs/cli.md, "AI steps"): Claude's
     * and Pi's.
     */
    static final List<String> CREDENTIAL_NAMES = List.of("ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN",
            "ANTHROPIC_OAUTH_TOKEN", "OPENAI_API_KEY", "GEMINI_API_KEY", "OPENROUTER_API_KEY");

    /** Prefixes of the variables the AI CLIs and their providers read: logins, endpoints, session and settings. */
    private static final List<String> CREDENTIAL_PREFIXES = List.of("ANTHROPIC_", "CLAUDE", "OPENAI_", "GEMINI_",
            "GOOGLE_", "OPENROUTER_", "PI_");

    /**
     * Whether the environment variable {@code name} may carry a credential of an AI provider or steer an AI CLI: a
     * credential name Traceworks knows, a name the CLIs read by prefix, or any name ending in {@code _API_KEY}. The
     * suite removes all of them from every command's environment.
     */
    static boolean credentialLike(String name) {
        return CREDENTIAL_NAMES.contains(name) || name.endsWith("_API_KEY")
                || CREDENTIAL_PREFIXES.stream().anyMatch(name::startsWith);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ItEnvironment environment;
    private final Path home;
    private final Path workspace;
    private final Path userHome;
    private final Path logs;
    private final Map<String, String> extraEnvironment = new LinkedHashMap<>();
    private Map<String, Object> settings = Map.of();
    private Map<String, String> aiImages = Map.of();
    private boolean aiNetworkOpen;
    private final AtomicInteger commands = new AtomicInteger();

    private Traceworks(ItEnvironment environment, Path home, Path workspace, Path userHome, Path logs) {
        this.environment = environment;
        this.home = home;
        this.workspace = workspace;
        this.userHome = userHome;
        this.logs = logs;
    }

    /**
     * A new sandbox in {@code root}: {@code home/} with a {@code traceworks.json} that names the suite's runtime,
     * {@code work/} (the workspace), {@code user/} (the fake home directory) and {@code logs/} (what each command
     * printed).
     */
    static Traceworks in(ItEnvironment environment, Path root) {
        try {
            Traceworks traceworks = new Traceworks(environment, Files.createDirectories(root.resolve("home")),
                    Files.createDirectories(root.resolve("work")), Files.createDirectories(root.resolve("user")),
                    Files.createDirectories(root.resolve("logs")));
            traceworks.write();
            return traceworks;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Path home() {
        return home;
    }

    Path workspace() {
        return workspace;
    }

    /** The fake home directory of the user: {@code user.home} of every command, so {@code ~} expands to it. */
    Path userHome() {
        return userHome;
    }

    ItEnvironment environment() {
        return environment;
    }

    /** The package {@code integration-tests/packages/<name>}. */
    Path packageDir(String name) {
        return environment.packageDir(name);
    }

    /**
     * Writes {@code traceworks.json}: the suite's {@code container_runtime} and {@code settings} (such as
     * {@code container_limits}), and the {@code ai_images} of {@link #aiImages}, which later calls keep.
     */
    void configure(Map<String, Object> settings) {
        this.settings = Map.copyOf(settings);
        write();
    }

    /**
     * Sets {@code ai_images} in {@code traceworks.json}, by CLI id ({@code claude}, {@code pi}), and keeps it when
     * {@link #configure} writes the file again. An empty map writes none.
     */
    void aiImages(Map<String, String> images) {
        this.aiImages = Map.copyOf(images);
        write();
    }

    /**
     * Sets {@code ai_network: "open"} in {@code traceworks.json}, and keeps it when {@link #configure} writes the file
     * again: the containers of this sandbox's AI calls get the runtime's default network, so no run of it makes a
     * network or starts an egress proxy (docs/cli.md, "The network of an AI step"). For tests that are not about
     * where an AI container can connect; a test's own {@code ai_network} in {@link #configure} still wins.
     */
    void openAiNetwork() {
        this.aiNetworkOpen = true;
        write();
    }

    private void write() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("container_runtime", environment.runtime());
        if (aiNetworkOpen) {
            config.put("ai_network", "open");
        }
        config.putAll(settings);
        if (!aiImages.isEmpty()) {
            config.put("ai_images", aiImages);
        }
        try {
            JSON.writeValue(home.resolve("traceworks.json").toFile(), config);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes {@code credentials.json} with {@code values}, readable by its owner only, as Traceworks requires. */
    void credentials(Map<String, String> values) {
        Path file = home.resolve("credentials.json");
        try {
            JSON.writeValue(file.toFile(), values);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Sets an environment variable for every later command of this sandbox. */
    void environmentVariable(String name, String value) {
        extraEnvironment.put(name, value);
    }

    /**
     * {@code traceworks run <package> --workspace <workspace> --no-input <more>}: the package from
     * {@code integration-tests/packages/}, with no terminal question.
     */
    Result run(String packageName, String... more) {
        return runPackage(packageDir(packageName), more);
    }

    /** Runs the package in {@code examples/<name>} of the repository, the way {@link #run} runs a test package. */
    Result runExample(String name, String... more) {
        return runPackage(examplePackage(name), more);
    }

    /** The example {@code examples/<name>} of the repository, two levels above the test packages. */
    Path examplePackage(String name) {
        Path dir = environment.packageDir("placeholders").getParent().getParent().getParent()
                .resolve("examples").resolve(name);
        if (!Files.isRegularFile(dir.resolve("workflow.json"))) {
            throw new AssertionError("No example " + name + " in " + dir.getParent());
        }
        return dir;
    }

    private Result runPackage(Path dir, String... more) {
        List<String> args = new ArrayList<>(List.of("run", dir.toString(), "--workspace", workspace.toString(),
                "--no-input"));
        args.addAll(Arrays.asList(more));
        return exec(args.toArray(String[]::new));
    }

    /** {@code traceworks <args>}, with the default time limit. */
    Result exec(String... args) {
        return exec(TIMEOUT_SECONDS, args);
    }

    /**
     * {@code java -Duser.home=<user home> -jar traceworks.jar <args>} in the workspace. stdout and stderr go to files
     * in {@code logs/}, so a command that prints much never blocks on a full pipe. A command that does not end within
     * {@code timeoutSeconds} is killed, and the test fails.
     */
    Result exec(long timeoutSeconds, String... args) {
        return exec(timeoutSeconds, new File("/dev/null"), args);
    }

    /** {@code traceworks <args>} with {@code input} as its standard input, as from a pipe. */
    Result execWithInput(String input, String... args) {
        try {
            Path file = logs.resolve(commands.get() + 1 + ".in");
            Files.writeString(file, input, StandardCharsets.UTF_8);
            return exec(TIMEOUT_SECONDS, file.toFile(), args);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Result exec(long timeoutSeconds, File standardInput, String... args) {
        return start(List.of(), standardInput, args).await(timeoutSeconds);
    }

    /**
     * Starts {@code traceworks run <package> --workspace <workspace> --no-input <more>} and returns at once, with the
     * command still running, as in a terminal of its own: a test sends it signals ({@link Running#ctrlC},
     * {@link Running#terminate}) and then waits for its end.
     *
     * <p>The command leads a process group of its own ({@code setsid}), so a Ctrl-C can go to the whole group, as a
     * terminal sends it, and it takes SIGINT and SIGTERM by their defaults ({@code env --default-signal}), whatever
     * the JVM of the suite inherited: a build started in the background ignores SIGINT, and so would every process
     * it starts. Both programs are part of a Linux system (util-linux, GNU coreutils 8.31 or later).
     */
    Running startRun(String packageName, String... more) {
        List<String> args = new ArrayList<>(List.of("run", packageDir(packageName).toString(), "--workspace",
                workspace.toString(), "--no-input"));
        args.addAll(Arrays.asList(more));
        return start(List.of("setsid", "env", "--default-signal=INT", "--default-signal=TERM"),
                new File("/dev/null"), args.toArray(String[]::new));
    }

    /** Starts {@code <launcher> java -Duser.home=<user home> -jar traceworks.jar <args>} in the workspace. */
    private Running start(List<String> launcher, File standardInput, String... args) {
        List<String> command = new ArrayList<>(launcher);
        command.addAll(List.of(environment.java().toString(), "-Duser.home=" + userHome, "-jar",
                environment.jar().toString()));
        command.addAll(Arrays.asList(args));
        int n = commands.incrementAndGet();
        Path out = logs.resolve(n + ".out");
        Path err = logs.resolve(n + ".err");
        ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.toFile())
                .redirectInput(ProcessBuilder.Redirect.from(standardInput))
                .redirectOutput(out.toFile()).redirectError(err.toFile());
        Map<String, String> env = builder.environment();
        env.keySet().removeIf(name -> name.startsWith("TRACEWORKS_") || credentialLike(name));
        env.put("TRACEWORKS_HOME", home.toString());
        env.put("TRACEWORKS_ACTOR", ACTOR);
        env.put("LANG", "C.UTF-8");
        env.put("LC_ALL", "C.UTF-8");
        env.putAll(extraEnvironment);
        try {
            return new Running(builder.start(), Arrays.asList(args), out, err);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A command that was started and may still run. */
    static final class Running {

        private final Process process;
        private final List<String> args;
        private final Path out;
        private final Path err;

        private Running(Process process, List<String> args, Path out, Path err) {
            this.process = process;
            this.args = args;
            this.out = out;
            this.err = err;
        }

        /**
         * Ctrl-C, as a terminal sends it: SIGINT to every process of the command's process group, which are the
         * command and whatever it started without a session of its own.
         */
        void ctrlC() {
            signal("-INT", "--", "-" + process.pid());
        }

        /** SIGTERM to the command alone, as {@code kill <pid>} or a service manager sends it. */
        void terminate() {
            signal("-TERM", String.valueOf(process.pid()));
        }

        private void signal(String... killArguments) {
            List<String> kill = new ArrayList<>(List.of("kill"));
            kill.addAll(Arrays.asList(killArguments));
            try {
                Process sent = new ProcessBuilder(kill).redirectErrorStream(true).start();
                String said = new String(sent.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!sent.waitFor(30, TimeUnit.SECONDS) || sent.exitValue() != 0) {
                    throw new AssertionError(String.join(" ", kill) + " failed: " + said);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while signalling traceworks " + String.join(" ", args), e);
            }
        }

        /** What the command printed so far on standard error. */
        String errSoFar() {
            try {
                return Files.readString(err, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /**
         * Waits for the end of the command and returns its result. A command that does not end within
         * {@code timeoutSeconds} is killed with everything it started, and the test fails.
         */
        Result await(long timeoutSeconds) {
            try {
                boolean ended = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!ended) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly().waitFor(30, TimeUnit.SECONDS);
                }
                Result result = new Result(args, ended ? process.exitValue() : -1,
                        Files.readString(out, StandardCharsets.UTF_8), Files.readString(err, StandardCharsets.UTF_8));
                if (!ended) {
                    throw new AssertionError("The command did not end within " + timeoutSeconds
                            + " s and was killed:\n" + result.describe());
                }
                return result;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while traceworks " + String.join(" ", args) + " ran", e);
            }
        }
    }

    /** The folder of run {@code runId} in this sandbox's home. */
    RunFolder folder(String runId) {
        return RunFolder.find(home, runId);
    }

    /** The folder of the run that {@code result} names in its summary line. */
    RunFolder folder(Result result) {
        return folder(result.runId());
    }

    /** Every run folder in this sandbox's home, oldest day first. */
    List<RunFolder> folders() {
        Path runs = home.resolve("runs");
        if (!Files.isDirectory(runs)) {
            return List.of();
        }
        try (Stream<Path> days = Files.list(runs)) {
            List<RunFolder> folders = new ArrayList<>();
            for (Path day : days.sorted().toList()) {
                try (Stream<Path> dirs = Files.list(day)) {
                    dirs.filter(Files::isDirectory).sorted().map(RunFolder::new).forEach(folders::add);
                }
            }
            return folders;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Deletes {@code root} and everything in it, without following links; a file that cannot go fails. */
    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
