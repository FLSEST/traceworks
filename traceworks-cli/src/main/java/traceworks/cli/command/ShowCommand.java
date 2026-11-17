package traceworks.cli.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.cli.Args.Parsed;
import traceworks.cli.Args.Spec;
import traceworks.cli.Command;
import traceworks.cli.Context;
import traceworks.cli.ExitCodes;
import traceworks.cli.RunReport;
import traceworks.cli.TerminalFormat;
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
import traceworks.core.record.WorkflowOutline;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

    private static final ObjectMapper JSON = new ObjectMapper();

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
        PrintStream out = context.out();
        Function<String, String> text = sha -> services.content(ref, sha)
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8)).orElse("(deleted)");

        out.println("Run       " + TerminalFormat.safe(view.runId()));
        out.println("Workflow  " + TerminalFormat.safe(view.workflowName())
                + (view.workflowId() == null ? "" : " (" + TerminalFormat.safe(view.workflowId()) + ")"));
        out.println("Status    " + view.status());
        out.println("Actor     " + TerminalFormat.safe(view.actor()));
        out.println("Started   " + time(view.startedAt()));
        if (view.finishedAt() != null) {
            out.println("Finished  " + time(view.finishedAt()));
        }
        out.println("Package   " + TerminalFormat.safe(view.packageDir()));
        if (view.packageCommit() != null) {
            out.println("Commit    " + TerminalFormat.safe(view.packageCommit())
                    + (Boolean.TRUE.equals(view.packageDirty()) ? " (with uncommitted changes)" : ""));
        }
        out.println("Seal      " + (view.seal() == null ? "unsealed" : TerminalFormat.safe(view.seal().summary())));
        // The option the run picked of each model choice its workflow opens: what its steps ran with
        view.modelChoices().forEach(pick -> out.println("Model     " + TerminalFormat.safe(pick.summary())));
        out.println("Ledger    " + view.ledgerStartLine());
        if (view.register() != null) {
            // What the run of an audit knew of earlier runs: the head of the register's log it started from
            out.println("Register  " + TerminalFormat.safe(view.register().summary()));
        }
        egress(out, view);
        if (view.error() != null) {
            out.println("Error     " + TerminalFormat.oneLine(text.apply(view.error().sha256())));
        }
        workflow(out, view, services, ref);
        byte[] definition = view.definition() == null ? null
                : services.content(ref, view.definition().sha256()).orElse(null);
        steps(out, view, text, new Shown(args.has("outputs") || args.has("full"), args.has("full"),
                args.has("children")), definition);
        interventions(out, view);
        gate(out, view.gate(), text);
        RunReport.waiting(out, folder != null ? folder : view.runId(), view, view.pendingQuestion(), text,
                services.resumeNeedsHostFlag(view));
        return view.status() == RunStatus.INTERRUPTED ? ExitCodes.INTERRUPTED : ExitCodes.OK;
    }

    /**
     * Where the run's AI containers could connect, and what they asked the egress proxy for in the whole run, with
     * the denied connections counted: an observation that fails nothing, but must not be overlooked.
     */
    private static void egress(PrintStream out, RunView view) {
        if (view.aiNetwork() != null) {
            StringBuilder line = new StringBuilder(TerminalFormat.safe(view.aiNetwork()));
            if (view.aiNetwork().equals("open")) {
                line.append(view.webHostsNotEnforced() ? " (every AI container can connect anywhere; the web_hosts of"
                        + " a step are recorded, but not enforced)" : " (every AI container can connect anywhere)");
            }
            view.aiHosts().forEach((cli, hosts) -> line.append("; more hosts for ").append(TerminalFormat.safe(cli))
                    .append(": ").append(TerminalFormat.safe(String.join(", ", hosts))));
            out.println("AI net    " + line);
        }
        RunView.Egress egress = view.egress();
        if (egress.commands() > 0 || egress.connections() > 0) {
            out.println("Egress    " + egress.connections() + (egress.connections() == 1 ? " connection" : " connections")
                    + " asked of the egress proxy by " + egress.commands()
                    + (egress.commands() == 1 ? " command, " : " commands, ")
                    + (egress.denied() == 0 ? "none denied" : egress.denied() + " DENIED"));
        }
    }

    /**
     * The network of each command of the step that could connect to listed hosts only: the hosts, then one line per
     * target and verdict its container asked the egress proxy for. A target comes from inside the container.
     */
    private static void egress(PrintStream out, List<CommandView> commands) {
        for (CommandView command : commands) {
            if (!command.throughProxy()) {
                continue;
            }
            out.println("      network hosts " + TerminalFormat.safe(String.join(", ", command.networkHosts())));
            if (command.result() == null) {
                continue;
            }
            for (CommandView.Connection connection : command.result().egress()) {
                out.println(String.format("      egress %-7s %s  x%d%s", connection.denied() ? "DENIED"
                                : TerminalFormat.safe(connection.verdict()), TerminalFormat.safe(connection.target()),
                        connection.count(), connection.denied() ? "   <== not allowed" : ""));
            }
        }
    }

    /** The blocks of the recorded definition, one line each, with the step each became. */
    private static void workflow(PrintStream out, RunView view, RunServices services, String ref) {
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
            out.println("  " + TerminalFormat.oneLine(outline.description()));
        }
        Map<Integer, StepView> steps = new HashMap<>();
        view.steps().forEach(step -> steps.putIfAbsent(step.position(), step));
        for (WorkflowOutline.Block block : outline.blocks()) {
            StepView step = steps.get(block.position());
            String became = step == null ? "did not run" : "step " + step.stepIndex() + " " + step.shownStatus();
            String type = block.shownType();
            out.println(String.format("  %-3d %-24s %-34s %s", block.position(),
                    TerminalFormat.oneLine(block.name() == null ? "(unnamed)" : block.name()),
                    TerminalFormat.safe(type), became));
            if (block.what() != null) {
                out.println("      " + TerminalFormat.oneLine(block.what()));
            }
        }
        images(out, view);
    }

    /** What each image the run may use holds, as the record says. */
    private static void images(PrintStream out, RunView view) {
        if (view.images().isEmpty()) {
            return;
        }
        out.println();
        out.println("Images");
        for (ImageFacts image : view.images()) {
            out.println(String.format("  %-24s %-30s %s", TerminalFormat.safe(image.reference()),
                    image.base() == null ? "(a registry image)" : TerminalFormat.safe(image.base()),
                    image.description() == null ? "" : TerminalFormat.oneLine(image.description())).stripTrailing());
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

    private static void steps(PrintStream out, RunView view, Function<String, String> text, Shown shown,
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
    private static void stepLines(PrintStream out, RunView run, Function<String, String> text, Shown shown,
                                  byte[] definition, Set<String> drawn) {
        for (StepView step : run.steps()) {
            String badge = "[" + TerminalFormat.safe(step.blockType()) + (step.ai() ? ", ai" : "") + "]";
            out.println(String.format("  %-3d %-24s %-16s %-17s %s", step.stepIndex(),
                    TerminalFormat.oneLine(step.label()), badge, step.shownStatus(), duration(step)
                            + (step.interrupted() ? "  (interrupted)" : "")
                            // The loop a pause stopped between two items: nothing runs now
                            + (run.paused() && step.status() == StepStatus.RUNNING ? "  (paused)" : "")
                            + (step.attempts() > 1 ? "  (" + step.attempts() + " attempts)" : "")));
            aiCalls(out, step.aiCalls(), text);
            egress(out, step.commands());
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
    private static void switchCase(PrintStream out, StepView step, Function<String, String> text, byte[] definition) {
        if (!"switch".equals(step.blockType()) || step.metadata() == null) {
            return;
        }
        String matched;
        try {
            JsonNode value = JSON.readTree(text.apply(step.metadata().sha256())).path("matchedCase");
            matched = value.isTextual() ? value.asText() : null;
        } catch (IOException e) {
            matched = null;   // a deleted or damaged metadata blob: nothing to name
        }
        if (matched == null) {
            return;
        }
        if (matched.equals("none")) {
            out.println(UNDER + "no case matched");
            return;
        }
        List<WorkflowOutline.Block> blocks = definition == null ? List.of()
                : WorkflowOutline.caseBlocks(definition, step.position(), matched);
        out.println(UNDER + "case " + TerminalFormat.oneLine(matched) + (blocks.isEmpty() ? "" : ": " + blocks.stream()
                .map(block -> TerminalFormat.oneLine(block.name() == null ? StepView.UNNAMED : block.name()) + " ["
                        + TerminalFormat.safe(block.shownType()) + "]")
                .collect(Collectors.joining(", "))));
    }

    /**
     * Under a step that started child runs: each child run with its status, its item for a {@code foreach}, and its
     * gate, then its steps. Of more than {@value #CHILD_RUNS_SHOWN} child runs only those that did not complete are
     * listed, at most that many, and the others are counted, unless every one is asked for.
     */
    private static void childRuns(PrintStream out, RunView run, StepView step, Function<String, String> text,
                                  Shown shown, Set<String> drawn) {
        List<String> ids = step.childRunIds();
        if (ids.isEmpty()) {
            return;
        }
        List<String> listed = shown.children() || ids.size() <= CHILD_RUNS_SHOWN ? ids
                : ids.stream().filter(id -> !completed(run.children().get(id))).limit(CHILD_RUNS_SHOWN).toList();
        Map<String, ItemView> items = new HashMap<>();
        step.items().forEach(item -> {
            if (item.subRun() != null) {
                items.putIfAbsent(item.subRun(), item);
            }
        });
        PrintStream inner = indented(out, UNDER);
        for (String id : listed) {
            ItemView item = items.get(id);
            String label = UNDER + (item == null ? "" : "item " + (item.index() + 1) + "/" + item.total() + "  ")
                    + "child run " + TerminalFormat.safe(TerminalFormat.shortId(id));
            RunView child = run.children().get(id);
            if (child == null) {
                out.println(label + " (not in the record)");
            } else if (!drawn.add(id)) {
                // Only a record nobody's run wrote names a child twice
                out.println(label + " (listed above, under the step that names it first)");
            } else {
                GateView gate = child.gate();
                out.println(label + " " + child.status() + (gate == null ? "" : "  gate "
                        + TerminalFormat.safe(gate.status())
                        + (gate.category() == null ? "" : " (" + TerminalFormat.safe(gate.category()) + ")")));
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

    /** A stream that writes each line to {@code out} after {@code indent}. */
    private static PrintStream indented(PrintStream target, String indent) {
        return new PrintStream(target, false, StandardCharsets.UTF_8) {
            @Override
            public void println(String line) {
                target.println(indent + line);
            }
        };
    }

    /**
     * One line per AI call of the step: provider, model, tools and how it ended; for a call that failed, the first
     * line of the CLI's error, so that nobody has to open a blob to learn why; then one line per tool call it made, in
     * order.
     */
    private static void aiCalls(PrintStream out, List<AiCallView> calls, Function<String, String> text) {
        for (AiCallView call : calls) {
            StringBuilder line = new StringBuilder("      ai ").append(TerminalFormat.safe(call.provider()));
            if (call.item() != null) {
                line.append("  item ").append(call.item());
            }
            if (call.model() != null) {
                line.append("  model ").append(TerminalFormat.safe(call.model()));
            }
            if (call.local() != null) {
                // A call that went to a model server of the operator's, not to a provider
                line.append("  ").append(TerminalFormat.safe(call.localSummary()));
            }
            line.append("  tools ").append(TerminalFormat.safe(call.tools()));
            if (call.hasWeb()) {
                line.append("  web ").append(TerminalFormat.safe(call.web()));
                if (!call.webHosts().isEmpty()) {
                    line.append(" (").append(TerminalFormat.safe(String.join(", ", call.webHosts()))).append(")");
                }
            }
            AiCallView.Response response = call.response();
            if (response == null) {
                line.append("  (no response)");
            } else {
                line.append("  exit ").append(response.exitCode() == null ? "none" : response.exitCode());
                if (response.inputTokens() != null || response.outputTokens() != null) {
                    line.append("  ").append(response.inputTokens()).append(" in / ").append(response.outputTokens())
                            .append(" out tokens");
                }
            }
            line.append("  ").append(call.toolCalls().size())
                    .append(call.toolCalls().size() == 1 ? " tool call" : " tool calls");
            out.println(line);
            if (response != null && response.error() != null) {
                String why = AiCallView.firstErrorLine(text.apply(response.error().sha256()));
                if (why != null) {
                    out.println("        error " + TerminalFormat.oneLine(why));
                }
            }
            for (AiCallView.ToolCallView tool : call.toolCalls()) {
                out.println("        tool " + TerminalFormat.safe(tool.tool()) + (tool.error() ? "  (error)" : ""));
            }
        }
    }

    /** One line per file the step left in the scratch directory, and per link and removed path. */
    private static void files(PrintStream out, StepFiles files) {
        if (files == null) {
            return;
        }
        for (FileView file : files.files()) {
            out.println("      file " + TerminalFormat.safe(file.path()) + "  " + file.size() + " bytes"
                    + (file.blob() == null ? "  (not stored: " + TerminalFormat.safe(file.reason()) + ")"
                            : "  blob " + TerminalFormat.safe(file.blob().sha256())));
        }
        for (StepFiles.Link link : files.links()) {
            out.println("      link " + TerminalFormat.safe(link.path()) + " -> "
                    + TerminalFormat.safe(link.target()));
        }
        for (StepFiles.Unreadable entry : files.unreadable()) {
            out.println("      unreadable " + TerminalFormat.safe(entry.path()) + " ("
                    + TerminalFormat.safe(entry.reason()) + ")");
        }
        for (String path : files.removed()) {
            out.println("      removed " + TerminalFormat.safe(path));
        }
        if (files.truncated()) {
            out.println("      (listing cut)");
        }
    }

    /** What people and processes did to the run from outside its steps: resumes, cancels, answers, refusals. */
    private static void interventions(PrintStream out, RunView view) {
        if (view.interventions().isEmpty()) {
            return;
        }
        out.println();
        out.println("Interventions");
        for (RunView.Intervention intervention : view.interventions()) {
            out.println("  " + time(intervention.at()) + "  "
                    + TerminalFormat.safe(intervention.actor() == null ? "-" : intervention.actor()) + "  "
                    + TerminalFormat.oneLine(intervention.description()));
        }
    }

    /**
     * The shell variables each command of the step found set (the block's {@code values}): their names, and with
     * the outputs shown their values too, cut as an output is. Commands with the same values are named once.
     */
    private static void values(PrintStream out, List<CommandView> commands, Function<String, String> text,
                               boolean contents, boolean full) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (CommandView command : commands) {
            if (command.values() == null || !seen.add(command.values().sha256())) {
                continue;
            }
            Map<String, String> values = CommandView.values(text.apply(command.values().sha256()));
            out.println("      values " + TerminalFormat.safe(String.join(", ", values.keySet())));
            if (contents) {
                values.forEach((name, value) -> {
                    out.println("      value " + TerminalFormat.safe(name) + ":");
                    shown(out, value, full);
                });
            }
        }
    }

    private static void shown(PrintStream out, String content, boolean full) {
        boolean cut = !full && content.length() > OUTPUT_LIMIT;
        String body = TerminalFormat.printable(cut ? content.substring(0, OUTPUT_LIMIT) : content);
        body.stripTrailing().lines().forEach(line -> out.println("      | " + line));
        if (cut) {
            out.println("      | ... (cut at " + OUTPUT_LIMIT + " characters; --full shows it whole)");
        }
    }

    private static void gate(PrintStream out, GateView gate, Function<String, String> text) {
        if (gate == null) {
            return;
        }
        out.println();
        out.println("Gate      " + TerminalFormat.safe(gate.status())
                + (gate.category() == null ? "" : " (" + TerminalFormat.safe(gate.category()) + ")"));
        if (gate.description() != null) {
            out.println("          " + TerminalFormat.oneLine(text.apply(gate.description().sha256())));
        }
    }

    private static String duration(StepView step) {
        return step.durationMs() + " ms";
    }

    private static String time(Instant at) {
        return at == null ? "-" : at.truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
