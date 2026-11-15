package traceworks.core.port;

import java.nio.file.Path;

/**
 * Port to the host directories a run mounts into every one of its containers: the workspace and the package, which
 * the check of a step's own mounts never sees, and the run's scratch directory. The adapter reads the
 * file system: real paths, and whether a directory may be mounted at all.
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
