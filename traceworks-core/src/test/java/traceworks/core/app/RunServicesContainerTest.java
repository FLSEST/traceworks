package traceworks.core.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.app.AppFixture.Background;
import traceworks.core.app.AppFixture.Events;
import traceworks.core.engine.EventLogException;
import traceworks.core.model.RunStatus;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.CommandRunner.Mount;
import traceworks.core.port.ContainerRuntime.ContainerRun;
import traceworks.core.record.RecordedEvent;
import traceworks.core.testing.FakeCommandRunner;
import traceworks.core.testing.InMemoryEventLog;
import org.junit.jupiter.api.AfterEach;
import traceworks.core.port.HostDirectories;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static traceworks.core.app.AppFixture.BUILD;
import static traceworks.core.app.AppFixture.DEPLOY;
import static traceworks.core.app.AppFixture.PARENT;
import static traceworks.core.app.AppFixture.SHELL_ID;
import static traceworks.core.app.AppFixture.SHELL_TAG;
import static traceworks.core.app.AppFixture.WORKSPACE;
import static traceworks.core.app.AppFixture.request;
import static traceworks.core.app.AppFixture.resumeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Where a run runs its steps, on in-memory ports and a fake container runtime: a container run wherever a runtime
 * works, a host run with the operator's consent where none works, the start check before any run exists, and the
 * leftover containers of a resume and a cancel.
 */
class RunServicesContainerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** A shell step without an image, then one in {@code alpine:3}. */
    private static final Path MIXED = Path.of("/pkg/mixed");
    private static final String ALPINE_ID = "sha256:" + "a1".repeat(32);

    private final AppFixture fx = new AppFixture();
    private final List<Background<?>> background = new ArrayList<>();

    RunServicesContainerTest() {
        fx.settings = OperatorSettings.defaults();
        fx.packages.put(MIXED, """
                {"id": "wf-mixed", "name": "Mixed", "blocks": [
                  {"type": "shell", "name": "plain", "config": {"command": "./build"}},
                  {"type": "shell", "name": "tool", "config": {"command": "./scan", "container_image": "alpine:3"}}
                ]}""");
    }

    @AfterEach
    void noThreadOutlivesAUseCase() throws InterruptedException {
        for (Background<?> work : background) {
            work.awaitEnd();
        }
        assertThat(AppFixture.liveServiceThreads()).isEmpty();
    }

    private RecordedEvent started(String id) {
        return fx.events(id).getFirst();
    }

    private List<RecordedEvent> commands(String id) {
        return fx.ofType(id, "step.command");
    }

    // ---- the target ----

    @Test
    void containerRun_isTheDefault_andRecordsExecutionAndRuntime() throws Exception {
        fx.withRuntime();
        fx.containers.on(SHELL_ID, run -> new CommandResult(0, "in a container", "", 1, false));

        RunResult result = fx.services().start(request(BUILD), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        RecordedEvent started = started(result.runId());
        assertThat(started.fields().path("execution").asText()).isEqualTo("container");
        assertThat(started.fields().path("container_runtime").path("name").asText()).isEqualTo("fake");
        assertThat(started.fields().path("container_runtime").path("version").asText()).isEqualTo("1.0.0");
        assertThat(fx.commands.commands()).as("nothing runs on the host").isEmpty();

        List<ContainerRun> runs = fx.containers.runs();
        assertThat(runs).hasSize(2);
        ContainerRun first = runs.getFirst();
        assertThat(first.imageId()).isEqualTo(SHELL_ID);
        assertThat(first.name()).isEqualTo("traceworks-" + result.runId() + "-1");
        assertThat(first.workingDirectory()).isEqualTo("/workspace");
        assertThat(first.args()).containsExactly("-c", "./build");
        assertThat(first.mounts()).containsExactly(new Mount(WORKSPACE.toString(), "/workspace", true),
                new Mount(BUILD.toString(), "/package", true),
                new Mount("/runs/" + result.runId() + "/scratch", "/scratch", false));
        assertThat(first.limits()).isEqualTo(fx.settings.containerLimits());

        for (RecordedEvent command : commands(result.runId())) {
            assertThat(command.fields().path("host").asBoolean()).isFalse();
            assertThat(command.fields().path("image").asText()).isEqualTo("shell");
            assertThat(command.fields().path("image_id").asText()).isEqualTo(SHELL_ID);
        }
        JsonNode inputs = JSON.readTree(fx.text(result.runId(), started, "inputs"));
        assertThat(inputs.has("package_dir")).as("the engine sets it from the run's paths").isFalse();
        JsonNode manifest = JSON.readTree(fx.run(result.runId()).readManifest().orElseThrow());
        assertThat(manifest.path("execution").asText()).isEqualTo("container");
        assertThat(manifest.path("images")).hasSize(1);
        assertThat(manifest.path("images").get(0).path("reference").asText()).isEqualTo("shell");
        assertThat(manifest.path("images").get(0).path("id").asText()).isEqualTo(SHELL_ID);
    }

    /**
     * The runtime keeps some things for the whole of a run (the networks of containers with listed hosts), so it is
     * told when a run has ended in this process: once, after the last step, whether the run finished or parked.
     */
    @Test
    void theRuntime_isToldWhenARunHasEndedInThisProcess_onceAfterItsLastStep() throws Exception {
        fx.withRuntime();
        java.util.List<Integer> endedWhileRunning = new java.util.ArrayList<>();
        fx.containers.on(SHELL_ID, run -> {
            endedWhileRunning.add(fx.containers.ended().size());
            return new CommandResult(0, "in a container", "", 1, false);
        });

        RunResult result = fx.services().start(request(BUILD), null, null);

        assertThat(endedWhileRunning).as("not while a step runs").isNotEmpty().containsOnly(0);
        assertThat(fx.containers.ended()).containsExactly(result.runId());
    }

    @Test
    void containerRun_seesItsPackageAndScratchAtTheFixedPaths() throws Exception {
        fx.withRuntime();
        fx.packages.put(Path.of("/pkg/paths"), """
                {"id": "wf-paths", "name": "Paths", "blocks": [
                  {"type": "shell", "name": "where",
                   "config": {"command": "echo {{ project_path }} {{ package_dir }} {{ scratch_dir }}"}}
                ]}""");

        RunResult result = fx.services().start(request(Path.of("/pkg/paths")), null, null);

        assertThat(fx.containers.runs().getFirst().args())
                .containsExactly("-c", "echo /workspace /package /scratch");
        assertThat(fx.run(result.runId()).scratchPrepared()).isEqualTo(1);
    }

    @Test
    void noRuntime_refusesAContainerRun_beforeCreatingIt() {
        RunServices services = fx.services();

        assertThatThrownBy(() -> services.start(request(BUILD), null, null))
                .isInstanceOf(RunRefusedException.class)
                .message().contains("podman", "docker", "--allow-host-execution");
        assertThat(fx.store.runIds()).isEmpty();
        assertThat(fx.ledger.prepared()).as("refused before the ledger is touched").isZero();
    }

    @Test
    void noRuntime_ofAConfiguredOne_namesIt() {
        fx.settings = new OperatorSettings(false, null, List.of(), "podman", null);

        assertThatThrownBy(() -> fx.services().start(request(BUILD), null, null))
                .isInstanceOf(RunRefusedException.class)
                .message().contains("container_runtime podman", "podman or docker");
    }

    /** The consent only allows a host run: where a container runtime works, the run runs in containers. */
    @Test
    void consent_withAWorkingRuntime_givesAContainerRun() {
        fx.withRuntime();
        fx.containers.present("alpine:3", ALPINE_ID);
        fx.settings = new OperatorSettings(true, null, List.of());

        RunResult byFlag = fx.services().start(new StartRequest(MIXED, Map.of(), WORKSPACE, true, false), null,
                null);
        RunResult byConfig = fx.services().start(request(MIXED), null, null);

        for (RunResult result : List.of(byFlag, byConfig)) {
            assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(started(result.runId()).fields().path("execution").asText()).isEqualTo("container");
        }
        assertThat(fx.commands.commands()).as("nothing runs on the host").isEmpty();
        assertThat(fx.containers.runs()).hasSize(4);
    }

    /** {@link #MIXED} after an approval: a host run that parks before any command. */
    private static final Path MIXED_LATER = Path.of("/pkg/mixed-later");

    /**
     * A host run that parked where no runtime worked, resumed where one works: it stays a host run, so its shell step
     * without an image runs on the host and its image step in a container that sees each directory at its host path.
     */
    @Test
    void hostRun_resumedWhereARuntimeWorks_runsShellStepsWithoutImageOnTheHost_andImageStepsInContainers() {
        fx.settings = new OperatorSettings(true, null, List.of());
        fx.packages.put(MIXED_LATER, """
                {"id": "wf-mixed-later", "name": "Mixed later", "blocks": [
                  {"type": "approval", "name": "go", "config": {"question": "Go?", "options": ["Yes", "No"]}},
                  {"type": "shell", "name": "plain", "config": {"command": "./build"}},
                  {"type": "shell", "name": "tool", "config": {"command": "./scan", "container_image": "alpine:3"}}
                ]}""");
        String id = fx.services().start(request(MIXED_LATER), null, null).runId();
        assertThat(started(id).fields().path("execution").asText()).isEqualTo("host");
        fx.services().answer(id, new AnswerRequest("approval", 0, "Yes", null, null));
        fx.withRuntime();
        fx.containers.present("alpine:3", ALPINE_ID).on(ALPINE_ID, FakeCommandRunner.ok("scanned"));
        fx.commands.on("./scan", "must not run on the host");

        RunResult result = fx.services().resume(resumeOf(id), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.ofType(id, "run.resumed").getFirst().fields().path("execution").asText()).isEqualTo("host");
        assertThat(fx.commands.commands()).containsExactly("./build");
        assertThat(fx.containers.runs()).hasSize(1);
        ContainerRun tool = fx.containers.runs().getFirst();
        assertThat(tool.imageId()).isEqualTo(ALPINE_ID);
        assertThat(tool.workingDirectory()).as("a host run mounts at the host paths").isEqualTo(WORKSPACE.toString());
        assertThat(tool.mounts()).first().isEqualTo(new Mount(WORKSPACE.toString(), WORKSPACE.toString(), true));
        List<RecordedEvent> commands = commands(id);
        assertThat(commands.get(0).fields().path("host").asBoolean()).isTrue();
        assertThat(commands.get(0).fields().path("image").isNull()).isTrue();
        assertThat(commands.get(1).fields().path("host").asBoolean()).isFalse();
        assertThat(commands.get(1).fields().path("image_id").asText()).isEqualTo(ALPINE_ID);
    }

    @Test
    void hostRun_withoutARuntime_starts_andOnlyItsImageStepsFail() {
        fx.settings = new OperatorSettings(true, null, List.of());

        RunResult result = fx.services().start(request(MIXED), null, null);

        assertThat(started(result.runId()).fields().path("container_runtime").isNull()).isTrue();
        assertThat(fx.commands.commands()).containsExactly("./build");
        RecordedEvent refused = fx.ofType(result.runId(), "step.command.finished").get(1);
        assertThat(refused.fields().path("exit_code").asInt()).isEqualTo(125);
    }

    // ---- the start check ----

    @Test
    void missingShellImage_refusesWithImagesBuild() {
        fx.runtime = fx.containers;   // a runtime without any image

        assertThatThrownBy(() -> fx.services().start(request(MIXED), null, null))
                .isInstanceOf(RunRefusedException.class)
                .message().contains("image " + SHELL_TAG + " is missing: run 'traceworks images build'",
                        "image alpine:3 is missing: run 'traceworks images build " + MIXED + "'");
        assertThat(fx.store.runIds()).isEmpty();
    }

    /** A host run resumed where a runtime works needs no shell image there, but the images its steps name. */
    @Test
    void hostRun_needsNoShellImage_butTheImagesItsStepsName() {
        fx.settings = new OperatorSettings(true, null, List.of());
        fx.packages.put(MIXED_LATER, """
                {"id": "wf-mixed-later", "name": "Mixed later", "blocks": [
                  {"type": "approval", "name": "go", "config": {"question": "Go?", "options": ["Yes", "No"]}},
                  {"type": "shell", "name": "tool", "config": {"command": "./scan", "container_image": "alpine:3"}}
                ]}""");
        String id = fx.services().start(request(MIXED_LATER), null, null).runId();
        fx.services().answer(id, new AnswerRequest("approval", 0, "Yes", null, null));
        fx.runtime = fx.containers;   // a runtime without any image

        assertThatThrownBy(() -> fx.services().resume(resumeOf(id), null, null))
                .isInstanceOf(RunRefusedException.class)
                .message().contains("alpine:3").doesNotContain(SHELL_TAG);
    }

    /**
     * The run's package and the child it runs from {@code ./child} each have {@code images/foo}, both built, and the
     * child's step names {@code ./images/foo}. A run resolves {@code ./images/} against its own package only, so it
     * never runs its own {@code foo} for the child's step: a literal {@code workflow_id} is refused at the start, one
     * with a placeholder at the child's admission.
     */
    @Test
    void childOfAnotherPackage_neverRunsTheRunsImageOfTheSameName() {
        fx.withRuntime();
        Path literal = Path.of("/pkg/imaged");
        Path placeholder = Path.of("/pkg/imaged-later");
        String child = """
                {"id": "wf-imaged-child", "name": "Child", "requires": ["package.images"], "blocks": [
                  {"type": "shell", "name": "scan", "config": {"command": "./scan", "container_image": "./images/foo"}}
                ]}""";
        fx.packages.put(literal, """
                {"id": "wf-imaged", "name": "Imaged", "blocks": [
                  {"type": "sub-workflow", "name": "child", "config": {"workflow_id": "./child"}}
                ]}""");
        fx.packages.put(placeholder, """
                {"id": "wf-imaged", "name": "Imaged", "blocks": [
                  {"type": "sub-workflow", "name": "child", "config": {"workflow_id": "{{ which }}"}}
                ]}""");
        for (Path root : List.of(literal, placeholder)) {
            fx.packages.images(root, "foo");
            fx.packages.put(root.resolve("child"), child);
            fx.packages.images(root.resolve("child"), "foo");
        }
        String rootFoo = "sha256:" + "f1".repeat(32);
        fx.containers.present("traceworks-pkg/wf-imaged/foo:bbbbbbbbbbbb", rootFoo)
                .present("traceworks-pkg/wf-imaged-child/foo:bbbbbbbbbbbb", "sha256:" + "f2".repeat(32))
                .on(rootFoo, FakeCommandRunner.ok("the run's foo"));

        assertThatThrownBy(() -> fx.services().start(request(literal), null, null))
                .isInstanceOf(RunRefusedException.class)
                .message().contains("./images/foo", "wf-imaged-child", "another package");
        assertThat(fx.store.runIds()).as("refused before the run exists").isEmpty();

        RunResult later = fx.services().start(new StartRequest(placeholder, Map.of("which", "./child"), WORKSPACE,
                false, false), null, null);

        assertThat(later.status()).isEqualTo(RunStatus.FAILED);
        assertThat(fx.containers.runs()).as("no container started, the run's foo least of all").isEmpty();
        assertThat(fx.ofType(later.runId(), "step.output")).anySatisfy(output ->
                assertThat(fx.text(later.runId(), output, "output")).contains("workflow refused: ", "./images/foo",
                        "another package"));
    }

    /**
     * Every step only reads the package, so a writable workspace that lies inside it is refused at the start; a
     * workspace there that no step writes is fine.
     */
    @Test
    void writableWorkspaceInsideThePackage_isRefusedAtTheStart() {
        fx.withRuntime();
        Path writes = Path.of("/pkg/writes");
        fx.packages.put(writes, """
                {"id": "wf-writes", "name": "Writes", "blocks": [
                  {"type": "shell", "name": "fix", "config": {"command": "./fix", "workspace_writable": true}}
                ]}""");
        Path inside = writes.resolve("src");

        assertThatThrownBy(() -> fx.services().start(new StartRequest(writes, Map.of(), inside, false, false), null,
                null)).isInstanceOf(RunRefusedException.class)
                .message().contains("the workspace " + inside, "inside the package " + writes, "workspace_writable");
        assertThat(fx.store.runIds()).isEmpty();

        RunResult readOnly = fx.services().start(new StartRequest(BUILD, Map.of(), BUILD, false, false), null, null);
        assertThat(readOnly.status()).isEqualTo(RunStatus.COMPLETED);
    }

    /** A step nested in a case of a switch that writes the workspace writes it as much as a top-level one. */
    @Test
    void writableWorkspaceInsideThePackage_isRefusedAtTheStart_whenANestedStepWritesIt() {
        fx.withRuntime();
        Path writes = Path.of("/pkg/nested-writes");
        fx.packages.put(writes, """
                {"id": "wf-nested-writes", "name": "Nested writes", "blocks": [
                  {"type": "switch", "name": "route", "config": {"expression": "fix", "cases": [
                    {"value": "fix", "blocks": [
                      {"type": "shell", "name": "fix", "config": {"command": "./fix", "workspace_writable": true}}
                    ]}]}}
                ]}""");
        Path inside = writes.resolve("src");

        assertThatThrownBy(() -> fx.services().start(new StartRequest(writes, Map.of(), inside, false, false), null,
                null)).isInstanceOf(RunRefusedException.class)
                .message().contains("the workspace " + inside, "inside the package " + writes, "workspace_writable");
        assertThat(fx.store.runIds()).isEmpty();
    }

    @Test
    void workspaceThatIsOrContainsTheHome_isRefused() {
        fx.withRuntime();
        fx.refusedDirectories.put(WORKSPACE, "it is or contains the home directory");
        fx.refusedDirectories.put(BUILD, "forbidden path /etc");

        assertThatThrownBy(() -> fx.services().start(request(BUILD), null, null))
                .isInstanceOf(RunRefusedException.class)
                .message().contains("the workspace " + WORKSPACE + " cannot be mounted: it is or contains the home"
                        + " directory", "the package " + BUILD + " cannot be mounted: forbidden path /etc");
        assertThat(fx.store.runIds()).isEmpty();
        assertThat(fx.checkedAs).as("each directory is checked as what it is, so its refusal gives fitting advice")
                .containsEntry(WORKSPACE, HostDirectories.Role.WORKSPACE)
                .containsEntry(BUILD, HostDirectories.Role.PACKAGE);
    }

    /**
     * The start check judges one resolution of a directory, and the run uses that one: a directory that something
     * swaps after the check (a link in its place) would otherwise reach the containers unjudged.
     */
    @Test
    void directorySwappedAfterTheStartCheck_isNotWhatTheRunUses() {
        fx.withRuntime();
        fx.containers.on(SHELL_ID, run -> new CommandResult(0, "in a container", "", 1, false));
        fx.swappedAfterFirstResolution.put(WORKSPACE, "/swapped/workspace");
        fx.swappedAfterFirstResolution.put(BUILD, "/swapped/package");

        RunResult result = fx.services().start(request(BUILD), null, null);

        assertThat(started(result.runId()).fields().path("workspace").asText()).isEqualTo(WORKSPACE.toString());
        assertThat(fx.containers.runs()).isNotEmpty().allSatisfy(run -> assertThat(run.mounts())
                .contains(new Mount(WORKSPACE.toString(), "/workspace", true),
                        new Mount(BUILD.toString(), "/package", true)));
    }

    @Test
    void everyRefusal_isListedInOneMessage() {
        fx.refusedDirectories.put(WORKSPACE, "forbidden path /");

        assertThatThrownBy(() -> fx.services().start(request(BUILD), null, null))
                .isInstanceOf(RunRefusedException.class)
                .message().startsWith("The run is refused: ").contains("podman", "forbidden path /");
    }

    // ---- resume and cancel ----

    private String parkedContainerRun() {
        fx.withRuntime();
        String id = fx.services().start(request(DEPLOY), null, null).runId();
        fx.services().answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        return id;
    }

    @Test
    void resume_keepsTheRecordedTarget_andRemovesLeftoverContainersFirst() {
        String id = parkedContainerRun();
        int before = fx.containers.runs().size();
        AtomicBoolean removedFirst = new AtomicBoolean();
        fx.containers.on(SHELL_ID, run -> {
            removedFirst.compareAndSet(false, fx.containers.leftoversRemoved().contains(id));
            return FakeCommandRunner.ok("done");
        });
        fx.settings = new OperatorSettings(true, null, List.of());   // the machine now allows host runs

        RunResult resumed = fx.services().resume(resumeOf(id), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.containers.runs()).as("still a container run").hasSize(before + 1);
        assertThat(fx.commands.commands()).isEmpty();
        assertThat(fx.containers.leftoversRemoved()).containsExactly(id);
        assertThat(removedFirst).as("before the first container of the resume").isTrue();
        JsonNode runtime = fx.ofType(id, "run.resumed").getFirst().fields().path("container_runtime");
        assertThat(runtime.path("name").asText()).as("the resuming process's runtime").isEqualTo("fake");
        assertThat(runtime.path("version").asText()).isEqualTo("1.0.0");
        assertThat(fx.run(id).scratchPrepared()).as("a run folder from before scratch directories gets one")
                .isEqualTo(2);
    }

    /**
     * A container a killed process left can still write into the run's directories. When its removal
     * cannot be confirmed, the resume is refused, records nothing, and says how to remove it.
     */
    @Test
    void resume_isRefused_whenTheLeftoverContainersCannotBeConfirmedGone() {
        String id = parkedContainerRun();
        int before = fx.events(id).size();
        fx.containers.leftoversConfirmed(false);

        assertThatThrownBy(() -> fx.services().resume(resumeOf(id), null, null))
                .isInstanceOf(RunRefusedException.class)
                .message().contains("Run " + id + " cannot resume", "label traceworks.run=" + id,
                        "fake rm -f $(fake ps -aq --filter label=traceworks.run=" + id + ")",
                        "fake network rm $(fake network ls -q --filter label=traceworks.run=" + id
                                + ")");
        assertThat(fx.events(id)).hasSize(before);
    }

    @Test
    void cancelOfAParkedRun_isRefused_whenTheLeftoverContainersCannotBeConfirmedGone() {
        String id = parkedContainerRun();
        int before = fx.events(id).size();
        fx.containers.leftoversConfirmed(false);

        assertThatThrownBy(() -> fx.services().cancel(id))
                .isInstanceOf(RunRefusedException.class)
                .message().contains("Run " + id + " cannot be cancelled",
                        "fake rm -f $(fake ps -aq --filter label=traceworks.run=" + id + ")");
        assertThat(fx.events(id)).hasSize(before);
        assertThat(fx.run(id).readManifest()).isEmpty();
        assertThat(fx.services().view(id).status()).isEqualTo(RunStatus.PARKED);
    }

    /**
     * Review C, L4: a run that ran with a container runtime may have left a container; without a runtime that answers
     * now, that cannot be confirmed gone, so the cancel is refused and records nothing, as docs/cli.md says. A run
     * that never had a runtime (a host run without image steps) is cancelled as before.
     */
    @Test
    void cancelOfAContainerRun_isRefused_whenNoRuntimeAnswers() {
        String id = parkedContainerRun();
        int before = fx.events(id).size();
        fx.runtime = null;   // the daemon restarts, or this shell cannot reach its socket

        assertThatThrownBy(() -> fx.services().cancel(id))
                .isInstanceOf(RunRefusedException.class)
                .message().contains("Run " + id + " cannot be cancelled", "no container runtime",
                        "label traceworks.run=" + id);
        assertThat(fx.events(id)).hasSize(before);
        assertThat(fx.run(id).readManifest()).isEmpty();
    }

    /**
     * Ctrl-C cannot refuse: what it could not confirm gone counts as a container that may still run, so the listing
     * of the step it ends is skipped and recorded instead of racing that container.
     */
    @Test
    void cancelActiveRuns_thatCannotConfirmTheLeftovers_skipsTheScratchListing() throws Exception {
        fx.withRuntime().containers.leftoversConfirmed(false);
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        fx.containers.on(SHELL_ID, run -> {
            started.countDown();
            long deadline = System.nanoTime() + AppFixture.WAIT.toNanos();
            while (!fx.containers.leftoversRemoved().contains(run.runId()) && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            return new CommandResult(130, "", "cancelled", 1, false);
        });
        RunServices services = fx.services();
        Background<RunResult> run = new Background<>(() -> services.start(request(BUILD), null, null));
        background.add(run);
        assertThat(started.await(AppFixture.WAIT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        services.cancelActiveRuns();

        String id = run.join().runId();
        List<RecordedEvent> files = fx.ofType(id, "step.files");
        assertThat(files).isNotEmpty();
        assertThat(files.getFirst().fields().path("unreadable").get(0).path("reason").asText())
                .isEqualTo("not listed: a container of the run may still be running");
    }

    /** A refusal after the run was created leaves no run behind. */
    @Test
    void start_refusedAfterItsRunWasCreated_leavesNoRun() {
        fx.withRuntime();
        fx.store.failScratchPreparation(true);

        assertThatThrownBy(() -> fx.services().start(request(BUILD), null, null))
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("scratch");
        assertThat(fx.store.runIds()).isEmpty();
        assertThat(fx.containers.runs()).isEmpty();
    }

    /** A run folder that cannot be discarded then does not hide why the run was refused. */
    @Test
    void start_refusedAfterItsRunWasCreated_keepsTheRefusal_whenTheDiscardFails() {
        fx.withRuntime();
        fx.store.failScratchPreparation(true);
        fx.store.failDiscard(true);

        assertThatThrownBy(() -> fx.services().start(request(BUILD), null, null))
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("scratch")
                .satisfies(e -> assertThat(e.getSuppressed()).singleElement()
                        .satisfies(s -> assertThat(s).hasMessageContaining("Cannot remove the folder of run")));
        assertThat(fx.containers.runs()).isEmpty();
    }

    @Test
    void resume_ofAContainerRun_needsNoConsent_butARuntime() {
        String id = parkedContainerRun();
        fx.runtime = null;

        assertThatThrownBy(() -> fx.services().resume(resumeOf(id), null, null))
                .isInstanceOf(RunRefusedException.class)
                .message().contains("Run " + id + " cannot resume", "podman");
        assertThat(fx.ofType(id, "run.resumed")).isEmpty();
    }

    /** A host run started where no runtime worked stays a host run where one works now, with the consent again. */
    @Test
    void resume_ofAHostRun_keepsItOnTheHost_andNeedsTheConsent() {
        String id = fx.services().start(new StartRequest(DEPLOY, Map.of("version", "1"), WORKSPACE, true, false),
                null, null).runId();
        fx.services().answer(id, new AnswerRequest("approval", 1, "Yes", null, null));
        fx.withRuntime();

        assertThatThrownBy(() -> fx.services().resume(resumeOf(id), null, null))
                .isInstanceOf(RunRefusedException.class)
                .message().contains("Host execution is not allowed", "host run", "--allow-host-execution")
                .doesNotContain("until containers arrive");
        RunResult resumed = fx.services().resume(new ResumeRequest(id, true, false), null, null);

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fx.commands.commands()).contains("./done");
        assertThat(fx.containers.runs()).isEmpty();
    }

    /**
     * A host run whose consent came from the flag needs the flag again to resume, so the hints name it; with the
     * consent in the operator config, and for a container run, they do not.
     */
    @Test
    void resumeHints_ofAHostRunWithoutConsentInTheConfig_carryTheFlag() {
        String host = fx.services().start(new StartRequest(DEPLOY, Map.of("version", "1"), WORKSPACE, true, false),
                null, null).runId();
        String container = parkedContainerRun();

        AnswerResult answered = fx.services().answer(host, new AnswerRequest("approval", 1, "Yes", null, null));
        AnswerResult again = fx.services().answer(host, new AnswerRequest("approval", 1, "Yes", null, null));

        assertThat(answered.message()).endsWith("continue the run with traceworks resume " + host
                + " --allow-host-execution");
        assertThat(again.message()).endsWith("resume the run with traceworks resume " + host
                + " --allow-host-execution");
        assertThat(fx.services().resumeNeedsHostFlag(fx.services().view(host))).isTrue();
        assertThat(fx.services().resumeNeedsHostFlag(fx.services().view(container))).isFalse();
        fx.settings = new OperatorSettings(true, null, List.of());
        assertThat(fx.services().resumeNeedsHostFlag(fx.services().view(host))).isFalse();
    }

    @Test
    void resume_isRefusedWhenTheWorkspaceCanNoLongerBeMounted() {
        String id = parkedContainerRun();
        fx.refusedDirectories.put(WORKSPACE, "it does not exist");

        assertThatThrownBy(() -> fx.services().resume(resumeOf(id), null, null))
                .isInstanceOf(RunRefusedException.class).message().contains("the workspace", "it does not exist");
        assertThat(fx.ofType(id, "run.resumed")).isEmpty();
    }

    @Test
    void cancelOfAnInterruptedRun_removesLeftoverContainers() {
        fx.withRuntime();
        AtomicReference<String> running = new AtomicReference<>();
        fx.containers.on(SHELL_ID, run -> {
            if (running.compareAndSet(null, run.runId())) {
                InMemoryEventLog log = fx.run(run.runId()).log();
                log.failAfter(log.readAll().size());   // the process can record nothing more and goes down
            }
            return FakeCommandRunner.ok("built");
        });
        RunServices services = fx.services();
        assertThatThrownBy(() -> services.start(request(BUILD), null, null)).isInstanceOf(EventLogException.class);
        String id = running.get();
        fx.run(id).log().failAfter(-1);
        assertThat(services.view(id).status()).isEqualTo(RunStatus.INTERRUPTED);

        RunResult cancelled = services.cancel(id);

        assertThat(cancelled.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(fx.containers.leftoversRemoved()).containsExactly(id);
    }

    @Test
    void cancelOfAParkedRun_removesLeftoverContainers() {
        String id = parkedContainerRun();

        fx.services().cancel(id);

        assertThat(fx.containers.leftoversRemoved()).containsExactly(id);
    }

    @Test
    void cancelActiveRuns_requestsTheCancel_thenRemovesTheLeftovers() throws Exception {
        fx.withRuntime();
        RunServices services = fx.services();
        Events events = new Events();
        Background<RunResult> run = new Background<>(() -> services.start(request(PARENT), events, null));
        background.add(run);
        String id = events.await("approval.requested").parent();

        services.cancelActiveRuns();

        assertThat(run.join().status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(fx.containers.leftoversRemoved()).containsExactly(id);
        assertThat(fx.ofType(id, "run.cancel_requested")).hasSize(1);
    }

    // ---- the scratch directory ----

    /**
     * The scratch directory is listed only when no container of the run can still write into it.
     * While one may (its removal was not confirmed), the listing is skipped, and the record says so.
     */
    @Test
    void theScratch_isNotListed_whileAContainerOfTheRunMayStillRun() throws Exception {
        fx.withRuntime();
        fx.containers.on(SHELL_ID, run -> {
            fx.run(run.runId()).scratch().put("out.txt", "half".getBytes(StandardCharsets.UTF_8));
            fx.containers.stillRunning(run.runId(), run.args().contains("./build"));
            return FakeCommandRunner.ok("");
        });

        RunResult result = fx.services().start(request(BUILD), null, null);

        List<RecordedEvent> files = fx.ofType(result.runId(), "step.files");
        assertThat(files).hasSize(2);
        JsonNode skipped = files.get(0).fields();
        assertThat(skipped.path("files")).isEmpty();
        assertThat(skipped.path("unreadable")).hasSize(1);
        assertThat(skipped.path("unreadable").get(0).path("path").asText()).isEmpty();
        assertThat(skipped.path("unreadable").get(0).path("reason").asText())
                .isEqualTo("not listed: a container of the run may still be running");
        assertThat(fx.run(result.runId()).scratch().reads()).as("nothing was read while it ran").isEqualTo(1);
        assertThat(files.get(1).fields().path("files").get(0).path("path").asText()).isEqualTo("out.txt");
    }
}
