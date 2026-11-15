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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The summary {@link RunReport} prints after a run, through {@code traceworks run} on the host. */
class RunReportTest {

    @TempDir
    Path temp;

    private String run(String blocks) throws IOException {
        Path pkg = Files.createDirectories(temp.resolve("pkg"));
        Files.writeString(pkg.resolve("workflow.json"), "{\"id\":\"pkg\",\"name\":\"pkg flow\",\"blocks\":[" + blocks
                + "]}");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Main.run(new String[] {"run", pkg.toString(), "--allow-host-execution", "--no-input", "--quiet", "--home",
                        temp.resolve("home").toString()}, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                Map.of("TRACEWORKS_ACTOR", "tester"), Files.createDirectories(temp.resolve("work")), null,
                RuntimeSource.NONE);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aRunThatFailed_namesItsErrorInTheSummary() throws IOException {
        String out = run("{\"type\":\"shell\",\"name\":\"boom\",\"config\":{\"command\":\"echo broken >&2; exit 3\"}}");

        assertThat(out).containsPattern("(?m)^run [0-9a-f-]{36} FAILED$").containsPattern("(?m)^  error   .+$");
    }

    @Test
    void aRunThatCompleted_hasNoErrorLine() throws IOException {
        String out = run("{\"type\":\"shell\",\"name\":\"fine\",\"config\":{\"command\":\"echo fine\"}}");

        assertThat(out).containsPattern("(?m)^run [0-9a-f-]{36} COMPLETED$").doesNotContain("  error   ");
    }
}
