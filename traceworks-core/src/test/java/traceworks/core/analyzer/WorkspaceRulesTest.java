package traceworks.core.analyzer;

import traceworks.core.model.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class WorkspaceRulesTest {

    private final WorkflowAnalyzer analyzer = new WorkflowAnalyzer();

    private static BlockDefinition shell(Map<String, Object> config) {
        BlockDefinition block = new BlockDefinition();
        block.setType("shell");
        block.setName("first");
        block.setConfig(new HashMap<>(config));
        return block;
    }

    @Test
    void installNpm_isReported_whereItIsSet_inTheMetadataAndOnTheFirstBlock() {
        var block = shell(Map.of("command", "set -euo pipefail\nls",
                "workspace", Map.of("mode", "isolated", "install_npm", true)));

        var findings = analyzer.analyze(List.of(block), null,
                Map.of("workspace", Map.of("mode", "isolated", "install_npm", true)));

        var installNpm = findings.stream().filter(f -> f.rule().equals("legacy-install-npm")).toList();
        assertThat(installNpm)
                .extracting(AnalyzerFinding::severity, AnalyzerFinding::blockIndex)
                .containsExactly(tuple(AnalyzerFinding.Severity.WARNING, -1),
                        tuple(AnalyzerFinding.Severity.WARNING, 0));
        assertThat(installNpm.getFirst().detail()).contains("npm ci", "remove install_npm");
    }

    @Test
    void anUnknownMode_isReportedWhereItIsSet() {
        var findings = analyzer.analyze(List.of(shell(Map.of("command", "set -euo pipefail\nls",
                "workspace", Map.of("mode", "sandbox")))), null, Map.of("workspace", Map.of("mode", "clone")));

        assertThat(findings).filteredOn(f -> f.rule().equals("unknown-workspace-mode"))
                .extracting(AnalyzerFinding::severity, AnalyzerFinding::blockIndex)
                .containsExactly(tuple(AnalyzerFinding.Severity.ERROR, -1), tuple(AnalyzerFinding.Severity.ERROR, 0));
        assertThat(findings).filteredOn(f -> f.rule().equals("unknown-workspace-mode"))
                .extracting(AnalyzerFinding::detail)
                .containsExactly("metadata.workspace.mode \"clone\" does not exist. Traceworks accepts project (the run"
                        + " works in its workspace as it is) and refuses isolated (the operator isolates a run with"
                        + " run --source).", "workspace.mode \"sandbox\" does not exist. Traceworks accepts project"
                        + " (the run works in its workspace as it is) and refuses isolated (the operator isolates a"
                        + " run with run --source).");
    }

    @Test
    void workspaceOnALaterBlock_hasNoEffect_andIsReported() {
        var later = shell(Map.of("command", "set -euo pipefail\nls", "workspace", Map.of("mode", "isolated")));

        var findings = analyzer.analyze(List.of(shell(Map.of("command", "set -euo pipefail\nls")), later), null, Map.of());

        assertThat(findings).filteredOn(f -> f.rule().equals("workspace-not-on-first-block"))
                .extracting(AnalyzerFinding::blockIndex).containsExactly(1);
    }

    @Test
    void anIsolatedRunChangingIntoTheProjectFolder_isReported_readingAndHandingOverAreNot() {
        Map<String, Object> isolated = Map.of("workspace", Map.of("mode", "isolated"));
        var builds = shell(Map.of("command", "set -euo pipefail\ncd \"{{ project_path }}\"\nmake package"));
        var handsOver = shell(Map.of("command", "set -euo pipefail\ngit -C {{ project_path }} fetch x y:z"));

        var findings = analyzer.analyze(List.of(builds, handsOver), null, isolated);
        var notIsolated = analyzer.analyze(List.of(builds), null, Map.of());

        assertThat(findings).filteredOn(f -> f.rule().equals("build-in-project-path"))
                .extracting(AnalyzerFinding::blockIndex).containsExactly(0);
        assertThat(notIsolated).noneMatch(f -> f.rule().equals("build-in-project-path"));
    }

    @Test
    void theVisibleStepThatReplacesIt_andIsolationWithoutIt_areFine() {
        var npm = shell(Map.of("command", "set -euo pipefail\nnpm ci", "workspace", Map.of("mode", "isolated")));

        var findings = analyzer.analyze(List.of(npm), null,
                Map.of("workspace", Map.of("mode", "isolated", "install_npm", false)));

        assertThat(findings).noneMatch(f -> f.rule().equals("legacy-install-npm"));
    }

    @Test
    void workspaceRules_useTheDefinitionsOwnIsolationCheck() {
        var block = shell(Map.of("command", "cd {{ project_path }} && make",
                "workspace", Map.of("mode", "isolated")));
        Map<String, Object> metadata = Map.of("workspace", "isolated");
        var definition = new traceworks.core.model.WorkflowDefinition(null, null, null,
                List.of(block), null, null, null, null, metadata, null, null, null, null);

        boolean verdict = analyzer.analyze(List.of(block), null, metadata).stream()
                .anyMatch(f -> f.rule().equals("build-in-project-path"));

        assertThat(verdict).isEqualTo(definition.requestsIsolatedWorkspace());
    }
}
