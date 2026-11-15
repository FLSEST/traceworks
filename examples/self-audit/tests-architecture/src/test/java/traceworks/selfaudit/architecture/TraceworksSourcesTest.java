package traceworks.selfaudit.architecture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The measured rules of the architecture, held against the Traceworks sources themselves in every build: no package
 * cycle, no layer violation and no I/O the rule file forbids. The self-audit measures the same, but only when it
 * runs: on 2026-11-12 it found a cycle (core.engine, core.register, core.record) that a day of builds had not shown.
 */
class TraceworksSourcesTest {

    @TempDir
    Path temp;

    @Test
    void theSources_haveNoPackageCycle_noLayerViolation_andNoForbiddenIo() throws Exception {
        Path root = Path.of("../../..").toAbsolutePath().normalize();
        Path rules = root.resolve("examples/self-audit/architecture/rules/architecture.rules");
        Path facts = temp.resolve("facts.json");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = Main.run(new String[] {"facts", "--rules", rules.toString(), "--root", root.toString(), "--facts",
                        facts.toString(), "--candidates", temp.resolve("candidates.jsonl").toString()},
                new ByteArrayInputStream(new byte[0]), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertThat(code).as(err.toString(StandardCharsets.UTF_8)).isZero();
        Map<?, ?> measured = (Map<?, ?>) Json.parse(Files.readString(facts));
        assertThat((List<?>) measured.get("notRead")).as("every source file was read").isEmpty();
        assertThat((List<?>) measured.get("cycles")).as("package cycles").isEmpty();
        assertThat((List<?>) measured.get("layerViolations")).as("layer violations").isEmpty();
        assertThat(((List<?>) measured.get("io")).stream().filter(entry -> !Boolean.TRUE.equals(
                ((Map<?, ?>) entry).get("allowed")))).as("types with I/O the rule file forbids").isEmpty();
    }
}
