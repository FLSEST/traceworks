package traceworks.core.port;

import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.CommandRunner.Mount;
import traceworks.core.util.ProcessTracking;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Port to a container runtime (the {@code docker} or {@code podman} CLI). The core decides what a container may do
 * ({@code core.container}); an adapter starts it with that and with the secure defaults no workflow or operator setting
 * removes.
 */
public interface ContainerRuntime {

    /** The label every container of a run carries, with the run id as its value. */
    String RUN_LABEL = "traceworks.run";

    /** {@code docker} or {@code podman}. */
    String name();

    /** The version the runtime reports. */
    String version();

    /** The local ID ({@code sha256:...}) of the image {@code reference}; empty when it is not present. */
    Optional<String> imageId(String reference);

    /**
     * Looks the image {@code reference} up as {@link #imageId} does, and says when the lookup could not tell whether
     * it is present, such as a lookup that timed out: that is no missing image. By default {@link #imageId}'s answer,
     * which always tells.
     */
    default ImageLookup lookup(String reference) {
        return new ImageLookup(imageId(reference).orElse(null), null);
    }

    /**
     * What a lookup of an image found.
     *
     * @param imageId the local ID; {@code null} when the image is not present or the lookup failed
     * @param failure why the lookup could not tell whether the image is present; {@code null} when it could
     */
    record ImageLookup(String imageId, String failure) {}

    /**
     * Why this runtime would refuse to start {@code run}, found without starting anything: such as a declared mount its
     * mount check refuses, or a run its client's command line cannot carry. {@link #run} checks the same once more
     * right before it starts the container. Empty when it would start it; by default nothing is refused.
     */
    default Optional<String> refusal(ContainerRun run) {
        return Optional.empty();
    }

    /**
     * Starts the container, waits for it and removes it. The client process registers a
     * {@linkplain ProcessTracking.StopHandle stop handle} with {@link ProcessTracking#current()}, so cancelling the
     * run ends it. A run it refuses ({@link #refusal}) starts nothing and ends with exit code 125.
     */
    CommandResult run(ContainerRun run);

    /** Builds the image in {@code contextDirectory} and tags it {@code tag}. */
    void build(String tag, String contextDirectory) throws IOException;

    /** Pulls the image {@code reference}. */
    void pull(String reference) throws IOException;

    /**
     * Removes what is left of the containers of run {@code runId}, found by their run label ({@value #RUN_LABEL}).
     * What it cannot confirm gone counts as a container that {@linkplain #mayStillRun may still run}.
     *
     * @return whether every container of the run is confirmed gone: the listing worked, every removal succeeded, and
     *         a listing afterwards finds none
     */
    boolean removeLeftovers(String runId);

    /**
     * The run {@code runId} has ended in this process: it finished, failed, parked or paused. A runtime removes
     * what it keeps for the whole of a run and not for one container (the networks of containers with listed hosts);
     * what it cannot remove is a leftover that {@link #removeLeftovers} finds. Does nothing by default.
     */
    default void runEnded(String runId) {
    }

    /**
     * Whether a container of run {@code runId} may still run: one this runtime started whose removal after its end
     * (a timeout, a cancel) was not confirmed within its bound, or one {@link #removeLeftovers} could not confirm
     * gone. While one may, it can still write into the run's mounts, so the run does not list its scratch directory.
     * Asking removes such a container once more.
     */
    boolean mayStillRun(String runId);

    /**
     * One container, fully specified. Mounts, arguments and environment are read-only copies.
     *
     * @param name             the container's name, {@code traceworks-<run-id>-<n>}
     * @param runId            the run the container belongs to; its label
     * @param imageId          the local image ID the container starts from, never a tag that could move
     * @param entrypoint       the entrypoint in place of the image's own; {@code null} keeps the image's
     * @param args             the arguments after the image
     * @param workingDirectory the directory inside the container the command starts in; {@code null} keeps the
     *                         image's
     * @param mounts           every bind mount: first one for each of the run's {@code directories}, in their order,
     *                         then the {@linkplain #declaredMounts() mounts the step declared}
     * @param directories      the run's own directories, mounted first; absent is {@link RunDirectories#NONE}
     * @param network          where the container can connect; absent is {@link Egress#NONE}. With listed hosts
     *                         ({@link Egress#hosts}) the runtime starts the egress proxy next to the container, as its
     *                         only door, and the result names the connections it asked for
     * @param environment      the variables the command gets; their values never go on a command line
     * @param stdin            what the command reads on its standard input; {@code null} for nothing
     * @param timeoutSeconds   after this many seconds the container is stopped and removed
     * @param limits           memory, pids and CPU limits
     * @param egressImageId    the local image ID of the egress proxy; needed with listed hosts, {@code null} otherwise
     * @throws IllegalArgumentException if {@code mounts} does not begin with the run's directories: a declared mount
     *                                  must never pass for one of them; or if the network has listed hosts and no
     *                                  image of the egress proxy
     */
    record ContainerRun(String name, String runId, String imageId, String entrypoint, List<String> args,
                        String workingDirectory, List<Mount> mounts, RunDirectories directories, Egress network,
                        Map<String, String> environment, String stdin, int timeoutSeconds,
                        ContainerLimits limits, String egressImageId) {
        public ContainerRun {
            network = network == null ? Egress.NONE : network;
            if (network.restricted() && (egressImageId == null || egressImageId.isBlank())) {
                throw new IllegalArgumentException("The container " + name + " connects to listed hosts only, which"
                        + " needs the image of the egress proxy");
            }
            args = args == null ? List.of() : List.copyOf(args);
            mounts = mounts == null ? List.of() : List.copyOf(mounts);
            directories = directories == null ? RunDirectories.NONE : directories;
            environment = environment == null ? Map.of() : Map.copyOf(environment);
            List<String> own = directories.present();
            for (int i = 0; i < own.size(); i++) {
                if (i >= mounts.size() || !own.get(i).equals(mounts.get(i).hostPath())) {
                    throw new IllegalArgumentException("The mounts of container " + name + " must begin with the run's"
                            + " directory " + own.get(i));
                }
            }
        }

        /** A container with the network on ({@link Egress#OPEN}) or off ({@link Egress#NONE}). */
        public ContainerRun(String name, String runId, String imageId, String entrypoint, List<String> args,
                            String workingDirectory, List<Mount> mounts, RunDirectories directories, boolean network,
                            Map<String, String> environment, String stdin, int timeoutSeconds,
                            ContainerLimits limits) {
            this(name, runId, imageId, entrypoint, args, workingDirectory, mounts, directories, Egress.of(network),
                    environment, stdin, timeoutSeconds, limits, null);
        }

        /**
         * The mounts the step declared: those after the run's own. The runtime checks these. The run's own are its
         * directories and, when a writable workspace holds the package, the package once more, read-only, at a path
         * inside the workspace's mount, which keeps that part of the workspace read-only.
         */
        public List<Mount> declaredMounts() {
            return mounts.subList(ownMounts(), mounts.size());
        }

        private int ownMounts() {
            int own = directories.present().size();
            String pkg = directories.packageDirectory();
            if (own == 0 || own >= mounts.size() || pkg == null || directories.workspace() == null
                    || directories.workspace().isBlank()) {
                return own;
            }
            Mount next = mounts.get(own);
            String workspaceAt = mounts.getFirst().containerPath();
            boolean insideWorkspace = workspaceAt != null && next.containerPath() != null
                    && next.containerPath().startsWith(workspaceAt.endsWith("/") ? workspaceAt : workspaceAt + "/");
            return next.readOnly() && pkg.equals(next.hostPath()) && insideWorkspace ? own + 1 : own;
        }

        /** The container with the names of its environment variables, never their values (a secret's among them). */
        @Override
        public String toString() {
            return "ContainerRun[name=" + name + ", runId=" + runId + ", imageId=" + imageId + ", entrypoint="
                    + entrypoint + ", args=" + args + ", workingDirectory=" + workingDirectory + ", mounts=" + mounts
                    + ", network=" + network + ", environment=" + new java.util.TreeSet<>(environment.keySet())
                    + ", timeoutSeconds=" + timeoutSeconds + ", limits=" + limits + ", egressImageId=" + egressImageId
                    + "]";
        }
    }

    /**
     * The host directories of a run that every one of its containers mounts: the workspace, the package and the run's
     * scratch directory, each {@code null} (or blank) when the run has none; and the register folder of an audit
     * run ({@code register}), which every container but that of an AI call mounts, read-only. The container policy decides where and how
     * they are mounted; the runtime knows them so that it can tell them from a step's own mounts, and checks those
     * against them (a writable mount inside the workspace or the package is refused).
     */
    record RunDirectories(String workspace, String packageDirectory, String scratch, String register) {

        /** A run without any of them. */
        public static final RunDirectories NONE = new RunDirectories(null, null, null);

        /** The directories of a container that does not get the register folder. */
        public RunDirectories(String workspace, String packageDirectory, String scratch) {
            this(workspace, packageDirectory, scratch, null);
        }

        /** The directories the run has, in the order they are mounted: workspace, package, scratch, register. */
        public List<String> present() {
            List<String> present = new ArrayList<>(4);
            for (String directory : new String[] {workspace, packageDirectory, scratch, register}) {
                if (directory != null && !directory.isBlank()) present.add(directory);
            }
            return List.copyOf(present);
        }
    }
}
