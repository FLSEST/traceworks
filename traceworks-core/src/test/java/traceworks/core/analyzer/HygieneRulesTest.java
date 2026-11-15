package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.model.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Hygiene rules directly: limits of the patterns and thresholds. */
class HygieneRulesTest {

    private static final String PARAGRAPH =
            "Work only inside the project directory and never change any file outside of it, under no circumstances.";

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(config);
        return b;
    }

    private static List<AnalyzerFinding> apply(String instructions, BlockDefinition... blocks) {
        List<AnalyzerFinding> out = new ArrayList<>();
        HygieneRules.apply(List.of(blocks), instructions, out);
        return out;
    }

    /** The detail names the path as written: {@code /Users/anna}, not {@code /home/anna}. */
    @Test
    void aHomePath_namesTheUserName_andAppliesToEveryBlockType() {
        List<AnalyzerFinding> out = apply(null,
                block("simple", "s", Map.of("command", "Read /Users/anna/notes.md")));

        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("absolute-home-path");
            assertThat(f.severity()).isEqualTo(Severity.ERROR);
            assertThat(f.detail()).contains("/Users/anna");
        });
    }

    @Test
    void homeWithoutAUserDirectory_isNoFinding() {
        assertThat(apply(null, block("simple", "s", Map.of("command", "cd /home and then $HOME/x"))))
                .isEmpty();
    }

    /**
     * {@code /home} in the middle of another path is a directory of that name, not the home of a user: the path of a
     * Traceworks home under a temporary directory once made {@code run} refuse a package.
     */
    @Test
    void homeInTheMiddleOfAPath_isNoFinding_aHomePathAfterAnyOtherTextIs() {
        assertThat(apply(null, block("simple", "s", Map.of("command", "ls /tmp/junit123/home/runs/2026/ "
                + "{{ scratch_dir }}/home/a/ ${ROOT}/home/a/ $ROOT/Users/a/ ~/home/a/ $(pwd)/home/a/ ./home/a/"))))
                .isEmpty();
        for (String command : List.of("ls /home/anna/x", "cd \"/home/anna/x\"", "a=/home/anna/x",
                "--dir=/Users/anna/x", "ro:/home/anna/x")) {
            assertThat(apply(null, block("simple", "s", Map.of("command", command)))).as(command)
                    .extracting(AnalyzerFinding::rule).containsExactly("absolute-home-path");
        }
    }

    @Test
    void aHomePath_isReportedOncePerBlock() {
        assertThat(apply(null, block("simple", "s",
                Map.of("command", "/home/a/x /home/b/y")))).hasSize(1);
    }

    /** The container side of a mount is a path inside the image, the same on every machine. */
    @Test
    void mountTargetInsideTheImageIsNoHomePath() {
        assertThat(apply(null, block("shell", "trivy", Map.of(
                "container_args", List.of("trivy", "fs", "/workspace"),
                "mounts", List.of("~/.m2/repository:/home/agent/.m2/repository:ro")))))
                .noneMatch(f -> f.rule().equals("absolute-home-path"));
    }

    @Test
    void mountSourceUnderAHomeDirectoryIsStillReported() {
        assertThat(apply(null, block("shell", "s", Map.of(
                "container_args", List.of("tool"),
                "mounts", List.of("/home/anna/data:/data:ro")))))
                .singleElement().satisfies(f -> {
                    assertThat(f.rule()).isEqualTo("absolute-home-path");
                    assertThat(f.detail()).contains("/home/anna");
                });
    }

    @Test
    void shellWithSetEOrErrexit_isStrict() {
        assertThat(apply(null,
                block("shell", "a", Map.of("command", "set -e\nls")),
                block("shell", "b", Map.of("command", "set -o errexit; ls")))).isEmpty();
    }

    @Test
    void shellWithoutStrictMode_isInfo_andAnEmptyCommandIsNot() {
        List<AnalyzerFinding> out = apply(null,
                block("shell", "a", Map.of("command", "ls")),
                block("shell", "b", Map.of("command", "  ")),
                block("shell", "c", Map.of("command", 42)),
                block("simple", "d", Map.of("command", "not shell")));

        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("missing-strict-mode");
            assertThat(f.severity()).isEqualTo(Severity.INFO);
            assertThat(f.blockIndex()).isZero();
        });
    }

    @Test
    void aVerbatimParagraphOfEightyCharactersOrMore_isDuplicated() {
        String instructions = "Short.\n\n" + PARAGRAPH + "\n   \nSomething else.";
        List<AnalyzerFinding> out = apply(instructions,
                block("simple", "s", Map.of("command", "Task.\n" + PARAGRAPH)));

        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("duplicated-instructions");
            assertThat(f.severity()).isEqualTo(Severity.WARNING);
            assertThat(f.detail()).contains(PARAGRAPH.length() + " characters");
        });
    }

    @Test
    void shortParagraphs_doNotCountAsDuplicated() {
        String shortText = PARAGRAPH.substring(0, 79);
        assertThat(apply(shortText, block("simple", "s", Map.of("command", shortText)))).isEmpty();
    }

    @Test
    void severalDuplicatedParagraphs_makeOneFinding() {
        String second = PARAGRAPH.replace("Work", "Write");
        List<AnalyzerFinding> out = apply(PARAGRAPH + "\n\n" + second,
                block("simple", "s", Map.of("command", PARAGRAPH + "\n" + second)));

        assertThat(out).extracting(AnalyzerFinding::rule).containsExactly("duplicated-instructions");
    }

    @Test
    void withoutInstructionsOrWithoutATask_noDuplicatedTask() {
        assertThat(apply(PARAGRAPH, block("simple", "s", Map.of("command", PARAGRAPH))))
                .extracting(AnalyzerFinding::rule).containsExactly("duplicated-instructions");
        assertThat(apply("   ", block("simple", "s", Map.of("command", PARAGRAPH)))).isEmpty();
        assertThat(apply(PARAGRAPH, block("simple", "s", Map.of()))).isEmpty();
    }

    /** agentic carries its task in goal. */
    @Test
    void anAgenticGoalWithAVerbatimParagraph_isDuplicated() {
        assertThat(apply(PARAGRAPH, block("agentic", "plan", Map.of("goal", "Plan.\n" + PARAGRAPH))))
                .extracting(AnalyzerFinding::rule).containsExactly("duplicated-instructions");
    }

    /** Wrappers send their command to the AI with workflow.instructions only in AI mode. */
    @Test
    void wrapperTasks_countOnlyInAiMode() {
        List<AnalyzerFinding> out = apply(PARAGRAPH,
                block("retry", "a", Map.of("command", PARAGRAPH)),
                block("retry", "b", Map.of("command", PARAGRAPH, "execution_mode", "shell")),
                block("conditional", "c", Map.of("condition", "x", "else_command", PARAGRAPH)),
                block("conditional", "d", Map.of("condition", "true", "then_command", PARAGRAPH,
                        "execution_mode", "shell")),
                block("foreach", "e", Map.of("command", PARAGRAPH, "execution_mode", "ai")));

        assertThat(out).extracting(AnalyzerFinding::blockName).containsExactly("a", "c", "e");
    }

    /** A shell command, the question to a human and a key that no executor reads do not go to the AI. */
    @Test
    void aShellCommand_aQuestionToAHuman_andAnUnreadKey_areNoDuplicatedTask() {
        assertThat(apply(PARAGRAPH,
                block("shell", "a", Map.of("command", "set -e\necho '" + PARAGRAPH + "'")),
                block("human-input", "b", Map.of("prompt", PARAGRAPH)),
                block("simple", "c", Map.of("prompt", PARAGRAPH, "command", "Summarise")))).isEmpty();
    }

    /** A simple block in a switch case gets workflow.instructions as system prompt just the same. */
    @Test
    void theAiTaskOfANestedBlock_isDuplicated_andNamesIt() {
        Map<String, Object> config = Map.of("expression", "{{ environment }}",
                "cases", List.of(Map.of("value", "prod", "blocks", List.of(
                        Map.of("type", "shell", "config", Map.of("command", "set -e\necho deploy")),
                        Map.of("type", "simple", "name", "notes",
                                "config", Map.of("command", "Write release notes.\n" + PARAGRAPH))))));

        assertThat(apply(PARAGRAPH, block("switch", "route", config))).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("duplicated-instructions");
            assertThat(f.blockName()).isEqualTo("route");
            assertThat(f.detail()).contains("in the nested block 'notes'");
        });
    }

    /** Nested as above: shell does not go to the AI, nor does simple with execution_mode "shell". */
    @Test
    void nestedShellCommands_areNoDuplicatedTask() {
        Map<String, Object> config = Map.of("expression", "x",
                "cases", List.of(Map.of("value", "x", "blocks", List.of(
                        Map.of("type", "shell", "config", Map.of("command", "set -e\necho '" + PARAGRAPH + "'"))))),
                "default", List.of(Map.of("type", "simple",
                        "config", Map.of("command", PARAGRAPH, "execution_mode", "shell"))));

        assertThat(apply(PARAGRAPH, block("switch", "route", config))).isEmpty();
    }

    @Test
    void aDatedModelId_onlyInTheDirectModelField() {
        List<AnalyzerFinding> out = apply(null,
                block("simple", "a", Map.of("model", "claude-sonnet-4-5")),
                block("simple", "b", Map.of("model", "sonnet")),
                block("simple", "c", Map.of("command", "use claude-opus-4")));

        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("dated-model-id");
            assertThat(f.blockName()).isEqualTo("a");
            assertThat(f.detail()).contains("\"claude-sonnet-4-5\"");
        });
    }
}
