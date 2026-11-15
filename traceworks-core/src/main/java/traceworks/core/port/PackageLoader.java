package traceworks.core.port;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Port to workflow packages on disk: reads a package and describes it. */
public interface PackageLoader {

    /**
     * Loads a package.
     *
     * @param location a package directory or its {@code workflow.json}
     * @return the package, never {@code null}
     * @throws IllegalArgumentException if the location holds no readable package
     */
    LoadedPackage load(Path location);

    /** The immediate subdirectories of {@code directory} that hold a {@code workflow.json}, sorted; empty if none. */
    List<Path> packagesIn(Path directory);

    /** Only the {@code id} of the package's {@code workflow.json}: no digest, no git. Empty when unreadable or absent. */
    Optional<String> idOf(Path packageDirectory);
}
