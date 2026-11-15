package traceworks.core.blocks;

import traceworks.core.context.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgenticSafetyGuardTest {

    @Test
    void checkTotalTimeout_withinLimit_returnsNull() {
        AgenticSafetyGuard guard = new AgenticSafetyGuard(3600, 300, 3);
        assertThat(guard.checkTotalTimeout()).isNull();
    }

    /** The guard reads the run's clock, so the test moves the time instead of sleeping two seconds. */
    @Test
    void checkTotalTimeout_exceeded_returnsStopReason() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-03T10:00:00Z"));
        AgenticSafetyGuard guard = new AgenticSafetyGuard(now::get, 1, 300, 3);
        now.set(now.get().plusMillis(2100));
        assertThat(guard.checkTotalTimeout()).isEqualTo("TOTAL_TIMEOUT");
        assertThat(guard.elapsedSeconds()).isEqualTo(2);
    }

    @Test
    void checkIterationTimeout_withinLimit_returnsNull() {
        AgenticSafetyGuard guard = new AgenticSafetyGuard(3600, 300, 3);
        Instant iterStart = Instant.now();
        assertThat(guard.checkIterationTimeout(iterStart)).isNull();
    }

    @Test
    void checkIterationTimeout_exceeded_returnsStopReason() {
        AgenticSafetyGuard guard = new AgenticSafetyGuard(3600, 1, 3);
        // Iteration started 2 seconds ago
        Instant iterStart = Instant.now().minusSeconds(2);
        assertThat(guard.checkIterationTimeout(iterStart)).isEqualTo("ITERATION_TIMEOUT");
    }

    @Test
    void trackAndCheckRepeatedAction_differentActions_returnsNull() {
        AgenticSafetyGuard guard = new AgenticSafetyGuard(3600, 300, 3);

        assertThat(guard.trackAndCheckRepeatedAction("Action A")).isNull();
        assertThat(guard.trackAndCheckRepeatedAction("Action B")).isNull();
        assertThat(guard.trackAndCheckRepeatedAction("Action C")).isNull();
    }

    @Test
    void trackAndCheckRepeatedAction_sameActionRepeated_returnsStopReason() {
        AgenticSafetyGuard guard = new AgenticSafetyGuard(3600, 300, 3);

        assertThat(guard.trackAndCheckRepeatedAction("Same action")).isNull();
        assertThat(guard.trackAndCheckRepeatedAction("Same action")).isNull();
        assertThat(guard.trackAndCheckRepeatedAction("Same action")).isEqualTo("REPEATED_ACTIONS");
    }

    @Test
    void trackAndCheckRepeatedAction_interruptedRepetition_returnsNull() {
        AgenticSafetyGuard guard = new AgenticSafetyGuard(3600, 300, 3);

        assertThat(guard.trackAndCheckRepeatedAction("Action A")).isNull();
        assertThat(guard.trackAndCheckRepeatedAction("Action A")).isNull();
        assertThat(guard.trackAndCheckRepeatedAction("Action B")).isNull(); // breaks the streak
        assertThat(guard.trackAndCheckRepeatedAction("Action A")).isNull(); // starts new streak
    }

    @Test
    void waitIfPaused_notPaused_returnsTrue() {
        ExecutionContext context = ExecutionContext.builder()
                .containerId("test")
                .workflowRunId("run-1")
                .build();

        AgenticSafetyGuard guard = new AgenticSafetyGuard(3600, 300, 3);
        assertThat(guard.waitIfPaused(context)).isTrue();
    }

    @Test
    void waitIfPaused_cancelled_returnsFalse() {
        ExecutionContext context = ExecutionContext.builder()
                .containerId("test")
                .workflowRunId("run-1")
                .build();
        context.cancel();

        AgenticSafetyGuard guard = new AgenticSafetyGuard(3600, 300, 3);
        assertThat(guard.waitIfPaused(context)).isFalse();
    }

    @Test
    void elapsedSeconds_returnsNonNegative() {
        AgenticSafetyGuard guard = new AgenticSafetyGuard(3600, 300, 3);
        assertThat(guard.elapsedSeconds()).isGreaterThanOrEqualTo(0);
    }
}
