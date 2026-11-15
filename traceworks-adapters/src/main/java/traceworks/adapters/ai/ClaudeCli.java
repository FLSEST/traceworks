package traceworks.adapters.ai;

import traceworks.core.ai.AiProviderCapabilities;
import traceworks.core.ai.ReasoningEffort;
import traceworks.core.ai.ToolPolicy;
import traceworks.core.port.AiCli;
import traceworks.core.port.WebAccess;
import traceworks.core.port.CommandRunner.CommandResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Claude Code CLI ({@code claude}, in the built-in image {@code claude}). Every call is
 * {@code claude -p} with the prompt on stdin and the stream on stdout ({@link ClaudeStream}):
 *
 * <pre>
 * claude -p --output-format stream-json --verbose --strict-mcp-config
 *   --setting-sources "" --settings {"disableAllHooks":true} --disable-slash-commands
 *   reason: [--system-prompt-file F] [--model M] [--effort E] --max-turns N   (N: the call's, else 3)
 *   action: [--model M] [--effort E] [--append-system-prompt-file F]
 *   then the tools of the call's policy
 * </pre>
 *
 * <ul>
 *   <li><b>Tools</b>: {@code none} is {@code --tools ""}; {@code read}, {@code write} and {@code shell}
 *       name their tools in {@code --tools} and then skip the CLI's permission prompts, which no one could answer
 *       in a container ({@code --dangerously-skip-permissions}, only ever next to that explicit list).</li>
 *   <li><b>Web</b> ({@link WebAccess}) is a switch of its own and joins the same list: {@code search} adds
 *       {@code WebSearch}, which runs at the provider, and {@code fetch} adds {@code WebFetch} as well, which fetches
 *       a page from the container. So {@code tools: none} with {@code web: search} is {@code --tools WebSearch}.
 *       Checked against Claude Code 2.1.288 without a model call: its init line lists exactly the named tools.</li>
 *   <li><b>System prompt</b>: in a file the container's launch script writes from an environment
 *       variable ({@link SystemPromptLaunch}), never on the command line. A reasoning call replaces the CLI's own
 *       system prompt with it, an action appends it.</li>
 *   <li><b>Nothing from the workspace</b>: the call starts in the workspace, which configures
 *       nothing. {@code --setting-sources ""} reads no settings file of the user, the project or the local
 *       checkout, so none of their hooks, permissions, environment, MCP servers or plugins, nor the project's
 *       commands, skills, agents or {@code CLAUDE.md}; {@code disableAllHooks} switches off every hook but the
 *       managed ones, which only the image could hold; {@code --strict-mcp-config} drops every MCP server not on the
 *       command line, and none is; {@code CLAUDE_CODE_DISABLE_CLAUDE_MDS} switches off every {@code CLAUDE.md} and
 *       rules file. None of it depends on the credential, so a call runs the same with {@code ANTHROPIC_API_KEY}
 *       and {@code CLAUDE_CODE_OAUTH_TOKEN}; {@code --bare} would switch off the same and more, but ignores
 *       {@code CLAUDE_CODE_OAUTH_TOKEN}.</li>
 *   <li><b>A prompt is text, never a command:</b> {@code --disable-slash-commands} runs no slash command and no
 *       skill, so a prompt that starts with {@code /compact} or {@code /init} does not run it; the CLI then answers
 *       that the command is not available, and any other {@code /word} reaches the model as text.</li>
 *   <li><b>Environment:</b> no auto-update and no traffic besides the model's
 *       ({@code DISABLE_AUTOUPDATER}, {@code CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC}); the run passes the
 *       credentials.</li>
 * </ul>
 */
public final class ClaudeCli implements AiCli {

    /** The settings every call adds: no hook runs. */
    static final String NO_HOOKS = "{\"disableAllHooks\":true}";

    /** The turn limit of a reasoning call that names none. */
    static final int DEFAULT_REASONING_TURNS = 3;

    /** The variables every call gets besides the credentials. */
    static final Map<String, String> ENVIRONMENT = Map.of("DISABLE_AUTOUPDATER", "1",
            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1", "CLAUDE_CODE_DISABLE_CLAUDE_MDS", "1");

    private static final List<String> READ_TOOLS = List.of("Read", "Grep", "Glob");
    private static final List<String> WRITE_TOOLS = List.of("Edit", "Write");
    private static final String SHELL_TOOL = "Bash";
    /** The CLI's search tool, which runs at the provider ({@code web: search} and {@code fetch}). */
    private static final String SEARCH_TOOL = "WebSearch";
    /** The CLI's tool that fetches a page itself, from the container ({@code web: fetch}). */
    private static final String FETCH_TOOL = "WebFetch";

    @Override
    public String id() {
        return "CLAUDE";
    }

    @Override
    public String image() {
        return "claude";
    }

    /** Anthropic's API, which Claude Code talks to whatever the call. */
    @Override
    public List<String> hosts(AiCall call) {
        return List.of("api.anthropic.com:443");
    }

    @Override
    public AiProviderCapabilities capabilities() {
        return AiProviderCapabilities.full();
    }

    /**
     * @throws IllegalArgumentException for a system prompt over 95 KiB, and for a model the CLI could read as
     *                                  something else ({@link ModelArgument})
     */
    @Override
    public AiInvocation invocation(AiCall call) {
        ModelArgument.check(call.model());
        List<String> toolFlags = toolFlags(call.tools(), call.web());
        SystemPromptLaunch launch = SystemPromptLaunch.of(call.systemPrompt());
        List<String> cli = new ArrayList<>(List.of("claude", "-p", "--output-format", "stream-json", "--verbose",
                "--strict-mcp-config", "--setting-sources", "", "--settings", NO_HOOKS, "--disable-slash-commands"));
        if (call.kind() == AiCall.Kind.REASON) {
            cli.addAll(launch.option("--system-prompt-file"));
            addModelAndEffort(cli, call);
            cli.addAll(List.of("--max-turns", String.valueOf(call.maxTurns() != null ? call.maxTurns()
                    : DEFAULT_REASONING_TURNS)));
        } else {
            addModelAndEffort(cli, call);
            cli.addAll(launch.option("--append-system-prompt-file"));
        }
        cli.addAll(toolFlags);
        return launch.invocation(cli, ENVIRONMENT, call.prompt());
    }

    private static void addModelAndEffort(List<String> cli, AiCall call) {
        if (call.model() != null && !call.model().isBlank()) {
            cli.addAll(List.of("--model", call.model()));
        }
        ReasoningEffort.parse(call.effort()).ifPresent(effort -> cli.addAll(List.of("--effort", effort.cliValue())));
    }

    private static List<String> toolFlags(ToolPolicy policy, WebAccess web) {
        List<String> tools = new ArrayList<>();
        switch (policy) {
            case NONE -> { }
            case READ -> tools.addAll(READ_TOOLS);
            case WRITE -> {
                tools.addAll(READ_TOOLS);
                tools.addAll(WRITE_TOOLS);
            }
            case SHELL -> {
                tools.addAll(READ_TOOLS);
                tools.addAll(WRITE_TOOLS);
                tools.add(SHELL_TOOL);
            }
        }
        if (web.enabled()) {
            tools.add(SEARCH_TOOL);
        }
        if (web.fetches()) {
            tools.add(FETCH_TOOL);
        }
        if (tools.isEmpty()) {
            return List.of("--tools", "");
        }
        return List.of("--tools", String.join(",", tools), "--dangerously-skip-permissions");
    }

    @Override
    public AiOutcome parse(CommandResult result, AiCall call) {
        return ClaudeStream.outcome(result);
    }
}
