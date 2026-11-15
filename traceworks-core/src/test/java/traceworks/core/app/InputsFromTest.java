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
