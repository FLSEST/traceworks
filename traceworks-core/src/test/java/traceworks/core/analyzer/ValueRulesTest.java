package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.model.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ValueRulesTest {

    private static BlockDefinition block(String type, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName("b");
        b.setConfig(new HashMap<>(config));
        return b;
    }

    private static List<AnalyzerFinding> findings(BlockDefinition... blocks) {
        List<AnalyzerFinding> out = new ArrayList<>();
        ValueRules.apply(List.of(blocks), out);
        return out;
    }

    @Test
    void valuesTheBlocksShellLinesUse_areFine_inEveryShellLineOfTheBlock() {
        assertThat(findings(
                block("shell", Map.of("values", Map.of("REVIEW", "{{ block.review.output }}", "TITLE", "a {{ name }}"),
                        "command", "printf '%s' \"$REVIEW\" > \"${TITLE}.md\"")),
                block("shell", Map.of("values", Map.of("ANSWER", "{{ previous_output }}"),
                        "run_if", "test -n \"$ANSWER\"", "command", "true")),
                block("foreach", Map.of("execution_mode", "ai", "items_source", "command",
                        "values", Map.of("KNOWN", "{{ audit_items }}"), "items_command", "printf '%s' \"$KNOWN\"",
                        "command", "Judge {{ item }}")),
                block("conditional", Map.of("execution_mode", "shell", "values", Map.of("V", "{{ item }}"),
                        "condition", "test \"${#V}\" -gt 0", "then_command", "true")))).isEmpty();
    }

    @Test
    void aNameThatIsNoneOrReserved_andValuesOfAnotherShape_areErrors() {
        Map<Map<String, Object>, String> invalid = new java.util.LinkedHashMap<>();
        invalid.put(Map.of("values", Map.of("review", "x"), "command", "echo \"$review\""),
                "values: \"review\" is not a variable name — use capital letters, digits and _, not starting with a"
                        + " digit; the step fails when it is reached");
        invalid.put(Map.of("values", Map.of("1ST", "x"), "command", "echo"), "values: \"1ST\" is not a variable name");
        invalid.put(Map.of("values", Map.of("PATH", "x"), "command", "echo \"$PATH\""),
                "values: \"PATH\" is reserved — bash sets or uses it");
        for (String name : List.of("_", "EPOCHSECONDS", "EPOCHREALTIME", "SRANDOM", "HISTCMD", "DIRSTACK",
                "POSIXLY_CORRECT", "FUNCNEST", "EXECIGNORE", "TMOUT", "PS1", "HISTFILE", "TIMEFORMAT", "INPUTRC",
                "LANGUAGE", "HOSTNAME", "IFS", "BASH_ENV", "BASHFUL", "GLOBSORT", "MAPFILE", "COPROC")) {
            invalid.put(Map.of("values", Map.of(name, "x"), "command", "echo \"$" + name + "\""),
                    "values: \"" + name + "\" is reserved — bash sets or uses it");
        }
        invalid.put(Map.of("values", Map.of("HOME", "x"), "command", "echo"), "values: \"HOME\" is reserved");
        invalid.put(Map.of("values", Map.of("TRACEWORKS_VALUE", "x"), "command", "echo"),
                "values: \"TRACEWORKS_VALUE\" is reserved — names that start with TRACEWORKS_ are Traceworks' own");
        invalid.put(Map.of("values", Map.of("LC_ALL", "x"), "command", "echo"), "values: \"LC_ALL\" is reserved");
        invalid.put(Map.of("values", Map.of("IFS", "x"), "command", "echo"),
                "values: \"IFS\" is reserved — bash sets or uses it; choose another name");
        invalid.put(Map.of("values", Map.of("BASH_ENV", "x"), "command", "echo"), "values: \"BASH_ENV\" is reserved");
        invalid.put(Map.of("values", Map.of("ANTHROPIC_API_KEY", "x"), "command", "echo"),
                "values: \"ANTHROPIC_API_KEY\" is reserved — it is the credential of an AI provider");
        invalid.put(Map.of("values", Map.of("TOKEN", "x"), "secrets", List.of("TOKEN"), "command", "echo \"$TOKEN\""),
                "values: \"TOKEN\" is a secret of this block — a value needs a name of its own");
        invalid.put(Map.of("values", Map.of("N", 5), "command", "echo \"$N\""),
                "values: the value of \"N\" is not a text");
        invalid.put(Map.of("values", List.of("N"), "command", "echo"), "values is not an object of names and templates");
        invalid.put(Map.of("values", Map.of("ARG", "x"), "container_image", "alpine:3", "container_args", List.of("a")),
                "values: a tool step (container_args) runs no shell, so no variable can be set for it");

        invalid.forEach((config, message) -> assertThat(findings(block("shell", config))).as(message)
                .singleElement().satisfies(f -> {
                    assertThat(f.rule()).isEqualTo("invalid-value-name");
                    assertThat(f.severity()).isEqualTo(Severity.ERROR);
                    assertThat(f.detail()).contains(message);
                }));
    }

    @Test
    void aNameNoShellLineUses_isAnInfo() {
        assertThat(findings(block("shell", Map.of("values", Map.of("REVIEW", "x", "REVIEWED", "y"),
                "command", "echo \"$REVIEWED\" $REVIEW_2 'REVIEW'")))).singleElement().satisfies(f -> {
                    assertThat(f.rule()).isEqualTo("unused-value");
                    assertThat(f.severity()).isEqualTo(Severity.INFO);
                    assertThat(f.detail()).isEqualTo("values sets REVIEW, but no shell line of the block uses $REVIEW"
                            + " — the variable is set and nothing reads it");
                });
        assertThat(findings(block("foreach", Map.of("execution_mode", "ai", "items", List.of("a"),
                "values", Map.of("NOTE", "x"), "command", "Judge with $NOTE")))).as("a prompt is no shell line")
                .singleElement().satisfies(f -> assertThat(f.rule()).isEqualTo("unused-value"));
    }

    /** A name programs read as configuration is allowed, since the variable is not exported; the author is told. */
    @Test
    void aNameOfAFamilyProgramsReadAsConfiguration_isAWarning() {
        for (String name : List.of("GIT_SSH_COMMAND", "NODE_OPTIONS", "JAVA_TOOL_OPTIONS", "PYTHONPATH",
                "MAVEN_OPTIONS", "NODE_PATH", "JAVA_HOME")) {
            assertThat(findings(block("shell", Map.of("values", Map.of(name, "x"), "command", "echo \"$" + name + "\""))))
                    .as(name).singleElement().satisfies(f -> {
                        assertThat(f.rule()).isEqualTo("value-name-configures-program");
                        assertThat(f.severity()).isEqualTo(Severity.WARNING);
                        assertThat(f.detail()).startsWith("values sets " + name + ", a name programs read as their"
                                + " configuration").contains("is not exported");
                    });
        }
        assertThat(findings(block("shell", Map.of("values", Map.of("REVIEW", "x", "OPTIONS", "y", "GITHUB", "z"),
                "command", "echo \"$REVIEW$OPTIONS$GITHUB\"")))).isEmpty();
    }

    @Test
    void nestedBlocks_areCheckedToo() {
        BlockDefinition route = block("switch", Map.of("expression", "a", "cases", List.of(Map.of("value", "a",
                "blocks", List.of(Map.of("type", "shell", "name", "inner",
                        "config", Map.of("values", Map.of("lower", "x"), "command", "echo")))))));

        assertThat(findings(route)).singleElement().satisfies(f -> assertThat(f.detail())
                .startsWith("Block 1 > ").contains("values: \"lower\" is not a variable name"));
    }
}
