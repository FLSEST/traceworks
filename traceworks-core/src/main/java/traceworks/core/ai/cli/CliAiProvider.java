package traceworks.core.ai.cli;

import traceworks.core.ai.AiProviderCapabilities;
import traceworks.core.port.AiCallFailedException;
import traceworks.core.port.AiCli;
import traceworks.core.port.AiCli.AiCall;
import traceworks.core.port.AiCli.AiInvocation;
import traceworks.core.port.AiCli.AiOutcome;
import traceworks.core.port.AiNetwork;
import traceworks.core.port.AiProvider;
import traceworks.core.port.CommandRunner;
import traceworks.core.port.CommandRunner.CommandRequest;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.Egress;
import traceworks.core.port.LocalModels;

import java.io.IOException;
import java.util.Optional;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * An {@link AiProvider} that runs an {@link AiCli} through the run's command path: every call is one
 * command in the CLI's built-in image, with the CLI as the image's entrypoint, the prompt on stdin, the
 * credentials its CLI names for the call ({@link AiCli#credentialNames}) in its environment and the call's timeout. Its container connects only to the hosts of the
 * call's provider ({@link AiCli#hosts}), the operator's hosts for the CLI and, for a call that may fetch pages, its
 * step's {@code web_hosts}, unless the operator opened the AI network ({@link AiNetwork}). The run's {@link CommandRunner} plans it like any
 * command (the image ID, the run's standard mounts with the workspace read-only unless the call may write it, the
 * working directory) and the record writes it as a {@code step.command}. A call of a host run runs in a container
 * too.
 *
 * <p>A reasoning call that fails throws an {@link AiCallFailedException} with its outcome, as the port requires; a
 * failed action returns a non-zero exit code. A call of a provider without credentials is refused before anything
 * runs: the start check reads the credentials of every provider it sees used, so a provider without them belongs to
 * a workflow the start check did not see (one named by a placeholder), and its CLI would only fail to log in. A call
 * the CLI cannot make as asked (its invocation refuses it, such as a system prompt too large to carry) fails the same
 * way, with the CLI's message, before anything runs, so an AI condition fails its step, not the run. So does a call
 * whose backend the CLI cannot tell, or whose backend has no credential with a value: it gets no key of another
 * backend in its place. A call with no host at all (a CLI that names none for it, and none from the operator) is
 * refused too, since it could reach nothing: a guard for a CLI to come, which neither built-in CLI reaches, since
 * Claude always names its host and Pi refuses a backend it knows no host of for its credentials first.
 *
 * <p>A call to a model server of the operator's ({@link AiCli#local}, {@link LocalModels}) is different in three
 * ways: it needs no credential of the provider and gets none, only the server's secret when it has one; its
 * container reaches that one server and nothing else, through the proxy, also when the operator opened the AI
 * network, and neither the provider's hosts nor {@code ai_hosts} are on its list; and a model the server does not
 * list is refused before anything runs.
 * A call that ran out of time failed, whatever its CLI made of the output it had so far, and so did a call whose
 * stream went beyond the runner's output limit ({@link CommandResult#truncationNote()}): its CLI read only the first
 * part of the stream, so neither its answer nor its error about a missing end is the call's outcome. Of such a stream
 * the parser reads only the lines that ended, so no tool call comes from the line the limit cut.
 */
public final class CliAiProvider implements AiProvider {

    /** The timeout of a call that names none. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 300;

    private final AiCli cli;
    private final CommandRunner runner;
    private final String imageReference;
    private final Map<String, String> credentials;
    private final AiNetwork network;

    /** A provider whose containers reach its hosts only ({@link AiNetwork#RESTRICTED}). */
    public CliAiProvider(AiCli cli, CommandRunner runner, String imageReference, Map<String, String> credentials) {
        this(cli, runner, imageReference, credentials, AiNetwork.RESTRICTED);
    }

    /**
     * @param runner         the run's command runner, the recording one, so the call's command is in the record
     * @param imageReference the tag of the CLI's built-in image ({@code traceworks/claude:<h>})
     * @param credentials    the provider's credentials with a value, by name; those of a call reach its
     *                       container's environment, never a command line or the record. Empty for a provider the start check
     *                       did not see used, whose calls are refused
     * @param network        where the containers of the calls can connect: the operator's setting
     */
    public CliAiProvider(AiCli cli, CommandRunner runner, String imageReference, Map<String, String> credentials,
                         AiNetwork network) {
        this.network = Objects.requireNonNull(network, "network");
        this.cli = Objects.requireNonNull(cli, "cli");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.imageReference = Objects.requireNonNull(imageReference, "imageReference");
        this.credentials = Map.copyOf(Objects.requireNonNull(credentials, "credentials"));
    }

    @Override
    public String getId() {
        return cli.id();
    }

    @Override
    public AiProviderCapabilities getCapabilities() {
        return cli.capabilities();
    }

    /**
     * @throws AiCallFailedException when the call failed, naming the exit code and the CLI's message, with the outcome
     *                               (tool calls, usage) for the record
     * @throws IOException           when the provider has no credential in this run, or its CLI cannot make the
     *                               call, before anything runs
     */
    @Override
    public ReasoningResult reason(ReasoningRequest request) throws IOException {
        AiOutcome outcome = run(new AiCall(AiCall.Kind.REASON, request.prompt(), request.systemPrompt(),
                request.model(), request.effort(), request.tools(), request.maxTurns(),
                timeout(request.timeoutSeconds()), request.web()), request.workingDirectory(),
                request.writableWorkspace());
        if (outcome.failed()) {
            throw new AiCallFailedException(cli.id(), new AiOutcome(outcome.text(), exitCode(outcome), true,
                    outcome.metadata(), outcome.toolCalls()));
        }
        return new ReasoningResult(outcome.text(), outcome.metadata(), null, outcome.toolCalls());
    }

    /**
     * @throws IOException when the provider has no credential in this run, or its CLI cannot make the call, before
     *                     anything runs
     */
    @Override
    public ActionResult executeAction(ActionRequest request) throws IOException {
        AiOutcome outcome = run(new AiCall(AiCall.Kind.ACTION, request.prompt(), request.systemPrompt(),
                request.model(), request.effort(), request.tools(), null, timeout(request.timeoutSeconds()),
                request.web()), request.workingDirectory(), request.writableWorkspace());
        return new ActionResult(outcome.text(), exitCode(outcome), outcome.metadata(), outcome.toolCalls());
    }

    /** The exit code a failed call reports: never 0, also when the CLI ended with 0 but reported an error. */
    private static int exitCode(AiOutcome outcome) {
        return outcome.failed() && outcome.exitCode() == 0 ? 1 : outcome.exitCode();
    }

    /** The model server of the operator's a call with {@code model} goes to, as the CLI tells it. */
    @Override
    public Optional<LocalModels.Use> local(String model) {
        return cli.local(new AiCall(AiCall.Kind.ACTION, "", null, model, null, null, null, 1));
    }

    private AiOutcome run(AiCall call, String workingDirectory, boolean writableWorkspace) throws IOException {
        Optional<LocalModels.Use> local = cli.local(call);
        if (local.isPresent()) {
            String problem = local.get().problem();
            if (problem != null) {
                throw new IOException(problem);
            }
        }
        if (credentials.isEmpty() && local.isEmpty()) {
            throw new IOException("AI provider " + cli.id() + " has no credential in this run: the start check did"
                    + " not see the workflow of this step, so it read none of "
                    + String.join(", ", AiCli.credentialNamesOf(cli.id())) + "; name the workflow by a literal"
                    + " workflow_id, so the start check reads its credentials");
        }
        if (call.web().enabled() && !cli.capabilities().supportsWeb()) {
            throw new IOException("AI provider " + cli.id() + " has no web tool, so a step with web: "
                    + call.web().value() + " cannot run on it; remove web, or run the step on a provider that has"
                    + " one");
        }
        Map<String, String> environment = new LinkedHashMap<>(local.isPresent() ? secretOf(local.get())
                : credentialsOf(call));
        AiInvocation invocation;
        try {
            invocation = cli.invocation(call);
        } catch (IllegalArgumentException e) {
            // A call the CLI cannot make is a failed call, which every caller handles, not an error of the run
            throw new IOException(e.getMessage(), e);
        }
        List<String> hosts = new ArrayList<>(cli.hosts(call));
        // a step that may fetch pages reaches its web_hosts too; the star stays one of the hosts, for the proxy
        hosts.addAll(call.web().hosts());
        // No built-in CLI gets here without a host (see the class comment); the allowlist is never empty
        // A call to a model server of the operator's reaches that server and nothing else, whatever ai_network and
        // ai_hosts say: neither the provider's hosts nor the operator's other hosts are on its list
        Egress egress = local.isPresent()
                ? Egress.server(local.get().backend().host(), local.get().backend().plainHttp())
                : network.egress(cli.id(), hosts).orElseThrow(() -> new IOException("AI provider "
                + cli.id() + " knows no host for a call with the model " + call.model() + ", so its container could"
                + " reach nothing; name the host of its API in ai_hosts." + cli.id().toLowerCase(Locale.ROOT)
                + " of traceworks.json (host:port), or set ai_network to \"" + AiNetwork.OPEN_MODE + "\""));
        environment.putAll(invocation.environment());
        CommandRequest request = CommandRequest.entrypoint(imageReference, invocation.args(), workingDirectory,
                        call.timeoutSeconds(), environment, List.of(), false)
                .withNetwork(egress)
                .withStdin(invocation.stdin())
                .withWritableWorkspace(writableWorkspace)
                // What a model reads of the register is what a step put into its prompt, not the folder
                .withoutRegisterFolder();
        CommandResult result = runner.run(request);
        AiOutcome outcome = cli.parse(endedLines(result), call);
        if (result.timedOut()) {
            String note = result.truncated() ? "; " + result.truncationNote() : "";
            return new AiOutcome(cli.id() + " timed out after " + call.timeoutSeconds() + " s" + note,
                    result.exitCode(), true, outcome.metadata(), outcome.toolCalls());
        }
        if (result.truncated()) {
            return new AiOutcome(cli.id() + ": " + result.truncationNote(), result.exitCode(), true,
                    outcome.metadata(), outcome.toolCalls());
        }
        return outcome;
    }

    /**
     * The credentials the container of {@code call} gets: of the provider's that have a value, those its CLI names
     * for the call ({@link AiCli#credentialNames}), so a CLI of several backends holds only the key of the backend
     * the call's model belongs to. Only names of the provider's table ({@link AiCli#CREDENTIALS}) count, whatever
     * the CLI names and whatever this provider was given.
     *
     * @throws IOException when the CLI cannot tell the call's backend, or none of the names has a value; no message
     *                     shows a value
     */
    private Map<String, String> credentialsOf(AiCall call) throws IOException {
        List<String> names;
        try {
            names = cli.credentialNames(call);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
        // The CLI narrows its provider's table and never adds to it: a name outside the table is not passed
        List<String> table = AiCli.credentialNamesOf(cli.id());
        Map<String, String> ofCall = new LinkedHashMap<>();
        for (String name : names) {
            String value = table.contains(name) ? credentials.get(name) : null;
            if (value != null) {
                ofCall.put(name, value);
            }
        }
        if (ofCall.isEmpty()) {
            String backend = cli.backend(call);
            String what = call.model() != null
                    ? "a call with the model " + call.model() + " in this run: it needs "
                    : "this call in this run: the step names no model" + (backend == null ? "; it needs "
                            : ", so its call goes to " + backend + ", which needs ");
            throw new IOException("AI provider " + cli.id() + " has no credential for " + what
                    + String.join(" or ", names) + "; store it with 'traceworks credentials set <NAME>', or set it as"
                    + " an environment variable of Traceworks");
        }
        return ofCall;
    }

    /**
     * What the container of a call to a model server of the operator's gets: the server's secret, when it has one,
     * and no credential of the provider. The value is the one the start check read under the secret's name.
     *
     * @throws IOException when the server has a secret and this run holds no value for it
     */
    private Map<String, String> secretOf(LocalModels.Use local) throws IOException {
        String name = local.backend().secret();
        if (name == null) {
            return Map.of();
        }
        String value = credentials.get(name);
        if (value == null) {
            throw new IOException("The local backend " + local.backend().id() + " needs the secret " + name
                    + " (" + LocalModels.SETTING + "." + local.backend().id() + ".secret in traceworks.json), and this"
                    + " run holds no value for it: the start check did not see the workflow of this step; store it"
                    + " with 'traceworks credentials set " + name + "' and name the workflow by a literal"
                    + " workflow_id");
        }
        return Map.of(name, value);
    }

    /**
     * {@code result} as the CLI's parser reads it: of a stdout that did not end by itself only the lines that ended.
     * That is the stdout of a CLI that went beyond the output limit, timed out, or exited with any other code than 0,
     * such as a cancel (143) or a kill (137). Its last line broke off where the CLI was stopped, maybe inside a
     * credential, and what a parser repairs from it (a tool call's result, say) would hold the credential's first
     * characters, which the record cannot redact. A line holds no line feed of a value: the CLIs write one JSON object
     * per line, and a value with a control character is refused at the start. A CLI that exited 0 ended its stream
     * itself, and its parser gets it as it came, a last line without a line feed included.
     */
    private static CommandResult endedLines(CommandResult result) {
        String stdout = result.stdout();
        if (stdout == null || result.exitCode() == 0 && !result.timedOut() && !result.truncated()) {
            return result;
        }
        return new CommandResult(result.exitCode(), stdout.substring(0, stdout.lastIndexOf('\n') + 1),
                result.stderr(), result.durationMs(), result.timedOut(), result.truncatedStreams(),
                result.outputLimitBytes());
    }

    private static int timeout(Integer requested) {
        return requested != null && requested > 0 ? requested : DEFAULT_TIMEOUT_SECONDS;
    }

    /** The provider with its CLI and image, never a credential value. */
    @Override
    public String toString() {
        return "CliAiProvider[" + cli.id() + ", " + imageReference + ", credentials=" + credentials.keySet() + "]";
    }
}
