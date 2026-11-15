package traceworks.adapters.ai;

import traceworks.core.port.WebAccess;
import traceworks.core.ai.AiProviderCapabilities;
import traceworks.core.ai.ToolPolicy;
import traceworks.core.port.AiCli;
import traceworks.core.port.AiCli.AiCall;
import traceworks.core.port.AiCli.AiInvocation;
import traceworks.core.port.CommandRunner.CommandResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How the Claude CLI is started: its arguments per call and tool policy, its environment, the system prompt in a
 * file, and no configuration from the workspace.
 */
class ClaudeCliTest {

    private static final String FILE = "/tmp/traceworks-system-prompt";
    /**
     * What every call starts with: stream output, nothing configured by the workspace or the user, and no slash
     * command: a prompt is text, never a command.
     */
    private static final List<String> START = List.of("claude", "-p", "--output-format", "stream-json", "--verbose",
            "--strict-mcp-config", "--setting-sources", "", "--settings", "{\"disableAllHooks\":true}",
            "--disable-slash-commands");
    private static final Map<ToolPolicy, List<String>> TOOL_FLAGS = Map.of(
            ToolPolicy.NONE, List.of("--tools", ""),
            ToolPolicy.READ, List.of("--tools", "Read,Grep,Glob", "--dangerously-skip-permissions"),
            ToolPolicy.WRITE, List.of("--tools", "Read,Grep,Glob,Edit,Write", "--dangerously-skip-permissions"),
            ToolPolicy.SHELL, List.of("--tools", "Read,Grep,Glob,Edit,Write,Bash", "--dangerously-skip-permissions"));

    private final ClaudeCli cli = new ClaudeCli();

    private static AiCall reason(String systemPrompt, String model, String effort, Integer maxTurns,
                                 ToolPolicy tools) {
        return new AiCall(AiCall.Kind.REASON, "Think about it", systemPrompt, model, effort, tools, maxTurns, 60);
    }

    private static AiCall action(String systemPrompt, String model, String effort, ToolPolicy tools) {
        return new AiCall(AiCall.Kind.ACTION, "Do it", systemPrompt, model, effort, tools, null, 60);
    }

    /** The Claude CLI's own arguments: after the launch script, when the call has a system prompt. */
    private static List<String> cliArgs(AiInvocation invocation) {
        List<String> args = invocation.args();
        return args.getFirst().equals("sh") ? args.subList(4, args.size()) : args;
    }

    private static List<String> concat(List<String> first, List<String> second, List<String> third) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        all.addAll(third);
        return all;
    }

    @Test
    void idImageAndCapabilities() {
        assertThat(cli.id()).isEqualTo("CLAUDE");
        assertThat(cli.image()).isEqualTo("claude");
        assertThat(cli.capabilities()).isEqualTo(AiProviderCapabilities.full());
        assertThat(AiCli.credentialNamesOf(cli.id())).containsExactly("ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN");
    }

    @Test
    void arguments_reason() {
        AiInvocation plain = cli.invocation(reason(null, null, null, null, ToolPolicy.NONE));
        AiInvocation full = cli.invocation(reason("You plan.", "opus", "HIGH", 7, ToolPolicy.READ));

        assertThat(plain.args()).as("without a system prompt the CLI starts as the image's program")
                .isEqualTo(concat(START, List.of("--max-turns", "3"), TOOL_FLAGS.get(ToolPolicy.NONE)));
        assertThat(full.args().subList(0, 5)).containsExactly("sh", "-c", SystemPromptLaunch.SCRIPT, "sh", "claude");
        assertThat(cliArgs(full)).isEqualTo(concat(START, List.of("--system-prompt-file", FILE, "--model", "opus",
                "--effort", "high", "--max-turns", "7"), TOOL_FLAGS.get(ToolPolicy.READ)));
        assertThat(cliArgs(cli.invocation(reason(null, " ", "extreme", 2, ToolPolicy.SHELL))))
                .as("a blank model and an unknown effort are left out")
                .isEqualTo(concat(START, List.of("--max-turns", "2"), TOOL_FLAGS.get(ToolPolicy.SHELL)));
    }

    static Stream<Arguments> policies() {
        return Stream.of(ToolPolicy.NONE, ToolPolicy.READ, ToolPolicy.WRITE, ToolPolicy.SHELL)
                .map(policy -> Arguments.of(policy, TOOL_FLAGS.get(policy)));
    }

    @ParameterizedTest
    @MethodSource("policies")
    void arguments_action_perPolicy(ToolPolicy policy, List<String> toolFlags) {
        AiInvocation full = cli.invocation(action("Be careful.", "sonnet", "low", policy));
        AiInvocation plain = cli.invocation(action(null, null, null, policy));

        assertThat(cliArgs(full)).isEqualTo(concat(START, List.of("--model", "sonnet", "--effort", "low",
                "--append-system-prompt-file", FILE), toolFlags));
        assertThat(plain.args()).isEqualTo(concat(START, List.of(), toolFlags));
        for (AiInvocation invocation : List.of(full, plain, cli.invocation(reason("s", "m", "medium", 1, policy)))) {
            List<String> args = invocation.args();
            assertThat(args).doesNotContain("--mcp-config", "--bare", "--allowedTools", "--allowed-tools",
                    "--system-prompt", "--append-system-prompt");
            int skip = args.indexOf("--dangerously-skip-permissions");
            if (skip >= 0) {
                assertThat(args.get(skip - 2)).as("only right after an explicit tool list").isEqualTo("--tools");
                assertThat(args.get(skip - 1)).isNotEmpty();
            }
            assertThat(args.indexOf("--dangerously-skip-permissions")).isEqualTo(args.lastIndexOf(
                    "--dangerously-skip-permissions"));
        }
    }

    @Test
    void environment() {
        Map<String, String> settings = Map.of("DISABLE_AUTOUPDATER", "1",
                "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1", "CLAUDE_CODE_DISABLE_CLAUDE_MDS", "1");

        assertThat(cli.invocation(action(null, null, null, ToolPolicy.NONE)).environment()).isEqualTo(settings);
        Map<String, String> withSystemPrompt = cli.invocation(reason("You plan.", null, null, null,
                ToolPolicy.NONE)).environment();
        assertThat(withSystemPrompt).as("the system prompt, base64-encoded").containsAllEntriesOf(settings)
                .containsEntry("TRACEWORKS_SYSTEM_PROMPT", "WW91IHBsYW4u").hasSize(4);
    }

    /**
     * Every process can read a command line, so the prompt goes on stdin and the system prompt into a file.
     */
    @Test
    void noPromptText_userOrSystem_isInTheArguments() {
        String prompt = "Review the diff of sk-live-PROMPT-7f3a";
        String systemPrompt = "You are careful; token sk-live-SYSTEM-9c1b";
        for (AiCall.Kind kind : AiCall.Kind.values()) {
            AiInvocation invocation = cli.invocation(new AiCall(kind, prompt, systemPrompt, "opus", null,
                    ToolPolicy.READ, null, 60));

            assertThat(invocation.args()).noneMatch(arg -> arg.contains("PROMPT-7f3a") || arg.contains("SYSTEM-9c1b")
                    || arg.contains("careful") || arg.contains("Review"));
            assertThat(invocation.stdin()).isEqualTo(prompt);
            assertThat(invocation.environment()).containsEntry("TRACEWORKS_SYSTEM_PROMPT", Base64.getEncoder()
                    .encodeToString(systemPrompt.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** The launch script is one constant, whatever the system prompt says. */
    @Test
    void theLaunchScript_isAConstant() {
        AiInvocation one = cli.invocation(reason("first", "opus", null, 3, ToolPolicy.READ));
        AiInvocation other = cli.invocation(reason("a second\nsystem prompt with 'quotes' and $(commands)", "opus",
                null, 3, ToolPolicy.READ));

        assertThat(one.args()).isEqualTo(other.args());
        assertThat(one.args().get(2)).isSameAs(SystemPromptLaunch.SCRIPT);
    }

    @Test
    void aSystemPromptOver95KiB_isRefused() {
        assertThatThrownBy(() -> cli.invocation(action("x".repeat(95 * 1024 + 1), null, null, ToolPolicy.NONE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 97280 (95 KiB)");
    }

    /**
     * Every call starts in the workspace, which must configure nothing. Checked in the CLI of the image
     * (2.1.288), offline: {@code --setting-sources ""} reads no user, project or local settings file (so neither
     * their hooks, permissions, environment, MCP servers or plugins, nor the project's commands, skills, agents and
     * {@code CLAUDE.md}); {@code disableAllHooks} switches off every hook but managed ones, which only the image
     * could hold; {@code --strict-mcp-config} drops every MCP server not on the command line, and none is;
     * {@code CLAUDE_CODE_DISABLE_CLAUDE_MDS} switches off every {@code CLAUDE.md} and rules file. No flag depends
     * on the credential: the invocation is built without it, so a workflow behaves the same with
     * {@code ANTHROPIC_API_KEY} and {@code CLAUDE_CODE_OAUTH_TOKEN}. {@code --bare} is not used: it ignores
     * {@code CLAUDE_CODE_OAUTH_TOKEN}.
     */
    @Test
    void workspaceConfiguration_isOff_forEveryCall() {
        for (AiCall.Kind kind : AiCall.Kind.values()) {
            for (ToolPolicy policy : TOOL_FLAGS.keySet()) {
                for (String systemPrompt : new String[] {null, "s"}) {
                    AiInvocation invocation = cli.invocation(new AiCall(kind, "p", systemPrompt, null, null, policy,
                            null, 60));

                    assertThat(cliArgs(invocation).subList(0, START.size())).isEqualTo(START);
                    assertThat(invocation.args()).doesNotContain("--bare", "--setting-sources=project",
                            "--add-dir", "--plugin-dir", "--mcp-config");
                    assertThat(invocation.environment()).containsEntry("CLAUDE_CODE_DISABLE_CLAUDE_MDS", "1");
                }
            }
        }
    }

    @Test
    void parse_readsTheStream() throws IOException {
        String stream;
        try (InputStream in = getClass().getResourceAsStream("/claude/tools.jsonl")) {
            stream = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        CommandResult result = new CommandResult(0, stream, "", 900, false);

        assertThat(cli.parse(result, action(null, null, null, ToolPolicy.READ)))
                .isEqualTo(ClaudeStream.outcome(result));
    }

    /** Claude Code talks to Anthropic's API, whatever the call. */
    @Test
    void hosts_areAnthropicsApi() {
        assertThat(cli.hosts(action(null, null, null, ToolPolicy.NONE))).containsExactly("api.anthropic.com:443");
        assertThat(cli.hosts(reason("s", "opus", "high", 3, ToolPolicy.SHELL))).containsExactly("api.anthropic.com:443");
    }

    private static AiCall withWeb(ToolPolicy tools, WebAccess web) {
        return new AiCall(AiCall.Kind.ACTION, "Do it", null, null, null, tools, null, 60, web);
    }

    private static List<String> toolFlagsOf(AiInvocation invocation) {
        List<String> args = invocation.args();
        return args.subList(args.indexOf("--tools"), args.size());
    }

    /**
     * Web is a switch of its own: its tools join the policy's in the same {@code --tools} list, so {@code tools: none}
     * with {@code web: search} gives exactly {@code WebSearch}. The names are the CLI's own (Claude Code 2.1.288
     * lists them in its init line for these lists).
     */
    @Test
    void arguments_webToolsJoinThePolicysTools() {
        WebAccess search = new WebAccess(WebAccess.Mode.SEARCH, List.of());
        WebAccess fetch = new WebAccess(WebAccess.Mode.FETCH, List.of("docs.example.org:443"));

        assertThat(toolFlagsOf(cli.invocation(withWeb(ToolPolicy.NONE, search))))
                .containsExactly("--tools", "WebSearch", "--dangerously-skip-permissions");
        assertThat(toolFlagsOf(cli.invocation(withWeb(ToolPolicy.NONE, fetch))))
                .containsExactly("--tools", "WebSearch,WebFetch", "--dangerously-skip-permissions");
        assertThat(toolFlagsOf(cli.invocation(withWeb(ToolPolicy.READ, search))))
                .containsExactly("--tools", "Read,Grep,Glob,WebSearch", "--dangerously-skip-permissions");
        assertThat(toolFlagsOf(cli.invocation(withWeb(ToolPolicy.SHELL, fetch))))
                .containsExactly("--tools", "Read,Grep,Glob,Edit,Write,Bash,WebSearch,WebFetch",
                        "--dangerously-skip-permissions");
        assertThat(toolFlagsOf(cli.invocation(withWeb(ToolPolicy.NONE, WebAccess.NONE))))
                .containsExactly("--tools", "");
        AiCall reason = new AiCall(AiCall.Kind.REASON, "Think", null, null, null, ToolPolicy.NONE, 2, 60, search);
        assertThat(toolFlagsOf(cli.invocation(reason))).as("a reasoning call of an agentic block too")
                .containsExactly("--tools", "WebSearch", "--dangerously-skip-permissions");
        assertThat(cli.capabilities().supportsWeb()).isTrue();
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
