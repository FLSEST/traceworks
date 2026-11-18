package traceworks.cli;

import traceworks.cli.main.Main;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shipped {@code examples/hello} package as a host run, end to end: park, approve, resume, verify, tamper. It needs
 * no container runtime; {@link HelloContainerTest} runs it in containers, as by default.
 */
class HelloExampleTest {

    /** The summary line {@code run <id> STATUS}; a UUID in the checkout path or a step's output is not it. */
    private static final Pattern RUN_ID =
            Pattern.compile("(?m)^run ([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}) [A-Z_]+$");
    /** The module directory is the working directory of Surefire. */
    private static final String PACKAGE = Path.of("../examples/hello").toAbsolutePath().normalize().toString();

    @TempDir
    Path temp;

    private record Result(int code, String out, String err) {
    }

    private Result exec(Path home, Path work, String... args) {
        List<String> all = new java.util.ArrayList<>(List.of(args));
        all.add("--home");
        all.add(home.toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(all.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Map.of("TRACEWORKS_ACTOR", "tester"), work, null,
                RuntimeSource.NONE);
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void parkApproveResumeVerify() throws IOException {
        Path home = Files.createDirectories(temp.resolve("home"));
        Path work = Files.createDirectories(temp.resolve("work"));
        Files.writeString(work.resolve("one.txt"), "1");
        Files.writeString(work.resolve("two.txt"), "2");

        Result run = exec(home, work, "run", PACKAGE, "--no-input", "--allow-host-execution");
        assertThat(run.code()).as(run.out() + run.err()).isEqualTo(5);
        Matcher m = RUN_ID.matcher(run.out());
        assertThat(m.find()).as(run.out()).isTrue();
        String id = m.group(1);
        assertThat(run.out()).contains("Hello, world", "Files in the workspace: 2");

        Result approve = exec(home, work, "approve", id, "--answer", "Yes");
        assertThat(approve.code()).as(approve.out() + approve.err()).isZero();
        Result resume = exec(home, work, "resume", id, "--no-input", "--allow-host-execution");
        assertThat(resume.code()).as(resume.out() + resume.err()).isZero();
        assertThat(resume.out()).contains("Published: Hello, world");
        Result verify = exec(home, work, "verify", id);
        assertThat(verify.code()).as(verify.out() + verify.err()).isZero();

        Path folder;
        try (Stream<Path> days = Files.walk(home.resolve("runs"), 2)) {
            folder = days.filter(p -> p.getFileName().toString().equals(id)).findFirst().orElseThrow();
        }
        List<String> lines = Files.readAllLines(folder.resolve("events.jsonl"));
        assertThat(lines.getFirst()).contains("\"execution\":\"host\"", "\"container_runtime\":null");
        assertThat(lines.stream().filter(l -> l.contains("\"type\":\"step.command\"")))
                .hasSize(3).allSatisfy(l -> assertThat(l).contains("\"host\":true", "\"image\":null"));
        String html = Files.readString(folder.resolve("run.html"));
        assertThat(html).contains("greet", "count", "publish", "done", "chain intact");
        assertThat(html).contains("Hello, world");

        try (Stream<Path> commits = Files.list(home.resolve("ledger/runs"))) {
            assertThat(commits.map(p -> p.getFileName().toString())).containsExactly(id + ".json");
        }

        Path events = folder.resolve("events.jsonl");
        byte[] bytes = Files.readAllBytes(events);
        bytes[bytes.length / 2] ^= 1;
        Files.write(events, bytes);
        Result tampered = exec(home, work, "verify", id);
        assertThat(tampered.code()).as(tampered.out() + tampered.err()).isEqualTo(8);
    }

    /** The first example a reader runs has nothing for {@code check} to note: every shell block has strict mode. */
    @Test
    void check_findsNothing() throws IOException {
        Path home = Files.createDirectories(temp.resolve("home"));
        Path work = Files.createDirectories(temp.resolve("work"));

        Result check = exec(home, work, "check", PACKAGE);

        assertThat(check.code()).as(check.out() + check.err()).isZero();
        assertThat(check.out()).isEqualTo("no findings" + System.lineSeparator());
        assertThat(check.err()).isEmpty();
    }
}
