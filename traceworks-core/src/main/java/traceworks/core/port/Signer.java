package traceworks.core.port;

/**
 * Port to what signs for the operator: {@code ssh-keygen} with the operator's key. The core verifies what it
 * returns itself ({@code core.seal.SshSignature}), so it trusts no signer.
 */
public interface Signer {

    /**
     * Signs {@code message} as an SSH signature in {@code namespace}.
     *
     * @param key the key to sign with, as the person named it for this one call; {@code null} for the operator's
     *            configured key
     * @return the armored signature, as {@code ssh-keygen -Y sign} writes it
     * @throws IllegalStateException if it cannot sign: no key is configured, the key or the signing program is
     *                               missing, or the program failed. The message is for the person and says what to do
     */
    String sign(byte[] message, String namespace, String key);
}
