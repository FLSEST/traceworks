package traceworks.core.blocks;

import traceworks.core.ai.ToolPolicy;
import traceworks.core.context.ExecutionContext;
import traceworks.core.engine.WorkflowExecutionException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgenticBlockConfigTest {

    private ExecutionContext buildContext() {
        return ExecutionContext.builder()
                .containerId("test-container")
                .workflowRunId("run-1")
                .build();
    }

    @Test
    void from_fullConfig_parsesAllFields() {
        Map<String, Object> config = new HashMap<>();
        config.put("goal", "Fix the tests");
        config.put("tools", "read");
        config.put("success_criteria", "All tests pass");
        config.put("max_iterations", 5);
        config.put("iteration_timeout_seconds", 500);
        config.put("total_timeout_seconds", 3000);
        config.put("max_total_tokens", 100000);
        config.put("max_repeated_actions", 4);
        config.put("require_approval", true);
        config.put("timeout_seconds", 60);

        AgenticBlockConfig cfg = AgenticBlockConfig.from(config, buildContext());

        assertThat(cfg.goal()).isEqualTo("Fix the tests");
        assertThat(cfg.successCriteria()).isEqualTo("All tests pass");
        assertThat(cfg.maxIterations()).isEqualTo(5);
        // iteration_timeout_seconds stays at 500 because it's already > minIterationTimeout (60*2+60=180)
        assertThat(cfg.iterationTimeoutSeconds()).isEqualTo(500);
        assertThat(cfg.totalTimeoutSeconds()).isEqualTo(3000);
        assertThat(cfg.maxTotalTokens()).isEqualTo(100000);
        assertThat(cfg.maxRepeatedActions()).isEqualTo(4);
        assertThat(cfg.requireApproval()).isTrue();
        assertThat(cfg.callTimeoutSeconds()).isEqualTo(60);
    }

    @Test
    void from_autoAlignsIterationTimeout_whenCallTimeoutLarger() {
        Map<String, Object> config = new HashMap<>();
        config.put("goal", "Test auto-alignment");
        config.put("tools", "read");
        config.put("timeout_seconds", 600);
        config.put("iteration_timeout_seconds", 300); // too small for timeout_seconds=600

        AgenticBlockConfig cfg = AgenticBlockConfig.from(config, buildContext());

        // minIterationTimeout = 600*2 + 60 = 1260
        assertThat(cfg.iterationTimeoutSeconds()).isEqualTo(1260);
        assertThat(cfg.callTimeoutSeconds()).isEqualTo(600);
    }

    @Test
    void from_minimalConfig_usesDefaults() {
        Map<String, Object> config = new HashMap<>();
        config.put("goal", "Do something");
        config.put("tools", "read");

        AgenticBlockConfig cfg = AgenticBlockConfig.from(config, buildContext());

        assertThat(cfg.goal()).isEqualTo("Do something");
        assertThat(cfg.successCriteria()).isEmpty();
        assertThat(cfg.maxIterations()).isEqualTo(10);
        assertThat(cfg.iterationTimeoutSeconds()).isEqualTo(300);
        assertThat(cfg.totalTimeoutSeconds()).isEqualTo(1800);
        assertThat(cfg.maxTotalTokens()).isEqualTo(200_000);
        assertThat(cfg.maxRepeatedActions()).isEqualTo(3);
        assertThat(cfg.requireApproval()).isFalse();
        assertThat(cfg.callTimeoutSeconds()).isNull();
    }

    /** An agentic block must say what its model may do: without tools its actions change nothing. */
    @Test
    void from_missingTools_throws() {
        Map<String, Object> config = new HashMap<>(Map.of("goal", "Fix it"));

        assertThatThrownBy(() -> AgenticBlockConfig.from(config, buildContext()))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("tools");
    }

    @Test
    void from_unknownTools_throws() {
        Map<String, Object> config = new HashMap<>(Map.of("goal", "Fix it", "tools", "everything"));

        assertThatThrownBy(() -> AgenticBlockConfig.from(config, buildContext()))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("none, read, write or shell");
    }

    @Test
    void from_toolsAndWritableWorkspace_areRead() {
        AgenticBlockConfig cfg = AgenticBlockConfig.from(new HashMap<>(Map.of("goal", "Fix it", "tools", "shell",
                "workspace_writable", true)), buildContext());
        AgenticBlockConfig readOnly = AgenticBlockConfig.from(new HashMap<>(Map.of("goal", "Fix it", "tools",
                "write")), buildContext());

        assertThat(cfg.tools()).isEqualTo(ToolPolicy.SHELL);
        assertThat(cfg.writableWorkspace()).isTrue();
        assertThat(readOnly.tools()).isEqualTo(ToolPolicy.WRITE);
        assertThat(readOnly.writableWorkspace()).as("read-only unless the block says so").isFalse();
    }

    @Test
    void from_missingGoal_throws() {
        Map<String, Object> config = new HashMap<>();

        assertThatThrownBy(() -> AgenticBlockConfig.from(config, buildContext()))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("goal");
    }

    /**
     * Timeouts that already leave room for REASON and ACT are not aligned and log nothing. The values are ones a
     * workflow generator writes into every agentic block (900/1860/3600), which once raised the iteration timeout and
     * logged a warning for each block.
     */
    @Test
    void from_timeoutsThatFit_needNoAlignment_andLogNoWarning() {
        Map<String, Object> config = new HashMap<>();
        config.put("goal", "Generated block");
        config.put("tools", "read");
        config.put("timeout_seconds", 900);
        config.put("iteration_timeout_seconds", 1860);
        config.put("total_timeout_seconds", 3600);
        Logger logger = Logger.getLogger(AgenticBlockConfig.class.getName());
        List<LogRecord> logged = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        AgenticBlockConfig cfg;
        try {
            cfg = AgenticBlockConfig.from(config, buildContext());
            // the handler sees a warning when one is logged
            AgenticBlockConfig.from(Map.of("goal", "g", "tools", "read", "timeout_seconds", 600,
                            "iteration_timeout_seconds", 300),
                    buildContext());
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
        }

        assertThat(cfg.iterationTimeoutSeconds()).isEqualTo(1860);
        assertThat(cfg.totalTimeoutSeconds()).isEqualTo(3600);
        assertThat(logged).as("only the second config is aligned").singleElement()
                .satisfies(record -> assertThat(record.getMessage()).startsWith("Auto-aligning"));
    }

    @Test
    void from_resolvesTemplateVariables() {
        ExecutionContext ctx = buildContext();
        ctx.setVariable("target", "unit tests");

        Map<String, Object> config = new HashMap<>();
        config.put("goal", "Fix {{ target }}");
        config.put("tools", "read");
        config.put("success_criteria", "{{ target }} pass");

        AgenticBlockConfig cfg = AgenticBlockConfig.from(config, ctx);

        assertThat(cfg.goal()).isEqualTo("Fix unit tests");
        assertThat(cfg.successCriteria()).isEqualTo("unit tests pass");
    }
}
