package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.model.BlockDefinition;
import org.junit.jupiter.api.Test;

import traceworks.core.model.WorkflowDefinition;
import traceworks.core.testing.MapWorkflowResolver;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code protected-value-outside-shell}: the three places besides a shell line where a run refuses a protected value
 * (the arguments of a tool, a mount, a {@code workflow_id}), each reported once and deliberately not reported once.
 */
class ProtectedPlaceRulesTest {

    private static final String RULE = "protected-value-outside-shell";

    private static final BlockDefinition ASK = block("simple", "ask", Map.of("command", "Name a directory"));
    private static final BlockDefinition SEED = block("shell", "seed", Map.of("command", "echo docs"));

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(new HashMap<>(config));
        return b;
    }

    private static BlockDefinition tool(String... args) {
        return block("shell", "tool", Map.of("container_image", "./images/tool", "container_args", List.of(args)));
    }

    private static BlockDefinition mounting(String mount) {
        return block("shell", "list", Map.of("command", "ls /data", "mounts", List.of(mount)));
    }

    private static BlockDefinition child(String workflowId) {
        return block("sub-workflow", "child", Map.of("workflow_id", workflowId));
    }

    private static final Map<String, Object> AUDIT = Map.of("audit",
            Map.of("enabled", true, "key", "k", "category", "c", "report_block", "report"));

    private static List<AnalyzerFinding> findings(BlockDefinition... blocks) {
        return ours(new WorkflowAnalyzer().analyze(List.of(blocks), null));
    }

    private static List<AnalyzerFinding> ours(List<AnalyzerFinding> findings) {
        return findings.stream().filter(f -> f.rule().equals(RULE)).toList();
    }

    private static BlockDefinition skipped(BlockDefinition block) {
        Map<String, Object> config = new HashMap<>(block.getConfig());
        config.put("run_if", "test -f go");
        return block(block.getType(), block.getName(), config);
    }

    // ---------------------------------------------------------------- container_args

    @Test
    void modelTextInTheArgumentsOfATool_isAnError_thatNamesThePlaceAndWhatToWriteInstead() {
        assertThat(findings(ASK, tool("scan", "--dir={{ block.ask.output }}"))).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.ERROR);
            assertThat(f.blockIndex()).isEqualTo(1);
            assertThat(f.blockName()).isEqualTo("tool");
            assertThat(f.detail()).isEqualTo("puts {{ block.ask.output }} into container_args, the output of the AI"
                    + " step ask — text a model wrote and text from outside the run never go into the arguments of"
                    + " a tool: the value would choose what the tool does; a run refuses the step before the tool"
                    + " starts, whatever on_failure says, so Traceworks refuses the workflow. Correct: a tool step"
                    + " takes no values, so have a shell step before it write the value to a file (\"values\":"
                    + " {\"ASK\": \"{{ block.ask.output }}\"} and printf '%s' \"$ASK\" >"
                    + " \"{{ scratch_dir }}/value.txt\"), and name the file in the arguments");
        });
    }

    @Test
    void everyProtectedKindInTheArgumentsOfATool_isReported() {
        // The output right before, the output of a shell step after the AI step, a step number, and outside text
        assertThat(findings(ASK, tool("{{ previous_output }}"))).hasSize(1);
        assertThat(findings(ASK, block("shell", "echo", Map.of("command", "cat notes.txt")),
                tool("{{ block.echo.output }}", "{{ step_0_output }}"))).singleElement()
                .satisfies(f -> assertThat(f.detail()).startsWith("puts {{ block.echo.output }}, {{ step_0_output }}"
                        + " into container_args, the output of the step echo, which runs after the AI step ask and"
                        + " may hold what the model wrote, the output of the AI step ask — "));
    }

    /**
     * Outside text is protected where a run sets it: the register's data in an audit workflow, the output of a chain
     * in a run that gets it as an input. Anywhere else the placeholder stays as it is written and the run takes it
     * ({@code chain-only-placeholder} and {@code audit-only-placeholder} say so).
     */
    @Test
    void outsideText_isReportedWhereTheRunSetsIt_andNowhereElse() {
        BlockDefinition tool = tool("{{ previous_chain_output }}", "{{ audit_findings }}");
        BlockDefinition report = block("shell", "report", Map.of("command", "echo done"));

        assertThat(findings(tool)).isEmpty();
        assertThat(findings(mounting("{{ previous_report }}:/data:ro"))).isEmpty();
        assertThat(findings(child("{{ previous_chain_output }}"))).isEmpty();

        assertThat(ours(new WorkflowAnalyzer().withInputs(List.of("previous_chain_output"))
                .analyze(List.of(tool), null))).singleElement().satisfies(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.ERROR);
                    assertThat(f.detail()).startsWith("puts {{ previous_chain_output }} into container_args, text"
                            + " from outside the run — ");
                });
        assertThat(ours(new WorkflowAnalyzer().analyze(List.of(tool, report), null, AUDIT))).singleElement()
                .satisfies(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.ERROR);
                    assertThat(f.detail()).startsWith("puts {{ audit_findings }} into container_args, text from"
                            + " outside the run — ");
                });
        assertThat(ours(new WorkflowAnalyzer().withInputs(List.of("previous_chain_output"))
                .analyze(List.of(child("{{ previous_chain_output }}")), null))).hasSize(1);
    }

    // ---------------------------------------------------------------- a model step a run may skip

    /**
     * The run protects model text from the first call of a model on, not from the first block that could make one:
     * where that block may be skipped, only some runs refuse, and the finding is a warning that says which.
     */
    @Test
    void anOutputProtectedOnlyWhenASkippableStepAsksAModel_isAWarningThatNamesTheRuns() {
        BlockDefinition pick = block("shell", "pick", Map.of("command", "echo docs"));

        assertThat(findings(skipped(ASK), pick, tool("{{ block.pick.output }}"))).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.WARNING);
            assertThat(f.detail()).isEqualTo("puts {{ block.pick.output }} into container_args, the output of the"
                    + " step pick, which runs after the AI step ask and may hold what the model wrote — text a"
                    + " model wrote and text from outside the run never go into the arguments of a tool: the value"
                    + " would choose what the tool does; a run in which ask asks a model refuses the step before"
                    + " the tool starts, whatever on_failure says, and any other run takes the value. Correct: a"
                    + " tool step takes no values, so have a shell step before it write the value to a file"
                    + " (\"values\": {\"PICK\": \"{{ block.pick.output }}\"} and printf '%s' \"$PICK\" >"
                    + " \"{{ scratch_dir }}/value.txt\"), and name the file in the arguments");
        });
        // The skippable step's own output, in a mount; and in a workflow_id a step number
        assertThat(findings(skipped(ASK), mounting("{{ block.ask.output }}:/data:ro"))).singleElement()
                .satisfies(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.WARNING);
                    assertThat(f.detail()).contains("; a container run in which ask asks a model refuses the"
                            + " step before anything starts");
                });
        assertThat(findings(skipped(ASK), pick, child("{{ step_1_output }}"))).singleElement()
                .satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.WARNING));
    }

    @Test
    void everyStepThatMayNotAskAModel_makesAWarning_andOneThatAlwaysAsks_anError() {
        BlockDefinition pick = block("shell", "pick", Map.of("command", "echo docs"));
        BlockDefinition use = tool("{{ block.pick.output }}");
        Map<String, Object> inner = Map.of("type", "simple", "name", "inner", "config", Map.of("command", "Judge"));
        MapWorkflowResolver children = new MapWorkflowResolver().add("judge", new WorkflowDefinition("judge",
                "judge", null, List.of(ASK), null, null, null, null, null, null, null, null, null));

        // A switch case, a loop that may have no item, a compression that may refuse its input, a child workflow
        for (BlockDefinition maybe : List.of(
                block("switch", "route", Map.of("expression", "{{ mode }}",
                        "cases", List.of(Map.of("value", "a", "blocks", List.of(inner))))),
                block("foreach", "each", Map.of("execution_mode", "ai", "items_command", "ls", "command", "Judge")),
                block("compress-ai", "short", Map.of("instruction", "Shorten")),
                block("sub-workflow", "sub", Map.of("workflow_id", "judge")))) {
            assertThat(ours(new WorkflowAnalyzer().analyze(new WorkflowDefinition(null, null, null,
                    List.of(maybe, pick, use), null, null, null, null, Map.of(), null, null, null, null), children)))
                    .as(maybe.getType()).singleElement()
                    .satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.WARNING));
        }
        // A step that asks in every run that gets past it
        for (BlockDefinition always : List.of(ASK,
                block("agentic", "work", Map.of("goal", "Fix it")),
                block("retry", "again", Map.of("execution_mode", "ai", "command", "Judge")),
                block("while", "loop", Map.of("execution_mode", "ai", "condition", "Done?", "command", "Judge")),
                block("conditional", "branch", Map.of("execution_mode", "ai", "condition", "Ready?",
                        "then_command", "Judge")))) {
            assertThat(findings(always, pick, use)).as(always.getType()).singleElement()
                    .satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.ERROR));
        }
        // One that always asks before the skippable one: every run protects what follows
        assertThat(findings(ASK, skipped(block("simple", "second", Map.of("command", "Again"))), pick, use))
                .singleElement().satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.ERROR));
    }

    @Test
    void anUnprotectedPlaceholderInTheArgumentsOfATool_isNoFinding() {
        // A variable, the output of a step in a workflow that asks no model, and of a step before the first AI step
        assertThat(findings(SEED, tool("{{ who }}", "{{ block.seed.output }}", "{{ previous_output }}"))).isEmpty();
        assertThat(findings(SEED, tool("{{ block.seed.output }}", "{{ step_0_output }}"), ASK)).isEmpty();
    }

    @Test
    void aValueWrittenToAFileForTheTool_isNoFinding() {
        BlockDefinition write = block("shell", "write", Map.of("values", Map.of("ANSWER", "{{ block.ask.output }}"),
                "command", "printf '%s' \"$ANSWER\" > \"{{ scratch_dir }}/answer.txt\""));

        assertThat(findings(ASK, write, tool("scan", "--from={{ scratch_dir }}/answer.txt"))).isEmpty();
    }

    @Test
    void argumentsNoRunReads_areNotJudged() {
        // A block nested in a switch runs its command, never container_args; arguments that are no list fail the step
        BlockDefinition nested = block("switch", "route", Map.of("expression", "{{ mode }}", "cases", List.of(
                Map.of("value", "a", "blocks", List.of(Map.of("type", "shell", "name", "inner", "config",
                        Map.of("container_image", "./images/tool",
                                "container_args", List.of("{{ block.ask.output }}"))))))));
        BlockDefinition noList = block("shell", "tool", Map.of("container_image", "./images/tool",
                "container_args", "{{ block.ask.output }}"));

        assertThat(findings(ASK, nested)).isEmpty();
        assertThat(findings(ASK, noList)).isEmpty();
    }

    // ---------------------------------------------------------------- mounts

    @Test
    void modelTextInAMount_isAnError_thatNamesTheEntryAndWhatToWriteInstead() {
        assertThat(findings(ASK, mounting("{{ block.ask.output }}:/data:ro"))).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.ERROR);
            assertThat(f.blockIndex()).isEqualTo(1);
            assertThat(f.blockName()).isEqualTo("list");
            assertThat(f.detail()).isEqualTo("names {{ block.ask.output }} in mounts"
                    + " (\"{{ block.ask.output }}:/data:ro\"), the output of the AI step ask — text a model wrote"
                    + " and text from outside the run never go into a mount: the value would choose what the"
                    + " container sees; a container run refuses the step before anything starts, whatever"
                    + " on_failure says (a host run does not read the mounts of a step without a container_image),"
                    + " so Traceworks refuses the workflow. Correct: write the path out, or take it from a variable"
                    + " that default_context or an input of the run sets");
        });
    }

    /** A step with an image, a tool step and a step of a wrapper read their mounts in a host run too. */
    @Test
    void theMountsOfAStepThatAHostRunReadsToo_areRefusedByEveryRun() {
        String mount = "{{ block.ask.output }}:/data:ro";
        String everyRun = "; a run refuses the step before anything starts, whatever on_failure says, so";

        assertThat(findings(ASK, block("shell", "list", Map.of("command", "ls /data", "container_image", "alpine",
                "mounts", List.of(mount))))).singleElement()
                .satisfies(f -> assertThat(f.detail()).contains(everyRun));
        assertThat(findings(ASK, block("shell", "tool", Map.of("container_image", "./images/tool",
                "container_args", List.of("scan"), "mounts", List.of(mount))))).singleElement()
                .satisfies(f -> assertThat(f.detail()).contains(everyRun));
        assertThat(findings(ASK, block("retry", "again", Map.of("execution_mode", "shell", "command", "ls /data",
                "mounts", List.of(mount))))).singleElement()
                .satisfies(f -> assertThat(f.detail()).contains(everyRun));
    }

    @Test
    void aProtectedValueAnywhereInAMountEntry_isReported_inEveryBlockThatMounts() {
        // The run refuses the whole entry, the container side included
        assertThat(findings(ASK, mounting("/srv/data:/{{ previous_output }}:ro"))).hasSize(1);
        // A tool step, a wrapper in shell mode, and a shell step in a switch case
        assertThat(findings(ASK, block("shell", "tool", Map.of("container_image", "./images/tool",
                "container_args", List.of("scan"), "mounts", List.of("{{ block.ask.output }}:/data:ro"))))).hasSize(1);
        assertThat(findings(ASK, block("retry", "again", Map.of("execution_mode", "shell", "command", "ls /data",
                "mounts", List.of("{{ block.ask.output }}:/data:ro"))))).hasSize(1);
        assertThat(findings(ASK, block("switch", "route", Map.of("expression", "{{ mode }}", "cases", List.of(
                Map.of("value", "a", "blocks", List.of(Map.of("type", "shell", "name", "inner", "config",
                        Map.of("command", "ls /data", "mounts", List.of("{{ block.ask.output }}:/data:ro"))))))))))
                .singleElement().satisfies(f -> {
                    assertThat(f.blockName()).isEqualTo("route");
                    assertThat(f.detail()).startsWith("Block 2 > cases[0] › Block 1: names {{ block.ask.output }}"
                            + " in mounts ");
                });
    }

    @Test
    void anUnprotectedPlaceholderInAMount_isNoFinding() {
        assertThat(findings(ASK, mounting("{{ ro_dir }}:/data:ro"))).isEmpty();
        assertThat(findings(SEED, mounting("{{ block.seed.output }}:/data:ro"))).isEmpty();
        assertThat(findings(SEED, mounting("{{ previous_output }}:/data:ro"), ASK)).isEmpty();
    }

    @Test
    void mountsNoRunReads_areNotJudged() {
        // A wrapper in AI mode starts no container of its own with them, and a foreach of child workflows runs no step
        assertThat(findings(ASK, block("retry", "again", Map.of("execution_mode", "ai", "command", "Try again",
                "mounts", List.of("{{ block.ask.output }}:/data:ro"))))).isEmpty();
        assertThat(findings(ASK, block("foreach", "each", Map.of("execution_mode", "shell", "items", List.of("a"),
                "workflow_id", "./child", "mounts", List.of("{{ block.ask.output }}:/data:ro"))))).isEmpty();
    }

    // ---------------------------------------------------------------- workflow_id

    @Test
    void modelTextInAWorkflowId_isAnError_thatNamesThePlaceAndWhatToWriteInstead() {
        assertThat(findings(ASK, child("./{{ block.ask.output }}"))).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.ERROR);
            assertThat(f.blockIndex()).isEqualTo(1);
            assertThat(f.blockName()).isEqualTo("child");
            assertThat(f.detail()).isEqualTo("names {{ block.ask.output }} in workflow_id, the output of the AI step"
                    + " ask — text a model wrote and text from outside the run never go into a workflow_id: the"
                    + " value would choose which workflow runs, with the run's workspace and secrets; a run fails"
                    + " the step before any workflow starts, so Traceworks refuses the workflow. Correct: write the"
                    + " workflow out, or take it from a variable that default_context or an input of the run sets");
        });
    }

    @Test
    void everyStepOutputInAWorkflowId_isReported_protectedOrNot() {
        // The run refuses previous_output and block.<name>.output there in every run, also in one that asks no model
        assertThat(findings(SEED, child("./{{ block.seed.output }}"))).singleElement()
                .satisfies(f -> assertThat(f.detail()).startsWith("names {{ block.seed.output }} in workflow_id, a"
                        + " step output, which a workflow_id takes in no run — "));
        assertThat(findings(SEED, child("{{ previous_output }}"))).hasSize(1);
        assertThat(findings(ASK, block("foreach", "each", Map.of("items", List.of("a"),
                "workflow_id", "{{ previous_output }}")))).hasSize(1);
        assertThat(findings(ASK, child("{{ step_0_output }}"))).hasSize(1);
    }

    @Test
    void aWorkflowIdWrittenOutOrFromAVariable_isNoFinding() {
        assertThat(findings(ASK, child("./review"))).isEmpty();
        assertThat(findings(ASK, child("./{{ kind }}"))).isEmpty();
        // A step number in a run that asks no model is a trusted variable, which the run takes
        assertThat(findings(SEED, child("./{{ step_0_output }}"))).isEmpty();
        // A workflow_id on a block that runs no child is read by no run
        assertThat(findings(ASK, block("shell", "s", Map.of("command", "ls", "workflow_id", "{{ previous_output }}"))))
                .isEmpty();
    }
}
