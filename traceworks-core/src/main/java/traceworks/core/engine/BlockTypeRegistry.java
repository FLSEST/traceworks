package traceworks.core.engine;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The block types the engine can execute, collected from the registered {@link BlockExecutor}s, never a
 * hand-maintained list. The engine looks up the executor of each block here ({@link #executorFor}); the run admission
 * refuses unknown types with {@link #isKnown}, so a typo fails before a run instead of in the middle of it.
 *
 * <p>The registry of the core block types is {@code CoreBlocks.registry} in the block package: the engine does not
 * depend on the block executors, they depend on it.
 */
public class BlockTypeRegistry {

    private final List<BlockExecutor> executors;
    private final Set<String> knownTypes;

    /** @param executors the executors, read once: the registry keeps its own copy of the list */
    public BlockTypeRegistry(List<BlockExecutor> executors) {
        this.executors = List.copyOf(executors);
        Set<String> collected = new TreeSet<>();
        for (BlockExecutor executor : this.executors) {
            Collection<String> types = executor.supportedTypes();
            if (types != null) {
                collected.addAll(types);
            }
        }
        this.knownTypes = Collections.unmodifiableSet(collected);
    }

    public boolean isKnown(String blockType) {
        return blockType != null && knownTypes.contains(blockType);
    }

    /** All executable block types, sorted. */
    public Set<String> knownTypes() {
        return knownTypes;
    }

    /**
     * The executor that runs blocks of {@code blockType}.
     *
     * @throws WorkflowExecutionException when no registered executor supports the type
     */
    public BlockExecutor executorFor(String blockType) {
        return executors.stream()
                .filter(e -> e.supports(blockType))
                .findFirst()
                .orElseThrow(() -> new WorkflowExecutionException(
                        "No executor found for block type: " + blockType));
    }

    /** The known types for error messages: {@code "agentic, approval, ..."}. */
    public String knownTypesAsText() {
        return String.join(", ", knownTypes());
    }
}
