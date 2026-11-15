package traceworks.core.port;

/**
 * An image Traceworks builds from a directory with a Dockerfile: one of its built-in images, or one under a package's
 * {@code images/}.
 *
 * @param name       the image's name, {@code shell} or the name of the directory under {@code images/}
 * @param directory  the build context: the absolute path of the directory; {@code null} for a built-in image, whose
 *                   files are in the jar and are written out when the image is built
 * @param digest     the digest of the directory, computed as the package digest, lowercase hex; the tag derives
 *                   from it
 * @param dockerfile the text of its Dockerfile, for the analyzer
 */
public record PackageImage(String name, String directory, String digest, String dockerfile) {
}
