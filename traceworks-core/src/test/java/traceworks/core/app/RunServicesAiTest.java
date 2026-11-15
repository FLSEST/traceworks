package traceworks.core.app;

import com.fasterxml.jackson.databind.JsonNode;
import traceworks.core.port.AiNetwork;
import traceworks.core.port.Egress;
import traceworks.core.ai.ToolCall;
import traceworks.core.ai.cli.CliAiProvider;
import traceworks.core.engine.RunRequirements;
import traceworks.core.model.RunStatus;
import traceworks.core.port.AiCli;
import traceworks.core.port.AiProvider;
import traceworks.core.port.CommandRunner;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.ContainerRuntime.ContainerRun;
import traceworks.core.record.RecordedEvent;
import traceworks.core.testing.FakeAiCli;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static traceworks.core.app.AppFixture.ANTHROPIC_KEY;
import static traceworks.core.app.AppFixture.CLAUDE_ID;
import static traceworks.core.app.AppFixture.CLAUDE_TAG;
import static traceworks.core.app.AppFixture.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI steps of a run: the run builds its providers from the configured AI CLIs, runs each call
 * in the provider's built-in image through the run's command path, passes the provider's credentials to that
 * container only, redacts them, and refuses at start a provider without credentials or without its image.
 */
class RunServicesAiTest {

    /** A shell step, then a simple step that reviews its output with tools {@code read}. */
    private static final Path REVIEW = Path.of("/pkg/review");
    /** A simple step whose workflow names Pi as its provider. */
    private static final Path PI = Path.of("/pkg/pi");
    /** Two shell steps, no AI. */
    private static final Path BUILD = AppFixture.BUILD;

    private final AppFixture fx = new AppFixture();

    RunServicesAiTest() {
        fx.settings = OperatorSettings.defaults();
        fx.packages.put(REVIEW, """
                {"id": "wf-review", "name": "Review", "requires": ["ai.tools"], "blocks": [
                  {"type": "shell", "name": "build", "config": {"command": "./build"}},
                  {"type": "simple", "name": "review",
                   "config": {"command": "Review: {{ block.build.output }}", "tools": "read"}}
                ]}""");
        fx.packages.put(PI, """
                {"id": "wf-pi", "name": "Pi", "provider": "PI", "blocks": [
                  {"type": "simple", "name": "ask", "config": {"command": "Say hi"}}
                ]}""");
    }

    private String text(String runId, RecordedEvent event, String field) {
        return fx.text(runId, event, field);
    }

    private static final String OPERATOR_IMAGE = "registry.example/ops/claude:7";
    private static final String OPERATOR_ID = "sha256:" + "7".repeat(64);

    /**
     * {@code ai_images} replaces the built-in image of a CLI and nothing else: the same command line, stdin, mounts
     * and network, and the record names the operator's image and its ID.
     */
    @Test
    void aiImages_runsTheCliInTheOperatorsImage_andChangesNothingElse() {
        fx.withClaude();
        fx.containers.on(AppFixture.SHELL_ID, new CommandResult(0, "built", "", 1, false));
        fx.claude.answers("looks fine");
        fx.containers.present(OPERATOR_IMAGE, OPERATOR_ID);
        fx.settings = new OperatorSettings(false, null, List.of(), null, null, 16, Map.of("claude", OPERATOR_IMAGE));

        RunResult result = fx.services().start(request(REVIEW), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        ContainerRun ai = fx.containers.runs().get(1);
        assertThat(ai.imageId()).isEqualTo(OPERATOR_ID);
        assertThat(ai.args()).containsExactly("fake-claude", "--kind", "ACTION", "--tools", "read");
        assertThat(ai.stdin()).isEqualTo("Review: built");
        assertThat(ai.network()).isEqualTo(Egress.hosts(List.of("api.claude.example:443")));
        assertThat(ai.environment()).containsEntry("ANTHROPIC_API_KEY", ANTHROPIC_KEY);
        assertThat(ai.mounts()).first().satisfies(m -> assertThat(m.readOnly()).isTrue());
        RecordedEvent command = fx.ofType(result.runId(), "step.command").get(1);
        assertThat(command.fields().path("image").asText()).isEqualTo(OPERATOR_IMAGE);
        assertThat(command.fields().path("image_id").asText()).isEqualTo(OPERATOR_ID);
        assertThat(fx.ofType(result.runId(), "run.started").getFirst().fields().path("ai_images").path("claude")
                .asText()).isEqualTo(OPERATOR_IMAGE);
    }

    /** A run without a replacement records an empty {@code ai_images}, and the other CLI keeps its built-in image. */
    @Test
    void aiImages_ofAnotherCli_leaveThisOneInItsBuiltInImage_andAreRecordedAsEmptyWithout() {
        fx.withClaude();
        fx.containers.on(AppFixture.SHELL_ID, new CommandResult(0, "built", "", 1, false));
        fx.claude.answers("ok");
        fx.settings = new OperatorSettings(false, null, List.of(), null, null, 16, Map.of("pi", OPERATOR_IMAGE));

        RunResult result = fx.services().start(request(REVIEW), null, null);

        assertThat(fx.containers.runs().get(1).imageId()).isEqualTo(CLAUDE_ID);
        assertThat(fx.ofType(result.runId(), "run.started").getFirst().fields().path("ai_images").path("pi")
                .asText()).isEqualTo(OPERATOR_IMAGE);
        fx.settings = OperatorSettings.defaults();
        RunResult plain = fx.services().start(request(REVIEW), null, null);
        assertThat(fx.ofType(plain.runId(), "run.started").getFirst().fields().path("ai_images").isObject())
                .isTrue();
        assertThat(fx.ofType(plain.runId(), "run.started").getFirst().fields().path("ai_images").size()).isZero();
    }

    /**
     * The start check asks for the operator's image, not the built-in one, and says where it comes from and that
     * Traceworks never builds it.
     */
    @Test
    void aiImages_theStartCheckAsksForTheOperatorsImage_notTheBuiltInOne() {
        fx.withClaude();
        fx.settings = new OperatorSettings(false, null, List.of(), null, null, 16, Map.of("claude", OPERATOR_IMAGE));

        assertThatThrownBy(() -> fx.services().start(request(REVIEW), null, null))
                .isInstanceOf(RunRefusedException.class)
                .message().contains("image " + OPERATOR_IMAGE + " is missing")
                .contains("ai_images of traceworks.json").contains("never builds or pulls")
                .doesNotContain(CLAUDE_TAG).doesNotContain("traceworks images build");
    }

    @Test
    void aiImageNotes_nameEachReplacement() {
        fx.settings = new OperatorSettings(false, null, List.of(), null, null, 16,
                Map.of("pi", "pi:2", "claude", OPERATOR_IMAGE));

        assertThat(fx.services().aiImageNotes()).containsExactly(
                "ai_images: claude runs in " + OPERATOR_IMAGE + " instead of the built-in image",
                "ai_images: pi runs in pi:2 instead of the built-in image");
        fx.settings = OperatorSettings.defaults();
        assertThat(fx.services().aiImageNotes()).isEmpty();
    }

    @Test
    void aiStep_runsItsCliInTheProvidersImage_throughTheRunsCommandPath() {
        fx.withClaude();
        fx.containers.on(AppFixture.SHELL_ID, new CommandResult(0, "built", "", 1, false));
        fx.claude.answersAfter("looks fine", new ToolCall("Read", "{\"file_path\":\"a\"}", "x", false));

        RunResult result = fx.services().start(request(REVIEW), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        List<ContainerRun> runs = fx.containers.runs();
        assertThat(runs).hasSize(2);
        ContainerRun ai = runs.get(1);
        assertThat(ai.imageId()).isEqualTo(CLAUDE_ID);
        assertThat(ai.name()).as("counted with the run's other containers").isEqualTo("traceworks-" + result.runId()
                + "-2");
        assertThat(ai.network()).as("only the provider's host, through the egress proxy")
                .isEqualTo(Egress.hosts(List.of("api.claude.example:443")));
        assertThat(ai.egressImageId()).isEqualTo(AppFixture.EGRESS_ID);
        assertThat(ai.entrypoint()).as("the CLI is the image's entrypoint").isNull();
        assertThat(ai.args()).containsExactly("fake-claude", "--kind", "ACTION", "--tools", "read");
        assertThat(ai.stdin()).isEqualTo("Review: built");
        assertThat(ai.workingDirectory()).isEqualTo("/workspace");
        assertThat(ai.mounts()).as("the workspace read-only").first()
                .satisfies(m -> assertThat(m.readOnly()).isTrue());
        assertThat(fx.claude.calls()).hasSize(1);

        List<String> types = fx.types(result.runId());
        int request = types.indexOf("ai.request");
        assertThat(types.subList(request, request + 5)).containsExactly("ai.request", "step.command",
                "step.command.finished", "ai.tool_call", "ai.response");
        RecordedEvent command = fx.ofType(result.runId(), "step.command").get(1);
        assertThat(command.fields().path("image").asText()).isEqualTo(CLAUDE_TAG);
        assertThat(command.fields().path("image_id").asText()).isEqualTo(CLAUDE_ID);
        assertThat(command.step()).isEqualTo(1);
    }

    @Test
    void credentials_reachTheContainerAsEnvironment_andAreRedacted() {
        fx.withClaude();
        fx.claude.answers("the key is " + ANTHROPIC_KEY);

        RunResult result = fx.services().start(request(REVIEW), null, null);

        ContainerRun ai = fx.containers.runs().get(1);
        assertThat(ai.environment()).containsEntry("ANTHROPIC_API_KEY", ANTHROPIC_KEY)
                .doesNotContainKey("CLAUDE_CODE_OAUTH_TOKEN");
        assertThat(fx.containers.runs().getFirst().environment()).as("a shell step gets no credential")
                .doesNotContainKey("ANTHROPIC_API_KEY");
        RecordedEvent response = fx.ofType(result.runId(), "ai.response").getFirst();
        assertThat(text(result.runId(), response, "output")).isEqualTo("the key is [redacted:ANTHROPIC_API_KEY]");
        for (byte[] line : fx.run(result.runId()).log().readAll()) {
            assertThat(new String(line, StandardCharsets.UTF_8)).doesNotContain(ANTHROPIC_KEY);
        }
        fx.run(result.runId()).blobs().all().forEach(blob ->
                assertThat(new String(blob, StandardCharsets.UTF_8)).doesNotContain(ANTHROPIC_KEY));
    }

    /**
     * A credential is kept whole by every cut of the run's text, as a secret is: the error of a failed
     * AI condition is cut at 300 characters, and a cut through the key would leave a part the record cannot redact.
     */
    @Test
    void credentialInACutText_isKeptWhole_soTheRecordRedactsAllOfIt() {
        fx.withClaude();
        fx.packages.put(Path.of("/pkg/check"), """
                {"id": "wf-check", "name": "Check", "blocks": [
                  {"type": "conditional", "name": "check",
                   "config": {"execution_mode": "ai", "condition": "Is it fine?", "then_command": "Say yes"}}
                ]}""");
        fx.claude.fails(1, "x".repeat(280) + ANTHROPIC_KEY + " was refused");

        RunResult result = fx.services().start(request(Path.of("/pkg/check")), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        String keyStart = ANTHROPIC_KEY.substring(0, 7);
        for (byte[] line : fx.run(result.runId()).log().readAll()) {
            assertThat(new String(line, StandardCharsets.UTF_8)).doesNotContain(keyStart);
        }
        fx.run(result.runId()).blobs().all().forEach(blob ->
                assertThat(new String(blob, StandardCharsets.UTF_8)).doesNotContain(keyStart));
    }

    /**
     * An AI condition whose CLI cannot make the call (its invocation throws) fails its step, and the run
     * goes on as for any failed AI call; no command starts.
     */
    @Test
    void anAiConditionWhoseCliCannotMakeTheCall_failsItsStep_asAFailedCallDoes() {
        RunResult refused = conditionRun(fx, cli -> cli.refuses("The system prompt is 99999 bytes; at most 97280"
                + " (95 KiB) reach the AI CLI"));
        AppFixture failing = new AppFixture();
        RunResult failed = conditionRun(failing, cli -> cli.fails(1, "usage limit reached"));

        assertThat(refused.status()).as("the run went on to its next block").isEqualTo(RunStatus.COMPLETED)
                .as("as for a call that failed").isEqualTo(failed.status());
        assertThat(fx.containers.runs()).as("only the shell step ran; the CLI never started").hasSize(1)
                .allSatisfy(run -> assertThat(run.imageId()).isEqualTo(AppFixture.SHELL_ID));
        assertThat(fx.ofType(refused.runId(), "step.finished")).extracting(e -> e.fields().path("name").asText()
                + " " + e.fields().path("status").asText()).containsExactly("check SKIPPED", "after COMPLETED")
                .isEqualTo(failing.ofType(failed.runId(), "step.finished").stream().map(e -> e.fields().path("name")
                        .asText() + " " + e.fields().path("status").asText()).toList());
        RecordedEvent output = fx.ofType(refused.runId(), "step.output").getFirst();
        assertThat(text(refused.runId(), output, "output")).contains("Condition evaluation failed")
                .contains("at most 97280 (95 KiB)");
    }

    /** A run of an AI condition ({@code on_condition_error: skip}) and a block after it; the call as scripted. */
    private static RunResult conditionRun(AppFixture fixture, java.util.function.Consumer<FakeAiCli> script) {
        fixture.settings = OperatorSettings.defaults();
        fixture.withClaude();
        fixture.packages.put(Path.of("/pkg/check"), """
                {"id": "wf-check", "name": "Check", "blocks": [
                  {"type": "conditional", "name": "check",
                   "config": {"execution_mode": "ai", "condition": "Is it fine?", "then_command": "Say yes",
                              "on_condition_error": "skip"}},
                  {"type": "shell", "name": "after", "config": {"command": "echo after"}}
                ]}""");
        fixture.containers.on(AppFixture.SHELL_ID, new CommandResult(0, "after", "", 1, false));
        script.accept(fixture.claude);
        return fixture.services().start(request(Path.of("/pkg/check")), null, null);
    }

    /** A credential is no secret of the run's steps: a step that names it without the start check gets nothing. */
    @Test
    void aCredential_isNoSecretOfTheSteps() {
        fx.withClaude();
        fx.claude.answers("ok");

        fx.services().start(request(REVIEW), null, null);

        assertThat(fx.containers.runs().getFirst().environment()).doesNotContainKey("ANTHROPIC_API_KEY")
                .doesNotContainValue(ANTHROPIC_KEY);
    }

    /**
     * A credentials file that cannot be used is read once and reported once, also for a run that declares a
     * secret and uses an AI provider, which both need it.
     */
    @Test
    void brokenCredentialsFile_isReadAndReportedOnce() {
        fx.withClaude();
        fx.packages.put(Path.of("/pkg/both"), """
                {"id": "wf-both", "name": "Both", "blocks": [
                  {"type": "shell", "name": "deploy", "config": {"command": "./deploy", "secrets": ["DEPLOY_TOKEN"]}},
                  {"type": "simple", "name": "ask", "config": {"command": "Say hi"}}
                ]}""");
        fx.credentialsFailure = new IllegalArgumentException("credentials.json can be read by others; run chmod 600");

        assertThatThrownBy(() -> fx.services().start(request(Path.of("/pkg/both")), null, null))
                .isInstanceOf(RunRefusedException.class)
                .satisfies(e -> assertThat(e.getMessage().split("chmod 600", -1)).as(e.getMessage()).hasSize(2));
        assertThat(fx.credentialReads).hasValue(1);
    }

    /**
     * One credential table (fix round 2): the start check and the secret names both read {@link AiCli#CREDENTIALS} by
     * provider id. A CLI's one method about credentials, {@code credentialNames(AiCall)}, only narrows the table's
     * names to those of a call's backend: a call gets values from what the start check read by the table, so no
     * adapter can add a name or make the two drift.
     */
    @Test
    void credentialNames_comeOnlyFromTheTable() throws IOException {
        assertThat(AiCli.class.getMethods()).filteredOn(m -> m.getName().startsWith("credentialNames")
                && !Modifier.isStatic(m.getModifiers())).extracting(m -> m.getName() + List.of(m.getParameterTypes()))
                .containsExactly("credentialNames" + List.of(AiCli.AiCall.class));
        FakeAiCli widening = FakeAiCli.claude().credentialNames("ANTHROPIC_API_KEY", "DEPLOY_TOKEN");
        widening.answers("fine");
        List<Map<String, String>> environments = new ArrayList<>();
        new CliAiProvider(widening, request -> {
            environments.add(request.environment());
            return new CommandRunner.CommandResult(0, "out", "", 1, false);
        }, "traceworks/claude:a73397fba910", Map.of("ANTHROPIC_API_KEY", "sk-ant-api03-Abc_def-123"))
                .executeAction(new AiProvider.ActionRequest("a", null, null, null));
        assertThat(environments.getFirst()).as("a name outside the table has no value to pass")
                .containsOnlyKeys("ANTHROPIC_API_KEY", "FAKE_CLI");
        assertThat(AiCli.credentialNamesOf("claude")).containsExactly("ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN");
        assertThat(AiCli.credentialNamesOf("GEMINI")).isEmpty();
    }

    /** A CLI whose provider the table does not know can never get a credential: the start check says so. */
    @Test
    void cliOfAProviderWithoutCredentialNames_isRefusedAtStart() {
        fx.withClaude();
        fx.clis.add(new FakeAiCli("GEMINI", "claude"));
        fx.packages.put(Path.of("/pkg/gemini"), """
                {"id": "wf-gemini", "name": "Gemini", "provider": "GEMINI", "blocks": [
                  {"type": "simple", "name": "ask", "config": {"command": "Say hi"}}
                ]}""");

        assertThatThrownBy(() -> fx.services().start(request(Path.of("/pkg/gemini")), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("AI provider GEMINI has no credential names in Traceworks");
    }

    @Test
    void credentialsFromTheFile_reachTheContainerToo() {
        fx.withClaude();
        fx.environment.clear();
        fx.credentials.put("CLAUDE_CODE_OAUTH_TOKEN", "sk-ant-oat01-Token_value-42");
        fx.claude.answers("ok");

        fx.services().start(request(REVIEW), null, null);

        assertThat(fx.containers.runs().get(1).environment())
                .containsEntry("CLAUDE_CODE_OAUTH_TOKEN", "sk-ant-oat01-Token_value-42")
                .doesNotContainKey("ANTHROPIC_API_KEY");
    }

    @Test
    void providerWithoutCredentials_isRefusedAtStart_namingTheVariables() {
        fx.withClaude();
        fx.environment.clear();

        assertThatThrownBy(() -> fx.services().start(request(REVIEW), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("CLAUDE")
                .hasMessageContaining("ANTHROPIC_API_KEY")
                .hasMessageContaining("CLAUDE_CODE_OAUTH_TOKEN")
                .hasMessageContaining("credentials.json");
        assertThat(fx.store.runIds()).as("refused before the run exists").isEmpty();
        assertThat(fx.containers.runs()).isEmpty();
    }

    /** A credential is a secret like any other: a value the record cannot redact refuses the run. */
    @Test
    void credentialWithAValueTheRecordCannotRedact_isRefusedAtStart() {
        fx.withClaude();
        fx.environment.put("ANTHROPIC_API_KEY", "k3Yq7");

        assertThatThrownBy(() -> fx.services().start(request(REVIEW), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY")
                .hasMessageContaining("shorter than 8 characters")
                .satisfies(e -> assertThat(e.getMessage()).as("the refusal never shows the value")
                        .doesNotContain("k3Yq7"));
        assertThat(fx.store.runIds()).isEmpty();
    }

    /** A run with an AI step needs the proxy that is its container's only door, unless the AI network is open. */
    @Test
    void missingEgressImage_isRefusedAtStart_unlessTheAiNetworkIsOpen() {
        fx.withClaude();
        fx.containers.absent(AppFixture.EGRESS_TAG);
        fx.containers.on(AppFixture.SHELL_ID, new CommandResult(0, "built", "", 1, false));
        fx.claude.answers("looks fine");
        fx.settings = OperatorSettings.defaults();

        assertThatThrownBy(() -> fx.services().start(request(REVIEW), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("image " + AppFixture.EGRESS_TAG + " is missing: run 'traceworks images build'");
        assertThat(fx.store.runIds()).isEmpty();

        fx.settings = OperatorSettings.defaults().withAiNetwork(new AiNetwork(true, Map.of()));
        RunResult open = fx.services().start(request(REVIEW), null, null);

        assertThat(open.status()).isEqualTo(RunStatus.COMPLETED);
        ContainerRun ai = fx.containers.runs().get(1);
        assertThat(ai.network()).as("as before the allowlist").isEqualTo(Egress.OPEN);
        assertThat(ai.egressImageId()).isNull();
        RecordedEvent started = fx.ofType(open.runId(), "run.started").getFirst();
        assertThat(started.fields().path("ai_network").asText()).isEqualTo("open");
        assertThat(fx.ofType(open.runId(), "step.command").get(1).fields().path("network").asText())
                .isEqualTo("open");
    }

    /** The operator's hosts for a CLI join its provider's, in the container and in the record. */
    @Test
    void aiHosts_reachTheContainerOfThatCli_andTheRecord() {
        fx.withClaude();
        fx.containers.on(AppFixture.SHELL_ID, new CommandResult(0, "built", "", 1, false));
        fx.claude.answers("looks fine");
        fx.settings = OperatorSettings.defaults().withAiNetwork(new AiNetwork(false,
                Map.of("claude", List.of("gateway.corp.example:8443"), "pi", List.of("llm.corp.example:443"))));

        RunResult result = fx.services().start(request(REVIEW), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.containers.runs().get(1).network()).isEqualTo(Egress.hosts(
                List.of("api.claude.example:443", "gateway.corp.example:8443")));
        JsonNode started = fx.ofType(result.runId(), "run.started").getFirst().fields();
        assertThat(started.path("ai_network").asText()).isEqualTo("restricted");
        assertThat(started.path("ai_hosts").path("claude")).extracting(JsonNode::asText)
                .containsExactly("gateway.corp.example:8443");
        JsonNode command = fx.ofType(result.runId(), "step.command").get(1).fields();
        assertThat(command.path("network").asText()).isEqualTo("hosts");
        assertThat(command.path("network_hosts")).extracting(JsonNode::asText)
                .containsExactly("api.claude.example:443", "gateway.corp.example:8443");
    }

    @Test
    void missingAiImage_isRefusedAtStart() {
        fx.withClaude();
        fx.containers.absent(CLAUDE_TAG);

        assertThatThrownBy(() -> fx.services().start(request(REVIEW), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("image " + CLAUDE_TAG + " is missing: run 'traceworks images build'");
        assertThat(fx.store.runIds()).isEmpty();
    }

    private FakeAiCli piWithBackends() {
        FakeAiCli pi = FakeAiCli.pi()
                .credentialNamesFor("sonnet", "ANTHROPIC_API_KEY", "ANTHROPIC_OAUTH_TOKEN")
                .credentialNamesFor(null, "ANTHROPIC_API_KEY", "ANTHROPIC_OAUTH_TOKEN").backendFor(null, "anthropic")
                .credentialNamesFor("openai/gpt-5.5", "OPENAI_API_KEY")
                .unknownModel("ollama/llama3", "The Pi model ollama/llama3 names the backend ollama");
        fx.clis.add(pi);
        fx.withRuntime();
        fx.containers.present("traceworks/pi:aaaaaaaaaaaa", "sha256:" + "d".repeat(64));
        fx.containers.present(AppFixture.EGRESS_TAG, AppFixture.EGRESS_ID);
        fx.environment.put("OPENAI_API_KEY", "sk-proj-Openai_key-0123456789");
        return pi;
    }

    private static String piPackage(String ask) {
        return """
                {"id": "wf-pi", "name": "Pi", "provider": "PI", "blocks": [
                  {"type": "shell", "name": "first", "config": {"command": "echo first"}},
                  {"type": "simple", "name": "ask", "config": %s}
                ]}""".formatted(ask);
    }

    /**
     * A Pi step whose model is written as a literal needs the credential of that model's backend before the run
     * starts: with only the OpenAI key, a step on {@code sonnet} would fail after the steps before it ran.
     */
    @Test
    void aLiteralPiModelWhoseBackendHasNoCredential_isRefusedAtStart_namingTheCredential() {
        piWithBackends();
        fx.packages.put(PI, piPackage("{\"command\": \"Say hi\", \"model\": \"sonnet\"}"));

        assertThatThrownBy(() -> fx.services().start(request(PI), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("AI provider PI has no credential for the model sonnet")
                .hasMessageContaining("ANTHROPIC_API_KEY, ANTHROPIC_OAUTH_TOKEN")
                .hasMessageContaining("traceworks credentials set")
                .hasMessageNotContaining("sk-proj-Openai_key");
        assertThat(fx.store.runIds()).as("no step ran").isEmpty();
    }

    @Test
    void aPiStepWithoutAModel_isRefusedAtStart_sayingWhereItGoes() {
        piWithBackends();
        fx.packages.put(PI, piPackage("{\"command\": \"Say hi\"}"));

        assertThatThrownBy(() -> fx.services().start(request(PI), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("AI provider PI has no credential for a step that names no model, which goes"
                        + " to anthropic")
                .hasMessageContaining("ANTHROPIC_API_KEY, ANTHROPIC_OAUTH_TOKEN");
        assertThat(fx.store.runIds()).isEmpty();
    }

    /**
     * The backend with a credential starts. A model from a placeholder passes the start check, which judges literal
     * models, and its run is refused by the admission before its first block: no run resolves the placeholder.
     */
    @Test
    void aLiteralPiModelWhoseBackendHasACredential_starts_andAModelFromAPlaceholder_isRefusedByTheAdmission() {
        FakeAiCli pi = piWithBackends();
        pi.answers("hi", "hi");
        fx.packages.put(PI, piPackage("{\"command\": \"Say hi\", \"model\": \"openai/gpt-5.5\"}"));
        assertThat(fx.services().start(request(PI), null, null).status()).isEqualTo(RunStatus.COMPLETED);

        fx.packages.put(PI, piPackage("{\"command\": \"Say hi\", \"model\": \"{{ which }}\"}"));
        RunResult late = fx.services().start(request(PI), null, null);
        assertThat(fx.store.runIds()).as("the start check let it start").hasSize(2);
        assertThat(late.status()).isEqualTo(RunStatus.FAILED);
        assertThat(late.error()).startsWith("workflow refused: ").contains("holds a placeholder, which no run"
                + " resolves");
        assertThat(pi.calls()).as("only the first run called the model").hasSize(1);
    }

    /** {@code check} reports a literal model whose backend the CLI cannot tell, with the CLIs of the services. */
    @Test
    void check_reportsALiteralModelOfAnUnknownBackend() {
        piWithBackends();
        fx.packages.put(PI, piPackage("{\"command\": \"Say hi\", \"model\": \"ollama/llama3\"}"));

        assertThat(fx.services().checkBeforeRun(request(PI), true).findings())
                .filteredOn(f -> f.rule().equals("unknown-model-backend"))
                .extracting(f -> f.severity().name() + " " + f.detail())
                .containsExactly("ERROR The Pi model ollama/llama3 names the backend ollama");
    }

    /** The default provider is the workflow's: its credentials are the ones read, its CLI the one that runs. */
    @Test
    void workflowProvider_isTheDefault_andNeedsItsOwnCredentials() {
        FakeAiCli pi = FakeAiCli.pi();
        fx.clis.add(pi);
        fx.withRuntime();
        fx.containers.present("traceworks/pi:aaaaaaaaaaaa", "sha256:" + "d".repeat(64));
        fx.containers.present(AppFixture.EGRESS_TAG, AppFixture.EGRESS_ID);
        pi.answers("hi");

        assertThatThrownBy(() -> fx.services().start(request(PI), null, null))
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("PI")
                .hasMessageContaining("OPENROUTER_API_KEY");

        fx.environment.put("OPENAI_API_KEY", "sk-proj-Openai_key-0123456789");
        RunResult result = fx.services().start(request(PI), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(pi.calls()).hasSize(1);
        assertThat(fx.claude.calls()).isEmpty();
        assertThat(fx.containers.runs().getFirst().environment())
                .containsEntry("OPENAI_API_KEY", "sk-proj-Openai_key-0123456789");
    }

    // ---- the workflows a run reaches ----

    /** A parent without AI steps that runs a child, by a literal workflow_id, whose step uses Pi. */
    private static final Path PARENT = Path.of("/pkg/parent-pi");

    private FakeAiCli withPiChild() {
        fx.packages.put(PARENT, """
                {"id": "wf-parent-pi", "name": "Parent", "blocks": [
                  {"type": "shell", "name": "build", "config": {"command": "./build"}},
                  {"type": "sub-workflow", "name": "child", "config": {"workflow_id": "./child"}}
                ]}""");
        fx.packages.put(PARENT.resolve("child"), """
                {"id": "wf-child-pi", "name": "Child", "provider": "PI", "blocks": [
                  {"type": "simple", "name": "ask", "config": {"command": "Say hi"}}
                ]}""");
        FakeAiCli pi = FakeAiCli.pi();
        fx.clis.add(pi);
        fx.withRuntime();
        return pi;
    }

    @Test
    void childWorkflow_itsProviderNeedsItsImageAndCredential_atStart() {
        FakeAiCli pi = withPiChild();
        fx.credentialsLocation = "/home/x/.traceworks/credentials.json";

        assertThatThrownBy(() -> fx.services().start(request(PARENT), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("image traceworks/pi:aaaaaaaaaaaa is missing")
                .hasMessageContaining("AI provider PI has no credential: store one with 'traceworks credentials set"
                        + " <NAME>' (in /home/x/.traceworks/credentials.json), or set it as an environment variable"
                        + " of Traceworks, under one of the names ANTHROPIC_API_KEY, ANTHROPIC_OAUTH_TOKEN,")
                .hasMessageContaining("'claude setup-token' on the host prints a token for ANTHROPIC_OAUTH_TOKEN");
        assertThat(fx.store.runIds()).isEmpty();

        fx.containers.present("traceworks/pi:aaaaaaaaaaaa", "sha256:" + "d".repeat(64));
        fx.containers.present(AppFixture.EGRESS_TAG, AppFixture.EGRESS_ID);
        fx.environment.put("OPENAI_API_KEY", "sk-proj-Openai_key-0123456789");
        pi.answers("hi");
        RunResult result = fx.services().start(request(PARENT), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(pi.calls()).hasSize(1);
        assertThat(fx.containers.runs().getLast().environment())
                .containsEntry("OPENAI_API_KEY", "sk-proj-Openai_key-0123456789");
        assertThat(fx.claude.calls()).isEmpty();
    }

    /**
     * A child that names Codex, the child's provider or a block's own, at any depth, refuses the run at its start,
     * before the run exists and before any block runs, with the admission's message and the child's id: its own
     * admission would refuse it only when its step runs, after the blocks before it.
     */
    @Test
    void childWorkflowOfCodex_isRefusedAtStart_beforeAnyBlockRuns() {
        fx.packages.put(PARENT, """
                {"id": "wf-parent-codex", "name": "Parent", "blocks": [
                  {"type": "shell", "name": "build", "config": {"command": "./build"}},
                  {"type": "sub-workflow", "name": "child", "config": {"workflow_id": "./child"}}
                ]}""");
        fx.packages.put(PARENT.resolve("child"), """
                {"id": "wf-child-codex", "name": "Child", "provider": "codex", "blocks": [
                  {"type": "shell", "name": "list", "config": {"command": "ls"}},
                  {"type": "foreach", "name": "each", "config": {"items": ["a"], "workflow_id": "./grandchild"}}
                ]}""");
        fx.packages.put(PARENT.resolve("child").resolve("grandchild"), """
                {"id": "wf-grandchild", "name": "Grandchild", "blocks": [
                  {"type": "simple", "name": "ask", "config": {"command": "Say hi", "provider": " CODEX "}}
                ]}""");
        fx.withRuntime();

        assertThatThrownBy(() -> fx.services().start(request(PARENT), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("the child workflow wf-child-codex: provider codex: "
                        + RunRequirements.CODEX_REFUSAL)
                .hasMessageContaining("the child workflow wf-grandchild: Block 1 \"ask\": provider CODEX: "
                        + RunRequirements.CODEX_REFUSAL);
        assertThat(fx.store.runIds()).as("refused before the run exists").isEmpty();
        assertThat(fx.commands.requests()).as("no block ran").isEmpty();
        assertThat(fx.containers.runs()).isEmpty();
    }

    /** A literal reference is read as the run reads it, without its edge spaces (fix round 2). */
    @Test
    void literalReferenceWithEdgeSpaces_isWalkedAtStart() {
        withPiChild();
        fx.packages.put(PARENT, """
                {"id": "wf-parent-pi", "name": "Parent", "blocks": [
                  {"type": "sub-workflow", "name": "child", "config": {"workflow_id": " ./child "}}
                ]}""");

        assertThatThrownBy(() -> fx.services().start(request(PARENT), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("image traceworks/pi:aaaaaaaaaaaa is missing");
    }

    /** A cycle of literal references (parent, child, parent again) ends the walk; the run refuses it later. */
    @Test
    @Timeout(10)
    void childWorkflowCycle_endsTheStartCheck() {
        withPiChild();
        fx.packages.put(PARENT.resolve("child"), """
                {"id": "wf-child-pi", "name": "Child", "provider": "PI", "blocks": [
                  {"type": "simple", "name": "ask", "config": {"command": "Say hi"}},
                  {"type": "sub-workflow", "name": "back", "config": {"workflow_id": ".."}}
                ]}""");

        assertThatThrownBy(() -> fx.services().start(request(PARENT), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("image traceworks/pi:aaaaaaaaaaaa is missing");
    }

    /**
     * A reference that names no workflow, or holds a placeholder, is not followed: the step fails when it runs, as
     * before, and the start check asks for nothing on its account.
     */
    @Test
    void unresolvableOrPlaceholderReference_isSkippedByTheStartCheck() {
        fx.withRuntime();
        fx.environment.clear();
        fx.packages.put(PARENT, """
                {"id": "wf-parent-pi", "name": "Parent", "blocks": [
                  {"type": "sub-workflow", "name": "gone", "config": {"workflow_id": "./missing"}},
                  {"type": "sub-workflow", "name": "chosen", "config": {"workflow_id": "{{ which }}"}}
                ]}""");

        RunResult result = fx.services().start(request(PARENT), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(fx.store.runIds()).hasSize(1);
    }

    /**
     * A child named by a placeholder is not seen by the start check, so the credentials of its provider are not read:
     * its AI step fails, naming them and how to make the start check see the workflow (fix round 2).
     */
    @Test
    void placeholderChild_whoseProviderTheStartCheckDidNotSee_failsNamingItsCredentials() {
        withPiChild();
        fx.containers.present("traceworks/pi:aaaaaaaaaaaa", "sha256:" + "d".repeat(64));
        fx.packages.put(PARENT, """
                {"id": "wf-parent-pi", "name": "Parent", "blocks": [
                  {"type": "sub-workflow", "name": "child", "config": {"workflow_id": "{{ which }}"}}
                ]}""");
        fx.environment.put("OPENAI_API_KEY", "sk-proj-Openai_key-0123456789");

        RunResult result = fx.services().start(new StartRequest(PARENT, Map.of("which", "./child"),
                AppFixture.WORKSPACE, false, false), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        RecordedEvent response = fx.ofType(result.runId(), "ai.response").getFirst();
        assertThat(text(result.runId(), response, "error")).startsWith("AI provider PI has no credential in this run:"
                + " the start check did not see the workflow of this step").contains("OPENAI_API_KEY")
                .contains("literal workflow_id");
        assertThat(fx.containers.runs()).as("the CLI never started").isEmpty();
    }

    /** A run without AI steps reads no credential, so a credential nobody uses can never refuse it. */
    @Test
    void runWithoutAiSteps_readsNoCredential() {
        fx.withRuntime();
        fx.environment.put("ANTHROPIC_API_KEY", "short");

        assertThat(fx.services().start(request(BUILD), null, null).status()).isEqualTo(RunStatus.COMPLETED);
    }

    /**
     * A host run still runs its AI steps in a container: the host runner never sees them. A host run starts only where
     * no runtime works, so this one parks there and is resumed where one works.
     */
    @Test
    void hostRun_runsItsAiStepsInAContainer() {
        fx.settings = new OperatorSettings(true, null, List.of());
        Path later = Path.of("/pkg/review-later");
        fx.packages.put(later, """
                {"id": "wf-review-later", "name": "Review later", "blocks": [
                  {"type": "approval", "name": "go", "config": {"question": "Go?", "options": ["Yes", "No"]}},
                  {"type": "shell", "name": "build", "config": {"command": "./build"}},
                  {"type": "simple", "name": "review", "config": {"command": "Review {{ previous_output }}"}}
                ]}""");
        fx.environment.put("ANTHROPIC_API_KEY", AppFixture.ANTHROPIC_KEY);
        String id = fx.services().start(request(later), null, null).runId();
        fx.services().answer(id, new AnswerRequest("approval", 0, "Yes", null, null));
        fx.withClaude();
        fx.claude.answers("ok");

        RunResult result = fx.services().resume(AppFixture.resumeOf(id), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.commands.commands()).as("only the shell step runs on the host").containsExactly("./build");
        assertThat(fx.containers.runs()).singleElement()
                .satisfies(run -> assertThat(run.imageId()).isEqualTo(CLAUDE_ID));
    }

    /** A resume reads the credentials again and passes them on as at the start. */
    @Test
    void resume_checksAndPassesTheCredentialsAgain() {
        fx.withClaude();
        fx.packages.put(Path.of("/pkg/ask"), """
                {"id": "wf-ask", "name": "Ask", "blocks": [
                  {"type": "approval", "name": "go", "config": {"question": "Go?", "options": ["Yes", "No"]}},
                  {"type": "simple", "name": "say", "config": {"command": "Say hi"}}
                ]}""");
        String id = fx.services().start(request(Path.of("/pkg/ask")), null, null).runId();
        fx.services().answer(id, new AnswerRequest("approval", 0, "Yes", null, null));
        fx.environment.clear();

        assertThatThrownBy(() -> fx.services().resume(AppFixture.resumeOf(id), null, null))
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("ANTHROPIC_API_KEY");

        String rotated = "sk-ant-api03-Rotated_Key-9876543210";
        fx.environment.put("ANTHROPIC_API_KEY", rotated);
        fx.claude.answers("hi");
        RunResult resumed = fx.services().resume(AppFixture.resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.containers.runs().getLast().environment()).as("the value read at the resume, not at the start")
                .containsEntry("ANTHROPIC_API_KEY", rotated).doesNotContainValue(ANTHROPIC_KEY);
    }
}
