package traceworks.core.app;

import traceworks.core.app.AppFixture.Background;
import traceworks.core.app.AppFixture.Events;
import traceworks.core.model.RunStatus;
import traceworks.core.record.RecordedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static traceworks.core.app.AppFixture.PARENT;
import static traceworks.core.app.AppFixture.request;
import static org.assertj.core.api.Assertions.assertThat;

/** Two people answer the same question of a running run at once: exactly one answer counts. */
class RunServicesConcurrencyTest {

    private final AppFixture fx = new AppFixture();
    private final RunServices services = fx.services();
    private final List<Background<?>> background = new ArrayList<>();

    @AfterEach
    void noThreadOutlivesAUseCase() throws InterruptedException {
        try {
            services.cancelActiveRuns();
        } finally {
            for (Background<?> work : background) {
                work.awaitEnd();
            }
        }
        assertThat(AppFixture.liveServiceThreads()).isEmpty();
    }

    @RepeatedTest(5)
    void twoApprovesOnARunningRun_oneCounts() throws Exception {
        Events events = new Events();
        Background<RunResult> running = track(new Background<>(() -> services.start(request(PARENT), events, null)));
        String id = events.await("approval.requested").parent();
        CountDownLatch go = new CountDownLatch(1);

        Background<AnswerResult> yes = track(new Background<>(() -> answerAfter(go, id, "Yes")));
        Background<AnswerResult> no = track(new Background<>(() -> answerAfter(go, id, "No")));
        go.countDown();
        AnswerResult yesResult = yes.join();
        AnswerResult noResult = no.join();

        assertThat(running.join().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(List.of(yesResult.accepted(), noResult.accepted())).containsExactlyInAnyOrder(true, false);
        List<RecordedEvent> answered = fx.ofType(id, "approval.answered");
        List<RecordedEvent> refused = fx.ofType(id, "request.refused");
        assertThat(answered).hasSize(1);
        // The loser is refused through the inbox (request.refused) or before it is posted (a refused result only)
        assertThat(refused.size()).isLessThanOrEqualTo(1);
        assertThat(List.of(yesResult, noResult)).filteredOn(r -> !r.accepted()).hasSize(1)
                .allSatisfy(r -> assertThat(r.message()).isNotBlank());
        String winner = yesResult.accepted() ? "Yes" : "No";
        assertThat(fx.text(id, answered.getFirst(), "answer")).isEqualTo(winner);
        if (!refused.isEmpty()) {
            assertThat(answered.getFirst().fields().path("request").asText())
                    .isNotEqualTo(refused.getFirst().fields().path("request").asText());
            assertThat(refused.getFirst().fields().path("reason").asText()).isNotBlank();
        }
    }

    private AnswerResult answerAfter(CountDownLatch go, String id, String option) {
        try {
            go.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return services.answer(id, new AnswerRequest("approval", null, option, null, null));
    }

    private <T> Background<T> track(Background<T> work) {
        background.add(work);
        return work;
    }
}
