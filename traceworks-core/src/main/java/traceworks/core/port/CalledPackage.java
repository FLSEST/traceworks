package traceworks.core.port;

/**
 * A package a run called: what the record names of the package the workflow of a child run was loaded from, when
 * that is another package than the run's ({@link WorkflowResolver#calledPackage}). The package digest of a run
 * covers the package the run was started with only; the workflow of another package decides what its child run
 * does, so the record names that package too, as loaded for the call.
 *
 * @param reference    the reference the resolver was asked for: a package-relative path or a workflow id
 * @param directory    the package directory, absolute and normalised
 * @param digest       the package digest, lowercase hex
 * @param workflowJson the bytes of the package's {@code workflow.json} as read
 */
public record CalledPackage(String reference, String directory, String digest, byte[] workflowJson) {
}
