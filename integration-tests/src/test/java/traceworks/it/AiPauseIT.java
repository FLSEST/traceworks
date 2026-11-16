package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A pause inside a loop of AI calls (package {@code ai-pause-loop}, the fake Claude): the call in flight is finished,
 * the run parks, and the resume asks only for the items that did not finish. No call is made twice: the record holds
 * exactly one {@code ai.request} per item. An interrupt (SIGTERM) cuts the call in flight instead: nothing of it is
 * left on the machine, and the resume asks again for that item and the ones after it, once each.
 */
class AiPauseIT extends AiIntegrationTest {

    @Test
    void ctrlCOnce_inALoopOfAiCalls_costsNoCallASecondTime() throws Exception {
        Traceworks.Running running = traceworks.startRun("ai-pause-loop");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (PauseIT.recorded(traceworks, "ai.request") < 2) {
            assertThat(System.nanoTime()).as("waited 120 s for the second AI call").isLessThan(deadline);
            Thread.sleep(100);
        }

        running.ctrlC();
        Result run = running.await(180);

        assertThat(run.code()).as(run.describe()).isEqualTo(5);
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.only("run.parked").path("kind").asText()).isEqualTo("pause");
        assertThat(folder.ownEvents("ai.request")).as("the call in flight was finished, no third one started")
                .hasSize(2);
        assertThat(folder.ownEvents("ai.response")).hasSize(2);
        assertThat(folder.ofStep("foreach.item.finished", 0)).extracting(item -> item.path("status").asText())
                .as("the items so far: " + run.describe()).containsExactly("COMPLETED", "COMPLETED");

        Result resumed = traceworks.exec("resume", run.runId());

        assertThat(resumed.code()).as(resumed.describe()).isZero();
        List<JsonNode> requests = folder.ownEvents("ai.request");
        assertThat(requests).extracting(request -> request.path("item").asInt(-1))
                .as("one AI call per item, in the whole record").containsExactly(0, 1, 2);
        assertThat(folder.metadata("judged").path("itemsResumed").asInt()).isEqualTo(2);
        assertThat(folder.output("judged")).contains("[one]: ", "[two]: ", "[three]: ");
    }

    /**
     * SIGTERM while an AI call runs (package {@code ai-interrupt-loop}): the process must end at once, so the call
     * is cut. Nothing of it stays on the machine, the run is resumable, and the resume asks once for each item that
     * had not finished: the item whose call was cut, and the one after it. The call that had finished is not paid
     * for again.
     *
     * <p>Each call of the fake waits until the test puts a file of its item into the workspace, and notes in the
     * scratch directory that it waits: the test knows that the second call's container runs, and no call ends
     * before the test lets it.
     */
    @Test
    void sigtermDuringAnAiCall_leavesNothing_andTheResumeAsksAgainOnce() throws Exception {
        String runtime = traceworks.environment().runtime();
        Files.createFile(traceworks.workspace().resolve("go-one"));
        Traceworks.Running running = traceworks.startRun("ai-interrupt-loop");
        PauseIT.await(() -> waits("go-two"), "the second AI call to wait in its container");

        running.terminate();
        Result run = running.await(120);

        assertThat(run.code()).as("128 + SIGTERM: " + run.describe()).isEqualTo(143);
        assertThat(traceworks.folders()).hasSize(1);
        RunFolder folder = traceworks.folders().getFirst();
        assertThat(folder.ownEvents("run.finished")).isEmpty();
        assertThat(folder.ownEvents("run.parked")).isEmpty();
        assertThat(folder.hasManifest()).isFalse();
        Result show = traceworks.exec("show", folder.id());
        assertThat(show.code()).as(show.describe()).isEqualTo(9);
        assertThat(show.out()).contains("INTERRUPTED");
        // The process removed what it had started before it ended: the call's container, its proxy and the two
        // networks of the run. A process that only ended would leave all four, found by the run's label
        assertThat(Containers.of(runtime, folder.id())).as("containers left after SIGTERM").isEmpty();
        assertThat(Containers.networksOf(runtime, folder.id())).as("networks left after SIGTERM").isEmpty();
        assertThat(folder.ownEvents("ai.request")).extracting(request -> request.path("item").asInt(-1))
                .as("the calls before the signal: the first item's, and the one that was cut").containsExactly(0, 1);
        assertThat(folder.ownEvents("ai.response")).as("only the first call answered").hasSize(1);

        Files.createFile(traceworks.workspace().resolve("go-two"));
        Files.createFile(traceworks.workspace().resolve("go-three"));
        Result resumed = traceworks.exec("resume", folder.id());

        assertThat(resumed.code()).as(resumed.describe()).isZero();
        int resumedAt = folder.only("run.resumed").path("seq").asInt();
        // One call per item that had not finished, and none for the item that had: a resume that ran the whole loop
        // again would ask for item 0 too, and one that retried a call would ask twice for an item
        assertThat(folder.ownEvents("ai.request").stream().filter(request -> request.path("seq").asInt() > resumedAt))
                .extracting(request -> request.path("item").asInt(-1)).containsExactly(1, 2);
        assertThat(folder.ownEvents("ai.request")).extracting(request -> request.path("item").asInt(-1))
                .as("every AI call of the record").containsExactly(0, 1, 1, 2);
        assertThat(folder.ownEvents("ai.response")).hasSize(3);
        assertThat(folder.metadata("judged").path("itemsResumed").asInt()).isEqualTo(1);
        assertThat(folder.output("judged")).contains("[one]: ", "[two]: ", "[three]: ");
        assertThat(Containers.networksOf(runtime, folder.id())).as("networks left after the resume").isEmpty();
    }

    /** Whether a call of the fake notes in the scratch directory of the run that it waits for the file {@code name}. */
    private boolean waits(String name) {
        return traceworks.folders().stream()
                .anyMatch(folder -> Files.exists(folder.dir().resolve("scratch").resolve("fake-waiting-" + name)));
    }
}
