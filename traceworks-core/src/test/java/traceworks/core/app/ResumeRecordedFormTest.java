package traceworks.core.app;

import traceworks.core.engine.EventLogException;
import traceworks.core.port.BlobStore;
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

    /**
     * The checkout a failure kept is named on the terminal from a suppressed exception. Its path holds the value when
     * the checkouts lie under a directory the value names, and the failure itself may name no path at all.
     */
    @Test
    void theRecordedFormOfAnException_namesTheCheckoutItKept_asTheRecordDoes() {
        RecordedForm form = new RecordedForm(Map.of("PROJECT", PROJECT));
        RecordDamagedException failure = new RecordDamagedException("The record cannot be written");
        failure.addSuppressed(new KeptCheckoutNotice("/home/" + PROJECT + "/checkouts/run-1"));

        RuntimeException recorded = form.of(failure);

        assertThat(recorded).isExactlyInstanceOf(RecordDamagedException.class)
                .hasMessage("The record cannot be written");
        assertThat(recorded.getSuppressed()).singleElement().isInstanceOfSatisfying(KeptCheckoutNotice.class, kept -> {
            assertThat(kept.path()).isEqualTo("/home/[redacted:PROJECT]/checkouts/run-1");
            assertThat(kept).hasMessageContaining("the checkout /home/[redacted:PROJECT]/checkouts/run-1 ");
        });
        assertThat(everyText(recorded)).doesNotContain(PROJECT);
    }

    /**
     * The command line picks its exit code by the kind of the failure. A failure of a kind that cannot be made again,
     * here a changed blob (exit 8), whose own text names no value stays the one it is, and the checkout it kept is
     * named as the record does from the start.
     */
    @Test
    void aFailureOfAKindThatCannotBeMadeAgain_staysTheOneItIs_andNamesTheCheckoutItKept_asTheRecordDoes() {
        RecordedForm form = new RecordedForm(Map.of("PROJECT", PROJECT));
        fx.sources.root = Path.of("/home/" + PROJECT + "/checkouts");
        fx.sources.present.add(fx.sources.directoryOf("run-1"));
        BlobStore.ChangedBlobException changed = new BlobStore.ChangedBlobException("ab12", "cd34");

        new Checkouts(fx.sources, fx.directories).noteKept("run-1", changed, form);
        RuntimeException recorded = form.of(changed);

        assertThat(recorded).isSameAs(changed);
        assertThat(recorded.getSuppressed()).singleElement().isInstanceOfSatisfying(KeptCheckoutNotice.class,
                kept -> assertThat(kept.path()).isEqualTo("/home/[redacted:PROJECT]/checkouts/run-1"));
        assertThat(everyText(recorded)).doesNotContain(PROJECT);
    }

    /**
     * Also when something else it suppressed names a value: that is shown nowhere, and the kind decides the exit code.
     * A failure whose own text names a value has no such choice, and one of a kind that can be made again needs none.
     */
    @Test
    void aFailureOfAKindThatCannotBeMadeAgain_staysTheOneItIs_whenOnlyWhatItSuppressedNamesAValue() {
        RecordedForm form = new RecordedForm(Map.of("PROJECT", PROJECT));
        BlobStore.ChangedBlobException changed = new BlobStore.ChangedBlobException("ab12", "cd34");
        changed.addSuppressed(new java.io.UncheckedIOException("Cannot write /home/" + PROJECT + "/runs/run-1/run.html",
                new java.io.IOException("the disk is full")));

        assertThat(form.of(changed)).isSameAs(changed);
    }

    /** The same through a resume: the record cannot be written, which ends the command as an internal error. */
    @Test
    void aResumeThatFailsAndKeepsItsCheckout_failsWithTheSameKind_andNamesTheCheckoutAsTheRecordDoes() {
        fx.sources.root = Path.of("/home/" + PROJECT + "/checkouts");
        String id = services.start(new StartRequest(PKG, Map.of(), null, false, false,
                "https://git.example.org/org/repo", null), null, null).runId();
        services.answer(id, new AnswerRequest("approval", 0, "Yes", null, null));
        String recordedCheckout = "/home/[redacted:PROJECT]/checkouts/" + id;
        assertThat(fx.events(id).getFirst().fields().path("workspace").asText()).isEqualTo(recordedCheckout);
        fx.run(id).log().failAfter(fx.events(id).size() + 2);

        Throwable failed = org.assertj.core.api.Assertions.catchThrowable(() ->
                services.resume(resumeOf(id), null, null));

        assertThat(failed).isInstanceOf(EventLogException.class);
        assertThat(failed.getSuppressed()).singleElement().isInstanceOfSatisfying(KeptCheckoutNotice.class,
                kept -> assertThat(kept.path()).isEqualTo(recordedCheckout));
        assertThat(everyText(failed)).doesNotContain(PROJECT);
    }

    /** Whatever else a failure carries as suppressed, with its causes and its own suppressed ones. */
    @Test
    void theRecordedFormOfAnException_holdsNoValueInWhatItSuppressed() {
        RecordedForm form = new RecordedForm(Map.of("PROJECT", PROJECT));
        RunRefusedException refusal = new RunRefusedException("The workspace /work/" + PROJECT + " is refused");
        java.io.IOException removal = new java.io.IOException("Cannot remove the checkout",
                new java.nio.file.AccessDeniedException("/home/" + PROJECT + "/checkouts/run-1"));
        removal.addSuppressed(new IllegalStateException("/home/" + PROJECT + "/checkouts/run-1/objects is busy"));
        IllegalArgumentException clean = new IllegalArgumentException("nothing of it");
        refusal.addSuppressed(removal);
        refusal.addSuppressed(clean);

        RuntimeException recorded = form.of(refusal);

        assertThat(recorded).isExactlyInstanceOf(RunRefusedException.class)
                .hasMessage("The workspace /work/[redacted:PROJECT] is refused");
        assertThat(recorded.getSuppressed()).hasSize(2);
        assertThat(recorded.getSuppressed()[0]).hasMessageContaining("IOException: Cannot remove the checkout")
                .hasNoCause();
        assertThat(recorded.getSuppressed()[0].getSuppressed()).singleElement().satisfies(busy ->
                assertThat(busy).hasMessageContaining("/home/[redacted:PROJECT]/checkouts/run-1/objects is busy"));
        assertThat(recorded.getSuppressed()[1]).isSameAs(clean);
        assertThat(everyText(recorded)).doesNotContain(PROJECT);
    }

    /** The message of {@code e}, of its causes and of what they suppressed, each on a line. */
    private static String everyText(Throwable e) {
        StringBuilder text = new StringBuilder(String.valueOf(e.getMessage())).append('\n');
        if (e.getCause() != null) {
            text.append(everyText(e.getCause()));
        }
        for (Throwable suppressed : e.getSuppressed()) {
            text.append(everyText(suppressed));
        }
        return text.toString();
    }
}
