package traceworks.core.app;

/**
 * What resumes a parked or interrupted run.
 *
 * @param runRef              the run id, a unique prefix of it, or the path of the run folder
 * @param allowHostExecution  whether the operator consents to a host run for this resume, in addition to the
 *                            setting; a host run's resume needs the same consent as its start, and a container run
 *                            stays one either way
 * @param allowChangedPackage resume although the package's files changed since the start
 * @param attended            whether the resume counts as attended for the operator's {@code require_seal: unattended}:
 *                            Traceworks has a terminal, or the command line carries the operator's flag for an
 *                            unsealed package
 */
public record ResumeRequest(String runRef, boolean allowHostExecution, boolean allowChangedPackage,
                            boolean attended) {

    /** A resume nobody attends, as far as {@code require_seal} asks. */
    public ResumeRequest(String runRef, boolean allowHostExecution, boolean allowChangedPackage) {
        this(runRef, allowHostExecution, allowChangedPackage, false);
    }
}
