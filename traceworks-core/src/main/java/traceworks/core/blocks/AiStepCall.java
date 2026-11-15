package traceworks.core.blocks;

import traceworks.core.ai.AiProviderCapabilities;
import traceworks.core.ai.CallPriority;
import traceworks.core.ai.ToolPolicy;
import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.EventLogException;
import traceworks.core.engine.StepAiMetadata;
import traceworks.core.engine.WorkflowExecutionException;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.port.AiProvider;
import traceworks.core.port.Clock;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One AI call of a block step through the run's {@link traceworks.core.port.AiProviders} ({@link #run} and
 * {@link #ask}). The call has the tools of its request (none unless a
 * {@code simple} block names them) and runs in the background slots; the step or answer carries the response metadata,
 * the provider, the requested model, the system prompt and the {@code aiRequest} settings. In {@link #run} a provider
 * that throws, or a provider id the run does not know, makes a failed step that names the error; a record of the call
 * that cannot be written ({@link EventLogException}) goes on to the engine, which ends the run.
 */
class AiStepCall {

    /**
     * The answer of {@link #ask}.
     *
     * @param text     what the provider answered; on a non-zero exit code its error message
     * @param exitCode the exit code of the provider CLI
     * @param metadata the step metadata of the call (as {@link #run} puts it on a step)
     */
    record Answer(String text, int exitCode, Map<String, Object> metadata) {}

    /**
     * {@link #ask} was refused: the run has no provider with the requested id, or no default one. Not a
     * {@link WorkflowExecutionException}, so a block cannot mistake it for a failed answer: a step that asks for one
     * provider never runs on another, and never goes on as if the question had been answered.
     */
    static final class ProviderNotAvailable extends RuntimeException {
        ProviderNotAvailable(IllegalArgumentException cause) {
            super(cause.getMessage(), cause);
        }
    }

    /** Runs the call and returns its step. */
    StepResult run(AiStepRequest request, ExecutionContext context) {
        Clock clock = context.getClock();
        StepResult step = new StepResult();
        step.setStepIndex(request.stepIndex());
        Instant started = clock.now();
        step.setStartedAt(started);
        step.setInput(request.prompt());
        step.setStatus(StepStatus.RUNNING);
        try {
            AiProvider provider = context.getAiProviders().resolve(request.providerId());
            context.markModelAsked();
            AiProvider.ActionResult result = provider.executeAction(actionRequest(request));
            step.setOutput(result.text());
            step.setStatus(result.exitCode() == 0 ? StepStatus.COMPLETED : StepStatus.FAILED);
            step.setMetadata(callMetadata(request, provider, result));
        } catch (EventLogException e) {
            throw e;   // the record of the call could not be written: the run stops, the step result does not matter
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            step.setOutput("Error: " + message);
            step.setStatus(StepStatus.FAILED);
            step.setMetadata(Map.of("error", message));
        }
        Instant completed = clock.now();
        step.setCompletedAt(completed);
        step.setDurationMs(completed.toEpochMilli() - started.toEpochMilli());
        return step;
    }

    /**
     * Asks the provider a question whose answer the block reads itself, such as the condition of a
     * {@code conditional} or a {@code while}. A non-zero exit code is an answer too; the caller decides what it means.
     *
     * @throws ProviderNotAvailable      when the run has no such provider
     * @throws WorkflowExecutionException when the call itself fails; the cause says why
     */
    Answer ask(AiStepRequest request, ExecutionContext context) {
        AiProvider provider;
        try {
            provider = context.getAiProviders().resolve(request.providerId());
        } catch (IllegalArgumentException e) {
            throw new ProviderNotAvailable(e);
        }
        context.markModelAsked();
        try {
            AiProvider.ActionResult result = provider.executeAction(actionRequest(request));
            return new Answer(result.text(), result.exitCode(), callMetadata(request, provider, result));
        } catch (IOException e) {
            throw new WorkflowExecutionException("AI call failed (project: " + request.workingDirectory() + ")", e);
        }
    }

    /** The provider request of a step call: a background call with the request's tools and workspace setting. */
    private static AiProvider.ActionRequest actionRequest(AiStepRequest request) {
        return new AiProvider.ActionRequest(request.prompt(), request.model(), request.systemPrompt(),
                request.workingDirectory(), request.timeoutSeconds(), CallPriority.BACKGROUND, request.tools(),
                request.writableWorkspace(), request.effort(), request.web());
    }

    /** Step metadata of one call: response metadata plus requestedModel, model, provider, aiSystemPrompt and aiRequest. */
    private static Map<String, Object> callMetadata(AiStepRequest request, AiProvider provider,
                                                    AiProvider.ActionResult result) {
        Map<String, Object> meta = new LinkedHashMap<>(
                result.metadata() != null ? result.metadata().toMap() : Map.of());
        if (request.model() != null && !request.model().isBlank()) {
            meta.put("requestedModel", request.model());
            // A provider that reports no model ran the one it was asked for
            meta.putIfAbsent("model", request.model());
        }
        meta.put("provider", provider.getId());
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            meta.put("aiSystemPrompt", request.systemPrompt());
        }
        AiProviderCapabilities capabilities = provider.getCapabilities();
        meta.put(StepAiMetadata.AI_REQUEST, aiRequest(request, provider.getId(),
                capabilities != null && capabilities.supportsEffort()));
        return meta;
    }

    /**
     * The {@code aiRequest} map of a call: the request settings plus the resolved provider id. The tools appear when
     * there are any, and {@code workspaceWritable} when the workspace is writable; both are left out by default, as
     * an unset model or effort is.
     *
     * @param effortApplies whether the provider passes an effort on to its CLI. A provider without a flag for it ignores
     *                      it, so its steps do not claim one.
     */
    static Map<String, Object> aiRequest(AiStepRequest request, String resolvedProviderId, boolean effortApplies) {
        Map<String, Object> params = StepAiMetadata.aiRequest(resolvedProviderId, request.model(),
                effortApplies ? request.effort() : null);
        if (request.timeoutSeconds() != null) {
            params.put("timeoutSeconds", request.timeoutSeconds());
        }
        if (request.tools() != ToolPolicy.NONE) {
            params.put("tools", request.tools().value());
        }
        if (request.web().enabled()) {
            params.put("web", request.web().value());
        }
        if (request.writableWorkspace()) {
            params.put("workspaceWritable", true);
        }
        return params;
    }
}
