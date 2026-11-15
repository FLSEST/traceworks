package traceworks.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The status names are part of the run record and of the workflow format. */
class StatusEnumsTest {

    @Test
    void runStatuses_inDeclaredOrder() {
        assertThat(RunStatus.values()).containsExactly(
                RunStatus.PENDING,
                RunStatus.RUNNING,
                RunStatus.PAUSED,
                RunStatus.COMPLETED,
                RunStatus.COMPLETED_WITH_ERRORS,
                RunStatus.FAILED,
                RunStatus.CANCELLED,
                RunStatus.INTERRUPTED,
                RunStatus.PARKED);
    }

    @Test
    void stepStatuses_inDeclaredOrder() {
        assertThat(StepStatus.values()).containsExactly(
                StepStatus.PENDING,
                StepStatus.RUNNING,
                StepStatus.COMPLETED,
                StepStatus.FAILED,
                StepStatus.SKIPPED,
                StepStatus.WAITING_APPROVAL,
                StepStatus.CANCELLED);
    }

    @Test
    void gateStatuses_inDeclaredOrder() {
        assertThat(GateStatus.values()).containsExactly(GateStatus.GREEN, GateStatus.YELLOW, GateStatus.RED);
    }
}
