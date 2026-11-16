package traceworks.core.record;

import com.fasterxml.jackson.databind.JsonNode;
import traceworks.core.container.ImageFacts;
import traceworks.core.model.ModelPick;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepStatus;
import traceworks.core.port.BlobRef;
import traceworks.core.port.RunEvent.RunParked;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/** Folds the events of a record, in order, into a {@link RunView}. */
final class RunViewBuilder {

    private final Map<String, RunAcc> runs = new LinkedHashMap<>();
    private final List<RunView.Intervention> interventions = new ArrayList<>();
    private final String rootId;
    private final int eventCount;
    private final String head;

    private RunViewBuilder(List<RecordedEvent> events) {
        rootId = events.getFirst().run();
        eventCount = events.size();
        head = EventChain.sha256Hex(events.getLast().raw());
    }

    static RunView build(List<RecordedEvent> events, boolean writerAlive) {
        if (events.isEmpty()) {
            throw new IllegalArgumentException("A run view needs at least one event");
        }
        RunViewBuilder builder = new RunViewBuilder(events);
        for (RecordedEvent event : events) {
            builder.apply(event);
        }
        return builder.view(builder.runs.get(builder.rootId), writerAlive);
    }

    // ---- folding ----

    /**
     * Checks the parent of a run's first event: the root has none, and a child names a run the record has already
     * shown. A run is then never its own parent or ancestor, and walking from a run to its children ends.
     *
     * @throws IllegalArgumentException for a record the recorder does not write
     */
    private void requireParent(RecordedEvent e) {
        String what = "Event " + e.type() + " (seq " + e.seq() + ") of run " + e.run();
        if (e.run().equals(rootId)) {
            if (e.parent() != null) {
                throw new IllegalArgumentException(what + ", the run of the record, names a parent: " + e.parent());
            }
        } else if (e.parent() != null && !runs.containsKey(e.parent())) {
            // Covers a run that names itself too: it is not known before its first event
            throw new IllegalArgumentException(what + " names a parent the record has not shown before: "
                    + e.parent());
        }
    }

    private void apply(RecordedEvent e) {
        RunAcc run = runs.get(e.run());
        if (run == null) {
            requireParent(e);
            run = new RunAcc(e.run(), e.parent(), e.at());
            runs.put(e.run(), run);
            RunAcc parent = e.parent() == null ? null : runs.get(e.parent());
            if (parent != null && parent.openStep != null) {
                parent.steps.get(parent.openStep).childRunIds.add(e.run());
            }
        } else if (!Objects.equals(run.parent, e.parent())) {
            // Otherwise a run.finished of the root with a parent would end the run here and for nobody else
            throw new IllegalArgumentException("Event " + e.type() + " (seq " + e.seq() + ") of run " + e.run()
                    + " names the parent " + e.parent() + ", the run's first event " + run.parent);
        }
        run.events++;
        requireFields(e);
        Integer stepIndex = e.step();
        StepAcc step = stepIndex == null ? null : run.steps.get(stepIndex);
        switch (e.type()) {
            case RecordTypes.RUN_STARTED -> {
                run.startedAt = e.at();
                run.workflowId = text(e.fields().path("workflow"), "id");
                run.workflowName = text(e.fields().path("workflow"), "name");
                run.packageDir = text(e.fields().path("package"), "dir");
                run.packageDigest = text(e.fields().path("package"), "digest");
                run.packageCommit = text(e.fields().path("package"), "git_commit");
                JsonNode dirty = e.fields().path("package").path("git_dirty");
                run.packageDirty = dirty.isBoolean() ? dirty.asBoolean() : null;
                run.actor = text(e.fields(), "actor");
                run.trigger = text(e.fields(), "trigger");
                run.execution = text(e.fields(), "execution");
                run.definition = e.blob("definition");
                run.aiNetwork = text(e.fields(), "ai_network");
                run.seal = seal(e.fields().path("seal"));
                run.register = register(e.fields().path("register"));
                run.ledgerStart = e.fields().path("ledger_start").asBoolean(false);
                for (Map.Entry<String, JsonNode> choice : e.fields().path("model_choices").properties()) {
                    JsonNode pick = choice.getValue();
                    run.modelChoices.add(new ModelPick(choice.getKey(), text(pick, "option"),
                            text(pick, "provider"), text(pick, "model"), pick.path("default").asBoolean()));
                }
                for (Map.Entry<String, JsonNode> cli : e.fields().path("ai_hosts").properties()) {
                    List<String> hosts = new ArrayList<>();
                    cli.getValue().forEach(host -> hosts.add(host.asText()));
                    run.aiHosts.put(cli.getKey(), hosts);
                }
                for (JsonNode image : e.fields().path("images")) {
                    run.images.add(new ImageFacts(text(image, "reference"), text(image, "base"),
                            text(image, "description")));
                }
                run.inputs = e.blob("inputs");
            }
            case RecordTypes.STEP_STARTED -> {
                StepAcc previous = run.steps.get(e.step());
                StepAcc started = new StepAcc(e.step(), previous == null ? 1 : previous.attempts + 1);
                started.startedAt = e.at();
                if (previous != null) {
                    started.earlier.addAll(previous.earlier);
                    started.earlier.add(new StepView.Attempt(previous.attempts, previous.startedAt,
                            previous.commands, previous.questions, previous.aiCalls));
                }
                started.name = text(e.fields(), "name");
                started.blockType = text(e.fields(), "block_type");
                started.position = e.fields().path("position").asInt();
                started.ai = "ai".equals(text(e.fields(), "mode"));
                e.fields().path("uses").forEach(use -> started.uses.add(use.asText()));
                run.steps.put(e.step(), started);
                run.openStep = e.step();
            }
            case RecordTypes.STEP_WAITING -> {
                if (step != null) {
                    step.waiting = e.fields().path("waiting").asBoolean();
                }
            }
            case RecordTypes.STEP_COMMAND -> {
                if (step != null) {
                    step.commands.add(command(e));
                }
            }
            case RecordTypes.STEP_COMMAND_FINISHED -> {
                if (step != null) {
                    finishCommand(step, e);
                }
            }
            case RecordTypes.AI_REQUEST -> {
                if (step != null) {
                    step.aiCalls.add(new AiCallView(e.seq(), item(e), text(e.fields(), "provider"),
                            text(e.fields(), "model"), text(e.fields(), "effort"), text(e.fields(), "tools"),
                            text(e.fields(), "web"), webHosts(e.fields()),
                            e.blob("prompt"), e.blob("system_prompt"), List.of(), null));
                }
            }
            case RecordTypes.AI_TOOL_CALL -> {
                if (step != null) {
                    openAiCall(step, e, call -> call.withToolCall(new AiCallView.ToolCallView(e.seq(),
                            text(e.fields(), "tool"), e.blob("arguments"), e.blob("result"),
                            e.fields().path("error").asBoolean())));
                }
            }
            case RecordTypes.AI_RESPONSE -> {
                if (step != null) {
                    JsonNode f = e.fields();
                    openAiCall(step, e, call -> call.withResponse(new AiCallView.Response(text(f, "model"),
                            sentTokens(f), integer(f, "output_tokens"),
                            f.path("cost_usd").isNumber() ? f.path("cost_usd").doubleValue() : null,
                            integer(f, "num_turns"), integer(f, "exit_code"), e.blob("output"), e.blob("error"))));
                }
            }
            case RecordTypes.STEP_OUTPUT -> {
                if (step != null) {
                    step.output = e.blob("output");
                }
            }
            case RecordTypes.STEP_FILES -> {
                if (step != null) {
                    step.files = files(e);
                }
            }
            case RecordTypes.GATE_REPORTED -> {
                if (step != null) {
                    step.gate = new GateView(text(e.fields(), "status"), text(e.fields(), "category"),
                            e.blob("description"), null);
                }
            }
            case RecordTypes.APPROVAL_REQUESTED -> {
                if (step != null) {
                    step.questions.add(QuestionView.of(e));
                }
            }
            case RecordTypes.APPROVAL_ANSWERED -> answered(run, step, e);
            case RecordTypes.FOREACH_ITEM_STARTED -> {
                if (step != null) {
                    step.items.put(e.fields().path("item_index").asInt(), new ItemView(
                            e.fields().path("item_index").asInt(), e.fields().path("total").asInt(),
                            e.blob("item"), null, null, null, null));
                }
            }
            case RecordTypes.FOREACH_ITEM_FINISHED -> {
                if (step != null) {
                    int index = e.fields().path("item_index").asInt();
                    ItemView started = step.items.get(index);
                    // An item a resumed loop took over has no foreach.item.started: its line names the item itself
                    JsonNode from = e.fields().path("resumed_from");
                    step.items.put(index, new ItemView(index, e.fields().path("total").asInt(),
                            from.isInt() || started == null ? e.blob("item") : started.item(),
                            text(e.fields(), "status"), e.blob("output"), text(e.fields(), "sub_run"),
                            from.isInt() ? from.intValue() : null));
                    String subRun = text(e.fields(), "sub_run");
                    if (subRun != null && !step.childRunIds.contains(subRun)) {
                        step.childRunIds.add(subRun);
                    }
                }
            }
            case RecordTypes.RUN_STATUS -> run.lastStatusEvent = text(e.fields(), "status");
            case RecordTypes.RUN_CHECKPOINT -> run.checkpoint = new RunView.Checkpoint(e.seq(),
                    e.fields().path("next_position").asInt(), e.blob("state"));
            case RecordTypes.STEP_FINISHED -> {
                if (step != null) {
                    step.finished = true;
                    step.status = status(StepStatus.class, e);
                    step.durationMs = e.fields().path("duration_ms").asLong();
                    step.input = e.blob("input");
                    step.metadata = e.blob("metadata");
                    if (stepIndex.equals(run.openStep)) {
                        run.openStep = null;
                    }
                }
            }
            case RecordTypes.RUN_PARKED -> {
                run.parkedBy = text(e.fields(), "kind");
                if (stepIndex == null && !RunParked.PAUSE.equals(run.parkedBy)) {
                    // Only a pause between two blocks parks at no step: a question is always a step's
                    throw new IllegalArgumentException("The event " + e.seq() + " (" + e.type()
                            + ") lacks the int field step");
                }
                run.parkedSeq = e.seq();
                run.parkedStep = stepIndex;
                run.resumedAfterPark = false;
                run.parkedAnswer = null;
            }
            case RecordTypes.RUN_RESUMED -> {
                run.resumedAfterPark = true;
                intervention(e, text(e.fields(), "actor"), resumed(e.fields()), null);
            }
            case RecordTypes.RUN_PAUSE_REQUESTED -> intervention(e, text(e.fields(), "actor"), "requested a pause"
                    + (text(e.fields(), "request") == null ? "" : " (request " + text(e.fields(), "request") + ")"),
                    null);
            case RecordTypes.RUN_CANCEL_REQUESTED -> intervention(e, text(e.fields(), "actor"), "requested a cancel"
                    + (text(e.fields(), "request") == null ? "" : " (request " + text(e.fields(), "request") + ")"),
                    null);
            case RecordTypes.RUN_RECOVERED -> intervention(e, null, "a writer found a torn last line, set it aside"
                    + " and went on", e.blob("discarded"));
            case RecordTypes.REQUEST_REFUSED -> intervention(e, text(e.fields(), "actor"), "a request ("
                    + text(e.fields(), "kind") + ", " + text(e.fields(), "request") + ") was refused: "
                    + text(e.fields(), "reason"), null);
            case RecordTypes.RUN_FINISHED -> {
                run.finishedAt = e.at();
                run.finishedStatus = status(RunStatus.class, e);
                run.error = e.blob("error");
                JsonNode gate = e.fields().path("gate");
                run.gate = gate.isObject() ? new GateView(text(gate, "status"), text(gate, "category"),
                        e.blob("gate_description"), e.blob("gate_summary")) : null;
            }
            default -> { }
        }
    }

    private static StepFiles files(RecordedEvent e) {
        List<FileView> files = new ArrayList<>();
        e.fields().path("files").forEach(f -> {
            files.add(new FileView(text(f, "path"), f.path("size").asLong(), text(f, "sha256"),
                    RecordedEvent.reference(f.get("blob")), text(f, "reason")));
        });
        List<StepFiles.Link> links = new ArrayList<>();
        e.fields().path("links").forEach(l -> links.add(new StepFiles.Link(text(l, "path"), text(l, "target"))));
        List<StepFiles.Unreadable> unreadable = new ArrayList<>();
        e.fields().path("unreadable").forEach(u -> unreadable.add(new StepFiles.Unreadable(text(u, "path"),
                text(u, "reason"))));
        List<String> removed = new ArrayList<>();
        e.fields().path("removed").forEach(r -> removed.add(r.asText()));
        return new StepFiles(files, links, unreadable, removed, e.fields().path("truncated").asBoolean());
    }

    private static CommandView command(RecordedEvent e) {
        List<CommandView.Mount> mounts = new ArrayList<>();
        e.fields().path("mounts").forEach(m -> mounts.add(new CommandView.Mount(text(m, "host"),
                text(m, "container"), m.path("read_only").asBoolean())));
        List<String> envNames = new ArrayList<>();
        e.fields().path("env_names").forEach(n -> envNames.add(n.asText()));
        List<String> networkHosts = new ArrayList<>();
        e.fields().path("network_hosts").forEach(h -> networkHosts.add(h.asText()));
        JsonNode item = e.fields().path("item");
        return new CommandView(e.seq(), item.isInt() ? item.intValue() : null, e.blob("command"), e.blob("values"),
                text(e.fields(), "working_dir"), text(e.fields(), "image"), text(e.fields(), "image_id"), mounts,
                network(e.fields()), networkHosts,
                envNames, e.fields().path("host").asBoolean(), null);
    }

    /** The {@code web_hosts} of an AI request; empty for a call that fetches nothing. */
    private static List<String> webHosts(JsonNode fields) {
        List<String> hosts = new ArrayList<>();
        fields.path("web_hosts").forEach(host -> hosts.add(host.asText()));
        return hosts;
    }

    /**
     * The network of a command: {@code none}, {@code open} or {@code hosts}. A record from before the egress
     * allowlist says {@code true} or {@code false}, which is {@code open} or {@code none}.
     */
    static String network(JsonNode fields) {
        JsonNode network = fields.path("network");
        return network.isBoolean() ? network.booleanValue() ? "open" : "none" : text(fields, "network");
    }

    /** The foreach item of an event; {@code null} outside an item. */
    private static Integer item(RecordedEvent e) {
        JsonNode item = e.fields().path("item");
        return item.isInt() ? item.intValue() : null;
    }

    /**
     * The input tokens of an {@code ai.response}, as what was sent: the input the prompt cache did not serve
     * ({@code input_tokens}) plus the cached part ({@code cache_read_input_tokens},
     * {@code cache_creation_input_tokens}). A record of an earlier version has no cache fields, and a CLI may report
     * none: then it is {@code input_tokens} alone. {@code null} when the record has none of the three.
     */
    private static Integer sentTokens(JsonNode f) {
        Integer sent = null;
        for (String field : List.of("input_tokens", "cache_read_input_tokens", "cache_creation_input_tokens")) {
            Integer part = integer(f, field);
            if (part != null) {
                sent = (int) Math.min(Integer.MAX_VALUE, (sent == null ? 0L : sent) + part);
            }
        }
        return sent;
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isInt() ? value.intValue() : null;
    }

    /** Applies {@code change} to the step's last AI call of the event's item that has no response yet. */
    private static void openAiCall(StepAcc step, RecordedEvent e, UnaryOperator<AiCallView> change) {
        Integer itemIndex = item(e);
        for (int i = step.aiCalls.size() - 1; i >= 0; i--) {
            AiCallView candidate = step.aiCalls.get(i);
            if (candidate.response() == null && Objects.equals(candidate.item(), itemIndex)) {
                step.aiCalls.set(i, change.apply(candidate));
                return;
            }
        }
    }

    private static void finishCommand(StepAcc step, RecordedEvent e) {
        JsonNode item = e.fields().path("item");
        Integer itemIndex = item.isInt() ? item.intValue() : null;
        for (int i = step.commands.size() - 1; i >= 0; i--) {
            CommandView candidate = step.commands.get(i);
            if (candidate.result() == null && Objects.equals(candidate.item(), itemIndex)) {
                JsonNode exit = e.fields().path("exit_code");
                List<CommandView.Connection> egress = new ArrayList<>();
                e.fields().path("egress").forEach(c -> egress.add(new CommandView.Connection(text(c, "target"),
                        text(c, "verdict"), c.path("count").asInt())));
                step.commands.set(i, candidate.withResult(new CommandView.Result(
                        exit.isInt() ? exit.intValue() : null, e.fields().path("duration_ms").asLong(),
                        e.fields().path("timed_out").asBoolean(), egress)));
                return;
            }
        }
    }

    /** A resume: where it went on, which step runs again, which answer it uses, whether the package changed. */
    private static String resumed(JsonNode f) {
        StringBuilder text = new StringBuilder("resumed the run");
        if (f.path("from_checkpoint").isInt()) {
            text.append(" from the checkpoint at seq ").append(f.path("from_checkpoint").intValue());
        }
        if (f.path("next_position").isInt()) {
            text.append(", going on at position ").append(f.path("next_position").intValue());
        }
        if (f.path("rerun_step").isInt()) {
            text.append("; step ").append(f.path("rerun_step").intValue()).append(" runs again");
        }
        if (f.path("answer").isInt()) {
            text.append("; with the answer recorded at seq ").append(f.path("answer").intValue());
        }
        if (f.path("package_changed").asBoolean(false)) {
            text.append("; the package changed since the start (digest now ")
                    .append(text(f, "package_digest")).append(')');
        }
        return text.toString();
    }

    private void intervention(RecordedEvent e, String actor, String description, BlobRef content) {
        interventions.add(new RunView.Intervention(e.seq(), e.at(), e.type(), actor, description, content));
    }

    private void answered(RunAcc run, StepAcc step, RecordedEvent e) {
        String source = text(e.fields(), "source");
        // A resumed step receives the answer given while the run was parked; it shows who gave it, when and why
        QuestionView.Answer given = "resume".equals(source) && run.parkedAnswer != null && e.step() != null
                && e.step() == run.parkedAnswer.step() ? run.parkedAnswer : null;
        QuestionView.Answer answer = new QuestionView.Answer(e.seq(), e.step() == null ? -1 : e.step(),
                text(e.fields(), "kind"), e.blob("answer"), text(e.fields(), "actor"), source,
                text(e.fields(), "request"), e.blob("comment"), e.at(), given);
        if ("cli".equals(source) || "inbox".equals(source)) {
            intervention(e, answer.actor(), "answered step " + answer.step()
                    + (run.runId.equals(rootId) ? "" : " of child run " + run.runId)
                    + " (" + answer.kind() + ") via " + source, answer.answer());
        }
        if (step != null) {
            for (int i = step.questions.size() - 1; i >= 0; i--) {
                if (step.questions.get(i).answered() == null) {
                    step.questions.set(i, step.questions.get(i).withAnswer(answer));
                    break;
                }
            }
        }
        if (run.parkedSeq >= 0 && !run.resumedAfterPark && e.step() != null && e.step().equals(run.parkedStep)) {
            run.parkedAnswer = answer;
        }
    }

    // ---- result ----

    private RunView view(RunAcc run, boolean writerAlive) {
        RunStatus status = status(run, writerAlive);
        List<StepView> steps = new ArrayList<>();
        for (StepAcc s : run.steps.values()) {
            boolean open = !s.finished;
            // A step still open when the run ended CANCELLED (a parked or interrupted run that was cancelled) never
            // finished, and the record says so; the view derives CANCELLED from run.finished, no event rewrites it
            StepStatus stepStatus = !open ? s.status : status == RunStatus.CANCELLED ? StepStatus.CANCELLED
                    : s.waiting ? StepStatus.WAITING_APPROVAL : StepStatus.RUNNING;
            steps.add(new StepView(s.index, s.name, s.blockType, s.position, s.ai, stepStatus,
                    open && status == RunStatus.INTERRUPTED, open && status == RunStatus.CANCELLED, s.durationMs, s.attempts, s.uses, s.commands, s.input,
                    s.output, s.metadata, s.gate, s.questions, new ArrayList<>(s.items.values()), s.childRunIds,
                    s.earlier, s.files, s.aiCalls));
        }
        QuestionView pending = null;
        if (status == RunStatus.PARKED && run.parkedStep != null && !RunParked.PAUSE.equals(run.parkedBy)) {
            StepAcc parked = run.steps.get(run.parkedStep);
            if (parked != null && !parked.questions.isEmpty()) {
                pending = parked.questions.getLast();
            }
        }
        Map<String, RunView> children = new LinkedHashMap<>();
        for (RunAcc child : runs.values()) {
            if (run.runId.equals(child.parent)) {
                children.put(child.runId, view(child, writerAlive));
            }
        }
        boolean root = run.runId.equals(rootId);
        return new RunView(run.runId, run.workflowId, run.workflowName, run.packageDir, run.packageDigest,
                run.packageCommit, run.packageDirty, run.actor,
                run.trigger, run.execution, run.startedAt, run.finishedAt, status, run.error, run.gate, steps, pending,
                status == RunStatus.PARKED ? run.parkedAnswer : null, run.checkpoint, run.definition, run.inputs,
                children, root ? eventCount : run.events, root ? head : null, root ? interventions : List.of(),
                run.images, run.aiNetwork, run.aiHosts, status == RunStatus.PARKED ? run.parkedBy : null, run.seal,
                run.modelChoices, run.register, root && run.ledgerStart);
    }

    /** What the register handed the run, as {@code run.started} records it; {@code null} when it records none. */
    private static RegisterHandover register(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        List<RegisterHandover.File> files = new ArrayList<>();
        node.path("files").forEach(file -> files.add(new RegisterHandover.File(text(file, "name"),
                text(file, "sha256"), file.path("size").asLong())));
        return new RegisterHandover(text(node, "key"), node.path("seq").isInt() ? node.path("seq").asInt() : null,
                text(node, "head"), files);
    }

    /** The seal {@code run.started} records; {@code null} when it records none. */
    private static SealView seal(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        List<String> principals = new ArrayList<>();
        node.path("principals").forEach(principal -> principals.add(principal.asText()));
        return new SealView(text(node, "workflow_id"), text(node, "digest"), text(node, "signer"),
                text(node, "sealed_at"), text(node, "key"), principals, text(node, "signature"));
    }

    private static RunStatus status(RunAcc run, boolean writerAlive) {
        if (run.finishedStatus != null) {
            return run.finishedStatus;
        }
        if (run.parkedSeq >= 0 && !run.resumedAfterPark) {
            return RunStatus.PARKED;
        }
        if (writerAlive) {
            return "PAUSED".equals(run.lastStatusEvent) ? RunStatus.PAUSED : RunStatus.RUNNING;
        }
        return RunStatus.INTERRUPTED;
    }

    private enum Kind { INT, TEXT, BOOL, OBJECT }

    private record Field(String name, Kind kind) { }

    private static Field int_(String name) {
        return new Field(name, Kind.INT);
    }

    private static Field text_(String name) {
        return new Field(name, Kind.TEXT);
    }

    /** The fields the view reads without a fallback, by event type; every other field it reads may be absent. */
    private static final Map<String, List<Field>> REQUIRED = Map.ofEntries(
            Map.entry(RecordTypes.RUN_STARTED, List.of(new Field("workflow", Kind.OBJECT),
                    new Field("package", Kind.OBJECT))),
            Map.entry(RecordTypes.STEP_STARTED, List.of(int_("step"), int_("position"),
                    text_("block_type"))),
            Map.entry(RecordTypes.STEP_WAITING, List.of(int_("step"), new Field("waiting", Kind.BOOL))),
            Map.entry(RecordTypes.STEP_COMMAND, List.of(int_("step"))),
            Map.entry(RecordTypes.STEP_COMMAND_FINISHED, List.of(int_("step"))),
            Map.entry(RecordTypes.AI_REQUEST, List.of(int_("step"))),
            Map.entry(RecordTypes.AI_TOOL_CALL, List.of(int_("step"))),
            Map.entry(RecordTypes.AI_RESPONSE, List.of(int_("step"))),
            Map.entry(RecordTypes.STEP_OUTPUT, List.of(int_("step"))),
            Map.entry(RecordTypes.STEP_FILES, List.of(int_("step"))),
            Map.entry(RecordTypes.GATE_REPORTED, List.of(int_("step"), text_("status"))),
            Map.entry(RecordTypes.APPROVAL_REQUESTED, List.of(int_("step"))),
            Map.entry(RecordTypes.FOREACH_ITEM_STARTED, List.of(int_("step"), int_("item_index"), int_("total"))),
            Map.entry(RecordTypes.FOREACH_ITEM_FINISHED, List.of(int_("step"), int_("item_index"), int_("total"))),
            Map.entry(RecordTypes.RUN_CHECKPOINT, List.of(int_("next_position"))),
            Map.entry(RecordTypes.STEP_FINISHED, List.of(int_("step"), text_("status"), int_("duration_ms"))),
            Map.entry(RecordTypes.RUN_FINISHED, List.of(text_("status"))));

    /**
     * Checks the fields the view needs of an event of this type: a record that lacks one or has it of another type
     * is damaged, not a bug of the view.
     *
     * @throws IllegalArgumentException naming the event type, its seq and the field
     */
    private static void requireFields(RecordedEvent e) {
        for (Field field : REQUIRED.getOrDefault(e.type(), List.of())) {
            JsonNode value = e.fields().get(field.name());
            boolean ok = value != null && switch (field.kind()) {
                case INT -> value.isIntegralNumber();
                case TEXT -> value.isTextual();
                case BOOL -> value.isBoolean();
                case OBJECT -> value.isObject();
            };
            if (!ok) {
                throw new IllegalArgumentException("The event " + e.seq() + " (" + e.type() + ") lacks the "
                        + field.kind().name().toLowerCase(Locale.ROOT) + " field " + field.name());
            }
        }
    }

    /** The {@code status} field as the enum; an unknown value is bad input ({@code requireFields} saw it is there). */
    private static <E extends Enum<E>> E status(Class<E> type, RecordedEvent e) {
        return Enum.valueOf(type, text(e.fields(), "status"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asText();
    }

    // ---- accumulators ----

    private static final class RunAcc {
        String aiNetwork;
        final Map<String, List<String>> aiHosts = new TreeMap<>();
        final String runId;
        final String parent;
        final TreeMap<Integer, StepAcc> steps = new TreeMap<>();
        int events;
        Instant startedAt;
        Instant finishedAt;
        String workflowId;
        String workflowName;
        String packageDir;
        String packageDigest;
        SealView seal;
        RegisterHandover register;
        boolean ledgerStart;
        final List<ModelPick> modelChoices = new ArrayList<>();
        String packageCommit;
        final List<ImageFacts> images = new ArrayList<>();
        Boolean packageDirty;
        String actor;
        String trigger;
        String execution;
        BlobRef definition;
        BlobRef inputs;
        BlobRef error;
        GateView gate;
        RunStatus finishedStatus;
        String lastStatusEvent;
        RunView.Checkpoint checkpoint;
        int parkedSeq = -1;
        Integer parkedStep;
        String parkedBy;
        boolean resumedAfterPark;
        QuestionView.Answer parkedAnswer;
        Integer openStep;

        RunAcc(String runId, String parent, Instant firstAt) {
            this.runId = runId;
            this.parent = parent;
            this.startedAt = firstAt;
        }
    }

    private static final class StepAcc {
        final int index;
        final int attempts;
        Instant startedAt;
        final List<StepView.Attempt> earlier = new ArrayList<>();
        String name;
        String blockType;
        int position;
        boolean ai;
        boolean waiting;
        boolean finished;
        StepStatus status;
        long durationMs;
        final List<String> uses = new ArrayList<>();
        final List<CommandView> commands = new ArrayList<>();
        final List<AiCallView> aiCalls = new ArrayList<>();
        final List<QuestionView> questions = new ArrayList<>();
        final TreeMap<Integer, ItemView> items = new TreeMap<>();
        final List<String> childRunIds = new ArrayList<>();
        BlobRef input;
        BlobRef output;
        BlobRef metadata;
        GateView gate;
        StepFiles files;

        StepAcc(int index, int attempts) {
            this.index = index;
            this.attempts = attempts;
        }
    }
}
