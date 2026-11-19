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
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code watch} and {@code show} without a run: they take the one run that is running now. Runs are real host runs in
 * this process, each held in its step until the test lets it go, so "running" is the record's own answer.
 */
class RunningRunTest {

    @TempDir
    Path temp;
    private Path home;
    private Path work;
    private final List<CompletableFuture<Result>> started = new ArrayList<>();
    private final List<Path> gates = new ArrayList<>();

    private record Result(int code, String out, String err) {
    }

    @BeforeEach
    void directories() throws IOException {
        home = Files.createDirectories(temp.resolve("home"));
        work = Files.createDirectories(temp.resolve("work"));
    }

    /** Lets every held run finish, so no thread outlives the test. */
    @AfterEach
    void release() throws Exception {
        for (Path gate : gates) {
            Files.writeString(gate, "go");
        }
        for (CompletableFuture<Result> run : started) {
            run.get(60, TimeUnit.SECONDS);
        }
    }

    private Result exec(String... args) {
        return exec(new ByteArrayOutputStream(), args);
    }

    private Result exec(ByteArrayOutputStream out, String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--home");
        all.add(home.toString());
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(all.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Map.of("TRACEWORKS_ACTOR", "tester"), work,
                null, RuntimeSource.NONE);
        return new Result(code, traceworks.cli.Unbroken.of(out.toString(StandardCharsets.UTF_8)),
                traceworks.cli.Unbroken.of(err.toString(StandardCharsets.UTF_8)));
    }

    /** A package whose one step waits until {@code gate} exists. */
    private Path heldPackage(String name, Path gate) throws IOException {
        Path dir = Files.createDirectories(temp.resolve(name));
        Files.writeString(dir.resolve("workflow.json"), "{\"id\":\"" + name + "\",\"name\":\"" + name + " flow\","
                + "\"blocks\":[{\"type\":\"shell\",\"name\":\"wait\",\"config\":{\"command\":"
                + "\"while [ ! -f '" + gate + "' ]; do sleep 0.05; done\"}}]}");
        return dir;
    }

    /** Starts a run that stays RUNNING until the test ends or opens its gate; returns the gate. */
    private Path startHeld(String name) throws IOException {
        Path gate = temp.resolve(name + ".go");
        gates.add(gate);
        Path pkg = heldPackage(name, gate);
        started.add(OwnThread.start("run of " + name,
                () -> exec("run", pkg.toString(), "--allow-host-execution", "--no-input")));
        return gate;
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        await(condition, () -> what);
    }

    private static void await(BooleanSupplier condition, java.util.function.Supplier<String> what)
            throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= end) {
                throw new AssertionError("waited 30 s for " + what.get());
            }
            Thread.sleep(20);
        }
    }

    private void awaitRunning(int count) throws InterruptedException {
        await(() -> exec("runs", "--status", "RUNNING").out().lines().count() == count, () -> count
                + " running runs; runs: " + exec("runs").out() + "; ended: " + started.stream()
                .filter(CompletableFuture::isDone).map(CompletableFuture::join).toList() + "; "
                + OwnThread.threads());
    }

    @Test
    void noRunning_saysSoAndPointsToRuns() throws IOException {
        Path done = heldPackage("done", temp.resolve("open"));
        Files.writeString(temp.resolve("open"), "go");
        Result ran = exec("run", done.toString(), "--allow-host-execution", "--no-input");
        assertThat(ran.code()).as(ran.err()).isZero();

        Result watch = exec("watch");
        Result show = exec("show");

        assertThat(watch.code()).isEqualTo(2);
        assertThat(watch.err()).contains("no run is running now, so 'watch' needs <run>; 'traceworks runs' lists"
                + " the runs");
        assertThat(show.code()).isEqualTo(2);
        assertThat(show.err()).contains("so 'show' needs <run>");
    }

    @Test
    void oneRunning_showAndWatchTakeIt() throws Exception {
        Path gate = startHeld("held");
        awaitRunning(1);
        String id = exec("runs").out().substring(0, 8);

        Result show = exec("show");

        assertThat(show.code()).as(show.err()).isZero();
        assertThat(show.out()).startsWith("Run       " + id).contains("Status    RUNNING", "held flow");

        ByteArrayOutputStream watched = new ByteArrayOutputStream();
        CompletableFuture<Result> watch = OwnThread.start("watch", () -> exec(watched, "watch"));
        await(() -> watched.toString(StandardCharsets.UTF_8).contains("(run " + id + ")"),
                () -> "watch to follow the run; " + OwnThread.threads());
        Files.writeString(gate, "go");
        Result followed = watch.get(60, TimeUnit.SECONDS);

        assertThat(followed.code()).as(followed.err()).isZero();
        assertThat(followed.out()).contains("run finished: COMPLETED");
    }

    @Test
    void severalRunning_nameThemAndGuessNothing() throws Exception {
        startHeld("first");
        startHeld("second");
        awaitRunning(2);
        List<String> ids = exec("runs").out().lines().map(line -> line.substring(0, 8)).toList();

        Result watch = exec("watch");
        Result show = exec("show");

        assertThat(watch.code()).isEqualTo(2);
        assertThat(watch.err()).contains("2 runs are running now, so 'watch' needs one of them: ",
                ids.get(0), ids.get(1), "(first flow)", "(second flow)");
        assertThat(show.code()).isEqualTo(2);
        assertThat(show.out()).isEmpty();
    }

    /** A run that is named is taken as before, whether or not other runs are running. */
    @Test
    void aNamedRun_isTakenAsBefore() throws Exception {
        startHeld("other");
        startHeld("another");
        awaitRunning(2);
        String id = exec("runs").out().substring(0, 8);

        Result show = exec("show", id);

        assertThat(show.code()).as(show.err()).isZero();
        assertThat(show.out()).startsWith("Run       " + id);
    }
}
