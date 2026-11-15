package traceworks.core.record;

import traceworks.core.model.BlockDefinition;
import traceworks.core.model.TriggerSource;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.Clock;
import traceworks.core.port.RunEvent;
import traceworks.core.testing.InMemoryBlobStore;
import traceworks.core.testing.InMemoryEventLog;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Builds records with the real recorder and the in-memory ports. */
final class RecordFixture {

    static final String RUN = "00000000-0000-0000-0000-000000000001";

    final InMemoryEventLog log = new InMemoryEventLog();
    final InMemoryBlobStore blobs = new InMemoryBlobStore();
    private final AtomicLong tick = new AtomicLong();
    final Clock clock = () -> Instant.parse("2026-11-03T12:00:00Z").plusMillis(tick.incrementAndGet() * 10);
    RunRecorder recorder = RunRecorder.start(log, blobs, clock, RUN, definition());

    static WorkflowDefinition definition() {
        BlockDefinition shell = BlockDefinition.builder().type("shell").name("build")
                .config(Map.of("command", "echo {{ ticket }}")).build();
        return new WorkflowDefinition("wf", "Wf", null, List.of(shell), null, null, null, null, null, null, null,
                null, null);
    }

    RecordFixture started() {
        return started("host");
    }

    /** A run that started with this execution target ({@code host} or {@code container}). */
    RecordFixture started(String execution) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("ticket", "T-1");
        recorder.runStarted(new RunStart("wf", "Wf", "/pkg", "digest-1", null, null,
                "{\"id\":\"wf\"}".getBytes(StandardCharsets.UTF_8), inputs, TriggerSource.MANUAL, "flo@box",
                "0.1.0", execution, null, "/ws"));
        return this;
    }

    /** Another process goes on writing the record, as {@code approve} or {@code resume} does after a park. */
    RecordFixture nextWriter() {
        recorder = RunRecorder.continueAfter(log, blobs, clock, RUN, definition(), events());
        return this;
    }

    RecordFixture emit(RunEvent... events) {
        for (RunEvent event : events) {
            recorder.emit(event);
        }
        return this;
    }

    List<byte[]> lines() {
        return log.readAll();
    }

    List<RecordedEvent> events() {
        return lines().stream().map(RecordedEvent::parse).toList();
    }
}
