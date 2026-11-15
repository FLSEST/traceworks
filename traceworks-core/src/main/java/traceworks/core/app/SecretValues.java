package traceworks.core.app;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Where the values of a run's secrets come from: the operator's {@code credentials.json}, else the
 * environment variable of the same name in the Traceworks process. It remembers every value it hands out, so the run
 * passes on exactly those, and its redactor knows exactly those. Neither {@link #toString()} nor anything else here
 * shows a value.
 *
 * <p>It also knows when the file was last modified, so a resume can warn that a value may have changed while the run
 * was stopped ({@link #fileModified}); it keeps nothing derived from a value to tell whether one did.
 */
public final class SecretValues {

    private final Map<String, String> fromFile;
    private final Map<String, String> environment;
    private final Instant fileModified;
    private final String fileLocation;
    private final Predicate<String> environmentAllowed;
    private final Map<String, String> used = new TreeMap<>();

    /**
     * Values whose file's modification time is not known.
     *
     * @param fromFile    what {@code <home>/credentials.json} holds, by name
     * @param environment the environment of the Traceworks process
     */
    public SecretValues(Map<String, String> fromFile, Map<String, String> environment) {
        this(fromFile, environment, null);
    }

    /**
     * @param fromFile     what {@code <home>/credentials.json} holds, by name
     * @param environment  the environment of the Traceworks process
     * @param fileModified when {@code credentials.json} was last modified; {@code null} when there is none or the time
     *                     is not known
     */
    public SecretValues(Map<String, String> fromFile, Map<String, String> environment, Instant fileModified) {
        this(fromFile, environment, fileModified, null);
    }

    /**
     * @param fromFile     what {@code <home>/credentials.json} holds, by name
     * @param environment  the environment of the Traceworks process
     * @param fileModified when {@code credentials.json} was last modified; {@code null} when there is none or the time
     *                     is not known
     * @param fileLocation the path of {@code credentials.json}, for a refusal that says where a missing value goes;
     *                     {@code null} when not known
     */
    public SecretValues(Map<String, String> fromFile, Map<String, String> environment, Instant fileModified,
                        String fileLocation) {
        this(fromFile, environment, fileModified, fileLocation, name -> true);
    }

    /**
     * @param fromFile           what {@code <home>/credentials.json} holds, by name
     * @param environment        the environment of the Traceworks process
     * @param fileModified       when {@code credentials.json} was last modified; {@code null} when there is none or
     *                           the time is not known
     * @param fileLocation       the path of {@code credentials.json}, for a refusal that says where a missing value
     *                           goes; {@code null} when not known
     * @param environmentAllowed which names may take their value from the environment: the operator's
     *                           {@code secret_names}, or every name without it. The file is not subject to it
     */
    public SecretValues(Map<String, String> fromFile, Map<String, String> environment, Instant fileModified,
                        String fileLocation, Predicate<String> environmentAllowed) {
        this.fromFile = Map.copyOf(Objects.requireNonNull(fromFile, "fromFile"));
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        this.fileModified = fileModified;
        this.fileLocation = fileLocation;
        this.environmentAllowed = Objects.requireNonNull(environmentAllowed, "environmentAllowed");
    }

    /** Neither a file nor an environment: no secret has a value. */
    public static SecretValues none() {
        return new SecretValues(Map.of(), Map.of());
    }

    /**
     * The value of {@code name}: from the file, else from the environment when the name may come from there; empty
     * when neither has one.
     */
    public synchronized Optional<String> value(String name) {
        String value = fromFile.containsKey(name) ? fromFile.get(name)
                : environmentAllowed.test(name) ? environment.get(name) : null;
        if (value != null) {
            used.put(name, value);
        }
        return Optional.ofNullable(value);
    }

    /**
     * Whether the value of {@code name} comes from {@code credentials.json}, which takes precedence over the
     * environment. It hands out nothing.
     */
    public boolean inFile(String name) {
        return fromFile.containsKey(name);
    }

    /** When {@code credentials.json} was last modified; empty when there is none or the time is not known. */
    public Optional<Instant> fileModified() {
        return Optional.ofNullable(fileModified);
    }

    /**
     * Where a missing value goes, as the middle of a refusal: the command that stores it and the file it goes into,
     * or the environment.
     *
     * @param several whether the refusal lists several names, not one
     */
    String whereToPut(boolean several) {
        String file = fileLocation == null ? "credentials.json in the Traceworks home" : fileLocation;
        return "store " + (several ? "them" : "one") + " with 'traceworks credentials set <NAME>' (in " + file
                + "), or set " + (several ? "them as environment variables" : "it as an environment variable")
                + " of Traceworks";
    }

    /**
     * Whether the environment holds a value for {@code name} that {@code secret_names} keeps from the run: the file has
     * none, the environment has one, and the name is not allowed. It hands out nothing.
     */
    public boolean keptFromTheEnvironment(String name) {
        return !fromFile.containsKey(name) && environment.containsKey(name) && !environmentAllowed.test(name);
    }

    /** Every value {@link #value} handed out, by name, sorted by name. */
    public synchronized Map<String, String> used() {
        return Collections.unmodifiableMap(new TreeMap<>(used));
    }

    /** The names of the values handed out, never the values. */
    @Override
    public synchronized String toString() {
        return "SecretValues" + used.keySet();
    }
}
