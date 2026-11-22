package traceworks.cli.mcp;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class CallLimitsTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000_000L);
    private final CallLimits limits = new CallLimits(nanos::get);

    private void seconds(long seconds) {
        nanos.addAndGet(seconds * 1_000_000_000L);
    }

    @Test
    void calls_areTakenUpToTheLimitOfAMinute_andTheNextIsRefusedWithTheWait() {
        for (int i = 0; i < CallLimits.CALLS_PER_MINUTE; i++) {
            assertThat(limits.refusal(false)).as("call " + i).isNull();
        }
        seconds(20);

        String refusal = limits.refusal(false);

        assertThat(refusal).contains("at most " + CallLimits.CALLS_PER_MINUTE + " calls a minute", "40 s");
    }

    @Test
    void aRefusedCall_doesNotCount_soTheLimitOpensAMinuteAfterTheFirstCallItTook() {
        for (int i = 0; i < CallLimits.CALLS_PER_MINUTE; i++) {
            limits.refusal(false);
        }
        seconds(59);
        assertThat(limits.refusal(false)).isNotNull();
        seconds(2);

        assertThat(limits.refusal(false)).isNull();
    }

    @Test
    void changes_haveALimitOfTheirOwn_belowThatOfAllCalls_andReadsGoOn() {
        for (int i = 0; i < CallLimits.CHANGES_PER_MINUTE; i++) {
            assertThat(limits.refusal(true)).as("change " + i).isNull();
        }

        assertThat(limits.refusal(true)).contains("at most " + CallLimits.CHANGES_PER_MINUTE + " calls that change"
                + " something a minute");
        assertThat(limits.refusal(false)).isNull();
        assertThat(CallLimits.CHANGES_PER_MINUTE).isLessThan(CallLimits.CALLS_PER_MINUTE);
    }
}
