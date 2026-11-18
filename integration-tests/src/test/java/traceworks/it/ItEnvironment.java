package traceworks.it;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What every test of the suite shares, set up once per JVM: the container runtime the suite runs against, the jar under
 * test, the workflow packages, and the images those packages need, built with {@code traceworks images build}.
 *
 * <p>Whatever is missing fails the suite, with a message that says what to do. Nothing is skipped, so a CI run can never
 * pass by skipping. The suite runs only with {@code mvn -Pit verify}; a plain {@code mvn verify} never gets here.
 */
final class ItEnvironment {

    /** The environment variable that names the container runtime: {@code docker} (the default) or {@code podman}. */
    static final String RUNTIME_VARIABLE = "TRACEWORKS_IT_RUNTIME";

    /** How long {@code <runtime> version} may take; Traceworks itself waits 10 seconds. */
    private static final long VERSION_TIMEOUT_SECONDS = 60;

    /** How long building the images may take: the first build of the {@code shell} image installs Debian packages. */
    private static final long IMAGES_TIMEOUT_SECONDS = 1200;

    /** How long building one fake AI image may take: its base is the one of the built-in AI images. */
    private static final long FAKE_IMAGE_TIMEOUT_SECONDS = 600;

    private static ItEnvironment shared;
    private static AssertionError failure;

    private final String runtime;
    private final Path jar;
    private final Path packages;
    private final Path java;
    private final Path fakeAi;
    private final Map<String, String> fakeImages = new TreeMap<>();
    private boolean egressImageBuilt;

    private ItEnvironment(String runtime, Path jar, Path packages, Path java, Path fakeAi) {
        this.runtime = runtime;
        this.jar = jar;
        this.packages = packages;
        this.java = java;
        this.fakeAi = fakeAi;
    }

    /**
     * The environment, prepared on the first call: the runtime answers, the jar and the packages exist, and the images
     * are built. A failure is kept, so every later test fails with the same message instead of trying again.
     */
    static synchronized ItEnvironment get() {
        if (failure != null) {
            throw failure;
        }
        if (shared == null) {
            try {
                ItEnvironment environment = locate();
                environment.buildImages();
                shared = environment;
            } catch (AssertionError | RuntimeException | IOException e) {
                failure = new AssertionError("The integration test environment is not ready: " + e.getMessage(), e);
                throw failure;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = new AssertionError("Interrupted while the integration test environment was prepared", e);
                throw failure;
            }
        }
        return shared;
    }

    /** {@code docker} or {@code podman}: the client the suite runs against and checks for leftover containers. */
    String runtime() {
        return runtime;
    }

    /** The shaded {@code traceworks.jar} under test. */
    Path jar() {
        return jar;
    }

    /** The {@code java} of the JVM that runs the tests, which starts the jar. */
    Path java() {
        return java;
    }

    /**
     * The tag of the fake image of the AI CLI {@code cli} ({@code claude} or {@code pi}):
     * {@code traceworks-it/fake-<cli>:<h>}, {@code <h>} from the files of {@code integration-tests/fake-ai/<cli>}. The
     * image is built on the first call, once per suite run, with the suite's runtime; a build that fails fails the test
     * with the runtime's output. Its name is not that of a built-in image: the suite never builds, tags or removes
     * {@code traceworks/claude} or {@code traceworks/pi}.
     */
    synchronized String fakeImage(String cli) {
        String known = fakeImages.get(cli);
        if (known != null) {
            return known;
        }
        try {
            Path directory = fakeAi.resolve(cli);
            if (!Files.isRegularFile(directory.resolve("Dockerfile"))) {
                throw new AssertionError("No Dockerfile of the fake " + cli + " image in " + directory);
            }
            String tag = "traceworks-it/fake-" + cli + ":" + directoryHash(directory);
            Path log = Files.createTempFile("traceworks-it-fake-" + cli + "-", ".log");
            try {
                Process process = new ProcessBuilder(runtime, "build", "-t", tag, directory.toString())
                        .redirectErrorStream(true).redirectOutput(log.toFile())
                        .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null"))).start();
                boolean ended = process.waitFor(FAKE_IMAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!ended) {
                    process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
                }
                if (!ended || process.exitValue() != 0) {
                    throw new AssertionError("'" + runtime + " build -t " + tag + " " + directory + "' failed ("
                            + (ended ? "exit " + process.exitValue() : "no end within " + FAKE_IMAGE_TIMEOUT_SECONDS
                            + " s") + "); the base image needs the network once:\n"
                            + Files.readString(log, StandardCharsets.UTF_8));
                }
            } finally {
                Files.deleteIfExists(log);
            }
            fakeImages.put(cli, tag);
            return tag;
        } catch (IOException e) {
            throw new AssertionError("The fake " + cli + " image cannot be built: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while the fake " + cli + " image was built", e);
        }
    }

    /**
     * Makes sure the built-in image of the egress proxy is there, which every AI call's container needs as its only
     * door: {@code traceworks/egress:<h>}, built once per suite run from {@code images/egress} of the repository,
     * with the suite's runtime. Its base is the JDK image the self-audit images pin.
     *
     * <p>{@code traceworks images build} would build it, but with the real images of Claude and Pi, which the suite
     * never builds. So the suite asks Traceworks for the tag instead: a run with an AI step and no egress image is
     * refused by the start check, which names the image it misses, and the suite builds exactly that.
     */
    synchronized void egressImage() {
        if (egressImageBuilt) {
            return;
        }
        try {
            Path scratch = Files.createTempDirectory("traceworks-it-egress-");
            try {
                Traceworks probe = Traceworks.in(this, scratch);
                probe.aiImages(Map.of("claude", fakeImage("claude"), "pi", fakeImage("pi")));
                probe.credentials(Map.of("ANTHROPIC_API_KEY", AiIntegrationTest.FAKE_KEY));
                Result run = probe.run("ai-simple");
                Matcher missing = Pattern
                        .compile("image (traceworks/egress:[0-9a-f]{12}) is missing: run 'traceworks images build'")
                        .matcher(run.err());
                if (missing.find()) {
                    Path directory = packages.getParent().getParent().resolve("images").resolve("egress");
                    build(missing.group(1), directory, "--label", "traceworks.image="
                            + missing.group(1));
                } else if (run.code() != 0) {
                    throw new AssertionError("The start check neither ran ai-simple nor named the egress image it"
                            + " misses:\n" + run.describe());
                }
                probe.folders().forEach(folder -> {
                    Containers.assertNoneLeft(runtime, folder.runIds());
                    Containers.assertNoNetworkLeft(runtime, folder.runIds());
                });
            } finally {
                Traceworks.deleteTree(scratch);
            }
            egressImageBuilt = true;
        } catch (IOException e) {
            throw new AssertionError("The egress image cannot be built: " + e.getMessage(), e);
        }
    }

    /** {@code <runtime> build -t <tag> <more> <directory>}; fails with the runtime's output. */
    private void build(String tag, Path directory, String... more) throws IOException {
        Path log = Files.createTempFile("traceworks-it-build-", ".log");
        try {
            List<String> command = new ArrayList<>(List.of(runtime, "build", "-t", tag));
            command.addAll(List.of(more));
            command.add(directory.toString());
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null"))).start();
            boolean ended = process.waitFor(FAKE_IMAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!ended) {
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
            if (!ended || process.exitValue() != 0) {
                throw new AssertionError("'" + String.join(" ", command) + "' failed (" + (ended ? "exit "
                        + process.exitValue() : "no end within " + FAKE_IMAGE_TIMEOUT_SECONDS + " s")
                        + "); the base image needs the network once:\n"
                        + Files.readString(log, StandardCharsets.UTF_8));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while image " + tag + " was built", e);
        } finally {
            Files.deleteIfExists(log);
        }
    }

    /**
     * What the runtime has of the repositories {@code traceworks/claude} and {@code traceworks/pi}, the built-in AI
     * images, as sorted lines {@code <repository>:<tag> <image ID>}; empty when it has none. The AI tests compare it
     * before and after: it must not change, since the suite uses fake images only.
     */
    List<String> builtInAiImages() {
        try {
            Path out = Files.createTempFile("traceworks-it-images-", ".txt");
            try {
                Process process = new ProcessBuilder(runtime, "images", "--no-trunc", "--format",
                        "{{.Repository}}:{{.Tag}} {{.ID}}").redirectErrorStream(true).redirectOutput(out.toFile())
                        .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null"))).start();
                if (!process.waitFor(VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS) || process.exitValue() != 0) {
                    process.destroyForcibly();
                    throw new AssertionError("'" + runtime + " images' does not work: "
                            + Files.readString(out, StandardCharsets.UTF_8).strip());
                }
                return Files.readAllLines(out, StandardCharsets.UTF_8).stream()
                        .filter(line -> line.matches("(?:[^ /]+/)?traceworks/(?:claude|pi):\\S* .*")).sorted().toList();
            } finally {
                Files.deleteIfExists(out);
            }
        } catch (IOException e) {
            throw new AssertionError("The built-in AI images cannot be listed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while the images were listed", e);
        }
    }

    /** The image ID the runtime has for {@code reference}, as it prints it ({@code sha256:...}); fails when it has none. */
    String imageId(String reference) {
        try {
            Path out = Files.createTempFile("traceworks-it-image-id-", ".txt");
            try {
                Process process = new ProcessBuilder(runtime, "image", "inspect", "--format", "{{.Id}}", reference)
                        .redirectErrorStream(true).redirectOutput(out.toFile())
                        .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null"))).start();
                if (!process.waitFor(VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS) || process.exitValue() != 0) {
                    process.destroyForcibly();
                    throw new AssertionError("'" + runtime + " image inspect " + reference + "' failed: "
                            + Files.readString(out, StandardCharsets.UTF_8).strip());
                }
                // Podman prints the bare hex; the record names an image ID with its algorithm, as Docker prints it.
                String id = Files.readString(out, StandardCharsets.UTF_8).strip();
                return id.startsWith("sha256:") ? id : "sha256:" + id;
            } finally {
                Files.deleteIfExists(out);
            }
        } catch (IOException e) {
            throw new AssertionError("The ID of image " + reference + " cannot be read: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while the ID of image " + reference + " was read", e);
        }
    }

    /** The first 12 hex characters of the SHA-256 of the names and bytes of the files below {@code directory}. */
    private static String directoryHash(Path directory) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                digest.update(directory.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(file));
                digest.update((byte) 0);
            }
        }
        return HexFormat.of().formatHex(digest.digest()).substring(0, 12);
    }

    /** The package {@code integration-tests/packages/<name>}. */
    Path packageDir(String name) {
        Path dir = packages.resolve(name);
        if (!Files.isRegularFile(dir.resolve("workflow.json"))) {
            throw new AssertionError("No package " + name + " in " + packages);
        }
        return dir;
    }

    private static ItEnvironment locate() throws IOException, InterruptedException {
        String configured = System.getenv(RUNTIME_VARIABLE);
        String runtime = configured == null || configured.isBlank() ? "docker" : configured.strip()
                .toLowerCase(Locale.ROOT);
        if (!runtime.equals("docker") && !runtime.equals("podman")) {
            throw new AssertionError(RUNTIME_VARIABLE + " is '" + configured + "'; it must be docker or podman");
        }
        Path jar = Path.of(required("traceworks.it.jar")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar)) {
            throw new AssertionError("The jar under test " + jar + " does not exist; build it first: mvn -Pit verify"
                    + " from the repository root builds traceworks-cli before this module");
        }
        Path packages = Path.of(required("traceworks.it.packages")).toAbsolutePath().normalize();
        if (!Files.isDirectory(packages)) {
            throw new AssertionError("The packages directory " + packages + " does not exist");
        }
        Path fakeAi = Path.of(required("traceworks.it.fakeai")).toAbsolutePath().normalize();
        if (!Files.isDirectory(fakeAi)) {
            throw new AssertionError("The directory of the fake AI images " + fakeAi + " does not exist");
        }
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        answersVersion(runtime);
        return new ItEnvironment(runtime, jar, packages, java, fakeAi);
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new AssertionError("The system property " + property + " is not set; run the suite with"
                    + " mvn -Pit verify");
        }
        return value;
    }

    /** Fails unless {@code <runtime> version} ends with 0 in time: the runtime Traceworks would detect must work. */
    private static void answersVersion(String runtime) throws IOException, InterruptedException {
        Path output = Files.createTempFile("traceworks-it-version-", ".txt");
        try {
            Process process;
            try {
                process = new ProcessBuilder(runtime, "version").redirectErrorStream(true)
                        .redirectOutput(output.toFile()).start();
            } catch (IOException e) {
                throw new AssertionError(RUNTIME_VARIABLE + " names " + runtime + ", which is not installed: "
                        + e.getMessage(), e);
            }
            process.getOutputStream().close();
            boolean ended = process.waitFor(VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!ended) {
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
            String printed = Files.readString(output, StandardCharsets.UTF_8);
            if (!ended || process.exitValue() != 0) {
                throw new AssertionError("'" + runtime + " version' does not work (" + (ended ? "exit "
                        + process.exitValue() : "no answer within " + VERSION_TIMEOUT_SECONDS + " s") + "): "
                        + printed.strip());
            }
        } finally {
            Files.deleteIfExists(output);
        }
    }

    /**
     * Builds the images the packages use, as a user would: {@code traceworks images build <package>} for one package
     * without images of its own (the built-in {@code shell} image every container run needs) and for every package
     * with an {@code images/} directory. The built-in AI images are not built: no test of this suite calls a model, and the AI
     * tests run fake CLIs in fake images of their own ({@link #fakeImage}).
     */
    private void buildImages() throws IOException, InterruptedException {
        List<Path> targets = new ArrayList<>();
        targets.add(packageDir("placeholders"));
        try (Stream<Path> dirs = Files.list(packages)) {
            dirs.filter(dir -> Files.isDirectory(dir.resolve("images"))).sorted().forEach(targets::add);
        }
        Path scratch = Files.createTempDirectory("traceworks-it-images-");
        try {
            Traceworks builder = Traceworks.in(this, scratch);
            for (Path target : targets) {
                Result built = builder.exec(IMAGES_TIMEOUT_SECONDS, "images", "build", target.toString());
                if (built.code() != 0) {
                    throw new AssertionError("traceworks images build " + target + " failed:\n" + built.describe());
                }
            }
        } finally {
            Traceworks.deleteTree(scratch);
        }
    }
}
