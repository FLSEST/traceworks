package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stopping a run from its terminal with real signals (docs/cli.md, "run"; package {@code pause-loop}): Ctrl-C once
 * pauses, Ctrl-C twice cancels, and SIGTERM leaves the run interrupted. A paused or interrupted run goes on with
 * {@code resume}, and its loop runs only the items that did not finish: every item of the loop notes its start in
 * {@code ran.txt} in the workspace, so the file says how often each one ran. The Ctrl-C of these tests goes to the
 * whole process group of the command, as a terminal sends it.
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

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("waited 90 s for " + what);
            }
            Thread.sleep(100);
        }
    }

    /** The folder of the one run in the home. */
    private RunFolder theRun() {
        assertThat(traceworks.folders()).hasSize(1);
        return traceworks.folders().getFirst();
    }

    @Test
    void ctrlCOnce_pausesAfterTheItemInFlight_andTheResumeRunsEveryItemOnce() throws Exception {
        Traceworks.Running running = traceworks.startRun("pause-loop");
        await(() -> ranItems().size() == 2, "the second item to start");

        running.ctrlC();
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

        Result pause = traceworks.exec("pause");
        Result run = running.await(120);

        assertThat(pause.code()).as(pause.describe()).isZero();
        assertThat(pause.out()).contains("pause requested: run ", "traceworks resume '");
        assertThat(run.code()).as(run.describe()).isEqualTo(5);
        assertThat(ranItems()).containsExactly("one");
        Result cancel = traceworks.exec("cancel", run.runId());
        assertThat(cancel.code()).as(cancel.describe()).isZero();
    }

    /** Counts the lines of {@code type} in the events of the one run in the home; 0 before the run exists. */
    static long recorded(Traceworks traceworks, String type) {
        Path runs = traceworks.home().resolve("runs");
        if (!Files.isDirectory(runs)) {
            return 0;
        }
        try (Stream<Path> found = Files.walk(runs, 2)) {
            long count = 0;
            for (Path dir : found.filter(path -> Files.isRegularFile(path.resolve("events.jsonl"))).toList()) {
                count += Files.readAllLines(dir.resolve("events.jsonl")).stream()
                        .filter(line -> line.contains("\"type\":\"" + type + "\"")).count();
            }
            return count;
        } catch (IOException e) {
            return 0;
        }
    }
}
