package traceworks.core.analyzer;

import traceworks.core.compat.EarlierNames;
import traceworks.core.compat.GermanWords;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every analyzer rule delivers its finding in English. The test
 * triggers every rule, including the variants with their own text module (suggestion, block level, "only takes effect
 * with", nested path), and checks the text. Which rules exist it reads from the sources
 * ({@code AnalyzerFinding.of("...")}): a new rule without a fixture here turns it red.
 */
class AnalyzerFindingLanguagesTest {

    private static final String RULES_PACKAGE = "src/main/java/traceworks/core/analyzer";
    private static final Pattern RULE_KEY = Pattern.compile("AnalyzerFinding\\.of\\(\\s*\"([a-z-]+)\"");
    /** Umlauts, sharp s and German quotation marks, written as escapes so this file passes the language gate. */
    private static final Pattern GERMAN_LETTERS =
            Pattern.compile("[\\u00e4\\u00f6\\u00fc\\u00c4\\u00d6\\u00dc\\u00df\\u201e\\u201c]");

    private final WorkflowAnalyzer analyzer = new WorkflowAnalyzer();

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(new HashMap<>(config));
        return b;
    }

    private static final String PARAGRAPH = "Always keep the answer short, name the file and the line, and never "
            + "invent a finding that the log does not show.";

    /** Fixtures, together every rule and every text module at least once. */
    private List<AnalyzerFinding> allFindings() {
        List<AnalyzerFinding> out = new ArrayList<>();
        // Wiring: unknown-block-reference, chain-only-placeholder, audit-only-placeholder, unwired-output
        out.addAll(analyzer.analyze(List.of(
                block("simple", "first", Map.of("command", "Summarise {{ block.nope.output }} {{ previous_chain_output }}"
                        + " {{ audit_findings }}")),
                block("simple", "second", Map.of("command", "Write a report")),
                block("shell", "last", Map.of("command", "set -euo pipefail\necho done"))), null));
        // Hygiene: absolute-home-path, missing-strict-mode, duplicated-instructions (nested too), dated-model-id
        out.addAll(analyzer.analyze(List.of(
                block("shell", "home", Map.of("command", "ls /home/alice/projects")),
                block("simple", "dup", Map.of("command", "Check it. " + PARAGRAPH, "model", "claude-opus-4")),
                block("switch", "branch", Map.of("expression", "x", "cases", List.of(Map.of("value", "a", "blocks",
                        List.of(Map.of("type", "simple", "name", "inner", "config", Map.of("command", PARAGRAPH)))))))),
                "Intro\n\n" + PARAGRAPH));
        // Approval, foreach (both variants)
        out.addAll(analyzer.analyze(List.of(
                block("approval", "gate", Map.of("message", "Go?", "on_no", "fail", "options", List.of("Yes", "Ok"))),
                block("foreach", "each", Map.of("items_command", "ls", "command", "echo {{ item }}")),
                block("foreach", "each2", Map.of("items_command", "ls", "items", List.of("a"), "items_source", "list",
                        "command", "echo {{ item }}"))), null));
        // Keys: unknown with suggestion / block level / without hint, "only takes effect with", required missing
        // (with "is set instead"), invalid value, nested path with names
        Map<String, Object> nested = new HashMap<>();
        nested.put("type", "simple");
        nested.put("name", "report");
        nested.put("config", Map.of("prompt", "Write the report", "on_failure", "fail"));
        out.addAll(analyzer.analyze(List.of(
                block("shell", "s", Map.of("command", "set -euo pipefail\nls", "timeout_second", 60, "name", "x")),
                block("retry", "r", Map.of("command", "npm test", "container_image", "node:24")),
                block("foreach", "f", Map.of("items", List.of("a"), "comand", "echo")),
                block("switch", "w", Map.of("expression", "{{ previous_output }}",
                        "cases", List.of(Map.of("value", "GREEN", "blocks", List.of(nested))))),
                // Old key name: alone (rename) and next to the new one (remove)
                block("sub-workflow", "old", Map.of("workflowId", "abc")),
                block("sub-workflow", "both", Map.of("workflow_id", "abc", "workflowId", "abc"))), null));
        // Quoting: quoted-placeholder, placeholder-self-compare
        out.addAll(analyzer.analyze(List.of(
                block("shell", "quoted", Map.of("command", "set -euo pipefail\necho 'LAST={{ previous_output }}'")),
                block("shell", "unset", Map.of("command", "set -euo pipefail\nX='{{ x }}'\n"
                        + "[ \"$X\" = '{{ x }}' ] && X=default"))), null));
        // An AI step's output in a shell command: protected-value-in-shell
        out.addAll(analyzer.analyze(List.of(
                block("simple", "review", Map.of("command", "Review the diff")),
                block("shell", "report", Map.of("command", "set -euo pipefail\nprintf '%s' {{ block.review.output }}"))),
                null));
        // The shell variables of a block: invalid-value-name, value-name-configures-program, unused-value
        out.addAll(analyzer.analyze(List.of(
                block("shell", "named", Map.of("command", "set -euo pipefail\necho", "values", Map.of("lower", "x"))),
                block("shell", "family", Map.of("command", "set -euo pipefail\necho \"$NODE_OPTIONS\"",
                        "values", Map.of("NODE_OPTIONS", "x"))),
                block("shell", "unused", Map.of("command", "set -euo pipefail\necho", "values", Map.of("NOTE", "x")))),
                null));
        // Heredoc bodies: heredoc-placeholder (interpreter source, unquoted delimiter, escaped block)
        out.addAll(analyzer.analyze(List.of(
                block("shell", "source", Map.of("command", "set -euo pipefail\npython3 - <<'PY'\n"
                        + "x = '''{{ previous_output }}'''\nPY\ncat <<EOF\n{{ previous_output }}\nEOF")),
                block("shell", "escaped", Map.of("command", "set -euo pipefail\ncat > out.txt <<'EOF'\n{{ x }}\nEOF",
                        "shell_escape_variables", true))), null));
        // Isolation setting: legacy-install-npm (metadata and first block), unknown-workspace-mode,
        // workspace-not-on-first-block, build-in-project-path
        out.addAll(analyzer.analyze(List.of(
                block("shell", "prepare", Map.of("command", "set -euo pipefail\nls",
                        "workspace", Map.of("mode", "sandbox", "install_npm", true))),
                block("shell", "build", Map.of("command", "set -euo pipefail\ncd {{ project_path }}\nmake",
                        "workspace", Map.of("mode", "isolated")))), null,
                Map.of("workspace", Map.of("mode", "isolated", "install_npm", true))));
        // Shape: dead-nested-blocks, unknown-block-field (config key, near spelling, otherwise; nested)
        BlockDefinition loose = block("shell", "loose", Map.of("command", "set -euo pipefail\nls"));
        loose.putUnknownField("on_failure", "continue");
        loose.putUnknownField("Name", "loose");
        loose.putUnknownField("comment", "note");
        Map<String, Object> strayNested = new HashMap<>();
        strayNested.put("type", "shell");
        strayNested.put("config", Map.of("command", "set -euo pipefail\nls"));
        strayNested.put("timeout_seconds", 60);
        out.addAll(analyzer.analyze(List.of(
                block("foreach", "dead", Map.of("items", List.of("a"), "command", "echo {{ item }}",
                        "blocks", List.of(Map.of("type", "simple", "config", Map.of("command", "x"))))),
                loose,
                block("switch", "sw", Map.of("expression", "x", "default", List.of(strayNested)))), null));
        // Mounts: secret-mount (top level and nested)
        Map<String, Object> mounted = new HashMap<>();
        mounted.put("type", "shell");
        mounted.put("config", Map.of("command", "set -euo pipefail\nls", "container_image", "alpine:3.20",
                "mounts", List.of("stable-secrets:/s:ro")));
        out.addAll(analyzer.analyze(List.of(
                block("shell", "keys", Map.of("command", "set -euo pipefail\nls", "container_image", "alpine:3.20",
                        "mounts", List.of("~/" + EarlierNames.SERVICE_DATA_DIRECTORY + ":/data:ro", "~/.ssh:/ssh:ro",
                                "/run:/r", "~:/h", "/run/podman/podman.sock:/p", "/:/all"))),
                block("switch", "pick", Map.of("expression", "x", "default", List.of(mounted)))), null));
        // Secrets: invalid-secret-name
        out.addAll(analyzer.analyze(List.of(block("shell", "deploy", Map.of("command", "set -euo pipefail\n./deploy",
                "secrets", List.of("PATH", "{{ name }}", "lower")))), null));
        // Parking: nested-wait-point (agentic plan approval), question-changes-on-resume
        out.addAll(analyzer.analyze(List.of(
                block("agentic", "plan", Map.of("goal", "Fix the tests", "require_approval", true)),
                block("approval", "when", Map.of("question", "Ship the build of {{ run_timestamp }}?"))), null));
        // Traceworks rules: explicit-execution-mode, unbalanced-placeholder, report-block-required,
        // unsupported-block-type (a type known by name, near spelling, no type), unprovided-variable, unknown-provider,
        // blank-ai-prompt, host-dependent-step, unknown-requirement (with the network-none hint), unsupported-skills,
        // dollar-placeholder-in-switch, docker-socket-refused, unknown-format, unsupported-workspace-isolation
        BlockDefinition skills = block("simple", "skills", Map.of("command", "Summarise"));
        skills.putUnknownField("skillIds", List.of("skill-1"));
        out.addAll(analyzer.analyze(new WorkflowDefinition("id", "all", null, List.of(
                block("conditional", "cond", Map.of("condition", "test -f x", "then_command", "echo y")),
                block("shell", "spaces", Map.of("command", "set -euo pipefail\necho {{x }}")),
                block("simple", "prev", Map.of("command", "Compare with {{ previous_report }}")),
                block("todo-create", "todo", Map.of("title", "x")),
                block("shel", "typo", Map.of("command", "ls")),
                block(null, "untyped", Map.of()),
                block("simple", "platform", Map.of("command", "Open: {{ open_todos }}", "provider", "gemini")),
                block("simple", "blank", Map.of("command", "  ")),
                block("shell", "fetch", Map.of("command", "set -euo pipefail\ncurl -s https://example.org")),
                skills,
                block("switch", "dollar", Map.of("expression", "${status}", "cases", List.of())),
                block("shell", "socket", Map.of("command", "set -euo pipefail\nls", "mount_docker_socket", true))),
                null, null, "openai", null, Map.of("workspace", Map.of("mode", "isolated")), null,
                List.of("gpu", "container.network-none"), "n8n-workflow", 2)));
        // Images: unpinned-base-image
        out.addAll(analyzer.analyze(new WorkflowDefinition("id", "images", null, List.of(), null, null, null, null,
                null, null, null, null, null), null, List.of(new traceworks.core.port.PackageImage(
                "tools", "/pkg/images/tools", "a".repeat(64), "FROM node:22-slim\n"))));
        // Audit workflows: invalid-audit-config, invalid-audit-key, report-block-missing, catalog-invalid,
        // previous-report-outside-audit
        out.addAll(analyzer.analyze(new WorkflowDefinition("id", "audit", null, List.of(
                block("shell", "report", Map.of("command", "set -euo pipefail\necho report"))), null, null, null, null,
                Map.of("audit", Map.of("enabled", true, "key", "Bad Key", "category", "c", "schema_version", 3)),
                null, null, null, null), null, List.of(), List.of(), "{\"format\":2}".getBytes()));
        out.addAll(analyzer.analyze(new WorkflowDefinition("id", "audit", null, List.of(
                block("shell", "report", Map.of("command", "set -euo pipefail\necho report")),
                block("simple", "prev", Map.of("command", "Compare with {{ previous_report }}"))), null, null, null,
                null, Map.of("audit", Map.of("enabled", true, "key", "k", "category", "c", "report_block", "summary")),
                null, null, null, null), null));
        // The register folder outside an audit workflow: register-dir-outside-audit
        out.addAll(analyzer.analyze(new WorkflowDefinition("id", "no audit", null, List.of(
                block("shell", "known", Map.of("command", "set -euo pipefail\nwc -l {{ register_dir }}/findings.jsonl"))),
                null, null, null, null, null, null, null, null, null), null));
        // The register folder in a prompt: register-dir-in-ai-prompt
        out.addAll(analyzer.analyze(new WorkflowDefinition("id", "prompt", null, List.of(
                block("simple", "judge", Map.of("command", "Read {{ register_dir }}/findings.jsonl"))),
                null, null, null, null, null, null, null, null, null), null));
        // An image directory whose name is not valid: invalid-image-name
        out.addAll(analyzer.analyze(new WorkflowDefinition("id", "images", null, List.of(), null, null, null, null,
                null, null, null, null, null), null, List.of(), List.of("MyTool")));
        // Package images of a child of another package: child-package-image
        out.addAll(analyzer.analyze(new WorkflowDefinition("id", "parent", null, List.of(
                block("sub-workflow", "child", Map.of("workflow_id", "./other"))), null, null, null, null, null, null,
                null, null, null), new traceworks.core.testing.MapWorkflowResolver()
                .addFromAnotherPackage("./other", new WorkflowDefinition("other", "other", null, List.of(
                        block("shell", "scan", Map.of("command", "set -euo pipefail\nscan",
                                "container_image", "./images/tools"))),
                        null, null, null, null, null, null, List.of("package.images"), null, null))));
        // The commonest typos: unknown-block-property, step-output-out-of-range, duplicate-block-name and
        // unknown-variable (with a near name); empty-workflow comes from the definitions without blocks above
        out.addAll(analyzer.analyze(new WorkflowDefinition("id", "typos", null, List.of(
                block("shell", "y", Map.of("command", "set -euo pipefail\necho one")),
                block("shell", "y", Map.of("command", "set -euo pipefail\necho {{ block.y.outptu }} {{ nme }}"
                        + " {{ step_7_output }}"))), null, null, null, null, null, Map.of("name", "world"), null,
                null, null)));
        // unknown-model-backend: the text is the CLI's own, here a fake's
        out.addAll(new WorkflowAnalyzer().withAiClis(List.of(traceworks.core.testing.FakeAiCli.pi()
                .unknownModel("ollama/llama3", "The Pi model ollama/llama3 names a backend nobody knows")))
                .analyze(List.of(block("simple", "ask", Map.of("command", "Say hi", "provider", "PI", "model",
                        "ollama/llama3"))), null));
        // Model choices: unused-model-choice, and the admission's invalid-model-choice and placeholder-in-model
        out.addAll(analyzer.analyze(new WorkflowDefinition("wf-choice", "Choice", null, List.of(
                block("simple", "typo", Map.of("command", "Judge", "model_choice", "jugde")),
                block("simple", "text", Map.of("command", "Judge", "model", "{{ m }}"))), null, null, null, null, null,
                null, List.of("ai.model_choice"), null, null, Map.of("judge", Map.of("default", "a", "options",
                Map.of("a", Map.of("provider", "CLAUDE", "model", "claude-fable-5-2")))))));
        return out;
    }

    @Test
    void everyFindingIsEnglish() {
        List<AnalyzerFinding> findings = allFindings();

        assertThat(findings).isNotEmpty().allSatisfy(f -> {
            assertThat(f.detail()).as("detail of %s", f.rule()).isNotBlank();
            assertThat(GERMAN_LETTERS.matcher(f.detail()).find()
                    || GermanWords.FUNCTION_WORDS.matcher(f.detail()).find())
                    .as("detail of %s sounds German: %s", f.rule(), f.detail()).isFalse();
        });
    }

    @Test
    void theFixturesTriggerEveryRule() throws IOException {
        Set<String> declared = new TreeSet<>();
        List<Path> sources;
        try (Stream<Path> files = Files.list(rulesDirectory())) {
            sources = files.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertThat(sources).as("analyzer sources").isNotEmpty();
        for (Path file : sources) {
            Matcher m = RULE_KEY.matcher(Files.readString(file));
            while (m.find()) declared.add(m.group(1));
        }
        Set<String> covered = new TreeSet<>();
        allFindings().forEach(f -> covered.add(f.rule()));

        assertThat(declared).as("rules in the sources").hasSizeGreaterThanOrEqualTo(33);
        assertThat(covered).containsAll(declared);
    }

    @Test
    void theTextModulesAreCovered() {
        List<String> texts = allFindings().stream().map(AnalyzerFinding::detail).toList();

        assertThat(texts).anyMatch(t -> t.contains("did you mean timeout_seconds?"))
                .anyMatch(t -> t.contains("did you mean {{ name }} for {{ nme }}?"))
                .anyMatch(t -> t.contains("name belongs on the block level"))
                .anyMatch(t -> t.contains("only takes effect with"))
                .anyMatch(t -> t.contains("(comand is set instead)"))
                .anyMatch(t -> t.startsWith("Block 4 › cases[0] › Block 1 \"report\": "))
                .anyMatch(t -> t.contains("in the nested block 'inner'"))
                .anyMatch(t -> t.contains("the block runs with zero items"))
                .anyMatch(t -> t.contains("the items come from items"))
                .anyMatch(t -> t.contains("config.blocks (foreach) with 1 block entries never runs"))
                .anyMatch(t -> t.contains("on_failure sits next to config instead of inside it"))
                .anyMatch(t -> t.endsWith("rename the key to workflow_id"))
                .anyMatch(t -> t.endsWith("workflow_id is set as well and wins, remove workflowId"))
                .anyMatch(t -> t.contains("did you mean name?"))
                .anyMatch(t -> t.contains("unknown block field comment"))
                .anyMatch(t -> t.startsWith("Block 3 › default › Block 1: timeout_seconds sits next to config"))
                .anyMatch(t -> t.contains("into the body of a heredoc that python3 reads as its program"))
                .anyMatch(t -> t.contains("into the body of a heredoc with an unquoted delimiter"))
                .anyMatch(t -> t.contains("into the body of a heredoc although the block sets shell_escape_variables"))
                .anyMatch(t -> t.contains("todo-create is a block type Traceworks does not run"))
                .anyMatch(t -> t.contains("did you mean shell?"))
                .anyMatch(t -> t.contains("no network is the default"));
    }

    @Test
    void of_requiresADetail() {
        assertThatThrownBy(() -> AnalyzerFinding.of("r", AnalyzerFinding.Severity.INFO, 0, null, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnalyzerFinding.of("r", AnalyzerFinding.Severity.INFO, 0, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * One text per finding: Traceworks produces English only, so no second text in another language exists and
     * there is nothing to choose.
     */
    @Test
    void aFindingCarriesOneText() {
        assertThat(Stream.of(AnalyzerFinding.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("rule", "severity", "blockIndex", "blockName", "detail");
    }

    /**
     * The analyzer's main sources, found from this class's compiled location, not from the working directory: the
     * nearest directory above it that holds {@value #RULES_PACKAGE} is the module, whatever the build tool's output
     * layout ({@code target/test-classes} for Maven). Fails with the searched location instead of passing empty.
     */
    private static Path rulesDirectory() {
        Path start;
        try {
            start = Path.of(AnalyzerFindingLanguagesTest.class.getProtectionDomain().getCodeSource().getLocation()
                    .toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("cannot locate the test classes", e);
        }
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            Path rules = dir.resolve(RULES_PACKAGE);
            if (Files.isDirectory(rules)) return rules;
        }
        throw new IllegalStateException("no " + RULES_PACKAGE + " above " + start);
    }
}
