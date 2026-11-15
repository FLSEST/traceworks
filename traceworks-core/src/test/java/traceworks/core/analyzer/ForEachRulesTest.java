package traceworks.core.analyzer;

import traceworks.core.model.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ForEachRulesTest {

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(config);
        return b;
    }

    private static List<AnalyzerFinding> run(BlockDefinition... blocks) {
        List<AnalyzerFinding> out = new ArrayList<>();
        ForEachRules.apply(List.of(blocks), out);
        return out;
    }

    private static Map<String, Object> config(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void commandWithoutSourceAndWithoutItems_isAnError() {
        var findings = run(block("foreach", "loop", config("items_command", "ls")));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("items-command-without-source");
            assertThat(f.severity()).isEqualTo(AnalyzerFinding.Severity.ERROR);
            assertThat(f.blockName()).isEqualTo("loop");
            assertThat(f.detail()).contains("not set").contains("zero items");
        });
    }

    @Test
    void commandWithAnotherSource_namesTheSourceInTheText() {
        var findings = run(block("foreach", "f", config("items_command", "ls", "items_source", "static")));

        assertThat(findings).singleElement()
                .satisfies(f -> assertThat(f.detail()).contains("\"static\""));
    }

    @Test
    void whenItemsStepIn_itIsOnlyAWarning() {
        var findings = run(block("foreach", "f", config("items_command", "ls", "items", List.of("a", "b"))));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(AnalyzerFinding.Severity.WARNING);
            assertThat(f.detail()).contains("come from items");
        });
    }

    @Test
    void itemsAsANonEmptyText_countAsItems() {
        var findings = run(block("foreach", "f", config("items_command", "ls", "items", "a\nb")));

        assertThat(findings).singleElement()
                .satisfies(f -> assertThat(f.severity()).isEqualTo(AnalyzerFinding.Severity.WARNING));
    }

    @Test
    void emptyItems_doNotCountAsItems() {
        for (Object empty : new Object[]{List.of(), List.of(" ", ""), "  ", 5}) {
            var findings = run(block("foreach", "f", config("items_command", "ls", "items", empty)));
            assertThat(findings).singleElement()
                    .satisfies(f -> assertThat(f.severity()).isEqualTo(AnalyzerFinding.Severity.ERROR));
        }
    }

    @Test
    void sourceCommand_isNoFinding() {
        assertThat(run(block("foreach", "f", config("items_command", "ls", "items_source", "command")))).isEmpty();
    }

    @Test
    void emptyOrMissingCommand_isNoFinding() {
        assertThat(run(block("foreach", "f", config("items_command", "  ")))).isEmpty();
        assertThat(run(block("foreach", "f", config("items", List.of("a"))))).isEmpty();
        assertThat(run(block("foreach", "f", config("items_command", 42)))).isEmpty();
    }

    @Test
    void otherBlockTypes_blocksWithoutConfig_andNullEntries_areIgnored() {
        assertThat(run(block("shell", "s", config("items_command", "ls")))).isEmpty();
        assertThat(run(block("foreach", "f", null))).isEmpty();
        List<AnalyzerFinding> out = new ArrayList<>();
        List<BlockDefinition> withNull = new ArrayList<>();
        withNull.add(null);
        ForEachRules.apply(withNull, out);
        assertThat(out).isEmpty();
    }

    @Test
    void index_isThePositionInTheWorkflow() {
        var findings = run(block("shell", "a", config("command", "x")),
                block("foreach", "b", config("items_command", "ls")));

        assertThat(findings).singleElement().satisfies(f -> assertThat(f.blockIndex()).isEqualTo(1));
    }
}
