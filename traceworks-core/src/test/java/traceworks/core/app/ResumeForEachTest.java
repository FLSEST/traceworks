package traceworks.core.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.engine.EventLogException;
import traceworks.core.model.RunStatus;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.record.RecordedEvent;
import traceworks.core.testing.FakeCommandRunner;
import traceworks.core.testing.InMemoryEventLog;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static traceworks.core.app.AppFixture.request;
import static traceworks.core.app.AppFixture.resumeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A resumed {@code foreach} goes on where it stopped: the items the record holds as finished are taken over, the rest
 * runs. The runs here stop as a killed process does: from one command on, nothing more can be recorded.
 */
class ResumeForEachTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A loop over four items, then a step that reads the loop's output. */
    private static final Path LOOP = Path.of("/pkg/loop");
    /** The same loop over the lines of a command. */
    private static final Path LISTED = Path.of("/pkg/listed");
    /** A loop that goes on after a failed item. */
    private static final Path TOLERANT = Path.of("/pkg/tolerant");
    /** A loop whose commands get a secret. */
    private static final Path SECRET = Path.of("/pkg/secret");
    /** A loop of AI calls. */
    private static final Path ASKED = Path.of("/pkg/asked");
    /** A loop that runs a workflow per item. */
    private static final Path CHILDREN = Path.of("/pkg/children");

    private static final String TOKEN_VALUE = "made-up-value-for-the-loop-0123";

    private final AppFixture fx = new AppFixture();

    ResumeForEachTest() {
        put(fx);
    }

    private static void put(AppFixture fx) {
        fx.packages.put(LOOP, """
                {"id": "wf-loop", "name": "Loop", "blocks": [
                  {"type": "foreach", "name": "each", "config": {"execution_mode": "shell",
                   "items": ["a", "b", "c", "d"], "command": "./work {{ item }}"}},
                  {"type": "shell", "name": "report", "config": {"command": "./report {{ block.each.output }}"}}
                ]}""");
        fx.packages.put(LISTED, """
                {"id": "wf-listed", "name": "Listed", "blocks": [
                  {"type": "foreach", "name": "each", "config": {"execution_mode": "shell",
                   "items_source": "command", "items_command": "./list", "command": "./work {{ item }}"}}
                ]}""");
        fx.packages.put(TOLERANT, """
                {"id": "wf-tolerant", "name": "Tolerant", "blocks": [
                  {"type": "foreach", "name": "each", "config": {"execution_mode": "shell",
                   "items": ["a", "b", "c", "d"], "command": "./work {{ item }}", "continue_on_error": true}}
                ]}""");
        fx.packages.put(SECRET, """
                {"id": "wf-secret", "name": "Secret", "blocks": [
                  {"type": "foreach", "name": "each", "config": {"execution_mode": "shell",
                   "items": ["a", "b", "c"], "command": "./work {{ item }}", "secrets": ["LOOP_TOKEN"]}}
                ]}""");
        fx.packages.put(ASKED, """
                {"id": "wf-asked", "name": "Asked", "blocks": [
                  {"type": "foreach", "name": "each", "config": {"execution_mode": "ai",
                   "items": ["a", "b", "c"], "command": "Judge {{ item }}"}}
                ]}""");
        fx.packages.put(CHILDREN, """
                {"id": "wf-children", "name": "Children", "blocks": [
                  {"type": "foreach", "name": "each", "config": {"items": ["a", "b", "c"], "workflow_id": "./child"}}
                ]}""");
        fx.packages.put(CHILDREN.resolve("child"), """
                {"id": "wf-child", "name": "Child", "blocks": [
                  {"type": "shell", "name": "work", "config": {"command": "./work {{ item }}"}}
                ]}""");
        fx.commands.on("./work ", req -> FakeCommandRunner.ok("did " + req.command().substring("./work ".length())))
                .on("./report", "reported");
    }

    /** From the first command that starts with {@code prefix} on, the run's process can record nothing more. */
    private void diesAt(String prefix) {
        AtomicBoolean dies = new AtomicBoolean(true);
        fx.commands.on(prefix, req -> {
            if (dies.getAndSet(false)) {
                die();
            }
            return FakeCommandRunner.ok("did " + req.command().substring("./work ".length()));
        });
    }

    private void die() {
        InMemoryEventLog log = fx.run(fx.store.runIds().getFirst()).log();
        log.failAfter(log.readAll().size());
    }

    /** Starts the package, whose process dies, and returns the run's id with its log writable again. */
    private String interrupted(RunServices services, Path pkg) {
        assertThatThrownBy(() -> services.start(request(pkg), null, null)).isInstanceOf(EventLogException.class);
        String id = fx.store.runIds().getFirst();
        assertThat(services.view(id).status()).isEqualTo(RunStatus.INTERRUPTED);
        fx.run(id).log().failAfter(-1);
        return id;
    }

    private long ran(String command) {
        return fx.commands.commands().stream().filter(command::equals).count();
    }

    private JsonNode metadata(String id, RecordedEvent finished) throws IOException {
        return JSON.readTree(fx.text(id, finished, "metadata"));
    }

    @Test
    void aResumedLoop_takesTheFinishedItemsOver_andRunsTheRest() throws Exception {
        diesAt("./work c");
        RunServices services = fx.services();
        String id = interrupted(services, LOOP);

        RunResult resumed = services.resume(resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(ran("./work a")).isEqualTo(1);
        assertThat(ran("./work b")).isEqualTo(1);
        assertThat(ran("./work c")).as("in flight when the process died: it runs again").isEqualTo(2);
        assertThat(ran("./work d")).isEqualTo(1);
        // The record of the resumed loop is complete on its own, and says where each output comes from
        int resumedAt = fx.ofType(id, "run.resumed").getFirst().seq();
        List<RecordedEvent> first = fx.ofType(id, "foreach.item.finished").stream()
                .filter(e -> e.seq() < resumedAt).toList();
        List<RecordedEvent> again = fx.ofType(id, "foreach.item.finished").stream()
                .filter(e -> e.seq() > resumedAt).toList();
        assertThat(first).hasSize(2);
        assertThat(again).extracting(e -> e.fields().path("item_index").asInt()).containsExactly(0, 1, 2, 3);
        assertThat(again).extracting(e -> e.fields().path("status").asText()).containsOnly("COMPLETED");
        assertThat(again.get(0).fields().path("resumed_from").asInt()).isEqualTo(first.get(0).seq());
        assertThat(again.get(1).fields().path("resumed_from").asInt()).isEqualTo(first.get(1).seq());
        assertThat(again.get(2).fields().has("resumed_from")).isFalse();
        assertThat(again.get(3).fields().has("resumed_from")).isFalse();
        assertThat(fx.text(id, again.get(0), "item")).isEqualTo("a");
        assertThat(fx.text(id, again.get(0), "output")).isEqualTo("did a");
        assertThat(metadata(id, fx.ofType(id, "step.finished").getFirst()).path("itemsResumed").asInt())
                .isEqualTo(2);
        assertThat(services.verify(id).ok()).isTrue();
    }

    /** The step's output, the indices of the steps after it and what they read are those of one pass. */
    @Test
    void aResumedLoop_endsAsOnePassDoes() throws Exception {
        AppFixture whole = new AppFixture();
        put(whole);
        String onePass = whole.services().start(request(LOOP), null, null).runId();
        diesAt("./work c");
        RunServices services = fx.services();
        String id = interrupted(services, LOOP);

        services.resume(resumeOf(id), null, null);

        assertThat(fx.text(id, fx.ofType(id, "step.output").getFirst(), "output"))
                .isEqualTo(whole.text(onePass, whole.ofType(onePass, "step.output").getFirst(), "output"))
                .isEqualTo("[a]: did a\n[b]: did b\n[c]: did c\n[d]: did d");
        assertThat(fx.commands.commands().getLast()).isEqualTo(whole.commands.commands().getLast())
                .startsWith("./report ");
        assertThat(fx.ofType(id, "step.finished")).extracting(RecordedEvent::step)
                .isEqualTo(whole.ofType(onePass, "step.finished").stream().map(RecordedEvent::step).toList());
        JsonNode items = metadata(id, fx.ofType(id, "step.finished").getFirst()).path("items");
        assertThat(items).hasSize(4);
        assertThat(items.get(0).path("resumed").asBoolean()).isTrue();
        assertThat(items.get(2).has("resumed")).isFalse();
    }

    /** A second stop: the items the first resume took over are taken over again, from the resumed pass. */
    @Test
    void aLoopResumedTwice_takesOverWhatBothPassesFinished() {
        diesAt("./work b");
        RunServices services = fx.services();
        String id = interrupted(services, LOOP);
        diesAt("./work d");
        assertThatThrownBy(() -> services.resume(resumeOf(id), null, null)).isInstanceOf(EventLogException.class);
        fx.run(id).log().failAfter(-1);

        RunResult resumed = services.resume(resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(ran("./work a")).isEqualTo(1);
        assertThat(ran("./work b")).isEqualTo(2);
        assertThat(ran("./work c")).isEqualTo(1);
        assertThat(ran("./work d")).isEqualTo(2);
    }

    @Test
    void anItemWhoseTextChanged_runsAgain() {
        AtomicInteger listed = new AtomicInteger();
        fx.commands.on("./list", req -> FakeCommandRunner.ok(listed.getAndIncrement() == 0 ? "a\nb\nc\nd" : "a\nB\nc\nd"));
        diesAt("./work d");
        RunServices services = fx.services();
        String id = interrupted(services, LISTED);

        RunResult resumed = services.resume(resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(ran("./work a")).isEqualTo(1);
        assertThat(ran("./work b")).isEqualTo(1);
        assertThat(ran("./work B")).as("another item at index 1").isEqualTo(1);
        assertThat(ran("./work c")).isEqualTo(1);
        assertThat(fx.text(id, fx.ofType(id, "step.output").getFirst(), "output"))
                .isEqualTo("[a]: did a\n[B]: did B\n[c]: did c\n[d]: did d");
    }

    @Test
    void aFailedItem_runsAgain() {
        AtomicInteger tries = new AtomicInteger();
        fx.commands.on("./work b", req -> tries.getAndIncrement() == 0
                ? FakeCommandRunner.exit(1, "broke") : FakeCommandRunner.ok("did b"));
        diesAt("./work d");
        RunServices services = fx.services();
        String id = interrupted(services, TOLERANT);

        RunResult resumed = services.resume(resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(tries.get()).isEqualTo(2);
        assertThat(ran("./work a")).isEqualTo(1);
        assertThat(ran("./work c")).isEqualTo(1);
    }

    /** An output the record holds redacted is not the output: the item runs again. */
    @Test
    void anItemWhoseOutputTheRecordRedacted_runsAgain() {
        fx.credentials.put("LOOP_TOKEN", TOKEN_VALUE);
        fx.commands.on("./work a", req -> FakeCommandRunner.ok("did a with " + TOKEN_VALUE));
        diesAt("./work c");
        RunServices services = fx.services();
        String id = interrupted(services, SECRET);

        RunResult resumed = services.resume(resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(ran("./work a")).as("its recorded output holds [redacted:LOOP_TOKEN]").isEqualTo(2);
        assertThat(ran("./work b")).isEqualTo(1);
    }

    @Test
    void aResumedLoopOfAiCalls_asksOnlyForTheItemsThatDidNotFinish() {
        fx.settings = OperatorSettings.defaults();
        fx.withClaude();
        AtomicInteger calls = new AtomicInteger();
        fx.containers.on(AppFixture.CLAUDE_ID, run -> {
            if (calls.incrementAndGet() == 3) {
                die();
            }
            return new CommandResult(0, "verdict on " + run.stdin().substring("Judge ".length()), "", 1, false);
        });
        RunServices services = fx.services();
        String id = interrupted(services, ASKED);

        RunResult resumed = services.resume(resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.claude.prompts()).containsExactly("Judge a", "Judge b", "Judge c", "Judge c");
        assertThat(fx.text(id, fx.ofType(id, "step.output").getFirst(), "output"))
                .isEqualTo("[a]: verdict on a\n[b]: verdict on b\n[c]: verdict on c");
    }

    /** A loop that runs a workflow per item takes nothing over: a child run is resumed with its parent's step. */
    @Test
    void aLoopOfChildRuns_runsEveryItemAgain() {
        diesAt("./work c");
        RunServices services = fx.services();
        String id = interrupted(services, CHILDREN);

        RunResult resumed = services.resume(resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(ran("./work a")).isEqualTo(2);
        assertThat(ran("./work b")).isEqualTo(2);
        assertThat(fx.ofType(id, "foreach.item.finished")).noneMatch(e -> e.fields().has("resumed_from"));
    }

    /** A loop over {@code items} whose output must hold "good": an output without it runs the loop again. */
    private Path validated(String... items) {
        Path pkg = Path.of("/pkg/validated");
        fx.packages.put(pkg, """
                {"id": "wf-validated", "name": "Validated", "blocks": [
                  {"type": "foreach", "name": "each", "config": {"execution_mode": "shell",
                   "items": %s, "command": "./work {{ item }}",
                   "validation": {"contains": "good", "retry_on_fail": true, "max_retries": 1}}}
                ]}""".formatted(JSON.valueToTree(List.of(items))));
        return pkg;
    }

    /** {@code ./work <item>} answers "did", then "good"; its {@code dies}-th call is the one the process dies in. */
    private void works(String item, int dies) {
        AtomicInteger calls = new AtomicInteger();
        fx.commands.on("./work " + item, req -> {
            int call = calls.incrementAndGet();
            if (call == dies) {
                die();
            }
            return FakeCommandRunner.ok((call == 1 ? "did " : "good ") + item);
        });
    }

    /**
     * The loop ran again for its validation, in the same process, and the process died in that second pass: only
     * the second pass counts, so an item only the first pass finished runs again.
     */
    @Test
    void aPassTheLoopRanAgain_forgetsTheItemsOfThePassBefore() {
        Path pkg = validated("a", "b");
        works("a", 0);
        works("b", 2);
        RunServices services = fx.services();
        String id = interrupted(services, pkg);

        RunResult resumed = services.resume(resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(ran("./work a")).as("both passes, then taken over").isEqualTo(2);
        assertThat(ran("./work b")).as("not taken over from the first pass").isEqualTo(3);
        assertThat(fx.text(id, fx.ofType(id, "step.output").getFirst(), "output"))
                .isEqualTo("[a]: good a\n[b]: good b");
    }

    /** A pass of one item begins again at the same index: the item the first pass finished is forgotten too. */
    @Test
    void aPassOfOneItemTheLoopRanAgain_forgetsTheItemOfThePassBefore() {
        Path pkg = validated("a");
        works("a", 2);
        RunServices services = fx.services();
        String id = interrupted(services, pkg);

        RunResult resumed = services.resume(resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.ofType(id, "foreach.item.finished")).noneMatch(e -> e.fields().has("resumed_from"));
    }
}
