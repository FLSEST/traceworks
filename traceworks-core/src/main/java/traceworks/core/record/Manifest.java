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
 * The summary written when a run ends: what ran, where, with which inputs and images, and the head of the record.
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
 */
public record Manifest(String runId, String workflowId, String workflowName, String packageDigest,
                       String inputsDigest, String execution, List<Image> images, List<String> models, int events,
                       String head, RunStatus status, GateView gate, Instant startedAt, Instant finishedAt,
                       String version, SealView seal, String runStarted) {

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
                view.finishedAt(), version, view.seal(), view.runStarted());
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
                otherModels, events, head, status, gate, startedAt, finishedAt, version, seal, runStarted);
    }

    /** The same manifest without the {@code run.started} line, as a version that did not name it wrote it. */
    public Manifest withoutRunStarted() {
        return new Manifest(runId, workflowId, workflowName, packageDigest, inputsDigest, execution, images,
                models, events, head, status, gate, startedAt, finishedAt, version, seal, null);
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
        root.put("package_digest", packageDigest);
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
