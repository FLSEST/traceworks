package traceworks.cli;

import com.fasterxml.jackson.databind.JsonNode;
import traceworks.core.port.BlobRef;
import traceworks.core.port.CommandRunner;
import traceworks.core.record.RecordedEvent;
import traceworks.core.record.StepView;
import traceworks.core.register.DecisionAgent;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Turns the events of a run into lines for a terminal: one line per event, the time first, children indented.
 * Content (outputs, commands, questions) is shown on one line and cut at {@value #LIMIT} characters; control
 * characters in it, such as terminal escapes, are replaced. One instance formats the events of one run in their
 * order: a stream cut at the output limit is named with the limit its last {@code run.started} or {@code run.resumed}
 * records.
 */
public final class TerminalFormat {

    /** How many characters of a content blob one line shows. */
    public static final int LIMIT = 200;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

    private final Function<String, Optional<String>> blobText;
    /** The output limit of the run so far, as {@code run.started} and {@code run.resumed} record it. */
    private volatile int outputLimitMib = CommandRunner.OUTPUT_LIMIT_MIB;

    /**
     * @param blobText the text of a blob by its SHA-256; empty for a deleted blob
     */
    public TerminalFormat(Function<String, Optional<String>> blobText) {
        this.blobText = blobText;
    }

    /** The line of an event; empty for events a terminal does not need (checkpoints, status changes). */
    public Optional<String> line(RecordedEvent event) {
        Object text = describe(event);
        if (text == null) {
            return Optional.empty();
        }
        // Everything a line shows that is not Traceworks' own wording is a text from outside: a record can be
        // another's, or edited. Each such part goes through TerminalText, so none starts a terminal line unmarked
        List<String> lines = TerminalText.lines(TIME.format(event.at()) + " " + (event.parent() == null ? "" : "  "),
                text);
        if (event.type().equals("approval.requested")) {
            lines = options(lines, event.fields().path("options"));
        }
        return Optional.of(String.join("\n", lines));
    }

    /** The parts of the line of an event, for {@link TerminalText#lines}; {@code null} for an event without one. */
    private Object describe(RecordedEvent e) {
        JsonNode f = e.fields();
        if (e.type().equals("run.started") || e.type().equals("run.resumed")) {
            // A record of an earlier version names no limit: it had the default
            outputLimitMib = f.path("output_limit_mib").isInt() ? f.path("output_limit_mib").intValue()
                    : CommandRunner.OUTPUT_LIMIT_MIB;
        }
        String step = e.step() == null ? "" : "step " + e.step() + "  ";
        return switch (e.type()) {
            case "run.started" -> seq("run started: ", text(f.path("workflow").path("name")), " (run ",
                    TerminalText.word(shortId(e.run())), ") by ", text(f.path("actor")), where(f, false));
            case "step.started" -> seq(step, name(f), " [", word(f.path("block_type")),
                    f.path("mode").asText().equals("ai") ? ", ai" : "", "] started");
            case "step.waiting" -> step + (f.path("waiting").asBoolean() ? "waiting for an answer" : "going on");
            case "step.command" -> seq(step, "$ ", content(e.blob("command")));
            case "step.command.finished" -> seq(step, "exit ", word(f.path("exit_code")), " after "
                    + f.path("duration_ms").asLong() + " ms" + (f.path("timed_out").asBoolean() ? " (timed out)" : ""),
                    truncated(f.path("truncated")), outputCut(f.path("output_cut")));
            case "step.output" -> seq(step, "output: ", content(e.blob("output")));
            case "gate.reported" -> seq(step, "gate ", word(f.path("status")),
                    f.path("category").isNull() || f.path("category").isMissingNode() ? ""
                            : seq(" ", TerminalText.safe(f.path("category").asText())),
                    suffix(e.blob("description")));
            case "approval.requested" -> seq(step, "question: ", content(e.blob("question")));
            case "approval.answered" -> seq(step, "answered: ", content(e.blob("answer")), by(f), through(f));
            case "request.refused" -> seq("request refused: ", text(f.path("reason")));
            case "foreach.item.started" -> seq(step, "item " + (f.path("item_index").asInt() + 1) + "/"
                    + f.path("total").asInt() + " started: ", content(e.blob("item")));
            case "foreach.item.finished" -> seq(step, "item " + (f.path("item_index").asInt() + 1) + "/"
                    + f.path("total").asInt() + " ", word(f.path("status")),
                    f.path("resumed_from").isInt()
                            ? " (taken over from event " + f.path("resumed_from").asInt() + ", not run again)" : "");
            case "agentic.progress" -> seq(step, "agent ", word(f.path("phase")));
            case "step.finished" -> seq(step, name(f), " ", word(f.path("status")), " after "
                    + f.path("duration_ms").asLong() + " ms");
            case "run.parked" -> "pause".equals(f.path("kind").asText())
                    ? "run parked: paused" + (f.path("step").isInt()
                    ? " inside the loop of step " + f.path("step").asInt() : " before the next step")
                    : seq("run parked at step ", word(f.path("step")), ": waiting for an answer");
            case "run.resumed" -> seq("run resumed by ", text(f.path("actor")), where(f, true),
                    f.path("rerun_step").isNumber() ? ", running step " + f.path("rerun_step").asInt() + " again"
                            : "");
            case "run.cancel_requested" -> seq("cancel requested", by(f));
            case "run.pause_requested" -> seq("pause requested", by(f),
                    ": the run parks after the step or the loop item in flight");
            case "run.recovered" -> "record recovered: a torn last line was set aside";
            case "run.finished" -> seq("run finished: ", word(f.path("status")),
                    f.path("gate").isObject() ? seq(", gate ", word(f.path("gate").path("status")))
                            : "",
                    suffix(e.blob("error")));
            case "run.checkpoint", "run.status" -> null;
            default -> TerminalText.safe(e.type());
        };
    }

    private static Object seq(Object... parts) {
        return TerminalText.seq(parts);
    }

    /** A value of the record as a text from outside; {@code ?} for one that is missing. */
    private static TerminalText text(JsonNode value) {
        return TerminalText.safe(value.asText("?"));
    }

    /**
     * A value of a field Traceworks writes itself (a status, an exit code, the runtime's name and version): its own
     * where it has the shape of one, a text from outside where a record holds anything else
     * ({@link TerminalText#word}).
     */
    private static TerminalText word(JsonNode value) {
        return TerminalText.word(value.asText("?"));
    }

    /**
     * Where the run runs, as {@code run.started} and {@code run.resumed} record it: in containers with the runtime and
     * its version, or on the host, which a run starts on only where no container runtime works; empty for a record
     * that names neither.
     *
     * @param resumed whether it is a resume, which keeps a host run on the host where a runtime works now
     */
    private static Object where(JsonNode f, boolean resumed) {
        JsonNode runtime = f.path("container_runtime");
        boolean found = runtime.isObject();
        return switch (f.path("execution").asText("")) {
            case "container" -> seq(" in containers", found ? seq(" (", word(runtime.path("name")), " ",
                    word(runtime.path("version")), ")") : "");
            case "host" -> " on the host" + (!found ? " (no container runtime works)" : resumed ? ", where it started"
                    : "");
            default -> "";
        };
    }

    /** The block name of a step event; a block without one is shown as {@value StepView#UNNAMED}. */
    private static Object name(JsonNode f) {
        JsonNode name = f.path("name");
        return name.isTextual() ? TerminalText.safe(name.asText()) : StepView.UNNAMED;
    }

    private static Object by(JsonNode f) {
        JsonNode actor = f.path("actor");
        return actor.isNull() || actor.isMissingNode() ? "" : seq(" by ", TerminalText.safe(actor.asText()));
    }

    /**
     * The agent an answer names beside its actor, in the register's words; nothing for an answer without one. The
     * name is bounded where it is given; a record that was edited can hold any, so it is made safe here.
     */
    private static Object through(JsonNode f) {
        JsonNode agent = f.path("agent");
        return agent.isTextual() ? through(agent.asText()) : "";
    }

    /** {@link DecisionAgent#through} as the parts of a line: its words, and the name as a text from outside. */
    public static Object through(String agent) {
        return agent == null ? "" : seq(DecisionAgent.through(""), TerminalText.oneLine(agent));
    }

    /**
     * The streams a command's end names as cut at the output limit, such as {@code " (stdout beyond 16 MiB)"}, with the
     * run's limit.
     */
    private Object truncated(JsonNode streams) {
        List<String> names = new ArrayList<>();
        streams.forEach(stream -> names.add(stream.asText()));
        return names.isEmpty() ? "" : seq(" (", TerminalText.oneLine(String.join(", ", names)), " beyond "
                + CommandRunner.sizeText(outputLimitMib * 1024L * 1024L) + ")");
    }

    /**
     * The cut a command's end names, such as {@code " (output cut: stdout after 5000 ms)"}: the reading of the output
     * ended at its bound, because something the command left behind still held it.
     */
    private static Object outputCut(JsonNode cut) {
        List<String> names = new ArrayList<>();
        cut.path("streams").forEach(stream -> names.add(stream.asText()));
        return names.isEmpty() ? "" : seq(" (output cut: ", TerminalText.oneLine(String.join(", ", names)), " after "
                + cut.path("after_ms").asLong() + " ms)");
    }

    /**
     * The lines of a question with its options behind them. The options are the workflow's text: they stay on the
     * question's last line only where it fits the terminal and none holds a run of blanks, else each follows on
     * marked lines.
     *
     * @param head the lines of the question, the first as it is printed and each further one with its mark
     */
    private static List<String> options(List<String> head, JsonNode options) {
        List<String> all = new ArrayList<>();
        options.forEach(o -> all.add(o.asText()));
        if (all.isEmpty()) {
            return head;
        }
        List<String> lines = new ArrayList<>(head.subList(0, head.size() - 1));
        List<String> shown = TerminalText.shown("", head.getLast(), " [", "]", " [options:]", all);
        lines.add(shown.getFirst());
        shown.stream().skip(1).forEach(line -> lines.add("  " + line));
        return lines;
    }

    private Object suffix(BlobRef text) {
        return text == null ? "" : seq(": ", content(text));
    }

    private Object content(BlobRef ref) {
        if (ref == null) {
            return "";
        }
        return blobText.apply(ref.sha256()).<Object>map(TerminalText::oneLine).orElse("(deleted)");
    }

    /**
     * Text on one line: white space runs become one space, control characters become {@code ?}, cut at the limit.
     * A {@code String} for a place that prints nothing; a line takes {@link TerminalText#oneLine}.
     */
    static String oneLine(String text) {
        StringBuilder out = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            i += Character.charCount(c);
            if (Character.isWhitespace(c)) {
                space = out.length() > 0;
                continue;
            }
            if (space) {
                out.append(' ');
                space = false;
            }
            out.appendCodePoint(dangerous(c) ? '?' : c);
            if (out.length() >= LIMIT + 1) {
                return out.substring(0, LIMIT) + "...";
            }
        }
        return out.toString();
    }

    /**
     * A value from the record or a package, made safe to print as part of a line: every control character (C0, DEL
     * and C1, so terminal escapes and the OSC introducers), every invisible format character (bidirectional
     * overrides and isolates, zero-width and tag characters, also those outside the basic plane) and every line
     * separator becomes {@code ?}. Nothing is cut and nothing collapsed. Visible in this package only: a line
     * takes {@link TerminalText#safe}, which also keeps the text from starting a terminal line.
     */
    static String safe(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(c -> out.appendCodePoint(dangerous(c) ? '?' : c));
        return out.toString();
    }

    /** Whether a character can change what a terminal shows: controls, invisible format characters, separators. */
    static boolean dangerous(int c) {
        int type = Character.getType(c);
        return Character.isISOControl(c)
                || type == Character.FORMAT                // bidi marks, overrides, isolates, zero width, tags
                || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                || c >= 0xE0000 && c <= 0xE0FFF           // the tag block, assigned or not
                || c == '\u061c';
    }

    /** Text for several lines: control characters other than line feed and tab become {@code ?}. */
    static String printable(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(c -> out.appendCodePoint(dangerous(c) && c != '\n' && c != '\t' ? '?' : c));
        return out.toString();
    }

    /** The first characters of a run id. */
    public static String shortId(String runId) {
        return runId.length() <= 8 ? runId : runId.substring(0, 8);
    }
}
