package traceworks.cli;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code scripts/report.sh} of {@code examples/audit-hello} on a fixed list of long files, with nothing on the PATH
 * but {@code jq}: the first audit example needs no second language. The expected reports are the bytes the
 * script wrote when it was written in Python, file names with a tab, quotes, a backslash, letters outside ASCII, an
 * emoji, control characters and a byte that is no UTF-8 among them; the envelope keeps the form of Python's
 * {@code json.dumps}. {@code RegistersIT} runs the package in the shell image.
 */
class AuditHelloReportTest {

    private static final Path SCRIPT = Path.of("../examples/audit-hello/scripts/report.sh").toAbsolutePath().normalize();

    @TempDir
    Path temp;

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = AuditHelloReportTest.class.getResourceAsStream("/audit-hello/" + name)) {
            assertThat(in).as(name).isNotNull();
            return in.readAllBytes();
        }
    }

    private static Path onPath(String tool) {
        return Arrays.stream(System.getenv("PATH").split(File.pathSeparator)).map(dir -> Path.of(dir, tool))
                .filter(Files::isExecutable).findFirst()
                .orElseThrow(() -> new AssertionError("the report tests need " + tool + " on the PATH"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"long", "none"})
    void theReport_isTheSameBytes_withJqAlone(String list) throws IOException, InterruptedException {
        Path scratch = Files.createDirectories(temp.resolve("scratch"));
        Files.write(scratch.resolve("long-files.tsv"), resource(list + "/long-files.tsv"));
        Path tools = Files.createDirectories(temp.resolve("tools"));
        Files.createSymbolicLink(tools.resolve("jq"), onPath("jq"));
        Path out = temp.resolve("out.md");
        Path err = temp.resolve("err.txt");
        ProcessBuilder builder = new ProcessBuilder(onPath("bash").toString(), SCRIPT.toString(), scratch.toString())
                .directory(temp.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile());
        builder.environment().clear();
        builder.environment().put("PATH", tools.toString());

        Process process = builder.start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();

        assertThat(process.exitValue()).as(Files.readString(err)).isZero();
        assertThat(out).hasBinaryContent(resource(list + "/report.md"));
        assertThat(Files.readString(SCRIPT)).doesNotContain("python");
    }
}
