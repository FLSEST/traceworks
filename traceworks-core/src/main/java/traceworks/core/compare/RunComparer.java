package traceworks.core.compare;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.compare.RunComparison.AiCall;
import traceworks.core.compare.RunComparison.Call;
import traceworks.core.compare.RunComparison.Field;
import traceworks.core.compare.RunComparison.Input;
import traceworks.core.compare.RunComparison.Item;
import traceworks.core.compare.RunComparison.Output;
import traceworks.core.compare.RunComparison.Step;
import traceworks.core.port.BlobRef;
import traceworks.core.record.AiCallView;
import traceworks.core.record.GateView;
import traceworks.core.record.ItemView;
import traceworks.core.record.Manifest;
import traceworks.core.record.Redactor;
import traceworks.core.record.RunView;
import traceworks.core.record.StepView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Compares two finished runs, A and B, from their records, and decides whether B reproduced A.
 *
 * <p><b>Matching.</b> A step is matched by its block name; a name that ran more than once in a run by its occurrence
 * ({@code poll}, {@code poll #2}); a block without a name by its position. A step of one run only is listed as such.
 * A {@code foreach} item is matched by its index, an AI call by its item and its order among the calls of that item.
 * A {@code retry} or {@code while} is one step in the record, with one output: its attempts and iterations show as
 * its AI calls, in order. The steps of a child run are compared under the step that started it, the child of a
 * {@code foreach} item with the child of the same item, any other in the order the step started them. Of a step that
 * started again after a resume, the latest attempt is compared.
 *
 * <p><b>What fails the check</b> ({@link RunComparison#differences()}):
 * <ul>
 *   <li>another package digest, or other inputs (another inputs digest);</li>
 *   <li>another picked option of a model choice ({@code run.started.model_choices});</li>
 *   <li>a deterministic step whose status or output differs, or that only one run has, and a {@code foreach} item of
 *       such a step likewise. An output is compared by the hash of its blob. This holds also after an AI output that
 *       differed: the reason then says so;</li>
 *   <li>an AI call with another setup: provider, model asked for, effort, tool policy, web mode and hosts, system
 *       prompt, prompt, or the model that answered (when both calls report one). A prompt that differs counts only
 *       when no AI output before the call differed; after one, it is that output carried on, and the setup is the
 *       same.</li>
 * </ul>
 *
 * <p>What does not: the answer of an AI call, the output and status of an AI step, an AI step or call that only one
 * run has, and every other header field (version, execution, images, the operator's AI settings, models, status,
 * gate). They are reported.
 */
public final class RunComparer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<Step> steps = new ArrayList<>();
    private final List<String> differences = new ArrayList<>();
    /** Whether an AI output differed in what was compared so far, in the order of run A's steps. */
    private boolean aiOutputDiffered;

    private RunComparer() {
    }

    public static RunComparison compare(RunFacts a, RunFacts b) {
        RunComparer comparer = new RunComparer();
        List<String> notes = new ArrayList<>();
        List<Field> header = comparer.header(a, b);
        List<Input> inputs = inputs(a, b, notes);
        comparer.steps("", a.view(), b.view());
        return new RunComparison(a.view().runId(), b.view().runId(), header, inputs, comparer.steps,
                comparer.differences, notes, null);
    }

    private List<Field> header(RunFacts a, RunFacts b) {
        RunView va = a.view();
        RunView vb = b.view();
        Manifest ma = a.manifest();
        Manifest mb = b.manifest();
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("workflow id", va.workflowId(), vb.workflowId()));
        fields.add(new Field("workflow name", va.workflowName(), vb.workflowName()));
        fields.add(new Field("package digest", va.packageDigest(), vb.packageDigest()));
        fields.add(new Field("package commit", commit(va), commit(vb)));
        fields.add(new Field("inputs digest", ma.inputsDigest(), mb.inputsDigest()));
        fields.add(new Field("Traceworks version", ma.version(), mb.version()));
        fields.add(new Field("execution", va.execution(), vb.execution()));
        byKey("image ", images(ma), images(mb), fields);
        byKey("ai_images ", a.aiImages(), b.aiImages(), fields);
        fields.add(new Field("ai_network", va.aiNetwork(), vb.aiNetwork()));
        byKey("ai_hosts ", joined(va.aiHosts()), joined(vb.aiHosts()), fields);
        List<String> picksThatDiffer = modelChoices(va, vb, fields);
        fields.add(new Field("models", list(ma.models()), list(mb.models())));
        fields.add(new Field("status", va.status().name(), vb.status().name()));
        fields.add(new Field("gate", gate(va.gate()), gate(vb.gate())));
        if (!Objects.equals(va.packageDigest(), vb.packageDigest())) {
            differences.add("the package digest differs");
        }
        if (!Objects.equals(ma.inputsDigest(), mb.inputsDigest())) {
            differences.add("the inputs differ");
        }
        differences.addAll(picksThatDiffer);
        return fields;
    }

    /**
     * The option each run picked of every model choice either names, one field per choice; a choice whose picks
     * differ is a difference of its own, next to the inputs that picked.
     */
    private static List<String> modelChoices(RunView a, RunView b, List<Field> fields) {
        List<String> differences = new ArrayList<>();
        Map<String, String> picksA = new TreeMap<>();
        Map<String, String> picksB = new TreeMap<>();
        a.modelChoices().forEach(pick -> picksA.put(pick.choice(), pick.picked()));
        b.modelChoices().forEach(pick -> picksB.put(pick.choice(), pick.picked()));
        byKey("model choice ", picksA, picksB, fields);
        Set<String> choices = new TreeSet<>(picksA.keySet());
        choices.addAll(picksB.keySet());
        for (String choice : choices) {
            if (!Objects.equals(picksA.get(choice), picksB.get(choice))) {
                differences.add("the model choice " + choice + " differs: " + shown(picksA.get(choice)) + " in run A, "
                        + shown(picksB.get(choice)) + " in run B");
            }
        }
        return differences;
    }

    private static String shown(String pick) {
        return pick == null ? "no such choice" : pick;
    }

    private static String commit(RunView view) {
        return view.packageCommit() == null ? null
                : view.packageCommit() + (Boolean.TRUE.equals(view.packageDirty()) ? " (with uncommitted changes)" : "");
    }

    private static String gate(GateView gate) {
        return gate == null ? null : gate.status() + (gate.category() == null ? "" : " (" + gate.category() + ")");
    }

    private static String list(List<String> values) {
        return values.isEmpty() ? null : String.join(", ", values);
    }

    /** The image ids by reference; a reference that ran from several ids has them all, sorted. */
    private static Map<String, String> images(Manifest manifest) {
        Map<String, Set<String>> ids = new TreeMap<>();
        manifest.images().forEach(image -> ids.computeIfAbsent(image.reference(), k -> new TreeSet<>()).add(image.id()));
        Map<String, String> joined = new TreeMap<>();
        ids.forEach((reference, set) -> joined.put(reference, String.join(", ", set)));
        return joined;
    }

    private static Map<String, String> joined(Map<String, List<String>> lists) {
        Map<String, String> joined = new TreeMap<>();
        lists.forEach((key, values) -> joined.put(key, String.join(", ", values)));
        return joined;
    }

    /** One field per key of either map, sorted by key. */
    private static void byKey(String prefix, Map<String, String> a, Map<String, String> b, List<Field> fields) {
        Set<String> keys = new TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        keys.forEach(key -> fields.add(new Field(prefix + key, a.get(key), b.get(key))));
    }

    private static List<Input> inputs(RunFacts a, RunFacts b, List<String> notes) {
        for (RunFacts facts : List.of(a, b)) {
            if (facts.inputs() == null) {
                notes.add("the inputs of run " + facts.view().runId() + " are deleted from its record: the inputs are"
                        + " compared by their digest only");
            }
        }
        if (a.inputs() == null || b.inputs() == null) {
            return List.of();
        }
        Set<String> keys = new LinkedHashSet<>(a.inputs().keySet());
        keys.addAll(b.inputs().keySet());
        List<Input> inputs = new ArrayList<>();
        for (String key : keys) {
            String valueA = a.inputs().containsKey(key) ? json(a.inputs().get(key)) : null;
            String valueB = b.inputs().containsKey(key) ? json(b.inputs().get(key)) : null;
            boolean hidden = redacted(key) || redacted(valueA) || redacted(valueB);
            inputs.add(new Input(key, Objects.equals(valueA, valueB), hidden, hidden ? null : valueA,
                    hidden ? null : valueB));
        }
        return inputs;
    }

    private static boolean redacted(String text) {
        return text != null && text.contains(Redactor.MARKER);
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    /** Compares the steps of two runs, or of two child runs; {@code null} for a child only one run has. */
    private void steps(String prefix, RunView a, RunView b) {
        Map<String, StepView> stepsA = keyed(a);
        Map<String, StepView> stepsB = keyed(b);
        for (Map.Entry<String, StepView> entry : stepsA.entrySet()) {
            step(prefix + entry.getKey(), entry.getValue(), stepsB.get(entry.getKey()), a, b);
        }
        for (Map.Entry<String, StepView> entry : stepsB.entrySet()) {
            if (!stepsA.containsKey(entry.getKey())) {
                step(prefix + entry.getKey(), null, entry.getValue(), a, b);
            }
        }
    }

    /** The steps of a run by what they are matched by, in order. */
    private static Map<String, StepView> keyed(RunView view) {
        Map<String, StepView> keyed = new LinkedHashMap<>();
        if (view == null) {
            return keyed;
        }
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (StepView step : view.steps()) {
            String name = step.name() == null ? "(unnamed, position " + step.position() + ")" : step.name();
            int occurrence = seen.merge(name, 1, Integer::sum);
            keyed.put(occurrence == 1 ? name : name + " #" + occurrence, step);
        }
        return keyed;
    }

    private void step(String label, StepView a, StepView b, RunView runA, RunView runB) {
        boolean ai = isAi(a) || isAi(b);
        BlobRef outputA = a == null ? null : a.output();
        BlobRef outputB = b == null ? null : b.output();
        Output output = Output.of(outputA, outputB);
        String statusA = a == null ? null : a.shownStatus();
        String statusB = b == null ? null : b.shownStatus();
        String after = aiOutputDiffered ? " (after an AI output that differed)" : "";
        List<Item> items = items(a, b);
        // What this step answered excuses the prompts of later steps, not its own: it counts after its calls.
        boolean ownOutputDiffered = false;
        if (a == null || b == null) {
            if (ai) {
                ownOutputDiffered = true;
            } else {
                differences.add("step " + label + ": only in run " + (a == null ? "B" : "A") + after);
            }
        } else if (ai) {
            if (!statusA.equals(statusB) || output.differs()) {
                ownOutputDiffered = true;   // also for an AI step without a call of its own, such as a wrapper
            }
        } else {
            if (!statusA.equals(statusB)) {
                differences.add("step " + label + ": status " + statusA + " in A, " + statusB + " in B" + after);
            }
            if (output.differs()) {
                differences.add("step " + label + ": the output differs" + after);
            }
            for (Item item : items) {
                if (!item.same()) {
                    differences.add("step " + label + ", item " + item.index() + ": " + (item.statusA() == null
                            ? "only in run B" : item.statusB() == null ? "only in run A" : "differs") + after);
                }
            }
        }
        List<AiCall> calls = calls(label, a, b);
        aiOutputDiffered |= ownOutputDiffered;
        steps.add(new Step(label, ai, statusA, statusB, output, outputA, outputB, calls, items));
        children(label, a, b, runA, runB);
    }

    private static boolean isAi(StepView step) {
        return step != null && (step.ai() || !step.aiCalls().isEmpty());
    }

    private static List<Item> items(StepView a, StepView b) {
        Map<Integer, ItemView> itemsA = byIndex(a);
        Map<Integer, ItemView> itemsB = byIndex(b);
        Set<Integer> indexes = new TreeSet<>(itemsA.keySet());
        indexes.addAll(itemsB.keySet());
        List<Item> items = new ArrayList<>();
        for (int index : indexes) {
            ItemView ia = itemsA.get(index);
            ItemView ib = itemsB.get(index);
            BlobRef outputA = ia == null ? null : ia.output();
            BlobRef outputB = ib == null ? null : ib.output();
            items.add(new Item(index, ia != null && ib != null && !Output.of(ia.item(), ib.item()).differs(),
                    ia == null ? null : status(ia),
                    ib == null ? null : status(ib), Output.of(outputA, outputB), outputA, outputB));
        }
        return items;
    }

    /** The status of an item; one that never finished has none in the record. */
    private static String status(ItemView item) {
        return item.status() == null ? "(not finished)" : item.status();
    }

    private static Map<Integer, ItemView> byIndex(StepView step) {
        Map<Integer, ItemView> items = new TreeMap<>();
        if (step != null) {
            step.items().forEach(item -> items.put(item.index(), item));
        }
        return items;
    }

    /** The AI calls of a step in both runs, matched by item and order, in the order of run A's. */
    private List<AiCall> calls(String label, StepView a, StepView b) {
        Map<String, AiCallView> callsA = keyedCalls(a);
        Map<String, AiCallView> callsB = keyedCalls(b);
        Set<String> keys = new LinkedHashSet<>(callsA.keySet());
        keys.addAll(callsB.keySet());
        List<AiCall> calls = new ArrayList<>();
        for (String key : keys) {
            AiCallView ca = callsA.get(key);
            AiCallView cb = callsB.get(key);
            BlobRef outputA = answer(ca);
            BlobRef outputB = answer(cb);
            Output output = Output.of(outputA, outputB);
            List<String> setup = new ArrayList<>();
            boolean promptSame = true;
            boolean follows = false;
            if (ca != null && cb != null) {
                differs("provider", ca.provider(), cb.provider(), setup);
                differs("model", ca.model(), cb.model(), setup);
                differs("effort", ca.effort(), cb.effort(), setup);
                differs("tools", ca.tools(), cb.tools(), setup);
                differs("web", web(ca), web(cb), setup);
                differs("system prompt", sha(ca.systemPrompt()), sha(cb.systemPrompt()), setup);
                promptSame = Objects.equals(sha(ca.prompt()), sha(cb.prompt()));
                follows = !promptSame && aiOutputDiffered;
                if (!promptSame && !follows) {
                    setup.add("prompt");
                }
                String answeredA = ca.response() == null ? null : ca.response().model();
                String answeredB = cb.response() == null ? null : cb.response().model();
                if (answeredA != null && answeredB != null && !answeredA.equals(answeredB)) {
                    setup.add("answering model");
                }
                if (!setup.isEmpty()) {
                    differences.add("step " + label + ", " + key + ": the AI setup differs (" + String.join(", ", setup)
                            + ")");
                }
            }
            if (output.differs() || ca == null || cb == null) {
                aiOutputDiffered = true;
            }
            calls.add(new AiCall(key, side(ca), side(cb), setup, promptSame, follows, output, outputA, outputB));
        }
        return calls;
    }

    private static void differs(String name, String a, String b, List<String> setup) {
        if (!Objects.equals(a, b)) {
            setup.add(name);
        }
    }

    private static Map<String, AiCallView> keyedCalls(StepView step) {
        Map<String, AiCallView> keyed = new LinkedHashMap<>();
        if (step == null) {
            return keyed;
        }
        Map<Integer, Integer> perItem = new LinkedHashMap<>();
        for (AiCallView call : step.aiCalls()) {
            int n = perItem.merge(call.item() == null ? -1 : call.item(), 1, Integer::sum);
            keyed.put((call.item() == null ? "" : "item " + call.item() + ", ") + "AI call " + n, call);
        }
        return keyed;
    }

    private static BlobRef answer(AiCallView call) {
        return call == null || call.response() == null ? null : call.response().output();
    }

    private static String sha(BlobRef blob) {
        return blob == null ? null : blob.sha256();
    }

    /** The web mode with the hosts of a fetch; a record from before web tools had none. */
    private static String web(AiCallView call) {
        String mode = call.web() == null ? "none" : call.web();
        return call.webHosts().isEmpty() ? mode : mode + " (" + String.join(", ", call.webHosts()) + ")";
    }

    private static Call side(AiCallView call) {
        if (call == null) {
            return null;
        }
        AiCallView.Response response = call.response();
        return new Call(call.provider(), call.model(), response == null ? null : response.model(), call.effort(),
                call.tools(), web(call), response == null ? null : response.inputTokens(),
                response == null ? null : response.outputTokens(), response == null ? null : response.costUsd());
    }

    /** The child runs the step started, pair by pair: by {@code foreach} item where an item names its run, else in order. */
    private void children(String label, StepView a, StepView b, RunView runA, RunView runB) {
        Map<String, String> childrenA = keyedChildren(a);
        Map<String, String> childrenB = keyedChildren(b);
        Set<String> keys = new LinkedHashSet<>(childrenA.keySet());
        keys.addAll(childrenB.keySet());
        for (String key : keys) {
            RunView childA = childrenA.containsKey(key) ? runA.children().get(childrenA.get(key)) : null;
            RunView childB = childrenB.containsKey(key) ? runB.children().get(childrenB.get(key)) : null;
            if (childA != null || childB != null) {
                steps(label + key + " > ", childA, childB);
            }
        }
    }

    /** The ids of the step's child runs by what they are matched by: {@code [item n]}, or their order. */
    private static Map<String, String> keyedChildren(StepView step) {
        Map<String, String> keyed = new LinkedHashMap<>();
        if (step == null) {
            return keyed;
        }
        Set<String> ofItems = new LinkedHashSet<>();
        for (ItemView item : step.items()) {
            if (item.subRun() != null) {
                keyed.put(" [item " + item.index() + "]", item.subRun());
                ofItems.add(item.subRun());
            }
        }
        int n = 0;
        for (String id : step.childRunIds()) {
            if (!ofItems.contains(id)) {
                n++;
                keyed.put(n == 1 ? "" : " [run " + n + "]", id);
            }
        }
        return keyed;
    }
}
