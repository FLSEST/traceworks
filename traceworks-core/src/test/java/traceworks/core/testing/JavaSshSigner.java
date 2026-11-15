package traceworks.core.testing;

import traceworks.core.port.Signer;
import traceworks.core.seal.SshSignature;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;

/**
 * A {@link Signer} in plain Java, for tests that must not depend on {@code ssh-keygen}: it makes an Ed25519 key of its
 * own and writes the SSH signature format that {@code ssh-keygen -Y sign} writes.
 */
public final class JavaSshSigner implements Signer {

    private final KeyPair pair;
    /** When set, every call fails with it, as a missing key or a missing ssh-keygen does. */
    public volatile RuntimeException failure;
    /** When set, the signature is made over other bytes than the ones asked for, so it does not verify. */
    public volatile boolean signsSomethingElse;
    /** The key the last call named; {@code null} for the configured one. */
    public volatile String lastKey;

    public JavaSshSigner() {
        try {
            pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] keyBlob() {
        byte[] x509 = pair.getPublic().getEncoded();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SshSignature.writeString(out, SshSignature.ED25519.getBytes(StandardCharsets.US_ASCII));
        SshSignature.writeString(out, Arrays.copyOfRange(x509, x509.length - 32, x509.length));
        return out.toByteArray();
    }

    /** The public key as an allowed signers line names it: {@code ssh-ed25519 <base64>}. */
    public String publicKey() {
        return SshSignature.ED25519 + " " + Base64.getEncoder().encodeToString(keyBlob());
    }

    /** One line of an allowed signers file that allows this key as {@code principal}. */
    public String allowedLine(String principal) {
        return principal + " " + publicKey() + "\n";
    }

    @Override
    public String sign(byte[] message, String namespace, String key) {
        lastKey = key;
        if (failure != null) {
            throw failure;
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-512").digest(signsSomethingElse
                    ? "something else".getBytes(StandardCharsets.UTF_8) : message);
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(pair.getPrivate());
            signer.update(SshSignature.signedData(namespace, new byte[0], "sha512", hash));
            ByteArrayOutputStream signature = new ByteArrayOutputStream();
            SshSignature.writeString(signature, SshSignature.ED25519.getBytes(StandardCharsets.US_ASCII));
            SshSignature.writeString(signature, signer.sign());
            ByteArrayOutputStream blob = new ByteArrayOutputStream();
            blob.writeBytes("SSHSIG".getBytes(StandardCharsets.US_ASCII));
            blob.writeBytes(new byte[] {0, 0, 0, 1});
            SshSignature.writeString(blob, keyBlob());
            SshSignature.writeString(blob, namespace.getBytes(StandardCharsets.UTF_8));
            SshSignature.writeString(blob, new byte[0]);
            SshSignature.writeString(blob, "sha512".getBytes(StandardCharsets.US_ASCII));
            SshSignature.writeString(blob, signature.toByteArray());
            return "-----BEGIN SSH SIGNATURE-----\n"
                    + Base64.getMimeEncoder(70, new byte[] {'\n'}).encodeToString(blob.toByteArray())
                    + "\n-----END SSH SIGNATURE-----\n";
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
