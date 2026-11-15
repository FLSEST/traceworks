package traceworks.core.blocks;

import traceworks.core.ai.ToolPolicy;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The request of one AI step call. */
class AiStepRequestTest {

    /**
     * The call runs in a container, which starts in the run's workspace when the request names no directory: an AI
     * actor of a run without a project has none.
     */
    @Test
    void workingDirectory_isOptional_aBlankOneIsNone() {
        for (String missing : Arrays.asList(null, "", "   ", "\t")) {
            assertThat(AiStepRequest.builder().workingDirectory(missing).prompt("task").build().workingDirectory())
                    .as(String.valueOf(missing)).isNull();
        }
    }

    /** A blank prompt is still a prompt (a simple block with {@code command: ""}); only a missing one is refused. */
    @Test
    void prompt_isRequired_butMayBeBlank() {
        assertThatThrownBy(() -> AiStepRequest.builder().workingDirectory("/p").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("prompt is required");
        assertThat(AiStepRequest.builder().workingDirectory("/p").prompt("").build().prompt()).isEmpty();
    }

    @Test
    void optionalFields_defaultToNull() {
        AiStepRequest request = AiStepRequest.builder().workingDirectory("/p").prompt("task").build();

        assertThat(request.stepIndex()).isZero();
        assertThat(request.model()).isNull();
        assertThat(request.systemPrompt()).isNull();
        assertThat(request.timeoutSeconds()).isNull();
        assertThat(request.providerId()).isNull();
        assertThat(request.effort()).isNull();
        assertThat(request.tools()).as("no tools unless the block says so").isEqualTo(ToolPolicy.NONE);
        assertThat(request.writableWorkspace()).isFalse();
    }

    @Test
    void everyField_isSetThroughTheBuilder() {
        AiStepRequest request = AiStepRequest.builder()
                .workingDirectory("/home/user/project")
                .prompt("Analyze this code")
                .stepIndex(3)
                .model("claude-opus-4-6")
                .systemPrompt("You are a senior engineer")
                .timeoutSeconds(600)
                .providerId("CLAUDE")
                .effort("low")
                .tools(ToolPolicy.READ)
                .writableWorkspace(true)
                .build();

        assertThat(request).isEqualTo(new AiStepRequest("/home/user/project", "Analyze this code", 3,
                "claude-opus-4-6", "You are a senior engineer", 600, "CLAUDE", "low", ToolPolicy.READ, true,
                traceworks.core.port.WebAccess.NONE));
    }
}
