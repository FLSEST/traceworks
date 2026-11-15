package traceworks.core.blocks;

import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.StepAiMetadata;
import traceworks.core.engine.StepFailureSummary;
import traceworks.core.engine.WorkflowExecutionException;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.util.TruncationUtil;

import java.util.Locale;

/**
 * The AI evaluation of a conditional's {@code condition} or a while's {@code condition_command}, and what a failed
 * evaluation means. A CLI that ends with an error (limit, login, crash) returns its message instead of throwing; read
 * as an answer, that message would make a conditional take its else branch and a while run its body up to
 * {@code max_iterations}, and the run would end COMPLETED. So a failed evaluation is a failure of its own, settled by
 * {@code on_condition_error}. Two things are no failed evaluation and are never settled that way: a provider the run
 * does not know fails the step ({@link #refused}), and a blank condition is refused before any call.
 *
 * @param answer  the AI's answer, {@code null} when the evaluation failed
 * @param error   why it failed (exit code and the CLI's message, cut), {@code null} when the AI answered
 * @param refused the provider was not available: the step fails whatever {@code on_condition_error} says
 */
record AiCondition(String answer, String error, boolean refused) {

    /** Step metadata key: why the evaluation failed. */
    static final String ERROR_KEY = "conditionError";

    /** Why a blank AI condition is refused. */
    static final String BLANK_MESSAGE = "The condition is blank; an AI condition needs a prompt";

    private static final int ERROR_MAX_CHARS = 300;

    /**
     * {@code on_condition_error}: what a failed evaluation means. A JSON boolean {@code false} means what its author
     * meant, {@code "false"}; {@code true} and any other value fail, like the default.
     */
    enum OnError {
        FAIL, FALSE, SKIP;

        static OnError of(Object configured) {
            if (Boolean.FALSE.equals(configured)) return FALSE;
            if (!(configured instanceof String value)) return FAIL;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "false" -> FALSE;
                case "skip" -> SKIP;
                default -> FAIL;
            };
        }
    }

    /**
     * Asks the AI. The call's AI metadata goes onto {@code target} (the first AI call of a block wins), the failure,
     * if any, under {@link #ERROR_KEY}.
     *
     * @throws WorkflowExecutionException with {@link #BLANK_MESSAGE} for a blank prompt, before any call
     */
    static AiCondition ask(WrapperCommands commands, AiStepRequest request, StepResult target,
                           ExecutionContext context) {
        if (request.prompt().isBlank()) {
            throw new WorkflowExecutionException(BLANK_MESSAGE);
        }
        AiCondition outcome;
        java.util.Collection<String> keepWhole = context.secretValues();
        try {
            AiStepCall.Answer evaluation = commands.ask(request, context);
            StepAiMetadata.carryAi(target, evaluation.metadata());
            outcome = evaluation.exitCode() != 0
                    ? failed("exit code " + evaluation.exitCode() + ": " + evaluation.text(), false, keepWhole)
                    : new AiCondition(evaluation.text() == null ? "" : evaluation.text(), null, false);
        } catch (AiStepCall.ProviderNotAvailable e) {
            outcome = failed(e.getMessage(), true, keepWhole);
        } catch (WorkflowExecutionException e) {
            Throwable cause = e.getCause();
            outcome = failed(e.getMessage()
                    + (cause != null && cause.getMessage() != null ? ": " + cause.getMessage() : ""), false,
                    keepWhole);
        }
        if (outcome.failed()) StepAiMetadata.put(target, ERROR_KEY, outcome.error());
        return outcome;
    }

    boolean failed() {
        return error != null;
    }

    /**
     * The step text for a failed evaluation under {@code onError}; for {@code FALSE} there is none, the condition just
     * counts as false.
     */
    String describe(OnError onError) {
        String reason = "Condition evaluation failed (" + error + ")";
        return onError == OnError.SKIP ? reason + " — block skipped (on_condition_error: skip)" : reason;
    }

    /** {@code FAIL}: the step fails with the reason as its failure summary; {@code SKIP}: it is skipped. */
    void settle(StepResult step, OnError onError) {
        if (onError == OnError.SKIP) {
            step.setStatus(StepStatus.SKIPPED);
        } else if (onError == OnError.FAIL) {
            step.setStatus(StepStatus.FAILED);
            StepFailureSummary.put(step, describe(OnError.FAIL));
        }
    }

    /**
     * What this failed evaluation means: {@code FAIL} when the provider was refused or the run is stopping, else what
     * the block's {@code on_condition_error} says.
     */
    OnError onError(Object configured, boolean runStopping) {
        return refused ? OnError.FAIL : effective(configured, runStopping);
    }

    /** A cancelled run takes no branch and no further iteration on a failed evaluation, whatever the block says. */
    static OnError effective(Object configured, boolean runStopping) {
        return runStopping ? OnError.FAIL : OnError.of(configured);
    }

    /** @param keepWhole the run's secret values, which the cut of a long error keeps whole */
    private static AiCondition failed(String error, boolean refused, java.util.Collection<String> keepWhole) {
        String text = error == null ? "" : error.strip();
        return new AiCondition(null, text.length() > ERROR_MAX_CHARS
                ? text.substring(0, TruncationUtil.cutBefore(text, ERROR_MAX_CHARS, keepWhole)) + "…" : text,
                refused);
    }
}
