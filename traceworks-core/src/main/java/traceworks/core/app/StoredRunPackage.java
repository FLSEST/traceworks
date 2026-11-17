package traceworks.core.app;

import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.LoadedPackage;
import traceworks.core.port.PackageLoader;
import traceworks.core.port.StoredRun;
import traceworks.core.record.Redactor;
import traceworks.core.record.RunView;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The package a stored run runs with, resolved from its record: the package in the directory the record names, at its
 * real path, as it is now; whether it changed since the start, by its digest; the definition recorded at the start
 * for a package that changed; and the refusal of a recorded definition that lost a secret's value to the redactor.
 * A resume runs the run with it ({@link Resuming}), and an answer to a parked run takes its redactor from it.
 */
final class StoredRunPackage {

    private final PackageLoader packages;
    private final RunPreparation preparation;

    /**
     * @param packages    loads the run's package from the directory its record names
     * @param preparation names what a run redacts, and makes a path the record holds redacted real again
     */
    StoredRunPackage(PackageLoader packages, RunPreparation preparation) {
        this.packages = packages;
        this.preparation = preparation;
    }

    /**
     * The package the run resumes with: the one in its directory as it is now. When its digest is the one recorded at
     * the start, it is the package the run started with, and its definition is the one the run runs: the copy in
     * {@code run.started} is for the record's readers, with every secret's value redacted, and is never executed.
     * When the package changed, the definition recorded at the start goes with it, as {@code --allow-changed-package}
     * promises; {@link #packageChanged} decides whether that may run.
     *
     * <p>The recorded copy is checked either way: a record whose definition was changed is damaged, and is not
     * resumed.
     *
     * @throws RunRefusedException    if the record has no definition or names no package directory, or the package
     *                                cannot be loaded from it
     * @throws RecordDamagedException if the recorded definition has other content than the record names
     */
    LoadedPackage recordedPackage(StoredRun run, RunView view) {
        return resolve(run, view).runs();
    }

    /**
     * The package of a stored run, loaded once.
     *
     * @param current the package in the run's directory as it is now: what a seal is checked against
     * @param runs    the package the run resumes with ({@link #recordedPackage}): {@code current} itself while the
     *                package is unchanged, else {@code current} with the definition recorded at the start
     */
    record Resolved(LoadedPackage current, LoadedPackage runs) {}

    /** {@link #recordedPackage}, together with the package as it is now. */
    Resolved resolve(StoredRun run, RunView view) {
        byte[] workflowJson = RunRecords.content(run, view.definition(), "workflow definition");
        LoadedPackage current = currentPackage(run.runId(), view.packageDir(), workflowJson);
        if (Objects.equals(current.digest(), view.packageDigest())) {
            return new Resolved(current, current);
        }
        WorkflowDefinition definition = RunRecords.definition(run.runId(), workflowJson);
        return new Resolved(current, new LoadedPackage(current.directory(), definition, workflowJson,
                current.digest(), current.gitCommit(), current.gitDirty(), current.images(),
                current.invalidImageDirectories()));
    }

    /**
     * Refuses to run the definition recorded at the start when its copy lost a secret's value to the redactor: it is
     * not the definition the run started with, and a step would run {@code [redacted:<NAME>]} in its place.
     *
     * @param pkg the changed package, with the recorded definition
     * @throws RunRefusedException naming the replacement and how to go on
     */
    void refuseARedactedDefinition(String runId, RunView view, LoadedPackage pkg) {
        String recorded = new String(pkg.workflowJson(), StandardCharsets.UTF_8);
        for (String name : preparation.redactedNames(pkg)) {
            String replacement = Redactor.MARKER + name + "]";
            if (recorded.contains(replacement)) {
                throw new RunRefusedException("Run " + runId + " cannot resume with its changed package: the copy of"
                        + " its definition recorded at the start holds " + replacement + " where workflow.json held"
                        + " that secret's value, so it is not the definition the run started with; restore the"
                        + " package as it was at the start (digest " + view.packageDigest() + ") to resume the run");
            }
        }
    }

    /**
     * Whether the package changed since the run started, by its digest.
     *
     * @param allowed whether the run may resume with a changed package, with the definition recorded at the start
     * @throws RunRefusedException if it changed and that is not allowed
     */
    static boolean packageChanged(String runId, RunView view, LoadedPackage pkg, boolean allowed) {
        boolean changed = !Objects.equals(pkg.digest(), view.packageDigest());
        if (changed && !allowed) {
            throw new RunRefusedException("The package of run " + runId + " changed since the run started"
                    + " (digest " + view.packageDigest() + ", now " + pkg.digest() + "); resume with"
                    + " --allow-changed-package to run the definition recorded at the start anyway");
        }
        return changed;
    }

    /**
     * The package as it is now, in the directory the record names: at its real path, which the record holds redacted
     * when a secret's value occurs in it ({@link RunPreparation#realPath}). A refusal names the path as the record does.
     *
     * <p>The package is not loaded yet, so the names the run redacts are those of the definition the record holds,
     * without its child workflows, which are in the package: a directory named after a secret that only a child
     * declares is not found.
     *
     * @param workflowJson the definition recorded at the start
     */
    private LoadedPackage currentPackage(String runId, String packageDir, byte[] workflowJson) {
        if (packageDir == null) {
            throw new RunRefusedException("The record of run " + runId + " names no package directory");
        }
        String real = preparation.realPath("The package directory of run " + runId, packageDir,
                () -> preparation.redactedNames(RunRecords.definition(runId, workflowJson), null));
        try {
            return packages.load(Path.of(real));
        } catch (IllegalArgumentException | java.io.UncheckedIOException e) {
            // InvalidPathException included; a package that is there and cannot be read (a file without read
            // permission) refuses the resume like one that is gone, and names its directory as the record does
            throw new RunRefusedException("The package of run " + runId + " cannot be loaded from " + packageDir
                    + ": " + String.valueOf(e.getMessage()).replace(real, packageDir));
        }
    }
}
