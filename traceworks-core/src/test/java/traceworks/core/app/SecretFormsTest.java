package traceworks.core.app;

import traceworks.core.model.RunStatus;
import traceworks.core.record.RecordedEvent;
import traceworks.core.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static traceworks.core.app.AppFixture.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The forms in which a secret's value reaches the record other than as a step printed it: each is either redacted,
 * or refused at the start, before the run exists.
 */
class SecretFormsTest {

    private static final String REDACTED = "[redacted:DEPLOY_TOKEN]";

    private final AppFixture fixture = new AppFixture();

    SecretFormsTest() {
        fixture.commands.on("./print", request -> FakeCommandRunner.ok(
                "token: " + request.environment().getOrDefault("DEPLOY_TOKEN", "none")));
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Every line and every blob of the run, and its run.html, as text. */
    private List<String> everything(String runId) {
        List<String> all = new ArrayList<>();
        fixture.run(runId).log().readAll().forEach(line -> all.add(text(line)));
        fixture.run(runId).blobs().all().forEach(blob -> all.add(text(blob)));
        fixture.run(runId).view().ifPresent(all::add);
        return all;
    }

    private void assertNoFormIn(String runId, String... forms) {
        List<String> all = everything(runId);
        for (String form : forms) {
            assertThat(all).as(form).noneMatch(t -> t.contains(form));
        }
    }

    private RunResult runWith(String pkg, String secret, String blocks) {
        fixture.packages.put(packageOf(pkg), "{\"id\": \"wf-" + pkg + "\", \"name\": \"" + pkg + "\", \"blocks\": ["
                + blocks + "]}");
        fixture.credentials.put("DEPLOY_TOKEN", secret);
        return fixture.services().start(request(packageOf(pkg)), null, null);
    }

    private static Path packageOf(String pkg) {
        return Path.of("/pkg/" + pkg);
    }

    private static final String PRINT = """
            {"type": "shell", "name": "print", "config": {"command": "./print", "secrets": ["DEPLOY_TOKEN"]}}""";

    private static final String QUOTE = """
            , {"type": "shell", "name": "use", "config": {"command": "echo {{ previous_output }}",
               "shell_escape_variables": true}}""";

    /**
     * A shell placeholder would write a value with {@code '} as {@code '\''}, a form the record could not find in
     * every layer: such a value refuses the run at the start, naming the secret and the rule, never the value, and
     * nothing runs.
     */
    @Test
    void aValueWithASingleQuote_isRefusedAtStart_namingTheRule() {
        String quoted = "pa'ss-w0rd-x9";

        assertThatThrownBy(() -> runWith("quoted", quoted, PRINT + QUOTE))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("DEPLOY_TOKEN").hasMessageContaining("with '")
                .hasMessageContaining("shell placeholder").hasMessageNotContaining(quoted);
        assertThat(fixture.store.runIds()).as("refused before the run exists").isEmpty();
        assertThat(fixture.commands.requests()).isEmpty();
    }

    /**
     * A value without {@code '} reads the same in the quoted form a shell placeholder writes ({@code 'value'}), so the
     * recorded command, the step's input and run.html hold its name, not the value.
     */
    @Test
    void aValueWithoutASingleQuote_isRedactedInTheCommandAShellPlaceholderQuoted() {
        String secret = "s3cr3t-d3ploy-t0ken";
        fixture.commands.on("echo ", "echoed");

        RunResult result = runWith("unquoted", secret, PRINT + QUOTE);

        assertThat(result.status()).as(result.error()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fixture.commands.commands()).contains("echo 'token: " + secret + "'");
        RecordedEvent command = fixture.ofType(result.runId(), "step.command").getLast();
        assertThat(fixture.text(result.runId(), command, "command")).isEqualTo("echo 'token: " + REDACTED + "'");
        RecordedEvent finished = fixture.ofType(result.runId(), "step.finished").getLast();
        assertThat(fixture.text(result.runId(), finished, "input")).isEqualTo("echo 'token: " + REDACTED + "'");
        assertNoFormIn(result.runId(), secret);
    }

    /**
     * A value with a character outside the BMP, in a failing {@code foreach}: its summary lands in the step's metadata
     * blob and the checkpoint state, JSON blobs, which hold the character as UTF-8, so the value is redacted there as
     * in the text blobs, and nothing decodes back to it.
     */
    @Test
    void aValueWithACharacterOutsideTheBmp_isRedactedInTheJsonBlobs() throws Exception {
        String astral = "key-\uD83D\uDD11-0123456789";
        fixture.commands.on("./fail", FakeCommandRunner.exit(1, "cannot deploy with " + astral));

        RunResult result = runWith("astral", astral, """
                {"type": "foreach", "name": "each", "config": {"items": ["a", "b"], "command": "./fail",
                 "execution_mode": "shell", "secrets": ["DEPLOY_TOKEN"], "on_failure": "continue"}}""");

        assertThat(result.runId()).isNotNull();
        assertNoFormIn(result.runId(), astral, "\\ud83d\\udd11", "\\uD83D\\uDD11", "0123456789");
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        for (byte[] blob : fixture.run(result.runId()).blobs().all()) {
            if (blob.length > 0 && blob[0] == '{') {
                assertThat(json.readTree(blob).toString()).doesNotContain("0123456789");
            }
        }
        RecordedEvent finished = fixture.ofType(result.runId(), "step.finished").getLast();
        assertThat(fixture.text(result.runId(), finished, "metadata")).contains("cannot deploy with " + REDACTED);
    }

    private static final String SECRET = "s3cr3t-d3ploy-t0ken";
    /** What a stream broken off inside the secret ends with. */
    private static final String BROKEN = "config: token=" + SECRET.substring(0, 12);

    /**
     * A stream that a timeout, a cancel or a kill broke off may end inside a value, as one cut at the output limit
     * does: a shell step, an inner shell step of a wrapper and an {@code items_command} each keep it up to before the
     * value's first characters, so no part of the value reaches the record.
     */
    @Test
    void aStreamBrokenOffInsideASecret_leavesNoPartOfItInTheRecord() {
        fixture.commands.on("./slow", new traceworks.core.port.CommandRunner.CommandResult(143,
                        BROKEN, "", 60_000, true))
                .on("./cancelled", FakeCommandRunner.exit(143, BROKEN))
                .on("./killed", FakeCommandRunner.exit(137, BROKEN))
                .on("./list", FakeCommandRunner.exit(137, "a\n" + BROKEN));

        RunResult result = runWith("broken-off", SECRET, """
                {"type": "shell", "name": "slow", "config": {"command": "./slow", "secrets": ["DEPLOY_TOKEN"],
                 "on_failure": "continue"}},
                {"type": "shell", "name": "cancelled", "config": {"command": "./cancelled",
                 "secrets": ["DEPLOY_TOKEN"], "on_failure": "continue"}},
                {"type": "retry", "name": "again", "config": {"command": "./killed", "execution_mode": "shell",
                 "max_retries": 1, "delay": 0, "secrets": ["DEPLOY_TOKEN"], "on_failure": "continue"}},
                {"type": "foreach", "name": "each", "config": {"execution_mode": "shell", "items_source": "command",
                 "items_command": "./list", "command": "./item", "secrets": ["DEPLOY_TOKEN"]}}""");

        assertThat(result.runId()).isNotNull();
        assertThat(fixture.commands.commands()).contains("./slow", "./cancelled", "./killed", "./list");
        assertNoFormIn(result.runId(), SECRET.substring(0, 12));
        List<String> outputs = fixture.ofType(result.runId(), "step.output").stream()
                .map(event -> fixture.text(result.runId(), event, "output")).toList();
        assertThat(outputs).anyMatch(output -> output.startsWith("config: token=\nCommand timed out"))
                .contains("config: token=");
    }
}
