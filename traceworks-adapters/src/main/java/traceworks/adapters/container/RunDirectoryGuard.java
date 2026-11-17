package traceworks.adapters.container;

import traceworks.adapters.config.HomeLayout;
import traceworks.core.port.HostDirectories;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The {@link HostDirectories} of this machine: real paths from the file system, and the start check of the run's own
 * directories with {@link MountGuard}'s rules ({@link MountGuard#checkRunDirectory}), so a workspace or a package
 * cannot hand a container what a step's own mount may not; and the guard's answer on a writable mount that a
 * workflow writes out, before the run starts ({@link MountGuard#writableMountRefusal}).
 */
public final class RunDirectoryGuard implements HostDirectories {

    private final HomeLayout layout;
    private final Path home;
    private final List<Path> sessionPaths;

    /**
     * @param traceworksHome the Traceworks home, with its runs, ledger and registers in their fixed folders
     * @param home           the operator's home directory
     * @param environment    the operator's environment, which names its session paths ({@link MountGuard#sessionPaths})
     */
    public RunDirectoryGuard(Path traceworksHome, Path home, Map<String, String> environment) {
        this(HomeLayout.of(Objects.requireNonNull(traceworksHome, "traceworksHome")), home, environment);
    }

    /**
     * @param layout      the Traceworks home and where it keeps its runs, ledger and registers
     * @param home        the operator's home directory
     * @param environment the operator's environment, which names its session paths ({@link MountGuard#sessionPaths})
     */
    public RunDirectoryGuard(HomeLayout layout, Path home, Map<String, String> environment) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.home = Objects.requireNonNull(home, "home");
        this.sessionPaths = MountGuard.sessionPaths(environment);
    }

    @Override
    public String realPath(Path directory) {
        try {
            return directory.toRealPath().toString();
        } catch (IOException | InvalidPathException e) {
            throw new IllegalArgumentException("Cannot resolve " + directory + ": " + e, e);
        }
    }

    @Override
    public Checked check(Path directory) {
        return check(directory, Role.WORKSPACE);
    }

    @Override
    public Checked check(Path directory, Role role) {
        return MountGuard.checkRunDirectory(directory, home, layout, sessionPaths, role);
    }

    /** By the home's {@code writable_mounts} and the real path, as the mount check asks it when the step runs. */
    @Override
    public java.util.Optional<String> writableMountRefusal(String hostPath) {
        return MountGuard.writableMountRefusal(hostPath, home, layout, null);
    }

    /** The home's {@code workspace_git_writable}. */
    @Override
    public boolean workspaceGitWritable() {
        return layout.workspaceGitWritable();
    }

    /** The same, and nothing asked of a mount in the workspace that its step may write, by the real path. */
    @Override
    public java.util.Optional<String> writableMountRefusal(String hostPath, String writableWorkspace) {
        return MountGuard.writableMountRefusal(hostPath, home, layout, writableWorkspace);
    }
}
