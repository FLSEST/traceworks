package traceworks.core.testing;

import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What a container test does when the machine lacks what it needs: it fails in a build that asked for it. */
class ContainerPreconditionTest {

    @Test
    void aMetPrecondition_doesNothing_requiredOrNot() {
        assertThatCode(() -> ContainerPrecondition.check(true, true, "the shell image is not built", "build it"))
                .doesNotThrowAnyException();
        assertThatCode(() -> ContainerPrecondition.check(false, true, "the shell image is not built", "build it"))
                .doesNotThrowAnyException();
    }

    @Test
    void inABuildThatAskedForContainerTests_aMissingPreconditionFails_andSaysWhatToDo() {
        assertThatThrownBy(() -> ContainerPrecondition.check(true, false, "the shell image x:1 is not built",
                "build it"))
                .isInstanceOf(AssertionError.class)
                .isNotInstanceOf(TestAbortedException.class)
                .hasMessage("Container tests were asked for (-Pcontainers or -Pit), but the shell image x:1 is not"
                        + " built. To fix: build it");
    }

    @Test
    void elsewhere_aMissingPreconditionSkipsTheTest_withTheSameWords() {
        assertThatThrownBy(() -> ContainerPrecondition.check(false, false, "no container runtime works", "start one"))
                .isInstanceOf(TestAbortedException.class)
                .hasMessageContaining("no container runtime works. To fix: start one");
    }

    @Test
    void theBuildAsksThroughASystemProperty_whichOnlyTheWordTrueSets() {
        assertThat(ContainerPrecondition.required("true")).isTrue();
        assertThat(ContainerPrecondition.required("false")).isFalse();
        assertThat(ContainerPrecondition.required("")).isFalse();
        assertThat(ContainerPrecondition.required(null)).isFalse();
        assertThat(ContainerPrecondition.PROPERTY).isEqualTo("traceworks.containers.required");
    }

    @Test
    void theHints_nameTheCommands() {
        assertThat(ContainerPrecondition.START_A_RUNTIME).contains("docker", "podman");
        assertThat(ContainerPrecondition.BUILD_THE_IMAGES)
                .contains("java -jar traceworks-cli/target/traceworks.jar images build");
    }
}
