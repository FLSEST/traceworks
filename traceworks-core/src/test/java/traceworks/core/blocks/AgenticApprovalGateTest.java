package traceworks.core.blocks;

import traceworks.core.ai.ConversationPrompt.Turn;
import traceworks.core.context.ApprovalRequest;
import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.EventLogException;
import traceworks.core.port.RunEvent.AgenticProgress;
import traceworks.core.port.RunEvent.ApprovalAnswered;
import traceworks.core.port.RunEvent.ApprovalRequested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AgenticApprovalGateTest {

    private final AgenticApprovalGate gate = new AgenticApprovalGate();
    private final List<Object> calls = new ArrayList<>();

    private ExecutionContext contextAnswering(CompletableFuture<String> answer) {
        ExecutionContext context = spy(ExecutionContext.builder()
                .containerId("c").workflowRunId("run-1").events(calls::add).build());
        doAnswer(inv -> {
            calls.add("requestApproval");
            return answer;
        }).when(context).requestApproval(any(ApprovalRequest.class));
        return context;
    }

    private static AgenticPlan plan(String action) {
        return AgenticPlan.builder()
                .goalStatus("IN_PROGRESS")
                .rationale("because")
                .action(action)
                .summary("summary")
                .build();
    }

    @Test
    void no_returnsRetryIteration_andAppendsRejectionTurn() {
        ExecutionContext context = contextAnswering(CompletableFuture.completedFuture("No"));
        Map<String, Object> iterData = new LinkedHashMap<>();
        StringBuilder fullOutput = new StringBuilder();
        List<Turn> history = new ArrayList<>();

        AgenticApprovalGate.Decision decision = gate.ask(context, 0, 1, plan("rm -rf build"),
                iterData, fullOutput, history);

        assertThat(decision).isEqualTo(AgenticApprovalGate.Decision.RETRY_ITERATION);
        assertThat(history).contains(new Turn("user", "The user rejected this action. Propose an alternative approach."));
        assertThat(fullOutput.toString()).contains("[iteration 1 - REJECTED]: rm -rf build");
        assertThat(iterData.get("approval")).isEqualTo("rejected");
    }

    /**
     * Whoever reacts to the announcement must find the question already open in the run, so the question is stored
     * first; the announcement is the agentic progress event and the run's approval request, the answer is recorded.
     */
    @Test
    void registersTheQuestionBeforeAnnouncingIt() {
        ExecutionContext context = contextAnswering(CompletableFuture.completedFuture("Yes"));

        gate.ask(context, 2, 1, plan("do it"), new LinkedHashMap<>(), new StringBuilder(), new ArrayList<>());

        assertThat(calls).hasSize(4);
        assertThat(calls.get(0)).isEqualTo("requestApproval");
        assertThat(calls.get(1)).isInstanceOfSatisfying(AgenticProgress.class,
                progress -> assertThat(progress.type()).isEqualTo("agentic_approval_required"));
        assertThat(calls.get(2)).isEqualTo(new ApprovalRequested(2, "agentic-2-iter-1", "approval",
                "Approve action: do it", List.of("Yes", "Yes always", "No"), List.of("No"), null, null, false, null,
                null, null));
        assertThat(calls.get(3)).isEqualTo(new ApprovalAnswered(2, "approval", "Yes"));
    }

    @Test
    void yesAlways_returnsProceedAndDisable() {
        ExecutionContext context = contextAnswering(CompletableFuture.completedFuture("Yes always"));
        Map<String, Object> iterData = new LinkedHashMap<>();

        AgenticApprovalGate.Decision decision = gate.ask(context, 0, 1, plan("do it"),
                iterData, new StringBuilder(), new ArrayList<>());

        assertThat(decision).isEqualTo(AgenticApprovalGate.Decision.PROCEED_AND_DISABLE);
        assertThat(iterData.get("approval")).isEqualTo("approved");
    }

    @Test
    void yes_returnsProceed() {
        ExecutionContext context = contextAnswering(CompletableFuture.completedFuture("Yes"));
        Map<String, Object> iterData = new LinkedHashMap<>();

        AgenticApprovalGate.Decision decision = gate.ask(context, 0, 1, plan("do it"),
                iterData, new StringBuilder(), new ArrayList<>());

        assertThat(decision).isEqualTo(AgenticApprovalGate.Decision.PROCEED);
        assertThat(iterData.get("approval")).isEqualTo("approved");
    }

    @Test
    void futureTimesOut_returnsStop_andAppendsTimeoutLine() {
        // A future whose get(timeout, unit) always throws TimeoutException: exercises the gate's
        // timeout branch without waiting 30 minutes.
        CompletableFuture<String> neverCompletes = new CompletableFuture<>() {
            @Override
            public String get(long timeout, TimeUnit unit) throws TimeoutException {
                throw new TimeoutException("simulated");
            }
        };
        ExecutionContext context = contextAnswering(neverCompletes);
        Map<String, Object> iterData = new LinkedHashMap<>();
        StringBuilder fullOutput = new StringBuilder();

        AgenticApprovalGate.Decision decision = gate.ask(context, 0, 1, plan("do it"),
                iterData, fullOutput, new ArrayList<>());

        assertThat(decision).isEqualTo(AgenticApprovalGate.Decision.STOP);
        assertThat(fullOutput.toString()).contains("[iteration 1 - APPROVAL TIMEOUT]");
        assertThat(iterData.get("result")).isEqualTo("approval_timeout");
    }

    /** A wait the run's thread was interrupted in approves nothing: the loop stops, and the interrupt stays set. */
    @Test
    void waitInterrupted_returnsStop_andKeepsTheInterrupt() {
        CompletableFuture<String> interrupted = new CompletableFuture<>() {
            @Override
            public String get(long timeout, TimeUnit unit) throws InterruptedException {
                throw new InterruptedException("simulated");
            }
        };
        ExecutionContext context = contextAnswering(interrupted);
        Map<String, Object> iterData = new LinkedHashMap<>();
        StringBuilder fullOutput = new StringBuilder();

        AgenticApprovalGate.Decision decision = gate.ask(context, 0, 1, plan("rm -rf build"),
                iterData, fullOutput, new ArrayList<>());

        assertThat(Thread.interrupted()).as("the interrupt is kept for the run's thread").isTrue();
        assertThat(decision).isEqualTo(AgenticApprovalGate.Decision.STOP);
        assertThat(fullOutput.toString()).contains("[iteration 1 - INTERRUPTED]");
        assertThat(iterData.get("result")).isEqualTo("approval_interrupted");
    }

    @Test
    void futureFails_returnsStop_andAppendsApprovalError() {
        ExecutionContext context = contextAnswering(
                CompletableFuture.failedFuture(new IllegalStateException("channel closed")));
        Map<String, Object> iterData = new LinkedHashMap<>();
        StringBuilder fullOutput = new StringBuilder();

        AgenticApprovalGate.Decision decision = gate.ask(context, 0, 1, plan("do it"),
                iterData, fullOutput, new ArrayList<>());

        assertThat(decision).isEqualTo(AgenticApprovalGate.Decision.STOP);
        assertThat(fullOutput.toString()).contains("[iteration 1 - APPROVAL ERROR: ").contains("channel closed");
        assertThat(iterData.get("result")).isEqualTo("approval_error");
    }

    @Test
    void requestApproval_carriesQuestionAndOptionsAndBlockId() {
        ExecutionContext context = contextAnswering(CompletableFuture.completedFuture("Yes"));

        gate.ask(context, 3, 2, plan("rm -rf build"), new LinkedHashMap<>(), new StringBuilder(), new ArrayList<>());

        verify(context, times(1)).requestApproval(argThat(r ->
                r.getQuestion().equals("Approve action: rm -rf build")
                        && r.getOptions().equals(List.of("Yes", "Yes always", "No"))
                        && r.getBlockId().equals("agentic-3-iter-2")));
    }

    /** The run record is the event log: a failed write is never turned into an "approval error" of the loop. */
    @Test
    void eventLogFailureWhileTheWaitEnds_propagates() {
        ExecutionContext context = ExecutionContext.builder().workflowRunId("run-1").build();
        context.onApprovalWait(isWaiting -> {
            if (isWaiting) {
                context.resolveApproval(context.getCurrentApprovalRequest(), "Yes");
            } else {
                throw new EventLogException(new IOException("disk full"));
            }
        });

        assertThatThrownBy(() -> gate.ask(context, 0, 1, plan("do it"), new LinkedHashMap<>(), new StringBuilder(),
                new ArrayList<>())).isInstanceOf(EventLogException.class);
    }
}
