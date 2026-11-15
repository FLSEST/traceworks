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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every package under {@code examples/} passes {@code traceworks check}: no shipped example has an error. And
 * {@code traceworks describe} describes each, as text and as a page.
 */
class ExamplePackagesTest {

    /** The module directory is the working directory of Surefire. */
    private static final Path EXAMPLES = Path.of("../examples").toAbsolutePath().normalize();

    @TempDir
    Path home;

    private static List<Path> packages() throws IOException {
        List<Path> packages;
        try (Stream<Path> walk = Files.walk(EXAMPLES)) {
            packages = walk.filter(p -> p.getFileName().toString().equals("workflow.json"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .map(Path::getParent).sorted().toList();
        }

        assertThat(packages).extracting(p -> EXAMPLES.relativize(p).toString().replace('\\', '/'))
                .contains("hello", "ai-hello", "audit-hello", "self-audit/security", "self-audit/architecture", "self-audit/build",
                        "self-audit/functionality", "self-audit/testing");
        return packages;
    }

    @Test
    void everyExamplePackage_isDescribed_asTextAndAsAPage(@TempDir Path work) throws IOException {
        for (Path pkg : packages()) {
            for (boolean html : List.of(false, true)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ByteArrayOutputStream err = new ByteArrayOutputStream();
                String[] args = html ? new String[] {"describe", pkg.toString(), "--html", "page.html", "--home",
                        home.resolve("none").toString()}
                        : new String[] {"describe", pkg.toString(), "--home", home.resolve("none").toString()};
                int code = Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8), Map.of(), work, null, RuntimeSource.NONE);
                String printed = out.toString(StandardCharsets.UTF_8);

                assertThat(code).as(pkg + "\n" + printed + err.toString(StandardCharsets.UTF_8)).isZero();
                assertThat(err.toString(StandardCharsets.UTF_8)).as(pkg.toString()).isEmpty();
                String described = html ? Files.readString(work.resolve("page.html")) : printed;
                assertThat(described).as(pkg.toString()).contains(pkg.toString()).containsPattern("[1-9][0-9]* blocks?: ")
                        .doesNotContain("invalid value").doesNotContain("not shown:");
            }
        }
        assertThat(home.resolve("none")).doesNotExist();
    }

    @Test
    void everyExamplePackage_passesCheck() throws IOException {
        List<Path> packages = packages();
        for (Path pkg : packages) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int code = Main.run(new String[] {"check", pkg.toString(), "--home", home.toString()},
                    new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                    Map.of("TRACEWORKS_ACTOR", "tester"), pkg, null, RuntimeSource.NONE);
            String output = out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);

            assertThat(code).as(pkg + "\n" + output).isZero();
            assertThat(output).as(pkg.toString()).doesNotContain("ERROR").doesNotContain("WARN");
        }
    }
}
