package traceworks.core.testing;

import traceworks.core.ai.AiProviderCapabilities;
import traceworks.core.port.AiProvider;
import traceworks.core.port.AiProviders;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An {@link AiProvider} for tests that calls no model: it answers with scripted responses, in order, and records every
 * prompt and request. When the script is used up it answers with the {@link #otherwise} fallback, or throws an
 * {@link IllegalStateException}, so a test never passes on a call it did not expect.
 */
public final class FakeAiProvider implements AiProvider {

    /** One scripted response: the text and the exit code of the provider CLI. */
    private record Response(String text, int exitCode) {}

    private final String id;
    private final AiProviderCapabilities capabilities;
    private final Deque<Response> script = new ArrayDeque<>();
    private Response fallback;
    private final List<String> prompts = new CopyOnWriteArrayList<>();
    private final List<ActionRequest> actionRequests = new CopyOnWriteArrayList<>();
    private final List<ReasoningRequest> reasoningRequests = new CopyOnWriteArrayList<>();

    /** A provider with every capability, such as Claude. */
    public FakeAiProvider(String id) {
        this(id, AiProviderCapabilities.full());
    }

    public FakeAiProvider(String id, AiProviderCapabilities capabilities) {
        this.id = id;
        this.capabilities = capabilities;
    }

    /** The next calls answer with these texts, one each, exit code 0. */
    public synchronized FakeAiProvider answers(String... texts) {
        for (String text : texts) {
            script.add(new Response(text, 0));
        }
        return this;
    }

    /** The next call fails: the CLI ends with {@code exitCode} and writes {@code text}. */
    public synchronized FakeAiProvider fails(int exitCode, String text) {
        script.add(new Response(text, exitCode));
        return this;
    }

    /** Calls after the script is used up answer with {@code text} instead of throwing. */
    public synchronized FakeAiProvider otherwise(String text) {
        fallback = new Response(text, 0);
        return this;
    }

    /** The run's providers with this one as the only and default provider. */
    public AiProviders asProviders() {
        return AiProviders.of(id, List.of(this));
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public AiProviderCapabilities getCapabilities() {
        return capabilities;
    }

    @Override
    public ReasoningResult reason(ReasoningRequest request) {
        reasoningRequests.add(request);
        prompts.add(request.prompt());
        Response response = next(request.prompt());
        if (response.exitCode() != 0) {
            // the port's contract: a failed reasoning call throws instead of returning the CLI's error as the answer
            throw new IllegalStateException(id + " exited with " + response.exitCode() + ": " + response.text());
        }
        return new ReasoningResult(response.text(), null);
    }

    @Override
    public ActionResult executeAction(ActionRequest request) {
        actionRequests.add(request);
        prompts.add(request.prompt());
        Response response = next(request.prompt());
        return new ActionResult(response.text(), response.exitCode(), null);
    }

    private synchronized Response next(String prompt) {
        Response response = script.poll();
        if (response != null) {
            return response;
        }
        if (fallback != null) {
            return fallback;
        }
        throw new IllegalStateException("FakeAiProvider " + id + ": no scripted response left for prompt: " + prompt);
    }

    /** Every prompt so far, reasoning and action calls together, in call order. */
    public List<String> prompts() {
        return List.copyOf(prompts);
    }

    /** Every action call so far, in order. */
    public List<ActionRequest> actionRequests() {
        return List.copyOf(actionRequests);
    }

    /** Every reasoning call so far, in order. */
    public List<ReasoningRequest> reasoningRequests() {
        return List.copyOf(reasoningRequests);
    }
}
