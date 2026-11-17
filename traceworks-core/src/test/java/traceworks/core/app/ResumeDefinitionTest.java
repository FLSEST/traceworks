package traceworks.core.app;

import traceworks.core.model.RunStatus;
import traceworks.core.port.CommandRunner.CommandRequest;
import traceworks.core.record.RecordTypes;
import traceworks.core.record.RecordedEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static traceworks.core.app.AppFixture.resumeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a resume runs: the definition of the package on disk, at the real paths, while the record keeps holding the
 * redacted copies (review D, M4).
 */
class ResumeDefinitionTest {

    private static final String HOST = "registry.corp.example";
    private static final Path PUSH = Path.of("/pkg/push");
    /** A package and a workspace whose paths hold a secret's value, as a project name used as one does. */
    private static final String PROJECT = "acme-payments";
    private static final Path IN_PROJECT = Path.of("/pkg/" + PROJECT);
    private static final Path PROJECT_WORKSPACE = Path.of("/work/" + PROJECT);

    private final AppFixture fx = new AppFixture();

    ResumeDefinitionTest() {
        fx.packages.put(PUSH, """
                {"id": "wf-push", "name": "Push", "blocks": [
                  {"type": "shell", "name": "login", "config": {"command": "login --registry registry.corp.example",
                   "secrets": ["REGISTRY_HOST"]}},
                  {"type": "approval", "name": "ok", "config": {"question": "Go?", "options": ["Yes", "No"]}},
                  {"type": "shell", "name": "push", "config": {"command": "push --registry registry.corp.example",
                   "secrets": ["REGISTRY_HOST"]}}
                ]}""");
        fx.packages.put(IN_PROJECT, """
                {"id": "wf-project", "name": "Project", "blocks": [
                  {"type": "approval", "name": "ok", "config": {"question": "Go?", "options": ["Yes", "No"]}},
                  {"type": "shell", "name": "deploy", "config": {"command": "./deploy", "secrets": ["PROJECT"]}}
                ]}""");
        fx.credentials.put("REGISTRY_HOST", HOST);
        fx.credentials.put("PROJECT", PROJECT);
        fx.commands.on("login ", "in").on("push ", "pushed").on("./deploy", "deployed");
    }

    private static StartRequest start(Path pkg, Path workspace) {
        return new StartRequest(pkg, Map.of(), workspace, false, false);
    }

    private void assertNowhere(String id, String value) {
        for (byte[] line : fx.run(id).log().readAll()) {
            assertThat(new String(line, StandardCharsets.UTF_8)).doesNotContain(value);
        }
        fx.run(id).blobs().all().forEach(blob ->
                assertThat(new String(blob, StandardCharsets.UTF_8)).doesNotContain(value));
    }

    /** A secret's value that occurs in workflow.json: the resumed step runs it, the record keeps the marker. */
    @Test
    void aResume_runsTheDefinitionOnDisk_notTheRedactedCopy() {
        RunServices services = fx.services();
        String id = services.start(start(PUSH, AppFixture.WORKSPACE), null, null).runId();
        services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));

        RunResult resumed = services.resume(resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.commands.commands())
                .containsExactly("login --registry " + HOST, "push --registry " + HOST);
        RecordedEvent pushed = fx.ofType(id, RecordTypes.STEP_COMMAND).getLast();
        assertThat(fx.text(id, pushed, "command")).isEqualTo("push --registry [redacted:REGISTRY_HOST]");
        assertNowhere(id, HOST);
    }

    /** The package and the workspace are found by their real paths; the record names them redacted. */
    @Test
    void aResume_findsThePackageAndTheWorkspaceByTheirRealPaths() {
        RunServices services = fx.services();
        String id = services.start(start(IN_PROJECT, PROJECT_WORKSPACE), null, null).runId();
        services.answer(id, new AnswerRequest("approval", 0, "Yes", null, null));
        assertThat(services.view(id).packageDir()).isEqualTo("/pkg/[redacted:PROJECT]");

        RunResult resumed = services.resume(resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        CommandRequest deploy = fx.commands.requests().getLast();
        assertThat(deploy.command()).isEqualTo("./deploy");
        assertThat(deploy.workingDirectory()).isEqualTo(PROJECT_WORKSPACE.toString());
        assertNowhere(id, PROJECT);
    }

    /** A package that is gone refuses the resume, naming the directory as the record does. */
    @Test
    void aResume_ofARunWhosePackageIsGone_isRefusedWithTheRecordedPath() {
        RunServices services = fx.services();
        String id = services.start(start(IN_PROJECT, PROJECT_WORKSPACE), null, null).runId();
        services.answer(id, new AnswerRequest("approval", 0, "Yes", null, null));
        fx.packages.remove(IN_PROJECT);

        assertThatThrownBy(() -> services.resume(resumeOf(id), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("The package of run " + id + " cannot be loaded from /pkg/[redacted:PROJECT]")
                .message().doesNotContain(PROJECT);
        assertThat(fx.types(id)).doesNotContain(RecordTypes.RUN_RESUMED);
    }

    /**
     * The start check of a resume judges the real paths, and its refusal goes to the terminal: it names the
     * workspace and the package as the record does, with no value of a secret in it.
     */
    @Test
    void aResume_whoseWorkspaceOrPackageIsRefused_namesThemAsTheRecordDoes() {
        RunServices services = fx.services();
        String id = services.start(start(IN_PROJECT, PROJECT_WORKSPACE), null, null).runId();
        services.answer(id, new AnswerRequest("approval", 0, "Yes", null, null));
        fx.refusedDirectories.put(PROJECT_WORKSPACE, "it does not exist (" + PROJECT_WORKSPACE + ")");
        fx.refusedDirectories.put(IN_PROJECT, "it directly holds the socket " + IN_PROJECT + "/s");

        assertThatThrownBy(() -> services.resume(resumeOf(id), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("the workspace /work/[redacted:PROJECT] cannot be mounted")
                .hasMessageContaining("it does not exist (/work/[redacted:PROJECT])")
                .hasMessageContaining("the package /pkg/[redacted:PROJECT] cannot be mounted")
                .message().doesNotContain(PROJECT);
        assertThat(fx.types(id)).doesNotContain(RecordTypes.RUN_RESUMED);
    }

    /**
     * With --allow-changed-package a resume runs the definition recorded at the start. One whose recorded copy had a
     * secret's value replaced is not that definition, so the resume is refused rather than run the replacement.
     */
    @Test
    void aResumeWithAChangedPackage_refusesARecordedDefinitionThatLostASecretsValue() {
        RunServices services = fx.services();
        String id = services.start(start(PUSH, AppFixture.WORKSPACE), null, null).runId();
        services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        fx.packages.put(PUSH, """
                {"id": "wf-push", "name": "Push", "blocks": [
                  {"type": "shell", "name": "login", "config": {"command": "login", "secrets": ["REGISTRY_HOST"]}},
                  {"type": "approval", "name": "ok", "config": {"question": "Go?", "options": ["Yes", "No"]}},
                  {"type": "shell", "name": "push", "config": {"command": "push", "secrets": ["REGISTRY_HOST"]}}
                ]}""");

        assertThatThrownBy(() -> services.resume(new ResumeRequest(id, false, true), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("[redacted:REGISTRY_HOST]")
                .hasMessageContaining("restore the package");
        assertThat(fx.types(id)).doesNotContain(RecordTypes.RUN_RESUMED);
        assertThat(fx.commands.commands()).containsExactly("login --registry " + HOST);
    }

    // ---- a replacement the redactor did not write ----

    private static final String SOURCE = "https://git.example.org/org/repo";
    /** A value this home holds that the run below neither declares nor uses. */
    private static final String UNRELATED = "plain-value-of-another-run";

    /**
     * Rewrites the run.started of a run and chains the lines after it again, as a person forging a record, or copying
     * one from elsewhere, would.
     */
    private void rewriteStarted(String id, String from, String to) {
        var log = fx.run(id).log();
        java.util.List<byte[]> lines = log.readAll();
        log.dropLast(lines.size());
        String before = null;
        String after = null;
        for (int i = 0; i < lines.size(); i++) {
            String line = new String(lines.get(i), StandardCharsets.UTF_8);
            if (i == 0) {
                assertThat(line).contains(from);
                line = line.replace(from, to);
            } else {
                assertThat(line).contains(before);
                line = line.replace(before, after);
            }
            before = traceworks.core.record.EventChain.sha256Hex(lines.get(i));
            after = log.append(line.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * A replacement in a recorded source is made real only for a name the run redacts. Any other one was not written
     * by the redactor: the value of that name goes nowhere, least of all into the URL of a clone.
     */
    @Test
    void aResume_neverPutsAValueTheRunDoesNotRedact_intoTheSourceOfAClone() {
        fx.credentials.put("SOME_NAME", UNRELATED);
        RunServices services = fx.services();
        String id = services.start(AppFixture.sourced(AppFixture.DEPLOY, SOURCE, null), null, null).runId();
        services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        fx.sources.present.clear();
        rewriteStarted(id, "\"workspace_source\":\"" + SOURCE + "\"",
                "\"workspace_source\":\"https://elsewhere.example/[redacted:SOME_NAME]/repo\"");
        int callsBefore = fx.sources.calls().size();

        assertThatThrownBy(() -> services.resume(resumeOf(id), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("[redacted:SOME_NAME]")
                .message().doesNotContain(UNRELATED);
        assertThat(fx.sources.calls()).as("no clone").hasSize(callsBefore);
        assertThat(fx.sources.calls()).noneMatch(call -> call.contains(UNRELATED));
        assertThat(fx.types(id)).doesNotContain(RecordTypes.RUN_RESUMED);
    }

    /**
     * A replacement in a recorded source is never made real for a clone, not even for a name the run redacts: whoever
     * can rewrite the record (a run folder copied from elsewhere) chooses the host, and the value would go there.
     */
    @Test
    void aResume_neverPutsAValueTheRunRedacts_intoTheSourceOfAClone() {
        RunServices services = fx.services();
        String id = services.start(AppFixture.sourced(PUSH, SOURCE, null), null, null).runId();
        services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        fx.sources.present.clear();
        rewriteStarted(id, "\"workspace_source\":\"" + SOURCE + "\"",
                "\"workspace_source\":\"https://elsewhere.example/[redacted:REGISTRY_HOST]/repo\"");
        int callsBefore = fx.sources.calls().size();

        assertThatThrownBy(() -> services.resume(resumeOf(id), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("is missing")
                .hasMessageContaining("[redacted:REGISTRY_HOST]")
                .hasMessageContaining("start the run again with --source")
                .message().doesNotContain(HOST);
        assertThat(fx.sources.calls()).as("no clone").hasSize(callsBefore);
        assertThat(fx.types(id)).doesNotContain(RecordTypes.RUN_RESUMED);
    }

    /** The checkout is still there: nothing is cloned, so the recorded source, redacted or not, is not used. */
    @Test
    void aResume_inACheckoutThatIsStillThere_goesOn_thoughItsRecordedSourceHoldsAReplacement() {
        RunServices services = fx.services();
        String id = services.start(AppFixture.sourced(PUSH, "https://" + HOST + "/repo", null), null, null)
                .runId();
        assertThat(fx.events(id).getFirst().fields().path("workspace_source").asText())
                .isEqualTo("https://[redacted:REGISTRY_HOST]/repo");
        services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        int callsBefore = fx.sources.calls().size();

        RunResult resumed = services.resume(resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(traceworks.core.model.RunStatus.COMPLETED);
        assertThat(fx.sources.calls().subList(callsBefore, fx.sources.calls().size()))
                .noneMatch(call -> call.startsWith("checkout"));
        assertThat(fx.ofType(id, RecordTypes.RUN_RESUMED).getFirst().fields().path("workspace_cloned")
                .asBoolean(true)).isFalse();
    }

    /**
     * A run folder that is not in the runs folder of this home (a copy, opened by its path) restores no checkout by cloning:
     * its record was not written here, and the clone would run git on the host against whatever it names.
     */
    @Test
    void aResume_ofARunFolderOutsideTheStore_doesNotCloneItsMissingCheckout() {
        RunServices services = fx.services();
        String gone = services.start(AppFixture.sourced(AppFixture.DEPLOY, SOURCE, null), null, null).runId();
        String kept = services.start(AppFixture.sourced(AppFixture.DEPLOY, SOURCE, null), null, null).runId();
        for (String id : java.util.List.of(gone, kept)) {
            services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
            fx.run(id).simulateOutsideStore();
        }
        fx.sources.present.removeIf(path -> path.endsWith(gone));
        int callsBefore = fx.sources.calls().size();

        assertThatThrownBy(() -> services.resume(resumeOf(gone), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("is missing")
                .hasMessageContaining("not in the runs folder of this home")
                .hasMessageContaining("start the run again with --source");
        assertThat(fx.sources.calls()).as("no clone").hasSize(callsBefore);
        assertThat(fx.types(gone)).doesNotContain(RecordTypes.RUN_RESUMED);

        assertThat(services.resume(resumeOf(kept), null, null).status())
                .as("a checkout that is still there is used as before")
                .isEqualTo(traceworks.core.model.RunStatus.COMPLETED);
    }

    /** The same for the package directory and the workspace: neither is looked for at a path made of such a value. */
    @Test
    void aResume_neverPutsAValueTheRunDoesNotRedact_intoAPath() {
        fx.credentials.put("SOME_NAME", UNRELATED);
        fx.packages.put(Path.of("/pkg/" + UNRELATED), """
                {"id": "wf-other", "name": "Other", "blocks": [
                  {"type": "shell", "name": "other", "config": {"command": "./other"}}
                ]}""");
        RunServices services = fx.services();
        String inPackage = services.start(AppFixture.request(AppFixture.DEPLOY), null, null).runId();
        String inWorkspace = services.start(AppFixture.request(AppFixture.DEPLOY), null, null).runId();
        for (String id : java.util.List.of(inPackage, inWorkspace)) {
            services.answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        }
        rewriteStarted(inPackage, "\"dir\":\"" + AppFixture.DEPLOY + "\"", "\"dir\":\"/pkg/[redacted:SOME_NAME]\"");
        rewriteStarted(inWorkspace, "\"workspace\":\"" + AppFixture.WORKSPACE + "\"",
                "\"workspace\":\"/work/[redacted:SOME_NAME]\"");

        for (String id : java.util.List.of(inPackage, inWorkspace)) {
            assertThatThrownBy(() -> services.resume(new ResumeRequest(id, false, true), null, null))
                    .isInstanceOf(RunRefusedException.class)
                    .hasMessageContaining("[redacted:SOME_NAME]")
                    .message().doesNotContain(UNRELATED);
            assertThat(fx.types(id)).doesNotContain(RecordTypes.RUN_RESUMED);
        }
        assertThat(fx.commands.commands()).doesNotContain("./other", "./done");
    }

    /**
     * A source, a workspace or a package directory that holds the start of a replacement is refused at the start: what
     * a record then holds of one is always what the redactor wrote.
     */
    @Test
    void aRefusedSource_isNotRepeated_itMayHoldALogin() {
        assertThatThrownBy(() -> fx.services().start(AppFixture.sourced(AppFixture.BUILD,
                "https://flo:hunter2hunter2@elsewhere.example/[redacted:PROJECT]/repo?token=abcdefgh12", null), null,
                null)).isInstanceOf(RunRefusedException.class).hasMessageContaining("its source contains '[redacted:'")
                .message().doesNotContain("hunter2hunter2").doesNotContain("abcdefgh12").doesNotContain("elsewhere");
    }

    @Test
    void aStart_refusesASourceOrADirectoryThatLooksRedacted() {
        RunServices services = fx.services();
        Path looksRedacted = Path.of("/pkg/[redacted:PROJECT]");
        fx.packages.put(looksRedacted, """
                {"id": "wf-looks", "name": "Looks", "blocks": [
                  {"type": "shell", "name": "x", "config": {"command": "./x"}}
                ]}""");

        assertThatThrownBy(() -> services.start(
                AppFixture.sourced(AppFixture.BUILD, "https://elsewhere.example/[redacted:PROJECT]/repo", null), null,
                null)).isInstanceOf(RunRefusedException.class).hasMessageContaining("[redacted:")
                .hasMessageContaining("source").message().doesNotContain(PROJECT);
        assertThatThrownBy(() -> services.start(start(AppFixture.BUILD, Path.of("/work/[redacted:PROJECT]")), null, null))
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("workspace");
        assertThatThrownBy(() -> services.start(start(looksRedacted, AppFixture.WORKSPACE), null, null))
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("package directory");

        assertThat(fx.sources.calls()).as("nothing was cloned").isEmpty();
        assertThat(fx.store.runIds()).isEmpty();
        assertThat(fx.commands.commands()).isEmpty();
    }
}
