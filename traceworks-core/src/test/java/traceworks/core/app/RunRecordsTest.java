package traceworks.core.app;

import traceworks.core.record.RecordedEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunRecordsTest {

    private static RecordedEvent event(int seq, String type, String extra) {
        String line = "{\"seq\":" + seq + ",\"at\":\"2026-11-04T10:00:00Z\",\"type\":\"" + type
                + "\",\"run\":\"r1\",\"prev\":\"x\"" + extra + "}";
        return RecordedEvent.parse(line.getBytes(StandardCharsets.UTF_8));
    }

    private static List<RecordedEvent> record(String finishedFields) {
        List<RecordedEvent> events = new ArrayList<>();
        events.add(event(0, "run.started", ",\"workflow\":{},\"package\":{}"));
        events.add(event(1, "run.finished", finishedFields));
        return events;
    }

    @Test
    void anUnknownStatus_isADamagedRecord() {
        assertThatThrownBy(() -> RunRecords.view("r1", record(",\"status\":\"BOGUS\""), false))
                .isInstanceOf(RecordDamagedException.class).hasMessageContaining("r1");
    }

    @Test
    void aMissingRequiredStatus_isADamagedRecord() {
        assertThatThrownBy(() -> RunRecords.view("r1", record(""), false))
                .isInstanceOf(RecordDamagedException.class);
    }

    private static List<RecordedEvent> recordWith(String type, String extra) {
        return List.of(event(0, "run.started", ",\"workflow\":{\"id\":\"w\"},\"package\":{}"),
                event(1, type, extra));
    }

    @Test
    void aStepStartedWithoutItsStep_isADamagedRecord() {
        assertThatThrownBy(() -> RunRecords.view("r1", recordWith("step.started", ",\"position\":0,\"block_type\":\"shell\""), false))
                .isInstanceOf(RecordDamagedException.class).hasMessageContaining("step.started").hasMessageContaining("step");
    }

    @Test
    void aRunStartedWithoutItsWorkflowAndPackage_isADamagedRecord() {
        assertThatThrownBy(() -> RunRecords.view("r1", List.of(event(0, "run.started", "")), false))
                .isInstanceOf(RecordDamagedException.class).hasMessageContaining("run.started")
                .hasMessageContaining("workflow");
    }

    @Test
    void aStepStartedWithAStepThatIsNoNumber_isADamagedRecord() {
        assertThatThrownBy(() -> RunRecords.view("r1", recordWith("step.started",
                ",\"step\":\"one\",\"position\":0,\"block_type\":\"shell\""), false))
                .isInstanceOf(RecordDamagedException.class);
    }

    @Test
    void aStepFinishedWithoutItsDuration_andACheckpointWithoutItsPosition_areDamagedRecords() {
        assertThatThrownBy(() -> RunRecords.view("r1", recordWith("step.finished", ",\"step\":0,\"status\":\"COMPLETED\""), false))
                .isInstanceOf(RecordDamagedException.class).hasMessageContaining("duration_ms");
        assertThatThrownBy(() -> RunRecords.view("r1", recordWith("run.checkpoint", ",\"step\":0"), false))
                .isInstanceOf(RecordDamagedException.class).hasMessageContaining("next_position");
    }

    /** The AI events need their step: the view hangs the call on it. */
    @Test
    void anAiEventWithoutItsStep_isADamagedRecord() {
        for (String type : List.of("ai.request", "ai.tool_call", "ai.response")) {
            assertThatThrownBy(() -> RunRecords.view("r1", recordWith(type, ",\"item\":null"), false)).as(type)
                    .isInstanceOf(RecordDamagedException.class).hasMessageContaining(type).hasMessageContaining("step");
        }
    }

    @Test
    void viewBuilderBug_isAnInternalError_notADamagedRecord() {
        List<RecordedEvent> broken = Arrays.asList((RecordedEvent) null);   // not a parse error: a bug

        assertThatThrownBy(() -> RunRecords.view("r1", broken, false))
                .isInstanceOf(NullPointerException.class);
    }
}
