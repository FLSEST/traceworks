package traceworks.core.record;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** The hash chain of a run record: {@code prev} of the first event is the genesis, then the hash of the line before. */
public final class EventChain {

    /** What the genesis hash is computed over, before the run id. */
    public static final String GENESIS_PREFIX = "traceworks-run-v1|";

    private EventChain() {
    }

    /** What the genesis hash of a register log is computed over, before the register key. */
    public static final String REGISTER_GENESIS_PREFIX = "traceworks-register-v1|";

    /** The {@code prev} of the first event of a run: SHA-256 of the prefix and the run id. */
    public static String genesis(String runId) {
        return genesis(GENESIS_PREFIX, runId);
    }

    /** The {@code prev} of the first line of a chain: SHA-256 of {@code prefix} and {@code id}. */
    public static String genesis(String prefix, String id) {
        return sha256Hex((prefix + id).getBytes(StandardCharsets.UTF_8));
    }

    /** The SHA-256 of the bytes as lower-case hex. */
    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
