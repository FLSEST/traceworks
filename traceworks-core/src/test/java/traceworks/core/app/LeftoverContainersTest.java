package traceworks.core.app;

import org.junit.jupiter.api.Test;
import traceworks.core.record.EventChain;
import traceworks.core.record.RecordedEvent;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Whether a run may have left a container, told from its record alone. */
class LeftoverContainersTest {

    private static final String RUN = "00000000-0000-0000-0000-00000000abcd";
    private static final String CHILD = "00000000-0000-0000-0000-0000000000c1";
    private static final String RUNTIME = "\"container_runtime\":{\"name\":\"docker\",\"version\":\"29.1.3\"}";
    private static final String NO_RUNTIME = "\"container_runtime\":null";

    private static RecordedEvent event(String type, String parent, String fields) {
        String line = "{\"seq\":0,\"at\":\"2026-11-12T10:00:00Z\",\"type\":\"" + type + "\",\"run\":\""
                + (parent == null ? RUN : CHILD) + "\"," + (parent == null ? "" : "\"parent\":\"" + parent + "\",")
                + "\"prev\":\"" + EventChain.genesis(RUN) + "\"" + (fields == null ? "" : "," + fields) + "}";
        return RecordedEvent.parse(line.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void mayHaveContainers_ofAnUnfinishedRunThatStartedWithARuntime_isTrue() {
        assertThat(LeftoverContainers.mayHaveContainers(List.of(
                event("run.started", null, RUNTIME),
                event("run.parked", null, null)))).isTrue();
    }

    /** The process that started the run had no runtime, the one that went on with it had one. */
    @Test
    void mayHaveContainers_ofAnUnfinishedRunThatOnlyAResumeRanWithARuntime_isTrue() {
        assertThat(LeftoverContainers.mayHaveContainers(List.of(
                event("run.started", null, NO_RUNTIME),
                event("run.parked", null, null),
                event("run.resumed", null, RUNTIME),
                event("run.parked", null, null)))).isTrue();
        assertThat(LeftoverContainers.mayHaveContainers(List.of(
                event("run.started", null, null),
                event("run.resumed", null, RUNTIME)))).as("a start that says nothing of a runtime").isTrue();
    }

    @Test
    void mayHaveContainers_ofARunThatNeverRanWithARuntime_isFalse() {
        assertThat(LeftoverContainers.mayHaveContainers(List.of(
                event("run.started", null, NO_RUNTIME),
                event("run.parked", null, null),
                event("run.resumed", null, NO_RUNTIME)))).isFalse();
        assertThat(LeftoverContainers.mayHaveContainers(List.of())).isFalse();
    }

    /** Only the start and the resumes say with what a process ran: the same field of another event says nothing. */
    @Test
    void mayHaveContainers_doesNotTakeTheRuntimeFromAnotherEvent() {
        assertThat(LeftoverContainers.mayHaveContainers(List.of(
                event("run.started", null, NO_RUNTIME),
                event("run.parked", null, RUNTIME)))).isFalse();
    }

    /** A finished run's process removed its containers before it recorded the end. */
    @Test
    void mayHaveContainers_ofAFinishedRun_isFalse() {
        assertThat(LeftoverContainers.mayHaveContainers(List.of(
                event("run.started", null, RUNTIME),
                event("run.finished", null, null)))).isFalse();
    }

    /** The events of a child run say nothing of the run itself: neither its end nor its runtime. */
    @Test
    void mayHaveContainers_looksAtTheRunsOwnEventsOnly() {
        assertThat(LeftoverContainers.mayHaveContainers(List.of(
                event("run.started", null, RUNTIME),
                event("run.started", RUN, RUNTIME),
                event("run.finished", RUN, null)))).as("a child that finished").isTrue();
        assertThat(LeftoverContainers.mayHaveContainers(List.of(
                event("run.started", null, NO_RUNTIME),
                event("run.started", RUN, RUNTIME)))).as("a child's start with a runtime").isFalse();
    }
}
