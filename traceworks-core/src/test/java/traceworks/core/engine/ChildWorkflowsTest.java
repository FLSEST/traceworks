package traceworks.core.engine;

import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.testing.MapWorkflowResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static traceworks.core.engine.ModelChoicesTest.block;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The workflows a run can reach before it starts, which the start check judges: every child a {@code sub-workflow}
 * or a {@code foreach} names literally, at any depth, each once.
 */
class ChildWorkflowsTest {

    private final MapWorkflowResolver workflows = new MapWorkflowResolver();

    private static WorkflowDefinition workflow(String id, BlockDefinition... blocks) {
        return new WorkflowDefinition(id, "Workflow " + id, null, Arrays.asList(blocks), null, null, null, null,
                Map.of(), Map.of(), null, null, null);
    }

    private static BlockDefinition child(String type, String ref) {
        return block(type, "child", Map.of("workflow_id", ref));
    }

    private List<String> reachedIds(WorkflowDefinition root) {
        return ChildWorkflows.reachable(root, workflows).stream().map(WorkflowDefinition::id).toList();
    }

    @Test
    void theWalk_reachesEveryLiteralChild_atAnyDepth_rootFirst_andSaysWhichComeFromAnotherPackage() {
        workflows.add(workflow("a", child("foreach", "c")));
        workflows.add(workflow("b"));
        workflows.addFromAnotherPackage("c", workflow("c"));
        WorkflowDefinition root = workflow("root", child("sub-workflow", " a "), child("sub-workflow", "b"),
                block("shell", "sh", Map.of("command", "true")));

        assertThat(ChildWorkflows.reached(root, workflows))
                .extracting(reached -> reached.definition().id(), ChildWorkflows.Reached::otherPackage)
                .containsExactly(tuple("root", false), tuple("a", false), tuple("c", true), tuple("b", false));
    }

    /** A child that names the workflow the walk started from closes a cycle: the root is not reached again. */
    @Test
    void aChildThatNamesTheRoot_doesNotReachTheRootAgain() {
        workflows.add(workflow("a", child("sub-workflow", "root")));
        WorkflowDefinition root = workflow("root", child("sub-workflow", "a"));
        workflows.add(root);

        assertThat(reachedIds(root)).containsExactly("root", "a");
    }

    @Test
    void aRootWithoutId_isWalkedToo() {
        workflows.add(workflow("a"));

        assertThat(reachedIds(workflow(null, child("sub-workflow", "a")))).containsExactly(null, "a");
    }

    /** The walk follows children of children 16 levels deep; the engine itself has no such limit. */
    @Test
    void theWalk_followsSixteenLevels() {
        for (int level = 1; level <= 20; level++) {
            workflows.add(workflow("w" + level, child("sub-workflow", "w" + (level + 1))));
        }
        List<String> expected = new ArrayList<>(List.of("root"));
        for (int level = 1; level <= 16; level++) {
            expected.add("w" + level);
        }

        assertThat(reachedIds(workflow("root", child("sub-workflow", "w1")))).isEqualTo(expected);
    }

    @Test
    void aPlaceholder_anUnknownReference_aNullBlock_andNoResolver_reachNothing() {
        WorkflowDefinition root = workflow("root", child("sub-workflow", "{{ which }}"),
                child("sub-workflow", "nowhere"), null, block("sub-workflow", "none", Map.of()));

        assertThat(reachedIds(root)).containsExactly("root");
        assertThat(workflows.requested()).containsExactly("nowhere");
        assertThat(ChildWorkflows.reachable(workflow("root", child("sub-workflow", "a")), null))
                .extracting(WorkflowDefinition::id).containsExactly("root");
    }

    @Test
    void onlyASubWorkflowOrAForEach_namesAChild_stripped() {
        assertThat(ChildWorkflows.literalRef("sub-workflow", Map.of("workflow_id", " a "))).isEqualTo("a");
        assertThat(ChildWorkflows.literalRef("foreach", Map.of("workflow_id", "b"))).isEqualTo("b");
        assertThat(ChildWorkflows.literalRef("shell", Map.of("workflow_id", "a"))).isNull();
        assertThat(ChildWorkflows.literalRef("sub-workflow", null)).isNull();
        assertThat(ChildWorkflows.literalRef("sub-workflow", Map.of("workflow_id", " "))).isNull();
        assertThat(ChildWorkflows.literalRef("sub-workflow", Map.of("workflow_id", 7))).isNull();
    }
}
