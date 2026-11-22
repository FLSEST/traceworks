package traceworks.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.app.QuestionAsker;
import traceworks.core.context.InputRules;
import traceworks.core.port.BlobRef;
import traceworks.core.record.QuestionView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Asks the questions of a run on the terminal. One reader thread owns the input for the whole session and queues the
 * lines it reads, so a question that is interrupted (answered elsewhere, timed out) never swallows the line meant for
 * the next one. Questions are asked one at a time. An answer is checked before it is returned, an approval's against
 * its options and a human input's against the rules the run checks every answer with ({@link InputRules}); a typo is
 * asked again.
 */
public final class ConsoleAsker implements QuestionAsker {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** The end of the input. */
    private static final String EOF = new String("<eof>");

    private final PrintStream out;
    private final Function<String, Optional<String>> blobText;
    private final String agent;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final ReentrantLock turn = new ReentrantLock();

    /**
     * @param blobText the text of a blob by its SHA-256, for the question and the default value
     */
    public ConsoleAsker(InputStream in, PrintStream out, Function<String, Optional<String>> blobText) {
        this(in, out, blobText, null);
    }

    /**
     * @param agent the agent that types the answers here under the actor of the run's process, by its checked name;
     *              {@code null} when a person does, or nobody said otherwise
     */
    public ConsoleAsker(InputStream in, PrintStream out, Function<String, Optional<String>> blobText, String agent) {
        this.out = out;
        this.blobText = blobText;
        this.agent = agent;
        Thread reader = new Thread(() -> read(in), "traceworks-console");
        reader.setDaemon(true);
        reader.start();
    }

    @Override
    public String agent() {
        return agent;
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
            String text = text(question.question(), "(deleted)");
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
        question(text);
        if (!options.isEmpty()) {
            options("answer with one of:", options);
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
            options("'" + TerminalFormat.oneLine(line) + "' is not one of:", options);
        }
    }

    /**
     * The question behind {@code ?}. It is the workflow's text: on that line only where it is one line that fits
     * the terminal and holds no run of blanks, else every line of it on marked lines below, so that no part of it
     * starts a terminal line that reads as one of Traceworks.
     */
    private void question(String text) {
        out.println();
        String shown = text.strip();
        List<String> inline = TerminalText.lines("? ", TerminalText.safe(shown));
        if (shown.lines().count() <= 1 && inline.size() == 1) {
            out.println(inline.getFirst());
            return;
        }
        out.println("?");
        TerminalText.block(LEAD, shown).forEach(out::println);
    }

    private Optional<String> human(QuestionView question, String text) throws InterruptedException {
        question(text);
        if (question.fields() == null || question.fields().isEmpty()) {
            String value = value(text(question.defaultValue(), ""), new InputRules.Rules(question.inputType(),
                    question.options(), question.required(), question.pattern()));
            return value == null ? Optional.empty() : Optional.of(value);
        }
        Map<String, Object> form = new LinkedHashMap<>();
        for (Map<String, Object> field : question.fields()) {
            Object name = field.get("variable_name");
            Object label = field.getOrDefault("label", name);
            // A label is the workflow's text, as an option is
            TerminalText.shown(LEAD, "", "", "", "", List.of(String.valueOf(label)))
                    .forEach(shown -> out.println(LEAD + shown));
            String value = value(string(field.get("default_value")), InputRules.Rules.ofField(field));
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

    /**
     * One value: asks again until the rules of its input take it, as the run checks it ({@link InputRules}); the
     * value in the input's format, or {@code null} when the input ends. A select shows its options.
     */
    private String value(String defaultValue, InputRules.Rules rules) throws InterruptedException {
        boolean select = "select".equals(rules.inputType()) && !rules.options().isEmpty();
        if (select) {
            options("one of:", rules.options());
        }
        // What comes back from here is the person's own answer, and the step stores it without the protection the
        // default had (a default may be made of a step's or a model's output). An empty line therefore takes the
        // default only when the hint shows all of it, not one cut at the limit, changed to fit a line or broken
        // over several (a break drops the blanks it is made at). The hint is the workflow's text, or a step's
        List<String> hint = defaultValue.isEmpty() ? List.of("")
                : TerminalText.lines(" [", TerminalText.oneLine(defaultValue), "]");
        boolean shownWhole = TerminalFormat.oneLine(defaultValue).equals(defaultValue) && hint.size() == 1;
        while (true) {
            String line = prompt(hint);
            if (line == null) {
                return null;
            }
            if (line.isEmpty() && !shownWhole) {
                out.println("  the default is not shown whole here (" + defaultValue.length() + " characters, or with"
                        + " line breaks, a run of blanks or characters a terminal does not show), so an empty line"
                        + " does not take it: type the value");
                continue;
            }
            InputRules.Checked checked = InputRules.check(line.isEmpty() ? defaultValue : line, rules);
            if (checked.ok()) {
                return checked.value();
            }
            if (InputRules.OPTIONS.equals(checked.rule())) {
                options("not one of:", rules.options());
            } else {
                // The value, the options and the pattern it names are text from outside
                TerminalText.println(out, "  ", TerminalText.safe(checked.problem()));
            }
        }
    }

    private static final String LEAD = "  ";

    /**
     * The options behind {@code caption}. They are the workflow's text: on the caption's line only where that line
     * fits the terminal and no option holds a run of blanks, else each on marked lines below it, so that the end
     * of a padded option never starts a terminal line that reads as one of Traceworks.
     */
    private void options(String caption, List<String> options) {
        TerminalText.shown(LEAD, caption, " ", "", "", options).forEach(line -> out.println(LEAD + line));
    }

    private String prompt() throws InterruptedException {
        return prompt(List.of(""));
    }

    /**
     * Shows the prompt and waits for a line; {@code null} at the end of the input.
     *
     * @param hint the lines of the hint before the prompt, as {@link TerminalText#lines} gives them
     */
    private String prompt(List<String> hint) throws InterruptedException {
        hint.subList(0, hint.size() - 1).forEach(out::println);
        out.print(hint.getLast() + "> ");
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

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }
}
