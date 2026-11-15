package traceworks.core.app;

import traceworks.core.record.RecordedEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a command of a run could write in its checkout, decided from the record alone ({@link Checkouts#writer}):
 * {@code step.command} lines built by hand, so every shape the record can have is seen, a malformed one included.
 */
class CheckoutsTest {

    private static final String CHECKOUT = "/home/anna/.traceworks/workspaces/run-1";
    private static final List<String> PATHS = List.of(CHECKOUT);
    private static final String STANDARD = mount(CHECKOUT, true) + "," + mount("/work/pkg", true) + ","
            + mount("/home/anna/.traceworks/runs/2026-11-05/run-1/scratch", false);

    private static String mount(String host, boolean readOnly) {
        return "{\"host\":\"" + host + "\",\"container\":\"/x\",\"read_only\":" + readOnly + "}";
    }

    /** A {@code step.command} line of run {@code run} (a child run when {@code parent} is set) with these fields. */
    private static RecordedEvent command(String parent, String fields) {
        String line = "{\"seq\":1,\"at\":\"2026-11-05T08:00:00Z\",\"type\":\"step.command\",\"run\":\"child-or-root\","
                + (parent == null ? "" : "\"parent\":\"" + parent + "\",") + "\"prev\":\"0\"," + fields + "}";
        return RecordedEvent.parse(line.getBytes(StandardCharsets.UTF_8));
    }

    private static RecordedEvent command(String fields) {
        return command(null, fields);
    }

    @Test
    void containersThatSawTheCheckoutReadOnly_couldNotWriteInIt() {
        List<RecordedEvent> events = List.of(
                command("\"host\":false,\"mounts\":[" + STANDARD + "]"),
                command("\"host\":false,\"mounts\":[" + STANDARD + "," + mount("/tmp/out", false) + "]"),
                command("\"host\":false,\"mounts\":[" + STANDARD + "," + mount(CHECKOUT + "/src", true) + "]"),
                command("\"host\":false,\"mounts\":[" + mount(CHECKOUT + "-other", false) + "]"));

        assertThat(Checkouts.writer(events, PATHS)).as("a writable mount beside it, not in it").isNull();
        assertThat(Checkouts.writer(List.of(), PATHS)).isNull();
    }

    @Test
    void aCommandOnTheHost_couldWriteInIt_alsoWhenTheRecordDoesNotSay() {
        assertThat(Checkouts.writer(List.of(command("\"host\":true,\"mounts\":[]")), PATHS)).contains("host");
        assertThat(Checkouts.writer(List.of(command("\"mounts\":[" + STANDARD + "]")), PATHS))
                .as("host missing").contains("host");
        assertThat(Checkouts.writer(List.of(command("\"host\":\"no\",\"mounts\":[" + STANDARD + "]")), PATHS))
                .as("host not a boolean").contains("host");
    }

    @Test
    void aWritableMountOfTheCheckout_inItOrAroundIt_couldWriteInIt() {
        for (String host : List.of(CHECKOUT, CHECKOUT + "/", CHECKOUT + "/src", "/home/anna/.traceworks/workspaces")) {
            List<RecordedEvent> events = List.of(command("\"host\":false,\"mounts\":[" + mount(host, false) + "]"));
            assertThat(Checkouts.writer(events, PATHS)).as(host).contains("could write");
        }
    }

    @Test
    void aMountTheRecordDoesNotDescribeFully_countsAsWritable() {
        assertThat(Checkouts.writer(List.of(command("\"host\":false,\"mounts\":[{\"host\":\"" + CHECKOUT
                + "\",\"container\":\"/workspace\"}]")), PATHS)).as("read_only missing").isNotNull();
        assertThat(Checkouts.writer(List.of(command("\"host\":false,\"mounts\":[{\"host\":null,"
                + "\"container\":\"/x\",\"read_only\":false}]")), PATHS)).as("host null").isNotNull();
        assertThat(Checkouts.writer(List.of(command("\"host\":false,\"mounts\":[{\"container\":\"/x\","
                + "\"read_only\":\"false\"}]")), PATHS)).as("host missing, read_only no boolean").isNotNull();
        assertThat(Checkouts.writer(List.of(command("\"host\":false,\"mounts\":[{\"host\":null,"
                + "\"container\":\"/x\",\"read_only\":true}]")), PATHS)).as("read-only, wherever it is").isNull();
    }

    /** A child run (sub-workflow, foreach) works in the same checkout; its commands count as the run's own. */
    @Test
    void aChildRunsCommand_countsLikeTheRunsOwn() {
        List<RecordedEvent> events = List.of(command("\"host\":false,\"mounts\":[" + STANDARD + "]"),
                command("run-1", "\"host\":false,\"mounts\":[" + mount(CHECKOUT, false) + "]"));

        assertThat(Checkouts.writer(events, PATHS)).contains("could write");
    }

    /** The checkout as the start recorded it and as it is now: a writable mount of either counts. */
    @Test
    void everyPathOfTheCheckout_counts() {
        List<RecordedEvent> events = List.of(command("\"host\":false,\"mounts\":[" + mount("/moved/run-1", false)
                + "]"));

        assertThat(Checkouts.writer(events, PATHS)).isNull();
        assertThat(Checkouts.writer(events, List.of(CHECKOUT, "/moved/run-1"))).isNotNull();
    }

    @Test
    void otherEvents_doNotCount() {
        String started = "{\"seq\":0,\"at\":\"2026-11-05T08:00:00Z\",\"type\":\"run.started\",\"run\":\"run-1\","
                + "\"prev\":\"0\",\"host\":true,\"mounts\":[" + mount(CHECKOUT, false) + "]}";

        assertThat(Checkouts.writer(List.of(RecordedEvent.parse(started.getBytes(StandardCharsets.UTF_8))), PATHS))
                .isNull();
    }
}
