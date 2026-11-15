package traceworks.core.describe;

import traceworks.core.record.WorkflowOutline;
import traceworks.core.view.DescribeHtml;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What {@code describe} says of a workflow that opens a model choice: the choice, its options, and who uses it. */
class ModelChoiceDescriptionTest {

    private static final String JUDGING = """
            {"id":"wf-1","name":"Judging","requires":["ai.model_choice"],
             "model_choices":{"judge":{"default":"fable","options":{
               "fable":{"provider":"CLAUDE","model":"claude-fable-5-2"},
               "astra":{"provider":"PI","model":"openai/gpt-6-astra"}}},
               "spare":{"default":"a","options":{"a":{"provider":"CLAUDE","model":"m"}}}},
             "blocks":[
              {"type":"simple","name":"verdict","config":{"command":"Judge it.","model_choice":"judge"}},
              {"type":"switch","name":"route","config":{"expression":"x","default":[
                {"type":"simple","name":"second","config":{"command":"Again.","model_choice":"judge"}}]}},
              {"type":"simple","name":"fixed","config":{"command":"Sum up.","model":"claude-opus-5-5"}}]}""";

    @Test
    void theDescription_holdsTheChoiceItsDefaultItsOptionsAndTheBlocksThatUseIt() {
        WorkflowDescription described = Described.of(JUDGING);

        assertThat(described.modelChoices()).containsExactly(
                new WorkflowDescription.ModelChoice("judge", "fable", List.of(
                        new WorkflowDescription.ModelChoice.Option("fable", "CLAUDE", "claude-fable-5-2"),
                        new WorkflowDescription.ModelChoice.Option("astra", "PI", "openai/gpt-6-astra")),
                        List.of("verdict", "route › second")),
                new WorkflowDescription.ModelChoice("spare", "a", List.of(
                        new WorkflowDescription.ModelChoice.Option("a", "CLAUDE", "m")), List.of()));
    }

    @Test
    void theText_listsTheChoices_andAStepSaysWhatItRunsWithUnlessTheRunPicks() {
        List<String> lines = DescriptionText.lines(Described.of(JUDGING));

        assertThat(lines).containsSubsequence(
                "Inputs    none",
                "Model choices",
                "  judge  pick with --input judge=<option>; without it: fable",
                "    fable  CLAUDE claude-fable-5-2  (the default)",
                "    astra  PI openai/gpt-6-astra",
                "    used by verdict, route › second",
                "  spare  pick with --input spare=<option>; without it: a",
                "    a  CLAUDE m  (the default)",
                "    used by no block",
                "Requires  ai.model_choice");
        assertThat(lines).anyMatch(line -> line.matches("  0 +verdict +simple \\(model choice judge\\)"))
                .anyMatch(line -> line.contains("ai: model choice judge: fable (provider CLAUDE, model"
                        + " claude-fable-5-2) unless the run picks astra, tools none"))
                .anyMatch(line -> line.contains("ai: provider CLAUDE (the default), model claude-opus-5-5"));
        assertThat(lines).last().isEqualTo(WorkflowDescription.CHECK_NOTE);
        assertThat(WorkflowDescription.OPERATOR_NOTE).contains("allowed_models", "which models may run at all");
    }

    @Test
    void aWorkflowWithoutAChoice_hasNoSuchSection() {
        String plain = """
                {"id":"wf-2","name":"Plain","blocks":[{"type":"shell","name":"a","config":{"command":"true"}}]}""";

        assertThat(DescriptionText.lines(Described.of(plain))).doesNotContain("Model choices");
        assertThat(DescribeHtml.render(Described.of(plain), "1")).doesNotContain("Model choices");
    }

    @Test
    void thePage_holdsTheChoicesToo() {
        String html = DescribeHtml.render(Described.of(JUDGING), "1.2.3");

        assertThat(html).contains("<h2>Model choices</h2>", "<td>judge<br><small>pick with <code>--input"
                        + " judge=&lt;option&gt;</code></small></td><td>fable <small>(the default)</small></td>"
                        + "<td>CLAUDE</td><td><code>claude-fable-5-2</code></td><td>verdict, route › second</td>",
                "<td></td><td>astra</td><td>PI</td><td><code>openai/gpt-6-astra</code></td><td></td>",
                "model choice judge: fable");
    }

    /** The outline of a recorded definition, which {@code show} and {@code run.html} print, names the choice. */
    @Test
    void theOutlineOfARecordedDefinition_namesTheChoiceWhereABlockNamesNoModel() {
        WorkflowOutline outline = WorkflowOutline.of(JUDGING.getBytes(StandardCharsets.UTF_8)).orElseThrow();

        assertThat(outline.blocks()).extracting(WorkflowOutline.Block::shownType)
                .containsExactly("simple (model choice judge)", "switch", "simple (claude-opus-5-5)");
    }
}
