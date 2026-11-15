package traceworks.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every text the blocks, the engine, the context, the record and the AI calls cut is cut where no secret
 * value is split, since the record finds a value only where it is whole (the {@code ai.tool_call} result
 * among them). The cut helpers have overloads that take the run's secret values
 * ({@code ExecutionContext#secretValues}); this test fails for a call of one that does not, and for the helpers that
 * have no such overload at all. The main sources are read from this class's compiled location, as in
 * {@link PackageDependencyTest}.
 */
class SecretAwareCutsTest {

    private static final String BASE = "traceworks/core/";

    /** Each cut helper, by {@code Class.method}, with the number of arguments of its secret-aware overload. */
    private static final Map<String, Integer> AWARE_ARITY = Map.of(
            "TruncationUtil.head", 3,
            "TruncationUtil.smart", 4,
            "TruncationUtil.cutBefore", 3,
            "TruncationUtil.cutAfter", 3,
            "UntrustedText.inline", 3,
            "UntrustedText.block", 3,
            "TextTruncateStage.truncate", 4,
            "TextTruncateStage.cut", 4,
            "CompressionOutput.assemble", 5,
            "RegexGuard.startingNow", 2);

    /** Cut helpers without a secret-aware overload: not to be called here at all. */
    private static final List<String> BANNED = List.of("TruncationUtil.window", "TruncationUtil.tail",
            "TruncationUtil.tailInline");

    private static final Pattern CALL = Pattern.compile(
            "\\b(TruncationUtil|UntrustedText|TextTruncateStage|CompressionOutput|RegexGuard)\\s*\\.\\s*(\\w+)\\s*\\(");
    private static final Pattern STATIC_IMPORT = Pattern.compile(
            "import\\s+static\\s+[\\w.]*\\b(TruncationUtil|UntrustedText|TextTruncateStage)\\b");

    @Test
    void everyCutInTheBlocksTheEngineTheContextTheRecordAndTheAiCalls_keepsSecretValuesWhole() {
        List<String> unaware = new ArrayList<>();
        int calls = 0;
        for (String pkg : List.of("blocks", "engine", "context", "record", "ai")) {
            for (Path file : javaFiles(moduleDirectory().resolve("src/main/java").resolve(BASE + pkg))) {
                String source = read(file);
                if (STATIC_IMPORT.matcher(source).find()) {
                    unaware.add(file.getFileName() + ": a static import of a cut helper");
                }
                Matcher call = CALL.matcher(source);
                while (call.find()) {
                    String name = call.group(1) + "." + call.group(2);
                    int arguments = arguments(source, call.end());
                    String where = file.getFileName() + ":" + line(source, call.start()) + " " + name + " with "
                            + arguments + " arguments";
                    if (BANNED.contains(name)) {
                        unaware.add(where + " (no secret-aware overload)");
                    } else if (AWARE_ARITY.containsKey(name)) {
                        calls++;
                        if (arguments != AWARE_ARITY.get(name)) {
                            unaware.add(where);
                        }
                    }
                }
            }
        }

        assertThat(calls).as("cut calls found; the scan must see them").isGreaterThanOrEqualTo(20);
        assertThat(unaware).as("cuts that do not take the run's secret values").isEmpty();
    }

    /** The number of top-level arguments of a call whose argument list starts at {@code open} (after the paren). */
    static int arguments(String source, int open) {
        int depth = 0;
        int commas = 0;
        boolean any = false;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                i = endOfLiteral(source, i);
                any = true;
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) {
                    return any ? commas + 1 : 0;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                commas++;
            }
            if (!Character.isWhitespace(c)) {
                any = true;
            }
        }
        throw new IllegalStateException("a call without its closing parenthesis");
    }

    /** The index of the last character of the string, text block or char literal that starts at {@code at}. */
    private static int endOfLiteral(String source, int at) {
        char quote = source.charAt(at);
        if (quote == '"' && source.startsWith("\"\"\"", at)) {
            int end = source.indexOf("\"\"\"", at + 3);
            return end + 2;
        }
        for (int i = at + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote) {
                return i;
            }
        }
        throw new IllegalStateException("an unterminated literal");
    }

    @Test
    void theArgumentCount_readsNestedCallsAndLiterals() {
        String source = "f(a, g(b, c), \"x, (y\", 'c', new int[] {1, 2})";

        assertThat(arguments(source, 2)).isEqualTo(5);
        assertThat(arguments("f()", 2)).isZero();
    }

    private static int line(String source, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (source.charAt(i) == '\n') line++;
        }
        return line;
    }

    private static Path moduleDirectory() {
        try {
            Path testClasses = Path.of(SecretAwareCutsTest.class.getProtectionDomain().getCodeSource().getLocation()
                    .toURI());
            return testClasses.getParent().getParent();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("cannot locate the test classes", e);
        }
    }

    private static List<Path> javaFiles(Path directory) {
        assertThat(directory).isDirectory();
        try (Stream<Path> files = Files.walk(directory)) {
            List<Path> found = files.filter(p -> p.toString().endsWith(".java")).toList();
            assertThat(found).as("source files in %s", directory).isNotEmpty();
            return found;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
