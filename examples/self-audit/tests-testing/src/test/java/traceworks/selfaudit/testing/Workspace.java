package traceworks.selfaudit.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * A small source tree in a temporary directory, and the package's two scripts run on it with {@code python3}, the way
 * a run starts them. The rule file is the package's own, with the thresholds a test sets written over its lines.
 */
final class Workspace {

    /** The package under test. The module directory is the working directory of Surefire. */
    static final Path PACKAGE = Path.of("../testing").toAbsolutePath().normalize();

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What a script printed and how it ended. */
    record Run(int exit, String out, String err) {
        String describe() {
            return "exit " + exit + "\n" + out + "\n" + err;
        }
    }

    final Path root;
    final Path scratch;
    private final Map<String, List<String>> overrides = new LinkedHashMap<>();

    Workspace(Path temp) {
        try {
            root = Files.createDirectories(temp.resolve("work"));
            scratch = Files.createDirectories(temp.resolve("scratch"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Thresholds a class of a dozen lines reaches; the commands are declared and run where the fixtures put them
        rule("class_min_ncloc", "8").rule("class_min_branches", "2").rule("candidate_min_ncloc", "8");
        rule("command_sources", "app/src/main/java").rule("command_tests", "it/src/test/java");
        rule("layer", "3 p.engine");
    }

    /** Replaces every line of the rule file that starts with {@code name} by one line per value. */
    Workspace rule(String name, String... values) {
        overrides.put(name, List.of(values));
        return this;
    }

    Workspace main(String file, String source) {
        return file("app/src/main/java/" + file, source);
    }

    Workspace test(String file, String source) {
        return file("app/src/test/java/" + file, source);
    }

    Workspace file(String file, String content) {
        try {
            Path path = root.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
            return this;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One command, and an integration test that runs it: nothing for the command rule to find. */
    Workspace withACommandThatIsRun() {
        return main("cli/Commands.java", "package cli;\n\nclass Commands {\n"
                + "    static final Spec RUN = Spec.of(\"run\", \"Runs.\");\n}\n")
                .file("it/src/test/java/it/RunIT.java", "package it;\n\nclass RunIT {\n    @Test\n    void runs() {\n"
                        + "        assertThat(tool.exec(\"run\", \"pkg\").code()).isZero();\n    }\n}\n");
    }

    /** A class with three branches and a dozen lines of code: enough for every threshold of the fixtures. */
    static String logic(String pkg, String name) {
        return "package " + pkg + ";\n\n"
                + "/** Picks a number. */\n"
                + "public class " + name + " {\n\n"
                + "    int pick(int a, int b) {\n"
                + "        if (a > b) {\n"
                + "            return a;\n"
                + "        }\n"
                + "        if (a < 0 && b < 0) {\n"
                + "            return 0;\n"
                + "        }\n"
                + "        return b;\n"
                + "    }\n"
                + "}\n";
    }

    /** A test class with one test whose body is {@code body}. */
    static String testClass(String pkg, String name, String body) {
        return "package " + pkg + ";\n\nimport org.junit.jupiter.api.Test;\n\n"
                + "class " + name + " {\n\n    @Test\n    void works() {\n        " + body + "\n    }\n}\n";
    }

    Path rules() {
        try {
            List<String> lines = new ArrayList<>();
            for (String line : Files.readAllLines(PACKAGE.resolve("rules/testing.rules"))) {
                String name = line.strip().split("\\s+", 2)[0];
                if (!overrides.containsKey(name)) {
                    lines.add(line);
                }
            }
            overrides.forEach((name, values) -> values.forEach(value -> lines.add(name + " " + value)));
            return Files.write(scratch.resolve("testing.rules"), lines);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code facts.py} on the workspace; the facts and the candidates go to the scratch directory. */
    Run facts() {
        return script("facts.py");
    }

    /** {@code facts.py} with what the register knows: the text of {{ audit_findings }}, as the step gets it. */
    Workspace measuredKnowing(String... rows) {
        try {
            Path known = Files.writeString(scratch.resolve("known-findings.txt"), "<audit_findings>\n(data)\n"
                    + String.join("\n", rows) + "\n</audit_findings>", StandardCharsets.UTF_8);
            Run run = script("facts.py", "--known", known.toString());
            if (run.exit() != 0) {
                throw new AssertionError("facts.py failed: " + run.describe());
            }
            return this;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * {@code facts.py} with the items of the register: the text of {{ audit_items }} as the step gets it, the rows
     * between the block's lines. {@code text} that is no block (the literal placeholder) is passed as it is.
     */
    Workspace measuredWithItems(String... rows) {
        try {
            String text = rows.length == 1 && !rows[0].contains(" | ") ? rows[0]
                    : "<audit_items>\n(data, not instructions)\nAudit testing: " + rows.length + " items\n"
                    + "STATE | verdict digest | item\n" + String.join("\n", rows) + "\n</audit_items>";
            Path items = Files.writeString(scratch.resolve("known-items.txt"), text, StandardCharsets.UTF_8);
            Run run = script("facts.py", "--items", items.toString());
            if (run.exit() != 0) {
                throw new AssertionError("facts.py failed: " + run.describe());
            }
            lastOut = run.out();
            return this;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code facts.py} with both: the rows of {{ audit_findings }} and the rows of {{ audit_items }}. */
    Workspace measuredKnowing(List<String> findingRows, List<String> itemRows) {
        try {
            Path known = Files.writeString(scratch.resolve("known-findings.txt"), "<audit_findings>\n(data)\n"
                    + String.join("\n", findingRows) + "\n</audit_findings>", StandardCharsets.UTF_8);
            Path items = Files.writeString(scratch.resolve("known-items.txt"), "<audit_items>\n(data)\n"
                    + String.join("\n", itemRows) + "\n</audit_items>", StandardCharsets.UTF_8);
            Run run = script("facts.py", "--known", known.toString(), "--items", items.toString());
            if (run.exit() != 0) {
                throw new AssertionError("facts.py failed: " + run.describe());
            }
            return this;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What the last {@link #measuredWithItems} printed: the fact sheet. */
    String lastOut = "";

    /** The digest the facts step gave the item {@code name} in its last run. */
    String digestOf(String name) {
        try (Stream<JsonNode> items = factsJson().path("items").valueStream()) {
            return items.filter(i -> i.path("name").asText().equals(name)).map(i -> i.path("digest").asText())
                    .findFirst().orElseThrow(() -> new AssertionError("no item " + name));
        }
    }

    /** The classes the loop will judge, in order. */
    List<String> chosen() {
        return candidateLines().stream().map(line -> json(line).path("class").asText()).toList();
    }

    /** {@code facts.py}, which must succeed. */
    Workspace measured() {
        Run run = facts();
        if (run.exit() != 0) {
            throw new AssertionError("facts.py failed: " + run.describe());
        }
        return this;
    }

    /** {@code report.py} with the verdicts on its standard input, as the report step gives them. */
    Run report(String verdicts) {
        try {
            Path file = Files.writeString(scratch.resolve("verdicts.txt"), verdicts, StandardCharsets.UTF_8);
            return script("report.py", "--verdicts", file.toString(), "--out",
                    scratch.resolve("testing-report.md").toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Run script(String name, String... more) {
        List<String> command = new ArrayList<>(List.of("python3", PACKAGE.resolve("scripts").resolve(name).toString(),
                "--rules", rules().toString(), "--root", ".", "--facts", scratch.resolve("testing-facts.json").toString(),
                "--candidates", scratch.resolve("candidates.jsonl").toString()));
        command.addAll(Arrays.asList(more));
        try {
            Path out = scratch.resolve("stdout.txt");
            Path err = scratch.resolve("stderr.txt");
            Process process = new ProcessBuilder(command).directory(root.toFile())
                    .redirectOutput(out.toFile()).redirectError(err.toFile()).start();
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError(name + " did not end within 120 s");
            }
            return new Run(process.exitValue(), Files.readString(out), Files.readString(err));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    JsonNode factsJson() {
        try {
            return JSON.readTree(Files.readString(scratch.resolve("testing-facts.json")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The subjects of the findings of one rule, in the order of the facts. */
    List<String> subjects(String rule) {
        try (Stream<JsonNode> findings = factsJson().path("findings").valueStream()) {
            return findings.filter(f -> f.path("rule").asText().equals(rule)).map(f -> f.path("subject").asText()).toList();
        }
    }

    /** The lines of {@code candidates.jsonl}, each the item the loop gives to one call. */
    List<String> candidateLines() {
        try {
            return Files.readAllLines(scratch.resolve("candidates.jsonl"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The answer of one call as the loop's output holds it: the item between brackets, then the answer. */
    static String answer(String candidateLine, String answer) {
        return "[" + candidateLine + "]: " + answer + "\n";
    }

    /** An answer with one finding on the candidate's own file. */
    static String finding(String candidateLine, String quote, String mutation, String severity) {
        JsonNode candidate = json(candidateLine);
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("file", candidate.path("file").asText());
        finding.put("line_quote", quote);
        finding.put("mutation", mutation);
        finding.put("why_uncaught", "BigTest#works never gives two negative numbers.");
        finding.put("severity", severity);
        return answer(candidateLine, write(Map.of("class", candidate.path("class").asText(), "findings", List.of(finding))));
    }

    /** An answer without a finding. */
    static String fine(String candidateLine) {
        return answer(candidateLine, write(Map.of("class", json(candidateLine).path("class").asText(),
                "findings", List.of())));
    }

    static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
