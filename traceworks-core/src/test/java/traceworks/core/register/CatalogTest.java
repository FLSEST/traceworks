package traceworks.core.register;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code catalog.json} of a package: the rule keys a report may use, each with a severity range and maybe a fixed subject. */
class CatalogTest {

    private static Catalog parse(String json) {
        return Catalog.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private static ReportedFinding finding(String ruleKey, Severity severity) {
        return new ReportedFinding(ruleKey, "s", severity, "t", null, null, List.of());
    }

    @Test
    void parse_readsRangesAndFixedSubjects() {
        Catalog catalog = parse("""
                {"format":1,"rules":{
                  "dep.outdated":{"severity":{"min":"LOW","max":"HIGH"}},
                  "size.total":{"severity":{"min":"MEDIUM","max":"MEDIUM"},"subject":"pom.xml"}}}""");

        assertThat(catalog.rules()).containsKeys("dep.outdated", "size.total");
        assertThat(catalog.fixedSubject("size.total")).contains("pom.xml");
        assertThat(catalog.fixedSubject("dep.outdated")).isEmpty();
        assertThat(catalog.rules().get("dep.outdated")).isEqualTo(new Catalog.Rule(Severity.LOW, Severity.HIGH, null));
    }

    @Test
    void aRuleWithoutASeverityRange_allowsEverySeverity() {
        Catalog catalog = parse("{\"format\":1,\"rules\":{\"x\":{}}}");

        assertThat(catalog.problems(List.of(finding("x", Severity.CRITICAL), finding("x", Severity.LOW)))).isEmpty();
    }

    @Test
    void anUnknownRuleKey_isAProblem() {
        Catalog catalog = parse("{\"format\":1,\"rules\":{\"known\":{}}}");

        assertThat(catalog.problems(List.of(finding("known", Severity.LOW), finding("other", Severity.LOW))))
                .singleElement().asString().contains("other").contains("not in catalog.json");
    }

    @Test
    void aSeverityOutOfRange_isAProblem() {
        Catalog catalog = parse("{\"format\":1,\"rules\":{\"r\":{\"severity\":{\"min\":\"LOW\",\"max\":\"MEDIUM\"}}}}");

        assertThat(catalog.problems(List.of(finding("r", Severity.HIGH)))).singleElement().asString()
                .contains("r").contains("HIGH").contains("LOW").contains("MEDIUM");
        assertThat(catalog.problems(List.of(finding("r", Severity.MEDIUM)))).isEmpty();
    }

    @Test
    void anInvalidCatalog_isRefused_namingTheProblem() {
        for (String bad : List.of("not json", "[]", "{\"rules\":{}}", "{\"format\":2,\"rules\":{}}",
                "{\"format\":1}", "{\"format\":1,\"rules\":[]}", "{\"format\":1,\"rules\":{\"r\":\"x\"}}",
                "{\"format\":1,\"rules\":{\"r\":{\"severity\":{\"min\":\"HIGH\",\"max\":\"LOW\"}}}}",
                "{\"format\":1,\"rules\":{\"r\":{\"severity\":{\"min\":\"URGENT\"}}}}",
                "{\"format\":1,\"rules\":{\"r\":{\"subject\":\" \"}}}", "{\"format\":1,\"rules\":{\"r\":{\"other\":1}}}",
                "{\"format\":1,\"rules\":{\"\":{}}}")) {
            assertThatThrownBy(() -> parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("catalog.json");
        }
    }

    /** A rule is measured unless the catalog says a model judges it: an existing catalog keeps its meaning. */
    @Test
    void aRule_isJudgedOnlyWhenTheCatalogSaysSo() {
        Catalog catalog = parse("{\"format\":1,\"rules\":{\"verdict\":{\"judged\":true},\"size\":{},"
                + "\"cycle\":{\"judged\":false}}}");

        assertThat(catalog.judged("verdict")).isTrue();
        assertThat(catalog.judged("size")).isFalse();
        assertThat(catalog.judged("cycle")).isFalse();
        assertThat(catalog.judged("unknown")).isFalse();
        assertThatThrownBy(() -> parse("{\"format\":1,\"rules\":{\"r\":{\"judged\":\"yes\"}}}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("judged must be true or false");
    }

    @Test
    void aGoneEntryOfARuleTheCatalogDoesNotKnow_isAProblem() {
        Catalog catalog = parse("{\"format\":1,\"rules\":{\"known\":{}}}");

        assertThat(catalog.problemsOfGone(List.of(new GoneFinding("known", "a"), new GoneFinding("nope", "b"))))
                .containsExactly("gone[1]: rule key 'nope' is not in catalog.json");
    }

    @Test
    void aMinOrMaxAlone_boundsOneSide() {
        Catalog catalog = parse("{\"format\":1,\"rules\":{\"r\":{\"severity\":{\"max\":\"MEDIUM\"}}}}");

        assertThat(catalog.problems(List.of(finding("r", Severity.LOW)))).isEmpty();
        assertThat(catalog.problems(List.of(finding("r", Severity.HIGH)))).hasSize(1);
        Catalog atLeast = parse("{\"format\":1,\"rules\":{\"r\":{\"severity\":{\"min\":\"MEDIUM\"}}}}");
        assertThat(atLeast.problems(List.of(finding("r", Severity.LOW)))).as("one step below the min").hasSize(1);
        assertThat(atLeast.problems(List.of(finding("r", Severity.CRITICAL)))).isEmpty();
    }
}
