package traceworks.core.testing;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OwnThreadTest {

    /**
     * Two pieces that each wait for the other can only both end when they run at the same time. On one shared
     * thread the first would wait for a second that never starts.
     */
    @Test
    void twoPieces_runAtTheSameTime_eachOnAThreadWithItsName() throws Exception {
        CountDownLatch firstRuns = new CountDownLatch(1);
        CountDownLatch secondRuns = new CountDownLatch(1);

        CompletableFuture<String> first = OwnThread.start("first piece", () -> {
            firstRuns.countDown();
            return awaited(secondRuns) + " on " + Thread.currentThread().getName();
        });
        CompletableFuture<String> second = OwnThread.start("second piece", () -> {
            secondRuns.countDown();
            return awaited(firstRuns) + " on " + Thread.currentThread().getName();
        });

        assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo("met the other on test: first piece");
        assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo("met the other on test: second piece");
    }

    @Test
    void aPieceThatStillRuns_isNamedWithTheStateOfItsThread_andNoLongerOnceItEnded() throws Exception {
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<String> held = OwnThread.start("held piece", () -> awaited(release));

        assertThat(OwnThread.threads()).startsWith("own threads: ").contains("\"test: held piece\" ");
        release.countDown();
        held.get(10, TimeUnit.SECONDS);
        for (int i = 0; i < 500 && OwnThread.threads().contains("held piece"); i++) {
            Thread.sleep(10);
        }
        assertThat(OwnThread.threads()).doesNotContain("held piece");
    }

    @Test
    void whatAPieceThrows_endsItsFuture() {
        CompletableFuture<String> failed = OwnThread.start("failing piece", () -> {
            throw new IllegalStateException("no");
        });

        assertThatThrownBy(() -> failed.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class).hasMessageContaining("no");
    }

    private static String awaited(CountDownLatch latch) {
        try {
            return latch.await(10, TimeUnit.SECONDS) ? "met the other" : "waited in vain";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }
}
