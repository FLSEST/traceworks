package traceworks.core.engine;

import traceworks.core.context.ExecutionContext;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.BlockSettings;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.port.Clock;
import traceworks.core.port.CommandRunner;
import traceworks.core.port.CommandRunner.CommandRequest;
import traceworks.core.port.CommandRunner.CommandResult;

import java.time.Instant;
import java.util.Optional;

/**
 * {@code run_if}: a shell test before a top-level block. Exit 0 runs the block; any other exit skips it with
 * {@link StepStatus#SKIPPED} and a fixed text, without calling its executor: no AI call, no approval, no write. A test
 * whose output went beyond the runner's output limit, or that ran out of time (by the runner's mark, not by the exit
 * code 124, which a test may return by itself), fails the block the same way, with {@link StepStatus#FAILED} and the
 * note why: the runner stopped it, so its exit code is not the test's answer. So does a test that did not run, by the
 * runner's mark ({@link CommandResult#notRunReason()}: a refusal, a start failure), not by an exit code such as 125.
 * Placeholders resolve as in every shell field: untrusted values always quoted, with {@code shell_escape_variables}
 * all of them. The test runs in the run's working directory through the {@link CommandRunner}, without an image (in
 * the run's {@code shell} image in a container run, on the host in a host run), for at most
 * {@value #TIMEOUT_SECONDS} s. Nested blocks do not know the key. A {@code run_if} that is no text, or a
 * {@code shell_escape_variables} that is no boolean, fails the block ({@link BlockSettings}). A test the run's cancel
 * ended is no answer either: the block is neither run nor skipped, its step is {@link StepStatus#CANCELLED}
 * ({@link CancelledSteps}).
 */
final class RunIfGate {

    static final String KEY = "run_if";
    static final int TIMEOUT_SECONDS = 60;

    private final CommandRunner commands;
    private final Clock clock;

    RunIfGate(CommandRunner commands, Clock clock) {
        this.commands = commands;
        this.clock = clock;
    }

    /** The result of a skipped block, or of one whose test went beyond the output limit; empty if it should run. */
    Optional<StepResult> skipIfUnmet(BlockDefinition block, ExecutionContext context) {
        BlockSettings settings = BlockSettings.of(block);
        String condition = settings.text(KEY);
        if (condition == null || condition.isBlank()) {
            return Optional.empty();
        }
        RegisterFolderUse.inCommand(condition, context);
        String resolved = context.resolveShellTemplate(condition, settings.flag("shell_escape_variables"));
        Instant start = clock.now();
        CommandResult test = commands.run(new CommandRequest(resolved, context.getProjectPath(), TIMEOUT_SECONDS,
                null, null, null, false).withValues(ShellValues.resolve(block.getConfig(), context)));
        int exit = test.exitCode();
        boolean cancelled = context.shouldStop();
        if (exit == 0 && !test.truncated() && !test.timedOut() && !test.didNotRun() && !cancelled) {
            return Optional.empty();
        }
        StepResult instead = new StepResult();
        instead.setStepIndex(context.nextStepIndex());
        instead.setStartedAt(start);
        instead.setInput("run_if: " + resolved);
        if (cancelled) {
            instead.setOutput(CancelledSteps.WHILE_RUNNING);
            instead.setStatus(StepStatus.CANCELLED);
        } else if (test.timedOut()) {
            instead.setOutput("run_if failed: it timed out after " + TIMEOUT_SECONDS + " seconds");
            instead.setStatus(StepStatus.FAILED);
        } else if (test.truncated()) {
            instead.setOutput("run_if failed: " + test.truncationNote());
            instead.setStatus(StepStatus.FAILED);
        } else if (test.didNotRun()) {
            instead.setOutput("run_if failed: it did not run: " + test.notRunReason());
            instead.setStatus(StepStatus.FAILED);
        } else {
            instead.setOutput("Skipped (run_if: exit " + exit + ")");
            instead.setStatus(StepStatus.SKIPPED);
        }
        instead.setCompletedAt(clock.now());
        instead.setDurationMs(instead.getCompletedAt().toEpochMilli() - start.toEpochMilli());
        return Optional.of(instead);
    }
}
