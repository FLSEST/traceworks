package traceworks.core.record;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import traceworks.core.ai.AiProviderCapabilities;
import traceworks.core.ai.AiResponseMetadata;
import traceworks.core.ai.ConversationPrompt;
import traceworks.core.ai.ToolCall;
import traceworks.core.ai.ToolPolicy;
import traceworks.core.port.WebAccess;
import traceworks.core.engine.EventLogException;
import traceworks.core.port.AiCallFailedException;
import traceworks.core.port.AiProvider;
import traceworks.core.port.AiProviders;
import traceworks.core.record.RecordingCommandRunner.Attribution;
import traceworks.core.util.TruncationUtil;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The run's AI providers, recorded: every call of a provider they resolve writes {@code ai.request}
 * before the call, and once it returns one {@code ai.tool_call} per tool call, in order, then {@code ai.response}. The
 * provider runs its CLI through the recording command runner, so the CLI's {@code step.command} and
 * {@code step.command.finished} stand between the request and the tool calls. Every event is attributed like a
 * command, to the step running now and its foreach item, and all of them to the attribution of the request.
 *
 * <p>A reasoning call that fails throws an {@link AiCallFailedException}: its tool calls and its response with the exit
 * code, the usage and the CLI's message are recorded from the outcome it carries (a timeout's partial tool calls
 * included), and the exception goes on to the caller. A call that throws anything else still gets its
 * {@code ai.response}, with the error and no exit code. What the CLI reports is untrusted: its names (a tool's, the
 * model's) are cut at {@value #NAME_LIMIT} characters inline, and its texts go to blobs. Finding a provider records
 * nothing. A tool's result is cut at {@value #RESULT_LIMIT} characters, where the cut splits no secret value, so the
 * record can redact what is left of it.
 *
 * <p>A line or a blob of these events that cannot be written is an {@link EventLogException}, so the block that makes
 * the call lets it through and the run ends as an event-log failure, as for a failed write of any other event.
 */
final class RecordingAiProviders implements AiProviders {

    /** The most characters of a tool's result the record keeps. */
    static final int RESULT_LIMIT = 2_000;

    /** The most characters of a name the CLI reports (a tool's, the model's) that a line holds inline. */
    static final int NAME_LIMIT = 256;

    private final AiProviders delegate;
    private final RunRecorder recorder;
    private final Blobs blobs;
    private final Map<AiProvider, AiProvider> recorded = new ConcurrentHashMap<>();

    RecordingAiProviders(AiProviders delegate, RunRecorder recorder, Blobs blobs) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
    }

    @Override
    public AiProvider resolve(String providerId) {
        return recorded.computeIfAbsent(delegate.resolve(providerId), Recording::new);
    }

    @Override
    public String defaultProviderId() {
        return delegate.defaultProviderId();
    }

    /** {@code text} cut at {@value #RESULT_LIMIT} characters, before a secret value or a character it would split. */
    static String cut(String text, Collection<String> keepWhole) {
        return cut(text, RESULT_LIMIT, keepWhole);
    }

    /** {@code text} cut at {@code limit} characters, before a secret value or a character it would split. */
    static String cut(String text, int limit, Collection<String> keepWhole) {
        if (text == null || text.length() <= limit) {
            return text;
        }
        int at = TruncationUtil.cutBefore(text, limit, keepWhole);
        if (at > 0 && Character.isHighSurrogate(text.charAt(at - 1))) {
            at = TruncationUtil.cutBefore(text, at - 1, keepWhole);
        }
        return text.substring(0, at);
    }

    /** One provider, recorded. */
    private final class Recording implements AiProvider {

        private final AiProvider provider;

        Recording(AiProvider provider) {
            this.provider = provider;
        }

        @Override
        public String getId() {
            return provider.getId();
        }

        @Override
        public AiProviderCapabilities getCapabilities() {
            return provider.getCapabilities();
        }

        @Override
        public String buildConversationPrompt(String systemPrompt, List<ConversationPrompt.Turn> history) {
            return provider.buildConversationPrompt(systemPrompt, history);
        }

        @Override
        public ReasoningResult reason(ReasoningRequest request) throws IOException {
            Attribution where = request(request.model(), request.effort(), request.tools(), request.web(),
                    request.prompt(), request.systemPrompt());
            ReasoningResult result;
            try {
                result = provider.reason(request);
            } catch (AiCallFailedException e) {
                toolCalls(where, e.outcome().toolCalls());
                response(where, e.outcome().metadata(), e.outcome().exitCode() == 0 ? 1 : e.outcome().exitCode(),
                        e.outcome().text());
                throw e;
            } catch (IOException | RuntimeException e) {
                thrown(where, e);
                throw e;
            }
            toolCalls(where, result.toolCalls());
            response(where, result.metadata(), 0, result.text());
            return result;
        }

        @Override
        public ActionResult executeAction(ActionRequest request) throws IOException {
            Attribution where = request(request.model(), request.effort(), request.tools(), request.web(),
                    request.prompt(), request.systemPrompt());
            ActionResult result;
            try {
                result = provider.executeAction(request);
            } catch (IOException | RuntimeException e) {
                thrown(where, e);
                throw e;
            }
            toolCalls(where, result.toolCalls());
            response(where, result.metadata(), result.exitCode(), result.text());
            return result;
        }

        private Attribution request(String model, String effort, ToolPolicy tools, WebAccess web, String prompt,
                                    String systemPrompt) {
            return written(() -> {
                ObjectNode f = Blobs.MAPPER.createObjectNode();
                f.put("provider", provider.getId());
                f.put("model", model);
                f.put("effort", effort);
                f.put("tools", (tools != null ? tools : ToolPolicy.NONE).value());
                WebAccess access = web != null ? web : WebAccess.NONE;
                f.put("web", access.value());
                if (access.fetches()) {
                    ArrayNode hosts = f.putArray("web_hosts");
                    access.hosts().forEach(hosts::add);
                }
                f.set("prompt", blobs.of(prompt));
                f.set("system_prompt", blobs.of(systemPrompt));
                return recorder.appendCommand(RecordTypes.AI_REQUEST, f, null);
            });
        }

        private void toolCalls(Attribution where, List<ToolCall> toolCalls) {
            Collection<String> keepWhole = recorder.keepWhole();
            for (ToolCall call : toolCalls) {
                written(() -> {
                    ObjectNode f = Blobs.MAPPER.createObjectNode();
                    f.put("tool", cut(call.tool(), NAME_LIMIT, keepWhole));
                    f.set("arguments", blobs.of(call.argumentsJson()));
                    f.set("result", blobs.of(cut(call.result(), keepWhole)));
                    f.put("error", call.error());
                    return recorder.appendCommand(RecordTypes.AI_TOOL_CALL, f, where);
                });
            }
        }

        /** The response of a call that returned: its answer on exit code 0, else the CLI's message as the error. */
        private void response(Attribution where, AiResponseMetadata metadata, int exitCode, String text) {
            written(() -> {
                ObjectNode f = usage(metadata);
                f.put("exit_code", exitCode);
                f.set("output", blobs.of(exitCode == 0 ? text : null));
                f.set("error", blobs.of(exitCode == 0 ? null : text));
                return recorder.appendCommand(RecordTypes.AI_RESPONSE, f, where);
            });
        }

        private void thrown(Attribution where, Exception e) {
            written(() -> {
                ObjectNode f = usage(null);
                f.putNull("exit_code");
                f.putNull("output");
                f.set("error", blobs.of(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                return recorder.appendCommand(RecordTypes.AI_RESPONSE, f, where);
            });
        }

        /** A write of the record; one that fails is an event-log failure, which ends the run. */
        private static <T> T written(Supplier<T> write) {
            try {
                return write.get();
            } catch (EventLogException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new EventLogException(e);
            }
        }

        /** The model that answered and the usage, each {@code null} when the CLI reported none. */
        private ObjectNode usage(AiResponseMetadata metadata) {
            ObjectNode f = Blobs.MAPPER.createObjectNode();
            if (metadata == null) {
                f.putNull("model");
                f.putNull("input_tokens");
                f.putNull("output_tokens");
                f.putNull("cache_read_input_tokens");
                f.putNull("cache_creation_input_tokens");
                f.putNull("cost_usd");
                f.putNull("num_turns");
                return f;
            }
            f.put("model", cut(metadata.model(), NAME_LIMIT, recorder.keepWhole()));
            f.put("input_tokens", metadata.inputTokens());
            f.put("output_tokens", metadata.outputTokens());
            // input_tokens is only the input the prompt cache did not serve: the cached part is sent too
            f.put("cache_read_input_tokens", metadata.cacheReadInputTokens());
            f.put("cache_creation_input_tokens", metadata.cacheCreationInputTokens());
            f.put("cost_usd", metadata.costUsd());
            f.put("num_turns", metadata.numTurns());
            return f;
        }
    }
}
