package traceworks.core.app;

import traceworks.core.record.RecordTypes;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static traceworks.core.app.AppFixture.resumeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A resume makes real again the paths its record holds redacted, and works at the real paths. Whatever it then says
 * of them, in a refusal, in an error or in a line of the seal log, names them as the record does: with
 * {@code [redacted:<NAME>]}, never with the value.
 */
class ResumeRecordedFormTest {

    private static final String PROJECT = "acme-payments";
    private static final Path PKG = Path.of("/pkg/" + PROJECT + "/deploy");
    private static final Path WORKSPACE = Path.of("/work/" + PROJECT);
    private static final String RECORDED = "/pkg/[redacted:PROJECT]/deploy";

    private final AppFixture fx = new AppFixture().allowSigner();
    private final RunServices services = fx.services();

    ResumeRecordedFormTest() {
        fx.packages.put(PKG, """
                {"id": "wf-project", "name": "Project", "blocks": [
                  {"type": "approval", "name": "ok", "config": {"question": "Go?", "options": ["Yes", "No"]}},
                  {"type": "shell", "name": "deploy", "config": {"command": "./deploy", "secrets": ["PROJECT"]}}
                ]}""");
        fx.credentials.put("PROJECT", PROJECT);
        fx.commands.on("./deploy", "deployed");
    }

    private String parked() {
        String id = services.start(new StartRequest(PKG, Map.of(), WORKSPACE, false, false), null, null).runId();
        services.answer(id, new AnswerRequest("approval", 0, "Yes", null, null));
        assertThat(services.view(id).packageDir()).isEqualTo(RECORDED);
        return id;
    }

    /** A package that is there and cannot be read: a refusal with the recorded path, not an error with the real one. */
    @Test
    void aPackageThatCannotBeRead_refusesTheResume_namingItAsTheRecordDoes() {
        String id = parked();
        fx.packages.unreadable(PKG);

        assertThatThrownBy(() -> services.resume(resumeOf(id), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("The package of run " + id + " cannot be loaded from " + RECORDED)
                .message().doesNotContain(PROJECT);
        assertThat(fx.types(id)).doesNotContain(RecordTypes.RUN_RESUMED);
    }

    /** A refusal of the seal check, and the line the seal log keeps of it. */
    @Test
    void aSealRefusal_andItsLineInTheSealLog_nameThePackageAsTheRecordDoes() {
        String id = parked();
        fx.packages.script(PKG, "echo changed");
        services.seals().seal(PKG, null);

        assertThatThrownBy(() -> services.resume(new ResumeRequest(id, false, true), null, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("The package " + RECORDED + " is sealed now")
                .message().doesNotContain(PROJECT);
        assertThat(fx.sealStore.lines().getLast()).contains("\"type\":\"REFUSED\"", "\"action\":\"resume\"",
                "\"package_dir\":\"" + RECORDED + "\"").doesNotContain(PROJECT);
    }

    /** The recorded form of an exception is an exception of the same kind: a damaged record stays one (exit 8). */
    @Test
    void theRecordedFormOfAnException_keepsItsKind_andWhatItCarries() {
        RecordedForm form = new RecordedForm(Map.of("PROJECT", PROJECT));
        RecordDamagedException damaged = new RecordDamagedException("the state of /work/" + PROJECT + " is damaged");
        damaged.addSuppressed(new IllegalStateException("kept"));

        RuntimeException recorded = form.of(damaged);

        assertThat(recorded).isInstanceOf(RecordDamagedException.class)
                .hasMessage("the state of /work/[redacted:PROJECT] is damaged");
        assertThat(recorded.getSuppressed()).hasSize(1);
        assertThat(form.of(new RunRefusedException("/x/" + PROJECT))).isExactlyInstanceOf(RunRefusedException.class)
                .hasMessage("/x/[redacted:PROJECT]");
        RunRefusedException clean = new RunRefusedException("nothing of it");
        assertThat(form.of(clean)).isSameAs(clean);
        assertThat(form.of(new java.io.UncheckedIOException("Cannot read /pkg/" + PROJECT,
                new java.io.IOException("/pkg/" + PROJECT)))).hasMessageContaining("/pkg/[redacted:PROJECT]")
                .hasNoCause().message().doesNotContain(PROJECT);
    }
}
