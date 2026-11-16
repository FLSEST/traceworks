package traceworks.core.container;

import traceworks.core.port.CommandRunner;
import traceworks.core.port.CommandRunner.CommandFacts;
import traceworks.core.port.CommandRunner.CommandPlan;
import traceworks.core.port.CommandRunner.CommandRequest;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.CommandRunner.Mount;
import traceworks.core.port.ContainerLimits;
import traceworks.core.port.Egress;
import traceworks.core.port.ContainerRuntime.ContainerRun;
import traceworks.core.port.ContainerRuntime.RunDirectories;
import traceworks.core.testing.FakeCommandRunner;
import traceworks.core.testing.FakeContainerRuntime;
import traceworks.core.util.ProcessTracking;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The container policy: a command request becomes a fully specified container run, and the facts the record keeps
 * are resolved before anything starts. A container run sees the workspace, the package and the scratch directory at
 * fixed paths; a host run runs image-less commands on the host and mounts each directory of a container step at its
 * own host path.
 */
class ContainerCommandRunnerTest {

    private static final String ALPINE = "alpine@sha256:" + "a".repeat(64);
    private static final String ALPINE_ID = "sha256:" + "1".repeat(64);
    private static final String SHELL_TAG = "traceworks/shell:0123456789ab";
    private static final String SHELL_ID = "sha256:" + "2".repeat(64);
    private static final String TOOLS_TAG = "traceworks-pkg/pkg-1/tools:abcdef012345";
    private static final RunPaths CONTAINER_PATHS = new RunPaths("/w", "/p", "/s", true);
    private static final RunPaths HOST_PATHS = new RunPaths("/w", "/p", "/s", false);
    private static final ImageCatalog IMAGES = new ImageCatalog(Map.of("shell", SHELL_TAG),
            Map.of("tools", TOOLS_TAG));
    private static final List<Mount> FIXED = List.of(new Mount("/w", "/workspace", true),
            new Mount("/p", "/package", true), new Mount("/s", "/scratch", false));

    private final FakeContainerRuntime runtime = new FakeContainerRuntime()
            .present(ALPINE, ALPINE_ID).present(SHELL_TAG, SHELL_ID);
    private final FakeCommandRunner host = new FakeCommandRunner().otherwise(FakeCommandRunner.ok("on the host"));

    private ContainerCommandRunner containerRun() {
        return new ContainerCommandRunner(runtime, null, CONTAINER_PATHS, IMAGES, ContainerLimits.DEFAULTS, "run-1");
    }

    private ContainerCommandRunner hostRun() {
        return new ContainerCommandRunner(runtime, host, HOST_PATHS, IMAGES, ContainerLimits.DEFAULTS, "run-1");
    }

    /** A shell command as the shell block sends it: from the workspace's host path. */
    private static CommandRequest shell(String command, String image, List<Mount> mounts) {
        return new CommandRequest(command, "/w", 60, Map.of("B", "2", "A", "1"), image, mounts, false);
    }

    /** What the tool cache is named after: the reference the runner runs, built for the package. */
    @Test
    void imageReference_isWhatTheCatalogResolves() {
        assertThat(containerRun().imageReference("./images/tools")).isEqualTo(TOOLS_TAG);
        assertThat(containerRun().imageReference("./images/unbuilt")).isNull();
        assertThat(containerRun().imageReference(ALPINE)).isEqualTo(ALPINE);
        assertThat(hostRun().imageReference("./images/tools")).isEqualTo(TOOLS_TAG);
    }

    @Test
    void plan_resolvesTheImageId_andAddsTheStandardMounts() {
        Mount data = new Mount("/data", "/data", true);

        CommandPlan plan = containerRun().plan(shell("make", ALPINE, List.of(data)));

        assertThat(plan.facts()).isEqualTo(new CommandFacts("/workspace", ALPINE, ALPINE_ID,
                List.of(FIXED.get(0), FIXED.get(1), FIXED.get(2), data), false, List.of("A", "B"), false));
        assertThat(runtime.runs()).as("planning starts nothing").isEmpty();
    }

    /**
     * In a container run, a command starts in the workspace as the container sees it: a working directory below one
     * of the run's directories is mapped, any other host path is replaced by {@code /workspace} and never leaks.
     */
    @Test
    void containerRun_startsInTheWorkspace_andNeverInAHostPathOutsideTheRunsDirectories() {
        ContainerCommandRunner runner = containerRun();

        assertThat(runner.plan(new CommandRequest("x", "/elsewhere/dir", 60, null, null, List.of(), false))
                .facts().workingDirectory()).isEqualTo("/workspace");
        assertThat(runner.plan(new CommandRequest("x", null, 60, null, null, List.of(), false))
                .facts().workingDirectory()).isEqualTo("/workspace");
        assertThat(runner.plan(new CommandRequest("x", "/w/sub", 60, null, null, List.of(), false))
                .facts().workingDirectory()).isEqualTo("/workspace/sub");
        assertThat(runner.plan(new CommandRequest("x", "/s/out", 60, null, null, List.of(), false))
                .facts().workingDirectory()).isEqualTo("/scratch/out");
        assertThat(hostRun().plan(new CommandRequest("x", "/elsewhere/dir", 60, null, ALPINE, List.of(), false))
                .facts().workingDirectory()).as("a host run mounts at host paths").isEqualTo("/elsewhere/dir");
    }

    @Test
    void execution_startsTheResolvedImageId_withThePlannedFacts_namedPerRun() {
        runtime.on(ALPINE, new CommandResult(0, "made", "", 7, false));
        ContainerCommandRunner runner = containerRun();
        CommandRequest request = new CommandRequest("make", "/w", 60, Map.of("A", "1"), ALPINE, List.of(), true,
                null, "input", false);

        CommandResult result = runner.run(request);
        runner.plan(request).execution().get();

        assertThat(result).isEqualTo(new CommandResult(0, "made", "", 7, false));
        assertThat(runtime.runs()).hasSize(2);
        assertThat(runtime.runs().getFirst()).isEqualTo(new ContainerRun("traceworks-run-1-1", "run-1", ALPINE_ID,
                "/bin/bash", List.of("-c", "make"), "/workspace", FIXED, new RunDirectories("/w", "/p", "/s"), true,
                Map.of("A", "1"), "input", 60, ContainerLimits.DEFAULTS));
        assertThat(runtime.runs().get(1).name()).as("the containers of a run are counted from 1")
                .isEqualTo("traceworks-run-1-2");
    }

    private static final String EGRESS_TAG = "traceworks/egress:ba9876543210";
    private static final String EGRESS_ID = "sha256:" + "3".repeat(64);
    private static final Egress PROVIDER = Egress.hosts(List.of("api.example:443"));

    private ContainerCommandRunner withEgressImage() {
        return new ContainerCommandRunner(runtime, null, CONTAINER_PATHS, new ImageCatalog(
                Map.of("shell", SHELL_TAG, "egress", EGRESS_TAG), Map.of()), ContainerLimits.DEFAULTS, "run-1");
    }

    /** Listed hosts: the container gets them and the image ID of the proxy, resolved when the command is planned. */
    @Test
    void listedHosts_reachTheRuntime_withTheImageIdOfTheEgressProxy() {
        runtime.present(EGRESS_TAG, EGRESS_ID);
        CommandRequest request = shell("call", ALPINE, List.of()).withNetwork(PROVIDER);

        CommandPlan plan = withEgressImage().plan(request);
        plan.execution().get();

        assertThat(plan.facts().network()).isEqualTo(PROVIDER);
        assertThat(runtime.runs()).hasSize(1);
        assertThat(runtime.runs().getFirst().network()).isEqualTo(PROVIDER);
        assertThat(runtime.runs().getFirst().egressImageId()).isEqualTo(EGRESS_ID);
    }

    @Test
    void openAndNoNetwork_needNoEgressImage() {
        withEgressImage().run(shell("a", ALPINE, List.of()));
        withEgressImage().run(shell("b", ALPINE, List.of()).withNetwork(Egress.OPEN));

        assertThat(runtime.runs()).extracting(ContainerRun::network).containsExactly(Egress.NONE, Egress.OPEN);
        assertThat(runtime.runs()).extracting(ContainerRun::egressImageId).containsOnlyNulls();
    }

    /** Without the proxy's image the command would have open network or none: it is refused, and nothing starts. */
    @Test
    void listedHosts_withoutTheEgressImage_areRefused() {
        CommandRequest request = shell("call", ALPINE, List.of()).withNetwork(PROVIDER);

        CommandResult notBuilt = withEgressImage().run(request);
        CommandResult notInThisDistribution = containerRun().run(request);

        assertThat(notBuilt).isEqualTo(CommandResult.notRun(125, "image " + EGRESS_TAG + " is not present; run"
                + " traceworks images build (it is the egress proxy, the only door of a container that connects to"
                + " listed hosts)", CommandRunner.OUTPUT_LIMIT_BYTES));
        assertThat(notInThisDistribution.exitCode()).isEqualTo(125);
        assertThat(notInThisDistribution.notRunReason()).contains("egress").contains("traceworks images build");
        assertThat(runtime.runs()).isEmpty();
    }

    /** The lookup of the egress image that failed is the reason, as it is for the step's own image. */
    @Test
    void listedHosts_whoseEgressLookupFails_areRefused_withTheFailure() {
        runtime.lookupFails(EGRESS_TAG, "the lookup of image " + EGRESS_TAG + " failed: it timed out after 60 seconds");

        CommandResult refused = withEgressImage().run(shell("call", ALPINE, List.of()).withNetwork(PROVIDER));

        assertThat(refused.exitCode()).isEqualTo(125);
        assertThat(refused.notRunReason()).isEqualTo("the lookup of image " + EGRESS_TAG
                + " failed: it timed out after 60 seconds");
        assertThat(runtime.runs()).isEmpty();
    }

    @Test
    void toolMode_runsTheImagesOwnEntrypoint_withItsArguments() {
        containerRun().run(CommandRequest.entrypoint(ALPINE, List.of("fs", "/workspace"), "/w", 30, null,
                List.of(), false));

        ContainerRun run = runtime.runs().getFirst();
        assertThat(run.entrypoint()).as("the image's own entrypoint").isNull();
        assertThat(run.args()).containsExactly("fs", "/workspace");
        assertThat(run.timeoutSeconds()).isEqualTo(30);
    }

    @Test
    void writableWorkspace_mountsTheWorkspaceReadWrite() {
        CommandPlan plan = containerRun().plan(shell("make", ALPINE, List.of()).withWritableWorkspace(true));

        assertThat(plan.facts().mounts()).containsExactly(new Mount("/w", "/workspace", false),
                new Mount("/p", "/package", true), new Mount("/s", "/scratch", false));
    }

    /**
     * The package is always read-only: a writable workspace that lies inside it would make that part writable, in a
     * container run and in a host run, so such a request is refused and nothing starts.
     */
    @Test
    void writableWorkspaceInsideThePackage_isRefused() {
        for (boolean fixedPaths : new boolean[] {true, false}) {
            ContainerCommandRunner runner = new ContainerCommandRunner(runtime, host,
                    new RunPaths("/p/sub", "/p", "/s", fixedPaths), IMAGES, ContainerLimits.DEFAULTS, "run-1");

            CommandResult refused = runner.plan(new CommandRequest("make", "/p/sub", 60, Map.of(), ALPINE, List.of(),
                    false).withWritableWorkspace(true)).execution().get();

            assertThat(refused.exitCode()).isEqualTo(125);
            assertThat(refused.stderr()).contains("/p/sub", "inside the package /p", "read-only");
            CommandResult readOnly = runner.plan(new CommandRequest("make", "/p/sub", 60, Map.of(), ALPINE, List.of(),
                    false)).execution().get();
            assertThat(readOnly.exitCode()).as("a read-only workspace there is fine").isZero();
        }
        assertThat(runtime.runs()).as("only the read-only requests started").hasSize(2);
    }

    @Test
    void workingDirectoryBelowTheWorkspace_isMappedIntoTheContainer() {
        CommandRequest request = new CommandRequest("make", "/w/sub", 60, null, ALPINE, null, false);

        assertThat(containerRun().plan(request).facts().workingDirectory()).isEqualTo("/workspace/sub");
        assertThat(hostRun().plan(request).facts().workingDirectory()).isEqualTo("/w/sub");
    }

    /**
     * A request without an image (a shell step without {@code container_image}, {@code run_if}, a shell condition or
     * {@code items_command}) runs in the {@code shell} image in a container run, and on the host in a host run.
     */
    @Test
    void requestWithoutImage_runsInTheShellImage_inAContainerRun_andOnTheHost_inAHostRun() {
        CommandRequest test = new CommandRequest("test -f pom.xml", "/w", 60, null, null, null, false);

        CommandPlan inContainer = containerRun().plan(test);

        assertThat(inContainer.facts()).isEqualTo(new CommandFacts("/workspace", "shell", SHELL_ID, FIXED, false,
                List.of(), false));
        inContainer.execution().get();
        assertThat(runtime.runs()).singleElement().extracting(ContainerRun::imageId).isEqualTo(SHELL_ID);

        CommandPlan onHost = hostRun().plan(test);

        assertThat(onHost.facts()).as("the host runner's facts")
                .isEqualTo(new CommandFacts("/w", null, null, List.of(), false, List.of(), true));
        assertThat(onHost.execution().get().stdout()).isEqualTo("on the host");
        assertThat(host.requests()).containsExactly(test);
        assertThat(runtime.runs()).as("nothing more ran in a container").hasSize(1);
    }

    @Test
    void hostRun_mountsEachDirectoryAtItsHostPath() {
        CommandPlan plan = hostRun().plan(shell("make", ALPINE, List.of()));

        assertThat(plan.facts()).isEqualTo(new CommandFacts("/w", ALPINE, ALPINE_ID, List.of(
                new Mount("/w", "/w", true), new Mount("/p", "/p", true), new Mount("/s", "/s", false)), false,
                List.of("A", "B"), false));
        plan.execution().get();
        assertThat(runtime.runs()).singleElement().extracting(ContainerRun::workingDirectory).isEqualTo("/w");
        assertThat(host.requests()).as("a step with an image never runs on the host").isEmpty();
    }

    /**
     * The runtime checks the step's own mounts against the forbidden host paths, not the run's directories: it learns
     * which is which, in a container run and in a host run alike.
     */
    @Test
    void execution_tellsTheRuntimeTheRunsDirectories_andWhichMountsTheStepDeclared() {
        Mount data = new Mount("/data", "/data", true);

        containerRun().run(shell("make", ALPINE, List.of(data)));
        hostRun().run(shell("make", ALPINE, List.of(data)));
        new ContainerCommandRunner(runtime, null, new RunPaths(null, null, "/s", true), IMAGES,
                ContainerLimits.DEFAULTS, "run-1").run(shell("make", ALPINE, List.of(data)));

        assertThat(runtime.runs()).extracting(ContainerRun::directories).containsExactly(
                new RunDirectories("/w", "/p", "/s"), new RunDirectories("/w", "/p", "/s"),
                new RunDirectories(null, null, "/s"));
        assertThat(runtime.runs()).extracting(ContainerRun::declaredMounts).containsOnly(List.of(data));
    }

    @Test
    void runWithoutScratchDirectory_mountsOnlyWhatItHas() {
        ContainerCommandRunner runner = new ContainerCommandRunner(runtime, null, new RunPaths("/w", "/p", null,
                true), IMAGES, ContainerLimits.DEFAULTS, "run-1");

        assertThat(runner.plan(shell("make", ALPINE, List.of())).facts().mounts())
                .containsExactly(new Mount("/w", "/workspace", true), new Mount("/p", "/package", true));
    }

    @Test
    void missingImage_plansARefusal() {
        FakeContainerRuntime empty = new FakeContainerRuntime();
        ContainerCommandRunner runner = new ContainerCommandRunner(empty, null, CONTAINER_PATHS, IMAGES,
                ContainerLimits.DEFAULTS, "run-1");

        CommandPlan pkg = runner.plan(shell("x", "./images/tools", List.of()));

        assertThat(pkg.facts()).isEqualTo(new CommandFacts("/workspace", "./images/tools", null, FIXED, false,
                List.of("A", "B"), false));
        String missing = "image " + TOOLS_TAG + " is not present; run traceworks images build <package>";
        assertThat(pkg.execution().get()).as("a refusal is marked: the command did not run")
                .isEqualTo(new CommandResult(125, "", missing, 0, false, List.of(),
                        CommandRunner.OUTPUT_LIMIT_BYTES, missing));
        assertThat(runner.plan(shell("x", null, List.of())).execution().get().stderr())
                .isEqualTo("image " + SHELL_TAG + " is not present; run traceworks images build");
        assertThat(runner.plan(shell("x", "./images/unknown", List.of())).execution().get().stderr())
                .isEqualTo("image ./images/unknown is not present; run traceworks images build <package>");
        CommandResult registry = runner.plan(shell("x", "alpine:3", List.of())).execution().get();
        assertThat(registry.exitCode()).isEqualTo(125);
        assertThat(registry.stderr())
                .isEqualTo("image alpine:3 is not present; pull it or run traceworks images build <package>");
        assertThat(empty.runs()).as("the runtime never starts a container for a missing image").isEmpty();
    }

    /** A lookup that could not tell whether the image is present is no missing image: the refusal says what happened. */
    @Test
    void aFailedImageLookup_plansARefusalThatSaysSo_notAMissingImage() {
        runtime.lookupFails(ALPINE, "the lookup of image " + ALPINE + " failed: it timed out after 60 seconds");

        CommandResult refused = containerRun().plan(shell("x", ALPINE, List.of())).execution().get();

        assertThat(refused.exitCode()).isEqualTo(125);
        assertThat(refused.stderr()).isEqualTo("the lookup of image " + ALPINE + " failed: it timed out after 60"
                + " seconds").doesNotContain("is not present");
        assertThat(refused.didNotRun()).isTrue();
        assertThat(runtime.runs()).isEmpty();
    }

    /**
     * A request planned while the step's run is cancelled starts nothing and looks no image up, whose lookup the
     * cancel would end and leave as "not present": it says that the run was cancelled. So does an image-less request
     * of a host run.
     */
    @Test
    void aRequestPlannedAfterTheCancel_startsNothing_andSaysTheRunWasCancelled() {
        ProcessTracking.Tracker cancelled = new ProcessTracking.Tracker() {
            @Override public void started(ProcessTracking.StopHandle stop) { stop.stop(); }
            @Override public void finished(ProcessTracking.StopHandle stop) { }
            @Override public boolean cancelled() { return true; }
        };

        CommandPlan container = ProcessTracking.bind(cancelled, () -> containerRun().plan(shell("x", ALPINE,
                List.of())));
        CommandPlan onTheHost = ProcessTracking.bind(cancelled, () -> hostRun().plan(
                new CommandRequest("ls", "/w", 10, null, null, null, false)));

        CommandResult result = container.execution().get();
        assertThat(result.exitCode()).isEqualTo(130);
        assertThat(result.notRunReason()).isEqualTo("the run was cancelled before the command started");
        assertThat(result.stderr()).doesNotContain("is not present");
        assertThat(container.facts().imageId()).isNull();
        assertThat(onTheHost.execution().get().notRunReason())
                .isEqualTo("the run was cancelled before the command started");
        assertThat(onTheHost.facts().host()).isTrue();
        assertThat(runtime.lookups()).as("no lookup under a cancel").isEmpty();
        assertThat(runtime.runs()).isEmpty();
        assertThat(host.requests()).isEmpty();
    }

    @Test
    void invalidPackageImageName_plansARefusal() {
        CommandResult refused = containerRun().plan(shell("x", "./images/Bad_Name", List.of())).execution().get();

        assertThat(refused.exitCode()).isEqualTo(125);
        assertThat(refused.stderr()).isEqualTo("image ./images/Bad_Name: \"Bad_Name\" is not a valid package image"
                + " name ([a-z0-9][a-z0-9._-]*)");
        assertThat(runtime.runs()).isEmpty();
    }

    /** An image reference must never reach the runtime's command line as an option. */
    @Test
    void imageReferenceThatIsBlankOrLooksLikeAnOption_isRefused() {
        for (String image : List.of("--privileged", "-v", " -x", "  ")) {
            CommandPlan plan = containerRun().plan(shell("x", image, List.of()));

            assertThat(plan.facts().imageId()).as(image).isNull();
            CommandResult refused = plan.execution().get();
            assertThat(refused.exitCode()).as(image).isEqualTo(125);
            assertThat(refused.notRunReason()).as(image).isEqualTo(refused.stderr());
            assertThat(refused.stderr()).as(image).isEqualTo("image \"" + image + "\" is not a valid image reference:"
                    + " it is blank or starts with \"-\"");
        }
        assertThat(runtime.runs()).isEmpty();
    }

    /** A reference with a line break could write lines of its own wherever it is shown, so it is not shown. */
    @Test
    void imageReferenceWithWhiteSpaceOrAControlCharacter_isRefused_withoutBeingShown() {
        for (String image : List.of("busybox\ntraceworks: done", "busy box", "busybox\u0007")) {
            CommandPlan plan = containerRun().plan(shell("x", image, List.of()));

            CommandResult refused = plan.execution().get();
            assertThat(refused.exitCode()).as(image).isEqualTo(125);
            assertThat(refused.stderr()).as(image).isEqualTo("the image is not a valid image reference: it holds"
                    + " white space or a control character");
        }
        assertThat(runtime.runs()).isEmpty();
    }

    /**
     * A container path is read as text in more than one place: with {@code ..} in it, {@code /workspace/../etc} is
     * {@code /etc} for the runtime and a path below {@code /workspace} for a reader of its first letters. So a
     * declared mount names its container path in its plain form.
     */
    @Test
    void declaredMountWhoseContainerPathIsNotPlain_isRefused() {
        for (String target : List.of("/workspace/../etc", "/data/./x", "/data/../x", "/data//x", "/data/..")) {
            CommandResult refused = containerRun().plan(shell("x", ALPINE, List.of(new Mount("/data", target, true))))
                    .execution().get();

            assertThat(refused.exitCode()).as(target).isEqualTo(125);
            assertThat(refused.stderr()).as(target).contains("container path").contains("'.', '..'");
        }
        assertThat(runtime.runs()).isEmpty();
        assertThat(containerRun().plan(shell("x", ALPINE, List.of(new Mount("/data", "/data/x/", true))))
                .execution().get().exitCode()).as("a slash at the end is plain").isZero();
    }

    /** A cancel that came during the lookup of the image ended the lookup: the run was cancelled, no image is missing. */
    @Test
    void aCancelDuringTheImageLookup_saysTheRunWasCancelled_notThatTheImageIsMissing() {
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        ProcessTracking.Tracker cancelledDuringTheLookup = new ProcessTracking.Tracker() {
            @Override public void started(ProcessTracking.StopHandle stop) { stop.stop(); }
            @Override public void finished(ProcessTracking.StopHandle stop) { }
            @Override public boolean cancelled() { return cancelled.get(); }
        };
        runtime.duringLookup(() -> cancelled.set(true));

        CommandPlan plan = ProcessTracking.bind(cancelledDuringTheLookup, () -> containerRun().plan(shell("x",
                "absent@sha256:" + "f".repeat(64), List.of())));

        CommandResult result = plan.execution().get();
        assertThat(result.exitCode()).isEqualTo(130);
        assertThat(result.notRunReason()).isEqualTo("the run was cancelled before the command started");
        assertThat(runtime.lookups()).hasSize(1);
    }

    /** A request refused before its image is looked up never names an image: no container could have started. */
    @Test
    void aRefusedRequest_looksUpNoImage() {
        CommandPlan plan = containerRun().plan(shell("x", ALPINE, List.of(new Mount("/data", "/workspace/x", true))));

        assertThat(plan.execution().get().exitCode()).isEqualTo(125);
        assertThat(plan.facts().imageId()).isNull();
        assertThat(runtime.lookups()).isEmpty();
    }

    /** A declared mount on a standard mount point would cover what the run mounts there. */
    @Test
    void declaredMountOnAStandardMountPoint_isRefused_inAContainerRun_andInAHostRun() {
        for (String target : List.of("/workspace", "/workspace/x", "/package/scripts", "/scratch", "//scratch/x",
                "/tmp/../workspace")) {
            CommandResult refused = containerRun().plan(shell("x", ALPINE, List.of(new Mount("/data", target, true))))
                    .execution().get();

            assertThat(refused.exitCode()).as(target).isEqualTo(125);
            assertThat(refused.stderr()).as(target).startsWith("the mount /data -> " + target + " lies on the"
                    + " run's mount at /");
        }
        CommandResult inHostRun = hostRun().plan(shell("x", ALPINE, List.of(new Mount("/data", "/w/x", false))))
                .execution().get();
        assertThat(inHostRun.exitCode()).isEqualTo(125);
        assertThat(inHostRun.didNotRun()).isTrue();
        assertThat(inHostRun.stderr()).isEqualTo("the mount /data -> /w/x lies on the run's mount at /w; mount it"
                + " at another container path");
        assertThat(runtime.runs()).isEmpty();

        CommandResult ran = containerRun().plan(shell("x", ALPINE, List.of(new Mount("/data", "/workspaces", true))))
                .execution().get();
        assertThat(ran.exitCode()).as("a whole segment only").isZero();
        assertThat(ran.didNotRun()).isFalse();
        assertThat(hostRun().plan(shell("x", ALPINE, List.of(new Mount("/data", "/workspace/x", true))))
                .execution().get().exitCode()).as("not a mount point of a host run").isZero();
    }

    /**
     * A container the runtime would refuse (a declared mount its mount check refuses) is planned as a refusal: nothing
     * starts from the image, so the record names no image ID, and the runtime starts nothing.
     */
    @Test
    void aContainerTheRuntimeRefuses_plansARefusal_withoutAnImageId() {
        Mount login = new Mount("~/.ssh", "/login", true);
        runtime.refusing(run -> run.declaredMounts().contains(login)
                ? java.util.Optional.of("the mount ~/.ssh:/login:ro is refused: it lies in ~/.ssh (credentials)")
                : java.util.Optional.empty());

        CommandPlan refused = containerRun().plan(shell("ls /login", ALPINE, List.of(login)));
        CommandPlan allowed = containerRun().plan(shell("ls /login", ALPINE, List.of()));

        assertThat(refused.facts()).isEqualTo(new CommandFacts("/workspace", ALPINE, null,
                List.of(FIXED.get(0), FIXED.get(1), FIXED.get(2), login), false, List.of("A", "B"), false));
        assertThat(refused.execution().get()).isEqualTo(CommandResult.notRun(125,
                "the mount ~/.ssh:/login:ro is refused: it lies in ~/.ssh (credentials)",
                CommandRunner.OUTPUT_LIMIT_BYTES));
        assertThat(runtime.runs()).isEmpty();
        assertThat(allowed.facts().imageId()).isEqualTo(ALPINE_ID);
        assertThat(allowed.execution().get().exitCode()).isZero();
        assertThat(hostRun().plan(shell("ls /login", ALPINE, List.of(login))).facts().imageId())
                .as("in a host run too").isNull();
    }

    // ---- the register folder of an audit run ----

    private static final RunPaths AUDIT_PATHS = new RunPaths("/w", "/p", "/s", "/r", true);
    private static final RunPaths AUDIT_HOST_PATHS = new RunPaths("/w", "/p", "/s", "/r", false);
    private static final Mount REGISTER = new Mount("/r", "/register", true);

    private ContainerCommandRunner auditRun(RunPaths paths, java.util.function.Supplier<String> problem) {
        return new ContainerCommandRunner(runtime, paths.fixedContainerPaths() ? null : host, paths, IMAGES,
                ContainerLimits.DEFAULTS, "run-1", problem);
    }

    /** The register folder of an audit run is one of the run's directories: after the scratch, and only to read. */
    @Test
    void anAuditRun_mountsItsRegisterFolder_readOnly_afterTheScratch() {
        ContainerCommandRunner runner = auditRun(AUDIT_PATHS, () -> null);
        Mount data = new Mount("/data", "/data", true);

        CommandPlan plan = runner.plan(shell("cat /register/findings.jsonl", null, List.of(data)));
        plan.execution().get();

        assertThat(plan.facts().mounts()).containsExactly(FIXED.get(0), FIXED.get(1), FIXED.get(2), REGISTER, data);
        ContainerRun run = runtime.runs().getFirst();
        assertThat(run.directories()).isEqualTo(new RunDirectories("/w", "/p", "/s", "/r"));
        assertThat(run.declaredMounts()).as("the folder is the run's own, not a mount the step declared")
                .containsExactly(data);
    }

    /** What a model reads of the register is what a step puts into its prompt: its container has no such mount. */
    @Test
    void aRequestWithoutTheRegisterFolder_anAiCall_doesNotGetIt() {
        ContainerCommandRunner runner = auditRun(AUDIT_PATHS, () -> "never asked");

        CommandPlan plan = runner.plan(CommandRequest.entrypoint(ALPINE, List.of("-p"), "/w", 60, Map.of(), List.of(),
                false).withoutRegisterFolder());
        plan.execution().get();

        assertThat(plan.facts().mounts()).containsExactlyElementsOf(FIXED);
        assertThat(runtime.runs().getFirst().directories()).isEqualTo(new RunDirectories("/w", "/p", "/s"));
    }

    @Test
    void aDeclaredMountOnTheRegisterFolder_isRefused_likeOneOnAnyOfTheRunsMounts() {
        CommandPlan plan = auditRun(AUDIT_PATHS, () -> null)
                .plan(shell("x", ALPINE, List.of(new Mount("/mine", "/register/findings.jsonl", false))));

        assertThat(plan.execution().get().stderr()).contains("lies on the run's mount at /register");
        assertThat(runtime.runs()).isEmpty();
    }

    /** In a host run a container step sees the folder at its host path, read-only, as it sees the package. */
    @Test
    void hostRun_aContainerStep_mountsTheRegisterFolderAtItsHostPath() {
        CommandPlan plan = auditRun(AUDIT_HOST_PATHS, () -> null).plan(shell("x", ALPINE, List.of()));

        assertThat(plan.facts().mounts()).containsExactly(new Mount("/w", "/w", true), new Mount("/p", "/p", true),
                new Mount("/s", "/s", false), new Mount("/r", "/r", true));
    }

    /**
     * A command on the host could write the folder, whatever its permissions say. So a host run compares the files
     * with what the run recorded before every command that gets the folder, and starts none once they differ.
     */
    @Test
    void hostRun_aRegisterFolderThatChanged_refusesEveryCommandThatWouldGetIt() {
        java.util.concurrent.atomic.AtomicReference<String> problem = new java.util.concurrent.atomic.AtomicReference<>();
        ContainerCommandRunner runner = auditRun(AUDIT_HOST_PATHS, problem::get);

        assertThat(runner.run(new CommandRequest("ls", "/w", 10, null, null, null, false)).stdout())
                .isEqualTo("on the host");
        problem.set("findings.jsonl of the run's register folder is not the file the run started with");
        CommandPlan onTheHost = runner.plan(new CommandRequest("ls", "/w", 10, null, null, null, false));
        CommandPlan inAContainer = runner.plan(shell("x", ALPINE, List.of()));

        assertThat(onTheHost.facts().host()).isTrue();
        assertThat(onTheHost.execution().get()).isEqualTo(CommandResult.notRun(125,
                "findings.jsonl of the run's register folder is not the file the run started with",
                CommandRunner.OUTPUT_LIMIT_BYTES));
        assertThat(inAContainer.execution().get().didNotRun()).isTrue();
        assertThat(host.requests()).as("only the command before the change ran").hasSize(1);
        assertThat(runtime.runs()).isEmpty();
    }

    /** A container run's steps cannot write the folder: its mount is read-only, and no host command runs. */
    @Test
    void containerRun_doesNotAskForTheFolder() {
        ContainerCommandRunner runner = auditRun(AUDIT_PATHS, () -> {
            throw new AssertionError("a container run does not compare the files");
        });

        assertThat(runner.plan(shell("x", ALPINE, List.of())).execution().get().exitCode()).isZero();
    }

    /** A host run that found no runtime runs its host commands, and refuses what needs a container. */
    @Test
    void withoutRuntime_aContainerCommandIsRefused_andAHostCommandRunsOnTheHost() {
        ContainerCommandRunner noRuntime = new ContainerCommandRunner(null, host, HOST_PATHS, IMAGES,
                ContainerLimits.DEFAULTS, "run-1");

        CommandPlan container = noRuntime.plan(shell("make", ALPINE, List.of()));

        assertThat(container.facts().imageId()).isNull();
        assertThat(container.facts().host()).isFalse();
        assertThat(container.execution().get()).isEqualTo(CommandResult.notRun(125,
                "no container runtime found (podman or docker)", CommandRunner.OUTPUT_LIMIT_BYTES));
        assertThat(noRuntime.run(new CommandRequest("ls", "/w", 10, null, null, null, false)).stdout())
                .isEqualTo("on the host");
    }
}
