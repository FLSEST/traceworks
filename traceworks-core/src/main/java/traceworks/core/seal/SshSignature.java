package traceworks.core.seal;

import traceworks.core.record.EventChain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * An SSH signature (SSHSIG, what {@code ssh-keygen -Y sign} writes), read and verified in Java, so verifying needs no
 * OpenSSH. Only Ed25519 keys are verified: a signature of any other key type is read, and
 * {@link #problem} says that it cannot be checked. It never counts as valid.
 */
public final class SshSignature {

    /** The namespace of every signature Traceworks makes and accepts. */
    public static final String NAMESPACE = "traceworks";
    /** The key type that is verified. */
    public static final String ED25519 = "ssh-ed25519";
    /** The most characters an armored signature may have: far more than any key type needs. */
    public static final int MAX_CHARS = 16 * 1024;

    private static final String BEGIN = "-----BEGIN SSH SIGNATURE-----";
    private static final String END = "-----END SSH SIGNATURE-----";
    private static final byte[] MAGIC = "SSHSIG".getBytes(StandardCharsets.US_ASCII);
    /** The fixed start of an Ed25519 public key in X.509 form, before its 32 bytes. */
    private static final byte[] ED25519_X509 = HexFormat.of().parseHex("302a300506032b6570032100");

    private final String keyType;
    private final byte[] keyBlob;
    private final String namespace;
    private final byte[] reserved;
    private final String hashAlgorithm;
    private final byte[] signatureBlob;

    private SshSignature(String keyType, byte[] keyBlob, String namespace, byte[] reserved, String hashAlgorithm,
                         byte[] signatureBlob) {
        this.keyType = keyType;
        this.keyBlob = keyBlob;
        this.namespace = namespace;
        this.reserved = reserved;
        this.hashAlgorithm = hashAlgorithm;
        this.signatureBlob = signatureBlob;
    }

    /**
     * Reads an armored signature: the two marker lines around the Base64 of the SSHSIG structure, and nothing else.
     *
     * @throws IllegalArgumentException if the text is no such signature
     */
    public static SshSignature parse(String armored) {
        if (armored == null) {
            throw new IllegalArgumentException("No signature: it must start with " + BEGIN);
        }
        if (armored.length() > MAX_CHARS) {
            throw new IllegalArgumentException("The signature is longer than " + MAX_CHARS + " characters");
        }
        String text = armored.strip();
        if (!text.startsWith(BEGIN) || !text.endsWith(END) || text.length() < BEGIN.length() + END.length()) {
            throw new IllegalArgumentException("Not an SSH signature: it must start with " + BEGIN + " and end with "
                    + END);
        }
        String body = text.substring(BEGIN.length(), text.length() - END.length()).replaceAll("\\s", "");
        byte[] blob;
        try {
            blob = Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not an SSH signature: its content is not Base64", e);
        }
        Reader in = new Reader(blob);
        if (!Arrays.equals(in.bytes(MAGIC.length), MAGIC)) {
            throw new IllegalArgumentException("Not an SSH signature: it does not start with SSHSIG");
        }
        long version = in.uint32();
        if (version != 1) {
            throw new IllegalArgumentException("An SSH signature of version " + version + " is not read; only"
                    + " version 1 is");
        }
        byte[] key = in.string();
        String namespace = new String(in.string(), StandardCharsets.UTF_8);
        byte[] reserved = in.string();
        String hash = new String(in.string(), StandardCharsets.US_ASCII);
        byte[] signature = in.string();
        if (in.remaining() != 0) {
            throw new IllegalArgumentException("Not an SSH signature: " + in.remaining() + " bytes after its end");
        }
        String keyType = new String(new Reader(key).string(), StandardCharsets.US_ASCII);
        return new SshSignature(keyType, key, namespace, reserved, hash, signature);
    }

    /**
     * The signature as {@code ssh-keygen} writes it: the marker lines around Base64 in lines of 70 characters, each
     * ended by a line feed. A seal takes its signature only in this form, so the text holds nothing but the
     * signature.
     */
    public String armored() {
        java.io.ByteArrayOutputStream blob = new java.io.ByteArrayOutputStream();
        blob.writeBytes(MAGIC);
        blob.writeBytes(new byte[] {0, 0, 0, 1});
        writeString(blob, keyBlob);
        writeString(blob, namespace.getBytes(StandardCharsets.UTF_8));
        writeString(blob, reserved);
        writeString(blob, hashAlgorithm.getBytes(StandardCharsets.US_ASCII));
        writeString(blob, signatureBlob);
        String base64 = Base64.getEncoder().encodeToString(blob.toByteArray());
        StringBuilder text = new StringBuilder(BEGIN).append('\n');
        for (int at = 0; at < base64.length(); at += 70) {
            text.append(base64, at, Math.min(base64.length(), at + 70)).append('\n');
        }
        return text.append(END).append('\n').toString();
    }

    /** The type of the key that signed, such as {@code ssh-ed25519}. */
    public String keyType() {
        return keyType;
    }

    /** The public key that signed, as a line of an allowed signers file names it: type and Base64, no comment. */
    public String publicKey() {
        return keyType + " " + Base64.getEncoder().encodeToString(keyBlob);
    }

    /** The fingerprint of the public key as OpenSSH prints it: {@code SHA256:} and unpadded Base64. */
    public String fingerprint() {
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(sha("SHA-256", keyBlob));
    }

    /**
     * Why this is no valid signature of {@code message} in {@code namespace}; {@code null} when it is one. A key
     * type or hash that is not verified here is a problem, not a pass.
     */
    public String problem(byte[] message, String namespace) {
        if (!this.namespace.equals(namespace)) {
            return "the signature was made for the namespace '" + this.namespace + "', not '" + namespace + "'";
        }
        if (!ED25519.equals(keyType)) {
            return "the signature was made with a key of the type " + keyType + ", and Traceworks verifies only"
                    + " Ed25519 keys (" + ED25519 + ")";
        }
        if (!hashAlgorithm.equals("sha512") && !hashAlgorithm.equals("sha256")) {
            return "the signature uses the hash " + hashAlgorithm + ", and only sha512 and sha256 are read";
        }
        try {
            Reader key = new Reader(keyBlob);
            key.string();
            byte[] raw = key.string();
            Reader sig = new Reader(signatureBlob);
            String sigType = new String(sig.string(), StandardCharsets.US_ASCII);
            byte[] rawSignature = sig.string();
            if (raw.length != 32 || key.remaining() != 0 || !ED25519.equals(sigType) || rawSignature.length != 64
                    || sig.remaining() != 0) {
                return "the signature does not hold an Ed25519 key and signature";
            }
            byte[] x509 = Arrays.copyOf(ED25519_X509, ED25519_X509.length + raw.length);
            System.arraycopy(raw, 0, x509, ED25519_X509.length, raw.length);
            PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(x509));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(signedData(message));
            return verifier.verify(rawSignature) ? null : "the signature does not match what it should sign";
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return "the signature cannot be checked: " + e.getMessage();
        }
    }

    /** What the key signed: the SSHSIG preamble, the namespace, the hash's name and the hash of the message. */
    private byte[] signedData(byte[] message) {
        byte[] hash = sha(hashAlgorithm.equals("sha512") ? "SHA-512" : "SHA-256", message);
        return signedData(namespace, reserved, hashAlgorithm, hash);
    }

    /** The bytes an SSH signature signs, for a signer that makes one. */
    public static byte[] signedData(String namespace, byte[] reserved, String hashAlgorithm, byte[] messageHash) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.writeBytes(MAGIC);
        writeString(out, namespace.getBytes(StandardCharsets.UTF_8));
        writeString(out, reserved);
        writeString(out, hashAlgorithm.getBytes(StandardCharsets.US_ASCII));
        writeString(out, messageHash);
        return out.toByteArray();
    }

    /** One SSH string: a length of four bytes, then the bytes. */
    public static void writeString(java.io.ByteArrayOutputStream out, byte[] bytes) {
        out.write(bytes.length >>> 24);
        out.write(bytes.length >>> 16);
        out.write(bytes.length >>> 8);
        out.write(bytes.length);
        out.writeBytes(bytes);
    }

    private static byte[] sha(String algorithm, byte[] bytes) {
        try {
            return MessageDigest.getInstance(algorithm).digest(bytes);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(algorithm + " is part of every Java runtime", e);
        }
    }

    /** The SHA-256 of the armored text would not name the key; this names the signature for a log line. */
    @Override
    public String toString() {
        return keyType + " " + fingerprint() + " " + EventChain.sha256Hex(signatureBlob).substring(0, 12);
    }

    /** Reads the SSH wire format: big-endian lengths, each checked against what is left. */
    private static final class Reader {
        private final byte[] bytes;
        private int at;

        Reader(byte[] bytes) {
            this.bytes = bytes;
        }

        int remaining() {
            return bytes.length - at;
        }

        byte[] bytes(int count) {
            if (count < 0 || count > remaining()) {
                throw new IllegalArgumentException("Not an SSH signature: it ends before its content does");
            }
            byte[] out = Arrays.copyOfRange(bytes, at, at + count);
            at += count;
            return out;
        }

        long uint32() {
            byte[] four = bytes(4);
            return ((four[0] & 0xffL) << 24) | ((four[1] & 0xffL) << 16) | ((four[2] & 0xffL) << 8) | (four[3] & 0xffL);
        }

        byte[] string() {
            long length = uint32();
            if (length > remaining()) {
                throw new IllegalArgumentException("Not an SSH signature: it ends before its content does");
            }
            return bytes((int) length);
        }
    }
}
