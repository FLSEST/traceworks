package traceworks.core.context;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalGateTest {

    private final ApprovalGate gate = new ApprovalGate();

    @Test
    void request_setsCurrentQuestion_andResolveCompletesAndClears() throws Exception {
        ApprovalRequest request = ApprovalRequest.builder().question("Go on?").build();

        CompletableFuture<String> future = gate.request(request);
        assertThat(gate.currentRequest()).isSameAs(request);
        assertThat(future).isNotDone();

        assertThat(gate.resolve(request, "Yes")).isTrue();

        assertThat(future.get()).isEqualTo("Yes");
        assertThat(gate.currentRequest()).isNull();
    }

    @Test
    void resolveWithoutPendingRequest_isHarmless() {
        assertThat(gate.resolve(ApprovalRequest.builder().build(), "Yes")).isFalse();

        assertThat(gate.currentRequest()).isNull();
    }

    @Test
    void secondResolve_doesNotTouchTheAlreadyCompletedFuture() throws Exception {
        ApprovalRequest request = ApprovalRequest.builder().build();
        CompletableFuture<String> future = gate.request(request);
        assertThat(gate.resolve(request, "first")).isTrue();
        assertThat(gate.resolve(request, "second")).isFalse();

        assertThat(future.get()).isEqualTo("first");
    }

    @Test
    void cancel_failsPendingFutureWithCancellation_andClearsQuestion() {
        CompletableFuture<String> future = gate.request(ApprovalRequest.builder().question("q").build());

        gate.cancel();

        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(() -> gate.await(future, 1, TimeUnit.SECONDS)).isInstanceOf(CancellationException.class)
                .hasMessageContaining("Execution cancelled");
        assertThat(gate.currentRequest()).isNull();
    }

    @Test
    void cancelWithoutPendingRequest_isHarmless() {
        gate.cancel();

        assertThat(gate.currentRequest()).isNull();
    }

    @Test
    void newRequest_replacesTheOldFuture_soResolveAnswersOnlyTheNewOne() throws Exception {
        ApprovalRequest oldQuestion = ApprovalRequest.builder().question("old").build();
        ApprovalRequest newQuestion = ApprovalRequest.builder().question("new").build();
        CompletableFuture<String> old = gate.request(oldQuestion);
        CompletableFuture<String> current = gate.request(newQuestion);

        assertThat(gate.resolve(oldQuestion, "x")).isFalse();
        assertThat(gate.resolve(newQuestion, "x")).isTrue();

        assertThat(current.get()).isEqualTo("x");
        assertThat(old).isNotDone();
    }

    @Test
    void memory_remembersAnswersPerQuestion() {
        assertThat(gate.remembered("q")).isNull();

        gate.remember("q", "a");
        gate.remember("q", "b");

        assertThat(gate.remembered("q")).isEqualTo("b");
        assertThat(gate.remembered("other")).isNull();
    }

    // ── Show waiting ──

    @Test
    void request_reportsWaiting_andAwaitReportsItsEnd() throws Exception {
        List<Boolean> events = new ArrayList<>();
        gate.onWait(events::add);

        ApprovalRequest request = ApprovalRequest.builder().question("Go on?").build();
        CompletableFuture<String> future = gate.request(request);
        assertThat(events).containsExactly(true);
        gate.resolve(request, "Yes");

        assertThat(gate.await(future, 1, TimeUnit.SECONDS)).isEqualTo("Yes");
        assertThat(events).containsExactly(true, false);
    }

    @Test
    void await_reportsTheEndAlsoOnTimeout() {
        List<Boolean> events = new ArrayList<>();
        gate.onWait(events::add);
        CompletableFuture<String> future = gate.request(ApprovalRequest.builder().build());

        assertThatThrownBy(() -> gate.await(future, 1, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
        assertThat(events).containsExactly(true, false);
    }

    @Test
    void withoutAListener_theWaitWorksAsBefore() throws Exception {
        gate.onWait(null);
        ApprovalRequest request = ApprovalRequest.builder().build();
        CompletableFuture<String> future = gate.request(request);
        gate.resolve(request, "Yes");

        assertThat(gate.await(future, 1, TimeUnit.SECONDS)).isEqualTo("Yes");
    }

    // ── Parking and preset answers ──

    @Test
    void theQuestionOfTheParkingStep_parks_withoutOpening() {
        List<Boolean> events = new ArrayList<>();
        gate.onWait(events::add);
        gate.parkQuestionOf(2);
        ApprovalRequest request = ApprovalRequest.builder().question("Deploy?").stepIndex(2).build();

        CompletableFuture<String> future = gate.request(request);

        assertThatThrownBy(() -> gate.await(future, 1, TimeUnit.SECONDS)).isInstanceOf(RunParkedException.class);
        assertThat(gate.parked()).isSameAs(request);
        assertThat(gate.currentRequest()).as("nobody can answer it in the process").isNull();
        assertThat(events).as("the step goes on waiting, outside the process").containsExactly(true);
    }

    @Test
    void aQuestionOfAnotherStep_opensAsBefore() {
        gate.parkQuestionOf(2);
        ApprovalRequest request = ApprovalRequest.builder().question("Go on?").stepIndex(3).build();

        CompletableFuture<String> future = gate.request(request);

        assertThat(future).isNotDone();
        assertThat(gate.currentRequest()).isSameAs(request);
        assertThat(gate.parked()).isNull();
    }

    @Test
    void aPreset_answersItsQuestionOnce_withoutWaiting() throws Exception {
        List<Boolean> events = new ArrayList<>();
        gate.onWait(events::add);
        gate.parkQuestionOf(1);
        gate.preset(1, "approval", "Deploy?", "Yes", java.util.List.of());
        ApprovalRequest request = ApprovalRequest.builder().question("Deploy?").stepIndex(1).build();

        CompletableFuture<String> answered = gate.request(request);

        assertThat(gate.await(answered, 1, TimeUnit.SECONDS)).isEqualTo("Yes");
        assertThat(gate.parked()).as("the preset wins over parking").isNull();
        assertThat(gate.currentRequest()).isNull();
        assertThat(events).as("the step never shows waiting").doesNotContain(true);
        assertThatThrownBy(() -> gate.await(gate.request(request), 1, TimeUnit.SECONDS))
                .as("used up: the same question parks now")
                .isInstanceOf(RunParkedException.class);
    }

    @Test
    void aPreset_forAnotherStepOrType_answersNothing() {
        gate.preset(1, "human-input", "Who?", "Operator", java.util.List.of());

        CompletableFuture<String> otherType = gate.request(
                ApprovalRequest.builder().question("Who?").stepIndex(1).type("approval").build());
        CompletableFuture<String> otherStep = gate.request(
                ApprovalRequest.builder().question("Who?").stepIndex(2).type("human-input").build());

        assertThat(otherType).isNotDone();
        assertThat(otherStep).isNotDone();
    }

    @Test
    void aPreset_forAChangedQuestion_failsTheQuestion() {
        gate.preset(1, "approval", "Deploy?", "Yes", java.util.List.of());

        CompletableFuture<String> future = gate.request(
                ApprovalRequest.builder().question("Deploy to production?").stepIndex(1).build());

        assertThatThrownBy(() -> gate.await(future, 1, TimeUnit.SECONDS))
                .as("the step's own failure, not a failed wait")
                .isInstanceOf(QuestionChangedException.class)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the question changed while the run was parked: the answer was given to \"Deploy?\"");
        assertThat(gate.currentRequest()).isNull();
    }

    @Test
    void aShadowQuestion_ofTheParkingStep_opensAsBefore() {
        gate.parkQuestionOf(2);
        ApprovalRequest request = ApprovalRequest.builder().question("Deploy?").stepIndex(2).shadow(true).build();

        CompletableFuture<String> future = gate.request(request);

        assertThat(future).as("a person does not answer it alone: it waits in the process").isNotDone();
        assertThat(gate.currentRequest()).isSameAs(request);
        assertThat(gate.parked()).isNull();
    }

    // ── Answering one question from outside (RunControl.answer) ──

    @Test
    void resolveTheQuestion_answersOnlyWhileItIsTheOpenOne() throws Exception {
        ApprovalRequest first = ApprovalRequest.builder().question("first?").build();
        ApprovalRequest second = ApprovalRequest.builder().question("second?").build();
        CompletableFuture<String> firstAnswer = gate.request(first);

        assertThat(gate.resolve(second, "x")).as("not the open question").isFalse();
        assertThat(firstAnswer).isNotDone();
        assertThat(gate.resolve(first, "Yes")).isTrue();
        assertThat(firstAnswer.get()).isEqualTo("Yes");
        assertThat(gate.currentRequest()).isNull();
        assertThat(gate.resolve(first, "again")).as("already answered").isFalse();
    }

    @Test
    void resolveTheQuestion_doesNotAnswerTheNextQuestion() {
        ApprovalRequest first = ApprovalRequest.builder().question("first?").build();
        gate.request(first);
        gate.resolve(first, "Yes");
        CompletableFuture<String> next = gate.request(ApprovalRequest.builder().question("next?").build());

        assertThat(gate.resolve(first, "late answer")).isFalse();
        assertThat(next).isNotDone();
    }

    /** A timed-out question does not stay open until the next one, where an answer to it would go nowhere. */
    @Test
    void aQuestionThatTimedOut_isNoLongerOpen() {
        ApprovalRequest question = ApprovalRequest.builder().question("q").build();
        CompletableFuture<String> future = gate.request(question);

        assertThatThrownBy(() -> gate.await(future, 1, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        assertThat(gate.currentRequest()).isNull();
        assertThat(gate.resolve(question, "Yes")).as("refused, so the timeout stands").isFalse();
        assertThat(future).isNotDone();
    }

    /** A wait the thread was interrupted in closes its question: an answer to it is refused, not left for nobody. */
    @Test
    void aWaitThatWasInterrupted_closesItsQuestion() {
        ApprovalRequest question = ApprovalRequest.builder().question("q").build();
        ApprovalGate interrupted = new ApprovalGate() {
            @Override
            CompletableFuture<String> promise() {
                return new CompletableFuture<>() {
                    @Override
                    public String get(long timeout, TimeUnit unit) throws InterruptedException {
                        throw new InterruptedException("simulated");
                    }
                };
            }
        };
        CompletableFuture<String> future = interrupted.request(question);

        assertThatThrownBy(() -> interrupted.await(future, 1, TimeUnit.MINUTES))
                .isInstanceOf(InterruptedException.class);

        assertThat(interrupted.currentRequest()).isNull();
        assertThat(interrupted.resolve(question, "Yes")).as("nobody would take it").isFalse();
    }

    /**
     * The wait timed out and finds no answer, and right then an answer arrives: the question was closed before the
     * wait looked, so the answer is refused and its sender told so, never accepted and then lost to the timeout.
     */
    @Test
    void anAnswerRightAfterTheTimedOutWaitLookedForOne_isRefused_notLost() {
        ApprovalRequest question = ApprovalRequest.builder().question("Release?").build();
        AtomicBoolean accepted = new AtomicBoolean();
        AtomicBoolean arrived = new AtomicBoolean();
        AtomicReference<ApprovalGate> racing = new AtomicReference<>();
        racing.set(new ApprovalGate() {
            @Override
            CompletableFuture<String> promise() {
                return new CompletableFuture<>() {
                    @Override
                    public String get(long timeout, TimeUnit unit) throws TimeoutException {
                        throw new TimeoutException("simulated");
                    }

                    @Override
                    public boolean isDone() {
                        boolean done = super.isDone();
                        if (arrived.compareAndSet(false, true)) {
                            accepted.set(racing.get().resolve(question, "Yes"));   // just after the look
                        }
                        return done;
                    }
                };
            }
        });
        CompletableFuture<String> future = racing.get().request(question);

        assertThatThrownBy(() -> racing.get().await(future, 1, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        assertThat(arrived).isTrue();
        assertThat(accepted).as("the sender is told it did not arrive").isFalse();
    }

    /** Only an answer that completes the waiting block's promise is reported as delivered. */
    @Test
    void anAnswerToAQuestionWhosePromiseIsCompleteAlready_isNotReportedAsDelivered() {
        ApprovalRequest question = ApprovalRequest.builder().question("q").build();
        CompletableFuture<String> future = gate.request(question);
        future.complete("taken otherwise");

        assertThat(gate.resolve(question, "Yes")).isFalse();
        assertThat(future.join()).isEqualTo("taken otherwise");
    }

    @Test
    void restore_replacesTheRememberedAnswers() {
        gate.remember("Old?", "Yes always");

        gate.restore(java.util.Map.of("New?", "Yes always"));

        assertThat(gate.remembered("Old?")).isNull();
        assertThat(gate.remembered("New?")).isEqualTo("Yes always");
    }

    @Test
    void anAwaitOfAnOlderQuestion_leavesTheOpenOneOpen() {
        CompletableFuture<String> old = gate.request(ApprovalRequest.builder().question("old").build());
        ApprovalRequest current = ApprovalRequest.builder().question("current").build();
        gate.request(current);

        assertThatThrownBy(() -> gate.await(old, 1, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        assertThat(gate.currentRequest()).isSameAs(current);
    }

    /**
     * The wait times out, and before the gate closes the question an answer arrives: its sender was told it reached the
     * question, so the answer wins over the timeout instead of being lost. The hook steps in exactly between the
     * timeout and the close.
     */
    @Test
    void anAnswerBetweenTheTimeoutAndTheClose_winsOverTheTimeout() throws Exception {
        ApprovalRequest question = ApprovalRequest.builder().question("Release?").build();
        AtomicBoolean accepted = new AtomicBoolean();
        AtomicReference<ApprovalGate> racing = new AtomicReference<>();
        racing.set(new ApprovalGate() {
            @Override
            CompletableFuture<String> promise() {
                return new CompletableFuture<>() {
                    @Override
                    public String get(long timeout, TimeUnit unit)
                            throws InterruptedException, ExecutionException, TimeoutException {
                        try {
                            return super.get(timeout, unit);
                        } catch (TimeoutException e) {
                            accepted.set(racing.get().resolve(question, "Yes"));   // the late answer
                            throw e;
                        }
                    }
                };
            }
        });
        CompletableFuture<String> future = racing.get().request(question);

        String answer = racing.get().await(future, 1, TimeUnit.MILLISECONDS);

        assertThat(accepted).as("the sender was told the answer arrived").isTrue();
        assertThat(answer).isEqualTo("Yes");
        assertThat(racing.get().currentRequest()).isNull();
    }

    /**
     * The same interleaving with what goes with the answer (who gave it): the answer that arrives between the timeout
     * and the close wins, and what goes with it was applied by the time the wait hands the answer to the step. An
     * answer after the close resolves nothing, and nothing of it is applied.
     */
    @Test
    void anAnswerBetweenTheTimeoutAndTheClose_wins_withWhatGoesWithIt_andOneAfterTheCloseDoesNot() throws Exception {
        ApprovalRequest question = ApprovalRequest.builder().question("Release?").build();
        java.util.List<String> applied = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicReference<ApprovalGate> racing = new AtomicReference<>();
        racing.set(new ApprovalGate() {
            @Override
            CompletableFuture<String> promise() {
                return new CompletableFuture<>() {
                    @Override
                    public String get(long timeout, TimeUnit unit)
                            throws InterruptedException, ExecutionException, TimeoutException {
                        try {
                            return super.get(timeout, unit);
                        } catch (TimeoutException e) {
                            racing.get().resolve(question, "Yes", () -> applied.add("anna@box"));   // the late answer
                            throw e;
                        }
                    }
                };
            }
        });
        CompletableFuture<String> future = racing.get().request(question);

        String answer = racing.get().await(future, 1, TimeUnit.MILLISECONDS);

        assertThat(answer).isEqualTo("Yes");
        assertThat(applied).as("the sender of the answer that won").containsExactly("anna@box");

        ApprovalGate gate = new ApprovalGate();
        ApprovalRequest next = ApprovalRequest.builder().question("Again?").build();
        CompletableFuture<String> late = gate.request(next);
        assertThatThrownBy(() -> gate.await(late, 1, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        assertThat(gate.resolve(next, "Yes", () -> applied.add("too late"))).isFalse();
        assertThat(applied).containsExactly("anna@box");
    }
}
