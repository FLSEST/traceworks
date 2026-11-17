package traceworks.core.app;

import com.fasterxml.jackson.databind.JsonNode;
import traceworks.core.app.AnswerDelivery.Attribution;
import traceworks.core.engine.RunControl;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.Clock;
import traceworks.core.port.RunEvent.ApprovalAnswered;
import traceworks.core.record.RecordTypes;
import traceworks.core.record.RecordedEvent;
import traceworks.core.record.RunRecorder;
import traceworks.core.testing.InMemoryBlobStore;
import traceworks.core.testing.InMemoryEventLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who an {@code approval.answered} names: the sender of the answer that resolved the question, and nobody when the
 * step took its answer by itself.
 */
@Timeout(20)
class AnswerDeliveryTest {

    private static final String RUN = "00000000-0000-0000-0000-000000000001";
    private static final Attribution ANNA = new Attribution("anna@box", "inbox", "req-1", null);

    private final InMemoryEventLog log = new InMemoryEventLog();
    private final AtomicLong tick = new AtomicLong();
    private final Clock clock = () -> Instant.parse("2026-11-03T12:00:00Z").plusMillis(tick.incrementAndGet() * 10);
    private final RunRecorder recorder = RunRecorder.start(log, new InMemoryBlobStore(), clock, RUN, definition());
    private final AnswerDelivery delivery = new AnswerDelivery(recorder, new RunControl());

    AnswerDeliveryTest() {
        recorder.onAppend(delivery::appended);
    }

    private static WorkflowDefinition definition() {
        BlockDefinition ask = BlockDefinition.builder().type("approval").name("ask")
                .config(Map.of("question", "Ship?")).build();
        return new WorkflowDefinition("wf", "Wf", null, List.of(ask), null, null, null, null, null, null, null, null,
                null);
    }

    private JsonNode answered() {
        return log.readAll().stream().map(RecordedEvent::parse)
                .filter(e -> RecordTypes.APPROVAL_ANSWERED.equals(e.type())).reduce((a, b) -> b).orElseThrow()
                .fields();
    }

    /**
     * The race, made deterministic: an answer from the inbox is being delivered at the very moment the question's
     * wait times out. The step records the answer it takes by itself (its default), and the run refuses the delivered
     * one. That {@code approval.answered} must not name the sender of the answer that did not resolve it.
     */
    @Test
    void aQuestionThatTimesOutWhileAnAnswerIsDelivered_isNotAttributedToTheSender() {
        assertThatThrownBy(() -> delivery.deliver(ANNA, () -> { }, (control, attribute) -> {
            recorder.emit(new ApprovalAnswered(0, "approval", "No"));
            throw new IllegalStateException("No approval is waiting: it was answered, timed out or cancelled"
                    + " meanwhile");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(answered().path("actor").isNull()).as(answered().toString()).isTrue();
        assertThat(answered().path("source").isNull()).isTrue();
        assertThat(answered().path("request").isNull()).isTrue();
    }

    /** The attribution is the delivery's, handed to the run, which applies it when the answer resolves. */
    @Test
    void anAnswerThatResolvesTheQuestion_isAttributedToItsSender() throws InterruptedException {
        delivery.deliver(ANNA, () -> { }, (control, attribute) -> {
            attribute.run();
            recorder.emit(new ApprovalAnswered(0, "approval", "Yes"));
        });

        assertThat(answered().path("actor").asText()).isEqualTo("anna@box");
        assertThat(answered().path("source").asText()).isEqualTo("inbox");
        assertThat(answered().path("request").asText()).isEqualTo("req-1");
    }

    private static final Attribution BOB = new Attribution("bob@box", "cli", "req-2", null);

    /** Who each {@code approval.answered} names so far, in order; empty text for nobody. */
    private List<String> answerers() {
        return log.readAll().stream().map(RecordedEvent::parse)
                .filter(e -> RecordTypes.APPROVAL_ANSWERED.equals(e.type()))
                .map(e -> e.fields().path("actor").isTextual() ? e.fields().path("actor").asText() : "").toList();
    }

    /** A delivery on a thread of its own; what it throws is kept for the test. */
    private Thread delivering(Attribution who, Runnable check,
                              java.util.function.BiConsumer<RunControl, Runnable> answer, List<Throwable> failures) {
        Thread thread = new Thread(() -> {
            try {
                delivery.deliver(who, check, answer);
            } catch (Throwable e) {
                failures.add(e);
            }
        });
        thread.start();
        return thread;
    }

    /** Waits until {@code thread} blocks or ends, or {@code ran} has something: no duration decides the test. */
    private static void awaitBlockedOrRan(Thread thread, List<?> ran) throws InterruptedException {
        while (thread.getState() != Thread.State.WAITING && thread.getState() != Thread.State.TERMINATED
                && ran.isEmpty()) {
            Thread.sleep(5);
        }
    }

    /** The turn passes on only once the answer given is recorded: no other answer can take its attribution. */
    @Test
    void aDelivery_holdsTheTurnUntilItsAnswerIsRecorded() throws Exception {
        List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.CountDownLatch given = new java.util.concurrent.CountDownLatch(1);
        Thread anna = delivering(ANNA, () -> { }, (control, attribute) -> {
            attribute.run();
            given.countDown();   // with the run, not recorded yet
        }, failures);
        given.await();
        List<List<String>> seenByBob = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread bob = delivering(BOB, () -> seenByBob.add(answerers()), (control, attribute) -> {
            attribute.run();
            recorder.emit(new ApprovalAnswered(1, "approval", "Yes"));
        }, failures);

        awaitBlockedOrRan(bob, seenByBob);
        assertThat(seenByBob).as("the second delivery waits for its turn").isEmpty();
        recorder.emit(new ApprovalAnswered(0, "approval", "Yes"));   // the run records the first answer
        anna.join();
        bob.join();

        assertThat(failures).isEmpty();
        assertThat(seenByBob).containsExactly(List.of("anna@box"));
        assertThat(answerers()).containsExactly("anna@box", "bob@box");
    }

    /** The answer a resume replays holds the turn until its step took it, and carries the resume's attribution. */
    @Test
    void aReplayedAnswer_holdsTheTurnUntilItsStepTookIt() throws Exception {
        delivery.replay(0, "operator@box", null);
        List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<String> checked = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread anna = delivering(ANNA, () -> checked.add("anna"), (control, attribute) -> {
            attribute.run();
            recorder.emit(new ApprovalAnswered(1, "approval", "Yes"));
        }, failures);

        awaitBlockedOrRan(anna, checked);
        assertThat(checked).as("no delivery while the replay holds the turn").isEmpty();
        recorder.emit(new ApprovalAnswered(0, "approval", "Yes"));   // the resumed step takes the replayed answer
        anna.join();

        assertThat(failures).isEmpty();
        List<String> answers = log.readAll().stream().map(RecordedEvent::parse)
                .filter(e -> RecordTypes.APPROVAL_ANSWERED.equals(e.type()))
                .map(e -> e.fields().path("actor").asText() + " " + e.fields().path("source").asText()).toList();
        assertThat(answers)
                .containsExactly("operator@box resume", "anna@box inbox");
    }

    /** A replayed step that ends without its answer leaves the attribution to nobody, and the turn to the next. */
    @Test
    void aReplayedStepThatEndsWithoutItsAnswer_leavesNoAttributionBehind() throws InterruptedException {
        delivery.replay(0, "operator@box", null);

        recorder.emit(new traceworks.core.port.RunEvent.StepFinished(0, "ask", "approval",
                traceworks.core.model.StepStatus.FAILED, "Ship?", 0, Map.of()));
        recorder.emit(new ApprovalAnswered(1, "approval", "No"));   // a later step takes its default by itself

        assertThat(answered().path("actor").isNull()).as(answered().toString()).isTrue();
        delivery.deliver(ANNA, () -> { }, (control, attribute) -> {
            attribute.run();
            recorder.emit(new ApprovalAnswered(2, "approval", "Yes"));
        });
        assertThat(answerers()).containsExactly("", "anna@box");
    }

    /** A run that ends while the resume replays an answer lets the next delivery in, to be refused. */
    @Test
    void aRunThatEndsDuringTheReplay_refusesTheNextDelivery_insteadOfKeepingItWaiting() {
        delivery.replay(0, "operator@box", null);

        recorder.emit(new traceworks.core.port.RunEvent.RunFinished(traceworks.core.model.RunStatus.FAILED, Map.of(),
                "boom"));

        assertThatThrownBy(() -> delivery.deliver(ANNA, () -> { }, (control, attribute) -> attribute.run()))
                .isInstanceOf(IllegalStateException.class).hasMessage("the run has ended");
    }

    /** {@link AnswerDelivery#end} frees a delivery that waits for its record, and refuses every one after it. */
    @Test
    void theEndOfTheRun_freesTheDeliveryThatWaits_andRefusesTheNext() throws Exception {
        List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread anna = delivering(ANNA, () -> { }, (control, attribute) -> attribute.run(), failures);
        awaitBlockedOrRan(anna, List.of());

        delivery.end();
        anna.join();

        assertThat(failures).isEmpty();
        assertThatThrownBy(() -> delivery.deliver(BOB, () -> { }, (control, attribute) -> attribute.run()))
                .isInstanceOf(IllegalStateException.class).hasMessage("the run has ended");
    }

    @Test
    void theEndOfTheRunDuringTheReplay_refusesTheNextDelivery_insteadOfKeepingItWaiting() {
        delivery.replay(0, "operator@box", null);

        delivery.end();

        assertThatThrownBy(() -> delivery.deliver(ANNA, () -> { }, (control, attribute) -> attribute.run()))
                .isInstanceOf(IllegalStateException.class).hasMessage("the run has ended");
    }

    /** The check runs in the delivery's turn and refuses the answer before it is given. */
    @Test
    void aCheckThatThrows_refusesTheAnswer_beforeItIsGiven() throws InterruptedException {
        // An answer given here would wait for a record that never comes: it fails the delivery instead
        assertThatThrownBy(() -> delivery.deliver(ANNA, () -> {
            throw new IllegalStateException("no approval is waiting");
        }, (control, attribute) -> {
            throw new AssertionError("the answer was given although its check refused it");
        })).isInstanceOf(IllegalStateException.class).hasMessage("no approval is waiting");

        delivery.deliver(BOB, () -> { }, (control, attribute) -> {
            attribute.run();
            recorder.emit(new ApprovalAnswered(0, "approval", "Yes"));
        });
        assertThat(answerers()).containsExactly("bob@box");
    }

    /** An interrupt while the delivery waits for its record does not end the wait early, and stays for the caller. */
    @Test
    void anInterruptWhileTheDeliveryWaits_isKeptForTheCaller() throws InterruptedException {
        delivery.deliver(ANNA, () -> { }, (control, attribute) -> {
            attribute.run();
            recorder.emit(new ApprovalAnswered(0, "approval", "Yes"));
            Thread.currentThread().interrupt();
        });

        assertThat(Thread.interrupted()).isTrue();
        assertThat(answerers()).containsExactly("anna@box");
    }
}
