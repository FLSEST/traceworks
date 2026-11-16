package traceworks.adapters.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import traceworks.core.port.AiCli.AiInvocation;
import traceworks.core.port.LocalModels;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How Pi learns of a model server of the operator's ({@link LocalModels}): Traceworks writes Pi's own provider
 * configuration, a {@code models.json} in Pi's agent directory, from the operator's setting and from nothing else.
 * Pi's documentation (1.5.2: {@code models.md}, "Configure a compatible endpoint"; {@code models-configuration.md};
 * {@code configuration.md}, "Agent directory"; {@code environment-variables.md}) says what the file holds and where
 * Pi reads it:
 *
 * <pre>
 * {"providers": {"traceworks-local": {
 *     "baseUrl": "http://llm.internal:8080/v1",
 *     "api": "openai-completions",
 *     "apiKey": "$LLM_KEY",
 *     "authHeader": true,
 *     "models": [{"id": "qwen2.5-coder"}]}}}
 * </pre>
 *
 * <ul>
 *   <li><b>The provider id</b> is always {@value #PROVIDER}, never the operator's backend id: an id Pi knows as a
 *       built-in provider would change that provider instead of adding one, and Pi's list of them is long and
 *       grows. The call names it with {@code --provider}.</li>
 *   <li><b>The model</b> is the one the call asks for, and no other; it is one the operator listed.</li>
 *   <li><b>The key</b> is never in the file. For a server with a secret the file says {@code "$NAME"}, which Pi
 *       resolves from its environment when it sends a request, and the value travels as every credential does, in
 *       the container's environment; with it the file says {@code "authHeader": true}, which Pi's reference
 *       describes as "whether to send the resolved API key as Authorization: Bearer" and which is off by
 *       default. For a server without one the file holds the word {@value #NO_KEY}: Pi offers
 *       a model only when its provider has a key, and a server that wants none ignores it.</li>
 *   <li><b>No extension:</b> a provider in {@code models.json} with a {@code baseUrl} and an {@code api} is Pi's
 *       own feature. {@code --no-extensions} and the other options of a call stay as they are.</li>
 * </ul>
 *
 * <p>The file reaches the container as the system prompt does ({@link SystemPromptLaunch}): its UTF-8 bytes in
 * base64 in the environment variable {@value #VARIABLE}, which the constant {@link #SCRIPT} decodes into
 * {@value #FILE}, in the container's own {@code /tmp}, drops, and then starts the CLI with
 * {@code PI_CODING_AGENT_DIR} set to {@value #AGENT_DIR}, a directory that holds this one file. Nothing of it is on
 * a command line, and nothing of it comes from the workspace.
 */
final class PiModelsLaunch {

    /** The variable that carries the configuration to the container. */
    static final String VARIABLE = "TRACEWORKS_PI_MODELS";

    /** Pi's agent directory in the container of a call to a local server: it holds {@code models.json} only. */
    static final String AGENT_DIR = "/tmp/traceworks-pi-agent";

    /** Where the container's script puts the configuration: where Pi reads it. */
    static final String FILE = AGENT_DIR + "/models.json";

    /** The id Pi knows the server under, whatever the operator calls the backend. */
    static final String PROVIDER = "traceworks-local";

    /** What stands for the key of a server that wants none. */
    static final String NO_KEY = "none";

    /** The script that writes the file and starts the CLI: one constant, which takes no text of the setting. */
    static final String SCRIPT = "(umask 077 && mkdir -p " + AGENT_DIR + " && printf '%s' \"$" + VARIABLE
            + "\" | base64 -d > " + FILE + ") && unset " + VARIABLE + " && PI_CODING_AGENT_DIR=" + AGENT_DIR
            + " && export PI_CODING_AGENT_DIR && exec \"$@\"";

    private static final ObjectMapper JSON = new ObjectMapper();

    private PiModelsLaunch() {
    }

    /**
     * The text of {@code models.json} for a call to {@code use}: one provider, {@value #PROVIDER}, with the server's
     * URL and API, the name of its secret as {@code $NAME} with {@code authHeader} (or {@value #NO_KEY} without
     * it), and the one model of the call.
     */
    static String modelsJson(LocalModels.Use use) {
        LocalModels.Backend backend = use.backend();
        ObjectNode root = JSON.createObjectNode();
        ObjectNode provider = root.putObject("providers").putObject(PROVIDER);
        provider.put("baseUrl", backend.url());
        provider.put("api", backend.api());
        provider.put("apiKey", backend.secret() == null ? NO_KEY : "$" + backend.secret());
        if (backend.secret() != null) {
            // Pi's models-configuration.md: authHeader, false by default, is "whether to send the resolved API key
            // as Authorization: Bearer". A server that wants a key wants it there.
            provider.put("authHeader", true);
        }
        provider.putArray("models").addObject().put("id", use.model());
        return root.toString();
    }

    /**
     * How the container starts {@code invocation} with the configuration of {@code use} in place: the script with
     * the invocation's arguments as its own, and the configuration in base64 in {@value #VARIABLE} besides the
     * invocation's environment.
     */
    static AiInvocation invocation(LocalModels.Use use, AiInvocation invocation) {
        List<String> args = new ArrayList<>(List.of("sh", "-c", SCRIPT, "sh"));
        args.addAll(invocation.args());
        Map<String, String> environment = new HashMap<>(invocation.environment());
        environment.put(VARIABLE, Base64.getEncoder().encodeToString(
                modelsJson(use).getBytes(StandardCharsets.UTF_8)));
        return new AiInvocation(args, environment, invocation.stdin());
    }
}
