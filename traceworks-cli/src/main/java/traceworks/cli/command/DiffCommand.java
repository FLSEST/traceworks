package traceworks.cli.command;

import traceworks.cli.Args.Parsed;
import traceworks.cli.Args.Spec;
import traceworks.cli.Command;
import traceworks.cli.Context;
import traceworks.cli.ExitCodes;
import traceworks.cli.TerminalFormat;
import traceworks.core.app.RunServices;
import traceworks.core.compare.LineDiff;
import traceworks.core.compare.RunComparison;
import traceworks.core.compare.RunComparison.AiCall;
import traceworks.core.compare.RunComparison.Call;
import traceworks.core.compare.RunComparison.Field;
import traceworks.core.compare.RunComparison.Finding;
import traceworks.core.compare.RunComparison.FindingsDelta;
import traceworks.core.compare.RunComparison.Input;
import traceworks.core.compare.RunComparison.Item;
import traceworks.core.compare.RunComparison.Output;
import traceworks.core.compare.RunComparison.Step;
import traceworks.core.port.BlobRef;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@code traceworks diff <runA> <runB>}: two finished runs side by side, from their records, and whether run B
 * reproduced run A. It reads both runs and writes nothing.
 */
public final class DiffCommand implements Command {

    /** The AI calls of a step that are printed even when nothing of them differs; beyond it, those are counted. */
    private static final int CALLS_SHOWN = 5;
    /** The reasons printed after {@code differs}; beyond it, the rest is counted. */
    private static final int REASONS_SHOWN = 20;
    private static final int CONTEXT = 2;

    private static final Spec SPEC = Spec.of("diff", "Compares two finished runs. Exits " + ExitCodes.DIFFERS
                    + " when run B did not reproduce run A.", "<runA> <runB>")
            .positionals(2, 2)
            .flag("outputs", "add a line diff of the step outputs that differ")
            .note("Exits 0 when the package digest, the inputs, the status and output of every deterministic step and"
                    + " the setup of\nevery AI call are equal, and " + ExitCodes.DIFFERS + " otherwise. The answer of"
                    + " an AI call may differ. A run that has not\nfinished (exit 6), cannot be read or does not"
                    + " verify (exit 8) is an error, not a difference.\nSteps are matched by block name. See"
                    + " docs/cli.md, \"diff\".");

    @Override
    public Spec spec() {
        return SPEC;
    }

    @Override
    public int run(Context context, Parsed args) {
        RunServices services = context.services();
        String refA = args.positionals().get(0);
        String refB = args.positionals().get(1);
        RunComparison comparison = services.compare(refA, refB);
        PrintStream out = context.out();
        out.println("Run A     " + TerminalFormat.safe(comparison.runA()));
        out.println("Run B     " + TerminalFormat.safe(comparison.runB()));
        header(out, comparison);
        inputs(out, comparison);
        // The outputs are read by the references as given: a run folder outside the home has no id the home knows
        steps(out, comparison, args.has("outputs") ? sha -> services.content(refA, sha) : null,
                sha -> services.content(refB, sha));
        findings(out, comparison.findings());
        out.println();
        if (comparison.reproduced()) {
            out.println("same: run B reproduced run A (package digest, inputs, every deterministic step and every AI"
                    + " setup are equal)");
            return ExitCodes.OK;
        }
        out.println("differs: run B did not reproduce run A");
        List<String> reasons = comparison.differences();
        reasons.stream().limit(REASONS_SHOWN).forEach(reason -> out.println("  " + TerminalFormat.oneLine(reason)));
        if (reasons.size() > REASONS_SHOWN) {
            out.println("  ... and " + (reasons.size() - REASONS_SHOWN) + " more");
        }
        return ExitCodes.DIFFERS;
    }

    /** The header fields that differ, with both values, then one line that names the fields that are the same. */
    private static void header(PrintStream out, RunComparison comparison) {
        out.println();
        out.println("Header");
        for (Field field : comparison.header()) {
            if (!field.same()) {
                both(out, field.name(), value(field.a()), value(field.b()));
            }
        }
        same(out, comparison.header().stream().filter(Field::same).map(Field::name).toList());
        comparison.notes().forEach(note -> out.println("  note: " + TerminalFormat.oneLine(note)));
    }

    /** The inputs that differ, key by key, then one line that names the ones that are the same. */
    private static void inputs(PrintStream out, RunComparison comparison) {
        if (comparison.inputs().isEmpty()) {
            return;
        }
        out.println();
        out.println("Inputs");
        for (Input input : comparison.inputs()) {
            if (input.same()) {
                continue;
            }
            if (input.hidden()) {
                out.println(String.format("  %-20s differs (a redacted value is not shown)", name(input.key())));
            } else {
                both(out, name(input.key()), value(input.a()), value(input.b()));
            }
        }
        same(out, comparison.inputs().stream().filter(Input::same).map(input -> name(input.key())).toList());
    }

    private static void both(PrintStream out, String name, String a, String b) {
        out.println(String.format("  %-20s A %s", TerminalFormat.safe(name), a));
        out.println(String.format("  %-20s B %s", "", b));
    }

    private static void same(PrintStream out, List<String> names) {
        if (!names.isEmpty()) {
            out.println("  same: " + TerminalFormat.safe(String.join(", ", names)));
        }
    }

    /** The name of an input; one the record redacted is shown as that. */
    private static String name(String key) {
        return TerminalFormat.oneLine(key);
    }

    private static String value(String value) {
        return value == null ? "-" : TerminalFormat.oneLine(value);
    }

    /**
     * @param contentA reads a blob of run A; {@code null} when the outputs are not asked for
     * @param contentB reads a blob of run B
     */
    private static void steps(PrintStream out, RunComparison comparison, Function<String, Optional<byte[]>> contentA,
                              Function<String, Optional<byte[]>> contentB) {
        if (comparison.steps().isEmpty()) {
            return;
        }
        out.println();
        out.println("Steps");
        for (Step step : comparison.steps()) {
            String label = TerminalFormat.oneLine(step.label()) + (step.ai() ? "  [ai]" : "");
            if (!step.inBoth()) {
                out.println(String.format("  %-30s only in run %s (%s)", label, step.statusA() == null ? "B" : "A",
                        step.statusA() == null ? step.statusB() : step.statusA()));
            } else {
                String status = step.statusA().equals(step.statusB()) ? step.statusA()
                        : step.statusA() + " / " + step.statusB();
                out.println(String.format("  %-30s %-17s %s", label, status, words(step.output())));
            }
            calls(out, step.calls());
            items(out, step.items());
            if (contentA != null && step.inBoth() && step.output().differs()) {
                lineDiff(out, text(contentA, step.outputA()), text(contentB, step.outputB()));
            }
        }
    }

    private static String words(Output output) {
        return switch (output) {
            case SAME -> "same output";
            case DIFFERS -> "output differs";
            case NONE -> "no output";
            case ONLY_A -> "output only in run A";
            case ONLY_B -> "output only in run B";
        };
    }

    /**
     * The AI calls of a step: each with what both runs' records have of it. Of a step with more than
     * {@value #CALLS_SHOWN} calls, the ones with nothing that differs are counted, and the tokens and cost summed.
     */
    private static void calls(PrintStream out, List<AiCall> calls) {
        boolean fold = calls.size() > CALLS_SHOWN;
        int folded = 0;
        for (AiCall call : calls) {
            boolean nothingDiffers = call.inBoth() && call.setupDifferences().isEmpty() && call.promptSame()
                    && !call.output().differs();
            if (fold && nothingDiffers) {
                folded++;
                continue;
            }
            String verdict;
            if (!call.inBoth()) {
                verdict = "only in run " + (call.a() == null ? "B" : "A");
            } else {
                verdict = (call.setupDifferences().isEmpty() ? "same setup"
                        : "SETUP DIFFERS (" + String.join(", ", call.setupDifferences()) + ")")
                        + ", " + (call.promptSame() ? "prompt same" : call.promptFollowsAiOutput()
                        ? "prompt differs (it follows an AI output that differed)" : "prompt differs")
                        + ", " + words(call.output());
            }
            out.println("      " + call.label() + "  " + verdict);
            side(out, "A", call.a());
            side(out, "B", call.b());
        }
        if (folded > 0) {
            out.println("      " + folded + " more AI calls with the same setup, prompt and output");
        }
        if (calls.size() > 1) {
            out.println("      AI total  A " + total(calls.stream().map(AiCall::a).toList()) + "   B "
                    + total(calls.stream().map(AiCall::b).toList()));
        }
    }

    private static void side(PrintStream out, String run, Call call) {
        if (call == null) {
            return;
        }
        out.println("        " + run + "  " + TerminalFormat.safe(call.provider())
                + "  model " + (call.model() == null ? "(default)" : TerminalFormat.safe(call.model()))
                + "  answered by " + (call.answeredBy() == null ? "-" : TerminalFormat.safe(call.answeredBy()))
                + (call.effort() == null ? "" : "  effort " + TerminalFormat.safe(call.effort()))
                + "  tools " + TerminalFormat.safe(call.tools())
                + "  web " + TerminalFormat.safe(call.web())
                + "  " + tokens(call.inputTokens(), call.outputTokens())
                + "  cost " + (call.costUsd() == null ? "-" : usd(call.costUsd())));
    }

    private static String tokens(Integer in, Integer out) {
        return in == null && out == null ? "no token count" : (in == null ? "-" : in) + " in / "
                + (out == null ? "-" : out) + " out tokens";
    }

    private static String usd(double cost) {
        return String.format(Locale.ROOT, "%.4f USD", cost);
    }

    /** The calls, tokens and cost of one run's side of a step; a call that reported none adds nothing. */
    private static String total(List<Call> calls) {
        long in = 0;
        long out = 0;
        double cost = 0;
        int n = 0;
        boolean anyCost = false;
        for (Call call : calls) {
            if (call == null) {
                continue;
            }
            n++;
            in += call.inputTokens() == null ? 0 : call.inputTokens();
            out += call.outputTokens() == null ? 0 : call.outputTokens();
            if (call.costUsd() != null) {
                cost += call.costUsd();
                anyCost = true;
            }
        }
        return n + (n == 1 ? " call, " : " calls, ") + in + " in / " + out + " out tokens, cost "
                + (anyCost ? usd(cost) : "-");
    }

    /** The {@code foreach} items that differ, one line each; the others are counted. */
    private static void items(PrintStream out, List<Item> items) {
        int same = 0;
        for (Item item : items) {
            if (item.same()) {
                same++;
            } else if (item.statusA() == null || item.statusB() == null) {
                out.println("      item " + item.index() + "  only in run " + (item.statusA() == null ? "B" : "A"));
            } else {
                out.println("      item " + item.index() + "  " + (item.sameItem() ? "same item" : "another item")
                        + ", " + (item.statusA().equals(item.statusB()) ? TerminalFormat.safe(item.statusA())
                        : TerminalFormat.safe(item.statusA()) + " / " + TerminalFormat.safe(item.statusB()))
                        + ", " + words(item.output()));
            }
        }
        if (same > 0 && same < items.size()) {
            out.println("      " + same + (same == 1 ? " item is" : " items are") + " the same");
        }
    }

    /**
     * The line diff of two outputs, read from the record as {@code show --full} reads them, so with the same
     * redaction; over the bound of {@link LineDiff}, a line that says so.
     */
    private static void lineDiff(PrintStream out, String textA, String textB) {
        if (textA == null || textB == null) {
            out.println("      | (the output of run " + (textA == null ? "A" : "B") + " is deleted from its record)");
            return;
        }
        List<String> linesA = TerminalFormat.printable(textA).lines().toList();
        List<String> linesB = TerminalFormat.printable(textB).lines().toList();
        Optional<List<LineDiff.Line>> diff = LineDiff.of(linesA, linesB);
        if (diff.isEmpty()) {
            out.println("      | (too large for a line diff (" + linesA.size() + " and " + linesB.size()
                    + " lines); traceworks show <run> --full prints each output whole)");
            return;
        }
        LineDiff.fold(diff.get(), CONTEXT).forEach(line -> out.println("      | " + line));
    }

    /** The text of an output; empty for a step without one, {@code null} when its blob was deleted. */
    private static String text(Function<String, Optional<byte[]>> content, BlobRef blob) {
        if (blob == null) {
            return "";
        }
        return content.apply(blob.sha256()).map(bytes -> new String(bytes, StandardCharsets.UTF_8)).orElse(null);
    }

    private static void findings(PrintStream out, FindingsDelta delta) {
        if (delta == null) {
            return;
        }
        out.println();
        out.println("Findings (register " + TerminalFormat.safe(delta.register()) + ")");
        if (delta.note() != null) {
            out.println("  not compared: " + TerminalFormat.oneLine(delta.note()));
            return;
        }
        if (delta.added().isEmpty() && delta.resolved().isEmpty() && delta.reopened().isEmpty()) {
            out.println("  none new, resolved or reopened between the two runs");
            return;
        }
        findings(out, "new", delta.added());
        findings(out, "resolved", delta.resolved());
        findings(out, "reopened", delta.reopened());
    }

    private static void findings(PrintStream out, String what, List<Finding> findings) {
        for (Finding finding : findings) {
            out.println(String.format("  %-9s %s", what, Stream.of(finding.id() == null ? null
                            : finding.id().substring(0, Math.min(8, finding.id().length())), finding.severity(),
                    finding.subject(), finding.title()).filter(java.util.Objects::nonNull)
                    .map(TerminalFormat::oneLine).collect(Collectors.joining("  "))));
        }
    }
}
