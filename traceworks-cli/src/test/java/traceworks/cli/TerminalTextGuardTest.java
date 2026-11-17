package traceworks.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps {@link TerminalText} the one way a text from outside gets into a line: the functions that make such a text
 * a safe {@code String} cannot be called by a command, and where the package of the gate calls them is pinned.
 */
class TerminalTextGuardTest {

    private static final Path SOURCES = Path.of("src/main/java/traceworks/cli");
    private static final Pattern CALL = Pattern.compile("TerminalFormat(\\.|::)(safe|oneLine|printable)\\b");

    /**
     * The commands live in other packages ({@code traceworks.cli.command}, {@code traceworks.cli.main}): what is
     * not public there does not compile. A text they print has to become a {@link TerminalText}, which no
     * {@code println} takes as itself.
     */
    @Test
    void noCommandCanMakeATextFromOutsideAString() {
        for (Method method : TerminalFormat.class.getDeclaredMethods()) {
            if (method.getReturnType() == String.class && Modifier.isStatic(method.getModifiers())
                    && !method.getName().equals("shortId")) {
                assertThat(Modifier.isPublic(method.getModifiers()) || Modifier.isProtected(method.getModifiers()))
                        .as("TerminalFormat." + method.getName() + " gives a text as a String: it must stay"
                                + " visible in its package only, and a line takes TerminalText").isFalse();
            }
        }
    }

    /**
     * In the package of the gate the calls are few, and each prints text of the operator or hands its result to a
     * check of the gate: the id or folder of a run in a command to copy, the options and names that
     * {@code TerminalText.inline} and {@code listed} judge, the line the person typed, and the comparison of a
     * default with its hint. A new call is a text that may reach a line around the gate: route it through
     * {@link TerminalText}, or add it here with its reason.
     */
    @Test
    void theCallsInThePackageOfTheGate_areTheOnesKnown() throws IOException {
        Map<String, Integer> calls = new TreeMap<>();
        try (Stream<Path> files = Files.list(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (name.equals("TerminalText.java") || name.equals("TerminalFormat.java")) {
                    continue;
                }
                Matcher matcher = CALL.matcher(Files.readString(file));
                while (matcher.find()) {
                    calls.merge(name, 1, Integer::sum);
                }
            }
        }

        assertThat(calls).isEqualTo(Map.of("AnswerCommands.java", 4, "ConsoleAsker.java", 2));
    }

    /** {@link TerminalFormat} defines the three functions and calls none of them: its lines are built from parts. */
    @Test
    void theLinesOfEvents_areBuiltFromParts() throws IOException {
        Matcher matcher = Pattern.compile("(?<![.\\w])(safe|oneLine|printable)\\(")
                .matcher(Files.readString(SOURCES.resolve("TerminalFormat.java")));
        int found = 0;
        while (matcher.find()) {
            found++;
        }

        assertThat(found).as("the three definitions, and no call").isEqualTo(3);
    }

    /**
     * {@code TerminalText.word} writes a value of a record as Traceworks' own where it has the shape of one word.
     * It is for a field that Traceworks writes itself (a run id, a status, an exit code, a digest, the runtime's
     * name and version, a model's id, the name of a credential), never for a name a workflow or a person chooses.
     * The calls are pinned: a new one is a field somebody looked at, added here with the others.
     */
    @Test
    void theValuesWrittenAsTraceworksOwn_areTheOnesKnown() throws IOException {
        Map<String, Integer> calls = new TreeMap<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = Pattern.compile("TerminalText\\.word\\(").matcher(Files.readString(file));
                while (matcher.find()) {
                    calls.merge(file.getFileName().toString(), 1, Integer::sum);
                }
            }
        }
        assertThat(calls).isEqualTo(Map.of("TerminalFormat.java", 2, "CredentialsCommand.java", 1,
                "DiffCommand.java", 2, "RunsCommand.java", 4, "ShowCommand.java", 1, "VerifyCommand.java", 6));
    }

    /**
     * A message says its parts in a type of the core, and the gate reads them: a value is own only where the code
     * that composed the message wrote it as a {@code String}. No command turns the parts back into a line itself:
     * {@code parts()} is read in the package of the gate alone, where {@code TerminalText.of} gives each its kind.
     */
    @Test
    void thePartsOfAMessage_areReadByTheGateAlone() throws IOException {
        java.util.List<String> readers = new java.util.ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (Pattern.compile("\\.parts\\(\\)\\.(parts|text)\\(|Message\\.Part\\b").matcher(
                        Files.readString(file)).find()) {
                    readers.add(file.getFileName().toString());
                }
            }
        }
        assertThat(readers).containsExactly("TerminalText.java");
    }
}
