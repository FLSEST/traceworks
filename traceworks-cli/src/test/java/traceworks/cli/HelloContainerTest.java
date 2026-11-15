package traceworks.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.adapters.container.CliContainerRuntime;
import traceworks.cli.ContainerRuns.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.stream.Stream;

import static traceworks.cli.ContainerRuns.RUN_ID;
import static traceworks.cli.ContainerRuns.exec;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shipped {@code examples/hello} package as a container run, the default, through the real {@code podman} or
 * {@code docker} client: park, approve, resume and verify, without {@code --allow-host-execution}. Every step runs in
 * the built-in {@code shell} image. Tagged {@code container}: it runs with {@code mvn -Pcontainers verify}, and fails
 * there when no runtime works or the {@code shell} image is not built ({@code traceworks images build}). Prints
 * nothing, and leaves no container behind.
 */
@Tag("container")
class HelloContainerTest {

    /** The module directory is the working directory of Surefire. */
    private static final String PACKAGE = Path.of("../examples/hello").toAbsolutePath().normalize().toString();

    @TempDir
    static Path probeHome;
    private static CliContainerRuntime runtime;

    @TempDir
    Path temp;
    private final List<String> runIds = new ArrayList<>();

    @BeforeAll
    static void runtimeAndShellImage() {
        runtime = ContainerRuns.runtimeWithShellImage(probeHome);
    }

    @AfterEach
    void noContainerOfTheRunIsLeft() throws Exception {
        for (String id : runIds) {
            try {
                assertThat(ContainerRuns.containersOf(runtime, id)).as("containers of run %s left behind", id)
                        .isEmpty();
            } finally {
                runtime.removeLeftovers(id);
            }
        }
    }

    @Test
    void parkApproveResumeVerify_inContainers() throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        Path work = Files.createDirectories(temp.resolve("work"));
        Files.writeString(work.resolve("one.txt"), "1");
        Files.writeString(work.resolve("two.txt"), "2");

        Result run = exec(home, work, "run", PACKAGE, "--no-input");
        assertThat(run.code()).as(run.out() + run.err()).isEqualTo(5);
        Matcher m = RUN_ID.matcher(run.out());
        assertThat(m.find()).as(run.out()).isTrue();
        String id = m.group(1);
        runIds.add(id);
        assertThat(run.out()).contains("Hello, world", "Files in the workspace: 2");

        Result approve = exec(home, work, "approve", id, "--answer", "Yes");
        assertThat(approve.code()).as(approve.out() + approve.err()).isZero();
        Result resume = exec(home, work, "resume", id, "--no-input");
        assertThat(resume.code()).as(resume.out() + resume.err()).isZero();
        assertThat(resume.out()).contains("Published: Hello, world");
        Result verify = exec(home, work, "verify", id);
        assertThat(verify.code()).as(verify.out() + verify.err()).isZero();

        Path folder;
        try (Stream<Path> days = Files.walk(home.resolve("runs"), 2)) {
            folder = days.filter(p -> p.getFileName().toString().equals(id)).findFirst().orElseThrow();
        }
        ObjectMapper json = new ObjectMapper();
        List<JsonNode> events = new ArrayList<>();
        for (String line : Files.readAllLines(folder.resolve("events.jsonl"))) {
            events.add(json.readTree(line));
        }
        JsonNode started = events.getFirst();
        assertThat(started.path("execution").asText()).isEqualTo("container");
        assertThat(started.path("container_runtime").path("name").asText()).isEqualTo(runtime.name());
        List<JsonNode> commands = events.stream().filter(e -> e.path("type").asText().equals("step.command"))
                .toList();
        assertThat(commands).hasSize(3);
        for (JsonNode command : commands) {
            assertThat(command.path("image").asText()).isEqualTo("shell");
            assertThat(command.path("image_id").asText()).startsWith("sha256:");
            assertThat(command.path("host").asBoolean()).isFalse();
            assertThat(command.path("working_dir").asText()).isEqualTo("/workspace");
        }
        assertThat(events).filteredOn(e -> e.path("type").asText().equals("step.command.finished"))
                .allSatisfy(e -> assertThat(e.path("exit_code").asInt()).isZero());
        JsonNode manifest = json.readTree(folder.resolve("manifest.json").toFile());
        assertThat(manifest.path("execution").asText()).isEqualTo("container");
        assertThat(manifest.path("images")).hasSize(1);
        assertThat(manifest.path("images").get(0).path("reference").asText()).isEqualTo("shell");
        assertThat(manifest.path("images").get(0).path("id").asText())
                .isEqualTo(commands.getFirst().path("image_id").asText());
        assertThat(ContainerRuns.containersOf(runtime, id)).as("no container carries the run's label").isEmpty();
    }
}
