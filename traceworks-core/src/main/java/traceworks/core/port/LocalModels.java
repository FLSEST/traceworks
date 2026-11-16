package traceworks.core.port;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The model servers of the operator's own ({@value #SETTING} in {@code traceworks.json}): a {@code PI} step whose
 * model is {@code <backend>/<model>} with one of these backends runs against that server and reaches nothing else.
 * Only the operator declares one; no workflow, workspace, run input or environment variable can.
 *
 * @param backends the declared servers by backend id, sorted
 * @throws IllegalArgumentException for an entry whose key is not its backend's id
 */
public record LocalModels(Map<String, Backend> backends) {

    /** The key of the setting in {@code traceworks.json}. */
    public static final String SETTING = "local_models";

    /** The API a server speaks that Traceworks can configure: the chat completions API of OpenAI. */
    public static final String OPENAI_COMPLETIONS = "openai-completions";

    /** The API kinds a backend may name. */
    public static final List<String> APIS = List.of(OPENAI_COMPLETIONS);

    /** The most models one backend lists. */
    public static final int MAX_MODELS = 64;

    /** No server declared: the setting is absent. */
    public static final LocalModels NONE = new LocalModels(Map.of());

    /** What a backend id looks like. */
    public static final String ID_SYNTAX = "[a-z][a-z0-9-]{0,31}";

    /** What a part of a model name looks like; a name is such parts with {@code /} between them. */
    public static final String MODEL_SYNTAX = "[A-Za-z0-9][A-Za-z0-9._:+-]*";

    /** The most characters of a model name. */
    public static final int MAX_MODEL_LENGTH = 128;

    /** The backends an AI CLI reaches at their providers: no local server can take one of these ids. */
    private static final Set<String> PROVIDER_BACKENDS = Set.of("anthropic", "openai", "google", "openrouter");

    /** What Pi reads after a model's last colon as a thinking level; a model name cannot end like that. */
    private static final Set<String> THINKING_LEVELS = Set.of("off", "minimal", "low", "medium", "high", "xhigh",
            "max");

    private static final Pattern ID = Pattern.compile(ID_SYNTAX);
    private static final Pattern MODEL = Pattern.compile(MODEL_SYNTAX + "(/" + MODEL_SYNTAX + ")*");
    private static final Pattern URL = Pattern.compile("(https?)://([^/:?#@\\s]+)(?::([0-9]{1,5}))?(/[^?#\\s]*)?");
    private static final Pattern HOST = Pattern.compile(Egress.HOST_SYNTAX);
    private static final Pattern PATH = Pattern.compile("(/[A-Za-z0-9._~-]+)*");
    private static final Pattern SECRET = Pattern.compile("[A-Z_][A-Z0-9_]*");

    public LocalModels {
        Map<String, Backend> sorted = new TreeMap<>();
        if (backends != null) {
            backends.forEach((id, backend) -> {
                if (backend == null || !backend.id().equals(id)) {
                    throw new IllegalArgumentException("The local backend under '" + id + "' has another id");
                }
                sorted.put(id, backend);
            });
        }
        backends = Collections.unmodifiableMap(sorted);
    }

    /**
     * One model server.
     *
     * @param id     the backend id a model names before its slash ({@value #ID_SYNTAX}); none of the backends of a
     *               provider
     * @param url    the base URL of the server's API, such as {@code http://llm.internal:8080/v1}: {@code http} or
     *               {@code https}, a host name or an IPv4 address, an optional port and path, nothing else. Not this
     *               machine's loopback, which a container cannot reach
     * @param api    the API the server speaks, one of {@link #APIS}
     * @param models the model names a step may ask the server for (parts of {@value #MODEL_SYNTAX} with {@code /}
     *               between them), a closed list, in the order given, each once
     * @param secret the name of the secret that holds the server's key; {@code null} for a server that wants none
     * @throws IllegalArgumentException for a value that is none of the above, with what to write instead
     */
    public record Backend(String id, String url, String api, List<String> models, String secret) {

        public Backend {
            String idProblem = idProblem(id);
            if (idProblem != null) {
                throw new IllegalArgumentException(idProblem);
            }
            String where = "the local backend " + id;
            String urlProblem = urlProblem(url);
            if (urlProblem != null) {
                throw new IllegalArgumentException("the url of " + where + " " + urlProblem);
            }
            if (api == null || !APIS.contains(api)) {
                throw new IllegalArgumentException("the api of " + where + " must be one of "
                        + String.join(", ", APIS) + (api == null ? "" : ", not \"" + api + "\""));
            }
            if (models == null || models.isEmpty()) {
                throw new IllegalArgumentException(where + " lists no model: name the models a step may ask for in"
                        + " models");
            }
            if (models.size() > MAX_MODELS) {
                throw new IllegalArgumentException(where + " lists " + models.size() + " models; at most "
                        + MAX_MODELS);
            }
            Set<String> distinct = new LinkedHashSet<>();
            for (String model : models) {
                String problem = modelProblem(model);
                if (problem != null) {
                    throw new IllegalArgumentException("a model of " + where + " " + problem);
                }
                if (!distinct.add(model)) {
                    throw new IllegalArgumentException(where + " lists the model " + model + " twice");
                }
            }
            models = List.copyOf(distinct);
            if (secret != null) {
                String problem = secretProblem(secret);
                if (problem != null) {
                    throw new IllegalArgumentException("the secret of " + where + " " + problem);
                }
            }
        }

        /** The server as the egress proxy lists it: {@code host:port}, with the port of the scheme when none is written. */
        public String host() {
            Matcher matcher = URL.matcher(url);
            if (!matcher.matches()) {
                throw new IllegalStateException(url);
            }
            String port = matcher.group(3) != null ? String.valueOf(Integer.parseInt(matcher.group(3)))
                    : matcher.group(1).equals("https") ? "443" : "80";
            return matcher.group(2) + ":" + port;
        }

        /** Whether the server speaks plain HTTP: its URL starts with {@code http://}. */
        public boolean plainHttp() {
            return url.startsWith("http://");
        }

        /** Whether a step may ask this server for {@code model}. */
        public boolean serves(String model) {
            return models.contains(model);
        }
    }

    /**
     * A model of a local backend, as a call uses it.
     *
     * @param backend the server
     * @param model   the model name after the slash; one of the backend's when {@link #listed}
     */
    public record Use(Backend backend, String model) {

        /** Whether the backend lists the model. */
        public boolean listed() {
            return backend.serves(model);
        }

        /** Why the call cannot be made; {@code null} when the backend lists the model. */
        public String problem() {
            return listed() ? null : "The local backend " + backend.id() + " does not serve the model " + model
                    + ": " + SETTING + "." + backend.id() + ".models in traceworks.json lists "
                    + String.join(", ", backend.models());
        }
    }

    /** Whether no server is declared. */
    public boolean isEmpty() {
        return backends.isEmpty();
    }

    /** The backend ids, sorted. */
    public List<String> ids() {
        return List.copyOf(backends.keySet());
    }

    /**
     * The local backend {@code model} names, when it is written {@code <backend>/<model>} with a declared backend;
     * empty for every other model, and for none.
     */
    public Optional<Use> of(String model) {
        if (model == null) {
            return Optional.empty();
        }
        int slash = model.indexOf('/');
        if (slash <= 0 || slash == model.length() - 1) {
            return Optional.empty();
        }
        Backend backend = backends.get(model.substring(0, slash));
        return backend == null ? Optional.empty() : Optional.of(new Use(backend, model.substring(slash + 1)));
    }

    /** Why {@code id} is no backend id, as a whole sentence part; {@code null} when it is one. */
    public static String idProblem(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            return "the local backend id \"" + id + "\" must be " + ID_SYNTAX + " (a small letter, then small"
                    + " letters, digits and -)";
        }
        if (PROVIDER_BACKENDS.contains(id)) {
            return "the local backend id \"" + id + "\" is the backend of a provider; choose another id, such as "
                    + "local-" + id;
        }
        return null;
    }

    /** Why {@code model} is no model name of a backend; {@code null} when it is one. */
    public static String modelProblem(String model) {
        if (model == null || model.length() > MAX_MODEL_LENGTH || !MODEL.matcher(model).matches()) {
            return "\"" + model + "\" must be made of parts " + MODEL_SYNTAX + " with / between them (letters,"
                    + " digits and . _ : + -, each part starting with a letter or a digit), at most "
                    + MAX_MODEL_LENGTH + " characters";
        }
        int colon = model.lastIndexOf(':');
        if (colon >= 0 && THINKING_LEVELS.contains(model.substring(colon + 1))) {
            return "\"" + model + "\" ends in :" + model.substring(colon + 1) + ", which Pi reads as a thinking"
                    + " level and not as part of the name";
        }
        return null;
    }

    /**
     * Why {@code url} cannot be the base URL of a model server; {@code null} when it can. It names what to write
     * instead of this machine's loopback, which is not the loopback of the container that connects.
     */
    public static String urlProblem(String url) {
        if (url == null || url.isBlank()) {
            return "is missing";
        }
        Matcher matcher = URL.matcher(url);
        if (!matcher.matches()) {
            return "must be http://host[:port][/path] or https://host[:port][/path], such as"
                    + " http://llm.internal:8080/v1, without a user, a query or a fragment: \"" + url + "\"";
        }
        String host = matcher.group(2);
        if (!host.equals(host.toLowerCase(Locale.ROOT)) || !HOST.matcher(host).matches() || host.length() > 253) {
            return "must name its host as a host name in small letters or an IPv4 address (" + Egress.HOST_SYNTAX
                    + "), not \"" + host + "\"";
        }
        if (matcher.group(3) != null) {
            int port = Integer.parseInt(matcher.group(3));
            if (port < 1 || port > 65535) {
                return "has a port that is not from 1 to 65535: " + matcher.group(3);
            }
        }
        String path = matcher.group(4) == null ? "" : matcher.group(4);
        if (!PATH.matcher(path).matches() || path.contains("/./") || path.contains("/../") || path.endsWith("/.")
                || path.endsWith("/..")) {
            return "has a path that is not made of /segments of letters, digits and . _ ~ - (no / at its end, no"
                    + " . or .. segment): \"" + path + "\"";
        }
        if (loopback(host)) {
            return "names this machine's loopback (" + host + "), which the container of an AI step cannot reach:"
                    + " its loopback is its own. Let the server listen on an address of this machine that"
                    + " containers reach (not only 127.0.0.1), and write that address or a name of it here, such as"
                    + " http://192.168.1.20" + (matcher.group(3) == null ? "" : ":" + matcher.group(3)) + path;
        }
        return null;
    }

    /** Whether {@code host} is a name or address of the loopback, or the address that means every address. */
    private static boolean loopback(String host) {
        return host.equals("localhost") || host.endsWith(".localhost") || host.equals("0.0.0.0")
                || host.equals("0") || host.startsWith("127.") && host.matches("[0-9.]+")
                || host.equals("ip6-localhost") || host.equals("ip6-loopback");
    }

    /**
     * Why {@code name} cannot name the secret of a model server; {@code null} when it can: the form of a secret name,
     * no credential of an AI provider, and no name the CLI or its runtime reads as a setting.
     */
    public static String secretProblem(String name) {
        if (name == null || !SECRET.matcher(name).matches()) {
            return "\"" + name + "\" is not a secret name (capital letters, digits and _, not starting with a"
                    + " digit)";
        }
        if (AiCli.credentialNamesOfAllProviders().contains(name)) {
            return "\"" + name + "\" is the credential of an AI provider; store the server's key under another name";
        }
        for (String prefix : List.of("TRACEWORKS_", "PI_", "NODE_", "LC_", "TINI_", "NPM_")) {
            if (name.startsWith(prefix)) {
                return "\"" + name + "\" starts with " + prefix + ", which the CLI or its container reads as a"
                        + " setting; store the server's key under another name";
            }
        }
        if (ReservedVariables.reserved(name) || RESERVED_SECRETS.contains(name)) {
            return "\"" + name + "\" is a name the container or the CLI uses itself; store the server's key under"
                    + " another name";
        }
        return null;
    }

    private static final Set<String> RESERVED_SECRETS = Set.of("PATH", "HOME", "TMPDIR", "LANG", "USER", "NO_PROXY",
            "ALL_PROXY");

    /** The declared servers as a list, in id order. */
    public List<Backend> list() {
        return new ArrayList<>(backends.values());
    }
}
