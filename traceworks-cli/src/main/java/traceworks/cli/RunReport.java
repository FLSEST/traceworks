package traceworks.cli;

import traceworks.core.app.RunResult;
import traceworks.core.app.RunServices;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepStatus;
import traceworks.core.record.QuestionView;
import traceworks.core.record.FileView;
import traceworks.core.record.RunView;
import traceworks.core.record.StepView;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** What a command prints about a run when it is done, and how a waiting run is answered. */
public final class RunReport {

    private RunReport() {
    }

    /**
     * The error of a run, on one line. It is cut at the usual length, except the refusal of the run admission, which
     * is printed whole: it lists every reason the workflow is refused for, and a cut would show the first reasons
     * and lose the later ones in the place a person reads.
     */
    private static void error(PrintStream out, String error) {
        boolean refusal = error.startsWith(traceworks.core.engine.RunAdmission.MESSAGE_PREFIX);
        out.println("  error   " + (refusal ? TerminalFormat.safe(error) : TerminalFormat.oneLine(error)));
    }

    /** The summary of a run of the home that a use case just left. */
    public static void summary(PrintStream out, RunServices services, RunResult result) {
        summary(out, services, result, null);
    }

    /**
     * The summary of a run that a use case just left: its status, where it lives, where it ran its steps (in
     * containers or on the host) and, if parked, how to go on. The
     * record is read from the folder the result names, never looked up by the run's id.
     *
     * @param folder the run's folder when the command was given one as a path ({@link Context#runFolder}), which the
     *               hints then name; {@code null} for a run of the home, named by its id
     */
    public static void summary(PrintStream out, RunServices services, RunResult result, String folder) {
        out.println();
        out.println("run " + TerminalFormat.safe(result.runId()) + " " + result.status());
        out.println("  record  " + TerminalFormat.safe(result.location()));
        out.println("  report  " + TerminalFormat.safe(result.location()) + "/run.html (open it in a browser)");
        RunView record = services.view(result.location());
        String where = where(record.execution());
        if (where != null) {
            out.println("  ran     " + where);
        }
        Object gate = result.gate().get("gate_status");
        if (gate != null) {
            Object category = result.gate().get("gate_category");
            out.println("  gate    " + TerminalFormat.safe(gate.toString())
                    + (category == null ? "" : " (" + TerminalFormat.safe(category.toString()) + ")"));
        }
        if (result.error() != null) {
            error(out, result.error());
        }
        if (result.checkout() != null) {
            out.println("  kept    " + TerminalFormat.safe(result.checkout().path()) + " (the checkout of the source: "
                    + TerminalFormat.oneLine(result.checkout().reason()) + ")");
        }
        if (!result.completed().isEmpty()) {
            out.println("  wrote   " + String.join(" and ", result.completed())
                    + " (the run had finished, but its finalisation had not)");
        }
        files(out, record, result, folder);
        if (result.register() != null) {
            out.println("  register " + TerminalFormat.safe(result.register().key()) + ": "
                    + TerminalFormat.oneLine(result.register().summary()));
        }
        if (result.status() == RunStatus.PARKED) {
            RunView view = services.view(result.location());
            waiting(out, folder != null ? folder : result.runId(), view, view.pendingQuestion(),
                    sha -> text(services, result.location(), sha), services.resumeNeedsHostFlag(view));
        }
    }

    /**
     * Where the run ran its steps, as its start recorded it: the start line says it, and under {@code --quiet}, which
     * prints no start line, only the summary does. {@code null} for a record that names no target it knows.
     */
    private static String where(String execution) {
        if ("container".equals(execution)) {
            return "in containers";
        }
        return "host".equals(execution) ? "on the host" : null;
    }

    /** How many files of the scratch directory the summary names. */
    private static final int FILES_SHOWN = 8;

    /**
     * The files the run left in its scratch directory, as the last step that recorded them saw it: the results of a
     * run, named so that no one has to look for them. Nothing when no step recorded files.
     */
    private static void files(PrintStream out, RunView view, RunResult result, String folder) {
        StepView last = null;
        for (StepView step : view.steps()) {
            if (step.files() != null) {
                last = step;
            }
        }
        if (last == null || last.files().files().isEmpty()) {
            return;
        }
        List<FileView> files = last.files().files();
        for (FileView file : files.subList(0, Math.min(files.size(), FILES_SHOWN))) {
            out.println("  file    " + TerminalFormat.safe(result.location()) + "/scratch/"
                    + TerminalFormat.oneLine(file.path()) + " (" + file.size() + " bytes)");
        }
        if (files.size() > FILES_SHOWN) {
            out.println("  files   and " + (files.size() - FILES_SHOWN) + " more; 'traceworks show "
                    + TerminalFormat.safe(folder != null ? folder : TerminalFormat.shortId(result.runId()))
                    + "' lists them");
        }
    }

    /** What every line of a question begins with. */
    private static final String QUESTION_LINE = "  | ";
    /** The most characters of a question on one line, so a marked line fits a terminal of 80 columns. */
    static final int QUESTION_WIDTH = 76;

    /**
     * A line of a question in parts of at most {@value #QUESTION_WIDTH} characters: broken at the last blank within
     * the width, else at the width; the blanks at a break are dropped. A short line is one part, as it is.
     */
    static List<String> broken(String line) {
        List<String> parts = new ArrayList<>();
        String rest = line.stripTrailing();
        while (rest.length() > QUESTION_WIDTH) {
            int cut = rest.lastIndexOf(' ', QUESTION_WIDTH);
            // A blank that only leads the line is no place to break: the part before it would be empty
            if (rest.substring(0, Math.max(cut, 0)).isBlank()) {
                cut = QUESTION_WIDTH;
                // Not between the two halves of one character
                if (Character.isHighSurrogate(rest.charAt(cut - 1))) cut--;
            }
            parts.add(rest.substring(0, cut).stripTrailing());
            rest = rest.substring(cut).stripLeading();
        }
        if (!rest.isEmpty() || parts.isEmpty()) {
            parts.add(rest);
        }
        return parts;
    }

    /**
     * The question a parked run waits for, each of its lines behind {@value #QUESTION_LINE}, with the commands that
     * answer it and resume the run.
     *
     * @param run      how the commands name the run: its id, or its folder when the command was given one as a path
     * @param hostFlag whether the resume needs {@code --allow-host-execution} ({@link RunServices#resumeNeedsHostFlag})
     */
    public static void waiting(PrintStream out, String run, RunView view, QuestionView question,
                        java.util.function.Function<String, String> text, boolean hostFlag) {
        if (view.paused()) {
            paused(out, run, view, hostFlag);
            return;
        }
        if (question == null) {
            return;
        }
        out.println();
        out.println("waiting for an answer at step " + question.step() + ":");
        if (question.question() != null) {
            // The question is text of the workflow: every line of it is marked, so none reads as a hint below. A
            // long line is broken here and each part marked: the terminal would break it without the mark, and a
            // line padded with blanks to the terminal's width would then start a terminal line of its own
            TerminalFormat.printable(text.apply(question.question().sha256())).strip().lines()
                    .forEach(line -> broken(line).forEach(part -> out.println(QUESTION_LINE + part)));
        }
        if (view.parkedAnswer() == null) {
            out.println("answer it with one of:");
            List<String> names = new ArrayList<>();
            if (question.fields() != null) {
                question.fields().forEach(field -> names.add(String.valueOf(field.get("variable_name"))));
            }
            AnswerCommands.of(run, question.kind(), question.options(), names)
                    .forEach(command -> out.println("  " + command));
        } else {
            out.println("it is answered.");
        }
        out.println("then go on with:");
        out.println("  " + AnswerCommands.resume(run, hostFlag));
    }

    /**
     * Where a paused run stopped, and the command that goes on: between two steps, or inside a loop, whose finished
     * items the resume takes over.
     */
    private static void paused(PrintStream out, String run, RunView view, boolean hostFlag) {
        StepView open = view.steps().isEmpty() ? null : view.steps().getLast();
        out.println();
        if (open != null && open.status() == StepStatus.RUNNING) {
            long finished = open.items().stream().filter(item -> "COMPLETED".equals(item.status())).count();
            int total = open.items().isEmpty() ? 0 : open.items().getFirst().total();
            out.println("paused inside the loop of step " + open.stepIndex() + ": " + finished + " of " + total
                    + " items are finished, and the resume runs only the others.");
        } else {
            out.println("paused before the next step: every step that started is finished.");
        }
        out.println("go on with:");
        out.println("  " + AnswerCommands.resume(run, hostFlag));
    }

    private static String text(RunServices services, String runRef, String sha) {
        return services.content(runRef, sha).map(b -> new String(b, StandardCharsets.UTF_8)).orElse("(deleted)");
    }
}
