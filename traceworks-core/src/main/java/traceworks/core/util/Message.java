package traceworks.core.util;

import java.util.ArrayList;
import java.util.List;

/**
 * A message for a person (a refusal, an error, a warning, a note) that says which of its parts are Traceworks' own
 * words and which quote a value from outside: a path, the name of a package, a text of a workflow, a mount.
 *
 * <p><b>Why.</b> A terminal breaks a line wider than it is, so a quoted value that is long or padded with blanks
 * can put its end where a line of Traceworks is read. What prints a message on a terminal therefore keeps every
 * quoted part on one terminal line or on marked lines; Traceworks' own words, a command to copy among them, are
 * printed whole. A message that is only a {@code String} says nothing about its parts and is treated as quoted as a
 * whole.
 *
 * <p><b>The parts are not in the text.</b> {@link #text} is the message as it always was, part after part, with
 * nothing between them: no mark is ever written into a string, so none reaches a record, an event, a digest, a
 * register, JSON, HTML or an exception's message, and no quoted value can hold a mark, since there is none to hold.
 * What is quoted is decided by the code that composes the message alone: a value is a part of its own kind, never a
 * part of the text of another.
 *
 * <p><b>The kinds.</b> A {@code String}, a number, a {@code Boolean} or an {@code Enum} handed to {@link #of} is
 * Traceworks' own: write only literals and what Traceworks derived there (a setting's name, a count, a
 * digest, a path it built below its home). Any other object (a {@code Path}, say) and every value of
 * {@link #quoted} is from outside; so is the id of a stored run, which for a run named by its path is the name of
 * its folder. {@link #typed} is for what the operator typed on this command line and nothing
 * else could set: it is shown whole, so that it can be copied.
 */
public final class Message {

    /** What a part is to a terminal. */
    public enum Kind {
        /** Traceworks' own words: printed whole, at any length. */
        OWN,
        /** A value from outside: kept from starting or crossing a terminal line. */
        QUOTED,
        /** What the operator typed on this command line: printed whole, its control characters replaced. */
        TYPED
    }

    /**
     * One part of a message.
     *
     * @param text the part as it stands in the message
     */
    public record Part(Kind kind, String text) {
        public Part {
            text = text == null ? "null" : text;
        }
    }

    /** What has a message with parts beside its text: an exception whose message a terminal prints. */
    public interface Carrier {

        /** The message with its parts; {@code null} where only the text is known. */
        Message parts();
    }

    private final List<Part> parts;

    private Message(List<Part> parts) {
        this.parts = List.copyOf(parts);
    }

    /**
     * A message from its parts in their order; see the class comment for what each kind of argument becomes. A
     * {@code Message} among them adds its parts, a {@link Part} is taken as it is.
     */
    public static Message of(Object... parts) {
        List<Part> all = new ArrayList<>();
        for (Object part : parts) {
            if (part instanceof Message nested) {
                all.addAll(nested.parts);
            } else if (part instanceof Part one) {
                all.add(one);
            } else if (part instanceof String || part instanceof Number || part instanceof Boolean
                    || part instanceof Enum<?>) {
                all.add(new Part(Kind.OWN, String.valueOf(part)));
            } else {
                all.add(new Part(Kind.QUOTED, String.valueOf(part)));
            }
        }
        return new Message(all);
    }

    /** A value from outside, as a part for {@link #of}. */
    public static Part quoted(Object value) {
        return new Part(Kind.QUOTED, String.valueOf(value));
    }

    /** What the operator typed on this command line, as a part for {@link #of}; see {@link Kind#TYPED}. */
    public static Part typed(Object value) {
        return new Part(Kind.TYPED, String.valueOf(value));
    }

    /**
     * The message of {@code e} with its parts where it has them; else its text as one quoted part, which is how a
     * message that says nothing about its parts is treated. For a message that goes on with the message of a cause.
     */
    public static Message of(Throwable e) {
        if (e instanceof Carrier carrier && carrier.parts() != null) {
            return carrier.parts();
        }
        return of(quoted(e.getMessage()));
    }

    /**
     * Messages joined into one with Traceworks' own {@code separator} between them.
     */
    public static Message join(String separator, List<Message> messages) {
        List<Part> all = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) {
                all.add(new Part(Kind.OWN, separator));
            }
            all.addAll(messages.get(i).parts);
        }
        return new Message(all);
    }

    /** The parts in their order. */
    public List<Part> parts() {
        return parts;
    }

    /** The message as plain text: every part as it is, with nothing added. */
    public String text() {
        StringBuilder text = new StringBuilder();
        parts.forEach(part -> text.append(part.text()));
        return text.toString();
    }

    /** {@link #text}. */
    @Override
    public String toString() {
        return text();
    }
}
