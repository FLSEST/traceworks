package traceworks.core.engine;

import traceworks.core.record.RunSnapshotJson;
import traceworks.core.context.ExecutionContext;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.RunEvent;
import traceworks.core.testing.RecordingRunEvents;
import traceworks.core.testing.SnapshotTexts;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The secrets of a run reach the blocks through their context, read-only: they are no variable, no placeholder
 * resolves them, and no checkpoint holds them.
 */
class RunSecretsTest {

    private static final String VALUE = "deploy-token-value";

    @Test
    void theContextHasTheRunsSecrets_readOnly_butNoVariableAndNoCheckpointHoldsThem() {
        AtomicReference<ExecutionContext> seen = new AtomicReference<>();
        WorkflowEngine engine = new WorkflowEngine(new BlockTypeRegistry(List.of(new BlockExecutor() {
            @Override
            public StepResult execute(BlockDefinition block, ExecutionContext context) {
                seen.set(context);
                return StepResult.builder().stepIndex(context.nextStepIndex()).status(StepStatus.COMPLETED)
                        .output(context.resolveTemplate("{{ DEPLOY_TOKEN }}")).build();
            }

            @Override
            public Set<String> supportedTypes() {
                return Set.of("shell");
            }
        })));
        RecordingRunEvents events = new RecordingRunEvents();
        WorkflowDefinition definition = new WorkflowDefinition("wf", "Wf", null, List.of(BlockDefinition.builder()
                .type("shell").name("deploy").config(new HashMap<>(Map.of("command", "./deploy",
                        "secrets", List.of("DEPLOY_TOKEN")))).build()), null, null, null, null, null, null, null,
                null, null);

        RunOutcome outcome = engine.run(definition, Map.of(), RunOptions.builder().runId("run-1").events(events)
                .checkpoints(true).secrets(Map.of("DEPLOY_TOKEN", VALUE)).build());

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        ExecutionContext context = seen.get();
        assertThat(context.getSecrets()).containsExactly(Map.entry("DEPLOY_TOKEN", VALUE));
        assertThatThrownBy(() -> context.getSecrets().put("X", "y")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(context.getVariables()).doesNotContainValue(VALUE).doesNotContainKey("DEPLOY_TOKEN");
        assertThat(outcome.steps().get(0).getOutput()).as("no placeholder resolves a secret")
                .isEqualTo("{{ DEPLOY_TOKEN }}");
        assertThat(events.all()).filteredOn(e -> e instanceof RunEvent.Checkpoint).isNotEmpty().allSatisfy(e -> {
            SnapshotTexts texts = new SnapshotTexts();
            byte[] state = RunSnapshotJson.write(((RunEvent.Checkpoint) e).snapshot(), texts);
            assertThat(new String(state, StandardCharsets.UTF_8)).doesNotContain(VALUE);
            assertThat(texts.blobs().all()).as("nor do the texts it refers to")
                    .allSatisfy(text -> assertThat(new String(text, StandardCharsets.UTF_8)).doesNotContain(VALUE));
        });
    }

    @Test
    void aChildsOptions_keepTheSecrets_andToStringNeverShowsAValue() {
        RunOptions options = RunOptions.builder().runId("run-1").secrets(Map.of("DEPLOY_TOKEN", VALUE)).build();

        assertThat(options.toBuilder().runId("child").build().secrets()).containsEntry("DEPLOY_TOKEN", VALUE);
        assertThat(options.toString()).contains("DEPLOY_TOKEN").doesNotContain(VALUE);
        assertThat(RunOptions.builder().runId("run-2").build().secrets()).isEmpty();
    }
}
