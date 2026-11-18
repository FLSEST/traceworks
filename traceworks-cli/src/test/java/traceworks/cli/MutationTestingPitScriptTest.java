package traceworks.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code scripts/pit.sh} of {@code examples/mutation-testing}: it refuses a run that names no modules or classes, and
 * it keeps a result of PIT in the cache folder under a key. A run takes a kept result when the key is the same, so
 * everything that decides the result must be in the key, in any Maven layout. The script runs on the host, the key
 * with {@code bash pit.sh key} in the folder of a small Maven project; it needs {@code bash}, {@code find}, {@code sort} and {@code sha256sum}, as the script tests of this build
 * do. {@code examples/mutation-testing-grounded} holds the same script byte for byte ({@link ExamplePackagesTest}).
 */
class MutationTestingPitScriptTest {

    private static final Path PACKAGE = Path.of("../examples/mutation-testing").toAbsolutePath().normalize();
    private static final Path SCRIPT = PACKAGE.resolve("scripts/pit.sh");

    @TempDir
    Path temp;

    private String key(Path project, String classes) throws IOException {
        return key(project, SCRIPT, classes);
    }

    private record Ran(int code, String stdout, String stderr) {
    }

    private Ran run(Path directory, Path script, String... args) throws IOException {
        Path out = Files.createTempFile(temp, "out", ".txt");
        Path err = Files.createTempFile(temp, "err", ".txt");
        List<String> command = new ArrayList<>(List.of("bash", script.toString()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile());
        try {
            Process process = builder.start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("did not end: " + String.join(" ", command));
            }
            return new Ran(process.exitValue(), Files.readString(out), Files.readString(err));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private String key(Path project, Path script, String classes) throws IOException {
        Ran ran = run(project, script, "key", "app", classes, "2");
        assertThat(ran.code()).as(ran.stderr()).isZero();
        String key = ran.stdout().strip();
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

    /**
     * The package names no module and no classes of its own: a run without them stops in its first step, before
     * anything is copied, built or fetched, and says what to pass.
     */
    @Test
    void withoutModulesOrClasses_theScriptRefuses_andSaysWhatToPass() throws IOException {
        Path project = project("inputs", "pom.xml", "src/main/java/demo/A.java");

        for (String[] inputs : List.of(new String[] {"", ""}, new String[] {"app", ""}, new String[] {"", "demo.*"})) {
            for (String mode : List.of("fetch", "measure")) {
                Ran ran = run(project, SCRIPT, mode, inputs[0], inputs[1], "2");

                assertThat(ran.code()).as(mode + " " + List.of(inputs)).isEqualTo(2);
                assertThat(ran.stderr()).contains("--input modules=").contains("--input classes=");
                assertThat(ran.stdout()).isEmpty();
            }
        }
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
        String image = Files.readString(dockerfile);
        String newerFrom = image.replaceFirst("(?m)^FROM maven:\\S+", "FROM maven:3.99.0-eclipse-temurin-21");
        assertThat(newerFrom).as("the Dockerfile names its Maven image on a FROM line").isNotEqualTo(image);
        Files.writeString(dockerfile, newerFrom);
        String newerImage = key(project, script, "demo.*");

        assertThat(newerPit).isNotEqualTo(before);
        assertThat(newerImage).isNotEqualTo(newerPit).isNotEqualTo(before);
    }

    @Test
    void aJavaPackageNamedTarget_isSource_andChangesTheKey() throws IOException {
        Path project = project("package", "pom.xml", "src/main/java/demo/target/Aim.java",
                "core/pom.xml", "core/src/main/java/demo/target/Hit.java");
        String before = key(project, "demo.*");

        write(project, "src/main/java/demo/target/Aim.java", "class Aim { }\n");
        String root = key(project, "demo.*");
        write(project, "core/src/main/java/demo/target/Hit.java", "class Hit { }\n");
        String core = key(project, "demo.*");

        assertThat(root).isNotEqualTo(before);
        assertThat(core).isNotEqualTo(root);
    }

    @Test
    void theBuildOutputOfAModule_nextToItsPom_isNotInTheKey() throws IOException {
        Path project = project("output", "pom.xml", "core/pom.xml", "core/src/main/java/demo/A.java");
        String before = key(project, "demo.*");

        write(project, "target/generated-sources/src/G.java", "class G { }\n");
        write(project, "core/target/generated-sources/src/H.java", "class H { }\n");

        assertThat(key(project, "demo.*")).isEqualTo(before);
    }

    private Ran stems(String modules) throws IOException {
        Path project = project("stems" + modules.hashCode(), "pom.xml");
        return run(project, SCRIPT, "stems", modules, "demo.*", "2");
    }

    @Test
    void aModuleGivenAsAPathOrAnArtifactId_getsOneSafeFileNameStem() throws IOException {
        Ran ran = stems("core,services/billing,:my-app,.,.hidden,a:b/c*");

        assertThat(ran.code()).as(ran.stderr()).isZero();
        assertThat(ran.stdout().lines().toList()).containsExactly("core\tcore", "services/billing\tservices_billing",
                ":my-app\t_my-app", ".\troot", ".hidden\t_hidden", "a:b/c*\ta_b_c_");
    }

    @Test
    void twoModulesWithOneStem_areRefused_inEveryMode() throws IOException {
        for (String modules : List.of("a/b,a_b", "core,core", ":x,_x", ".,root")) {
            for (String mode : List.of("fetch", "measure", "stems")) {
                Path project = project("clash" + mode + modules.hashCode(), "pom.xml");
                Ran ran = run(project, SCRIPT, mode, modules, "demo.*", "2");

                assertThat(ran.code()).as(mode + " " + modules).isEqualTo(2);
                assertThat(ran.stderr()).contains("both give the file name");
                assertThat(ran.stdout()).isEmpty();
            }
        }
    }
}
