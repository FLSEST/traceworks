package traceworks.core.engine;

import traceworks.core.context.ExecutionContext;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.port.Clock;
import traceworks.core.port.CommandRunner;
import traceworks.core.port.CommandRunner.CommandRequest;
import traceworks.core.port.CommandRunner.CommandResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code run_if}: a shell test before a top-level block. */
@ExtendWith(MockitoExtension.class)
class RunIfGateTest {

    private static final Clock CLOCK = () -> Instant.parse("2026-11-02T08:15:30Z");

    @Mock private CommandRunner commandRunner;

    private ExecutionContext context() {
        ExecutionContext ctx = ExecutionContext.builder().workflowRunId("run-1").variables(new HashMap<>()).build();
        ctx.setProjectPath("/work");
        return ctx;
    }

    private static BlockDefinition block(Map<String, Object> config) {
        return BlockDefinition.builder().type("simple").config(config).build();
    }

    /** The test command as the gate hands it to the runner: in the working directory, at most 60 s. */
    private static CommandRequest request(String command) {
        return new CommandRequest(command, "/work", 60, null, null, null, false);
    }

    private static CommandResult exit(int code) {
        return new CommandResult(code, "", "", 3, false);
    }

    @Test
    void withoutRunIf_nothingIsExecuted() {
        assertThat(new RunIfGate(commandRunner, CLOCK).skipIfUnmet(block(new HashMap<>()), context())).isEmpty();
        verify(commandRunner, never()).run(any());
    }

    @Test
    void exitZero_runsTheBlock() {
        when(commandRunner.run(request("test -s ids.txt"))).thenReturn(exit(0));

        assertThat(new RunIfGate(commandRunner, CLOCK)
                .skipIfUnmet(block(Map.of("run_if", "test -s ids.txt")), context())).isEmpty();
    }

    /** A test the run's cancel killed is no answer: the block is neither run nor skipped, but cancelled. */
    @ParameterizedTest
    @ValueSource(ints = {0, 130, 143})
    void aTestTheCancelEnded_cancelsTheStep_insteadOfSkippingOrRunningIt(int code) {
        ExecutionContext context = context();
        when(commandRunner.run(request("./ready"))).thenAnswer(inv -> {
            context.cancel();
            return exit(code);
        });

        Optional<StepResult> instead = new RunIfGate(commandRunner, CLOCK)
                .skipIfUnmet(block(Map.of("run_if", "./ready")), context);

        assertThat(instead).hasValueSatisfying(step -> {
            assertThat(step.getStatus()).isEqualTo(StepStatus.CANCELLED);
            assertThat(step.getOutput()).isEqualTo(CancelledSteps.WHILE_RUNNING);
        });
    }

    /**
     * A test whose output went beyond the limit fails the block: its exit code is not the command's own, so neither
     * running the block nor skipping it would be what the workflow says.
     */
    @Test
    void outputBeyondTheLimit_failsTheBlock_withTheNote() {
        when(commandRunner.run(request("yes; true"))).thenReturn(new CommandResult(153, "y\n", "", 3, false,
                List.of("stdout")));

        Optional<StepResult> failed = new RunIfGate(commandRunner, CLOCK)
                .skipIfUnmet(block(Map.of("run_if", "yes; true")), context());

        assertThat(failed).isPresent();
        assertThat(failed.get().getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(failed.get().getOutput()).isEqualTo("run_if failed: The command's standard output exceeded 16 MiB;"
                + " Traceworks stopped it and kept the first 16 MiB");
        assertThat(failed.get().getInput()).isEqualTo("run_if: yes; true");
    }

    /** A test that ran out of time fails the block: the runner's mark decides, and no skip is its answer. */
    @Test
    void timeout_failsTheBlock_withTheSeconds() {
        when(commandRunner.run(request("sleep 999"))).thenReturn(new CommandResult(124, "", "", 60000, true));

        Optional<StepResult> failed = new RunIfGate(commandRunner, CLOCK)
                .skipIfUnmet(block(Map.of("run_if", "sleep 999")), context());

        assertThat(failed).isPresent();
        assertThat(failed.get().getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(failed.get().getOutput()).isEqualTo("run_if failed: it timed out after 60 seconds");
        assertThat(failed.get().getInput()).isEqualTo("run_if: sleep 999");
    }

    /** A test the runner refused never ran: the block fails with the reason, and no skip is its answer. */
    @Test
    void notRun_failsTheBlock_withTheReason() {
        when(commandRunner.run(request("test -f failed")))
                .thenReturn(CommandResult.notRun(125, "image traceworks/shell:0123456789ab is not present; run traceworks images build", CommandRunner.OUTPUT_LIMIT_BYTES));

        Optional<StepResult> failed = new RunIfGate(commandRunner, CLOCK)
                .skipIfUnmet(block(Map.of("run_if", "test -f failed")), context());

        assertThat(failed).isPresent();
        assertThat(failed.get().getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(failed.get().getOutput()).isEqualTo("run_if failed: it did not run: image traceworks/shell:0123456789ab is not present; run traceworks images build");
        assertThat(failed.get().getInput()).isEqualTo("run_if: test -f failed");
    }

    /** A test may exit 125, 1 or 74 by itself; without the runner's mark that is an ordinary false test. */
    @ParameterizedTest
    @ValueSource(ints = {125, 1, 74})
    void exitLikeARefusalByItself_isAFalseTest(int exitCode) {
        when(commandRunner.run(request("odd"))).thenReturn(exit(exitCode));

        Optional<StepResult> skipped = new RunIfGate(commandRunner, CLOCK)
                .skipIfUnmet(block(Map.of("run_if", "odd")), context());

        assertThat(skipped).isPresent();
        assertThat(skipped.get().getStatus()).isEqualTo(StepStatus.SKIPPED);
        assertThat(skipped.get().getOutput()).isEqualTo("Skipped (run_if: exit " + exitCode + ")");
    }

    /** A test may exit 124 by itself; without the runner's mark that is an ordinary false test. */
    @Test
    void exit124ByItself_isAFalseTest() {
        when(commandRunner.run(request("odd"))).thenReturn(exit(124));

        Optional<StepResult> skipped = new RunIfGate(commandRunner, CLOCK)
                .skipIfUnmet(block(Map.of("run_if", "odd")), context());

        assertThat(skipped).isPresent();
        assertThat(skipped.get().getStatus()).isEqualTo(StepStatus.SKIPPED);
        assertThat(skipped.get().getOutput()).isEqualTo("Skipped (run_if: exit 124)");
    }

    @Test
    void nonZero_skipsWithFixedText() {
        when(commandRunner.run(request("false"))).thenReturn(exit(1));

        Optional<StepResult> skipped = new RunIfGate(commandRunner, CLOCK)
                .skipIfUnmet(block(Map.of("run_if", "false")), context());

        assertThat(skipped).isPresent();
        assertThat(skipped.get().getStatus()).isEqualTo(StepStatus.SKIPPED);
        assertThat(skipped.get().getOutput()).isEqualTo("Skipped (run_if: exit 1)");
        assertThat(skipped.get().getInput()).isEqualTo("run_if: false");
        assertThat(skipped.get().getStartedAt()).isEqualTo(CLOCK.now());
        assertThat(skipped.get().getDurationMs()).isZero();
    }

    @Test
    void untrustedValue_isQuoted() {
        ExecutionContext ctx = context();
        ctx.setUntrustedVariable("x", "a; touch /tmp/pwned");
        when(commandRunner.run(request("test 'a; touch /tmp/pwned' = y"))).thenReturn(exit(1));

        assertThat(new RunIfGate(commandRunner, CLOCK)
                .skipIfUnmet(block(Map.of("run_if", "test {{ x }} = y")), ctx)).isPresent();
    }
}
