package traceworks.core.container;

import traceworks.core.port.CommandRunner;
import traceworks.core.port.ContainerLimits;
import traceworks.core.port.ContainerRuntime;
import traceworks.core.port.ContainerRuntime.ContainerRun;
import traceworks.core.port.ContainerRuntime.RunDirectories;
import traceworks.core.port.Egress;
import traceworks.core.util.ProcessTracking;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The container policy of a run: what a {@link CommandRequest} may do as a container, decided in the core so the
 * adapter only carries it out. Planning a request resolves its image to the local image ID, adds the standard mounts
 * (workspace, package, scratch, and for an audit run its register folder, from {@link RunPaths}) in front of the
 * request's own and maps the working directory
 * into the container; the execution starts exactly that ({@link ContainerRun}) through the {@link ContainerRuntime}.
 *
 * <ul>
 *   <li><b>Where:</b> a request with an image always runs in a container. A request without one (a shell step without
 *       {@code container_image}, {@code run_if}, a shell condition, {@code items_command}) runs in the
 *       {@value ImageCatalog#SHELL} image in a container run, and through the host runner in a host run.</li>
 *   <li><b>Image:</b> the container starts from the image ID resolved when the command was planned, so a tag that
 *       moves afterwards changes nothing.</li>
 *   <li><b>Refusals:</b> what cannot run as planned is planned as a refusal with exit code
 *       {@value #REFUSED_EXIT_CODE}, and nothing is started: an image reference that is blank or starts with
 *       {@code -} (it would reach the runtime's command line as an option), a {@code ./images/<name>} whose name is
 *       not valid, a declared mount on or below a standard mount point (it would cover that mount), a writable
 *       workspace that is the package or lies inside it (the package is always read-only), no container runtime (a
 *       host run that found none), a missing image, with the command that provides it, and a container the runtime
 *       would refuse ({@link ContainerRuntime#refusal}, such as a declared mount its mount check refuses), and an
 *       image lookup that could not tell whether the image is present ({@link ContainerRuntime#lookup}, such as one
 *       that timed out). Nothing starts from the image then, so the facts name no image ID.</li>
 *   <li><b>Listed hosts:</b> a request that connects to listed hosts only ({@link Egress#hosts}, an AI call) needs
 *       the built-in image {@value ImageCatalog#EGRESS}, the proxy that is the container's only door. Its image ID is
 *       resolved with the plan; without it the request is refused.</li>
 *   <li><b>Cancel:</b> a request planned while the step's run is cancelled ({@link ProcessTracking}) starts nothing
 *       and looks no image up: exit code {@value #CANCELLED_EXIT_CODE}, marked as not run. That holds for an
 *       image-less request of a host run too.</li>
 *   <li><b>Command:</b> a shell command runs as {@code /bin/bash -c <command>}, a tool-mode request with the image's
 *       own entrypoint and its arguments.</li>
 *   <li><b>Name:</b> the containers of a run are named {@code traceworks-<run-id>-<n>}, counted from 1.</li>
 * </ul>
 */
public final class ContainerCommandRunner implements CommandRunner {

    /** The exit code of a command whose container cannot start, as {@code docker run} reports it. */
    static final int REFUSED_EXIT_CODE = 125;

    /** The exit code of a command not started because the run was cancelled, as a cancelled command reports it. */
    static final int CANCELLED_EXIT_CODE = 130;

    /** The shell a shell command runs in, in place of the image's own entrypoint. */
    static final String SHELL = "/bin/bash";

    private final ContainerRuntime runtime;
    private final CommandRunner hostRunner;
    private final RunPaths paths;
    private final ImageCatalog images;
    private final ContainerLimits limits;
    private final String runId;
    private final Supplier<String> registerProblem;
    private final AtomicInteger containers = new AtomicInteger();

    /**
     * @param runtime    the container runtime; {@code null} for a host run that found none, whose commands that need a
     *                   container are refused
     * @param hostRunner runs the image-less commands of a host run; {@code null} in a container run, where they run in
     *                   the {@value ImageCatalog#SHELL} image
     * @param runId      the run whose containers these are: their name and label
     */
    public ContainerCommandRunner(ContainerRuntime runtime, CommandRunner hostRunner, RunPaths paths,
                                  ImageCatalog images, ContainerLimits limits, String runId) {
        this(runtime, hostRunner, paths, images, limits, runId, () -> null);
    }

    /**
     * @param registerProblem what is wrong with the files of the run's register folder, compared with what the run
     *                        recorded of them at its start; {@code null} when nothing is. Asked in a host run only,
     *                        before every command that gets the folder: a command on the host can write it
     */
    public ContainerCommandRunner(ContainerRuntime runtime, CommandRunner hostRunner, RunPaths paths,
                                  ImageCatalog images, ContainerLimits limits, String runId,
                                  Supplier<String> registerProblem) {
        this.registerProblem = Objects.requireNonNull(registerProblem, "registerProblem");
        this.runtime = runtime;
        this.hostRunner = hostRunner;
        this.paths = Objects.requireNonNull(paths, "paths");
        this.images = Objects.requireNonNull(images, "images");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.runId = Objects.requireNonNull(runId, "runId");
    }

    @Override
    public CommandResult run(CommandRequest request) {
        return plan(request).execution().get();
    }

    /** What the run's image catalog resolves {@code image} to, the reference this runner runs; {@code null} if unknown. */
    @Override
    public String imageReference(String image) {
        return images.reference(image);
    }

    @Override
    public CommandPlan plan(CommandRequest request) {
        boolean withRegister = paths.register() != null && !request.noRegisterFolder();
        // A host run: a command before this one may have run on the host, where no mount keeps the folder read-only
        String changed = withRegister && !paths.fixedContainerPaths() && !cancelled() ? registerProblem.get() : null;
        if (request.image() == null && hostRunner != null) {
            CommandPlan onTheHost = hostRunner.plan(request);
            return cancelled() ? cancelled(onTheHost.facts())
                    : changed != null ? refused(onTheHost.facts(), changed) : onTheHost;
        }
        List<Mount> mounts = new ArrayList<>(paths.standardMounts(request.writableWorkspace(), withRegister));
        mounts.addAll(request.mounts());
        if (cancelled()) {
            return cancelled(facts(request, null, mounts));
        }
        if (changed != null) {
            return refused(facts(request, null, mounts), changed);
        }
        String refusal = refusal(request);
        String reference = refusal == null ? images.reference(request.image()) : null;
        ContainerRuntime.ImageLookup lookup = reference == null || runtime == null
                ? new ContainerRuntime.ImageLookup(null, null) : runtime.lookup(reference);
        String imageId = lookup.imageId();
        if (cancelled()) {
            // the cancel came during the lookup, and ended it: that says nothing about the image
            return cancelled(facts(request, null, mounts));
        }
        if (refusal == null && runtime == null) {
            refusal = "no container runtime found (podman or docker)";
        }
        if (refusal == null && lookup.failure() != null) {
            refusal = lookup.failure();
        }
        if (refusal == null && imageId == null) {
            refusal = missing(request.image(), reference);
        }
        if (refusal != null) {
            return refused(facts(request, imageId, mounts), refusal);
        }
        CommandFacts facts = facts(request, imageId, mounts);
        String egressImageId = null;
        if (request.network().restricted()) {
            String egressTag = images.builtInTags().get(ImageCatalog.EGRESS);
            ContainerRuntime.ImageLookup proxy = egressTag == null ? new ContainerRuntime.ImageLookup(null, null)
                    : runtime.lookup(egressTag);
            if (cancelled()) {
                return cancelled(facts(request, null, mounts));
            }
            if (proxy.failure() != null || proxy.imageId() == null) {
                // Without its only door the container would have open network or none: nothing starts
                return refused(facts, proxy.failure() != null ? proxy.failure() : "image "
                        + (egressTag == null ? ImageCatalog.EGRESS : egressTag) + " is not present; run traceworks"
                        + " images build (it is the egress proxy, the only door of a container that connects to"
                        + " listed hosts)");
            }
            egressImageId = proxy.imageId();
        }
        ContainerRun run = containerRun(request, facts, egressImageId, withRegister);
        Optional<String> refusedByRuntime = runtime.refusal(run);
        if (refusedByRuntime.isPresent()) {
            // Nothing starts from the image, so the record names none
            return refused(facts(request, null, mounts), refusedByRuntime.get());
        }
        return new CommandPlan(facts, () -> runtime.run(run));
    }

    private CommandFacts facts(CommandRequest request, String imageId, List<Mount> mounts) {
        return new CommandFacts(workingDirectory(request),
                request.image() != null ? request.image() : ImageCatalog.SHELL, imageId, mounts, request.network(),
                List.copyOf(request.environment().keySet()), false);
    }

    /**
     * A plan whose execution starts nothing and ends with exit code {@value #REFUSED_EXIT_CODE}, saying why, marked as
     * not run ({@link CommandResult#notRunReason()}).
     */
    private static CommandPlan refused(CommandFacts facts, String why) {
        return new CommandPlan(facts, () -> CommandResult.notRun(REFUSED_EXIT_CODE, why, OUTPUT_LIMIT_BYTES));
    }

    /** Whether the run of the step that plans is stopping: a cancel of the step's tracker ({@link ProcessTracking}). */
    private static boolean cancelled() {
        return ProcessTracking.current().cancelled();
    }

    /**
     * A plan whose execution starts nothing, because the run is stopping: exit code {@value #CANCELLED_EXIT_CODE},
     * marked as not run, and no image lookup that a cancel would end and leave as "not present".
     */
    private static CommandPlan cancelled(CommandFacts facts) {
        return new CommandPlan(facts, () -> CommandResult.notRun(CANCELLED_EXIT_CODE,
                "the run was cancelled before the command started", OUTPUT_LIMIT_BYTES));
    }

    /** Why the request cannot run as it is, whatever the runtime has; {@code null} when it can. */
    private String refusal(CommandRequest request) {
        String image = request.image();
        if (image != null && (image.isBlank() || image.strip().startsWith("-"))) {
            return "image \"" + image + "\" is not a valid image reference: it is blank or starts with \"-\"";
        }
        if (image != null && image.strip().chars().anyMatch(c -> Character.isWhitespace(c)
                || Character.isISOControl(c))) {
            // Shown without the reference: a line break in it would let it write lines of its own
            return "the image is not a valid image reference: it holds white space or a control character";
        }
        if (image != null && image.startsWith(ImageCatalog.PACKAGE_IMAGE_PREFIX)
                && !ImageCatalog.validPackageImageName(image)) {
            return "image " + image + ": \"" + image.substring(ImageCatalog.PACKAGE_IMAGE_PREFIX.length())
                    + "\" is not a valid package image name (" + ImageCatalog.NAME_SYNTAX + ")";
        }
        if (request.writableWorkspace() && paths.workspaceInsidePackage()) {
            return "the workspace " + paths.workspace() + " lies inside the package " + paths.packageDirectory()
                    + ", which every step sees read-only, so it cannot be writable (workspace_writable: true); work in"
                    + " a directory outside the package";
        }
        List<String> mountPoints = paths.standardMountPoints();
        for (Mount mount : request.mounts()) {
            String target = normalized(mount.containerPath());
            for (String point : mountPoints) {
                if (RunPaths.within(target, point)) {
                    return "the mount " + mount.hostPath() + " -> " + mount.containerPath() + " lies on the run's"
                            + " mount at " + point + (point.equals(paths.registerInContainer()) ? ", where the run of"
                            + " an audit workflow sees its register folder" : "")
                            + "; mount it at another container path";
                }
            }
            if (!plain(mount.containerPath())) {
                // Read as text in more than one place: /workspace/../etc is /etc for the runtime, and a path below
                // /workspace for a reader of its first letters
                return "the mount " + mount.hostPath() + " -> " + mount.containerPath() + " names its container path"
                        + " with '.', '..' or a doubled slash; write the path it means";
            }
        }
        return null;
    }

    /**
     * Where the command starts in the container: the request's directory as the container sees it, else the
     * workspace. In a container run a directory outside the run's own directories does not exist in the container,
     * so the command starts in the workspace instead, and no host path reaches the container or the record.
     */
    private String workingDirectory(CommandRequest request) {
        String directory = request.workingDirectory();
        if (directory == null || paths.fixedContainerPaths() && !paths.holds(directory)) {
            return paths.workspaceInContainer();
        }
        return paths.containerPathOf(directory);
    }

    private ContainerRun containerRun(CommandRequest request, CommandFacts facts, String egressImageId,
                                      boolean withRegister) {
        String name = "traceworks-" + runId + "-" + containers.incrementAndGet();
        String entrypoint = request.runsEntrypoint() ? null : SHELL;
        List<String> args = request.runsEntrypoint() ? request.entrypointArgs() : List.of("-c", request.script());
        return new ContainerRun(name, runId, facts.imageId(), entrypoint, args, facts.workingDirectory(),
                facts.mounts(), new RunDirectories(paths.workspace(), paths.packageDirectory(), paths.scratch(),
                        withRegister ? paths.register() : null),
                facts.network(), request.environment(), request.stdin(), request.timeoutSeconds(), limits,
                egressImageId);
    }

    /** Whether {@code path} has no {@code .} or {@code ..} segment and no doubled slash; one slash may end it. */
    private static boolean plain(String path) {
        if (path == null) return true;
        String[] segments = path.strip().split("/", -1);
        for (int i = 1; i < segments.length; i++) {
            String segment = segments[i];
            boolean last = i == segments.length - 1;
            if (segment.equals(".") || segment.equals("..") || segment.isEmpty() && !last) {
                return false;
            }
        }
        return true;
    }

    /** The path with {@code .}, {@code ..} and doubled slashes resolved as text, as the runtime reads it. */
    private static String normalized(String path) {
        try {
            return Path.of(path).normalize().toString();
        } catch (InvalidPathException e) {
            return path;
        }
    }

    /** Why the image is missing, with the command that provides it. */
    private String missing(String containerImage, String reference) {
        String named = reference != null ? reference : containerImage != null ? containerImage : ImageCatalog.SHELL;
        String fix = images.builtIn(containerImage) ? "run traceworks images build"
                : images.packageImage(containerImage) ? "run traceworks images build <package>"
                : "pull it or run traceworks images build <package>";
        return "image " + named + " is not present; " + fix;
    }
}
