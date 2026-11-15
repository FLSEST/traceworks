package traceworks.core.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The two spellings of a key of {@code workflow.json}: with an underscore, and the earlier camel-case name. */
class WorkflowJsonTest {

    private static final String UNDERSCORE = """
            {
              "format": "traceworks-workflow",
              "format_version": 1,
              "name": "audit",
              "requires": ["ai.model_choice"],
              "default_context": {"branch": "main"},
              "model_choices": {"judge": {"default": "a", "options": {"a": {"provider": "CLAUDE", "model": "m"}}}},
              "metadata": {"audit": {"enabled": true, "key": "k", "category": "c", "report_block": "report",
                                     "schema_version": 1}},
              "blocks": [{"type": "shell", "name": "report", "config": {"command": "true"}}]
            }
            """;

    private static final String CAMEL_CASE = UNDERSCORE.replace("format_version", "formatVersion")
            .replace("default_context", "defaultContext").replace("model_choices", "modelChoices")
            .replace("report_block", "reportBlock").replace("schema_version", "schemaVersion");

    private static WorkflowDefinition read(String json) throws JsonProcessingException {
        return WorkflowJson.read(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void underscoreKeys_areRead() throws Exception {
        WorkflowDefinition definition = read(UNDERSCORE);

        assertThat(definition.formatVersion()).isEqualTo(1);
        assertThat(definition.defaultContext()).containsExactly(Map.entry("branch", "main"));
        assertThat(definition.modelChoices()).containsOnlyKeys("judge");
        assertThat(AuditConfig.problems(definition.metadata())).isEmpty();
        assertThat(AuditConfig.of(definition.metadata())).hasValueSatisfying(
                audit -> assertThat(audit.reportBlock()).isEqualTo("report"));
    }

    /** The earlier names stay valid: a definition in them is the same definition. */
    @Test
    void camelCaseKeys_readAsTheSameDefinition() throws Exception {
        assertThat(CAMEL_CASE).contains("formatVersion", "defaultContext", "modelChoices", "reportBlock",
                "schemaVersion");

        WorkflowDefinition camelCase = read(CAMEL_CASE);
        WorkflowDefinition underscore = read(UNDERSCORE);

        assertThat(camelCase.formatVersion()).isEqualTo(1);
        assertThat(camelCase.defaultContext()).isEqualTo(underscore.defaultContext()).isNotEmpty();
        assertThat(camelCase.modelChoices()).isEqualTo(underscore.modelChoices()).isNotEmpty();
        assertThat(camelCase.metadata()).isEqualTo(underscore.metadata());
        assertThat(AuditConfig.of(camelCase.metadata())).isPresent();
    }

    @ParameterizedTest
    @CsvSource({"format_version,formatVersion", "default_context,defaultContext", "model_choices,modelChoices",
            "report_block,reportBlock", "schema_version,schemaVersion"})
    void eachKey_isReadInEitherSpelling(String underscore, String camelCase) throws Exception {
        WorkflowDefinition mixed = read(UNDERSCORE.replace(underscore, camelCase));
        WorkflowDefinition plain = read(UNDERSCORE);

        assertThat(mixed.formatVersion()).isEqualTo(plain.formatVersion());
        assertThat(mixed.defaultContext()).isEqualTo(plain.defaultContext());
        assertThat(mixed.modelChoices()).isEqualTo(plain.modelChoices());
        assertThat(mixed.metadata()).isEqualTo(plain.metadata());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "\"format_version\": 1|\"formatVersion\": 1|format_version|formatVersion",
            "\"default_context\": {\"branch\": \"main\"}|\"defaultContext\": {}|default_context|defaultContext",
            "\"requires\": [\"ai.model_choice\"]|\"modelChoices\": {}|model_choices|modelChoices",
            "\"report_block\": \"report\"|\"reportBlock\": \"report\"|metadata.audit.report_block"
                    + "|metadata.audit.reportBlock",
            "\"schema_version\": 1|\"schemaVersion\": 1|metadata.audit.schema_version|metadata.audit.schemaVersion"})
    void bothSpellingsOfOneKey_areRefused(String present, String added, String name, String earlier) {
        String both = UNDERSCORE.replace(present, present + ", " + added);

        assertThatThrownBy(() -> read(both)).isInstanceOf(JsonProcessingException.class)
                .hasMessageContaining("duplicate-key-spelling")
                .hasMessageContaining("both " + name + " and " + earlier);
    }

    /** Only the keys of the definition have two spellings: a key of the same letters elsewhere stays as written. */
    @Test
    void otherKeys_stayAsWritten() throws Exception {
        WorkflowDefinition definition = read("""
                {"name": "n", "metadata": {"reportBlock": "x", "gate": {"schemaVersion": 2}},
                 "default_context": {"formatVersion": "v"},
                 "blocks": [{"type": "shell", "config": {"command": "true", "defaultContext": "kept"}}]}""");

        assertThat(definition.metadata()).containsEntry("reportBlock", "x")
                .containsEntry("gate", Map.of("schemaVersion", 2));
        assertThat(definition.defaultContext()).containsExactly(Map.entry("formatVersion", "v"));
        assertThat(definition.blocks().getFirst().getConfig()).containsEntry("defaultContext", "kept");
    }

    @Test
    void anUnknownTopLevelKey_isRefused() {
        assertThatThrownBy(() -> read("{\"name\": \"n\", \"default_contxt\": {}}"))
                .isInstanceOf(JsonProcessingException.class).hasMessageContaining("default_contxt");
    }

    @Test
    void aDefinitionThatIsNoObject_isRefused() {
        assertThatThrownBy(() -> read("[1]")).isInstanceOf(JsonProcessingException.class);
    }
}
