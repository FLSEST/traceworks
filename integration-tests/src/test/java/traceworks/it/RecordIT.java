package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The record of a run (docs/events.md): the lines of {@code events.jsonl}, the manifest and the ledger entry, which
 * {@code traceworks verify} checks and which one changed byte breaks; {@code run.html}; and {@code show}, {@code runs}
 * and {@code render}, which read it.
 */
class RecordIT extends IntegrationTest {

    @Test
    void aFinishedRun_hasAnEventLog_aManifest_andALedgerEntry() {
        Result run = traceworks.run("placeholders");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        List<String> types = folder.types();
        assertThat(types.getFirst()).isEqualTo("run.started");
        assertThat(types.get(1)).isEqualTo("run.checkpoint");
        assertThat(types.getLast()).isEqualTo("run.finished");
        assertThat(types).contains("step.started", "step.command", "step.command.finished", "step.output",
                "step.finished");

        JsonNode started = folder.only("run.started");
        assertThat(started.path("workflow").path("name").asText()).isEqualTo("IT placeholders");
        assertThat(started.path("workflow").path("id").asText()).isEqualTo("02e3e025-bb6a-484a-8128-bd0be47d91b7");
        assertThat(started.path("package").path("dir").asText())
                .isEqualTo(traceworks.packageDir("placeholders").toString());
        assertThat(started.path("package").path("digest").asText()).isNotBlank();
        assertThat(started.path("trigger").asText()).isEqualTo("MANUAL");
        assertThat(started.path("actor").asText()).isEqualTo(Traceworks.ACTOR);
        assertThat(started.path("execution").asText()).isEqualTo("container");
        assertThat(started.path("container_runtime").path("name").asText())
                .isEqualTo(traceworks.environment().runtime());
        assertThat(folder.blob(started.path("definition")))
                .isEqualTo(read(traceworks.packageDir("placeholders").resolve("workflow.json")));

        JsonNode manifest = folder.manifest();
        assertThat(manifest.path("run_id").asText()).isEqualTo(folder.id());
        assertThat(manifest.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(manifest.path("execution").asText()).isEqualTo("container");
        assertThat(manifest.path("seal").asText()).isEqualTo("unsealed");
        assertThat(manifest.path("signature").isNull()).isTrue();
        assertThat(manifest.path("events").asInt()).isEqualTo(types.size());
        assertThat(manifest.path("head").asText()).isEqualTo(folder.chainHead());
        assertThat(manifest.path("package_digest").asText()).isEqualTo(started.path("package").path("digest").asText());
        assertThat(manifest.path("images")).extracting(i -> i.path("reference").asText()).containsExactly("shell");
        assertThat(folder.ledgerEntry(traceworks.home()))
                .as("the ledger holds a byte-identical copy of the manifest")
                .isEqualTo(bytes(folder.dir().resolve("manifest.json")));
    }

    @Test
    void verify_holds_andOneChangedByteOfTheLog_exits8() throws Exception {
        String id = traceworks.run("placeholders").runId();
        Result verify = traceworks.exec("verify", id);
        assertThat(verify.code()).as(verify.describe()).isZero();
        assertThat(verify.out()).containsPattern("(?m)^chain +ok \\(\\d+ events, head [0-9a-f]+\\)$")
                .containsPattern("(?m)^manifest +ok$").containsPattern("(?m)^ledger +ok$")
                .endsWith("verified\n");

        Path log = traceworks.folder(id).eventsFile();
        byte[] original = Files.readAllBytes(log);
        byte[] changed = original.clone();
        int at = new String(original, StandardCharsets.UTF_8).indexOf("\"actor\":\"" + Traceworks.ACTOR + "\"")
                + "\"actor\":\"".length();
        changed[at] = (byte) 'I';   // it-tester becomes It-tester
        Files.write(log, changed);
        try {
            Result broken = traceworks.exec("verify", id);
            assertThat(broken.code()).as(broken.describe()).isEqualTo(8);
            assertThat(broken.out()).doesNotContain("verified\n");
            assertThat(broken.both()).containsPattern("(?m)^chain +");
            assertThat(broken.both()).doesNotContainPattern("(?m)^chain +ok");
        } finally {
            Files.write(log, original);
        }
        assertThat(traceworks.exec("verify", id).code()).as("the original bytes verify again").isZero();
    }

    @Test
    void aChangedManifest_exits8() throws Exception {
        String id = traceworks.run("placeholders").runId();
        Path manifest = traceworks.folder(id).dir().resolve("manifest.json");
        String original = Files.readString(manifest);
        Files.writeString(manifest, original.replace("\"COMPLETED\"", "\"FAILED\""));
        try {
            Result broken = traceworks.exec("verify", id);
            assertThat(broken.code()).as(broken.describe()).isEqualTo(8);
            assertThat(broken.both()).doesNotContainPattern("(?m)^manifest +ok");
        } finally {
            Files.writeString(manifest, original);
        }
    }

    @Test
    void aCopiedRunFolder_verifiesOffline_withoutItsLedger() throws Exception {
        RunFolder folder = traceworks.folder(traceworks.run("placeholders").runId());
        Path copy = sandbox.resolve("usb").resolve(folder.id());
        copyTree(folder.dir(), copy);

        Result verify = traceworks.exec("verify", copy.toString());

        assertThat(verify.code()).as(verify.describe()).isZero();
        assertThat(verify.out()).containsPattern("(?m)^chain +ok").containsPattern("(?m)^ledger +not checked");
    }

    @Test
    void runHtml_isOneSelfContainedFile_withoutScripts() {
        Result run = traceworks.run("placeholders");

        RunFolder folder = traceworks.folder(run);
        assertThat(run.out()).contains("report  " + folder.dir().resolve("run.html") + " (open it in a browser)");
        String html = folder.html();
        assertThat(html).startsWith("<!DOCTYPE html>")
                .contains("Content-Security-Policy", "default-src 'none'")
                .contains(folder.id(), "IT placeholders", "greet", "by_index", "Hello, world")
                .doesNotContainIgnoringCase("<script");

        Result render = traceworks.exec("render", folder.id());
        assertThat(render.code()).as(render.describe()).isZero();
        assertThat(render.out()).contains(folder.dir().resolve("run.html").toString());
        assertThat(folder.hasHtml()).isTrue();
    }

    @Test
    void showAndRuns_printTheRun() {
        String id = traceworks.run("placeholders").runId();

        Result show = traceworks.exec("show", id.substring(0, 8));
        assertThat(show.code()).as(show.describe()).isZero();
        assertThat(show.out()).containsPattern("(?m)^Run +" + id + "$")
                .containsPattern("(?m)^Workflow +IT placeholders \\(02e3e025-bb6a-484a-8128-bd0be47d91b7\\)$")
                .containsPattern("(?m)^Status +COMPLETED$")
                .containsPattern("(?m)^Actor +" + Traceworks.ACTOR + "$")
                .containsPattern("(?m)^ +0 +greet +\\[shell\\] +COMPLETED")
                .containsPattern("(?m)^ +\\d+ +skipped +\\[shell\\] +SKIPPED")
                .doesNotContain("Hello, world");

        Result outputs = traceworks.exec("show", id, "--outputs");
        assertThat(outputs.out()).contains("| Hello, world", "| by index: second-output");

        Result runs = traceworks.exec("runs");
        assertThat(runs.code()).as(runs.describe()).isZero();
        assertThat(runs.out()).containsPattern("(?m)^" + id.substring(0, 8)
                + " +COMPLETED +IT placeholders +\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$");

        Result unknown = traceworks.exec("show", "ffffffff-0000-0000-0000-000000000000");
        assertThat(unknown.code()).as(unknown.describe()).isEqualTo(2);
    }

    private static String read(Path file) {
        return new String(bytes(file), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path source : paths.toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target);
                }
            }
        }
    }
}
