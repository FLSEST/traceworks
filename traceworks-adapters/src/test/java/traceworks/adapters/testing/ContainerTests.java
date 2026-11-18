package traceworks.adapters.testing;

import traceworks.adapters.container.CliContainerRuntime;
import traceworks.adapters.container.RuntimeDetection;
import traceworks.adapters.images.BuiltInImages;
import traceworks.core.app.Images;
import traceworks.core.port.PackageImage;
import traceworks.core.testing.ContainerPrecondition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the container tests of the adapters share: the runtime of the machine, the pinned test base and the built-in
 * images, and the runtime's own client for what the adapter does not do itself (listing a run's containers, removing
 * a test image).
 *
 * <p>Every class that uses it starts real containers and carries {@code @Tag("container")}: the default build leaves
 * it out, and {@code mvn -Pcontainers verify} runs it. What the machine lacks then fails the test
 * ({@link ContainerPrecondition}); no container test skips in a build that asked for it.
 */
public final class ContainerTests {

    /** The test base every container test starts from, pinned by digest (global constraints). */
    public static final String BASE = "debian:bookworm-slim@sha256:"
            + "734a72b3d6142a71993b4bc580ca01d8ca687f77a2c422d96a961d3376b179bc";

    private ContainerTests() {
    }

    /**
     * The container runtime of this machine, as Traceworks detects it for the home {@code traceworksHome}; without
     * one the test fails, or is skipped outside a build that asked for the container tests.
     */
    public static CliContainerRuntime runtime(Path traceworksHome) {
        Optional<CliContainerRuntime> detected = RuntimeDetection.detect(null, traceworksHome);
        ContainerPrecondition.require(detected.isPresent(), "no container runtime works (podman or docker)",
                ContainerPrecondition.START_A_RUNTIME);
        return detected.get();
    }

    /**
     * The ID of {@link #BASE} in {@code runtime}, pulled when it is not there; a base that cannot be pulled fails
     * the test, or skips it outside a build that asked for the container tests.
     */
    public static String baseImageId(CliContainerRuntime runtime) {
        Optional<String> id = runtime.imageId(BASE);
        String fix = "connect to the network once, so that '" + runtime.name() + " pull " + BASE + "' works";
        if (id.isEmpty()) {
            try {
                runtime.pull(BASE);
            } catch (IOException e) {
                ContainerPrecondition.require(false, "the test base " + BASE + " cannot be pulled ("
                        + e.getMessage() + ")", fix);
            }
            id = runtime.imageId(BASE);
        }
        ContainerPrecondition.require(id.isPresent(), "the test base " + BASE + " is not present", fix);
        return id.get();
    }

    /**
     * The ID of the built-in image {@code name} ({@code shell}, {@code claude}, {@code pi}, {@code egress}) in
     * {@code runtime}, under the tag this checkout gives it; an image that is not built fails the test, or skips it
     * outside a build that asked for the container tests. No test builds a built-in image.
     */
    public static String builtInImageId(CliContainerRuntime runtime, String name) {
        PackageImage image = new BuiltInImages().all().stream().filter(i -> i.name().equals(name)).findFirst()
                .orElseThrow();
        String tag = Images.builtInTag(image);
        Optional<String> id = runtime.imageId(tag);
        ContainerPrecondition.require(id.isPresent(), "the built-in image " + name + " (" + tag + ") is not built",
                ContainerPrecondition.BUILD_THE_IMAGES);
        return id.get();
    }

    /**
     * Runs the client {@code executable} ({@code docker} or {@code podman}) with {@code arguments}, and returns what
     * it printed, stdout and stderr together. The output goes to a file in {@code dir}, so the wait of 60 s holds
     * whatever the client prints. Fails the test when the client does not end within it, or ends with an exit code
     * other than 0.
     */
    public static String client(String executable, Path dir, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(executable));
        command.addAll(List.of(arguments));
        Path output = Files.createTempFile(dir, "client", ".out");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile())
                .start();
        process.getOutputStream().close();
        boolean ended = process.waitFor(60, TimeUnit.SECONDS);
        if (!ended) {
            process.destroyForcibly();
        }
        String text = Files.readString(output);
        assertThat(ended).as("%s ended within 60 s", command).isTrue();
        assertThat(process.exitValue()).as("%s: %s", command, text).isZero();
        return text;
    }
}
