package traceworks.core.app;

import traceworks.core.record.Redactor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a text of a resume into the form the record holds: each value of a secret that the resume put back into a
 * recorded path ({@link RunPreparation#realPath}) becomes its {@code [redacted:<NAME>]} again. A resume works at the
 * real paths before its run has a redactor, so everything it says of them until then goes through this one place: a
 * refusal, an error, a line of the seal log.
 */
final class RecordedForm {

    /** Nothing was made real: every text is as it is. */
    static final RecordedForm NONE = new RecordedForm(Map.of());

    /** Name and value, the longest value first: a value that is part of another must not break the longer one. */
    private final List<Map.Entry<String, String>> values;

    /** @param values the value of each name that stands as a replacement in a recorded path */
    RecordedForm(Map<String, String> values) {
        List<Map.Entry<String, String>> sorted = new ArrayList<>();
        values.forEach((name, value) -> {
            if (value != null && !value.isEmpty()) {
                sorted.add(Map.entry(name, value));
            }
        });
        sorted.sort((a, b) -> b.getValue().length() - a.getValue().length());
        this.values = List.copyOf(sorted);
    }

    /** {@code text} with every value replaced; {@code null} for {@code null}. */
    String of(String text) {
        if (text == null) {
            return null;
        }
        String recorded = text;
        for (Map.Entry<String, String> entry : values) {
            recorded = recorded.replace(entry.getValue(), Redactor.MARKER + entry.getKey() + "]");
        }
        return recorded;
    }

    /** Whether nothing was made real, so every text stays as it is. */
    boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * {@code e} itself when neither it, nor a cause of it, nor an exception one of them suppressed names a value, else
     * its recorded form: of the same kind, so the command ends with the same exit code (a damaged record stays a
     * damaged record), with its suppressed exceptions in their recorded form (the checkouts a failure kept stay
     * {@link KeptCheckoutNotice}s, which the command line names), and without its cause, whose text would name the
     * real path. An exception of another kind whose own message or a cause names a value becomes an
     * {@link IllegalStateException}, which ends the command as an internal error. One of another kind that names a
     * value only in what it suppressed stays {@code e}: the command line picks its exit code by the kind (a changed
     * blob ends it as a damaged record does), and shows of the suppressed exceptions only the checkouts a failure
     * kept, which a resume notes in recorded form from the start ({@link Checkouts#noteKept}).
     */
    RuntimeException of(RuntimeException e) {
        if (values.isEmpty() || !namesAValue(e, identitySet())) {
            return e;
        }
        boolean madeAgain = e instanceof RecordDamagedException || e instanceof RunRefusedException;
        return madeAgain || textNamesAValue(e) ? recorded(e, identitySet()) : e;
    }

    /** Whether the message of {@code e} or of a cause of it names a value. */
    private boolean textNamesAValue(Throwable e) {
        Set<Throwable> seen = identitySet();
        for (Throwable at = e; at != null && seen.add(at); at = at.getCause()) {
            String message = at.getMessage();
            if (message != null && !of(message).equals(message)) {
                return true;
            }
        }
        return false;
    }

    /** @param above the exceptions this one is suppressed under: one that comes back to itself is left out */
    private RuntimeException recorded(Throwable e, Set<Throwable> above) {
        String message = of(e.getMessage());
        RuntimeException recorded = e instanceof KeptCheckoutNotice kept ? new KeptCheckoutNotice(of(kept.path()))
                : e instanceof RecordDamagedException ? new RecordDamagedException(message)
                : e instanceof RunRefusedException ? new RunRefusedException(message)
                : new IllegalStateException(e.getClass().getSimpleName() + ": " + message);
        recorded.setStackTrace(e.getStackTrace());
        above.add(e);
        for (Throwable suppressed : e.getSuppressed()) {
            if (!namesAValue(suppressed, identitySet())) {
                recorded.addSuppressed(suppressed);
            } else if (!above.contains(suppressed)) {
                recorded.addSuppressed(recorded(suppressed, above));
            }
        }
        above.remove(e);
        return recorded;
    }

    /** Whether {@code e}, a cause of it or an exception one of them suppressed names a value. */
    private boolean namesAValue(Throwable e, Set<Throwable> seen) {
        if (e == null || !seen.add(e)) {
            return false;
        }
        String message = e.getMessage();
        if (message != null && !of(message).equals(message) || namesAValue(e.getCause(), seen)) {
            return true;
        }
        for (Throwable suppressed : e.getSuppressed()) {
            if (namesAValue(suppressed, seen)) {
                return true;
            }
        }
        return false;
    }

    private static Set<Throwable> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
