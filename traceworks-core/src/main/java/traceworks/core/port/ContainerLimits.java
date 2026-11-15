package traceworks.core.port;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * The resource limits every container gets. The operator sets them ({@code container_limits}); no setting lifts them,
 * so a limit of zero or below, and anything the runtime would not read as a limit, is refused.
 *
 * @param memory the memory limit, a positive number with an optional unit {@code b}, {@code k}, {@code m} or
 *               {@code g} ({@code 4g})
 * @param pids   the most processes the container may run at once, at least 1
 * @param cpus   how many CPUs the container may use, a positive decimal ({@code 2}, {@code 0.5})
 * @throws IllegalArgumentException for a value that is no positive limit
 */
public record ContainerLimits(String memory, int pids, String cpus) {

    // Before DEFAULTS: the constructor that makes it reads them
    private static final Pattern MEMORY = Pattern.compile("[1-9][0-9]*[bkmgBKMG]?");
    private static final Pattern CPUS = Pattern.compile("[0-9]+(\\.[0-9]+)?");

    /** Memory {@code 4g}, 1024 processes, 2 CPUs. */
    public static final ContainerLimits DEFAULTS = new ContainerLimits("4g", 1024, "2");

    public ContainerLimits {
        if (memory == null || !MEMORY.matcher(memory).matches()) {
            throw new IllegalArgumentException("container memory limit '" + memory + "' must be a positive number"
                    + " with an optional unit b, k, m or g, such as 4g");
        }
        if (pids < 1) {
            throw new IllegalArgumentException("container pids limit " + pids + " must be at least 1");
        }
        if (cpus == null || !CPUS.matcher(cpus).matches() || new BigDecimal(cpus).signum() <= 0) {
            throw new IllegalArgumentException("container cpus limit '" + cpus + "' must be a positive number,"
                    + " such as 2 or 0.5");
        }
    }
}
