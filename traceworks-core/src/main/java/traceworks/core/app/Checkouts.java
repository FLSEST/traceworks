package traceworks.core.app;

import com.fasterxml.jackson.databind.JsonNode;
import traceworks.core.app.RunResult.KeptCheckout;
import traceworks.core.container.RunPaths;
import traceworks.core.model.RunStatus;
import traceworks.core.port.HostDirectories;
import traceworks.core.port.SourceCheckout;
import traceworks.core.port.StoredRun;
import traceworks.core.record.RecordTypes;
import traceworks.core.record.RecordedEvent;
import traceworks.core.record.Redactor;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The checkout a run started with {@code --source} works in, the run's isolated workspace: a clone of the source in
 * the run's own directory ({@link SourceCheckout#directoryOf}), detached at one commit, which is the run's workspace.
 * <ul>
 *   <li>A new run checks its source out after the start check and before the run exists; the checkout must pass the
 *       check of a workspace, or it is removed and the run refused.</li>
 *   <li>A resume works in the checkout as it is. Once a step has run in it, git never runs in it on the host again:
 *       a step with {@code workspace_writable} can write {@code .git/config} (a clean filter, a pager, an fsmonitor),
 *       and git on the host would run that. Only a checkout that is missing is cloned again, from the recorded source
 *       at the recorded commit.</li>
 *   <li>At a final status the checkout is removed, unless a command of the run could write in it: a command on the
 *       host, or a container with a writable mount of it. What it holds may then be the run's own work, so it stays
 *       and the result names it. The record decides, never git in the checkout. A parked or interrupted run keeps
 *       its checkout for its resume.</li>
 * </ul>
 * Telling a checkout that holds nothing of its own from one with changes or commits of its own needs git in a tree
 * the run could write, so Traceworks keeps every checkout a run could write.
 */
final class Checkouts {

    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    private final SourceCheckout sources;
    private final HostDirectories directories;

    Checkouts(SourceCheckout sources, HostDirectories directories) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.directories = Objects.requireNonNull(directories, "directories");
    }

    /**
     * What {@code run.started} says of the run's source; {@code null} for a run without one.
     *
     * @param source the source as recorded, without the credentials of a URL
     * @param sha    the commit the checkout was made at
     */
    record Recorded(String source, String sha) {

        static Recorded of(RecordedEvent started) {
            String source = started.fields().path("workspace_source").asText(null);
            return source == null ? null : new Recorded(source, started.fields().path("workspace_source_sha")
                    .asText(null));
        }
    }

    /**
     * The checkout of a new run.
     *
     * @param workspace the real path of the checkout, the run's workspace
     * @param checkout  what the record names: the source and the commit
     */
    record Started(String workspace, SourceCheckout.Checkout checkout) {}

    /**
     * Checks {@code source} out into the directory of run {@code runId}.
     *
     * @throws RunRefusedException if it cannot be checked out, or the checkout may not be mounted as a workspace; then
     *                             nothing of it is left
     */
    Started start(String source, String ref, String runId) {
        Path directory = directoryOf(runId, "The run is refused");
        SourceCheckout.Checkout checkout;
        try {
            checkout = sources.checkout(source, ref, directory);
        } catch (IllegalArgumentException | IllegalStateException | UncheckedIOException e) {
            throw new RunRefusedException("The run is refused: " + e.getMessage());
        }
        try {
            // One resolution, judged and handed on: the workspace is the path the check saw
            HostDirectories.Checked checked = directories.check(directory);
            if (checked.isRefused()) {
                throw new RunRefusedException("The run is refused: its checkout " + directory + " cannot be mounted: "
                        + checked.refusal());
            }
            return new Started(checked.realPath(), checkout);
        } catch (RuntimeException e) {
            removeAfter(directory, e);
            throw e instanceof RunRefusedException ? e
                    : new RunRefusedException("The run is refused: " + e.getMessage());
        }
    }

    /**
     * Names the checkout of run {@code runId} on {@code failure}, which ended the run after the checkout was made, when
     * the checkout is still there ({@link KeptCheckoutNotice}).
     */
    void noteKept(String runId, RuntimeException failure) {
        noteKept(runId, failure, RecordedForm.NONE);
    }

    /**
     * {@link #noteKept(String, RuntimeException)} for a resume, which made recorded paths real again: the notice names
     * the checkout as the run's record does ({@link RecordedForm}). The failure then needs no other form because of
     * it, and stays of its kind.
     */
    void noteKept(String runId, RuntimeException failure, RecordedForm form) {
        try {
            Path directory = sources.directoryOf(runId);
            if (sources.exists(directory)) {
                failure.addSuppressed(new KeptCheckoutNotice(form.of(directory.toString())));
            }
        } catch (RuntimeException ignored) {
            // the failure that brought us here is the one to report
        }
    }

    /** Removes the checkout of run {@code runId}, which never came to run, after {@code failure}. */
    void discard(String runId, RuntimeException failure) {
        removeAfter(sources.directoryOf(runId), failure);
    }

    /**
     * The workspace of a resume, and whether it had to be cloned again.
     *
     * @param directory the run's checkout
     * @param cloned    whether it was missing and is a new clone at the recorded commit
     */
    record Restored(Path directory, boolean cloned) {}

    /**
     * The checkout a resume of run {@code runId} works in: the one there is, untouched, or, when it is missing, a new
     * clone of the recorded source at the recorded commit.
     *
     * <p>The clone runs git on the host against what the record names, so the record must be one to rely on for it:
     * <ul>
     * <li>A recorded source that holds a replacement ({@code [redacted:<NAME>]}) is not cloned. Making it real
     *     would put a secret's value into the URL, and whoever can rewrite the record, such as the author of a run
     *     folder copied from elsewhere, chooses the host that gets it. The redactor wrote the replacement because
     *     the operator's own source held the value; the operator has that source and can start the run again.</li>
     * <li>A run folder that is not in the runs folder of this home is not cloned for either. Its record was not
     *     written here, the ledger of this home does not speak for it, and the source in it is nobody's choice on
     *     this machine: a URL that the operator's credential helper or SSH agent would answer, or a local directory
     *     in which git on the host would run {@code git-upload-pack}.</li>
     * </ul>
     * A checkout that is still there is used whatever the record says of its source: nothing is cloned.
     *
     * @param recorded the source and the commit as the record names them
     * @param inStore  whether the run folder is in this home's {@code runs/}
     * @throws RunRefusedException if it is missing and is not cloned again: for one of the reasons above, or the
     *                             source cannot be reached, or no longer has the commit
     */
    Restored restore(String runId, Recorded recorded, boolean inStore) {
        String what = "Run " + runId + " cannot resume";
        Path directory = directoryOf(runId, what);
        if (sources.exists(directory)) {
            return new Restored(directory, false);
        }
        String missing = what + ": its checkout " + directory + " is missing";
        if (recorded.sha() == null || !SHA.matcher(recorded.sha()).matches()) {
            throw new RunRefusedException(missing + ", and its record names no commit to check out again");
        }
        if (!inStore) {
            throw new RunRefusedException(missing + ", and the run folder is not in the runs folder of this home, so"
                    + " the source its record names (" + recorded.source() + ") is not cloned: the record was not"
                    + " written here; start the run again with --source");
        }
        if (recorded.source().contains(Redactor.MARKER)) {
            throw new RunRefusedException(missing + ", and its recorded source " + recorded.source() + " holds a"
                    + " redacted secret, which a resume does not put back into the URL of a clone; start the run"
                    + " again with --source");
        }
        SourceCheckout.Checkout again;
        try {
            again = sources.checkout(recorded.source(), recorded.sha(), directory);
        } catch (IllegalArgumentException | IllegalStateException | UncheckedIOException e) {
            throw new RunRefusedException(missing + ", and checking " + recorded.source() + " out again at "
                    + recorded.sha() + " failed: " + e.getMessage());
        }
        if (!recorded.sha().equals(again.sha())) {
            RunRefusedException refused = new RunRefusedException(missing + ", and checking " + recorded.source()
                    + " out again at " + recorded.sha() + " gave the commit " + again.sha());
            removeAfter(directory, refused);
            throw refused;
        }
        return new Restored(directory, true);
    }

    /**
     * After this process left run {@code run} at {@code status}: at a final status its checkout goes, unless a
     * command of the run could write in it.
     *
     * @return the checkout that stays although the run has finished, with the reason; {@code null} when the run has
     *         none, it was removed, or the run has not finished
     */
    KeptCheckout afterRun(StoredRun run, RunStatus status) {
        if (!RunRecords.isFinal(status)) {
            return null;
        }
        List<RecordedEvent> events;
        try {
            events = RunRecords.events(run);
        } catch (RuntimeException e) {
            // The record cannot tell whether a command wrote in it: the checkout stays, finishing reports the record
            return keptIfThere(run.runId(), "the record cannot be read: " + e.getMessage());
        }
        return afterRun(run.runId(), events, status);
    }

    /**
     * Like {@link #afterRun(StoredRun, RunStatus)}, with the run's events read already: those up to its end are
     * enough, since what decides is {@code run.started} and every {@code step.command}.
     */
    KeptCheckout afterRun(String runId, List<RecordedEvent> events, RunStatus status) {
        if (!RunRecords.isFinal(status)) {
            return null;
        }
        Recorded recorded;
        try {
            recorded = Recorded.of(RunRecords.started(events));
        } catch (RuntimeException e) {
            return keptIfThere(runId, "the record cannot be read: " + e.getMessage());
        }
        if (recorded == null) {
            return null;
        }
        Path directory = sources.directoryOf(runId);
        if (!sources.exists(directory)) {
            return null;
        }
        String writer = writer(events, checkoutPaths(events, directory));
        if (writer != null) {
            return new KeptCheckout(directory.toString(), writer);
        }
        try {
            sources.remove(directory);
            return null;
        } catch (RuntimeException e) {
            return new KeptCheckout(directory.toString(), "it could not be removed: " + e.getMessage());
        }
    }

    private KeptCheckout keptIfThere(String runId, String reason) {
        try {
            Path directory = sources.directoryOf(runId);
            return sources.exists(directory) ? new KeptCheckout(directory.toString(), reason) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Where the run's commands saw the checkout: as {@code run.started} recorded it, and as it is now. */
    private List<String> checkoutPaths(List<RecordedEvent> events, Path directory) {
        List<String> paths = new ArrayList<>();
        String recorded = RunRecords.started(events).fields().path("workspace").asText(null);
        if (recorded != null) {
            paths.add(recorded);
        }
        try {
            paths.add(directories.realPath(directory));
        } catch (IllegalArgumentException e) {
            paths.add(directory.toString());
        }
        return paths;
    }

    /**
     * Why a command of the run could write in the checkout; {@code null} when none could. A command on the host can
     * write anywhere its user can; a container could write through a writable mount of the checkout, of something in
     * it or of a folder that holds it. What the record does not say for sure counts as writable.
     */
    static String writer(List<RecordedEvent> events, List<String> checkoutPaths) {
        for (RecordedEvent event : events) {
            if (!RecordTypes.STEP_COMMAND.equals(event.type())) {
                continue;
            }
            JsonNode host = event.fields().path("host");
            if (!host.isBoolean() || host.booleanValue()) {
                return "a command of the run ran on the host, where it could write in it";
            }
            for (JsonNode mount : event.fields().path("mounts")) {
                JsonNode readOnly = mount.path("read_only");
                if (readOnly.isBoolean() && readOnly.booleanValue()) {
                    continue;
                }
                String path = mount.path("host").asText(null);
                if (path == null || checkoutPaths.stream()
                        .anyMatch(checkout -> RunPaths.within(path, checkout) || RunPaths.within(checkout, path))) {
                    return "a command of the run could write in it";
                }
            }
        }
        return null;
    }

    private Path directoryOf(String runId, String what) {
        try {
            return sources.directoryOf(runId);
        } catch (IllegalArgumentException e) {
            throw new RunRefusedException(what + ": " + e.getMessage());
        }
    }

    /** Removes a checkout after {@code failure}, which stays the one to report. */
    private void removeAfter(Path directory, RuntimeException failure) {
        try {
            sources.remove(directory);
        } catch (RuntimeException removal) {
            failure.addSuppressed(removal);
        }
    }
}
