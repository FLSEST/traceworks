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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.stream.Stream;

import static traceworks.cli.ContainerRuns.RUN_ID;
import static traceworks.cli.ContainerRuns.exec;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A secret through a whole container run, through the real {@code podman} or {@code docker} client and the built-in
 * {@code shell} image: a step that prints its secret, writes it into the scratch directory and fails with it in its
 * error leaves the value nowhere in the run folder but the scratch directory (which is no part of the record), and
 * nowhere in what {@code run}, {@code show}, {@code watch}, {@code render} and {@code verify} print. Tagged
 * {@code container}: it runs with {@code mvn -Pcontainers verify}, and fails there when no runtime works or the
 * {@code shell} image is not built ({@code traceworks images build}). Prints nothing, and leaves no container behind.
 */
@Tag("container")
class SecretEndToEndTest {

    /** A dummy value, never a real credential. */
    private static final String SECRET = "tw-e2e-dummy-Q7mX4pZ9vK2r";
    private static final String NAME = "DEPLOY_TOKEN";

    @TempDir
    static Path probeHome;
    private static CliContainerRuntime runtime;

    @TempDir
    Path temp;
    private String runId;

    @BeforeAll
    static void runtimeAndShellImage() {
        runtime = ContainerRuns.runtimeWithShellImage(probeHome);
    }

    @AfterEach
    void noContainerOfTheRunIsLeft() throws Exception {
        if (runId == null) return;
        try {
            assertThat(ContainerRuns.containersOf(runtime, runId)).as("containers of run %s left behind", runId)
                    .isEmpty();
        } finally {
            runtime.removeLeftovers(runId);
        }
    }

    @Test
    void noSecretReachesTheRecord_orTheTerminal() throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        Path work = Files.createDirectories(temp.resolve("work"));
        Path credentials = home.resolve("credentials.json");
        Files.writeString(credentials, "{\"" + NAME + "\": \"" + SECRET + "\"}");
        Files.setPosixFilePermissions(credentials, PosixFilePermissions.fromString("rw-------"));
        Path pkg = Files.createDirectories(temp.resolve("leak"));
        Files.writeString(pkg.resolve("workflow.json"), """
                {
                  "format": "traceworks-workflow",
                  "formatVersion": 1,
                  "id": "5f0c2a8e-3d41-4b6f-9e27-8a1c0d4b7e63",
                  "name": "Leak",
                  "blocks": [
                    {
                      "type": "shell",
                      "name": "leak",
                      "config": {
                        "command": "echo \\"the token is $DEPLOY_TOKEN\\"; printf '%s\\\\n' \\"$DEPLOY_TOKEN\\" > /scratch/out.txt; echo \\"cannot deploy with $DEPLOY_TOKEN\\" >&2; exit 3",
                        "secrets": ["DEPLOY_TOKEN"]
                      }
                    }
                  ]
                }
                """);

        Result run = exec(home, work, "run", pkg.toString(), "--no-input");
        Matcher m = RUN_ID.matcher(run.out());
        assertThat(m.find()).as(run.both()).isTrue();
        runId = m.group(1);
        assertThat(run.code()).as(run.both()).isEqualTo(ExitCodes.FAILED);

        Path folder;
        try (Stream<Path> days = Files.walk(home.resolve("runs"), 2)) {
            folder = days.filter(p -> p.getFileName().toString().equals(runId)).findFirst().orElseThrow();
        }
        // The step did see the value: it is in the scratch file, which is no part of the record
        assertThat(Files.readString(folder.resolve("scratch/out.txt"))).isEqualTo(SECRET + "\n");

        Result show = exec(home, work, "show", runId, "--outputs", "--full");
        Result watch = exec(home, work, "watch", runId);
        Result render = exec(home, work, "render", runId);
        Result verify = exec(home, work, "verify", runId);
        assertThat(watch.code()).as(watch.both()).isEqualTo(ExitCodes.FAILED);
        assertThat(render.code()).as(render.both()).isZero();
        assertThat(verify.code()).as(verify.both()).isZero();

        for (Result printed : List.of(run, show, watch, render, verify)) {
            assertThat(printed.both()).doesNotContain(SECRET);
        }
        assertThat(filesHolding(folder, SECRET.getBytes(StandardCharsets.UTF_8)))
                .as("files of the run folder (scratch excluded) that hold the secret").isEmpty();

        // What the step printed and failed with is in the record, with the value replaced
        String marker = "[redacted:" + NAME + "]";
        assertThat(show.out()).contains("the token is " + marker, "cannot deploy with " + marker);
        assertThat(watch.out()).contains("the token is " + marker, "cannot deploy with " + marker);
        ObjectMapper json = new ObjectMapper();
        List<JsonNode> events = new ArrayList<>();
        for (String line : Files.readAllLines(folder.resolve("events.jsonl"))) {
            events.add(json.readTree(line));
        }
        JsonNode finished = events.getLast();
        assertThat(finished.path("type").asText()).isEqualTo("run.finished");
        assertThat(finished.path("status").asText()).isEqualTo("FAILED");
        assertThat(blob(folder, finished.path("error"))).contains(marker);
        JsonNode command = events.stream().filter(e -> e.path("type").asText().equals("step.command")).findFirst()
                .orElseThrow();
        assertThat(command.path("host").asBoolean()).isFalse();
        assertThat(command.path("env_names")).extracting(JsonNode::asText).containsExactly(NAME);
        JsonNode files = events.stream().filter(e -> e.path("type").asText().equals("step.files")).findFirst()
                .orElseThrow();
        assertThat(files.path("files")).hasSize(1);
        JsonNode out = files.path("files").get(0);
        assertThat(out.path("path").asText()).isEqualTo("out.txt");
        assertThat(out.path("blob").isNull()).isTrue();
        assertThat(out.path("sha256").isNull()).isTrue();
        assertThat(out.path("reason").asText()).isEqualTo("contains a secret");

        assertThat(ContainerRuns.containersOf(runtime, runId)).as("no container carries the run's label").isEmpty();
    }

    /** What {@code grep -rlF <value> <folder> --exclude-dir=scratch} lists: every file outside scratch that holds it. */
    private static List<Path> filesHolding(Path folder, byte[] value) throws Exception {
        Path scratch = folder.resolve("scratch");
        List<Path> holding = new ArrayList<>();
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.filter(p -> !p.startsWith(scratch)).filter(Files::isRegularFile).toList()) {
                if (indexOf(Files.readAllBytes(file), value) >= 0) {
                    holding.add(folder.relativize(file));
                }
            }
        }
        return holding;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            if (Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)) return i;
        }
        return -1;
    }

    private static String blob(Path folder, JsonNode reference) throws Exception {
        return Files.readString(folder.resolve("blobs").resolve(reference.path("sha256").asText()));
    }
}
