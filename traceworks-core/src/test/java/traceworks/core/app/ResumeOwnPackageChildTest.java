package traceworks.core.app;

import org.junit.jupiter.api.Test;
import traceworks.core.model.RunStatus;
import traceworks.core.testing.FakeCommandRunner;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static traceworks.core.app.AppFixture.request;

/**
 * A run that calls its own package as a child is not resumed with a changed package. Its own steps would be those of
 * the definition recorded at the start, and the child would be loaded from the package as it is now: one run with
 * two versions of one package. So the resume is refused, with {@code --allow-changed-package} too, before anything
 * is written. A run without such a child, one whose package is unchanged, and a child of another package go on as
 * before.
 */
@org.junit.jupiter.api.Timeout(20)
class ResumeOwnPackageChildTest {

    private static final Path SELF = Path.of("/pkgs/self");
    private static final Path OTHER = Path.of("/pkgs/other");

    private final AppFixture fx = new AppFixture();
    private final RunServices services = fx.services();

    ResumeOwnPackageChildTest() {
        fx.commands.on("echo one", "said").on("echo two", "said").on("echo other", "other");
    }

    /** A question, then {@code after}, then a last step whose command is what a changed package changes. */
    private static String asking(String before, String after, String say) {
        return """
                {"id": "wf-self", "name": "Self", "blocks": [%s
                  {"type": "approval", "name": "go", "config": {"question": "Go on?", "options": ["Yes", "No"]}},%s
                  {"type": "shell", "name": "say", "config": {"command": "echo %s"}}
                ]}""".formatted(before, after, say);
    }

    private static final String CHILD = """
            {"type": "sub-workflow", "name": "again", "config": {"workflow_id": "."}},""";

    /** Starts the run of {@code first}, answers its question, and puts {@code atResume} in the package's place. */
    private String parked(String first, String atResume) {
        return parked(first, atResume, 0);
    }

    /** As {@link #parked(String, String)}, for a question that is the step {@code step}. */
    private String parked(String first, String atResume, int step) {
        fx.packages.put(SELF, first);
        RunResult parked = services.start(request(SELF), null, null);
        assertThat(parked.status()).as(String.valueOf(parked.error())).isEqualTo(RunStatus.PARKED);
        services.answer(parked.runId(), new AnswerRequest("approval", step, "Yes", null, null));
        fx.packages.put(SELF, atResume);
        return parked.runId();
    }

    private void assertRefused(String id, boolean allowed, String... says) {
        List<String> before = fx.types(id);
        int ledgerBefore = fx.ledger.prepared();

        assertThatThrownBy(() -> services.resume(new ResumeRequest(id, false, allowed), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("cannot resume with its changed package")
                .hasMessageContaining("two versions of one package")
                .hasMessageContaining("Restore the package as it was at the start")
                .hasMessageContaining("or start a new run")
                .satisfies(e -> assertThat(e.getMessage()).contains(says));

        assertThat(fx.types(id)).as("nothing was recorded").isEqualTo(before).doesNotContain("run.resumed");
        assertThat(fx.ledger.prepared()).isEqualTo(ledgerBefore);
        assertThat(services.view(id).status()).isEqualTo(RunStatus.PARKED);
    }

    @Test
    void aRunThatCallsItsOwnPackage_isNotResumedWithAChangedPackage_alsoWithTheFlag_andNothingIsWritten() {
        String id = parked(asking("", CHILD, "one"), asking("", CHILD, "two"));

        assertRefused(id, true, "its block 'again' runs the run's own package as a child workflow");
        assertRefused(id, false, "its block 'again'");
        assertThatThrownBy(() -> services.resume(new ResumeRequest(id, false, false), null, null))
                .as("the flag would not help, so the refusal does not offer it")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("resume with --allow-changed-package"));
    }

    /** The child was reached and finished before the question: the run still calls its own package. */
    @Test
    void aChildThatFinishedBeforeTheRunParked_refusesTheResumeToo() {
        AtomicInteger asked = new AtomicInteger();
        fx.commands.on("./mark", "marked");
        // Only the run itself calls and asks: the first and the fourth to ask; its child, between them, skips both
        fx.commands.on("./outer", req -> FakeCommandRunner.exit(List.of(1, 4).contains(asked.incrementAndGet())
                ? 0 : 1, ""));
        String self = """
                {"id": "wf-self", "name": "Self", "blocks": [
                  {"type": "shell", "name": "mark", "config": {"command": "./mark"}},
                  {"type": "sub-workflow", "name": "first", "config": {"workflow_id": ".", "run_if": "./outer"}},
                  {"type": "approval", "name": "go", "config": {"question": "Go on?", "options": ["Yes", "No"],
                     "run_if": "./outer"}},
                  {"type": "shell", "name": "say", "config": {"command": "echo %s"}}
                ]}""";
        String id = parked(self.formatted("one"), self.formatted("two"), 2);

        assertThat(services.view(id).children()).as("the child ran before the question").hasSize(1);
        assertRefused(id, true, "its block 'first'");
    }

    @Test
    void aChildInAForeach_inACaseOfASwitch_andInItsDefault_refuseTheResume() {
        String foreach = """

                  {"type": "foreach", "name": "each", "config": {"items": ["a"], "workflow_id": "."}},""";
        String inACase = """

                  {"type": "switch", "name": "route", "config": {"expression": "a", "cases": [{"value": "b", "blocks": [
                    {"type": "sub-workflow", "name": "inner", "config": {"workflow_id": "."}}]}]}},""";
        String inTheDefault = """

                  {"type": "switch", "name": "route", "config": {"expression": "a", "cases": [], "default": [
                    {"type": "sub-workflow", "name": "fallback", "config": {"workflow_id": "./"}}]}},""";

        assertRefused(parked(asking("", foreach, "one"), asking("", foreach, "two")), true, "its block 'each'");
        assertRefused(parked(asking("", inACase, "one"), asking("", inACase, "two")), true, "its block 'inner'");
        assertRefused(parked(asking("", inTheDefault, "one"), asking("", inTheDefault, "two")), true,
                "its block 'fallback'");
    }

    /** The package of a child that is another package calls the run's package: reached, so refused. */
    @Test
    void aChildOfAnotherPackage_thatCallsTheRunsPackage_refusesTheResume() {
        fx.packages.put(OTHER, """
                {"id": "wf-other", "name": "Other", "blocks": [
                  {"type": "sub-workflow", "name": "back", "config": {"workflow_id": "../self"}}]}""");
        String viaOther = """

                  {"type": "sub-workflow", "name": "out", "config": {"workflow_id": "../other"}},""";
        String id = parked(asking("", viaOther, "one"), asking("", viaOther, "two"));

        assertRefused(id, true, "the block 'back' of the workflow wf-other");
    }

    /** What a placeholder names is known only when the block runs: it may be the run's own package. */
    @Test
    void aChildNamedWithAPlaceholder_refusesTheResume_sinceItMayBeTheRunsOwnPackage() {
        String placeholder = """

                  {"type": "sub-workflow", "name": "any", "config": {"workflow_id": "{{ which }}"}},""";
        String id = parked(asking("", placeholder, "one"), asking("", placeholder, "two"));

        assertRefused(id, true, "its block 'any' names its child workflow with a placeholder");
    }

    @Test
    void aRunThatCallsItsOwnPackage_resumesAsBefore_whileThePackageIsUnchanged() {
        AtomicInteger started = new AtomicInteger();
        fx.commands.on("./mark", req -> FakeCommandRunner.ok("run " + started.incrementAndGet()));
        fx.commands.on("./outer", req -> FakeCommandRunner.exit(started.get() == 1 ? 0 : 1, ""));
        // Only the run itself asks and calls: its child, the second to run the first step, skips both blocks
        String self = """
                {"id": "wf-self", "name": "Self", "blocks": [
                  {"type": "shell", "name": "mark", "config": {"command": "./mark"}},
                  {"type": "approval", "name": "go", "config": {"question": "Go on?", "options": ["Yes", "No"],
                     "run_if": "./outer"}},
                  {"type": "sub-workflow", "name": "again", "config": {"workflow_id": ".", "run_if": "./outer"}},
                  {"type": "shell", "name": "say", "config": {"command": "echo one"}}
                ]}""";
        String id = parked(self, self, 1);

        RunResult resumed = services.resume(new ResumeRequest(id, false, false), null, null);

        assertThat(resumed.status()).as(String.valueOf(resumed.error())).isEqualTo(RunStatus.COMPLETED);
        assertThat(services.view(id).children()).as("the package ran as its own child").hasSize(1);
        assertThat(fx.types(id)).doesNotContain("run.package");
    }

    @Test
    void aRunWithoutSuchAChild_resumesWithAChangedPackage_asBefore_andSoDoesOneThatCallsAnotherPackage() {
        fx.packages.put(OTHER, """
                {"id": "wf-other", "name": "Other", "blocks": [
                  {"type": "shell", "name": "there", "config": {"command": "echo other"}}]}""");
        String other = """

                  {"type": "sub-workflow", "name": "out", "config": {"workflow_id": "../other"}},""";
        String plain = parked(asking("", "", "one"), asking("", "", "two"));
        String calling = parked(asking("", other, "one"), asking("", other, "two"));

        assertThatThrownBy(() -> services.resume(new ResumeRequest(plain, false, false), null, null))
                .as("without the flag: the refusal that offers it")
                .hasMessageContaining("resume with --allow-changed-package");
        RunResult ofPlain = services.resume(new ResumeRequest(plain, false, true), null, null);
        RunResult ofCalling = services.resume(new ResumeRequest(calling, false, true), null, null);

        assertThat(ofPlain.status()).as(String.valueOf(ofPlain.error())).isEqualTo(RunStatus.COMPLETED);
        assertThat(ofCalling.status()).as(String.valueOf(ofCalling.error())).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.types(calling)).as("the child of another package is recorded as before")
                .contains("run.package", "run.resumed");
        assertThat(fx.commands.commands()).contains("echo one").doesNotContain("echo two");
    }
}
