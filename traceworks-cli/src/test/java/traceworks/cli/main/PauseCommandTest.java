package traceworks.cli.main;

import traceworks.cli.RuntimeSource;
import traceworks.core.testing.OwnThread;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code traceworks pause}: a running run of another command gets the pause through its inbox, finishes what is in
 * flight, parks with exit 5 and says how to go on; {@code resume} then runs only what did not finish. The runs are
 * real host runs in this process, each held in a step until the test opens its gate.
 */
class PauseCommandTest {

    private static final Pattern RUN_ID = Pattern.compile("(?m)^run ([0-9a-f-]{36}) [A-Z_]+$");

    @TempDir
    Path temp;
    private Path home;
    private Path work;
    private Path gate;
    private CompletableFuture<Result> running;

    private record Result(int code, String out, String err) {
    }

    @BeforeEach
    void directories() throws IOException {
        home = Files.createDirectories(temp.resolve("home"));
        work = Files.createDirectories(temp.resolve("work"));
        gate = temp.resolve("go");
    }

    /** Lets a held run finish, so no thread outlives the test. */
    @AfterEach
    void release() throws Exception {
        Files.writeString(gate, "go");
        if (running != null) {
            running.get(60, TimeUnit.SECONDS);
        }
    }

    private Result exec(String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--home");
        all.add(home.toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(all.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Map.of("TRACEWORKS_ACTOR", "tester"), work,
                null, RuntimeSource.NONE);
        return new Result(code, traceworks.cli.Unbroken.of(out.toString(StandardCharsets.UTF_8)),
                traceworks.cli.Unbroken.of(err.toString(StandardCharsets.UTF_8)));
    }

    private Path pkg(String name, String blocks) throws IOException {
        Path dir = Files.createDirectories(temp.resolve(name));
        Files.writeString(dir.resolve("workflow.json"),
                "{\"id\":\"" + name + "\",\"name\":\"" + name + " flow\",\"blocks\":[" + blocks + "]}");
        return dir;
    }

    /** Starts the package and waits until its run is RUNNING. */
    private void start(Path pkg) throws InterruptedException {
        running = OwnThread.start("run of " + pkg.getFileName(),
                () -> exec("run", pkg.toString(), "--allow-host-execution", "--no-input"));
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (exec("runs", "--status", "RUNNING").out().lines().count() != 1) {
            assertThat(System.nanoTime()).as("waited 30 s for the run to start; " + OwnThread.threads()).isLessThan(end);
            Thread.sleep(20);
        }
    }

    private void awaitFile(Path file) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(file)) {
            assertThat(System.nanoTime()).as("waited 30 s for " + file).isLessThan(end);
            Thread.sleep(20);
        }
    }

    private static String idOf(Result result) {
        Matcher m = RUN_ID.matcher(result.out());
        assertThat(m.find()).as(result.out() + result.err()).isTrue();
        return m.group(1);
    }

    @Test
    void pause_withoutARun_takesTheOneThatRuns_andTheRunParksAfterItsStep() throws Exception {
        Path inFlight = temp.resolve("wait.started");
        start(pkg("steps", """
                {"type":"shell","name":"wait","config":{"command":"touch '%s'; while [ ! -f '%s' ]; do sleep 0.05; done"}},
                {"type":"shell","name":"after","config":{"command":"echo after >> '%s'"}}"""
                .formatted(inFlight, gate, temp.resolve("after.txt"))));
        // A run is RUNNING from its first event on, before its first step: a pause that came then would park it at
        // once, and the pause command would say PARKED. This case is about a step in flight
        awaitFile(inFlight);

        Result pause = exec("pause");

        assertThat(pause.code()).as(pause.out() + pause.err()).isZero();
        assertThat(pause.out()).contains("pause requested: run ", "finishes the step or the loop item in flight",
                "go on later with:", "traceworks resume '", "--allow-host-execution");

        Files.writeString(gate, "go");
        Result run = running.get(60, TimeUnit.SECONDS);
        String id = idOf(run);

        assertThat(pause.out()).as("the hint names the run").contains("  traceworks resume '" + id
                + "' --allow-host-execution");
        assertThat(run.code()).as(run.out() + run.err()).isEqualTo(5);
        assertThat(run.out()).contains("pause requested by tester", "run parked: paused before the next step",
                "run " + id + " PARKED", "paused before the next step: every step that started is finished.",
                "go on with:", "traceworks resume '" + id + "' --allow-host-execution")
                .doesNotContain("waiting for an answer");
        assertThat(temp.resolve("after.txt")).doesNotExist();
        Result show = exec("show", id);
        assertThat(show.out()).contains("Status    PARKED", "requested a pause", "paused before the next step");
        assertThat(exec("runs", "--status", "PARKED").out()).contains(id.substring(0, 8));

        Result resumed = exec("resume", id, "--allow-host-execution");

        assertThat(resumed.code()).as(resumed.out() + resumed.err()).isZero();
        assertThat(temp.resolve("after.txt")).hasContent("after\n");
        assertThat(exec("verify", id).code()).isZero();
    }

    /** In a loop the run parks after the item in flight; the resume takes the finished items over. */
    @Test
    void pause_inALoop_parksAfterTheItem_andTheResumeRunsEachItemOnce() throws Exception {
        Path counter = temp.resolve("counter.txt");
        Path second = temp.resolve("second.started");
        start(pkg("loop", """
                {"type":"foreach","name":"each","config":{"execution_mode":"shell","items":["a","b","c"],
                 "command":"echo {{ item }} >> '%s'; if [ {{ item }} = b ]; then touch '%s'; while [ ! -f '%s' ]; do sleep 0.05; done; fi; echo did {{ item }}"}}"""
                .formatted(counter, second, gate).replace("\n", " ")));
        awaitFile(second);
        String id = exec("runs", "--status", "RUNNING").out().substring(0, 8);

        Result pause = exec("pause", id);
        assertThat(pause.code()).as(pause.out() + pause.err()).isZero();
        Files.writeString(gate, "go");
        Result run = running.get(60, TimeUnit.SECONDS);

        assertThat(run.code()).as(run.out() + run.err()).isEqualTo(5);
        assertThat(run.out()).contains("run parked: paused inside the loop of step 0",
                "paused inside the loop of step 0: 2 of 3 items are finished, and the resume runs only the others.");
        assertThat(counter).hasContent("a\nb\n");
        assertThat(exec("show", idOf(run)).out()).containsPattern("each +\\[foreach\\] +RUNNING .*\\(paused\\)");

        Result resumed = exec("resume", idOf(run), "--allow-host-execution");

        assertThat(resumed.code()).as(resumed.out() + resumed.err()).isZero();
        assertThat(counter).as("every item's command ran exactly once").hasContent("a\nb\nc\n");
        assertThat(resumed.out()).contains("item 1/3 COMPLETED (taken over from event ",
                "item 2/3 COMPLETED (taken over from event ", "item 3/3 started: c");
        Result show = exec("show", idOf(run), "--outputs");
        assertThat(show.out()).contains("[a]: did a", "[b]: did b", "[c]: did c");
    }

    @Test
    void pause_ofARunThatDoesNotRun_isRefused() throws Exception {
        Files.writeString(gate, "go");
        Result done = exec("run", pkg("done", """
                {"type":"shell","name":"say","config":{"command":"echo hello"}}""").toString(),
                "--allow-host-execution", "--no-input");

        Result pause = exec("pause", idOf(done));
        Result none = exec("pause");

        assertThat(pause.code()).isEqualTo(6);
        assertThat(pause.err()).contains("is not running (COMPLETED): a finished run cannot be paused");
        assertThat(none.code()).isEqualTo(2);
        assertThat(none.err()).contains("no run is running now, so 'pause' needs <run>");
    }

    @Test
    void help_namesPause() {
        assertThat(exec("--help").out()).containsPattern("(?m)^  pause ");
        assertThat(exec("pause", "--help").out()).contains("pause", "[<run>]");
    }
}
