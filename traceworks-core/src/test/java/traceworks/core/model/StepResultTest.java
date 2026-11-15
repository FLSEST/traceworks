package traceworks.core.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StepResultTest {

    @Test
    void builder_defaultsStatusToPending_andMetadataToAnEmptyMutableMap() {
        StepResult result = StepResult.builder().build();

        assertThat(result.getStatus()).isEqualTo(StepStatus.PENDING);
        assertThat(result.getMetadata()).isNotNull().isEmpty();
        result.getMetadata().put("detail", "extra info");
        assertThat(result.getMetadata()).containsExactly(Map.entry("detail", "extra info"));
    }

    @Test
    void builder_leavesEverythingElseUnset() {
        StepResult result = StepResult.builder().build();

        assertThat(result.getId()).isNull();
        assertThat(result.getInput()).isNull();
        assertThat(result.getOutput()).isNull();
        assertThat(result.getStartedAt()).isNull();
        assertThat(result.getCompletedAt()).isNull();
        assertThat(result.getDurationMs()).isNull();
        assertThat(result.getBlockName()).isNull();
        assertThat(result.getBlockType()).isNull();
    }

    @Test
    void noArgsConstructor_hasTheSameDefaultsAsTheBuilder() {
        StepResult result = new StepResult();

        assertThat(result.getStatus()).isEqualTo(StepStatus.PENDING);
        assertThat(result.getMetadata()).isNotNull().isEmpty();
        assertThat(result.getId()).isNull();
        assertThat(result.getOutput()).isNull();
    }

    @Test
    void builder_setsEveryField() {
        Instant started = Instant.parse("2024-06-15T10:00:00Z");
        Instant completed = Instant.parse("2024-06-15T10:05:00Z");
        Map<String, Object> metadata = new HashMap<>(Map.of("key", "value"));

        StepResult result = StepResult.builder()
                .id("step-id")
                .stepIndex(2)
                .input("input text")
                .output("output text")
                .status(StepStatus.COMPLETED)
                .startedAt(started)
                .completedAt(completed)
                .durationMs(300000L)
                .metadata(metadata)
                .blockName("tests")
                .blockType("shell")
                .build();

        assertThat(result.getId()).isEqualTo("step-id");
        assertThat(result.getStepIndex()).isEqualTo(2);
        assertThat(result.getInput()).isEqualTo("input text");
        assertThat(result.getOutput()).isEqualTo("output text");
        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getStartedAt()).isEqualTo(started);
        assertThat(result.getCompletedAt()).isEqualTo(completed);
        assertThat(result.getDurationMs()).isEqualTo(300000L);
        assertThat(result.getMetadata()).isSameAs(metadata);
        assertThat(result.getBlockName()).isEqualTo("tests");
        assertThat(result.getBlockType()).isEqualTo("shell");
    }

    @Test
    void setters_replaceTheValues() {
        StepResult result = new StepResult();

        result.setId("s1");
        result.setStepIndex(3);
        result.setInput("in");
        result.setOutput("out");
        result.setStatus(StepStatus.FAILED);
        result.setDurationMs(5000L);
        result.setBlockName("build");
        result.setBlockType("shell");

        assertThat(result.getId()).isEqualTo("s1");
        assertThat(result.getStepIndex()).isEqualTo(3);
        assertThat(result.getInput()).isEqualTo("in");
        assertThat(result.getOutput()).isEqualTo("out");
        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getDurationMs()).isEqualTo(5000L);
        assertThat(result.getBlockName()).isEqualTo("build");
        assertThat(result.getBlockType()).isEqualTo("shell");
    }
}
