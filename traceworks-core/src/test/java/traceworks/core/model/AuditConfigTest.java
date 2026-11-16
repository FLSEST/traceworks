package traceworks.core.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code metadata.audit}: understood completely, or named as a problem; a broken one is never ignored silently. */
class AuditConfigTest {

    private static Map<String, Object> audit(Map<String, Object> block) {
        return Map.of("audit", block);
    }

    private static Map<String, Object> complete() {
        Map<String, Object> block = new HashMap<>();
        block.put("enabled", true);
        block.put("key", "platform.health");
        block.put("category", "health");
        block.put("report_block", "report");
        return block;
    }

    @Test
    void of_readsACompleteEnabledBlock_schemaVersionDefaultsToOne() {
        assertThat(AuditConfig.of(audit(complete())))
                .contains(new AuditConfig("platform.health", "health", "report", 1));
        assertThat(AuditConfig.problems(audit(complete()))).isEmpty();
    }

    @Test
    void of_acceptsExplicitSchemaVersionOne() {
        Map<String, Object> block = complete();
        block.put("schema_version", 1L);

        assertThat(AuditConfig.of(audit(block))).map(AuditConfig::schemaVersion).contains(1);
    }

    @Test
    void anUnknownSchemaVersion_isAProblem() {
        Map<String, Object> block = complete();
        block.put("schema_version", 2);

        assertThat(AuditConfig.of(audit(block))).isEmpty();
        assertThat(AuditConfig.problems(audit(block))).singleElement().asString().contains("schema_version")
                .contains("2").contains("1");
    }

    /** A schema version that is no number is not read as 1; Traceworks names it (docs/workflow-format.md). */
    @Test
    void aNonNumericSchemaVersion_isAProblem() {
        Map<String, Object> block = complete();
        block.put("schema_version", "2");

        assertThat(AuditConfig.of(audit(block))).isEmpty();
        assertThat(AuditConfig.problems(audit(block))).singleElement().asString().contains("schema_version");
    }

    @Test
    void enabled_mustBeTheBooleanTrue_andAnythingElseIsNoAudit() {
        Map<String, Object> block = complete();
        block.put("enabled", "true");
        assertThat(AuditConfig.of(audit(block))).isEmpty();
        assertThat(AuditConfig.problems(audit(block))).singleElement().asString().contains("enabled")
                .contains("boolean");

        block.put("enabled", false);
        assertThat(AuditConfig.of(audit(block))).isEmpty();
        assertThat(AuditConfig.problems(audit(block))).as("disabled is no problem").isEmpty();

        block.remove("enabled");
        assertThat(AuditConfig.of(audit(block))).isEmpty();
        assertThat(AuditConfig.problems(audit(block))).isEmpty();
    }

    @Test
    void aMissingOrBlankRequiredField_isAProblem_namingIt() {
        for (String field : new String[] {"key", "category", "report_block"}) {
            Map<String, Object> missing = complete();
            missing.remove(field);
            assertThat(AuditConfig.of(audit(missing))).as(field).isEmpty();
            assertThat(AuditConfig.problems(audit(missing))).as(field).singleElement().asString().contains(field);

            Map<String, Object> blank = complete();
            blank.put(field, " ");
            assertThat(AuditConfig.problems(audit(blank))).as(field + " blank").singleElement().asString()
                    .contains(field);
        }
    }

    /** A number is not turned into text; Traceworks wants strings, so a mistake is named. */
    @Test
    void aNonStringValue_isAProblem() {
        Map<String, Object> block = complete();
        block.put("report_block", 3);

        assertThat(AuditConfig.of(audit(block))).isEmpty();
        assertThat(AuditConfig.problems(audit(block))).singleElement().asString().contains("report_block")
                .contains("string");
    }

    @Test
    void theKey_isAPathSegment() {
        for (String ok : List.of("a", "health", "platform.health", "self-audit_1", "x".repeat(128))) {
            Map<String, Object> block = complete();
            block.put("key", ok);
            assertThat(AuditConfig.problems(audit(block))).as(ok).isEmpty();
        }
        for (String bad : List.of("Health", "a/b", ".hidden", "-dash", "a b", "x".repeat(129), "\u00e4")) {
            Map<String, Object> block = complete();
            block.put("key", bad);
            assertThat(AuditConfig.of(audit(block))).as(bad).isEmpty();
            assertThat(AuditConfig.problems(audit(block))).as(bad).singleElement().asString().contains("key")
                    .contains(AuditConfig.KEY);
        }
    }

    @Test
    void withoutAnAuditBlock_thereIsNoConfig_andNoProblem() {
        assertThat(AuditConfig.of(null)).isEmpty();
        assertThat(AuditConfig.of(Map.of())).isEmpty();
        assertThat(AuditConfig.problems(Map.of())).isEmpty();
        assertThat(AuditConfig.of(Map.of("audit", "enabled"))).isEmpty();
        assertThat(AuditConfig.problems(Map.of("audit", "enabled"))).singleElement().asString()
                .contains("metadata.audit must be an object");
    }

    @Test
    void unknownKeysOfTheBlock_areIgnored() {
        Map<String, Object> block = complete();
        block.put("promptBlock", "coach");
        block.put("todoMinSeverity", "HIGH");

        assertThat(AuditConfig.of(audit(block))).isPresent();
        assertThat(AuditConfig.problems(audit(block))).isEmpty();
    }
}
