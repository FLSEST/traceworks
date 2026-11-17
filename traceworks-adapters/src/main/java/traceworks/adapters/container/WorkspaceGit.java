package traceworks.adapters.container;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The top-level {@code .git} of a workspace that a step may write ({@code workspace_writable: true}). What a step
 * leaves in a repository, the operator's next git command in that directory runs on the host, outside every
 * container: a hook, a config value that names a command ({@code core.fsmonitor}, an alias, a pager, a filter), or
 * a {@code commondir} file that points git at a config and hooks somewhere else. No list of such files could be
 * complete.
 *
 * <p>So the container of such a step gets one more mount inside the workspace's own ({@link #readOnly}): the
 * {@code .git}, a directory or a file, once more at its place, read-only and as a whole. A step reads the
 * repository and writes none of it: it can run {@code git log} and {@code git diff}, and cannot commit. Nothing is
 * asked of what the {@code .git} holds and nothing is written into the workspace. The operator opens it with
 * {@code workspace_git_writable} in {@code traceworks.json}; the run's own checkout of its source is open without
 * it ({@link MountGuard.Context#workspaceGitWritable()}), and neither gets the mount.
 *
 * <p>What the mount does not hold (docs/security.md, "What they do not protect"): a workspace without a
 * {@code .git}, where a step can make one; the repositories deeper in the workspace, submodules among them; a git
 * directory in the workspace that a {@code .git} file names, which is a folder like any other; files of the
 * workspace that the config names; and a command on the host, which no mount holds.
 *
 * <p>The name is {@code .git} as the file system of the workspace reads it, which is how git on the host finds it:
 * on a file system that ignores case, another spelling names the same entry here as there.
 */
final class WorkspaceGit {

    static final String NAME = ".git";

    private WorkspaceGit() {
    }

    /**
     * The {@code .git} of a writable workspace that the container mounts read-only at its place in the workspace;
     * empty for a workspace without one. The mount makes {@code .git} a mount point of the container, which no step
     * can rename, remove or replace, and read-only is the whole of it: a directory with everything in it, or the
     * file of a linked worktree, which no step can then point at a git directory of its own making.
     *
     * @param workspace the real path of the workspace, as it is mounted
     * @throws IllegalArgumentException for a {@code .git} that a mount cannot lie on: a symbolic link (the runtime
     *                                  would mount onto what it names, and a step could put another link at its
     *                                  place), or what is neither a directory nor a file
     */
    static Optional<Path> readOnly(Path workspace) {
        Path git = workspace.resolve(NAME);
        if (!Files.exists(git, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (Files.isDirectory(git, LinkOption.NOFOLLOW_LINKS) || Files.isRegularFile(git, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.of(git);
        }
        String what = Files.isSymbolicLink(git) ? "a symbolic link" : "neither a directory nor a file";
        throw new IllegalArgumentException("The workspace " + workspace + " is mounted writable (workspace_writable:"
                + " true), so its " + NAME + " is mounted read-only: what a step leaves there, your next git command"
                + " runs on the host. " + git + " is " + what + ", which a mount cannot hold in place: make it the"
                + " repository's own directory, run the package on a checkout of its own (run --source), or, if"
                + " every package you run may write that repository, set \"workspace_git_writable\": true in"
                + " traceworks.json");
    }

    /**
     * Whether {@code real} is the {@code .git} of {@code workspace}, lies in it or holds it (the workspace itself
     * and every folder above it): a writable mount of such a path would show the repository writable at another
     * place of the container. A {@code .git} of any kind counts: a directory, a file (a linked worktree), a link.
     * {@code false} for a workspace without one.
     *
     * <p>The paths are compared as they are, and by the file each names, so another spelling of one of them on a
     * file system that ignores case, or the target of a {@code .git} that is a link, counts as well.
     *
     * @param real      the real path of the host side of a mount
     * @param workspace the real path of the workspace
     */
    static boolean isInOrHolds(Path real, Path workspace) {
        Path git = workspace.resolve(NAME);
        if (!Files.exists(git, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        return isOrLiesIn(real, git) || isOrLiesIn(git, real);
    }

    /** Whether {@code inner} is {@code outer} or lies in it, by path or by the file a folder on the way is. */
    private static boolean isOrLiesIn(Path inner, Path outer) {
        if (inner.startsWith(outer)) {
            return true;
        }
        for (Path folder = inner; folder != null; folder = folder.getParent()) {
            if (sameFile(folder, outer)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameFile(Path one, Path other) {
        try {
            return Files.isSameFile(one, other);
        } catch (IOException e) {
            // One of them does not exist or cannot be read: the comparison of the paths has spoken
            return false;
        }
    }
}
