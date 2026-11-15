package traceworks.core.blocks;

import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.EventLogException;
import traceworks.core.port.RunEvent;
import traceworks.core.port.RunEvent.AgenticProgress;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The agentic loop reports its phases as {@link AgenticProgress} run events.
 */
class AgenticEventsTest {

    @Test
    void emit_sendsTypeStepIndexAndData_inOrder() {
        List<RunEvent> events = new ArrayList<>();
        ExecutionContext context = ExecutionContext.builder().workflowRunId("run-1").events(events::add).build();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("iteration", 1);
        data.put("reasoning", "think");

        AgenticEvents.emit(context, 2, "agentic_reasoned", data);

        assertThat(events).singleElement().isInstanceOfSatisfying(AgenticProgress.class, progress -> {
            assertThat(progress.stepIndex()).isEqualTo(2);
            assertThat(progress.type()).isEqualTo("agentic_reasoned");
            assertThat(progress.data().keySet()).containsExactly("iteration", "reasoning");
            assertThat(progress.data()).isUnmodifiable();
        });
    }

    /**
     * The events are the run's record, so a failed write is never hidden: it reaches the engine, which stops the
     * run fail-closed.
     */
    @Test
    void emit_eventLogFailure_propagates() {
        ExecutionContext context = ExecutionContext.builder().workflowRunId("run-1").events(event -> {
            throw new EventLogException(new IOException("disk full"));
        }).build();

        assertThatThrownBy(() -> AgenticEvents.emit(context, 0, "agentic_started", Map.of()))
                .isInstanceOf(EventLogException.class);
    }
}
