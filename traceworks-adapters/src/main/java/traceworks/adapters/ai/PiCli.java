package traceworks.adapters.ai;

import traceworks.core.ai.AiProviderCapabilities;
import traceworks.core.ai.ReasoningEffort;
import traceworks.core.ai.ToolPolicy;
import traceworks.core.port.AiCli;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.LocalModels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The Pi coding agent CLI ({@code pi} of {@code @earendil-works/pi-coding-agent}, in the built-in image {@code pi}).
 * Pi is a hub: one CLI calls the model of any backend it knows (Anthropic, OpenAI, Google,
 * OpenRouter and more), with that backend's credential. Every call is {@code pi -p} with the prompt on stdin and the
 * JSON event stream on stdout ({@link PiStream}):
 *
 * <pre>
 * pi -p --no-session --no-context-files --mode json
 *   --offline --no-extensions --no-mcp --no-approve --no-skills --no-prompt-templates --no-themes
 *   [--provider B --model M] --thinking T          (a local server: --provider traceworks-local --model M)
 *   reason: --system-prompt F|"" --append-system-prompt ""
 *   action: --system-prompt ""   --append-system-prompt F|""
 *   then the tools of the call's policy
 * </pre>
 *
 * <ul>
 *   <li><b>Model</b>: {@code backend/model} routes the call
 *       ({@code openrouter/meta-llama/llama-3.3-70b-instruct} goes to {@code openrouter}); without a slash the
 *       backend is {@value #DEFAULT_BACKEND}; without a model Pi takes the first backend it
 *       has a credential for, and it has that of {@value #DEFAULT_BACKEND} only ({@link #credentialNames}).</li>
 *   <li><b>A model server of the operator's</b> ({@link LocalModels}, {@code local_models} in
 *       {@code traceworks.json}): a model whose backend is one the operator declared goes to that server and to no
 *       provider. Pi gets the server as a provider of its own configuration, which Traceworks writes
 *       ({@link PiModelsLaunch}), and is called with {@code --provider traceworks-local --model <model>}; the call
 *       gets no credential of a provider, only the server's secret when it has one, and its only host is the
 *       server. A model the server does not list is refused. Every option above stays.</li>
 *   <li><b>Thinking</b>: a valid effort wins; else a level after the model's
 *       last {@code :} ({@code sonnet:high}), which Pi itself reads there but an explicit {@code --thinking}
 *       overrides; else a reasoning call maps its turn limit (1 {@code low}, up to 3 {@code medium}, more
 *       {@code high}); else {@value #DEFAULT_THINKING}, Pi's own default. Every call gives it on the command line, so
 *       that a settings file in the workspace cannot choose it. The model keeps its suffix.</li>
 *   <li><b>Tools</b>: {@code none} is {@code --no-tools}; {@code read} is {@code read}, {@code grep},
 *       {@code find} and {@code ls}, {@code write} adds {@code edit} and {@code write}, {@code shell} adds
 *       {@code bash}. Pi asks no permission for them. Its other built-in tool, {@code powershell}, is in no
 *       policy.</li>
 *   <li><b>System prompt</b>: in a file the container's launch script writes from an environment
 *       variable ({@link SystemPromptLaunch}), never on the command line; Pi reads a path it is given that exists. A
 *       reasoning call replaces Pi's own system prompt with it, an action adds it.</li>
 *   <li><b>Nothing from the workspace:</b> the call starts in the workspace, which configures nothing.
 *       {@code --no-context-files} reads no {@code AGENTS.md} or {@code CLAUDE.md}; {@code --no-extensions} loads no
 *       extension, which is code, and none of Pi's built-in ones ({@code mcp}, {@code codemode}, {@code tool-search},
 *       {@code llama.cpp}); {@code --no-mcp} starts no MCP server, which {@code --tools} alone would not keep out
 *       (it keeps MCP tools unless an entry starts with {@code mcp__}); {@code --no-approve} trusts no project, so
 *       none of the workspace's {@code .pi/} settings, MCP servers or resources apply, whatever a home's
 *       {@code trust.json} or {@code defaultProjectTrust} says; {@code --no-skills}, {@code --no-prompt-templates}
 *       and {@code --no-themes} load none of those; {@code --offline} installs no package a settings file names
 *       (installing runs the settings' {@code npmCommand}, tools or not) and fetches nothing at start; both system
 *       prompt options are always given, empty without a system prompt, since either one left out makes Pi read
 *       {@code .pi/SYSTEM.md} or {@code .pi/APPEND_SYSTEM.md} of a trusted workspace. None of it depends on the
 *       credential. Pi still renames a writable workspace's {@code .pi/commands/} to {@code .pi/prompts/} when it
 *       starts, and parses its {@code .pi/settings.json} for a session directory, which {@code --no-session} leaves
 *       unused (of a read-only workspace it cannot lock the file, and says so on stderr).</li>
 *   <li><b>A prompt is text, never a command:</b> on stdin, {@code @file} is not read, and with nothing loaded no
 *       {@code /template}, {@code /skill:name} or extension command expands or runs; Pi's own commands exist only
 *       in its interactive mode.</li>
 *   <li><b>The stream</b> ({@link #STREAM_FILTER}): Pi repeats a tool's arguments with each piece of its output,
 *       so the filter drops those events, and the pieces of a streamed message, in the container.</li>
 * </ul>
 */
public final class PiCli implements AiCli {

    /**
     * The Pi of the built-in image {@code pi}, against which every fact above was checked; {@code PiCliTest} keeps
     * {@code images/pi/Dockerfile} on it, so that a new Pi is checked before it is installed.
     */
    static final String VERSION = "1.5.2";

    /** The backend of a model without one. */
    static final String DEFAULT_BACKEND = "anthropic";

    /** The thinking level of a call that names neither an effort nor a level of its model, nor a turn limit. */
    static final String DEFAULT_THINKING = "medium";

    /**
     * The script that runs Pi behind a filter: one constant, which takes no prompt text. Pi's JSON stream repeats a
     * tool's arguments with each piece of its output ({@code tool_execution_update}); Pi 0.74.2 also repeated the
     * whole message so far with each streamed piece of it ({@code message_update}), and a 100 KB file written in
     * 50-character pieces gave 404 MB (Pi 1.5.2 sends only the piece). Nothing reads those two events, and the filter
     * drops them in the container, before the client holds the output in memory. It keeps every other line as it is
     * ({@code LC_ALL=C}, {@code -a}: any bytes are text), and ends with Pi's exit code, which it keeps in a fresh file
     * in the container's own {@code /tmp}, since {@code sh} has no {@code pipefail}. When grep fails (a status above
     * 1), the output may be cut, and the filter ends with grep's status; when the file cannot be written, it says so
     * on stderr and ends with 74.
     */
    static final String STREAM_FILTER = "rm -f /tmp/traceworks-pi-exit; { \"$@\"; echo $? > /tmp/traceworks-pi-exit; }"
            + " | LC_ALL=C grep -a -v -e '^{\"type\":\"message_update\",' -e '^{\"type\":\"tool_execution_update\",';"
            + " g=$?; [ \"$g\" -le 1 ] || exit \"$g\"; s=$(cat /tmp/traceworks-pi-exit 2>/dev/null); case \"$s\" in"
            + " ''|*[!0-9]*) echo \"traceworks: Pi's exit code could not be kept in /tmp/traceworks-pi-exit\" >&2;"
            + " exit 74;; esac; exit \"$s\"";

    /** Pi's thinking levels, which a model may name after its last {@code :}. */
    private static final List<String> LEVELS = List.of("off", "minimal", "low", "medium", "high", "xhigh", "max");

    private static final List<String> READ_TOOLS = List.of("read", "grep", "find", "ls");
    private static final List<String> WRITE_TOOLS = List.of("edit", "write");
    private static final String SHELL_TOOL = "bash";

    /** The model servers of the operator's own; {@link LocalModels#NONE} without the setting. */
    private final LocalModels localModels;

    /** A Pi that knows no model server of the operator's. */
    public PiCli() {
        this(LocalModels.NONE);
    }

    /**
     * @param localModels the operator's {@code local_models}: a model that names one of these backends runs against
     *                    its server
     */
    public PiCli(LocalModels localModels) {
        this.localModels = localModels == null ? LocalModels.NONE : localModels;
    }

    @Override
    public String id() {
        return "PI";
    }

    /** The server of the operator's the call's model names, by the backend before its first slash. */
    @Override
    public Optional<LocalModels.Use> local(AiCall call) {
        return localModels.of(call.model());
    }

    @Override
    public String image() {
        return "pi";
    }

    /**
     * The API hosts of the backends Pi is run against, by backend as {@code --provider} names it. A backend that is
     * not here has no built-in host and no credential ({@link #BACKEND_CREDENTIALS}): a call to it is refused.
     */
    static final Map<String, String> BACKEND_HOSTS = Map.of(
            "anthropic", "api.anthropic.com:443",
            "openai", "api.openai.com:443",
            "google", "generativelanguage.googleapis.com:443",
            "openrouter", "openrouter.ai:443");

    /**
     * The credentials of each backend, by backend as {@code --provider} names it: the names Pi reads that backend's
     * key from. Together they are the provider's credentials ({@link AiCli#CREDENTIALS}), and the backends are those
     * with a built-in host.
     */
    static final Map<String, List<String>> BACKEND_CREDENTIALS = Map.of(
            "anthropic", List.of("ANTHROPIC_API_KEY", "ANTHROPIC_OAUTH_TOKEN"),
            "openai", List.of("OPENAI_API_KEY"),
            "google", List.of("GEMINI_API_KEY"),
            "openrouter", List.of("OPENROUTER_API_KEY"));

    /**
     * The credentials of the backend of the call's model, as {@link #invocation} names that backend: a call gets the
     * key of the one backend it talks to. A call without a model gets the default backend's, so Pi, which then takes
     * the first backend it has a credential for, takes that one.
     *
     * @throws IllegalArgumentException for a backend that is not in {@link #BACKEND_CREDENTIALS}: which key it takes
     *                                  cannot be told, and the call gets none rather than all
     */
    @Override
    public List<String> credentialNames(AiCall call) {
        Optional<LocalModels.Use> local = local(call);
        if (local.isPresent()) {
            // A server of the operator's: its own secret, if it has one, and no credential of a provider
            String problem = local.get().problem();
            if (problem != null) {
                throw new IllegalArgumentException(problem);
            }
            String secret = local.get().backend().secret();
            return secret == null ? List.of() : List.of(secret);
        }
        String backend = backendAndModel(call.model())[0];
        List<String> names = BACKEND_CREDENTIALS.get(backend);
        if (names == null) {
            // What to do comes first: a view may cut a long line
            throw new IllegalArgumentException("The Pi model " + call.model() + " names the backend " + backend
                    + ", which is no backend of a provider ("
                    + String.join(", ", new TreeSet<>(BACKEND_CREDENTIALS.keySet()))
                    + ") and no model server that " + LocalModels.SETTING + " in traceworks.json declares"
                    + (localModels.isEmpty() ? " (it declares none)"
                    : " (it declares " + String.join(", ", localModels.ids()) + ")")
                    + "; Traceworks cannot tell which credential the backend takes, so the call gets none and does"
                    + " not run. A model without a backend goes to " + DEFAULT_BACKEND);
        }
        return names;
    }

    /** The backend of the call's model, as {@link #invocation} names it; the default backend without a model. */
    @Override
    public String backend(AiCall call) {
        return backendAndModel(call.model())[0];
    }

    /**
     * The API host of the backend of the call's model, as {@link #invocation} names that backend; none if unknown.
     * Of a call to a server of the operator's, that server and nothing else.
     */
    @Override
    public List<String> hosts(AiCall call) {
        Optional<LocalModels.Use> local = local(call);
        if (local.isPresent()) {
            return List.of(local.get().backend().host());
        }
        String host = BACKEND_HOSTS.get(backendAndModel(call.model())[0]);
        return host == null ? List.of() : List.of(host);
    }

    /**
     * Everything but a web tool: the JSON stream carries the tokens, so Traceworks reads that stream. Pi's
     * built-in tools are read, bash, edit, write, grep, find and ls; none reaches the web.
     */
    @Override
    public AiProviderCapabilities capabilities() {
        return AiProviderCapabilities.full().withoutWeb();
    }

    /**
     * @throws IllegalArgumentException for a system prompt over 95 KiB, for a call with web, for a model Pi
     *                                  could read as something else ({@link ModelArgument}), and for a model that
     *                                  the server of the operator's it names does not list
     */
    @Override
    public AiInvocation invocation(AiCall call) {
        if (call.web().enabled()) {
            throw new IllegalArgumentException("Pi has no web tool (Pi " + VERSION + "), so a call with web: "
                    + call.web().value() + " cannot run on it");
        }
        ModelArgument.check(call.model());
        List<String> toolFlags = toolFlags(call.tools());
        SystemPromptLaunch launch = SystemPromptLaunch.of(call.systemPrompt());
        List<String> cli = new ArrayList<>(List.of("sh", "-c", STREAM_FILTER, "sh", "pi", "-p", "--no-session",
                "--no-context-files", "--mode", "json", "--offline", "--no-extensions", "--no-mcp", "--no-approve",
                "--no-skills", "--no-prompt-templates", "--no-themes"));
        Optional<LocalModels.Use> local = local(call);
        if (local.isPresent()) {
            String problem = local.get().problem();
            if (problem != null) {
                throw new IllegalArgumentException(problem);
            }
            cli.addAll(List.of("--provider", PiModelsLaunch.PROVIDER, "--model", local.get().model()));
        } else {
            addBackendAndModel(cli, call.model());
        }
        cli.addAll(List.of("--thinking", thinking(call)));
        boolean reason = call.kind() == AiCall.Kind.REASON;
        List<String> replace = reason ? launch.option("--system-prompt") : List.of();
        List<String> append = reason ? List.of() : launch.option("--append-system-prompt");
        cli.addAll(replace.isEmpty() ? List.of("--system-prompt", "") : replace);
        cli.addAll(append.isEmpty() ? List.of("--append-system-prompt", "") : append);
        cli.addAll(toolFlags);
        AiInvocation invocation = launch.invocation(cli, Map.of(), call.prompt());
        return local.isPresent() ? PiModelsLaunch.invocation(local.get(), invocation) : invocation;
    }

    /** {@code --provider} and {@code --model} of {@code backend/model}; nothing without a model. */
    private static void addBackendAndModel(List<String> cli, String model) {
        if (model == null || model.isBlank()) {
            return;
        }
        String[] parts = backendAndModel(model);
        cli.addAll(List.of("--provider", parts[0], "--model", parts[1]));
    }

    /**
     * The backend and the model id of {@code backend/model}; the default backend for a model without one, and for no
     * model at all (the id is then {@code null}), since Pi then runs against its own default.
     */
    private static String[] backendAndModel(String model) {
        if (model == null || model.isBlank()) {
            return new String[] {DEFAULT_BACKEND, null};
        }
        int slash = model.indexOf('/');
        String backend = slash < 0 ? "" : model.substring(0, slash);
        String id = slash < 0 ? "" : model.substring(slash + 1);
        if (backend.isBlank() || id.isBlank()) {
            return new String[] {DEFAULT_BACKEND, model};
        }
        return new String[] {backend, id};
    }

    private static String thinking(AiCall call) {
        return ReasoningEffort.parse(call.effort()).map(ReasoningEffort::cliValue).orElseGet(() -> {
            String model = call.model() == null ? "" : call.model();
            String suffix = model.substring(model.lastIndexOf(':') + 1);
            if (model.contains(":") && LEVELS.contains(suffix)) {
                return suffix;
            }
            Integer turns = call.kind() == AiCall.Kind.REASON ? call.maxTurns() : null;
            if (turns == null) {
                return DEFAULT_THINKING;
            }
            return turns <= 1 ? "low" : turns <= 3 ? "medium" : "high";
        });
    }

    private static List<String> toolFlags(ToolPolicy policy) {
        List<String> tools = new ArrayList<>();
        switch (policy) {
            case NONE -> {
                return List.of("--no-tools");
            }
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
        return List.of("--tools", String.join(",", tools));
    }

    @Override
    public AiOutcome parse(CommandResult result, AiCall call) {
        return PiStream.outcome(result, call.prompt());
    }
}
