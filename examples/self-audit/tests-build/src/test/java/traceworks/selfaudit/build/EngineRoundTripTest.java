package traceworks.selfaudit.build;

import com.sun.net.httpserver.HttpServer;
import traceworks.cli.RuntimeSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The package's shape through the real engine, as a host run without network and without a container: the tool's
 * {@code offline}, {@code maven} (with a fake {@code mvn}), {@code registries} (against a registry of the JDK's HTTP
 * server on the loopback address) and {@code report}, with shell stand-ins for the scanner's tool steps that print the
 * engine's SARIF fact sheets. It pins what the report relies on: the scratch directory between the steps, the
 * shell-escaped fact sheets, and the gate line first.
 */
class EngineRoundTripTest {

    private static final String DIGEST = "sha256:" + "a".repeat(64);

    @TempDir
    Path temp;

    @Test
    void theReport_readsTheFactsOfEveryStep() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            String body = switch (path) {
                case "/m2/com/fasterxml/jackson/core/jackson-databind/maven-metadata.xml" -> "<metadata><versioning>"
                        + "<versions><version>2.22.4</version><version>2.23.0</version></versions></versioning></metadata>";
                case "/npm/cli" -> "{\"dist-tags\":{\"latest\":\"1.0.0\"},\"versions\":{\"1.0.0\":{}}}";
                case "/v2/library/base/manifests/1" -> "{}";
                default -> null;
            };
            if (path.startsWith("/v2/")) exchange.getResponseHeaders().add("Docker-Content-Digest", DIGEST);
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            boolean head = exchange.getRequestMethod().equals("HEAD");
            exchange.sendResponseHeaders(body == null ? 404 : 200, head ? -1 : bytes.length);
            if (!head) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            run(server.getAddress().getPort());
        } finally {
            server.stop(0);
        }
    }

    private void run(int port) throws IOException {
        Path work = Files.createDirectories(temp.resolve("work"));
        Files.writeString(work.resolve("pom.xml"), """
                <project><groupId>example</groupId><artifactId>app</artifactId><version>1.0</version>
                <dependencies><dependency><groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId><version>2.22.4</version></dependency></dependencies></project>
                """);
        Files.createDirectories(work.resolve("images/tool"));
        Files.writeString(work.resolve("images/tool/Dockerfile"), "FROM 127.0.0.1:" + port + "/library/base:1@" + DIGEST
                + "\nRUN npm install -g cli@1.0.0\n");
        Path pkg = Files.createDirectories(temp.resolve("pkg"));
        Files.writeString(pkg.resolve("build.rules"), """
                runtime-allow com.fasterxml.jackson.core:*
                maven-repository http://127.0.0.1:%1$d/m2
                npm-registry http://127.0.0.1:%1$d/npm
                image-registry 127.0.0.1:%1$d
                dockerfile images/*/Dockerfile
                """.formatted(port));
        Path mvn = temp.resolve("mvn");
        Files.writeString(mvn, """
                #!/usr/bin/env bash
                for a in "$@"; do case "$a" in -DoutputFile=*) out=${a#-DoutputFile=};; esac; done
                printf '%s\\n' 'example:app:jar:1.0' '\\- com.fasterxml.jackson.core:jackson-databind:jar:2.22.4:compile' > "$out"
                """);
        Files.setPosixFilePermissions(mvn, PosixFilePermissions.fromString("rwx------"));

        String java = ProcessHandle.current().info().command().orElse("java");
        String tool = "'" + java + "' -cp '" + Path.of("target/classes").toAbsolutePath() + "' " + Main.class.getName();
        String rules = "{{ package_dir }}/build.rules";
        String scratch = "{{ scratch_dir }}";
        String workflow = Json.write(Map.of(
                "format", "traceworks-workflow", "format_version", 1L,
                "id", "3f0c5f53-6a1e-4a43-9a43-1c3a2e2b7d10", "name", "Round trip",
                "metadata", Map.of("gate", Map.of("expected", true)),
                "blocks", List.of(
                        shell("offline", tool + " offline --rules " + rules + " --root . --out " + scratch
                                + "/build-offline.json", false),
                        shell("maven", tool + " maven --rules " + rules + " --root . --out " + scratch
                                + "/build-maven.json --sbom-runtime " + scratch + "/build-sbom-runtime.cdx.json"
                                + " --sbom-test " + scratch + "/build-sbom-test.cdx.json --mvn '" + mvn + "'", false),
                        shell("registries", tool + " registries --rules " + rules + " --offline " + scratch
                                + "/build-offline.json --out " + scratch + "/build-registries.json", false),
                        shell("vulns_runtime", "printf '%s\\n' '## FACT SHEET vulns_runtime (Trivy 0.76.1, 1 SARIF file)'"
                                + " 'FACT vulns_runtime_findings: 0' 'FACT vulns_runtime_exit_code: 0'", false),
                        shell("vulns_test", "printf '%s\\n' '## FACT SHEET vulns_test'"
                                + " 'NOT MEASURED: vulns_test_findings (no SARIF file in /out)'"
                                + " 'FACT vulns_test_exit_code: 1'; exit 1", false),
                        shell("report", "printf '%s' {{ block.vulns_runtime.output }} > " + scratch + "/v1.txt;"
                                + " printf '%s' {{ block.vulns_test.output }} > " + scratch + "/v2.txt; " + tool
                                + " report --rules " + rules + " --offline " + scratch + "/build-offline.json --maven "
                                + scratch + "/build-maven.json --registries " + scratch + "/build-registries.json"
                                + " --vulns-runtime " + scratch + "/v1.txt --vulns-test " + scratch + "/v2.txt --out "
                                + scratch + "/build-report.md", true))));
        Files.writeString(pkg.resolve("workflow.json"), workflow);
        Path home = Files.createDirectories(temp.resolve("home"));
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TRACEWORKS_ACTOR", "tester");

        // --no-check: the workflow names the JVM and the classes of this machine by their absolute paths, which
        // the check before a run refuses (absolute-home-path) wherever they lie below a home directory
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        traceworks.cli.main.Main.run(new String[] {"run", pkg.toString(), "--workspace", work.toString(),
                        "--home", home.toString(), "--no-input", "--allow-host-execution", "--quiet", "--no-check"},
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                env, temp, null, RuntimeSource.NONE);
        String output = out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);

        Path report;
        try (Stream<Path> walk = Files.walk(home.resolve("runs"))) {
            report = walk.filter(p -> p.getFileName().toString().equals("build-report.md")).findFirst()
                    .orElseThrow(() -> new AssertionError("no report:\n" + output));
        }
        String markdown = Files.readString(report);
        assertThat(markdown).as(output).contains("**Gate:** YELLOW: 0 broken rules, 1 item to look at, 1 not measured,"
                        + " 1 note")
                .contains("- `pom.xml:2` `com.fasterxml.jackson.core:jackson-databind`: 2.22.4 \\-\\> 2.23.0 \\(minor;"
                        + " 1 newer release\\)")
                .contains("- `vulns_test`: vulns\\_test\\_findings \\(no SARIF file in /out\\)")
                .contains("| maven runtime_artifacts | `1` |", "| vulns_runtime findings | `0` |");
        assertThat(report.resolveSibling("build-sbom-runtime.cdx.json")).content()
                .contains("pkg:maven/com.fasterxml.jackson.core/jackson-databind@2.22.4");
        assertThat(output).contains("YELLOW (build)");
    }

    private static Map<String, Object> shell(String name, String command, boolean escape) {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "set -euo pipefail; " + command);
        config.put("on_failure", "continue");
        if (escape) config.put("shell_escape_variables", true);
        return Map.of("type", "shell", "name", name, "config", config);
    }
}
