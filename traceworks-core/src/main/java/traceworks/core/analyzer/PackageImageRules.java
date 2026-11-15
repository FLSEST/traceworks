package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.container.ImageCatalog;
import traceworks.core.container.ImageFacts;
import traceworks.core.engine.ChildWorkflows;
import traceworks.core.engine.NestedBlocks;
import traceworks.core.engine.RunRequirements;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.PackageImage;
import traceworks.core.port.WorkflowResolver;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rules about a package's own images ({@code ./images/<name>}): which workflow may name them, how their
 * directories are named, and what their Dockerfiles say (the {@code FROM} lines and the description label).
 * <ul>
 *   <li>{@code child-package-image} ({@code ERROR}): a child workflow that a {@code sub-workflow} or {@code foreach}
 *       block runs by a literal {@code workflow_id}, at any depth, comes from another package than the checked one and
 *       names a {@code ./images/<name>}. A run resolves {@code ./images/} against the package it was started with, so
 *       the run refuses that child ({@link RunRequirements#packageImagesOfAnotherPackage}). Reported at the
 *       top-level block that leads to the child; the children are looked into only when the analyzer has a resolver
 *       for them.</li>
 *   <li>{@code invalid-image-name} ({@code WARNING}): a directory under the package's {@code images/} has a
 *       Dockerfile but a name that is not a valid package image name ({@value ImageCatalog#NAME_SYNTAX}): no
 *       {@code ./images/} reference can name it, so Traceworks never builds or runs it.</li>
 *   <li>{@code unpinned-base-image} ({@code WARNING}): a Dockerfile under the package's {@code images/} whose
 *       {@code FROM} names no digest ({@code @sha256:}), so the image is not repeatable.</li>
 *   <li>{@code image-without-description} ({@code WARNING}): a Dockerfile under the package's {@code images/} without
 *       the label {@code org.opencontainers.image.description}, so the record cannot say what the image holds.</li>
 * </ul>
 */
public final class PackageImageRules {

    private PackageImageRules() {
    }

    /**
     * A {@code FROM} line of a Dockerfile: its flags, the image and what follows (such as {@code AS name}).
     *
     * <p>The flags are matched possessively ({@code *+}), as in {@code ImageFacts}: the engine then keeps no way back
     * into the group, and without that it takes a stack frame per repetition, so a line of a package's Dockerfile
     * with thousands of flags overflowed the stack of {@code traceworks check}.
     */
    private static final Pattern FROM =
            Pattern.compile("(?i)^\\s*FROM((?:\\s+--\\S+)*+)\\s+(\\S+)(?:\\s+AS\\s+(\\S+))?.*$");

    /**
     * @param children finds the workflows of {@code sub-workflow} and {@code foreach} blocks; {@code null} for none
     */
    static void apply(List<BlockDefinition> blocks, WorkflowResolver children, List<AnalyzerFinding> out) {
        if (children == null) {
            return;
        }
        for (int i = 0; i < blocks.size(); i++) {
            BlockDefinition block = blocks.get(i);
            String ref = block == null ? null : ChildWorkflows.literalRef(block.getType(), block.getConfig());
            if (ref == null) {
                continue;
            }
            WorkflowDefinition child;
            try {
                child = children.resolve(ref);
            } catch (RuntimeException e) {
                continue;   // a reference that names no workflow fails at run time, with its own message
            }
            boolean childOfAnother = children.fromAnotherPackage(child);
            for (ChildWorkflows.Reached reached : ChildWorkflows.reached(child, children.forChild(child))) {
                boolean otherPackage = reached.definition() == child ? childOfAnother : reached.otherPackage();
                if (otherPackage) {
                    report(block, i, reached.definition() == child ? ref : null, reached.definition(), out);
                }
            }
        }
    }

    /** @param ref the reference of the block to the child; {@code null} for a child of a child */
    private static void report(BlockDefinition block, int index, String ref, WorkflowDefinition child,
                               List<AnalyzerFinding> out) {
        List<BlockDefinition> childBlocks = child.blocks();
        for (RunRequirements.Problem problem : RunRequirements.packageImagesOfAnotherPackage(child)) {
            BlockDefinition at = childBlocks.get(problem.blockIndex());
            NestedBlocks.Nested nested = problem.nested();
            String where = "Block " + (problem.blockIndex() + 1) + named(at.getName())
                    + (nested == null ? "" : " › " + nested.path() + named(nested.name()));
            out.add(AnalyzerFinding.of("child-package-image", Severity.ERROR, index, block.getName(),
                    "the child workflow " + child.id() + (ref == null || ref.equals(child.id()) ? "" : " (" + ref + ")")
                            + " comes from another package, and its " + where + ": " + problem.message()
                            + " (Traceworks refuses that child)"));
        }
    }

    /** @param names the directories under {@code images/} with a Dockerfile and a name that is not valid */
    static void invalidNames(List<String> names, List<AnalyzerFinding> out) {
        for (String name : names) {
            String lower = name.toLowerCase(Locale.ROOT);
            String example = ImageCatalog.validPackageImageName(ImageCatalog.PACKAGE_IMAGE_PREFIX + lower)
                    ? ", for example to " + lower : "";
            out.add(AnalyzerFinding.of("invalid-image-name", Severity.WARNING, -1, null, "images/" + name + " has a"
                    + " Dockerfile, but \"" + name + "\" is not a valid package image name ("
                    + ImageCatalog.NAME_SYNTAX + "): no ./images/ reference can name it, so Traceworks never builds"
                    + " or runs it — rename the directory" + example));
        }
    }

    /** {@code unpinned-base-image}: a package Dockerfile whose {@code FROM} names no digest ({@code @sha256:}). */
    static void unpinnedBaseImages(java.util.Collection<PackageImage> images, List<AnalyzerFinding> out) {
        for (PackageImage image : images) {
            for (String base : unpinnedBases(image.dockerfile())) {
                out.add(AnalyzerFinding.of("unpinned-base-image", Severity.WARNING, -1, null,
                        "images/" + image.name() + "/Dockerfile starts FROM " + base + " without a digest — the same"
                                + " Dockerfile can build a different image tomorrow; pin it as " + base
                                + "@sha256:<digest>"));
            }
        }
    }

    private static final Pattern DIGEST_REFERENCE = Pattern.compile("[^\\s@#?]+@sha256:[0-9a-f]{64}");
    private static final Pattern COPY = Pattern.compile("(?i)^\\s*COPY\\s+(.*)$");
    private static final Pattern RUN = Pattern.compile("(?i)^\\s*RUN\\s+(.*)$");
    private static final Pattern COPY_FROM = Pattern.compile("(?i)(?:^|\\s)--from=(\\S+)");
    private static final Pattern MOUNT = Pattern.compile("(?i)(?:^|\\s)--mount=(\\S+)");

    /**
     * Whether an image reference names one image for good: it ends with {@code @sha256:} and 64 lowercase hex
     * characters. The text {@code @sha256:} somewhere in it is not enough.
     */
    public static boolean pinnedByDigest(String image) {
        return image != null && DIGEST_REFERENCE.matcher(image).matches();
    }

    /**
     * Every place of a Dockerfile that names an image without a digest, in the order of its lines, each as the
     * instruction and the image: the base of a {@code FROM}, the image of a {@code COPY --from=} and of a
     * {@code RUN --mount=...,from=}. {@code scratch}, and a name or number of an earlier build stage of the same file,
     * are no image. A line that ends with a backslash goes on in the next. Empty for {@code null}.
     */
    public static List<String> unpinnedReferences(String dockerfile) {
        List<String> found = new java.util.ArrayList<>();
        if (dockerfile == null) return found;
        Set<String> stages = new LinkedHashSet<>();
        int stageCount = 0;
        StringBuilder joined = new StringBuilder();
        for (String raw : dockerfile.split("\\R")) {
            if (raw.stripTrailing().endsWith("\\")) {
                String part = raw.stripTrailing();
                joined.append(part, 0, part.length() - 1).append(' ');
                continue;
            }
            String line = joined.append(raw).toString();
            joined.setLength(0);
            Matcher from = FROM.matcher(line);
            if (from.matches()) {
                String base = from.group(2);
                if (!stage(base, stages, stageCount) && !base.equalsIgnoreCase("scratch") && !pinnedByDigest(base)) {
                    found.add("FROM " + base);
                }
                if (from.group(3) != null) stages.add(from.group(3).toLowerCase(Locale.ROOT));
                stageCount++;
                continue;
            }
            Matcher copy = COPY.matcher(line);
            if (copy.matches()) {
                Matcher flag = COPY_FROM.matcher(copy.group(1));
                while (flag.find()) {
                    if (!stage(flag.group(1), stages, stageCount) && !pinnedByDigest(flag.group(1))) {
                        found.add("COPY --from=" + flag.group(1));
                    }
                }
                continue;
            }
            Matcher run = RUN.matcher(line);
            if (run.matches()) {
                Matcher mount = MOUNT.matcher(run.group(1));
                while (mount.find()) {
                    for (String option : mount.group(1).split(",")) {
                        if (!option.toLowerCase(Locale.ROOT).startsWith("from=")) continue;
                        String image = option.substring("from=".length());
                        if (!stage(image, stages, stageCount) && !pinnedByDigest(image)) {
                            found.add("RUN --mount from=" + image);
                        }
                    }
                }
            }
        }
        return found;
    }

    /** Whether {@code name} is an earlier build stage: its name, or its number. */
    private static boolean stage(String name, Set<String> stages, int stageCount) {
        if (stages.contains(name.toLowerCase(Locale.ROOT))) return true;
        return name.matches("\\d{1,4}") && Integer.parseInt(name) < stageCount;
    }

    /**
     * The bases a Dockerfile starts {@code FROM} without a digest, in the order of its lines; {@code scratch} and an
     * earlier stage of the same file are no such base. Empty for {@code null}.
     */
    public static List<String> unpinnedBases(String dockerfile) {
        List<String> bases = new java.util.ArrayList<>();
        if (dockerfile == null) return bases;
        Set<String> stages = new LinkedHashSet<>();
        for (String line : dockerfile.split("\\R")) {
            Matcher from = FROM.matcher(line);
            if (!from.matches()) continue;
            String base = from.group(2);
            boolean earlierStage = stages.contains(base.toLowerCase(Locale.ROOT));
            if (from.group(3) != null) stages.add(from.group(3).toLowerCase(Locale.ROOT));
            if (earlierStage || base.equalsIgnoreCase("scratch") || base.contains("@sha256:")) continue;
            bases.add(base);
        }
        return bases;
    }

    /**
     * {@code image-without-description}: a package Dockerfile without the label {@value ImageFacts#DESCRIPTION_LABEL},
     * so the record cannot say what the image holds.
     */
    static void imagesWithoutDescription(java.util.Collection<PackageImage> images, List<AnalyzerFinding> out) {
        for (PackageImage image : images) {
            if (image.dockerfile() == null) continue;
            if (ImageFacts.of(image.name(), image.dockerfile()).description() != null) continue;
            out.add(AnalyzerFinding.of("image-without-description", Severity.WARNING, -1, null,
                    "images/" + image.name() + "/Dockerfile has no label " + ImageFacts.DESCRIPTION_LABEL
                            + ", so the record cannot say what the image holds; add LABEL "
                            + ImageFacts.DESCRIPTION_LABEL + "=\"<the tools, on which base>\""));
        }
    }

    private static String named(String name) {
        return name == null ? "" : " \"" + name + "\"";
    }
}
