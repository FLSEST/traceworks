package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stopping a run from its terminal with real signals (docs/cli.md, "run"; package {@code pause-loop}): Ctrl-C once
 * pauses, Ctrl-C twice cancels, and SIGTERM leaves the run interrupted. A paused or interrupted run goes on with
 * {@code resume}, and its loop runs only the items that did not finish: every item of the loop notes its start in
 * {@code ran.txt} in the workspace, so the file says how often each one ran, and then waits until the test lets it
 * go ({@link #let}): no item ends before the test has done what it does while the item runs, and none takes longer
 * than that. The Ctrl-C of these tests goes to the
 * whole process group of the command, as a terminal sends it. A child workflow does not park in the middle (package
 * {@code pause-child}): it ends, and its parent parks.
 */
class PauseIT extends IntegrationTest {

    private Path ran() {
        return traceworks.workspace().resolve("ran.txt");
    }

    private List<String> ranItems() {
        try {
            return Files.isRegularFile(ran()) ? Files.readAllLines(ran()) : List.of();
        } catch (IOException e) {
            return List.of();   // read while an item writes: the next look sees it
        }
    }

    /** Waits for {@code condition}, looking every 100 ms; fails after 90 s, which only a failing test waits. */
    static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("waited 90 s for " + what);
            }
            Thread.sleep(100);
        }
    }

    /** Lets the items {@code names} end: each waits for its file {@code go-<item>} in the workspace. */
    private void let(String... names) throws IOException {
        for (String name : names) {
            Files.createFile(traceworks.workspace().resolve("go-" + name));
        }
    }

    /** The folder of the one run in the home. */
    private RunFolder theRun() {
        assertThat(traceworks.folders()).hasSize(1);
        return traceworks.folders().getFirst();
    }

    @Test
    void ctrlCOnce_pausesAfterTheItemInFlight_andTheResumeRunsEveryItemOnce() throws Exception {
        let("one");
        Traceworks.Running running = traceworks.startRun("pause-loop");
        await(() -> ranItems().size() == 2, "the second item to start");

        running.ctrlC();
        await(() -> running.errSoFar().contains("Ctrl-C again cancels"), "the pause to be announced");
        let("two");   // only now: the pause came while the item ran
        Result run = running.await(120);

        assertThat(run.code()).as(run.describe()).isEqualTo(5);
        assertThat(run.err()).contains("traceworks: pausing after the step in flight; Ctrl-C again cancels");
        assertThat(run.status()).isEqualTo("PARKED");
        assertThat(run.out()).contains("paused inside the loop of step 0: 2 of 4 items are finished",
                "traceworks resume '" + run.runId() + "'");
        assertThat(ranItems()).as("the item in flight was finished, the next one did not start")
                .containsExactly("one", "two");
        RunFolder folder = traceworks.folder(run);
        JsonNode parked = folder.only("run.parked");
        assertThat(parked.path("kind").asText()).isEqualTo("pause");
        assertThat(parked.path("step").asInt(-1)).isZero();
        assertThat(folder.only("run.pause_requested").path("actor").asText()).isEqualTo(Traceworks.ACTOR);
        List<JsonNode> finished = folder.ofStep("foreach.item.finished", 0);
        assertThat(finished).extracting(item -> item.path("status").asText()).containsExactly("COMPLETED", "COMPLETED");
        assertThat(folder.ownEvents("step.finished")).as("the loop's step is not finished").isEmpty();

        let("three", "four");
        Result resumed = traceworks.exec("resume", run.runId());

        assertThat(resumed.code()).as(resumed.describe()).isZero();
        assertThat(ranItems()).as("every item's command ran exactly once")
                .containsExactly("one", "two", "three", "four");
        assertThat(resumed.out()).contains("item 1/4 COMPLETED (taken over from event ",
                "item 2/4 COMPLETED (taken over from event ");
        assertThat(folder.output("slow_items"))
                .isEqualTo("[one]: did one\n[two]: did two\n[three]: did three\n[four]: did four");
        assertThat(folder.metadata("slow_items").path("itemsResumed").asInt()).isEqualTo(2);
        assertThat(folder.output("after")).isEqualTo("after the loop");
    }

    @Test
    void ctrlCTwice_cancelsTheRun() throws Exception {
        Traceworks.Running running = traceworks.startRun("pause-loop");
        await(() -> ranItems().size() == 1, "the first item to start");

        running.ctrlC();
        await(() -> running.errSoFar().contains("Ctrl-C again cancels"), "the pause to be announced");
        running.ctrlC();
        Result run = running.await(120);

        assertThat(run.code()).as(run.describe()).isEqualTo(4);
        assertThat(run.err()).contains("traceworks: cancelling the run");
        assertThat(run.status()).isEqualTo("CANCELLED");
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.only("run.finished").path("status").asText()).isEqualTo("CANCELLED");
        assertThat(folder.ownEvents("run.pause_requested")).hasSize(1);
        assertThat(folder.ownEvents("run.cancel_requested")).hasSize(1);
        assertThat(ranItems()).as("no further item started").containsExactly("one");
        Result resume = traceworks.exec("resume", run.runId());
        assertThat(resume.code()).as("a cancelled run is final: " + resume.describe()).isEqualTo(6);
    }

    /** The end of the process cannot wait for a step: the run stays resumable, and costs only the item in flight. */
    @Test
    void sigterm_leavesTheRunInterrupted_andTheResumeGoesOn() throws Exception {
        let("one");
        Traceworks.Running running = traceworks.startRun("pause-loop");
        await(() -> ranItems().size() == 2, "the second item to start");

        running.terminate();
        Result run = running.await(120);

        assertThat(run.code()).as("128 + SIGTERM: " + run.describe()).isEqualTo(143);
        assertThat(run.err()).doesNotContain("internal error");
        RunFolder folder = theRun();
        assertThat(folder.ownEvents("run.finished")).isEmpty();
        assertThat(folder.ownEvents("run.parked")).isEmpty();
        assertThat(folder.ownEvents("run.cancel_requested")).isEmpty();
        assertThat(folder.hasManifest()).isFalse();
        Result show = traceworks.exec("show", folder.id());
        assertThat(show.code()).as(show.describe()).isEqualTo(9);
        assertThat(show.out()).contains("INTERRUPTED");
        Containers.assertNoneLeft(traceworks.environment().runtime(), java.util.Set.of(folder.id()));

        let("two", "three", "four");
        Result resumed = traceworks.exec("resume", folder.id());

        assertThat(resumed.code()).as(resumed.describe()).isZero();
        assertThat(ranItems()).as("only the item in flight ran again")
                .containsExactly("one", "two", "two", "three", "four");
        List<JsonNode> afterResume = folder.ofStep("foreach.item.finished", 0).stream()
                .filter(item -> item.has("resumed_from")).toList();
        assertThat(afterResume).extracting(item -> item.path("item_index").asInt()).containsExactly(0);
        assertThat(folder.output("slow_items"))
                .isEqualTo("[one]: did one\n[two]: did two\n[three]: did three\n[four]: did four");
    }

    /** {@code traceworks pause} from another terminal does what the first Ctrl-C does. */
    @Test
    void pauseFromAnotherProcess_parksTheRun() throws Exception {
        Traceworks.Running running = traceworks.startRun("pause-loop");
        await(() -> ranItems().size() == 1, "the first item to start");

        Result pause = traceworks.exec("pause");   // returns once the run recorded the request
        let("one");
        Result run = running.await(120);

        assertThat(pause.code()).as(pause.describe()).isZero();
        assertThat(pause.out()).contains("pause requested: run ", "traceworks resume '");
        assertThat(run.code()).as(run.describe()).isEqualTo(5);
        assertThat(ranItems()).containsExactly("one");
        Result cancel = traceworks.exec("cancel", run.runId());
        assertThat(cancel.code()).as(cancel.describe()).isZero();
    }

    private List<String> counted() {
        Path file = traceworks.workspace().resolve("counted.txt");
        try {
            return Files.isRegularFile(file) ? Files.readAllLines(file) : List.of();
        } catch (IOException e) {
            return List.of();   // read while the step writes: the next look sees it
        }
    }

    /**
     * A pause while a child workflow runs (package {@code pause-child}; docs/cli.md, "pause"): a child run has no
     * checkpoint, so it does not park in the middle. Its step, whose container runs when the pause comes, ends by
     * itself, the child completes, and the parent parks before its next step. The resume starts no second child.
     * The child's step notes each start in {@code counted.txt} in the workspace and then waits for the file
     * {@code go}, which the test writes only after the pause was announced.
     */
    @Test
    void aPauseWhileAChildRuns_letsItEnd_andParksTheParent() throws Exception {
        Traceworks.Running running = traceworks.startRun("pause-child");
        await(() -> counted().size() == 1, "the child's step to start");

        running.ctrlC();
        await(() -> running.errSoFar().contains("Ctrl-C again cancels"), "the pause to be announced");
        Files.createFile(traceworks.workspace().resolve("go"));
        Result run = running.await(120);

        // Exit 5 after the child's step: a pause that cut the container would fail the step (its command ends 0
        // and prints "child done" only when it found the file), and one that waited in the process would never exit
        assertThat(run.code()).as(run.describe()).isEqualTo(5);
        assertThat(run.status()).isEqualTo("PARKED");
        assertThat(run.out()).contains("paused before the next step: every step that started is finished.",
                "traceworks resume '" + run.runId() + "'");
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.only("run.parked").path("kind").asText()).isEqualTo("pause");
        assertThat(folder.stepStatus("slow_child")).isEqualTo("COMPLETED");
        assertThat(folder.output("slow_child")).isEqualTo("child done");
        String child = folder.metadata("slow_child").path("subRunId").asText();
        assertThat(folder.metadata("slow_child").path("subRunStatus").asText()).isEqualTo("COMPLETED");
        assertThat(folder.eventsOf(child, "run.finished")).extracting(e -> e.path("status").asText())
                .containsExactly("COMPLETED");
        assertThat(folder.ownEvents("step.started")).extracting(e -> e.path("name").asText())
                .as("the step after the child did not start").containsExactly("slow_child");
        assertThat(counted()).containsExactly("started");

        Result resumed = traceworks.exec("resume", run.runId());

        assertThat(resumed.code()).as(resumed.describe()).isZero();
        assertThat(resumed.status()).isEqualTo("COMPLETED");
        // The child ran once: a resume that started its step again would count a second start and add a run id
        assertThat(counted()).as("the child's command ran exactly once").containsExactly("started");
        assertThat(folder.runIds()).containsExactlyInAnyOrder(folder.id(), child);
        assertThat(folder.output("after")).isEqualTo("after the child");
    }

    /**
     * SIGTERM while the child's container runs: the process cannot wait for the child. The run is interrupted, its
     * container is gone, and the resume runs the step that was in flight again, which is the whole child: a second
     * child run, and a second start of its command.
     */
    @Test
    void sigtermWhileAChildRuns_leavesTheRunInterrupted_andTheResumeRunsTheChildAgain() throws Exception {
        Traceworks.Running running = traceworks.startRun("pause-child");
        await(() -> counted().size() == 1, "the child's step to start");

        running.terminate();
        Result run = running.await(120);

        assertThat(run.code()).as("128 + SIGTERM: " + run.describe()).isEqualTo(143);
        RunFolder folder = theRun();
        assertThat(folder.ownEvents("run.finished")).isEmpty();
        assertThat(folder.ownEvents("run.parked")).isEmpty();
        Result show = traceworks.exec("show", folder.id());
        assertThat(show.code()).as(show.describe()).isEqualTo(9);
        assertThat(show.out()).contains("INTERRUPTED");
        List<String> cut = folder.runIds().stream().filter(id -> !id.equals(folder.id())).toList();
        assertThat(cut).as("the child run that was in flight").hasSize(1);
        assertThat(folder.eventsOf(cut.getFirst(), "run.finished")).as("it never ended").isEmpty();
        // The child's container carries the id of the run that was started, and the process removed it
        Containers.assertNoneLeft(traceworks.environment().runtime(), folder.runIds());

        Files.createFile(traceworks.workspace().resolve("go"));
        Result resumed = traceworks.exec("resume", folder.id());

        assertThat(resumed.code()).as(resumed.describe()).isZero();
        assertThat(counted()).as("the child's command started again: a child run has no checkpoint of its own")
                .containsExactly("started", "started");
        String again = folder.metadata("slow_child").path("subRunId").asText();
        assertThat(again).isNotEqualTo(cut.getFirst());
        assertThat(folder.runIds()).containsExactlyInAnyOrder(folder.id(), cut.getFirst(), again);
        assertThat(folder.eventsOf(again, "run.finished")).extracting(e -> e.path("status").asText())
                .containsExactly("COMPLETED");
        assertThat(folder.output("after")).isEqualTo("after the child");
    }

    /** The lines of {@code name} in the scratch directory of the one run in the home; none before it exists. */
    private List<String> scratchLines(String name) {
        try {
            for (RunFolder folder : traceworks.folders()) {
                Path file = folder.dir().resolve("scratch").resolve(name);
                if (Files.isRegularFile(file)) {
                    return Files.readAllLines(file);
                }
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            // read while the step writes, or while the run folder is made: the next look sees it
        }
        return List.of();
    }

    /**
     * SIGTERM inside the second pass of a {@code while} (package {@code interrupt-while}; docs/cli.md, "resume"):
     * a {@code while} keeps nothing a resume could take over, so the resumed loop starts at its first pass. The
     * body adds a line to a file in the scratch directory at each start: two before the signal, three after the
     * resume. A resume that went on at the pass in flight would leave four lines, one that skipped the loop two.
     */
    @Test
    void sigtermInsideAWhile_leavesTheRunInterrupted_andTheResumeRunsEveryPassAgain() throws Exception {
        Traceworks.Running running = traceworks.startRun("interrupt-while");
        await(() -> scratchLines("passes.txt").size() == 2, "the second pass to start");

        running.terminate();
        Result run = running.await(120);

        assertThat(run.code()).as("128 + SIGTERM: " + run.describe()).isEqualTo(143);
        RunFolder folder = theRun();
        assertThat(folder.ownEvents("run.finished")).isEmpty();
        Containers.assertNoneLeft(traceworks.environment().runtime(), java.util.Set.of(folder.id()));
        assertThat(scratchLines("passes.txt")).hasSize(2);

        Files.createFile(traceworks.workspace().resolve("go"));
        Result resumed = traceworks.exec("resume", folder.id());

        assertThat(resumed.code()).as(resumed.describe()).isZero();
        assertThat(scratchLines("passes.txt")).as("two starts before the signal, then all three passes again")
                .hasSize(5);
        assertThat(folder.output("loop")).isEqualTo("[iteration 1]: pass 3\n[iteration 2]: pass 4\n"
                + "[iteration 3]: pass 5");
        assertThat(folder.metadata("loop").path("iterationCount").asInt()).isEqualTo(3);
        assertThat(folder.output("after")).isEqualTo("passes: 5");
    }

    /**
     * SIGTERM inside the second attempt of a {@code retry} of two attempts (package {@code interrupt-retry}): the
     * resumed step has both its attempts again. Its command passes at its fourth start ever, so a resume that went
     * on counting (one attempt used, one cut) would have none left and fail the run.
     */
    @Test
    void sigtermInsideARetry_leavesTheRunInterrupted_andTheResumeHasEveryAttemptAgain() throws Exception {
        Traceworks.Running running = traceworks.startRun("interrupt-retry");
        await(() -> scratchLines("attempts.txt").size() == 2, "the second attempt to start");

        running.terminate();
        Result run = running.await(120);

        assertThat(run.code()).as("128 + SIGTERM: " + run.describe()).isEqualTo(143);
        RunFolder folder = theRun();
        assertThat(folder.ownEvents("run.finished")).isEmpty();
        Containers.assertNoneLeft(traceworks.environment().runtime(), java.util.Set.of(folder.id()));

        Files.createFile(traceworks.workspace().resolve("go"));
        Result resumed = traceworks.exec("resume", folder.id());

        assertThat(resumed.code()).as(resumed.describe()).isZero();
        assertThat(scratchLines("attempts.txt")).as("two starts before the signal, two attempts after the resume")
                .hasSize(4);
        assertThat(folder.output("flaky")).isEqualTo("attempt 4 passes");
        assertThat(folder.metadata("flaky").path("attempts").asInt()).as("counted from one again").isEqualTo(2);
        assertThat(folder.output("after")).isEqualTo("attempts: 4");
    }
}
