package traceworks.cli.main;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import traceworks.cli.RuntimeSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code traceworks run <package> --project KEY}: the project a run works on is the operator's word. It is recorded
 * with the run, a key that is none is a usage error, and the run of an audit workflow needs one. Host runs of real
 * shell steps in a temporary home.
 */
class RunProjectCliTest {

    private static final Pattern RUN_ID = Pattern.compile("(?m)^run ([0-9a-f-]{36}) [A-Z_]+$");

    @TempDir
    Path temp;
    private Path home;
    private Path work;

    private record Result(int code, String out, String err) {
    }

    @BeforeEach
    void directories() throws IOException {
        home = Files.createDirectories(temp.resolve("home"));
        work = Files.createDirectories(temp.resolve("work"));
    }

    private Result exec(String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--home");
        all.add(home.toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(all.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Map.of("TRACEWORKS_ACTOR", "tester"), work, null,
                RuntimeSource.NONE);
        return new Result(code, traceworks.cli.Unbroken.of(out.toString(StandardCharsets.UTF_8)),
                traceworks.cli.Unbroken.of(err.toString(StandardCharsets.UTF_8)));
    }

    private Path plain() throws IOException {
        Path dir = Files.createDirectories(temp.resolve("plain"));
        Files.writeString(dir.resolve("workflow.json"), "{\"id\":\"plain\",\"name\":\"Plain\",\"blocks\":["
                + "{\"type\":\"shell\",\"name\":\"say\",\"config\":{\"command\":\"echo hello\"}}]}");
        return dir;
    }

    private static String runId(Result result) {
        Matcher m = RUN_ID.matcher(result.out());
        assertThat(m.find()).as(result.out() + result.err()).isTrue();
        return m.group(1);
    }

    private List<String> entries(String folder) throws IOException {
        if (!Files.exists(home.resolve(folder))) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(home.resolve(folder))) {
            return children.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void aRunWithAProject_recordsIt_andShowNamesIt() throws IOException {
        Result run = exec("run", plain().toString(), "--project", "shop", "--allow-host-execution", "--no-input",
                "--quiet");

        assertThat(run.code()).as(run.out() + run.err()).isZero();
        Result shown = exec("show", runId(run));
        assertThat(shown.out()).contains("Project   shop");
    }

    @Test
    void aRunWithoutAProject_showsNoProject() throws IOException {
        Result run = exec("run", plain().toString(), "--allow-host-execution", "--no-input", "--quiet");

        assertThat(run.code()).as(run.out() + run.err()).isZero();
        assertThat(exec("show", runId(run)).out()).doesNotContain("Project");
    }

    /** A resume takes the project of run.started, and takes no --project. */
    @Test
    void aResume_takesNoProject() throws IOException {
        Path dir = Files.createDirectories(temp.resolve("asking"));
        Files.writeString(dir.resolve("workflow.json"), "{\"id\":\"asking\",\"name\":\"Asking\",\"blocks\":["
                + "{\"type\":\"approval\",\"name\":\"go\",\"config\":{\"question\":\"Go on?\",\"options\":"
                + "[\"Yes\",\"No\"]}}]}");
        Result parked = exec("run", dir.toString(), "--project", "shop", "--allow-host-execution", "--no-input",
                "--quiet");
        assertThat(parked.code()).as(parked.out() + parked.err()).isEqualTo(5);
        String id = runId(parked);
        assertThat(exec("approve", id, "--answer", "Yes").code()).isZero();

        Result withProject = exec("resume", id, "--project", "web", "--allow-host-execution");
        Result resumed = exec("resume", id, "--allow-host-execution");

        assertThat(withProject.code()).isEqualTo(2);
        assertThat(withProject.err()).contains("unknown option --project");
        assertThat(resumed.code()).as(resumed.out() + resumed.err()).isZero();
        assertThat(exec("show", id).out()).contains("Project   shop");
    }

    /** A key that is no project key is a usage error, and no run exists. */
    @Test
    void aKeyThatIsNoProjectKey_isAUsageError_andStartsNoRun() throws IOException {
        for (String bad : List.of("Shop", "a/b", "..", "")) {
            Result run = exec("run", plain().toString(), "--project", bad, "--allow-host-execution", "--no-input",
                    "--quiet");

            assertThat(run.code()).as(bad + ": " + run.out() + run.err()).isEqualTo(2);
            assertThat(run.err()).as(bad).contains("--project").contains("[a-z0-9][a-z0-9._-]{0,127}");
        }
        assertThat(entries("runs")).as("no run exists").isEmpty();
    }
}
