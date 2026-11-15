package traceworks.core.engine;

import traceworks.core.context.ExecutionContext;
import traceworks.core.context.UntrustedInput;
import traceworks.core.gate.CommandExitDescription;
import traceworks.core.gate.GateStatusOutputParser;
import traceworks.core.gate.GateStatusOutputParser.ParsedGateStatus;
import traceworks.core.model.GateStatus;
import traceworks.core.model.StepResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The run's gate verdict plus every gate behind it, as {@link RunCompletionHandler} writes them into the run
 * metadata. {@code items} and {@code countsByStatus} become {@code gate_summary}, so trend tooling can diff runs
 * without re-parsing step outputs.
 */
record GateAggregation(ParsedGateStatus verdict, List<Map<String, Object>> items,
                       Map<String, Integer> countsByStatus) {

    /** The run metadata keys {@link #writeTo} writes. */
    static final List<String> METADATA_KEYS = List.of("gate_status", "gate_category", "gate_description",
            "gate_summary");

    /**
     * Parses the gate_status markers of the given steps: highest severity wins, between equals see
     * {@link #closingGreenVerdict}. {@code null} when no step reported a gate.
     */
    static GateAggregation of(List<StepResult> markerSteps) {
        ParsedGateStatus verdict = null;
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Integer> countsByStatus = new HashMap<>();
        for (StepResult step : markerSteps) {
            Optional<ParsedGateStatus> parsed = GateStatusOutputParser.parse(step.getOutput());
            if (parsed.isEmpty()) {
                continue;
            }
            ParsedGateStatus gate = parsed.get();
            verdict = verdict == null ? gate : fold(verdict, gate);
            Map<String, Object> item = item(gate);
            item.put("step_index", step.getStepIndex());
            items.add(item);
            countsByStatus.merge(gate.status().name(), 1, Integer::sum);
        }
        return verdict == null ? null : new GateAggregation(verdict, items, countsByStatus);
    }

    /**
     * The stand-in for an expected but missing verdict: YELLOW gate-missing, and in gate_summary one item marked
     * synthetic. No step delivered it, so it has no step_index.
     */
    static GateAggregation missing() {
        ParsedGateStatus verdict = new ParsedGateStatus(GateStatus.YELLOW,
                RunCompletionHandler.GATE_MISSING_CATEGORY, RunCompletionHandler.GATE_MISSING_DESCRIPTION);
        Map<String, Object> item = item(verdict);
        item.put("synthetic", true);
        return new GateAggregation(verdict, new ArrayList<>(List.of(item)),
                new HashMap<>(Map.of(verdict.status().name(), 1)));
    }

    /**
     * {@code gate} plus one YELLOW item per step that found an instruction aimed at an AI in its input or output
     * ({@link UntrustedInput#METADATA_KEY}): the run shows it even when nothing expected a verdict. Returns
     * {@code gate} itself when no step found one.
     */
    static GateAggregation withUntrustedInstructions(GateAggregation gate, List<StepResult> steps) {
        GateAggregation result = gate;
        for (StepResult step : steps) {
            Object found = step.getMetadata() != null ? step.getMetadata().get(UntrustedInput.METADATA_KEY) : null;
            if (!(found instanceof List<?> suspects) || suspects.isEmpty()) {
                continue;
            }
            ParsedGateStatus finding = new ParsedGateStatus(GateStatus.YELLOW,
                    RunCompletionHandler.UNTRUSTED_INSTRUCTION_CATEGORY, untrustedDescription(step, suspects));
            Map<String, Object> item = item(finding);
            item.put("step_index", step.getStepIndex());
            List<Map<String, Object>> items = new ArrayList<>(result == null ? List.of() : result.items());
            items.add(item);
            Map<String, Integer> counts = new HashMap<>(result == null ? Map.of() : result.countsByStatus());
            counts.merge(GateStatus.YELLOW.name(), 1, Integer::sum);
            result = new GateAggregation(result == null ? finding : fold(result.verdict(), finding), items, counts);
        }
        return result;
    }

    private static String untrustedDescription(StepResult step, List<?> suspects) {
        Object name = step.getMetadata().get("blockName");
        String block = name != null ? "Block " + name : "Step " + step.getStepIndex();
        String first = suspects.getFirst() instanceof Map<?, ?> s
                ? ("output".equals(s.get("where")) ? "output" : "input") + " line " + s.get("line") + ": \""
                        + s.get("excerpt") + "\""
                : "";
        return block + ": possible injected instruction to AI systems in " + first
                + (suspects.size() > 1 ? " (+" + (suspects.size() - 1) + " more)" : "");
    }

    /** gate_status/gate_category/gate_description carry the verdict, gate_summary all gates. */
    void writeTo(ExecutionContext context) {
        metadata().forEach(context::setRunMetadata);
    }

    /** The run metadata {@link #writeTo} writes, in the order of {@link #METADATA_KEYS}; absent values are left out. */
    Map<String, Object> metadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("gate_status", verdict.status().name());
        if (verdict.category() != null) metadata.put("gate_category", verdict.category());
        if (verdict.description() != null) metadata.put("gate_description", verdict.description());

        Map<String, Object> gateSummary = new HashMap<>();
        gateSummary.put("highest", verdict.status().name());
        gateSummary.put("counts_by_status", countsByStatus);
        gateSummary.put("items", items);
        metadata.put("gate_summary", gateSummary);
        return metadata;
    }

    /**
     * The verdict so far after one more gate: a worse one replaces all three fields, an equal one only as a
     * {@link #closingGreenVerdict}. Without a category of its own it keeps the one so far, so a closing verdict does
     * not rename the run's category.
     */
    private static ParsedGateStatus fold(ParsedGateStatus soFar, ParsedGateStatus next) {
        if (next.status().ordinal() > soFar.status().ordinal()) {
            return next;
        }
        if (!closingGreenVerdict(next, soFar)) {
            return soFar;
        }
        String category = next.category() != null ? next.category() : soFar.category();
        return new ParsedGateStatus(soFar.status(), category, next.description());
    }

    /**
     * Whether an equal gate replaces the one so far as the run's {@code gate_description} (a worse one always
     * replaces all three fields). It depends on the status.
     *
     * <p>RED and YELLOW keep the first: the cause, typically a {@code checked_command} whose category names what
     * failed, which tooling keys incidents by, so a closing verdict must not rename it. GREEN has no cause, so the
     * closing verdict speaks for the run, unless it says less than the one so far ({@link #descriptionWeight}).
     * Neither a {@code checked_command} before the verdict nor one after it replaces the verdict, and a bare later
     * GREEN does not replace a described one.
     */
    private static boolean closingGreenVerdict(ParsedGateStatus next, ParsedGateStatus soFar) {
        return soFar.status() == GateStatus.GREEN && next.status() == GateStatus.GREEN
                && descriptionWeight(next.description()) >= descriptionWeight(soFar.description());
    }

    /** What a gate description says: words of its own (2), only a checked_command's exit code (1), nothing (0). */
    private static int descriptionWeight(String description) {
        if (description == null) {
            return 0;
        }
        return CommandExitDescription.isSynthetic(description) ? 1 : 2;
    }

    private static Map<String, Object> item(ParsedGateStatus gate) {
        Map<String, Object> item = new HashMap<>();
        item.put("status", gate.status().name());
        if (gate.category() != null) item.put("category", gate.category());
        if (gate.description() != null) item.put("description", gate.description());
        return item;
    }
}
