package traceworks.adapters.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import traceworks.core.port.StoredRun;
import traceworks.core.record.EventChain;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The register folder of a run in its run folder: written once, its files for reading only, read without a link. */
class FileRegisterFolderTest {

    @TempDir
    Path home;

    private StoredRun create() {
        return new FileRunStore(home.resolve("runs")).create("abcd-1", Instant.parse("2026-11-03T10:00:00Z"));
    }

    private static Map<String, byte[]> files() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("findings.jsonl", "{\"id\":\"a\"}\n".getBytes(StandardCharsets.UTF_8));
        files.put("previous-report.md", new byte[0]);
        return files;
    }

    @Test
    void theFolder_liesNextToTheScratch_andItsFilesAreForReadingOnly() throws Exception {
        try (StoredRun run = create()) {
            assertThat(run.registerFileDigests()).as("a run of no audit has none").isEmpty();

            String folder = run.writeRegisterFiles(files());

            assertThat(Path.of(folder)).isDirectory().isEqualTo(Path.of(run.location()).resolve("register"))
                    .isEqualTo(Path.of(run.registerDirectory()));
            Path findings = Path.of(folder).resolve("findings.jsonl");
            assertThat(Files.readString(findings)).isEqualTo("{\"id\":\"a\"}\n");
            assertThat(Files.isWritable(findings)).isFalse();
            assertThat(Files.isReadable(findings)).isTrue();
            assertThat(Path.of(folder).resolve("previous-report.md")).isEmptyFile();
            assertThat(run.registerFileDigests()).hasValueSatisfying(digests -> assertThat(digests)
                    .containsExactly(Map.entry("findings.jsonl", EventChain.sha256Hex(files().get("findings.jsonl"))),
                            Map.entry("previous-report.md", EventChain.sha256Hex(new byte[0]))));
        }
        try (StoredRun again = new FileRunStore(home.resolve("runs")).open("abcd-1")) {
            assertThat(again.registerFileDigests()).as("kept for a resume").hasValueSatisfying(
                    digests -> assertThat(digests).hasSize(2));
        }
    }

    @Test
    void aRun_getsItsFolderOnce_andOnlyPlainFileNames() {
        try (StoredRun run = create()) {
            assertThatThrownBy(() -> run.writeRegisterFiles(Map.of("../escape", new byte[0])))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("../escape");
        }
        try (StoredRun run = new FileRunStore(home.resolve("runs")).create("abcd-2",
                Instant.parse("2026-11-03T10:00:00Z"))) {
            run.writeRegisterFiles(files());

            assertThatThrownBy(() -> run.writeRegisterFiles(files())).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("exists already");
        }
    }

    /** What a host step can do to the folder shows in the digests: a changed file, a new one, a link in a file's place. */
    @Test
    void theDigests_nameEveryEntry_andNeverFollowALink(@TempDir Path elsewhere) throws Exception {
        try (StoredRun run = create()) {
            Path folder = Path.of(run.writeRegisterFiles(files()));
            Path secret = Files.writeString(elsewhere.resolve("secret"), "{\"id\":\"a\"}\n");
            Files.delete(folder.resolve("findings.jsonl"));
            Files.createSymbolicLink(folder.resolve("findings.jsonl"), secret);
            Files.writeString(folder.resolve("added.txt"), "x");
            Files.createDirectory(folder.resolve("sub"));

            Map<String, String> digests = run.registerFileDigests().orElseThrow();

            assertThat(digests).containsEntry("findings.jsonl", StoredRun.NOT_A_FILE)
                    .containsEntry("added.txt", EventChain.sha256Hex("x".getBytes(StandardCharsets.UTF_8)))
                    .containsEntry("sub", StoredRun.NOT_A_FILE).containsKey("previous-report.md").hasSize(4);
        }
    }

    @Test
    void aLinkInTheFoldersPlace_isNoFolder(@TempDir Path elsewhere) throws Exception {
        try (StoredRun run = create()) {
            Files.createSymbolicLink(Path.of(run.location()).resolve("register"), elsewhere);

            assertThat(run.registerFileDigests()).isEmpty();
            assertThatThrownBy(() -> run.writeRegisterFiles(files())).isInstanceOf(IllegalStateException.class);
        }
    }

    /** A refused start discards its run: the folder, with its read-only files, goes with it. */
    @Test
    void discard_removesTheFolderToo() {
        Path location;
        try (StoredRun run = create()) {
            run.tryLockWriter();
            run.writeRegisterFiles(files());
            location = Path.of(run.location());

            run.discard();
        }
        assertThat(location).doesNotExist();
    }
}
