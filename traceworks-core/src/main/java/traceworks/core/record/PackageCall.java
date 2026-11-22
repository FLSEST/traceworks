package traceworks.core.record;

import traceworks.core.port.BlobRef;

/**
 * The package a child run's workflow was loaded from, as the child's {@code run.package} line records it: what
 * {@code run.started} says about the run's own package, for a package the run called. A child has one whenever
 * what was loaded for it is not what {@code run.started} names: a child of another package than the run's, and a
 * child of the run's own package that was loaded with another digest, since the package changed while the run
 * ran. The package digest of the run covers a child of its own package as the run started with it.
 *
 * @param workflowId    the {@code id} of the workflow that ran as the child
 * @param workflowName  its {@code name}
 * @param reference     the {@code workflow_id} the calling block asked for, with its placeholders filled in: a
 *                      package-relative path or a workflow id
 * @param packageDir    the directory the package was loaded from
 * @param packageDigest the digest of the package's files, as loaded for this call
 * @param definition    the blob of the {@code workflow.json} that ran
 */
public record PackageCall(String workflowId, String workflowName, String reference, String packageDir,
                          String packageDigest, BlobRef definition) {
}
