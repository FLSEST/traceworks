package traceworks.core.engine;

import traceworks.core.describe.Described;
import traceworks.core.engine.WritableMounts.Written;
import traceworks.core.model.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The writable mounts a workflow writes out, which the start check of a run asks the operator's listed folders
 * about before any step runs.
 */
class WritableMountsTest {

    private static List<Written> writtenOut(String json, boolean containerRun) {
        return WritableMounts.writtenOut(Described.definition(json), null, containerRun);
    }

    @Test
    void aWritableEntryWithALiteralHostSide_isFound_withItsBlockAndItsHostSide() {
        List<Written> found = writtenOut("""
                {"id":"wf","blocks":[
                 {"type":"shell","name":"plain","config":{"command":"true"}},
                 {"type":"shell","name":"export","config":{"command":"true",
                  "mounts":["/data/in:/in:ro"," ~/out :/out:rw","/srv/reports:/reports:rw"]}}]}""", true);

        assertThat(found).containsExactly(
                new Written("Block 2 \"export\"", "~/out :/out:rw", "~/out", false),
                new Written("Block 2 \"export\"", "/srv/reports:/reports:rw", "/srv/reports", false));
    }

    /**
     * A step that may write its workspace writes a folder in it anyway, so the start check asks no listed folder of
     * a writable mount there: each mount says whether its own step, a nested one too, sets
     * {@code workspace_writable: true}. Only the boolean counts, as for the step itself.
     */
    @Test
    void aWritableEntry_saysWhetherItsStepMayWriteTheWorkspace() {
        List<Written> found = writtenOut("""
                {"id":"wf","blocks":[
                 {"type":"shell","name":"fix","config":{"command":"true","workspace_writable":true,
                  "mounts":["/work/project/out:/out:rw"]}},
                 {"type":"shell","name":"read","config":{"command":"true","workspace_writable":false,
                  "mounts":["/work/project/out:/out:rw"]}},
                 {"type":"shell","name":"text","config":{"command":"true","workspace_writable":"true",
                  "mounts":["/work/project/out:/out:rw"]}},
                 {"type":"switch","name":"route","workspace_writable":true,"config":{"expression":"a",
                  "workspace_writable":true,"cases":[
                   {"value":"a","blocks":[
                    {"type":"shell","name":"inner","config":{"command":"true","mounts":["/work/project/a:/a:rw"]}},
                    {"type":"shell","name":"writer","config":{"command":"true","workspace_writable":true,
                     "mounts":["/work/project/b:/b:rw"]}}]}]}}]}""", true);

        assertThat(found).extracting(Written::hostPath, Written::workspaceWritable).containsExactly(
                org.assertj.core.groups.Tuple.tuple("/work/project/out", true),
                org.assertj.core.groups.Tuple.tuple("/work/project/out", false),
                org.assertj.core.groups.Tuple.tuple("/work/project/out", false),
                org.assertj.core.groups.Tuple.tuple("/work/project/a", false),
                org.assertj.core.groups.Tuple.tuple("/work/project/b", true));
    }

    /** What a placeholder names is known when the step runs, and the step's own check judges it there. */
    @Test
    void anEntryWithAPlaceholder_aReadOnlyOne_andOneThatIsNoMount_areLeftToTheStep() {
        assertThat(writtenOut("""
                {"id":"wf","blocks":[{"type":"shell","name":"s","config":{"command":"true","mounts":[
                  "{{ scratch_dir }}/out:/out:rw","{{ project_path }}/reports:/reports:rw","{{ all }}",
                  "/data:/data:ro","/data:/data","/data:/data:RW","volume","/a:/b:rw:z",7]}}]}""", true)).isEmpty();
        assertThat(writtenOut("""
                {"id":"wf","blocks":[{"type":"shell","name":"s","config":{"command":"true","mounts":"/a:/b:rw"}}]}""",
                true)).isEmpty();
    }

    @Test
    void aNestedStep_andAStepOfAChildWorkflow_areFound() {
        WorkflowDefinition parent = Described.definition("""
                {"id":"wf","blocks":[
                 {"type":"switch","name":"route","config":{"expression":"a","cases":[
                   {"value":"a","blocks":[{"type":"shell","name":"inner","config":{"command":"true",
                     "mounts":["/srv/a:/a:rw"]}}]}]}},
                 {"type":"sub-workflow","name":"child","config":{"workflow_id":"child-wf"}}]}""");
        WorkflowDefinition child = Described.definition("""
                {"id":"child-wf","blocks":[{"type":"shell","name":"deep","config":{"command":"true",
                  "mounts":["/srv/b:/b:rw"]}}]}""");

        List<Written> found = WritableMounts.writtenOut(parent, ref -> child, true);

        assertThat(found).extracting(Written::hostPath).containsExactly("/srv/a", "/srv/b");
        assertThat(found.get(0).where()).startsWith("Block 1 \"route\"").contains("inner");
        assertThat(found.get(1).where()).isEqualTo("workflow child-wf, Block 1 \"deep\"");
    }

    /** A block that no shell step reads has no mounts, whatever its config says. */
    @Test
    void theMountsOfABlockThatRunsNoShellStep_areNotRead() {
        assertThat(writtenOut("""
                {"id":"wf","blocks":[
                 {"type":"simple","name":"ask","config":{"prompt":"hi","mounts":["/srv/a:/a:rw"]}},
                 {"type":"retry","name":"again","config":{"execution_mode":"ai","prompt":"hi",
                  "mounts":["/srv/b:/b:rw"]}},
                 {"type":"retry","name":"shell","config":{"execution_mode":"shell","command":"true",
                  "mounts":["/srv/c:/c:rw"]}}]}""", true)).extracting(Written::hostPath).containsExactly("/srv/c");
    }

    /** In a host run a command without an image runs on the host, without its mounts; a tool step has them. */
    @Test
    void inAHostRun_onlyAStepWithAnImageOfItsOwnHasMounts() {
        assertThat(writtenOut("""
                {"id":"wf","blocks":[
                 {"type":"shell","name":"onTheHost","config":{"command":"true","mounts":["/srv/a:/a:rw"]}},
                 {"type":"shell","name":"tool","config":{"container_image":"aquasec/trivy:0.58.1",
                  "container_args":["fs","."],"mounts":["/srv/b:/b:rw"]}}]}""", false))
                .extracting(Written::hostPath).containsExactly("/srv/b");
    }
}
