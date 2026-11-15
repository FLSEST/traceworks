package traceworks.core.seal;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The vector was made once with OpenSSH 9.6: {@code ssh-keygen -Y sign -f key -n traceworks msg}, with a key that was
 * made for it and deleted. Only its public half and the signature are here.
 */
class SshSignatureTest {

    static final String PUBLIC_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAICn6AFfARimOwmTjbauTkhCQ/WWiufbaJ0epa1m8nHr8";
    static final byte[] MESSAGE = "traceworks-seal-v1\nhello\n".getBytes(StandardCharsets.UTF_8);
    static final String BODY = """
            U1NIU0lHAAAAAQAAADMAAAALc3NoLWVkMjU1MTkAAAAgKfoAV8BGKY7CZONtq5OSEJD9Za
            K59tonR6lrWbycevwAAAAKdHJhY2V3b3JrcwAAAAAAAAAGc2hhNTEyAAAAUwAAAAtzc2gt
            ZWQyNTUxOQAAAEDxk/lTIbl966NZufgZKyKCiJ+jfb2udMYBlJHOKJWcWYS4P1jzseyOyg
            M23c8xY0UGkIPVJ6XWXPaaB6tarNYH
            """;
    static final String ARMORED = "-----BEGIN SSH SIGNATURE-----\n" + BODY + "-----END SSH SIGNATURE-----\n";
    /** The same message signed with a 2048-bit RSA key, which the verifier does not read. */
    static final String RSA = "-----BEGIN SSH SIGNATURE-----\n"
            + "U1NIU0lHAAAAAQAAARcAAAAHc3NoLXJzYQAAAAMBAAEAAAEBAMvvhBQTmdyRX2se3XnELFgbrfUt3cj6OhOWPyxure1T/uMgJQ5YxJIwaE6JSc3p/sG32MsseBP9fHjStdvkMiUKGhFXprtnssCjd95HpQEgQEKx05tV2Lv+JqAoBW1veKa5ZSWz0Nbj8LFCxF2T1iGXjhefSOcjZ+cnjuTkuQZyX21ENZzGX4MzF4WZy3L7pmpKz6mx/eR4RtI5XxFwmXPRU0DntSHfTAkGXkdRC17p6EgW+drvYJM/ABFz4P3XqQLUQKswtU0ObhC499l34SFlW1UyeCct1qO/5AFp3xQ1FunmkN2s2W1KjkECn2UrR2TjaW0BngkLuTdJ1IGRGTcAAAAKdHJhY2V3b3JrcwAAAAAAAAAGc2hhNTEyAAABFAAAAAxyc2Etc2hhMi01MTIAAAEAxJMyL+MCQdwR/f8xcqOst7otWioJLdvC/Kxi2ZKyd+cTwvPfS7Y7386zH0T+4hoOykfyi6scj+y2OH4k2Boo3Aa56O47JohXdJdY5Rk3Z8nHt0KH0oNT7Zp+JRfrRjAtCGh9pwhgV6iRK396dN0RpfGHS0ATXOCrLymT0r0q5b6Qd2xkcgYOgfF4R/mJkjTO+tqz6gZMTyssmc1g54wCB/HBGpEZo1ZSn5YAIA99OfF+2aEk5Gls5WP05XLC05aMdZlwmrvHSfRXv6yiJZ0HD4nwem3Ue0/TYnFxW5ZjqasnsmZtYFikvrpDpELMffQ7+DMJwrTrDTwZnjmvz7SB4Q==\n"
            + "-----END SSH SIGNATURE-----\n";

    @Test
    void aSignatureOfSshKeygen_verifies_andNamesItsKey() {
        SshSignature signature = SshSignature.parse(ARMORED);

        assertThat(signature.problem(MESSAGE, "traceworks")).isNull();
        assertThat(signature.publicKey()).isEqualTo(PUBLIC_KEY);
        assertThat(signature.keyType()).isEqualTo("ssh-ed25519");
        assertThat(signature.fingerprint()).isEqualTo("SHA256:0pVQIJF1boObMiCP7RTVl8F5utu6I2ROqm7b7z4L2Ik");
    }

    @Test
    void theArmoredForm_isTheOneSshKeygenWrote_whateverTheSpacingOfWhatWasRead() {
        String spaced = ARMORED.replace("\n", "\n  \n").replace("U1NIU0lH", "U1NI U0lH");

        assertThat(SshSignature.parse(ARMORED).armored()).isEqualTo(ARMORED);
        assertThat(SshSignature.parse(spaced).armored()).isEqualTo(ARMORED);
    }

    @Test
    void anotherMessage_doesNotVerify() {
        byte[] other = "traceworks-seal-v1\nhellO\n".getBytes(StandardCharsets.UTF_8);

        assertThat(SshSignature.parse(ARMORED).problem(other, "traceworks")).contains("does not match");
    }

    @Test
    void anotherNamespace_doesNotVerify() {
        assertThat(SshSignature.parse(ARMORED).problem(MESSAGE, "git"))
                .contains("namespace").contains("traceworks").contains("git");
    }

    @Test
    void aChangedSignatureByte_doesNotVerify() {
        byte[] blob = Base64.getMimeDecoder().decode(BODY);
        blob[blob.length - 1] ^= 1;

        assertThat(SshSignature.parse(armor(blob)).problem(MESSAGE, "traceworks")).contains("does not match");
    }

    @Test
    void aChangedKey_doesNotVerify() {
        byte[] blob = Base64.getMimeDecoder().decode(BODY);
        blob[40] ^= 1;   // inside the 32 bytes of the public key

        assertThat(SshSignature.parse(armor(blob)).problem(MESSAGE, "traceworks")).isNotNull();
    }

    @Test
    void anRsaSignature_isNotRead_andNeverCountsAsValid() {
        assertThat(SshSignature.parse(RSA).problem(MESSAGE, "traceworks"))
                .contains("ssh-rsa").contains("Ed25519");
    }

    @Test
    void whatIsNoSignature_isRefused() {
        byte[] blob = Base64.getMimeDecoder().decode(BODY);
        byte[] cut = java.util.Arrays.copyOf(blob, blob.length - 10);
        byte[] longer = java.util.Arrays.copyOf(blob, blob.length + 4);
        byte[] magic = blob.clone();
        magic[0] = 'X';
        byte[] version = blob.clone();
        version[9] = 2;

        assertThatThrownBy(() -> SshSignature.parse("hello")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BEGIN SSH SIGNATURE");
        assertThatThrownBy(() -> SshSignature.parse(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SshSignature.parse(armor(cut))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SshSignature.parse(armor(longer))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("after");
        assertThatThrownBy(() -> SshSignature.parse(armor(magic))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SSHSIG");
        assertThatThrownBy(() -> SshSignature.parse(armor(version))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
        assertThatThrownBy(() -> SshSignature.parse("-----BEGIN SSH SIGNATURE-----\n!!!\n-----END SSH SIGNATURE-----\n"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SshSignature.parse(ARMORED + "more")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aVeryLongSignature_isRefusedBeforeItIsDecoded() {
        String huge = "-----BEGIN SSH SIGNATURE-----\n" + "A".repeat(SshSignature.MAX_CHARS) + "\n-----END SSH SIGNATURE-----\n";

        assertThatThrownBy(() -> SshSignature.parse(huge)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("longer");
    }

    /** {@code ssh-keygen -Y sign -O hashalg=sha256} hashes the message with SHA-256: such a signature verifies too. */
    @Test
    void aSignatureOverSha256_verifiesToo() throws Exception {
        assertThat(SshSignature.parse(signed("sha256", "SHA-256", 32)).problem(MESSAGE, "traceworks")).isNull();
    }

    @Test
    void aHashThatIsNotRead_isAProblemThatNamesIt() throws Exception {
        assertThat(SshSignature.parse(signed("sha384", "SHA-384", 32)).problem(MESSAGE, "traceworks"))
                .isEqualTo("the signature uses the hash sha384, and only sha512 and sha256 are read");
    }

    @Test
    void anEd25519KeyThatIsNot32Bytes_orNoKeyAtAll_isAProblem_neverAPass() throws Exception {
        assertThat(SshSignature.parse(signed("sha512", "SHA-512", 31)).problem(MESSAGE, "traceworks"))
                .isEqualTo("the signature does not hold an Ed25519 key and signature");
        assertThat(SshSignature.parse(signed("sha512", "SHA-512", -1)).problem(MESSAGE, "traceworks"))
                .isEqualTo("the signature cannot be checked: Not an SSH signature: it ends before its content does");
    }

    @Test
    void markersThatOverlap_areNoSignature() {
        assertThatThrownBy(() -> SshSignature.parse("-----BEGIN SSH SIGNATURE-----END SSH SIGNATURE-----"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("BEGIN SSH SIGNATURE");
    }

    @Test
    void aSignatureOfExactlyTheMostCharacters_isRead() {
        String longest = ARMORED + " ".repeat(SshSignature.MAX_CHARS - ARMORED.length());

        assertThat(SshSignature.parse(longest).publicKey()).isEqualTo(PUBLIC_KEY);
    }

    /** A key longer than 255 bytes needs every byte of its length: written and read back, it is the same signature. */
    @Test
    void theArmoredForm_ofASignatureWithALongKey_readsBackTheSame() {
        SshSignature rsa = SshSignature.parse(RSA);

        SshSignature again = SshSignature.parse(rsa.armored());

        assertThat(again.publicKey()).isEqualTo(rsa.publicKey());
        assertThat(again.armored()).isEqualTo(rsa.armored());
        assertThat(again.problem(MESSAGE, "traceworks")).contains("ssh-rsa");
    }

    /**
     * An Ed25519 signature of {@link #MESSAGE} in the namespace {@code traceworks}, made with a fresh key in Java as
     * {@code ssh-keygen} makes one, with the hash {@code hashName} over the message, and a key of {@code keyBytes}
     * bytes, or none for a negative count.
     */
    private static String signed(String hashName, String digest, int keyBytes) throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] x509 = pair.getPublic().getEncoded();
        ByteArrayOutputStream key = new ByteArrayOutputStream();
        SshSignature.writeString(key, SshSignature.ED25519.getBytes(StandardCharsets.US_ASCII));
        if (keyBytes >= 0) {
            SshSignature.writeString(key, Arrays.copyOf(Arrays.copyOfRange(x509, x509.length - 32, x509.length),
                    keyBytes));
        }
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(SshSignature.signedData("traceworks", new byte[0], hashName,
                MessageDigest.getInstance(digest).digest(MESSAGE)));
        ByteArrayOutputStream signature = new ByteArrayOutputStream();
        SshSignature.writeString(signature, SshSignature.ED25519.getBytes(StandardCharsets.US_ASCII));
        SshSignature.writeString(signature, signer.sign());
        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        blob.writeBytes("SSHSIG".getBytes(StandardCharsets.US_ASCII));
        blob.writeBytes(new byte[] {0, 0, 0, 1});
        SshSignature.writeString(blob, key.toByteArray());
        SshSignature.writeString(blob, "traceworks".getBytes(StandardCharsets.UTF_8));
        SshSignature.writeString(blob, new byte[0]);
        SshSignature.writeString(blob, hashName.getBytes(StandardCharsets.US_ASCII));
        SshSignature.writeString(blob, signature.toByteArray());
        return armor(blob.toByteArray());
    }

    static String armor(byte[] blob) {
        return "-----BEGIN SSH SIGNATURE-----\n"
                + Base64.getMimeEncoder(70, new byte[] {'\n'}).encodeToString(blob)
                + "\n-----END SSH SIGNATURE-----\n";
    }
}
