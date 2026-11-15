package traceworks.core.app;

import traceworks.core.record.RecordedEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static traceworks.core.app.AppFixture.ANTHROPIC_KEY;
import static traceworks.core.app.AppFixture.request;
import static traceworks.core.app.AppFixture.resumeOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An answer typed to a parked run is redacted as the run's resume would redact it: with the secrets its definition
 * declares and the credentials of its AI providers, from {@code credentials.json} and the environment. A run whose
 * package cannot be loaded takes no answer, with the reason the resume would give.
 */
class AnswerRedactionTest {

    private static final String SECRET = "s3cr3t-d3ploy-t0ken";
    private static final Path APPROVES = Path.of("/pkg/answer-approves");
    private static final Path ASKS = Path.of("/pkg/answer-asks");
    private static final Path AI = Path.of("/pkg/answer-ai");

    private final AppFixture fx = new AppFixture();

    AnswerRedactionTest() {
        fx.packages.put(APPROVES, """
                {"id": "wf-approves", "name": "Approves", "blocks": [
                  {"type": "approval", "name": "go", "config": {"question": "Go?", "options": ["Yes", "No"]}},
                  {"type": "shell", "name": "deploy", "config": {"command": "./deploy", "secrets": ["DEPLOY_TOKEN"]}}
                ]}""");
        fx.packages.put(ASKS, """
                {"id": "wf-asks", "name": "Asks", "blocks": [
                  {"type": "human-input", "name": "who", "config": {"prompt": "Who?", "variable_name": "who"}},
                  {"type": "shell", "name": "deploy", "config": {"command": "./deploy", "secrets": ["DEPLOY_TOKEN"]}}
                ]}""");
        fx.packages.put(AI, """
                {"id": "wf-ai", "name": "Ai", "requires": ["ai.tools"], "blocks": [
                  {"type": "approval", "name": "go", "config": {"question": "Go?", "options": ["Yes", "No"]}},
                  {"type": "simple", "name": "ask", "config": {"command": "Say hi", "tools": "read"}}
                ]}""");
        fx.credentials.put("DEPLOY_TOKEN", SECRET);
    }

    private String recorded(String id, String type, String field) {
        RecordedEvent event = fx.ofType(id, type).getFirst();
        return event.blob(field) == null ? null : fx.text(id, event, field);
    }

    private void assertNowhere(String id, String value) {
        for (byte[] line : fx.run(id).log().readAll()) {
            assertThat(new String(line, StandardCharsets.UTF_8)).doesNotContain(value);
        }
        fx.run(id).blobs().all().forEach(blob ->
                assertThat(new String(blob, StandardCharsets.UTF_8)).doesNotContain(value));
    }

    @Test
    void aDeclaredSecretInTheCommentOfAnApproval_isRecordedRedacted() {
        RunServices services = fx.services();
        String id = services.start(request(APPROVES), null, null).runId();

        AnswerResult answered = services.answer(id, new AnswerRequest("approval", 0, "Yes", null,
                "the token is " + SECRET));

        assertThat(answered.accepted()).as(answered.message()).isTrue();
        assertThat(recorded(id, "approval.answered", "comment")).isEqualTo("the token is [redacted:DEPLOY_TOKEN]");
        assertNowhere(id, SECRET);
    }

    @Test
    void aDeclaredSecretInAHumanInputAndItsComment_isRecordedRedacted_andTheResumeStillRuns() {
        fx.commands.on("./deploy", "deployed");
        RunServices services = fx.services();
        String id = services.start(request(ASKS), null, null).runId();

        AnswerResult answered = services.answer(id, new AnswerRequest("human-input", 0, null,
                Map.of("who", "me " + SECRET), "typed " + SECRET));

        assertThat(answered.accepted()).as(answered.message()).isTrue();
        assertThat(recorded(id, "approval.answered", "answer")).doesNotContain(SECRET)
                .contains("[redacted:DEPLOY_TOKEN]");
        assertThat(recorded(id, "approval.answered", "comment")).isEqualTo("typed [redacted:DEPLOY_TOKEN]");
        assertNowhere(id, SECRET);
        assertThat(services.resume(resumeOf(id), null, null).status().name()).isEqualTo("COMPLETED");
    }

    @Test
    void aCredentialOfTheRunsAiProvider_isRecordedRedactedToo() {
        fx.withClaude();
        RunServices services = fx.services();
        String id = services.start(request(AI), null, null).runId();

        AnswerResult answered = services.answer(id, new AnswerRequest("approval", 0, "Yes", null,
                "key " + ANTHROPIC_KEY));

        assertThat(answered.accepted()).as(answered.message()).isTrue();
        assertThat(recorded(id, "approval.answered", "comment")).isEqualTo("key [redacted:ANTHROPIC_API_KEY]");
        assertNowhere(id, ANTHROPIC_KEY);
    }

    @Test
    void anAnswerWithoutSecrets_isRecordedUnchanged() {
        RunServices services = fx.services();
        String id = services.start(request(APPROVES), null, null).runId();

        AnswerResult answered = services.answer(id, new AnswerRequest("approval", 0, "Yes", null, "ship it"));

        assertThat(answered.accepted()).isTrue();
        assertThat(recorded(id, "approval.answered", "answer")).isEqualTo("Yes");
        assertThat(recorded(id, "approval.answered", "comment")).isEqualTo("ship it");
    }

    @Test
    void aRunWhosePackageWasMovedAway_takesNoAnswer() {
        RunServices services = fx.services();
        String id = services.start(request(APPROVES), null, null).runId();
        fx.packages.remove(APPROVES);

        AnswerResult answered = services.answer(id, new AnswerRequest("approval", 0, "Yes", null,
                "the token is " + SECRET));

        assertThat(answered.accepted()).isFalse();
        assertThat(answered.message()).contains("Run " + id, "its resume would be refused",
                "The package of run " + id + " cannot be loaded", "/pkg/answer-approves");
        assertThat(fx.ofType(id, "approval.answered")).as("nothing is recorded").isEmpty();
    }
}
