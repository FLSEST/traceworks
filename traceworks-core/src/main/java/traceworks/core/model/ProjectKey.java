package traceworks.core.model;

import java.util.regex.Pattern;

/**
 * The key of a project: what a run works on, in the operator's word ({@code run --project KEY}, or the key the
 * home's {@code projects} setting binds to the folder the run works in). A register is kept
 * per workflow key and project (docs/registers.md), and the project is a folder of the register, so its key is one
 * path segment with the pattern of a register key ({@link AuditConfig#KEY}). It never comes from the workspace: a
 * repository that could name its own project could take over the decisions made for another.
 */
public final class ProjectKey {

    /** What a project key may look like: the pattern of a register key. */
    public static final String PATTERN = AuditConfig.KEY;

    private static final Pattern KEY = Pattern.compile(PATTERN);

    private ProjectKey() {
    }

    /** Whether {@code key} is a project key. */
    public static boolean matches(String key) {
        return key != null && KEY.matcher(key).matches();
    }

    /**
     * @return {@code key}
     * @throws IllegalArgumentException if it is no project key; the message names the pattern
     */
    public static String checked(String key) {
        if (!matches(key)) {
            throw new IllegalArgumentException("'" + key + "' is no project key: it must match " + PATTERN);
        }
        return key;
    }
}
