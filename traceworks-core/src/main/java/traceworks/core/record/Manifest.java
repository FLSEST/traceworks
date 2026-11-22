package traceworks.core.record;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import traceworks.core.model.RunStatus;
import traceworks.core.port.BlobStore;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The summary written when a run ends: what ran (the run's package and every package it called), where, with which
 * inputs and images, and the head of the record.
 * Manifests are unsigned, and the tool versions are empty, in this version. A run of a sealed package names the seal:
 * {@code seal} is {@code sealed}, followed by the seal digest, the signer and the key's fingerprint; any other run
 * says {@code unsealed} and has none of the three.
 *
 * @param inputsDigest the SHA-256 of the inputs blob
 * @param execution    where the run ran its steps, as {@code run.started} records it: {@code container} or
 *                     {@code host}
 * @param images       every image a container of the run started from, each {@code {reference, id}} once, sorted
 * @param models       the distinct models the steps' metadata names, sorted
 * @param gate         the run's verdict; {@code null} when no step reported one
 * @param seal         the seal the run's package carried at the start; {@code null} for a package that was not sealed
 * @param runStarted   the SHA-256 of the {@code run.started} line, which the start entry of the ledger names too, so
 *                     an end entry can be matched to its start entry without the run folder; {@code null} for a
 *                     manifest of a version that did not name it, which then has no such field
 * @param starter      the project of the run and who started it, as {@code run.started} records them, so that the
 *                     end entry of the ledger names both; {@code null} for a manifest of a version that named
 *                     neither, which then has no such fields
 * @param calledPackages every package a child run of the run was loaded from that is not what the run started
 *                     with (another package, or the run's own with another digest, changed while the run ran),
 *                     each {@code {workflow_id, package_digest}} once, sorted: {@code packageDigest} covers the
 *                     package the run was started with only. Empty for a run that called none and for a record of a
 *                     version that did not name them; the manifest then has no such field
 * @param resumedWith  the packages the run went on with after its start ({@code --allow-changed-package}), in
 *                     order and without repeats ({@link RunView#resumedWith}): {@code packageDigest} is the
 *                     package at the start only. Resumed twice with one changed package it holds that package
 *                     once. Empty for a run that only ever went on with the package of its start and for a
 *                     manifest of a version that did not list them; the manifest then has no such field
 */
public record Manifest(String runId, String workflowId, String workflowName, String packageDigest,
                       String inputsDigest, String execution, List<Image> images, List<String> models, int events,
                       String head, RunStatus status, GateView gate, Instant startedAt, Instant finishedAt,
                       String version, SealView seal, String runStarted, RunStarter starter,
                       List<CalledPackage> calledPackages, List<String> resumedWith) {

    /** A manifest of a run that was resumed with no other package than the one it started with. */
    public Manifest(String runId, String workflowId, String workflowName, String packageDigest,
                    String inputsDigest, String execution, List<Image> images, List<String> models, int events,
                    String head, RunStatus status, GateView gate, Instant startedAt, Instant finishedAt,
                    String version, SealView seal, String runStarted, RunStarter starter,
                    List<CalledPackage> calledPackages) {
        this(runId, workflowId, workflowName, packageDigest, inputsDigest, execution, images, models, events, head,
                status, gate, startedAt, finishedAt, version, seal, runStarted, starter, calledPackages, List.of());
    }

    /** A manifest of a run that called no workflow of another package. */
    public Manifest(String runId, String workflowId, String workflowName, String packageDigest,
                    String inputsDigest, String execution, List<Image> images, List<String> models, int events,
                    String head, RunStatus status, GateView gate, Instant startedAt, Instant finishedAt,
                    String version, SealView seal, String runStarted, RunStarter starter) {
        this(runId, workflowId, workflowName, packageDigest, inputsDigest, execution, images, models, events, head,
                status, gate, startedAt, finishedAt, version, seal, runStarted, starter, List.of());
    }

    /** A manifest that names neither the project nor who started the run, as an earlier version wrote it. */
    public Manifest(String runId, String workflowId, String workflowName, String packageDigest,
                    String inputsDigest, String execution, List<Image> images, List<String> models, int events,
                    String head, RunStatus status, GateView gate, Instant startedAt, Instant finishedAt,
                    String version, SealView seal, String runStarted) {
        this(runId, workflowId, workflowName, packageDigest, inputsDigest, execution, images, models, events, head,
                status, gate, startedAt, finishedAt, version, seal, runStarted, null);
    }

    /** A manifest that does not name the {@code run.started} line, as an earlier version wrote it. */
    public Manifest(String runId, String workflowId, String workflowName, String packageDigest,
                    String inputsDigest, String execution, List<Image> images, List<String> models, int events,
                    String head, RunStatus status, GateView gate, Instant startedAt, Instant finishedAt,
                    String version, SealView seal) {
        this(runId, workflowId, workflowName, packageDigest, inputsDigest, execution, images, models, events, head,
                status, gate, startedAt, finishedAt, version, seal, null);
    }

    /** The manifest of a run whose package was not sealed. */
    public Manifest(String runId, String workflowId, String workflowName, String packageDigest,
                    String inputsDigest, String execution, List<Image> images, List<String> models, int events,
                    String head, RunStatus status, GateView gate, Instant startedAt, Instant finishedAt,
                    String version) {
        this(runId, workflowId, workflowName, packageDigest, inputsDigest, execution, images, models, events, head,
                status, gate, startedAt, finishedAt, version, null);
    }


    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final Set<RunStatus> FINAL =
            Set.of(RunStatus.COMPLETED, RunStatus.COMPLETED_WITH_ERRORS, RunStatus.FAILED, RunStatus.CANCELLED);

    public Manifest {
        images = List.copyOf(images);
        models = List.copyOf(models);
        calledPackages = List.copyOf(calledPackages);
        resumedWith = List.copyOf(resumedWith);
    }

    /**
     * An image a container of the run started from, as the step named it and as the runtime knew it.
     *
     * @param reference the image as the step names it ({@code shell} for the default image, {@code ./images/<name>}
     *                  for a package image), from {@code step.command}'s {@code image}
     * @param id        the local image ID ({@code sha256:...}) the container started from, its {@code image_id}
     */
    public record Image(String reference, String id) {}

    private static final Comparator<Image> IMAGE_ORDER =
            Comparator.comparing(Image::reference).thenComparing(Image::id);

    /**
     * A package a child run of the run was loaded from, as the child's {@code run.package} line names it.
     *
     * @param workflowId    the {@code id} of the workflow that ran as the child
     * @param packageDigest the digest of its package as loaded for the call
     */
    public record CalledPackage(String workflowId, String packageDigest) {}

    private static final Comparator<CalledPackage> CALLED_ORDER =
            Comparator.comparing(CalledPackage::workflowId, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(CalledPackage::packageDigest, Comparator.nullsFirst(Comparator.naturalOrder()));

    /**
     * The packages the child runs of {@code view} were loaded from, at every depth, each
     * {@code {workflow_id, package_digest}} once, sorted: what a manifest lists. A package that changed between two
     * calls of one run is there with both digests.
     */
    public static List<CalledPackage> calledPackages(RunView view) {
        Set<CalledPackage> called = new TreeSet<>(CALLED_ORDER);
        view.packageCalls().forEach(call -> called.add(new CalledPackage(call.workflowId(), call.packageDigest())));
        return List.copyOf(called);
    }

    /**
     * @param blobs   where the step metadata is, for the models; a deleted metadata blob names no model
     * @param version the Traceworks version that wrote the record
     * @throws IllegalStateException if the run has not reached a final status
     */
    public static Manifest of(RunView view, BlobStore blobs, String version) {
        if (!FINAL.contains(view.status())) {
            throw new IllegalStateException("A run with the status " + view.status() + " has no manifest");
        }
        List<String> models = models(view, blobs);
        Set<Image> images = new TreeSet<>(IMAGE_ORDER);
        collectImages(view, images);
        return new Manifest(view.runId(), view.workflowId(), view.workflowName(), view.packageDigest(),
                view.inputs() == null ? null : view.inputs().sha256(), view.execution(), List.copyOf(images),
                models, view.eventCount(), view.head(), view.status(), view.gate(), view.startedAt(),
                view.finishedAt(), version, view.seal(), view.runStarted(),
                new RunStarter(view.project(), view.actor()), calledPackages(view), resumedWith(view));
    }

    /**
     * The package digests a manifest lists as those the run was resumed with: the ones the record's resumes name
     * ({@link RunView#resumedWith}), where the version that wrote the manifest lists them
     * ({@link RunView#recordsResumedWith}); none for a record whose last start or resume is of a version that did
     * not, since its manifest has no such field.
     */
    public static List<String> resumedWith(RunView view) {
        return view.recordsResumedWith() ? view.resumedWith() : List.of();
    }

    /**
     * The distinct models the metadata of the run's steps names, child runs included, sorted: what a manifest lists,
     * also of a run that has not finished.
     *
     * @param blobs where the step metadata is; a deleted metadata blob names no model
     */
    public static List<String> models(RunView view, BlobStore blobs) {
        Set<String> models = new TreeSet<>();
        collectModels(view, blobs, models);
        return List.copyOf(models);
    }

    /**
     * Whether {@code written} can be the models of this run: what a manifest written when the run finished may list.
     * With every metadata blob there, that is exactly {@link #models}. A deleted metadata blob names no model any
     * more, so the list may then hold more than the record still tells, but within limits: it names every model the
     * remaining blobs name, at most one more model for each deleted blob (a blob names one model), and it is sorted
     * and distinct as a manifest writes it. Without these limits, deleting one blob would let the whole list be
     * rewritten, the models of the other steps included.
     */
    public static boolean modelsPossible(List<String> written, RunView view, BlobStore blobs) {
        List<String> told = models(view, blobs);
        Set<String> deleted = new TreeSet<>();
        collectDeletedMetadata(view, blobs, deleted);
        for (int i = 1; i < written.size(); i++) {
            if (written.get(i - 1).compareTo(written.get(i)) >= 0) {
                return false;
            }
        }
        return written.containsAll(told) && written.size() - told.size() <= deleted.size();
    }

    /** Whether a metadata blob of the run or of one of its children is deleted. */
    public static boolean metadataDeleted(RunView view, BlobStore blobs) {
        Set<String> deleted = new TreeSet<>();
        collectDeletedMetadata(view, blobs, deleted);
        return !deleted.isEmpty();
    }

    private static void collectDeletedMetadata(RunView view, BlobStore blobs, Set<String> deleted) {
        for (StepView step : view.steps()) {
            if (step.metadata() != null && blobs.getUnverified(step.metadata().sha256()).isEmpty()) {
                deleted.add(step.metadata().sha256());
            }
        }
        view.children().values().forEach(child -> collectDeletedMetadata(child, blobs, deleted));
    }

    /** The same manifest with other models, for a check that cannot tell them from the record. */
    public Manifest withModels(List<String> otherModels) {
        return new Manifest(runId, workflowId, workflowName, packageDigest, inputsDigest, execution, images,
                otherModels, events, head, status, gate, startedAt, finishedAt, version, seal, runStarted, starter,
                calledPackages, resumedWith);
    }

    /** The same manifest without the {@code run.started} line, as a version that did not name it wrote it. */
    public Manifest withoutRunStarted() {
        return new Manifest(runId, workflowId, workflowName, packageDigest, inputsDigest, execution, images,
                models, events, head, status, gate, startedAt, finishedAt, version, seal, null, starter,
                calledPackages, resumedWith);
    }

    /**
     * The same manifest without the project and without who started the run, as a version that named neither wrote
     * it.
     */
    public Manifest withoutStarter() {
        return new Manifest(runId, workflowId, workflowName, packageDigest, inputsDigest, execution, images,
                models, events, head, status, gate, startedAt, finishedAt, version, seal, runStarted, null,
                calledPackages, resumedWith);
    }

    /** The images of the commands that started a container, in the run and its children; a refused one has no ID. */
    private static void collectImages(RunView view, Set<Image> images) {
        for (StepView step : view.steps()) {
            for (CommandView command : step.commands()) {
                if (command.image() != null && command.imageId() != null) {
                    images.add(new Image(command.image(), command.imageId()));
                }
            }
        }
        view.children().values().forEach(child -> collectImages(child, images));
    }

    private static void collectModels(RunView view, BlobStore blobs, Set<String> models) {
        for (StepView step : view.steps()) {
            if (step.metadata() == null) {
                continue;
            }
            // Unverified: verify builds the manifest it expects even of a record whose blob changed, and reports the
            // change itself; at a run's end the blobs were just written
            blobs.getUnverified(step.metadata().sha256()).ifPresent(bytes -> {
                try {
                    JsonNode model = MAPPER.readTree(bytes).path("model");
                    if (model.isTextual() && !model.asText().isEmpty()) {
                        models.add(model.asText());
                    }
                } catch (java.io.IOException e) {
                    // Metadata that is not JSON names no model
                }
            });
        }
        view.children().values().forEach(child -> collectModels(child, blobs, models));
    }

    /** The manifest as pretty-printed JSON in a fixed field order, ending with a line feed. */
    public byte[] toJson() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("run_id", runId);
        ObjectNode workflow = root.putObject("workflow");
        workflow.put("id", workflowId);
        workflow.put("name", workflowName);
        if (starter != null) {
            // Always both, the project as null for a run that names none: a manifest without them is one of a
            // version that named neither
            root.put("project", starter.project());
            root.put("actor", starter.actor());
        }
        root.put("package_digest", packageDigest);
        if (!calledPackages.isEmpty()) {
            // Only for a run that called a workflow of another package: every other manifest keeps its bytes
            ArrayNode calledArray = root.putArray("called_packages");
            for (CalledPackage called : calledPackages) {
                ObjectNode entry = calledArray.addObject();
                entry.put("workflow_id", called.workflowId());
                entry.put("package_digest", called.packageDigest());
            }
        }
        if (!resumedWith.isEmpty()) {
            // Only for a run that was resumed with another package than it started with: every other manifest
            // keeps its bytes
            ArrayNode resumedArray = root.putArray("resumed_with");
            resumedWith.forEach(resumedArray::add);
        }
        if (seal == null) {
            root.put("seal", "unsealed");
        } else {
            root.put("seal", "sealed");
            root.put("seal_digest", seal.digest());
            root.put("seal_signer", seal.signer());
            root.put("seal_key", seal.key());
        }
        root.put("inputs_digest", inputsDigest);
        root.put("execution", execution);
        ArrayNode imageArray = root.putArray("images");
        for (Image image : images) {
            ObjectNode entry = imageArray.addObject();
            entry.put("reference", image.reference());
            entry.put("id", image.id());
        }
        var modelArray = root.putArray("models");
        models.forEach(modelArray::add);
        root.putObject("tool_versions");
        root.put("events", events);
        root.put("head", head);
        if (runStarted != null) {
            root.put("run_started", runStarted);
        }
        root.put("status", status.name());
        if (gate == null) {
            root.putNull("gate");
        } else {
            ObjectNode g = root.putObject("gate");
            g.put("status", gate.status());
            g.put("category", gate.category());
        }
        root.put("started_at", startedAt == null ? null : AT.format(startedAt));
        root.put("finished_at", finishedAt == null ? null : AT.format(finishedAt));
        root.put("traceworks_version", version);
        root.putNull("signature");
        try {
            return (MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n")
                    .getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write the manifest: " + e.getOriginalMessage(), e);
        }
    }
}
