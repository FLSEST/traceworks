package traceworks.core.app;

import traceworks.core.port.Clock;
import traceworks.core.port.LoadedPackage;
import traceworks.core.port.PackageLoader;
import traceworks.core.port.SealStore;
import traceworks.core.seal.AllowedSigners;
import traceworks.core.seal.Seal;
import traceworks.core.seal.SealLog;
import traceworks.core.seal.SealLogCheck;
import traceworks.core.seal.SshSignature;
import traceworks.core.util.Message;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The seal use case: seal a package, remove its seal, and read what the home holds. Sealing signs the package's seal
 * digest with the operator's key, checks the signature and the signer as a run will, and only then writes: first the
 * {@code SEALED} line of the seal log, then the seal file of the package, so a failure between the two leaves the
 * home holding the package as sealed, and a run of it refused.
 */
public final class Seals {

    private final SealSupport support;
    private final SealSettings settings;
    private final PackageLoader packages;
    private final List<Path> packagePath;
    private final Clock clock;
    private final String actor;
    private final SealAdmission admission;

    Seals(SealSupport support, SealSettings settings, PackageLoader packages, List<Path> packagePath, Clock clock,
          String actor, SealAdmission admission) {
        this.support = support;
        this.settings = settings;
        this.packages = packages;
        this.packagePath = packagePath;
        this.clock = clock;
        this.actor = actor;
        this.admission = admission;
    }

    /**
     * A package that was sealed.
     *
     * @param seal       the seal its directory now carries
     * @param key        the fingerprint of the key that signed
     * @param principals the names the allowed signers of the home give that key
     * @param children   the workflow ids of the other packages the seal covers
     * @param directory  the package directory
     */
    public record Sealed(Seal seal, String workflowName, String key, List<String> principals, List<String> children,
                         String directory) {
    }

    /**
     * A package whose seal was removed.
     *
     * @param digest      the digest of the seal that was removed; {@code null} when the package carried none that
     *                    could be read
     * @param fileRemoved whether the package carried a seal file
     */
    public record Unsealed(String workflowId, String digest, boolean fileRemoved, String directory) {
    }

    /**
     * Seals the package: signs its seal digest, writes {@code traceworks.seal} into it and appends {@code SEALED} to
     * the seal log. A seal the package already carries is replaced.
     *
     * @param key the key to sign with, for this call; {@code null} for the operator's {@code signing_key}
     * @throws RunRefusedException if the package cannot be loaded or has no id, a seal cannot cover it, the seal log
     *                             does not hold, the signer cannot sign, its signature does not verify, or the home
     *                             does not allow the signing key
     */
    public Sealed seal(Path packageLocation, String key) {
        LoadedPackage pkg = load(packageLocation);
        String id = requireId(pkg, "sealed");
        SealClosure closure = SealClosure.of(pkg, packagePath, packages);
        if (!closure.violations().isEmpty()) {
            throw new RunRefusedException(Message.of("The package ", Message.typed(pkg.directory()), " cannot be sealed: ",
                    Message.quoted(String.join("; ", closure.violations()))));
        }
        requireIntactLog();
        Seal unsigned;
        try {
            unsigned = new Seal(id, closure.digest(), actor, clock.now(), "-");
        } catch (IllegalArgumentException e) {
            throw new RunRefusedException(Message.of("The package ", Message.typed(pkg.directory()), " cannot be sealed: ", Message.of(e)));
        }
        String armored;
        try {
            armored = support.signer().sign(unsigned.payload(), SshSignature.NAMESPACE, key);
        } catch (IllegalStateException e) {
            throw new RunRefusedException(Message.of("The package ", Message.typed(pkg.directory()), " was not sealed: ", Message.of(e)));
        }
        Seal seal;
        SshSignature signature;
        try {
            signature = SshSignature.parse(armored);
            // In the one form a run accepts, whatever line length the signer wrote
            seal = new Seal(id, closure.digest(), actor, unsigned.sealedAt(), signature.armored());
        } catch (IllegalArgumentException e) {
            throw new RunRefusedException(Message.of("The package ", Message.typed(pkg.directory()), " was not sealed: the signer returned"
                    + " no signature that can be read (", Message.quoted(e.getMessage()), ")"));
        }
        String problem = signature.problem(seal.payload(), SshSignature.NAMESPACE);
        if (problem != null) {
            throw new RunRefusedException(Message.of("The package ", Message.typed(pkg.directory()), " was not sealed: ", Message.quoted(problem)));
        }
        AllowedSigners signers;
        try {
            signers = support.store().allowedSigners().map(AllowedSigners::parse).orElseGet(AllowedSigners::none);
        } catch (RuntimeException e) {
            throw new RunRefusedException(Message.of("The package ", Message.typed(pkg.directory()), " was not sealed: the allowed signers"
                    + " of this home (" + support.store().allowedSignersLocation() + ") cannot be read: ",
                    Message.of(e)));
        }
        List<String> principals = signers.principals(signature.publicKey(), SshSignature.NAMESPACE);
        if (principals.isEmpty()) {
            throw new RunRefusedException(admission.notAllowed("The package " + pkg.directory() + " was not sealed:"
                    + " no run would accept a seal signed with the key " + signature.fingerprint(), signers,
                    signature, actor));
        }
        Map<String, Object> fields = SealAdmission.sealedFields(id, pkg.definition().name(),
                pkg.directory().toString(), pkg.digest(), seal, signature.fingerprint(), principals, actor, false);
        try (SealStore.Log log = support.store().openLogForWrite()) {
            SealLog.forWriting(log.events(), clock).append(SealLog.SEALED, fields);
            support.store().writeSeal(pkg.directory(), seal.toJson());
        } catch (RuntimeException e) {
            throw new RunRefusedException(Message.of("The seal of ", Message.typed(pkg.directory()), " could not be written: ",
                    Message.of(e), ". Run 'traceworks seal' again; until then a run of the package may be"
                    + " refused"));
        }
        return new Sealed(seal, pkg.definition().name(), signature.fingerprint(), principals, closure.children(),
                pkg.directory().toString());
    }

    /**
     * Removes the seal of the package: appends {@code UNSEALED} to the seal log and deletes the seal file.
     *
     * @param note why, for the log; may be {@code null}
     * @throws RunRefusedException if the operator made seals immutable, the package cannot be loaded or has no id,
     *                             it is not sealed, or the seal log does not hold
     */
    public Unsealed unseal(Path packageLocation, String note) {
        if (settings.immutable()) {
            throw new RunRefusedException(Message.of("seal_immutable is set in traceworks.json: this home removes no seal"
                    + " through the command line. The operator removes the setting first"));
        }
        LoadedPackage pkg = load(packageLocation);
        String id = requireId(pkg, "unsealed");
        SealLogCheck check = requireIntactLog();
        if (pkg.sealJson() == null && !check.sealed().containsKey(id)) {
            throw new RunRefusedException(Message.of("The package ", Message.typed(pkg.directory()), " is not sealed: it carries no "
                    + Seal.FILE_NAME + ", and this home does not hold the workflow ", Message.quoted(id), " as sealed"));
        }
        String digest = null;
        if (pkg.sealJson() != null) {
            try {
                digest = Seal.parse(pkg.sealJson()).digest();
            } catch (IllegalArgumentException e) {
                // a file that is no seal is removed all the same; the log then names no digest
            }
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("workflow_id", id);
        fields.put("package_dir", pkg.directory().toString());
        fields.put("digest", digest);
        fields.put("note", note);
        fields.put("actor", actor);
        boolean removed;
        try (SealStore.Log log = support.store().openLogForWrite()) {
            SealLog.forWriting(log.events(), clock).append(SealLog.UNSEALED, fields);
            removed = support.store().removeSeal(pkg.directory());
        } catch (RuntimeException e) {
            throw new RunRefusedException(Message.of("The seal of ", Message.typed(pkg.directory()), " could not be removed: ",
                    Message.of(e)));
        }
        return new Unsealed(id, digest, removed, pkg.directory().toString());
    }

    /**
     * Refuses to build the images of a package whose seal does not hold, as a run of it is refused, and records the
     * refusal: a build runs the {@code RUN} steps of the package's Dockerfiles. An unsealed package passes, whatever
     * {@code require_seal} says; that setting is about runs.
     *
     * @throws RunRefusedException if the package cannot be loaded, or it is sealed and its seal does not hold
     */
    public void admitBuild(Path packageLocation) {
        LoadedPackage pkg = load(packageLocation);
        SealStatus status = admission.judge(pkg);
        if (status.state() == SealStatus.State.REFUSED) {
            throw admission.refuse(pkg, status, "images-build", null);
        }
    }

    /**
     * The seal log of the home, read and verified; the caller looks at {@link SealLogCheck#intact} before it believes
     * what the log holds as sealed.
     *
     * @throws RunRefusedException if the log cannot be read
     */
    public SealLogCheck log() {
        return admission.log();
    }

    /** Where the seal log lives, for a person. */
    public String logLocation() {
        return support.store().logLocation();
    }

    /**
     * What a run of the package would find of its seal. Nothing is recorded, and {@code require_seal} is not applied:
     * an unsealed package is reported as unsealed.
     *
     * @throws RunRefusedException if the package cannot be loaded
     */
    public SealStatus verify(Path packageLocation) {
        return admission.judge(load(packageLocation));
    }

    private SealLogCheck requireIntactLog() {
        SealLogCheck check = admission.log();
        if (!check.intact()) {
            throw new RunRefusedException(admission.broken(check));
        }
        return check;
    }

    private static String requireId(LoadedPackage pkg, String verb) {
        String id = pkg.definition().id();
        if (id == null || id.isBlank()) {
            throw new RunRefusedException(Message.of("The package ", Message.typed(pkg.directory()), " cannot be " + verb + ": its"
                    + " workflow.json has no id, and a seal names the workflow by its id"));
        }
        return id;
    }

    private LoadedPackage load(Path location) {
        try {
            return packages.load(location);
        } catch (IllegalArgumentException e) {
            throw new RunRefusedException(Message.of(e));
        }
    }
}
