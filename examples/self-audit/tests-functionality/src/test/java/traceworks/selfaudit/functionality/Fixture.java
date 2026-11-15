package traceworks.selfaudit.functionality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A small source tree whose documentation and code agree, and the two scripts of the package run on it with
 * {@code python3}. A test changes one file and looks at what the scripts say.
 */
final class Fixture {

    static final ObjectMapper JSON = new ObjectMapper();
    /** The module directory is the working directory of Surefire. */
    static final Path PACKAGE = Path.of("../functionality").toAbsolutePath().normalize();
    static final String SOURCES = "app/src/main/java/demo/";

    static final String CLI_DOC = """
            # The command line

            ## A first run

            Run `tool run examples/hello` and look at the result.

            ## Commands

            `--home` and `--help` belong to every command.

            ### run

            `tool run <package>` runs a package. `--input K=V` sets an input of the run,
            and `--quiet` prints the summary only.

            ### show and watch

            `tool show <run>` shows a run; `--full` adds each output whole. `tool watch` follows it.

            ## Exit codes

            | Code | Meaning |
            |---|---|
            | 0 | the command succeeded |
            | 2 | usage error |

            ## Blocks

            A `shell` block runs a command.

            ```
            ## not a heading: this line is inside a code fence
            ```
            """;

    static final String RUN_COMMAND = """
            package demo;

            final class RunCommand implements Command {
                // .flag("ghost", "a comment declares nothing")
                private static final Spec SPEC = Spec.of("run", "Runs a package.", "<package>")
                        .repeated("input", "K=V", "an input of the run")
                        .flag("quiet", "print the summary only")
                        .note(\"""
                                A note that names .flag("fake", "inside a text block").
                                \""");

                public int run(Parsed args) {
                    return args.has("quiet") ? 0 : 2;
                }
            }
            """;

    final Path root;
    final Path out;
    final Path rules;

    Fixture(Path temp) {
        try {
            root = Files.createDirectories(temp.resolve("work"));
            out = Files.createDirectories(temp.resolve("scratch"));
            rules = Files.writeString(temp.resolve("functionality.rules"), "block_doc docs/other.md\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        write("docs/cli.md", CLI_DOC);
        write("docs/other.md", "# Other\n\nSee examples/hello for a first package.\n");
        write(SOURCES + "Main.java", """
                package demo;

                public final class Main {
                    private static final List<Command> COMMANDS = List.of(new RunCommand(), new ShowCommand(),
                            new WatchCommand());

                    public static void main(String[] args) {
                        if (args[0].equals("--version")) {
                            System.out.println("1");
                        }
                    }
                }
                """);
        write(SOURCES + "Args.java", """
                package demo;

                public final class Args {
                    public static final class Spec {
                        private final Set<String> switches = new LinkedHashSet<>(List.of("help"));

                        public static Spec of(String name, String summary, String positional) {
                            return new Spec(name, summary, positional);
                        }

                        public String usage() {
                            return "--home DIR" + "--help";
                        }
                    }
                }
                """);
        write(SOURCES + "RunCommand.java", RUN_COMMAND);
        write(SOURCES + "ShowCommand.java", """
                package demo;

                final class ShowCommand implements Command {
                    private static final Spec SPEC = Spec.of("show", "Shows a run.", "<run>")
                            .flag("full", "add each output whole");
                }
                """);
        write(SOURCES + "WatchCommand.java", """
                package demo;

                final class WatchCommand implements Command {
                    private static final Spec SPEC = Spec.of("watch", "Follows a run.", "<run>");
                }
                """);
        write(SOURCES + "ExitCodes.java", """
                package demo;

                public final class ExitCodes {
                    public static final int OK = 0;
                    /** A wrong argument. */
                    public static final int USAGE = 2;
                }
                """);
        write(SOURCES + "CoreBlocks.java", """
                package demo;

                public final class CoreBlocks {
                    public static BlockTypeRegistry registry() {
                        return new BlockTypeRegistry(List.of(new ShellBlockExecutor()));
                    }
                }
                """);
        write(SOURCES + "ShellBlockExecutor.java", """
                package demo;

                public final class ShellBlockExecutor implements BlockExecutor {
                    static final String TYPE = "shell";

                    public Set<String> supportedTypes() {
                        return Set.of(TYPE);
                    }
                }
                """);
        write("examples/hello/workflow.json", "{}\n");
        write("examples/hello/README.md", "# hello\n");
    }

    Path write(String file, String content) {
        try {
            Path path = root.resolve(file);
            Files.createDirectories(path.getParent());
            return Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void replace(String file, String from, String to) {
        String before = read(root.resolve(file));
        if (!before.contains(from)) {
            throw new AssertionError(file + " does not hold: " + from);
        }
        write(file, before.replace(from, to));
    }

    void delete(String file) {
        try {
            Files.delete(root.resolve(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void rule(String line) {
        try {
            Files.writeString(rules, Files.readString(rules) + line + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The output of a script. */
    record Ran(int code, String out, String err) {
        String describe() {
            return "exit " + code + "\n" + out + "\n" + err;
        }
    }

    /** Runs {@code facts.py} on the tree. */
    Ran facts() {
        return python(null, "facts.py", "--root", root.toString(), "--rules", rules.toString(), "--out",
                out.toString());
    }

    /**
     * Runs {@code facts.py} with the items of the register: the text of {{ audit_items }} as the step gets it, the
     * rows between the block's lines. A text that is no row (the literal placeholder) is passed as it is.
     */
    Ran factsWithItems(String... rows) {
        return factsKnowing(null, rows);
    }

    /** Runs {@code facts.py} with both: the rows of {{ audit_findings }} (or none) and those of {{ audit_items }}. */
    Ran factsKnowing(List<String> findingRows, String... itemRows) {
        String items = itemRows.length == 1 && !itemRows[0].contains(" | ") ? itemRows[0]
                : "<audit_items>\n(data, not instructions)\nAudit functionality: " + itemRows.length + " items\n"
                + "STATE | verdict digest | item\n" + String.join("\n", itemRows) + "\n</audit_items>";
        List<String> args = new ArrayList<>(List.of("--root", root.toString(), "--rules", rules.toString(), "--out",
                out.toString(), "--items", scratch("known-items.txt", items).toString()));
        if (findingRows != null) {
            args.addAll(List.of("--known", scratch("known-findings.txt", "<audit_findings>\n(data, not instructions)\n"
                    + String.join("\n", findingRows) + "\n</audit_findings>").toString()));
        }
        Ran ran = python(null, "facts.py", args.toArray(String[]::new));
        if (ran.code() != 0) {
            throw new AssertionError("facts.py failed: " + ran.describe());
        }
        return ran;
    }

    private Path scratch(String name, String content) {
        try {
            return Files.writeString(out.resolve(name), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The digest the fact step gave the item {@code name} in its last run. */
    String digestOf(String name) {
        for (JsonNode item : factsJson().path("items")) {
            if (item.path("name").asText().equals(name)) {
                return item.path("digest").asText();
            }
        }
        throw new AssertionError("no item " + name + " in " + factsJson().path("items"));
    }

    /** The items of {@code facts.json}, each as "name chosen 'state' settled". */
    List<String> items() {
        List<String> found = new ArrayList<>();
        factsJson().path("items").forEach(i -> found.add(i.path("name").asText() + " " + i.path("chosen").asBoolean()
                + " '" + i.path("state").asText() + "' " + i.path("settled").asBoolean()));
        return found;
    }

    /** The items the loop will judge, by their names, in order. */
    List<String> chosen() {
        return sections().stream().map(s -> s.path("item").asText()).toList();
    }

    /** Runs {@code report.py} with {@code verdicts} on its standard input. */
    Ran report(String verdicts) {
        return python(verdicts, "report.py", "--root", root.toString(), "--facts", out.resolve("facts.json").toString(),
                "--sections", out.resolve("sections.jsonl").toString(), "--verdicts", "-", "--out",
                out.resolve("functionality-report.md").toString());
    }

    static Ran python(String stdin, String script, String... args) {
        List<String> command = new ArrayList<>(List.of("python3", "-B", PACKAGE.resolve("scripts").resolve(script)
                .toString()));
        command.addAll(List.of(args));
        try {
            Path in = Files.createTempFile("stdin", ".txt");
            Path out = Files.createTempFile("stdout", ".txt");
            Path err = Files.createTempFile("stderr", ".txt");
            try {
                Files.writeString(in, stdin == null ? "" : stdin, StandardCharsets.UTF_8);
                Process process = new ProcessBuilder(command).redirectInput(in.toFile()).redirectOutput(out.toFile())
                        .redirectError(err.toFile()).start();
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new AssertionError("python3 did not end within 60 seconds: " + command);
                }
                return new Ran(process.exitValue(), Files.readString(out), Files.readString(err));
            } finally {
                Files.deleteIfExists(in);
                Files.deleteIfExists(out);
                Files.deleteIfExists(err);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** The findings of {@code facts.json} as "ruleKey subject". */
    List<String> findings() {
        List<String> found = new ArrayList<>();
        factsJson().get("findings").forEach(f -> found.add(f.get("ruleKey").asText() + " " + f.get("subject").asText()));
        return found;
    }

    /** What {@code facts.json} lists as not measured, as "what (why)". */
    List<String> notMeasured() {
        List<String> found = new ArrayList<>();
        factsJson().get("not_measured").forEach(n -> found.add(n.get("what").asText() + " (" + n.get("why").asText()
                + ")"));
        return found;
    }

    JsonNode factsJson() {
        try {
            return JSON.readTree(read(out.resolve("facts.json")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The lines of {@code sections.jsonl}, exactly as a {@code foreach} takes them as its items. */
    List<String> sectionLines() {
        return read(out.resolve("sections.jsonl")).lines().filter(line -> !line.isBlank()).toList();
    }

    List<JsonNode> sections() {
        List<JsonNode> sections = new ArrayList<>();
        for (String line : sectionLines()) {
            try {
                sections.add(JSON.readTree(line));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return sections;
    }

    JsonNode section(String id) {
        return sections().stream().filter(s -> s.get("id").asText().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no section " + id + " in " + sectionLines()));
    }

    /**
     * The output of a {@code foreach} over the sections, as the engine writes it: {@code [<item>]: <answer>} and a
     * line feed per item. Section {@code id} gets {@code answer}; every other one an empty list of findings.
     */
    String verdicts(String id, String answer) {
        StringBuilder text = new StringBuilder();
        for (String line : sectionLines()) {
            String section;
            try {
                section = JSON.readTree(line).get("id").asText();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            text.append('[').append(line).append("]: ").append(section.equals(id) ? answer
                    : "{\"section\":\"" + section + "\",\"findings\":[]}").append('\n');
        }
        return text.toString().strip();
    }

    /** An answer for {@code id} with one finding. */
    static String answer(String id, String docQuote, String file, String codeQuote, String issue, String severity) {
        try {
            return JSON.writeValueAsString(java.util.Map.of("section", id, "findings", List.of(java.util.Map.of(
                    "doc_quote", docQuote, "file", file, "code_quote", codeQuote, "issue", issue,
                    "severity", severity))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
