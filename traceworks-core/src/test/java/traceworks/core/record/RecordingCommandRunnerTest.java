package traceworks.core.record;

import traceworks.core.port.CommandRunner.Connection;
import traceworks.core.port.Egress;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.container.ContainerCommandRunner;
import traceworks.core.port.ContainerLimits;
import traceworks.core.container.ImageCatalog;
import traceworks.core.container.RunPaths;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.Clock;
import traceworks.core.port.CommandRunner;
import traceworks.core.port.CommandRunner.CommandFacts;
import traceworks.core.port.CommandRunner.CommandPlan;
import traceworks.core.port.CommandRunner.CommandRequest;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.CommandRunner.Mount;
import traceworks.core.port.RunEvent.StepStarted;
import traceworks.core.testing.FakeCommandRunner;
import traceworks.core.testing.FakeContainerRuntime;
import traceworks.core.testing.InMemoryBlobStore;
import traceworks.core.testing.InMemoryEventLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The command runner of the record: {@code step.command} carries the facts the run's runner planned (image, image ID,
 * effective mounts, network, environment names, host), written before the command runs; {@code step.command.finished}
 * follows once it has ended.
 */
class RecordingCommandRunnerTest {

    private static final String RUN = "00000000-0000-0000-0000-000000000001";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final InMemoryEventLog log = new InMemoryEventLog();
    private final InMemoryBlobStore blobs = new InMemoryBlobStore();
    private final Clock clock = () -> Instant.parse("2026-11-04T12:00:00Z");
    private final RunRecorder recorder = RunRecorder.start(log, blobs, clock, RUN, new WorkflowDefinition("wf", "Wf",
            null, List.of(BlockDefinition.builder().type("shell").name("build").config(Map.of("command", "make"))
            .build()), null, null, null, null, null, null, null, null, null));

    @BeforeEach
    void inAStep() {
        recorder.emit(new StepStarted(0, "build", "shell", 0, false));
    }

    private List<JsonNode> lines() throws Exception {
        List<JsonNode> result = new ArrayList<>();
        for (byte[] line : log.readAll()) {
            result.add(JSON.readTree(line));
        }
        return result;
    }

    private List<JsonNode> ofType(String type) throws Exception {
        return lines().stream().filter(n -> n.get("type").asText().equals(type)).toList();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private String text(JsonNode blobNode) {
        return new String(blobs.get(blobNode.get("sha256").asText()).orElseThrow(), StandardCharsets.UTF_8);
    }

    /** The recording wrapper answers as the runner it wraps, so a step sees the reference that is really run. */
    @Test
    void imageReference_isTheDelegates() {
        CommandRunner resolving = new CommandRunner() {
            @Override
            public CommandResult run(CommandRequest request) {
                return new CommandResult(0, "", "", 1, false);
            }

            @Override
            public String imageReference(String image) {
                return "resolved/" + image;
            }
        };
        CommandRunner plain = request -> new CommandResult(0, "", "", 1, false);

        assertThat(recorder.recording(resolving).imageReference("./images/x")).isEqualTo("resolved/./images/x");
        assertThat(recorder.recording(plain).imageReference("img:1")).isEqualTo("img:1");
    }

    @Test
    void recordingRunner_recordsThePlannedFacts() throws Exception {
        CommandFacts facts = new CommandFacts("/workspace", "shell", "sha256:abc", List.of(
                new Mount("/w", "/workspace", true), new Mount("/s", "/scratch", false)), true, List.of("A", "B"),
                false);
        AtomicInteger linesWhenItRan = new AtomicInteger(-1);
        CommandRunner planned = new CommandRunner() {
            @Override
            public CommandResult run(CommandRequest request) {
                throw new AssertionError("the recorder runs the plan, not the request");
            }

            @Override
            public CommandPlan plan(CommandRequest request) {
                return new CommandPlan(facts, () -> {
                    linesWhenItRan.set(log.readAll().size());
                    return new CommandResult(2, "out", "", 9, false);
                });
            }
        };

        CommandResult result = recorder.recording(planned).run(new CommandRequest("make", "/host/w", 30,
                Map.of("B", "secret-b", "A", "secret-a"), null, null, false));

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(linesWhenItRan.get()).as("step.started and step.command were written before it ran").isEqualTo(2);
        JsonNode command = ofType("step.command").getFirst();
        assertThat(fieldNames(command)).as("the order of docs/events.md").containsExactly("seq", "at", "type", "run",
                "prev", "step", "item", "command", "working_dir", "image", "image_id", "mounts", "network",
                "env_names", "host");
        assertThat(text(command.get("command"))).isEqualTo("make");
        assertThat(command.get("working_dir").asText()).as("the planned one").isEqualTo("/workspace");
        assertThat(command.get("image").asText()).isEqualTo("shell");
        assertThat(command.get("image_id").asText()).isEqualTo("sha256:abc");
        assertThat(command.get("mounts")).isEqualTo(JSON.readTree("[{\"host\":\"/w\",\"container\":\"/workspace\","
                + "\"read_only\":true},{\"host\":\"/s\",\"container\":\"/scratch\",\"read_only\":false}]"));
        assertThat(command.get("network").asText()).isEqualTo("open");
        assertThat(JSON.convertValue(command.get("env_names"), List.class)).containsExactly("A", "B");
        assertThat(command.get("host").asBoolean()).isFalse();
        assertThat(new String(log.readAll().get(1), StandardCharsets.UTF_8)).doesNotContain("secret-");
        JsonNode finished = ofType("step.command.finished").getFirst();
        assertThat(fieldNames(finished)).as("the order of docs/events.md").containsExactly("seq", "at", "type", "run",
                "prev", "step", "item", "exit_code", "duration_ms", "timed_out", "truncated");
        assertThat(finished.get("exit_code").asInt()).isEqualTo(2);
        assertThat(finished.get("duration_ms").asLong()).isEqualTo(9);
        assertThat(finished.get("step").asInt()).isZero();
        assertThat(finished.get("truncated")).as("the output was whole").isEmpty();
    }

    /**
     * A command that connects to listed hosts only: the command names them, and its end names every connection its
     * container asked the egress proxy for, by target and verdict, an empty list when it asked for none.
     */
    @Test
    void listedHosts_areInTheCommand_andTheConnectionsInItsEnd() throws Exception {
        Egress hosts = Egress.hosts(List.of("b.example:443", "api.example:443"));
        CommandRunner proxied = new CommandRunner() {
            private int calls;

            @Override
            public CommandResult run(CommandRequest request) {
                CommandResult result = new CommandResult(0, "out", "", 9, false);
                return calls++ > 0 ? result : result.withConnections(List.of(
                        new Connection("api.example:443", Connection.ALLOW, 3),
                        new Connection("evil.example:443", Connection.DENY, 1)));
            }
        };

        CommandResult result = recorder.recording(proxied).run(CommandRequest.entrypoint("img", List.of("call"),
                "/w", 30, null, null, false).withNetwork(hosts));
        recorder.recording(proxied).run(CommandRequest.entrypoint("img", List.of("call"), "/w", 30, null, null,
                false).withNetwork(hosts));

        assertThat(result.connections()).as("the caller gets them too").hasSize(2);
        JsonNode command = ofType("step.command").getFirst();
        assertThat(fieldNames(command)).as("the order of docs/events.md").containsSubsequence("mounts", "network",
                "network_hosts", "env_names", "host");
        assertThat(command.get("network").asText()).isEqualTo("hosts");
        assertThat(command.get("network_hosts")).isEqualTo(JSON.readTree("[\"api.example:443\",\"b.example:443\"]"));
        JsonNode finished = ofType("step.command.finished").getFirst();
        assertThat(fieldNames(finished)).as("the order of docs/events.md").containsSubsequence("timed_out",
                "truncated", "egress");
        assertThat(finished.get("egress")).isEqualTo(JSON.readTree("[{\"target\":\"api.example:443\","
                + "\"verdict\":\"ALLOW\",\"count\":3},{\"target\":\"evil.example:443\",\"verdict\":\"DENY\","
                + "\"count\":1}]"));
        assertThat(ofType("step.command.finished").get(1).get("egress")).as("asked for nothing").isEmpty();
    }

    /** A command with open network or none has no hosts and no connections to name. */
    @Test
    void openAndNoNetwork_nameNoHostsAndNoConnections() throws Exception {
        CommandRunner plain = request -> new CommandResult(0, "", "", 1, false);

        recorder.recording(plain).run(new CommandRequest("a", "/w", 30, null, null, null, false));
        recorder.recording(plain).run(new CommandRequest("b", "/w", 30, null, null, null, true));

        assertThat(ofType("step.command")).extracting(c -> c.get("network").asText()).containsExactly("none", "open");
        assertThat(ofType("step.command")).noneMatch(c -> c.has("network_hosts"));
        assertThat(ofType("step.command.finished")).noneMatch(f -> f.has("egress"));
    }

    /**
     * The shell variables a command found set are recorded by name next to the command, whose text holds none of
     * them; a command without values has no such field.
     */
    @Test
    void theValuesOfACommand_areRecordedByName_nextToTheCommand() throws Exception {
        CommandRunner plain = request -> new CommandResult(0, "", "", 1, false);
        Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("REVIEW", "it's $(not run)\n");
        values.put("TITLE", "Review");

        recorder.recording(plain).run(new CommandRequest("printf '%s' \"$REVIEW\"", "/w", 30, null, null, null, false)
                .withValues(values));
        recorder.recording(plain).run(new CommandRequest("true", "/w", 30, null, null, null, false));

        JsonNode command = ofType("step.command").getFirst();
        assertThat(text(command.get("command"))).isEqualTo("printf '%s' \"$REVIEW\"");
        assertThat(text(command.get("values"))).isEqualTo("{\"REVIEW\":\"it's $(not run)\\n\",\"TITLE\":\"Review\"}");
        assertThat(CommandView.values(text(command.get("values")))).containsExactly(
                Map.entry("REVIEW", "it's $(not run)\n"), Map.entry("TITLE", "Review"));
        assertThat(ofType("step.command").get(1).has("values")).isFalse();
    }

    /** A command whose output went beyond the limit: its end names the cut streams, stdout before stderr. */
    @Test
    void aTruncatedOutput_isNamedInTheCommandsEnd() throws Exception {
        CommandRunner loud = request -> new CommandResult(153, "y", "n", 900, false, List.of("stdout", "stderr"));

        recorder.recording(loud).run(new CommandRequest("yes", "/w", 30, null, null, null, false));

        JsonNode finished = ofType("step.command.finished").getFirst();
        assertThat(finished.get("exit_code").asInt()).isEqualTo(153);
        assertThat(finished.get("timed_out").asBoolean()).isFalse();
        assertThat(finished.get("truncated")).isEqualTo(JSON.readTree("[\"stdout\",\"stderr\"]"));
    }

    /** A runner without a plan of its own runs on the host: the request's own facts, without an image ID. */
    @Test
    void aRunnerWithoutPlan_isRecordedWithTheRequestsFacts_onTheHost() throws Exception {
        recorder.recording(new FakeCommandRunner().otherwise(FakeCommandRunner.ok("")))
                .run(new CommandRequest("ls", "/host/w", 30, Map.of(), null, null, false));

        JsonNode command = ofType("step.command").getFirst();
        assertThat(command.get("working_dir").asText()).isEqualTo("/host/w");
        assertThat(command.get("image").isNull()).isTrue();
        assertThat(command.get("image_id").isNull()).isTrue();
        assertThat(command.get("mounts")).isEmpty();
        assertThat(command.get("host").asBoolean()).isTrue();
    }

    @Test
    void aPlannedRefusal_recordsItsFacts_andExit125() throws Exception {
        CommandRunner runner = recorder.recording(new ContainerCommandRunner(new FakeContainerRuntime(), null,
                new RunPaths("/w", "/p", "/s", true), new ImageCatalog(Map.of("shell", "traceworks/shell:0123456789ab"),
                Map.of()), ContainerLimits.DEFAULTS, RUN));

        CommandResult result = runner.run(new CommandRequest("ls", "/w", 30, null, "alpine:3", null, false));

        assertThat(result.exitCode()).isEqualTo(125);
        JsonNode command = ofType("step.command").getFirst();
        assertThat(command.get("image").asText()).isEqualTo("alpine:3");
        assertThat(command.get("image_id").isNull()).isTrue();
        assertThat(command.get("working_dir").asText()).isEqualTo("/workspace");
        assertThat(command.get("mounts")).hasSize(3);
        assertThat(command.get("host").asBoolean()).isFalse();
        assertThat(ofType("step.command.finished").getFirst().get("exit_code").asInt()).isEqualTo(125);
    }

    /** A plan that cannot be made records nothing; a command that throws leaves its step.command without an end. */
    @Test
    void aRunnerException_propagates() throws Exception {
        CommandRunner noPlan = new CommandRunner() {
            @Override
            public CommandResult run(CommandRequest request) {
                throw new AssertionError("not reached");
            }

            @Override
            public CommandPlan plan(CommandRequest request) {
                throw new IllegalStateException("no plan");
            }
        };
        CommandRequest request = new CommandRequest("ls", "/w", 30, null, null, null, false);

        assertThat(catchThrowable(() -> recorder.recording(noPlan).run(request))).hasMessage("no plan");
        assertThat(ofType("step.command")).isEmpty();

        CommandRunner throwing = request1 -> {
            throw new IllegalStateException("runtime gone");
        };
        assertThat(catchThrowable(() -> recorder.recording(throwing).run(request))).hasMessage("runtime gone");
        assertThat(ofType("step.command")).hasSize(1);
        assertThat(ofType("step.command.finished")).isEmpty();
    }

    /** A recording runner planned by another: its execution records, so the command is recorded once. */
    @Test
    void thePlanOfARecordingRunner_recordsWhenItIsExecuted() throws Exception {
        CommandRunner recording = recorder.recording(new FakeCommandRunner().otherwise(FakeCommandRunner.ok("")));

        CommandPlan plan = recording.plan(new CommandRequest("ls", "/w", 30, null, null, null, false));

        assertThat(plan.facts().host()).isTrue();
        assertThat(ofType("step.command")).as("nothing ran yet").isEmpty();
        plan.execution().get();
        assertThat(ofType("step.command")).hasSize(1);
        assertThat(ofType("step.command.finished")).hasSize(1);
    }
}
