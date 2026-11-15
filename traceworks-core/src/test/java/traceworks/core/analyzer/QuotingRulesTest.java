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
     * Chain output and the platform data Traceworks keeps ({@code previous_report}, {@code audit_findings}) are
     * outside text. The TODO and goal variables are not: Traceworks does not provide them
     * ({@code unprovided-variable} reports them).
     */
    @Test
    void should_reportChainOutputAndPlatformData_asErrorInOneFinding() {
        assertThat(apply(shell("echo 'CHAIN={{ previous_chain_output }}'\nPREV='{{ previous_report }}'\n"
                + "echo '{{ audit_findings }}' '{{ previous_output }}'")))
                .singleElement().satisfies(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.ERROR);
                    assertThat(f.detail()).startsWith("puts {{ previous_chain_output }}, {{ previous_report }}, "
                            + "{{ audit_findings }}, {{ previous_output }} inside quotes in shell code");
                });
        assertThat(apply(shell("echo 'TITLE={{ todo_title }}' '{{ open_todos }}' '{{ goal_status }}'"))).isEmpty();
    }

    @Test
    void should_acceptUnquotedPlaceholders_andTheGuidesSetForm() {
        assertThat(apply(escapedShell("set -- {{ previous_chain_output }}; CHAIN=\"$*\"\nRESULT={{ previous_output }}\n"
                + "printf '%s\\n' {{ previous_report }}"))).isEmpty();
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
        assertThat(apply(shell("cat <<'EOF' | sh\necho {{ previous_chain_output }}\nEOF"))).singleElement()
                .satisfies(f -> {
                    assertThat(f.rule()).isEqualTo("heredoc-placeholder");
                    assertThat(f.severity()).isEqualTo(Severity.ERROR);
                    assertThat(f.detail()).startsWith("puts {{ previous_chain_output }} into the body of a heredoc"
                            + " that sh reads");
                });
    }

    /** Files, script input, inline code, command substitution, a grep check. */
    @Test
    void should_acceptQuotedHeredocs_whoseReaderTakesTheBodyAsInput() {
        assertThat(apply(shell("cat > \"$OUT/raw.txt\" <<'RAW'\n{{ previous_output }}\nRAW\n"
                + "python3 -I bin/backlog.py candidates --max \"$MAX\" <<'JIRA'\n{{ block.jira_issues.output }}\nJIRA\n"
                + "python3 -c \"\nimport sys\nprint(sys.stdin.read())\n\" <<'IN'\n{{ previous_report }}\nIN\n"
                + "BODY=$(cat <<'B'\n{{ previous_chain_output }}\nB\n)\n"
                + "if cat <<\\STEP | grep -qiE \"error|failed\"\n{{ step_1_output }}\nSTEP\nthen echo red; fi"))).isEmpty();
    }

    @Test
    void should_leaveOrdinaryVariablesInInterpreterSourceAlone_withoutEscaping() {
        assertThat(apply(shell("python3 - <<'PY'\nroot = '{{ project_path }}'\nrun = '{{ workflow_run_id }}'\nPY")))
                .isEmpty();
    }

    @Test
    void should_reportFreeTextInAnUnquotedHeredoc_asWarningForOutputs_andErrorForOutsideText() {
        assertThat(apply(shell("cat > report.md <<EOF\n{{ previous_output }}\nEOF"))).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("heredoc-placeholder");
            assertThat(f.severity()).isEqualTo(Severity.WARNING);
            assertThat(f.detail()).startsWith("puts {{ previous_output }} into the body of a heredoc with an unquoted"
                    + " delimiter (<<EOF)");
        });
        assertThat(apply(shell("cat <<-END\n\t{{ previous_chain_output }} {{ step_2_output }}\n\tEND")))
                .singleElement().satisfies(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.ERROR);
                    assertThat(f.detail()).startsWith("puts {{ previous_chain_output }}, {{ step_2_output }} into"
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
        assertThat(apply(shell("echo $(( 1 << n ))\necho '{{ previous_chain_output }}'"))).singleElement()
                .satisfies(f -> {
                    assertThat(f.rule()).isEqualTo("quoted-placeholder");
                    assertThat(f.severity()).isEqualTo(Severity.ERROR);
                });
        assertThat(apply(shell("(( x = 1 << SHIFT ))\necho \"{{ previous_output }}\""))).singleElement()
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

    // -- unquoted-ai-output ------------------------------------------------------------------------------

    private static BlockDefinition named(String type, String name, Map<String, Object> config) {
        BlockDefinition b = block(type, config);
        b.setName(name);
        return b;
    }

    private static final BlockDefinition REVIEW = named("simple", "review", Map.of("command", "Review the diff"));

    private static List<AnalyzerFinding> unquotedAi(BlockDefinition... blocks) {
        return rule("unquoted-ai-output", apply(blocks));
    }

    @Test
    void should_reportTheBareOutputOfAnAiStepInShellCode_asError() {
        assertThat(unquotedAi(REVIEW, shell("printf '%s' {{ block.review.output }} | ./report")))
                .singleElement().satisfies(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.ERROR);
                    assertThat(f.blockIndex()).isEqualTo(1);
                    assertThat(f.detail()).isEqualTo("puts {{ block.review.output }} into shell code without"
                            + " shell_escape_variables, the output of the AI step review — a model reads the"
                            + " workspace, and what it reads can steer its answer, so a run quotes what a model wrote"
                            + " in every shell line and this command would not run as written; Traceworks refuses"
                            + " the workflow. Correct: shell_escape_variables: true on this block, which quotes"
                            + " every value; to hand the text on as standard input or as a file, write"
                            + " printf '%s\\n' {{ block.review.output }} | tool, or > file");
                });
    }

    /**
     * In the author's quotes the run's quotes cancel out, in a block that escapes every value too: an error for what a
     * model wrote, where the output of a step of a workflow without AI stays a warning.
     */
    @Test
    void should_reportTheOutputOfAnAiStepInsideQuotes_asError_withOrWithoutEscaping() {
        for (BlockDefinition use : List.of(shell("echo \"{{ block.review.output }}\""),
                shell("X='{{ previous_output }}'"), escapedShell("echo \"{{ step_0_output }}\""))) {
            assertThat(rule("quoted-placeholder", apply(REVIEW, use))).as(BlockText.joined(use)).singleElement()
                    .satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.ERROR));
            assertThat(unquotedAi(REVIEW, use)).isEmpty();
        }
        assertThat(rule("quoted-placeholder", apply(named("shell", "review", Map.of("command", "make")),
                shell("echo \"{{ block.review.output }}\"")))).singleElement()
                .satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.WARNING));
    }

    /**
     * No heredoc body takes what a model wrote: the run quotes it there too, and a line of it that equals the
     * delimiter ends the body. A quoted heredoc that only stores the output of a step without AI stays fine.
     */
    @Test
    void should_reportTheOutputOfAnAiStepInAnyHeredocBody_asError() {
        for (BlockDefinition use : List.of(shell("cat > out.md <<'EOF'\n{{ block.review.output }}\nEOF"),
                escapedShell("cat > out.md <<'EOF'\n{{ block.review.output }}\nEOF"),
                shell("cat > out.md <<EOF\n{{ block.review.output }}\nEOF"),
                shell("python3 - <<'PY'\nprint('''{{ block.review.output }}''')\nPY"))) {
            assertThat(rule("heredoc-placeholder", apply(REVIEW, use))).as(BlockText.joined(use)).singleElement()
                    .satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.ERROR));
        }
        assertThat(rule("heredoc-placeholder", apply(REVIEW,
                shell("cat > out.md <<'EOF'\n{{ block.review.output }}\nEOF")))).singleElement()
                .satisfies(f -> assertThat(f.detail()).isEqualTo("puts {{ block.review.output }} into the body of a"
                        + " heredoc, the output of the AI step review — a run quotes what a model wrote wherever it"
                        + " stands in shell code, so the quotes land in the text, and a line of the value that equals"
                        + " the delimiter ends the body and the rest runs as commands; Traceworks refuses the"
                        + " workflow. Correct: pass the value outside the body with shell_escape_variables: true,"
                        + " e.g. printf '%s\\n' {{ block.review.output }} > file instead of cat <<'EOF'"));
        assertThat(apply(named("shell", "review", Map.of("command", "make")),
                shell("cat > out.md <<'EOF'\n{{ block.review.output }}\nEOF"))).isEmpty();
    }

    /**
     * From its first AI step on a run quotes every output: a later shell step can print what the model wrote without
     * naming it. So the output of a step after the AI step is refused the same way, and one before it is not.
     */
    @Test
    void should_reportTheOutputOfAStepAfterAnAiStep_andLeaveTheStepsBeforeIt() {
        BlockDefinition list = named("shell", "list", Map.of("command", "ls"));
        BlockDefinition build = named("shell", "build", Map.of("command", "make"));

        assertThat(unquotedAi(list, REVIEW, build, shell("for f in {{ block.list.output }}; do echo; done")))
                .isEmpty();
        assertThat(unquotedAi(list, REVIEW, build, shell("echo {{ block.build.output }}"))).singleElement()
                .satisfies(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.ERROR);
                    assertThat(f.detail()).startsWith("puts {{ block.build.output }} into shell code without"
                            + " shell_escape_variables, the output of the step build, which runs after the AI step"
                            + " review and may hold what the model wrote — ");
                });
        assertThat(rule("quoted-placeholder", apply(list, REVIEW, build, shell("echo \"{{ previous_output }}\""))))
                .singleElement().satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.ERROR));
        assertThat(unquotedAi(list, REVIEW, build, escapedShell("echo {{ block.build.output }}"))).isEmpty();
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
            assertThat(rule("unquoted-ai-output", out)).as(asking).singleElement()
                    .satisfies(f -> assertThat(f.detail()).contains("the output of the AI step child"));
        }
        List<AnalyzerFinding> out = new ArrayList<>();
        QuotingRules.apply(List.of(named("sub-workflow", "child", Map.of("workflow_id", "plain")), use,
                named("foreach", "each", Map.of("items", List.of("a"), "workflow_id", "judge")),
                shell("echo {{ block.each.output }}")), children, out);
        assertThat(rule("unquoted-ai-output", out)).singleElement()
                .satisfies(f -> assertThat(f.blockIndex()).isEqualTo(3));
        assertThat(unquotedAi(named("sub-workflow", "child", Map.of("workflow_id", "judge")), use))
                .as("without a resolver the child is not looked into: the run quotes").isEmpty();
        assertThat(rule("unquoted-ai-output", new WorkflowAnalyzer().analyze(workflow("parent",
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
        assertThat(unquotedAi(named("agentic", "fix", Map.of("goal", "Fix it")), shell("echo {{ previous_output }}")))
                .hasSize(1);
        assertThat(unquotedAi(named("compress-ai", "short", Map.of("source", "x")), shell("echo {{ step_0_output }}")))
                .hasSize(1);
        for (String type : List.of("foreach", "retry", "while", "conditional")) {
            assertThat(unquotedAi(named(type, "loop", Map.of("execution_mode", "ai", "command", "Judge")),
                    shell("echo {{ block.loop.output }}"))).as(type).hasSize(1);
        }
    }

    @Test
    void should_notReport_whenTheBlockEscapesEveryValue() {
        assertThat(unquotedAi(REVIEW, escapedShell("printf '%s' {{ block.review.output }} | ./report"))).isEmpty();
    }

    /** Quotes and heredoc bodies have their own rules (quoted-placeholder, heredoc-placeholder), errors too. */
    @Test
    void should_leaveQuotesAndHeredocBodies_toTheirOwnRules() {
        assertThat(unquotedAi(REVIEW, shell("echo '{{ block.review.output }}'"))).isEmpty();
        assertThat(unquotedAi(REVIEW, shell("cat > out.md <<'EOF'\n{{ block.review.output }}\nEOF"))).isEmpty();
    }

    @Test
    void should_notReport_theOutputOfADeterministicStep() {
        assertThat(unquotedAi(named("shell", "build", Map.of("command", "make")), shell("echo {{ previous_output }}")))
                .isEmpty();
        assertThat(unquotedAi(named("foreach", "lint", Map.of("execution_mode", "shell", "command", "lint")),
                shell("echo {{ block.lint.output }}"))).isEmpty();
        assertThat(unquotedAi(named("simple", "probe", Map.of("execution_mode", "shell", "command", "ls")),
                shell("echo {{ block.probe.output }}"))).isEmpty();
    }

    /** The step index of a wrapper itself is known, as long as no block before it draws further indices. */
    @Test
    void should_followAStepIndex_toAWrapperInAiMode_atThatIndex() {
        assertThat(unquotedAi(named("foreach", "each", Map.of("execution_mode", "ai", "items", List.of("a"),
                "command", "Judge {{ item }}")), shell("echo {{ step_0_output }}"))).hasSize(1);
    }

    /** A wrapper draws further step indices, so after one step_N_output cannot be told from the block list. */
    @Test
    void should_notGuessAStepIndex_afterAWrapper() {
        assertThat(unquotedAi(named("foreach", "lint", Map.of("execution_mode", "shell", "items", List.of("a"),
                "command", "lint")), REVIEW, shell("echo {{ step_1_output }}"))).isEmpty();
    }

    /** A shell text of a nested block counts too; its previous_output may be an inner block's, so only names do. */
    @Test
    void should_checkNestedShellTexts_byName() {
        Map<String, Object> inner = Map.of("type", "shell", "name", "send", "config",
                Map.of("command", "notify {{ block.review.output }} {{ previous_output }}"));
        BlockDefinition loop = named("foreach", "each", Map.of("execution_mode", "shell", "items", List.of("a"),
                "blocks", List.of(inner)));
        assertThat(unquotedAi(REVIEW, loop)).singleElement()
                .satisfies(f -> assertThat(f.detail()).startsWith("puts {{ block.review.output }} into shell code"));
    }

    @Test
    void should_checkTheShellFieldsOfAWrapper_suchAsItsItemsCommand() {
        assertThat(unquotedAi(REVIEW, named("foreach", "each", Map.of("execution_mode", "ai",
                "items_source", "command", "items_command", "printf '%s\\n' {{ block.review.output }}",
                "command", "Judge {{ item }}")))).hasSize(1);
    }

    /** A switch whose cases hold an AI step writes AI text, and so does an AI actor deciding a human-input step. */
    @Test
    void should_countBlocksWhoseNestedStepsOrActorAreAi() {
        Map<String, Object> caseBlock = Map.of("type", "simple", "name", "draft", "config", Map.of("command", "Draft"));
        BlockDefinition route = named("switch", "route", Map.of("expression", "x",
                "cases", List.of(Map.of("value", "a", "blocks", List.of(caseBlock)))));
        assertThat(unquotedAi(route, shell("echo {{ block.route.output }}"))).hasSize(1);
        assertThat(unquotedAi(named("human-input", "ask", Map.of("question", "Why?", "actor", "ai")),
                shell("echo {{ block.ask.output }}"))).hasSize(1);
    }

    /** The first block has no step before it, so its previous_output is no AI step's. */
    @Test
    void should_notReport_previousOutputInTheFirstBlock() {
        assertThat(unquotedAi(shell("echo {{ previous_output }}"), REVIEW)).isEmpty();
    }

    /** block.<name>.output names a block nested in a switch too, the one of that name, not another case block. */
    @Test
    void should_followABlockName_toTheAiStepNestedInASwitch() {
        Map<String, Object> probe = Map.of("type", "simple", "name", "probe",
                "config", Map.of("execution_mode", "shell", "command", "ls"));
        Map<String, Object> draft = Map.of("type", "simple", "name", "draft", "config", Map.of("command", "Draft"));
        BlockDefinition route = named("switch", "route", Map.of("expression", "x",
                "cases", List.of(Map.of("value", "a", "blocks", List.of(probe, draft)))));

        assertThat(unquotedAi(route, shell("echo {{ block.draft.output }}"))).singleElement()
                .satisfies(f -> assertThat(f.detail()).contains("the output of the AI step draft"));
        assertThat(unquotedAi(route, shell("echo {{ block.probe.output }}"))).as("a nested step in shell mode")
                .isEmpty();
    }

    /** step_N_output past deterministic blocks still names the block at position N. */
    @Test
    void should_followAStepIndex_pastTheDeterministicBlocksBeforeIt() {
        assertThat(unquotedAi(named("shell", "build", Map.of("command", "make")), REVIEW,
                shell("echo {{ step_1_output }}"))).singleElement()
                .satisfies(f -> assertThat(f.detail()).contains("the output of the AI step review"));
    }

    /** A block's own step index is no earlier step: its output does not exist yet when its fields resolve. */
    @Test
    void should_notReport_theStepIndexOfTheBlockItself() {
        assertThat(unquotedAi(named("foreach", "each", Map.of("execution_mode", "ai", "items_source", "command",
                "items_command", "printf '%s\\n' {{ step_0_output }}", "command", "Judge {{ item }}")))).isEmpty();
    }

    /** The items of a register carry names a step wrote: outside text, as the findings are. */
    @Test
    void auditItems_insideQuotesInShellCode_areAnError() {
        assertThat(apply(shell("echo '{{ audit_items }}'"))).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.ERROR);
            assertThat(f.detail()).startsWith("puts {{ audit_items }} inside quotes in shell code");
        });
    }
}
