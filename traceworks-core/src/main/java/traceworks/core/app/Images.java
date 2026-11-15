package traceworks.core.app;

import traceworks.core.container.ImageCatalog;
import traceworks.core.engine.ChildWorkflows;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.AiNetwork;
import traceworks.core.port.BuiltInImageSource;
import traceworks.core.port.BuiltInImageSource.BuildContext;
import traceworks.core.port.ContainerRuntime;
import traceworks.core.port.LoadedPackage;
import traceworks.core.port.PackageImage;
import traceworks.core.port.PackageLoader;
import traceworks.core.port.WorkflowResolver;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The images of a run: builds the built-in images and a package's images, and tells which images a run
 * needs and the runtime does not have. A run itself never builds and never pulls.
 */
public final class Images {

    /** The AI providers that have a built-in image of the same name. */
    private static final List<String> AI_IMAGES = List.of("claude", "pi");

    private static final String BUILD_COMMAND = "traceworks images build";

    private final ContainerRuntime runtime;
    private final BuiltInImageSource builtIns;
    private final PackageLoader packages;
    private final List<Path> packagePath;
    private final Map<String, String> aiImages;
    private final AiNetwork aiNetwork;

    /** Without a package path: a build with a package finds its literal child workflows by path, not by id. */
    public Images(ContainerRuntime runtime, BuiltInImageSource builtIns, PackageLoader packages) {
        this(runtime, builtIns, packages, List.of());
    }

    /**
     * @param packagePath where a {@code workflow_id} that is an id is looked for ({@code package_path}), so a build
     *                    with a package finds its literal child workflows
     */
    public Images(ContainerRuntime runtime, BuiltInImageSource builtIns, PackageLoader packages,
                  List<Path> packagePath) {
        this(runtime, builtIns, packages, packagePath, Map.of());
    }

    /**
     * @param aiImages the operator's images for AI CLIs ({@code ai_images}) by provider name in lower case: a start
     *                 check asks for these instead of the built-in images of those CLIs. A build still builds the
     *                 built-in ones, and never the operator's
     */
    public Images(ContainerRuntime runtime, BuiltInImageSource builtIns, PackageLoader packages,
                  List<Path> packagePath, Map<String, String> aiImages) {
        this(runtime, builtIns, packages, packagePath, aiImages, AiNetwork.RESTRICTED);
    }

    /**
     * @param aiNetwork where the containers of the AI CLIs can connect ({@code ai_network}): unless it is open, a
     *                  start check of a run with an AI step asks for the image of the egress proxy too
     */
    public Images(ContainerRuntime runtime, BuiltInImageSource builtIns, PackageLoader packages,
                  List<Path> packagePath, Map<String, String> aiImages, AiNetwork aiNetwork) {
        this.aiNetwork = aiNetwork;
        this.aiImages = Map.copyOf(aiImages);
        this.runtime = runtime;
        this.builtIns = builtIns;
        this.packages = packages;
        this.packagePath = List.copyOf(packagePath);
    }

    /**
     * What a build did.
     *
     * @param lines    one line per image it built or pulled: {@code built <tag>} or {@code pulled <reference>}
     * @param failures one line per image it could not build or pull, with the runtime's error, and per image
     *                 directory whose name is not a valid package image name; empty when all went well
     */
    public record Built(List<String> lines, List<String> failures) {
        public Built {
            lines = List.copyOf(lines);
            failures = List.copyOf(failures);
        }
    }

    /** The tag of a built-in image: {@code traceworks/<name>:<h>}, {@code <h>} from the image directory's digest. */
    public static String builtInTag(PackageImage image) {
        return ImageCatalog.tag("traceworks/" + image.name(), image.digest());
    }

    /** The tag of a package's image: {@code traceworks-pkg/<package id>/<name>:<h>}. */
    public static String packageTag(String packageId, PackageImage image) {
        return ImageCatalog.tag("traceworks-pkg/" + repositoryId(packageId) + "/" + image.name(), image.digest());
    }

    private static final java.util.regex.Pattern REPOSITORY_ID = java.util.regex.Pattern.compile("[a-z0-9][a-z0-9._-]*");

    /**
     * The package id as a part of an image repository: lowercase (a UUID is case-insensitive).
     *
     * @throws RunRefusedException if the lowercase id is not {@code [a-z0-9][a-z0-9._-]*}
     */
    static String repositoryId(String packageId) {
        String id = packageId == null ? "" : packageId.toLowerCase(Locale.ROOT);
        if (!REPOSITORY_ID.matcher(id).matches()) {
            throw new RunRefusedException("The package id \"" + packageId + "\" cannot name its images: in lowercase it"
                    + " must match [a-z0-9][a-z0-9._-]* (letters, digits, '.', '_' and '-')");
        }
        return id;
    }

    /**
     * The catalog of the tags a run resolves images with: the built-in images, and the package's.
     *
     * @param pkg the package, or {@code null} for none
     */
    public ImageCatalog catalog(LoadedPackage pkg) {
        Map<String, String> builtInTags = new LinkedHashMap<>();
        builtIns.all().forEach(image -> builtInTags.put(image.name(), builtInTag(image)));
        Map<String, String> packageTags = new LinkedHashMap<>();
        if (pkg != null) {
            pkg.images().values().forEach(image -> packageTags.put(image.name(), packageTag(pkg.definition().id(), image)));
        }
        return new ImageCatalog(builtInTags, packageTags);
    }

    /**
     * Builds the images steps run in, each once, and goes on after one that fails. Without a package: the
     * built-in images, {@code shell} first. With one: the built-in images the package uses ({@code shell}, which every
     * container run needs, the image of each AI provider that its steps and those of its literal child workflows
     * use, and {@code egress}, the proxy of an AI step, when they use any), then the package's images, then it pulls the registry images that the package and its literal children
     * name as literals.
     *
     * @param packageOrNull a package directory or its {@code workflow.json}; {@code null} for the built-in images
     * @throws RunRefusedException if the package cannot be loaded, or its id cannot name its images
     */
    public Built build(Path packageOrNull) {
        List<String> lines = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        if (packageOrNull == null) {
            builtIns.all().forEach(image -> buildBuiltIn(image, lines, failures));
            return new Built(lines, failures);
        }
        LoadedPackage pkg = load(packageOrNull);
        Map<PackageImage, String> packageTags = new LinkedHashMap<>();
        pkg.images().values().forEach(image -> packageTags.put(image, packageTag(pkg.definition().id(), image)));
        WorkflowResolver children = new PackageWorkflowResolver(pkg, packagePath, packages);
        Set<String> used = WorkflowUses.providers(pkg.definition(), children);
        for (PackageImage image : builtIns.all()) {
            if (image.name().equals(ImageCatalog.SHELL) || used.contains(image.name())
                    || image.name().equals(ImageCatalog.EGRESS) && !used.isEmpty()) {
                buildBuiltIn(image, lines, failures);
            }
        }
        packageTags.forEach((image, tag) -> transfer("build", tag, () -> runtime.build(tag, image.directory()),
                lines, failures));
        for (String name : pkg.invalidImageDirectories()) {
            failures.add("skipped images/" + name + ": \"" + name + "\" is not a valid package image name ("
                    + ImageCatalog.NAME_SYNTAX + "), so no ./images/ reference can name it; rename the directory");
        }
        for (String reference : WorkflowUses.literalImages(pkg.definition(), children)) {
            if (!reference.startsWith(ImageCatalog.PACKAGE_IMAGE_PREFIX)) {
                transfer("pull", reference, () -> runtime.pull(reference), lines, failures);
            }
        }
        return new Built(lines, failures);
    }

    private void buildBuiltIn(PackageImage image, List<String> lines, List<String> failures) {
        String tag = builtInTag(image);
        transfer("build", tag, () -> {
            try (BuildContext context = builtIns.context(image)) {
                runtime.build(tag, context.directory());
            }
        }, lines, failures);
    }

    /** A build or a pull that may fail. */
    @FunctionalInterface
    private interface Transfer {
        void run() throws IOException;
    }

    /** Runs one build or pull, and notes it as done ({@code built}, {@code pulled}) or failed, with the error. */
    private static void transfer(String what, String image, Transfer transfer, List<String> lines,
                                 List<String> failures) {
        try {
            transfer.run();
            lines.add((what.equals("build") ? "built " : "pulled ") + image);
        } catch (IOException | RuntimeException e) {
            failures.add("failed to " + what + " " + image + ": "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    /**
     * The images a run of the package needs that the runtime does not have, one line each with the command that
     * provides it: every literal {@code container_image} of the definition and of the workflows it reaches by a literal
     * {@code workflow_id} ({@link ChildWorkflows}), nested blocks included (one with a placeholder is checked when it
     * runs), the {@code shell} image in a container run, the image of every AI provider they use, and with any the
     * image of the egress proxy, unless the operator opened the AI network ({@code ai_network}). A
     * {@code ./images/<name>} in a child of another package is a line too: the run resolves it against its own
     * package, so it can never be the child's image.
     *
     * @param containerRun whether the run runs its commands in containers, so that the shell image is needed
     * @param children     finds the child workflows; {@code null} looks into none
     */
    public List<String> missing(LoadedPackage pkg, boolean containerRun, WorkflowResolver children) {
        return missing(pkg, containerRun, children, WorkflowUses.Picks.EVERY);
    }

    /**
     * {@link #missing(LoadedPackage, boolean, WorkflowResolver)} for the runs {@code picks} means: of a step that
     * refers to a model choice, the image of the picked option's provider, and none of an option that was not
     * picked.
     */
    List<String> missing(LoadedPackage pkg, boolean containerRun, WorkflowResolver children,
                         WorkflowUses.Picks picks) {
        ImageCatalog catalog;
        try {
            catalog = catalog(pkg);
        } catch (RunRefusedException e) {
            return List.of(e.getMessage());
        }
        String packageCommand = BUILD_COMMAND + " " + pkg.directory();
        Set<String> seen = new LinkedHashSet<>();
        List<String> lines = new ArrayList<>();
        if (containerRun) {
            check(catalog.builtInTags().get(ImageCatalog.SHELL), BUILD_COMMAND, seen, lines);
        }
        Set<String> providers = WorkflowUses.providers(pkg.definition(), children, picks);
        for (String name : AI_IMAGES) {
            if (!providers.contains(name)) {
                continue;
            }
            String operatorImage = aiImages.get(name);
            if (operatorImage == null) {
                check(catalog.builtInTags().get(name), BUILD_COMMAND, seen, lines);
            } else if (seen.add(operatorImage) && !present(operatorImage, lines)) {
                lines.add("image " + operatorImage + " is missing: it is the image of " + name + " in ai_images of"
                        + " traceworks.json, which Traceworks never builds or pulls; build or pull it yourself,"
                        + " or remove the entry to use the built-in image");
            }
        }
        if (!aiNetwork.open() && AI_IMAGES.stream().anyMatch(providers::contains)) {
            check(catalog.builtInTags().get(ImageCatalog.EGRESS), BUILD_COMMAND, seen, lines);
        }
        for (ChildWorkflows.Reached reached : ChildWorkflows.reached(pkg.definition(), children)) {
            if (!reached.otherPackage()) {
                continue;
            }
            WorkflowDefinition child = reached.definition();
            for (String image : WorkflowUses.literalImages(child)) {
                if (image.startsWith(ImageCatalog.PACKAGE_IMAGE_PREFIX) && seen.add(child.id() + " " + image)) {
                    lines.add("image " + image + " of the child workflow " + child.id() + " names an image of"
                            + " another package than the run's, and a run resolves ./images/ against its own package"
                            + " only: give the image a registry name, or run " + child.id() + " as a run of its own");
                }
            }
        }
        for (String image : WorkflowUses.literalImages(pkg.definition(), children)) {
            if (image.startsWith(ImageCatalog.PACKAGE_IMAGE_PREFIX)) {
                String tag = catalog.reference(image);
                if (!ImageCatalog.validPackageImageName(image)) {
                    if (seen.add(image)) {
                        String name = image.substring(ImageCatalog.PACKAGE_IMAGE_PREFIX.length());
                        lines.add("image " + image + ": \"" + name + "\" is not a valid package image name ("
                                + ImageCatalog.NAME_SYNTAX + ")");
                    }
                } else if (tag == null) {
                    if (seen.add(image)) {
                        lines.add("image " + image + " has no Dockerfile: " + pkg.directory() + "/images/"
                                + image.substring(ImageCatalog.PACKAGE_IMAGE_PREFIX.length()) + "/Dockerfile");
                    }
                } else {
                    check(tag, packageCommand, seen, lines);
                }
            } else {
                check(image, packageCommand, seen, lines);
            }
        }
        return lines;
    }

    private void check(String reference, String command, Set<String> seen, List<String> lines) {
        if (reference != null && seen.add(reference) && !present(reference, lines)) {
            lines.add("image " + reference + " is missing: run '" + command + "'");
        }
    }

    /**
     * Whether the image is present. A lookup that could not tell, such as one that timed out, is no missing image:
     * its own reason becomes the line, and the image counts as present for the caller.
     */
    private boolean present(String reference, List<String> lines) {
        ContainerRuntime.ImageLookup lookup = runtime.lookup(reference);
        if (lookup.failure() != null) {
            lines.add(lookup.failure());
            return true;
        }
        return lookup.imageId() != null;
    }

    private LoadedPackage load(Path location) {
        try {
            return packages.load(location);
        } catch (IllegalArgumentException e) {
            throw new RunRefusedException(e.getMessage());
        }
    }
}
