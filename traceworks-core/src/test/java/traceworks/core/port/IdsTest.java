package traceworks.core.port;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The one rule for a run id that names a folder or a file: no path, no option, nothing hidden. */
class IdsTest {

    @Test
    void isRunId_acceptsWhatTheStoreMakes_andNothingThatIsAPathOrAnOption() {
        assertThat(Ids.isRunId("c0c0f5eb-0000-4000-8000-000000000001")).isTrue();
        assertThat(Ids.isRunId("run-1")).isTrue();
        assertThat(Ids.isRunId("a.b_c")).isTrue();
        for (String bad : List.of("", "../x", "a/b", "..", ".hidden", "-x", "_x", "a b", "a\nb")) {
            assertThat(Ids.isRunId(bad)).as(bad).isFalse();
        }
        assertThat(Ids.isRunId(null)).isFalse();
    }
}
