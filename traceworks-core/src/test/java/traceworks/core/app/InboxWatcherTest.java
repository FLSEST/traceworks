package traceworks.core.app;

import traceworks.core.port.InboxRequest;
import traceworks.core.testing.InMemoryRunStore.InMemoryInbox;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InboxWatcherTest {

    /** A request that cannot be handled is reported, and the requests taken with it are still handled. */
    @Test
    void aRequestThatFails_isReported_andTheRestOfTheBatchIsHandled() throws Exception {
        InMemoryInbox inbox = new InMemoryInbox();
        inbox.post(request("a"));
        inbox.post(request("b"));
        List<String> handled = new CopyOnWriteArrayList<>();
        List<String> failed = new CopyOnWriteArrayList<>();
        CountDownLatch both = new CountDownLatch(2);
        InboxWatcher watcher = new InboxWatcher(inbox, request -> {
            if (request.id().equals("a")) {
                throw new IllegalStateException("boom");
            }
            handled.add(request.id());
            both.countDown();
        }, (request, e) -> {
            failed.add(request.id() + ": " + e.getMessage());
            both.countDown();
        }, "traceworks-inbox-test");

        watcher.start();
        try {
            assertThat(both.await(AppFixture.WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        } finally {
            watcher.close();
        }

        assertThat(failed).containsExactly("a: boom");
        assertThat(handled).containsExactly("b");
        assertThat(AppFixture.liveServiceThreads()).isEmpty();
    }

    private static InboxRequest request(String id) {
        return new InboxRequest(id, "approval", "run-1", 0, 3, "Yes", Map.of(), null, "alex@box",
                Instant.parse("2026-11-03T12:00:00Z"));
    }
}
