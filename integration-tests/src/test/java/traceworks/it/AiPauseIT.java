package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A pause inside a loop of AI calls (package {@code ai-pause-loop}, the fake Claude): the call in flight is finished,
 * the run parks, and the resume asks only for the items that did not finish. No call is made twice: the record holds
 * exactly one {@code ai.request} per item.
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
}
