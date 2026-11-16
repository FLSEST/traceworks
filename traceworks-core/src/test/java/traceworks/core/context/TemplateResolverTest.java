package traceworks.core.context;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateResolverTest {

    @Test
    void nullTemplate_staysNull_onEveryPath() {
        assertThat(TemplateResolver.resolve(null, "p", Map.of(), Map.of())).isNull();
        assertThat(TemplateResolver.shellLine(null, "p", Map.of(), Map.of(), TemplateResolver.Taint.NONE, true))
                .isNull();
        assertThat(TemplateResolver.shellLine(null, "p", Map.of(), Map.of(),
                new TemplateResolver.Taint(Set.of("x"), Set.of(), true), false)).isNull();
    }

    @Test
    void previousOutputNull_resolvesToEmpty_andNullBlockOutputToo() {
        Map<String, String> outputs = new HashMap<>();
        outputs.put("b", null);

        assertThat(TemplateResolver.resolve("[{{ previous_output }}][{{ block.b.output }}]", null, outputs, Map.of()))
                .isEqualTo("[][]");
    }

    @Test
    void blockPlaceholderWithEmptyName_orUnknownBlock_fallsBackToVariables_thenStaysLiteral() {
        Map<String, Object> vars = new HashMap<>(Map.of("block.x.output", "from-var"));

        assertThat(TemplateResolver.resolve("{{ block..output }} {{ block.x.output }} {{ block.y.output }}",
                "", Map.of(), vars))
                .isEqualTo("{{ block..output }} from-var {{ block.y.output }}");
    }

    @Test
    void nonStringVariables_areRenderedViaStringValueOf() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("n", 42);
        vars.put("nothing", null);

        assertThat(TemplateResolver.resolve("{{ n }}/{{ nothing }}", "", Map.of(), vars)).isEqualTo("42/null");
    }

    @Test
    void replacementValuesWithRegexSpecials_areInsertedLiterally() {
        Map<String, Object> vars = new HashMap<>(Map.of("v", "$1 \\ $0"));

        assertThat(TemplateResolver.resolve("x={{ v }}", "", Map.of(), vars)).isEqualTo("x=$1 \\ $0");
    }

    @Test
    void resolvePrompt_reportsEveryResolvedKey_butNotUnknownOnes() {
        List<String> reported = new ArrayList<>();
        Map<String, Object> vars = new HashMap<>(Map.of("a", "1"));

        String out = TemplateResolver.resolvePrompt("{{ a }} {{a}} {{ b }} {{ previous_output }}", "p",
                Map.of(), vars, reported::add);

        assertThat(out).isEqualTo("1 1 {{ b }} p");
        assertThat(reported).containsExactly("a", "a", "previous_output");
    }

    @Test
    void shellEscape_quotesAndEscapesSingleQuotes_nullBecomesEmptyQuotes() {
        assertThat(TemplateResolver.shellEscape(null)).isEqualTo("''");
        assertThat(TemplateResolver.shellEscape("")).isEqualTo("''");
        assertThat(TemplateResolver.shellEscape("it's")).isEqualTo("'it'\\''s'");
    }

    @Test
    void shellLine_withoutTaint_insertsAsItIs_orInSingleQuotes() {
        Map<String, Object> vars = new HashMap<>(Map.of("v", "a b", "q", "it's"));

        assertThat(TemplateResolver.shellLine("echo {{ v }} {{q}} {{ unknown }}", "", Map.of(), vars,
                TemplateResolver.Taint.NONE, false)).isEqualTo("echo a b it's {{ unknown }}");
        assertThat(TemplateResolver.shellLine("echo {{ v }} {{q}} {{ unknown }}", "", Map.of(), vars,
                TemplateResolver.Taint.NONE, true)).isEqualTo("echo 'a b' 'it'\\''s' {{ unknown }}");
    }

    /** A protected value never goes into a shell line: the line is refused, wherever the placeholder stands. */
    @Test
    void shellLine_thatNamesAProtectedValue_isRefused_withEscapingToo() {
        Map<String, Object> vars = new HashMap<>(Map.of("evil", "$(rm -rf /)", "safe", "ok", "unused", "x"));
        Map<String, String> outputs = Map.of("build", "out'put", "clean", "c");
        var taint = new TemplateResolver.Taint(Set.of("evil", "unused"), Set.of("build"), true);

        for (boolean escapeAll : new boolean[] {false, true}) {
            for (String line : List.of("echo {{ evil }}", "echo \"{{evil}}\"", "echo '{{ block.build.output }}'",
                    "true # {{ previous_output }}", "cat <<'EOF'\n{{ evil }}\nEOF", "X={{ evil }}")) {
                assertThatThrownBy(() -> TemplateResolver.shellLine(line, "prev", outputs, vars, taint, escapeAll))
                        .as(line).isInstanceOf(ShellLineRefusedException.class)
                        .hasMessageStartingWith("shell line refused, nothing ran: {{ ");
            }
        }
        assertThat(TemplateResolver.shellLine("echo {{ safe }} {{ block.clean.output }}", "prev", outputs, vars,
                taint, false)).as("the other values of the same run").isEqualTo("echo ok c");
    }

    @Test
    void theRefusal_namesEveryPlaceholder_andTheFix() {
        var taint = new TemplateResolver.Taint(Set.of("item"), Set.of("review"), false);
        Map<String, Object> vars = new HashMap<>(Map.of("item", "x"));

        assertThatThrownBy(() -> TemplateResolver.shellLine("echo {{ block.review.output }} {{ item }} {{item}}", "",
                Map.of("review", "r"), vars, taint, false))
                .hasMessage("shell line refused, nothing ran: {{ block.review.output }}, {{ item }} are text a model"
                        + " wrote or text from outside the run, and such a value never goes into shell code, quoted"
                        + " or not. Hand it to the command as a variable: put it under the block's values, for"
                        + " example \"values\": {\"REVIEW\": \"{{ block.review.output }}\"}, and write \"$REVIEW\""
                        + " where the placeholder stands");
        assertThat(ShellLineRefusedException.variableName("step_3_output")).isEqualTo("STEP_3_OUTPUT");
        assertThat(ShellLineRefusedException.variableName("block.Build Report.output")).isEqualTo("BUILD_REPORT");
        assertThat(ShellLineRefusedException.variableName("block.1st.output")).isEqualTo("VALUE_1ST");
    }

    /** A protected name that resolves to nothing stays literal, as it always did: there is no value to protect. */
    @Test
    void shellLine_protectedUnknownBlock_keepsItsPlaceholder() {
        var taint = new TemplateResolver.Taint(Set.of(), Set.of("ghost"), false);

        assertThat(TemplateResolver.shellLine("x {{ block.ghost.output }}", "", Map.of(), Map.of(), taint, false))
                .isEqualTo("x {{ block.ghost.output }}");
    }

    @Test
    void taintIsEmpty_onlyWhenAllPartsAreEmpty() {
        assertThat(TemplateResolver.Taint.NONE.isEmpty()).isTrue();
        assertThat(new TemplateResolver.Taint(Set.of("v"), Set.of(), false).isEmpty()).isFalse();
        assertThat(new TemplateResolver.Taint(Set.of(), Set.of("o"), false).isEmpty()).isFalse();
        assertThat(new TemplateResolver.Taint(Set.of(), Set.of(), true).isEmpty()).isFalse();
    }

    @Test
    void resolves_answersLikeLookup() {
        Map<String, String> outputs = Map.of("b", "");
        Map<String, Object> vars = new HashMap<>(Map.of("v", ""));

        assertThat(TemplateResolver.resolves("previous_output", null, outputs, vars)).isTrue();
        assertThat(TemplateResolver.resolves("block.b.output", null, outputs, vars)).isTrue();
        assertThat(TemplateResolver.resolves("v", null, outputs, vars)).isTrue();
        assertThat(TemplateResolver.resolves("missing", null, outputs, vars)).isFalse();
        assertThat(TemplateResolver.resolves("block.other.output", null, outputs, vars)).isFalse();
    }
}
