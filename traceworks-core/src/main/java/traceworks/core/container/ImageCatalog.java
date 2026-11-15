package traceworks.core.container;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The images Traceworks builds for a run, by name, and which image a {@code container_image} names. A
 * {@code container_image} is a registry reference, or {@code ./images/<name>} for an image the package builds from
 * {@code images/<name>/Dockerfile}; without one, a command runs in the built-in {@value #SHELL} image.
 *
 * @param builtInTags the tags of the built-in images ({@code shell}, {@code claude}, {@code pi} and {@code egress}), by
 *                    name: {@code traceworks/<name>:<h>}
 * @param packageTags the tags of the package's images, by name: {@code traceworks-pkg/<package id>/<name>:<h>}
 */
public record ImageCatalog(Map<String, String> builtInTags, Map<String, String> packageTags) {

    /** The built-in image a command without {@code container_image} runs in. */
    public static final String SHELL = "shell";

    /** The built-in image of the egress proxy, the only door of a container that connects to listed hosts. */
    public static final String EGRESS = "egress";

    /** How a {@code container_image} names one of the package's images. */
    public static final String PACKAGE_IMAGE_PREFIX = "./images/";

    /** What the name of a package image looks like. */
    public static final String NAME_SYNTAX = "[a-z0-9][a-z0-9._-]*";

    private static final Pattern NAME = Pattern.compile(NAME_SYNTAX);
    private static final Pattern DIGEST = Pattern.compile("(?:sha256:)?([0-9a-f]{12})[0-9a-f]*");

    public ImageCatalog {
        builtInTags = builtInTags == null ? Map.of() : Map.copyOf(builtInTags);
        packageTags = packageTags == null ? Map.of() : Map.copyOf(packageTags);
    }

    /**
     * The reference of the image {@code containerImage} names: the {@value #SHELL} tag for {@code null}, the package's
     * tag for {@code ./images/<name>}, and a registry reference as it is. {@code null} when Traceworks has no tag for
     * it: the built-in images or that package image are not known (not built), or {@code <name>} is no image name.
     */
    public String reference(String containerImage) {
        if (containerImage == null) {
            return builtInTags.get(SHELL);
        }
        if (containerImage.startsWith(PACKAGE_IMAGE_PREFIX)) {
            return validPackageImageName(containerImage)
                    ? packageTags.get(containerImage.substring(PACKAGE_IMAGE_PREFIX.length())) : null;
        }
        return containerImage;
    }

    /**
     * Whether {@code containerImage} names a built-in image ({@code traceworks images build} builds it): {@code null}
     * for the {@value #SHELL} image, or one of the built-in tags.
     */
    public boolean builtIn(String containerImage) {
        return containerImage == null || builtInTags.containsValue(containerImage);
    }

    /**
     * Whether {@code containerImage} names one of the package's images ({@code traceworks images build <package>}
     * builds it): {@code ./images/<name>}, valid or not, or one of the package tags.
     */
    public boolean packageImage(String containerImage) {
        return containerImage != null && (containerImage.startsWith(PACKAGE_IMAGE_PREFIX)
                || packageTags.containsValue(containerImage));
    }

    /** Whether {@code containerImage} is {@code ./images/<name>} with a valid {@code <name>} ({@value #NAME_SYNTAX}). */
    public static boolean validPackageImageName(String containerImage) {
        return containerImage != null && containerImage.startsWith(PACKAGE_IMAGE_PREFIX)
                && NAME.matcher(containerImage.substring(PACKAGE_IMAGE_PREFIX.length())).matches();
    }

    /**
     * The tag of an image Traceworks builds: {@code <repository>:<h>}, where {@code <h>} is the first 12 hex characters
     * of the image directory's digest, so a changed directory gets a new tag.
     *
     * @param directoryDigest the digest of the image directory, hex, optionally with the prefix {@code sha256:}
     * @throws IllegalArgumentException for a blank repository or a digest of fewer than 12 lowercase hex characters
     */
    public static String tag(String repository, String directoryDigest) {
        if (repository == null || repository.isBlank()) {
            throw new IllegalArgumentException("An image tag needs a repository");
        }
        Matcher digest = directoryDigest == null ? null : DIGEST.matcher(directoryDigest);
        if (digest == null || !digest.matches()) {
            throw new IllegalArgumentException("'" + directoryDigest + "' is not a directory digest (lowercase hex,"
                    + " at least 12 characters)");
        }
        return repository + ":" + digest.group(1);
    }
}
