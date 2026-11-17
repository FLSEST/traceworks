package traceworks.core.seal;

import traceworks.core.port.Clock;
import traceworks.core.testing.InMemoryEventLog;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SealLogTest {

    private final InMemoryEventLog events = new InMemoryEventLog();
    private final Clock clock = () -> Instant.parse("2026-11-13T12:00:00Z");

    private static Map<String, Object> sealed(String id, String digest) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("workflow_id", id);
        fields.put("workflow_name", "Name of " + id);
        fields.put("package_dir", "/pkg/" + id);
        fields.put("digest", digest);
        fields.put("signer", "operator@box");
        fields.put("key", "SHA256:abc");
        return fields;
    }

    private SealLogCheck check() {
        return SealLogCheck.of(events.readAll());
    }

    @Test
    void theFold_knowsWhatIsSealedNow() {
        SealLog log = SealLog.forWriting(events, clock);
        log.append(SealLog.SEALED, sealed("wf-a", "1".repeat(64)));
        log.append(SealLog.SEALED, sealed("wf-b", "2".repeat(64)));
        log.append(SealLog.REFUSED, Map.of("workflow_id", "wf-a", "reason", "changed"));
        log.append(SealLog.UNSEALED, Map.of("workflow_id", "wf-b"));
        log.append(SealLog.SEALED, sealed("wf-a", "3".repeat(64)));

        SealLogCheck check = check();

        assertThat(check.problems()).isEmpty();
        assertThat(check.intact()).isTrue();
        assertThat(check.sealed()).containsOnlyKeys("wf-a");
        assertThat(check.sealed().get("wf-a").digest()).isEqualTo("3".repeat(64));
        assertThat(check.sealed().get("wf-a").packageDir()).isEqualTo("/pkg/wf-a");
        assertThat(check.events()).extracting(SealEvent::type)
                .containsExactly("SEALED", "SEALED", "REFUSED", "UNSEALED", "SEALED");
        assertThat(check.events()).extracting(SealEvent::seq).containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    void anEmptyLog_isIntact_andSealsNothing() {
        assertThat(check().intact()).isTrue();
        assertThat(check().sealed()).isEmpty();
    }

    @Test
    void aSecondWriter_continuesTheChain() {
        SealLog.forWriting(events, clock).append(SealLog.SEALED, sealed("wf-a", "1".repeat(64)));
        SealLog.forWriting(events, clock).append(SealLog.UNSEALED, Map.of("workflow_id", "wf-a"));

        assertThat(check().problems()).isEmpty();
        assertThat(check().sealed()).isEmpty();
    }

    @Test
    void aChangedLine_breaksTheChain() {
        SealLog log = SealLog.forWriting(events, clock);
        log.append(SealLog.SEALED, sealed("wf-a", "1".repeat(64)));
        log.append(SealLog.SEALED, sealed("wf-b", "2".repeat(64)));
        List<byte[]> lines = new ArrayList<>(events.readAll());
        lines.set(0, new String(lines.get(0), StandardCharsets.UTF_8).replace("1".repeat(64), "9".repeat(64))
                .getBytes(StandardCharsets.UTF_8));

        SealLogCheck check = SealLogCheck.of(lines);

        assertThat(check.intact()).isFalse();
        assertThat(check.problems()).singleElement().asString().contains("seq 1").contains("changed or removed");
    }

    @Test
    void aLineRemovedInTheMiddle_orAtTheStart_breaksTheChain() {
        SealLog log = SealLog.forWriting(events, clock);
        log.append(SealLog.SEALED, sealed("wf-a", "1".repeat(64)));
        log.append(SealLog.UNSEALED, Map.of("workflow_id", "wf-a"));
        log.append(SealLog.SEALED, sealed("wf-b", "2".repeat(64)));
        List<byte[]> lines = events.readAll();

        assertThat(SealLogCheck.of(List.of(lines.get(0), lines.get(2))).intact()).isFalse();
        assertThat(SealLogCheck.of(List.of(lines.get(1), lines.get(2))).intact()).isFalse();
        assertThat(SealLogCheck.of(List.of(lines.get(1), lines.get(2))).problems())
                .anyMatch(problem -> problem.contains("genesis"));
    }

    @Test
    void aTypeThisVersionDoesNotKnow_isAProblem_notSkipped() {
        SealLog log = SealLog.forWriting(events, clock);
        log.append(SealLog.SEALED, sealed("wf-a", "1".repeat(64)));
        log.append("PARDONED", Map.of("workflow_id", "wf-a"));

        assertThat(check().intact()).isFalse();
        assertThat(check().problems()).singleElement().asString().contains("PARDONED");
    }

    @Test
    void aLineThatIsNoEvent_isAProblem() {
        SealLog.forWriting(events, clock).append(SealLog.SEALED, sealed("wf-a", "1".repeat(64)));
        List<byte[]> lines = new ArrayList<>(events.readAll());
        lines.add("not json".getBytes(StandardCharsets.UTF_8));

        assertThat(SealLogCheck.of(lines).intact()).isFalse();
    }

    @Test
    void aSealedLineWithoutItsFields_isAProblem() {
        SealLog.forWriting(events, clock).append(SealLog.SEALED, Map.of("workflow_id", "wf-a"));

        assertThat(check().intact()).isFalse();
        assertThat(check().problems()).singleElement().asString().contains("digest");
    }

    @Test
    void aTornTail_isDiscardedByTheNextWriter_andRecorded() {
        SealLog.forWriting(events, clock).append(SealLog.SEALED, sealed("wf-a", "1".repeat(64)));
        events.tearTail("{\"seq\":1,\"at".getBytes(StandardCharsets.UTF_8));

        SealLog.forWriting(events, clock).append(SealLog.UNSEALED, Map.of("workflow_id", "wf-a"));

        assertThat(check().problems()).isEmpty();
        assertThat(check().events()).extracting(SealEvent::type).containsExactly("SEALED", "REPAIRED", "UNSEALED");
        assertThat(check().events().get(1).fields().path("discarded_bytes").asInt()).isEqualTo(12);
    }

    /** What a seal that arrives from elsewhere must be newer than: the last change the log holds for its workflow. */
    @Test
    void theFold_keepsTheTimeOfTheLastChangeOfEachWorkflow_alsoAfterAnUnseal_andNeverGoesBack() {
        java.util.concurrent.atomic.AtomicReference<Instant> now =
                new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-11-13T12:00:00Z"));
        SealLog log = SealLog.forWriting(events, now::get);
        Map<String, Object> first = sealed("wf-a", "1".repeat(64));
        first.put("sealed_at", "2026-11-13T11:59:59.000Z");
        log.append(SealLog.SEALED, first);
        assertThat(check().lastChange("wf-a")).isEqualTo(Instant.parse("2026-11-13T12:00:00Z"));
        assertThat(check().lastChange("wf-b")).isNull();

        now.set(Instant.parse("2026-11-14T12:00:00Z"));
        log.append(SealLog.UNSEALED, Map.of("workflow_id", "wf-a"));
        assertThat(check().lastChange("wf-a")).isEqualTo(Instant.parse("2026-11-14T12:00:00Z"));

        // a seal signed by a clock that runs ahead: its signed time is the mark, and a refusal moves nothing
        now.set(Instant.parse("2026-11-15T12:00:00Z"));
        Map<String, Object> ahead = sealed("wf-a", "2".repeat(64));
        ahead.put("sealed_at", "2030-01-01T00:00:00.000Z");
        log.append(SealLog.SEALED, ahead);
        now.set(Instant.parse("2026-11-16T12:00:00Z"));
        log.append(SealLog.REFUSED, Map.of("workflow_id", "wf-a", "reason", "changed"));
        log.append(SealLog.UNSEALED, Map.of("workflow_id", "wf-a"));

        assertThat(check().lastChange("wf-a")).isEqualTo(Instant.parse("2030-01-01T00:00:00Z"));
    }
}
