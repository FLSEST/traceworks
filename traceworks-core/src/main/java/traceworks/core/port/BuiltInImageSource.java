package traceworks.core.port;

import java.io.IOException;
import java.util.List;

/**
 * Port to the built-in images ({@code shell}, {@code claude} and {@code pi}), which the distribution
 * carries and which are built from their Dockerfiles.
 */
public interface BuiltInImageSource {

    /** The built-in images in the order they are built: {@code shell} first. */
    List<PackageImage> all();

    /**
     * Writes the build context of a built-in image to a temporary directory.
     *
     * @throws IOException if the files cannot be written
     */
    BuildContext context(PackageImage image) throws IOException;

    /** A build context on disk; closing it removes it. */
    interface BuildContext extends AutoCloseable {

        /** The absolute path of the context directory. */
        String directory();

        @Override
        void close();
    }
}
