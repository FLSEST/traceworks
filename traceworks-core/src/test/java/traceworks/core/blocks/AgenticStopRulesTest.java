package traceworks.core.blocks;

import traceworks.core.context.ExecutionContext;
import traceworks.core.port.RunEvent;
import traceworks.core.port.RunEvent.AgenticProgress;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgenticStopRulesTest {

    private final AgenticStopRules rules = new AgenticStopRules();
    private final List<RunEvent> events = new ArrayList<>();
    private final ExecutionContext context =
            ExecutionContext.builder().containerId("c").workflowRunId("run-1").events(events::add).build();

    private static AgenticBlockConfig cfg(int maxTotalTokens, int maxRepeated) {
        return AgenticBlockConfig.from(Map.of("goal", "g", "tools", "read", "max_total_tokens", maxTotalTokens,
                "max_repeated_actions", maxRepeated), ExecutionContext.builder().containerId("c").workflowRunId("r").build());
    }

    @Test
    void noRuleHit_returnsNull() {
        AgenticSafetyGuard guard = new AgenticSafetyGuard(3600, 3600, 3);
        String reason = rules.afterAct(context, 0, 1, Instant.now(), "ls", cfg(1_000_000, 3),
                new AgenticTokenTracker(), guard, new StringBuilder());
        assertThat(reason).isNull();
        assertThat(events).isEmpty();
    }

    @Test
    void repeatedAction_stopsWithMessageInOutput() {
        AgenticSafetyGuard guard = new AgenticSafetyGuard(3600, 3600, 2);
        StringBuilder out = new StringBuilder();
        rules.afterAct(context, 0, 1, Instant.now(), "ls", cfg(1_000_000, 2), new AgenticTokenTracker(), guard, out);
        String reason = rules.afterAct(context, 0, 2, Instant.now(), "ls", cfg(1_000_000, 2), new AgenticTokenTracker(), guard, out);
        assertThat(reason).isNotNull();
        assertThat(out.toString()).contains("[ABORTED: Same action repeated 2 times]");
        assertThat(events).singleElement().isInstanceOfSatisfying(AgenticProgress.class,
                progress -> assertThat(progress.type()).isEqualTo("agentic_repeated_actions"));
    }

    @Test
    void iterationTimeout_winsOverBudgetAndRepeat() {
        AgenticSafetyGuard guard = new AgenticSafetyGuard(3600, 1, 1);
        StringBuilder out = new StringBuilder();
        String reason = rules.afterAct(context, 0, 1, Instant.now().minusSeconds(5), "ls", cfg(1, 1),
                new AgenticTokenTracker(), guard, out);
        assertThat(out.toString()).contains("[iteration 1 - TIMEOUT]").doesNotContain("TOKEN BUDGET").doesNotContain("ABORTED");
        assertThat(reason).isNotNull();
    }
}
