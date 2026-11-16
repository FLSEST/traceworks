package traceworks.core.ai.cli;

import traceworks.core.port.WebAccess;
import traceworks.core.port.AiNetwork;
import traceworks.core.port.Egress;
import traceworks.core.ai.ToolCall;
import traceworks.core.ai.ToolPolicy;
import traceworks.core.port.AiCallFailedException;
import traceworks.core.port.AiCli.AiCall;
import traceworks.core.port.AiCli.AiOutcome;
import traceworks.core.port.AiProvider.ActionRequest;
import traceworks.core.port.AiProvider.ActionResult;
import traceworks.core.port.AiProvider.ReasoningRequest;
import traceworks.core.port.AiProvider.ReasoningResult;
import traceworks.core.port.CommandRunner;
import traceworks.core.port.CommandRunner.CommandRequest;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.testing.FakeAiCli;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** An AI call through the command path: the CLI runs as a container command of the run. */
class CliAiProviderTest {

    private static final String TAG = "traceworks/claude:a73397fba910";
    private static final String KEY = "sk-ant-api03-Abc_def-123";

    private final FakeAiCli cli = FakeAiCli.claude();
    private final List<CommandRequest> requests = new CopyOnWriteArrayList<>();
    private CommandResult next = new CommandResult(0, "out", "", 12, false);
    private final CommandRunner runner = request -> {
        requests.add(request);
        return next;
    };
    private final CliAiProvider provider = new CliAiProvider(cli, runner, TAG, Map.of("ANTHROPIC_API_KEY", KEY));

    @Test
    void aiCall_runsTheCliInItsImage_withNetwork_andThePromptOnStdin() throws IOException {
        cli.answers("fine");

        provider.executeAction(new ActionRequest("Review the diff", "opus", "be brief", "/work", 90, null,
                ToolPolicy.READ, true, "high"));

        CommandRequest request = requests.getFirst();
        assertThat(request.runsEntrypoint()).as("the CLI is the image's entrypoint, no shell in between").isTrue();
        assertThat(request.image()).isEqualTo(TAG);
        assertThat(request.entrypointArgs()).containsExactly("fake-claude", "--kind", "ACTION", "--tools", "read",
                "--model", "opus");
        assertThat(request.stdin()).isEqualTo("Review the diff");
        assertThat(request.network()).as("an AI container reaches its provider's host, and nothing else")
                .isEqualTo(Egress.hosts(List.of("api.claude.example:443")));
        assertThat(request.mounts()).as("only the run's standard mounts, which the runner adds").isEmpty();
        assertThat(request.workingDirectory()).isEqualTo("/work");
        assertThat(request.timeoutSeconds()).isEqualTo(90);
        assertThat(request.writableWorkspace()).isTrue();
        assertThat(request.environment()).containsExactlyInAnyOrderEntriesOf(Map.of("ANTHROPIC_API_KEY", KEY,
                "FAKE_CLI", "1"));
        AiCall call = cli.calls().getFirst();
        assertThat(call).isEqualTo(new AiCall(AiCall.Kind.ACTION, "Review the diff", "be brief", "opus", "high",
                ToolPolicy.READ, null, 90));
    }

    private CommandRequest requestWith(AiNetwork network) throws IOException {
        requests.clear();
        cli.answers("fine");
        new CliAiProvider(cli, runner, TAG, Map.of("ANTHROPIC_API_KEY", KEY), network)
                .executeAction(new ActionRequest("p", null, null, "/work", 90, null, ToolPolicy.SHELL, false, null));
        return requests.getFirst();
    }

    @Test
    void theOperatorsHostsOfTheCli_joinTheProviders_andAnotherClisDoNot() throws IOException {
        CommandRequest request = requestWith(new AiNetwork(false, Map.of("claude", List.of("gateway.corp.example:8443"),
                "pi", List.of("llm.corp.example:443"))));

        assertThat(request.network()).isEqualTo(Egress.hosts(List.of("api.claude.example:443",
                "gateway.corp.example:8443")));
    }

    @Test
    void anOpenAiNetwork_isTheRuntimesDefaultNetwork_asBeforeTheAllowlist() throws IOException {
        assertThat(requestWith(new AiNetwork(true, Map.of())).network()).isEqualTo(Egress.OPEN);
    }

    /**
     * A step of a workflow the start check never saw (a child named by a placeholder): the provider holds no
     * credential at all. A call to a model server of the operator's without a secret runs all the same, since it
     * needs none; what the step itself still holds is the closed list of the server's models and the server as its
     * only host. A server with a secret has no value in such a run, and its call is refused.
     */
    @Test
    void aLocalCallTheStartCheckNeverSaw_runsWithoutASecret_andStillHoldsTheModelListAndTheOneHost() throws IOException {
        traceworks.core.port.LocalModels local = new traceworks.core.port.LocalModels(Map.of(
                "llm", new traceworks.core.port.LocalModels.Backend("llm", "http://llm.internal:8080/v1",
                        traceworks.core.port.LocalModels.OPENAI_COMPLETIONS, List.of("qwen"), null),
                "keyed", new traceworks.core.port.LocalModels.Backend("keyed", "http://keyed.internal/v1",
                        traceworks.core.port.LocalModels.OPENAI_COMPLETIONS, List.of("qwen"), "LLM_KEY")));
        cli.localModels(local).answers("fine");
        CliAiProvider unseen = new CliAiProvider(cli, runner, TAG, Map.of(), new AiNetwork(true, Map.of()));

        ActionResult result = unseen.executeAction(new ActionRequest("p", "llm/qwen", null, "/work", 90, null,
                ToolPolicy.NONE, false, null));

        assertThat(result.text()).isEqualTo("fine");
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().network()).isEqualTo(Egress.server("llm.internal:8080", true));
        assertThat(requests.getFirst().environment()).containsOnlyKeys("FAKE_CLI");
        assertThatThrownBy(() -> unseen.executeAction(new ActionRequest("p", "llm/llama3", null, "/work", 90, null,
                ToolPolicy.NONE, false, null))).isInstanceOf(IOException.class)
                .hasMessageContaining("The local backend llm does not serve the model llama3");
        assertThatThrownBy(() -> unseen.executeAction(new ActionRequest("p", "keyed/qwen", null, "/work", 90, null,
                ToolPolicy.NONE, false, null))).isInstanceOf(IOException.class)
                .hasMessageContaining("The local backend keyed needs the secret LLM_KEY");
        assertThatThrownBy(() -> unseen.executeAction(new ActionRequest("p", "opus", null, "/work", 90, null,
                ToolPolicy.NONE, false, null))).as("a call to the provider is refused as before")
                .isInstanceOf(IOException.class).hasMessageContaining("has no credential in this run");
        assertThat(requests).as("none of the three refused calls ran").hasSize(1);
    }

    /** A backend without a built-in host, and none from the operator: the call cannot reach anything, so it fails. */
    @Test
    void aCallWithoutAnyHost_failsBeforeAnythingRuns_andSaysWhatToSet() {
        cli.needs();

        assertThatThrownBy(() -> provider.executeAction(new ActionRequest("p", "local/llama", null, "/work", 90, null,
                ToolPolicy.NONE, false, null)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("CLAUDE").hasMessageContaining("local/llama")
                .hasMessageContaining("ai_hosts").hasMessageContaining("claude")
                .hasMessageContaining("ai_network");
        assertThat(requests).isEmpty();
    }

    @Test
    void reason_passesItsTurnsAndPolicy_andAnswers() throws IOException {
        cli.answersAfter("plan", new ToolCall("Read", "{\"file_path\":\"a\"}", "text", false));

        ReasoningResult result = provider.reason(new ReasoningRequest("Think", "system", "opus", null, 3, 60, null,
                ToolPolicy.SHELL, false));

        assertThat(cli.calls().getFirst()).isEqualTo(new AiCall(AiCall.Kind.REASON, "Think", "system", "opus", null,
                ToolPolicy.SHELL, 3, 60));
        assertThat(requests.getFirst().workingDirectory()).as("the runner starts it in the workspace").isNull();
        assertThat(requests.getFirst().writableWorkspace()).isFalse();
        assertThat(result.text()).isEqualTo("plan");
        assertThat(result.metadata()).isEqualTo(FakeAiCli.usage());
        assertThat(result.toolCalls()).containsExactly(new ToolCall("Read", "{\"file_path\":\"a\"}", "text", false));
    }

    @Test
    void action_returnsTheAnswerItsExitCodeMetadataAndToolCalls() throws IOException {
        ToolCall grep = new ToolCall("Grep", "{}", "no match", true);
        cli.answersAfter("done", grep);

        ActionResult result = provider.executeAction(new ActionRequest("Do it", null, null, "/work"));

        assertThat(result.text()).isEqualTo("done");
        assertThat(result.exitCode()).isZero();
        assertThat(result.metadata()).isEqualTo(FakeAiCli.usage());
        assertThat(result.toolCalls()).containsExactly(grep);
        assertThat(cli.results()).as("the CLI parses what its command returned").containsExactly(next);
    }

    /** The port's contract: a failed reasoning call throws, so its error message is never taken for the answer. */
    @Test
    void reason_failedCall_throwsNamingTheExitCodeAndTheMessage() {
        cli.fails(2, "usage limit reached");

        assertThatThrownBy(() -> provider.reason(new ReasoningRequest("Think")))
                .isInstanceOf(AiCallFailedException.class)
                .isInstanceOf(IOException.class)
                .hasMessage("CLAUDE failed (exit code 2): usage limit reached")
                .satisfies(e -> assertThat(((AiCallFailedException) e).outcome())
                        .isEqualTo(new AiOutcome("usage limit reached", 2, true, null, List.of())));
    }

    /** A failed action is an answer with a non-zero exit code, also when the CLI itself ended with 0. */
    @Test
    void action_failedCall_hasANonZeroExitCode() throws IOException {
        cli.fails(3, "crashed").then(new AiOutcome("is_error result", 0, true, null, List.of()));

        assertThat(provider.executeAction(new ActionRequest("a", null, null, null)).exitCode()).isEqualTo(3);
        ActionResult failedWithZero = provider.executeAction(new ActionRequest("b", null, null, null));
        assertThat(failedWithZero.exitCode()).isEqualTo(1);
        assertThat(failedWithZero.text()).isEqualTo("is_error result");
    }

    /** A call that ran out of time failed, whatever the CLI's parser made of what it had written so far. */
    @Test
    void timedOut_isAFailedCall() throws IOException {
        next = new CommandResult(124, "{\"partial\":", "", 60_000, true);
        cli.then(new AiOutcome("half an answer", 0, false, null, List.of()));

        ActionResult result = provider.executeAction(new ActionRequest("slow", null, null, null, 60, null, null,
                false, null));

        assertThat(result.exitCode()).isEqualTo(124);
        assertThat(result.text()).isEqualTo("CLAUDE timed out after 60 s");
    }

    /**
     * A call whose stream went beyond the output limit failed with the note, whatever the CLI's parser made of the cut
     * stream: an answer it repaired from the cut last line, or an error of its own about the missing end.
     */
    @Test
    void truncated_isAFailedCall_withTheNote() {
        next = new CommandResult(153, "{\"type\":\"result\",\"result\":\"aaaa", "", 900, false, List.of("stdout"));
        cli.then(new AiOutcome("aaaa", 0, false, null, List.of()))
                .then(new AiOutcome("Claude CLI wrote no result line (exit code 153)", 153, true, null, List.of()));

        assertThatThrownBy(() -> provider.reason(new ReasoningRequest("Think")))
                .isInstanceOf(AiCallFailedException.class)
                .hasMessage("CLAUDE failed (exit code 153): CLAUDE: The command's standard output exceeded 16 MiB;"
                        + " Traceworks stopped it and kept the first 16 MiB");
        assertThatThrownBy(() -> provider.reason(new ReasoningRequest("Think")))
                .hasMessageEndingWith("The command's standard output exceeded 16 MiB; Traceworks stopped it and kept"
                        + " the first 16 MiB")
                .hasMessageNotContaining("result line");
    }

    /**
     * The CLI's parser gets only the lines of a cut stdout that ended: the last one broke off where the limit fell,
     * maybe inside a credential, and a tool call repaired from it would carry the credential's first characters, which
     * the record cannot redact. A cut stderr stopped the CLI as well, so its stdout broke off too.
     */
    @Test
    void truncated_theParserGetsOnlyTheLinesThatEnded() throws IOException {
        String ended = "{\"type\":\"assistant\"}\n{\"type\":\"user\"}\n";
        next = new CommandResult(153, ended + "{\"type\":\"user\",\"content\":\"" + KEY.substring(0, 10), "late", 900,
                false, List.of("stdout"));
        provider.executeAction(new ActionRequest("env", null, null, null));
        next = new CommandResult(153, ended + "{\"partial\":", "late", 900, false, List.of("stderr"));
        provider.executeAction(new ActionRequest("env", null, null, null));

        assertThat(cli.results()).hasSize(2);
        assertThat(cli.results().getFirst()).isEqualTo(new CommandResult(153, ended, "late", 900, false,
                List.of("stdout")));
        assertThat(cli.results().getLast()).isEqualTo(new CommandResult(153, ended, "late", 900, false,
                List.of("stderr")));
    }

    /**
     * A CLI stopped for time, by a cancel or by a kill broke off where it was stopped, as one cut at the limit does:
     * its parser gets only the lines that ended, so no tool call is repaired from a last line that may end inside a
     * credential. A CLI that exited 0 ended its stream itself, and its parser gets it as it came.
     */
    @Test
    void timedOutCancelledOrKilled_theParserGetsOnlyTheLinesThatEnded() throws IOException {
        String ended = "{\"type\":\"assistant\"}\n";
        String broken = ended + "{\"type\":\"user\",\"content\":\"ANTHROPIC_API_KEY=" + KEY.substring(0, 10);
        next = new CommandResult(124, broken, "", 60_000, true);
        provider.executeAction(new ActionRequest("env", null, null, null));
        next = new CommandResult(143, broken, "", 900, false);
        provider.executeAction(new ActionRequest("env", null, null, null));
        next = new CommandResult(137, broken, "", 900, false);
        provider.executeAction(new ActionRequest("env", null, null, null));
        CommandResult exitedByItself = new CommandResult(0, ended + "{\"type\":\"result\"}", "", 900, false);
        next = exitedByItself;
        provider.executeAction(new ActionRequest("env", null, null, null));

        assertThat(cli.results()).extracting(CommandResult::stdout).containsExactly(ended, ended, ended,
                exitedByItself.stdout());
        assertThat(cli.results().getFirst()).isEqualTo(new CommandResult(124, ended, "", 60_000, true));
    }

    @Test
    void truncated_actionHasTheLimitsExitCode() throws IOException {
        next = new CommandResult(153, "{\"partial\":", "", 900, false, List.of("stdout"));
        cli.then(new AiOutcome("half an answer", 0, false, null, List.of()));

        ActionResult result = provider.executeAction(new ActionRequest("loud", null, null, null));

        assertThat(result.exitCode()).isEqualTo(153);
        assertThat(result.text()).isEqualTo("CLAUDE: The command's standard output exceeded 16 MiB; Traceworks stopped"
                + " it and kept the first 16 MiB");
    }

    /** The note names the limit the runner had, and the parser's copy of the cut stream keeps it. */
    @Test
    void truncated_namesTheOperatorsLimit() throws IOException {
        next = new CommandResult(153, "{\"type\":\"user\"}\n{\"partial\":", "", 900, false, List.of("stdout"),
                4 * 1024 * 1024);
        cli.then(new AiOutcome("half an answer", 0, false, null, List.of()));

        ActionResult result = provider.executeAction(new ActionRequest("loud", null, null, null));

        assertThat(result.text()).isEqualTo("CLAUDE: The command's standard output exceeded 4 MiB; Traceworks stopped"
                + " it and kept the first 4 MiB");
        assertThat(cli.results().getFirst().outputLimitBytes()).isEqualTo(4 * 1024 * 1024);
    }

    @Test
    void noTimeout_usesTheDefault() throws IOException {
        cli.answers("ok");

        provider.executeAction(new ActionRequest("a", null, null, null));

        assertThat(requests.getFirst().timeoutSeconds()).isEqualTo(CliAiProvider.DEFAULT_TIMEOUT_SECONDS)
                .isEqualTo(300);
        assertThat(cli.calls().getFirst().timeoutSeconds()).isEqualTo(300);
    }

    /**
     * A call without credentials is refused before anything runs, naming them (fix round 2): the start check read none
     * for this provider, because it did not see the workflow of the step.
     */
    @Test
    void aCallWithoutCredentials_isRefusedBeforeItRuns_namingThem() {
        CliAiProvider bare = new CliAiProvider(cli, runner, TAG, Map.of());
        String message = "AI provider CLAUDE has no credential in this run: the start check did not see the workflow"
                + " of this step, so it read none of ANTHROPIC_API_KEY, CLAUDE_CODE_OAUTH_TOKEN; name the workflow by"
                + " a literal workflow_id, so the start check reads its credentials";

        assertThatThrownBy(() -> bare.executeAction(new ActionRequest("a", null, null, null)))
                .isInstanceOf(IOException.class).hasMessage(message);
        assertThatThrownBy(() -> bare.reason(new ReasoningRequest("Think")))
                .isInstanceOf(IOException.class).hasMessage(message);
        assertThat(requests).as("nothing ran").isEmpty();
        assertThat(cli.calls()).as("not even the invocation was built").isEmpty();
    }

    /**
     * A call's container gets the credentials its CLI names for the call, not every credential of the provider that
     * has a value: a hub CLI (Pi) names those of the backend the call's model belongs to.
     */
    @Test
    void aCall_getsOnlyTheCredentialsItsCliNamesForIt() throws IOException {
        FakeAiCli pi = FakeAiCli.pi().credentialNames("OPENAI_API_KEY");
        pi.answers("fine");
        CliAiProvider hub = new CliAiProvider(pi, runner, TAG, Map.of("ANTHROPIC_API_KEY", KEY, "OPENAI_API_KEY",
                "sk-proj-Openai_key-0123456789", "OPENROUTER_API_KEY", "sk-or-v1-0123456789"));

        hub.executeAction(new ActionRequest("p", "openai/gpt-5.5", null, "/work", 90, null, ToolPolicy.NONE, false,
                null));

        assertThat(requests.getFirst().environment()).containsExactlyInAnyOrderEntriesOf(Map.of("OPENAI_API_KEY",
                "sk-proj-Openai_key-0123456789", "FAKE_CLI", "1"));
    }

    /**
     * A CLI narrows the credentials of its provider, and can never add to them: a name it gives that is not in the
     * provider's table ({@code AiCli.CREDENTIALS}) is not passed, also when the run holds a value under that name.
     */
    @Test
    void aNameTheCliGivesOutsideItsProvidersTable_isNotPassed_evenWithAValue() throws IOException {
        FakeAiCli widening = FakeAiCli.claude().credentialNames("ANTHROPIC_API_KEY", "DEPLOY_TOKEN",
                "OPENAI_API_KEY");
        widening.answers("fine");
        CliAiProvider wide = new CliAiProvider(widening, runner, TAG, Map.of("ANTHROPIC_API_KEY", KEY,
                "DEPLOY_TOKEN", "deploy-token-0123456789", "OPENAI_API_KEY", "sk-proj-Openai_key-0123456789"));

        wide.executeAction(new ActionRequest("a", null, null, null));

        assertThat(requests.getFirst().environment()).as("OPENAI_API_KEY is Pi's, DEPLOY_TOKEN nobody's")
                .containsOnlyKeys("ANTHROPIC_API_KEY", "FAKE_CLI");
    }

    /** None of the credentials the call may get has a value: the call fails before anything runs, naming them. */
    @Test
    void aCallWhoseCredentialsHaveNoValue_failsBeforeAnythingRuns_namingThem() {
        FakeAiCli pi = FakeAiCli.pi().credentialNames("OPENAI_API_KEY");
        CliAiProvider hub = new CliAiProvider(pi, runner, TAG, Map.of("ANTHROPIC_API_KEY", KEY));

        assertThatThrownBy(() -> hub.executeAction(new ActionRequest("p", "openai/gpt-5.5", null, "/work", 90, null,
                ToolPolicy.NONE, false, null)))
                .isExactlyInstanceOf(IOException.class)
                .hasMessage("AI provider PI has no credential for a call with the model openai/gpt-5.5 in this run:"
                        + " it needs OPENAI_API_KEY; store it with 'traceworks credentials set <NAME>', or set it as"
                        + " an environment variable of Traceworks")
                .hasMessageNotContaining(KEY);
        assertThat(requests).as("nothing ran").isEmpty();
    }

    /** A call without a model has none to name: the message says so, and where the call goes when the CLI tells. */
    @Test
    void aCallWithoutAModelWhoseCredentialsHaveNoValue_saysThatTheStepNamesNoModel() {
        FakeAiCli pi = FakeAiCli.pi().credentialNamesFor(null, "ANTHROPIC_API_KEY", "ANTHROPIC_OAUTH_TOKEN")
                .backendFor(null, "anthropic");
        CliAiProvider hub = new CliAiProvider(pi, runner, TAG, Map.of("OPENAI_API_KEY",
                "sk-proj-Openai_key-0123456789"));

        assertThatThrownBy(() -> hub.reason(new ReasoningRequest("Think")))
                .isExactlyInstanceOf(IOException.class)
                .hasMessage("AI provider PI has no credential for this call in this run: the step names no model, so"
                        + " its call goes to anthropic, which needs ANTHROPIC_API_KEY or ANTHROPIC_OAUTH_TOKEN; store"
                        + " it with 'traceworks credentials set <NAME>', or set it as an environment variable of"
                        + " Traceworks");
        FakeAiCli plain = FakeAiCli.pi().credentialNamesFor(null, "OPENROUTER_API_KEY");
        assertThatThrownBy(() -> new CliAiProvider(plain, runner, TAG, Map.of("OPENAI_API_KEY",
                "sk-proj-Openai_key-0123456789")).reason(new ReasoningRequest("Think")))
                .hasMessageStartingWith("AI provider PI has no credential for this call in this run: the step names"
                        + " no model; it needs OPENROUTER_API_KEY; store it");
    }

    /** A call whose backend the CLI cannot tell gets no credential at all: it fails with the CLI's message. */
    @Test
    void aCallWhoseCredentialsTheCliCannotTell_failsBeforeAnythingRuns() {
        FakeAiCli pi = FakeAiCli.pi().knowsNoCredentialNames("the backend ollama is not known");
        CliAiProvider hub = new CliAiProvider(pi, runner, TAG, Map.of("ANTHROPIC_API_KEY", KEY));

        assertThatThrownBy(() -> hub.reason(new ReasoningRequest("Think")))
                .isExactlyInstanceOf(IOException.class).hasMessage("the backend ollama is not known")
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThat(requests).as("nothing ran").isEmpty();
        assertThat(pi.calls()).as("not even the invocation was built").isEmpty();
    }

    /**
     * A call the CLI cannot make (a system prompt it cannot carry) is a failed call, an
     * {@link IOException} with the CLI's message, before anything runs; not an exception that would end the run.
     */
    @Test
    void aCallTheCliCannotMake_failsBeforeAnythingRuns() {
        cli.refuses("The system prompt is 99999 bytes; at most 97280 (95 KiB) reach the AI CLI");

        assertThatThrownBy(() -> provider.executeAction(new ActionRequest("a", null, "s", null)))
                .isExactlyInstanceOf(IOException.class)
                .hasMessage("The system prompt is 99999 bytes; at most 97280 (95 KiB) reach the AI CLI")
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.reason(new ReasoningRequest("Think")))
                .isExactlyInstanceOf(IOException.class)
                .hasMessage("The system prompt is 99999 bytes; at most 97280 (95 KiB) reach the AI CLI");
        assertThat(requests).as("nothing ran").isEmpty();
    }

    @Test
    void idAndCapabilities_areTheClis() {
        assertThat(provider.getId()).isEqualTo("CLAUDE");
        assertThat(provider.getCapabilities()).isEqualTo(cli.capabilities());
    }

    /** No credential value is ever in what the request shows of itself (a log line, an exception). */
    @Test
    void theRequest_neverShowsACredentialValue() throws IOException {
        cli.answers("ok");

        provider.executeAction(new ActionRequest("a", null, null, null));

        assertThat(requests.getFirst().toString()).contains("ANTHROPIC_API_KEY").doesNotContain(KEY);
        assertThat(provider.toString()).doesNotContain(KEY);
    }

    private CommandRequest requestWithWeb(WebAccess web, AiNetwork network) throws IOException {
        requests.clear();
        cli.answers("fine");
        new CliAiProvider(cli, runner, TAG, Map.of("ANTHROPIC_API_KEY", KEY), network).executeAction(
                new ActionRequest("p", null, null, "/work", 90, null, ToolPolicy.NONE, false, null, web));
        return requests.getFirst();
    }

    /** Search runs at the provider: the container reaches no more than without it. Fetch adds the step's hosts. */
    @Test
    void web_reachesTheCli_andOnlyFetchWidensTheEgress() throws IOException {
        WebAccess search = new WebAccess(WebAccess.Mode.SEARCH, List.of());
        WebAccess fetch = new WebAccess(WebAccess.Mode.FETCH, List.of("docs.example.org:443"));
        WebAccess any = new WebAccess(WebAccess.Mode.FETCH, List.of("*"));

        assertThat(requestWithWeb(search, AiNetwork.RESTRICTED).network())
                .isEqualTo(Egress.hosts(List.of("api.claude.example:443")));
        assertThat(cli.calls().getLast().web()).isEqualTo(search);
        assertThat(requests.getFirst().entrypointArgs()).containsSubsequence("--web", "search");
        assertThat(requestWithWeb(fetch, AiNetwork.RESTRICTED).network())
                .isEqualTo(Egress.hosts(List.of("api.claude.example:443", "docs.example.org:443")));
        assertThat(requestWithWeb(any, AiNetwork.RESTRICTED).network().hosts())
                .as("the star is one of the hosts, for the proxy to judge").containsExactly("*",
                        "api.claude.example:443");
        assertThat(requestWithWeb(fetch, new AiNetwork(true, Map.of())).network())
                .as("an open AI network: web works, its hosts are not enforced").isEqualTo(Egress.OPEN);
        assertThat(cli.calls().getLast().web()).isEqualTo(fetch);
    }

    /** A CLI without a web tool never gets a call with web: it fails before anything runs. */
    @Test
    void web_onACliWithoutAWebTool_failsBeforeAnythingRuns() {
        cli.withoutWeb();

        assertThatThrownBy(() -> requestWithWeb(new WebAccess(WebAccess.Mode.SEARCH, List.of()),
                AiNetwork.RESTRICTED)).isInstanceOf(IOException.class)
                .hasMessageContaining("CLAUDE has no web tool").hasMessageContaining("web: search");
        assertThat(requests).isEmpty();
        assertThat(cli.calls()).isEmpty();
    }
}
