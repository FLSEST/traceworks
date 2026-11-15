package traceworks.core.app;

import traceworks.core.seal.Seal;
import traceworks.core.seal.SealLogCheck;
import traceworks.core.seal.SshSignature;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static traceworks.core.app.AppFixture.BUILD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The seal use case: {@code traceworks seal}, {@code unseal}, {@code seals}. */
class SealsTest {

    private final AppFixture fx = new AppFixture().allowSigner();

    private Seals seals() {
        return fx.services().seals();
    }

    private void assertNothingWritten() {
        assertThat(fx.sealStore.seal(BUILD)).isNull();
        assertThat(fx.sealStore.lines()).isEmpty();
    }

    @Test
    void seal_writesTheSealFile_andASealedLine() {
        Seals.Sealed sealed = seals().seal(BUILD, null);

        Seal seal = Seal.parse(fx.sealStore.seal(BUILD));
        assertThat(seal).isEqualTo(sealed.seal());
        assertThat(seal.workflowId()).isEqualTo("wf-build");
        assertThat(seal.signer()).isEqualTo(AppFixture.ACTOR);
        assertThat(seal.digest()).isEqualTo(SealClosure.of(fx.packages.load(BUILD), List.of(), fx.packages).digest());
        assertThat(SshSignature.parse(seal.signature()).problem(seal.payload(), "traceworks")).isNull();
        assertThat(sealed.principals()).containsExactly(AppFixture.ACTOR);
        assertThat(sealed.key()).startsWith("SHA256:");
        assertThat(fx.sealStore.lines()).singleElement().asString()
                .contains("\"type\":\"SEALED\"").contains("\"workflow_id\":\"wf-build\"")
                .contains("\"digest\":\"" + seal.digest() + "\"").contains("\"package_dir\":\"/pkg/build\"")
                .contains("\"actor\":\"" + AppFixture.ACTOR + "\"").contains(sealed.key());
        assertThat(fx.signer.lastKey).isNull();
    }

    @Test
    void seal_passesTheKeyOfTheCommandLineToTheSigner() {
        seals().seal(BUILD, "/keys/release");

        assertThat(fx.signer.lastKey).isEqualTo("/keys/release");
    }

    @Test
    void seal_withAKeyTheHomeDoesNotAllow_isRefused_andNamesTheLineToAdd() {
        fx.sealStore.allow(null);

        assertThatThrownBy(() -> seals().seal(BUILD, null)).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("memory:allowed_signers").hasMessageContaining(fx.signer.publicKey())
                .hasMessageContaining(AppFixture.ACTOR + " " + fx.signer.publicKey());
        assertNothingWritten();
    }

    @Test
    void seal_whenTheSignerCannotSign_isRefusedWithItsReason() {
        fx.signer.failure = new IllegalStateException("no signing key: set signing_key in traceworks.json");

        assertThatThrownBy(() -> seals().seal(BUILD, null)).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("no signing key: set signing_key");
        assertNothingWritten();
    }

    @Test
    void seal_whenTheSignatureDoesNotVerify_writesNothing() {
        fx.signer.signsSomethingElse = true;

        assertThatThrownBy(() -> seals().seal(BUILD, null)).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("does not match");
        assertNothingWritten();
    }

    @Test
    void seal_ofAPackageWithoutAnId_isRefused() {
        Path anonymous = Path.of("/pkg/anonymous");
        fx.packages.put(anonymous, """
                {"name": "No id", "blocks": [{"type": "shell", "name": "s", "config": {"command": "echo"}}]}""");

        assertThatThrownBy(() -> seals().seal(anonymous, null)).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("id");
        assertThat(fx.sealStore.lines()).isEmpty();
    }

    @Test
    void seal_ofAPackageASealCannotPin_isRefused_namingEveryReason() {
        Path loose = Path.of("/pkg/loose");
        fx.packages.put(loose, """
                {"id": "wf-loose", "name": "Loose", "blocks": [
                  {"type": "sub-workflow", "name": "any", "config": {"workflow_id": "{{ which }}"}},
                  {"type": "shell", "name": "floating", "config": {"command": "x", "container_image": "alpine:3"}}]}""");

        assertThatThrownBy(() -> seals().seal(loose, null)).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("'any'").hasMessageContaining("alpine:3");
        assertThat(fx.sealStore.seal(loose)).isNull();
        assertThat(fx.sealStore.lines()).isEmpty();
    }

    @Test
    void seal_ofWhatIsNoPackage_isRefused() {
        assertThatThrownBy(() -> seals().seal(Path.of("/pkg/none"), null)).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("Not a package");
    }

    @Test
    void seal_whileTheLogDoesNotHold_isRefused() {
        seals().seal(BUILD, null);
        seals().seal(AppFixture.GATE, null);
        List<String> lines = fx.sealStore.lines();
        fx.sealStore.rewrite(List.of(lines.get(0).replace("wf-build", "wf-other"), lines.get(1)));

        assertThatThrownBy(() -> seals().seal(AppFixture.DEPLOY, null)).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("seal log");
        assertThatThrownBy(() -> seals().unseal(AppFixture.GATE, null)).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("seal log");
        assertThat(fx.sealStore.seal(AppFixture.DEPLOY)).isNull();
        assertThat(fx.sealStore.seal(AppFixture.GATE)).isNotNull();
        assertThat(fx.sealStore.lines()).hasSize(2);
    }

    @Test
    void seal_whenTheFileCannotBeWritten_leavesTheHomeHoldingThePackageAsSealed() {
        fx.sealStore.writeFailure = new java.io.UncheckedIOException(new java.io.IOException("read-only"));

        assertThatThrownBy(() -> seals().seal(BUILD, null)).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("read-only");

        // Closed, not open: the next run of the package is refused until it is sealed or unsealed
        assertThat(seals().verify(BUILD).state()).isEqualTo(SealStatus.State.REFUSED);
    }

    @Test
    void sealingAgain_replacesTheSeal() {
        String first = seals().seal(BUILD, null).seal().digest();
        fx.packages.script(BUILD, "echo more");

        String second = seals().seal(BUILD, null).seal().digest();

        assertThat(second).isNotEqualTo(first);
        assertThat(seals().log().sealed().get("wf-build").digest()).isEqualTo(second);
        assertThat(seals().verify(BUILD).state()).isEqualTo(SealStatus.State.SEALED);
    }

    @Test
    void unseal_removesTheSealFile_andAddsAnUnsealedLine() {
        String digest = seals().seal(BUILD, null).seal().digest();

        seals().unseal(BUILD, "release 2 is prepared");

        assertThat(fx.sealStore.seal(BUILD)).isNull();
        assertThat(fx.sealStore.lines()).hasSize(2);
        assertThat(fx.sealStore.lines().get(1)).contains("\"type\":\"UNSEALED\"").contains("\"workflow_id\":\"wf-build\"")
                .contains("\"digest\":\"" + digest + "\"").contains("\"note\":\"release 2 is prepared\"")
                .contains("\"actor\":\"" + AppFixture.ACTOR + "\"");
        assertThat(seals().verify(BUILD).state()).isEqualTo(SealStatus.State.UNSEALED);
    }

    @Test
    void unseal_isRefused_whenTheOperatorMadeSealsImmutable() {
        seals().seal(BUILD, null);
        fx.settings = fx.settings.withSeals(new SealSettings(SealSettings.Require.NEVER, true, null, null));

        assertThatThrownBy(() -> seals().unseal(BUILD, null)).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("seal_immutable");
        assertThat(fx.sealStore.seal(BUILD)).isNotNull();
        assertThat(fx.sealStore.lines()).hasSize(1);
    }

    @Test
    void unseal_ofAPackageThatIsNotSealed_isRefused() {
        assertThatThrownBy(() -> seals().unseal(BUILD, null)).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("not sealed");
        assertThat(fx.sealStore.lines()).isEmpty();
    }

    @Test
    void unseal_alsoClearsAHomeThatHoldsAPackageSealedWhoseFileIsGone() {
        seals().seal(BUILD, null);
        fx.sealStore.removeSeal(BUILD);
        assertThat(seals().verify(BUILD).state()).isEqualTo(SealStatus.State.REFUSED);

        seals().unseal(BUILD, null);

        assertThat(seals().verify(BUILD).state()).isEqualTo(SealStatus.State.UNSEALED);
    }

    @Test
    void theLog_listsWhatIsSealedNow() {
        seals().seal(BUILD, null);
        seals().seal(AppFixture.DEPLOY, null);
        seals().unseal(BUILD, null);

        SealLogCheck log = seals().log();

        assertThat(log.intact()).isTrue();
        assertThat(log.sealed()).containsOnlyKeys("wf-deploy");
        assertThat(log.events()).hasSize(3);
        assertThat(seals().logLocation()).isEqualTo("memory:seals");
    }

    @Test
    void verify_saysWhatARunWouldFind() {
        assertThat(seals().verify(BUILD).state()).isEqualTo(SealStatus.State.UNSEALED);
        seals().seal(BUILD, null);
        SealStatus sealed = seals().verify(BUILD);
        fx.packages.script(BUILD, "echo changed");
        SealStatus changed = seals().verify(BUILD);

        assertThat(sealed.state()).isEqualTo(SealStatus.State.SEALED);
        assertThat(sealed.principals()).containsExactly(AppFixture.ACTOR);
        assertThat(changed.state()).isEqualTo(SealStatus.State.REFUSED);
        assertThat(changed.reason()).isEqualTo("changed");
        assertThat(changed.detail()).contains("changed since it was sealed").contains("traceworks seal");
        // verify records nothing
        assertThat(fx.sealStore.lines()).hasSize(1);
    }
}
