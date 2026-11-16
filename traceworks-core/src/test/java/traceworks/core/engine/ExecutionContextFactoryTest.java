package traceworks.core.engine;

import traceworks.core.context.ShellLineRefusedException;
import traceworks.core.container.RunPaths;
import traceworks.core.model.ContextState;
import traceworks.core.context.ExecutionContext;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.model.TriggerSource;
import traceworks.core.port.AiProviders;
import traceworks.core.port.Clock;
import traceworks.core.port.CommandRunner;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.RunEvent;
import traceworks.core.testing.RecordingRunEvents;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionContextFactoryTest {

    private static final Instant NOW = Instant.parse("2026-11-02T08:15:30Z");
    private static final Clock CLOCK = () -> NOW;

    private final ExecutionContextFactory factory = new ExecutionContextFactory(CLOCK);

    private static ExecutionContextFactory.ContextSeed seed(Map<String, Object> input, String instructions,
                                                            String model, String provider, String effort,
                                                            String resume) {
        return new ExecutionContextFactory.ContextSeed("run-1", "wf-1", "Demo", "/p", input, instructions, model,
                provider, effort, resume, null, null, null);
    }

    private static ExecutionContextFactory.ContextSeed seed(Map<String, Object> input) {
        return seed(input, null, "", "", "", null);
    }

    /** A seed whose working directory is the workspace {@code /w} of {@code paths}. */
    private static ExecutionContextFactory.ContextSeed seed(Map<String, Object> input, RunPaths paths) {
        return new ExecutionContextFactory.ContextSeed("run-1", "wf-1", "Demo", "/w", input, null, "", "", "", null,
                null, null, null, paths);
    }

    @Test
    void create_seedsInputAndSystemVariables() {
        ExecutionContext ctx = factory.create(seed(Map.of("foo", "bar")));

        assertThat(ctx.getVariable("foo")).isEqualTo("bar");
        assertThat(ctx.getVariable("workflow_run_id")).isEqualTo("run-1");
        assertThat(ctx.getVariable("workflow_id")).isEqualTo("wf-1");
        assertThat(ctx.getVariable("workflow_name")).isEqualTo("Demo");
        assertThat(ctx.getVariable("project_path")).isEqualTo("/p");
        assertThat(ctx.getVariable("execution_path")).isEqualTo("/p");
        assertThat(ctx.getProjectPath()).isEqualTo("/p");
        assertThat(ctx.getWorkflowRunId()).isEqualTo("run-1");
        assertThat(ctx.getVariable("run_timestamp")).isEqualTo("20261102-081530");
        assertThat(ctx.getVariable("started_at")).isEqualTo("2026-11-02T08:15:30Z");
        assertThat(ctx.getVariables()).as("variables Traceworks does not provide")
                .doesNotContainKeys("project_id", "home_dir", "user", "platform_scripts", "workflow_version");
    }

    /**
     * In a container run a step sees the workspace, the package and the scratch directory at fixed paths, so a command
     * reads the same on every machine; in a host run it sees the host paths. The engine keeps the host path of the
     * workspace for the command runner, which mounts it.
     */
    @Test
    void containerRunVariables_areTheFixedPaths() {
        Map<String, Object> input = Map.of("package_dir", "/host/pkg");
        ExecutionContext container = factory.create(seed(input, new RunPaths("/w", "/p", "/s", true)));
        ExecutionContext host = factory.create(seed(input, new RunPaths("/w", "/p", "/s", false)));

        assertThat(container.getVariables()).containsEntry("project_path", "/workspace")
                .containsEntry("execution_path", "/workspace").containsEntry("package_dir", "/package")
                .containsEntry("scratch_dir", "/scratch");
        assertThat(container.getProjectPath()).as("the host path, for the runner").isEqualTo("/w");
        assertThat(host.getVariables()).containsEntry("project_path", "/w").containsEntry("execution_path", "/w")
                .containsEntry("package_dir", "/p").containsEntry("scratch_dir", "/s");
        assertThat(host.getProjectPath()).isEqualTo("/w");
        assertThat(container.isContainerRun()).isTrue();
        assertThat(host.isContainerRun()).isFalse();
    }

    /**
     * The run of an audit workflow has a register folder, and {@code register_dir} is where a step sees it: the fixed
     * path in a container run, the folder's path in a host run. No input takes its place. Every other run has no
     * such variable, so the placeholder stays as written (and the analyzer refuses it).
     */
    @Test
    void registerDir_isWhereAStepSeesTheRegisterFolder_andOnlyARunWithOneHasIt() {
        Map<String, Object> input = Map.of("register_dir", "/etc");
        ExecutionContext container = factory.create(seed(input, new RunPaths("/w", "/p", "/s", "/r", true)));
        ExecutionContext host = factory.create(seed(input, new RunPaths("/w", "/p", "/s", "/r", false)));
        ExecutionContext noAudit = factory.create(seed(Map.of(), new RunPaths("/w", "/p", "/s", true)));

        assertThat(container.getVariables()).containsEntry("register_dir", "/register");
        assertThat(host.getVariables()).containsEntry("register_dir", "/r");
        assertThat(container.resolveHostTemplate("{{ register_dir }}")).isEqualTo("/r");
        assertThat(container.resolveShellTemplate("cat {{ register_dir }}/findings.jsonl", false))
                .as("a path the engine sets, trusted like scratch_dir").isEqualTo("cat /register/findings.jsonl");
        assertThat(noAudit.getVariables()).doesNotContainKey("register_dir");
        assertThat(noAudit.resolveTemplate("{{ register_dir }}")).isEqualTo("{{ register_dir }}");
        assertThat(factory.restore(seed(Map.of(), new RunPaths("/w", "/p", "/s", "/r", true)), host.state())
                .getVariables()).as("set again on a resume").containsEntry("register_dir", "/register");
    }

    /** The host side of a mount names host directories, also in a container run. */
    @Test
    void hostTemplates_resolveThePathVariablesToTheHostPaths() {
        String paths = "{{ project_path }}|{{ execution_path }}|{{ package_dir }}|{{ scratch_dir }}|{{ workflow_id }}";
        ExecutionContext container = factory.create(seed(Map.of(), new RunPaths("/w", "/p", "/s", true)));
        ExecutionContext host = factory.create(seed(Map.of(), new RunPaths("/w", "/p", "/s", false)));
        ExecutionContext withoutPaths = factory.create(seed(Map.of("package_dir", "/host/pkg")));

        assertThat(container.resolveTemplate(paths)).isEqualTo("/workspace|/workspace|/package|/scratch|wf-1");
        assertThat(container.resolveHostTemplate(paths)).isEqualTo("/w|/w|/p|/s|wf-1");
        assertThat(host.resolveHostTemplate(paths)).isEqualTo("/w|/w|/p|/s|wf-1");
        assertThat(withoutPaths.resolveHostTemplate(paths)).isEqualTo("/p|/p|/host/pkg||wf-1");
        assertThat(withoutPaths.isContainerRun()).isFalse();
    }

    @Test
    void containerRunVariables_areSetAgainOnResume() {
        ContextState state = factory.create(seed(Map.of("package_dir", "/host/pkg"))).state();

        ExecutionContext resumed = factory.restore(seed(Map.of(), new RunPaths("/w", "/p", "/s", true)), state);

        assertThat(resumed.getVariables()).containsEntry("project_path", "/workspace")
                .containsEntry("package_dir", "/package").containsEntry("scratch_dir", "/scratch");
        assertThat(resumed.isContainerRun()).isTrue();
        assertThat(resumed.resolveHostTemplate("{{ package_dir }}")).isEqualTo("/p");
    }

    /** Without run paths (host runs) the paths are what they were: no scratch directory, the package input. */
    @Test
    void withoutRunPaths_scratchDirIsEmpty_andPackageDirIsTheInput() {
        ExecutionContext ctx = factory.create(seed(Map.of("package_dir", "/host/pkg")));

        assertThat(ctx.getVariable("scratch_dir")).isEqualTo("");
        assertThat(ctx.getVariable("package_dir")).isEqualTo("/host/pkg");
        assertThat(ctx.getVariable("project_path")).isEqualTo("/p");
    }

    @Test
    void create_userSuppliedRunId_isOverwrittenBySystemValue() {
        ExecutionContext ctx = factory.create(seed(Map.of("workflow_run_id", "spoofed")));

        assertThat(ctx.getVariable("workflow_run_id")).isEqualTo("run-1");
    }

    @Test
    void create_resumePreviousOutput_winsOverInputVariable() {
        ExecutionContext ctx = factory.create(seed(Map.of("previous_output", "from-input"), null, "", "", "", "from-resume"));

        assertThat(ctx.getPreviousOutput()).isEqualTo("from-resume");
    }

    /** RUN_VARIABLES is what a run never takes over from an input: it must stay complete. */
    @Test
    void runVariables_areExactlyWhatCreateSetsOnEveryRun() {
        ExecutionContext ctx = factory.create(seed(Map.of()));

        assertThat(ctx.getVariables().keySet())
                .as("a variable create() sets itself belongs into ExecutionContextFactory.RUN_VARIABLES")
                .containsExactlyInAnyOrderElementsOf(ExecutionContextFactory.RUN_VARIABLES);
    }

    @Test
    void runVariables_inTheInputContext_neverReachTheRun() {
        Map<String, Object> input = new HashMap<>();
        ExecutionContextFactory.RUN_VARIABLES.forEach(name -> input.put(name, "spoofed"));

        ExecutionContext ctx = factory.create(seed(input));

        ExecutionContextFactory.RUN_VARIABLES.forEach(name ->
                assertThat(ctx.getVariable(name)).as(name).isNotEqualTo("spoofed"));
    }

    @Test
    void create_previousOutputFromInput_whenNotResuming() {
        ExecutionContext ctx = factory.create(seed(Map.of("previous_output", "from-input")));

        assertThat(ctx.getPreviousOutput()).isEqualTo("from-input");
    }

    @Test
    void create_injectsTheTriggerSource() {
        ExecutionContext ctx = factory.create(new ExecutionContextFactory.ContextSeed("run-1", "wf-1", "Demo", "/p",
                Map.of("trigger_source", "SPOOFED"), null, "", "", "", null, TriggerSource.API, null, null));

        assertThat(ctx.getVariable("trigger_source")).isEqualTo("API");
    }

    @Test
    void create_withoutTriggerSource_leavesTheVariableEmpty() {
        ExecutionContext ctx = factory.create(seed(Map.of()));

        assertThat(ctx.getVariable("trigger_source")).isEqualTo("");
    }

    @Test
    void create_blankInstructionsModelProviderEffort_stayNull() {
        ExecutionContext ctx = factory.create(seed(Map.of()));

        assertThat(ctx.getModel()).isNull();
        assertThat(ctx.getProvider()).isNull();
        assertThat(ctx.getEffort()).isNull();
        assertThat(ctx.getInstructionPrefix()).isNull();
    }

    @Test
    void create_nonBlankInstructionsModelProviderEffort_areApplied() {
        ExecutionContext ctx = factory.create(seed(Map.of(), "be brief", "claude-opus-4-6", "CLAUDE", "high", null));

        assertThat(ctx.getInstructionPrefix()).isEqualTo("## Workflow-Specific Instructions\nbe brief");
        assertThat(ctx.getModel()).isEqualTo("claude-opus-4-6");
        assertThat(ctx.getProvider()).isEqualTo("CLAUDE");
        assertThat(ctx.getEffort()).isEqualTo("high");
        assertThat(ctx.getVariable("model")).isEqualTo("claude-opus-4-6");
    }

    @Test
    void instructionPrefix_overlongInstructions_areCut() {
        String prefix = ExecutionContextFactory.instructionPrefix("  " + "x".repeat(10_005) + "  ");

        assertThat(prefix).isEqualTo("## Workflow-Specific Instructions\n" + "x".repeat(10_000) + "\n[... truncated]");
        assertThat(ExecutionContextFactory.instructionPrefix("   ")).isEmpty();
        assertThat(ExecutionContextFactory.instructionPrefix(null)).isEmpty();
    }

    @Test
    void instructionPrefix_instructionsOfExactlyTheLimit_areKeptWhole() {
        String prefix = ExecutionContextFactory.instructionPrefix("x".repeat(10_000));

        assertThat(prefix).isEqualTo("## Workflow-Specific Instructions\n" + "x".repeat(10_000));
    }

    /**
     * The record redacts a secret's value only where it is whole. Instructions cut in the middle of one would put
     * half of it into every AI prompt and so into the record: the cut moves in front of the value.
     */
    @Test
    void create_overlongInstructions_areCutInFrontOfAValueOfTheRunTheCutWouldSplit() {
        String whole = "deploy-login-value-1";
        ExecutionContextFactory withValue = new ExecutionContextFactory(CLOCK, null, null, null, null,
                Map.of("DEPLOY_LOGIN", whole));
        String instructions = "x".repeat(9_995) + whole + " and more";

        ExecutionContext ctx = withValue.create(seed(Map.of(), instructions, "", "", "", null));

        assertThat(ctx.getInstructionPrefix())
                .isEqualTo("## Workflow-Specific Instructions\n" + "x".repeat(9_995) + "\n[... truncated]");
    }

    @Test
    void create_bindsTheRunControlAndTheEventSink() {
        RunControl control = new RunControl();
        RecordingRunEvents events = new RecordingRunEvents();
        ExecutionContext ctx = factory.create(new ExecutionContextFactory.ContextSeed("run-1", "wf-1", "Demo", "/p",
                Map.of(), null, null, null, null, null, TriggerSource.MANUAL, events, control));

        control.pause();
        assertThat(ctx.isPaused()).isTrue();
        control.resume();
        control.cancel();
        assertThat(ctx.isPaused()).isFalse();
        assertThat(ctx.shouldStop()).isTrue();
        RunEvent event = new RunEvent.StepWaiting(0, true);
        ctx.getEvents().emit(event);
        assertThat(events.all()).containsExactly(event);
    }

    /**
     * audit_findings describes the running workflow. A parent run hands every variable to its sub-workflow; the
     * parent's findings must not survive there as a trusted, unquoted variable.
     */
    @Test
    void create_handedOverAuditFindings_areDropped() {
        ExecutionContext ctx = factory.create(seed(Map.of("audit_findings", "OPEN | HIGH | x | $(id) | y",
                "foo", "bar")));

        assertThat(ctx.getVariables()).doesNotContainKey("audit_findings").containsEntry("foo", "bar");
        assertThat(ctx.resolveShellTemplate("echo {{ audit_findings }}", false))
                .as("not provided, so the placeholder stays")
                .isEqualTo("echo {{ audit_findings }}");
    }

    /** No platform variable comes from an input context: it would be a trusted, unquoted variable there. */
    @Test
    void create_handedOverPlatformData_isDroppedEveryVariable() {
        Map<String, Object> input = new HashMap<>();
        PlatformVariables.NAMES.forEach(name -> input.put(name, "forged $(id)"));
        input.put("goal_guard_item_1", "forged $(id)");
        input.put("execution_path_other", "/elsewhere");
        input.put("foo", "bar");

        ExecutionContext ctx = factory.create(seed(input));

        assertThat(ctx.getVariables()).doesNotContainKeys(PlatformVariables.NAMES.toArray(String[]::new))
                .doesNotContainKeys("goal_guard_item_1", "execution_path_other")
                .containsEntry("foo", "bar");
        assertThat(ctx.resolveShellTemplate("echo {{ goal_status }} {{ open_todos }}", false))
                .as("not provided, so the placeholders stay")
                .isEqualTo("echo {{ goal_status }} {{ open_todos }}");
    }

    @Test
    void create_untrustedKeys_markVariablesUntrusted() {
        ExecutionContext ctx = factory.create(seed(Map.of(
                "action", "deploy",
                "env", "test",
                ExecutionContext.UNTRUSTED_KEYS_VARIABLE, List.of("action"))));

        assertThat(ctx.isUntrusted("action")).isTrue();
        assertThat(ctx.isUntrusted("env")).isFalse();
        assertThatThrownBy(() -> ctx.resolveShellTemplate("run {{ action }} {{ env }}", false))
                .isInstanceOf(ShellLineRefusedException.class);
    }

    @Test
    void create_untrustedSystemVariable_isOverwrittenByTrustedSystemValue() {
        ExecutionContext ctx = factory.create(seed(Map.of(
                "project_path", "/evil",
                ExecutionContext.UNTRUSTED_KEYS_VARIABLE, List.of("project_path"))));

        assertThat(ctx.getVariable("project_path")).isEqualTo("/p");
        assertThat(ctx.isUntrusted("project_path")).isFalse();
    }

    @Test
    void create_withoutUntrustedKeys_marksNothing() {
        ExecutionContext ctx = factory.create(seed(Map.of("foo", "bar")));

        assertThat(ctx.getUntrustedVariables()).isEmpty();
    }

    @Test
    void create_untrustedOutputs_taintPreviousOutputAndNamedBlockOutput() {
        ExecutionContext ctx = factory.create(seed(Map.of(
                        ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE,
                        List.of(ExecutionContext.PREVIOUS_OUTPUT_NAME, "scan")),
                null, "", "", "", "x; id"));
        ctx.setBlockOutput("scan", "y && z");

        assertThat(ctx.hasUntrustedInput()).isTrue();
        assertThatThrownBy(() -> ctx.resolveShellTemplate("echo {{ previous_output }} {{ block.scan.output }}", false))
                .isInstanceOf(ShellLineRefusedException.class);
    }

    @Test
    void create_untrustedOutputsWithUntrustedKeys_roundTripsAnExportedTaint() {
        ExecutionContext source = ExecutionContext.builder().workflowRunId("run-0").build();
        source.setUntrustedVariable("branch", "main; id");
        source.recordOutput("scan", "out");
        Map<String, Object> input = new HashMap<>(source.exportTaint());
        input.put("branch", "main; id");

        ExecutionContext ctx = factory.create(seed(input, null, "", "", "", "out"));

        assertThat(ctx.isUntrusted("branch")).isTrue();
        assertThatThrownBy(() -> ctx.resolveShellTemplate("echo {{ branch }} {{ previous_output }}", false))
                .isInstanceOf(ShellLineRefusedException.class);
    }

    /**
     * A run tainted because platform data ended up in a shell command line keeps the flag through export and
     * import, even without a recorded output in between.
     */
    @Test
    void create_platformDataUsedInShell_roundTripsWithoutAnyRecordedOutput() {
        ExecutionContext source = ExecutionContext.builder().workflowRunId("run-0").build();
        source.setUntrustedPlatformVariable("previous_report", "report");
        source.resolveShellValue("{{ previous_report }}");
        Map<String, Object> input = new HashMap<>(source.exportTaint());

        ExecutionContext ctx = factory.create(seed(input, null, "", "", "", "a | b"));
        ctx.setDerivedVariable("item", "a.txt; id");

        assertThat(ctx.hasUntrustedInput()).isTrue();
        assertThat(ctx.isUntrusted("item")).isTrue();
        assertThatThrownBy(() -> ctx.resolveShellTemplate("cat {{ item }}", false))
                .isInstanceOf(ShellLineRefusedException.class);
    }

    @Test
    void create_withoutUntrustedOutputs_previousOutputStaysRaw() {
        ExecutionContext ctx = factory.create(seed(Map.of("foo", "bar"), null, "", "", "", "a | b"));

        assertThat(ctx.hasUntrustedInput()).isFalse();
        assertThat(ctx.resolveShellTemplate("echo {{ previous_output }}", false)).isEqualTo("echo a | b");
    }

    @Test
    void create_chainOutput_isEscapedLikePlatformData() {
        ExecutionContext ctx = factory.create(seed(Map.of("previous_chain_output", "a; rm -rf ~")));

        assertThat(ctx.isUntrusted("previous_chain_output")).isTrue();
        assertThat(ctx.hasUntrustedInput()).isFalse(); // a taint source on use, like previous_report
        assertThatThrownBy(() -> ctx.resolveShellTemplate("echo {{ previous_chain_output }}", false))
                .isInstanceOf(ShellLineRefusedException.class);
        assertThat(ctx.hasUntrustedInput()).as("a refused line put nothing into a shell").isFalse();
        assertThat(ctx.resolveShellValue("{{ previous_chain_output }}")).isEqualTo("a; rm -rf ~");
        assertThat(ctx.hasUntrustedInput()).isTrue();
    }

    @Test
    void create_chainOutputOfATaintedRun_isUntrustedInputFromTheStart() {
        ExecutionContext ctx = factory.create(seed(Map.of("previous_chain_output", "payload",
                ExecutionContext.UNTRUSTED_KEYS_VARIABLE, List.of("previous_chain_output"))));

        assertThat(ctx.hasUntrustedInput()).isTrue();
        assertThat(ctx.getUntrustedVariables()).contains("previous_chain_output");
    }

    /** The block executors reach the run's ports through the context: command runner, AI providers and clock. */
    @Test
    void create_handsTheRunsPortsToTheContext() {
        CommandRunner runner = request -> new CommandResult(0, "", "", 1, false);
        AiProviders providers = AiProviders.of(null, List.of());

        ExecutionContext ctx = new ExecutionContextFactory(CLOCK, runner, providers).create(seed(Map.of()));

        assertThat(ctx.getCommandRunner()).isSameAs(runner);
        assertThat(ctx.getAiProviders()).isSameAs(providers);
        assertThat(ctx.getClock()).isSameAs(CLOCK);
    }

    /**
     * A context restored from a state is the context the state was taken from: every part comes back, except the run
     * variables, which the resume sets from its seed exactly as {@code create} does, so they agree with the
     * context's own settings.
     */
    /**
     * A state of a version that did not mark model text is read as "everything may be model text"
     * ({@code RunSnapshotJson}). Restored, a shell line refuses every output and every variable of the run, and
     * the run's own variables, which the resume sets anew, are trusted.
     */
    @Test
    void restore_ofAStateThatMarksEverythingAsModelText_refusesItsOutputsAndVariables_notTheRunsOwn() {
        ExecutionContext before = factory.create(seed(Map.of("topic", "the release")));
        before.recordOutput("ask", "$(touch pwned)");
        before.setDerivedVariable("step_0_output", "$(touch pwned)");
        ContextState taken = before.state();
        Set<String> all = new java.util.HashSet<>(taken.variables().keySet());
        ContextState closed = new ContextState(taken.variables(), new ContextState.Taint(Set.of(), Set.of(), Set.of(),
                false, false, false, new ContextState.ModelText(true, all, taken.blockOutputs().keySet(), true)),
                taken.blockOutputs(), taken.previousOutput(), taken.sourceSteps(), taken.nextStepIndex(),
                taken.runMetadata(), taken.rememberedApprovals());

        ExecutionContext after = factory.restore(seed(Map.of("topic", "the release")), closed);

        for (String placeholder : List.of("{{ block.ask.output }}", "{{ previous_output }}", "{{ step_0_output }}",
                "{{ topic }}")) {
            assertThatThrownBy(() -> after.resolveShellTemplate("echo " + placeholder, false)).as(placeholder)
                    .isInstanceOf(traceworks.core.context.ShellLineRefusedException.class);
        }
        assertThat(after.resolveShellTemplate("cd {{ project_path }} && echo {{ workflow_run_id }}", false))
                .as("the run's own variables").doesNotContain("{{");
        after.recordOutput("next", "more");
        assertThatThrownBy(() -> after.resolveShellTemplate("echo {{ block.next.output }}", false))
                .as("what the resumed run records").isInstanceOf(traceworks.core.context.ShellLineRefusedException.class);
    }

    /** Model text named without the flag: the run counts as one that asked a model all the same. */
    @Test
    void create_modelKeysOrModelOutputsWithoutTheFlag_countAsARunThatAskedAModel() {
        ExecutionContext keys = factory.create(seed(Map.of("handed", "x; touch pwned",
                ExecutionContext.MODEL_KEYS_VARIABLE, List.of("handed"))));
        ExecutionContext outputs = factory.create(seed(Map.of(
                ExecutionContext.MODEL_OUTPUTS_VARIABLE, List.of("ask"))));
        ExecutionContext neither = factory.create(seed(Map.of("handed", "plain")));

        assertThat(keys.modelAsked()).isTrue();
        assertThat(outputs.modelAsked()).isTrue();
        assertThat(neither.modelAsked()).isFalse();
        keys.setDerivedVariable("item", "x; touch pwned");
        assertThatThrownBy(() -> keys.resolveShellTemplate("echo {{ item }}", false))
                .isInstanceOf(traceworks.core.context.ShellLineRefusedException.class);
    }

    /**
     * What a block derives while it runs takes the mark of what went into the block so far, in a run whose state
     * says nothing: a model-text variable (set without the run having asked a model) that a value of the block
     * resolved, then a loop item derived from the command's output.
     */
    @Test
    void aVariableDerivedWhileABlockRuns_takesTheMarkOfWhatWentIntoTheBlock() {
        ExecutionContext ctx = factory.create(seed(Map.of("previous_chain_output", "x; touch pwned")));
        ctx.setModelVariable("handed", "x; touch pwned");
        assertThat(ctx.modelAsked()).isFalse();

        ctx.setDerivedVariable("before", "plain");
        ctx.resolveShellValue("{{ handed }}");
        ctx.setDerivedVariable("item", "x; touch pwned");

        assertThat(ctx.resolveShellTemplate("echo {{ before }}", false)).isEqualTo("echo plain");
        assertThatThrownBy(() -> ctx.resolveShellTemplate("echo {{ item }}", false))
                .isInstanceOf(traceworks.core.context.ShellLineRefusedException.class);
        assertThat(ctx.hasUntrustedInput()).as("model text is no untrusted input").isFalse();

        ctx.blockEnded();
        ctx.setDerivedVariable("next", "plain");
        assertThat(ctx.resolveShellTemplate("echo {{ next }}", false)).as("the next block starts with nothing")
                .isEqualTo("echo plain");

        ctx.resolvePromptTemplate("Judge {{ previous_chain_output }}");
        ctx.setDerivedVariable("from_platform", "x; touch pwned");
        assertThatThrownBy(() -> ctx.resolveShellTemplate("echo {{ from_platform }}", false))
                .isInstanceOf(traceworks.core.context.ShellLineRefusedException.class);
    }

    @Test
    void restore_bringsBackTheStateAsItWas() {
        ExecutionContext before = factory.create(seed(Map.of("ticket", "a; reboot", "previous_chain_output", "chain",
                ExecutionContext.UNTRUSTED_KEYS_VARIABLE, List.of("ticket"))));
        before.resolveShellValue("{{ previous_chain_output }}");
        before.recordOutput("build", "built");
        before.resolvePromptTemplate("Use {{ previous_chain_output }}");
        before.getSourceSteps().record("build", sourceStep(0));
        before.getSourceSteps().record("build", sourceStep(1));
        before.preAllocateStepIndex();
        before.nextStepIndex();
        before.nextStepIndex();
        before.setRunMetadata("gate_status", "RED");
        before.rememberApproval("Deploy?", "Yes always");
        ContextState state = before.state();
        before.setVariable("late", "after the state was taken");

        ExecutionContextFactory.ContextSeed resumeSeed = new ExecutionContextFactory.ContextSeed("run-1", "wf-1b",
                "Demo resumed", "/moved", Map.of(), "Be brief", "opus", "CLAUDE", "high", null, TriggerSource.RESUME,
                null, null);
        ExecutionContextFactory later = new ExecutionContextFactory(() -> NOW.plusSeconds(60));
        ExecutionContext after = later.restore(resumeSeed, state);
        ExecutionContext created = later.create(resumeSeed);

        assertThat(state.variables()).as("the state is a copy").doesNotContainKey("late");
        assertThat(state.taint()).isEqualTo(new ContextState.Taint(Set.of("ticket"), Set.of("previous_chain_output"),
                Set.of("build"), true, true, true));
        assertThat(state.blockOutputs()).containsEntry("build", "built");
        assertThat(state.sourceSteps().ambiguous()).containsExactly("build");
        assertThat(state.nextStepIndex()).isEqualTo(2);
        assertThat(state.runMetadata()).containsEntry("gate_status", "RED");
        assertThat(state.rememberedApprovals()).containsEntry("Deploy?", "Yes always");
        Map<String, Object> resumedVariables = new HashMap<>(state.variables());
        ExecutionContextFactory.RUN_VARIABLES.forEach(name -> resumedVariables.put(name, created.getVariable(name)));
        assertThat(resumedVariables).containsEntry("workflow_run_id", "run-1").containsEntry("workflow_id", "wf-1b")
                .containsEntry("workflow_name", "Demo resumed").containsEntry("project_path", "/moved")
                .containsEntry("execution_path", "/moved").containsEntry("model", "opus")
                .containsEntry("provider", "CLAUDE").containsEntry("effort", "high")
                .containsEntry("started_at", "2026-11-02T08:16:30Z").containsEntry("run_timestamp", "20261102-081630")
                .containsEntry("trigger_source", "RESUME").containsEntry("scratch_dir", "");
        assertThat(after.state()).as("the state, with the run variables create sets from the same seed")
                .isEqualTo(new ContextState(resumedVariables, state.taint(), state.blockOutputs(),
                        state.previousOutput(), state.sourceSteps(), state.nextStepIndex(), state.runMetadata(),
                        state.rememberedApprovals()));
        assertThat(after.getVariable("ticket")).isEqualTo("a; reboot");
        assertThat(after.getPreviousOutput()).isEqualTo("built");
        assertThatThrownBy(() -> after.resolveShellTemplate("echo {{ block.build.output }} {{ ticket }}", false))
                .isInstanceOf(ShellLineRefusedException.class);
        assertThat(after.nextStepIndex()).isEqualTo(2);
        assertThat(after.getRememberedApproval("Deploy?")).isEqualTo("Yes always");
        assertThat(after.getWorkflowRunId()).isEqualTo("run-1");
        assertThat(after.getProjectPath()).as("the field agrees with project_path").isEqualTo("/moved");
        assertThat(after.getInstructionPrefix()).isEqualTo(ExecutionContextFactory.INSTRUCTIONS_HEADING + "Be brief");
        assertThat(after.getModel()).as("the field agrees with model").isEqualTo("opus");
        assertThat(after.getProvider()).as("the field agrees with provider").isEqualTo("CLAUDE");
        assertThat(after.getEffort()).as("the field agrees with effort").isEqualTo("high");
    }

    /**
     * {@code workspace_source_sha} is a run variable: the commit of the run's checkout of its
     * source, else empty. No input sets it, and a resume sets it again from its seed.
     */
    @Test
    void runVariable_workspaceSourceSha_isTheCheckoutsCommit_orEmpty() {
        String sha = "4b825dc642cb6eb9a060e54bf8d69288fbee4904";
        ExecutionContextFactory.ContextSeed isolated = new ExecutionContextFactory.ContextSeed("run-1", "wf-1",
                "Demo", "/w", Map.of("workspace_source_sha", "forged"), null, "", "", "", null, null, null, null, null,
                sha);

        assertThat(factory.create(seed(Map.of())).getVariable("workspace_source_sha")).isEqualTo("");
        assertThat(factory.create(seed(Map.of("workspace_source_sha", "forged"))).getVariable("workspace_source_sha"))
                .isEqualTo("");
        assertThat(factory.create(isolated).getVariable("workspace_source_sha")).isEqualTo(sha);
        ContextState state = factory.create(seed(Map.of())).state();
        assertThat(factory.restore(isolated, state).getVariable("workspace_source_sha")).isEqualTo(sha);
        assertThat(ExecutionContextFactory.RUN_VARIABLES).contains("workspace_source_sha");
    }

    private static StepResult sourceStep(int index) {
        return StepResult.builder().id("step-" + index).stepIndex(index).status(StepStatus.COMPLETED).build();
    }

    /** As {@code audit_findings}: the items of a register are the register's to set, never an input's. */
    @Test
    void create_handedOverAuditItems_areDropped() {
        ExecutionContext ctx = factory.create(seed(Map.of("audit_items", "clean | aa | $(id)", "foo", "bar")));

        assertThat(ctx.getVariables()).doesNotContainKey("audit_items").containsEntry("foo", "bar");
        assertThat(ctx.resolveShellTemplate("echo {{ audit_items }}", false)).isEqualTo("echo {{ audit_items }}");
    }

    /**
     * After a resume the package directory is the run's own, not a step's: it does not count against the budget,
     * whatever its value; a variable a step set does.
     */
    @Test
    void setByAStep_isNeverThePackageDirectory_norAStepOutput_butAVariableAStepSet() {
        assertThat(ExecutionContextFactory.setByAStep(ExecutionContextFactory.PACKAGE_DIR, "/package", Map.of()))
                .isFalse();
        assertThat(ExecutionContextFactory.setByAStep("step_0_output", "x", Map.of())).isFalse();
        assertThat(ExecutionContextFactory.setByAStep("report", "x", Map.of())).isTrue();
    }
}
