package traceworks.adapters.pkg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackageDigestTest {

    @TempDir
    Path dir;

    private void fixture(boolean executableScript) throws IOException {
        Files.writeString(dir.resolve("a.txt"), "a\n");
        Files.createDirectories(dir.resolve("scripts"));
        Path script = dir.resolve("scripts/run.sh");
        Files.writeString(script, "echo hi\n");
        script.toFile().setExecutable(executableScript);
        Files.writeString(dir.resolve("workflow.json"), "{\"name\":\"fixture\",\"blocks\":[]}\n");
    }

    @Test
    void digest_followsTheSpecFormula() throws IOException {
        fixture(true);

        assertThat(PackageDigest.of(dir)).isEqualTo("ab37a689dfd34942ebd15d8ef8e534aa08179b813a7473db9923bf7d932acc4c");
    }

    @Test
    void executableBit_changesTheDigest() throws IOException {
        fixture(false);

        assertThat(PackageDigest.of(dir)).isEqualTo("e20d940f14ec025d5ea18a45dc300e063a3b5324259f2644111c470ca9228440");
    }

    @Test
    void theGitFolder_isExcluded() throws IOException {
        fixture(true);
        String without = PackageDigest.of(dir);
        Files.createDirectories(dir.resolve(".git"));
        Files.writeString(dir.resolve(".git/HEAD"), "ref: refs/heads/main\n");

        assertThat(PackageDigest.of(dir)).isEqualTo(without);
    }

    @Test
    void symbolicLink_isRefused() throws IOException {
        fixture(true);
        Files.createSymbolicLink(dir.resolve("scripts/link.sh"), Path.of("run.sh"));

        assertThatThrownBy(() -> PackageDigest.of(dir)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbolic link").hasMessageContaining("scripts/link.sh");
    }


    /** An image directory is a build context: the runtime sends all of it, so all of it is in the image's digest. */
    @Test
    void theDigestOfAnImageDirectory_coversAGitDirectoryInIt(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Dockerfile"), "FROM scratch\n");
        Files.createDirectories(dir.resolve(".git"));
        Files.writeString(dir.resolve(".git").resolve("payload"), "one");
        String before = ImageDirectories.of("tool", dir, true).digest();

        Files.writeString(dir.resolve(".git").resolve("payload"), "two");

        assertThat(ImageDirectories.of("tool", dir, true).digest()).isNotEqualTo(before);
        assertThat(PackageDigest.of(dir)).isEqualTo(PackageDigest.of(dir));
    }

    /**
     * A file name is bytes, and its text form is lossy: every byte that is no UTF-8 becomes U+FFFD. Two files whose
     * names differ only in such bytes would be one path in the digest, so a package could swap them unseen.
     */
    @Test
    void aFileNameThatIsNotItsOwnText_isRefused() throws Exception {
        fixture(true);
        Process touch = new ProcessBuilder("bash", "-c", "touch \"$1/$(printf 'a\\377')\" \"$1/$(printf 'a\\376')\"",
                "bash", dir.toString()).inheritIO().start();
        assertThat(touch.waitFor()).isZero();

        assertThatThrownBy(() -> PackageDigest.of(dir)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("file name").hasMessageContaining("not valid");
    }

    /** The seal of a package signs its digest, so it cannot be part of it. */
    @Test
    void theSealFile_isNotPartOfTheDigest() throws IOException {
        fixture(true);
        String without = PackageDigest.of(dir);

        Files.writeString(dir.resolve("traceworks.seal"), "{\"version\": 1}\n");
        assertThat(PackageDigest.of(dir)).isEqualTo(without);

        Files.writeString(dir.resolve("traceworks.seal"), "{\"version\": 2}\n");
        assertThat(PackageDigest.of(dir)).isEqualTo(without);
    }

    /** Only the seal file itself is left out: a file of that name elsewhere is a file of the package. */
    @Test
    void aFileNamedLikeTheSeal_inASubdirectory_orADirectoryOfThatName_isPartOfTheDigest() throws IOException {
        fixture(true);
        String without = PackageDigest.of(dir);

        Files.writeString(dir.resolve("scripts/traceworks.seal"), "payload\n");
        String nested = PackageDigest.of(dir);
        Files.delete(dir.resolve("scripts/traceworks.seal"));
        Files.createDirectories(dir.resolve("traceworks.seal"));
        Files.writeString(dir.resolve("traceworks.seal/payload.sh"), "payload\n");

        assertThat(nested).isNotEqualTo(without);
        assertThat(PackageDigest.of(dir)).isNotEqualTo(without).isNotEqualTo(nested);
    }

    @Test
    void aSealFileThatIsALink_isRefused(@TempDir Path other) throws IOException {
        fixture(true);
        Files.writeString(other.resolve("seal"), "{}");
        Files.createSymbolicLink(dir.resolve("traceworks.seal"), other.resolve("seal"));

        assertThatThrownBy(() -> PackageDigest.of(dir)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbolic link").hasMessageContaining("traceworks.seal");
    }

    /** An image directory is handed over whole: a file of that name in it counts. */
    @Test
    void theDigestOfAWholeDirectory_coversAFileNamedLikeTheSeal() throws IOException {
        fixture(true);
        String without = PackageDigest.ofAll(dir);

        Files.writeString(dir.resolve("traceworks.seal"), "payload\n");

        assertThat(PackageDigest.ofAll(dir)).isNotEqualTo(without);
    }
}
