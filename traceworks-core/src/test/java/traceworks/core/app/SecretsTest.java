package traceworks.core.app;

import traceworks.core.record.RunSnapshotJson;
import traceworks.core.model.RunStatus;
import traceworks.core.port.CommandRunner.CommandRequest;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.record.RecordedEvent;
import traceworks.core.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static traceworks.core.app.AppFixture.request;
import static traceworks.core.app.AppFixture.resumeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Secrets of a run: the names a definition declares need a value of at least eight characters when
 * the run starts, from {@code credentials.json} or the environment; the step gets the values as environment
 * variables, and the record holds the names only.
 */
class SecretsTest {

    private static final String SECRET = "s3cr3t-d3ploy-t0ken";
    private static final String REDACTED = "[redacted:DEPLOY_TOKEN]";
    private static final Path DEPLOY = Path.of("/pkg/secret-deploy");
    private static final Path NESTED = Path.of("/pkg/secret-nested");
    private static final Path RESERVED = Path.of("/pkg/secret-reserved");
    private static final Path PARKS = Path.of("/pkg/secret-parks");
    private static final Path EACH = Path.of("/pkg/secret-each");
    /** The first and the last characters of the secret: what a cut through it would leave. */
    private static final List<String> FRAGMENTS = List.of(SECRET.substring(0, 6),
            SECRET.substring(SECRET.length() - 6));

    private final AppFixture fixture = new AppFixture();

    SecretsTest() {
        fixture.packages.put(DEPLOY, """
                {"id": "wf-secret", "name": "Secret", "blocks": [
                  {"type": "shell", "name": "deploy", "config": {"command": "./deploy", "secrets": ["DEPLOY_TOKEN"]}},
                  {"type": "shell", "name": "check", "config": {"command": "./check"}}
                ]}""");
        fixture.packages.put(NESTED, """
                {"id": "wf-nested", "name": "Nested", "blocks": [
                  {"type": "retry", "name": "again", "config": {"command": "./deploy", "execution_mode": "shell",
                   "secrets": ["RETRY_TOKEN"]}},
                  {"type": "switch", "name": "pick", "config": {"expression": "a", "cases": [{"value": "a",
                   "blocks": [{"type": "shell", "config": {"command": "./deploy", "secrets": ["CASE_TOKEN"]}}]}]}},
                  {"type": "sub-workflow", "name": "child", "config": {"workflow_id": "./child"}}
                ]}""");
        fixture.packages.put(NESTED.resolve("child"), """
                {"id": "wf-nested-child", "name": "Child", "blocks": [
                  {"type": "shell", "name": "inner", "config": {"command": "./deploy", "secrets": ["CHILD_TOKEN"]}}
                ]}""");
        fixture.packages.put(RESERVED, """
                {"id": "wf-reserved", "name": "Reserved", "blocks": [
                  {"type": "shell", "name": "deploy", "config": {"command": "./deploy", "secrets": ["PATH"]}}
                ]}""");
        fixture.packages.put(PARKS, """
                {"id": "wf-parks", "name": "Parks", "blocks": [
                  {"type": "approval", "name": "go", "config": {"question": "Go?", "options": ["Yes", "No"]}},
                  {"type": "shell", "name": "deploy", "config": {"command": "./deploy", "secrets": ["DEPLOY_TOKEN"]}}
                ]}""");
        fixture.packages.put(EACH, """
                {"id": "wf-each", "name": "Each", "blocks": [
                  {"type": "foreach", "name": "each", "config": {"items": ["a"], "command": "./item",
                   "execution_mode": "shell", "secrets": ["DEPLOY_TOKEN"]}}
                ]}""");
        fixture.commands.on("./deploy", request -> FakeCommandRunner.ok("deployed with "
                + request.environment().getOrDefault("DEPLOY_TOKEN", "nothing"))).on("./check", "checked");
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void missingSecret_isRefusedAtStart_namingIt() {
        fixture.environment.put("UNRELATED", "unrelated-value");

        assertThatThrownBy(() -> fixture.services().start(request(DEPLOY), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("DEPLOY_TOKEN").hasMessageContaining("credentials.json")
                .hasMessageContaining("environment");
        assertThat(fixture.store.runIds()).as("refused before the run exists").isEmpty();
        assertThat(fixture.commands.requests()).isEmpty();
    }

    /** The refusal says how to give the value: the command, the file it writes, and the environment. */
    @Test
    void missingSecret_theRefusalNamesTheCommandAndTheFile() {
        fixture.credentialsLocation = "/home/x/.traceworks/credentials.json";

        assertThatThrownBy(() -> fixture.services().start(request(DEPLOY), null, null))
                .hasMessageContaining("secrets without a value: DEPLOY_TOKEN — store them with 'traceworks"
                        + " credentials set <NAME>' (in /home/x/.traceworks/credentials.json), or set them as"
                        + " environment variables of Traceworks");
    }

    @Test
    void missingSecret_withoutAKnownFile_theRefusalNamesTheHome() {
        assertThatThrownBy(() -> fixture.services().start(request(DEPLOY), null, null))
                .hasMessageContaining("'traceworks credentials set <NAME>' (in credentials.json in the Traceworks"
                        + " home)");
    }

    /** {@code secret_names} keeps a value of the environment from the run, and the refusal says so. */
    @Test
    void aSecretTheAllowlistDoesNotName_isNotTakenFromTheEnvironment_andTheRefusalSaysSo() {
        fixture.environment.put("DEPLOY_TOKEN", SECRET);
        fixture.environmentAllowed = "OTHER_NAME"::equals;

        assertThatThrownBy(() -> fixture.services().start(request(DEPLOY), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("secrets without a value: DEPLOY_TOKEN")
                .hasMessageContaining(". DEPLOY_TOKEN is set in the environment, but 'secret_names' in"
                        + " traceworks.json does not allow it")
                .hasMessageNotContaining(SECRET);
        assertThat(fixture.store.runIds()).isEmpty();
    }

    /** The file is not subject to the allowlist: it is the operator's own. */
    @Test
    void aSecretTheAllowlistDoesNotName_stillComesFromTheFile() {
        fixture.credentials.put("DEPLOY_TOKEN", SECRET);
        fixture.environmentAllowed = name -> false;

        RunResult result = fixture.services().start(request(DEPLOY), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fixture.commands.requests().getFirst().environment()).containsEntry("DEPLOY_TOKEN", SECRET);
    }

    @Test
    void secretShorterThan8_isRefusedAtStart() {
        fixture.environment.put("DEPLOY_TOKEN", "abc1234");

        assertThatThrownBy(() -> fixture.services().start(request(DEPLOY), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("DEPLOY_TOKEN").hasMessageContaining("8 characters")
                .hasMessageNotContaining("abc1234");
        assertThat(fixture.store.runIds()).isEmpty();
    }

    /** The foreach run error cuts its first failure's output 80 characters in; never inside a value. */
    @Test
    void aForeachRunError_neverCutsASecretInTwo() {
        fixture.credentials.put("DEPLOY_TOKEN", SECRET);
        fixture.commands.on("./item", request -> FakeCommandRunner.exit(1, "x".repeat(72)
                + request.environment().get("DEPLOY_TOKEN") + "y".repeat(300)));

        RunResult result = fixture.services().start(request(EACH), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.error()).contains("x".repeat(72));
        for (String fragment : FRAGMENTS) {
            assertThat(result.error()).doesNotContain(fragment);
        }
        assertNoSecretIn(result.runId());
    }

    /**
     * A stream that the output limit cut inside the secret ends with its first characters, which no exact match
     * finds: the step's output keeps none of them, on stdout and on stderr, so neither the record nor run.html holds
     * a part of the value.
     */
    @Test
    void aStreamCutInsideTheSecret_leavesNoPartOfItInTheRecord() {
        fixture.credentials.put("DEPLOY_TOKEN", SECRET);
        String bearer = "+ curl -H 'Authorization: Bearer ";
        fixture.commands.on("./deploy", request -> new CommandResult(153, bearer + SECRET.substring(0, 12), "",
                900, false, List.of(CommandResult.STDOUT))).on("./check", request -> new CommandResult(153, "",
                bearer + SECRET.substring(0, 7), 900, false, List.of(CommandResult.STDERR)));
        fixture.packages.put(DEPLOY, """
                {"id": "wf-secret", "name": "Secret", "blocks": [
                  {"type": "shell", "name": "deploy", "config": {"command": "./deploy", "secrets": ["DEPLOY_TOKEN"],
                   "on_failure": "continue"}},
                  {"type": "shell", "name": "check", "config": {"command": "./check", "secrets": ["DEPLOY_TOKEN"]}}
                ]}""");

        RunResult result = fixture.services().start(request(DEPLOY), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        List<RecordedEvent> outputs = fixture.ofType(result.runId(), "step.output");
        assertThat(outputs).hasSize(2);
        assertThat(fixture.text(result.runId(), outputs.get(0), "output")).startsWith(bearer + "\nThe command's"
                + " standard output exceeded 16 MiB");
        assertThat(fixture.text(result.runId(), outputs.get(1), "output")).startsWith(bearer + "\nThe command's"
                + " standard error exceeded 16 MiB");
        assertThat(fixture.run(result.runId()).view()).hasValueSatisfying(html ->
                FRAGMENTS.forEach(fragment -> assertThat(html).doesNotContain(fragment)));
        assertNoSecretIn(result.runId());
    }

    /** Such a value is escaped once more in every layer of JSON, so no exact match finds it. */
    @Test
    void aValueWithAQuoteABackslashOrAControlCharacter_isRefusedAtStart_namingTheRule() {
        for (String value : List.of("ab\"cd-efgh12", "ab\\cd-efgh12", "ab\tcd-efgh12", "ab\u007fcd-efgh12")) {
            fixture.environment.put("DEPLOY_TOKEN", value);

            assertThatThrownBy(() -> fixture.services().start(request(DEPLOY), null, null)).as(value)
                    .isInstanceOf(RunRefusedException.class)
                    .hasMessageContaining("DEPLOY_TOKEN").hasMessageContaining("control character")
                    .hasMessageNotContaining(value);
        }
        assertThat(fixture.store.runIds()).isEmpty();
    }

    /** Shell outputs are trimmed, so a value with whitespace at an edge is recorded without it. */
    @Test
    void aValueWithWhitespaceAtAnEdge_isRefusedAtStart_namingTheRule() {
        fixture.environment.put("DEPLOY_TOKEN", "abcdefgh1 ");

        assertThatThrownBy(() -> fixture.services().start(request(DEPLOY), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("DEPLOY_TOKEN").hasMessageContaining("whitespace")
                .hasMessageNotContaining("abcdefgh1");
        assertThat(fixture.store.runIds()).isEmpty();
    }

    /** A digits-only value can stand in a JSON number, where a replacement breaks the record. */
    @Test
    void aValueOfDigitsOnly_isRefusedAtStart_namingTheRule() {
        fixture.environment.put("DEPLOY_TOKEN", "1234567890");

        assertThatThrownBy(() -> fixture.services().start(request(DEPLOY), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("DEPLOY_TOKEN").hasMessageContaining("only digits")
                .hasMessageNotContaining("1234567890");
    }

    /** So does a value that reads as a JSON number, with the same reason. */
    @Test
    void aValueThatReadsAsAJsonNumber_isRefusedAtStart_withTheSameReason() {
        fixture.environment.put("DEPLOY_TOKEN", "-1234.5678");

        assertThatThrownBy(() -> fixture.services().start(request(DEPLOY), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("DEPLOY_TOKEN").hasMessageContaining("JSON number")
                .hasMessageNotContaining("1234.5678");
    }

    @Test
    void aValueThatContainsTheReplacementMarker_isRefusedAtStart() {
        fixture.environment.put("DEPLOY_TOKEN", "x[redacted:OTHER]y");

        assertThatThrownBy(() -> fixture.services().start(request(DEPLOY), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("DEPLOY_TOKEN").hasMessageContaining("[redacted:'")
                .hasMessageNotContaining("x[redacted:OTHER]y");
    }

    @Test
    void secret_reachesTheStepAsEnvironment_andOnlyItsNameIsRecorded() {
        fixture.credentials.put("DEPLOY_TOKEN", SECRET);

        RunResult result = fixture.services().start(request(DEPLOY), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        List<CommandRequest> requests = fixture.commands.requests();
        assertThat(requests.get(0).environment()).containsExactly(java.util.Map.entry("DEPLOY_TOKEN", SECRET));
        assertThat(requests.get(1).environment()).as("only the step that declares it").isEmpty();
        assertThat(requests.get(0).toString()).doesNotContain(SECRET);
        RecordedEvent command = fixture.ofType(result.runId(), "step.command").get(0);
        assertThat(command.fields().get("env_names")).extracting(n -> n.asText()).containsExactly("DEPLOY_TOKEN");
        assertNoSecretIn(result.runId());
    }

    @Test
    void theFile_winsOverTheEnvironment() {
        fixture.credentials.put("DEPLOY_TOKEN", SECRET);
        fixture.environment.put("DEPLOY_TOKEN", "from-the-environment");

        fixture.services().start(request(DEPLOY), null, null);

        assertThat(fixture.commands.requests().get(0).environment()).containsEntry("DEPLOY_TOKEN", SECRET);
    }

    @Test
    void aValueFromTheEnvironment_counts() {
        fixture.environment.put("DEPLOY_TOKEN", SECRET);

        RunResult result = fixture.services().start(request(DEPLOY), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fixture.commands.requests().get(0).environment()).containsEntry("DEPLOY_TOKEN", SECRET);
    }

    @Test
    void stepThatEchoesItsSecret_leavesNoTraceInEventsBlobsHtmlOrShow() {
        fixture.credentials.put("DEPLOY_TOKEN", SECRET);

        RunResult result = fixture.services().start(request(DEPLOY), null, null);

        String runId = result.runId();
        assertNoSecretIn(runId);
        assertThat(fixture.run(runId).view()).hasValueSatisfying(html ->
                assertThat(html).doesNotContain(SECRET).contains(REDACTED));
        RecordedEvent output = fixture.ofType(runId, "step.output").get(0);
        assertThat(fixture.text(runId, output, "output")).isEqualTo("deployed with " + REDACTED);
        RecordedEvent checkpoint = fixture.ofType(runId, "run.checkpoint").getLast();
        byte[] state = fixture.run(runId).blobs().get(checkpoint.blob("state").sha256()).orElseThrow();
        assertThat(RunSnapshotJson.references(state)).extracting(RunSnapshotJson.Reference::blob)
                .as("the checkpoint refers to the redacted output").contains(output.blob("output"));
        assertThat(fixture.commands.requests().get(1).command()).isEqualTo("./check");
    }

    @Test
    void theErrorOfTheRunResult_isRedacted() {
        fixture.credentials.put("DEPLOY_TOKEN", SECRET);
        fixture.commands.on("./deploy", request -> FakeCommandRunner.exit(1, "refused "
                + request.environment().get("DEPLOY_TOKEN")));

        RunResult result = fixture.services().start(request(DEPLOY), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.error()).contains(REDACTED).doesNotContain(SECRET);
        assertNoSecretIn(result.runId());
    }

    @Test
    void secretsOfNestedBlocksAndOfChildWorkflows_areCheckedAtStartToo() {
        assertThatThrownBy(() -> fixture.services().start(request(NESTED), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("CASE_TOKEN").hasMessageContaining("CHILD_TOKEN")
                .hasMessageContaining("RETRY_TOKEN");
    }

    @Test
    void theInnerShellStepsOfWrappersAndChildren_getTheirSecrets() {
        fixture.environment.put("RETRY_TOKEN", "retry-token-value");
        fixture.environment.put("CASE_TOKEN", "case-token-value");
        fixture.environment.put("CHILD_TOKEN", "child-token-value");

        RunResult result = fixture.services().start(request(NESTED), null, null);

        assertThat(result.status()).as(result.error()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fixture.commands.requests()).extracting(CommandRequest::environment).containsExactly(
                java.util.Map.of("RETRY_TOKEN", "retry-token-value"),
                java.util.Map.of("CASE_TOKEN", "case-token-value"),
                java.util.Map.of("CHILD_TOKEN", "child-token-value"));
    }

    @Test
    void aReservedSecretName_isRefusedAtStart_beforeAnyValueIsLookedUp() {
        fixture.environment.put("PATH", "/usr/bin:/bin:/usr/local/bin");

        assertThatThrownBy(() -> fixture.services().start(request(RESERVED), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("PATH").hasMessageContaining("reserved");
        assertThat(fixture.store.runIds()).isEmpty();
    }

    @Test
    void credentialsThatCannotBeRead_refuseOnlyARunThatDeclaresSecrets() {
        fixture.credentialsFailure = new IllegalArgumentException("/home/x/credentials.json can be read by others;"
                + " run chmod 600 /home/x/credentials.json");

        assertThatThrownBy(() -> fixture.services().start(request(DEPLOY), null, null))
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("chmod 600");
        assertThat(fixture.services().start(request(AppFixture.BUILD), null, null).status())
                .isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void resume_checksTheSecretsAgain_andRedactsWithTheValuesItHasNow() {
        fixture.environment.put("DEPLOY_TOKEN", SECRET);
        RunResult parked = fixture.services().start(request(PARKS), null, null);
        assertThat(parked.status()).isEqualTo(RunStatus.PARKED);
        fixture.services().answer(parked.runId(), new AnswerRequest("approval", 0, "Yes", null, null));
        fixture.environment.remove("DEPLOY_TOKEN");

        assertThatThrownBy(() -> fixture.services().resume(resumeOf(parked.runId()), null, null))
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("DEPLOY_TOKEN");

        fixture.environment.put("DEPLOY_TOKEN", SECRET);
        RunResult resumed = fixture.services().resume(resumeOf(parked.runId()), null, null);
        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fixture.commands.requests().getLast().environment()).containsEntry("DEPLOY_TOKEN", SECRET);
        assertNoSecretIn(parked.runId());
    }

    /** No line and no blob of the run holds the secret. */
    private void assertNoSecretIn(String runId) {
        for (byte[] line : fixture.run(runId).log().readAll()) {
            assertThat(text(line)).doesNotContain(SECRET);
            for (String fragment : FRAGMENTS) {
                assertThat(text(line)).doesNotContain(fragment);
            }
        }
        for (byte[] blob : fixture.run(runId).blobs().all()) {
            assertThat(text(blob)).doesNotContain(SECRET);
            for (String fragment : FRAGMENTS) {
                assertThat(text(blob)).doesNotContain(fragment);
            }
        }
        fixture.run(runId).readManifest().ifPresent(manifest -> assertThat(text(manifest)).doesNotContain(SECRET));
    }
}
