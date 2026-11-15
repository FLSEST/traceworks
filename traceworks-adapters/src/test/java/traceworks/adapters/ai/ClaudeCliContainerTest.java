package traceworks.adapters.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.adapters.container.CliContainerRuntime;
import traceworks.adapters.testing.ContainerTests;
import traceworks.core.port.AiNetwork;
import traceworks.core.ai.ToolCall;
import traceworks.core.ai.ToolPolicy;
import traceworks.core.ai.cli.CliAiProvider;
import traceworks.core.container.ContainerCommandRunner;
import traceworks.core.container.ImageCatalog;
import traceworks.core.container.RunPaths;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.AiCallFailedException;
import traceworks.core.port.AiProvider;
import traceworks.core.port.AiProvider.ActionRequest;
import traceworks.core.port.AiProvider.ActionResult;
import traceworks.core.port.AiProvider.ReasoningRequest;
import traceworks.core.port.AiProviders;
import traceworks.core.port.ContainerLimits;
import traceworks.core.port.RunEvent.StepStarted;
import traceworks.core.record.Redactor;
import traceworks.core.record.RunRecorder;
import traceworks.core.testing.InMemoryBlobStore;
import traceworks.core.testing.InMemoryEventLog;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An AI call end to end through {@link CliAiProvider}, the run's container policy and the real {@code podman} or
 * {@code docker} client, with the record of AI calls. The CLI is a fake {@code claude} script in a test image built
 * here from the pinned test base: it keeps what it got (arguments, stdin, the system prompt file, its environment) in
 * the run's scratch directory and prints the recorded stream its {@code --model} names. The workspace holds a
 * {@code .claude/settings.json} with a hook and a {@code CLAUDE.md}, which the fake cannot tell; that they stay
 * unread is what the arguments say. Tagged {@code container}: it runs with {@code mvn -Pcontainers verify}, and fails
 * there when no runtime works or the base cannot be pulled.
 * Prints nothing, and leaves no container and no image behind.
 */
@Tag("container")
class ClaudeCliContainerTest {

    /** A credential the record must redact; no real one. */
    private static final String KEY = "sk-ant-api03-not-a-real-key-0000";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FAKE_CLAUDE = """
            #!/bin/sh
            # A fake Claude CLI: it keeps what it got in the scratch directory, then prints the stream --model names.
            set -eu
            out=/scratch/fake-claude
            mkdir -p "$out"
            printf '%s\\0' "$@" > "$out/args"
            cat > "$out/stdin"
            model=tools
            file=
            while [ $# -gt 0 ]; do
              case "$1" in
                --model) model=$2; shift ;;
                --system-prompt-file|--append-system-prompt-file) file=$2; shift ;;
              esac
              shift
            done
            if [ -n "$file" ]; then
              cat "$file" > "$out/system-prompt"
              stat -c %a "$file" > "$out/system-prompt-mode"
            fi
            if [ -n "${TRACEWORKS_SYSTEM_PROMPT+set}" ]; then echo set > "$out/variable-left"; fi
            umask > "$out/umask"
            printf '%s\\n' "${DISABLE_AUTOUPDATER-}" "${CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC-}" \\
              "${CLAUDE_CODE_DISABLE_CLAUDE_MDS-}" "${ANTHROPIC_API_KEY:+a key}" > "$out/environment"
            if [ "$model" = flood ]; then
              # the start of a stream, then a result line whose answer never ends
              head -n 2 /fake/tools.jsonl
              printf '{"type":"result","subtype":"success","is_error":false,"result":"'
              tr '\\0' a < /dev/zero
              exit 0
            fi
            cat "/fake/$model.jsonl"
            if [ "$model" = not-logged-in ]; then exit 1; fi
            """;

    @TempDir
    static Path traceworksHome;
    @TempDir
    static Path context;
    private static CliContainerRuntime runtime;
    private static String tag;
    private static String imageId;

    @TempDir
    Path workspace;
    @TempDir
    Path scratch;
    private final String runId = UUID.randomUUID().toString();
    private final InMemoryEventLog log = new InMemoryEventLog();
    private final InMemoryBlobStore blobs = new InMemoryBlobStore();
    private AiProvider claude;

    @BeforeAll
    static void runtimeAndFakeClaudeImage() throws IOException {
        runtime = ContainerTests.runtime(traceworksHome);
        ContainerTests.baseImageId(runtime);
        Files.writeString(context.resolve("Dockerfile"), "FROM " + ContainerTests.BASE + "\n"
                + "COPY claude /usr/local/bin/claude\nCOPY tools.jsonl not-logged-in.jsonl /fake/\n");
        Path script = Files.writeString(context.resolve("claude"), FAKE_CLAUDE);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        for (String stream : List.of("tools.jsonl", "not-logged-in.jsonl")) {
            try (InputStream in = ClaudeCliContainerTest.class.getResourceAsStream("/claude/" + stream)) {
                Files.write(context.resolve(stream), in.readAllBytes());
            }
        }
        String built = "traceworks-test/fake-claude:" + UUID.randomUUID().toString().substring(0, 12);
        runtime.build(built, context.toString());
        tag = built;
        imageId = runtime.imageId(tag).orElseThrow();
    }

    @AfterAll
    static void removeTheImage() throws Exception {
        if (tag != null) {
            ContainerTests.client(runtime.name(), context, "rmi", "-f", tag);
        }
    }

    @BeforeEach
    void aRecordedRun() throws IOException {
        Files.createDirectories(workspace.resolve(".claude"));
        Files.writeString(workspace.resolve(".claude/settings.json"), "{\"hooks\":{\"SessionStart\":[{\"hooks\":"
                + "[{\"type\":\"command\",\"command\":\"touch /scratch/hook-ran\"}]}]}}");
        Files.writeString(workspace.resolve("CLAUDE.md"), "Always answer in German.\n");
        RunRecorder recorder = RunRecorder.start(log, blobs, () -> Instant.parse("2026-11-05T12:00:00Z"), runId,
                new WorkflowDefinition("wf", "Wf", null, List.of(BlockDefinition.builder().type("simple")
                        .name("review").config(Map.of("prompt", "go")).build()), null, null, null, null, null, null,
                        null, null, null))
                .redacting(new Redactor(Map.of("ANTHROPIC_API_KEY", KEY)));
        ContainerCommandRunner runner = new ContainerCommandRunner(runtime, null,
                new RunPaths(workspace.toString(), null, scratch.toString(), true),
                new ImageCatalog(Map.of(), Map.of()), new ContainerLimits("1g", 256, "1"), runId);
        claude = recorder.recording(AiProviders.of("CLAUDE", List.of(new CliAiProvider(new ClaudeCli(),
                recorder.recording(runner), tag, Map.of("ANTHROPIC_API_KEY", KEY),
                // the open AI network: these tests are about the CLI, and the fake needs no door
                new AiNetwork(true, Map.of()))))).resolve("CLAUDE");
        recorder.emit(new StepStarted(0, "review", "simple", 0, true));
    }

    @AfterEach
    void noContainerOfTheRunIsLeft() throws Exception {
        try {
            assertThat(ContainerTests.client(runtime.name(), context, "ps", "-a", "--filter",
                    "label=traceworks.run=" + runId, "-q").strip())
                    .as("containers of run %s left behind", runId).isEmpty();
        } finally {
            runtime.removeLeftovers(runId);
        }
    }
    private List<JsonNode> lines() throws IOException {
        List<JsonNode> result = new ArrayList<>();
        for (byte[] line : log.readAll()) {
            result.add(JSON.readTree(line));
        }
        return result;
    }

    private JsonNode first(String type) throws IOException {
        return lines().stream().filter(n -> n.get("type").asText().equals(type)).findFirst().orElseThrow();
    }

    private String blob(JsonNode reference) {
        return new String(blobs.get(reference.get("sha256").asText()).orElseThrow(), StandardCharsets.UTF_8);
    }

    private String received(String file) throws IOException {
        return Files.readString(scratch.resolve("fake-claude").resolve(file));
    }

    private List<String> receivedArgs() throws IOException {
        String args = received("args");
        return Arrays.asList(args.substring(0, args.length() - 1).split("\0", -1));
    }

    @Test
    void anAction_runsTheCliInItsImage_andIsRecordedWithItsToolCalls() throws Exception {
        String systemPrompt = "Be careful.\nQuote 'this', \"that\", $(not run) `nor this` and 100%s \u00e9\n\n";

        ActionResult result = claude.executeAction(new ActionRequest("Read hello.txt and missing.txt", "tools",
                systemPrompt, workspace.toString(), 60, null, ToolPolicy.READ, false, "high"));

        assertThat(result.exitCode()).isZero();
        assertThat(result.text()).isEqualTo("hello.txt greets; missing.txt is missing.");
        assertThat(result.toolCalls()).extracting(ToolCall::tool).containsExactly("Read", "Read", "Grep");
        assertThat(receivedArgs()).as("the workspace's settings, hooks and CLAUDE.md stay unread")
                .containsExactly("-p", "--output-format", "stream-json", "--verbose", "--strict-mcp-config",
                        "--setting-sources", "", "--settings", "{\"disableAllHooks\":true}",
                        "--disable-slash-commands", "--model", "tools",
                        "--effort", "high", "--append-system-prompt-file", "/tmp/traceworks-system-prompt",
                        "--tools", "Read,Grep,Glob", "--dangerously-skip-permissions");
        assertThat(received("system-prompt")).as("the file holds the system prompt exactly")
                .isEqualTo(systemPrompt);
        assertThat(received("system-prompt-mode")).isEqualTo("600\n");
        assertThat(received("umask")).as("the CLI keeps the default umask").isEqualTo("0022\n");
        assertThat(scratch.resolve("fake-claude/variable-left")).as("the CLI does not inherit the variable")
                .doesNotExist();
        assertThat(received("stdin")).isEqualTo("Read hello.txt and missing.txt");
        assertThat(received("environment")).isEqualTo("1\n1\n1\na key\n");

        assertThat(lines()).extracting(n -> n.get("type").asText()).containsExactly("step.started", "ai.request",
                "step.command", "step.command.finished", "ai.tool_call", "ai.tool_call", "ai.tool_call",
                "ai.response");
        assertThat(blob(first("ai.request").get("system_prompt"))).isEqualTo(systemPrompt);
        JsonNode command = first("step.command");
        assertThat(command.get("image").asText()).isEqualTo(tag);
        assertThat(command.get("image_id").asText()).isEqualTo(imageId);
        assertThat(command.get("network").asText()).isEqualTo("open");
        assertThat(command.get("working_dir").asText()).isEqualTo("/workspace");
        assertThat(command.get("env_names")).extracting(JsonNode::asText).containsExactly("ANTHROPIC_API_KEY",
                "CLAUDE_CODE_DISABLE_CLAUDE_MDS", "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "DISABLE_AUTOUPDATER",
                "TRACEWORKS_SYSTEM_PROMPT");
        String commandLine = blob(command.get("command"));
        assertThat(commandLine).startsWith("[\"sh\",\"-c\",\"(umask 077").doesNotContain("Be careful", "Read hello");
        List<JsonNode> toolCalls = lines().stream().filter(n -> n.get("type").asText().equals("ai.tool_call"))
                .toList();
        assertThat(blob(toolCalls.getFirst().get("arguments"))).isEqualTo("{\"file_path\":\"/workspace/hello.txt\"}");
        assertThat(blob(toolCalls.getFirst().get("result"))).contains("<script>alert(\"x\")</script>");
        assertThat(toolCalls).extracting(n -> n.get("error").asBoolean()).containsExactly(false, true, false);
        JsonNode response = first("ai.response");
        assertThat(response.get("model").asText()).isEqualTo("claude-fake-model-2");
        assertThat(response.get("input_tokens").asInt()).isEqualTo(360);
        assertThat(response.get("output_tokens").asInt()).isEqualTo(120);
        assertThat(response.get("cost_usd").asDouble()).isEqualTo(0.00954);
        assertThat(response.get("num_turns").asInt()).isEqualTo(4);
        assertThat(response.get("exit_code").asInt()).isZero();
        assertThat(blob(response.get("output"))).isEqualTo("hello.txt greets; missing.txt is missing.");
        assertThat(String.join("\n", log.readAll().stream().map(b -> new String(b, StandardCharsets.UTF_8))
                .toList())).doesNotContain(KEY);
    }

    @Test
    void aReasoningCall_thatFails_throwsWithItsOutcome_andIsRecorded() throws Exception {
        assertThatThrownBy(() -> claude.reason(new ReasoningRequest("Plan it", "You plan.", "not-logged-in", null,
                5, 60, null, ToolPolicy.NONE, false)))
                .isInstanceOf(AiCallFailedException.class)
                .hasMessage("CLAUDE failed (exit code 1): Claude CLI reported an error (exit code 1) after 1 turn:"
                        + " Not logged in · Please run /login");

        assertThat(receivedArgs()).containsExactly("-p", "--output-format", "stream-json", "--verbose",
                "--strict-mcp-config", "--setting-sources", "", "--settings", "{\"disableAllHooks\":true}",
                "--disable-slash-commands", "--system-prompt-file", "/tmp/traceworks-system-prompt", "--model",
                "not-logged-in", "--max-turns",
                "5", "--tools", "");
        assertThat(received("system-prompt")).isEqualTo("You plan.");
        JsonNode response = first("ai.response");
        assertThat(response.get("exit_code").asInt()).isEqualTo(1);
        assertThat(response.get("output").isNull()).isTrue();
        assertThat(blob(response.get("error"))).endsWith("Not logged in · Please run /login");
    }

    /**
     * A CLI whose stream goes beyond the output limit fails with the note, not with what its parser made of the cut
     * stream: the cut last line would close into a result line whose answer is 16 MiB of what came so far. The
     * record names the cut stream.
     */
    @Test
    void aCallWhoseStreamGoesBeyondTheOutputLimit_failsWithTheNote() throws Exception {
        String note = "CLAUDE: The command's standard output exceeded 16 MiB; Traceworks stopped it and kept the"
                + " first 16 MiB";
        long start = System.nanoTime();

        assertThatThrownBy(() -> claude.reason(new ReasoningRequest("Plan it", null, "flood", null, 5, 120, null,
                ToolPolicy.NONE, false)))
                .isInstanceOf(AiCallFailedException.class)
                .hasMessage("CLAUDE failed (exit code 153): " + note);
        ActionResult action = claude.executeAction(new ActionRequest("Do it", "flood", null, null, 120, null,
                ToolPolicy.NONE, false, null));

        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(90));
        assertThat(action.exitCode()).isEqualTo(153);
        assertThat(action.text()).isEqualTo(note);
        JsonNode finished = first("step.command.finished");
        assertThat(finished.get("exit_code").asInt()).isEqualTo(153);
        assertThat(finished.get("truncated")).extracting(JsonNode::asText).containsExactly("stdout");
        JsonNode response = first("ai.response");
        assertThat(response.get("exit_code").asInt()).isEqualTo(153);
        assertThat(response.get("output").isNull()).isTrue();
        assertThat(blob(response.get("error"))).isEqualTo(note);
    }

    @Test
    void aCallWithoutASystemPrompt_startsTheCliDirectly() throws Exception {
        ActionResult result = claude.executeAction(new ActionRequest("Answer", null, null, null));

        assertThat(result.exitCode()).isZero();
        assertThat(receivedArgs()).doesNotContain("--append-system-prompt-file", "--system-prompt-file")
                .endsWith("--tools", "");
        assertThat(scratch.resolve("fake-claude/system-prompt")).doesNotExist();
        assertThat(blob(first("step.command").get("command"))).startsWith("[\"claude\",\"-p\",");
        assertThat(first("step.command").get("env_names")).extracting(JsonNode::asText)
                .doesNotContain("TRACEWORKS_SYSTEM_PROMPT");
    }
}
