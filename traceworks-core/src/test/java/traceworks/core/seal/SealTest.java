package traceworks.core.seal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SealTest {

    static final String DIGEST = "ab".repeat(32);
    static final Instant AT = Instant.parse("2026-11-13T12:00:00.123Z");

    private static Seal seal() {
        return new Seal("wf-build", DIGEST, "operator@box", AT, SshSignatureTest.ARMORED);
    }

    @Test
    void thePayload_namesEveryFieldTheSignatureCovers() {
        assertThat(new String(seal().payload(), StandardCharsets.UTF_8)).isEqualTo("traceworks-seal-v1\n"
                + "workflow_id=wf-build\ndigest=" + DIGEST + "\nsigner=operator@box\nsealed_at=2026-11-13T12:00:00.123Z\n");
    }

    @Test
    void theFile_readsBackAsWritten() {
        byte[] json = seal().toJson();

        assertThat(new String(json, StandardCharsets.UTF_8)).startsWith("{\n  \"version\": 1,\n  \"workflow_id\": \"wf-build\",\n")
                .endsWith("}\n");
        assertThat(Seal.parse(json)).isEqualTo(seal());
    }

    @Test
    void aFileThatIsNotWrittenAsTraceworksWritesIt_isRefused() {
        String text = new String(seal().toJson(), StandardCharsets.UTF_8);

        assertRefused(text.replace("{\n", "{\n  \"extra\": \"hidden\",\n"), "extra");
        assertRefused(text + "\n", "written");
        assertRefused(text.replace("\"version\": 1", "\"version\":   1"), "written");
        assertRefused(text.replace("\"version\": 1", "\"version\": 2"), "version");
        assertRefused(text.replace(DIGEST, "xyz"), "digest");
        assertRefused(text.replace("\"operator@box\"", "7"), "signer");
        assertRefused(text.replace("2026-11-13T12:00:00.123Z", "yesterday"), "sealed_at");
        assertRefused("[]", "JSON object");
        assertRefused("{", "JSON");
        assertRefused("", "JSON");
    }

    @Test
    void aFieldWithALineFeed_cannotBeSealed() {
        assertThatThrownBy(() -> new Seal("wf\nbuild", DIGEST, "operator", AT, "s")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Seal("wf", DIGEST, "operator\ndigest=0", AT, "s")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Seal("", DIGEST, "operator", AT, "s")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Seal("wf", DIGEST.toUpperCase(), "operator", AT, "s"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theTime_isKeptToTheMillisecond() {
        Seal fine = new Seal("wf", DIGEST, "operator", Instant.parse("2026-11-13T12:00:00.123456789Z"), "s");

        assertThat(fine.sealedAt()).isEqualTo(AT);
    }

    @Test
    void aFileBeyondTheLimit_isRefusedUnread() {
        byte[] huge = new byte[Seal.MAX_BYTES + 1];

        assertThatThrownBy(() -> Seal.parse(huge)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("larger");
    }

    private static void assertRefused(String text, String naming) {
        assertThatThrownBy(() -> Seal.parse(text.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(naming);
    }
}
