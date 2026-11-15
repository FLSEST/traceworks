package traceworks.adapters.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code <home>/credentials.json}: the values of secrets, in a file only its owner may read. */
class CredentialsFileTest {

    @TempDir
    Path home;

    private Path credentials(String json, String permissions) throws IOException {
        Path file = home.resolve("credentials.json");
        Files.writeString(file, json);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(permissions));
        return file;
    }

    @Test
    void missingFile_holdsNoSecret() {
        assertThat(CredentialsFile.read(home)).isEmpty();
    }

    @Test
    void modified_isTheFilesModificationTime_andNullWithoutAFile() throws IOException {
        assertThat(CredentialsFile.modified(home)).isNull();
        Path file = credentials("{\"DEPLOY_TOKEN\": \"deploy-token-value\"}", "rw-------");
        Instant at = Instant.parse("2026-11-03T12:00:00Z");
        Files.setLastModifiedTime(file, FileTime.from(at));

        assertThat(CredentialsFile.modified(home)).isEqualTo(at);
    }

    @Test
    void readsEveryNameAndValue() throws IOException {
        credentials("{\"DEPLOY_TOKEN\": \"deploy-token-value\", \"ANTHROPIC_API_KEY\": \"sk-ant-123456\"}",
                "rw-------");

        assertThat(CredentialsFile.read(home)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "DEPLOY_TOKEN", "deploy-token-value", "ANTHROPIC_API_KEY", "sk-ant-123456"));
    }

    @Test
    void credentialsFileReadableByOthers_isRefused() throws IOException {
        Path file = credentials("{\"DEPLOY_TOKEN\": \"deploy-token-value\"}", "rw-r--r--");

        assertThatThrownBy(() -> CredentialsFile.read(home))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(file.toString()).hasMessageContaining("chmod 600")
                .hasMessageNotContaining("deploy-token-value");
    }

    @Test
    void aFileTheGroupCanReadOrOthersCanChange_isRefusedToo() throws IOException {
        credentials("{\"DEPLOY_TOKEN\": \"deploy-token-value\"}", "rw-r-----");
        assertThatThrownBy(() -> CredentialsFile.read(home)).hasMessageContaining("chmod 600");

        credentials("{\"DEPLOY_TOKEN\": \"deploy-token-value\"}", "rw-----w-");
        assertThatThrownBy(() -> CredentialsFile.read(home)).hasMessageContaining("chmod 600");

        credentials("{\"DEPLOY_TOKEN\": \"deploy-token-value\"}", "r--------");
        assertThat(CredentialsFile.read(home)).containsKey("DEPLOY_TOKEN");
    }

    /** A message about the file never shows what is in it: a parse error would quote the token it stumbled on. */
    @Test
    void aBrokenFile_isRefused_withoutShowingItsContent() throws IOException {
        credentials("{\"DEPLOY_TOKEN\": unquoted-secret-value}", "rw-------");

        assertThatThrownBy(() -> CredentialsFile.read(home))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid JSON").hasMessageContaining("line 1")
                .hasMessageNotContaining("unquoted-secret-value")
                .satisfies(e -> assertThat(e.getCause()).isNull());
    }

    @Test
    void aValueThatIsNoString_orANameThatIsNoSecretName_isRefused_showingNeitherAValueNorAnInvalidKey() throws IOException {
        credentials("{\"DEPLOY_TOKEN\": 12345678901}", "rw-------");
        assertThatThrownBy(() -> CredentialsFile.read(home))
                .hasMessageContaining("DEPLOY_TOKEN").hasMessageContaining("string")
                .hasMessageNotContaining("12345678901");

        // A key that is no name is not shown either: a value pasted where the name belongs would be on the terminal
        credentials("{\"pasted-value-123\": \"DEPLOY_TOKEN\"}", "rw-------");
        assertThatThrownBy(() -> CredentialsFile.read(home))
                .hasMessageContaining("a key of").hasMessageContaining("is not a secret name")
                .hasMessageNotContaining("pasted-value-123");

        credentials("[\"deploy-token-value\"]", "rw-------");
        assertThatThrownBy(() -> CredentialsFile.read(home))
                .hasMessageContaining("object").hasMessageNotContaining("deploy-token-value");
    }

    private static String permissions(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }

    @Test
    void write_createsTheHomeAndTheFile_forTheirOwnerAlone() throws IOException {
        Path fresh = home.resolve("not").resolve("there").resolve(".traceworks");

        CredentialsFile.write(fresh, Map.of("DEPLOY_TOKEN", "deploy-token-value"));

        assertThat(permissions(fresh)).isEqualTo("rwx------");
        assertThat(permissions(fresh.resolve("credentials.json"))).isEqualTo("rw-------");
        assertThat(CredentialsFile.read(fresh)).containsExactly(Map.entry("DEPLOY_TOKEN", "deploy-token-value"));
    }

    @Test
    void write_replacesTheFile_keepsAnExistingHomeAsItIs_andLeavesNoOtherFile() throws IOException {
        Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwxr-xr-x"));
        credentials("{\"OLD_TOKEN\": \"old-token-value\"}", "rw-------");

        CredentialsFile.write(home, Map.of("B_TOKEN", "b-token-value", "A_TOKEN", "a-token-value"));

        assertThat(permissions(home)).isEqualTo("rwxr-xr-x");
        assertThat(permissions(home.resolve("credentials.json"))).isEqualTo("rw-------");
        assertThat(CredentialsFile.read(home)).containsOnlyKeys("A_TOKEN", "B_TOKEN");
        assertThat(Files.readString(home.resolve("credentials.json"))).as("sorted, one name per line")
                .isEqualToNormalizingNewlines("{\n  \"A_TOKEN\" : \"a-token-value\",\n  \"B_TOKEN\" : \"b-token-value\"\n}\n");
        try (var files = Files.list(home)) {
            assertThat(files.map(f -> f.getFileName().toString())).containsExactly("credentials.json");
        }
    }

    /** A file others can read is not replaced silently: its values may be known already. */
    @Test
    void write_refusesToReplaceAFileOthersCanRead_andLeavesItAsItIs() throws IOException {
        Path file = credentials("{\"OLD_TOKEN\": \"old-token-value\"}", "rw-r--r--");

        assertThatThrownBy(() -> CredentialsFile.write(home, Map.of("NEW_TOKEN", "new-token-value")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("chmod 600")
                .hasMessageNotContaining("new-token-value").hasMessageNotContaining("old-token-value");

        assertThat(Files.readString(file)).contains("OLD_TOKEN").doesNotContain("NEW_TOKEN");
        try (var files = Files.list(home)) {
            assertThat(files).hasSize(1);
        }
    }

    @Test
    void write_refusesANameThatIsNoName_beforeItTouchesAnything() {
        Path fresh = home.resolve("fresh");

        assertThatThrownBy(() -> CredentialsFile.write(fresh, Map.of("lower", "some-token-value")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'lower'")
                .hasMessageNotContaining("some-token-value");

        assertThat(fresh).doesNotExist();
    }

    @Test
    void write_anEmptyMap_leavesAnEmptyObject() {
        CredentialsFile.write(home, Map.of());

        assertThat(CredentialsFile.read(home)).isEmpty();
        assertThat(home.resolve("credentials.json")).exists();
    }

    @Test
    void write_aHomeThatCannotBeWritten_failsWithoutShowingAValue() throws IOException {
        Path blocked = Files.writeString(home.resolve("a-file"), "x").resolve("home");

        assertThatThrownBy(() -> CredentialsFile.write(blocked, Map.of("NEW_TOKEN", "new-token-value")))
                .isInstanceOf(java.io.UncheckedIOException.class).hasMessageContaining("Cannot write")
                .hasMessageNotContaining("new-token-value");
    }

    @Test
    void store_readsWritesAndNamesTheFile() {
        var store = CredentialsFile.store(home);

        store.write(Map.of("DEPLOY_TOKEN", "deploy-token-value"));

        assertThat(store.read()).containsExactly(Map.entry("DEPLOY_TOKEN", "deploy-token-value"));
        assertThat(store.location()).isEqualTo(home.resolve("credentials.json").toString())
                .isEqualTo(CredentialsFile.location(home));
    }
}
