package traceworks.core.port;

import java.nio.file.Path;

/**
 * Port to the host directories a run mounts into every one of its containers: the workspace and the package, which
 * the check of a step's own mounts never sees, and the run's scratch directory. The adapter reads the
 * file system: real paths, and whether a directory may be mounted at all. It also answers, before a run starts,
 * whether a writable mount a workflow writes out lies in a folder the operator listed.
 */
public interface HostDirectories {

    /**
     * The real path of {@code directory}: absolute, with {@code ..} and symbolic links resolved, as the container
     * runtime mounts it. For a directory nothing judges: the run's scratch directory, which Traceworks made. A
     * directory that is checked takes its real path from {@link #check}.
     *
     * @throws IllegalArgumentException if it cannot be resolved (it does not exist, a directory on the way cannot
     *                                  be read)
     */
    String realPath(Path directory);

    /**
     * Whether a run may mount {@code directory} into its containers as its workspace or its package, and the real
     * path that was judged. The check reads the real path: not {@code /}, not the home directory or a folder holding
     * it, not the Traceworks home or a folder holding it, and none of the paths a step's own mount may not name
     * either.
     *
     * <p>The directory is resolved once, and the path that was judged is the one handed back: a caller that resolved
     * the directory again after the check could get another path, when something replaced the directory or a folder
     * above it in between, and would hand its containers a directory nobody judged.
     */
    Checked check(Path directory);

    /**
     * As {@link #check(Path)}, for a directory whose role is known, so a refusal can say what to do instead: a
     * workspace is chosen by where the run starts or by {@code --workspace}, a package by the path the run names.
     */
    default Checked check(Path directory, Role role) {
        return check(directory);
    }

    /** What a run mounts a directory as. */
    enum Role { WORKSPACE, PACKAGE }

    /**
     * Why a mount that a workflow writes out as writable ({@code host:container:rw}), with the host side
     * {@code hostPath} as written, will be refused when its step runs for lying in no folder the operator listed
     * ({@code writable_mounts} in {@code traceworks.json}); empty when a listed folder holds its real path, and
     * for a host side that is no host path, which the step refuses for that. The start check of a run asks it of
     * every such mount before any step runs. It is the rule of the listed folders alone: every other rule of a
     * step's own mounts is asked when the step runs, where this one is asked again. By default nothing is refused.
     */
    default java.util.Optional<String> writableMountRefusal(String hostPath) {
        return java.util.Optional.empty();
    }

    /**
     * {@link #writableMountRefusal(String)} for the mount of a step that may write its workspace
     * ({@code workspace_writable: true}): empty as well when the real path of the host side is the workspace or
     * lies in it, since such a step writes that folder anyway and the mount shows it nothing more. A folder that
     * holds the workspace still needs a listed folder. By default nothing is refused.
     *
     * @param writableWorkspace the real path of the run's workspace, as the start check judged it, when the step
     *                          that declares the mount may write it; {@code null} for a step that only reads its
     *                          workspace and for a run without one, which is the question without a workspace
     */
    default java.util.Optional<String> writableMountRefusal(String hostPath, String writableWorkspace) {
        return java.util.Optional.empty();
    }

    /**
     * Whether the operator lets a step that may write its workspace ({@code workspace_writable: true}) write the
     * workspace's own {@code .git} as well ({@code workspace_git_writable} in {@code traceworks.json}). Without it
     * the container of such a step gets that {@code .git} read-only. What decides is the adapter that starts the
     * container; this is its word for a description of a workflow. {@code false} by default.
     */
    default boolean workspaceGitWritable() {
        return false;
    }

    /**
     * What {@link #check} found: a refusal, or the real path it judged.
     *
     * @param refusal  why the directory must not be mounted; {@code null} when it may
     * @param realPath the real path the check judged, which is what the run mounts; {@code null} with a refusal
     */
    record Checked(String refusal, String realPath) {

        public Checked {
            if ((refusal == null) == (realPath == null)) {
                throw new IllegalArgumentException("A checked directory has a refusal or a real path, and not both");
            }
        }

        /** A directory that must not be mounted, with the reason. */
        public static Checked refused(String reason) {
            return new Checked(reason, null);
        }

        /** A directory that may be mounted, at the real path that was judged. */
        public static Checked mountable(String realPath) {
            return new Checked(null, realPath);
        }

        public boolean isRefused() {
            return refusal != null;
        }
    }
}
