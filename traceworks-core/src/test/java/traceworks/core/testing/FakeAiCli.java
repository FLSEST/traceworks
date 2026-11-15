package traceworks.core.testing;

import traceworks.core.ai.AiProviderCapabilities;
import traceworks.core.ai.AiResponseMetadata;
import traceworks.core.ai.ToolCall;
import traceworks.core.port.AiCli;
import traceworks.core.port.CommandRunner.CommandResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An {@link AiCli} for tests that stands for no real CLI. Its invocation is {@code fake-<id> --kind <kind> --tools
 * <policy>} (and {@code --model <model>} when one is asked for) with {@code FAKE_CLI=1} in the environment and the
 * prompt on stdin. Its outcomes are scripted, tool calls included, and used in order; when the script is used up, the
 * outcome is what the command returned (its stdout as the answer, failed for a non-zero exit code or a timeout). It
 * records every call and every command result it parsed.
 */
public final class FakeAiCli implements AiCli {

    private final String id;
    private final String image;
    private final Deque<AiOutcome> script = new ArrayDeque<>();
    private final List<AiCall> calls = new CopyOnWriteArrayList<>();
    private final List<CommandResult> results = new CopyOnWriteArrayList<>();
    private volatile String refusal;
    private volatile List<String> hosts;
    private volatile boolean web = true;
    private volatile List<String> credentialNames;
    private volatile String noCredentialNames;
    private final Map<String, List<String>> credentialNamesByModel = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> unknownModels = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> backends = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A CLI whose credential names are its provider's ({@link AiCli#CREDENTIALS}).
     *
     * @param id    the provider id, such as {@code CLAUDE}
     * @param image the built-in image the CLI runs in, such as {@code claude}
     */
    public FakeAiCli(String id, String image) {
        this.id = id;
        this.image = image;
    }

    /** A CLI with Claude's id, image and credential names. */
    public static FakeAiCli claude() {
        return new FakeAiCli("CLAUDE", "claude");
    }

    /** A CLI with Pi's id, image and credential names. */
    public static FakeAiCli pi() {
        return new FakeAiCli("PI", "pi");
    }

    /** The host of the fake provider's API: {@code api.<id in lower case>.example:443}. */
    @Override
    public List<String> hosts(AiCall call) {
        return hosts != null ? hosts : List.of("api." + id.toLowerCase(java.util.Locale.ROOT) + ".example:443");
    }

    /** Every call from now on needs these hosts; none for a backend the CLI knows no host of. */
    public FakeAiCli needs(String... hosts) {
        this.hosts = List.of(hosts);
        return this;
    }

    /** The credential names of every call from now on, as a hub CLI names those of one backend. */
    public FakeAiCli credentialNames(String... names) {
        this.credentialNames = List.of(names);
        return this;
    }

    /**
     * From now on the CLI cannot tell the credentials of a call: {@link #credentialNames(AiCall)} throws an
     * {@link IllegalArgumentException} with {@code message}, as Pi's does for a backend it does not know.
     */
    public FakeAiCli knowsNoCredentialNames(String message) {
        this.noCredentialNames = message;
        return this;
    }

    /** A call with {@code model} ({@code null}: without a model) gets these credential names, as of one backend. */
    public FakeAiCli credentialNamesFor(String model, String... names) {
        credentialNamesByModel.put(String.valueOf(model), List.of(names));
        return this;
    }

    /** The CLI cannot tell the credentials of a call with {@code model}: it refuses it with {@code message}. */
    public FakeAiCli unknownModel(String model, String message) {
        unknownModels.put(model, message);
        return this;
    }

    /** A call with {@code model} ({@code null}: without a model) goes to {@code backend}. */
    public FakeAiCli backendFor(String model, String backend) {
        backends.put(String.valueOf(model), backend);
        return this;
    }

    @Override
    public String backend(AiCall call) {
        return backends.get(String.valueOf(call.model()));
    }

    @Override
    public List<String> credentialNames(AiCall call) {
        if (noCredentialNames != null) {
            throw new IllegalArgumentException(noCredentialNames);
        }
        if (call.model() != null && unknownModels.containsKey(call.model())) {
            throw new IllegalArgumentException(unknownModels.get(call.model()));
        }
        List<String> ofModel = credentialNamesByModel.get(String.valueOf(call.model()));
        if (ofModel != null) {
            return ofModel;
        }
        return credentialNames != null ? credentialNames : AiCli.super.credentialNames(call);
    }

    /** The next calls answer with these texts, one each, exit code 0, with real usage data. */
    public synchronized FakeAiCli answers(String... texts) {
        for (String text : texts) {
            script.add(new AiOutcome(text, 0, false, usage(), List.of()));
        }
        return this;
    }

    /** The next call answers with {@code text} after these tool calls. */
    public synchronized FakeAiCli answersAfter(String text, ToolCall... toolCalls) {
        script.add(new AiOutcome(text, 0, false, usage(), List.of(toolCalls)));
        return this;
    }

    /** The next call fails: the CLI ends with {@code exitCode} and reports {@code text}. */
    public synchronized FakeAiCli fails(int exitCode, String text) {
        script.add(new AiOutcome(text, exitCode, true, null, List.of()));
        return this;
    }

    /**
     * Every call from now on is one the CLI cannot make: its invocation throws an {@link IllegalArgumentException}
     * with {@code message}, as a real CLI's does for a system prompt it cannot carry.
     */
    public FakeAiCli refuses(String message) {
        refusal = message;
        return this;
    }

    /** The next call ends as {@code outcome}. */
    public synchronized FakeAiCli then(AiOutcome outcome) {
        script.add(outcome);
        return this;
    }

    /** What every answer reports: 100 tokens in, 20 out, 0.01 USD, one turn, the model {@code fake-model}. */
    public static AiResponseMetadata usage() {
        return new AiResponseMetadata(100, 20, 0.01, "fake-model", false, 50L, 1, null, List.of());
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String image() {
        return image;
    }

    @Override
    public AiProviderCapabilities capabilities() {
        return web ? AiProviderCapabilities.full() : AiProviderCapabilities.full().withoutWeb();
    }

    /** From now on the CLI has no web tool, as Pi has none. */
    public FakeAiCli withoutWeb() {
        web = false;
        return this;
    }

    @Override
    public AiInvocation invocation(AiCall call) {
        calls.add(call);
        if (refusal != null) {
            throw new IllegalArgumentException(refusal);
        }
        List<String> args = new ArrayList<>(List.of("fake-" + id.toLowerCase(java.util.Locale.ROOT), "--kind",
                call.kind().name(), "--tools", call.tools().value()));
        if (call.model() != null) {
            args.addAll(List.of("--model", call.model()));
        }
        if (call.web().enabled()) {
            args.addAll(List.of("--web", call.web().value()));
        }
        return new AiInvocation(args, Map.of("FAKE_CLI", "1"), call.prompt());
    }

    @Override
    public synchronized AiOutcome parse(CommandResult result, AiCall call) {
        results.add(result);
        AiOutcome scripted = script.poll();
        if (scripted != null) {
            return scripted;
        }
        boolean failed = result.exitCode() != 0 || result.timedOut();
        return new AiOutcome(failed && !result.stderr().isEmpty() ? result.stderr() : result.stdout(),
                result.exitCode(), failed, null, List.of());
    }

    /** Every call so far, in order. */
    public List<AiCall> calls() {
        return List.copyOf(calls);
    }

    /** The prompt of every call so far, in order. */
    public List<String> prompts() {
        return calls.stream().map(AiCall::prompt).toList();
    }

    /** Every command result parsed so far, in order. */
    public List<CommandResult> results() {
        return List.copyOf(results);
    }
}
