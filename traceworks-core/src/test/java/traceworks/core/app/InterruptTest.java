package traceworks.core.app;

import traceworks.core.app.AppFixture.Background;
import traceworks.core.engine.EventLogException;
import traceworks.core.model.RunStatus;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.record.RunView;
import traceworks.core.testing.FakeCommandRunner;
import traceworks.core.util.ProcessTracking;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static traceworks.core.app.AppFixture.BUILD;
import static traceworks.core.app.AppFixture.request;
import static traceworks.core.app.AppFixture.resumeOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The process is told to end (SIGTERM, a closed terminal, a shutdown): it cannot wait for the step in flight. The
 * step's command is ended, nothing more is recorded, and the run is left {@code INTERRUPTED}, not {@code CANCELLED},
 * so that {@code resume} goes on with it.
 */
class InterruptTest {

    /** A loop over four items. */
    private static final Path LOOP = Path.of("/pkg/loop");

    private final AppFixture fx = new AppFixture();
    private final RunServices services = fx.services();
    private final List<Background<?>> background = new ArrayList<>();
    private final CountDownLatch started = new CountDownLatch(1);

    InterruptTest() {
        fx.packages.put(LOOP, """
                {"id": "wf-loop", "name": "Loop", "blocks": [
                  {"type": "foreach", "name": "each", "config": {"execution_mode": "shell",
                   "items": ["a", "b", "c", "d"], "command": "./work {{ item }}"}}
                ]}""");
        fx.commands.on("./work ", req -> FakeCommandRunner.ok("did " + req.command().substring("./work ".length())));
    }

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

    private <T> Background<T> inBackground(Supplier<T> work) {
        Background<T> begun = new Background<>(work);
        background.add(begun);
        return begun;
    }

    /** The first command that starts with {@code prefix} runs until its process is killed; later ones just end. */
    private void runsUntilKilled(String prefix, String output) {
        fx.commands.on(prefix, req -> {
            if (started.getCount() == 0) {
                return FakeCommandRunner.ok(output);
            }
            started.countDown();
            long deadline = System.nanoTime() + AppFixture.WAIT.toNanos();
            while (!ProcessTracking.current().cancelled() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            return new CommandResult(143, "half done", "", 1, false);
        });
    }

    private long ran(String command) {
        return fx.commands.commands().stream().filter(command::equals).count();
    }

    @Test
    void interrupt_endsTheStepInFlight_recordsNothingMore_andLeavesTheRunInterrupted() throws Exception {
        runsUntilKilled("./build", "built");
        Background<RunResult> run = inBackground(() -> services.start(request(BUILD), null, null));
        assertThat(started.await(AppFixture.WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        String id = fx.store.runIds().getFirst();
        int recorded = fx.events(id).size();

        services.interruptActiveRuns();

        assertThat(run.failure()).as("the run's end is not in the record").isInstanceOf(EventLogException.class);
        assertThat(fx.events(id)).as("nothing is recorded after the interrupt").hasSize(recorded);
        assertThat(fx.types(id)).doesNotContain("run.finished", "run.cancel_requested", "run.parked")
                .endsWith("step.command");
        RunView view = services.view(id);
        assertThat(view.status()).isEqualTo(RunStatus.INTERRUPTED);
        assertThat(view.steps().getFirst().interrupted()).isTrue();
        assertThat(fx.run(id).readManifest()).isEmpty();

        RunResult resumed = services.resume(resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.commands.commands()).containsExactly("./build", "./build", "./test");
        assertThat(services.verify(id).ok()).isTrue();
    }

    /** With a resumed loop, an interrupt costs only the item in flight. */
    @Test
    void interrupt_inALoop_losesOnlyTheItemInFlight() throws Exception {
        runsUntilKilled("./work c", "did c");
        Background<RunResult> run = inBackground(() -> services.start(request(LOOP), null, null));
        assertThat(started.await(AppFixture.WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        String id = fx.store.runIds().getFirst();

        services.interruptActiveRuns();
        run.failure();
        assertThat(services.view(id).status()).isEqualTo(RunStatus.INTERRUPTED);

        RunResult resumed = services.resume(resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(ran("./work a")).isEqualTo(1);
        assertThat(ran("./work b")).isEqualTo(1);
        assertThat(ran("./work c")).as("in flight when the process was told to end").isEqualTo(2);
        assertThat(ran("./work d")).isEqualTo(1);
        assertThat(fx.text(id, fx.ofType(id, "step.output").getFirst(), "output"))
                .isEqualTo("[a]: did a\n[b]: did b\n[c]: did c\n[d]: did d");
    }

    /** A container run: what is left of the run's containers is removed, as for a cancel. */
    @Test
    void interrupt_removesTheContainersOfTheRun() throws Exception {
        fx.settings = OperatorSettings.defaults();
        fx.withRuntime();
        RunServices inContainers = fx.services();
        fx.containers.on(AppFixture.SHELL_ID, run -> {
            started.countDown();
            long deadline = System.nanoTime() + AppFixture.WAIT.toNanos();
            while (!ProcessTracking.current().cancelled() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            return new CommandResult(143, "", "", 1, false);
        });
        Background<RunResult> run = inBackground(() -> inContainers.start(request(BUILD), null, null));
        assertThat(started.await(AppFixture.WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        String id = fx.store.runIds().getFirst();

        inContainers.interruptActiveRuns();

        run.failure();
        assertThat(fx.containers.leftoversRemoved()).contains(id);
        assertThat(inContainers.view(id).status()).isEqualTo(RunStatus.INTERRUPTED);
    }
}
