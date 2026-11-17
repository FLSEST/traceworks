package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code approval} (package {@code approval}): without a terminal the question parks the run (exit 5);
 * {@code traceworks approve} records the answer and {@code traceworks resume} goes on with it. A rejection fails the
 * run ({@code on_no: fail}), and {@code traceworks cancel} ends a parked run.
 */
class ApprovalIT extends IntegrationTest {

    @Test
    void parks_thenApproveAndResume_completeTheRun() {
        Result run = traceworks.run("approval");

        assertThat(run.code()).as(run.describe()).isEqualTo(5);
        assertThat(run.status()).isEqualTo("PARKED");
        String id = run.runId();
        assertThat(run.out()).contains("traceworks approve '" + id + "' --answer 'Yes'",
                "traceworks resume '" + id + "'");
        RunFolder folder = traceworks.folder(id);
        JsonNode requested = folder.only("approval.requested");
        assertThat(folder.blob(requested.path("question"))).isEqualTo("Release prepared?");
        assertThat(requested.path("options")).extracting(JsonNode::asText).containsExactly("Yes", "No");
        assertThat(requested.path("reject_options")).extracting(JsonNode::asText).containsExactly("No");
        assertThat(requested.path("parks").asBoolean()).isTrue();
        assertThat(folder.only("run.parked").path("kind").asText()).isEqualTo("approval");
        assertThat(folder.hasManifest()).as("a parked run has no manifest").isFalse();
        assertThat(folder.hasHtml()).as("run.html is written when the run parks").isTrue();

        Result approve = traceworks.exec("approve", id, "--answer", "Yes");
        assertThat(approve.code()).as(approve.describe()).isZero();
        JsonNode answered = folder.only("approval.answered");
        assertThat(answered.path("source").asText()).isEqualTo("cli");
        assertThat(answered.path("actor").asText()).isEqualTo(Traceworks.ACTOR);
        assertThat(folder.blob(answered.path("answer"))).isEqualTo("Yes");

        Result resume = traceworks.exec("resume", id, "--no-input");
        assertThat(resume.code()).as(resume.describe()).isZero();
        assertThat(resume.status()).isEqualTo("COMPLETED");
        assertThat(folder.output("released")).isEqualTo("released after Yes");
        JsonNode resumed = folder.only("run.resumed");
        assertThat(resumed.path("actor").asText()).isEqualTo(Traceworks.ACTOR);
        assertThat(resumed.path("rerun_step").asInt()).isEqualTo(folder.stepIndex("release"));
        assertThat(resumed.path("execution").asText()).isEqualTo("container");
        assertThat(resumed.path("package_changed").asBoolean()).isFalse();
        List<JsonNode> answers = folder.ownEvents("approval.answered");
        assertThat(answers).extracting(e -> e.path("source").asText()).containsExactly("cli", "resume");
        assertThat(resumed.path("answer").asInt()).isEqualTo(answers.getFirst().path("seq").asInt());
        assertThat(folder.manifest().path("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void aRejection_failsTheRun_withTheComment() {
        String id = traceworks.run("approval").runId();

        // An agent that answers under the operator's user names itself, and the record says who answered
        Result approve = traceworks.exec("approve", id, "--answer", "No", "--comment", "not this week", "--agent",
                "it-agent");
        assertThat(approve.code()).as(approve.describe()).isZero();
        assertThat(approve.out()).contains("is recorded through the agent it-agent;");
        Result resume = traceworks.exec("resume", id, "--no-input");

        assertThat(resume.code()).as(resume.describe()).isEqualTo(3);
        assertThat(resume.status()).isEqualTo("FAILED");
        assertThat(resume.out()).contains("Approval denied: No");
        RunFolder folder = traceworks.folder(id);
        assertThat(folder.stepStatus("release")).isEqualTo("FAILED");
        assertThat(folder.ownEvents("step.finished")).extracting(e -> e.path("name").asText())
                .doesNotContain("released");
        assertThat(folder.blob(folder.ownEvents("approval.answered").getFirst().path("comment")))
                .isEqualTo("not this week");
        assertThat(folder.ownEvents("approval.answered")).as("the resumed step takes the answer over with its agent")
                .extracting(e -> e.path("agent").asText()).containsExactly("it-agent", "it-agent");
        assertThat(resume.out()).contains("through the agent it-agent");
        assertThat(traceworks.exec("show", id).out()).contains("via cli through the agent it-agent");
        assertThat(traceworks.exec("verify", id).code()).isZero();
        JsonNode finished = folder.only("run.finished");
        assertThat(finished.path("status").asText()).isEqualTo("FAILED");
        assertThat(folder.blob(finished.path("error"))).contains("Approval denied: No");
        assertThat(folder.manifest().path("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void cancel_endsAParkedRun_thatCannotBeResumedThen() {
        String id = traceworks.run("approval").runId();

        Result cancel = traceworks.exec("cancel", id);

        assertThat(cancel.code()).as(cancel.describe()).isZero();
        assertThat(cancel.status()).isEqualTo("CANCELLED");
        RunFolder folder = traceworks.folder(id);
        // docs/events.md, run.parked: a cancel writes run.cancel_requested, then run.finished, and nothing else
        assertThat(folder.types()).endsWith("approval.requested", "run.parked", "run.cancel_requested",
                "run.finished");
        assertThat(folder.only("run.cancel_requested").path("actor").asText()).isEqualTo(Traceworks.ACTOR);
        assertThat(folder.only("run.finished").path("status").asText()).isEqualTo("CANCELLED");
        assertThat(folder.manifest().path("status").asText()).as("a cancelled parked run gets a manifest")
                .isEqualTo("CANCELLED");

        Result resume = traceworks.exec("resume", id, "--no-input");
        assertThat(resume.code()).as(resume.describe()).isEqualTo(6);
        assertThat(resume.err()).contains("cannot be resumed");
        Result show = traceworks.exec("show", id);
        assertThat(show.code()).as(show.describe()).isZero();
        assertThat(show.out()).containsPattern("release +\\[approval\\] +CANCELLED").contains("requested a cancel");
    }
}
