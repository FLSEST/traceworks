package traceworks.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No test borrows a thread from the common pool. A test that runs two things at once on it passes where the pool
 * is large and waits in vain where it holds one thread: a machine with two cores, on a JDK that adds no thread
 * for a piece that blocks (seen in CI with Java 25 and 27, not with 21). A test gives each piece a thread of its
 * own instead: {@code traceworks.core.testing.OwnThread.start}, or {@code Traceworks.runBeside} in the
 * integration tests.
 *
 * <p>This test keeps the rule from being forgotten: it reads the test sources of every module and fails on a
 * call that takes the common pool. It reads source text, so it knows the calls this code base ever used for it,
 * not every way there could be.
 */
class OwnThreadGuardTest {

    /** The repository: the module directory is the working directory of Surefire. */
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    /** A call that runs on the common pool unless it is handed an executor; none of them is needed in a test. */
    private static final Pattern COMMON_POOL = Pattern.compile(
            "\\b(?:supplyAsync|runAsync|commonPool|parallelStream)\\s*\\(|\\.parallel\\(\\)");

    @Test
    void aCallThatTakesTheCommonPool_isFound_andACommentThatNamesOneIsNot() {
        assertThat(uses("CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> read());")).hasSize(1);
        assertThat(uses("CompletableFuture.runAsync(this::hold);")).hasSize(1);
        assertThat(uses("list.parallelStream().forEach(this::check);")).hasSize(1);
        assertThat(uses("IntStream.range(0, 9).parallel().sum();")).hasSize(1);
        assertThat(uses("ForkJoinPool.commonPool().submit(task);")).hasSize(1);
        assertThat(uses("     * {@code CompletableFuture.supplyAsync(work)} takes one from the common pool")).isEmpty();
        assertThat(uses("        // not supplyAsync(work): the pool may hold one thread")).isEmpty();
        assertThat(uses("CompletableFuture<String> f = OwnThread.start(\"read\", () -> read());")).isEmpty();
    }

    @Test
    void noTestOfAnyModule_takesAThreadFromTheCommonPool() throws IOException {
        List<String> found = new ArrayList<>();
        List<Path> sources = testSources();
        for (Path source : sources) {
            if (source.getFileName().toString().equals("OwnThreadGuardTest.java")) {
                continue;   // this test, which names what it looks for
            }
            List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (!uses(lines.get(i)).isEmpty()) {
                    found.add(ROOT.relativize(source) + ":" + (i + 1) + ": " + lines.get(i).strip());
                }
            }
        }

        assertThat(sources).as("the test sources of every module were read").hasSizeGreaterThan(200);
        assertThat(found).as("calls that take a thread from the common pool: give the piece a thread of its own"
                + " with OwnThread.start (Traceworks.runBeside in the integration tests)").isEmpty();
    }

    /** The calls in {@code line} that take the common pool; a line of a comment has none. */
    private static List<String> uses(String line) {
        String code = line.strip();
        if (code.startsWith("*") || code.startsWith("/*") || code.startsWith("//")) {
            return List.of();
        }
        return COMMON_POOL.matcher(code).results().map(result -> result.group()).toList();
    }

    /** Every {@code .java} file below a {@code src/test/java} of the repository, build output aside. */
    private static List<Path> testSources() throws IOException {
        try (Stream<Path> files = Files.walk(ROOT)) {
            return files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> {
                        String path = ROOT.relativize(file).toString().replace('\\', '/');
                        return path.contains("src/test/java/") && !path.contains("/target/")
                                && !path.startsWith(".") && !path.contains("/.");
                    }).sorted().toList();
        }
    }
}
