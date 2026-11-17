package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mark of an output that was not read to its end ({@code output_cut} in {@code step.command.finished},
 * docs/events.md), through the jar (package {@code output-cut}): a command that ends while a job it started still
 * writes.
 *
 * <p><b>In a container the cut cannot happen that way.</b> What Traceworks reads is the output of the client
 * ({@code docker run}, {@code podman run}), and the client alone holds those two pipes: the processes in the container
 * write to pipes of the runtime, which the client copies. The container ends with its command, everything the command
 * started ends with it, the client ends after the container's streams, and it starts nothing that outlives it. So the
 * first test asserts what does happen: the step does not wait for the bound, and the record names no cut. A client
 * that did leave a process behind with its pipes would be marked, which the unit tests show with a client that does
 * ({@code ContainerProcessTest}).
 *
 * <p><b>On the host it does</b>, and the second test runs the same package there: with a client on the {@code PATH}
 * that answers nothing, no container runtime works, and with the operator's consent the run goes to the host
 * (docs/cli.md, "Host execution"). It starts no container.
 */
class OutputCutIT extends IntegrationTest {

    @Test
    void aJobTheCommandLeavesBehind_endsWithTheContainer_soNoOutputIsCut() {
        Result run = traceworks.run("output-cut");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.only("run.started").path("execution").asText()).isEqualTo("container");
        assertThat(folder.only("run.started").path("records")).extracting(JsonNode::asText).contains("output_cut");
        List<JsonNode> ends = folder.events("step.command.finished");
        assertThat(ends).hasSize(2);
        assertThat(ends).as("every output was read to its end").noneMatch(end -> end.has("output_cut"));
        assertThat(ends.getFirst().path("duration_ms").asLong()).as("the step did not wait for the bound of 2 s")
                .isLessThan(30_000);
        assertThat(folder.output("serve")).startsWith("early");
        assertThat(folder.output("plain")).isEqualTo("fine");
        Result show = traceworks.exec("show", run.runId());
        assertThat(show.out()).doesNotContain("output CUT");
    }

    @Test
    void onTheHost_theJobHoldsTheOutputPastTheBound_andTheRecordMarksTheCut() throws Exception {
        // No container runtime works: the client the suite configures is, first on the PATH, one that only fails
        Path bin = Files.createDirectories(traceworks.userHome().resolve("no-runtime"));
        Path client = Files.writeString(bin.resolve(traceworks.environment().runtime()), "#!/bin/sh\nexit 1\n");
        Files.setPosixFilePermissions(client, PosixFilePermissions.fromString("rwx------"));
        traceworks.environmentVariable("PATH", bin + ":" + System.getenv("PATH"));

        Result run = traceworks.run("output-cut", "--allow-host-execution");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.only("run.started").path("execution").asText()).as(run.describe()).isEqualTo("host");
        List<JsonNode> ends = folder.events("step.command.finished");
        assertThat(ends).hasSize(2);
        JsonNode cut = ends.getFirst().path("output_cut");
        assertThat(cut.path("streams")).extracting(JsonNode::asText).containsExactly("stdout");
        assertThat(cut.path("after_ms").asLong()).isEqualTo(5000);
        assertThat(ends.getFirst().path("exit_code").asInt()).as("a mark, not a failure").isZero();
        assertThat(ends.get(1).has("output_cut")).as("an output that ended").isFalse();
        assertThat(folder.stepStatus("serve")).isEqualTo("COMPLETED");
        assertThat(folder.output("serve")).startsWith("early\ntick");
        Result show = traceworks.exec("show", run.runId());
        assertThat(show.out()).contains("Output    1 command whose output was CUT",
                "output CUT     stdout after 5000 ms   <== may be incomplete");
        Result verify = traceworks.exec("verify", run.runId());
        assertThat(verify.code()).as(verify.describe()).isZero();
    }
}
