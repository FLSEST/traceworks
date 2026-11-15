package traceworks.core.blocks;

import traceworks.core.ai.ConversationPrompt.Turn;
import traceworks.core.context.ApprovalRequest;
import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.EventLogException;
import traceworks.core.port.RunEvent.ApprovalAnswered;
import traceworks.core.port.RunEvent.ApprovalRequested;
import traceworks.core.util.TruncationUtil;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The optional approval gate of the agentic loop ({@code require_approval: true}): asks whether to proceed with the
 * planned action. The question is stored in the run first, then announced, as an {@code agentic_approval_required}
 * progress event (action and rationale) and as the run's {@link ApprovalRequested}; a person's answer is recorded as
 * an {@link ApprovalAnswered}. It is a nested wait point: it waits inside the step, never parks the run, and waits up
 * to 30 minutes.
 */
class AgenticApprovalGate {

    private static final List<String> OPTIONS = List.of("Yes", "Yes always", "No");
    private static final int TIMEOUT_MINUTES = 30;

    /** Outcome of an approval request. */
    enum Decision {
        /** Approved: proceed to the ACT phase. */
        PROCEED,
        /** Rejected: skip the ACT phase and go straight to the next REASON iteration. */
        RETRY_ITERATION,
        /** Timed out, was interrupted, or errored: abort the loop. */
        STOP,
        /** "Yes always": proceed, and disable the gate for the remaining iterations. */
        PROCEED_AND_DISABLE
    }

    Decision ask(ExecutionContext context, int stepIndex, int iteration, AgenticPlan plan,
                 Map<String, Object> iterData, StringBuilder fullOutput, List<Turn> history) {
        // Store the question first, then report it: whoever reacts to the event must already find the question open
        ApprovalRequest request = ApprovalRequest.builder()
                .blockId("agentic-" + stepIndex + "-iter-" + iteration)
                .question("Approve action: "
                        + TruncationUtil.head(plan.getAction(), AgenticBlockExecutor.TRUNCATE_SHORT,
                                context.secretValues()))
                .options(OPTIONS)
                .rejectOptions(List.of("No"))
                .stepIndex(stepIndex)
                .timeoutMinutes(TIMEOUT_MINUTES)
                .build();
        CompletableFuture<String> future = context.requestApproval(request);

        AgenticEvents.emit(context, stepIndex, "agentic_approval_required",
                Map.of("iteration", iteration,
                        "action", plan.getAction(),
                        "rationale", TruncationUtil.head(plan.getRationale(), AgenticBlockExecutor.TRUNCATE_MEDIUM,
                                context.secretValues())));
        context.getEvents().emit(new ApprovalRequested(stepIndex, request.getBlockId(), "approval",
                request.getQuestion(), request.getOptions(), request.getRejectOptions(), null, null, false, null, null,
                null, false, false));   // a plan approval waits in the process: never parked, never preset

        String answer;
        try {
            answer = context.awaitApproval(future, TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (EventLogException e) {
            throw e;   // the end of the wait could not be recorded: the run stops
        } catch (TimeoutException e) {
            iterData.put("result", "approval_timeout");
            fullOutput.append("[iteration ").append(iteration)
                    .append(" - APPROVAL TIMEOUT]\n");
            return Decision.STOP;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            iterData.put("result", "approval_interrupted");
            fullOutput.append("[iteration ").append(iteration)
                    .append(" - INTERRUPTED]\n");
            return Decision.STOP;
        } catch (Exception e) {
            iterData.put("result", "approval_error");
            fullOutput.append("[iteration ").append(iteration)
                    .append(" - APPROVAL ERROR: ").append(e.getMessage()).append("]\n");
            return Decision.STOP;
        }
        context.getEvents().emit(new ApprovalAnswered(stepIndex, "approval", answer));

        if ("No".equalsIgnoreCase(answer)) {
            // The rejection must reach the shared conversation, otherwise the next REASON call proposes the same
            // action again
            history.add(new Turn("user",
                    "The user rejected this action. Propose an alternative approach."));
            iterData.put("approval", "rejected");
            fullOutput.append("[iteration ").append(iteration)
                    .append(" - REJECTED]: ").append(plan.getAction()).append("\n");
            return Decision.RETRY_ITERATION;
        }
        if ("Yes always".equalsIgnoreCase(answer)) {
            iterData.put("approval", "approved");
            return Decision.PROCEED_AND_DISABLE;
        }
        iterData.put("approval", "approved");
        return Decision.PROCEED;
    }
}
