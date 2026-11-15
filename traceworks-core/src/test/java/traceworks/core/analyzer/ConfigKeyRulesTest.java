package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.model.BlockDefinition;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** The key rules on real cases: none of them was noticed on saving, each ran silently without the intended effect. */
class ConfigKeyRulesTest {

    private final WorkflowAnalyzer analyzer = new WorkflowAnalyzer();

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(config);
        return b;
    }

    /** Only the findings of the key rules; the other rules are not the subject here. */
    private List<AnalyzerFinding> keyFindings(BlockDefinition... blocks) {
        return analyzer.analyze(List.of(blocks), null).stream()
                .filter(f -> f.rule().endsWith("config-key") || f.rule().equals("invalid-config-value"))
                .toList();
    }

    @Nested
    class UnknownAndMissingKeys {

        /** A wrapper's AI step reads {@code tools} and {@code workspace_writable}, so neither is unknown or unread. */
        @Test
        void toolsAndWritableWorkspaceOnAWrappersAiStep_areKnown() {
            for (String type : List.of("foreach", "retry", "while", "conditional")) {
                java.util.Map<String, Object> config = new java.util.HashMap<>(Map.of("execution_mode", "ai",
                        "tools", "read", "workspace_writable", true));
                config.put(type.equals("conditional") ? "then_command" : "command", "Judge it");
                if (type.equals("foreach")) config.put("items", List.of("a"));
                if (type.equals("conditional")) config.put("condition", "Is it done?");

                assertThat(keyFindings(block(type, "judge", config))).as(type).isEmpty();
            }
        }

        /** {@code web} and {@code web_hosts} are keys of every block that owns {@code tools}. */
        @Test
        void webAndWebHosts_areKnownWhereToolsAre() {
            for (String type : List.of("foreach", "retry", "while", "conditional")) {
                java.util.Map<String, Object> config = new java.util.HashMap<>(Map.of("execution_mode", "ai",
                        "web", "fetch", "web_hosts", List.of("docs.example.org")));
                config.put(type.equals("conditional") ? "then_command" : "command", "Look it up");
                if (type.equals("foreach")) config.put("items", List.of("a"));
                if (type.equals("conditional")) config.put("condition", "Is it done?");

                assertThat(keyFindings(block(type, "look", config))).as(type).isEmpty();
            }
            assertThat(keyFindings(block("simple", "look", Map.of("command", "Look", "web", "search")))).isEmpty();
            assertThat(keyFindings(block("agentic", "dig", Map.of("goal", "Dig", "tools", "none", "web", "fetch",
                    "web_hosts", List.of("*"))))).isEmpty();
        }

        /** A wrong value is reported once, by the rule that also refuses the run, and web is no key elsewhere. */
        @Test
        void aWrongWebValue_isReportedOnce_andWebOnABlockWithoutTools_isUnknown() {
            assertThat(keyFindings(block("simple", "look", Map.of("command", "Look", "web", "all"))))
                    .extracting(AnalyzerFinding::rule).containsExactly("invalid-config-value");
            assertThat(keyFindings(block("simple", "look", Map.of("command", "Look", "web", "fetch",
                    "web_hosts", "docs.example.org")))).extracting(AnalyzerFinding::rule)
                    .containsExactly("invalid-config-value");
            assertThat(keyFindings(block("compress-ai", "short", Map.of("source", "x", "instruction", "y",
                    "web", "search")))).extracting(AnalyzerFinding::rule).contains("unknown-config-key");
        }

        @Test
        void agenticWithCommandInsteadOfGoal() {
            var findings = keyFindings(block("agentic", "implement", Map.of("command", "Build the feature")));

            assertThat(findings).extracting(AnalyzerFinding::rule, AnalyzerFinding::severity, AnalyzerFinding::blockIndex)
                    .containsExactly(
                            tuple("missing-required-config-key", Severity.ERROR, 0),
                            tuple("unknown-config-key", Severity.WARNING, 0));
            assertThat(findings.get(0).detail()).contains("goal");
            assertThat(findings.get(1).detail()).contains("command").contains("agentic");
        }

        @Test
        void simpleWithPromptInsteadOfCommand() {
            var findings = keyFindings(block("simple", "ask", Map.of("prompt", "Summarise")));

            assertThat(findings).extracting(AnalyzerFinding::rule)
                    .containsExactly("missing-required-config-key", "unknown-config-key");
            assertThat(findings.get(1).detail()).contains("prompt");
        }

        @Test
        void subWorkflowWithWorkflowIdLookup() {
            var findings = keyFindings(block("sub-workflow", "child", Map.of("workflow_id_lookup", "Deploy")));

            assertThat(findings).extracting(AnalyzerFinding::rule)
                    .containsExactly("missing-required-config-key", "unknown-config-key");
            assertThat(findings.get(0).detail()).contains("workflow_id");
        }

        /**
         * Traceworks does not read the old spelling, so the block fails.
         * One finding, an error, with the new name.
         */
        @Test
        void subWorkflowWithTheLegacySpellingWorkflowId_isAnError() {
            var findings = keyFindings(block("sub-workflow", "old", Map.of("workflowId", "abc")));

            assertThat(findings).extracting(AnalyzerFinding::rule, AnalyzerFinding::severity, AnalyzerFinding::blockIndex)
                    .containsExactly(tuple("legacy-config-key", Severity.ERROR, 0));
            assertThat(findings.get(0).detail())
                    .isEqualTo("workflowId is the legacy spelling of workflow_id —"
                            + " Traceworks does not read it, so the block fails; rename the key to workflow_id");
        }

        /** If both are present, workflow_id applies; the hint says that the old key can go. */
        @Test
        void subWorkflowWithBothSpellings_advisesRemovingTheOldOne() {
            var findings = keyFindings(block("sub-workflow", "both", Map.of("workflow_id", "new", "workflowId", "old")));

            assertThat(findings).singleElement().satisfies(f -> {
                assertThat(f.rule()).isEqualTo("legacy-config-key");
                assertThat(f.severity()).isEqualTo(Severity.WARNING);
                assertThat(f.detail()).endsWith("workflow_id is set as well and wins, remove workflowId");
            });
        }

        /** Also nested, with path. */
        @Test
        void aNestedSubWorkflowWithTheLegacySpelling() {
            Map<String, Object> inner = new HashMap<>();
            inner.put("type", "sub-workflow");
            inner.put("name", "child");
            inner.put("config", Map.of("workflowId", "abc"));
            var findings = keyFindings(block("switch", "route", Map.of("expression", "x",
                    "cases", List.of(Map.of("value", "a", "blocks", List.of(inner))))));

            assertThat(findings).singleElement().satisfies(f -> {
                assertThat(f.rule()).isEqualTo("legacy-config-key");
                assertThat(f.detail()).startsWith("Block 1 › cases[0] › Block 1 \"child\": workflowId is the legacy");
            });
        }

        @Test
        void agenticWithInstructionsAddendum() {
            var findings = keyFindings(block("agentic", "agent",
                    Map.of("goal", "Tidy up", "instructions_addendum", "Be thorough")));

            assertThat(findings).singleElement().satisfies(f -> {
                assertThat(f.rule()).isEqualTo("unknown-config-key");
                assertThat(f.detail()).contains("instructions_addendum");
            });
        }

        /** A key another type reads ({@code max_chars} of compress) has no effect on this one. */
        @Test
        void shellWithMaxChars() {
            var findings = keyFindings(block("shell", "list", Map.of("command", "ls", "max_chars", 4000)));

            assertThat(findings).singleElement().satisfies(f -> {
                assertThat(f.rule()).isEqualTo("unknown-config-key");
                assertThat(f.detail()).contains("max_chars").contains("shell");
            });
        }

        @Test
        void nameInTheConfig_belongsOnTheBlockLevel() {
            var findings = keyFindings(block("shell", null,
                    Map.of("command", "set -euo pipefail\nls", "name", "list")));

            assertThat(findings).singleElement().satisfies(f -> {
                assertThat(f.rule()).isEqualTo("unknown-config-key");
                assertThat(f.detail()).contains("name belongs on the block level, next to config")
                        .doesNotContain("did you mean");
            });
        }

        @Test
        void aNearSpelling_getsASuggestion() {
            assertThat(keyFindings(block("shell", "s", Map.of("command", "ls", "timeout_second", 60))))
                    .singleElement().extracting(AnalyzerFinding::detail).asString()
                    .contains("did you mean timeout_seconds?");
            assertThat(keyFindings(block("sub-workflow", "s", Map.of("workflow_id", "x", "Workflow-ID", "y"))))
                    .singleElement().extracting(AnalyzerFinding::detail).asString()
                    .contains("did you mean workflow_id?");
        }

        @Test
        void aMissingRequiredKey_namesTheSimilarOneSetInstead() {
            var findings = keyFindings(block("foreach", "f", Map.of("items", List.of("a"), "comand", "echo")));

            assertThat(findings.get(0).rule()).isEqualTo("missing-required-config-key");
            assertThat(findings.get(0).detail()).contains("(comand is set instead)");
        }

        /** foreach runs with command or workflow_id; context only takes effect with workflow_id. */
        @Test
        void foreachNeedsExactlyOneOfCommandAndWorkflowId() {
            assertThat(keyFindings(block("foreach", "f", Map.of("items", List.of("a"), "command", "echo")))).isEmpty();
            assertThat(keyFindings(block("foreach", "f", Map.of("items", List.of("a"), "workflow_id", "{{ wf }}",
                    "context", Map.of("x", "{{ item.id }}"))))).isEmpty();
            assertThat(keyFindings(block("foreach", "f", Map.of("items", List.of("a"), "command", "echo",
                    "workflow_id", "wf")))).singleElement()
                    .satisfies(f -> assertThat(f.detail()).contains("exclude each other"));
            assertThat(keyFindings(block("foreach", "f", Map.of("items", List.of("a"), "command", "echo",
                    "context", Map.of("x", "y"))))).singleElement()
                    .satisfies(f -> assertThat(f.detail()).contains("context only takes effect with workflow_id"));
        }

        /**
         * Exactly one of the keys: with neither or both the block aborts, and an empty text counts as not set, as in
         * the executor.
         */
        @Test
        void requiredOneOf_countsAnEmptyTextAsNotSet() {
            assertThat(keyFindings(block("shell", "s", Map.of("command", "ls")))).isEmpty();
            assertThat(keyFindings(block("shell", "s", Map.of("container_image", "img:1",
                    "container_args", List.of("fs"))))).isEmpty();
            assertThat(keyFindings(block("shell", "s", Map.of("timeout_seconds", 5))))
                    .singleElement().satisfies(f -> {
                        assertThat(f.rule()).isEqualTo("missing-required-config-key");
                        assertThat(f.detail()).contains("command or container_args");
                    });
            assertThat(keyFindings(block("shell", "s", Map.of("command", "", "container_image", "img:1",
                    "container_args", List.of("fs"))))).isEmpty();
            assertThat(keyFindings(block("shell", "s", Map.of("command", " "))))
                    .singleElement().extracting(AnalyzerFinding::detail).asString()
                    .contains("command or container_args");
            assertThat(keyFindings(block("foreach", "f", Map.of("command", "echo", "workflow_id", " "))))
                    .isEmpty();
        }

        /** Tool mode exists only at top level: nested, the analyzer reports it, and command stays mandatory. */
        @Test
        void toolModeInNestedBlocks_isReported() {
            Map<String, Object> inner = new HashMap<>();
            inner.put("type", "shell");
            inner.put("name", "scan");
            inner.put("config", Map.of("container_image", "img:1", "container_args", List.of("fs")));
            var findings = keyFindings(block("switch", "route", Map.of("expression", "x",
                    "cases", List.of(Map.of("value", "a", "blocks", List.of(inner))))));

            assertThat(findings).anySatisfy(f -> {
                assertThat(f.rule()).isEqualTo("unknown-config-key");
                assertThat(f.detail()).contains("container_args", "top-level");
            }).anySatisfy(f -> {
                assertThat(f.rule()).isEqualTo("missing-required-config-key");
                assertThat(f.detail()).contains("command");
            });
        }

        /** Tool mode runs the image's entrypoint: without container_image the step fails, so it is an error. */
        @Test
        void toolModeWithoutAnImage_isAnError() {
            assertThat(keyFindings(block("shell", "s", Map.of("container_args", List.of("fs")))))
                    .singleElement().satisfies(f -> {
                        assertThat(f.rule()).isEqualTo("missing-required-config-key");
                        assertThat(f.severity()).isEqualTo(Severity.ERROR);
                        assertThat(f.detail()).contains("container_image", "tool mode")
                                .doesNotContain("no effect");
                    });
            assertThat(keyFindings(block("shell", "s", Map.of("container_image", " ",
                    "container_args", List.of("fs"))))).singleElement()
                    .extracting(AnalyzerFinding::rule).isEqualTo("missing-required-config-key");
        }

        /** container_args is a list: a text (one string) makes the step fail when it is reached. */
        @Test
        void containerArgsThatIsNoList_isAnError() {
            assertThat(keyFindings(block("shell", "s", Map.of("container_image", "img:1",
                    "container_args", "fs --all")))).singleElement().satisfies(f -> {
                        assertThat(f.rule()).isEqualTo("invalid-config-value");
                        assertThat(f.severity()).isEqualTo(Severity.ERROR);
                        assertThat(f.detail()).contains("container_args", "\"fs --all\"", "list of strings");
                    });
            assertThat(keyFindings(block("shell", "s", Map.of("container_image", "img:1",
                    "container_args", List.of())))).isEmpty();
        }

        /** Shell runs with command or, in tool mode, with container_args, never with both. */
        @Test
        void shellNeedsExactlyOneOfCommandAndContainerArgs() {
            assertThat(keyFindings(block("shell", "s", Map.of("container_image", "img:1",
                    "container_args", List.of("fs"), "output_format", "sarif", "cache_dir", "/cache")))).isEmpty();
            assertThat(keyFindings(block("shell", "s", Map.of("command", "echo",
                    "container_image", "img:1", "container_args", List.of("fs")))))
                    .singleElement().satisfies(f -> {
                        assertThat(f.rule()).isEqualTo("missing-required-config-key");
                        assertThat(f.detail()).contains("set only one");
                    });
        }

        @Test
        void shortKeys_getNoGuessedSuggestion() {
            assertThat(FindingText.suggestion("mode", Set.of("node", "name"))).isEqualTo("node");
            assertThat(FindingText.suggestion("ab", Set.of("id"))).isNull();
            assertThat(FindingText.suggestion("prompt", Set.of("command", "provider"))).isNull();
        }

        /** retry, foreach, while and conditional pass container_image on to the shell run only in shell mode. */
        @Test
        void retryWithContainerImage_onlyTakesEffectInShellMode() {
            var ai = keyFindings(block("retry", "r", Map.of("command", "npm test", "container_image", "node:24")));
            var shell = keyFindings(block("retry", "r",
                    Map.of("command", "npm test", "container_image", "node:24", "execution_mode", "shell")));

            assertThat(ai).singleElement().satisfies(f -> {
                assertThat(f.rule()).isEqualTo("unknown-config-key");
                assertThat(f.detail()).contains("container_image only takes effect with execution_mode: shell");
            });
            assertThat(shell).isEmpty();
        }

        /** The run_if gate quotes the placeholders of the condition with shell_escape_variables, for every block type. */
        @Test
        void shellEscapeVariables_takesEffectOnEveryBlockWithRunIf() {
            assertThat(keyFindings(block("simple", "s", Map.of("command", "Summarise",
                    "run_if", "test -n {{ target }}", "shell_escape_variables", true)))).isEmpty();
            assertThat(keyFindings(block("simple", "s", Map.of("command", "Summarise",
                    "shell_escape_variables", true))))
                    .singleElement().satisfies(f -> {
                        assertThat(f.rule()).isEqualTo("unknown-config-key");
                        assertThat(f.detail()).contains("shell_escape_variables only takes effect with run_if");
                    });
            assertThat(keyFindings(block("shell", "s", Map.of("command", "ls", "shell_escape_variables", true))))
                    .as("shell blocks read the key themselves").isEmpty();
        }

        @Test
        void commonAiAndValidationKeys_areKnown() {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("command", "Summarise");
            config.put("label", "Summary");
            config.put("run_if", "test -f a");
            config.put("provider", "claude");
            config.put("effort", "HIGH");
            config.put("validation", Map.of("contains", "OK"));
            config.put("on_failure", "continue");

            assertThat(keyFindings(block("simple", "s", config))).isEmpty();
        }

        @Test
        void aiKeys_onlyOnAiBlocks() {
            assertThat(keyFindings(block("shell", "s", Map.of("command", "ls", "model", "opus"))))
                    .extracting(AnalyzerFinding::rule).containsExactly("unknown-config-key");
        }

        @Test
        void aBlockTypeWithoutSchema_staysUnchecked() {
            assertThat(keyFindings(block("future-block", "x", Map.of("whatever", 1, "on_failure", "fail")))).isEmpty();
            assertThat(keyFindings(block(null, "x", Map.of("whatever", 1)))).isEmpty();
        }

        @Test
        void aBlockWithoutConfig_reportsOnlyMissingRequiredKeys() {
            assertThat(keyFindings(block("switch", "s", null))).extracting(AnalyzerFinding::rule)
                    .containsExactly("missing-required-config-key", "missing-required-config-key");
        }
    }

    @Nested
    class Values {

        @Test
        void onFailureFail_isAnError() {
            var findings = keyFindings(block("shell", "s", Map.of("command", "ls", "on_failure", "fail")));

            assertThat(findings).singleElement().satisfies(f -> {
                assertThat(f.rule()).isEqualTo("invalid-config-value");
                assertThat(f.severity()).isEqualTo(Severity.ERROR);
                assertThat(f.detail()).contains("\"fail\"").contains("allowed: stop, continue")
                        .as("the message does not claim a run-time effect")
                        .doesNotContain("carries on").doesNotContain("stops");
            });
        }

        @Test
        void onConditionError_isKnownForConditionalAndWhile_withItsThreeValues() {
            for (String value : List.of("fail", "false", "skip", "Skip")) {
                assertThat(keyFindings(block("conditional", "c", Map.of("condition", "x?", "on_condition_error", value)),
                        block("while", "w", Map.of("command", "y", "on_condition_error", value)))).as(value).isEmpty();
            }
            assertThat(keyFindings(block("conditional", "c", Map.of("condition", "x?", "on_condition_error", "ignore"))))
                    .singleElement().satisfies(f -> assertThat(f.rule()).isEqualTo("invalid-config-value"));
            // the executor reads an unquoted false as "false", the analyzer still asks for the text
            assertThat(keyFindings(block("while", "w", Map.of("command", "y", "on_condition_error", false))))
                    .singleElement().satisfies(f -> assertThat(f.rule()).isEqualTo("invalid-config-value"));
        }

        @Test
        void otherInvalidValues_areWarnings() {
            var findings = keyFindings(block("approval", "a", Map.of("on_no", "abort")));

            assertThat(findings).singleElement().satisfies(f -> {
                assertThat(f.rule()).isEqualTo("invalid-config-value");
                assertThat(f.severity()).isEqualTo(Severity.WARNING);
            });
        }

        /**
         * A placeholder is only known at run time; the legacy alias claude is no valid execution_mode, and like
         * an invalid on_failure it is an error: the step fails. The run reads on_failure as written, so a placeholder
         * there is no valid value: it stops the run.
         */
        @Test
        void placeholdersAreValid_theLegacyExecutionModeClaudeIsNot() {
            assertThat(keyFindings(block("simple", "s", Map.of("command", "ls", "effort", "{{ effort }}")))).isEmpty();
            assertThat(keyFindings(block("shell", "s", Map.of("command", "ls", "on_failure", "{{ policy }}"))))
                    .singleElement().extracting(AnalyzerFinding::severity).isEqualTo(Severity.ERROR);
            assertThat(keyFindings(block("foreach", "f",
                    Map.of("command", "x", "items", List.of("a"), "execution_mode", "claude"))))
                    .singleElement().satisfies(f -> {
                        assertThat(f.rule()).isEqualTo("invalid-config-value");
                        assertThat(f.severity()).isEqualTo(Severity.ERROR);
                        assertThat(f.detail()).isEqualTo("execution_mode: \"claude\" is not a valid value, allowed: ai, shell");
                    });
            assertThat(keyFindings(block("retry", "r", Map.of("command", "x", "execution_mode", "shel"))))
                    .singleElement().extracting(AnalyzerFinding::severity).isEqualTo(Severity.ERROR);
        }

        @Test
        void invalidExecutionMode_isAnError_onEveryBlockType() {
            assertThat(keyFindings(block("shell", "s", Map.of("command", "ls", "execution_mode", "claude"))))
                    .extracting(AnalyzerFinding::rule, AnalyzerFinding::severity)
                    .contains(tuple("invalid-config-value", Severity.ERROR))
                    .doesNotContain(tuple("invalid-config-value", Severity.WARNING));
            assertThat(keyFindings(block("simple", "t", Map.of("command", "x", "execution_mode", "bogus"))))
                    .extracting(AnalyzerFinding::rule, AnalyzerFinding::severity)
                    .contains(tuple("invalid-config-value", Severity.ERROR))
                    .doesNotContain(tuple("invalid-config-value", Severity.WARNING));
        }

        /**
         * B7: the run reads on_failure trimmed and in any case, so check takes {@code " Continue "} and {@code "STOP"}
         * as the run does; anything else stops the run and stays an error.
         */
        @Test
        void caseCounts_onlyWhereTheExecutorHeedsIt() {
            assertThat(keyFindings(block("simple", "t", Map.of("command", "x", "effort", "High")))).isEmpty();
            assertThat(keyFindings(block("shell", "s", Map.of("command", "ls", "on_failure", " Continue ")))).isEmpty();
            assertThat(keyFindings(block("shell", "s", Map.of("command", "ls", "on_failure", "STOP")))).isEmpty();
            assertThat(keyFindings(block("shell", "s", Map.of("command", "ls", "on_failure", "fail"))))
                    .singleElement().satisfies(f -> {
                        assertThat(f.severity()).isEqualTo(Severity.ERROR);
                        assertThat(f.detail()).isEqualTo("on_failure: \"fail\" is not a valid value, allowed: stop,"
                                + " continue");
                    });
            assertThat(BlockDefinition.builder().type("shell").config(Map.of("on_failure", " Continue ")).build()
                    .shouldStopOnFailure()).isFalse();
        }

        @Test
        void nonTextValuesForFixedValues_areInvalid() {
            assertThat(keyFindings(block("shell", "s", Map.of("command", "ls", "on_failure", false))))
                    .singleElement().extracting(AnalyzerFinding::detail).asString().contains("on_failure: false");
        }

        /** The wrong items_source next to items_command is reported by items-command-without-source, not twice. */
        @Test
        void foreachItemsSourceNextToItemsCommand_reportsOnlyTheForEachRule() {
            var findings = analyzer.analyze(List.of(block("foreach", "f",
                    Map.of("command", "x", "items_command", "ls", "items_source", "cmd"))), null);

            assertThat(findings).extracting(AnalyzerFinding::rule)
                    .contains("items-command-without-source").doesNotContain("invalid-config-value");
            assertThat(keyFindings(block("foreach", "f", Map.of("command", "x", "items_source", "cmd"))))
                    .extracting(AnalyzerFinding::rule).containsExactly("invalid-config-value");
        }

        /**
         * Where the block reads provider, an unknown one is reported once, by unknown-provider, as the error it is at run
         * time. Where nothing reads it (a switch), the value check reports it.
         */
        @Test
        void anUnknownProvider_isLeftToTheProviderRule_whereTheBlockReadsIt() {
            BlockDefinition gemini = block("simple", "s", Map.of("command", "Summarise", "provider", "gemini"));
            BlockDefinition route = block("switch", "route", Map.of("expression", "x", "cases", List.of(),
                    "provider", "gemini"));

            assertThat(keyFindings(gemini)).isEmpty();
            assertThat(analyzer.analyze(List.of(gemini), null)).extracting(AnalyzerFinding::rule)
                    .contains("unknown-provider");
            assertThat(keyFindings(route)).singleElement().satisfies(f -> {
                assertThat(f.rule()).isEqualTo("invalid-config-value");
                assertThat(f.severity()).isEqualTo(Severity.WARNING);
            });
        }
    }

    @Nested
    class Nesting {

        @Test
        void aBlockInCases_namesItsPath_andHangsOnTheOuterBlock() {
            Map<String, Object> nested = new HashMap<>();
            nested.put("type", "simple");
            nested.put("name", "report");
            nested.put("config", Map.of("prompt", "Write the report", "on_failure", "fail"));
            var findings = keyFindings(
                    block("shell", "before", Map.of("command", "echo GREEN")),
                    block("switch", "route", Map.of("expression", "{{ previous_output }}",
                            "cases", List.of(Map.of("value", "GREEN", "blocks", List.of(nested))))));

            assertThat(findings).extracting(AnalyzerFinding::rule, AnalyzerFinding::blockIndex, AnalyzerFinding::blockName)
                    .containsExactlyInAnyOrder(
                            tuple("missing-required-config-key", 1, "route"),
                            tuple("invalid-config-value", 1, "route"),
                            tuple("unknown-config-key", 1, "route"));
            assertThat(findings).allSatisfy(f ->
                    assertThat(f.detail()).startsWith("Block 2 › cases[0] › Block 1 \"report\": "));
        }

        @Test
        void aCorrectNestedBlock_isNoFinding() {
            var findings = keyFindings(block("switch", "route", Map.of("expression", "x",
                    "cases", List.of(Map.of("value", "a", "blocks", List.of(
                            Map.of("type", "shell", "config", Map.of("command", "ls", "timeout_seconds", 30))))),
                    "default", List.of(Map.of("type", "simple", "config", Map.of("command", "Hello"))))));

            assertThat(findings).isEmpty();
        }
    }

    /**
     * The types and ranges of the schema (B1 to B7, L1): a wrong value is an {@code invalid-config-value} ERROR in the
     * run's own words, at the top level and in nested blocks. The run's side is in {@code BlockSettingsReadTest}.
     */
    @Nested
    class Types {

        private List<String> details(BlockDefinition... blocks) {
            return keyFindings(blocks).stream()
                    .filter(f -> f.rule().equals("invalid-config-value") && f.severity() == Severity.ERROR)
                    .map(AnalyzerFinding::detail).toList();
        }

        private static final String FAILS = " — the step fails when it is reached";

        @Test
        void b1_flagsAsText() {
            assertThat(details(block("shell", "s", Map.of("command", "echo {{ x }}", "shell_escape_variables", "true"))))
                    .containsExactly("shell: 'shell_escape_variables' must be true or false, found the text \"true\""
                            + FAILS);
            assertThat(details(block("shell", "s", Map.of("command", "make", "checked_command", "true"))))
                    .containsExactly("shell: 'checked_command' must be true or false, found the text \"true\"" + FAILS);
            assertThat(details(block("shell", "s", Map.of("command", "make", "network", "true",
                    "workspace_writable", "yes")))).hasSize(2);
        }

        /** The run still reads the text "true" of untrusted_input as true; check says so, and still reports it. */
        @Test
        void b1_untrustedInputAsText_isReported_withWhatTheRunDoes() {
            assertThat(details(block("shell", "s", Map.of("command", "make", "untrusted_input", "true"))))
                    .containsExactly("shell: 'untrusted_input' must be true or false, found the text \"true\" — the"
                            + " run takes the text \"true\" (any case) as true and any other value as false; write"
                            + " true or false");
        }

        @Test
        void b3_numbersAndListsAsText() {
            assertThat(details(block("shell", "s", Map.of("command", "make", "timeout_seconds", "5"))))
                    .containsExactly("shell: 'timeout_seconds' must be a whole number between 1 and 86,400, found the"
                            + " text \"5\"" + FAILS);
            assertThat(details(block("while", "w", Map.of("execution_mode", "shell", "command", "true",
                    "condition_command", "false", "max_iterations", "3")))).singleElement().asString()
                    .startsWith("while: 'max_iterations' must be a whole number between 1 and 10,000");
            assertThat(details(block("approval", "a", Map.of("question", "ok?", "options", "Yes,No"))))
                    .containsExactly("approval: 'options' must be a list of texts, found the text \"Yes,No\"" + FAILS);
            assertThat(details(block("foreach", "f", Map.of("items", "a,b", "execution_mode", "shell",
                    "command", "echo {{ item }}", "max_consecutive_failures", "2", "timeout_seconds", "9"))))
                    .hasSize(2);
        }

        /** B4 and L1: out of range, not whole, beyond an int; the analyzer's probe of review A. */
        @Test
        void b4_l1_rangesAndWholeNumbers() {
            assertThat(details(
                    block("shell", "zero", Map.of("command", "make", "timeout_seconds", 0)),
                    block("shell", "text", Map.of("command", "make", "timeout_seconds", "60")),
                    block("shell", "half", Map.of("command", "make", "timeout_seconds", 0.5)),
                    block("shell", "huge", Map.of("command", "make", "timeout_seconds", 3_000_000_000L)),
                    block("retry", "delay", Map.of("execution_mode", "shell", "command", "make", "delay", -1)),
                    block("retry", "many", Map.of("execution_mode", "shell", "command", "make",
                            "max_retries", Integer.MAX_VALUE)))).hasSize(6);
            assertThat(details(block("shell", "s", Map.of("command", "make", "timeout_seconds", 86_400)))).isEmpty();
            assertThat(details(block("shell", "s", Map.of("command", "make", "timeout_seconds", "{{ t }}"))))
                    .as("the run resolves no placeholder in a number").hasSize(1);
        }

        @Test
        void b5_b6_commandAsList_mountsAsText() {
            assertThat(details(block("shell", "s", Map.of("command", List.of("echo", "x")))))
                    .containsExactly("shell: 'command' must be text, found a list" + FAILS);
            assertThat(details(block("shell", "s", Map.of("command", "ls", "container_image", "alpine:3",
                    "mounts", "/data:/data:ro"))))
                    .containsExactly("shell: 'mounts' must be a list of texts, found the text \"/data:/data:ro\""
                            + FAILS);
        }

        @Test
        void nestedBlocks_areCheckedToo() {
            Map<String, Object> inner = new LinkedHashMap<>();
            inner.put("type", "shell");
            inner.put("name", "report");
            inner.put("config", Map.of("command", "make", "timeout_seconds", "5"));
            assertThat(details(block("switch", "sw", Map.of("expression", "a",
                    "cases", List.of(Map.of("value", "a", "blocks", List.of(inner)))))))
                    .containsExactly("Block 1 › cases[0] › Block 1 \"report\": shell: 'timeout_seconds' must be a whole"
                            + " number between 1 and 86,400, found the text \"5\"" + FAILS);
        }

        /** Keys the run does not read are reported, without claiming the step fails. */
        @Test
        void aLabelOfTheWrongType_isReported_asNotRead() {
            assertThat(details(block("shell", "s", Map.of("command", "make", "description", 5))))
                    .containsExactly("shell: 'description' must be text, found 5 — the run does not read it");
        }

        /** A key another rule reports keeps that rule's finding, not a second one. */
        @Test
        void keysOtherRulesReport_areNotReportedTwice() {
            assertThat(details(block("shell", "s", Map.of("command", "make", "on_failure", false))))
                    .containsExactly("on_failure: false is not a valid value, allowed: stop, continue");
            assertThat(details(block("shell", "s", Map.of("command", "make", "effort", 5)))).isEmpty();
        }

        @Test
        void aNullValue_isAbsent() {
            Map<String, Object> config = new HashMap<>();
            config.put("command", "make");
            config.put("timeout_seconds", null);
            assertThat(details(block("shell", "s", config))).isEmpty();
        }
    }

    /**
     * B2: {@code validation} is described in the schema: an unknown key is reported as unknown keys of a block are,
     * a wrong type or a non-object is an error.
     */
    @Nested
    class Validation {

        @Test
        void aMisspelledKey_isAnUnknownKey_withASuggestion() {
            assertThat(keyFindings(block("shell", "s", Map.of("command", "make",
                    "validation", Map.of("min_lenght", 100)))))
                    .singleElement().satisfies(f -> {
                        assertThat(f.rule()).isEqualTo("unknown-config-key");
                        assertThat(f.severity()).isEqualTo(Severity.WARNING);
                        assertThat(f.detail()).isEqualTo("unknown key min_lenght in validation — the shell block does"
                                + " not read it, the setting has no effect; did you mean min_length?");
                    });
        }

        @Test
        void aWrongType_orAText_isAnError() {
            assertThat(keyFindings(block("shell", "s", Map.of("command", "make",
                    "validation", Map.of("min_length", "100", "contains", 5)))))
                    .extracting(AnalyzerFinding::severity, AnalyzerFinding::detail)
                    .containsExactlyInAnyOrder(
                            tuple(Severity.ERROR, "shell: 'validation.min_length' must be a whole number between 0 and"
                                    + " 2,147,483,647, found the text \"100\" — the step fails when it is reached"),
                            tuple(Severity.ERROR, "shell: 'validation.contains' must be text, found 5 — the step"
                                    + " fails when it is reached"));
            assertThat(keyFindings(block("shell", "s", Map.of("command", "make", "validation", "OK"))))
                    .singleElement().extracting(AnalyzerFinding::detail)
                    .isEqualTo("shell: 'validation' must be an object, found the text \"OK\" — the step fails when"
                            + " it is reached");
        }

        @Test
        void rightValues_areNoFinding() {
            assertThat(keyFindings(block("shell", "s", Map.of("command", "make", "validation", Map.of(
                    "contains", "OK", "min_length", 1, "max_length", 9, "retry_on_fail", true, "max_retries", 2)))))
                    .isEmpty();
        }
    }
}
