package traceworks.core.testing;

import org.opentest4j.TestAbortedException;

/**
 * What a test that starts real containers (tagged {@code container}) does when the machine lacks what it needs: a
 * container runtime that works, a built-in image, the pinned test base.
 *
 * <p>A build that asked for the container tests ({@code mvn -Pcontainers verify} or {@code mvn -Pit verify}, which
 * set the system property {@value #PROPERTY}) fails the test, with a message that names the command to run: such a
 * build never passes by skipping, and what is missing stands in its list of failures. Anywhere else (a single class
 * started from an IDE) the test is skipped with the same words.
 */
public final class ContainerPrecondition {

    /** The system property the build sets to {@code true} when it asked for the container tests. */
    public static final String PROPERTY = "traceworks.containers.required";

    /** What to do when no container runtime works. */
    public static final String START_A_RUNTIME = "install and start docker or podman, so that"
            + " 'docker version' or 'podman version' answers";

    /** What to do when a built-in image is not built. The jar is there after a build without the container tests. */
    public static final String BUILD_THE_IMAGES = "run 'mvn verify' (it builds the jar and starts no container),"
            + " then 'java -jar traceworks-cli/target/traceworks.jar images build'";

    private ContainerPrecondition() {
    }

    /**
     * Goes on when {@code met}. Otherwise fails the test where the build asked for the container tests, and skips it
     * elsewhere.
     *
     * @param met     whether the machine has what the test needs
     * @param missing what is missing, as the middle of a sentence: {@code the shell image x:1 is not built}
     * @param fix     what to do about it: a command
     */
    public static void require(boolean met, String missing, String fix) {
        check(required(System.getProperty(PROPERTY)), met, missing, fix);
    }

    /** {@link #require} with the build's wish as an argument. */
    static void check(boolean required, boolean met, String missing, String fix) {
        if (met) {
            return;
        }
        if (required) {
            throw new AssertionError("Container tests were asked for (-Pcontainers or -Pit), but " + missing
                    + ". To fix: " + fix);
        }
        throw new TestAbortedException(missing + ". To fix: " + fix);
    }

    /** Whether {@code value} of {@value #PROPERTY} asks for the container tests: only the word {@code true} does. */
    static boolean required(String value) {
        return "true".equals(value);
    }
}
