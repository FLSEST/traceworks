package traceworks.core.container;

import traceworks.core.port.CommandRunner.Mount;

import java.util.ArrayList;
import java.util.List;

/**
 * The host directories a run's containers see, and where they see them: the workspace, the package and the run's
 * scratch directory. A container run sees them at fixed paths ({@value #WORKSPACE}, {@value #PACKAGE},
 * {@value #SCRATCH}), so a command reads the same on every machine; a host run mounts each one at its own host path, so
 * a path means the same on the host and in a container step.
 *
 * <p>A run of an audit workflow has a fourth directory, the register folder: the files its register handed it at its
 * start (docs/registers.md, "The register folder of a run"). Every container but that of an AI call mounts it,
 * read-only, at {@value #REGISTER}.
 *
 * @param workspace           the host path of the workspace; {@code null} for none
 * @param packageDirectory    the host path of the package; {@code null} for none
 * @param scratch             the host path of the run's scratch directory; {@code null} for none
 * @param register            the host path of the run's register folder; {@code null} for a run of a workflow that
 *                            is no audit
 * @param fixedContainerPaths whether containers see the directories at the fixed paths (a container run) or at their
 *                            host paths (a host run)
 */
public record RunPaths(String workspace, String packageDirectory, String scratch, String register,
                       boolean fixedContainerPaths) {

    /** The paths of a run without a register folder. */
    public RunPaths(String workspace, String packageDirectory, String scratch, boolean fixedContainerPaths) {
        this(workspace, packageDirectory, scratch, null, fixedContainerPaths);
    }

    /** Where a container run's containers see the workspace, and where their commands start. */
    public static final String WORKSPACE = "/workspace";
    /** Where a container run's containers see the package. */
    public static final String PACKAGE = "/package";
    /** Where a container run's containers see the scratch directory. */
    public static final String SCRATCH = "/scratch";
    /** Where a container run's containers see the register folder of an audit run. */
    public static final String REGISTER = "/register";

    /**
     * Where a container sees {@code hostPath}: below the deepest standard directory that holds it, at that
     * directory's fixed path; otherwise, and in a host run, unchanged. The workspace wins a tie with the package.
     */
    public String containerPathOf(String hostPath) {
        if (hostPath == null || !fixedContainerPaths) {
            return hostPath;
        }
        String best = null;
        String fixed = null;
        for (String[] dir : new String[][] {{workspace, WORKSPACE}, {packageDirectory, PACKAGE}, {scratch, SCRATCH},
                {register, REGISTER}}) {
            String host = trimmed(dir[0]);
            if (host != null && within(hostPath, host) && (best == null || host.length() > best.length())) {
                best = host;
                fixed = dir[1];
            }
        }
        if (best == null) {
            return hostPath;
        }
        String rest = hostPath.substring(best.length());
        return fixed + (best.equals("/") && !rest.isEmpty() ? "/" + rest : rest);
    }

    /** Whether {@code hostPath} is one of the run's directories or lies below one. */
    public boolean holds(String hostPath) {
        for (String dir : new String[] {workspace, packageDirectory, scratch, register}) {
            if (within(hostPath, trimmed(dir))) return true;
        }
        return false;
    }

    /** Where a container sees the workspace; {@code null} without one. */
    public String workspaceInContainer() {
        return seen(workspace, WORKSPACE);
    }

    /** Where a container sees the package; {@code null} without one. */
    public String packageInContainer() {
        return seen(packageDirectory, PACKAGE);
    }

    /** Where a container sees the scratch directory; {@code null} without one. */
    public String scratchInContainer() {
        return seen(scratch, SCRATCH);
    }

    /** Where a container sees the register folder; {@code null} for a run without one. */
    public String registerInContainer() {
        return seen(register, REGISTER);
    }

    /**
     * {@link #standardMounts(boolean)}, and with {@code withRegister} the register folder of an audit run, always
     * read-only, after the scratch directory: what a container gets that is not that of an AI call.
     */
    public List<Mount> standardMounts(boolean writableWorkspace, boolean withRegister) {
        List<Mount> mounts = new ArrayList<>(standardMounts(writableWorkspace));
        if (withRegister && present(register)) {
            // After the mounts of the run's three directories, and before the package's second mount, which is not
            // one of them
            int at = 0;
            while (at < mounts.size() && !isSecondPackageMount(mounts, at)) {
                at++;
            }
            mounts.add(at, new Mount(register, registerInContainer(), true));
        }
        return List.copyOf(mounts);
    }

    /** Whether the mount at {@code index} is the package once more, inside a writable workspace. */
    private boolean isSecondPackageMount(List<Mount> mounts, int index) {
        Mount mount = mounts.get(index);
        return index > 0 && fixedContainerPaths && mount.hostPath().equals(packageDirectory)
                && !PACKAGE.equals(mount.containerPath());
    }

    /**
     * The mounts every container of the run gets, in this order: the workspace (read-only unless
     * {@code writableWorkspace}), the package (always read-only) and the scratch directory (always writable). A
     * directory the run does not have is left out. In a host run, where each directory is mounted at its host path, a
     * workspace that is the package is mounted once, read-only as the package: two mounts with one destination are
     * refused by the runtime.
     *
     * <p>A writable workspace that holds the package would make the package writable at its place in the workspace. So
     * a container run then mounts the package once more, read-only, at that place, after the scratch directory: the
     * runtime mounts a deeper target after a shallower one, whatever the order of the arguments, so it covers that part
     * of the workspace. A host run needs none, since it mounts the package at its own path inside the workspace
     * anyway. A writable workspace inside the package is refused before it gets this far
     * ({@link #workspaceInsidePackage}).
     */
    public List<Mount> standardMounts(boolean writableWorkspace) {
        List<Mount> mounts = new ArrayList<>();
        String host = trimmed(workspace);
        String pkg = trimmed(packageDirectory);
        // In a host run a workspace that is the package has the package's destination: one mount, the package's
        boolean oneMount = !fixedContainerPaths && host != null && host.equals(pkg);
        if (present(workspace) && !oneMount) {
            mounts.add(new Mount(workspace, workspaceInContainer(), !writableWorkspace));
        }
        if (present(packageDirectory)) mounts.add(new Mount(packageDirectory, packageInContainer(), true));
        if (present(scratch)) mounts.add(new Mount(scratch, scratchInContainer(), false));
        if (writableWorkspace && fixedContainerPaths && host != null && pkg != null && !pkg.equals(host)
                && within(pkg, host)) {
            String rest = pkg.substring(host.length());
            mounts.add(new Mount(packageDirectory, WORKSPACE + (rest.startsWith("/") ? rest : "/" + rest), true));
        }
        return List.copyOf(mounts);
    }

    /**
     * Whether the workspace is the package or lies inside it. The package is always read-only, so such a workspace
     * cannot be writable.
     */
    public boolean workspaceInsidePackage() {
        return within(trimmed(workspace), trimmed(packageDirectory));
    }

    /**
     * Where the standard mounts lie in a container, on or below which a declared mount would cover one: the fixed paths
     * in a container run, whether the run has the directory or not, and {@value #REGISTER} only for a run that has
     * a register folder; the host paths of the directories it has in a host run.
     */
    public List<String> standardMountPoints() {
        if (fixedContainerPaths) {
            // The register folder only for a run that has one: no other run's steps lose a path they may mount at
            return present(register) ? List.of(WORKSPACE, PACKAGE, SCRATCH, REGISTER)
                    : List.of(WORKSPACE, PACKAGE, SCRATCH);
        }
        List<String> points = new ArrayList<>();
        for (String dir : new String[] {workspace, packageDirectory, scratch, register}) {
            String host = trimmed(dir);
            if (host != null) points.add(host);
        }
        return List.copyOf(points);
    }

    /** Whether {@code path} is {@code dir} or lies below it, by whole path segments. */
    public static boolean within(String path, String dir) {
        if (path == null || dir == null) return false;
        return path.equals(dir) || path.startsWith(dir.endsWith("/") ? dir : dir + "/");
    }

    private String seen(String hostPath, String fixed) {
        if (!present(hostPath)) return null;
        return fixedContainerPaths ? fixed : hostPath;
    }

    private static boolean present(String path) {
        return path != null && !path.isBlank();
    }

    /** The path without a trailing slash; {@code null} for none. */
    private static String trimmed(String path) {
        if (!present(path)) return null;
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}
