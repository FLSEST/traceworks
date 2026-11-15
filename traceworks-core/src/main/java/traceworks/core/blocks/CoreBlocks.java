package traceworks.core.blocks;

import traceworks.core.engine.BlockTypeRegistry;

import java.nio.file.Path;
import java.util.List;

/** The block types of the core, as the registry an engine runs them with. */
public final class CoreBlocks {

    private CoreBlocks() {
    }

    /**
     * The registry of the thirteen core block types: {@code shell}, {@code simple}, {@code agentic}, {@code retry},
     * {@code conditional}, {@code switch}, {@code while}, {@code foreach}, {@code approval}, {@code human-input},
     * {@code sub-workflow}, {@code compress} and {@code compress-ai}. The executors take the run's ports from the
     * execution context, so one registry serves every run.
     *
     * @param toolCacheDirectory where the tool mode of {@code shell} keeps a persistent cache directory per image
     *                           ({@code cache_dir}); {@code null} refuses the blocks that ask for one
     */
    public static BlockTypeRegistry registry(Path toolCacheDirectory) {
        return new BlockTypeRegistry(List.of(
                new ShellBlockExecutor(toolCacheDirectory),
                new SimpleBlockExecutor(),
                new AgenticBlockExecutor(),
                new RetryBlockExecutor(),
                new ConditionalBlockExecutor(),
                new SwitchBlockExecutor(),
                new WhileBlockExecutor(),
                new ForEachBlockExecutor(),
                new ApprovalBlockExecutor(),
                new HumanInputBlockExecutor(),
                new SubWorkflowBlockExecutor(),
                new CompressBlockExecutor(),
                new CompressAiBlockExecutor()));
    }
}
