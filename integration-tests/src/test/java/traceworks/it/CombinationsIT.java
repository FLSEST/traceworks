package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The block types together, without any AI (packages {@code combo-pipeline}, {@code combo-approval} and
 * {@code combo-failures}): the other classes test each block alone. {@code conditional}, {@code retry},
 * {@code while} and {@code foreach} hold a command, {@code switch} holds blocks, and {@code sub-workflow} and a
 * {@code foreach} with {@code workflow_id} hold a workflow; here they follow each other in one run, hand data on,
 * park in the middle and fail inside each other.
 *
 * <p>The steps of each package write one file in the scratch directory, a line each, so the file says which
 * commands ran, in which order and how often. Each assertion names in a comment what would break it.
 */
class CombinationsIT extends IntegrationTest {

    private static final String TRAIL = "seed\nitem red\nitem green\ncase fast\nthen\npass 1\npass 2\nattempt 1\n"
            + "attempt 2\n";

    private static String scratchFile(RunFolder folder, String name) throws IOException {
        return Files.readString(folder.dir().resolve("scratch").resolve(name), StandardCharsets.UTF_8);
    }

    /**
     * A shell step, a {@code foreach} over the lines of a command, a {@code switch} case, a {@code conditional}, a
     * child workflow with a {@code while} and a {@code retry}, a {@code compress} of the child's output and a shell
     * step that takes the earlier outputs as {@code values}. A child run shares the scratch directory of the run
     * that started it (docs/workflow-format.md): its last step prints what the parent's steps wrote there.
     */
    @Test
    void aPipelineOfEveryHolder_handsDataOnThroughScratchAndValues() throws IOException {
        Result run = traceworks.run("combo-pipeline");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        // Each holder's own output; a holder that ran another branch, case or item count would differ
        assertThat(folder.output("seed")).isEqualTo("seeded");
        assertThat(folder.output("gather")).isEqualTo("[red]: added red\n[green]: added green");
        assertThat(folder.output("route")).isEqualTo("routed fast");
        assertThat(folder.metadata("route").path("matchedCase").asText()).isEqualTo("fast");
        // The condition reads the file the switch case wrote: it holds only when the case's command ran before it
        assertThat(folder.output("check")).isEqualTo("the trail has the fast case");
        assertThat(folder.metadata("check").path("conditionMet").asBoolean()).isTrue();

        // The child's last step printed the file: a child with a scratch directory of its own would find none, its
        // while would never end by its condition and its output would lack the parent's lines
        assertThat(folder.output("child")).isEqualTo(TRAIL.strip());
        // The file on disk, in the one scratch directory of the run: every command once, in the order of the blocks
        assertThat(scratchFile(folder, "trail.txt")).isEqualTo(TRAIL);

        // The child run in the parent's log: its three steps, all complete, under its own run id
        JsonNode childStep = folder.metadata("child");
        assertThat(childStep.path("subRunStatus").asText()).isEqualTo("COMPLETED");
        String child = childStep.path("subRunId").asText();
        assertThat(folder.runIds()).as("the parent and its one child").containsExactlyInAnyOrder(folder.id(), child);
        assertThat(folder.stepNamesOf(child)).containsExactly("count_up", "flaky", "read_trail");
        assertThat(folder.eventsOf(child, "step.finished")).extracting(e -> e.path("status").asText())
                .containsExactly("COMPLETED", "COMPLETED", "COMPLETED");
        assertThat(folder.eventsOf(child, "run.finished")).extracting(e -> e.path("status").asText())
                .containsExactly("COMPLETED");
        // while: two passes by its condition (a third would break when the condition were not asked before each
        // pass); retry: one failed attempt, then one that passes
        assertThat(folder.metadataOf(child, "count_up").path("iterationCount").asInt()).isEqualTo(2);
        assertThat(folder.outputOf(child, "count_up")).isEqualTo("[iteration 1]: pass 1\n[iteration 2]: pass 2");
        assertThat(folder.metadataOf(child, "flaky").path("attempts").asInt()).isEqualTo(2);
        assertThat(folder.outputOf(child, "flaky")).isEqualTo("attempt 2 passes");
        // Three conditions and two passes, two attempts, one print: the commands of the child, under its run id
        List<JsonNode> childCommands = folder.eventsOf(child, "step.command");
        assertThat(childCommands).hasSize(8);
        // The record says the same as the file: every container of the child got the parent's scratch directory
        Path scratch = folder.dir().resolve("scratch").toRealPath();
        for (JsonNode command : childCommands) {
            assertThat(scratchMount(command)).as("the scratch mount of a child's command").isEqualTo(scratch);
        }
        assertThat(scratchMount(folder.ofStep("step.command", folder.stepIndex("seed")).getFirst()))
                .isEqualTo(scratch);

        // compress filters the output the sub-workflow step copied from its child: no command, the two item lines
        String items = folder.output("items_only");
        assertThat(items).startsWith("## child").contains("item red", "item green")
                .doesNotContain("pass 1", "attempt 1", "case fast");
        assertThat(folder.ofStep("step.command", folder.stepIndex("items_only"))).isEmpty();

        // The last step got each earlier output under its name, as a value: a value that held another block's
        // output, or a case block that were not addressable by name, would change a line
        assertThat(folder.output("final")).isEqualTo("gathered=[red]: added red\n[green]: added green\n"
                + "case=routed fast\nrouted=routed fast\nchecked=the trail has the fast case\nitems=2\nlines=9");
        JsonNode finalCommand = folder.ofStep("step.command", folder.stepIndex("final")).getFirst();
        JsonNode values = new ObjectMapper().readTree(folder.blob(finalCommand.path("values")));
        assertThat(values.path("CASE").asText()).as("the record names each value").isEqualTo("routed fast");
        assertThat(values.path("CHECKED").asText()).isEqualTo("the trail has the fast case");

        Result verify = traceworks.exec("verify", folder.id());
        assertThat(verify.code()).as(verify.describe()).isZero();
        assertThat(verify.out()).contains("verified");

        // show: one line per step of the run ...
        Result show = traceworks.exec("show", folder.id());
        assertThat(show.code()).as(show.describe()).isZero();
        for (String step : List.of("seed +\\[shell\\]", "gather +\\[foreach\\]", "route +\\[switch\\]",
                "check +\\[conditional\\]", "child +\\[sub-workflow\\]", "items_only +\\[compress\\]",
                "final +\\[shell\\]")) {
            assertThat(show.out()).containsPattern("(?m)^  \\d+ +" + step + " +COMPLETED ");
        }
        // ... and names what ran inside a step: the case of the switch with its block, and under the step that
        // started it the child run with its steps, further in. A show that listed the run's own steps only would
        // name neither case_fast nor a step of the child
        assertThat(show.out()).as(show.describe())
                .containsPattern("(?m)^      case fast: case_fast \\[shell\\]$")
                .containsPattern("(?m)^      child run " + child.substring(0, 8) + " COMPLETED$")
                .containsPattern("(?m)^        \\d+ +count_up +\\[while\\] +COMPLETED ")
                .containsPattern("(?m)^        \\d+ +flaky +\\[retry\\] +COMPLETED ")
                .containsPattern("(?m)^        \\d+ +read_trail +\\[shell\\] +COMPLETED ");
        // run.html draws the child's steps under the step that started them
        assertThat(folder.html()).contains("count_up", "flaky", "read_trail");
    }

    private static Path scratchMount(JsonNode command) throws IOException {
        for (JsonNode mount : command.path("mounts")) {
            if ("/scratch".equals(mount.path("container").asText())) {
                return Path.of(mount.path("host").asText()).toRealPath();
            }
        }
        throw new AssertionError("No mount at /scratch in " + command);
    }

    /**
     * A {@code foreach}, a child workflow and a {@code switch} case, then an {@code approval} that parks the run,
     * then a step that reads every earlier output and the scratch file. The resume runs only what had not run, and a
     * twin run that never parked (its approval answers itself) did the same in every step.
     */
    @Test
    void anApprovalAfterALoopAndAChild_resumesWithEverything() throws IOException {
        Result run = traceworks.run("combo-approval");

        assertThat(run.code()).as(run.describe()).isEqualTo(5);
        assertThat(run.status()).isEqualTo("PARKED");
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.only("run.parked").path("kind").asText()).isEqualTo("approval");
        // Two items, the child's step and the case block: four commands before the question
        assertThat(folder.events("step.command")).hasSize(4);
        String before = "item a\nitem b\nchild\ncase fast\n";
        assertThat(scratchFile(folder, "trail.txt")).isEqualTo(before);

        Result approve = traceworks.exec("approve", folder.id(), "--answer", "Yes");
        assertThat(approve.code()).as(approve.describe()).isZero();
        Result resume = traceworks.exec("resume", folder.id(), "--no-input");

        assertThat(resume.code()).as(resume.describe()).isZero();
        assertThat(resume.status()).isEqualTo("COMPLETED");
        // No step ran twice: a resume that started before the approval would add lines to the file and commands to
        // the record, and the loop, the child and the switch would each have started a second time
        assertThat(scratchFile(folder, "trail.txt")).isEqualTo(before);
        assertThat(folder.events("step.command")).as("the resume ran the last step's command and no other")
                .hasSize(5);
        assertThat(folder.ownEvents("step.started")).extracting(e -> e.path("name").asText())
                .filteredOn(name -> !name.equals("release"))   // the question's step starts again to take its answer
                .containsExactly("gather", "child", "route", "final");
        assertThat(folder.runIds()).as("one child run, not a second one after the resume").hasSize(2);
        // The outputs from before the park are intact after it: the last step reads them from the checkpoint
        assertThat(folder.output("gather")).isEqualTo("[a]: gathered a\n[b]: gathered b");
        assertThat(folder.output("child")).isEqualTo("child saw 2 lines");
        assertThat(folder.output("route")).isEqualTo("routed fast");
        assertThat(folder.output("final")).isEqualTo("gathered=[a]: gathered a\n[b]: gathered b\n"
                + "child=child saw 2 lines\nrouted=routed fast\nanswer=Yes\n" + before.strip());
        Result verify = traceworks.exec("verify", folder.id());
        assertThat(verify.code()).as(verify.describe()).isZero();
        assertThat(verify.out()).contains("verified");

        // The twin: the same package, with the approval answering itself, so it never parks
        Result twin = traceworks.run("combo-approval", "--input", "approval_mode=auto");
        assertThat(twin.code()).as(twin.describe()).isZero();
        assertThat(traceworks.folder(twin).ownEvents("run.parked")).isEmpty();

        Result diff = traceworks.exec("diff", folder.id(), twin.runId());

        // The input that made the twin differs, so diff exits 11; no step that ran a command differs. A park that
        // lost or changed an output would show here as "output differs" of final, or of the step it lost
        assertThat(diff.code()).as(diff.describe()).isEqualTo(11);
        assertThat(diff.out()).contains("the inputs differ");
        for (String step : List.of("gather", "child", "child > add", "route", "final")) {
            assertThat(diff.out()).as(diff.describe())
                    .containsPattern("(?m)^  " + step + " +COMPLETED +same output$");
        }
        assertThat(diff.out()).as(diff.describe()).doesNotContainPattern("(?m)^  step (gather|child|route|final)");
    }

    /**
     * A failure inside each holder, each with {@code on_failure: continue}, and what the holder makes of it
     * (docs/cli.md, "When a step fails"): the run gets to its last step and completes, since none of the failures
     * is a {@code checked_command} of the run itself.
     */
    @Test
    void aFailureInsideEachHolder_isWhatTheHolderSays() {
        Result run = traceworks.run("combo-failures");

        // Exit 0 and COMPLETED: a failed step with on_failure: continue does not change the status, and the failed
        // checked_command is the child's, not the run's
        assertThat(run.code()).as(run.describe()).isZero();
        assertThat(run.status()).isEqualTo("COMPLETED");
        RunFolder folder = traceworks.folder(run);

        // switch: a failed case block fails the switch, and the case does not go on to its next block
        assertThat(folder.stepStatus("sw")).isEqualTo("FAILED");
        assertThat(folder.output("sw")).isEqualTo("before the fall");
        assertThat(folder.ofStep("step.command", folder.stepIndex("sw"))).as("the second case block did not run")
                .hasSize(1);

        // sub-workflow: the child stops at its failed step; the parent's step fails with the child's last output
        // and the child's error
        assertThat(folder.stepStatus("child_fails")).isEqualTo("FAILED");
        JsonNode failedChild = folder.metadata("child_fails");
        assertThat(failedChild.path("subRunStatus").asText()).isEqualTo("FAILED");
        String late = failedChild.path("subRunId").asText();
        assertThat(folder.stepNamesOf(late)).as("the step after the failed one did not start")
                .containsExactly("good", "bad");
        assertThat(folder.eventsOf(late, "step.finished")).extracting(e -> e.path("status").asText())
                .containsExactly("COMPLETED", "FAILED");
        assertThat(folder.eventsOf(late, "run.finished").getFirst().path("status").asText()).isEqualTo("FAILED");
        assertThat(folder.output("child_fails")).startsWith("bad step")
                .containsPattern("Error: Step \\d+ \\(shell\\) failed: bad step");

        // foreach of children: a failed item does not stop the loop; the step fails after its last item and names
        // the item that failed
        assertThat(folder.stepStatus("each_child")).isEqualTo("FAILED");
        assertThat(folder.output("each_child")).contains("[ok-1]: did ok-1", "[bad]: broke on bad",
                "[ok-2]: did ok-2");
        List<JsonNode> items = folder.ofStep("foreach.item.finished", folder.stepIndex("each_child"));
        assertThat(items).extracting(e -> e.path("status").asText())
                .containsExactly("COMPLETED", "FAILED", "COMPLETED");
        assertThat(items).extracting(e -> e.path("sub_run").asText()).doesNotHaveDuplicates()
                .doesNotContain("", "null", late);
        assertThat(folder.metadata("each_child").path("failedItems")).extracting(JsonNode::asText)
                .containsExactly("bad");

        // while: a failed pass ends the loop and fails the step; the limit of four passes is not reached
        assertThat(folder.stepStatus("loop_fails")).isEqualTo("FAILED");
        assertThat(folder.output("loop_fails")).isEqualTo("[iteration 1]: pass 1\n[iteration 2]: pass 2");
        assertThat(folder.metadata("loop_fails").path("iterationCount").asInt()).isEqualTo(2);

        // retry: an attempt that timed out is retried like any failed one; the step is the last attempt's
        assertThat(folder.stepStatus("times_out")).isEqualTo("FAILED");
        assertThat(folder.output("times_out")).contains("waiting", "Command timed out after 2 seconds");
        assertThat(folder.metadata("times_out").path("attempts").asInt()).isEqualTo(2);
        assertThat(folder.metadata("times_out").path("maxAttempts").asInt()).isEqualTo(2);
        assertThat(folder.ofStep("step.command.finished", folder.stepIndex("times_out")))
                .extracting(e -> e.path("timed_out").asBoolean()).containsExactly(true, true);
        assertThat(folder.output("the_end")).as("both attempts started their command").isEqualTo("attempts: 2");

        // A child whose checked_command failed: the child completed with errors, the step that ran it completed,
        // and the RED gate is the child's. A copied marker that counted would make the run's gate RED
        assertThat(folder.stepStatus("red_child")).isEqualTo("COMPLETED");
        JsonNode redChild = folder.metadata("red_child");
        assertThat(redChild.path("subRunStatus").asText()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(redChild.path("subRunWarning").asText()).contains("COMPLETED_WITH_ERRORS");
        String red = redChild.path("subRunId").asText();
        assertThat(folder.output("red_child")).contains("gate_status: RED", "compiling");
        assertThat(folder.eventsOf(red, "gate.reported")).extracting(e -> e.path("status").asText())
                .containsExactly("RED");
        assertThat(folder.eventsOf(red, "run.finished").getFirst().path("gate").path("status").asText())
                .isEqualTo("RED");
        assertThat(folder.ownEvents("gate.reported")).as("no gate of the run itself").isEmpty();
        assertThat(folder.only("run.finished").path("gate").path("status").asText("")).isEmpty();
        assertThat(run.out()).doesNotContain("RED (");

        // show: each step with the status its holder gave it, each child run with its own, and no gate
        Result show = traceworks.exec("show", folder.id());
        assertThat(show.code()).as(show.describe()).isZero();
        assertThat(show.out()).contains("Status    COMPLETED");
        for (String step : List.of("sw +\\[switch\\] +FAILED", "child_fails +\\[sub-workflow\\] +FAILED",
                "each_child +\\[foreach\\] +FAILED", "loop_fails +\\[while\\] +FAILED",
                "times_out +\\[retry\\] +FAILED", "red_child +\\[sub-workflow\\] +COMPLETED",
                "the_end +\\[shell\\] +COMPLETED")) {
            assertThat(show.out()).as(show.describe()).containsPattern("(?m)^  \\d+ +" + step + " ");
        }
        // ... under each step the child run it started, with the child's own status, its gate and its steps
        assertThat(show.out()).as(show.describe())
                .containsPattern("(?m)^      child run " + late.substring(0, 8) + " FAILED$")
                .containsPattern("(?m)^        \\d+ +good +\\[shell\\] +COMPLETED ")
                .containsPattern("(?m)^        \\d+ +bad +\\[shell\\] +FAILED ")
                .containsPattern("(?m)^      item 2/3  child run [0-9a-f]{8} FAILED$")
                .containsPattern("(?m)^        \\d+ +work +\\[shell\\] +FAILED ")
                .containsPattern("(?m)^      child run " + red.substring(0, 8)
                        + " COMPLETED_WITH_ERRORS  gate RED \\(build\\)$")
                .containsPattern("(?m)^      case fast: sw_fails \\[shell\\], sw_never \\[shell\\]$");
        assertThat(show.out()).doesNotContainPattern("(?m)^Gate ");
    }
}
