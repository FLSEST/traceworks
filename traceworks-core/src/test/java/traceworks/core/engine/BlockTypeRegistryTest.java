package traceworks.core.engine;

import traceworks.core.context.ExecutionContext;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.StepResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The registry is the single source of truth for the block types a workflow may contain: it is derived from the
 * registered {@link BlockExecutor}s, never from a hand-maintained list.
 */
class BlockTypeRegistryTest {

    @Test
    void knownTypes_collectsEveryExecutorsTypes_sortedAndDeduplicated() {
        BlockTypeRegistry registry = new BlockTypeRegistry(List.of(
                executorFor("shell"), executorFor("simple"), executorFor("shell")));

        assertThat(registry.knownTypes()).containsExactly("shell", "simple");
    }

    @Test
    void isKnown_onlyForRegisteredTypes() {
        BlockTypeRegistry registry = new BlockTypeRegistry(List.of(executorFor("shell")));

        assertThat(registry.isKnown("shell")).isTrue();
        assertThat(registry.isKnown("frobnicate")).isFalse();
        assertThat(registry.isKnown("")).isFalse();
        assertThat(registry.isKnown(null)).isFalse();
    }

    @Test
    void knownTypesAsText_listsTypesForErrorMessages() {
        BlockTypeRegistry registry = new BlockTypeRegistry(List.of(executorFor("simple"), executorFor("shell")));

        assertThat(registry.knownTypesAsText()).isEqualTo("shell, simple");
    }

    @Test
    void knownTypes_isImmutable() {
        BlockTypeRegistry registry = new BlockTypeRegistry(List.of(executorFor("shell")));

        assertThat(registry.knownTypes()).isUnmodifiable();
    }

    @Test
    void executorFor_returnsTheExecutorSupportingTheType() {
        BlockExecutor shell = executorFor("shell");
        BlockExecutor simple = executorFor("simple");
        BlockTypeRegistry registry = new BlockTypeRegistry(List.of(shell, simple));

        assertThat(registry.executorFor("simple")).isSameAs(simple);
        assertThat(registry.executorFor("shell")).isSameAs(shell);
    }

    @Test
    void executorFor_unknownType_failsTheRunWithTheTypeInTheMessage() {
        BlockTypeRegistry registry = new BlockTypeRegistry(List.of(executorFor("shell")));

        assertThatThrownBy(() -> registry.executorFor("frobnicate"))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessage("No executor found for block type: frobnicate");
        assertThatThrownBy(() -> registry.executorFor(null)).isInstanceOf(WorkflowExecutionException.class);
    }

    static BlockExecutor executorFor(String type) {
        return new BlockExecutor() {
            @Override
            public StepResult execute(BlockDefinition block, ExecutionContext context) {
                return null;
            }

            @Override
            public Set<String> supportedTypes() {
                return Set.of(type);
            }
        };
    }
}
