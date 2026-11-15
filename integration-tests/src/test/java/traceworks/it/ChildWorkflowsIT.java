package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code sub-workflow} and {@code foreach} with {@code workflow_id} (package {@code children}): each runs the workflow in
 * {@code child/} as a child run, which writes into the log of its parent with its own run id.
 */
class ChildWorkflowsIT extends IntegrationTest {

    @Test
    void childRunsWriteIntoTheParentsLog_withTheirOwnRunIds() {
        Result run = traceworks.run("children");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        String parent = folder.id();

        // sub-workflow: the child sees the parent's variables, and its output is the step's output
        assertThat(folder.output("once")).isEqualTo("child greets parent");
        JsonNode once = folder.metadata("once");
        assertThat(once.path("subRunStatus").asText()).isEqualTo("COMPLETED");
        String onceChild = once.path("subRunId").asText();

        // foreach with workflow_id: one child run per item, which sees the item through the context mapping
        assertThat(folder.output("per_item")).isEqualTo("[red]: child greets item red\n"
                + "[green]: child greets item green");
        List<JsonNode> items = folder.ofStep("foreach.item.finished", folder.stepIndex("per_item"));
        assertThat(items).hasSize(2);
        List<String> itemChildren = items.stream().map(e -> e.path("sub_run").asText()).toList();
        assertThat(itemChildren).doesNotContainNull().doesNotHaveDuplicates().doesNotContain(onceChild, parent);

        assertThat(folder.output("after")).isEqualTo("after: child greets parent");

        // Three child runs in the parent's log, with the parent's id in "parent". A child run has no run.started of
        // its own: its first line is its step's step.started, its last one its run.finished
        Set<String> runs = folder.runIds();
        assertThat(runs).containsExactlyInAnyOrder(parent, onceChild, itemChildren.get(0), itemChildren.get(1));
        for (String child : List.of(onceChild, itemChildren.get(0), itemChildren.get(1))) {
            List<JsonNode> childEvents = folder.events().stream()
                    .filter(e -> child.equals(e.path("run").asText())).toList();
            assertThat(childEvents).allSatisfy(e -> assertThat(e.path("parent").asText()).isEqualTo(parent));
            assertThat(childEvents.getFirst().path("type").asText()).isEqualTo("step.started");
            assertThat(childEvents.getFirst().path("name").asText()).isEqualTo("greet_child");
            assertThat(childEvents.getLast().path("type").asText()).isEqualTo("run.finished");
            assertThat(childEvents.getLast().path("status").asText()).isEqualTo("COMPLETED");
        }
        // One folder, one manifest: the child runs have none of their own
        assertThat(traceworks.folders()).extracting(RunFolder::id).containsExactly(parent);
        assertThat(folder.manifest().path("status").asText()).isEqualTo("COMPLETED");
    }
}
