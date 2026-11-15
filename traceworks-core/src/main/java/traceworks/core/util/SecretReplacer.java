package traceworks.core.util;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Replaces each value of a run's secrets in a text by {@code [redacted:<NAME>]}: the matching the record redacts
 * with ({@code core.record.Redactor}), here so that a step can use the same one on text it is about to rewrite. A
 * replacement that happens only when the record is written would miss a value that a cleaning or a cut changed
 * before ({@link UntrustedText}): the changed form is no exact match any more.
 *
 * <p>A value is found byte for byte, as its UTF-8 bytes. Every occurrence of every value is found. Occurrences that
 * overlap, of one value or of several, are replaced together by one {@code [redacted:<NAME>]}, so no part of any of
 * them is left; it names the longest of them, on a tie the one that starts first. Occurrences that only touch stay
 * apart. The longest values are tried first at each position. Two names with the same value are replaced by the
 * first name in sort order. The input is scanned once; what is written is not scanned again.
 *
 * <p>The values are searched with an index of their first bytes, built once, so a text without a value costs one
 * pass over its bytes. Neither {@link #toString()} nor any message of this class names a value.
 */
public final class SecretReplacer {

    /** How every replacement starts. */
    public static final String MARKER = "[redacted:";

    private static final SecretReplacer NONE = new SecretReplacer(List.of());

    /** The forms that start with each byte, longest first, then by name. */
    private final Form[][] byFirstByte = new Form[256][];
    private final Set<String> names;
    private final List<String> replacements;

    /** One form of a value: its bytes as they occur, and the replacement that takes their place. */
    private record Form(byte[] bytes, String name, byte[] replacement) {}

    /** A value and the name its replacement shows. */
    private record Named(String name, String value) {}

    /**
     * @param valueByName the value of each secret by its name; a value that is empty is left out, since it occurs
     *                    everywhere
     */
    public SecretReplacer(Map<String, String> valueByName) {
        this(named(valueByName, false));
    }

    /**
     * A replacer for the secrets as a run's steps hold them: by a key that is the secret's name, or that ends in
     * {@code :<NAME>} (the credentials of the AI providers, under keys no step can declare). The replacement shows
     * the name alone, as the record does.
     */
    public static SecretReplacer ofKeys(Map<String, String> valueByKey) {
        return valueByKey.isEmpty() ? NONE : new SecretReplacer(named(valueByKey, true));
    }

    /** A replacer without a value: it changes nothing. */
    public static SecretReplacer none() {
        return NONE;
    }

    private static List<Named> named(Map<String, String> values, boolean keys) {
        Objects.requireNonNull(values, "values");
        List<Named> named = new ArrayList<>();
        values.forEach((key, value) -> {
            if (key != null && value != null && !value.isEmpty()) {
                named.add(new Named(keys ? key.substring(key.lastIndexOf(':') + 1) : key, value));
            }
        });
        return named;
    }

    private SecretReplacer(List<Named> named) {
        List<Named> sorted = new ArrayList<>(named);
        sorted.sort(Comparator.comparing(Named::name).thenComparing(Named::value));
        Set<String> allNames = new TreeSet<>();
        Set<String> allReplacements = new LinkedHashSet<>();
        List<Form> forms = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Named secret : sorted) {
            allNames.add(secret.name());
            // The same value under two names: the first name in sort order keeps it
            if (seen.add(secret.value())) {
                String replacement = MARKER + secret.name() + "]";
                allReplacements.add(replacement);
                forms.add(new Form(secret.value().getBytes(StandardCharsets.UTF_8), secret.name(),
                        replacement.getBytes(StandardCharsets.UTF_8)));
            }
        }
        this.names = Set.copyOf(allNames);
        this.replacements = List.copyOf(allReplacements);
        forms.sort(Comparator.comparingInt((Form f) -> -f.bytes().length).thenComparing(Form::name));
        List<List<Form>> index = new ArrayList<>();
        for (int b = 0; b < 256; b++) {
            index.add(new ArrayList<>());
        }
        forms.forEach(form -> index.get(form.bytes()[0] & 0xff).add(form));
        for (int b = 0; b < 256; b++) {
            byFirstByte[b] = index.get(b).isEmpty() ? null : index.get(b).toArray(Form[]::new);
        }
    }

    /** Whether there is no value to replace, so that this changes nothing. */
    public boolean isEmpty() {
        return names.isEmpty();
    }

    /**
     * Every replacement this can write, such as {@code [redacted:API_TOKEN]}: what a cut of a text that was
     * replaced must keep whole ({@link TruncationUtil}), so that no half of one is left.
     */
    public List<String> replacements() {
        return replacements;
    }

    /**
     * The content with every value replaced.
     *
     * @return {@code content} itself when no value occurs in it; {@code null} for {@code null}
     */
    public byte[] replace(byte[] content) {
        if (content == null || names.isEmpty()) {
            return content;
        }
        ByteArrayOutputStream out = null;
        int copied = 0;
        int regionStart = -1;
        int regionEnd = -1;
        Form regionForm = null;
        for (int i = 0; i < content.length; i++) {
            Form form = longestAt(content, i);
            if (form == null) {
                continue;
            }
            int end = i + form.bytes().length;
            if (regionForm != null && i < regionEnd) {
                regionEnd = Math.max(regionEnd, end);
                if (form.bytes().length > regionForm.bytes().length) {
                    regionForm = form;
                }
                continue;
            }
            if (regionForm != null) {
                out = replaced(out, content, copied, regionStart, regionForm);
                copied = regionEnd;
            }
            regionStart = i;
            regionEnd = end;
            regionForm = form;
        }
        if (regionForm == null) {
            return content;
        }
        out = replaced(out, content, copied, regionStart, regionForm);
        out.write(content, regionEnd, content.length - regionEnd);
        return out.toByteArray();
    }

    /**
     * The text with every value replaced.
     *
     * @return {@code text} itself when no value occurs in it; {@code null} for {@code null}
     */
    public String replace(String text) {
        if (text == null || names.isEmpty()) {
            return text;
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        byte[] replaced = replace(bytes);
        return replaced == bytes ? text : new String(replaced, StandardCharsets.UTF_8);
    }

    /** Whether a value occurs in the content, in any of the forms {@link #replace(byte[])} replaces. */
    public boolean occursIn(byte[] content) {
        if (content == null || names.isEmpty()) {
            return false;
        }
        for (int i = 0; i < content.length; i++) {
            if (longestAt(content, i) != null) {
                return true;
            }
        }
        return false;
    }

    /** The longest form that occurs at {@code at}, by the order of the index; {@code null} for none. */
    private Form longestAt(byte[] content, int at) {
        Form[] candidates = byFirstByte[content[at] & 0xff];
        if (candidates == null) {
            return null;
        }
        for (Form form : candidates) {
            if (occursAt(content, at, form.bytes())) {
                return form;
            }
        }
        return null;
    }

    private static boolean occursAt(byte[] content, int at, byte[] value) {
        if (at + value.length > content.length) {
            return false;
        }
        for (int j = 1; j < value.length; j++) {
            if (content[at + j] != value[j]) {
                return false;
            }
        }
        return true;
    }

    /** Writes the content from {@code copied} up to {@code start}, then the replacement of {@code form}. */
    private static ByteArrayOutputStream replaced(ByteArrayOutputStream out, byte[] content, int copied, int start,
                                                  Form form) {
        ByteArrayOutputStream target = out != null ? out : new ByteArrayOutputStream(content.length);
        target.write(content, copied, start - copied);
        target.write(form.replacement(), 0, form.replacement().length);
        return target;
    }

    /** The names of the secrets, never their values. */
    @Override
    public String toString() {
        return "SecretReplacer" + new TreeSet<>(names);
    }
}
