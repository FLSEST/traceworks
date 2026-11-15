package traceworks.core.engine;

import traceworks.core.context.ExecutionContext;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.StepResult;

import java.util.Set;

/** Runs the blocks of one or more types. The engine looks the executor of a block up in a {@link BlockTypeRegistry}. */
public interface BlockExecutor {

    /** Runs one block in the run's context and returns its result; the engine copies it into the step. */
    StepResult execute(BlockDefinition block, ExecutionContext context);

    /**
     * The block type(s) this executor runs: the single source of truth for which types exist.
     * {@link BlockTypeRegistry} collects them from all registered executors.
     */
    Set<String> supportedTypes();

    default boolean supports(String blockType) {
        return blockType != null && supportedTypes().contains(blockType);
    }
}
