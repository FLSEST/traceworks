package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The register folder of an audit run in containers (package {@code register-files}, docs/registers.md, "The
 * register folder of a run"): the steps of the second run read what the first run left in the register, as files
 * mounted read-only at {@code /register}, with the whole evidence and the description of each finding; no step can
 * write the folder; the record names the head of the register the run started from and the digest of each file.
 */
class RegisterFilesIT extends IntegrationTest {

    @Test
    void theSecondRun_readsWhatTheRegisterKnows_fromFilesItCannotWrite() throws IOException {
        file("a.txt");
        file("b.txt");

        Result first = traceworks.run("register-files");

        assertThat(first.code()).as(first.describe()).isZero();
        assertThat(first.out()).contains("register register-files: 2 new, 0 resolved, 0 reopened, 2 open now");
        RunFolder firstFolder = traceworks.folder(first);
        assertThat(firstFolder.output("known")).as("nothing known yet: every file is there and empty")
                .contains("files: findings.jsonl items.jsonl previous-report.md resolved.jsonl", "findings: 0",
                        "resolved: 0", "items: 0");
        JsonNode nothing = firstFolder.only("run.started").path("register");
        assertThat(nothing.path("key").asText()).isEqualTo("register-files");
        assertThat(nothing.path("seq").isNull()).isTrue();
        assertThat(nothing.path("head").isNull()).isTrue();

        Files.delete(traceworks.workspace().resolve("a.txt"));

        Result second = traceworks.run("register-files");

        assertThat(second.code()).as(second.describe()).isZero();
        assertThat(second.out()).contains("register register-files: 0 new, 1 resolved, 0 reopened, 1 open now");
        RunFolder folder = traceworks.folder(second);
        String known = folder.output("known");
        assertThat(known).contains("findings: 2", "resolved: 0");
        assertThat(known).as("each finding with its description and its whole evidence")
                .contains("\"subject\":\"a.txt\"", "\"subject\":\"b.txt\"", "\"status\":\"OPEN\"",
                        "\"description\":\"Remove a.txt or say why it stays.\"",
                        "file: a.txt " + "x".repeat(400) + " end-of-evidence",
                        "\"run_id\":\"" + firstFolder.id() + "\"");
        assertThat(known).as("the last report, whole, without its envelope")
                .contains("# Files", "2 file(s) in the workspace.", "the last line of the report")
                .doesNotContain("traceworks:audit-result");

        // No step can change the folder for a later one: the mount is read-only
        assertThat(folder.stepStatus("add_a_file")).isEqualTo("FAILED");
        assertThat(folder.output("add_a_file")).contains("Read-only file system");
        assertThat(folder.stepStatus("change_a_file")).isEqualTo("FAILED");
        // The mount refuses the write; where the kernel asks the file's own permissions first, those do
        assertThat(folder.output("change_a_file")).containsAnyOf("Read-only file system", "Permission denied");
        Path register = folder.dir().resolve("register");
        assertThat(register.resolve("probe")).doesNotExist();
        JsonNode mount = null;
        for (JsonNode candidate : folder.ofStep("step.command", folder.stepIndex("known")).getFirst().path("mounts")) {
            if ("/register".equals(candidate.path("container").asText())) {
                mount = candidate;
            }
        }
        assertThat(mount).as("the record names the mount of the folder").isNotNull();
        assertThat(mount.path("read_only").asBoolean()).isTrue();
        assertThat(mount.path("host").asText()).isEqualTo(register.toString());

        // The record names what the run got: the head of the register's log and the digest of each file
        JsonNode recorded = folder.only("run.started").path("register");
        assertThat(recorded.path("seq").isInt()).isTrue();
        assertThat(recorded.path("head").asText()).matches("[0-9a-f]{64}");
        assertThat(recorded.path("files")).hasSize(4);
        for (JsonNode recordedFile : recorded.path("files")) {
            byte[] content = Files.readAllBytes(register.resolve(recordedFile.path("name").asText()));
            assertThat(recordedFile.path("sha256").asText()).isEqualTo(sha256(content));
            assertThat(recordedFile.path("size").asLong()).isEqualTo(content.length);
        }
        assertThat(Files.readString(register.resolve("findings.jsonl")).lines()).hasSize(2);
        Result verify = traceworks.exec("verify", folder.id());
        assertThat(verify.code()).as(verify.describe()).isZero();
        assertThat(verify.out()).containsPattern("register  ok \\(4 files of the register register-files at seq \\d+\\)");
        Result show = traceworks.exec("show", folder.id());
        assertThat(show.out()).containsPattern("Register  register-files at seq \\d+ \\(head [0-9a-f]{12}\\), 4 files"
                + " handed to the steps");

        Result third = traceworks.run("register-files");

        assertThat(third.code()).as(third.describe()).isZero();
        assertThat(traceworks.folder(third).output("known")).as("the resolved finding has a file of its own")
                .contains("findings: 1", "resolved: 1")
                .containsPattern("--- resolved.jsonl\\n\\{[^\\n]*\"status\":\"RESOLVED\",\"subject\":\"a.txt\"");
    }

    /** A file of the folder changed after the run: {@code verify} of the run says which, and exits 8. */
    @Test
    void verify_namesAFileOfTheFolderThatChangedAfterTheRun() throws IOException {
        file("a.txt");
        Result run = traceworks.run("register-files");
        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        Path findings = folder.dir().resolve("register").resolve("findings.jsonl");

        assertThat(findings.toFile().setWritable(true)).isTrue();
        Files.writeString(findings, "{\"forged\":true}\n", StandardCharsets.UTF_8);
        Result verify = traceworks.exec("verify", folder.id());

        assertThat(verify.code()).as(verify.describe()).isEqualTo(8);
        assertThat(verify.out()).contains("findings.jsonl is not the file the run started with");
        // The suite verifies every finished run when the test ends: the file as it was
        Files.write(findings, new byte[0]);
    }

    private void file(String name) {
        try {
            Files.writeString(traceworks.workspace().resolve(name), "line\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
