package traceworks.adapters.sign;

import traceworks.core.seal.AllowedSigners;
import traceworks.core.seal.SshSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Signs with the real {@code ssh-keygen} and a key each test makes in a temporary directory, never a key of the
 * machine's user; what it signs is verified in Java.
 */
class SshKeygenSignerTest {

    private static final byte[] MESSAGE = "traceworks-seal-v1\nworkflow_id=wf\n".getBytes(StandardCharsets.UTF_8);
    /** Never a prompt on a developer's terminal: a passphrase is asked through a program that gives none. */
    private static final Map<String, String> ENV = Map.of("SSH_ASKPASS", "/bin/false", "SSH_ASKPASS_REQUIRE", "force");

    @TempDir
    Path keys;

    @BeforeEach
    void makeKey() throws Exception {
        keygen("-t", "ed25519", "-N", "", "-C", "test@traceworks", "-f", keys.resolve("key").toString());
    }

    private static void keygen(String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 2];
        command[0] = "ssh-keygen";
        command[1] = "-q";
        System.arraycopy(args, 0, command, 2, args.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
    }

    private SshKeygenSigner signer(Path configured) {
        return new SshKeygenSigner("ssh-keygen", configured, ENV, 20_000);
    }

    @Test
    void theSignature_verifiesInJava_andNamesTheKey() throws IOException {
        String armored = signer(keys.resolve("key")).sign(MESSAGE, "traceworks", null);

        SshSignature signature = SshSignature.parse(armored);
        String publicKey = Files.readString(keys.resolve("key.pub")).strip();
        assertThat(signature.problem(MESSAGE, "traceworks")).isNull();
        assertThat(signature.problem("other".getBytes(StandardCharsets.UTF_8), "traceworks")).isNotNull();
        assertThat(publicKey).startsWith(signature.publicKey());
        assertThat(AllowedSigners.parse(("operator@example.org " + publicKey + "\n").getBytes(StandardCharsets.UTF_8))
                .principals(signature.publicKey(), "traceworks")).containsExactly("operator@example.org");
    }

    @Test
    void theKeyOfTheCall_winsOverTheConfiguredOne() throws Exception {
        keygen("-t", "ed25519", "-N", "", "-f", keys.resolve("other").toString());

        String armored = signer(keys.resolve("key")).sign(MESSAGE, "traceworks", keys.resolve("other").toString());

        assertThat(Files.readString(keys.resolve("other.pub"))).startsWith(SshSignature.parse(armored).publicKey());
    }

    @Test
    void nothingIsLeftBehind_notNextToTheKeyEither() throws IOException {
        signer(keys.resolve("key")).sign(MESSAGE, "traceworks", null);

        try (var files = Files.list(keys)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("key", "key.pub");
        }
    }

    @Test
    void withoutAKey_itSaysHowToSetOne() {
        assertThatThrownBy(() -> signer(null).sign(MESSAGE, "traceworks", null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("signing_key")
                .hasMessageContaining("--key");
    }

    @Test
    void aKeyThatDoesNotExist_isNamed() {
        assertThatThrownBy(() -> signer(keys.resolve("missing")).sign(MESSAGE, "traceworks", null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(keys.resolve("missing").toString())
                .hasMessageContaining("does not exist");
    }

    @Test
    void aFileThatIsNoKey_failsWithWhatSshKeygenSaid() throws IOException {
        Path noKey = Files.writeString(keys.resolve("nokey"), "not a key\n");

        assertThatThrownBy(() -> signer(noKey).sign(MESSAGE, "traceworks", null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ssh-keygen")
                .hasMessageContaining(noKey.toString());
    }

    @Test
    void withoutSshKeygen_itSaysWhatIsMissing() {
        SshKeygenSigner signer = new SshKeygenSigner(keys.resolve("no-such-ssh-keygen").toString(),
                keys.resolve("key"), ENV, 20_000);

        assertThatThrownBy(() -> signer.sign(MESSAGE, "traceworks", null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot be started")
                .hasMessageContaining("OpenSSH");
    }

    @Test
    void aKeyWithAPassphraseNobodyGives_fails_itDoesNotHang() throws Exception {
        keygen("-t", "ed25519", "-N", "secret-passphrase", "-f", keys.resolve("locked").toString());

        assertThatThrownBy(() -> signer(keys.resolve("locked")).sign(MESSAGE, "traceworks", null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ssh-keygen");
    }

    @Test
    void aSignerThatDoesNotFinish_isEndedAtItsTimeout() throws IOException {
        Path slow = Files.writeString(keys.resolve("slow"), "#!/bin/sh\nsleep 30\n");
        slow.toFile().setExecutable(true);
        SshKeygenSigner signer = new SshKeygenSigner(slow.toString(), keys.resolve("key"), ENV, 300);

        long started = System.nanoTime();
        assertThatThrownBy(() -> signer.sign(MESSAGE, "traceworks", null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("did not finish");
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)).isLessThan(10);
    }

    /** A script in ssh-keygen's place that runs {@code body}, with {@code $sig} the file a signature goes to. */
    private Path fakeKeygen(String name, String body) throws IOException {
        Path script = Files.writeString(keys.resolve(name),
                "#!/bin/sh\nfor message; do :; done\nsig=\"$message.sig\"\n" + body + "\n");
        script.toFile().setExecutable(true);
        return script;
    }

    /** An ssh-keygen that fails made no seal, also when it left a signature file behind. */
    @Test
    void aSignerThatFails_makesNoSignature_evenWhenItLeftASignatureFile() throws IOException {
        Path failing = fakeKeygen("failing", "echo 'not a signature' > \"$sig\"\necho 'it failed' >&2\nexit 1");
        SshKeygenSigner signer = new SshKeygenSigner(failing.toString(), keys.resolve("key"), ENV, 20_000);

        assertThatThrownBy(() -> signer.sign(MESSAGE, "traceworks", null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("could not sign")
                .hasMessageContaining("exit code 1").hasMessageContaining("it failed");
    }

    /**
     * ssh-keygen gets the agent and the passphrase prompt of Traceworks' environment and nothing else: no other
     * variable of it, and none of the JVM's own beyond those it needs.
     */
    @Test
    void sshKeygen_getsNothingOfTheEnvironmentButWhatItNeeds() throws IOException {
        Path seen = keys.resolve("seen");
        Path dumping = fakeKeygen("dumping", "env > '" + seen + "'\necho signature > \"$sig\"");
        Map<String, String> environment = Map.of("SSH_AUTH_SOCK", "/run/test-agent.sock",
                "AWS_SECRET_ACCESS_KEY", "s3cr3t-of-the-operator");

        new SshKeygenSigner(dumping.toString(), keys.resolve("key"), environment, 20_000)
                .sign(MESSAGE, "traceworks", null);

        Set<String> needed = Set.of("PATH", "HOME", "USER", "LOGNAME", "SSH_AUTH_SOCK", "SSH_ASKPASS",
                "SSH_ASKPASS_REQUIRE", "DISPLAY", "WAYLAND_DISPLAY", "XDG_RUNTIME_DIR", "TMPDIR", "TERM", "LC_ALL");
        Set<String> ofTheShell = Set.of("PWD", "OLDPWD", "SHLVL", "_");
        List<String> lines = Files.readAllLines(seen);
        assertThat(lines).contains("SSH_AUTH_SOCK=/run/test-agent.sock", "LC_ALL=C");
        assertThat(String.join("\n", lines)).doesNotContain("s3cr3t");
        assertThat(lines).filteredOn(line -> line.matches("[A-Za-z_][A-Za-z0-9_]*=.*"))
                .extracting(line -> line.substring(0, line.indexOf('=')))
                .allMatch(name -> needed.contains(name) || ofTheShell.contains(name));
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv().keySet().stream().anyMatch(
                name -> !needed.contains(name) && !ofTheShell.contains(name)), "the JVM's own environment holds no"
                + " other variable, so the filter of it is not put to the test");
    }

    @Test
    void aSignatureWithAnRsaKey_isMade_andTheVerifierSaysItDoesNotReadIt() throws Exception {
        keygen("-t", "rsa", "-b", "2048", "-N", "", "-f", keys.resolve("rsa").toString());

        String armored = signer(keys.resolve("rsa")).sign(MESSAGE, "traceworks", null);

        assertThat(SshSignature.parse(armored).problem(MESSAGE, "traceworks")).contains("ssh-rsa");
    }
}
