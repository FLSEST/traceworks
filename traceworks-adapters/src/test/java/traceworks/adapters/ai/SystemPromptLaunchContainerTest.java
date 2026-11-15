package traceworks.adapters.ai;

import traceworks.adapters.container.CliContainerRuntime;
import traceworks.adapters.testing.ContainerTests;
import traceworks.core.port.AiCli.AiInvocation;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.ContainerLimits;
import traceworks.core.port.ContainerRuntime.ContainerRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The system prompt launch script in the real built-in AI images, through each image's own entrypoint
 * ({@code docker-entrypoint.sh}), offline: it decodes the largest system prompt into its file with mode 600, the
 * command behind it keeps the default umask, and the CLI starts: {@code claude}, and {@code pi} behind its stream
 * filter, as {@link PiCli} starts it, with the {@code rg} and {@code fd} its {@code grep} and {@code find} tools run,
 * since Pi downloads neither. Tagged {@code container}: it runs with {@code mvn -Pcontainers verify}, and fails
 * there when no runtime works or an image is not built ({@code traceworks images build}). Prints nothing, and leaves
 * no container behind.
 */
@Tag("container")
class SystemPromptLaunchContainerTest {

    private static final ContainerLimits LIMITS = new ContainerLimits("1g", 256, "1");

    @TempDir
    static Path traceworksHome;
    private static CliContainerRuntime runtime;

    @TempDir
    Path dir;
    private final String runId = UUID.randomUUID().toString();

    @BeforeAll
    static void aRuntime() {
        runtime = ContainerTests.runtime(traceworksHome);
    }

    /**
     * The command behind the launch script, as the image's CLI adapter starts it, and the version lines it prints, as
     * patterns: the CLI's, and for Pi those of {@code rg} (Debian's) and {@code fd} (release 10.5.0, which the image's
     * Dockerfile pins; Pi itself would download the latest release when it may). Pi prints its version on stderr when
     * stdin is no terminal. The file ends without a line break, which the stream filter adds, so both commands add
     * one.
     */
    static Stream<Arguments> clis() {
        String file = SystemPromptLaunch.FILE;
        String claude = "umask; stat -c %a " + file + "; claude --version; cat " + file + "; echo";
        String pi = "umask; stat -c %a " + file + "; pi --version 2>&1; rg --version | head -n 1; fd --version; cat "
                + file + "; echo";
        return Stream.of(
                Arguments.of("claude", List.of("sh", "-c", claude), List.of("\\S+ \\(Claude Code\\)")),
                Arguments.of("pi", List.of("sh", "-c", PiCli.STREAM_FILTER, "sh", "sh", "-c", pi),
                        List.of("1\\.5\\.2", "ripgrep 13\\.0\\.0\\b.*", "fd 10\\.5\\.0")));
    }

    @AfterEach
    void noContainerOfTheRunIsLeft() throws Exception {
        try {
            assertThat(ContainerTests.client(runtime.name(), dir, "ps", "-a", "--filter",
                    "label=traceworks.run=" + runId, "-q").strip())
                    .as("containers of run %s left behind", runId).isEmpty();
        } finally {
            runtime.removeLeftovers(runId);
        }
    }
    @ParameterizedTest(name = "{0}")
    @MethodSource("clis")
    void theScript_decodesTheLargestSystemPrompt_andStartsTheCli_throughTheImagesEntrypoint(String image,
                                                                                            List<String> command,
                                                                                            List<String> versions) {
        String imageId = ContainerTests.builtInImageId(runtime, image);
        String line = "Quote 'this', \"that\", $(not run) `nor this`, 100%s \\ \u00e9 \u00f8 \ud83d\ude00\n";
        StringBuilder text = new StringBuilder();
        while (bytes(text + line) <= SystemPromptLaunch.LIMIT_BYTES) {
            text.append(line);
        }
        text.append("x".repeat(SystemPromptLaunch.LIMIT_BYTES - bytes(text.toString())));
        String prompt = text.toString();
        assertThat(bytes(prompt)).isEqualTo(SystemPromptLaunch.LIMIT_BYTES);
        AiInvocation invocation = SystemPromptLaunch.of(prompt).invocation(command, Map.of(), null);

        CommandResult result = runtime.run(new ContainerRun("traceworks-" + runId + "-1", runId, imageId, null,
                invocation.args(), null, List.of(), null, false, invocation.environment(), null, 120, LIMITS));

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        String[] parts = result.stdout().split("\n", 3 + versions.size());
        assertThat(parts[0]).as("the command behind the script keeps the default umask").isEqualTo("0022");
        assertThat(parts[1]).as("the file's mode").isEqualTo("600");
        for (int i = 0; i < versions.size(); i++) {
            assertThat(parts[2 + i]).as("version line %d", i + 1).matches(versions.get(i));
        }
        assertThat(parts[2 + versions.size()]).as("the file holds the prompt byte for byte")
                .isEqualTo(prompt + "\n");
    }

    private static int bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
