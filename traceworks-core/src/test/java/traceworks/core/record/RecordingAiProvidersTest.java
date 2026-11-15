package traceworks.core.record;

import traceworks.core.port.WebAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.ai.cli.CliAiProvider;
import traceworks.core.ai.AiResponseMetadata;
import traceworks.core.ai.ToolCall;
import traceworks.core.ai.ToolPolicy;
import traceworks.core.engine.EventLogException;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.AiCallFailedException;
import traceworks.core.port.AiCli.AiOutcome;
import traceworks.core.port.AiProvider;
import traceworks.core.port.AiProvider.ActionRequest;
import traceworks.core.port.AiProvider.ReasoningRequest;
import traceworks.core.port.AiProvider.ReasoningResult;
import traceworks.core.port.AiProviders;
import traceworks.core.port.Clock;
import traceworks.core.port.CommandRunner;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.RunEvent.ForEachItemStarted;
import traceworks.core.port.RunEvent.StepStarted;
import traceworks.core.testing.FakeAiCli;
import traceworks.core.testing.InMemoryBlobStore;
import traceworks.core.testing.InMemoryEventLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The record wraps the run's AI providers. Per call it writes {@code ai.request}, the CLI's container
 * command ({@code step.command}, {@code step.command.finished}), one {@code ai.tool_call} per tool call and
 * {@code ai.response}, attributed like a command.
 */
class RecordingAiProvidersTest {

    private static final String RUN = "00000000-0000-0000-0000-000000000001";
    private static final String TAG = "traceworks/claude:a73397fba910";
    private static final String KEY = "sk-ant-api03-Abc_def-123";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final InMemoryEventLog log = new InMemoryEventLog();
    private final InMemoryBlobStore blobs = new InMemoryBlobStore();
    private final Clock clock = () -> Instant.parse("2026-11-04T12:00:00Z");
    private final RunRecorder recorder = RunRecorder.start(log, blobs, clock, RUN, new WorkflowDefinition("wf", "Wf",
            null, List.of(BlockDefinition.builder().type("simple").name("review").config(Map.of("command", "go"))
            .build()), null, null, null, null, null, null, null, null, null))
            .redacting(new Redactor(Map.of("ANTHROPIC_API_KEY", KEY)));
    private final FakeAiCli cli = FakeAiCli.claude();
    private final CommandRunner runner = request -> new CommandResult(0, "stream", "", 7, false);
    private final AiProviders providers = recorder.recording(AiProviders.of("CLAUDE", List.of(
            new CliAiProvider(cli, recorder.recording(runner), TAG, Map.of("ANTHROPIC_API_KEY", KEY)))));

    @BeforeEach
    void inAStep() {
        recorder.emit(new StepStarted(0, "review", "simple", 0, true));
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

    private String text(JsonNode blobNode) {
        return new String(blobs.get(blobNode.get("sha256").asText()).orElseThrow(), StandardCharsets.UTF_8);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void aiCall_recordsRequestCommandToolCallsAndResponse_inOrder() throws Exception {
        cli.answersAfter("looks fine", new ToolCall("Read", "{\"file_path\":\"src/A.java\"}", "class A {}", false),
                new ToolCall("Grep", "{\"pattern\":\"TODO\"}", "no match", true));

        AiProvider claude = providers.resolve("claude");
        claude.executeAction(new ActionRequest("Review src", "opus", "be brief", "/work", 90, null,
                ToolPolicy.READ, false, "high"));

        assertThat(lines()).extracting(n -> n.get("type").asText()).containsExactly("step.started", "ai.request",
                "step.command", "step.command.finished", "ai.tool_call", "ai.tool_call", "ai.response");
        JsonNode request = ofType("ai.request").getFirst();
        assertThat(fieldNames(request)).containsSubsequence("step", "item", "provider", "model", "effort", "tools",
                "prompt", "system_prompt");
        assertThat(request.get("step").asInt()).isZero();
        assertThat(request.get("item").isNull()).isTrue();
        assertThat(request.get("provider").asText()).isEqualTo("CLAUDE");
        assertThat(request.get("model").asText()).isEqualTo("opus");
        assertThat(request.get("effort").asText()).isEqualTo("high");
        assertThat(request.get("tools").asText()).isEqualTo("read");
        assertThat(request.get("prompt").isObject()).as("the prompt is a blob").isTrue();
        assertThat(text(request.get("prompt"))).isEqualTo("Review src");
        assertThat(text(request.get("system_prompt"))).isEqualTo("be brief");

        JsonNode command = ofType("step.command").getFirst();
        assertThat(command.get("step").asInt()).isZero();
        assertThat(command.get("image").asText()).isEqualTo(TAG);
        assertThat(command.get("network").asText()).isEqualTo("hosts");
        assertThat(command.get("network_hosts")).extracting(JsonNode::asText).containsExactly("api.claude.example:443");
        assertThat(command.get("env_names")).extracting(JsonNode::asText)
                .containsExactly("ANTHROPIC_API_KEY", "FAKE_CLI");

        List<JsonNode> toolCalls = ofType("ai.tool_call");
        assertThat(fieldNames(toolCalls.getFirst())).containsSubsequence("step", "item", "tool", "arguments", "result",
                "error");
        assertThat(toolCalls).extracting(n -> n.get("tool").asText()).containsExactly("Read", "Grep");
        assertThat(toolCalls.getFirst().get("arguments").isObject()).as("the arguments are a blob").isTrue();
        assertThat(text(toolCalls.getFirst().get("arguments"))).isEqualTo("{\"file_path\":\"src/A.java\"}");
        assertThat(text(toolCalls.getFirst().get("result"))).isEqualTo("class A {}");
        assertThat(toolCalls).extracting(n -> n.get("error").asBoolean()).containsExactly(false, true);

        JsonNode response = ofType("ai.response").getFirst();
        assertThat(fieldNames(response)).containsSubsequence("step", "item", "model", "input_tokens",
                "output_tokens", "cost_usd", "num_turns", "exit_code", "output", "error");
        assertThat(response.get("model").asText()).as("the model that answered").isEqualTo("fake-model");
        assertThat(response.get("input_tokens").asInt()).isEqualTo(100);
        assertThat(response.get("output_tokens").asInt()).isEqualTo(20);
        assertThat(response.get("cost_usd").asDouble()).isEqualTo(0.01);
        assertThat(response.get("num_turns").asInt()).isEqualTo(1);
        assertThat(response.get("exit_code").asInt()).isZero();
        assertThat(text(response.get("output"))).isEqualTo("looks fine");
        assertThat(response.get("error").isNull()).isTrue();
    }

    @Test
    void toolCallResult_isCutAt2000Characters() throws Exception {
        cli.answersAfter("ok", new ToolCall("Read", "{}", "x".repeat(5_000), false));

        providers.resolve("CLAUDE").executeAction(new ActionRequest("a", null, null, null));

        assertThat(text(ofType("ai.tool_call").getFirst().get("result"))).isEqualTo("x".repeat(2_000));
    }

    /** The cut never splits a secret value: it moves before the value, so the redactor finds what is left whole. */
    @Test
    void toolCallResultCut_keepsASecretWhole_andTheRecordRedactsIt() throws Exception {
        String result = "k".repeat(1_995) + KEY + "tail";
        cli.answersAfter("ok", new ToolCall("Read", "{\"key\":\"" + KEY + "\"}", result, false),
                new ToolCall("Read", "{}", "short " + KEY, false));

        providers.resolve("CLAUDE").executeAction(new ActionRequest("a", null, null, null));

        List<JsonNode> toolCalls = ofType("ai.tool_call");
        assertThat(text(toolCalls.get(0).get("result"))).isEqualTo("k".repeat(1_995));
        assertThat(text(toolCalls.get(0).get("arguments"))).isEqualTo("{\"key\":\"[redacted:ANTHROPIC_API_KEY]\"}");
        assertThat(text(toolCalls.get(1).get("result"))).isEqualTo("short [redacted:ANTHROPIC_API_KEY]");
        for (byte[] line : log.readAll()) {
            assertThat(new String(line, StandardCharsets.UTF_8)).doesNotContain(KEY);
        }
    }

    /** A cut that would split a surrogate pair keeps the pair out whole. */
    @Test
    void toolCallResultCut_neverSplitsACharacter() throws Exception {
        cli.answersAfter("ok", new ToolCall("Read", "{}", "a".repeat(1_999) + "😀" + "b", false));

        providers.resolve("CLAUDE").executeAction(new ActionRequest("a", null, null, null));

        assertThat(text(ofType("ai.tool_call").getFirst().get("result"))).isEqualTo("a".repeat(1_999));
    }

    @Test
    void reasonCall_isRecordedToo_withExitCodeZero() throws Exception {
        cli.answers("the plan");

        providers.resolve(null).reason(new ReasoningRequest("Think", null, null, null, 3, 60, null, ToolPolicy.WRITE,
                false));

        assertThat(ofType("ai.request").getFirst().get("tools").asText()).isEqualTo("write");
        assertThat(ofType("ai.request").getFirst().get("system_prompt").isNull()).isTrue();
        JsonNode response = ofType("ai.response").getFirst();
        assertThat(response.get("exit_code").asInt()).isZero();
        assertThat(text(response.get("output"))).isEqualTo("the plan");
    }

    /** A reasoning call that returned records its tool calls before its response, and hands back what came. */
    @Test
    void aReasonCall_recordsItsToolCalls_andReturnsTheProvidersResult() throws Exception {
        ToolCall read = new ToolCall("Read", "{\"file_path\":\"plan.md\"}", "the old plan", false);
        cli.answersAfter("the plan", read);

        ReasoningResult result = providers.resolve("CLAUDE").reason(new ReasoningRequest("Think"));

        assertThat(result.text()).isEqualTo("the plan");
        assertThat(result.toolCalls()).containsExactly(read);
        assertThat(lines()).extracting(n -> n.get("type").asText()).containsSubsequence("ai.request", "ai.tool_call",
                "ai.response");
        assertThat(ofType("ai.tool_call")).singleElement().satisfies(call -> {
            assertThat(call.get("tool").asText()).isEqualTo("Read");
            assertThat(text(call.get("result"))).isEqualTo("the old plan");
        });
    }

    /** What the recording does not record it passes on as its provider gives it. */
    @Test
    void theRecording_passesOnTheCapabilitiesAndTheConversationPromptOfItsProvider() {
        AiProvider plain = new CliAiProvider(FakeAiCli.claude(), runner, TAG, Map.of());
        AiProvider recording = providers.resolve("CLAUDE");
        List<traceworks.core.ai.ConversationPrompt.Turn> history = List.of(
                new traceworks.core.ai.ConversationPrompt.Turn("user", "Fix the build"));

        assertThat(recording.getCapabilities()).isEqualTo(plain.getCapabilities());
        assertThat(recording.buildConversationPrompt("Be brief.", history))
                .isEqualTo(plain.buildConversationPrompt("Be brief.", history)).contains("Fix the build");
    }

    /**
     * A line of the call that cannot be written is an event-log failure, which ends the run, not a failed call that a
     * block would turn into a failed step: before the call (its request) and after it (its response).
     */
    @Test
    void aLineOfTheCallThatCannotBeWritten_isAnEventLogFailure() {
        cli.answers("ok", "ok");
        log.failAfter(4);   // step.started, ai.request, step.command, step.command.finished; then ai.response fails

        assertThatThrownBy(() -> providers.resolve("CLAUDE").executeAction(new ActionRequest("a", null, null, null)))
                .as("the response").isInstanceOf(EventLogException.class);
        assertThatThrownBy(() -> providers.resolve("CLAUDE").executeAction(new ActionRequest("b", null, null, null)))
                .as("the request").isInstanceOf(EventLogException.class);
    }

    /** A call that fails is answered by the CLI's message: the response records it as the error, not as output. */
    @Test
    void failedAction_recordsItsMessageAsTheError() throws Exception {
        cli.fails(2, "usage limit");

        providers.resolve("CLAUDE").executeAction(new ActionRequest("a", null, null, null));

        JsonNode response = ofType("ai.response").getFirst();
        assertThat(response.get("exit_code").asInt()).isEqualTo(2);
        assertThat(response.get("output").isNull()).isTrue();
        assertThat(text(response.get("error"))).isEqualTo("usage limit");
        assertThat(response.get("model").isNull()).isTrue();
        assertThat(response.get("input_tokens").isNull()).isTrue();
    }

    /**
     * A failed reasoning call throws (the port's contract), and still is recorded whole: its tool calls, then a
     * response with its exit code, its usage and the CLI's message as the error. The exception reaches the caller.
     */
    @Test
    void failedReasonCall_recordsItsToolCallsExitCodeAndUsage_andRethrows() throws Exception {
        cli.then(new AiOutcome("rate limit reached", 2, true, FakeAiCli.usage(), List.of(
                new ToolCall("Read", "{\"file_path\":\"a\"}", "text", false),
                new ToolCall("Grep", "{}", "boom", true))));

        assertThatThrownBy(() -> providers.resolve("CLAUDE").reason(new ReasoningRequest("Think")))
                .isInstanceOf(AiCallFailedException.class)
                .hasMessage("CLAUDE failed (exit code 2): rate limit reached");

        assertThat(lines()).extracting(n -> n.get("type").asText()).containsExactly("step.started", "ai.request",
                "step.command", "step.command.finished", "ai.tool_call", "ai.tool_call", "ai.response");
        assertThat(ofType("ai.tool_call")).extracting(n -> n.get("tool").asText()).containsExactly("Read", "Grep");
        JsonNode response = ofType("ai.response").getFirst();
        assertThat(response.get("exit_code").asInt()).isEqualTo(2);
        assertThat(response.get("model").asText()).isEqualTo("fake-model");
        assertThat(response.get("input_tokens").asInt()).isEqualTo(100);
        assertThat(response.get("output").isNull()).isTrue();
        assertThat(text(response.get("error"))).isEqualTo("rate limit reached");
    }

    /** A reasoning call that ran out of time keeps the tool calls its CLI reported before the end. */
    @Test
    void timedOutReasonCall_recordsItsPartialToolCalls_andExitCode124() throws Exception {
        AiProviders slow = recorder.recording(AiProviders.of("CLAUDE", List.of(new CliAiProvider(cli,
                recorder.recording(request -> new CommandResult(124, "{\"partial\":", "", 60_000, true)), TAG,
                Map.of("ANTHROPIC_API_KEY", KEY)))));
        cli.then(new AiOutcome("half", 0, false, null, List.of(new ToolCall("Read", "{}", "x", false))));

        assertThatThrownBy(() -> slow.resolve("CLAUDE").reason(new ReasoningRequest("Think", null, null, null, null,
                60, null))).isInstanceOf(AiCallFailedException.class);

        assertThat(ofType("ai.tool_call")).hasSize(1);
        JsonNode response = ofType("ai.response").getFirst();
        assertThat(response.get("exit_code").asInt()).isEqualTo(124);
        assertThat(text(response.get("error"))).isEqualTo("CLAUDE timed out after 60 s");
    }

    /** A call that throws anything else ends with a response that names the error, without an exit code. */
    @Test
    void throwingCall_recordsTheErrorWithoutAnExitCode_andRethrows() throws Exception {
        AiProviders broken = recorder.recording(AiProviders.of("CLAUDE", List.of(new CliAiProvider(cli,
                request -> {
                    throw new IllegalStateException("runner broke");
                }, TAG, Map.of("ANTHROPIC_API_KEY", KEY)))));

        assertThatThrownBy(() -> broken.resolve("CLAUDE").reason(new ReasoningRequest("Think")))
                .isInstanceOf(IllegalStateException.class).hasMessage("runner broke");

        JsonNode response = ofType("ai.response").getFirst();
        assertThat(response.get("exit_code").isNull()).isTrue();
        assertThat(text(response.get("error"))).isEqualTo("runner broke");
        assertThat(ofType("ai.tool_call")).isEmpty();
    }

    /** An exception without a message still leaves a reason in the record: the kind of exception it was. */
    @Test
    void throwingCallWithoutAMessage_recordsTheKindOfExceptionAsTheError() throws Exception {
        AiProviders broken = recorder.recording(AiProviders.of("CLAUDE", List.of(new CliAiProvider(cli,
                request -> {
                    throw new IllegalStateException();
                }, TAG, Map.of("ANTHROPIC_API_KEY", KEY)))));

        assertThatThrownBy(() -> broken.resolve("CLAUDE").reason(new ReasoningRequest("Think")))
                .isInstanceOf(IllegalStateException.class);

        JsonNode response = ofType("ai.response").getFirst();
        assertThat(response.get("exit_code").isNull()).isTrue();
        assertThat(text(response.get("error"))).isEqualTo("IllegalStateException");
    }

    /** A value the cut would split is left out whole, also when it starts the text: nothing is left then. */
    @Test
    void cut_ofATextThatStartsWithAValueTheCutWouldSplit_isEmpty() {
        String whole = "abcdefgh-value";

        assertThat(RecordingAiProviders.cut(whole + " and what follows", 5, List.of(whole))).isEmpty();
        assertThat(RecordingAiProviders.cut("short", 5, List.of(whole))).isEqualTo("short");
        assertThat(RecordingAiProviders.cut("plain text", 5, List.of(whole))).isEqualTo("plain");
    }

    /** A cut after the first half of a pair of surrogates leaves the whole pair out, also at the very start. */
    @Test
    void cut_rightAfterALeadingHighSurrogate_keepsNoHalfOfThePair() {
        assertThat(RecordingAiProviders.cut("😀 smile", 1, List.of())).isEmpty();
        assertThat(RecordingAiProviders.cut("a😀 smile", 2, List.of())).isEqualTo("a");
    }

    /** A model name with a secret across its cut at 256 characters is cut in front of the secret, not inside it. */
    @Test
    void aModelNameWithASecretAcrossItsCut_isCutInFrontOfTheSecret() throws Exception {
        cli.then(new AiOutcome("ok", 0, false, new AiResponseMetadata(1, 1, null, "m".repeat(250) + KEY + "m".repeat(100),
                false, null, null, null, List.of()), List.of()));

        providers.resolve("CLAUDE").executeAction(new ActionRequest("a", null, null, null));

        assertThat(ofType("ai.response").getFirst().get("model").asText()).isEqualTo("m".repeat(250));
    }

    /**
     * What a CLI reports is hostile input: a tool name or a model of megabytes stays at most 256 characters
     * inline, cut where no secret value is split; an argument of megabytes goes to a blob, never inline; a lone
     * surrogate in a tool's result breaks nothing.
     */
    @Test
    void hostileOutcome_isCappedInline_andRecorded() throws Exception {
        String hugeName = "T".repeat(250) + KEY + "T".repeat(3_000_000);
        String hugeArgument = "{\"a\":\"" + "x".repeat(3_000_000) + "\"}";
        cli.then(new AiOutcome("ok", 0, false, new AiResponseMetadata(1, 1, null, "m".repeat(2_000_000), false, null,
                null, null, List.of()), List.of(new ToolCall(hugeName, hugeArgument, "half \uD800 a pair", false))));

        providers.resolve("CLAUDE").executeAction(new ActionRequest("a", null, null, null));

        JsonNode toolCall = ofType("ai.tool_call").getFirst();
        assertThat(toolCall.get("tool").asText()).isEqualTo("T".repeat(250));
        assertThat(toolCall.get("arguments").isObject()).as("a blob reference").isTrue();
        assertThat(toolCall.get("arguments").get("size").asLong()).isEqualTo(hugeArgument.length());
        assertThat(text(toolCall.get("result"))).isEqualTo("half ? a pair");
        assertThat(ofType("ai.response").getFirst().get("model").asText()).isEqualTo("m".repeat(256));
        for (byte[] line : log.readAll()) {
            assertThat(line.length).as("no line carries the megabytes").isLessThan(10_000);
        }
    }

    /** A call in a foreach item carries the item, as its command does. */
    @Test
    void callInAForEachItem_isAttributedToTheItem() throws Exception {
        recorder.emit(new ForEachItemStarted(0, 2, "b", 3));
        cli.answersAfter("ok", new ToolCall("Read", "{}", "r", false));

        providers.resolve("CLAUDE").executeAction(new ActionRequest("a", null, null, null));

        for (String type : List.of("ai.request", "step.command", "ai.tool_call", "ai.response")) {
            assertThat(ofType(type).getFirst().get("item").asInt()).as(type).isEqualTo(2);
        }
    }

    /** Finding a provider records nothing: only a call does. */
    @Test
    void resolve_recordsNothing_andKeepsTheLookupRules() throws Exception {
        AiProvider claude = providers.resolve("Claude");

        assertThat(claude.getId()).isEqualTo("CLAUDE");
        assertThat(providers.defaultProviderId()).isEqualTo("CLAUDE");
        assertThat(lines()).extracting(n -> n.get("type").asText()).containsExactly("step.started");
        assertThatThrownBy(() -> providers.resolve("GPT")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown AI provider 'GPT'");
    }

    /** The prompt that quotes a secret is redacted in its blob, and so is the answer. */
    @Test
    void promptAndAnswer_areRedacted() throws Exception {
        cli.answers("your key is " + KEY);

        providers.resolve("CLAUDE").executeAction(new ActionRequest("Is " + KEY + " valid?", null, null, null));

        assertThat(text(ofType("ai.request").getFirst().get("prompt")))
                .isEqualTo("Is [redacted:ANTHROPIC_API_KEY] valid?");
        assertThat(text(ofType("ai.response").getFirst().get("output")))
                .isEqualTo("your key is [redacted:ANTHROPIC_API_KEY]");
    }

    /** The request names the web mode next to the tools, and for fetch the hosts the step named. */
    @Test
    void aiRequest_namesTheWebMode_andTheHostsOfAFetch() throws Exception {
        cli.answers("a", "b", "c");
        AiProvider claude = providers.resolve("claude");
        ActionRequest plain = new ActionRequest("Ask", null, null, "/work", 90, null, ToolPolicy.NONE, false, null);

        claude.executeAction(plain);
        claude.executeAction(plain.withWeb(new WebAccess(WebAccess.Mode.SEARCH, List.of())));
        claude.executeAction(plain.withWeb(new WebAccess(WebAccess.Mode.FETCH,
                List.of("docs.example.org:443", "b.example:8443"))));

        List<JsonNode> requests = ofType("ai.request");
        assertThat(fieldNames(requests.get(2))).containsSubsequence("tools", "web", "web_hosts", "prompt");
        assertThat(requests).extracting(r -> r.get("web").asText()).containsExactly("none", "search", "fetch");
        assertThat(requests.get(0).has("web_hosts")).isFalse();
        assertThat(requests.get(1).has("web_hosts")).isFalse();
        assertThat(requests.get(2).get("web_hosts")).extracting(JsonNode::asText)
                .containsExactly("docs.example.org:443", "b.example:8443");
        assertThat(ofType("step.command").get(2).get("network_hosts")).extracting(JsonNode::asText)
                .containsExactly("api.claude.example:443", "b.example:8443", "docs.example.org:443");
    }
}
