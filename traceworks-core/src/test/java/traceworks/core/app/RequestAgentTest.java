package traceworks.core.app;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import traceworks.core.app.AppFixture.Background;
import traceworks.core.model.RunStatus;
import traceworks.core.record.RecordedEvent;
import traceworks.core.record.RunView;
import traceworks.core.testing.FakeCommandRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static traceworks.core.app.AppFixture.ACTOR;
import static traceworks.core.app.AppFixture.BUILD;
import static traceworks.core.app.AppFixture.DEPLOY;
import static traceworks.core.app.AppFixture.WORKSPACE;

/**
 * A start, a resume, a pause and a cancel can name the agent that asked for them under the actor, as an answer does:
 * a front end that an agent drives (the MCP server) gives the name, and the line of the record carries {@code agent}
 * beside {@code actor}. Without a name every line is as it was.
 */
class RequestAgentTest {

    private final AppFixture fx = new AppFixture();
    private final RunServices services = fx.services();
    private final List<Background<?>> background = new ArrayList<>();
    private final CountDownLatch goOn = new CountDownLatch(1);
    private final CountDownLatch started = new CountDownLatch(1);

    @AfterEach
    void noThreadOutlivesAUseCase() throws InterruptedException {
        goOn.countDown();
        try {
            services.cancelActiveRuns();
        } finally {
            for (Background<?> work : background) {
                work.awaitEnd();
            }
        }
    }

    private static StartRequest startBy(java.nio.file.Path pkg, String agent) {
        return new StartRequest(pkg, java.util.Map.of(), WORKSPACE, false, false, null, null, null, false).by(agent);
    }

    private String runningBuild() throws InterruptedException {
        fx.commands.on("./build", req -> {
            started.countDown();
            try {
                goOn.await(AppFixture.WAIT.toSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return FakeCommandRunner.ok("built");
        });
        background.add(new Background<>(() -> services.start(AppFixture.request(BUILD), null, null)));
        assertThat(started.await(AppFixture.WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        return fx.store.runIds().getFirst();
    }

    @Test
    void aStartAndAResumeByAnAgent_nameItBesideTheActor_andTheViewListsBoth() {
        String id = services.start(startBy(DEPLOY, " claude-code "), null, null).runId();
        services.answer(id, new AnswerRequest("approval", 1, "yes", null, null, "claude-code"));

        RunResult resumed = services.resume(new ResumeRequest(id, false, false, false, "claude-code"), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        RecordedEvent start = fx.ofType(id, "run.started").getFirst();
        assertThat(start.fields().path("actor").asText()).isEqualTo(ACTOR);
        assertThat(start.fields().path("agent").asText()).isEqualTo("claude-code");
        assertThat(start.fields().path("records")).extracting(n -> n.asText()).contains("request_agent");
        RecordedEvent resume = fx.ofType(id, "run.resumed").getFirst();
        assertThat(resume.fields().path("actor").asText()).isEqualTo(ACTOR);
        assertThat(resume.fields().path("agent").asText()).isEqualTo("claude-code");
        assertThat(services.view(id).interventions())
                .filteredOn(i -> i.type().equals("run.started") || i.type().equals("run.resumed"))
                .extracting(RunView.Intervention::agent).containsExactly("claude-code", "claude-code");
        assertThat(services.verify(id).ok()).isTrue();
    }

    @Test
    void withoutAnAgent_theLinesAreAsTheyWere_andTheStartIsNoIntervention() {
        String id = services.start(AppFixture.request(DEPLOY), null, null).runId();

        RunResult cancelled = services.cancel(id);

        assertThat(cancelled.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(fx.events(id)).allSatisfy(e -> assertThat(e.fields().has("agent")).as(e.type()).isFalse());
        assertThat(services.view(id).interventions()).extracting(RunView.Intervention::type)
                .doesNotContain("run.started");
    }

    @Test
    void aNameThatIsNone_startsNoRun() {
        assertThatThrownBy(() -> services.start(startBy(DEPLOY, "two\nlines"), null, null))
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("agent");
        assertThatThrownBy(() -> services.start(startBy(DEPLOY, " "), null, null))
                .isInstanceOf(RunRefusedException.class);
        assertThat(fx.store.runIds()).isEmpty();
    }

    @Test
    void aCancelOfAParkedRunByAnAgent_namesIt() {
        String id = services.start(AppFixture.request(DEPLOY), null, null).runId();

        services.cancel(id, "claude-code");

        RecordedEvent requested = fx.ofType(id, "run.cancel_requested").getFirst();
        assertThat(requested.fields().path("actor").asText()).isEqualTo(ACTOR);
        assertThat(requested.fields().path("agent").asText()).isEqualTo("claude-code");
        assertThat(services.view(id).interventions()).filteredOn(i -> i.type().equals("run.cancel_requested"))
                .extracting(RunView.Intervention::agent).containsExactly("claude-code");
    }

    @Test
    void aPauseThroughTheInboxByAnAgent_namesIt() throws Exception {
        String id = runningBuild();

        fx.services().pause(id, "claude-code");

        RecordedEvent requested = fx.ofType(id, "run.pause_requested").getFirst();
        assertThat(requested.fields().path("actor").asText()).isEqualTo(ACTOR);
        assertThat(requested.fields().path("agent").asText()).isEqualTo("claude-code");
    }

    @Test
    void aCancelThroughTheInboxByAnAgent_namesIt() throws Exception {
        String id = runningBuild();

        Background<RunResult> cancel = new Background<>(() -> fx.services().cancel(id, "claude-code"));
        background.add(cancel);
        long deadline = System.nanoTime() + AppFixture.WAIT.toNanos();
        while (fx.ofType(id, "run.cancel_requested").isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        goOn.countDown();
        cancel.join();

        RecordedEvent requested = fx.ofType(id, "run.cancel_requested").getFirst();
        assertThat(requested.fields().path("agent").asText()).isEqualTo("claude-code");
    }
}
