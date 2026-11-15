package traceworks.core.app;

import traceworks.core.engine.EventLogException;
import traceworks.core.model.RunStatus;
import traceworks.core.record.RecordedEvent;
import traceworks.core.testing.FakeCommandRunner;
import traceworks.core.testing.InMemoryEventLog;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static traceworks.core.app.AppFixture.ANTHROPIC_KEY;
import static traceworks.core.app.AppFixture.request;
import static traceworks.core.app.AppFixture.resumeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A resume warns, before the run goes on, when {@code credentials.json} was modified after the run stopped (its
 * {@code run.parked}, or its last line for an interrupted run) and holds the value of a secret the run declares or of
 * a credential of an AI provider it uses: the resume redacts with the values it reads now. The warning names the file
 * and the names, never a value, and is not recorded; the resume goes on as before.
 */
class ResumeCredentialsWarningTest {

    private static final String SECRET = "s3cr3t-d3ploy-t0ken";
    private static final Path PARKS = Path.of("/pkg/warn-parks");
    private static final Path FAILS = Path.of("/pkg/warn-fails");
    private static final Path AI = Path.of("/pkg/warn-ai");
    private static final Path PLAIN = Path.of("/pkg/warn-plain");

    private final AppFixture fx = new AppFixture();
    private final List<String> warnings = new ArrayList<>();

    ResumeCredentialsWarningTest() {
        fx.packages.put(PARKS, """
                {"id": "wf-warn-parks", "name": "Parks", "blocks": [
                  {"type": "approval", "name": "go", "config": {"question": "Go?", "options": ["Yes", "No"]}},
                  {"type": "shell", "name": "deploy", "config": {"command": "./deploy",
                   "secrets": ["DEPLOY_TOKEN", "OTHER_TOKEN"]}}
                ]}""");
        fx.packages.put(FAILS, """
                {"id": "wf-warn-fails", "name": "Fails", "blocks": [
                  {"type": "shell", "name": "build", "config": {"command": "./build"}},
                  {"type": "shell", "name": "deploy", "config": {"command": "./deploy", "secrets": ["DEPLOY_TOKEN"]}}
                ]}""");
        fx.packages.put(AI, """
                {"id": "wf-warn-ai", "name": "Ai", "requires": ["ai.tools"], "blocks": [
                  {"type": "approval", "name": "go", "config": {"question": "Go?", "options": ["Yes", "No"]}},
                  {"type": "simple", "name": "ask", "config": {"command": "Say hi", "tools": "read"}}
                ]}""");
        fx.packages.put(PLAIN, """
                {"id": "wf-warn-plain", "name": "Plain", "blocks": [
                  {"type": "approval", "name": "go", "config": {"question": "Go?", "options": ["Yes", "No"]}},
                  {"type": "shell", "name": "deploy", "config": {"command": "./deploy"}}
                ]}""");
        fx.commands.on("./deploy", "deployed");
    }

    /** Parks the run at its approval and answers it. */
    private String parkedAndAnswered(Path pkg) {
        RunServices services = fx.services();
        RunResult parked = services.start(request(pkg), null, null);
        assertThat(parked.status()).isEqualTo(RunStatus.PARKED);
        services.answer(parked.runId(), new AnswerRequest("approval", 0, "Yes", null, null));
        return parked.runId();
    }

    private Instant at(String id, String type) {
        return fx.ofType(id, type).getLast().at();
    }

    private RunResult resume(String id) {
        return fx.services().resume(resumeOf(id), null, null, warnings::add);
    }

    @Test
    void aFileModifiedAfterThePark_thatHoldsADeclaredSecret_warnsWithItsNames_beforeTheRunGoesOn() {
        fx.credentials.put("DEPLOY_TOKEN", SECRET);
        fx.credentials.put("UNUSED_TOKEN", "unused-t0ken-value");
        fx.environment.put("OTHER_TOKEN", "0ther-t0ken-value");
        String id = parkedAndAnswered(PARKS);
        // After the park, before the answer: the park is when the run stopped
        fx.credentialsModified = at(id, "run.parked").plusNanos(1);
        assertThat(fx.credentialsModified).isBefore(at(id, "approval.answered"));
        List<Boolean> resumedYet = new ArrayList<>();

        RunResult resumed = fx.services().resume(resumeOf(id), null, null, warning -> {
            warnings.add(warning);
            resumedYet.add(!fx.ofType(id, "run.resumed").isEmpty());
        });

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(warnings).singleElement().asString()
                .contains("credentials.json", id, "DEPLOY_TOKEN", "recorded in clear", "docs/security.md")
                .doesNotContain("OTHER_TOKEN", "UNUSED_TOKEN", SECRET);
        assertThat(resumedYet).as("the warning comes before the run goes on").containsExactly(false);
        for (byte[] line : fx.run(id).log().readAll()) {
            assertThat(new String(line, StandardCharsets.UTF_8)).doesNotContain("modified after");
        }
    }

    @Test
    void aFileOlderThanThePark_givesNoWarning() {
        fx.credentials.put("DEPLOY_TOKEN", SECRET);
        fx.environment.put("OTHER_TOKEN", "0ther-t0ken-value");
        String id = parkedAndAnswered(PARKS);
        fx.credentialsModified = at(id, "run.parked").minusMillis(1);

        assertThat(resume(id).status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(warnings).isEmpty();
    }

    @Test
    void noFile_givesNoWarning() {
        fx.environment.put("DEPLOY_TOKEN", SECRET);
        fx.environment.put("OTHER_TOKEN", "0ther-t0ken-value");
        String id = parkedAndAnswered(PARKS);

        assertThat(resume(id).status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(warnings).isEmpty();
    }

    @Test
    void aNewerFile_whenTheRunsSecretsComeFromTheEnvironmentOnly_givesNoWarning() {
        fx.environment.put("DEPLOY_TOKEN", SECRET);
        fx.environment.put("OTHER_TOKEN", "0ther-t0ken-value");
        fx.credentials.put("UNUSED_TOKEN", "unused-t0ken-value");
        String id = parkedAndAnswered(PARKS);
        fx.credentialsModified = at(id, "approval.answered").plusSeconds(60);

        assertThat(resume(id).status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(warnings).isEmpty();
    }

    @Test
    void aRunWithoutSecrets_givesNoWarning() {
        fx.credentials.put("DEPLOY_TOKEN", SECRET);
        String id = parkedAndAnswered(PLAIN);
        fx.credentialsModified = at(id, "approval.answered").plusSeconds(60);

        assertThat(resume(id).status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(warnings).isEmpty();
    }

    @Test
    void anAiCredentialFromTheFile_warnsWithItsName() {
        fx.withClaude();
        fx.environment.remove("ANTHROPIC_API_KEY");
        fx.credentials.put("ANTHROPIC_API_KEY", ANTHROPIC_KEY);
        String id = parkedAndAnswered(AI);
        fx.credentialsModified = at(id, "approval.answered").plusSeconds(60);

        resume(id);

        assertThat(warnings).singleElement().asString().contains("ANTHROPIC_API_KEY").doesNotContain(ANTHROPIC_KEY);
    }

    /** A run whose process went down while its first step ran: it is interrupted after that step's command. */
    private String interrupted() {
        AtomicBoolean diskFills = new AtomicBoolean(true);
        fx.commands.on("./build", request -> {
            if (diskFills.getAndSet(false)) {
                InMemoryEventLog log = fx.run(fx.store.runIds().getFirst()).log();
                log.failAfter(log.readAll().size());   // the process can record nothing more and goes down
            }
            return FakeCommandRunner.ok("built");
        });
        assertThatThrownBy(() -> fx.services().start(request(FAILS), null, null))
                .isInstanceOf(EventLogException.class);
        String id = fx.store.runIds().getFirst();
        assertThat(fx.services().view(id).status()).isEqualTo(RunStatus.INTERRUPTED);
        fx.run(id).log().failAfter(-1);
        return id;
    }

    @Test
    void anInterruptedRun_stoppedAtItsLastLine_aFileModifiedBeforeItGivesNoWarning() {
        fx.credentials.put("DEPLOY_TOKEN", SECRET);
        String id = interrupted();
        List<RecordedEvent> events = fx.events(id);
        assertThat(events.getFirst().at()).isBefore(events.getLast().at());
        // After the run started, not after its last line
        fx.credentialsModified = events.getLast().at();

        assertThat(resume(id).status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(warnings).isEmpty();
    }

    @Test
    void anInterruptedRun_aFileModifiedAfterItsLastLine_warns() {
        fx.credentials.put("DEPLOY_TOKEN", SECRET);
        String id = interrupted();
        fx.credentialsModified = fx.events(id).getLast().at().plusNanos(1);

        assertThat(resume(id).status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(warnings).singleElement().asString().contains("DEPLOY_TOKEN");
    }
}
