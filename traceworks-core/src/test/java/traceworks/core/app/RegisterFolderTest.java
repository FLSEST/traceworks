package traceworks.core.app;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import traceworks.core.model.RunStatus;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.CommandRunner.Mount;
import traceworks.core.port.ContainerRuntime.ContainerRun;
import traceworks.core.port.RegisterStore;
import traceworks.core.record.EventChain;
import traceworks.core.record.RecordedEvent;
import traceworks.core.register.RegisterLog;
import traceworks.core.testing.FakeCommandRunner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static traceworks.core.app.AppFixture.request;
import static traceworks.core.app.AppFixture.resumeOf;

/**
 * The register folder of a run: what the register knew when the run of an audit workflow started, as files its
 * steps read ({@code {{ register_dir }}}), recorded with the head of the register's log and the digest of each file.
 */
class RegisterFolderTest {

    /** A step that reads the folder, then the report. */
    private static final Path AUDIT = Path.of("/pkgs/audit");
    /** The same audit with an approval between the two steps. */
    private static final Path PARKING = Path.of("/pkgs/parking");
    /** A step that reads the folder, an AI step, the report. */
    private static final Path JUDGED = Path.of("/pkgs/judged");
    private static final String FIRST_RUN = "00000000-0000-0000-0000-000000000001";
    private static final String EMPTY = EventChain.sha256Hex(new byte[0]);

    private final AppFixture fx = new AppFixture();

    RegisterFolderTest() {
        String audit = "\"metadata\": {\"audit\": {\"enabled\": true, \"key\": \"hello\", \"category\": \"example\","
                + " \"report_block\": \"report\"}}";
        fx.packages.put(AUDIT, "{\"id\": \"wf-audit\", \"name\": \"Audit\", " + audit + ", \"blocks\": ["
                + "{\"type\": \"shell\", \"name\": \"known\", \"config\": {\"command\": \"./known {{ register_dir }}\"}},"
                + "{\"type\": \"shell\", \"name\": \"report\", \"config\": {\"command\": \"./report\"}}]}");
        fx.packages.put(PARKING, "{\"id\": \"wf-audit\", \"name\": \"Audit\", " + audit + ", \"blocks\": ["
                + "{\"type\": \"shell\", \"name\": \"known\", \"config\": {\"command\": \"./known {{ register_dir }}\"}},"
                + "{\"type\": \"approval\", \"name\": \"go\", \"config\": {\"question\": \"Go on?\","
                + " \"options\": [\"Yes\", \"No\"]}},"
                + "{\"type\": \"shell\", \"name\": \"report\", \"config\": {\"command\": \"./report\"}}]}");
        fx.packages.put(JUDGED, "{\"id\": \"wf-audit\", \"name\": \"Audit\", " + audit + ", \"blocks\": ["
                + "{\"type\": \"shell\", \"name\": \"known\", \"config\": {\"command\": \"./known {{ register_dir }}\"}},"
                + "{\"type\": \"simple\", \"name\": \"judge\", \"config\": {\"command\": \"Judge this\"}},"
                + "{\"type\": \"shell\", \"name\": \"report\", \"config\": {\"command\": \"./report\"}}]}");
        fx.commands.on("./known", "read");
        report("A.java", "B.java");
    }

    private void report(String... subjects) {
        StringBuilder findings = new StringBuilder();
        for (String subject : subjects) {
            if (!findings.isEmpty()) findings.append(",");
            findings.append("{\"ruleKey\":\"size\",\"subject\":\"").append(subject)
                    .append("\",\"severity\":\"MEDIUM\",\"title\":\"Big ").append(subject)
                    .append("\",\"description\":\"Split ").append(subject)
                    .append("\",\"evidence\":\"files 3\"}");
        }
        fx.commands.on("./report", "# Report\ngate_status: GREEN\n\n<!-- traceworks:audit-result:v1\n"
                + "{\"snapshotComplete\":true,\"findings\":[" + findings + "]}\n-->\n");
    }

    private JsonNode recorded(String runId) {
        return fx.ofType(runId, "run.started").getFirst().fields().path("register");
    }

    private String file(String runId, String name) {
        return new String(fx.run(runId).registerFolder().get(name), StandardCharsets.UTF_8);
    }

    private String commandOf(String prefix) {
        return fx.commands.requests().stream().map(r -> r.command()).filter(c -> c.startsWith(prefix))
                .reduce((a, b) -> b).orElseThrow();
    }

    @Test
    void theFirstRun_getsTheFolderWithEmptyFiles_andRecordsThatThereWasNoRegister() {
        RunResult result = fx.services().start(request(AUDIT), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(commandOf("./known")).as("a host run: the folder's own path")
                .isEqualTo("./known /runs/" + result.runId() + "/register");
        assertThat(fx.run(result.runId()).registerFolder().keySet()).containsExactly("findings.jsonl",
                "resolved.jsonl", "items.jsonl", "previous-report.md");
        assertThat(fx.run(result.runId()).registerFolder().values()).allSatisfy(bytes -> assertThat(bytes).isEmpty());
        JsonNode register = recorded(result.runId());
        assertThat(register.path("key").asText()).isEqualTo("hello");
        assertThat(register.path("seq").isNull()).as("no register yet").isTrue();
        assertThat(register.path("head").isNull()).isTrue();
        assertThat(register.path("files")).hasSize(4).allSatisfy(recordedFile -> {
            assertThat(recordedFile.path("sha256").asText()).isEqualTo(EMPTY);
            assertThat(recordedFile.path("size").asLong()).isZero();
        });
        assertThat(register.path("files").get(0).path("name").asText()).isEqualTo("findings.jsonl");
    }

    @Test
    void theNextRun_getsWhatTheRegisterKnows_andRecordsTheHeadItReadAndTheDigestOfEachFile() {
        fx.services().start(request(AUDIT), null, null);
        int seq;
        String head;
        try (RegisterStore.Opened opened = fx.registers.open("hello")) {
            RegisterLog log = new RegisterLog(opened, fx.clock);
            seq = log.lastSeq();
            head = log.head();
        }

        RunResult second = fx.services().start(request(AUDIT), null, null);

        String findings = file(second.runId(), "findings.jsonl");
        assertThat(findings.lines()).hasSize(2);
        assertThat(findings).contains("\"subject\":\"A.java\"", "\"description\":\"Split A.java\"",
                "\"evidence\":\"files 3\"", "\"status\":\"OPEN\"", "\"run_id\":\"" + FIRST_RUN + "\"");
        assertThat(file(second.runId(), "previous-report.md")).startsWith("# Report\ngate_status GREEN")
                .doesNotContain("audit-result");
        JsonNode register = recorded(second.runId());
        assertThat(register.path("seq").asInt()).isEqualTo(seq);
        assertThat(register.path("head").asText()).isEqualTo(head);
        for (JsonNode recordedFile : register.path("files")) {
            byte[] content = fx.run(second.runId()).registerFolder().get(recordedFile.path("name").asText());
            assertThat(recordedFile.path("sha256").asText()).isEqualTo(EventChain.sha256Hex(content));
            assertThat(recordedFile.path("size").asLong()).isEqualTo(content.length);
        }
        assertThat(fx.services().view(second.runId()).register().seq()).isEqualTo(seq);
    }

    @Test
    void aWorkflowThatIsNoAudit_hasNoFolder_andItsRunStartedSaysNothingOfARegister() {
        RunResult result = fx.services().start(request(AppFixture.BUILD), null, null);

        assertThat(fx.run(result.runId()).registerFolder()).isNull();
        assertThat(fx.ofType(result.runId(), "run.started").getFirst().fields().has("register")).isFalse();
        assertThat(fx.services().view(result.runId()).register()).isNull();
    }

    @Test
    void anInputNamedRegisterDir_isRefused() {
        assertThatThrownBy(() -> fx.services().start(new StartRequest(AUDIT, Map.of("register_dir", "/etc"),
                AppFixture.WORKSPACE, false, false), null, null))
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("register_dir is reserved");
    }

    // ---- child workflows, and prompts ----

    /**
     * The folder is the run's: a workflow started by a run that is no audit has none, whatever its own metadata
     * say, so a command that names it fails before anything runs instead of running with the placeholder as text.
     */
    @Test
    void aChildWithItsOwnAuditBlock_underARunThatIsNoAudit_failsTheStepThatNamesTheFolder() {
        Path parent = Path.of("/pkgs/plain-parent");
        fx.packages.put(parent, "{\"id\": \"wf-plain\", \"name\": \"Plain\", \"blocks\": ["
                + "{\"type\": \"sub-workflow\", \"name\": \"child\", \"config\": {\"workflow_id\": \"../audit\"}}]}");

        RunResult result = fx.services().start(request(parent), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(fx.commands.commands()).as("nothing ran").isEmpty();
        assertThat(allText(result.runId())).contains("{{ register_dir }} names the register folder of a run, and this"
                + " run has none: only a run of an audit workflow (metadata.audit with enabled: true) has the folder,"
                + " and a workflow started by a run that is no audit gets none. Nothing was run");
    }

    /** The other direction: a child of an audit run sees the folder of that run, whatever its own metadata say. */
    @Test
    void aChildThatIsNoAudit_underAnAuditRun_seesTheFolderOfThatRun() {
        Path parent = Path.of("/pkgs/audit-parent");
        Path child = Path.of("/pkgs/reader-child");
        fx.packages.put(parent, "{\"id\": \"wf-audit\", \"name\": \"Audit\", \"metadata\": {\"audit\":"
                + " {\"enabled\": true, \"key\": \"hello\", \"category\": \"example\", \"report_block\": \"report\"}},"
                + " \"blocks\": [{\"type\": \"sub-workflow\", \"name\": \"child\", \"config\": {\"workflow_id\":"
                + " \"../reader-child\"}}, {\"type\": \"shell\", \"name\": \"report\", \"config\": {\"command\":"
                + " \"./report\"}}]}");
        fx.packages.put(child, "{\"id\": \"wf-reader\", \"name\": \"Reader\", \"blocks\": [{\"type\": \"shell\","
                + " \"name\": \"known\", \"config\": {\"command\": \"./known {{ register_dir }}\"}}]}");

        RunResult result = fx.services().start(request(parent), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(commandOf("./known")).isEqualTo("./known /runs/" + result.runId() + "/register");
    }

    /** No model is handed the path: a prompt that names the folder fails its step, and no AI call is made. */
    @Test
    void aPromptThatNamesTheFolder_failsItsStep_andNoAiCallIsMade() {
        fx.settings = OperatorSettings.defaults();
        fx.withClaude();
        fx.containers.on(AppFixture.SHELL_ID, new CommandResult(0, "# Report\n", "", 1, false));
        Path prompting = Path.of("/pkgs/prompting");
        fx.packages.put(prompting, "{\"id\": \"wf-audit\", \"name\": \"Audit\", \"metadata\": {\"audit\":"
                + " {\"enabled\": true, \"key\": \"hello\", \"category\": \"example\", \"report_block\": \"report\"}},"
                + " \"blocks\": [{\"type\": \"simple\", \"name\": \"judge\", \"config\": {\"command\":"
                + " \"Read {{ register_dir }}/findings.jsonl\"}}, {\"type\": \"shell\", \"name\": \"report\","
                + " \"config\": {\"command\": \"./report\"}}]}");

        RunResult result = fx.services().start(request(prompting), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(fx.claude.calls()).isEmpty();
        assertThat(fx.containers.runs()).isEmpty();
        assertThat(allText(result.runId())).contains("{{ register_dir }} stands in the prompt of an AI step: the"
                + " container of an AI call does not get the register folder, and no model is handed its path. Let a"
                + " shell step read the files and put what the model needs into its output").doesNotContain("/runs/"
                + result.runId() + "/register/findings.jsonl");
    }

    /** Every blob of the run's record as one text, to look for a message in. */
    private String allText(String runId) {
        StringBuilder all = new StringBuilder();
        fx.run(runId).blobs().all().forEach(blob -> all.append(new String(blob, StandardCharsets.UTF_8)).append('\n'));
        return all.toString();
    }

    // ---- containers ----

    /** A container run: every shell step mounts the folder read-only at the fixed path; the AI step does not. */
    @Test
    void containerRun_theStepsMountTheFolderReadOnly_andTheAiStepDoesNot() {
        fx.settings = OperatorSettings.defaults();
        fx.withClaude();
        fx.containers.on(AppFixture.SHELL_ID, new CommandResult(0, "# Report\n", "", 1, false));
        fx.claude.answers("fine");

        RunResult result = fx.services().start(request(JUDGED), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        Mount folder = new Mount("/runs/" + result.runId() + "/register", "/register", true);
        List<ContainerRun> runs = fx.containers.runs();
        assertThat(runs).hasSize(3);
        assertThat(runs.get(0).args()).containsExactly("-c", "./known /register");
        assertThat(runs.get(0).mounts()).contains(folder);
        assertThat(runs.get(0).declaredMounts()).as("one of the run's own directories").isEmpty();
        assertThat(runs.get(2).mounts()).contains(folder);
        ContainerRun ai = runs.get(1);
        assertThat(ai.imageId()).isEqualTo(AppFixture.CLAUDE_ID);
        assertThat(ai.mounts()).extracting(Mount::containerPath).doesNotContain("/register");
        assertThat(ai.mounts()).extracting(Mount::hostPath).doesNotContain(folder.hostPath());
        List<RecordedEvent> commands = fx.ofType(result.runId(), "step.command");
        assertThat(commands.get(0).fields().path("mounts").toString()).as("the record names the mount")
                .contains("/register");
        assertThat(commands.get(1).fields().path("mounts").toString()).doesNotContain("/register");
    }

    // ---- a folder that changed ----

    /**
     * A host run: a step on the host changes a file of the folder. The next step, which would read it, does not
     * start, and the report that never came changes no finding.
     */
    @Test
    void hostRun_aStepThatChangesTheFolder_stopsEveryStepAfterIt() {
        fx.commands.on("./known", changed -> {
            fx.run(FIRST_RUN).registerFolder().put("findings.jsonl", "{\"forged\":true}\n".getBytes(
                    StandardCharsets.UTF_8));
            return FakeCommandRunner.ok("read");
        });

        RunResult result = fx.services().start(request(AUDIT), null, null);

        assertThat(fx.commands.commands()).as("the report step did not start").doesNotContain("./report");
        assertThat(result.status()).isNotEqualTo(RunStatus.COMPLETED);
        String refusal = new String(fx.run(FIRST_RUN).blobs().all().stream()
                .filter(blob -> new String(blob, StandardCharsets.UTF_8).contains("findings.jsonl"))
                .filter(blob -> new String(blob, StandardCharsets.UTF_8).contains("register folder"))
                .findFirst().orElseThrow(), StandardCharsets.UTF_8);
        assertThat(refusal).contains("findings.jsonl", "is not the file the run started with");
        assertThat(result.register().applied()).isFalse();
    }

    @Test
    void aFileAddedToTheFolder_orOneTakenAway_isAChangeToo() {
        RunResult result = fx.services().start(request(AUDIT), null, null);
        VerifyReport before = fx.services().verify(result.runId());
        assertThat(before.ok()).isTrue();
        assertThat(before.register()).isEqualTo("ok (4 files of the register hello, before its first line)");

        fx.run(result.runId()).registerFolder().put("extra.txt", new byte[] {1});
        VerifyReport added = fx.services().verify(result.runId());
        fx.run(result.runId()).registerFolder().remove("extra.txt");
        fx.run(result.runId()).registerFolder().remove("items.jsonl");
        VerifyReport removed = fx.services().verify(result.runId());

        assertThat(added.ok()).isFalse();
        assertThat(added.register()).contains("extra.txt", "is not a file the run started with");
        assertThat(removed.ok()).isFalse();
        assertThat(removed.register()).contains("items.jsonl", "is missing");
    }

    // ---- verify ----

    @Test
    void verify_comparesTheFilesWithTheDigestsTheRunRecorded() {
        fx.services().start(request(AUDIT), null, null);
        RunResult second = fx.services().start(request(AUDIT), null, null);
        int seq = recorded(second.runId()).path("seq").asInt();

        VerifyReport intact = fx.services().verify(second.runId());
        fx.run(second.runId()).registerFolder().put("previous-report.md", "another report".getBytes(
                StandardCharsets.UTF_8));
        VerifyReport changed = fx.services().verify(second.runId());
        fx.run(second.runId()).removeRegisterFolder();
        VerifyReport gone = fx.services().verify(second.runId());

        assertThat(intact.ok()).isTrue();
        assertThat(intact.register()).isEqualTo("ok (4 files of the register hello at seq " + seq + ")");
        assertThat(changed.ok()).isFalse();
        assertThat(changed.register()).contains("previous-report.md", "is not the file the run started with");
        assertThat(gone.ok()).isFalse();
        assertThat(gone.register()).contains("the register folder of the run is missing");
        assertThat(fx.services().verify(fx.services().start(request(AppFixture.BUILD), null, null).runId())
                .register()).as("a run of no audit").isEqualTo(VerifyReport.NOT_CHECKED);
    }

    // ---- resume ----

    @Test
    void aResumedRun_goesOnWithTheFolderItStartedWith() {
        fx.services().start(request(AUDIT), null, null);
        RunResult parked = fx.services().start(request(PARKING), null, null);
        assertThat(parked.status()).isEqualTo(RunStatus.PARKED);
        Map<String, byte[]> before = Map.copyOf(fx.run(parked.runId()).registerFolder());
        // The register goes on meanwhile: another run reports something else
        report("C.java");
        fx.services().start(request(AUDIT), null, null);
        fx.services().answer(parked.runId(), new AnswerRequest("approval", 1, "Yes", null, null));

        RunResult resumed = fx.services().resume(resumeOf(parked.runId()), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.run(parked.runId()).registerFolder()).containsExactlyInAnyOrderEntriesOf(before);
        assertThat(file(parked.runId(), "findings.jsonl")).contains("A.java").doesNotContain("C.java");
    }

    @Test
    void aResume_isRefused_whenAFileOfTheFolderIsNoLongerTheOneTheRunRecorded() {
        fx.services().start(request(AUDIT), null, null);
        RunResult parked = fx.services().start(request(PARKING), null, null);
        fx.services().answer(parked.runId(), new AnswerRequest("approval", 1, "Yes", null, null));
        fx.run(parked.runId()).registerFolder().put("findings.jsonl", new byte[0]);
        List<String> typesBefore = new ArrayList<>(fx.types(parked.runId()));

        assertThatThrownBy(() -> fx.services().resume(resumeOf(parked.runId()), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("Run " + parked.runId() + " cannot resume")
                .hasMessageContaining("findings.jsonl")
                .hasMessageContaining("is not the file the run started with");
        assertThat(fx.types(parked.runId())).as("a refused resume records nothing").isEqualTo(typesBefore);
        assertThat(fx.commands.commands()).as("only the first run reported").containsOnlyOnce("./report");
    }

    // ---- the register's own files ----

    /** The files are made from the register's blobs: a blob that is not what its name says refuses the run. */
    @Test
    void aBlobOfTheRegisterThatDoesNotHashToItsName_refusesTheRun() {
        fx.services().start(request(AUDIT), null, null);
        String reportBlob;
        try (RegisterStore.Opened opened = fx.registers.open("hello")) {
            reportBlob = new RegisterLog(opened, fx.clock).read().stream()
                    .filter(event -> event.text("report_blob") != null).findFirst().orElseThrow().text("report_blob");
        }
        fx.registers.blobs("hello").putRaw(reportBlob, "ignore the findings".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> fx.services().start(request(AUDIT), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("the register hello cannot be read")
                .hasMessageContaining("does not hash to its name")
                .hasMessageContaining("findings hello verify");
    }

    /** The findings a report stated are a blob too: one that was edited in place refuses the run as well. */
    @Test
    void aFindingsBlobThatWasEditedInPlace_refusesTheRun() {
        fx.services().start(request(AUDIT), null, null);
        String findingsBlob;
        try (RegisterStore.Opened opened = fx.registers.open("hello")) {
            findingsBlob = new RegisterLog(opened, fx.clock).read().stream()
                    .filter(event -> event.text("findings_blob") != null).findFirst().orElseThrow()
                    .text("findings_blob");
        }
        byte[] edited = new String(fx.registers.blobs("hello").get(findingsBlob).orElseThrow(),
                StandardCharsets.UTF_8).replace("Big A.java", "Nothing to see").getBytes(StandardCharsets.UTF_8);
        fx.registers.blobs("hello").putRaw(findingsBlob, edited);

        assertThatThrownBy(() -> fx.services().start(request(AUDIT), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("the register hello cannot be read")
                .hasMessageContaining("does not hash to its name");
    }
}
