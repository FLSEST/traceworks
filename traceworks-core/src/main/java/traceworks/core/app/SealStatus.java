package traceworks.core.app;

import traceworks.core.seal.Seal;

import java.util.List;

/**
 * What the seal check found for a package, as a run of it would: it is not sealed, it is sealed and the seal holds,
 * or a run of it is refused.
 *
 * @param state      the verdict
 * @param seal       the seal the package carries, when it could be read; {@code null} otherwise
 * @param key        the fingerprint of the key that signed the seal; {@code null} unless {@link State#SEALED}
 * @param principals the names the allowed signers file gives that key; empty unless {@link State#SEALED}
 * @param reason     a stable code of the refusal, as the seal log records it; {@code null} unless
 *                   {@link State#REFUSED}
 * @param detail     the refusal for a person, with what to do; {@code null} unless {@link State#REFUSED}
 * @param digest     the seal digest of the package as it is now; {@code null} when it could not be computed
 * @param adoptions  the seals a run of the package would write into the seal log; empty when the log knows them all
 * @param recordable whether the refusal can be appended to the seal log: not when the log itself does not hold
 */
public record SealStatus(State state, Seal seal, String key, List<String> principals, String reason, String detail,
                         String digest, boolean recordable, List<Adopted> adoptions) {

    /**
     * A valid seal this home's log does not hold yet, of the package itself or of a package it calls: made elsewhere,
     * and newer than the last change the log holds for its workflow. A run that is admitted writes it into the log.
     */
    public record Adopted(String workflowId, String workflowName, String packageDir, String packageDigest, Seal seal,
                          String key, List<String> principals) {
    }

    /** The verdict of the seal check. */
    public enum State {
        /** The package carries no seal, and the home does not hold its workflow as sealed. */
        UNSEALED,
        /** The package carries a seal, and it holds. */
        SEALED,
        /** A run of the package is refused. */
        REFUSED
    }

    public SealStatus {
        principals = principals == null ? List.of() : List.copyOf(principals);
        adoptions = adoptions == null ? List.of() : List.copyOf(adoptions);
    }

    /** This status, with the seals a run would take into the log. */
    SealStatus adopting(List<Adopted> seals) {
        return new SealStatus(state, seal, key, principals, reason, detail, digest, recordable, seals);
    }

    static SealStatus unsealed(String digest) {
        return new SealStatus(State.UNSEALED, null, null, List.of(), null, null, digest, true, List.of());
    }

    static SealStatus sealed(Seal seal, String key, List<String> principals) {
        return new SealStatus(State.SEALED, seal, key, principals, null, null, seal.digest(), true, List.of());
    }

    static SealStatus refused(String reason, String detail, Seal seal, String digest) {
        return new SealStatus(State.REFUSED, seal, null, List.of(), reason, detail, digest, true, List.of());
    }

    /** A refusal because the seal log itself cannot be trusted: nothing is appended to it. */
    static SealStatus logRefused(String reason, String detail) {
        return new SealStatus(State.REFUSED, null, null, List.of(), reason, detail, null, false, List.of());
    }
}
