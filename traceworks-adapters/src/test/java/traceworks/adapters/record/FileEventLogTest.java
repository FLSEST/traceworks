package traceworks.adapters.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import traceworks.core.record.EventChain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileEventLogTest {

    @TempDir
    Path home;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void append_thenReadAll_roundTrips() {
        try (FileStoredRun run = (FileStoredRun) new FileRunStore(home.resolve("runs")).create("run-1", Instant.now())) {
            run.events().append(b("{\"seq\":0}"));
            run.events().append(b("{\"seq\":1}"));
            assertThat(run.events().readAll()).containsExactly(b("{\"seq\":0}"), b("{\"seq\":1}"));
        }
    }

    /** Review C, L8: verify reads what follows the last line feed, without the writer lock and without cutting it. */
    @Test
    void tail_isWhatFollowsTheLastLineFeed_andStays() throws IOException {
        FileRunStore store = new FileRunStore(home.resolve("runs"));
        try (FileStoredRun run = (FileStoredRun) store.create("run-1", Instant.now())) {
            run.events().append(b("{\"seq\":0}"));
            assertThat(run.events().tail()).isEmpty();
        }
        Path log;
        try (var run = store.open("run-1")) {
            log = Path.of(run.location()).resolve("events.jsonl");
        }
        Files.write(log, b("{\"seq\":1"), java.nio.file.StandardOpenOption.APPEND);
        try (var run = store.open("run-1")) {
            assertThat(run.events().tail()).isEqualTo(b("{\"seq\":1"));
            assertThat(run.events().tail()).isEqualTo(b("{\"seq\":1"));
            assertThat(run.events().readAll()).hasSize(1);
        }
    }

    @Test
    void append_returnsTheLineHash() {
        try (FileStoredRun run = (FileStoredRun) new FileRunStore(home.resolve("runs")).create("run-1", Instant.now())) {
            assertThat(run.events().append(b("abc"))).isEqualTo(EventChain.sha256Hex(b("abc")));
        }
    }

    @Test
    void append_withoutTheWriterLock_isRefused() {
        FileRunStore store = new FileRunStore(home.resolve("runs"));
        store.create("run-1", Instant.now()).close();
        try (var run = store.open("run-1")) {
            assertThatThrownBy(() -> run.events().append(b("x"))).isInstanceOf(IllegalStateException.class);
            assertThat(run.events().readAll()).isEmpty();
        }
    }

    @Test
    void append_withTheInterruptFlagSet_stillWrites() {
        try (var run = new FileRunStore(home.resolve("runs")).create("run-1", Instant.now())) {
            Thread.currentThread().interrupt();
            try {
                run.events().append(b("{\"seq\":0}"));
            } finally {
                assertThat(Thread.interrupted()).isTrue();
            }
            assertThat(run.events().readAll()).containsExactly(b("{\"seq\":0}"));
        }
    }

    @Test
    void readAll_ignoresATornTail() throws IOException {
        try (var run = new FileRunStore(home.resolve("runs")).create("run-1", Instant.now())) {
            run.events().append(b("one"));
            Files.write(Path.of(run.location()).resolve("events.jsonl"), b("tor"),
                    java.nio.file.StandardOpenOption.APPEND);
            assertThat(run.events().readAll()).containsExactly(b("one"));
        }
    }

    @Test
    void discardTornTail_truncatesToTheLastNewline_andReturnsTheTail() throws IOException {
        try (var run = new FileRunStore(home.resolve("runs")).create("run-1", Instant.now())) {
            run.events().append(b("one"));
            Path file = Path.of(run.location()).resolve("events.jsonl");
            Files.write(file, b("tor"), java.nio.file.StandardOpenOption.APPEND);
            assertThat(run.events().discardTornTail()).isEqualTo(b("tor"));
            assertThat(Files.readString(file)).isEqualTo("one\n");
            run.events().append(b("two"));
            assertThat(run.events().readAll()).containsExactly(b("one"), b("two"));
        }
    }

    @Test
    void discardTornTail_withoutATornTail_returnsEmpty() {
        try (var run = new FileRunStore(home.resolve("runs")).create("run-1", Instant.now())) {
            run.events().append(b("one"));
            assertThat(run.events().discardTornTail()).isEmpty();
            assertThat(run.events().readAll()).containsExactly(b("one"));
        }
    }
}
