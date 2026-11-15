package traceworks.core.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * The result of one step: what a block executor returns and what the engine records. Mutable, because executors and
 * the engine fill it in step by step.
 */
public class StepResult {

    private String id;
    private int stepIndex;
    private String input;
    private String output;
    private StepStatus status = StepStatus.PENDING;
    private Instant startedAt;
    private Instant completedAt;
    private Long durationMs;
    private Map<String, Object> metadata = new HashMap<>();

    /** The name of the block that ran this step; {@code null} for an unnamed block. */
    private String blockName;

    /** The type of the block that ran this step, such as {@code shell}. */
    private String blockType;

    public StepResult() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getStepIndex() {
        return stepIndex;
    }

    public void setStepIndex(int stepIndex) {
        this.stepIndex = stepIndex;
    }

    public String getInput() {
        return input;
    }

    public void setInput(String input) {
        this.input = input;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public StepStatus getStatus() {
        return status;
    }

    public void setStatus(StepStatus status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public String getBlockName() {
        return blockName;
    }

    public void setBlockName(String blockName) {
        this.blockName = blockName;
    }

    public String getBlockType() {
        return blockType;
    }

    public void setBlockType(String blockType) {
        this.blockType = blockType;
    }

    /** Builds a step result; status starts as {@link StepStatus#PENDING} and metadata as an empty mutable map. */
    public static final class Builder {

        private String id;
        private int stepIndex;
        private String input;
        private String output;
        private StepStatus status = StepStatus.PENDING;
        private Instant startedAt;
        private Instant completedAt;
        private Long durationMs;
        private Map<String, Object> metadata;
        private boolean metadataSet;
        private String blockName;
        private String blockType;

        private Builder() {
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder stepIndex(int stepIndex) {
            this.stepIndex = stepIndex;
            return this;
        }

        public Builder input(String input) {
            this.input = input;
            return this;
        }

        public Builder output(String output) {
            this.output = output;
            return this;
        }

        public Builder status(StepStatus status) {
            this.status = status;
            return this;
        }

        public Builder startedAt(Instant startedAt) {
            this.startedAt = startedAt;
            return this;
        }

        public Builder completedAt(Instant completedAt) {
            this.completedAt = completedAt;
            return this;
        }

        public Builder durationMs(Long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            this.metadataSet = true;
            return this;
        }

        public Builder blockName(String blockName) {
            this.blockName = blockName;
            return this;
        }

        public Builder blockType(String blockType) {
            this.blockType = blockType;
            return this;
        }

        /** A new step result on every call; unless {@link #metadata} was called, it gets its own empty map. */
        public StepResult build() {
            StepResult result = new StepResult();
            result.setId(id);
            result.setStepIndex(stepIndex);
            result.setInput(input);
            result.setOutput(output);
            result.setStatus(status);
            result.setStartedAt(startedAt);
            result.setCompletedAt(completedAt);
            result.setDurationMs(durationMs);
            result.setMetadata(metadataSet ? metadata : new HashMap<>());
            result.setBlockName(blockName);
            result.setBlockType(blockType);
            return result;
        }
    }
}
