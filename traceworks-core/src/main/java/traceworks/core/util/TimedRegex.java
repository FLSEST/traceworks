package traceworks.core.util;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex matching with a time limit, for a pattern or a text that comes from a workflow, a model or a report. Java's
 * regex engine has no timeout and backtracks, so a pattern like {@code ((a+)\2?)+b} takes a time that doubles with
 * every letter of the text, and an innocent-looking pattern takes quadratic time on a text built for it. The matcher
 * here reads the text through a {@link CharSequence} that gives up once the time is spent.
 *
 * <p>A match that gives up throws {@link TimedOut}; a pattern nested too deeply for the text (the engine's
 * {@link StackOverflowError}) throws it too, and so does a pattern whose counted repetitions multiply too far
 * ({@link #repetitionProblem}), because the engine repeats those without reading a character. The caller says what that means: an answer that is refused, a report
 * that is not read.
 */
public final class TimedRegex {

    /** The most that the counted repetitions of one pattern may multiply to. */
    public static final long MAX_REPETITIONS = 1_000_000_000_000L;

    private TimedRegex() {
    }

    /**
     * The match did not end within its time, the pattern is nested too deeply for the text, or the pattern repeats
     * too often to be matched at all.
     */
    public static final class TimedOut extends RuntimeException {

        private static final long serialVersionUID = 1L;

        TimedOut(String message) {
            super(message);
        }
    }

    /**
     * What is wrong with the counted repetitions ({@code {n}}, {@code {n,}}, {@code {n,m}}) of {@code regex}, or
     * {@code null}. The clock is read where the engine reads a character, and a counted repetition of something
     * that reads none, like {@code (?:^{1000000}){1000000}}, repeats without ever reading one. So a pattern whose
     * counts multiply to more than {@link #MAX_REPETITIONS} is refused before the engine sees it.
     *
     * <p>The product of every count in the pattern is taken, nested or not: that refuses more than it must, and it
     * needs no reading of groups. An escaped brace, a brace in a character class or in quoted text
     * ({@code \Q...\E}) and the braces of {@code \p{...}} or {@code \x{...}} count nothing. Where the reading
     * here and the engine's could differ (a {@code ]} right after {@code [}), the class is taken to end early, so
     * a count is never missed.
     */
    public static String repetitionProblem(String regex) {
        long product = 1;
        int classDepth = 0;
        int i = 0;
        while (i < regex.length()) {
            char c = regex.charAt(i);
            if (c == '\\') {
                if (regex.startsWith("\\Q", i)) {
                    int end = regex.indexOf("\\E", i + 2);
                    if (end < 0) break;   // quoted to the end
                    i = end + 2;
                } else {
                    i = afterEscape(regex, i);
                }
                continue;
            }
            if (c == '[') {
                classDepth++;
            } else if (c == ']') {
                if (classDepth > 0) classDepth--;
            } else if (c == '{' && classDepth == 0) {
                long count = count(regex, i);
                if (count > 0) {
                    product = count > MAX_REPETITIONS / product ? MAX_REPETITIONS + 1 : product * count;
                    if (product > MAX_REPETITIONS) {
                        return "its counted repetitions ({n}) multiply to more than "
                                + String.format(java.util.Locale.ROOT, "%,d", MAX_REPETITIONS);
                    }
                }
            }
            i++;
        }
        return null;
    }

    /** The index after the escape that starts at {@code backslash}, with the braces of {@code \p{..}} and its kin. */
    private static int afterEscape(String regex, int backslash) {
        int next = backslash + 1;
        if (next >= regex.length()) return next;
        boolean braced = "pPxkNgo".indexOf(regex.charAt(next)) >= 0
                && next + 1 < regex.length() && regex.charAt(next + 1) == '{';
        if (!braced) return next + 1;
        int close = regex.indexOf('}', next + 2);
        // A name has no digits-only form the engine would take as a count, so skipping to the brace hides none
        return close < 0 ? next + 1 : close + 1;
    }

    /**
     * The larger count of the quantifier that starts at {@code open}, at least 1; 0 when no quantifier starts
     * there. A number too long for a {@code long} counts as the largest one. White space and a {@code #} comment
     * inside the braces are skipped, as the engine skips them in comments mode; without that mode the engine takes
     * no such count, so this reads a count too many there and never one too few.
     */
    private static long count(String regex, int open) {
        long largest = 0;
        long current = 0;
        int digits = 0;
        boolean comma = false;
        for (int i = open + 1; i < regex.length(); i++) {
            char c = regex.charAt(i);
            if (c >= '0' && c <= '9') {
                current = current > (Long.MAX_VALUE - 9) / 10 ? Long.MAX_VALUE : current * 10 + (c - '0');
                digits++;
            } else if (c == ',' && !comma && digits > 0) {
                comma = true;
                largest = current;
                current = 0;
            } else if (c == '}' && (digits > 0)) {
                return Math.max(1, Math.max(largest, current));
            } else if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0B) {
                // Skipped by the engine in comments mode ((?x)), also between two digits: {9 99} is {999} there
            } else if (c == '#') {
                int end = regex.indexOf('\n', i);
                if (end < 0) return 0;   // a comment to the end: no brace closes the count
                i = end;
            } else {
                return 0;
            }
        }
        return 0;
    }

    /**
     * A matcher of {@code pattern} on {@code text} that throws {@link TimedOut} once {@code budget} is spent, and
     * at once for a pattern with a {@link #repetitionProblem}.
     */
    public static Matcher matcher(Pattern pattern, String text, Duration budget) {
        String problem = repetitionProblem(pattern.pattern());
        if (problem != null) throw new TimedOut("the pattern is not matched: " + problem);
        return pattern.matcher(new Timed(text, System.nanoTime() + budget.toNanos(), new int[1]));
    }

    /** Whether {@code pattern} is found in {@code text}; {@link TimedOut} when that cannot be told in time. */
    public static boolean find(Pattern pattern, String text, Duration budget) {
        return onAWorker(pattern, budget, () -> matcher(pattern, text, budget).find());
    }

    /** Whether {@code pattern} matches all of {@code text}; {@link TimedOut} when that cannot be told in time. */
    public static boolean matches(Pattern pattern, String text, Duration budget) {
        return onAWorker(pattern, budget, () -> matcher(pattern, text, budget).matches());
    }

    /**
     * Runs {@code work} and gives up when it has not ended {@value #GRACE_MILLIS} ms after {@code budget}: the hard
     * limit behind the clock that is read with every character. The engine can spend any time without reading one
     * (an empty alternative repeated, {@code (?:|)(?:|)...\z}, doubles its time with every repetition), and a
     * thread in the engine cannot be stopped. So the work runs on a daemon thread of its own, which is left behind
     * when it does not end: it keeps one processor busy until the process ends, and the caller goes on. For work
     * that matches a pattern of a workflow many times, like the stages of a compress block.
     *
     * @throws TimedOut if the work did not end in time, or the thread that waits was interrupted
     */
    public static <T> T within(Duration budget, java.util.concurrent.Callable<T> work) {
        java.util.concurrent.Future<T> result = WORKERS.submit(work);
        try {
            return result.get(budget.toMillis() + GRACE_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            result.cancel(true);
            throw new TimedOut("the match did not end within its time limit");
        } catch (InterruptedException e) {
            result.cancel(true);
            Thread.currentThread().interrupt();
            throw new TimedOut("the match was interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof StackOverflowError) throw tooDeep();
            if (e.getCause() instanceof RuntimeException failure) throw failure;
            if (e.getCause() instanceof Error error) throw error;
            throw new IllegalStateException(e.getCause());
        }
    }

    /**
     * One match of {@code pattern} {@linkplain #within within} its budget. A pattern that was given up once is not
     * tried again in this process: each try would leave another thread behind.
     */
    private static boolean onAWorker(Pattern pattern, Duration budget, java.util.concurrent.Callable<Boolean> match) {
        String key = pattern.flags() + "|" + pattern.pattern();
        if (GIVEN_UP.contains(key)) {
            throw new TimedOut("the pattern did not end within its time limit before and is not tried again");
        }
        try {
            return within(budget, match);
        } catch (TimedOut e) {
            if (e.getMessage().contains("did not end")) GIVEN_UP.add(key);
            throw e;
        }
    }

    /** How long after its budget a match is given up: the clock in the text gets the first chance to end it. */
    static final long GRACE_MILLIS = 500;

    private static final java.util.Set<String> GIVEN_UP = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final java.util.concurrent.ExecutorService WORKERS =
            java.util.concurrent.Executors.newCachedThreadPool(work -> {
                Thread thread = new Thread(work, "regex-match");
                thread.setDaemon(true);
                return thread;
            });

    /** What a caller of {@link #matcher} throws for the engine's {@link StackOverflowError}. */
    public static TimedOut tooDeep() {
        return new TimedOut("the pattern is nested too deeply for the text");
    }

    /**
     * Checks the clock every 1,024 {@code charAt} calls, counted over the whole match and not by index, so
     * backtracking on a short text is caught too. Sub-sequences share the counter and the deadline.
     */
    private static final class Timed implements CharSequence {

        private final String text;
        private final long deadline;
        private final int[] calls;

        Timed(String text, long deadline, int[] calls) {
            this.text = text;
            this.deadline = deadline;
            this.calls = calls;
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public char charAt(int index) {
            if ((++calls[0] & 0x3FF) == 0 && System.nanoTime() > deadline) {
                throw new TimedOut("the match did not end within its time limit");
            }
            return text.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Timed(text.substring(start, end), deadline, calls);
        }

        @Override
        public String toString() {
            return text;
        }
    }
}
