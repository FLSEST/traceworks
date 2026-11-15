package traceworks.core.engine;

import traceworks.core.context.ExecutionContext;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.StepResult;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BlockExecutorTest {

    @Test
    void execute_mockImplementation_returnsExpectedStepResult() {
        StepResult expectedResult = Mockito.mock(StepResult.class);
        BlockDefinition block = Mockito.mock(BlockDefinition.class);
        ExecutionContext context = Mockito.mock(ExecutionContext.class);

        BlockExecutor executor = new BlockExecutor() {
            @Override
            public StepResult execute(BlockDefinition b, ExecutionContext ctx) {
                return expectedResult;
            }

            @Override
            public Set<String> supportedTypes() {
                return Set.of("test-block");
            }
        };

        StepResult actual = executor.execute(block, context);

        assertThat(actual).isSameAs(expectedResult);
    }

    @Test
    void supports_mockImplementation_returnsTrueForMatchingType() {
        BlockExecutor executor = new BlockExecutor() {
            @Override
            public StepResult execute(BlockDefinition b, ExecutionContext ctx) {
                return null;
            }

            @Override
            public Set<String> supportedTypes() {
                return Set.of("my-block");
            }
        };

        assertThat(executor.supports("my-block")).isTrue();
        assertThat(executor.supports("other-block")).isFalse();
        assertThat(executor.supports(null)).isFalse();
    }

    @Test
    void execute_receivesPassedBlockAndContext() {
        BlockDefinition block = Mockito.mock(BlockDefinition.class);
        ExecutionContext context = Mockito.mock(ExecutionContext.class);
        BlockDefinition[] capturedBlock = new BlockDefinition[1];
        ExecutionContext[] capturedContext = new ExecutionContext[1];

        BlockExecutor executor = new BlockExecutor() {
            @Override
            public StepResult execute(BlockDefinition b, ExecutionContext ctx) {
                capturedBlock[0] = b;
                capturedContext[0] = ctx;
                return null;
            }

            @Override
            public Set<String> supportedTypes() {
                return Set.of();
            }
        };

        executor.execute(block, context);

        assertThat(capturedBlock[0]).isSameAs(block);
        assertThat(capturedContext[0]).isSameAs(context);
    }
}
