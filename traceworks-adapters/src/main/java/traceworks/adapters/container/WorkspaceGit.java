package traceworks.adapters.container;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/**
 * The top-level {@code .git} of a workspace that a step may write ({@code workspace_writable: true}). What a step
 * leaves in a repository's hooks or in its config, the operator's next git command in that directory runs on the
 * host, outside every container: a hook, or a config value that names a command ({@code core.fsmonitor}, an alias,
 * a pager, a filter).
 *
 * <p>So the container of such a step gets more mounts inside the workspace's own ({@link #holds}): the hooks and
 * the config of a {@code .git} directory read-only, and a {@code .git} file read-only as a whole. A step can still
 * commit: everything else in {@code .git} stays writable. Nothing is written into the workspace to achieve this.
 * A mount needs something to lie on, so a repository without a hooks folder or without a config file cannot be
 * held, and a step could make what is missing: the container is refused, and the refusal names the command that
 * makes it. A symbolic link at one of the places cannot be held in place either (the runtime would follow it, and
 * a step could put another in its place), and is refused.
 *
 * <p>What is not held, because a mount cannot do it or git needs to write it (docs/security.md, "What they do not
 * protect"): a file that does not exist yet and that git reads once it does ({@code commondir}, which names another
 * directory for the config and the hooks; {@code config.worktree} when the config turns it on); the rest of
 * {@code .git}, such as {@code info/attributes}, the index and the state of a rebase; files of the workspace that
 * the config names; the git directories of submodules and of repositories deeper in the workspace; a workspace
 * without a {@code .git}, where a step can make one; and a command on the host, which no mount holds.
 *
 * <p>The name is {@code .git} as the file system of the workspace reads it, which is how git on the host finds it:
 * on a file system that ignores case, another spelling names the same entry here as there.
 */
final class WorkspaceGit {

    static final String NAME = ".git";

    private static final String HOOKS = "hooks";
    private static final String CONFIG = "config";

    private WorkspaceGit() {
    }

    /**
     * One more bind mount inside the mount of a writable workspace.
     *
     * @param source      the host path that is mounted
     * @param inWorkspace where it is mounted, relative to the workspace: the place it has there anyway
     * @param readOnly    whether the step may only read it
     */
    record Hold(Path source, String inWorkspace, boolean readOnly) {}

    /**
     * The mounts that hold the {@code .git} of a writable workspace, in the order they are mounted; empty for a
     * workspace without one.
     *
     * <p>For a {@code .git} directory: the directory itself once more at its place, writable, then its
     * {@code hooks} and its {@code config}, read-only. The first makes {@code .git} a mount point of the container,
     * which no step can rename or remove; without it a step would move the directory away, with the two mounts in
     * it, and put one of its own making at its place. For a {@code .git} file (a linked worktree, a submodule):
     * the file, read-only, so no step can point it at a git directory of its own making. The git directory such a
     * file names is not held: outside the workspace no step sees it, and inside it is a folder like any other.
     *
     * @param workspace the real path of the workspace, as it is mounted
     * @throws IllegalArgumentException for a {@code .git} that cannot be held: one that is a symbolic link or
     *                                  neither a directory nor a file, and a directory whose {@code hooks} is no
     *                                  directory or whose {@code config} is no file (missing, or a link)
     */
    static List<Hold> holds(Path workspace) {
        Path git = workspace.resolve(NAME);
        if (!Files.exists(git, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        String start = "The workspace " + workspace + " is mounted writable (workspace_writable: true), so the hooks"
                + " and the config of its " + NAME + " are mounted read-only: what a step leaves there, your next"
                + " git command runs on the host. ";
        String instead = "or run the package on a checkout of its own (run --source)";
        if (Files.isSymbolicLink(git)) {
            throw new IllegalArgumentException(start + git + " is a symbolic link, which a mount cannot hold in"
                    + " place: make it the repository's own directory, " + instead);
        }
        if (Files.isRegularFile(git, LinkOption.NOFOLLOW_LINKS)) {
            return List.of(new Hold(git, NAME, true));
        }
        if (!Files.isDirectory(git, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(start + git + " is neither a directory nor a file");
        }
        Path hooks = git.resolve(HOOKS);
        if (Files.isSymbolicLink(hooks) || Files.isSymbolicLink(git.resolve(CONFIG))) {
            Path link = Files.isSymbolicLink(hooks) ? hooks : git.resolve(CONFIG);
            throw new IllegalArgumentException(start + link + " is a symbolic link, which a mount cannot hold in"
                    + " place: put what it points at in its place, " + instead);
        }
        if (!Files.isDirectory(hooks, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(start + "It has no hooks folder, which a step could make, and"
                    + " Traceworks writes nothing into your repository: make an empty one (mkdir " + hooks + "), "
                    + instead);
        }
        Path config = git.resolve(CONFIG);
        if (!Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(start + "It has no config file, which a step could make, and"
                    + " Traceworks writes nothing into your repository: make an empty one (touch " + config + "), "
                    + instead);
        }
        return List.of(new Hold(git, NAME, false), new Hold(hooks, NAME + "/" + HOOKS, true),
                new Hold(config, NAME + "/" + CONFIG, true));
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
