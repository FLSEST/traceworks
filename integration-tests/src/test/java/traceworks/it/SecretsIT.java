package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A secret from {@code credentials.json} (package {@code secrets}, docs/cli.md "Secrets", docs/events.md "Redaction"):
 * it reaches only the step that declares it, and its value is in no line, no blob, no {@code run.html}, no manifest,
 * no ledger entry and nothing Traceworks prints. Only the scratch directory, which is no part of the record, keeps what
 * the step wrote.
 */
class SecretsIT extends IntegrationTest {

    private static final String NAME = "IT_TOKEN";
    private static final String REDACTED = "[redacted:" + NAME + "]";

    /** A dummy value made for this test, never a real credential. */
    private final String value = "it-dummy-" + UUID.randomUUID().toString().replace("-", "");

    @Test
    void theSecretReachesItsStep_andTheRecordHoldsOnlyItsName() throws Exception {
        traceworks.credentials(Map.of(NAME, value));

        Result run = traceworks.run("secrets");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        // The step got the value: it printed its hash, and the value itself only as its redaction
        assertThat(folder.output("use_secret")).isEqualTo(sha256(value) + "\ntoken is " + REDACTED);
        assertThat(folder.output("without_secret")).isEqualTo("token here: unset");
        assertThat(folder.output("echo_output")).contains(REDACTED);
        assertThat(folder.ofStep("step.command", folder.stepIndex("use_secret")).getFirst().path("env_names"))
                .extracting(JsonNode::asText).containsExactly(NAME);
        assertThat(folder.ofStep("step.command", folder.stepIndex("without_secret")).getFirst().path("env_names"))
                .isEmpty();

        String events = Files.readString(folder.eventsFile());
        assertThat(events).doesNotContain(value);
        assertThat(folder.blobTexts()).noneMatch(text -> text.contains(value))
                .anyMatch(text -> text.contains(REDACTED));
        assertThat(folder.html()).doesNotContain(value).contains(REDACTED);
        assertThat(Files.readString(folder.dir().resolve("manifest.json"))).doesNotContain(value);
        assertThat(new String(folder.ledgerEntry(traceworks.home()), StandardCharsets.UTF_8)).doesNotContain(value);
        assertThat(run.both()).doesNotContain(value).contains(REDACTED);
        Result show = traceworks.exec("show", folder.id(), "--full");
        assertThat(show.both()).doesNotContain(value).contains(REDACTED);

        // The scratch file that holds the value is listed without content or hash; the file itself stays
        JsonNode listed = folder.ofStep("step.files", folder.stepIndex("use_secret")).getFirst().path("files").get(0);
        assertThat(listed.path("path").asText()).isEqualTo("token.txt");
        assertThat(listed.path("reason").asText()).isEqualTo("contains a secret");
        assertThat(listed.path("sha256").isNull()).isTrue();
        assertThat(listed.path("blob").isNull()).isTrue();
        assertThat(folder.dir().resolve("scratch").resolve("token.txt")).hasContent(value);
    }

    /**
     * A secret's value that occurs literally in workflow.json (package {@code secrets-resume}): the step after a park
     * and a resume runs the definition as written, not the redacted copy in {@code run.started}, and the record still
     * holds only the name.
     */
    @Test
    void aSecretsValueInTheDefinition_runsAsWrittenAfterAResume() throws Exception {
        String host = "it-registry.example.test";   // a dummy value, never a real credential
        traceworks.credentials(Map.of("IT_HOST", host));

        Result run = traceworks.run("secrets-resume");
        assertThat(run.code()).as(run.describe()).isEqualTo(5);
        String id = run.runId();
        Result approve = traceworks.exec("approve", id, "--answer", "Yes");
        assertThat(approve.code()).as(approve.describe()).isZero();
        Result resume = traceworks.exec("resume", id, "--no-input");

        assertThat(resume.code()).as(resume.describe()).isZero();
        RunFolder folder = traceworks.folder(id);
        assertThat(folder.dir().resolve("scratch").resolve("before.txt")).hasContent("host " + host);
        assertThat(folder.dir().resolve("scratch").resolve("after.txt")).hasContent("host " + host);
        assertThat(Files.readString(folder.eventsFile())).doesNotContain(host);
        assertThat(folder.blobTexts()).noneMatch(text -> text.contains(host))
                .anyMatch(text -> text.contains("host [redacted:IT_HOST]"));
        assertThat(resume.both()).doesNotContain(host);
    }

    /**
     * A credentials file modified while the run was parked (package {@code secrets-resume}): the resume warns, naming
     * the file and the secret, never its value, on the output its notes use, and the run completes as before.
     */
    @Test
    void aCredentialsFileModifiedWhileTheRunWasParked_warnsAtTheResume_andTheRunCompletes() throws Exception {
        String host = "it-registry.example.test";   // a dummy value, never a real credential
        traceworks.credentials(Map.of("IT_HOST", host));
        Result run = traceworks.run("secrets-resume");
        assertThat(run.code()).as(run.describe()).isEqualTo(5);
        String id = run.runId();
        Result approve = traceworks.exec("approve", id, "--answer", "Yes");
        assertThat(approve.code()).as(approve.describe()).isZero();
        Path file = traceworks.home().resolve("credentials.json");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(1)));   // touched, not changed

        Result resume = traceworks.exec("resume", id, "--no-input");

        assertThat(resume.code()).as(resume.describe()).isZero();
        assertThat(resume.err()).contains("traceworks: warning: credentials.json", "IT_HOST", "docs/security.md")
                .doesNotContain(host);
        assertThat(resume.out()).doesNotContain("warning:");
        assertThat(traceworks.folder(id).dir().resolve("scratch").resolve("after.txt")).hasContent("host " + host);
        assertThat(Files.readString(traceworks.folder(id).eventsFile())).doesNotContain("modified after");
    }

    @Test
    void aCredentialsFileOthersCanRead_refusesTheRun() throws Exception {
        traceworks.credentials(Map.of(NAME, value));
        Path file = traceworks.home().resolve("credentials.json");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        Result run = traceworks.run("secrets");

        assertThat(run.code()).as(run.describe()).isEqualTo(6);
        assertThat(run.both()).doesNotContain(value);
        assertThat(traceworks.folders()).as("the start check refuses before a run exists").isEmpty();
    }

    @Test
    void aSecretWithoutAValue_refusesTheRun_namingIt() {
        Result run = traceworks.run("secrets");

        assertThat(run.code()).as(run.describe()).isEqualTo(6);
        assertThat(run.err()).contains(NAME, "'traceworks credentials set <NAME>'",
                traceworks.home().resolve("credentials.json").toString());
        assertThat(traceworks.folders()).isEmpty();
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
