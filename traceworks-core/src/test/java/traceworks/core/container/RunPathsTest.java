package traceworks.core.container;

import traceworks.core.port.CommandRunner.Mount;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Where a container sees the run's directories: at fixed paths in a container run, at their host paths otherwise. */
class RunPathsTest {

    private final RunPaths fixed = new RunPaths("/home/a/project", "/home/a/project/pkg", "/th/runs/r/scratch", true);
    private final RunPaths host = new RunPaths("/home/a/project", "/home/a/project/pkg", "/th/runs/r/scratch", false);

    @Test
    void fixedPaths_mapEachDirectoryAndWhatLiesBelowIt_theDeepestFirst() {
        assertThat(fixed.containerPathOf("/home/a/project")).isEqualTo("/workspace");
        assertThat(fixed.containerPathOf("/home/a/project/src/main")).isEqualTo("/workspace/src/main");
        assertThat(fixed.containerPathOf("/home/a/project/pkg/scripts")).isEqualTo("/package/scripts");
        assertThat(fixed.containerPathOf("/th/runs/r/scratch/out.txt")).isEqualTo("/scratch/out.txt");
        assertThat(fixed.containerPathOf("/home/a/projectX")).as("a whole segment only").isEqualTo("/home/a/projectX");
        assertThat(fixed.containerPathOf("/etc")).isEqualTo("/etc");
        assertThat(fixed.containerPathOf(null)).isNull();
    }

    @Test
    void hostPaths_stayAsTheyAre() {
        assertThat(host.containerPathOf("/home/a/project/src")).isEqualTo("/home/a/project/src");
        assertThat(host.workspaceInContainer()).isEqualTo("/home/a/project");
        assertThat(host.packageInContainer()).isEqualTo("/home/a/project/pkg");
        assertThat(host.scratchInContainer()).isEqualTo("/th/runs/r/scratch");
    }

    @Test
    void theStandardDirectories_haveTheirFixedPaths_alsoWhenTheyAreOneDirectory() {
        RunPaths same = new RunPaths("/p", "/p", "/s", true);

        assertThat(same.workspaceInContainer()).isEqualTo("/workspace");
        assertThat(same.packageInContainer()).isEqualTo("/package");
        assertThat(same.scratchInContainer()).isEqualTo("/scratch");
        assertThat(same.containerPathOf("/p")).as("the workspace wins a tie").isEqualTo("/workspace");
        assertThat(same.standardMounts(false)).containsExactly(new Mount("/p", "/workspace", true),
                new Mount("/p", "/package", true), new Mount("/s", "/scratch", false));
    }

    /**
     * A host run mounts each directory at its host path. A workspace that is the package would be two mounts with one
     * destination, which the runtime refuses ("Duplicate mount point", exit 125): it is mounted once, read-only, as
     * the package always is.
     */
    @Test
    void hostRun_aWorkspaceThatIsThePackage_isMountedOnce_readOnly() {
        assertThat(new RunPaths("/p", "/p", "/s", false).standardMounts(false)).containsExactly(
                new Mount("/p", "/p", true), new Mount("/s", "/s", false));
        assertThat(new RunPaths("/p/", "/p", "/s", false).standardMounts(false)).as("a trailing slash is no other"
                + " directory").containsExactly(new Mount("/p", "/p", true), new Mount("/s", "/s", false));
        assertThat(new RunPaths("/p", "/p", "/s", false).standardMounts(true)).as("the package is never writable; a"
                + " step that writes such a workspace is refused: by the start check where the definition sets"
                + " workspace_writable, and by the command runner when the step runs")
                .containsExactly(new Mount("/p", "/p", true), new Mount("/s", "/s", false));
    }

    @Test
    void standardMounts_workspaceReadOnlyUnlessWritable_packageReadOnly_scratchWritable() {
        assertThat(host.standardMounts(true)).containsExactly(
                new Mount("/home/a/project", "/home/a/project", false),
                new Mount("/home/a/project/pkg", "/home/a/project/pkg", true),
                new Mount("/th/runs/r/scratch", "/th/runs/r/scratch", false));
        assertThat(new RunPaths(null, " ", null, true).standardMounts(false)).isEmpty();
        assertThat(new RunPaths(null, null, null, true).workspaceInContainer()).isNull();
    }

    /**
     * A writable workspace that holds the package would make the package writable at its place inside
     * {@code /workspace}: a container run mounts the package there once more, read-only, after the run's directories.
     * Docker mounts a deeper target after a shallower one, so that mount covers that part of the workspace.
     */
    @Test
    void aWritableWorkspaceThatHoldsThePackage_getsThePackageAgain_readOnly_atItsPlaceInTheWorkspace() {
        assertThat(fixed.standardMounts(true)).containsExactly(
                new Mount("/home/a/project", "/workspace", false),
                new Mount("/home/a/project/pkg", "/package", true),
                new Mount("/th/runs/r/scratch", "/scratch", false),
                new Mount("/home/a/project/pkg", "/workspace/pkg", true));
        assertThat(fixed.standardMounts(false)).as("a read-only workspace keeps the package read-only").hasSize(3);
        assertThat(host.standardMounts(true)).as("a host run mounts the package at its own path inside the workspace")
                .hasSize(3);
        assertThat(new RunPaths("/p", "/p", "/s", true).standardMounts(true)).as("the workspace is the package")
                .hasSize(3);
        assertThat(new RunPaths("/w", "/other/pkg", "/s", true).standardMounts(true)).hasSize(3);
    }

    @Test
    void workspaceInsideThePackage_isTheWorkspaceOnOrBelowThePackage() {
        assertThat(new RunPaths("/p", "/p", "/s", true).workspaceInsidePackage()).isTrue();
        assertThat(new RunPaths("/p/sub", "/p", "/s", false).workspaceInsidePackage()).isTrue();
        assertThat(fixed.workspaceInsidePackage()).as("the package inside the workspace").isFalse();
        assertThat(new RunPaths("/px", "/p", "/s", true).workspaceInsidePackage()).isFalse();
        assertThat(new RunPaths(null, "/p", "/s", true).workspaceInsidePackage()).isFalse();
    }

    /** In a host run a declared mount must not cover any of the run's directories, each at its own host path. */
    @Test
    void standardMountPoints_areTheFixedPathsInAContainerRun_andEachDirectorysHostPathInAHostRun() {
        assertThat(fixed.standardMountPoints()).containsExactly("/workspace", "/package", "/scratch", "/register");
        assertThat(host.standardMountPoints())
                .containsExactly("/home/a/project", "/home/a/project/pkg", "/th/runs/r/scratch");
        assertThat(new RunPaths("/w", "/p", "/s", "/th/runs/r/register/", false).standardMountPoints())
                .as("the register folder of an audit run too").containsExactly("/w", "/p", "/s", "/th/runs/r/register");
        assertThat(new RunPaths("/w/", null, "/s/", false).standardMountPoints())
                .as("without a trailing slash, and only the directories the run has").containsExactly("/w", "/s");
        assertThat(new RunPaths(null, null, null, true).standardMountPoints())
                .as("a container run has the fixed paths whether it has the directory or not")
                .containsExactly("/workspace", "/package", "/scratch", "/register");
    }

    /**
     * The register folder of an audit run is the run's fourth directory: read-only, after the scratch directory and
     * before the package's second mount, which is not one of the run's directories.
     */
    @Test
    void theRegisterFolder_isMountedReadOnly_afterTheScratch_forWhoeverGetsIt() {
        RunPaths audit = new RunPaths("/home/a/project", "/home/a/project/pkg", "/th/runs/r/scratch",
                "/th/runs/r/register", true);

        assertThat(audit.standardMounts(true, true)).containsExactly(
                new Mount("/home/a/project", "/workspace", false),
                new Mount("/home/a/project/pkg", "/package", true),
                new Mount("/th/runs/r/scratch", "/scratch", false),
                new Mount("/th/runs/r/register", "/register", true),
                new Mount("/home/a/project/pkg", "/workspace/pkg", true));
        assertThat(audit.standardMounts(false, false)).as("an AI call goes without it")
                .isEqualTo(audit.standardMounts(false)).hasSize(3);
        assertThat(audit.registerInContainer()).isEqualTo("/register");
        assertThat(audit.containerPathOf("/th/runs/r/register/findings.jsonl")).isEqualTo("/register/findings.jsonl");
        assertThat(new RunPaths("/p", "/p", "/s", "/r", false).standardMounts(false, true))
                .as("a host run: at its host path, after a workspace that is the package and is mounted once")
                .containsExactly(new Mount("/p", "/p", true), new Mount("/s", "/s", false), new Mount("/r", "/r", true));
        assertThat(fixed.standardMounts(false, true)).as("a run without the folder").hasSize(3);
        assertThat(fixed.registerInContainer()).isNull();
    }

    @Test
    void aWorkspaceThatIsTheRootDirectory_mapsWhatLiesBelowItBelowTheFixedPath() {
        RunPaths root = new RunPaths("/", null, null, true);

        assertThat(root.containerPathOf("/")).isEqualTo("/workspace");
        assertThat(root.containerPathOf("/etc/hosts")).isEqualTo("/workspace/etc/hosts");
    }

    /** A writable workspace at the root directory covers the package below it with the read-only mount, too. */
    @Test
    void aWritableRootWorkspace_getsThePackageAgain_readOnly_belowTheWorkspacePath() {
        assertThat(new RunPaths("/", "/pkg", "/s", true).standardMounts(true))
                .contains(new Mount("/pkg", "/workspace/pkg", true));
    }
}
