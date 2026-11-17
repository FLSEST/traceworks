package traceworks.core.app;

import traceworks.core.port.Clock;
import traceworks.core.port.LoadedPackage;
import traceworks.core.port.PackageLoader;
import traceworks.core.port.SealStore;
import traceworks.core.seal.AllowedSigners;
import traceworks.core.seal.Seal;
import traceworks.core.seal.SealEvent;
import traceworks.core.seal.SealLog;
import traceworks.core.seal.SealLogCheck;
import traceworks.core.seal.SshSignature;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The seal check of run admission, fail-closed. A package is sealed when it carries a seal file, or when the seal log
 * of the home holds its workflow id as sealed. A sealed package is admitted only when its seal holds: the file is a
 * seal for this workflow, its digest is the package's seal digest now ({@link SealClosure}), the signature is a valid
 * one over the seal's fields, and the allowed signers of the home allow the signing key. An unsealed package is
 * admitted unless the operator's {@code require_seal} says otherwise.
 *
 * <p>A valid seal that the log does not hold (the package was sealed elsewhere) is taken only when its signed time
 * is later than the last change the log holds for the workflow, and the admitted run writes it into the log. An older
 * one is an earlier seal put back, and is refused. A package the run calls that is sealed itself must hold its own
 * seal, whether or not the calling package is sealed.
 *
 * <p>Every doubt refuses: a seal log that cannot be read or does not hold, a line type it does not know, a seal file
 * that is no seal, a key type the verifier does not read, allowed signers that cannot be read. A refusal is appended
 * to the seal log as a {@code REFUSED} line, unless the log itself is what cannot be trusted.
 */
final class SealAdmission {

    private final SealStore store;
    private final SealSettings settings;
    private final List<Path> packagePath;
    private final PackageLoader packages;
    private final Clock clock;
    private final String actor;

    SealAdmission(SealStore store, SealSettings settings, List<Path> packagePath, PackageLoader packages, Clock clock,
                  String actor) {
        this.store = store;
        this.settings = settings;
        this.packagePath = packagePath;
        this.packages = packages;
        this.clock = clock;
        this.actor = actor;
    }

    /**
     * The seal log of the home, read and verified.
     *
     * @throws RunRefusedException if it cannot be read
     */
    SealLogCheck log() {
        try (SealStore.Log log = store.openLog()) {
            return SealLogCheck.of(log.events().readAll());
        } catch (RuntimeException e) {
            throw new RunRefusedException(unreadable(e));
        }
    }

    private String unreadable(RuntimeException e) {
        return "The seal log of this home (" + store.logLocation() + ") cannot be read, so no seal can be checked: "
                + e.getMessage() + ". Make it readable, or restore it from a backup";
    }

    /** What is wrong with a seal log that does not hold, for a person, with what to do. */
    String broken(SealLogCheck check) {
        List<String> problems = check.problems();
        return "The seal log of this home (" + store.logLocation() + ") does not hold: " + problems.getFirst()
                + (problems.size() > 1 ? " (and " + (problems.size() - 1) + " more, see 'traceworks seals')" : "")
                + ". No seal of this home can be trusted until it does: restore the folder from a backup, or move it"
                + " away and seal the packages again";
    }

    /** What a run of {@code pkg} would find: never a refusal thrown, and nothing recorded. */
    SealStatus judge(LoadedPackage pkg) {
        SealLogCheck check;
        try (SealStore.Log log = store.openLog()) {
            check = SealLogCheck.of(log.events().readAll());
        } catch (RuntimeException e) {
            return SealStatus.logRefused("seal-log-unreadable", unreadable(e));
        }
        if (!check.intact()) {
            return SealStatus.logRefused("seal-log-broken", broken(check));
        }
        SealClosure closure = SealClosure.of(pkg, packagePath, packages);
        SealStatus own = judgeOwn(pkg, check, closure);
        if (own.state() == SealStatus.State.REFUSED) {
            return own;
        }
        // A sealed package keeps its seal when another package calls it: every package the walk reached that
        // carries a seal file, or that this home holds as sealed, must hold its own seal
        List<SealStatus.Adopted> adoptions = new ArrayList<>(own.adoptions());
        for (LoadedPackage child : closure.childPackages()) {
            String childId = child.definition().id();
            if (child.sealJson() == null && (childId == null || !check.sealed().containsKey(childId))) {
                continue;
            }
            SealStatus status = judgeOwn(child, check, SealClosure.of(child, packagePath, packages));
            if (status.state() == SealStatus.State.REFUSED) {
                return SealStatus.refused(status.reason(), "The package " + child.directory() + ", called by "
                        + pkg.directory() + ", is sealed, and its seal does not hold. " + status.detail(),
                        status.seal(), status.digest());
            }
            adoptions.addAll(status.adoptions());
        }
        return own.adopting(adoptions);
    }

    /** The seal of one package, without a look at the packages it calls. */
    private SealStatus judgeOwn(LoadedPackage pkg, SealLogCheck check, SealClosure closure) {
        String id = pkg.definition().id();
        Path dir = pkg.directory();
        SealLogCheck.Sealed held = id == null ? null : check.sealed().get(id);
        if (pkg.sealJson() == null) {
            if (held == null) {
                return SealStatus.unsealed(null);
            }
            return SealStatus.refused("seal-missing", "The workflow " + id + " is sealed in this home (digest "
                    + held.digest() + ", by " + held.signer() + " at " + held.at() + "), but the package " + dir
                    + " carries no " + Seal.FILE_NAME + ": restore the seal file, seal the package again with"
                    + " 'traceworks seal " + dir + "', or remove the seal with 'traceworks unseal " + dir + "'",
                    null, null);
        }
        Seal seal;
        try {
            seal = Seal.parse(pkg.sealJson());
        } catch (IllegalArgumentException e) {
            return SealStatus.refused("seal-unreadable", "The package " + dir + " carries a " + Seal.FILE_NAME
                    + " that is no seal (" + e.getMessage() + "), so the package cannot be taken for sealed or for"
                    + " unsealed: seal it again with 'traceworks seal " + dir + "', or delete the file if the package"
                    + " was never sealed", null, null);
        }
        if (!seal.workflowId().equals(id)) {
            return SealStatus.refused("seal-unreadable", "The " + Seal.FILE_NAME + " of " + dir + " is the seal of the"
                    + " workflow " + seal.workflowId() + ", not of " + id + ": seal the package with 'traceworks seal "
                    + dir + "'", seal, null);
        }
        if (!closure.violations().isEmpty()) {
            return SealStatus.refused("not-sealable", "The package " + dir + " carries a seal, but a seal cannot"
                    + " cover it: " + String.join("; ", closure.violations()), seal, closure.digest());
        }
        if (!closure.digest().equals(seal.digest())) {
            return SealStatus.refused("changed", "The package " + dir + " changed since it was sealed (sealed digest "
                    + seal.digest() + ", now " + closure.digest() + "): a file of the package or of a workflow it calls"
                    + " is different. Restore it as it was sealed, or seal it again with 'traceworks seal " + dir + "'",
                    seal, closure.digest());
        }
        SshSignature signature;
        try {
            signature = SshSignature.parse(seal.signature());
        } catch (IllegalArgumentException e) {
            return SealStatus.refused("signature-invalid", "The seal of " + dir + " carries no signature that can be"
                    + " read (" + e.getMessage() + "): seal the package again with 'traceworks seal " + dir + "'",
                    seal, closure.digest());
        }
        String problem = signature.problem(seal.payload(), SshSignature.NAMESPACE);
        if (problem == null && !signature.armored().equals(seal.signature())) {
            problem = "the text of the signature is not written as ssh-keygen writes it (other spacing), so it may"
                    + " hold what the signature does not cover";
        }
        if (problem != null) {
            return SealStatus.refused("signature-invalid", "The seal of " + dir + " is not validly signed: " + problem
                    + ". Seal the package again with 'traceworks seal " + dir + "'", seal, closure.digest());
        }
        AllowedSigners signers;
        try {
            signers = store.allowedSigners().map(AllowedSigners::parse).orElseGet(AllowedSigners::none);
        } catch (RuntimeException e) {
            return SealStatus.refused("signers-unreadable", "The allowed signers of this home ("
                    + store.allowedSignersLocation() + ") cannot be read, so the signer of the seal of " + dir
                    + " cannot be checked: " + e.getMessage(), seal, closure.digest());
        }
        List<String> principals = signers.principals(signature.publicKey(), SshSignature.NAMESPACE);
        if (principals.isEmpty()) {
            return SealStatus.refused("signer-not-allowed", notAllowed("The seal of " + dir + " is signed by "
                    + seal.signer() + " with the key " + signature.fingerprint(), signers, signature, seal.signer()),
                    seal, closure.digest());
        }
        SealStatus sealed = SealStatus.sealed(seal, signature.fingerprint(), principals);
        if (held != null && held.digest().equals(seal.digest())) {
            return sealed;   // the seal this home holds
        }
        // A valid seal the log does not hold: made elsewhere, or an earlier one put back. The signed time decides:
        // only a seal newer than the last change the log holds for the workflow is taken
        Instant last = check.lastChange(id);
        if (last != null && !seal.sealedAt().isAfter(last)) {
            return SealStatus.refused("seal-superseded", "The seal of " + dir + " (digest " + seal.digest()
                    + ", signed " + seal.sealedAt() + " by " + seal.signer() + ") is older than what this home last"
                    + " recorded for the workflow " + id + " at " + last + " (" + (held == null ? "unsealed"
                    : "sealed with the digest " + held.digest()) + "): an earlier seal was put back, or the clock of"
                    + " the machine that sealed it runs behind. Restore the package as it was last sealed, or seal it"
                    + " again with 'traceworks seal " + dir + "'", seal, closure.digest());
        }
        return sealed.adopting(List.of(new SealStatus.Adopted(id, pkg.definition().name(), dir.toString(),
                pkg.digest(), seal, signature.fingerprint(), principals)));
    }

    /** Why a key is not allowed, and the line that would allow it. */
    String notAllowed(String what, AllowedSigners signers, SshSignature signature, String signer) {
        List<String> notes = signers.notes(signature.publicKey(), SshSignature.NAMESPACE);
        return what + ", which " + store.allowedSignersLocation() + " does not allow"
                + (notes.isEmpty() ? "" : " (" + String.join("; ", notes) + ")")
                + ". If this home should trust that key, add this line to the file: " + signer.replaceAll("\\s", "_")
                + " " + signature.publicKey();
    }

    /**
     * Admits a run or a resume of {@code pkg}, or refuses it and records the refusal.
     *
     * @param attended whether a person is there, as {@code require_seal: unattended} asks
     * @param action   {@code run} or {@code resume}, for the record of a refusal
     * @param runId    the run that resumes; {@code null} for a start
     * @return {@link SealStatus.State#SEALED} or {@link SealStatus.State#UNSEALED}
     * @throws RunRefusedException if the seal check refuses
     */
    SealStatus admit(LoadedPackage pkg, boolean attended, String action, String runId) {
        return admit(pkg, attended, action, runId, RecordedForm.NONE);
    }

    /**
     * {@link #admit(LoadedPackage, boolean, String, String)} for a resume that works at paths it made real: what it
     * writes into the seal log and what it throws names them as the run's record does ({@link RecordedForm}).
     */
    SealStatus admit(LoadedPackage pkg, boolean attended, String action, String runId, RecordedForm form) {
        SealStatus status = judge(pkg);
        if (status.state() == SealStatus.State.REFUSED) {
            throw refuse(pkg, form, status, action, runId);
        }
        adopt(status.adoptions(), form);
        if (status.state() == SealStatus.State.UNSEALED) {
            SealSettings.Require require = settings.require();
            Path dir = pkg.directory();
            if (require == SealSettings.Require.ALWAYS) {
                throw refuse(pkg, form, SealStatus.refused("unsealed", "This home runs sealed packages only (require_seal:"
                        + " always in traceworks.json), and " + dir + " is not sealed: seal it with 'traceworks seal "
                        + dir + "'", null, null), action, runId);
            }
            if (require == SealSettings.Require.UNATTENDED && !attended) {
                throw refuse(pkg, form, SealStatus.refused("unsealed", "This home runs only sealed packages without a"
                        + " terminal (require_seal: unattended in traceworks.json), and " + dir + " is not sealed:"
                        + " seal it with 'traceworks seal " + dir + "', or pass --allow-unsealed", null, null),
                        action, runId);
            }
        }
        return status;
    }

    /**
     * Writes the seals a run takes from elsewhere into the seal log, each as a {@code SEALED} line marked
     * {@code adopted}, with the seal's own signer and signed time, so the log holds them from then on and an earlier
     * seal put back later is found.
     *
     * @throws RunRefusedException if a line cannot be written: a seal the log cannot hold is not run with
     */
    private void adopt(List<SealStatus.Adopted> adoptions, RecordedForm form) {
        if (adoptions.isEmpty()) {
            return;
        }
        try (SealStore.Log log = store.openLogForWrite()) {
            SealLog writer = SealLog.forWriting(log.events(), clock);
            for (SealStatus.Adopted adopted : adoptions) {
                writer.append(SealLog.SEALED, sealedFields(adopted.workflowId(), adopted.workflowName(),
                        form.of(adopted.packageDir()), adopted.packageDigest(), adopted.seal(), adopted.key(),
                        adopted.principals(), actor, true));
            }
        } catch (RuntimeException e) {
            throw new RunRefusedException("The seal of " + form.of(adoptions.getFirst().packageDir()) + " was made elsewhere,"
                    + " and it cannot be written into the seal log of this home (" + store.logLocation() + "): "
                    + e.getMessage() + ". Make the seal log writable; a seal this home cannot record is not run"
                    + " with");
        }
    }

    /** The fields of a {@code SEALED} line. */
    static Map<String, Object> sealedFields(String workflowId, String workflowName, String packageDir,
                                            String packageDigest, Seal seal, String key, List<String> principals,
                                            String actor, boolean adopted) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("workflow_id", workflowId);
        fields.put("workflow_name", workflowName);
        fields.put("package_dir", packageDir);
        fields.put("digest", seal.digest());
        fields.put("package_digest", packageDigest);
        fields.put("signer", seal.signer());
        fields.put("sealed_at", seal.sealedAt().toString());
        fields.put("key", key);
        fields.put("principals", principals);
        fields.put("actor", actor);
        if (adopted) {
            fields.put("adopted", true);
        }
        return fields;
    }

    /** The most characters of a refusal's message a {@code REFUSED} line keeps. */
    static final int DETAIL_LIMIT = 1000;

    /**
     * Records a refusal in the seal log and returns the exception that carries it. A refusal that cannot be recorded
     * stays a refusal, and its message says so.
     */
    RunRefusedException refuse(LoadedPackage pkg, SealStatus status, String action, String runId) {
        return refuse(pkg, RecordedForm.NONE, status, action, runId);
    }

    /**
     * {@link #refuse(LoadedPackage, SealStatus, String, String)} with the paths of {@code pkg} and of every package
     * the refusal names in their recorded form: in the line of the seal log and in the message.
     */
    RunRefusedException refuse(LoadedPackage pkg, RecordedForm form, SealStatus status, String action,
                               String runId) {
        String detail = form.of(status.detail());
        String message = detail;
        if (status.recordable()) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("action", action);
            if (runId != null) {
                fields.put("run_id", runId);
            }
            fields.put("workflow_id", pkg.definition().id());
            fields.put("package_dir", form.of(pkg.directory().toString()));
            fields.put("reason", status.reason());
            fields.put("detail", detail.length() <= DETAIL_LIMIT ? detail : detail.substring(0, DETAIL_LIMIT - 1) + "…");
            fields.put("digest", status.digest());
            fields.put("sealed_digest", status.seal() == null ? null : status.seal().digest());
            fields.put("actor", actor);
            try (SealStore.Log log = store.openLogForWrite()) {
                SealLog writer = SealLog.forWriting(log.events(), clock);
                // A job that is refused again and again for the same thing adds one line, not one per attempt
                if (!repeats(log.events().readAll(), fields)) {
                    writer.append(SealLog.REFUSED, fields);
                }
            } catch (RuntimeException e) {
                message += " (the refusal could not be recorded in the seal log " + store.logLocation() + ": "
                        + form.of(e.getMessage()) + ")";
            }
        }
        return new RunRefusedException(message);
    }

    /** Whether the last line of the log is the refusal {@code fields} would record: workflow, reason and digest. */
    private static boolean repeats(List<byte[]> lines, Map<String, Object> fields) {
        if (lines.isEmpty()) {
            return false;
        }
        try {
            SealEvent last = SealEvent.parse(lines.getLast());
            return SealLog.REFUSED.equals(last.type())
                    && java.util.Objects.equals(last.text("workflow_id"), fields.get("workflow_id"))
                    && java.util.Objects.equals(last.text("reason"), fields.get("reason"))
                    && java.util.Objects.equals(last.text("digest"), fields.get("digest"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
