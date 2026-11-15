package traceworks.adapters.pkg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a path lies in a directory that steps write, judged by its real path also when it does not exist yet: a
 * step may create it a moment later, and git would then read what the kernel resolves. The refusals that rest on it
 * are in {@link GitSourceCheckoutTest} and {@link FilePackageLoaderTest}.
 */
class CheckoutsDirectoryTest {

    @TempDir
    Path temp;

    /** A path below a link that does not exist yet is judged by the link's target, not by the link's place. */
    @Test
    void aPathThatDoesNotExist_belowALinkIntoTheDirectory_liesInIt() throws IOException {
        Path workspaces = Files.createDirectories(temp.resolve("workspaces"));
        Path alias = Files.createSymbolicLink(temp.resolve("alias"), workspaces);
        CheckoutsDirectory directory = new CheckoutsDirectory(workspaces);

        assertThat(directory.holds(alias.resolve("run-9/repo"))).isTrue();
        assertThat(directory.holds(temp.resolve("elsewhere/run-9/repo"))).isFalse();
    }

    /**
     * {@code ..} after a part that does not exist goes up from that part, as the kernel goes once the part exists:
     * {@code not/there/../../workspaces/run-0} is {@code workspaces/run-0}.
     */
    @Test
    void dotDotAfterAPartThatDoesNotExist_goesUp_asTheKernelWill() throws IOException {
        Path workspaces = Files.createDirectories(temp.resolve("workspaces"));
        CheckoutsDirectory directory = new CheckoutsDirectory(workspaces);

        assertThat(directory.holds(temp.resolve("not/there/../../workspaces/run-0"))).isTrue();
        assertThat(directory.holds(workspaces.resolve("not/there/../../../elsewhere"))).isFalse();
    }
}
