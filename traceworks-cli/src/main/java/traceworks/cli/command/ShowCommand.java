package traceworks.cli.command;

import traceworks.cli.Args.Parsed;
import traceworks.cli.Args.Spec;
import traceworks.cli.Command;
import traceworks.cli.Context;
import traceworks.cli.ExitCodes;
import traceworks.cli.RunReport;
import traceworks.cli.TerminalFormat;
import traceworks.cli.TerminalText;
import traceworks.core.app.RunServices;
import traceworks.core.container.ImageFacts;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepStatus;
import traceworks.core.port.BlobRef;
import traceworks.core.record.AiCallView;
import traceworks.core.record.CommandView;
import traceworks.core.record.FileView;
import traceworks.core.record.GateView;
import traceworks.core.record.ItemView;
import traceworks.core.record.RunView;
import traceworks.core.record.StepFiles;
import traceworks.core.record.StepView;
import traceworks.core.record.SwitchCase;
import traceworks.core.record.WorkflowOutline;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** {@code traceworks show [<run>]}: what the record of a run says; without a run, of the one that is running now. */
public final class ShowCommand implements Command {

    private static final int OUTPUT_LIMIT = 2000;

    /**
     * How many child runs of one step are listed with their steps. A step that started more lists those that did
     * not complete, at most this many, and counts the rest; {@code --children} lists each.
     */
    private static final int CHILD_RUNS_SHOWN = 10;

    /** What the lines under a step start with; the steps of a child run stand that much further in. */
    private static final String UNDER = "      ";

    private static final Spec SPEC = Spec.of("show", "Shows a run: status, steps, gate and the question it waits for.",
                    "[<run>]")
            .positionals(0, 1)
            .flag("outputs", "add each step's output, cut at " + OUTPUT_LIMIT + " characters")
            .flag("full", "add each step's output whole")
            .flag("children", "list the steps of every child run, also of a step that started more than "
                    + CHILD_RUNS_SHOWN)
            .note(RunningRun.NOTE);

    @Override
    public Spec spec() {
        return SPEC;
    }

    @Override
    public int run(Context context, Parsed args) {
        RunServices services = context.services();
        // Everything is read by the reference as given: the id in a folder's record may be unknown to the home, or
        // be another run of the home, whose texts are not this folder's
        String ref = RunningRun.ref(context, args.positional(), "show");
        String folder = context.runFolder(ref);
        RunView view = services.view(ref);
        // What a record holds is a text from outside, whoever's run it is: a folder can be another's, or edited
        TerminalText.Printer out = new TerminalText.Printer(context.out(), "");
        Function<String, String> text = sha -> services.content(ref, sha)
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8)).orElse("(deleted)");

        out.println("Run       ", TerminalText.safe(view.runId()));
        out.println("Workflow  ", TerminalText.safe(view.workflowName()),
                view.workflowId() == null ? "" : TerminalText.seq(" (", TerminalText.safe(view.workflowId()), ")"));
        out.println("Status    " + view.status());
        out.println("Actor     ", TerminalText.safe(view.actor()));
        out.println("Started   " + time(view.startedAt()));
        if (view.finishedAt() != null) {
            out.println("Finished  " + time(view.finishedAt()));
        }
        out.println("Package   ", TerminalText.safe(view.packageDir()));
        if (view.packageCommit() != null) {
            out.println("Commit    ", TerminalText.safe(view.packageCommit()),
                    Boolean.TRUE.equals(view.packageDirty()) ? " (with uncommitted changes)" : "");
        }
        out.println("Seal      ", view.seal() == null ? "unsealed" : TerminalText.safe(view.seal().summary()));
        // The option the run picked of each model choice its workflow opens: what its steps ran with
        view.modelChoices().forEach(pick -> out.println("Model     ", TerminalText.safe(pick.summary())));
        out.println("Ledger    ", TerminalText.safe(view.ledgerStartLine()));
        if (view.register() != null) {
            // What the run of an audit knew of earlier runs: the head of the register's log it started from
            out.println("Register  ", TerminalText.safe(view.register().summary()));
        }
        egress(out, view, args.has("children"));
        if (view.error() != null) {
            out.println("Error     ", TerminalText.oneLine(text.apply(view.error().sha256())));
        }
        workflow(out, view, services, ref);
        byte[] definition = view.definition() == null ? null
                : services.content(ref, view.definition().sha256()).orElse(null);
        steps(out, view, text, new Shown(args.has("outputs") || args.has("full"), args.has("full"),
                args.has("children")), definition);
        interventions(out, view);
        gate(out, view.gate(), text);
        RunReport.waiting(context.out(), folder != null ? folder : view.runId(), view, view.pendingQuestion(), text,
                services.resumeNeedsHostFlag(view));
        return view.status() == RunStatus.INTERRUPTED ? ExitCodes.INTERRUPTED : ExitCodes.OK;
    }

    /**
     * Where the run's AI containers could connect, and what they asked the egress proxy for in the whole run, with
     * the denied connections counted: an observation that fails nothing, but must not be overlooked.
     */
    private static void egress(TerminalText.Printer out, RunView view, boolean everyChild) {
        if (view.aiNetwork() != null) {
            List<Object> line = new ArrayList<>(List.of("AI net    ", TerminalText.safe(view.aiNetwork())));
            if (view.aiNetwork().equals("open")) {
                line.add(view.webHostsNotEnforced() ? " (every AI container can connect anywhere; the web_hosts of"
                        + " a step are recorded, but not enforced)" : " (every AI container can connect anywhere)");
            }
            view.aiHosts().forEach((cli, hosts) -> line.addAll(List.of("; more hosts for ", TerminalText.safe(cli),
                    ": ", TerminalText.safe(String.join(", ", hosts)))));
            out.println(line);
        }
        RunView.Egress egress = view.egress();
        if (egress.commands() > 0 || egress.connections() > 0) {
            out.println("Egress    " + egress.connections() + (egress.connections() == 1 ? " connection" : " connections")
                    + " asked of the egress proxy by " + egress.commands()
                    + (egress.commands() == 1 ? " command, " : " commands, ")
                    + (egress.denied() == 0 ? "none denied" : egress.denied() + " DENIED"));
        }
        int cuts = view.outputCuts();
        if (cuts > 0) {
            // The steps below, those of the child runs that are listed too, mark the commands of their latest
            // attempt; the line says how many more it counts
            int elsewhere = cuts - markedCuts(view, everyChild, new HashSet<>());
            out.println("Output    " + cuts + (cuts == 1 ? " command" : " commands")
                    + " whose output was CUT before the end of its stream: it may be incomplete"
                    + (elsewhere == 0 ? "" : " (" + elsewhere + " of them in earlier attempts of a step or in child"
                    + " runs that are not listed, which are not marked below)"));
        }
    }

    /**
     * How many commands with a cut output the steps section marks: those of the latest attempt of each step of
     * {@code run} and of each child run that is listed under its step ({@link #listed}), at every level.
     */
    private static int markedCuts(RunView run, boolean everyChild, Set<String> drawn) {
        int marked = run.outputCutsOfLatestAttempts();
        for (StepView step : run.steps()) {
            for (String id : listed(run, step, everyChild)) {
                RunView child = run.children().get(id);
                if (child != null && drawn.add(id)) {
                    marked += markedCuts(child, everyChild, drawn);
                }
            }
        }
        return marked;
    }

    /**
     * The child runs of {@code step} that are listed with their steps: all of them, or of more than
     * {@value #CHILD_RUNS_SHOWN} those that did not complete, at most that many.
     */
    private static List<String> listed(RunView run, StepView step, boolean everyChild) {
        List<String> ids = step.childRunIds();
        return everyChild || ids.size() <= CHILD_RUNS_SHOWN ? ids
                : ids.stream().filter(id -> !completed(run.children().get(id))).limit(CHILD_RUNS_SHOWN).toList();
    }

    /**
     * The commands of the step whose output was not read to its end: something the command left behind still held it
     * at the bound. A mark, like a denied connection; the step's status is as it was.
     */
    private static void outputCuts(TerminalText.Printer out, List<CommandView> commands) {
        for (CommandView command : commands) {
            if (command.outputCut() != null) {
                out.println("      output CUT     ", TerminalText.safe(command.outputCut().summary()),
                        "   <== may be incomplete");
            }
        }
    }

    /**
     * The network of each command of the step that could connect to listed hosts only: the hosts, then one line per
     * target and verdict its container asked the egress proxy for. A target comes from inside the container.
     */
    private static void egress(TerminalText.Printer out, List<CommandView> commands) {
        for (CommandView command : commands) {
            if (!command.throughProxy()) {
                continue;
            }
            out.println("      network hosts ", TerminalText.safe(String.join(", ", command.networkHosts())));
            if (command.result() == null) {
                continue;
            }
            for (CommandView.Connection connection : command.result().egress()) {
                out.println("      egress ", connection.denied() ? "DENIED "
                                : TerminalText.safe(connection.verdict()).padded(7), " ",
                        TerminalText.safe(connection.target()), "  x" + connection.count(),
                        connection.denied() ? "   <== not allowed" : "");
            }
        }
    }

    /** The blocks of the recorded definition, one line each, with the step each became. */
    private static void workflow(TerminalText.Printer out, RunView view, RunServices services, String ref) {
        if (view.definition() == null) {
            return;
        }
        WorkflowOutline outline = services.content(ref, view.definition().sha256())
                .flatMap(WorkflowOutline::of).orElse(null);
        if (outline == null) {
            return;
        }
        out.println();
        out.println("Workflow");
        if (outline.description() != null) {
            out.println("  ", TerminalText.oneLine(outline.description()));
        }
        Map<Integer, StepView> steps = new HashMap<>();
        view.steps().forEach(step -> steps.putIfAbsent(step.position(), step));
        for (WorkflowOutline.Block block : outline.blocks()) {
            StepView step = steps.get(block.position());
            String became = step == null ? "did not run" : "step " + step.stepIndex() + " " + step.shownStatus();
            String type = block.shownType();
            out.println(String.format("  %-3d ", block.position()),
                    TerminalText.oneLine(block.name() == null ? "(unnamed)" : block.name()).padded(24), " ",
                    TerminalText.safe(type).padded(34), " ", became);
            if (block.what() != null) {
                out.println("      ", TerminalText.oneLine(block.what()));
            }
        }
        images(out, view);
    }

    /** What each image the run may use holds, as the record says. */
    private static void images(TerminalText.Printer out, RunView view) {
        if (view.images().isEmpty()) {
            return;
        }
        out.println();
        out.println("Images");
        for (ImageFacts image : view.images()) {
            TerminalText base = TerminalText.safe(image.base() == null ? "(a registry image)" : image.base());
            if (image.description() == null) {
                out.println("  ", TerminalText.safe(image.reference()).padded(24), " ", base);
            } else {
                out.println("  ", TerminalText.safe(image.reference()).padded(24), " ", base.padded(30), " ",
                        TerminalText.oneLine(image.description()));
            }
        }
    }

    /**
     * What of a step is shown besides its line.
     *
     * @param outputs  each step's output
     * @param full     the outputs whole
     * @param children the steps of every child run, however many a step started
     */
    private record Shown(boolean outputs, boolean full, boolean children) {}

    private static void steps(TerminalText.Printer out, RunView view, Function<String, String> text, Shown shown,
                              byte[] definition) {
        if (view.steps().isEmpty()) {
            return;
        }
        out.println();
        out.println("Steps");
        stepLines(out, view, text, shown, definition, new HashSet<>());
    }

    /**
     * The steps of {@code run}, one line each with what belongs to it; under a {@code switch} the case that ran,
     * and under a step that started child runs each child run with its steps, further in, at every level. The child
     * runs are those {@code run.html} draws under the step: the same view of the record.
     *
     * @param definition the recorded {@code workflow.json} of {@code run}; {@code null} for a child run, whose
     *                   definition the record does not hold
     * @param drawn      the child runs listed so far: a child run is listed once
     */
    private static void stepLines(TerminalText.Printer out, RunView run, Function<String, String> text, Shown shown,
                                  byte[] definition, Set<String> drawn) {
        for (StepView step : run.steps()) {
            TerminalText type = TerminalText.safe(step.blockType());
            String badgeEnd = (step.ai() ? ", ai" : "") + "]";
            out.println(String.format("  %-3d ", step.stepIndex()), TerminalText.oneLine(step.label()).padded(24),
                    " [", type, badgeEnd + " ".repeat(Math.max(0, 15 - type.width() - badgeEnd.length())),
                    String.format(" %-17s %s", step.shownStatus(), duration(step)
                            + (step.interrupted() ? "  (interrupted)" : "")
                            // The loop a pause stopped between two items: nothing runs now
                            + (run.paused() && step.status() == StepStatus.RUNNING ? "  (paused)" : "")
                            + (step.attempts() > 1 ? "  (" + step.attempts() + " attempts)" : "")));
            aiCalls(out, step.aiCalls(), text);
            egress(out, step.commands());
            outputCuts(out, step.commands());
            values(out, step.commands(), text, shown.outputs(), shown.full());
            files(out, step.files());
            BlobRef output = step.output();
            if (shown.outputs() && output != null) {
                shown(out, text.apply(output.sha256()), shown.full());
            }
            switchCase(out, step, text, definition);
            childRuns(out, run, step, text, shown, drawn);
        }
    }

    /** Under a {@code switch} that finished: the case it ran and the blocks of that case, as the definition has them. */
    private static void switchCase(TerminalText.Printer out, StepView step, Function<String, String> text,
                                   byte[] definition) {
        if (!"switch".equals(step.blockType()) || step.metadata() == null) {
            return;
        }
        // A deleted or damaged metadata blob, or one without a case: nothing to name
        SwitchCase ran = SwitchCase.of(step, text.apply(step.metadata().sha256())).orElse(null);
        if (ran == null) {
            return;
        }
        if (!ran.ran()) {
            out.println(UNDER + "no case matched");
            return;
        }
        String matched = ran.matched();
        List<WorkflowOutline.Block> blocks = definition == null ? List.of()
                : WorkflowOutline.caseBlocks(definition, step.position(), matched);
        List<Object> line = new ArrayList<>(List.of(UNDER + "case ", TerminalText.oneLine(matched)));
        for (WorkflowOutline.Block block : blocks) {
            line.addAll(List.of(block == blocks.getFirst() ? ": " : ", ",
                    TerminalText.oneLine(block.name() == null ? StepView.UNNAMED : block.name()), " [",
                    TerminalText.safe(block.shownType()), "]"));
        }
        out.println(line);
    }

    /**
     * Under a step that started child runs: each child run with its status, its item for a {@code foreach}, and its
     * gate, then its steps. Of more than {@value #CHILD_RUNS_SHOWN} child runs only those that did not complete are
     * listed, at most that many, and the others are counted, unless every one is asked for.
     */
    private static void childRuns(TerminalText.Printer out, RunView run, StepView step, Function<String, String> text,
                                  Shown shown, Set<String> drawn) {
        List<String> ids = step.childRunIds();
        if (ids.isEmpty()) {
            return;
        }
        List<String> listed = listed(run, step, shown.children());
        Map<String, ItemView> items = new HashMap<>();
        step.items().forEach(item -> {
            if (item.subRun() != null) {
                items.putIfAbsent(item.subRun(), item);
            }
        });
        // The indent is part of each line of a child run, so its texts are broken where the indented line ends
        TerminalText.Printer inner = out.indented(UNDER);
        for (String id : listed) {
            ItemView item = items.get(id);
            Object label = TerminalText.seq(UNDER + (item == null ? "" : "item " + (item.index() + 1) + "/"
                    + item.total() + "  ") + "child run ", TerminalText.safe(TerminalFormat.shortId(id)));
            RunView child = run.children().get(id);
            if (child == null) {
                out.println(label, " (not in the record)");
            } else if (!drawn.add(id)) {
                // Only a record nobody's run wrote names a child twice
                out.println(label, " (listed above, under the step that names it first)");
            } else {
                GateView gate = child.gate();
                out.println(label, " " + child.status(), gate == null ? "" : TerminalText.seq("  gate ",
                        TerminalText.safe(gate.status()), gate.category() == null ? ""
                                : TerminalText.seq(" (", TerminalText.safe(gate.category()), ")")));
                stepLines(inner, child, text, shown, null, drawn);
            }
        }
        int more = ids.size() - listed.size();
        if (more > 0) {
            Map<String, Integer> byStatus = new TreeMap<>();
            ids.stream().filter(id -> !listed.contains(id)).forEach(id -> byStatus.merge(
                    run.children().get(id) == null ? "not in the record" : run.children().get(id).status().name(),
                    1, Integer::sum));
            String statuses = byStatus.size() == 1 ? "all " + byStatus.keySet().iterator().next()
                    : byStatus.entrySet().stream().map(entry -> entry.getValue() + " " + entry.getKey())
                            .collect(Collectors.joining(", "));
            out.println(UNDER + more + " more child run" + (more == 1 ? "" : "s") + ", " + statuses
                    + " (show --children lists each)");
        }
    }

    private static boolean completed(RunView child) {
        return child != null && child.status() == RunStatus.COMPLETED;
    }

    /**
     * One line per AI call of the step: provider, model, tools and how it ended; for a call that failed, the first
     * line of the CLI's error, so that nobody has to open a blob to learn why; then one line per tool call it made, in
     * order.
     */
    private static void aiCalls(TerminalText.Printer out, List<AiCallView> calls, Function<String, String> text) {
        for (AiCallView call : calls) {
            List<Object> line = new ArrayList<>(List.of("      ai ", TerminalText.safe(call.provider())));
            if (call.item() != null) {
                line.add("  item " + call.item());
            }
            if (call.model() != null) {
                line.addAll(List.of("  model ", TerminalText.safe(call.model())));
            }
            if (call.local() != null) {
                // A call that went to a model server of the operator's, not to a provider
                line.addAll(List.of("  ", TerminalText.safe(call.localSummary())));
            }
            line.addAll(List.of("  tools ", TerminalText.safe(call.tools())));
            if (call.hasWeb()) {
                line.addAll(List.of("  web ", TerminalText.safe(call.web())));
                if (!call.webHosts().isEmpty()) {
                    line.addAll(List.of(" (", TerminalText.safe(String.join(", ", call.webHosts())), ")"));
                }
            }
            AiCallView.Response response = call.response();
            if (response == null) {
                line.add("  (no response)");
            } else {
                line.add("  exit " + (response.exitCode() == null ? "none" : response.exitCode()));
                if (response.inputTokens() != null || response.outputTokens() != null) {
                    line.add("  " + response.inputTokens() + " in / " + response.outputTokens() + " out tokens");
                }
            }
            line.add("  " + call.toolCalls().size() + (call.toolCalls().size() == 1 ? " tool call" : " tool calls"));
            out.println(line);
            if (response != null && response.error() != null) {
                String why = AiCallView.firstErrorLine(text.apply(response.error().sha256()));
                if (why != null) {
                    out.println("        error ", TerminalText.oneLine(why));
                }
            }
            for (AiCallView.ToolCallView tool : call.toolCalls()) {
                out.println("        tool ", TerminalText.safe(tool.tool()), tool.error() ? "  (error)" : "");
            }
        }
    }

    /** One line per file the step left in the scratch directory, and per link and removed path. */
    private static void files(TerminalText.Printer out, StepFiles files) {
        if (files == null) {
            return;
        }
        for (FileView file : files.files()) {
            out.println("      file ", TerminalText.safe(file.path()), "  " + file.size() + " bytes",
                    file.blob() == null ? TerminalText.seq("  (not stored: ", TerminalText.safe(file.reason()), ")")
                            : TerminalText.seq("  blob ", TerminalText.safe(file.blob().sha256())));
        }
        for (StepFiles.Link link : files.links()) {
            out.println("      link ", TerminalText.safe(link.path()), " -> ", TerminalText.safe(link.target()));
        }
        for (StepFiles.Unreadable entry : files.unreadable()) {
            out.println("      unreadable ", TerminalText.safe(entry.path()), " (",
                    TerminalText.safe(entry.reason()), ")");
        }
        for (String path : files.removed()) {
            out.println("      removed ", TerminalText.safe(path));
        }
        if (files.truncated()) {
            out.println("      (listing cut)");
        }
    }

    /** What people and processes did to the run from outside its steps: resumes, cancels, answers, refusals. */
    private static void interventions(TerminalText.Printer out, RunView view) {
        if (view.interventions().isEmpty()) {
            return;
        }
        out.println();
        out.println("Interventions");
        for (RunView.Intervention intervention : view.interventions()) {
            out.println("  " + time(intervention.at()) + "  ",
                    TerminalText.safe(intervention.actor() == null ? "-" : intervention.actor()), "  ",
                    TerminalText.oneLine(intervention.description()), TerminalFormat.through(intervention.agent()));
        }
    }

    /**
     * The shell variables each command of the step found set (the block's {@code values}): their names, and with
     * the outputs shown their values too, cut as an output is. Commands with the same values are named once.
     */
    private static void values(TerminalText.Printer out, List<CommandView> commands, Function<String, String> text,
                               boolean contents, boolean full) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (CommandView command : commands) {
            if (command.values() == null || !seen.add(command.values().sha256())) {
                continue;
            }
            Map<String, String> values = CommandView.values(text.apply(command.values().sha256()));
            out.println("      values ", TerminalText.safe(String.join(", ", values.keySet())));
            if (contents) {
                values.forEach((name, value) -> {
                    out.println("      value ", TerminalText.safe(name), ":");
                    shown(out, value, full);
                });
            }
        }
    }

    /**
     * An output behind the mark, line by line. A line wider than the terminal is broken and each part marked: the
     * terminal would break it without the mark, and its end would read as a line of Traceworks.
     */
    private static void shown(TerminalText.Printer out, String content, boolean full) {
        boolean cut = !full && content.length() > OUTPUT_LIMIT;
        out.block("      ", (cut ? content.substring(0, OUTPUT_LIMIT) : content).stripTrailing());
        if (cut) {
            out.println("      | ... (cut at " + OUTPUT_LIMIT + " characters; --full shows it whole)");
        }
    }

    private static void gate(TerminalText.Printer out, GateView gate, Function<String, String> text) {
        if (gate == null) {
            return;
        }
        out.println();
        out.println("Gate      ", TerminalText.safe(gate.status()),
                gate.category() == null ? "" : TerminalText.seq(" (", TerminalText.safe(gate.category()), ")"));
        if (gate.description() != null) {
            out.println("          ", TerminalText.oneLine(text.apply(gate.description().sha256())));
        }
    }

    private static String duration(StepView step) {
        return step.durationMs() + " ms";
    }

    private static String time(Instant at) {
        return at == null ? "-" : at.truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
