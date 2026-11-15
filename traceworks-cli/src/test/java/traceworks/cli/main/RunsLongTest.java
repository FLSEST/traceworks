package traceworks.cli.main;

import traceworks.cli.RuntimeSource;
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

import static org.assertj.core.api.Assertions.assertThat;

/** {@code traceworks runs --long}: the plain line of each run, then what tells two runs of a workflow apart. */
class RunsLongTest {

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
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Path pkg(String name, String command) throws IOException {
        Path dir = Files.createDirectories(temp.resolve(name));
        Files.writeString(dir.resolve("workflow.json"), "{\"id\":\"" + name + "\",\"name\":\"" + name
                + " flow\",\"blocks\":[{\"type\":\"shell\",\"name\":\"say\",\"config\":{\"command\":\"" + command
                + "\"}}]}");
        return dir;
    }

    private void run(Path pkg, String... more) {
        List<String> args = new ArrayList<>(List.of("run", pkg.toString(), "--allow-host-execution", "--no-input",
                "--quiet"));
        args.addAll(List.of(more));
        Result result = exec(args.toArray(String[]::new));
        assertThat(result.code()).as(result.out() + result.err()).isZero();
    }

    @Test
    void long_addsGateDurationDigestsAndModels_toThePlainLine() throws IOException {
        run(pkg("plain", "echo 'gate_status: YELLOW'"), "--input", "name=Anna");

        String plain = exec("runs").out().strip();
        Result listed = exec("runs", "--long");

        assertThat(listed.code()).as(listed.err()).isZero();
        assertThat(listed.out().lines().toList()).singleElement().satisfies(line -> assertThat(line)
                .startsWith(plain + "  ")
                .matches(".*Z  gate YELLOW  \\d+(ms|\\.\\ds)  pkg [0-9a-f]{8}  inputs [0-9a-f]{8}  models -$"));
    }

    /** Plain {@code runs} stays as it was: four columns, nothing of the long form. */
    @Test
    void plain_staysAsItIs() throws IOException {
        run(pkg("plain", "echo hello"));

        assertThat(exec("runs").out()).matches(
                "[0-9a-f]{8}  COMPLETED              plain flow  \\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ\\R");
    }

    /**
     * A change of the workflow version or of the inputs between a run and the run of the same workflow listed below it
     * is marked with a star; a run of another workflow in between does not hide it.
     */
    @Test
    void long_marksADigestThatChangedSinceTheRunOfTheSameWorkflowBelow() throws IOException {
        Path plain = pkg("plain", "echo one");
        run(plain, "--input", "name=Anna");          // 4th line: the oldest, nothing to compare with
        run(plain, "--input", "name=Anna");          // 3rd line: the same package and inputs
        run(pkg("other", "echo other"));              // 2nd line: another workflow
        pkg("plain", "echo two");
        run(plain, "--input", "name=Bert");          // 1st line: another package and other inputs

        List<String> lines = exec("runs", "--long").out().lines().toList();

        assertThat(lines).hasSize(4);
        assertThat(lines.get(0)).containsPattern("pkg [0-9a-f]{8}\\*  inputs [0-9a-f]{8}\\*  ");
        assertThat(lines.get(1)).contains("other flow").containsPattern("pkg [0-9a-f]{8}  inputs [0-9a-f]{8}  ");
        assertThat(lines.get(2)).containsPattern("pkg [0-9a-f]{8}  inputs [0-9a-f]{8}  ");
        assertThat(lines.get(3)).containsPattern("pkg [0-9a-f]{8}  inputs [0-9a-f]{8}  ");
        assertThat(digest(lines.get(2), "pkg")).isEqualTo(digest(lines.get(3), "pkg"))
                .isNotEqualTo(digest(lines.get(0), "pkg"));
        assertThat(digest(lines.get(2), "inputs")).isEqualTo(digest(lines.get(3), "inputs"))
                .isNotEqualTo(digest(lines.get(0), "inputs"));
    }

    /** A run that has not finished has no duration yet; its line still has every column. */
    @Test
    void long_ofAParkedRun_hasNoDuration_andTheFiltersStillApply() throws IOException {
        Path dir = Files.createDirectories(temp.resolve("ask"));
        Files.writeString(dir.resolve("workflow.json"), """
                {"id":"ask","name":"ask flow","blocks":[
                  {"type":"approval","name":"go","config":{"question":"Go?","options":["Yes","No"]}}]}""");
        assertThat(exec("run", dir.toString(), "--allow-host-execution", "--no-input", "--quiet").code()).isEqualTo(5);
        run(pkg("plain", "echo hello"));

        Result parked = exec("runs", "--long", "--status", "parked");

        assertThat(parked.out().lines().toList()).singleElement().satisfies(line -> assertThat(line)
                .contains("PARKED", "ask flow").containsPattern("Z  gate -  -  pkg [0-9a-f]{8}  inputs [0-9a-f]{8}  models -$"));
        assertThat(exec("runs", "--long", "--limit", "1").out().lines().toList()).hasSize(1);
    }

    @Test
    void help_namesTheFlag_andTheStar() {
        assertThat(exec("runs", "--help").out()).contains("--long").contains("*");
    }

    private static String digest(String line, String label) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(label + " ([0-9a-f]{8})").matcher(line);
        assertThat(m.find()).as(line).isTrue();
        return m.group(1);
    }
}
