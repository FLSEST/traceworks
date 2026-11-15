package traceworks.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The forms in which a secret reaches the record other than as a step printed it (docs/events.md, "Redaction"): each
 * is redacted in {@code events.jsonl}, the blobs, {@code run.html}, the manifest, the ledger entry and what Traceworks
 * prints, or refused before the run exists.
 */
class SecretFormsIT extends IntegrationTest {

    private static final String NAME = "IT_TOKEN";
    private static final String REDACTED = "[redacted:" + NAME + "]";

    /** A dummy value made for this test, never a real credential. */
    private final String id = UUID.randomUUID().toString().replace("-", "");

    /**
     * Package {@code secrets}: a value with {@code '}, which a shell placeholder would write as {@code '\''}, refuses
     * the run at the start (exit 6), naming the secret and the rule, never the value.
     */
    @Test
    void aValueWithASingleQuote_refusesTheRun_namingTheRule() {
        String value = "it-dummy'" + id;
        traceworks.credentials(Map.of(NAME, value));

        Result run = traceworks.run("secrets");

        assertThat(run.code()).as(run.describe()).isEqualTo(6);
        assertThat(run.err()).contains(NAME).contains("with '");
        assertThat(run.both()).doesNotContain(id);
        assertThat(traceworks.folders()).as("the start check refuses before a run exists").isEmpty();
    }

    /**
     * Package {@code secrets}: step {@code echo_output} quotes an output that holds the value through a shell
     * placeholder. The quoted form holds the value as it is, so the recorded command holds its name.
     */
    @Test
    void aValueAShellPlaceholderQuoted_isRedactedInTheRecordedCommand() throws Exception {
        String value = "it-dummy-" + id;
        traceworks.credentials(Map.of(NAME, value));

        Result run = traceworks.run("secrets");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.blobTexts()).anyMatch(text -> text.startsWith("echo previous: '")
                && text.contains("token is " + REDACTED + "'"));
        assertNoneIn(folder, run, List.of(value, id));
        assertThat(folder.html()).contains(REDACTED);
    }

    /**
     * Package {@code secret-astral}: a value with a character outside the BMP, which a failing {@code foreach} puts
     * into its metadata and the checkpoint state, JSON blobs. They hold the character as UTF-8, not as {@code \}
     * {@code u} escapes, so the value is redacted there, and no JSON reader decodes it back.
     */
    @Test
    void aValueWithACharacterOutsideTheBmp_isRedactedInTheJsonBlobs() throws Exception {
        String value = "it-dummy-\uD83D\uDD11-" + id;
        traceworks.credentials(Map.of(NAME, value));

        Result run = traceworks.run("secret-astral");

        assertThat(run.code()).as(run.describe()).isNotEqualTo(6);
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.output("each")).contains("cannot deploy with " + REDACTED);
        ObjectMapper json = new ObjectMapper();
        for (String blob : folder.blobTexts()) {
            if (blob.startsWith("{")) {
                assertThat(json.readTree(blob).toString()).doesNotContain(id);
            }
        }
        assertNoneIn(folder, run, List.of(value, "\\ud83d\\udd11", "\\uD83D\\uDD11", id));
        assertThat(folder.html()).contains(REDACTED);
    }

    /**
     * Package {@code secret-broken-off}: a step prints the first 20 characters of its secret, without a line feed, and
     * is stopped at its timeout. Its output ends before them, as a stream cut at the output limit does.
     */
    @Test
    void aStreamStoppedAtItsTimeout_keepsNoPartOfTheSecret() throws Exception {
        String value = "it-dummy-" + id;
        traceworks.credentials(Map.of(NAME, value));

        Result run = traceworks.run("secret-broken-off");

        assertThat(run.code()).as(run.describe()).isNotEqualTo(6);
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.output("slow")).startsWith("config: token=\n")
                .contains("Command timed out after 3 seconds");
        assertNoneIn(folder, run, List.of(value.substring(0, 20), id.substring(0, 11)));
    }

    /** No line, blob, page, manifest, ledger entry or printed text of the run holds any of {@code forms}. */
    private void assertNoneIn(RunFolder folder, Result run, List<String> forms) throws Exception {
        String events = Files.readString(folder.eventsFile());
        List<String> blobs = folder.blobTexts();
        String html = folder.html();
        String manifest = Files.readString(folder.dir().resolve("manifest.json"));
        String ledger = new String(folder.ledgerEntry(traceworks.home()), StandardCharsets.UTF_8);
        Result show = traceworks.exec("show", folder.id(), "--full");
        for (String form : forms) {
            assertThat(events).as(form).doesNotContain(form);
            assertThat(blobs).as(form).noneMatch(text -> text.contains(form));
            assertThat(html).as(form).doesNotContain(form);
            assertThat(manifest).as(form).doesNotContain(form);
            assertThat(ledger).as(form).doesNotContain(form);
            assertThat(run.both()).as(form).doesNotContain(form);
            assertThat(show.both()).as(form).doesNotContain(form);
        }
    }
}
