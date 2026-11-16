package traceworks.core.engine;

import traceworks.core.blocks.CoreBlocks;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepResult;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.AiProvider;
import traceworks.core.port.AiProviders;
import traceworks.core.testing.FakeAiProvider;
import traceworks.core.testing.FakeCommandRunner;
import traceworks.core.testing.MapWorkflowResolver;
import traceworks.core.testing.RecordingRunEvents;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static traceworks.core.engine.ModelChoicesTest.block;
import static traceworks.core.engine.ModelChoicesTest.judge;
import static traceworks.core.engine.ModelChoicesTest.workflow;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run of a workflow with a model choice, through the engine with the real blocks: the pick comes from the inputs
 * the run starts with, the step is called with the picked provider and model, and nothing a step sets changes it.
 */
class ModelChoiceEngineTest {

    private final FakeCommandRunner commands = new FakeCommandRunner().on("report ", "written");
    private final FakeAiProvider claude = new FakeAiProvider("CLAUDE").otherwise("claude says");
    private final FakeAiProvider pi = new FakeAiProvider("PI").otherwise("pi says");
    private final MapWorkflowResolver workflows = new MapWorkflowResolver();
    private final RecordingRunEvents events = new RecordingRunEvents();
    private final WorkflowEngine engine = new WorkflowEngine(CoreBlocks.registry(null));

    private RunOptions.Builder options() {
        return RunOptions.builder().runId("run-1").events(events).commandRunner(commands)
                .aiProviders(AiProviders.of("CLAUDE", List.of(claude, pi))).workflowResolver(workflows)
                .clock(() -> Instant.parse("2026-11-14T08:00:00Z")).workingDirectory("/work");
    }

    private static WorkflowDefinition judging() {
        return workflow(Map.of("judge", judge()), Map.of(),
                block("simple", "verdict", Map.of("command", "Judge it", "model_choice", "judge")),
                block("shell", "report", Map.of("command",
                        "report {{ judge }} {{ judge_provider }} {{ judge_model }}")));
    }

    private static List<String> models(FakeAiProvider provider) {
        return provider.actionRequests().stream().map(AiProvider.ActionRequest::model).toList();
    }

    @Test
    void withoutAPick_theStepRunsOnTheDefaultOption_andTheVariablesNameIt() {
        RunOutcome outcome = engine.run(judging(), null, options().build());

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(models(claude)).containsExactly("claude-fable-5-2");
        assertThat(pi.actionRequests()).isEmpty();
        assertThat(commands.commands()).containsExactly("report fable CLAUDE claude-fable-5-2");
        assertThat(outcome.steps().getFirst().getMetadata()).containsEntry("provider", "CLAUDE")
                .containsEntry("requestedModel", "claude-fable-5-2");
    }

    @Test
    void theInputPicks_andTheStepRunsOnThatProviderAndModel() {
        RunOutcome outcome = engine.run(judging(), Map.of("judge", "astra"), options().build());

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(models(pi)).containsExactly("openai/gpt-6-astra");
        assertThat(claude.actionRequests()).isEmpty();
        assertThat(commands.commands()).containsExactly("report astra PI openai/gpt-6-astra");
    }

    @Test
    void aPickOutsideTheOptions_refusesTheRunBeforeAnyStep() {
        RunOutcome outcome = engine.run(judging(), Map.of("judge", "gpt"), options().build());

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).isEqualTo("workflow refused: the model choice \"judge\" has no option \"gpt\":"
                + " the input judge picks one of fable, opus, astra (the default is fable)");
        assertThat(outcome.steps()).isEmpty();
        assertThat(claude.actionRequests()).isEmpty();
    }

    /** The pick is what the run started with: the output of a step that names an option changes no model. */
    @Test
    void theOutputOfAStepThatNamesAnOption_doesNotChangeTheModel() {
        commands.on("echo", "astra");
        WorkflowDefinition workflow = workflow(Map.of("judge", judge()), Map.of(),
                block("shell", "say", Map.of("command", "echo judge=astra")),
                block("simple", "verdict", Map.of("command", "Judge it after {{ previous_output }}",
                        "model_choice", "judge")));

        RunOutcome outcome = engine.run(workflow, null, options().build());

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(claude.prompts()).containsExactly("Judge it after astra");
        assertThat(models(claude)).containsExactly("claude-fable-5-2");
        assertThat(pi.actionRequests()).isEmpty();
    }

    /** A block that would set a variable of the choice is refused before any step: nothing can replace the pick. */
    @Test
    void aBlockThatSetsAVariableOfTheChoice_refusesTheRun() {
        commands.on("echo", "[\"evil-model\"]");
        WorkflowDefinition workflow = workflow(Map.of("judge", judge()), Map.of(),
                block("foreach", "loop", Map.of("execution_mode", "shell", "items_source", "command",
                        "items_command", "echo", "variable_name", "judge_model", "command", "true")),
                block("simple", "verdict", Map.of("command", "Judge it", "model_choice", "judge")));

        RunOutcome outcome = engine.run(workflow, null, options().build());

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).isEqualTo("workflow refused: Block 1 \"loop\": the block sets the variable"
                + " \"judge_model\", which the run sets for the model choice \"judge\" — give the block's variable"
                + " another name");
        assertThat(commands.commands()).isEmpty();
        assertThat(claude.actionRequests()).isEmpty();
    }

    private WorkflowDefinition parentHandingOver(Map<String, Object> context) {
        workflows.add("child", workflow(Map.of("judge", judge()), Map.of(),
                block("simple", "verdict", Map.of("command", "Judge it", "model_choice", "judge")),
                block("shell", "report", Map.of("command", "report {{ judge }} {{ judge_provider }} {{ judge_model }}"))));
        return new WorkflowDefinition("parent", "Parent", null, List.of(
                block("foreach", "each", Map.of("items", List.of("one"), "workflow_id", "child",
                        "context", context))), null, null, null, null, Map.of(), Map.of(), null, null, null);
    }

    /**
     * A child picks by the inputs of the run that started first. What its parent hands over does not pick: a
     * {@code context} that names the choice is a variable of the parent's making.
     */
    @Test
    void aChildWorkflowsChoice_isPickedByTheRootRunsInputs_notByTheContextItsParentHandsOver() {
        RunOutcome outcome = engine.run(parentHandingOver(Map.of("judge", "opus")), Map.of("judge", "astra"),
                options().build());

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(models(pi)).as("the root's input, not the context that says opus")
                .containsExactly("openai/gpt-6-astra");
        assertThat(claude.actionRequests()).isEmpty();
        assertThat(commands.commands()).as("the child's variables name its pick, not what was handed over")
                .containsExactly("report astra PI openai/gpt-6-astra");
    }

    @Test
    void aChildWorkflowsChoice_withoutARootInput_runsItsDefault_whateverItsParentHandsOver() {
        RunOutcome outcome = engine.run(parentHandingOver(Map.of("judge", "opus")), null, options().build());

        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(models(claude)).containsExactly("claude-fable-5-2");
        assertThat(pi.actionRequests()).isEmpty();
        assertThat(commands.commands()).containsExactly("report fable CLAUDE claude-fable-5-2");
    }

    /** A model outside the operator's list is refused in a child run too, when its step starts it. */
    @Test
    void aChildRun_isHeldToTheAllowedModelsToo() {
        AllowedModels claudeOnly = new AllowedModels(List.of(new AllowedModels.Entry("CLAUDE", null)));

        RunOutcome outcome = engine.run(parentHandingOver(Map.of()), Map.of("judge", "astra"),
                options().allowedModels(claudeOnly).build());

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.steps().getFirst().getOutput()).contains("workflow refused: Block 1 \"verdict\": the"
                + " model openai/gpt-6-astra of PI may not run in this home: allowed_models");
        assertThat(pi.actionRequests()).isEmpty();
    }

    /** The other AI paths read the picked option too: the calls of an agentic block and an AI condition. */
    @Test
    void anAgenticBlockAndAnAiCondition_runOnThePickedOption() {
        pi.otherwise("TRUE");
        WorkflowDefinition workflow = new WorkflowDefinition("wf", "Paths", null, List.of(
                block("conditional", "cond", Map.of("execution_mode", "ai", "condition", "is it so?",
                        "then_command", "then", "else_command", "else", "model_choice", "judge")),
                block("agentic", "act", Map.of("goal", "Do it", "tools", "none", "max_iterations", 1,
                        "model_choice", "judge"))), null, null, null, null, Map.of(), Map.of(),
                List.of("ai.model_choice"), null, null, Map.of("judge", judge()));

        engine.run(workflow, Map.of("judge", "astra"), options().build());

        assertThat(claude.actionRequests()).isEmpty();
        assertThat(claude.reasoningRequests()).isEmpty();
        assertThat(models(pi)).as("the condition, its branch and the agentic actions").isNotEmpty()
                .containsOnly("openai/gpt-6-astra");
        assertThat(pi.reasoningRequests()).as("the agentic block's reasoning").isNotEmpty()
                .allSatisfy(request -> assertThat(request.model()).isEqualTo("openai/gpt-6-astra"));
    }

    /**
     * A resume goes on with the picks its start recorded: with them in its options, the run does not take the
     * default of the package as it is now.
     */
    @Test
    void aResumedRun_runsThePicksItsOptionsCarry_notTheDefaultOfTheDefinitionItIsGiven() {
        RecordingRunEvents first = new RecordingRunEvents();
        WorkflowDefinition asked = workflow(Map.of("judge", judge()), Map.of(),
                block("shell", "before", Map.of("command", "report before")),
                block("simple", "verdict", Map.of("command", "Judge it", "model_choice", "judge")));
        engine.run(asked, null, options().events(first).checkpoints(true).build());
        traceworks.core.port.RunEvent.Checkpoint afterFirst = first.of(
                traceworks.core.port.RunEvent.Checkpoint.class).get(1);
        Map<String, Object> changed = new java.util.LinkedHashMap<>(judge());
        changed.put("default", "astra");
        WorkflowDefinition now = workflow(Map.of("judge", changed), Map.of(), asked.blocks().toArray(
                traceworks.core.model.BlockDefinition[]::new));
        claude.actionRequests();
        int before = claude.actionRequests().size();

        RunOutcome resumed = engine.resume(now, new ResumePoint(afterFirst.snapshot(), 1, null),
                options().resumedPicks(Map.of("judge", "fable")).build());

        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(claude.actionRequests()).hasSize(before + 1);
        assertThat(pi.actionRequests()).isEmpty();
    }

    /** A recorded pick the definition no longer has as an option refuses the resumed run before any block. */
    @Test
    void aResumedRunWhoseRecordedPickIsNoOptionAnyMore_isRefused() {
        RecordingRunEvents first = new RecordingRunEvents();
        WorkflowDefinition asked = workflow(Map.of("judge", judge()), Map.of(),
                block("shell", "before", Map.of("command", "report before")),
                block("simple", "verdict", Map.of("command", "Judge it", "model_choice", "judge")));
        engine.run(asked, null, options().events(first).checkpoints(true).build());
        traceworks.core.port.RunEvent.Checkpoint afterFirst = first.of(
                traceworks.core.port.RunEvent.Checkpoint.class).get(1);
        int before = claude.actionRequests().size();

        RunOutcome resumed = engine.resume(asked, new ResumePoint(afterFirst.snapshot(), 1, null),
                options().resumedPicks(Map.of("judge", "sonnet")).build());

        assertThat(resumed.status()).isEqualTo(RunStatus.FAILED);
        assertThat(resumed.error()).startsWith("workflow refused: the model choice \"judge\" has no option"
                + " \"sonnet\"");
        assertThat(claude.actionRequests()).hasSize(before);
    }

    @Test
    void aModelTheOperatorDoesNotAllow_refusesTheRun_alsoWhenItWasPicked() {
        AllowedModels claudeOnly = new AllowedModels(List.of(new AllowedModels.Entry("CLAUDE", null)));

        RunOutcome picked = engine.run(judging(), Map.of("judge", "astra"),
                options().allowedModels(claudeOnly).build());
        RunOutcome allowed = engine.run(judging(), Map.of("judge", "opus"),
                options().allowedModels(claudeOnly).build());

        assertThat(picked.status()).isEqualTo(RunStatus.FAILED);
        assertThat(picked.error()).isEqualTo("workflow refused: Block 1 \"verdict\": the model openai/gpt-6-astra of"
                + " PI may not run in this home: allowed_models in traceworks.json allows CLAUDE (every model)");
        assertThat(pi.actionRequests()).isEmpty();
        assertThat(allowed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(models(claude)).containsExactly("claude-opus-5-5");
    }

    @Test
    void aPlaceholderInModel_refusesTheRun_andPointsToADeclaredChoice() {
        WorkflowDefinition workflow = workflow(null, Map.of("m", "claude-opus-5-5"),
                block("simple", "ask", Map.of("command", "Ask", "model", "{{ m }}")));

        RunOutcome outcome = engine.run(workflow, null, options().build());

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.error()).startsWith("workflow refused: ").contains("Block 1 \"ask\": model \"{{ m }}\""
                + " holds a placeholder, which no run resolves").contains("model_choices");
        assertThat(outcome.steps()).extracting(StepResult::getOutput).isEmpty();
    }

    @Test
    void aChoiceWithoutItsRequirement_isRefused() {
        WorkflowDefinition workflow = new WorkflowDefinition("wf", "No requires", null, List.of(
                block("simple", "verdict", Map.of("command", "Judge it", "model_choice", "judge"))), null, null, null,
                null, Map.of(), Map.of(), List.of(), null, null, Map.of("judge", judge()));

        RunOutcome outcome = engine.run(workflow, null, options().build());

        assertThat(outcome.error()).isEqualTo("workflow refused: model_choices needs \"ai.model_choice\" in requires"
                + " — add it to the workflow's requires");
    }
}
