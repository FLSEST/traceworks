package traceworks.core.app;

import traceworks.core.engine.SecretNames;
import traceworks.core.port.CredentialStore;
import traceworks.core.record.Redactor;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The use case of the operator's stored credentials: sets and removes one entry and lists the names. It changes one
 * entry and keeps the others as they are. It takes only what a run would take: a name a secret can have in the
 * store, and a value the record can redact ({@link Redactor#problem}). Nothing here shows a value.
 */
public final class Credentials {

    private final CredentialStore store;

    public Credentials(CredentialStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Where the credentials are kept, for a message. */
    public String location() {
        return store.location();
    }

    /** Whether {@code name} can name a stored credential: capital letters, digits and _, not starting with a digit. */
    public static boolean isName(String name) {
        return name != null && SecretNames.wellFormed(name);
    }

    /**
     * The stored names, sorted.
     *
     * @throws IllegalArgumentException when the store cannot be trusted or understood
     * @throws UncheckedIOException     when it cannot be read
     */
    public List<String> names() {
        return List.copyOf(new TreeMap<>(store.read()).keySet());
    }

    /**
     * Stores {@code value} under {@code name}.
     *
     * @return whether the name had a value before
     * @throws IllegalArgumentException when the name is no name, the value is one a run would refuse, or the store
     *                                  cannot be trusted or kept the operator's alone; the message shows no value
     * @throws UncheckedIOException     when the store cannot be read or written
     */
    public boolean set(String name, String value) {
        requireName(name);
        Objects.requireNonNull(value, "value");
        Redactor.ValueProblem problem = Redactor.problem(value);
        if (problem != null) {
            throw new IllegalArgumentException("the value given for " + name + " is one " + problem.what()
                    + ", which no run takes — " + problem.why());
        }
        Map<String, String> values = new TreeMap<>(store.read());
        boolean replaced = values.put(name, value) != null;
        store.write(values);
        return replaced;
    }

    /**
     * Removes {@code name}.
     *
     * @return whether the name had a value; when not, nothing is written
     * @throws IllegalArgumentException when the name is no name, or the store cannot be trusted
     * @throws UncheckedIOException     when the store cannot be read or written
     */
    public boolean remove(String name) {
        requireName(name);
        Map<String, String> values = new TreeMap<>(store.read());
        if (values.remove(name) == null) {
            return false;
        }
        store.write(values);
        return true;
    }

    private static void requireName(String name) {
        if (!isName(name)) {
            throw new IllegalArgumentException("'" + name + "' is not a credential name: use capital letters, digits"
                    + " and _, not starting with a digit");
        }
    }
}
