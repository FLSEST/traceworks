package traceworks.adapters.container;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * The top-level {@code .git} of a workspace that a step may write ({@code workspace_writable: true}). What a step
 * leaves in a repository's hooks or in its config, the operator's next git command in that directory runs on the
 * host, outside every container: a hook, or a config value that names a command ({@code core.fsmonitor}, an alias,
 * a pager, a filter).
 *
 * <p>The name is {@code .git} as the file system of the workspace reads it, which is how git on the host finds it:
 * on a file system that ignores case, another spelling names the same entry here as there.
 */
final class WorkspaceGit {

    static final String NAME = ".git";

    private WorkspaceGit() {
    }

    /**
     * Whether {@code real} is the {@code .git} of {@code workspace}, lies in it or holds it (the workspace itself
     * and every folder above it): a writable mount of such a path would show the hooks and the config writable at
     * another place of the container. A {@code .git} of any kind counts: a directory, a file (a linked worktree),
     * a link. {@code false} for a workspace without one.
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
