package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What stops a command before it ends by itself (packages {@code timeout}, {@code output-limit} and
 * {@code output-limit-setting}): its {@code timeout_seconds}, and a standard output beyond the output limit, 16 MiB
 * unless the operator sets {@code output_limit_mib} (docs/cli.md "Output limit"). Either way the step fails, the
 * record says why, and the container is removed (checked after each test).
 */
class StopsIT extends IntegrationTest {

    @Test
    void aStepTimeout_stopsTheStep() {
        long start = System.nanoTime();
        Result run = traceworks.run("timeout");
        long seconds = (System.nanoTime() - start) / 1_000_000_000L;

        assertThat(run.code()).as(run.describe()).isEqualTo(3);
        assertThat(seconds).as("the step sleeps 120 s, its timeout is 3 s").isLessThan(60);
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.stepStatus("sleepy")).isEqualTo("FAILED");
        assertThat(folder.output("sleepy")).contains("going to sleep", "Command timed out after 3 seconds")
                .doesNotContain("woke up");
        JsonNode finished = folder.ofStep("step.command.finished", folder.stepIndex("sleepy")).getFirst();
        assertThat(finished.path("timed_out").asBoolean()).isTrue();
        assertThat(finished.path("exit_code").asInt()).isEqualTo(124);
        assertThat(finished.path("duration_ms").asLong()).isBetween(3_000L, 60_000L);
    }

    /** The listing of a {@code foreach} that runs out of time is no list: its timeout line must not become an item. */
    @Test
    void anItemsCommandTimeout_failsTheForeach_beforeTheFirstItem() {
        long start = System.nanoTime();
        Result run = traceworks.run("items-timeout");
        long seconds = (System.nanoTime() - start) / 1_000_000_000L;

        assertThat(run.code()).as(run.describe()).isEqualTo(3);
        assertThat(seconds).as("the listing sleeps 120 s, its timeout is 3 s").isLessThan(60);
        RunFolder folder = traceworks.folder(run);
        int step = folder.stepIndex("slow_listing");
        assertThat(folder.stepStatus("slow_listing")).isEqualTo("FAILED");
        assertThat(folder.output("slow_listing")).isEqualTo("items_command failed: it timed out after 3 seconds");
        assertThat(folder.ofStep("foreach.item.started", step)).isEmpty();
        assertThat(folder.ofStep("step.command", step)).as("only the listing ran").hasSize(1);
        assertThat(folder.metadata("slow_listing").path("totalItems").asInt()).isZero();
        assertThat(folder.only("run.finished").path("status").asText()).isEqualTo("FAILED");
    }

    /** A shell condition that runs out of time is no false test: the block fails and no branch runs. */
    @Test
    void aConditionalConditionTimeout_failsTheBlock_andRunsNoBranch() {
        long start = System.nanoTime();
        Result run = traceworks.run("conditional-timeout");
        long seconds = (System.nanoTime() - start) / 1_000_000_000L;

        assertThat(run.code()).as(run.describe()).isEqualTo(3);
        assertThat(seconds).as("the condition sleeps 120 s, its timeout is 3 s").isLessThan(60);
        RunFolder folder = traceworks.folder(run);
        int step = folder.stepIndex("slow_condition");
        assertThat(folder.stepStatus("slow_condition")).isEqualTo("FAILED");
        assertThat(folder.output("slow_condition")).isEqualTo("The condition failed: it timed out after 3 seconds");
        assertThat(folder.ofStep("step.command", step)).as("only the condition ran").hasSize(1);
        assertThat(folder.only("run.finished").path("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void aWhileConditionTimeout_failsTheBlock_afterTheIterationsSoFar() {
        long start = System.nanoTime();
        Result run = traceworks.run("while-timeout");
        long seconds = (System.nanoTime() - start) / 1_000_000_000L;

        assertThat(run.code()).as(run.describe()).isEqualTo(3);
        assertThat(seconds).as("the condition sleeps 120 s, its timeout is 3 s").isLessThan(60);
        RunFolder folder = traceworks.folder(run);
        int step = folder.stepIndex("slow_condition");
        assertThat(folder.stepStatus("slow_condition")).isEqualTo("FAILED");
        assertThat(folder.output("slow_condition")).isEqualTo(
                "[iteration 1]: pass\n[condition] The condition failed: it timed out after 3 seconds");
        assertThat(folder.ofStep("step.command", step)).as("two conditions and one iteration").hasSize(3);
        assertThat(folder.only("run.finished").path("status").asText()).isEqualTo("FAILED");
    }

    /**
     * A cancel that comes while a {@code retry} waits between two attempts ends the wait at once: no further attempt
     * starts, and the run ends cancelled long before the wait of 120 seconds would.
     */
    @Test
    void aCancelDuringTheWaitOfARetry_endsTheRun_andStartsNoFurtherAttempt() throws Exception {
        CompletableFuture<Result> running = traceworks.runBeside("retry-cancel");
        RunFolder folder = awaitRunThatRecorded("step.command.finished");

        long start = System.nanoTime();
        Result cancel = traceworks.exec("cancel", folder.id());
        Result run = running.get(90, TimeUnit.SECONDS);
        long seconds = (System.nanoTime() - start) / 1_000_000_000L;

        assertThat(cancel.code()).as(cancel.describe()).isZero();
        assertThat(run.code()).as(run.describe()).isEqualTo(4);
        assertThat(seconds).as("the wait is 120 s").isLessThan(30);
        assertThat(folder.ownEvents("step.command")).as("only the first attempt ran").hasSize(1);
        assertThat(folder.only("run.finished").path("status").asText()).isEqualTo("CANCELLED");
    }

    /** The folder of the one run in the home, once its events hold a line of {@code type}; within 60 seconds. */
    private RunFolder awaitRunThatRecorded(String type) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        Path runs = traceworks.home().resolve("runs");
        while (System.nanoTime() < deadline) {
            if (Files.isDirectory(runs)) {
                try (Stream<Path> found = Files.walk(runs, 2)) {
                    for (Path dir : found.filter(path -> Files.isRegularFile(path.resolve("events.jsonl"))).toList()) {
                        if (Files.readString(dir.resolve("events.jsonl")).contains("\"type\":\"" + type + "\"")) {
                            return RunFolder.find(traceworks.home(), dir.getFileName().toString());
                        }
                    }
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError("No run recorded " + type + " within 60 s");
    }

    @Test
    void anOutputBeyond16MiB_stopsTheStep_withExit153() {
        Result run = traceworks.run("output-limit");

        assertThat(run.code()).as(run.describe()).isEqualTo(3);
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.stepStatus("flood")).isEqualTo("FAILED");
        JsonNode finished = folder.ofStep("step.command.finished", folder.stepIndex("flood")).getFirst();
        assertThat(finished.path("exit_code").asInt()).isEqualTo(153);
        assertThat(finished.path("timed_out").asBoolean()).isFalse();
        assertThat(finished.path("truncated")).extracting(JsonNode::asText).containsExactly("stdout");
        JsonNode output = folder.ownEvents("step.output").getFirst().path("output");
        assertThat(output.path("size").asLong()).as("the first 16 MiB and a note")
                .isBetween(16L * 1024 * 1024, 16L * 1024 * 1024 + 4096);
        assertThat(folder.output("flood"))
                .contains("The command's standard output exceeded 16 MiB; Traceworks stopped it and kept the first"
                        + " 16 MiB");
        assertThat(folder.metadata("flood").path("gateMarkers").asBoolean(true)).isFalse();
        assertThat(folder.metadata("flood").path("exitCode").asInt()).isEqualTo(153);
    }

    /**
     * {@code output_limit_mib} in {@code traceworks.json}: a step that prints 2 MiB and ends completes under the
     * default limit, and fails with exit code 153 under a limit of 1 MiB, with a note that names it; {@code run.started}
     * records the limit of each run.
     */
    @Test
    void theOperatorsOutputLimit_stopsAStep_thatTheDefaultLetsEnd() {
        Result whole = traceworks.run("output-limit-setting");
        assertThat(whole.code()).as(whole.describe()).isZero();
        RunFolder first = traceworks.folder(whole);
        assertThat(first.only("run.started").path("output_limit_mib").asInt()).isEqualTo(16);
        assertThat(first.output("two_mib").length()).as("2 MiB, its last line feed trimmed")
                .isEqualTo(2 * 1024 * 1024 - 1);

        traceworks.configure(Map.of("output_limit_mib", 1));
        Result run = traceworks.run("output-limit-setting");

        assertThat(run.code()).as(run.describe()).isEqualTo(3);
        assertThat(run.out()).contains("(stdout beyond 1 MiB)");
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.only("run.started").path("output_limit_mib").asInt()).isEqualTo(1);
        assertThat(folder.stepStatus("two_mib")).isEqualTo("FAILED");
        JsonNode finished = folder.ofStep("step.command.finished", folder.stepIndex("two_mib")).getFirst();
        assertThat(finished.path("exit_code").asInt()).isEqualTo(153);
        assertThat(finished.path("truncated")).extracting(JsonNode::asText).containsExactly("stdout");
        JsonNode output = folder.ownEvents("step.output").getFirst().path("output");
        assertThat(output.path("size").asLong()).as("the first 1 MiB and a note")
                .isBetween(1024L * 1024, 1024L * 1024 + 4096);
        assertThat(folder.output("two_mib"))
                .contains("The command's standard output exceeded 1 MiB; Traceworks stopped it and kept the first"
                        + " 1 MiB")
                .doesNotContain("16 MiB");
        assertThat(folder.metadata("two_mib").path("exitCode").asInt()).isEqualTo(153);
    }
}
