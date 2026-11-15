package traceworks.adapters.pkg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A directory of the Traceworks home that steps write, which git on the host is never pointed at. First of all
 * {@code <home>/workspaces}, where the runs' checkouts of their sources lie: a run's data, not a repository of the
 * operator's. A step with {@code workspace_writable} may have written a checkout's {@code .git/config} (a clean filter,
 * a pager, an fsmonitor), and git on the host reads that config: {@code git status} for a package's git state, and
 * {@code git-upload-pack} for a source, which a clone runs in the source without Traceworks' {@code -c} options. And
 * {@code <home>/runs} ({@link #ofRuns}): every step writes its run's scratch directory there, and a workflow can name
 * a folder in it as a child workflow.
 */
final class CheckoutsDirectory {

    private final Path root;
    /** What lies in the directory and why git must not read it, for a refusal. */
    private final String why;

    /** The runs' checkouts, {@code <home>/workspaces}. */
    CheckoutsDirectory(Path root) {
        this(root, "where the runs' checkouts are: a checkout is a run's data, a step may have written its"
                + " .git/config");
    }

    private CheckoutsDirectory(Path root, String why) {
        this.root = Objects.requireNonNull(root, "root");
        this.why = why;
    }

    /** The runs' folders, {@code <home>/runs}, with the scratch directories the steps write. */
    static CheckoutsDirectory ofRuns(Path runs) {
        return new CheckoutsDirectory(runs, "where the runs' folders with their scratch directories are: a step"
                + " writes its run's scratch directory and may have put a .git/config there");
    }

    /** The directories that steps write: the runs' checkouts in {@code checkouts}, and every folder of {@code runs}. */
    static List<CheckoutsDirectory> stepWritten(Path checkouts, List<Path> runs) {
        List<CheckoutsDirectory> directories = new ArrayList<>();
        directories.add(new CheckoutsDirectory(checkouts));
        runs.forEach(folder -> directories.add(ofRuns(folder)));
        return List.copyOf(directories);
    }

    /**
     * Whether {@code path}, as its real path (links and {@code ..} resolved), is this directory or lies in it. Of a
     * path that does not exist, the deepest part that does is resolved. A path that cannot be resolved for another
     * reason (a link without a target, a link in a circle, a real path too long to read) counts as inside: the kernel
     * may resolve what this check cannot, and then git would read it.
     */
    boolean holds(Path path) {
        try {
            return real(path).startsWith(real(root));
        } catch (InvalidPathException e) {
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    /** Why {@code what} at {@code path} is refused, for a message. */
    String refusal(String what, Path path) {
        String unresolved = null;
        try {
            real(path);
        } catch (InvalidPathException e) {
            // judged by holds
        } catch (IOException e) {
            unresolved = e.toString();
        }
        String risk = root + ", " + why + ", and git on the host would read it";
        return unresolved != null
                ? what + " " + path + " cannot be resolved (" + unresolved + "), so it may lie in " + risk
                : what + " " + path + " lies in " + risk + "; copy what you need out of it first";
    }

    /**
     * The real path as the kernel resolves it: a link before the {@code ..} that follows it, so {@code link/..} is
     * the parent of the link's target, not the folder of the link.
     *
     * @throws IOException if a part that exists cannot be resolved; only a part that does not exist at all, not as a
     *                     link either, is replaced by its real parent plus its name
     */
    private static Path real(Path path) throws IOException {
        Path absolute = path.toAbsolutePath();
        try {
            return absolute.toRealPath();
        } catch (NoSuchFileException e) {
            if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
                throw e;   // a link whose target does not exist: it may exist a moment later
            }
            Path parent = absolute.getParent();
            Path name = absolute.getFileName();
            if (parent == null || name == null) {
                return absolute.normalize();
            }
            Path realParent = real(parent);
            return switch (name.toString()) {
                case "." -> realParent;
                case ".." -> realParent.getParent() == null ? realParent : realParent.getParent();
                default -> realParent.resolve(name);
            };
        }
    }
}
