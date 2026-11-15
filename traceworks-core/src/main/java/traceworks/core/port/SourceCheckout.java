package traceworks.core.port;

import java.nio.file.Path;

/**
 * Port to the checkout a run started with {@code --source} works in, the run's isolated workspace: a clone of a git
 * repository in a directory of the run's own below the Traceworks home ({@code <home>/workspaces/<run-id>}), detached
 * at one commit, which the run mounts as its workspace. The adapter runs git, and nothing of the source runs while it
 * does: no hook, no filter driver, no submodule.
 */
public interface SourceCheckout {

    /**
     * Where the checkout of run {@code runId} lies: {@code <home>/workspaces/<run-id>}.
     *
     * @throws IllegalArgumentException if {@code runId} is no run id
     */
    Path directoryOf(String runId);

    /**
     * Clones {@code source} into {@code target} and checks {@code ref} out there, detached.
     *
     * @param source an absolute local path, or a URL: {@code https://}, {@code ssh://}, {@code file://} or the
     *               scp-like {@code user@host:path}
     * @param ref    a branch, a tag or a commit of the source; {@code null} for the source's {@code HEAD} (a URL's
     *               default branch)
     * @param target where the checkout goes; it must not exist
     * @return what the record names: the source without the credentials, query and fragment of a URL, and the full
     *         commit checked out
     * @throws IllegalArgumentException if the source or the ref is refused, or the ref names no commit of the source;
     *                                  nothing is left at {@code target}, and the message names no credential
     * @throws IllegalStateException    if git cannot clone the source or check the commit out; nothing is left at
     *                                  {@code target}, and the message names no credential
     */
    Checkout checkout(String source, String ref, Path target);

    /** Whether there is anything at {@code target}, a symbolic link included. */
    boolean exists(Path target);

    /**
     * Removes {@code target} and everything in it, without following a symbolic link: a link is removed, never what it
     * points to.
     *
     * @throws java.io.UncheckedIOException if something in it cannot be removed
     */
    void remove(Path target);

    /**
     * A checkout as the record names it.
     *
     * @param source the source without the credentials, query and fragment of a URL
     * @param sha    the full commit the checkout is detached at
     */
    record Checkout(String source, String sha) {}

    /**
     * Whether git reads {@code source} as a local path and not as a URL: it has no {@code :} before its first
     * {@code /}, as git tells a path from a URL and from the scp-like {@code host:path}.
     */
    static boolean isLocalPath(String source) {
        int colon = source.indexOf(':');
        int slash = source.indexOf('/');
        return colon < 0 || slash >= 0 && slash < colon;
    }
}
