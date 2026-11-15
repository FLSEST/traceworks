package traceworks.core.blocks;

import traceworks.core.context.ExecutionContext;
import traceworks.core.port.RunEvent.AgenticProgress;

import java.util.Map;

/**
 * Reports the phases of an agentic block ({@code agentic_started}, {@code agentic_reasoned}, {@code agentic_acted},
 * ...) as {@link AgenticProgress} events of the run. They are part of the run's record, so a failed write is not
 * swallowed: it propagates, and the engine stops the run.
 */
final class AgenticEvents {

    private AgenticEvents() {
    }

    static void emit(ExecutionContext context, int stepIndex, String type, Map<String, Object> data) {
        context.getEvents().emit(new AgenticProgress(stepIndex, type, data));
    }
}
