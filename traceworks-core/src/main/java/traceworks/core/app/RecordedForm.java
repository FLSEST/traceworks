package traceworks.core.app;

import traceworks.core.record.Redactor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
     * {@code e} itself when neither it nor a cause of it names a value, else its recorded form: of the same kind, so
     * the command ends with the same exit code (a damaged record stays a damaged record), with its suppressed
     * exceptions (the checkouts a failure kept), and without its cause, whose text would name the real path. An
     * exception of another kind becomes an {@link IllegalStateException}, which ends the command as an internal error
     * as before.
     */
    RuntimeException of(RuntimeException e) {
        if (values.isEmpty() || !namesAValue(e)) {
            return e;
        }
        String message = of(e.getMessage());
        RuntimeException recorded = e instanceof RecordDamagedException ? new RecordDamagedException(message)
                : e instanceof RunRefusedException ? new RunRefusedException(message)
                : new IllegalStateException(e.getClass().getSimpleName() + ": " + message);
        recorded.setStackTrace(e.getStackTrace());
        for (Throwable suppressed : e.getSuppressed()) {
            recorded.addSuppressed(suppressed);
        }
        return recorded;
    }

    private boolean namesAValue(Throwable e) {
        for (Throwable at = e; at != null; at = at.getCause() == at ? null : at.getCause()) {
            String message = at.getMessage();
            if (message != null && !of(message).equals(message)) {
                return true;
            }
        }
        return false;
    }
}
