package traceworks.it;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The frame of every test of the suite: a sandbox of its own ({@link Traceworks}) in a temporary directory that JUnit
 * removes after the test, which fails when something in it cannot be removed. After each test, whatever it did:
 *
 * <ul>
 *   <li>no container and no network of any run of the sandbox is left, child runs included ({@link Containers});
 *       </li>
 *   <li>every finished run (one with a {@code manifest.json}) passes {@code traceworks verify}, and its hash chain,
 *       checked by hand as docs/events.md describes it, ends at the head its manifest names.</li>
 * </ul>
 */
abstract class IntegrationTest {

    @TempDir
    Path sandbox;

    /** The sandbox of the test: its home, workspace and fake user home, and the jar to run there. */
    Traceworks traceworks;

    @BeforeEach
    void newSandbox() {
        traceworks = Traceworks.in(ItEnvironment.get(), sandbox);
    }

    @AfterEach
    void leavesNothingBehind_andEveryFinishedRunVerifies() {
        if (traceworks == null) {
            return;   // the environment was not ready: that failure is the test's
        }
        Set<String> runIds = new LinkedHashSet<>();
        traceworks.folders().forEach(folder -> {
            runIds.add(folder.id());
            runIds.addAll(folder.runIds());
        });
        Containers.assertNoneLeft(traceworks.environment().runtime(), runIds);
        Containers.assertNoNetworkLeft(traceworks.environment().runtime(), runIds);
        for (RunFolder folder : traceworks.folders()) {
            if (!folder.hasManifest()) {
                continue;
            }
            Result verify = traceworks.exec("verify", folder.id());
            assertThat(verify.code()).as(verify.describe()).isZero();
            assertThat(verify.out()).as(verify.describe()).contains("verified");
            assertThat(folder.chainHead()).as("the chain of run %s, checked by hand", folder.id())
                    .isEqualTo(folder.manifest().path("head").asText());
        }
    }
}
