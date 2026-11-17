package traceworks.core.app;

import traceworks.core.model.RunStatus;
import traceworks.core.record.RecordedEvent;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code run --inputs-from <run>}: a run takes the inputs an earlier run was started with. */
class InputsFromTest {

    private static final Path PKG = Path.of("/pkgs/echo");

    private final AppFixture fixture = new AppFixture();

    InputsFromTest() {
        fixture.packages.put(PKG, """
                {"id": "wf-echo", "name": "Echo", "blocks": [
                  {"type": "shell", "name": "say", "config": {"command": "./say {{ version }} {{ extra }}"}}
                ]}""");
        fixture.commands.on("./say", "said");
    }

    private StartRequest from(String runId, Map<String, String> inputs) {
        return new StartRequest(PKG, inputs, AppFixture.WORKSPACE, false, false, null, null, runId);
    }

    private String lastCommand() {
        return fixture.commands.requests().getLast().command();
    }

    @Test
    void inputsFrom_takesTheEarlierRunsInputs_andExplicitInputsOverride() {
        RunResult first = fixture.services().start(new StartRequest(PKG, Map.of("version", "1.2", "extra", "a"),
                AppFixture.WORKSPACE, false, false), null, null);
        assertThat(lastCommand()).isEqualTo("./say 1.2 a");

        RunResult second = fixture.services().start(from(first.runId(), Map.of()), null, null);
        assertThat(second.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(lastCommand()).isEqualTo("./say 1.2 a");

        fixture.services().start(from(first.runId(), Map.of("extra", "b")), null, null);
        assertThat(lastCommand()).isEqualTo("./say 1.2 b");
    }

    @Test
    void runStarted_recordsInputsFrom() {
        RunResult first = fixture.services().start(new StartRequest(PKG, Map.of("version", "1"), AppFixture.WORKSPACE,
                false, false), null, null);
        RunResult second = fixture.services().start(from(first.runId(), Map.of()), null, null);

        RecordedEvent started = fixture.services().events(second.runId()).getFirst();
        assertThat(started.fields().path("inputs_from").asText()).isEqualTo(first.runId());
        assertThat(fixture.services().events(first.runId()).getFirst().fields().has("inputs_from")).isFalse();
    }

    /**
     * The new run names the earlier one as the origin of its inputs, so the inputs must be what that run's record
     * names: a blob with other content than its name says refuses the run, as a resume refuses it.
     */
    @Test
    void aChangedInputsBlob_isRefused_beforeTheRunExists() {
        RunResult first = fixture.services().start(new StartRequest(PKG, Map.of("version", "1"), AppFixture.WORKSPACE,
                false, false), null, null);
        RecordedEvent started = fixture.services().events(first.runId()).getFirst();
        try (var run = fixture.store.open(first.runId())) {
            ((traceworks.core.testing.InMemoryBlobStore) run.blobs()).putRaw(started.blob("inputs").sha256(),
                    "{\"version\":\"9; rm -rf .\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        int commands = fixture.commands.requests().size();

        assertThatThrownBy(() -> fixture.services().start(from(first.runId(), Map.of()), null, null))
                .isInstanceOf(RecordDamagedException.class).hasMessageContaining("inputs")
                .hasMessageContaining("other content");
        assertThat(fixture.store.runIds()).hasSize(1);
        assertThat(fixture.commands.requests()).hasSize(commands);
    }

    /**
     * The inputs are taken from the first line of the earlier record, so that record must hold: a run that does not
     * verify (here its last line was changed, so it is no longer the record its manifest and its ledger entry name)
     * is no source of inputs.
     */
    @Test
    void aRunThatDoesNotVerify_isNoSourceOfInputs() {
        RunResult first = fixture.services().start(new StartRequest(PKG, Map.of("version", "1"), AppFixture.WORKSPACE,
                false, false), null, null);
        String last = new String(fixture.events(first.runId()).getLast().raw(), java.nio.charset.StandardCharsets.UTF_8);
        fixture.run(first.runId()).log().replaceLast(last.replaceFirst("\"status\":\"[A-Z_]+\"",
                "\"status\":\"FAILED\"").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(fixture.services().verify(first.runId()).ok()).isFalse();

        assertThatThrownBy(() -> fixture.services().start(from(first.runId(), Map.of()), null, null))
                .isInstanceOf(RecordDamagedException.class).hasMessageContaining("--inputs-from")
                .hasMessageContaining("traceworks verify " + first.runId());
        assertThat(fixture.store.runIds()).hasSize(1);
    }

    /** A workspace inside the package's .git would show a step what the digest leaves out: the start refuses it. */
    @Test
    void aWorkspaceInsideThePackagesGitDirectory_isRefused() {
        assertThatThrownBy(() -> fixture.services().start(new StartRequest(PKG, Map.of(), PKG.resolve(".git/x"),
                false, false), null, null))
                .isInstanceOf(RunRefusedException.class).hasMessageContaining(".git").hasMessageContaining("digest");
        assertThat(fixture.store.runIds()).isEmpty();
    }

    /** Inputs that are no JSON object are a damaged record, not a failure deeper down. */
    @Test
    void recordedInputsThatAreNoObject_areADamagedRecord() {
        assertThatThrownBy(() -> RunRecords.inputs("r1", "null".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(RecordDamagedException.class).hasMessageContaining("r1");
    }

    @Test
    void anUnknownRun_orADeletedInputsBlob_isRefused_beforeTheRunExists() {
        assertThatThrownBy(() -> fixture.services().start(from("deadbeef", Map.of()), null, null))
                .hasMessageContaining("deadbeef");
        RunResult first = fixture.services().start(new StartRequest(PKG, Map.of("version", "1"), AppFixture.WORKSPACE,
                false, false), null, null);
        RecordedEvent started = fixture.services().events(first.runId()).getFirst();
        try (var run = fixture.store.open(first.runId())) {
            ((traceworks.core.testing.InMemoryBlobStore) run.blobs()).delete(started.blob("inputs").sha256());
        }

        assertThatThrownBy(() -> fixture.services().start(from(first.runId(), Map.of()), null, null))
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("inputs").hasMessageContaining("deleted");
        assertThat(fixture.store.runIds()).hasSize(1);
    }
}
