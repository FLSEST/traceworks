package traceworks.cli;

import traceworks.adapters.container.CliContainerRuntime;
import traceworks.adapters.container.RuntimeDetection;
import traceworks.adapters.images.BuiltInImages;
import traceworks.cli.main.Main;
import traceworks.core.app.Images;
import traceworks.core.port.ContainerRuntime;
import traceworks.core.testing.ContainerPrecondition;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the command line's end-to-end tests with a real container runtime share: the runtime of the machine and its
 * built-in {@code shell} image, a command run as the CLI runs it, the run id it prints, and the containers a run
 * left behind.
 *
 * <p>Every class that uses it starts real containers and carries {@code @Tag("container")}: the default build leaves
 * it out, and {@code mvn -Pcontainers verify} runs it. What the machine lacks then fails the test
 * ({@link ContainerPrecondition}); no container test skips in a build that asked for it.
 */
final class ContainerRuns {

    /** The summary line {@code run <id> STATUS}; a UUID in a path or a step's output is not it. */
    static final Pattern RUN_ID =
            Pattern.compile("(?m)^run ([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}) [A-Z_]+$");

    /** How long one {@code ps} of the runtime may take. */
    private static final long PS_TIMEOUT_SECONDS = 60;

    private ContainerRuns() {
    }

    /**
     * The container runtime of this machine with its built-in {@code shell} image built, as a container run needs
     * them; without either the test fails, or is skipped outside a build that asked for the container tests. No test
     * builds the image.
     *
     * @param probeHome a Traceworks home for the detection
     */
    static CliContainerRuntime runtimeWithShellImage(Path probeHome) {
        Optional<CliContainerRuntime> detected = RuntimeDetection.detect(null, probeHome);
        ContainerPrecondition.require(detected.isPresent(), "no container runtime works (podman or docker)",
                ContainerPrecondition.START_A_RUNTIME);
        CliContainerRuntime runtime = detected.get();
        String shell = Images.builtInTag(new BuiltInImages().all().stream()
                .filter(image -> image.name().equals("shell")).findFirst().orElseThrow());
        ContainerPrecondition.require(runtime.imageId(shell).isPresent(), "the built-in image shell (" + shell
                + ") is not built", ContainerPrecondition.BUILD_THE_IMAGES);
        return runtime;
    }

    /** The exit code and what a command printed. */
    record Result(int code, String out, String err) {
        String both() {
            return out + err;
        }
    }

    /**
     * Runs {@code traceworks <args> --home <home>} in {@code work} through {@link Main}, with the container runtime
     * of the machine and {@code TRACEWORKS_ACTOR=tester}.
     */
    static Result exec(Path home, Path work, String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--home");
        all.add(home.toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(all.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Map.of("TRACEWORKS_ACTOR", "tester"), work, null,
                RuntimeSource.MACHINE);
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /**
     * What {@code ps -a --filter label=<run label>=<run id> -q} lists: the containers of the run, one id per line.
     * The whole call is bounded: its output goes to a file, and a {@code ps} that does not end in time is killed and
     * fails the test.
     */
    static String containersOf(CliContainerRuntime runtime, String runId) throws Exception {
        List<String> command = List.of(runtime.name(), "ps", "-a", "--filter",
                "label=" + ContainerRuntime.RUN_LABEL + "=" + runId, "-q");
        Path output = Files.createTempFile("traceworks-ps-", ".txt");
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(output.toFile()).start();
            process.getOutputStream().close();
            boolean ended = process.waitFor(PS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!ended) {
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
            String printed = Files.readString(output, StandardCharsets.UTF_8);
            assertThat(ended).as("%s ended within %d s: %s", command, PS_TIMEOUT_SECONDS, printed).isTrue();
            assertThat(process.exitValue()).as("%s: %s", command, printed).isZero();
            return printed.strip();
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
