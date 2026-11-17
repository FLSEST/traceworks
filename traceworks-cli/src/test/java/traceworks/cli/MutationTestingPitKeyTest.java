package traceworks.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The key under which {@code scripts/pit.sh} of {@code examples/mutation-testing} keeps a result of PIT in the cache
 * folder: a run takes a kept result when the key is the same, so everything that decides the result must be in the
 * key, in any Maven layout. The script runs on the host with {@code bash pit.sh key}, in the folder of a small Maven
 * project; it needs {@code bash}, {@code find}, {@code sort} and {@code sha256sum}, as the script tests of this build
 * do. {@code examples/mutation-testing-grounded} holds the same script byte for byte ({@link ExamplePackagesTest}).
 */
class MutationTestingPitKeyTest {

    private static final Path PACKAGE = Path.of("../examples/mutation-testing").toAbsolutePath().normalize();
    private static final Path SCRIPT = PACKAGE.resolve("scripts/pit.sh");

    @TempDir
    Path temp;

    private String key(Path project, String classes) throws IOException {
        return key(project, SCRIPT, classes);
    }

    private String key(Path project, Path script, String classes) throws IOException {
        Path out = Files.createTempFile(temp, "out", ".txt");
        Path err = Files.createTempFile(temp, "err", ".txt");
        ProcessBuilder builder = new ProcessBuilder(List.of("bash", script.toString(), "key", "app", classes, "2"))
                .directory(project.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile());
        try {
            Process process = builder.start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("pit.sh key did not end");
            }
            assertThat(process.exitValue()).as(Files.readString(err)).isZero();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        String key = Files.readString(out).strip();
        assertThat(key).matches("[0-9a-f]{16}");
        return key;
    }

    private static void write(Path project, String file, String content) throws IOException {
        Path path = project.resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private Path project(String name, String... files) throws IOException {
        Path project = Files.createDirectories(temp.resolve(name));
        for (String file : files) {
            write(project, file, "// " + file + "\n");
        }
        return project;
    }

    @Test
    void aSingleModuleProject_aChangedSourceInSrc_changesTheKey() throws IOException {
        Path project = project("single", "pom.xml", "src/main/java/demo/Calc.java", "src/test/java/demo/CalcTest.java");
        String before = key(project, "demo.*");

        write(project, "src/main/java/demo/Calc.java", "class Calc { int add(int a, int b) { return a - b; } }\n");
        String main = key(project, "demo.*");
        write(project, "src/test/java/demo/CalcTest.java", "class CalcTest { }\n");
        String test = key(project, "demo.*");

        assertThat(main).isNotEqualTo(before);
        assertThat(test).isNotEqualTo(main).isNotEqualTo(before);
    }

    @Test
    void nestedModules_aChangedSourceOrPomBelowTheFirstLevel_changesTheKey() throws IOException {
        Path project = project("nested", "pom.xml", "services/pom.xml", "services/billing/pom.xml",
                "services/billing/src/main/java/demo/Bill.java");
        String before = key(project, "demo.*");

        write(project, "services/billing/src/main/java/demo/Bill.java", "class Bill { }\n");
        String source = key(project, "demo.*");
        write(project, "services/billing/pom.xml", "<project><artifactId>billing</artifactId></project>\n");
        String pom = key(project, "demo.*");

        assertThat(source).isNotEqualTo(before);
        assertThat(pom).isNotEqualTo(source).isNotEqualTo(before);
    }

    @Test
    void modulesOneLevelDeep_aChangedSource_changesTheKey() throws IOException {
        Path project = project("flat", "pom.xml", "core/pom.xml", "core/src/main/java/demo/Core.java");
        String before = key(project, "demo.*");

        write(project, "core/src/main/java/demo/Core.java", "class Core { }\n");

        assertThat(key(project, "demo.*")).isNotEqualTo(before);
    }

    @Test
    void theMavenConfigurationOfTheProject_isPartOfTheKey() throws IOException {
        Path project = project("config", "pom.xml", "src/main/java/demo/A.java", ".mvn/maven.config");
        String before = key(project, "demo.*");

        write(project, ".mvn/maven.config", "-Dsurefire.rerunFailingTestsCount=2\n");

        assertThat(key(project, "demo.*")).isNotEqualTo(before);
    }

    @Test
    void theClasses_arePartOfTheKey() throws IOException {
        Path project = project("classes", "pom.xml", "src/main/java/demo/A.java");

        assertThat(key(project, "demo.*")).isNotEqualTo(key(project, "demo.core.*"));
    }

    @Test
    void theSameSourcesInAnotherFolder_giveTheSameKey_andAFileOutsideSrcDoesNot() throws IOException {
        Path one = project("one", "pom.xml", "core/pom.xml", "core/src/main/java/demo/A.java", "src/main/java/B.java");
        Path two = project("two", "pom.xml", "core/pom.xml", "core/src/main/java/demo/A.java", "src/main/java/B.java");
        String first = key(one, "demo.*");

        assertThat(key(two, "demo.*")).isEqualTo(first);
        write(one, "README.md", "Docs change no result of PIT.\n");
        assertThat(key(one, "demo.*")).isEqualTo(first);
    }

    /**
     * The script holds PIT's version and its settings, and the image's Dockerfile pins Maven and the JDK: a changed
     * script or Dockerfile keeps no result of the one before.
     */
    @Test
    void theScriptAndTheImage_arePartOfTheKey() throws IOException {
        Path project = project("tool", "pom.xml", "src/main/java/demo/A.java");
        Path copy = Files.createDirectories(temp.resolve("copy"));
        Files.createDirectories(copy.resolve("scripts"));
        Files.createDirectories(copy.resolve("images/maven"));
        Path script = Files.copy(SCRIPT, copy.resolve("scripts/pit.sh"));
        Path dockerfile = Files.copy(PACKAGE.resolve("images/maven/Dockerfile"), copy.resolve("images/maven/Dockerfile"));
        String before = key(project, script, "demo.*");
        assertThat(key(project, "demo.*")).as("a byte copy of the package").isEqualTo(before);

        Files.writeString(script, Files.readString(script).replace("pit=1.30.2", "pit=1.30.3"));
        String newerPit = key(project, script, "demo.*");
        Files.writeString(dockerfile, Files.readString(dockerfile).replace("maven:3.9.8", "maven:3.9.9"));
        String newerImage = key(project, script, "demo.*");

        assertThat(newerPit).isNotEqualTo(before);
        assertThat(newerImage).isNotEqualTo(newerPit).isNotEqualTo(before);
    }
}
