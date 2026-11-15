package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code unknown-variable} and the inputs no block reads. */
class VariableRulesTest {

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(config);
        return b;
    }

    private static BlockDefinition echo(String text) {
        return block("shell", "echo", Map.of("command", "echo " + text));
    }

    private static WorkflowDefinition workflow(Map<String, Object> defaultContext, BlockDefinition... blocks) {
        return new WorkflowDefinition("id-1", "test", null, List.of(blocks), null, null, null, null, null,
                defaultContext, null, "traceworks-workflow", 1);
    }

    private static List<AnalyzerFinding> apply(WorkflowDefinition workflow) {
        return apply(workflow, Set.of());
    }

    private static List<AnalyzerFinding> apply(WorkflowDefinition workflow, Set<String> inputs) {
        List<AnalyzerFinding> out = new ArrayList<>();
        VariableRules.apply(workflow, inputs, out);
        return out;
    }

    // ---- unknown-variable ----

    @Test
    void aVariableNothingSets_isAWarning_oncePerBlock_withANearName() {
        List<AnalyzerFinding> out = apply(workflow(Map.of("name", "world"),
                echo("{{ name }}"),
                block("shell", "greet", Map.of("command", "echo {{ nme }} {{nme}} {{ target }}"))));

        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("unknown-variable");
            assertThat(f.severity()).isEqualTo(Severity.WARNING);
            assertThat(f.blockIndex()).isEqualTo(1);
            assertThat(f.blockName()).isEqualTo("greet");
            assertThat(f.detail()).startsWith("{{ nme }}, {{ target }} — ")
                    .contains("default_context", "--input", "stays literal")
                    .contains("did you mean {{ name }} for {{ nme }}?");
        });
    }

    @Test
    void aVariableInANestedBlock_isFound() {
        List<AnalyzerFinding> out = apply(workflow(null, block("switch", "route", Map.of("expression", "a",
                "cases", List.of(Map.of("value", "a", "blocks", List.of(Map.of("type", "shell", "name", "inner",
                        "config", Map.of("command", "echo {{ nme }}")))))))));

        assertThat(out).extracting(AnalyzerFinding::rule).containsExactly("unknown-variable");
    }

    @Test
    void theDefaultContext_theRunVariables_andPreviousOutput_areKnown() {
        assertThat(apply(workflow(Map.of("name", "world", "empty", ""), echo("{{ name }} {{ empty }} "
                + "{{ previous_output }} {{ workflow_run_id }} {{ workflow_id }} {{ workflow_name }} "
                + "{{ project_path }} {{ execution_path }} {{ scratch_dir }} {{ workspace_source_sha }} "
                + "{{ package_dir }} {{ model }} {{ provider }} {{ effort }} {{ started_at }} {{ run_timestamp }} "
                + "{{ trigger_source }}")))).isEmpty();
    }

    /** Each of them has a rule of its own, or none on purpose: one finding per mistake. */
    @Test
    void whatAnotherRuleJudges_isNotReportedHere() {
        assertThat(apply(workflow(null, echo("{{ block.x.output }} {{ block.echo.outptu }} {{ step_7_output }} "
                + "{{ previous_chain_output }} {{ chain_run_id }} {{ audit_findings }} {{ previous_report }} "
                + "{{ open_todos }} {{ todo_title }} {{ goal_status }} {{ project_id }} {{ home_dir }} "
                + "{{ platform_scripts }} {{ execution_path_api }} {{ goal_guard_status }}")))).isEmpty();
    }

    @Test
    void whatABlockSets_isKnown_alsoBeforeTheBlock_andInACase() {
        assertThat(apply(workflow(null,
                echo("{{ approval_answer }} {{ user_input }} {{ item }} {{ item_index }} {{ agentic_goal_achieved }} "
                        + "{{ agentic_iterations }} {{ release }} {{ ticket }} {{ title }} {{ severity }} {{ file }} "
                        + "{{ file_index }} {{ area }} {{ nested_answer }}"),
                block("approval", "ok", Map.of("question", "ok?")),
                block("approval", "named", Map.of("question", "ok?", "variable_name", "release")),
                block("human-input", "ask", Map.of("prompt", "which?")),
                block("human-input", "one", Map.of("prompt", "which?", "variable_name", "ticket")),
                block("human-input", "form", Map.of("prompt", "which?", "fields", List.of(
                        Map.of("variable_name", "title"), Map.of("variable_name", "severity")))),
                block("foreach", "loop", Map.of("execution_mode", "shell", "items", List.of("a"),
                        "command", "echo {{ item }}")),
                block("foreach", "files", Map.of("variable_name", "file", "items", List.of("a"),
                        "workflow_id", "./child", "context", Map.of("area", "{{ file }}"))),
                block("agentic", "act", Map.of("goal", "do it", "tools", "none")),
                block("switch", "route", Map.of("expression", "a", "default", List.of(Map.of("type", "simple",
                        "name", "inner", "config", Map.of("variable_name", "nested_answer")))))))).isEmpty();
    }

    @Test
    void theDefaultNames_areKnownOnlyWithABlockThatSetsThem() {
        List<AnalyzerFinding> out = apply(workflow(null,
                echo("{{ approval_answer }} {{ user_input }} {{ item }} {{ item_index }} {{ agentic_iterations }}")));

        assertThat(out).singleElement().satisfies(f -> assertThat(f.detail()).contains("{{ approval_answer }}",
                "{{ user_input }}", "{{ item }}", "{{ item_index }}", "{{ agentic_iterations }}"));
    }

    /** Text that only looks like a placeholder: templates of other tools, which a shell step may hold. */
    @Test
    void whatIsNoPlainName_isNotJudged() {
        assertThat(apply(workflow(null, echo("{{ .State.Running }} {{.Names}} {{ item.id }} {{ a b }} {{ a-b }} "
                + "{{ #each }} {{ 1st }} {{ x | upper }} {{ secrets.TOKEN }}")))).isEmpty();
    }

    @Test
    void anInputOfTheRun_isKnown() {
        WorkflowDefinition workflow = workflow(null, echo("{{ payload }} {{ nme }}"));

        assertThat(apply(workflow, Set.of("payload", "nme"))).isEmpty();
        assertThat(apply(workflow, Set.of("payload"))).singleElement()
                .satisfies(f -> assertThat(f.detail()).startsWith("{{ nme }} — "));
    }

    @Test
    void aVariableNameThatIsAPlaceholder_switchesTheRuleOff() {
        assertThat(apply(workflow(null,
                echo("{{ nme }}"),
                block("human-input", "ask", Map.of("prompt", "which?", "variable_name", "{{ where }}"))))).isEmpty();
    }

    @Test
    void aWorkflowWithoutBlocks_orWithANullBlock_isNoError() {
        assertThat(apply(workflow(null))).isEmpty();
        List<BlockDefinition> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add(block("shell", "a", null));
        withNull.add(block(null, "untyped", Map.of("command", "echo")));
        assertThat(apply(new WorkflowDefinition(null, null, null, withNull, null, null, null, null, null, null, null,
                null, null))).isEmpty();
    }

    // ---- inputs no block reads ----

    @Test
    void anInputNoBlockReads_isNamed_withANearName() {
        WorkflowDefinition workflow = workflow(Map.of("name", "world"), echo("{{ name }} {{ payload }}"));

        assertThat(VariableRules.unreadInputs(workflow, List.of("nme", "payload", "name", "colour")))
                .containsExactly(
                        "input \"nme\" is read by no block and is not in default_context, so it changes nothing"
                                + " in this run; did you mean \"name\"?",
                        "input \"colour\" is read by no block and is not in default_context, so it changes nothing"
                                + " in this run");
    }

    @Test
    void anInputInTheDefaultContext_orReadInACase_orSeedingPreviousOutput_isRead() {
        WorkflowDefinition workflow = workflow(Map.of("unused_default", "x"), block("switch", "route",
                Map.of("expression", "{{ kind }}", "default", List.of(Map.of("type", "shell", "name", "inner",
                        "config", Map.of("command", "echo {{ deep }}"))))));

        assertThat(VariableRules.unreadInputs(workflow,
                List.of("unused_default", "kind", "deep", "previous_output", "__untrusted_keys"))).isEmpty();
    }

    /** A child gets every variable of its parent, so a block of the child may read the input. */
    @Test
    void aWorkflowThatStartsAChild_isNotJudged() {
        assertThat(VariableRules.unreadInputs(workflow(null,
                block("sub-workflow", "child", Map.of("workflow_id", "./child"))), List.of("anything"))).isEmpty();
        assertThat(VariableRules.unreadInputs(workflow(null,
                block("foreach", "each", Map.of("items", List.of("a"), "workflow_id", "./child"))),
                List.of("anything"))).isEmpty();
        assertThat(VariableRules.unreadInputs(workflow(null,
                block("foreach", "each", Map.of("execution_mode", "shell", "items", List.of("a"),
                        "command", "echo {{ item }}"))), List.of("anything"))).hasSize(1);
    }

    @Test
    void noInputs_orNoBlocks_yieldNothing() {
        assertThat(VariableRules.unreadInputs(workflow(null, echo("x")), List.of())).isEmpty();
        assertThat(VariableRules.unreadInputs(workflow(null), List.of("a"))).hasSize(1);
    }
}
