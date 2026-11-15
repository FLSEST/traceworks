package traceworks.core.app;

import traceworks.core.port.ContainerRuntime;
import traceworks.core.record.RecordTypes;
import traceworks.core.record.RecordedEvent;

import java.util.List;

/**
 * What is left of a run's containers once no process runs it: a container a killed process left behind can still
 * write into the run's directories after the run went on or was cancelled. A resume and a cancel of a run nobody
 * writes remove it before anything else, and refuse unless that is confirmed.
 */
final class LeftoverContainers {

    private LeftoverContainers() {
    }

    /**
     * Removes what is left of the run's containers, and of the networks of its AI steps' egress proxies, and refuses
     * unless that is confirmed. Without a runtime there is
     * nothing to remove only for a run that never had one: one that did may have left a container that still runs.
     *
     * @param runtime           the runtime of this process; {@code null} when none answers
     * @param mayHaveContainers whether the run may have left a container ({@link #mayHaveContainers})
     * @param what              what is refused, the start of the message: {@code Run <id> cannot resume}
     * @throws RunRefusedException if the runtime cannot confirm them removed, or none answers for a run that may
     *                             have left one
     */
    static void requireRemoved(ContainerRuntime runtime, String runId, boolean mayHaveContainers, String what) {
        String label = ContainerRuntime.RUN_LABEL + "=" + runId;
        if (runtime == null) {
            if (mayHaveContainers) {
                throw new RunRefusedException(what + ": no container runtime answers (podman or docker), so what is"
                        + " left of its containers (label " + label + ") cannot be confirmed removed, and one may"
                        + " still write into its directories; make the runtime answer for this shell, then try again");
            }
            return;
        }
        if (!runtime.removeLeftovers(runId)) {
            throw new RunRefusedException(what + ": what is left of its containers (label " + label + ") cannot be"
                    + " confirmed removed, and one may still write into its directories; remove them with "
                    + runtime.name() + " rm -f $(" + runtime.name() + " ps -aq --filter label=" + label + "), and"
                    + " the networks of its AI steps with " + runtime.name() + " network rm $(" + runtime.name()
                    + " network ls -q --filter label=" + label + "), then try again");
        }
    }

    /**
     * Whether a run may have left a container: it has not finished, and a process of it ran with a container runtime
     * ({@code container_runtime} in {@code run.started} or a {@code run.resumed}). A finished run's process removed its
     * own containers before it recorded the end.
     */
    static boolean mayHaveContainers(List<RecordedEvent> events) {
        boolean ranWithARuntime = false;
        for (RecordedEvent event : events) {
            if (event.parent() != null) {
                continue;
            }
            if (RecordTypes.RUN_FINISHED.equals(event.type())) {
                return false;
            }
            if ((RecordTypes.RUN_STARTED.equals(event.type()) || RecordTypes.RUN_RESUMED.equals(event.type()))
                    && event.fields().path("container_runtime").isObject()) {
                ranWithARuntime = true;
            }
        }
        return ranWithARuntime;
    }
}
