package traceworks.selfaudit.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MainTest {

    @TempDir
    Path temp;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    void offline_thenReport_printTheFactsAndTheGateFirst() throws IOException {
        Path root = Files.createDirectories(temp.resolve("work"));
        Files.writeString(root.resolve("pom.xml"), """
                <project><groupId>g</groupId><artifactId>a</artifactId><version>1</version>
                <dependencies><dependency><groupId>org.x</groupId><artifactId>y</artifactId><version>1</version>
                </dependency></dependencies></project>
                """);
        Files.createDirectories(root.resolve("images/a"));
        Files.writeString(root.resolve("images/a/Dockerfile"), "FROM debian‮:12\n");
        Path rules = rules("dockerfile images/*/Dockerfile\n");
        Path facts = temp.resolve("offline.json");

        int code = run("offline", "--rules", rules.toString(), "--root", root.toString(), "--out", facts.toString());

        assertThat(code).as(err.toString()).isZero();
        assertThat(stdout()).startsWith("## FACT SHEET offline\nFACT offline_poms: 1\n")
                .contains("HIT runtime_declared: pom.xml:2 org.x:y declared in scope compile\n")
                .contains("HIT base_unpinned: images/a/Dockerfile:1 debian\\u202e:12 no @sha256: digest");
        assertThat(Files.readString(facts)).startsWith("{\"step\":\"offline\"");

        out.reset();
        Path report = temp.resolve("build-report.md");
        code = run("report", "--rules", rules.toString(), "--offline", facts.toString(), "--maven",
                temp.resolve("missing.json").toString(), "--registries", temp.resolve("missing.json").toString(),
                "--out", report.toString());

        assertThat(code).as(err.toString()).isZero();
        assertThat(stdout()).startsWith("gate_status: RED\ngate_category: build\ngate_description: 2 broken rules, 0 items"
                + " to look at, 2 not measured, 0 notes\n")
                .contains("RED base_unpinned images/a/Dockerfile:1 debian\\u202e:12\n")
                .contains("NOT MEASURED: maven (the step left no facts: it failed or did not run)\n");
        assertThat(Files.readString(report)).startsWith("# Build and dependencies self-audit\n\n**Gate:** RED");
    }

    @Test
    void maven_withoutAPom_isAGap_notAFailure() throws IOException {
        Path facts = temp.resolve("maven.json");

        int code = run("maven", "--rules", rules("").toString(), "--root", temp.toString(), "--out", facts.toString(),
                "--sbom-runtime", temp.resolve("sbom.json").toString());

        assertThat(code).isZero();
        assertThat(stdout()).contains("NOT MEASURED: maven_tree (no POM to resolve: pom.xml no pom.xml there)");
        assertThat(temp.resolve("sbom.json")).doesNotExist();
    }

    @Test
    void registries_withoutOfflineFacts_isAGap_notAFailure() throws IOException {
        int code = run("registries", "--rules", rules("").toString(), "--offline", temp.resolve("none.json").toString(),
                "--out", temp.resolve("registries.json").toString());

        assertThat(code).isZero();
        assertThat(stdout()).contains("NOT MEASURED: registries (the offline facts are not readable: ");
    }

    @Test
    void aUsageError_exits2_andABrokenRuleFile_exits1() throws IOException {
        assertThat(run()).isEqualTo(2);
        assertThat(run("frobnicate")).isEqualTo(2);
        assertThat(run("offline", "--rules", "x")).isEqualTo(2);
        assertThat(run("offline", "--rules", "x", "--rules", "y", "--root", ".", "--out", "z")).isEqualTo(2);
        assertThat(run("registries", "--rules", rules("").toString(), "--offline", "x", "--out", "y", "--timeout", "0"))
                .isEqualTo(2);
        err.reset();
        assertThat(run("offline", "--rules", rules("nonsense here").toString(), "--root", ".", "--out", "z")).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("rule file line 1: unknown keyword 'nonsense'");
    }

    private Path rules(String text) throws IOException {
        Path rules = temp.resolve("build-" + System.nanoTime() + ".rules");
        Files.writeString(rules, text);
        return rules;
    }

    private int run(String... args) {
        return Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }
}
