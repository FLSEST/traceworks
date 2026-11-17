package traceworks.cli;

import com.fasterxml.jackson.databind.JsonNode;
import traceworks.core.port.BlobRef;
import traceworks.core.port.CommandRunner;
import traceworks.core.record.RecordedEvent;
import traceworks.core.record.StepView;

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
        Optional<String> text = describe(event);
        return text.map(t -> TIME.format(event.at()) + " " + (event.parent() == null ? "" : "  ") + safe(t));
    }

    private Optional<String> describe(RecordedEvent e) {
        JsonNode f = e.fields();
        if (e.type().equals("run.started") || e.type().equals("run.resumed")) {
            // A record of an earlier version names no limit: it had the default
            outputLimitMib = f.path("output_limit_mib").isInt() ? f.path("output_limit_mib").intValue()
                    : CommandRunner.OUTPUT_LIMIT_MIB;
        }
        String step = e.step() == null ? "" : "step " + e.step() + "  ";
        return Optional.ofNullable(switch (e.type()) {
            case "run.started" -> "run started: " + f.path("workflow").path("name").asText("?") + " (run "
                    + shortId(e.run()) + ") by " + f.path("actor").asText("?") + where(f, false);
            case "step.started" -> step + name(f) + " [" + badge(f) + "] started";
            case "step.waiting" -> step + (f.path("waiting").asBoolean() ? "waiting for an answer" : "going on");
            case "step.command" -> step + "$ " + content(e.blob("command"));
            case "step.command.finished" -> step + "exit " + f.path("exit_code").asText("?") + " after "
                    + f.path("duration_ms").asLong() + " ms" + (f.path("timed_out").asBoolean() ? " (timed out)" : "")
                    + truncated(f.path("truncated")) + outputCut(f.path("output_cut"));
            case "step.output" -> step + "output: " + content(e.blob("output"));
            case "gate.reported" -> step + "gate " + f.path("status").asText("?")
                    + (f.path("category").isNull() || f.path("category").isMissingNode() ? ""
                    : " " + f.path("category").asText())
                    + descriptionSuffix(e.blob("description"));
            case "approval.requested" -> step + "question: " + content(e.blob("question")) + options(f.path("options"));
            case "approval.answered" -> step + "answered: " + content(e.blob("answer")) + by(f);
            case "request.refused" -> "request refused: " + f.path("reason").asText("?");
            case "foreach.item.started" -> step + "item " + (f.path("item_index").asInt() + 1) + "/"
                    + f.path("total").asInt() + " started: " + content(e.blob("item"));
            case "foreach.item.finished" -> step + "item " + (f.path("item_index").asInt() + 1) + "/"
                    + f.path("total").asInt() + " " + f.path("status").asText("?")
                    + (f.path("resumed_from").isInt()
                    ? " (taken over from event " + f.path("resumed_from").asInt() + ", not run again)" : "");
            case "agentic.progress" -> step + "agent " + f.path("phase").asText("?");
            case "step.finished" -> step + name(f) + " " + f.path("status").asText("?") + " after "
                    + f.path("duration_ms").asLong() + " ms";
            case "run.parked" -> "pause".equals(f.path("kind").asText())
                    ? "run parked: paused" + (f.path("step").isInt()
                    ? " inside the loop of step " + f.path("step").asInt() : " before the next step")
                    : "run parked at step " + f.path("step").asText("?") + ": waiting for an answer";
            case "run.resumed" -> "run resumed by " + f.path("actor").asText("?") + where(f, true)
                    + (f.path("rerun_step").isNumber() ? ", running step " + f.path("rerun_step").asInt() + " again"
                    : "");
            case "run.cancel_requested" -> "cancel requested" + by(f);
            case "run.pause_requested" -> "pause requested" + by(f)
                    + ": the run parks after the step or the loop item in flight";
            case "run.recovered" -> "record recovered: a torn last line was set aside";
            case "run.finished" -> "run finished: " + f.path("status").asText("?")
                    + (f.path("gate").isObject() ? ", gate " + f.path("gate").path("status").asText() : "")
                    + errorSuffix(e.blob("error"));
            case "run.checkpoint", "run.status" -> null;
            default -> e.type();
        });
    }

    /**
     * Where the run runs, as {@code run.started} and {@code run.resumed} record it: in containers with the runtime and
     * its version, or on the host, which a run starts on only where no container runtime works; empty for a record
     * that names neither.
     *
     * @param resumed whether it is a resume, which keeps a host run on the host where a runtime works now
     */
    private static String where(JsonNode f, boolean resumed) {
        JsonNode runtime = f.path("container_runtime");
        boolean found = runtime.isObject();
        return switch (f.path("execution").asText("")) {
            case "container" -> " in containers" + (found ? " (" + runtime.path("name").asText("?") + " "
                    + runtime.path("version").asText("?") + ")" : "");
            case "host" -> " on the host" + (!found ? " (no container runtime works)" : resumed ? ", where it started"
                    : "");
            default -> "";
        };
    }

    /** The block name of a step event; a block without one is shown as {@value StepView#UNNAMED}. */
    private static String name(JsonNode f) {
        JsonNode name = f.path("name");
        return name.isTextual() ? name.asText() : StepView.UNNAMED;
    }

    private static String badge(JsonNode f) {
        return f.path("block_type").asText("?") + (f.path("mode").asText().equals("ai") ? ", ai" : "");
    }

    private static String by(JsonNode f) {
        JsonNode actor = f.path("actor");
        return actor.isNull() || actor.isMissingNode() ? "" : " by " + actor.asText();
    }

    /**
     * The streams a command's end names as cut at the output limit, such as {@code " (stdout beyond 16 MiB)"}, with the
     * run's limit.
     */
    private String truncated(JsonNode streams) {
        List<String> names = new ArrayList<>();
        streams.forEach(stream -> names.add(oneLine(stream.asText())));
        return names.isEmpty() ? "" : " (" + String.join(", ", names) + " beyond "
                + CommandRunner.sizeText(outputLimitMib * 1024L * 1024L) + ")";
    }

    /**
     * The cut a command's end names, such as {@code " (output cut: stdout after 5000 ms)"}: the reading of the output
     * ended at its bound, because something the command left behind still held it.
     */
    private static String outputCut(JsonNode cut) {
        List<String> names = new ArrayList<>();
        cut.path("streams").forEach(stream -> names.add(oneLine(stream.asText())));
        return names.isEmpty() ? "" : " (output cut: " + String.join(", ", names) + " after "
                + cut.path("after_ms").asLong() + " ms)";
    }

    private static String options(JsonNode options) {
        List<String> all = new ArrayList<>();
        options.forEach(o -> all.add(o.asText()));
        return all.isEmpty() ? "" : " [" + String.join(" / ", all) + "]";
    }

    private String descriptionSuffix(BlobRef description) {
        return description == null ? "" : ": " + content(description);
    }

    private String errorSuffix(BlobRef error) {
        return error == null ? "" : ": " + content(error);
    }

    private String content(BlobRef ref) {
        if (ref == null) {
            return "";
        }
        return blobText.apply(ref.sha256()).map(TerminalFormat::oneLine).orElse("(deleted)");
    }

    /** Text on one line: white space runs become one space, control characters become {@code ?}, cut at the limit. */
    public static String oneLine(String text) {
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
     * separator becomes {@code ?}. Nothing is cut and nothing collapsed.
     */
    public static String safe(String text) {
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
    public static String printable(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(c -> out.appendCodePoint(dangerous(c) && c != '\n' && c != '\t' ? '?' : c));
        return out.toString();
    }

    /** The first characters of a run id. */
    public static String shortId(String runId) {
        return runId.length() <= 8 ? runId : runId.substring(0, 8);
    }
}
