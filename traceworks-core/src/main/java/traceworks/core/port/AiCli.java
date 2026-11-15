package traceworks.core.port;

import traceworks.core.ai.AiProviderCapabilities;
import traceworks.core.ai.AiResponseMetadata;
import traceworks.core.ai.ToolCall;
import traceworks.core.ai.ToolPolicy;
import traceworks.core.port.CommandRunner.CommandResult;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Port to one AI command line tool (Claude Code, Pi), as a translator only: it turns a call into
 * the arguments, environment and stdin of its CLI, and the CLI's output into an outcome. It starts nothing itself:
 * {@code core.ai.cli.CliAiProvider} runs the CLI as a command of the run, in the CLI's built-in image, so an AI call
 * goes through the same command path, container policy and record as every other command.
 */
public interface AiCli {

    /** The provider id, upper case: {@code CLAUDE} or {@code PI}. */
    String id();

    /** The name of the built-in image the CLI runs in: {@code claude} or {@code pi}. */
    String image();

    /**
     * The credential names of each provider, by provider id: the one list of them. A provider's CLI reads
     * them from its environment: of the names that have a value, those the CLI names for a call
     * ({@link #credentialNames}) are passed to that call's container, and a provider a run uses with none of them is
     * refused at start. No step may name one as a secret. A CLI adds no name of its own to them, so the start check
     * and the secret names can never disagree.
     */
    Map<String, List<String>> CREDENTIALS = Map.of(
            "CLAUDE", List.of("ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN"),
            "PI", List.of("ANTHROPIC_API_KEY", "ANTHROPIC_OAUTH_TOKEN", "OPENAI_API_KEY", "GEMINI_API_KEY",
                    "OPENROUTER_API_KEY"));

    /**
     * The credential a Claude subscription serves a provider with, by provider id: the name the token of
     * {@code claude setup-token} is stored under. Each is one of the provider's {@link #CREDENTIALS}. The start
     * check's refusal and the help of {@code run} name it, since no login of the host reaches a container.
     */
    Map<String, String> SUBSCRIPTION_TOKENS = Map.of(
            "CLAUDE", "CLAUDE_CODE_OAUTH_TOKEN",
            "PI", "ANTHROPIC_OAUTH_TOKEN");

    /**
     * The credential names of the provider {@code providerId}, compared ignoring case; empty for a provider the table
     * does not know.
     */
    static List<String> credentialNamesOf(String providerId) {
        return providerId == null ? List.of()
                : CREDENTIALS.getOrDefault(providerId.toUpperCase(Locale.ROOT), List.of());
    }

    /** Every credential name of every provider, sorted. */
    static SortedSet<String> credentialNamesOfAllProviders() {
        SortedSet<String> names = new TreeSet<>();
        CREDENTIALS.values().forEach(names::addAll);
        return Collections.unmodifiableSortedSet(names);
    }

    /**
     * The names of the credentials the container of {@code call} may get, each one of the provider's
     * {@link #CREDENTIALS}: all of them for a CLI of one backend. A CLI that calls several backends (Pi) names only
     * those of the backend the call's model belongs to, so a call never holds the key of a backend it does not talk
     * to.
     *
     * @throws IllegalArgumentException for a call whose backend the CLI cannot tell, with what to change; such a
     *                                  call gets no credential and does not run
     */
    default List<String> credentialNames(AiCall call) {
        return credentialNamesOf(id());
    }

    /**
     * The backend {@code call} goes to, by the name a model carries it under, for a message: of a CLI that calls
     * several backends (Pi) the backend of the call's model, or its default for a call without a model. {@code null}
     * for a CLI of one backend.
     */
    default String backend(AiCall call) {
        return null;
    }

    /** What the provider supports. */
    AiProviderCapabilities capabilities();

    /**
     * The hosts the CLI must reach to make {@code call}, each {@code host:port}: the API of its provider, or of the
     * backend the call's model names. They are fixed in the CLI's adapter; a workflow picks a backend by its model,
     * but never a host. Empty for a backend the adapter knows no host of; such a call is
     * refused ({@link AiNetwork#egress}), and Pi refuses that backend earlier, for its credentials. The container of the call reaches these hosts, the operator's and, for a call that may
     * fetch pages, the step's {@code web_hosts} ({@link WebAccess}), and nothing else.
     */
    List<String> hosts(AiCall call);

    /**
     * The CLI's arguments (after the image's entrypoint), environment and stdin for {@code call}.
     *
     * @throws IllegalArgumentException for a call the CLI cannot make as asked, such as a tool policy it cannot
     *                                  restrict to; the run admission refuses such a call earlier
     */
    AiInvocation invocation(AiCall call);

    /**
     * The outcome of {@code call} from what its command returned. Never throws for output the CLI broke off or
     * garbled: such a call failed, and the outcome says why.
     */
    AiOutcome parse(CommandResult result, AiCall call);

    /**
     * One call, as the CLI gets it.
     *
     * @param systemPrompt   {@code null} for none
     * @param model          {@code null} for the CLI's default
     * @param effort         {@code null} for the CLI's default
     * @param tools          what the model may do besides answering
     * @param maxTurns       the turn limit of a reasoning call; {@code null} for the CLI's default
     * @param timeoutSeconds how long the call may take
     * @param web            whether the model may use the web; absent is {@link WebAccess#NONE}. A CLI without a web
     *                       tool ({@link AiProviderCapabilities#supportsWeb}) never gets another value
     */
    record AiCall(Kind kind, String prompt, String systemPrompt, String model, String effort, ToolPolicy tools,
                  Integer maxTurns, int timeoutSeconds, WebAccess web) {

        /** A reasoning call ({@code AiProvider.reason}) or an action ({@code AiProvider.executeAction}). */
        public enum Kind { REASON, ACTION }

        public AiCall {
            tools = tools != null ? tools : ToolPolicy.NONE;
            web = web != null ? web : WebAccess.NONE;
        }

        /** A call without web. */
        public AiCall(Kind kind, String prompt, String systemPrompt, String model, String effort, ToolPolicy tools,
                      Integer maxTurns, int timeoutSeconds) {
            this(kind, prompt, systemPrompt, model, effort, tools, maxTurns, timeoutSeconds, WebAccess.NONE);
        }
    }

    /**
     * How the CLI is started.
     *
     * @param args        the arguments after the image's entrypoint
     * @param environment the variables the CLI needs besides its credentials; the run passes the credentials
     * @param stdin       what the CLI reads on stdin, the prompt; {@code null} for nothing
     */
    record AiInvocation(List<String> args, Map<String, String> environment, String stdin) {

        public AiInvocation {
            args = args == null ? List.of() : List.copyOf(args);
            environment = environment == null ? Map.of() : Map.copyOf(environment);
        }

        /** The invocation with the names of its environment variables, never their values. */
        @Override
        public String toString() {
            return "AiInvocation[args=" + args + ", environment=" + new TreeSet<>(environment.keySet()) + ", stdin="
                    + (stdin == null ? null : stdin.length() + " characters") + "]";
        }
    }

    /**
     * What came of a call.
     *
     * @param text      the model's answer; for a failed call, why it failed (the CLI's message or the raw end of its
     *                  output)
     * @param exitCode  the CLI's exit code
     * @param failed    whether the call failed, also when the CLI ended with 0 (a result line marked as an error)
     * @param metadata  usage and the model that answered; {@code null} when the CLI reported none
     * @param toolCalls the tool calls the model made, in order
     */
    record AiOutcome(String text, int exitCode, boolean failed, AiResponseMetadata metadata,
                     List<ToolCall> toolCalls) {

        public AiOutcome {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }
    }
}
