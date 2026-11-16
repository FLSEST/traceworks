package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.model.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QuotingRulesTest {

    private static BlockDefinition block(String type, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName("b");
        b.setConfig(new HashMap<>(config));
        return b;
    }

    private static BlockDefinition shell(String command) {
        return block("shell", Map.of("command", command));
    }

    private static BlockDefinition escapedShell(String command) {
        return block("shell", Map.of("command", command, "shell_escape_variables", true));
    }

    private static List<AnalyzerFinding> apply(BlockDefinition... blocks) {
        List<AnalyzerFinding> out = new ArrayList<>();
        QuotingRules.apply(List.of(blocks), out);
        return out;
    }

    private static List<AnalyzerFinding> rule(String rule, List<AnalyzerFinding> findings) {
        return findings.stream().filter(f -> f.rule().equals(rule)).toList();
    }

    @Test
    void should_reportAQuotedBlockOutput_asWarning() {
        assertThat(apply(shell("RESULT='{{ previous_output }}'\necho \"{{ block.plan.output }} {{ step_2_output }}\"")))
                .singleElement().satisfies(f -> {
                    assertThat(f.rule()).isEqualTo("quoted-placeholder");
                    assertThat(f.severity()).isEqualTo(Severity.WARNING);
                    assertThat(f.detail()).startsWith("puts {{ previous_output }}, {{ block.plan.output }}, "
                            + "{{ step_2_output }} inside quotes");
                });
    }

    /**
     * Chain output and the platform data Traceworks keeps ({@code previous_report}, {@code audit_findings},
     * {@code audit_items}) are outside text, which never goes into shell code: an error wherever it stands, bare,
     * inside either quotes, with or without escaping. The quotes around an inserted output are a warning of their
     * own. The TODO and goal variables are no outside text: Traceworks does not provide them
     * ({@code unprovided-variable} reports them).
     */
    @Test
    void should_reportOutsideTextAnywhereInShellCode_asError() {
        List<AnalyzerFinding> findings = apply(shell("echo 'CHAIN={{ previous_chain_output }}'\nPREV=\"{{ previous_report }}\"\n"
                + "printf '%s' {{ audit_findings }} > known.txt; echo '{{ previous_output }}' # {{ audit_items }}"));

        assertThat(rule("protected-value-in-shell", findings)).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.ERROR);
            assertThat(f.detail()).isEqualTo("puts {{ previous_chain_output }}, {{ previous_report }},"
                    + " {{ audit_findings }}, {{ audit_items }} into shell code, text from outside the run — text a"
                    + " model wrote and text from outside the run never go into shell code, quoted or not: a model"
                    + " reads the workspace, and what it reads can steer its answer; a run refuses this shell line"
                    + " before anything runs, so Traceworks refuses the workflow. Correct: hand the value to the"
                    + " command as a variable: \"values\": {\"PREVIOUS_CHAIN_OUTPUT\": \"{{ previous_chain_output }}\"}"
                    + " in this block's config, and \"$PREVIOUS_CHAIN_OUTPUT\" where the placeholder stands");
        });
        assertThat(rule("quoted-placeholder", findings)).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.WARNING);
            assertThat(f.detail()).startsWith("puts {{ previous_output }} inside quotes in shell code");
        });
        assertThat(rule("protected-value-in-shell", apply(escapedShell("printf '%s\\n' {{ previous_report }}"))))
                .as("escaping every value does not make it shell-safe to put it there").hasSize(1);
        assertThat(apply(block("shell", Map.of("values", Map.of("KNOWN", "{{ audit_findings }}",
                "CHAIN", "{{ previous_chain_output }}"), "command", "printf '%s' \"$KNOWN\" \"$CHAIN\""))))
                .as("under values, any placeholder").isEmpty();
        assertThat(apply(shell("echo 'TITLE={{ todo_title }}' '{{ open_todos }}' '{{ goal_status }}'"))).isEmpty();
    }

    @Test
    void should_acceptUnquotedPlaceholders_andTheGuidesSetForm() {
        assertThat(apply(escapedShell("set -- {{ branch }}; BRANCH=\"$*\"\nRESULT={{ previous_output }}\n"
                + "printf '%s\\n' {{ block.build.output }}"))).isEmpty();
    }

    @Test
    void should_leaveOrdinaryVariablesInQuotesAlone_withoutEscaping() {
        assertThat(apply(shell("cd '{{ project_path }}' && echo \"{{ workflow_run_id }}\""))).isEmpty();
    }

    @Test
    void should_reportAnyPlaceholderInSingleQuotes_whenTheBlockEscapesEverything() {
        assertThat(apply(escapedShell("cd '{{ project_path }}'\n[ \"$X\" = \"{{ x }}\" ]")))
                .singleElement().satisfies(f -> {
                    assertThat(f.rule()).isEqualTo("quoted-placeholder");
                    assertThat(f.severity()).isEqualTo(Severity.WARNING);
                    assertThat(f.detail()).startsWith("puts {{ project_path }} inside quotes");
                });
    }

    /** Quotes in a heredoc body mean nothing to the shell; what reads the body is heredoc-placeholder's business. */
    @Test
    void should_ignoreHeredocBodiesAndComments() {
        assertThat(rule("quoted-placeholder", apply(shell("# keeps '{{ previous_output }}'\npython3 - <<'PY'\n"
                + "print('{{ previous_output }}')\nPY")))).isEmpty();
    }

    @Test
    void should_ignoreAiPrompts_butCheckShellModeAndItemsCommand() {
        assertThat(apply(block("simple", Map.of("command", "Summarise '{{ previous_output }}'")))).isEmpty();
        assertThat(apply(block("foreach", Map.of("items_command", "printf '%s' '{{ previous_output }}'",
                "command", "Look at '{{ item }}'")))).singleElement()
                .satisfies(f -> assertThat(f.rule()).isEqualTo("quoted-placeholder"));
        assertThat(apply(block("retry", Map.of("execution_mode", "shell",
                "command", "echo '{{ previous_chain_output }}'"))))
                .singleElement().satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.ERROR));
    }

    @Test
    void should_checkBlocksNestedInASwitchCase() {
        Map<String, Object> inner = Map.of("type", "shell", "name", "inner",
                "config", Map.of("command", "echo '{{ block.ask.output }}'"));
        assertThat(apply(block("switch", Map.of("expression", "x",
                "cases", List.of(Map.of("value", "a", "blocks", List.of(inner)))))))
                .singleElement().satisfies(f -> assertThat(f.rule()).isEqualTo("quoted-placeholder"));
    }

    @Test
    void should_reportAComparisonWithThePlaceholderTheVariableCameFrom() {
        List<AnalyzerFinding> findings = apply(shell("WAVE='{{ wave_name }}'\nset -- {{ scope }}; SCOPE=\"$*\"\n"
                + "if [ -z \"$WAVE\" ] || [ \"$WAVE\" = '{{ wave_name }}' ]; then WAVE=x; fi\n"
                + "[ \"$SCOPE\" != \"{{ scope }}\" ] && echo set"));

        assertThat(rule("placeholder-self-compare", findings)).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.WARNING);
            assertThat(f.detail()).startsWith("tests $WAVE = {{ wave_name }}, $SCOPE = {{ scope }} to tell");
        });
    }

    @Test
    void should_acceptTheComparison_whenTheBlockEscapes_orTheSidesDiffer() {
        assertThat(rule("placeholder-self-compare", apply(
                escapedShell("set -- {{ x }}; X=\"$*\"\n[ \"$X\" = \"{{ x }}\" ] && X=default")))).isEmpty();
        assertThat(rule("placeholder-self-compare", apply(
                shell("X='{{ x }}'\n[ \"$X\" = '{{ expected }}' ] && echo match")))).isEmpty();
        assertThat(rule("placeholder-self-compare", apply(
                shell("X='{{ x }}'\ncase \"$X\" in '{{ '*) X=default;; esac")))).isEmpty();
    }

    // --- heredoc-placeholder ------------------------------------------------------------------------------------

    @Test
    void should_reportFreeTextInInterpreterSource_asError() {
        List<AnalyzerFinding> findings = apply(shell("mkdir -p out && python3 - <<'PY'\n"
                + "content = '''{{ previous_output }}'''\nroot = '{{ report_dir }}'\nPY\n"
                + "python3 << 'PYEOF'\nprint(\"{{ block.plan.output }}\")\nPYEOF"));

        assertThat(rule("heredoc-placeholder", findings)).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.ERROR);
            assertThat(f.detail()).startsWith("puts {{ previous_output }}, {{ block.plan.output }} into the body of"
                    + " a heredoc that python3 reads as its program");
            assertThat(f.detail()).contains("X={{ previous_output }} python3 - <<'PY'");
        });
        assertThat(rule("quoted-placeholder", findings)).isEmpty();
    }

    @Test
    void should_reportFreeTextPipedIntoAShell_asError() {
        assertThat(apply(shell("cat <<'EOF' | sh\necho {{ previous_output }}\nEOF"))).singleElement()
                .satisfies(f -> {
                    assertThat(f.rule()).isEqualTo("heredoc-placeholder");
                    assertThat(f.severity()).isEqualTo(Severity.ERROR);
                    assertThat(f.detail()).startsWith("puts {{ previous_output }} into the body of a heredoc"
                            + " that sh reads");
                });
    }

    /** Files, script input, inline code, command substitution, a grep check. */
    @Test
    void should_acceptQuotedHeredocs_whoseReaderTakesTheBodyAsInput() {
        assertThat(apply(shell("cat > \"$OUT/raw.txt\" <<'RAW'\n{{ previous_output }}\nRAW\n"
                + "python3 -I bin/backlog.py candidates --max \"$MAX\" <<'JIRA'\n{{ block.jira_issues.output }}\nJIRA\n"
                + "python3 -c \"\nimport sys\nprint(sys.stdin.read())\n\" <<'IN'\n{{ step_0_output }}\nIN\n"
                + "BODY=$(cat <<'B'\n{{ previous_output }}\nB\n)\n"
                + "if cat <<\\STEP | grep -qiE \"error|failed\"\n{{ step_1_output }}\nSTEP\nthen echo red; fi"))).isEmpty();
    }

    @Test
    void should_leaveOrdinaryVariablesInInterpreterSourceAlone_withoutEscaping() {
        assertThat(apply(shell("python3 - <<'PY'\nroot = '{{ project_path }}'\nrun = '{{ workflow_run_id }}'\nPY")))
                .isEmpty();
    }

    @Test
    void should_reportAnOutputInAnUnquotedHeredoc_asWarning() {
        assertThat(apply(shell("cat > report.md <<EOF\n{{ previous_output }}\nEOF"))).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("heredoc-placeholder");
            assertThat(f.severity()).isEqualTo(Severity.WARNING);
            assertThat(f.detail()).startsWith("puts {{ previous_output }} into the body of a heredoc with an unquoted"
                    + " delimiter (<<EOF)");
        });
        assertThat(apply(shell("cat <<-END\n\t{{ block.plan.output }} {{ step_2_output }}\n\tEND")))
                .singleElement().satisfies(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.WARNING);
                    assertThat(f.detail()).startsWith("puts {{ block.plan.output }}, {{ step_2_output }} into"
                            + " the body");
                    assertThat(f.detail()).contains("quote the delimiter (<<'END')");
                });
        assertThat(apply(shell("cat <<EOF\nin {{ project_path }}\nEOF"))).isEmpty();
    }

    @Test
    void should_reportAnyPlaceholderInAHeredocBody_whenTheBlockEscapesEverything() {
        assertThat(apply(escapedShell("set -- {{ previous_output }}; PREV=\"$*\"\ncat > out.txt <<'EOF'\n"
                + "{{ report_dir }}\nEOF"))).singleElement().satisfies(f -> {
                    assertThat(f.rule()).isEqualTo("heredoc-placeholder");
                    assertThat(f.severity()).isEqualTo(Severity.WARNING);
                    assertThat(f.detail()).startsWith("puts {{ report_dir }} into the body of a heredoc although the"
                            + " block sets shell_escape_variables");
                });
    }

    @Test
    void should_acceptTheGuidesForm_valueOnTheShellLine() {
        assertThat(apply(escapedShell("LOG={{ previous_output }} python3 - <<'PY'\nimport os\n"
                + "print(os.environ['LOG'])\nPY"))).isEmpty();
    }

    @Test
    void should_reportOneFindingPerKind_inShellModeBlocks() {
        List<AnalyzerFinding> findings = apply(block("foreach", Map.of("execution_mode", "shell",
                "command", "python3 - <<'PY'\nprint('{{ previous_output }}')\nPY\ncat <<EOF\n{{ block.a.output }}\nEOF")));

        assertThat(rule("heredoc-placeholder", findings)).extracting(AnalyzerFinding::severity)
                .containsExactly(Severity.ERROR, Severity.WARNING);
    }

    /** A shift in arithmetic once opened a "heredoc" that swallowed the rest of the script. */
    @Test
    void should_notTakeAShiftInArithmeticForAHeredoc() {
        assertThat(apply(shell("echo $(( 1 << n ))\necho '{{ previous_output }}'"))).singleElement()
                .satisfies(f -> {
                    assertThat(f.rule()).isEqualTo("quoted-placeholder");
                    assertThat(f.severity()).isEqualTo(Severity.WARNING);
                });
    }

    @Test
    void should_seeTheInterpreter_pastARedirectionWithAnAmpersand() {
        for (String redirect : List.of("2>&1", "&>out.log")) {
            assertThat(apply(shell("python3 - " + redirect + " <<'PY'\nx = '''{{ previous_output }}'''\nPY")))
                    .as(redirect).singleElement().satisfies(f -> {
                        assertThat(f.rule()).isEqualTo("heredoc-placeholder");
                        assertThat(f.severity()).isEqualTo(Severity.ERROR);
                    });
        }
    }

    /**
     * An unknown {@code execution_mode} is {@code invalid-config-value}'s finding; this rule must not throw on it
     * (the executor's check would throw) and reads such a block as not in shell mode.
     */
    @Test
    void should_notThrow_onAnUnknownExecutionMode() {
        for (String mode : List.of("claude", "shel")) {
            assertThat(apply(block("retry", Map.of("execution_mode", mode,
                    "command", "echo '{{ previous_chain_output }}'")))).as(mode).isEmpty();
        }
    }

    // -- protected-value-in-shell --------------------------------------------------------------------------

    private static BlockDefinition named(String type, String name, Map<String, Object> config) {
        BlockDefinition b = block(type, config);
        b.setName(name);
        return b;
    }

    private static final BlockDefinition REVIEW = named("simple", "review", Map.of("command", "Review the diff"));

    private static List<AnalyzerFinding> protectedInShell(BlockDefinition... blocks) {
        return rule("protected-value-in-shell", apply(blocks));
    }

    @Test
    void should_reportTheOutputOfAnAiStepInShellCode_asError() {
        assertThat(protectedInShell(REVIEW, shell("printf '%s' {{ block.review.output }} | ./report")))
                .singleElement().satisfies(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.ERROR);
                    assertThat(f.blockIndex()).isEqualTo(1);
                    assertThat(f.detail()).isEqualTo("puts {{ block.review.output }} into shell code, the output of"
                            + " the AI step review — text a model wrote and text from outside the run never go into"
                            + " shell code, quoted or not: a model reads the workspace, and what it reads can steer"
                            + " its answer; a run refuses this shell line before anything runs, so Traceworks refuses"
                            + " the workflow. Correct: hand the value to the command as a variable: \"values\":"
                            + " {\"REVIEW\": \"{{ block.review.output }}\"} in this block's config, and \"$REVIEW\""
                            + " where the placeholder stands");
                });
    }

    /**
     * What a model wrote never goes into shell code, wherever its placeholder stands: bare, inside either quotes, in
     * a comment, in a heredoc of any kind, with or without escaping. One finding per block, of this rule alone: the
     * rules for the quotes around an inserted value say nothing of it. Under {@code values} it is fine.
     */
    @Test
    void should_reportTheOutputOfAnAiStep_whereverItStandsInShellCode_withOrWithoutEscaping() {
        for (String command : List.of("echo {{ block.review.output }}", "echo \"{{ block.review.output }}\"",
                "X='{{ previous_output }}'", "true # {{ step_0_output }}",
                "cat > out.md <<'EOF'\n{{ block.review.output }}\nEOF", "cat <<EOF\n{{ block.review.output }}\nEOF",
                "python3 - <<'PY'\nprint(\"{{ block.review.output }}\")\nPY",
                "echo \"$(printf '%s' {{ block.review.output }})\"")) {
            for (BlockDefinition use : List.of(shell(command), escapedShell(command))) {
                assertThat(apply(REVIEW, use)).as(command).singleElement().satisfies(f -> {
                    assertThat(f.rule()).isEqualTo("protected-value-in-shell");
                    assertThat(f.severity()).isEqualTo(Severity.ERROR);
                });
            }
        }
        assertThat(apply(REVIEW, block("shell", Map.of("values", Map.of("REVIEW", "{{ block.review.output }}"),
                "command", "printf '%s' \"$REVIEW\" > out.md")))).isEmpty();
        assertThat(rule("quoted-placeholder", apply(named("shell", "review", Map.of("command", "make")),
                shell("echo '{{ block.review.output }}'")))).as("the output of a step of a workflow without AI")
                .singleElement().satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.WARNING));
    }

    /**
     * From its first AI step on a run protects every output: a later shell step can print what the model wrote without
     * naming it. So the output of a step after the AI step is refused the same way, and one before it is not.
     */
    @Test
    void should_reportTheOutputOfAStepAfterAnAiStep_andLeaveTheStepsBeforeIt() {
        BlockDefinition list = named("shell", "list", Map.of("command", "ls"));
        BlockDefinition build = named("shell", "build", Map.of("command", "make"));

        assertThat(protectedInShell(list, REVIEW, build, shell("for f in {{ block.list.output }}; do echo; done")))
                .isEmpty();
        assertThat(protectedInShell(list, REVIEW, build, shell("echo {{ block.build.output }}"))).singleElement()
                .satisfies(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.ERROR);
                    assertThat(f.detail()).startsWith("puts {{ block.build.output }} into shell code, the output"
                            + " of the step build, which runs after the AI step review and may hold what the model"
                            + " wrote — ");
                });
        assertThat(apply(list, REVIEW, build, shell("echo '{{ previous_output }}'"))).singleElement()
                .satisfies(f -> assertThat(f.rule()).isEqualTo("protected-value-in-shell"));
        assertThat(protectedInShell(list, REVIEW, build, escapedShell("echo {{ block.build.output }}")))
                .as("escaping every value does not make it shell-safe to put it there").hasSize(1);
    }

    /** A child workflow that asks a model hands its answer on as the step's output; one that asks none does not. */
    @Test
    void should_followAChildWorkflow_toItsAiSteps_whenTheAnalyzerCanFindIt() {
        traceworks.core.testing.MapWorkflowResolver children = new traceworks.core.testing.MapWorkflowResolver()
                .add("judge", workflow("judge", REVIEW))
                .add("outer", workflow("outer", named("sub-workflow", "inner", Map.of("workflow_id", "judge"))))
                .add("plain", workflow("plain", named("shell", "build", Map.of("command", "make"))));
        BlockDefinition use = shell("echo {{ block.child.output }}");

        for (String asking : List.of("judge", "outer")) {
            List<AnalyzerFinding> out = new ArrayList<>();
            QuotingRules.apply(List.of(named("sub-workflow", "child", Map.of("workflow_id", asking)), use), children,
                    out);
            assertThat(rule("protected-value-in-shell", out)).as(asking).singleElement()
                    .satisfies(f -> assertThat(f.detail()).contains("the output of the AI step child"));
        }
        List<AnalyzerFinding> out = new ArrayList<>();
        QuotingRules.apply(List.of(named("sub-workflow", "child", Map.of("workflow_id", "plain")), use,
                named("foreach", "each", Map.of("items", List.of("a"), "workflow_id", "judge")),
                shell("echo {{ block.each.output }}")), children, out);
        assertThat(rule("protected-value-in-shell", out)).singleElement()
                .satisfies(f -> assertThat(f.blockIndex()).isEqualTo(3));
        assertThat(protectedInShell(named("sub-workflow", "child", Map.of("workflow_id", "judge")), use))
                .as("without a resolver the child is not looked into: the run quotes").isEmpty();
        assertThat(rule("protected-value-in-shell", new WorkflowAnalyzer().analyze(workflow("parent",
                named("sub-workflow", "child", Map.of("workflow_id", "judge")), use), children)))
                .as("the analyzer hands its resolver on").hasSize(1);
    }

    private static traceworks.core.model.WorkflowDefinition workflow(String id, BlockDefinition... blocks) {
        return new traceworks.core.model.WorkflowDefinition(id, id, null, List.of(blocks), null, null, null, null,
                Map.of(), Map.of(), null, null, null);
    }

    /** previous_output and step_N_output name the AI step too, and every AI type counts. */
    @Test
    void should_followPreviousOutputAndStepIndex_toEveryAiType() {
        assertThat(protectedInShell(named("agentic", "fix", Map.of("goal", "Fix it")), shell("echo {{ previous_output }}")))
                .hasSize(1);
        assertThat(protectedInShell(named("compress-ai", "short", Map.of("source", "x")), shell("echo {{ step_0_output }}")))
                .hasSize(1);
        for (String type : List.of("foreach", "retry", "while", "conditional")) {
            assertThat(protectedInShell(named(type, "loop", Map.of("execution_mode", "ai", "command", "Judge")),
                    shell("echo {{ block.loop.output }}"))).as(type).hasSize(1);
        }
    }

    @Test
    void should_notReport_theOutputOfADeterministicStep() {
        assertThat(protectedInShell(named("shell", "build", Map.of("command", "make")), shell("echo {{ previous_output }}")))
                .isEmpty();
        assertThat(protectedInShell(named("foreach", "lint", Map.of("execution_mode", "shell", "command", "lint")),
                shell("echo {{ block.lint.output }}"))).isEmpty();
        assertThat(protectedInShell(named("simple", "probe", Map.of("execution_mode", "shell", "command", "ls")),
                shell("echo {{ block.probe.output }}"))).isEmpty();
    }

    /** The step index of a wrapper itself is known, as long as no block before it draws further indices. */
    @Test
    void should_followAStepIndex_toAWrapperInAiMode_atThatIndex() {
        assertThat(protectedInShell(named("foreach", "each", Map.of("execution_mode", "ai", "items", List.of("a"),
                "command", "Judge {{ item }}")), shell("echo {{ step_0_output }}"))).hasSize(1);
    }

    /**
     * A wrapper draws further step indices, so after one the block list does not tell which step step_N_output is.
     * In a workflow that asks a model before the block it may be what the model wrote: the same error. Before the
     * first AI step, and in a workflow without one, it is not.
     */
    @Test
    void should_reportAStepIndexAfterAWrapper_whenAModelIsAskedBeforeTheBlock() {
        BlockDefinition lint = named("foreach", "lint", Map.of("execution_mode", "shell", "items", List.of("a"),
                "command", "lint"));
        assertThat(protectedInShell(lint, REVIEW, shell("echo {{ step_1_output }}"))).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.ERROR);
            assertThat(f.detail()).startsWith("puts {{ step_1_output }} into shell code, an output whose step"
                    + " the workflow does not tell, in a run that asks a model before it (the AI step review), so it"
                    + " may hold what the model wrote — ");
        });
        assertThat(apply(lint, REVIEW, shell("echo '{{ step_4_output }}'"))).singleElement()
                .satisfies(f -> assertThat(f.rule()).isEqualTo("protected-value-in-shell"));
        assertThat(protectedInShell(lint, shell("echo {{ step_1_output }}"), REVIEW)).as("before the AI step").isEmpty();
        assertThat(protectedInShell(lint, shell("echo {{ step_1_output }}"))).as("no AI step").isEmpty();
    }

    /** A shell text of a nested block counts too; its previous_output may be an inner block's, an untold step. */
    @Test
    void should_checkNestedShellTexts_byName_andTheirPreviousOutput() {
        Map<String, Object> inner = Map.of("type", "shell", "name", "send", "config",
                Map.of("command", "notify {{ block.review.output }} {{ previous_output }}"));
        BlockDefinition loop = named("foreach", "each", Map.of("execution_mode", "shell", "items", List.of("a"),
                "blocks", List.of(inner)));
        assertThat(protectedInShell(REVIEW, loop)).singleElement().satisfies(f -> assertThat(f.detail())
                .startsWith("puts {{ block.review.output }}, {{ previous_output }} into shell code"));
        assertThat(protectedInShell(loop, REVIEW)).as("before the AI step, previous_output is none of its answers")
                .singleElement().satisfies(f -> assertThat(f.detail()).doesNotContain("previous_output"));
    }

    /**
     * previous_output of a shell block nested after an AI block in the same switch case is that block's answer, and
     * the output of a nested shell block that runs after a nested AI block may hold it: both are refused. A nested
     * block written before the AI block of a switch is not; in a loop, where the blocks run again, it is.
     */
    @Test
    void should_reportNestedBlocksAfterANestedAiBlock_inTheWrapperThatAsksFirst() {
        Map<String, Object> probe = Map.of("type", "shell", "name", "probe", "config", Map.of("command", "ls"));
        Map<String, Object> draft = Map.of("type", "simple", "name", "draft", "config", Map.of("command", "Draft"));
        Map<String, Object> echo = Map.of("type", "shell", "name", "echo", "config",
                Map.of("command", "echo '{{ previous_output }}'"));
        Map<String, Object> store = Map.of("type", "shell", "name", "store", "config", Map.of("command", "cat"));
        BlockDefinition route = named("switch", "route", Map.of("expression", "x",
                "cases", List.of(Map.of("value", "a", "blocks", List.of(probe, draft, echo, store)))));

        assertThat(apply(route)).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("protected-value-in-shell");
            assertThat(f.blockIndex()).isZero();
        });
        assertThat(protectedInShell(route, shell("echo {{ block.store.output }}"))).filteredOn(f -> f.blockIndex() == 1)
                .singleElement()
                .satisfies(f -> assertThat(f.detail()).contains("the output of the step store, which runs after the"
                        + " AI step route"));
        assertThat(protectedInShell(route, shell("echo {{ block.probe.output }}"))).filteredOn(f -> f.blockIndex() == 1)
                .as("written before the AI block").isEmpty();

        BlockDefinition loop = named("foreach", "each", Map.of("execution_mode", "shell", "items", List.of("a", "b"),
                "blocks", List.of(probe, draft)));
        assertThat(protectedInShell(loop, shell("echo {{ block.probe.output }}"))).as("a loop runs probe again").hasSize(1);
    }

    @Test
    void should_checkTheShellFieldsOfAWrapper_suchAsItsItemsCommand() {
        assertThat(protectedInShell(REVIEW, named("foreach", "each", Map.of("execution_mode", "ai",
                "items_source", "command", "items_command", "printf '%s\\n' {{ block.review.output }}",
                "command", "Judge {{ item }}")))).hasSize(1);
    }

    /** A switch whose cases hold an AI step writes AI text, and so does an AI actor deciding a human-input step. */
    @Test
    void should_countBlocksWhoseNestedStepsOrActorAreAi() {
        Map<String, Object> caseBlock = Map.of("type", "simple", "name", "draft", "config", Map.of("command", "Draft"));
        BlockDefinition route = named("switch", "route", Map.of("expression", "x",
                "cases", List.of(Map.of("value", "a", "blocks", List.of(caseBlock)))));
        assertThat(protectedInShell(route, shell("echo {{ block.route.output }}"))).hasSize(1);
        assertThat(protectedInShell(named("human-input", "ask", Map.of("question", "Why?", "actor", "ai")),
                shell("echo {{ block.ask.output }}"))).hasSize(1);
    }

    /** The first block has no step before it, so its previous_output is no AI step's. */
    @Test
    void should_notReport_previousOutputInTheFirstBlock() {
        assertThat(protectedInShell(shell("echo {{ previous_output }}"), REVIEW)).isEmpty();
    }

    /** block.<name>.output names a block nested in a switch too, the one of that name, not another case block. */
    @Test
    void should_followABlockName_toTheAiStepNestedInASwitch() {
        Map<String, Object> probe = Map.of("type", "simple", "name", "probe",
                "config", Map.of("execution_mode", "shell", "command", "ls"));
        Map<String, Object> draft = Map.of("type", "simple", "name", "draft", "config", Map.of("command", "Draft"));
        BlockDefinition route = named("switch", "route", Map.of("expression", "x",
                "cases", List.of(Map.of("value", "a", "blocks", List.of(probe, draft)))));

        assertThat(protectedInShell(route, shell("echo {{ block.draft.output }}"))).singleElement()
                .satisfies(f -> assertThat(f.detail()).contains("the output of the AI step draft"));
        assertThat(protectedInShell(route, shell("echo {{ block.probe.output }}"))).as("a nested step in shell mode")
                .isEmpty();
    }

    /** step_N_output past deterministic blocks still names the block at position N. */
    @Test
    void should_followAStepIndex_pastTheDeterministicBlocksBeforeIt() {
        assertThat(protectedInShell(named("shell", "build", Map.of("command", "make")), REVIEW,
                shell("echo {{ step_1_output }}"))).singleElement()
                .satisfies(f -> assertThat(f.detail()).contains("the output of the AI step review"));
    }

    /** A block's own step index is no earlier step: its output does not exist yet when its fields resolve. */
    @Test
    void should_notReport_theStepIndexOfTheBlockItself() {
        assertThat(protectedInShell(named("foreach", "each", Map.of("execution_mode", "ai", "items_source", "command",
                "items_command", "printf '%s\\n' {{ step_0_output }}", "command", "Judge {{ item }}")))).isEmpty();
    }

}
