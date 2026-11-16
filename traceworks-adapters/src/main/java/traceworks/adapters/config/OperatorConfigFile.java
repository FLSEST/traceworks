package traceworks.adapters.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.engine.AllowedModels;
import traceworks.core.app.OperatorSettings;
import traceworks.core.app.SealSettings;
import traceworks.core.port.AiNetwork;
import traceworks.core.port.LocalModels;
import traceworks.core.register.DecisionOnHigherSeverity;
import traceworks.core.port.Egress;
import traceworks.core.engine.SecretNames;
import traceworks.core.port.ContainerLimits;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reads {@code <home>/traceworks.json}: {@code allow_host_execution} (boolean), {@code actor} (string or null),
 * {@code package_path} (list of directories; a relative one is relative to the home), {@code container_runtime}
 * ({@code "podman"}, {@code "docker"} or null for the first that works), {@code container_limits}
 * ({@code {memory, pids, cpus}}: a string such as {@code "4g"}, an integer, and a string or number such as
 * {@code "2"} or {@code 0.5}; a missing one keeps its default), {@code output_limit_mib} (an integer from 1 to
 * 1024, or null for 16), {@code run_output_budget_mib} (an integer from 1 to 65536 and at least twice
 * {@code output_limit_mib}, or null for 128 or eight times {@code output_limit_mib}, whichever is more) and
 * {@code ai_images} ({@code {claude, pi}}: the image reference an AI CLI runs in instead of its built-in image;
 * missing, null or {@code {}} keeps the built-in ones), {@code ai_network} ({@code "restricted"}, the default: the
 * container of an AI call reaches the hosts of its provider only; or {@code "open"}) and {@code ai_hosts}
 * ({@code {claude, pi}}: more hosts for that CLI's containers, each {@code host:port}), and the settings for seals:
 * {@code require_seal} ({@code "never"}, the default, {@code "unattended"} or {@code "always"}),
 * {@code seal_immutable} (boolean), {@code signing_key} and {@code allowed_signers} (each the path of a file; a
 * relative one is relative to the home), and {@code decision_on_higher_severity} ({@code "reopen"}, the default: a
 * finding a person accepted or dismissed is open again when a report states it with a severity above the one the
 * decision was made on; or {@code "keep"}), and {@code allowed_models} (a list of {@code {provider, model}}: the
 * models that may run in this home; missing or null is no restriction), and {@code local_models} (an object of
 * backend id to {@code {url, api, models, secret}}: the model servers of the operator's own that a {@code PI} step
 * may name; missing or null is none). The keys {@code runs_dir}, {@code ledger_dir} and {@code registers_dir}
 * are read by {@link HomeLayout}. An unknown key or a value of the wrong type is an error that names the key.
 */
public final class OperatorConfigFile {

    static final String FILE = "traceworks.json";
    private static final Set<String> KEYS = Set.of("allow_host_execution", "actor", "package_path", "secret_names", "hooks",
            "container_runtime", "container_limits", "output_limit_mib", "run_output_budget_mib", "ai_images", "ai_network",
            "ai_hosts", "allowed_models", LocalModels.SETTING, "decision_on_higher_severity", "require_seal", "seal_immutable", "signing_key", "allowed_signers", HomeLayout.RUNS_KEY,
            HomeLayout.LEDGER_KEY, HomeLayout.REGISTERS_KEY);
    private static final Set<String> LIMIT_KEYS = Set.of("memory", "pids", "cpus");
    private static final Set<String> RUNTIMES = Set.of("podman", "docker");

    private OperatorConfigFile() {
    }

    /** The settings in the home's config file, or the defaults when there is none. */
    public static OperatorSettings read(Path home) {
        Path file = home.resolve(FILE);
        JsonNode root = root(file);
        if (root == null) return OperatorSettings.defaults();
        // The places of the runs, the ledger and the registers are the layout's: checked here, so that every reader
        // of the file refuses a wrong one
        HomeLayout.from(home, file, root);
        boolean allowHost = false;
        if (root.has("allow_host_execution")) {
            JsonNode value = root.get("allow_host_execution");
            if (!value.isBoolean()) throw wrongType(file, "allow_host_execution", "a boolean");
            allowHost = value.booleanValue();
        }
        String actor = null;
        if (root.has("actor") && !root.get("actor").isNull()) {
            if (!root.get("actor").isTextual()) throw wrongType(file, "actor", "a string");
            actor = root.get("actor").textValue();
        }
        List<Path> packagePath = new ArrayList<>();
        if (root.has("package_path")) {
            JsonNode value = root.get("package_path");
            if (!value.isArray()) throw wrongType(file, "package_path", "a list of directories");
            for (JsonNode entry : value) {
                if (!entry.isTextual()) throw wrongType(file, "package_path", "a list of directories");
                packagePath.add(home.resolve(entry.textValue()).normalize());
            }
        }
        String runtime = null;
        if (root.has("container_runtime") && !root.get("container_runtime").isNull()) {
            JsonNode value = root.get("container_runtime");
            if (!value.isTextual() || !RUNTIMES.contains(value.textValue())) {
                throw wrongType(file, "container_runtime", "\"podman\", \"docker\" or null");
            }
            runtime = value.textValue();
        }
        ContainerLimits limits = root.has("container_limits") ? limits(file, root.get("container_limits"))
                : ContainerLimits.DEFAULTS;
        int outputLimit = OperatorSettings.DEFAULT_OUTPUT_LIMIT_MIB;
        if (root.has("output_limit_mib") && !root.get("output_limit_mib").isNull()) {
            outputLimit = outputLimitMib(file, root.get("output_limit_mib"));
        }
        int budget = OperatorSettings.defaultRunOutputBudgetMib(outputLimit);
        if (root.has("run_output_budget_mib") && !root.get("run_output_budget_mib").isNull()) {
            budget = runOutputBudgetMib(file, root.get("run_output_budget_mib"), outputLimit);
        }
        Map<String, String> aiImages = Map.of();
        if (root.has("ai_images") && !root.get("ai_images").isNull()) {
            aiImages = aiImages(file, root.get("ai_images"));
        }
        List<String> secretNames = null;
        if (root.has("secret_names") && !root.get("secret_names").isNull()) {
            JsonNode value = root.get("secret_names");
            if (!value.isArray()) throw wrongType(file, "secret_names", "a list of secret names");
            secretNames = new ArrayList<>();
            for (JsonNode entry : value) {
                if (!entry.isTextual() || !SecretNames.wellFormed(entry.textValue())) {
                    throw new IllegalArgumentException("'secret_names' in " + file + " holds "
                            + (entry.isTextual() ? "'" + entry.textValue() + "'" : "a value that is no string")
                            + ", which is no secret name (capital letters, digits and _, not starting with a digit)");
                }
                secretNames.add(entry.textValue());
            }
        }
        OperatorSettings.Hooks hooks = OperatorSettings.Hooks.NONE;
        if (root.has("hooks") && !root.get("hooks").isNull()) {
            hooks = hooks(file, root.get("hooks"));
        }
        boolean openAiNetwork = false;
        if (root.has("ai_network") && !root.get("ai_network").isNull()) {
            JsonNode value = root.get("ai_network");
            if (!value.isTextual() || AiNetwork.modeProblem(value.textValue()) != null) {
                throw wrongType(file, "ai_network", "\"" + AiNetwork.RESTRICTED_MODE + "\" or \""
                        + AiNetwork.OPEN_MODE + "\"");
            }
            openAiNetwork = value.textValue().equals(AiNetwork.OPEN_MODE);
        }
        Map<String, List<String>> aiHosts = Map.of();
        if (root.has("ai_hosts") && !root.get("ai_hosts").isNull()) {
            aiHosts = aiHosts(file, root.get("ai_hosts"));
        }
        DecisionOnHigherSeverity higherSeverity = DecisionOnHigherSeverity.DEFAULT;
        if (root.has("decision_on_higher_severity") && !root.get("decision_on_higher_severity").isNull()) {
            JsonNode value = root.get("decision_on_higher_severity");
            higherSeverity = (value.isTextual() ? DecisionOnHigherSeverity.of(value.textValue())
                    : java.util.Optional.<DecisionOnHigherSeverity>empty()).orElseThrow(() -> wrongType(file,
                    "decision_on_higher_severity", "\"" + DecisionOnHigherSeverity.REOPEN.word() + "\" or \""
                            + DecisionOnHigherSeverity.KEEP.word() + "\""));
        }
        AllowedModels allowedModels = AllowedModels.ANY;
        if (root.has(AllowedModels.SETTING) && !root.get(AllowedModels.SETTING).isNull()) {
            allowedModels = allowedModels(file, root.get(AllowedModels.SETTING));
        }
        LocalModels localModels = LocalModels.NONE;
        if (root.has(LocalModels.SETTING) && !root.get(LocalModels.SETTING).isNull()) {
            localModels = localModels(file, root.get(LocalModels.SETTING));
        }
        return new OperatorSettings(allowHost, actor, packagePath, runtime, limits, outputLimit, aiImages, budget,
                secretNames, hooks, new AiNetwork(openAiNetwork, aiHosts), seals(home, file, root), higherSeverity,
                allowedModels, localModels);
    }

    private static final Set<String> LOCAL_MODEL_KEYS = Set.of("url", "api", "models", "secret");

    /**
     * {@code local_models}: the model servers of the operator's own, an object of backend id to
     * {@code {url, api, models, secret}}: the base URL of the server's API, the API it speaks
     * ({@value LocalModels#OPENAI_COMPLETIONS}), the model names a step may ask for, and optionally the name of the
     * secret that holds the server's key. Everything is checked here, so a wrong entry refuses every command that
     * reads the file and never a step in the middle of a run.
     */
    private static LocalModels localModels(Path file, JsonNode value) {
        String shape = "an object of backend id to {url, api, models, secret}";
        if (!value.isObject()) throw wrongType(file, LocalModels.SETTING, shape);
        Map<String, LocalModels.Backend> backends = new java.util.TreeMap<>();
        for (Map.Entry<String, JsonNode> entry : value.properties()) {
            String id = entry.getKey();
            String key = LocalModels.SETTING + "." + id;
            JsonNode node = entry.getValue();
            if (!node.isObject()) throw wrongType(file, key, "an object {url, api, models, secret}");
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if (!LOCAL_MODEL_KEYS.contains(field.getKey())) {
                    throw new IllegalArgumentException("Unknown key '" + field.getKey() + "' in '" + key + "' of "
                            + file + "; known keys are url, api, models and secret");
                }
            }
            if (!node.path("url").isTextual()) throw wrongType(file, key + ".url", "a string, such as"
                    + " \"http://llm.internal:8080/v1\"");
            if (!node.path("api").isTextual()) throw wrongType(file, key + ".api", "a string, one of "
                    + String.join(", ", LocalModels.APIS));
            if (!node.path("models").isArray()) throw wrongType(file, key + ".models", "a list of model names");
            List<String> models = new ArrayList<>();
            for (JsonNode model : node.get("models")) {
                if (!model.isTextual()) throw wrongType(file, key + ".models", "a list of model names, each a string");
                models.add(model.textValue());
            }
            String secret = null;
            if (node.has("secret") && !node.get("secret").isNull()) {
                if (!node.get("secret").isTextual()) throw wrongType(file, key + ".secret", "the name of a secret, a"
                        + " string, or null");
                secret = node.get("secret").textValue();
            }
            try {
                backends.put(id, new LocalModels.Backend(id, node.get("url").textValue(),
                        node.get("api").textValue(), models, secret));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("'" + LocalModels.SETTING + "' in " + file + ": "
                        + e.getMessage(), e);
            }
        }
        return new LocalModels(backends);
    }

    /**
     * {@code allowed_models}: the provider and model pairs that may run, each an object with {@code provider} (an AI
     * CLI Traceworks runs) and an optional {@code model}, in which {@code *} stands for any text; without
     * {@code model} every model of the provider. An empty list allows none.
     */
    private static AllowedModels allowedModels(Path file, JsonNode value) {
        String shape = "a list of {\"provider\": ..., \"model\": ...} objects";
        if (!value.isArray()) throw wrongType(file, AllowedModels.SETTING, shape);
        List<AllowedModels.Entry> entries = new ArrayList<>();
        for (JsonNode entry : value) {
            if (!entry.isObject()) throw wrongType(file, AllowedModels.SETTING, shape);
            for (String key : (Iterable<String>) entry::fieldNames) {
                if (!key.equals("provider") && !key.equals("model")) {
                    throw new IllegalArgumentException("Unknown key '" + key + "' in an entry of "
                            + AllowedModels.SETTING + " of " + file + "; known keys are provider and model");
                }
            }
            JsonNode provider = entry.get("provider");
            if (provider == null || !provider.isTextual()
                    || !OperatorSettings.aiCliIds().contains(provider.textValue().strip().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("'" + AllowedModels.SETTING + "' in " + file + " holds an entry"
                        + " whose provider is " + (provider != null && provider.isTextual()
                        ? "'" + provider.textValue() + "'" : "no string") + ": a provider is one of "
                        + String.join(", ", OperatorSettings.aiCliIds().stream()
                        .map(id -> id.toUpperCase(Locale.ROOT)).toList()));
            }
            JsonNode model = entry.get("model");
            if (model != null && !model.isNull() && (!model.isTextual() || model.textValue().isBlank())) {
                throw new IllegalArgumentException("'" + AllowedModels.SETTING + "' in " + file + " holds an entry"
                        + " whose model is no text: a model is a name, in which * stands for any text; leave model"
                        + " out to allow every model of the provider");
            }
            entries.add(new AllowedModels.Entry(provider.textValue().strip().toUpperCase(Locale.ROOT),
                    model == null || model.isNull() ? null : model.textValue().strip()));
        }
        return new AllowedModels(entries);
    }

    /** {@code require_seal}, {@code seal_immutable}, {@code signing_key} and {@code allowed_signers}. */
    private static SealSettings seals(Path home, Path file, JsonNode root) {
        SealSettings.Require require = SealSettings.Require.NEVER;
        if (root.has("require_seal") && !root.get("require_seal").isNull()) {
            JsonNode value = root.get("require_seal");
            require = value.isTextual() ? SealSettings.Require.of(value.textValue()) : null;
            if (require == null) {
                throw wrongType(file, "require_seal", "\"never\", \"unattended\" or \"always\"");
            }
        }
        boolean immutable = false;
        if (root.has("seal_immutable") && !root.get("seal_immutable").isNull()) {
            JsonNode value = root.get("seal_immutable");
            if (!value.isBoolean()) throw wrongType(file, "seal_immutable", "a boolean");
            immutable = value.booleanValue();
        }
        return new SealSettings(require, immutable, path(home, file, root, "signing_key"),
                path(home, file, root, "allowed_signers"));
    }

    /** The file a key names, relative to the home; {@code null} without the key. */
    static Path path(Path home, Path file, JsonNode root, String key) {
        if (!root.has(key) || root.get(key).isNull()) {
            return null;
        }
        JsonNode value = root.get(key);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw wrongType(file, key, "the path of a file");
        }
        try {
            return home.resolve(value.textValue()).normalize();
        } catch (java.nio.file.InvalidPathException e) {
            throw wrongType(file, key, "the path of a file");
        }
    }

    /**
     * The object in {@code file} with known keys only, or {@code null} when there is no such file.
     *
     * @throws IllegalArgumentException for a file that is no JSON object, or has an unknown key
     */
    static JsonNode root(Path file) {
        if (!Files.exists(file)) return null;
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(Files.readAllBytes(file));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot read " + file + ": " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file + ": " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException(file + " must hold a JSON object");
        }
        for (String key : (Iterable<String>) root::fieldNames) {
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("Unknown key '" + key + "' in " + file
                        + "; the keys are " + String.join(", ", KEYS.stream().sorted().toList()));
            }
        }
        return root;
    }

    /** {@code ai_hosts}: for each AI CLI, more hosts its containers reach, each {@code host:port}. */
    private static Map<String, List<String>> aiHosts(Path file, JsonNode value) {
        if (!value.isObject()) {
            throw wrongType(file, "ai_hosts", "an object {claude, pi} of lists of host:port");
        }
        Map<String, List<String>> hosts = new TreeMap<>();
        for (String cli : (Iterable<String>) value::fieldNames) {
            if (!AiNetwork.cliIds().contains(cli)) {
                throw new IllegalArgumentException("Unknown key '" + cli + "' in ai_hosts of " + file
                        + "; the keys are " + String.join(", ", AiNetwork.cliIds()));
            }
            JsonNode list = value.get(cli);
            if (!list.isArray()) {
                throw wrongType(file, "ai_hosts." + cli, "a list of host:port, each a string");
            }
            List<String> ofCli = new ArrayList<>();
            for (JsonNode host : list) {
                if (!host.isTextual()) {
                    throw wrongType(file, "ai_hosts." + cli, "a list of host:port, each a string");
                }
                String problem = Egress.hostProblem(host.textValue());
                if (problem != null) {
                    throw new IllegalArgumentException("'ai_hosts." + cli + "' in " + file + " holds \""
                            + host.textValue() + "\", which " + problem);
                }
                ofCli.add(host.textValue());
            }
            hosts.put(cli, ofCli);
        }
        return hosts;
    }

    /** {@code ai_images}: an image reference for each AI CLI that is to run in an image of the operator's. */
    private static Map<String, String> aiImages(Path file, JsonNode value) {
        if (!value.isObject()) {
            throw wrongType(file, "ai_images", "an object {claude, pi} of image references");
        }
        Map<String, String> images = new TreeMap<>();
        for (String cli : (Iterable<String>) value::fieldNames) {
            if (!OperatorSettings.aiCliIds().contains(cli)) {
                throw new IllegalArgumentException("Unknown key '" + cli + "' in ai_images of " + file
                        + "; the keys are " + String.join(", ", OperatorSettings.aiCliIds()));
            }
            JsonNode image = value.get(cli);
            if (!image.isTextual()) {
                throw wrongType(file, "ai_images." + cli, "an image reference, a string");
            }
            String problem = OperatorSettings.aiImageProblem(image.textValue());
            if (problem != null) {
                throw new IllegalArgumentException("'ai_images." + cli + "' in " + file + " " + problem + ", not \""
                        + image.textValue() + "\"");
            }
            images.put(cli, image.textValue());
        }
        return images;
    }

    /** {@code output_limit_mib}: an integer of MiB within the bounds of {@link OperatorSettings}. */
    private static int outputLimitMib(Path file, JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < OperatorSettings.MIN_OUTPUT_LIMIT_MIB
                || value.intValue() > OperatorSettings.MAX_OUTPUT_LIMIT_MIB) {
            throw new IllegalArgumentException("'output_limit_mib' in " + file + " must be an integer from "
                    + OperatorSettings.MIN_OUTPUT_LIMIT_MIB + " to " + OperatorSettings.MAX_OUTPUT_LIMIT_MIB
                    + " (MiB), not " + value);
        }
        return value.intValue();
    }

    /**
     * {@code run_output_budget_mib}: an integer of MiB within the bounds of {@link OperatorSettings}, and at least
     * twice {@code output_limit_mib}, since one step can hold its stdout and its stderr up to the output limit each.
     */
    private static int runOutputBudgetMib(Path file, JsonNode value, int outputLimitMib) {
        if (!value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < OperatorSettings.MIN_RUN_OUTPUT_BUDGET_MIB
                || value.intValue() > OperatorSettings.MAX_RUN_OUTPUT_BUDGET_MIB) {
            throw new IllegalArgumentException("'run_output_budget_mib' in " + file + " must be an integer from "
                    + OperatorSettings.MIN_RUN_OUTPUT_BUDGET_MIB + " to " + OperatorSettings.MAX_RUN_OUTPUT_BUDGET_MIB
                    + " (MiB), not " + value);
        }
        if (value.intValue() < 2 * outputLimitMib) {
            throw new IllegalArgumentException("'run_output_budget_mib' in " + file + " must be at least twice"
                    + " 'output_limit_mib' (2 x " + outputLimitMib + " = " + 2 * outputLimitMib + " MiB), since one"
                    + " step can hold its stdout and its stderr up to the output limit each, not " + value);
        }
        return value.intValue();
    }

    /** The limits of {@code container_limits}; a missing key keeps its default. */
    private static ContainerLimits limits(Path file, JsonNode value) {
        if (!value.isObject()) throw wrongType(file, "container_limits", "an object {memory, pids, cpus}");
        for (String key : (Iterable<String>) value::fieldNames) {
            if (!LIMIT_KEYS.contains(key)) {
                throw new IllegalArgumentException("Unknown key '" + key + "' in container_limits of " + file
                        + "; the keys are cpus, memory, pids");
            }
        }
        ContainerLimits defaults = ContainerLimits.DEFAULTS;
        String memory = defaults.memory();
        if (value.has("memory")) {
            if (!value.get("memory").isTextual()) {
                throw wrongType(file, "container_limits.memory", "a string, such as \"4g\"");
            }
            memory = value.get("memory").textValue();
        }
        int pids = defaults.pids();
        if (value.has("pids")) {
            if (!value.get("pids").isInt()) throw wrongType(file, "container_limits.pids", "an integer");
            pids = value.get("pids").intValue();
        }
        String cpus = defaults.cpus();
        if (value.has("cpus")) {
            JsonNode node = value.get("cpus");
            if (!node.isTextual() && !node.isNumber()) {
                throw wrongType(file, "container_limits.cpus", "a string or a number, such as \"2\" or 0.5");
            }
            cpus = node.isTextual() ? node.textValue() : node.decimalValue().toPlainString();
        }
        try {
            return new ContainerLimits(memory, pids, cpus);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'container_limits' in " + file + ": " + e.getMessage(), e);
        }
    }

    /** {@code hooks}: {@code register_changed} as an argv list (never a shell string), {@code timeout_seconds}. */
    private static OperatorSettings.Hooks hooks(Path file, JsonNode node) {
        if (!node.isObject()) throw wrongType(file, "hooks", "an object");
        List<String> argv = new ArrayList<>();
        int timeout = OperatorSettings.Hooks.DEFAULT_TIMEOUT_SECONDS;
        for (java.util.Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            switch (key) {
                case "register_changed" -> {
                    JsonNode value = node.get(key);
                    if (!value.isArray() || value.isEmpty()) {
                        throw wrongType(file, "hooks.register_changed", "a list of the program and its arguments,"
                                + " such as [\"/opt/hooks/todos.sh\", \"--project\", \"x\"] (no shell line)");
                    }
                    for (JsonNode entry : value) {
                        if (!entry.isTextual()) throw wrongType(file, "hooks.register_changed", "a list of strings");
                        argv.add(entry.textValue());
                    }
                }
                case "timeout_seconds" -> {
                    JsonNode value = node.get(key);
                    if (!value.isIntegralNumber() || value.asInt() < 1) {
                        throw wrongType(file, "hooks.timeout_seconds", "a whole number of seconds, at least 1");
                    }
                    timeout = value.asInt();
                }
                default -> throw new IllegalArgumentException("'hooks." + key + "' in " + file
                        + " is not a known hook; the hooks are register_changed (with timeout_seconds)");
            }
        }
        return new OperatorSettings.Hooks(argv, timeout);
    }

    private static IllegalArgumentException wrongType(Path file, String key, String expected) {
        return new IllegalArgumentException("'" + key + "' in " + file + " must be " + expected);
    }
}
