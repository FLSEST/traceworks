package traceworks.adapters.container;

import traceworks.adapters.config.HomeLayout;
import traceworks.core.container.HostSecrets;
import traceworks.core.port.CommandRunner.Mount;
import traceworks.core.port.ContainerRuntime.ContainerRun;
import traceworks.core.port.ContainerRuntime.RunDirectories;
import traceworks.core.port.HostDirectories;
import traceworks.core.port.HostDirectories.Checked;
import traceworks.core.port.Ids;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Which host paths a step's own mounts ({@code mounts}) may hand a container. The run's own directories (workspace,
 * package, scratch) are not checked here: the run checks the workspace once at its start.
 *
 * <p>Refused, and the refusal says why:
 * <ul>
 *   <li>a host side that is not an absolute path after {@code ~} is expanded: a named volume, whose content no path
 *       check sees, or a relative path; and a container side that is not absolute;</li>
 *   <li>a {@linkplain #FORBIDDEN_PATHS forbidden path} on either side, or a host side that holds one ({@code /run}
 *       holds the Docker socket): among them the runtimes' sockets and storage roots, every user's runtime directory
 *       {@code /run/user} with the sockets of the user's session (a rootless Docker or Podman, the user's D-Bus and
 *       systemd, which start programs on the host, the GPG and SSH agents) and the X11 display sockets;</li>
 *   <li>the operator's {@linkplain Context#sessionPaths() session paths} ({@code $XDG_RUNTIME_DIR},
 *       {@code $SSH_AUTH_SOCK}, the sockets of {@code $DOCKER_HOST} and {@code $CONTAINER_HOST}), wherever they lie:
 *       a mount on one, in one or around one;</li>
 *   <li>the home directory or a folder that holds it, and a {@linkplain HostSecrets#HOME_SECRETS login below the home}
 *       ({@code ~/.ssh}, ...): a mount inside one, and a mount that holds one, also when the login is a link into the
 *       mount; and a mount that has its own {@linkplain #OWN_CREDENTIALS credential store} (a copy of a home).
 *       A login inside a mount is not covered with an empty tmpfs; Traceworks refuses the mount
 *       instead. The same holds for a rootless runtime's {@linkplain HostSecrets#HOME_STORAGE storage} below the
 *       home;</li>
 *   <li>the Traceworks home and anything in it, except the run's own scratch directory, the run's own checkout of
 *       its source ({@code <home>/workspaces/<run-id>}, its workspace, where the workspace's rules apply) and the
 *       one folder below {@code <home>/cache/tools/} that a tool step mounts for its image
 *       ({@link Mount#toolCache()}; a workflow's own {@code mounts} entry names no tool cache), and a folder that
 *       holds the Traceworks home;</li>
 *   <li>a writable mount in or around the run's package, and in or around its workspace while the workspace is
 *       mounted read-only (without {@code workspace_writable}): it would undo their read-only mounts. A read-only
 *       one shows what the step sees anyway;</li>
 *   <li>a writable mount that is, lies in or holds the {@code .git} of a workspace that is mounted writable: the
 *       run mounts its hooks and its config read-only ({@link WorkspaceGit}), and the mount would show them
 *       writable at another place;</li>
 *   <li>every other writable mount that lies in no folder the operator listed ({@code writable_mounts} in
 *       {@code traceworks.json}), wherever it lies: what a step writes on the host can be a file the host runs
 *       later. Without the setting no package mounts a host path writable. What Traceworks manages itself needs
 *       no entry: the run's scratch directory, the cache folder and the output folder of a tool step, and the
 *       run's own checkout of its source while the step may write its workspace. A listed folder lifts no other
 *       refusal of this list;</li>
 *   <li>a path that cannot be resolved, for any reason (it does not exist, a directory on the way cannot be read):
 *       the check fails closed, since a rootful runtime mounts it anyway and in the container the host's parent
 *       directories no longer guard it;</li>
 *   <li>a socket file, also through a link, and a folder that directly holds one (one level, links not followed):
 *       a line to a process on the host (tmux, screen, an agent), which read-only does not cut. A folder whose
 *       entries, or a file whose type, cannot be read is refused as well;</li>
 *   <li>{@code /dev} and everything in it (the terminals of the operator's session, shared memory, device nodes),
 *       and, by the rule of the sockets, a device node or a named pipe and a folder that directly holds one (one
 *       level, links not followed); one that lies deeper in a mounted folder is not looked for.</li>
 * </ul>
 *
 * <p>Every check reads the real path, with {@code ..} and symbolic links resolved, so {@code /tmp/../etc} or a link
 * to {@code /etc} count as what the runtime would mount, and the runtime mounts the real path it was checked as
 * ({@link #realHostPath}; for the run's own directories {@link #realRunDirectory}).
 */
public final class MountGuard {

    /**
     * Mount paths always refused. A path matches when it is one of them or lies below it ({@code /etc/passwd} matches
     * {@code /etc}); {@code /} matches only itself. A host side also matches when it holds one of them ({@code /run}
     * holds the Docker socket). Checked in this order, so a refusal names the same entry every time.
     */
    static final List<String> FORBIDDEN_PATHS = List.of(
            "/var/run/docker.sock",
            "/run/docker.sock",
            "/var/run/podman/podman.sock",
            "/run/podman/podman.sock",
            "/run/user",
            "/tmp/.X11-unix",
            "/var/lib/docker",
            "/var/lib/containers",
            "/",
            "/etc",
            "/root",
            "/proc",
            "/sys",
            "/dev");

    /**
     * The credential stores whose presence marks a folder as a copy of a home wherever it lies: a mount that has one
     * of its own is refused. Not among them are the names a repository also uses for its own settings
     * ({@code .claude}, {@code .npmrc}, {@code .docker}, ...).
     */
    static final List<String> OWN_CREDENTIALS = List.of(".ssh", ".gnupg", ".aws", ".azure", ".kube",
            ".config/gcloud", ".config/gh", ".netrc", ".git-credentials", ".password-store", ".pypirc");

    /** The variables of the operator's environment that name a {@linkplain Context#sessionPaths() session path}. */
    static final List<String> SESSION_VARIABLES = List.of("XDG_RUNTIME_DIR", "SSH_AUTH_SOCK");

    /** The variables that name the runtime's own socket as a {@code unix://} address, also a session path. */
    static final List<String> RUNTIME_SOCKET_VARIABLES = List.of("DOCKER_HOST", "CONTAINER_HOST");

    private static final String UNIX_ADDRESS = "unix://";

    private static final String ENDS_IN_WHITE_SPACE = "its path begins or ends with white space, which a mount"
            + " cannot name reliably: rename the directory";

    /** The file type bits of {@code unix:mode}, and the types of a socket, a named pipe and a device ({@code stat.h}). */
    private static final int S_IFMT = 0170000;
    private static final int S_IFSOCK = 0140000;
    private static final int S_IFIFO = 0010000;
    private static final int S_IFCHR = 0020000;
    private static final int S_IFBLK = 0060000;

    private MountGuard() {
    }

    /**
     * What a mount is checked against.
     *
     * @param home              the operator's home directory, where {@code ~} points
     * @param layout            the Traceworks home and its places ({@link HomeLayout}): the runs, the ledger and
     *                          the registers, wherever the operator put them
     * @param scratch           the host path of the run's scratch directory; {@code null} for none
     * @param workspace         the host path of the run's workspace; {@code null} for none
     * @param writableWorkspace whether the run mounts its workspace writable ({@code workspace_writable})
     * @param packageDirectory  the host path of the run's package; {@code null} for none
     * @param sessionPaths      the operator's session paths, from its environment ({@link #SESSION_VARIABLES}): the
     *                          runtime directory of its session and its SSH agent's socket
     * @param checkout          the host path of the run's own checkout of its source
     *                          ({@code <home>/workspaces/<run-id>}) when that is its workspace;
     *                          {@code null} otherwise
     */
    public record Context(Path home, HomeLayout layout, String scratch, String workspace,
                          boolean writableWorkspace, String packageDirectory, List<Path> sessionPaths,
                          String checkout) {
        public Context {
            Objects.requireNonNull(home, "home");
            Objects.requireNonNull(layout, "layout");
            sessionPaths = sessionPaths == null ? List.of() : List.copyOf(sessionPaths);
        }

        /** For a home whose places are its fixed folders, and a workspace that is no checkout of the run's own. */
        public Context(Path home, Path traceworksHome, String scratch, String workspace, boolean writableWorkspace,
                       String packageDirectory, List<Path> sessionPaths) {
            this(home, HomeLayout.of(traceworksHome), scratch, workspace, writableWorkspace, packageDirectory,
                    sessionPaths, null);
        }

        /** The Traceworks home. */
        public Path traceworksHome() {
            return layout.home();
        }

        /** {@link #of(Path, HomeLayout, ContainerRun, List)} for a home whose places are its fixed folders. */
        public static Context of(Path home, Path traceworksHome, ContainerRun run, List<Path> sessionPaths) {
            return of(home, HomeLayout.of(traceworksHome), run, sessionPaths);
        }

        /**
         * The context of {@code run}: its directories, whether its workspace mount (the first of its mounts, when
         * it has a workspace) is writable, and whether that workspace is the run's own checkout of its source.
         */
        public static Context of(Path home, HomeLayout layout, ContainerRun run, List<Path> sessionPaths) {
            RunDirectories directories = run.directories();
            boolean hasWorkspace = directories.workspace() != null && !directories.workspace().isBlank();
            boolean writable = hasWorkspace && !run.mounts().getFirst().readOnly();
            String checkout = null;
            if (hasWorkspace && Ids.isRunId(run.runId())) {
                try {
                    Path own = real(layout.workspaces().resolve(run.runId()));
                    checkout = own.equals(real(Path.of(directories.workspace()))) ? directories.workspace() : null;
                } catch (InvalidPathException e) {
                    checkout = null;
                }
            }
            return new Context(home, layout, directories.scratch(), directories.workspace(), writable,
                    directories.packageDirectory(), sessionPaths, checkout);
        }
    }

    /**
     * The session paths an environment names: the paths of {@link #SESSION_VARIABLES}, and the socket paths of the
     * {@code unix://} addresses of {@link #RUNTIME_SOCKET_VARIABLES}. A relative or invalid path, and any other
     * address ({@code tcp://}, {@code ssh://}), is left out.
     */
    public static List<Path> sessionPaths(Map<String, String> environment) {
        List<String> values = new ArrayList<>();
        SESSION_VARIABLES.forEach(variable -> values.add(environment.get(variable)));
        for (String variable : RUNTIME_SOCKET_VARIABLES) {
            String address = environment.get(variable);
            if (address != null && address.startsWith(UNIX_ADDRESS)) {
                values.add(address.substring(UNIX_ADDRESS.length()));
            }
        }
        List<Path> paths = new ArrayList<>();
        for (String value : values) {
            if (value == null || !value.startsWith("/")) continue;
            try {
                paths.add(Path.of(value));
            } catch (InvalidPathException ignored) {
                // no path the operator's session can use either
            }
        }
        return List.copyOf(paths);
    }

    /** Why the step's own {@code mount} must not be mounted; empty when it may be. */
    public static Optional<String> check(Mount mount, Context context) {
        return judge(mount, context).refusal();
    }

    /**
     * A step's own mount, judged.
     *
     * @param refusal  why it must not be mounted; empty when it may be
     * @param realPath the real path of its host side, the one that was judged: the runtime mounts this path and does
     *                 not read the given one again; {@code null} for a refused mount
     */
    record Judged(Optional<String> refusal, String realPath) {

        private static Judged refused(String why) {
            return new Judged(Optional.of(why), null);
        }
    }

    /**
     * Judges the step's own {@code mount}. Its host side is resolved by the file system once, and that one real path
     * is what every check looks at and what {@link Judged#realPath} hands on to be mounted: a path that was resolved
     * for the check and again for the mount could name another folder the second time.
     */
    static Judged judge(Mount mount, Context context) {
        String host = expandHome(mount.hostPath().strip(), context.home());
        String container = mount.containerPath().strip();
        if (!host.startsWith("/")) {
            return Judged.refused("not an absolute host path: named volumes are not allowed");
        }
        if (!container.startsWith("/")) {
            return Judged.refused("the container path is not absolute");
        }
        Path resolved = null;
        IOException unresolved = null;
        Path real;
        try {
            String forbidden = forbiddenSegment(host, container);
            if (forbidden != null) {
                return Judged.refused("forbidden path " + forbidden);
            }
            Path given = Path.of(host);
            try {
                resolved = given.toRealPath();
            } catch (IOException e) {
                unresolved = e;
            }
            // A path that does not resolve is refused below. What it would be is still judged first, so the refusal
            // names the reason that matters most
            real = resolved != null ? resolved : real(given);
            // And the path that is mounted: the kernel follows a link before it applies "..", so link/.. is the
            // parent of the link's target, where the comparison above stayed in the folder that holds the link
            forbidden = forbiddenHostSegment(real.toString());
            if (forbidden != null) {
                return Judged.refused("forbidden path " + forbidden);
            }
        } catch (InvalidPathException e) {
            return Judged.refused("not a valid path: " + e.getMessage());
        }
        Path mounted = resolved;
        IOException why = unresolved;
        Optional<String> refusal = sessionRefusal(real, context)
                .or(() -> homeRefusal(real, context.home(), true, "mount a directory below it"))
                .or(() -> traceworksHomeRefusal(real, context, mount.toolCache()))
                // After the home, as for a run's directory, so the home is named as the home; and whatever the
                // rule of the Traceworks home lets through (its tool cache, the run's scratch) is still asked here
                .or(() -> sealFilesRefusal(real, context.layout()))
                .or(() -> uncoveredGitRefusal(real, context))
                .or(() -> mount.readOnly() ? Optional.empty() : writableRefusal(mount, real, context))
                .or(() -> mounted == null ? Optional.of(unresolvedRefusal(why)) : socketRefusal(mounted));
        return new Judged(refusal, refusal.isPresent() ? null : mounted.toString());
    }

    /** Why a path that did not resolve to its real path is refused: the mount fails closed. */
    private static String unresolvedRefusal(IOException e) {
        if (e instanceof NoSuchFileException missing) {
            return "cannot be resolved: " + missing.getFile() + " does not exist";
        }
        if (e instanceof AccessDeniedException denied) {
            return "cannot be resolved: permission denied on the way to " + denied.getFile();
        }
        return "cannot be resolved: " + e;
    }

    /**
     * The {@code .git} of the package: the package digest leaves it out, so the run mounts the package with an empty
     * cover over it. A step's own mount gets no such cover, so one that is in that {@code .git}, is the package again
     * or holds it is refused; a folder of the package beside {@code .git} may be mounted.
     */
    private static Optional<String> uncoveredGitRefusal(Path real, Context context) {
        if (!present(context.packageDirectory())) {
            return Optional.empty();
        }
        Path git = real(Path.of(context.packageDirectory())).resolve(".git");
        if (!Files.isDirectory(git, LinkOption.NOFOLLOW_LINKS) || !(real.startsWith(git) || git.startsWith(real))) {
            return Optional.empty();
        }
        return Optional.of("it is, lies in or holds the package's .git, which the package digest does not cover and"
                + " the run hides from its steps; mount a folder of the package beside it");
    }

    /**
     * Why a run must not mount {@code directory} as one of its own directories, its workspace or its package, which
     * every container of the run sees and which {@link #check} never sees, or the real path it judged when it may. It
     * resolves the directory once, reads that real path, and refuses what a step's own mount may not name either: a
     * path that is not absolute, cannot be
     * resolved or is no directory; {@code /}, a {@linkplain #FORBIDDEN_PATHS forbidden path} or a folder holding one;
     * the operator's session paths; the home or a folder holding it, a login below the home and a rootless runtime's
     * storage; the Traceworks home or a folder holding it, and its {@linkplain HomeLayout#records() runs, ledger,
     * registers and caches}, wherever the operator put them, or a folder in them; a folder that directly holds a
     * socket; a path with a comma, a quote or a line break,
     * which {@code --mount} cannot carry; and a path that ends in white space, as given or as its real path.
     *
     * <p>Allowed, unlike for a step's own mount: another directory below the Traceworks home (a package on the
     * package path, a checkout of a source below {@code workspaces/}), and one with a credential store of its own
     * ({@code .aws} with a project's profiles). The run's directories are the operator's choice, not the workflow's.
     */
    public static Checked checkRunDirectory(Path directory, Path home, Path traceworksHome,
                                                            List<Path> sessionPaths) {
        return checkRunDirectory(directory, home, HomeLayout.of(traceworksHome), sessionPaths);
    }

    /** {@link #checkRunDirectory(Path, Path, Path, List)} for a home whose places are those of {@code layout}. */
    public static Checked checkRunDirectory(Path directory, Path home, HomeLayout layout, List<Path> sessionPaths) {
        return checkRunDirectory(directory, home, layout, sessionPaths, HostDirectories.Role.WORKSPACE);
    }

    /**
     * As {@link #checkRunDirectory(Path, Path, HomeLayout, List)}, with the advice of a refused home directory fitting
     * what the run mounts the directory as: a workspace is chosen by where the run starts or by {@code --workspace},
     * a package is the folder the run names.
     */
    public static Checked checkRunDirectory(Path directory, Path home, HomeLayout layout, List<Path> sessionPaths,
                                            HostDirectories.Role role) {
        Path traceworksHome = layout.home();
        if (!directory.isAbsolute()) {
            return Checked.refused("not an absolute path");
        }
        Path real;
        try {
            // A step's own mount is read with the white space at its ends cut off. A run directory is not, and a name
            // that would change by it is refused, so no reading on the way to the runtime can name another entry
            if (!directory.toString().equals(directory.toString().strip())) {
                return Checked.refused(ENDS_IN_WHITE_SPACE);
            }
            // As given and as its real path, so a link to /etc counts as /etc
            String forbidden = forbiddenRunDirectory(directory.toString());
            if (forbidden != null) {
                return Checked.refused("forbidden path " + forbidden);
            }
            real = directory.toRealPath();
            if (!real.toString().equals(real.toString().strip())) {
                return Checked.refused(ENDS_IN_WHITE_SPACE);
            }
            // And the path that is mounted: toRealPath resolves link/.. to the link target's parent, where the
            // lexical normalization above stays in the folder that holds the link
            forbidden = forbiddenRunDirectory(real.toString());
            if (forbidden != null) {
                return Checked.refused("forbidden path " + forbidden);
            }
        } catch (InvalidPathException e) {
            return Checked.refused("not a valid path: " + e.getMessage());
        } catch (NoSuchFileException e) {
            return Checked.refused("it does not exist (" + e.getFile() + ")");
        } catch (AccessDeniedException e) {
            return Checked.refused("cannot be resolved: permission denied on the way to " + e.getFile());
        } catch (IOException e) {
            return Checked.refused("cannot be resolved: " + e);
        }
        if (!Files.isDirectory(real)) {
            return Checked.refused("not a directory");
        }
        for (Path path : List.of(directory, real)) {
            String text = path.toString();
            if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0
                    || text.indexOf('\r') >= 0) {
                return Checked.refused("a path with a comma, a quote or a line break cannot be mounted");
            }
        }
        Context context = new Context(home, layout, null, null, false, null, sessionPaths, null);
        Path traceworks = real(traceworksHome);
        return sessionRefusal(real, context)
                .or(() -> homeRefusal(real, home, false, role == HostDirectories.Role.PACKAGE
                        ? "keep the package (workflow.json and its files) in a folder of its own, and name that"
                        + " folder" : "run from a project directory or pass --workspace"))
                .or(() -> traceworks.startsWith(real)
                        ? Optional.of("it is or contains the Traceworks home " + traceworksHome) : Optional.empty())
                .or(() -> recordsRefusal(real, layout))
                .or(() -> sealFilesRefusal(real, layout))
                .or(() -> socketRefusal(real))
                .map(Checked::refused)
                // The one resolution above is what every rule judged, and it is what the run mounts
                .orElseGet(() -> Checked.mountable(real.toString()));
    }

    /**
     * The places of the Traceworks home that hold what runs leave behind ({@link HomeLayout#records()}), which no
     * container may see or, with {@code workspace_writable}, change: the run records (another run's record, its
     * scratch directory), the ledger (manifests are not signed in this version), the registers and the caches. They
     * count where the operator put them and in their fixed folders of the home. A package on the package path or a
     * checkout below {@code <home>/workspaces/} lies elsewhere in the home and stays allowed.
     */
    private static Optional<String> recordsRefusal(Path real, HomeLayout layout) {
        for (Path place : layout.records()) {
            Path records = real(place);
            if (real.startsWith(records) || records.startsWith(real)) {
                return Optional.of("it is, lies in or holds " + place + " of the Traceworks"
                        + " home, which holds what runs leave behind; work in a directory outside it");
            }
        }
        return Optional.empty();
    }

    /**
     * The files the seal settings name ({@link HomeLayout#sealFiles()}): the signing key, which no step may read,
     * and the allowed signers file, which no step may write, since a step that can write it allows a key of its own
     * and a seal by that key then holds. A directory that is such a file or holds it is refused, for a workspace, a
     * package and a step's mount, read-only too: one rule for both files, so nothing has to tell which mount of
     * which step may write.
     *
     * <p>What is guarded is every place whose folder decides which file the setting names
     * ({@link #sealFilePlaces}): the path as written, every symbolic link met on the way to the file, and the place
     * the path ends at, whether a file is there yet or not.
     */
    private static Optional<String> sealFilesRefusal(Path real, HomeLayout layout) {
        for (HomeLayout.SealFile sealed : layout.sealFiles()) {
            for (Path place : sealFilePlaces(sealed.file())) {
                if (place.startsWith(real)) {
                    return Optional.of("it is or holds " + sealed.file() + ", the file that '" + sealed.setting()
                            + "' in traceworks.json names" + (place.equals(sealed.file()) ? "" : " (by way of " + place
                            + ")") + ": " + (sealed.setting().equals("signing_key")
                            ? "no step may read the key that seals packages"
                            : "a step that can write it could allow a key of its own")
                            + "; keep that file in a folder of its own, outside every workspace, package and mount");
                }
            }
        }
        return Optional.empty();
    }

    /** How many symbolic links the walk of a configured path follows; the kernel gives up at 40 too. */
    private static final int MAX_LINKS = 40;

    /**
     * The places that decide which file a configured path names, each by the real path of its folder plus its name.
     * The path is walked one name at a time, as the kernel resolves it: a symbolic link on the way, a directory
     * link in the middle as well as a link at the end or one a link points to, is a place of its own, since who can
     * write the folder that holds the link can point it elsewhere; then the walk goes on at its target. The place
     * the walk ends at is the file, or where the file would be created: a name that does not exist yet stays as it
     * is written below the last folder that exists. A mount that is one of these places, or a folder above one, can
     * change which file the setting names, or the file itself.
     *
     * <p>The path as written, with {@code .} and {@code ..} applied, is a place too. Links in a circle, or more
     * than {@value #MAX_LINKS} of them, end the walk; the places met until then are returned.
     */
    static List<Path> sealFilePlaces(Path file) {
        Path absolute = file.toAbsolutePath();
        List<Path> places = new ArrayList<>(List.of(absolute.normalize()));
        java.util.ArrayDeque<Path> pending = new java.util.ArrayDeque<>();
        absolute.forEach(pending::add);
        Path current = absolute.getRoot();
        int links = 0;
        while (!pending.isEmpty()) {
            Path name = pending.poll();
            String text = name.toString();
            if (text.isEmpty() || text.equals(".")) {
                continue;
            }
            if (text.equals("..")) {
                current = current.getParent() == null ? current : current.getParent();
                continue;
            }
            Path next = current.resolve(name);
            if (!Files.isSymbolicLink(next)) {
                current = next;
                continue;
            }
            places.add(next);
            Path target;
            try {
                target = Files.readSymbolicLink(next);
            } catch (IOException e) {
                break;   // the link is guarded; where it leads cannot be read
            }
            if (++links > MAX_LINKS) {
                break;
            }
            if (target.isAbsolute()) {
                current = target.getRoot();
            }
            List<Path> names = new ArrayList<>();
            target.forEach(names::add);
            for (int i = names.size() - 1; i >= 0; i--) {
                pending.addFirst(names.get(i));
            }
        }
        places.add(current);
        return places;
    }

    /** The operator's session paths, wherever they lie: in the mount, on it or around it. */
    private static Optional<String> sessionRefusal(Path real, Context context) {
        for (Path sessionPath : context.sessionPaths()) {
            Path session = real(sessionPath);
            if (real.startsWith(session) || session.startsWith(real)) {
                return Optional.of("it is, lies in or holds " + sessionPath + " of the operator's session, whose"
                        + " sockets reach the host");
            }
        }
        return Optional.empty();
    }

    /**
     * Fail closed: neither the path nor, for a folder, one of its direct entries (links not followed) may be a
     * socket, a named pipe or a device node. Whatever cannot be read is refused: a rootful runtime would mount it anyway, and in the container the
     * host's parent directories no longer guard it.
     *
     * @param real the real path the mount resolved to; it is not resolved again here
     */
    private static Optional<String> socketRefusal(Path real) {
        try {
            if (isSocket(real)) {
                return Optional.of("it is a socket: a line to a process on the host, which read-only does not cut");
            }
            if (isDeviceOrPipe(real)) {
                return Optional.of("it is a device or a named pipe: a line to the host or to a process on it, which"
                        + " read-only does not cut");
            }
            if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
        } catch (IOException e) {
            return Optional.of("cannot be resolved: its file type cannot be read: " + e);
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(real)) {
            for (Path entry : entries) {
                if (isSocket(entry)) {
                    return Optional.of("it holds the socket " + entry + ": a line to a process on the host");
                }
                if (isDeviceOrPipe(entry)) {
                    return Optional.of("it holds the device or named pipe " + entry + ": a line to the host or to a"
                            + " process on it");
                }
            }
        } catch (IOException | DirectoryIteratorException e) {
            return Optional.of("its entries cannot be read, so its sockets cannot be ruled out: " + e);
        }
        return Optional.empty();
    }

    /**
     * The home or a login below it, in the mount or around it.
     *
     * @param ownCredentials whether a folder with a credential store of its own counts as a copy of a home
     * @param belowHome      what to do instead when it is or contains the home directory: a step mounts a directory
     *                       below it, a run works in a project directory, a package lies in a folder of its own
     */
    private static Optional<String> homeRefusal(Path real, Path home, boolean ownCredentials, String belowHome) {
        if (real.getParent() == null || real(home).startsWith(real)) {
            return Optional.of("it is or contains the home directory; " + belowHome);
        }
        for (String name : HostSecrets.HOME_SECRETS) {
            Path secret = home.resolve(name);
            Path realSecret = real(secret);
            if (real.startsWith(realSecret)) {
                return Optional.of("it lies in ~/" + name + " (credentials)");
            }
            if (Files.exists(secret, LinkOption.NOFOLLOW_LINKS) && realSecret.startsWith(real)) {
                return Optional.of("it contains ~/" + name + " (credentials)");
            }
        }
        for (String name : HostSecrets.HOME_STORAGE) {
            Path storage = home.resolve(name);
            Path realStorage = real(storage);
            if (real.startsWith(realStorage)
                    || Files.exists(storage, LinkOption.NOFOLLOW_LINKS) && realStorage.startsWith(real)) {
                return Optional.of("it is, lies in or holds ~/" + name + ", a container runtime's storage: the"
                        + " images and containers later runs use");
            }
        }
        for (String name : ownCredentials ? OWN_CREDENTIALS : List.<String>of()) {
            Path own = real.resolve(name);
            if (Files.exists(own, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.of("it contains " + own + " (credentials, as in ~/" + name + ")");
            }
        }
        return Optional.empty();
    }

    /**
     * Whether the file at {@code path} itself (not what a link points to) is a socket.
     *
     * @throws IOException if its attributes cannot be read
     */
    private static boolean isSocket(Path path) throws IOException {
        try {
            int mode = (Integer) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
            return (mode & S_IFMT) == S_IFSOCK;
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther();
        }
    }

    /**
     * Whether the file at {@code path} itself is a character or block device or a named pipe; {@code false} where
     * the file system does not tell.
     *
     * @throws IOException if its attributes cannot be read
     */
    private static boolean isDeviceOrPipe(Path path) throws IOException {
        try {
            int type = (Integer) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS) & S_IFMT;
            return type == S_IFIFO || type == S_IFCHR || type == S_IFBLK;
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            return false;   // isSocket has refused everything there that is no file, folder or link
        }
    }

    /**
     * The Traceworks home: refused but for the run's own scratch directory, the run's own checkout of its source,
     * which is its workspace (a mount in that one follows the rules of a mount in a workspace,
     * {@link #writableRefusal}), and a folder below the tool caches when the engine mounts it as the cache of a tool
     * step's image. The tool caches are kept per image and later runs of other packages execute or trust what is in
     * them, so a workflow's own mount names neither their root nor a folder in it.
     *
     * @param toolCache whether the mount is a tool step's cache folder ({@link Mount#toolCache()})
     */
    private static Optional<String> traceworksHomeRefusal(Path real, Context context, boolean toolCache) {
        Path traceworksHome = real(context.traceworksHome());
        if (traceworksHome.startsWith(real)) {
            return Optional.of("it is or contains the Traceworks home " + context.traceworksHome());
        }
        Path caches = real(context.layout().toolCache());
        if (toolCache && real.startsWith(caches) && !real.equals(caches)
                || present(context.scratch()) && real.startsWith(real(Path.of(context.scratch())))
                || present(context.checkout()) && real.startsWith(real(Path.of(context.checkout())))) {
            return Optional.empty();
        }
        if (!real.startsWith(traceworksHome)) {
            // The records may lie elsewhere through a link (runs on another disk): their real paths count too
            return recordsRefusal(real, context.layout());
        }
        return Optional.of("it lies in the Traceworks home " + context.traceworksHome() + "; only the run's own"
                + " scratch directory and the run's own checkout of its source may be mounted from there, and a tool"
                + " step gets the cache folder of its image with cache_dir");
    }

    /**
     * Why a writable mount is refused: one in or around the package, or the read-only workspace, would undo its
     * read-only mount; one in or around the {@code .git} of a writable workspace would undo the read-only mounts of
     * its hooks and its config ({@link WorkspaceGit}); and any other needs a folder the operator listed
     * ({@link #unlistedRefusal}), unless Traceworks manages the folder itself ({@link #managedByTraceworks}).
     */
    private static Optional<String> writableRefusal(Mount mount, Path real, Context context) {
        if (!context.writableWorkspace() && overlaps(real, context.workspace())) {
            return Optional.of("a writable mount of the workspace would get around its read-only mount; set"
                    + " workspace_writable: true or mount it :ro");
        }
        if (overlaps(real, context.packageDirectory())) {
            return Optional.of("a writable mount of the package would get around its read-only mount; mount it :ro");
        }
        if (context.writableWorkspace() && present(context.workspace())
                && WorkspaceGit.isInOrHolds(real, real(Path.of(context.workspace())))) {
            return Optional.of("it is, lies in or holds the .git of the workspace, whose hooks and config a step"
                    + " sees read-only: a writable mount would show them writable at another place; mount a folder"
                    + " of the workspace beside it, or mount it :ro");
        }
        return managedByTraceworks(mount, real, context) ? Optional.empty()
                : unlistedRefusal(real, context.home(), context.layout());
    }

    /**
     * Whether Traceworks manages the folder a writable mount names, so that no listed folder is asked of it: the
     * folder the engine made for a tool step's output ({@link Mount#toolOutput()}), the cache folder of a tool
     * step's image ({@link Mount#toolCache()}, below the tool caches), the run's own scratch directory, and the
     * run's own checkout of its source while the step may write its workspace. Every other rule of the guard was
     * asked of the mount before this one.
     */
    private static boolean managedByTraceworks(Mount mount, Path real, Context context) {
        if (mount.toolOutput()) {
            return true;
        }
        Path caches = real(context.layout().toolCache());
        if (mount.toolCache() && real.startsWith(caches) && !real.equals(caches)) {
            return true;
        }
        if (present(context.scratch()) && real.startsWith(real(Path.of(context.scratch())))) {
            return true;
        }
        return context.writableWorkspace() && present(context.checkout())
                && real.startsWith(real(Path.of(context.checkout())));
    }

    /**
     * Why a writable mount of {@code real} is refused for lying in no folder the operator listed
     * ({@code writable_mounts}, {@link HomeLayout#writableMounts()}); empty when it is one of them or lies below
     * one. A step runs as the operator, so what it writes on the host can be a file the host runs later, outside
     * every container (a shell's startup file, a folder on the {@code PATH}, the hooks of a repository), and no
     * list of such files could be complete: the operator says where packages may write. Both sides are compared by
     * their real paths. A listed folder that is {@code /}, the operator's home directory or a folder that holds it
     * counts for nothing, whoever built the layout; the setting refuses it when the file is read.
     *
     * <p>The refusal names the line that would allow the mount: the list with the folder of the mount added.
     */
    private static Optional<String> unlistedRefusal(Path real, Path home, HomeLayout layout) {
        Path realHome = real(home);
        for (Path listed : layout.writableMounts()) {
            if (!listed.isAbsolute()) {
                continue;
            }
            Path folder = real(listed);
            if (folder.getNameCount() > 0 && !realHome.startsWith(folder) && real.startsWith(folder)) {
                return Optional.empty();
            }
        }
        Path file = layout.home().resolve("traceworks.json");
        String why = "a package may mount a host path writable only in or below a folder that writable_mounts in "
                + file + " lists, and " + real + " lies in none";
        Path folder = Files.isDirectory(real) || real.getParent() == null ? real : real.getParent();
        if (folder.getNameCount() == 0 || realHome.startsWith(folder)) {
            return Optional.of(why + "; its folder " + folder + " is your home directory or holds it, which"
                    + " writable_mounts cannot list: move it into a folder of its own, or mount it :ro");
        }
        List<String> entries = new ArrayList<>();
        layout.writableMounts().forEach(listed -> entries.add(quoted(listed.toString())));
        entries.add(quoted(folder.toString()));
        return Optional.of(why + "; mount it :ro, or, if every package you run may write that folder, allow it"
                + " with this line in " + file + ": \"" + HomeLayout.WRITABLE_MOUNTS + "\": ["
                + String.join(", ", entries) + "]");
    }

    /** {@code text} as a JSON string. */
    private static String quoted(String text) {
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /**
     * Why a mount that a workflow writes out as writable, with the host side {@code hostPath}, would be refused for
     * lying in no folder the operator listed; empty when a listed folder holds it, and for a host side that is no
     * absolute path after {@code ~} is expanded, which the step refuses for that. The question the start check of
     * a run asks before any step runs: only the rule of the listed folders, of which a workflow's own text can
     * name nothing that Traceworks manages. Every rule is asked again, of the real path, when the step runs
     * ({@link #judge}).
     */
    public static Optional<String> writableMountRefusal(String hostPath, Path home, HomeLayout layout) {
        String host = expandHome(hostPath.strip(), home);
        if (!host.startsWith("/")) {
            return Optional.empty();
        }
        try {
            return unlistedRefusal(real(Path.of(host)), home, layout);
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    private static boolean overlaps(Path real, String directory) {
        if (!present(directory)) return false;
        Path dir = real(Path.of(directory));
        return real.startsWith(dir) || dir.startsWith(real);
    }

    /**
     * The forbidden path that either side of a mount matches, or {@code null}. The host side is compared as given
     * and as its real path, the container side as given; both with {@code .} and {@code ..} resolved, as the runtime
     * resolves them.
     */
    static String forbiddenSegment(String host, String container) {
        String normalizedHost = normalized(host.strip());
        String realHost = normalizedHost.startsWith("/") ? real(Path.of(normalizedHost)).toString() : normalizedHost;
        String normalizedContainer = normalized(container.strip());
        for (String forbidden : FORBIDDEN_PATHS) {
            if (hostMatches(normalizedHost, forbidden) || hostMatches(realHost, forbidden)
                    || matchesForbidden(normalizedContainer, forbidden)) {
                return forbidden;
            }
        }
        return null;
    }

    /**
     * The forbidden path a host path matches, as given or as its real path, or that it holds; {@code null} for none.
     * In the order of {@link #FORBIDDEN_PATHS}, as {@link #forbiddenSegment}.
     */
    static String forbiddenHostSegment(String host) {
        String normalizedHost = normalized(host.strip());
        String realHost = normalizedHost.startsWith("/") ? real(Path.of(normalizedHost)).toString() : normalizedHost;
        for (String forbidden : FORBIDDEN_PATHS) {
            if (hostMatches(normalizedHost, forbidden) || hostMatches(realHost, forbidden)) {
                return forbidden;
            }
        }
        return null;
    }

    /** The memory disk, where a folder may be a run's workspace or package although it lies in {@code /dev}. */
    private static final String MEMORY_DISK = "/dev/shm";

    /**
     * {@link #forbiddenHostSegment} for one of a run's own directories, with one exception: a folder in
     * {@value #MEMORY_DISK} is allowed, as given and as its real path. A memory disk is a common place for a
     * build, and the operator chose the directory; {@code /dev} is forbidden because of what a workflow's own
     * mount could reach there (the terminals, the shared memory of other programs), and a step's mount from
     * {@code /dev} stays refused. {@value #MEMORY_DISK} itself and the rest of {@code /dev} stay forbidden here too.
     */
    private static String forbiddenRunDirectory(String directory) {
        String forbidden = forbiddenHostSegment(directory);
        if (!"/dev".equals(forbidden)) {
            return forbidden;   // "/dev" is the last of the list: it is named only when no other path matches
        }
        String normalized = normalized(directory.strip());
        String real = real(Path.of(normalized)).toString();
        return normalized.startsWith(MEMORY_DISK + "/") && real.startsWith(MEMORY_DISK + "/") ? null : forbidden;
    }

    /** As given or with symbolic links resolved: {@code /var/run/docker.sock} is {@code /run/docker.sock}. */
    private static boolean hostMatches(String host, String forbidden) {
        String realForbidden = real(Path.of(forbidden)).toString();
        for (String candidate : List.of(forbidden, realForbidden)) {
            if (matchesForbidden(host, candidate) || holds(host, candidate)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code host} is a directory above {@code forbidden}; {@code /} is matched on its own. */
    private static boolean holds(String host, String forbidden) {
        return host.startsWith("/") && !host.equals("/") && forbidden.startsWith(host + "/");
    }

    private static boolean matchesForbidden(String path, String forbidden) {
        if (path == null || path.isBlank()) return false;
        String trimmed = path.endsWith("/") && !path.equals("/") ? path.substring(0, path.length() - 1) : path;
        if (trimmed.equals(forbidden)) return true;
        if (forbidden.equals("/")) return false; // every path starts with "/": only an exact match counts
        return trimmed.startsWith(forbidden + "/");
    }

    /** {@code .} and {@code ..} resolved as the runtime resolves them: {@code /tmp/../etc} is {@code /etc}. */
    private static String normalized(String path) {
        if (path.isBlank()) return path;
        try {
            return Path.of(path).normalize().toString();
        } catch (InvalidPathException e) {
            return path;
        }
    }

    /**
     * The host side of a step's own mount as the runtime mounts it: {@code ~} expanded, then the real path. Relative paths stay
     * as they are, so the command line refuses them.
     */
    static String realHostPath(String hostPath, Path home) {
        String host = expandHome(hostPath.strip(), home);
        if (!host.startsWith("/")) {
            return host;
        }
        try {
            return real(Path.of(host)).toString();
        } catch (InvalidPathException e) {
            return host;
        }
    }

    /**
     * One of the run's own directories as the runtime mounts it: its real path, read exactly as it is named. Unlike
     * a step's own mount ({@link #realHostPath}) nothing is cut off and no {@code ~} is expanded: the run hands the
     * directory on as the real path its start check judged, and what was judged must be what is mounted.
     */
    static String realRunDirectory(String directory) {
        if (!directory.startsWith("/")) {
            return directory;
        }
        try {
            return real(Path.of(directory)).toString();
        } catch (InvalidPathException e) {
            return directory;
        }
    }

    /**
     * The real path, resolved as the kernel resolves it: links are followed before {@code ..} is applied. Removing
     * {@code ..} as text first would name another folder than the runtime mounts whenever a link comes before it, and
     * what is judged must be what is mounted. For a path that does not exist: the real path of its parent plus its
     * name, with {@code .} and {@code ..} applied to that real parent.
     */
    static Path real(Path path) {
        Path absolute = path.toAbsolutePath();
        try {
            return absolute.toRealPath();
        } catch (IOException e) {
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

    /** {@code ~} and {@code ~/...} below {@code home}; anything else unchanged. */
    static String expandHome(String path, Path home) {
        if (path.equals("~")) {
            return home.toString();
        }
        return path.startsWith("~/") ? home.resolve(path.substring(2)).toString() : path;
    }

    private static boolean present(String path) {
        return path != null && !path.isBlank();
    }
}
