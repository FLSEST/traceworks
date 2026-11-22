package traceworks.core.port;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Port to the content-addressed blobs of a run or a register: everything derived from run data is stored here, not in
 * the log. A blob is named by the SHA-256 of its content, and a read by that name is checked against it by default
 * ({@link #get}): what a use case acts on is what the line that names the blob pointed to. A verifier, which must
 * report a changed blob rather than refuse on it, reads with {@link #getUnverified}; a view, which must neither die
 * on one changed blob nor show it as sound, reads with {@link #getForDisplay}.
 */
public interface BlobStore {

    /** Stores the content under its SHA-256. Storing the same content again changes nothing. */
    BlobRef put(byte[] content);

    /**
     * The bytes stored under {@code sha256}, as they are, without any check: for a verifier that reports what is
     * wrong. Empty when the blob was never stored or has been deleted.
     */
    Optional<byte[]> getUnverified(String sha256);

    /**
     * The content of a blob, checked against its name; empty when it was never stored or has been deleted.
     *
     * @throws ChangedBlobException if the stored bytes do not hash to {@code sha256}
     */
    default Optional<byte[]> get(String sha256) {
        Optional<byte[]> content = getUnverified(sha256);
        content.ifPresent(bytes -> {
            String actual = sha256Hex(bytes);
            if (!actual.equals(sha256)) {
                throw new ChangedBlobException(sha256, actual);
            }
        });
        return content;
    }

    /**
     * The content of a blob for a view: the content when it matches its name, a marker in its place when it does not
     * ({@link #mismatchMarker}), empty when it was deleted. A view shows the marker and goes on.
     */
    default Optional<byte[]> getForDisplay(String sha256) {
        return getUnverified(sha256).map(bytes -> {
            String actual = sha256Hex(bytes);
            return actual.equals(sha256) ? bytes : mismatchMarker(sha256, actual).getBytes(StandardCharsets.UTF_8);
        });
    }

    /** What a view shows in place of a blob whose content does not match its name. */
    static String mismatchMarker(String sha256, String actual) {
        return "[this blob does not match its name: blob " + sha256 + " holds content with the SHA-256 " + actual
                + "; traceworks verify says more]";
    }

    /** The SHA-256 of {@code bytes} in lower-case hex. */
    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A blob holds other content than its name says: the record or the register it belongs to was changed. Nothing is
     * acted on with it; the command ends as for a damaged record ({@code traceworks verify} says more).
     */
    final class ChangedBlobException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String sha256;

        public ChangedBlobException(String sha256, String actual) {
            super(what(sha256, actual) + "; check the record or the"
                    + " register with traceworks verify or traceworks findings <key> verify --project <project>");
            this.sha256 = sha256;
            this.actual = actual;
        }

        private ChangedBlobException(String sha256, String actual, String check) {
            super(what(sha256, actual) + "; " + check);
            this.sha256 = sha256;
            this.actual = actual;
        }

        private final String actual;

        private static String what(String sha256, String actual) {
            return "The blob " + sha256 + " holds other content than its name says (its content has the SHA-256 "
                    + actual + "), so it is not what the line that names it pointed to";
        }

        /**
         * The same, for a blob of a register: the message ends with the command that checks that register, with
         * its key and its project, in a form that works as it stands.
         *
         * @param project the project the register is of; {@code null} for a register from before registers had
         *                projects
         */
        public ChangedBlobException ofRegister(String key, String project) {
            ChangedBlobException named = new ChangedBlobException(sha256, actual, "check the register with"
                    + " 'traceworks findings " + key + " verify" + (project == null ? "" : " --project " + project)
                    + "'");
            named.setStackTrace(getStackTrace());
            return named;
        }

        /** The name of the blob. */
        public String sha256() {
            return sha256;
        }
    }
}
