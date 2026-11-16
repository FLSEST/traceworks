package traceworks.adapters.ai;

import traceworks.core.ai.AiProviderCapabilities;
import traceworks.core.ai.ToolPolicy;
import traceworks.core.port.AiCli;
import traceworks.core.port.AiCli.AiCall;
import traceworks.core.port.AiCli.AiInvocation;
import traceworks.core.port.CommandRunner.CommandResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How the Pi CLI is started:
 * its arguments per call and tool policy, the system prompt in a file, nothing from the workspace, a prompt that is
 * text, and the stream filter in the container. Every fact about the CLI was checked in the image
 * ({@code @earendil-works/pi-coding-agent@1.5.2}, {@link PiCli#VERSION}), offline, against a fake Anthropic API on
 * the container's loopback.
 */
class PiCliTest {

    private static final String FILE = "/tmp/traceworks-system-prompt";
    /**
     * What every call starts with: one call that keeps no session, the JSON event stream, and nothing that the
     * workspace or a home directory could configure.
     */
    private static final List<String> START = List.of("pi", "-p", "--no-session", "--no-context-files", "--mode",
            "json", "--offline", "--no-extensions", "--no-mcp", "--no-approve", "--no-skills", "--no-prompt-templates",
            "--no-themes");
    private static final Map<ToolPolicy, List<String>> TOOL_FLAGS = Map.of(
            ToolPolicy.NONE, List.of("--no-tools"),
            ToolPolicy.READ, List.of("--tools", "read,grep,find,ls"),
            ToolPolicy.WRITE, List.of("--tools", "read,grep,find,ls,edit,write"),
            ToolPolicy.SHELL, List.of("--tools", "read,grep,find,ls,edit,write,bash"));
    /** The system prompt flags of a call without a system prompt: both given, both empty. */
    private static final List<String> NO_SYSTEM_PROMPT = List.of("--system-prompt", "", "--append-system-prompt", "");

    private final PiCli cli = new PiCli();

    private static AiCall reason(String systemPrompt, String model, String effort, Integer maxTurns,
                                 ToolPolicy tools) {
        return new AiCall(AiCall.Kind.REASON, "Think about it", systemPrompt, model, effort, tools, maxTurns, 60);
    }

    private static AiCall action(String systemPrompt, String model, String effort, ToolPolicy tools) {
        return new AiCall(AiCall.Kind.ACTION, "Do it", systemPrompt, model, effort, tools, null, 60);
    }

    /**
     * Pi's own arguments, behind the stream filter (and, with a system prompt, behind the launch script before it).
     */
    private static List<String> cliArgs(AiInvocation invocation) {
        List<String> args = invocation.args();
        int filter = args.indexOf(PiCli.STREAM_FILTER);
        assertThat(args.subList(filter - 2, filter)).containsExactly("sh", "-c");
        assertThat(args.get(filter + 1)).isEqualTo("sh");
        return args.subList(filter + 2, args.size());
    }

    private static List<String> concat(List<?>... parts) {
        List<String> all = new ArrayList<>();
        for (List<?> part : parts) {
            part.forEach(arg -> all.add((String) arg));
        }
        return all;
    }

    private static List<String> thinking(String level) {
        return List.of("--thinking", level);
    }

    @Test
    void idImageAndCapabilities() {
        assertThat(cli.id()).isEqualTo("PI");
        assertThat(cli.image()).isEqualTo("pi");
        assertThat(cli.capabilities()).as("the JSON stream carries the tokens, so they are no longer estimated")
                .isEqualTo(new AiProviderCapabilities(true, true, true, true, true, true, true, true));
        assertThat(AiCli.credentialNamesOf(cli.id())).containsExactly("ANTHROPIC_API_KEY", "ANTHROPIC_OAUTH_TOKEN",
                "OPENAI_API_KEY", "GEMINI_API_KEY", "OPENROUTER_API_KEY");
    }

    @Test
    void arguments_reason() {
        AiInvocation plain = cli.invocation(reason(null, null, null, null, ToolPolicy.NONE));
        AiInvocation full = cli.invocation(reason("You plan.", "openai/gpt-5.5", "HIGH", 1, ToolPolicy.READ));

        assertThat(plain.args().subList(0, 4)).as("without a system prompt the stream filter starts Pi")
                .containsExactly("sh", "-c", PiCli.STREAM_FILTER, "sh");
        assertThat(cliArgs(plain)).isEqualTo(concat(START, thinking("medium"), NO_SYSTEM_PROMPT,
                TOOL_FLAGS.get(ToolPolicy.NONE)));
        assertThat(full.args().subList(0, 8)).as("the launch script starts the stream filter").containsExactly("sh",
                "-c", SystemPromptLaunch.SCRIPT, "sh", "sh", "-c", PiCli.STREAM_FILTER, "sh");
        assertThat(cliArgs(full)).as("a reasoning call replaces Pi's system prompt").isEqualTo(concat(START,
                List.of("--provider", "openai", "--model", "gpt-5.5"), thinking("high"),
                List.of("--system-prompt", FILE, "--append-system-prompt", ""), TOOL_FLAGS.get(ToolPolicy.READ)));
    }

    /**
     * The thinking level: a valid effort wins, else a level after the model's last {@code :} (Pi 1.5.2 knows
     * {@code off}, {@code minimal}, {@code low}, {@code medium}, {@code high}, {@code xhigh} and {@code max}), else a
     * reasoning call's turn limit, else {@value PiCli#DEFAULT_THINKING}. The model keeps its suffix.
     */
    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "HIGH,  sonnet:low,   null, high",
            "null,  sonnet:off,   null, off",
            "null,  sonnet:xhigh, null, xhigh",
            "null,  sonnet:max,   null, max",
            "null,  sonnet:huge,  1,    low",
            "null,  sonnet,       3,    medium",
            "null,  sonnet,       4,    high",
            "null,  null,         null, medium",
            "max,   null,         null, medium"})
    void thinking_effortThenModelSuffixThenTurns(String effort, String model, Integer turns, String level) {
        List<String> args = cliArgs(cli.invocation(reason(null, model, effort, turns, ToolPolicy.NONE)));

        assertThat(args.get(args.indexOf("--thinking") + 1)).isEqualTo(level);
        if (model != null) {
            assertThat(args.get(args.indexOf("--model") + 1)).isEqualTo(model);
        }
    }

    /** The image installs the Pi these arguments were checked against: a new Pi is checked again first. */
    @Test
    void thePiImage_installsTheVersionTheArgumentsWereCheckedAgainst() throws IOException {
        String dockerfile = Files.readString(Path.of("../images/pi/Dockerfile"));

        assertThat(dockerfile).containsOnlyOnce("npm install -g @earendil-works/pi-coding-agent@" + PiCli.VERSION
                + " ");
        assertThat(PiCli.VERSION).isEqualTo("1.5.2");
    }

    static Stream<Arguments> policies() {
        return Stream.of(ToolPolicy.NONE, ToolPolicy.READ, ToolPolicy.WRITE, ToolPolicy.SHELL)
                .map(policy -> Arguments.of(policy, TOOL_FLAGS.get(policy)));
    }

    /** Every call carries its block's tools, a reasoning call too: none runs with {@code --no-tools} by itself. */
    @ParameterizedTest
    @MethodSource("policies")
    void arguments_action_perPolicy(ToolPolicy policy, List<String> toolFlags) {
        AiInvocation full = cli.invocation(action("Be careful.", "claude-sonnet-4-6", "low", policy));
        AiInvocation plain = cli.invocation(action(null, null, null, policy));

        assertThat(cliArgs(full)).as("an action adds to Pi's system prompt").isEqualTo(concat(START,
                List.of("--provider", "anthropic", "--model", "claude-sonnet-4-6"), thinking("low"),
                List.of("--system-prompt", "", "--append-system-prompt", FILE), toolFlags));
        assertThat(cliArgs(plain)).isEqualTo(concat(START, thinking("medium"), NO_SYSTEM_PROMPT, toolFlags));
        assertThat(cliArgs(cli.invocation(reason("s", null, null, null, policy)))).endsWith(toolFlags.toArray(
                String[]::new));
    }

    @Test
    void environment() {
        assertThat(cli.invocation(action(null, null, null, ToolPolicy.NONE)).environment()).isEmpty();
        assertThat(cli.invocation(reason("You plan.", null, null, null, ToolPolicy.NONE)).environment())
                .as("the system prompt, base64-encoded").containsExactlyEntriesOf(Map.of("TRACEWORKS_SYSTEM_PROMPT",
                        "WW91IHBsYW4u"));
    }

    /**
     * Every process can read a command line, so the prompt goes on stdin, which
     * {@code pi -p} reads when no message follows on the command line, and the system prompt into a file.
     */
    @Test
    void noPromptText_userOrSystem_isInTheArguments() {
        String prompt = "Review the diff of sk-live-PROMPT-7f3a";
        String systemPrompt = "You are careful; token sk-live-SYSTEM-9c1b";
        for (AiCall.Kind kind : AiCall.Kind.values()) {
            AiInvocation invocation = cli.invocation(new AiCall(kind, prompt, systemPrompt, "anthropic/opus", null,
                    ToolPolicy.READ, null, 60));

            assertThat(invocation.args()).noneMatch(arg -> arg.contains("PROMPT-7f3a") || arg.contains("SYSTEM-9c1b")
                    || arg.contains("careful") || arg.contains("Review"));
            assertThat(invocation.stdin()).isEqualTo(prompt);
            assertThat(invocation.environment()).containsEntry("TRACEWORKS_SYSTEM_PROMPT", Base64.getEncoder()
                    .encodeToString(systemPrompt.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** The launch script and the stream filter are constants, whatever the system prompt says. */
    @Test
    void theLaunchScriptAndTheStreamFilter_areConstants() {
        AiInvocation one = cli.invocation(reason("first", "opus", null, 3, ToolPolicy.READ));
        AiInvocation other = cli.invocation(reason("a second\nsystem prompt with 'quotes' and $(commands)", "opus",
                null, 3, ToolPolicy.READ));

        assertThat(one.args()).isEqualTo(other.args());
        assertThat(one.args().get(2)).isSameAs(SystemPromptLaunch.SCRIPT);
        assertThat(one.args().get(6)).isSameAs(PiCli.STREAM_FILTER);
    }

    /**
     * Pi's JSON stream repeats a tool's arguments with each piece of its output ({@code tool_execution_update}), and
     * Pi 0.74.2 repeated the whole message so far with each streamed piece ({@code message_update}): a 100 KB file
     * written in 50-character pieces came to 404 MB of output, which Traceworks would hold in memory. The filter
     * drops those two events in the container, where nothing else reads them, keeps every other line byte for byte
     * (as text, whatever its bytes), and ends with Pi's exit code. It fails with grep's own status when grep fails,
     * and says so when it cannot keep Pi's exit code.
     */
    @Test
    void theStreamFilter_dropsThePartialMessages_andKeepsPisExitCode() {
        assertThat(PiCli.STREAM_FILTER).isEqualTo("rm -f /tmp/traceworks-pi-exit;"
                + " { \"$@\"; echo $? > /tmp/traceworks-pi-exit; }"
                + " | LC_ALL=C grep -a -v -e '^{\"type\":\"message_update\",'"
                + " -e '^{\"type\":\"tool_execution_update\",'; g=$?; [ \"$g\" -le 1 ] || exit \"$g\";"
                + " s=$(cat /tmp/traceworks-pi-exit 2>/dev/null); case \"$s\" in ''|*[!0-9]*)"
                + " echo \"traceworks: Pi's exit code could not be kept in /tmp/traceworks-pi-exit\" >&2;"
                + " exit 74;; esac; exit \"$s\"");
    }

    @Test
    void aSystemPromptOver95KiB_isRefused() {
        assertThatThrownBy(() -> cli.invocation(action("x".repeat(95 * 1024 + 1), null, null, ToolPolicy.NONE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 97280 (95 KiB)");
    }

    /**
     * Nothing from the workspace, for every credential: every call starts in the workspace, and the invocation is
     * built without the credential. Checked in the CLI of the image, offline, with a writable and a read-only
     * workspace holding {@code AGENTS.md}, {@code CLAUDE.md}, {@code .pi/SYSTEM.md}, {@code .pi/APPEND_SYSTEM.md},
     * {@code .pi/mcp.json}, an extension, a skill, a prompt template, an {@code .agents} skill and a
     * {@code .pi/settings.json} with a package to install, a shell prefix, default tools and the built-in extensions,
     * and with a home whose settings trust every project and whose {@code mcp.json} names a server:
     * <ul>
     *   <li>{@code --no-context-files}: no {@code AGENTS.md} or {@code CLAUDE.md} in the system prompt;</li>
     *   <li>{@code --no-extensions}: the workspace's extension, which is code, is not loaded, and neither are Pi's
     *       built-in extensions ({@code mcp}, {@code codemode}, {@code tool-search}, {@code llama.cpp});</li>
     *   <li>{@code --no-mcp}: no MCP server starts and no MCP tool exists, which {@code --tools} would keep (only an
     *       entry that starts with {@code mcp__} filters them), also should the extensions be on;</li>
     *   <li>{@code --no-approve}: the workspace is not trusted, whatever the home says, so none of its
     *       {@code .pi/} settings or resources apply: without it a home that trusts projects lets a writable
     *       workspace's {@code shellCommandPrefix} run before each command of the {@code bash} tool;</li>
     *   <li>{@code --no-skills}, {@code --no-prompt-templates}, {@code --no-themes}: none of the workspace's
     *       resources;</li>
     *   <li>{@code --offline}: no package of a settings file is installed, which would run the settings'
     *       {@code npmCommand} even without tools, no tool binary is downloaded and no catalog or version is
     *       fetched;</li>
     *   <li>{@code --system-prompt} and {@code --append-system-prompt}, given on every call, empty when the call has
     *       no system prompt: no {@code .pi/SYSTEM.md} replaces Pi's system prompt and no
     *       {@code .pi/APPEND_SYSTEM.md} is added to it, also in a trusted workspace;</li>
     *   <li>{@code --thinking} on every call: no settings file chooses the thinking level.</li>
     * </ul>
     */
    @Test
    void workspaceConfiguration_isOff_forEveryCall() {
        for (AiCall.Kind kind : AiCall.Kind.values()) {
            for (ToolPolicy policy : TOOL_FLAGS.keySet()) {
                for (String systemPrompt : new String[] {null, "s"}) {
                    List<String> args = cliArgs(cli.invocation(new AiCall(kind, "p", systemPrompt, null, null,
                            policy, null, 60)));

                    assertThat(args.subList(0, START.size())).isEqualTo(START);
                    for (String once : List.of("--system-prompt", "--append-system-prompt", "--thinking")) {
                        assertThat(args.indexOf(once)).as(once).isPositive().isEqualTo(args.lastIndexOf(once));
                    }
                    assertThat(args).doesNotContain("-e", "--extension", "--skill", "--prompt-template", "--theme",
                            "--session", "--session-dir", "--continue", "-c", "--resume", "-r", "--fork", "--api-key",
                            "--no-builtin-tools", "--verbose", "--approve", "-a");
                }
            }
        }
    }

    /**
     * A prompt is text, never a command. On stdin, {@code @file} is never read; a workspace's prompt template
     * ({@code /name}), skill ({@code /skill:name}) or extension command would expand or run, and do not, since none
     * is loaded; Pi's own commands ({@code /compact}, {@code /login}) exist only in its interactive mode, so the model
     * gets them as text. {@code -p} takes the next argument as a message unless it starts with {@code -} or
     * {@code @}, so a flag follows it, and no argument is a message or a file.
     */
    @Test
    void aPromptIsText_neverACommandOrAFile() {
        for (AiCall.Kind kind : AiCall.Kind.values()) {
            AiInvocation invocation = cli.invocation(new AiCall(kind, "/skill:review @secrets.txt", "s", "m",
                    "high", ToolPolicy.SHELL, 2, 60));
            List<String> args = cliArgs(invocation);

            assertThat(args.get(args.indexOf("-p") + 1)).startsWith("--");
            assertThat(args).contains("--no-extensions", "--no-skills", "--no-prompt-templates")
                    .noneMatch(arg -> arg.startsWith("@"));
            assertThat(invocation.stdin()).isEqualTo("/skill:review @secrets.txt");
        }
    }

    @Test
    void parse_readsTheStream() throws IOException {
        String stream;
        try (InputStream in = getClass().getResourceAsStream("/pi/tools.jsonl")) {
            stream = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        CommandResult result = new CommandResult(0, stream, "", 900, false);
        AiCall call = action(null, null, null, ToolPolicy.READ);

        assertThat(cli.parse(result, call)).isEqualTo(PiStream.outcome(result, call.prompt()));
    }

    /** The host is the API of the backend the model names, as the arguments name it; the default backend without. */
    @Test
    void hosts_areTheApiOfTheModelsBackend() {
        assertThat(cli.hosts(action(null, null, null, ToolPolicy.NONE))).containsExactly("api.anthropic.com:443");
        assertThat(cli.hosts(action(null, "opus", null, ToolPolicy.NONE))).containsExactly("api.anthropic.com:443");
        assertThat(cli.hosts(action(null, "anthropic/opus", null, ToolPolicy.NONE)))
                .containsExactly("api.anthropic.com:443");
        assertThat(cli.hosts(action(null, "openai/gpt-5.5", null, ToolPolicy.NONE)))
                .containsExactly("api.openai.com:443");
        assertThat(cli.hosts(action(null, "google/gemini-3:high", null, ToolPolicy.NONE)))
                .containsExactly("generativelanguage.googleapis.com:443");
        assertThat(cli.hosts(action(null, "openrouter/meta/llama", null, ToolPolicy.NONE)))
                .containsExactly("openrouter.ai:443");
        assertThat(cli.hosts(action(null, "/opus", null, ToolPolicy.NONE))).as("no backend: the default")
                .containsExactly("api.anthropic.com:443");
    }

    /** A backend without a built-in host has none; its call is refused for its credentials. */
    @Test
    void hosts_ofABackendWithoutABuiltInHost_areEmpty() {
        assertThat(cli.hosts(action(null, "ollama/llama3", null, ToolPolicy.NONE))).isEmpty();
        assertThat(cli.hosts(action(null, "OpenAI/gpt-5.5", null, ToolPolicy.NONE)))
                .as("a backend is compared as the arguments carry it").isEmpty();
    }

    private List<String> credentialNames(String model) {
        return cli.credentialNames(action(null, model, null, ToolPolicy.NONE));
    }

    @Test
    void credentialNames_anthropic() {
        assertThat(credentialNames("anthropic/opus")).containsExactly("ANTHROPIC_API_KEY", "ANTHROPIC_OAUTH_TOKEN");
        assertThat(credentialNames("opus:high")).as("a model without a backend goes to the default backend")
                .containsExactly("ANTHROPIC_API_KEY", "ANTHROPIC_OAUTH_TOKEN");
        assertThat(credentialNames(null)).as("no model: Pi chooses among the backends it has a credential for, so it"
                + " gets the default backend's only").containsExactly("ANTHROPIC_API_KEY", "ANTHROPIC_OAUTH_TOKEN");
    }

    @Test
    void credentialNames_openai() {
        assertThat(credentialNames("openai/gpt-5.5")).containsExactly("OPENAI_API_KEY");
    }

    @Test
    void credentialNames_google() {
        assertThat(credentialNames("google/gemini-3:high")).containsExactly("GEMINI_API_KEY");
    }

    @Test
    void credentialNames_openrouter() {
        assertThat(credentialNames("openrouter/meta-llama/llama-3.3-70b-instruct"))
                .containsExactly("OPENROUTER_API_KEY");
    }

    /** The backend a call goes to, for a message: the model's, and the default without one. */
    @Test
    void backend_isTheModels_andTheDefaultWithoutOne() {
        assertThat(cli.backend(action(null, null, null, ToolPolicy.NONE))).isEqualTo("anthropic");
        assertThat(cli.backend(action(null, "sonnet", null, ToolPolicy.NONE))).isEqualTo("anthropic");
        assertThat(cli.backend(action(null, "openai/gpt-5.5", null, ToolPolicy.NONE))).isEqualTo("openai");
    }

    /** Every backend with a built-in host has credentials, each one of the provider's. */
    @Test
    void credentialNames_coverTheBackendsWithAHost_andAreThePiCredentials() {
        assertThat(PiCli.BACKEND_CREDENTIALS.keySet()).isEqualTo(PiCli.BACKEND_HOSTS.keySet());
        assertThat(PiCli.BACKEND_CREDENTIALS.values().stream().flatMap(List::stream).toList())
                .containsExactlyInAnyOrderElementsOf(AiCli.credentialNamesOf("PI"));
    }

    /** A backend Traceworks knows no credential of is refused, saying how to name one it knows. */
    @Test
    void credentialNames_ofAnUnknownBackend_areRefused_sayingHowToNameTheBackend() {
        assertThatThrownBy(() -> credentialNames("ollama/llama3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The Pi model ollama/llama3 names the backend ollama, which is no backend of a provider"
                        + " (anthropic, google, openai, openrouter) and no model server that local_models in"
                        + " traceworks.json declares (it declares none); Traceworks cannot tell which credential the"
                        + " backend takes, so the call gets none and does not run. A model without a backend goes to"
                        + " anthropic");
        assertThatThrownBy(() -> credentialNames("OpenAI/gpt-5.5")).as("a backend is compared as the arguments"
                + " carry it").isInstanceOf(IllegalArgumentException.class).hasMessageContaining("backend OpenAI");
    }

    /** Pi 1.5.2 has no web tool: a call with web is one it cannot make, and its capabilities say so. */
    @Test
    void web_isRefused_piHasNoWebTool() {
        AiCall search = new AiCall(AiCall.Kind.ACTION, "Do it", null, null, null, ToolPolicy.NONE, null, 60,
                new traceworks.core.port.WebAccess(
                        traceworks.core.port.WebAccess.Mode.SEARCH, List.of()));

        assertThat(cli.capabilities().supportsWeb()).isFalse();
        assertThatThrownBy(() -> cli.invocation(search)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Pi has no web tool");
    }

    /**
     * The model comes from the package and becomes a value on the command line. One the CLI could read as a flag
     * ({@code -x}), as a file ({@code @file}) or as two arguments is refused before any container starts.
     */
    @Test
    void aModelTheCliCouldReadAsSomethingElse_isRefused() {
        for (String model : List.of("-x/y", "anthropic/@file", "a b", "--tools=bash", "@file", "anthropic/-x",
                "opus\n--tools", "opus\u0000", "opus\u00a0x", "openrouter/meta/-x")) {
            assertThatThrownBy(() -> cli.invocation(action(null, model, null, ToolPolicy.NONE))).as(model)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("model")
                    .message().doesNotContain("\n", "\u0000");
            assertThatThrownBy(() -> cli.invocation(reason(null, model, null, 3, ToolPolicy.NONE))).as(model)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String model : List.of("opus", "anthropic/claude-opus-4-5", "openrouter/meta/llama-3.1:high",
                "claude-sonnet-4-5[1m]", "/opus")) {
            assertThat(cli.invocation(action(null, model, null, ToolPolicy.NONE)).args()).as(model).isNotEmpty();
        }
    }
}
