package traceworks.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowDefinitionJsonTest {

    private static final String WORKFLOW_JSON = """
            {
              "id": "7c0f2a9e-3b4d-4e6f-9a1b-2c3d4e5f6a7b",
              "name": "Build and review",
              "description": "Runs the tests, then reviews the result.",
              "blocks": [
                {
                  "type": "shell",
                  "name": "tests",
                  "config": {"command": "mvn -q verify", "checked_command": true},
                  "on_failure": "continue"
                },
                {
                  "type": "simple",
                  "name": "review",
                  "config": {"prompt": "Review: {{ block.tests.output }}"}
                }
              ],
              "instructions": "Answer in English.",
              "model": "example-model",
              "provider": "CLAUDE",
              "effort": "high",
              "metadata": {"gate": {"expected": true}},
              "default_context": {"branch": "main"},
              "requires": ["container.network"],
              "format": "traceworks-workflow",
              "format_version": 1
            }
            """;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void unknownBlockField_survivesTheRoundTrip() throws Exception {
        WorkflowDefinition definition = mapper.readValue(WORKFLOW_JSON, WorkflowDefinition.class);

        JsonNode written = mapper.readTree(mapper.writeValueAsString(definition));

        assertThat(definition.blocks().get(0).unknownFields()).containsExactly(Map.entry("on_failure", "continue"));
        assertThat(written.at("/blocks/0/on_failure").asText()).isEqualTo("continue");
        assertThat(written).isEqualTo(mapper.readTree(WORKFLOW_JSON));
    }

    /** The declaration is kept as written, and a definition without one is written without the key. */
    @Test
    void modelChoices_areReadAsWritten_andSurviveTheRoundTrip() throws Exception {
        String json = WORKFLOW_JSON.replace("\"format_version\": 1", "\"format_version\": 1,"
                + " \"model_choices\": {\"judge\":"
                + " {\"default\": \"a\", \"options\": {\"a\": {\"provider\": \"CLAUDE\", \"model\": \"m\"}}}}");

        WorkflowDefinition definition = mapper.readValue(json, WorkflowDefinition.class);

        assertThat(definition.modelChoices()).containsExactly(Map.entry("judge", Map.of("default", "a", "options",
                Map.of("a", Map.of("provider", "CLAUDE", "model", "m")))));
        assertThat(mapper.readTree(mapper.writeValueAsString(definition))).isEqualTo(mapper.readTree(json));
        WorkflowDefinition without = mapper.readValue(WORKFLOW_JSON, WorkflowDefinition.class);
        assertThat(without.modelChoices()).isEmpty();
        assertThat(mapper.readTree(mapper.writeValueAsString(without)).has("model_choices"))
                .as("a definition without a choice is written without the key").isFalse();
    }

    @Test
    void readsEveryTopLevelField() throws Exception {
        WorkflowDefinition definition = mapper.readValue(WORKFLOW_JSON, WorkflowDefinition.class);

        assertThat(definition.id()).isEqualTo("7c0f2a9e-3b4d-4e6f-9a1b-2c3d4e5f6a7b");
        assertThat(definition.name()).isEqualTo("Build and review");
        assertThat(definition.description()).isEqualTo("Runs the tests, then reviews the result.");
        assertThat(definition.blocks()).extracting(BlockDefinition::getName).containsExactly("tests", "review");
        assertThat(definition.instructions()).isEqualTo("Answer in English.");
        assertThat(definition.model()).isEqualTo("example-model");
        assertThat(definition.provider()).isEqualTo("CLAUDE");
        assertThat(definition.effort()).isEqualTo("high");
        assertThat(definition.metadata()).containsEntry("gate", Map.of("expected", true));
        assertThat(definition.defaultContext()).containsExactly(Map.entry("branch", "main"));
        assertThat(definition.requires()).containsExactly("container.network");
        assertThat(definition.format()).isEqualTo("traceworks-workflow");
        assertThat(definition.formatVersion()).isEqualTo(1);
    }

    @Test
    void absentCollections_readAsEmpty_andAbsentValuesAsNull() throws Exception {
        WorkflowDefinition definition = mapper.readValue("""
                {"name": "minimal"}""", WorkflowDefinition.class);

        assertThat(definition.blocks()).isEmpty();
        assertThat(definition.metadata()).isEmpty();
        assertThat(definition.defaultContext()).isEmpty();
        assertThat(definition.requires()).isEmpty();
        assertThat(definition.id()).isNull();
        assertThat(definition.format()).isNull();
        assertThat(definition.formatVersion()).isNull();
    }

    @Test
    void collectionsAreCopiedAndReadOnly() {
        List<String> requires = new ArrayList<>(List.of("ai.tools"));
        WorkflowDefinition definition = new WorkflowDefinition("id-1", "w", null, List.of(), null, null, null, null,
                Map.of(), Map.of(), requires, "traceworks-workflow", 1);

        requires.add("package.images");

        assertThat(definition.requires()).containsExactly("ai.tools");
        assertThatThrownBy(() -> definition.requires().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> definition.blocks().add(BlockDefinition.builder().type("shell").build()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
