package traceworks.core.record;

import java.util.List;

/**
 * The seal a run's package carried when the run started, as {@code run.started} records it: the fields of the seal
 * file, and what the seal check found of its signer.
 *
 * @param workflowId the workflow the seal names
 * @param digest     the seal digest: the package's digest and those of the packages it calls
 * @param signer     who sealed the package, as the seal names them
 * @param sealedAt   when, as the seal names it
 * @param key        the fingerprint of the key that signed the seal
 * @param principals the names the allowed signers of the home gave that key when the run started
 * @param signature  the armored SSH signature of the seal
 */
public record SealView(String workflowId, String digest, String signer, String sealedAt, String key,
                       List<String> principals, String signature) {

    public SealView {
        principals = principals == null ? List.of() : List.copyOf(principals);
    }

    /** The seal in one line, for a person: who sealed, when, as whom the home knew the key, and the seal digest. */
    public String summary() {
        return "sealed by " + signer + " at " + sealedAt + ", signed as " + String.join(", ", principals)
                + " with the key " + key + "; seal digest " + digest;
    }
}
