package traceworks.core.record;

import traceworks.core.container.ImageFacts;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import traceworks.core.model.ModelPick;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.AiProviders;
import traceworks.core.port.BlobStore;
import traceworks.core.port.Clock;
import traceworks.core.port.CommandRunner;
import traceworks.core.port.EventLog;
import traceworks.core.port.RunEvent;
import traceworks.core.port.ScratchDirectory;
import traceworks.core.port.RunEvent.ApprovalAnswered;
import traceworks.core.port.RunEvent.ChildRunEvent;
import traceworks.core.port.RunEvent.ForEachItemFinished;
import traceworks.core.port.RunEvent.ForEachItemStarted;
import traceworks.core.port.RunEvent.StepFinished;
import traceworks.core.port.RunEvent.StepStarted;
import traceworks.core.port.RunEvents;
import traceworks.core.record.RecordingCommandRunner.Attribution;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Writes the run record: turns the events of the engine and of the use cases into hash-chained lines of the
 * {@link EventLog}, with all content in the {@link BlobStore}. The run thread and the inbox watcher both write, so
 * every append is {@code synchronized}; an event is serialised completely before the call returns.
 *
 * <p>A recorder can be stopped ({@link #stop}) before the run has ended, when its process is told to end.
 *
 * <p>The record of this recorder ends with the run's {@code run.finished} or {@code run.parked}: nothing is appended
 * after it, so a request that arrives while the run ends cannot slip in behind its end.
 *
 * <p>With a {@link Redactor} ({@link #redacting}), no secret value reaches the record: every string of a line, nested
 * ones included, and every blob are redacted before they are hashed and written, and the listeners see the redacted
 * line. The engine keeps the real values; the record, and everything rendered from it, holds the replacements.
 */
public final class RunRecorder implements RunEvents {

    private static final DateTimeFormatter AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final EventLog log;
    private final Blobs blobs;
    private final Clock clock;
    private final String runId;
    private final EventFields fields;
    private final List<Consumer<RecordedEvent>> listeners = new CopyOnWriteArrayList<>();
    private final List<RunningStep> running = new ArrayList<>();
    private final ScratchFiles scratchFiles;
    /** Set before the first event; read by every thread that writes ({@link #redacting}). */
    private volatile Redactor redactor = Redactor.none();

    private int nextSeq;
    private String head;
    private EventFields.Answer nextAnswer;
    /** The type of the line that ended the record ({@code run.finished} or {@code run.parked}); {@code null} before. */
    private String endedWith;
    /** Whether the record was stopped ({@link #stop}): nothing more is appended, although the run has not ended. */
    private boolean stopped;

    private RunRecorder(EventLog log, BlobStore store, Clock clock, String runId, WorkflowDefinition definition,
                        int nextSeq, String head) {
        this.log = Objects.requireNonNull(log, "log");
        this.blobs = new Blobs(Objects.requireNonNull(store, "blobs"));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runId = Objects.requireNonNull(runId, "runId");
        this.fields = new EventFields(blobs, Objects.requireNonNull(definition, "definition"));
        this.scratchFiles = new ScratchFiles(blobs);
        this.nextSeq = nextSeq;
        this.head = head;
    }

    /** A recorder for a new run: its first event is {@code seq} 0, chained to the genesis. */
    public static RunRecorder start(EventLog log, BlobStore blobs, Clock clock, String runId,
                                    WorkflowDefinition definition) {
        return new RunRecorder(log, blobs, clock, runId, definition, 0, EventChain.genesis(runId));
    }

    /** A recorder that goes on after the events already in the log, in order. */
    public static RunRecorder continueAfter(EventLog log, BlobStore blobs, Clock clock, String runId,
                                            WorkflowDefinition definition, List<RecordedEvent> existing) {
        if (existing.isEmpty()) {
            return start(log, blobs, clock, runId, definition);
        }
        RecordedEvent last = existing.getLast();
        RunRecorder recorder = new RunRecorder(log, blobs, clock, runId, definition, last.seq() + 1,
                EventChain.sha256Hex(last.raw()));
        recorder.scratchFiles.restore(existing);
        return recorder;
    }

    /**
     * Redacts every line and every blob appended from now on with {@code redactor}. Call it before the first event,
     * so that the whole record is redacted.
     *
     * @return this recorder
     */
    public RunRecorder redacting(Redactor redactor) {
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        blobs.redactWith(redactor);
        return this;
    }

    /**
     * Lists the scratch directory after every finished step and records the changes as {@code step.files}. Without
     * this call nothing is listed. A new or changed file in which the recorder's {@link Redactor} finds a value of a
     * secret is listed without a blob and without a hash; one scan of the file decides, and a file without a value
     * is stored as it is.
     *
     * @return this recorder
     */
    public RunRecorder withScratch(ScratchDirectory scratch) {
        scratchFiles.attach(Objects.requireNonNull(scratch, "scratch"));
        return this;
    }

    /** Records {@code run.started}. */
    public void runStarted(RunStart start) {
        ObjectNode f = Blobs.MAPPER.createObjectNode();
        ObjectNode workflow = f.putObject("workflow");
        workflow.put("id", start.workflowId());
        workflow.put("name", start.workflowName());
        ObjectNode pkg = f.putObject("package");
        pkg.put("dir", start.packageDir());
        pkg.put("digest", start.packageDigest());
        pkg.put("git_commit", start.gitCommit());
        pkg.put("git_dirty", start.gitDirty());
        f.set("definition", blobs.of(start.workflowJson()));
        f.set("inputs", blobs.of(start.inputs()));
        if (start.inputsFrom() != null) {
            f.put("inputs_from", start.inputsFrom());
        }
        f.put("trigger", start.trigger() == null ? null : start.trigger().name());
        f.put("actor", start.actor());
        f.put("version", start.version());
        f.put("execution", start.execution());
        if (start.containerRuntime() == null) {
            f.putNull("container_runtime");
        } else {
            ObjectNode runtime = f.putObject("container_runtime");
            runtime.put("name", start.containerRuntime().name());
            runtime.put("version", start.containerRuntime().version());
        }
        f.put("output_limit_mib", start.outputLimitMib());
        f.put("run_output_budget_mib", start.runOutputBudgetMib());
        ObjectNode aiImages = f.putObject("ai_images");
        new TreeMap<>(start.aiImages()).forEach(aiImages::put);
        f.put("ai_network", start.aiNetwork().mode());
        ObjectNode aiHosts = f.putObject("ai_hosts");
        start.aiNetwork().hosts().forEach((cli, hosts) -> hosts.forEach(aiHosts.withArray(cli)::add));
        ArrayNode images = f.putArray("images");
        for (ImageFacts image : start.images()) {
            ObjectNode node = images.addObject();
            node.put("reference", image.reference());
            node.put("base", image.base());
            node.put("description", image.description());
        }
        if (start.seal() == null) {
            f.putNull("seal");
        } else {
            ObjectNode seal = f.putObject("seal");
            seal.put("workflow_id", start.seal().workflowId());
            seal.put("digest", start.seal().digest());
            seal.put("signer", start.seal().signer());
            seal.put("sealed_at", start.seal().sealedAt());
            seal.put("key", start.seal().key());
            start.seal().principals().forEach(seal.putArray("principals")::add);
            seal.put("signature", start.seal().signature());
        }
        if (!start.modelChoices().isEmpty()) {
            // Only for a workflow that declares a choice: every other run.started keeps its bytes
            ObjectNode choices = f.putObject("model_choices");
            for (ModelPick pick : start.modelChoices()) {
                ObjectNode choice = choices.putObject(pick.choice());
                choice.put("option", pick.option());
                choice.put("default", pick.theDefault());
                choice.put("provider", pick.provider());
                choice.put("model", pick.model());
            }
        }
        f.put("workspace", start.workspace());
        if (start.workspaceSource() != null) {
            f.put("workspace_source", start.workspaceSource());
            f.put("workspace_source_sha", start.workspaceSourceSha());
        }
        append(RecordTypes.RUN_STARTED, runId, null, f);
    }

    @Override
    public void emit(RunEvent event) {
        String run = runId;
        String parent = null;
        RunEvent inner = event;
        while (inner instanceof ChildRunEvent child) {
            parent = run;
            run = child.runId();
            inner = child.event();
        }
        // The attribution is taken and the step is tracked under the same lock as the append, so a concurrent writer
        // cannot take or see half of it
        synchronized (this) {
            EventFields.Answer answer = null;
            if (inner instanceof ApprovalAnswered) {
                answer = nextAnswer;
                nextAnswer = null;
            }
            EventFields.Mapped mapped = fields.map(inner, parent == null, answer);
            append(mapped.type(), run, parent, mapped.fields());
            track(run, parent, inner);
        }
        if (inner instanceof StepFinished finished) {
            recordFiles(run, parent, finished.stepIndex());
        }
    }

    /**
     * Lists the scratch directory, which can take long, without the lock of the recorder; only the append takes it.
     */
    private void recordFiles(String run, String parent, int stepIndex) {
        ObjectNode changes = scratchFiles.changes();
        if (changes == null) {
            return;
        }
        ObjectNode f = Blobs.MAPPER.createObjectNode();
        f.put("step", stepIndex);
        f.setAll(changes);
        synchronized (this) {
            if (endedWith == null && !stopped) {
                append(RecordTypes.STEP_FILES, run, parent, f);
            }
        }
    }

    /**
     * Records a use-case event such as {@code run.resumed}. The values are written in the given order; {@code byte[]}
     * values become blobs.
     *
     * @return {@code false}, and nothing is appended, when the record has ended ({@code run.finished} or
     *         {@code run.parked} of the run) or was stopped ({@link #stop})
     */
    public synchronized boolean record(String type, Map<String, Object> values) {
        if (endedWith != null || stopped) {
            return false;
        }
        append(type, runId, null, fields.custom(values));
        return true;
    }

    /**
     * Stops the record where it is: the process is going down and cannot wait for the run. A line being appended now
     * is written whole first; from then on every append fails, so the run's last lines stay what they are and the
     * run is left without an end, to be read as interrupted and resumed. There is no way back.
     */
    public synchronized void stop() {
        stopped = true;
    }

    /** Drops the attribution set by {@link #attributeNextAnswer}, when the answer it was set for was refused. */
    public synchronized void clearAnswerAttribution() {
        nextAnswer = null;
    }

    /** Names who gives the next {@code approval.answered}; it applies to that one event only. */
    public synchronized void attributeNextAnswer(String actor, String source, String requestId, String comment) {
        nextAnswer = new EventFields.Answer(actor, source, requestId, comment);
    }

    /** A command runner that records every command it runs and attributes it to the running step. */
    public CommandRunner recording(CommandRunner delegate) {
        return new RecordingCommandRunner(delegate, this, blobs);
    }

    /**
     * The AI providers, recorded: every call of a provider they resolve writes {@code ai.request}, then (through the
     * recording command runner its CLI runs on) the CLI's command, then one {@code ai.tool_call} per tool call and
     * {@code ai.response}, attributed to the running step.
     */
    public AiProviders recording(AiProviders delegate) {
        return new RecordingAiProviders(delegate, this, blobs);
    }

    /** The values the record redacts, which a cut of the record must keep whole. */
    java.util.Collection<String> keepWhole() {
        return redactor.values();
    }

    /**
     * Calls the listener for every line appended from now on, in order, on the thread that appends it, while that
     * thread holds this recorder. An exception the listener throws is dropped: the line is written, and the other
     * listeners and the recorder go on. A listener that may block hands the event to another thread.
     */
    public void onAppend(Consumer<RecordedEvent> listener) {
        listeners.add(listener);
    }

    /** The hash of the last line (the genesis before the first). */
    public synchronized String head() {
        return head;
    }

    /** The {@code seq} the next event gets. */
    public synchronized int nextSeq() {
        return nextSeq;
    }

    /** Appends a command event, attributed to the step running now, or to {@code where} when it is given. */
    synchronized Attribution appendCommand(String type, ObjectNode commandFields, Attribution where) {
        Attribution a = where != null ? where : currentAttribution();
        ObjectNode f = Blobs.MAPPER.createObjectNode();
        f.put("step", a.step());
        f.put("item", a.item());
        f.setAll(commandFields);
        append(type, a.run(), a.parent(), f);
        return a;
    }

    private Attribution currentAttribution() {
        if (running.isEmpty()) {
            return new Attribution(runId, null, null, null);
        }
        RunningStep top = running.getLast();
        return new Attribution(top.run, top.parent, top.step, top.item);
    }

    private void track(String run, String parent, RunEvent event) {
        switch (event) {
            case StepStarted e -> running.add(new RunningStep(run, parent, e.stepIndex()));
            case StepFinished e -> {
                for (int i = running.size() - 1; i >= 0; i--) {
                    RunningStep step = running.get(i);
                    if (step.run.equals(run) && step.step == e.stepIndex()) {
                        running.remove(i);
                        break;
                    }
                }
            }
            case ForEachItemStarted e -> withStep(run, e.stepIndex(), step -> step.item = e.itemIndex());
            case ForEachItemFinished e -> withStep(run, e.stepIndex(), step -> step.item = null);
            default -> { }
        }
    }

    private void withStep(String run, int stepIndex, Consumer<RunningStep> action) {
        for (int i = running.size() - 1; i >= 0; i--) {
            RunningStep step = running.get(i);
            if (step.run.equals(run) && step.step == stepIndex) {
                action.accept(step);
                return;
            }
        }
    }

    private synchronized void append(String type, String run, String parent, ObjectNode typeFields) {
        if (endedWith != null) {
            throw new IllegalStateException("The record of run " + runId + " has ended with " + endedWith
                    + "; nothing more is appended to it");
        }
        if (stopped) {
            throw new IllegalStateException("The record of run " + runId + " was stopped because its process is"
                    + " ending; nothing more is appended to it");
        }
        ObjectNode line = Blobs.MAPPER.createObjectNode();
        line.put("seq", nextSeq);
        line.put("at", AT.format(clock.now()));
        line.put("type", type);
        line.put("run", run);
        if (parent != null) {
            line.put("parent", parent);
        }
        line.put("prev", head);
        line.setAll(redactor.redactFields(typeFields));
        byte[] bytes = Blobs.json(line);
        String hash = log.append(bytes);
        nextSeq++;
        head = hash;
        if (parent == null && (RecordTypes.RUN_FINISHED.equals(type) || RecordTypes.RUN_PARKED.equals(type))) {
            endedWith = type;
            blobs.forgetCheckpointTexts();
        }
        if (!listeners.isEmpty()) {
            RecordedEvent recorded = RecordedEvent.parse(bytes);
            for (Consumer<RecordedEvent> listener : listeners) {
                try {
                    listener.accept(recorded);
                } catch (RuntimeException e) {
                    // A listener only watches: its failure must not stop the record, nor the listeners after it
                }
            }
        }
    }

    /** A step that has started and not finished, in some run of this record. */
    private static final class RunningStep {
        final String run;
        final String parent;
        final int step;
        Integer item;

        RunningStep(String run, String parent, int step) {
            this.run = run;
            this.parent = parent;
            this.step = step;
        }
    }
}
