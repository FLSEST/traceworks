package traceworks.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.app.QuestionAsker;
import traceworks.core.port.BlobRef;
import traceworks.core.record.QuestionView;
import traceworks.core.util.TimedRegex;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Asks the questions of a run on the terminal. One reader thread owns the input for the whole session and queues the
 * lines it reads, so a question that is interrupted (answered elsewhere, timed out) never swallows the line meant for
 * the next one. Questions are asked one at a time. An answer is checked against the options before it is returned; a
 * typo is asked again.
 */
public final class ConsoleAsker implements QuestionAsker {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** How long checking one typed value against a workflow's pattern may take. */
    private static final Duration PATTERN_TIME_LIMIT = Duration.ofSeconds(2);
    /** The end of the input. */
    private static final String EOF = new String("<eof>");

    private final PrintStream out;
    private final Function<String, Optional<String>> blobText;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final ReentrantLock turn = new ReentrantLock();

    /**
     * @param blobText the text of a blob by its SHA-256, for the question and the default value
     */
    public ConsoleAsker(InputStream in, PrintStream out, Function<String, Optional<String>> blobText) {
        this.out = out;
        this.blobText = blobText;
        Thread reader = new Thread(() -> read(in), "traceworks-console");
        reader.setDaemon(true);
        reader.start();
    }

    private void read(InputStream in) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            // The input is gone: treated like its end
        }
        lines.add(EOF);
    }

    @Override
    public Optional<String> ask(QuestionView question) {
        try {
            turn.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        try {
            String text = TerminalFormat.printable(text(question.question(), "(deleted)"));
            return "human-input".equals(question.kind()) ? human(question, text) : approval(question, text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            turn.unlock();
        }
    }

    private Optional<String> approval(QuestionView question, String text) throws InterruptedException {
        List<String> options = question.options();
        out.println();
        out.println("? " + text);
        if (!options.isEmpty()) {
            out.println("  answer with one of: " + joined(options));
        }
        while (true) {
            String line = prompt();
            if (line == null) {
                return Optional.empty();
            }
            line = line.strip();
            if (options.isEmpty()) {
                if (!line.isEmpty()) {
                    return Optional.of(line);
                }
                continue;
            }
            for (String option : options) {
                if (option.equalsIgnoreCase(line)) {
                    return Optional.of(option);
                }
            }
            out.println("  '" + TerminalFormat.oneLine(line) + "' is not one of: " + joined(options));
        }
    }

    private Optional<String> human(QuestionView question, String text) throws InterruptedException {
        out.println();
        out.println("? " + text);
        if (question.fields() == null || question.fields().isEmpty()) {
            String value = value(text(question.defaultValue(), ""), question.required(), pattern(question.pattern()),
                    question.options());
            return value == null ? Optional.empty() : Optional.of(value);
        }
        Map<String, Object> form = new LinkedHashMap<>();
        for (Map<String, Object> field : question.fields()) {
            Object name = field.get("variable_name");
            Object label = field.getOrDefault("label", name);
            out.println("  " + TerminalFormat.safe(String.valueOf(label)));
            String value = value(string(field.get("default_value")), !Boolean.FALSE.equals(field.get("required")),
                    pattern(field.get("validation_pattern")), strings(field.get("options")));
            if (value == null) {
                return Optional.empty();
            }
            form.put(String.valueOf(name), value);
        }
        try {
            return Optional.of(JSON.writeValueAsString(form));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One value: asks again until it is acceptable; {@code null} when the input ends. */
    private String value(String defaultValue, boolean required, String pattern, List<String> options)
            throws InterruptedException {
        if (!options.isEmpty()) {
            out.println("  one of: " + joined(options));
        }
        // What comes back from here is the person's own answer, and the step stores it without the protection the
        // default had (a default may be made of a step's or a model's output). An empty line therefore takes the
        // default only when the hint shows all of it, not one cut at the limit or changed to fit a line
        String hint = TerminalFormat.oneLine(defaultValue);
        boolean shownWhole = hint.equals(defaultValue);
        while (true) {
            String line = prompt(defaultValue.isEmpty() ? "" : " [" + hint + "]");
            if (line == null) {
                return null;
            }
            if (line.isEmpty() && !shownWhole) {
                out.println("  the default is not shown whole here (" + defaultValue.length() + " characters, or with"
                        + " line breaks or characters a terminal does not show), so an empty line does not take it:"
                        + " type the value");
                continue;
            }
            String value = line.isEmpty() ? defaultValue : line;
            if (value.isEmpty() && required) {
                out.println("  a value is required");
            } else if (!options.isEmpty() && !value.isEmpty() && !options.contains(value)) {
                out.println("  not one of: " + joined(options));
            } else if (pattern != null && !value.isEmpty()) {
                Boolean matches = matches(pattern, value);
                if (matches == null) {
                    // The pattern is the workflow's own; asking again would never end, and the step checks the
                    // answer once more
                    out.println("  could not be checked against the pattern in time, so it is taken as it is: "
                            + TerminalFormat.safe(pattern));
                    return value;
                } else if (!matches) {
                    out.println("  does not match " + TerminalFormat.safe(pattern));
                } else {
                    return value;
                }
            } else {
                return value;
            }
        }
    }

    private static String joined(List<String> options) {
        return options.stream().map(TerminalFormat::safe).collect(java.util.stream.Collectors.joining(" / "));
    }

    /**
     * Whether the value matches the workflow's pattern; {@code null} when that cannot be told within
     * {@link #PATTERN_TIME_LIMIT}. The pattern is the workflow's, and so is a default value: neither may hang the
     * console, which holds the turn of every other question while it asks.
     */
    private static Boolean matches(String pattern, String value) {
        try {
            return TimedRegex.matches(Pattern.compile(pattern), value, PATTERN_TIME_LIMIT);
        } catch (PatternSyntaxException e) {
            return true;   // the step reports a broken pattern
        } catch (TimedRegex.TimedOut e) {
            return null;
        }
    }

    private String prompt() throws InterruptedException {
        return prompt("");
    }

    /** Shows the prompt and waits for a line; {@code null} at the end of the input. */
    private String prompt(String hint) throws InterruptedException {
        out.print(hint + "> ");
        out.flush();
        String line = lines.take();
        if (line == EOF) {
            lines.add(EOF);   // the next question finds the end as well
            out.println();
            out.println("  the input ended: answer with the commands in the summary");
            return null;
        }
        return line;
    }

    private String text(BlobRef ref, String missing) {
        return ref == null ? "" : blobText.apply(ref.sha256()).orElse(missing);
    }

    private static String pattern(Object value) {
        return value == null || value.toString().isEmpty() ? null : value.toString();
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private static List<String> strings(Object value) {
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }
}
