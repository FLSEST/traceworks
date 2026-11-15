package traceworks.core.app;

import traceworks.core.record.ChainReport;

/**
 * The verification of a run.
 *
 * @param chain    the check of the hash chain and the blobs
 * @param manifest {@value #OK}, {@value #MISSING}, {@value #MISMATCH} or {@value #NOT_CHECKED}: whether
 *                 {@code manifest.json} names the head and the number of events the log has
 * @param ledger       the same for the ledger's copy of the manifest
 * @param ledgerReason why the ledger is {@value #NOT_CHECKED}, when it is for a reason other than an unfinished run;
 *                     {@code null} otherwise
 * @param ok           whether nothing is wrong: an intact chain, no manifest or ledger entry missing or
 *                     mismatched, and no recorded seal whose signature does not match
 * @param seal         {@value #UNSEALED} for a run of a package that was not sealed; for a sealed one, a text that
 *                     starts with {@value #OK} when the signature the record holds is a valid one over the seal's
 *                     recorded fields, else what is wrong with it; {@value #NOT_CHECKED} when the record could not be
 *                     read
 */
public record VerifyReport(ChainReport chain, String manifest, String ledger, String ledgerReason, boolean ok,
                           String seal) {

    /** The package of the run was not sealed. */
    public static final String UNSEALED = "unsealed";

    /** A report of a run whose seal was not looked at. */
    public VerifyReport(ChainReport chain, String manifest, String ledger, String ledgerReason, boolean ok) {
        this(chain, manifest, ledger, ledgerReason, ok, NOT_CHECKED);
    }

    /** It agrees with the log. */
    public static final String OK = "ok";
    /** A finished run has none. */
    public static final String MISSING = "missing";
    /** It disagrees with the log, or the ledger copy with {@code manifest.json}. */
    public static final String MISMATCH = "mismatch";
    /** A run that has not finished has none yet, or this home's ledger does not speak for the run. */
    public static final String NOT_CHECKED = "not checked";
}
