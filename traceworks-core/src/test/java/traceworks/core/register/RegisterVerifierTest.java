package traceworks.core.register;

import traceworks.core.port.Clock;
import traceworks.core.port.RegisterStore;
import traceworks.core.record.ChainReport;
import traceworks.core.testing.InMemoryRegisterStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RegisterVerifierTest {

    private static final Instant T1 = Instant.parse("2026-11-11T10:00:00Z");

    private final InMemoryRegisterStore store = new InMemoryRegisterStore();
    private final Clock clock = () -> T1;
    private List<byte[]> lines;

    @BeforeEach
    void aRegisterWithOneReport() {
        try (RegisterStore.Opened opened = store.openForWrite("arch")) {
            RegisterLog log = new RegisterLog(opened, clock);
            RegisterWriter writer = new RegisterWriter(log, opened.blobs());
            writer.created("wf-1", "c");
            String reportBlob = opened.blobs().put("# report".getBytes(StandardCharsets.UTF_8)).sha256();
            Reconciler.Applied applied = writer.applied(RegisterState.empty("arch", "wf-1", "c"),
                    new Reconciler.Report("run-1", T1, reportBlob, List.of(new ReportedFinding("r", "s", Severity.LOW,
                            "t", null, null, List.of())), Set.of()), null, "head-1");
            opened.writeState(StateJson.write(applied.state()));
            lines = new ArrayList<>(opened.events().readAll());
        }
    }

    private ChainReport verify(List<byte[]> lines) {
        try (RegisterStore.Opened opened = store.open("arch")) {
            return RegisterVerifier.verify("arch", lines, opened.blobs(), opened.state());
        }
    }

    @Test
    void anIntactRegister_verifies() {
        ChainReport report = verify(lines);

        assertThat(report.intact()).as(report.problems().toString()).isTrue();
        assertThat(report.events()).as("created, requires, applied, finding.new").isEqualTo(4);
        assertThat(report.deletedBlobs()).isEmpty();
    }

    @Test
    void aChangedLine_isFound() {
        List<byte[]> changed = new ArrayList<>(lines);
        changed.set(0, new String(lines.get(0), StandardCharsets.UTF_8).replace("\"category\":\"c\"", "\"category\":\"d\"")
                .getBytes(StandardCharsets.UTF_8));

        ChainReport report = verify(changed);

        assertThat(report.intact()).isFalse();
        assertThat(report.problems()).anySatisfy(p -> assertThat(p).contains("seq 1").contains("prev"));
    }

    @Test
    void aRemovedLine_isFound() {
        ChainReport report = verify(List.of(lines.get(2)));

        assertThat(report.intact()).isFalse();
        assertThat(report.problems()).anySatisfy(p -> assertThat(p).contains("seq"));
    }

    /** The first line of a cut log neither has seq 0 nor follows the genesis; each problem says which it is. */
    @Test
    void aLogThatLostItsFirstLines_namesTheSeqItExpected_andTheGenesis() {
        ChainReport report = verify(List.of(lines.get(2)));

        assertThat(report.problems()).contains(
                "seq 2: expected seq 0 here, so lines are missing or moved",
                "seq 2: prev does not match the genesis of the register, so the line before was changed or removed");
    }

    @Test
    void aChangedLine_isNamedByTheLineAfterIt() {
        List<byte[]> changed = new ArrayList<>(lines);
        changed.set(0, new String(lines.get(0), StandardCharsets.UTF_8).replace("\"category\":\"c\"", "\"category\":\"d\"")
                .getBytes(StandardCharsets.UTF_8));

        assertThat(verify(changed).problems()).contains(
                "seq 1: prev does not match the hash of seq 0, so the line before was changed or removed");
    }

    /** A line that cannot be read keeps its place: the line after it is expected with the next seq. */
    @Test
    void aLineThatCannotBeRead_keepsItsPlaceInTheCount() {
        List<byte[]> broken = new ArrayList<>(lines);
        broken.set(1, "not json".getBytes(StandardCharsets.UTF_8));

        ChainReport report = verify(broken);

        assertThat(report.intact()).isFalse();
        assertThat(report.problems()).anySatisfy(p -> assertThat(p).startsWith("line 1 (after seq 0): "));
        assertThat(report.problems()).noneSatisfy(p -> assertThat(p).contains("expected seq"));
    }

    /**
     * Each applied report records the state it left, and the fold of the log up to that line must give the same
     * state: a line that names another state, well formed and stored under its hash, is found.
     */
    @Test
    void aRecordedStateThatIsNotTheFoldOfTheLog_isFound() {
        String other = store.blobs("arch").put(StateJson.write(RegisterState.empty("arch", "wf-1", "c"))).sha256();
        String recorded = RegisterEvent.parse(lines.get(2)).text("state_blob");
        List<byte[]> tampered = new ArrayList<>(lines);
        tampered.set(2, new String(lines.get(2), StandardCharsets.UTF_8).replace(recorded, other)
                .getBytes(StandardCharsets.UTF_8));

        ChainReport report = verify(tampered);

        assertThat(report.intact()).isFalse();
        assertThat(report.problems()).contains("seq 2: the recorded state differs from the fold of the log up to here");
    }

    @Test
    void editedState_isReported() {
        store.editState("arch", "{\"edited\":true}".getBytes(StandardCharsets.UTF_8));

        ChainReport report = verify(lines);

        assertThat(report.intact()).isFalse();
        assertThat(report.problems()).anySatisfy(p -> assertThat(p).contains("state.json differs from the log"));
    }

    @Test
    void aMissingState_isNoProblem_butAMissingBlobIs() {
        try (RegisterStore.Opened opened = store.open("arch")) {
            assertThat(RegisterVerifier.verify("arch", lines, opened.blobs(), Optional.empty()).intact()).isTrue();
        }
        try (RegisterStore.Opened opened = store.open("arch")) {
            RegisterEvent applied = RegisterEvent.parse(lines.get(2));
            String findingsBlob = applied.fields().path("findings_blob").asText();
            ((traceworks.core.testing.InMemoryBlobStore) opened.blobs()).delete(findingsBlob);

            ChainReport report = RegisterVerifier.verify("arch", lines, opened.blobs(), Optional.empty());

            assertThat(report.deletedBlobs()).containsExactly(findingsBlob);
            assertThat(report.intact()).isFalse();
        }
    }

    /** A blob edited in place keeps its name; the verifier hashes the content, as the run's verifier does. */
    @Test
    void aBlobEditedInPlace_isFound() {
        String reportBlob = RegisterEvent.parse(lines.get(2)).text("report_blob");
        store.blobs("arch").putRaw(reportBlob, "# edited".getBytes(StandardCharsets.UTF_8));

        ChainReport report = verify(lines);

        assertThat(report.intact()).isFalse();
        assertThat(report.problems()).anySatisfy(p -> assertThat(p).contains("seq 2").contains("report_blob")
                .contains("does not match"));
    }

    /** A tampered field the fold cannot read is a problem of the report, not a crash of the command. */
    @Test
    void aFieldTheFoldCannotRead_isReported_notThrown() {
        String id = RegisterEvent.parse(lines.get(3)).text("id");
        List<byte[]> tampered = new ArrayList<>(lines);
        tampered.add(("{\"seq\":4,\"at\":\"2026-11-11T10:00:00.000Z\",\"type\":\"finding.accepted\",\"register\":\"arch\","
                + "\"prev\":\"x\",\"id\":\"" + id + "\",\"note\":\"n\",\"actor\":\"a\",\"until\":\"not-a-date\","
                + "\"signature\":null,\"state_blob\":\"y\"}").getBytes(StandardCharsets.UTF_8));

        ChainReport report = verify(tampered);

        assertThat(report.intact()).isFalse();
        assertThat(report.problems()).anySatisfy(p -> assertThat(p).contains("seq 4").contains("not-a-date"));
    }
}
