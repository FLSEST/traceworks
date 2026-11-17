package traceworks.core.blocks.compress;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class RegexGuardTest {

    /**
     * The line must lie between two bounds, otherwise the test checks nothing anymore.
     *
     * <p>Lower bound: the effort grows quadratically (measured on 2026-09-22 with
     * {@code ^(a+)+$} — 2 500 characters 69 ms, 5 000 characters 233 ms, 9 000 characters 797 ms).
     * With the earlier 5 000 characters the search was only 17 % over the deadline; on a
     * faster machine it finished earlier, no exception, test red (a CI run
     * measured 193 ms against a 200 ms deadline). 9 000 characters give roughly four times
     * the deadline and also tolerate a machine twice as fast.
     *
     * <p>Upper bound: from a line above {@link RegexGuard#MAX_MATCH_CHARS} the
     * guard cuts off the trailing {@code !} — then the pattern matches immediately, the search
     * is done in 0 ms and no deadline can ever apply.
     */
    @Test
    void catastrophicPattern_hitsDeadline() {
        RegexGuard guard = RegexGuard.startingNow(Duration.ofMillis(200));
        Pattern evil = RegexGuard.compile("^(a+)+$", 0, "Stage test");
        String line = "a".repeat(9000) + "!";
        assertThat(line.length()).isLessThanOrEqualTo(RegexGuard.MAX_MATCH_CHARS);

        assertThatThrownBy(() -> guard.find(evil, line))
                .isInstanceOf(CompressionException.class).hasMessageContaining("Time limit");
    }

    @Test
    void nestedQuantifier_onShortLine_hitsDeadline() {
        RegexGuard guard = RegexGuard.startingNow(Duration.ofMillis(300));
        Pattern evil = RegexGuard.compile("^((a+)+)+$", 0, "Stage test");
        String line = "a".repeat(40) + "!";

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThatThrownBy(() -> guard.find(evil, line))
                .isInstanceOf(CompressionException.class).hasMessageContaining("Time limit"));
    }

    @Test
    void nestedQuantifier_backtrackingNearLineEnd_hitsDeadline() {
        RegexGuard guard = RegexGuard.startingNow(Duration.ofMillis(300));
        Pattern evil = RegexGuard.compile("^((\\w+)+)+:", 0, "Stage test");
        String line = "w".repeat(90);

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThatThrownBy(() -> guard.find(evil, line))
                .isInstanceOf(CompressionException.class).hasMessageContaining("Time limit"));
    }

    @Test
    void matches_usesTheSameDeadline() {
        RegexGuard guard = RegexGuard.startingNow(Duration.ofMillis(300));
        Pattern evil = RegexGuard.compile("((a+)+)+", 0, "Stage test");

        assertThat(guard.matches(Pattern.compile("a+"), "aaa")).isTrue();
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThatThrownBy(() -> guard.matches(evil, "a".repeat(40) + "!"))
                .isInstanceOf(CompressionException.class).hasMessageContaining("Time limit"));
    }

    @Test
    void manyShortLines_stopAtTheDeadline_notOnlyBetweenStages() {
        Duration budget = Duration.ofMillis(200);
        RegexGuard guard = RegexGuard.startingNow(budget);
        Pattern harmless = RegexGuard.compile("^\\w+: \\d+$", 0, "Stage test");
        long start = System.nanoTime();

        assertThatThrownBy(() -> {
            for (int i = 0; i < 5_000_000; i++) guard.find(harmless, "warn: 12345");
        }).isInstanceOf(CompressionException.class).hasMessageContaining("Time limit");

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(budget.multipliedBy(2));
    }

    /**
     * Runs in a thread with an explicitly small stack: whether {@code ^(a|b)*$} overflows must not depend on
     * the default stack size of whoever runs the suite (-Xss, JDK, CI agent).
     */
    @Test
    void stackOverflow_becomesCompressionException() throws InterruptedException {
        RegexGuard guard = RegexGuard.startingNow(Duration.ofSeconds(30));
        Pattern deep = RegexGuard.compile("^(a|b)*$", 0, "Stage test");
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        Thread smallStack = new Thread(null, () -> {
            try {
                guard.find(deep, "ab".repeat(5000));
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "regex-guard-small-stack", 256 * 1024);
        smallStack.start();
        smallStack.join(30_000);

        assertThat(smallStack.isAlive()).as("matching has ended").isFalse();
        assertThat(thrown.get())
                .isInstanceOf(CompressionException.class)
                .hasMessage("Pattern nested too deeply for a line of 10,000 chars");
    }

    @Test
    void replaceAll_onlyTouchesFirstTenThousandChars() {
        RegexGuard guard = RegexGuard.startingNow(Duration.ofSeconds(5));
        String line = "a".repeat(12_000);

        String out = guard.replaceAll(Pattern.compile("a"), line, "b");

        assertThat(out).hasSize(12_000);
        assertThat(out).startsWith("b").endsWith("a");
    }

    /** validate() and apply() ask for the same pattern — it must be compiled once, not twice. */
    @Test
    void compile_reusesTheCompiledPattern() {
        Pattern first = RegexGuard.compile("^ab+c$", 0, "Stage test");

        assertThat(RegexGuard.compile("^ab+c$", 0, "Stage test")).isSameAs(first);
        assertThat(RegexGuard.compile("^ab+c$", Pattern.CASE_INSENSITIVE, "Stage test")).isNotSameAs(first);
        assertThatThrownBy(() -> RegexGuard.compile("(incomplete", 0, "Stage test"))
                .isInstanceOf(CompressionException.class).hasMessageContaining("invalid regular expression");
    }

    @Test
    void safeCut_movesBeforeSurrogatePair() {
        assertThat(RegexGuard.safeCut("x😀", 2)).isEqualTo(1);
        assertThat(RegexGuard.safeCut("xy", 1)).isEqualTo(1);
    }

    /** A cut at either end of the text takes nothing or everything: nothing to move. */
    @Test
    void safeCut_leavesACutAtEitherEndWhereItIs() {
        assertThat(RegexGuard.safeCut("😀x", 0)).isZero();
        assertThat(RegexGuard.safeCut("ab\uD83D", 3)).isEqualTo(3);
    }

    /** The guard hands the run's secret values to the stages that cut: a cut leaves a secret out whole. */
    @Test
    void aStageThatCuts_leavesTheRunsSecretsOutWhole() {
        String secret = "s3cr3t-value-that-must-stay-whole";
        String input = "a".repeat(150) + secret + "b".repeat(150);
        RegexGuard guard = RegexGuard.startingNow(Duration.ofSeconds(5), java.util.List.of(secret));

        StageResult cut = new TextTruncateStage().apply(input, java.util.Map.of("max_chars", 200, "keep", "head"),
                guard);

        assertThat(cut.output()).startsWith("a".repeat(150)).doesNotContain("s3cr3t");
    }

    @Test
    void matches_needsTheWholeLine_whereFindTakesAPart() {
        RegexGuard guard = RegexGuard.startingNow(Duration.ofSeconds(5));
        Pattern digits = RegexGuard.compile("[0-9]+", 0, "Stage test");

        assertThat(guard.matches(digits, "123")).isTrue();
        assertThat(guard.matches(digits, "a123")).isFalse();
        assertThat(guard.find(digits, "a123")).isTrue();
    }

    /** An empty line reads no character, so only the count of the calls themselves brings the deadline. */
    @Test
    void emptyLines_runIntoTheDeadlineToo() {
        RegexGuard spent = RegexGuard.startingNow(Duration.ofSeconds(-1));
        Pattern x = RegexGuard.compile("x", 0, "Stage test");

        assertThatThrownBy(() -> {
            for (int i = 0; i < 2048; i++) {
                spent.find(x, "");
            }
        }).isInstanceOf(CompressionException.class).hasMessageContaining("Time limit exceeded");
    }

    /** One long line reads far more than 1,024 characters, so the reads alone bring the deadline. */
    @Test
    void oneLongLine_runsIntoASpentDeadlineWhileItIsRead() {
        RegexGuard spent = RegexGuard.startingNow(Duration.ofSeconds(-1));
        Pattern neverMatches = RegexGuard.compile("a*b", 0, "Stage test");

        assertThatThrownBy(() -> spent.find(neverMatches, "a".repeat(5000)))
                .isInstanceOf(CompressionException.class).hasMessageContaining("Time limit exceeded");
    }

    /** The deadline is looked at every 1,024th read, not at every one: a short line is matched even when spent. */
    @Test
    void aShortLine_isMatchedBetweenTwoChecks_evenWhenTheDeadlineIsSpent() {
        RegexGuard spent = RegexGuard.startingNow(Duration.ofSeconds(-1));
        Pattern word = RegexGuard.compile("b+", 0, "Stage test");

        assertThat(spent.find(word, "aaabbb")).isTrue();
    }

    /** The first compile of a pattern hands it out as well as every later one. */
    @Test
    void compile_returnsANewPattern_onItsFirstCompileToo() {
        Pattern fresh = RegexGuard.compile("regex-guard-test-only-[a-z]+", 0, "Stage test");

        assertThat(fresh).isNotNull();
        assertThat(fresh.matcher("regex-guard-test-only-abc").matches()).isTrue();
    }

    /** Such a pattern reads no character, so the deadline would never be checked: it is refused like a syntax error. */
    @Test
    void compile_refusesCountedRepetitionsThatMultiply() {
        assertThatThrownBy(() -> RegexGuard.compile("(?:(?:){2147483647}){2147483647}", 0, "Stage test"))
                .isInstanceOf(CompressionException.class)
                .hasMessageContaining("Stage test").hasMessageContaining("repetition");

        RegexGuard guard = RegexGuard.startingNow(Duration.ofSeconds(5));
        assertThat(guard.matches(RegexGuard.compile("\\d{4}-\\d{2}-\\d{2}", 0, "Stage test"), "2026-11-12")).isTrue();
    }

    /**
     * The engine reads groups by recursion, and a pattern nested deeply enough overflows the stack while it is
     * compiled. The JDK (21) turns that into a syntax error itself, so the stage fails as for any invalid pattern.
     */
    @Test
    void aPatternNestedTooDeeplyToCompile_isAnInvalidPattern_notAnError() {
        String nested = "(".repeat(200_000) + "a" + ")".repeat(200_000);

        assertThatThrownBy(() -> RegexGuard.compile(nested, 0, "Stage test"))
                .isInstanceOf(CompressionException.class).hasMessageContaining("invalid regular expression")
                .hasMessageContaining("Stack overflow");
    }
}
