package traceworks.core.engine;

import traceworks.core.context.ApprovalRequest;
import traceworks.core.context.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunControlTest {

    private final RunControl control = new RunControl();

    private ExecutionContext contextOf(RunControl shared) {
        return shared.bind(ExecutionContext.builder()).build();
    }

    private static CompletableFuture<String> openApproval(ExecutionContext context) {
        return context.requestApproval(ApprovalRequest.builder().blockId("approval").question("Go?").build());
    }

    @Test
    void afterAChildRunEnded_aCancelReachesTheParentAgain() {
        ExecutionContext parent = contextOf(control);
        ExecutionContext child = contextOf(control);
        assertThat(control.attach(parent)).isNull();
        ExecutionContext beforeChild = control.attach(child);
        control.restore(beforeChild);   // the child run ended
        CompletableFuture<String> parentApproval = openApproval(parent);

        control.cancel();

        assertThat(beforeChild).isSameAs(parent);
        assertThat(parent.shouldStop()).isTrue();
        assertThat(parentApproval).as("the parent's open approval is cancelled").isCompletedExceptionally();
    }

    @Test
    void whileAChildRuns_aCancelReachesTheChild_andTheParentOnceItIsBack() {
        ExecutionContext parent = contextOf(control);
        ExecutionContext child = contextOf(control);
        control.attach(parent);
        ExecutionContext beforeChild = control.attach(child);
        CompletableFuture<String> childApproval = openApproval(child);
        CompletableFuture<String> parentApproval = openApproval(parent);

        control.cancel();

        assertThat(childApproval).isCompletedExceptionally();
        assertThat(parentApproval).as("not yet: the parent waits for its child").isNotDone();
        control.restore(beforeChild);
        assertThat(parentApproval).as("applied when the control is handed back").isCompletedExceptionally();
    }

    @Test
    void aCancelBeforeTheRunStarted_isAppliedWhenItAttaches() {
        ExecutionContext context = contextOf(control);
        control.cancel();

        control.attach(context);

        assertThat(context.shouldStop()).isTrue();
        assertThat(control.isCancelRequested()).isTrue();
    }

    @Test
    void pauseAndResume_reachTheContext() {
        ExecutionContext context = contextOf(control);

        control.pause();
        assertThat(context.isPaused()).isTrue();
        assertThat(control.isPaused()).isTrue();
        control.resume();
        assertThat(context.isPaused()).isFalse();
    }

    // -- answering a waiting run from outside, in-process -----------------------------------------------

    private static CompletableFuture<String> openApproval(ExecutionContext context, List<String> options) {
        return context.requestApproval(ApprovalRequest.builder().blockId("approval-0").question("Release?")
                .options(options).build());
    }

    private static CompletableFuture<String> openInput(ExecutionContext context) {
        return context.requestApproval(ApprovalRequest.builder().blockId("human-input-0").type("human-input")
                .question("Which branch?").variableName("branch").build());
    }

    @Test
    void answer_resolvesTheOpenApproval_withTheOptionAsOffered() throws Exception {
        ExecutionContext context = contextOf(control);
        control.attach(context);
        CompletableFuture<String> pending = openApproval(context, List.of("Yes", "No"));

        control.answer(" no ");

        assertThat(pending.get()).isEqualTo("No");
        assertThat(context.getCurrentApprovalRequest()).isNull();
    }

    @Test
    void answer_reachesAChildRunWhileItRuns() throws Exception {
        ExecutionContext parent = contextOf(control);
        ExecutionContext child = contextOf(control);
        control.attach(parent);
        control.attach(child);
        CompletableFuture<String> childApproval = openApproval(child, List.of("Yes", "No"));

        control.answer("Yes");

        assertThat(childApproval.get()).isEqualTo("Yes");
    }

    @Test
    void answer_withNothingWaiting_isRefused() {
        assertThatThrownBy(() -> control.answer("Yes")).as("no run attached")
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No approval is waiting: the run waits for no answer");
        control.attach(contextOf(control));
        assertThatThrownBy(() -> control.answer("Yes")).as("a run that waits for nothing")
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No approval is waiting: the run waits for no answer");
    }

    @Test
    void answer_notOffered_isRefused_andTheApprovalStaysOpen() throws Exception {
        ExecutionContext context = contextOf(control);
        control.attach(context);
        CompletableFuture<String> pending = openApproval(context, List.of("Yes", "No"));

        assertThatThrownBy(() -> control.answer("Maybe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Answer 'Maybe' is not one of the offered options [Yes, No]");
        assertThat(pending).isNotDone();
        control.answer("Yes");
        assertThat(pending.get()).isEqualTo("Yes");
    }

    @Test
    void answer_toAHumanInput_isRefused() {
        ExecutionContext context = contextOf(control);
        control.attach(context);
        CompletableFuture<String> pending = openInput(context);

        assertThatThrownBy(() -> control.answer("main"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No approval is waiting: the run waits for a human input; answer it with answerInput");
        assertThat(pending).isNotDone();
    }

    @Test
    void answer_afterTheApprovalTimedOut_isRefused() {
        ExecutionContext context = contextOf(control);
        control.attach(context);
        CompletableFuture<String> pending = openApproval(context, List.of("Yes", "No"));
        assertThatThrownBy(() -> context.awaitApproval(pending, 1, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        assertThatThrownBy(() -> control.answer("Yes"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No approval is waiting: the run waits for no answer");
    }

    @Test
    void answerInput_resolvesTheOpenHumanInput() throws Exception {
        ExecutionContext context = contextOf(control);
        control.attach(context);
        CompletableFuture<String> pending = openInput(context);

        control.answerInput(Map.of("branch", "main"));

        assertThat(pending.get()).isEqualTo("main");
    }

    /** The terminal and the inbox answer through the control: a value its field's rules refuse leaves it open. */
    @Test
    void answerInput_aValueTheFieldsRulesRefuse_isRefused_andTheInputStaysOpen() throws Exception {
        ExecutionContext context = contextOf(control);
        control.attach(context);
        CompletableFuture<String> pending = context.requestApproval(ApprovalRequest.builder()
                .blockId("human-input-0").type("human-input").question("How many?").variableName("n")
                .inputType("text").required(true).validationPattern("[0-9]+").build());

        assertThatThrownBy(() -> control.answerInput(Map.of("n", "abc; rm -rf x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'n' (validation_pattern)");
        assertThat(pending).isNotDone();
        control.answerInput(Map.of("n", "42"));
        assertThat(pending.get()).isEqualTo("42");
    }

    @Test
    void answerInput_toAnApproval_isRefused() {
        ExecutionContext context = contextOf(control);
        control.attach(context);
        CompletableFuture<String> pending = openApproval(context, List.of("Yes", "No"));

        assertThatThrownBy(() -> control.answerInput(Map.of("approval_answer", "Yes")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No human input is waiting: the run waits for an approval; answer it with answer");
        assertThat(pending).isNotDone();
    }

    @Test
    void answerInput_withNothingWaiting_isRefused() {
        assertThatThrownBy(() -> control.answerInput(Map.of("branch", "main")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No human input is waiting: the run waits for no answer");
    }

    // -- answering one named question ---------------------------------------------------------------------

    private static final RunControl.Question FIRST = new RunControl.Question("run-1", 0, 5);
    private static final RunControl.Question SECOND = new RunControl.Question("run-1", 1, 9);

    /**
     * An answer names the question it was given to, as the record names it. When that question's wait ended and the
     * next question opened meanwhile, the answer does not reach the next one, and nobody is named as its sender.
     */
    @Test
    void aNamedAnswer_neverReachesTheQuestionThatOpenedAfterItsOwn() throws Exception {
        ExecutionContext context = contextOf(control);
        control.attach(context);
        CompletableFuture<String> first = openApproval(context, List.of("Yes", "No"));
        control.nameWaiting(FIRST);
        assertThatThrownBy(() -> context.awaitApproval(first, 1, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
        CompletableFuture<String> second = openApproval(context, List.of("Yes", "No"));
        control.nameWaiting(SECOND);
        List<String> resolved = new java.util.ArrayList<>();

        assertThatThrownBy(() -> control.answer(FIRST, "Yes", () -> resolved.add("first")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No approval is waiting: the question it answers is no longer open");
        assertThat(second).as("the next question stays open").isNotDone();
        assertThat(resolved).isEmpty();

        control.answer(SECOND, "no", () -> resolved.add("second"));
        assertThat(second.get()).isEqualTo("No");
        assertThat(resolved).containsExactly("second");
    }

    /**
     * What goes with an answer (who gave it) is applied when the answer resolves the question, before the waiting
     * step can see the answer, and not at all for an answer that came after the question timed out: the answer the
     * step then takes by itself is nobody's.
     */
    @Test
    void whatGoesWithANamedAnswer_isAppliedWhenItResolves_andNotWhenTheQuestionTimedOut() throws Exception {
        ExecutionContext context = contextOf(control);
        control.attach(context);
        CompletableFuture<String> pending = openApproval(context, List.of("Yes", "No"));
        control.nameWaiting(FIRST);
        List<Boolean> doneWhenApplied = new java.util.ArrayList<>();

        control.answer(FIRST, "Yes", () -> doneWhenApplied.add(pending.isDone()));

        assertThat(doneWhenApplied).as("applied before the step can take the answer").containsExactly(false);
        assertThat(pending.get()).isEqualTo("Yes");

        CompletableFuture<String> late = openApproval(context, List.of("Yes", "No"));
        control.nameWaiting(SECOND);
        assertThatThrownBy(() -> context.awaitApproval(late, 1, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
        assertThatThrownBy(() -> control.answer(SECOND, "Yes", () -> doneWhenApplied.add(true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No approval is waiting: it was answered, timed out or cancelled meanwhile");
        assertThat(doneWhenApplied).as("nothing is applied for an answer that did not resolve").hasSize(1);
    }

    @Test
    void aNamedInput_resolvesTheHumanInputItNames() throws Exception {
        ExecutionContext context = contextOf(control);
        control.attach(context);
        CompletableFuture<String> pending = openInput(context);
        control.nameWaiting(FIRST);
        List<String> resolved = new java.util.ArrayList<>();

        assertThatThrownBy(() -> control.answerInput(SECOND, Map.of("branch", "main"), () -> resolved.add("x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No human input is waiting: the question it answers is no longer open");
        control.answerInput(FIRST, Map.of("branch", "main"), () -> resolved.add("first"));

        assertThat(pending.get()).isEqualTo("main");
        assertThat(resolved).containsExactly("first");
    }

    /** A name given while nothing waits (the question was answered from the record, or parked) names nothing. */
    @Test
    void aNameGivenWhileNothingWaits_namesNoLaterQuestion() {
        ExecutionContext context = contextOf(control);
        control.attach(context);
        control.nameWaiting(FIRST);
        CompletableFuture<String> pending = openApproval(context, List.of("Yes", "No"));

        assertThatThrownBy(() -> control.answer(FIRST, "Yes", () -> { }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(pending).isNotDone();
    }
}
