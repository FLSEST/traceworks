package traceworks.core.app;

import org.junit.jupiter.api.Test;
import traceworks.core.model.RunStatus;
import traceworks.core.record.RecordedEvent;
import traceworks.core.record.RunView;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static traceworks.core.app.AppFixture.BUILD;
import static traceworks.core.app.AppFixture.WORKSPACE;

/**
 * A run names its project: the operator's word, given with the request and recorded in {@code run.started}. A
 * workflow without an audit may name one; it is recorded and changes nothing else.
 */
class RunProjectTest {

    private final AppFixture fixture = new AppFixture();

    private static StartRequest plain() {
        return new StartRequest(BUILD, Map.of(), WORKSPACE, false, false);
    }

    @Test
    void aRunThatNamesAProject_recordsItInRunStarted() {
        RunResult result = fixture.services().start(plain().inProject("shop"), null, null);

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        List<RecordedEvent> events = fixture.events(result.runId());
        assertThat(events.getFirst().fields().path("project").asText(null)).isEqualTo("shop");
        assertThat(RunView.of(events, false).project()).isEqualTo("shop");
        assertThat(fixture.run(result.runId()).view().orElseThrow())
                .contains("<tr><th>project</th><td>shop</td></tr>");
    }

    @Test
    void aRunWithoutAProject_hasNoSuchFieldInRunStarted() {
        RunResult result = fixture.services().start(plain(), null, null);

        List<RecordedEvent> events = fixture.events(result.runId());
        assertThat(events.getFirst().fields().has("project")).as("no field, not a null").isFalse();
        assertThat(RunView.of(events, false).project()).isNull();
        assertThat(fixture.run(result.runId()).view().orElseThrow()).doesNotContain("<th>project</th>");
    }

    /** The pattern of a register key: the project is a folder of the register, so it must be one path segment. */
    @Test
    void aKeyThatIsNoProjectKey_isRefusedWithTheRequest() {
        for (String bad : List.of("..", "a/b", "Shop", "", " ", ".hidden", "a b", "x".repeat(129))) {
            assertThatThrownBy(() -> plain().inProject(bad)).as(bad).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("project key").hasMessageContaining("[a-z0-9][a-z0-9._-]{0,127}");
        }
        assertThat(plain().inProject("shop-2.web_1").project()).isEqualTo("shop-2.web_1");
        assertThat(plain().inProject("x".repeat(128)).project()).hasSize(128);
        assertThat(plain().project()).isNull();
        assertThat(plain().inProject("shop").inProject(null).project()).isNull();
    }
}
