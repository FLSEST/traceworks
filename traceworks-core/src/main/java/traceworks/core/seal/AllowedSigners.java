package traceworks.core.seal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The allowed signers of a home, in the format of git and OpenSSH ({@code ssh-keygen -Y verify -f}): one signer per
 * line, {@code principals [options] keytype key [comment]}; {@code #} starts a comment line.
 *
 * <p>The one option that is read is {@code namespaces="..."}, with names and {@code *} patterns. A line with any other
 * option (a validity date, a certificate authority), with a negated namespace pattern ({@code !name}) or with the
 * option given twice allows nothing: ignoring an option that restricts would allow more than the line says.
 * {@link #notes} names such a line, so a refusal can say why a key that is listed is not allowed.
 */
public final class AllowedSigners {

    private static final Pattern KEY_TYPE = Pattern.compile("(ssh|ecdsa|sk)-[A-Za-z0-9@.-]+");

    /** One readable line. */
    private record Line(int number, List<String> principals, String key, List<String> namespaces,
                        List<String> unreadOptions) {
    }

    private final List<Line> lines;
    private final List<Integer> unreadable;

    private AllowedSigners(List<Line> lines, List<Integer> unreadable) {
        this.lines = List.copyOf(lines);
        this.unreadable = List.copyOf(unreadable);
    }

    /** No signer is allowed: a home without the file. */
    public static AllowedSigners none() {
        return new AllowedSigners(List.of(), List.of());
    }

    /** Reads the file's content. A line that names no signer allows nothing and is counted in {@link #unreadable}. */
    public static AllowedSigners parse(byte[] content) {
        List<Line> lines = new ArrayList<>();
        List<Integer> unreadable = new ArrayList<>();
        String[] rows = new String(content, StandardCharsets.UTF_8).split("\\R", -1);
        for (int i = 0; i < rows.length; i++) {
            String row = rows[i].strip();
            if (row.isEmpty() || row.startsWith("#")) {
                continue;
            }
            Line line = line(i + 1, tokens(row, ' '));
            if (line == null) {
                unreadable.add(i + 1);
            } else {
                lines.add(line);
            }
        }
        return new AllowedSigners(lines, unreadable);
    }

    private static Line line(int number, List<String> tokens) {
        int type = -1;
        for (int i = 1; i < tokens.size() && type < 0; i++) {
            if (KEY_TYPE.matcher(tokens.get(i)).matches()) {
                type = i;
            }
        }
        if (type < 0 || type + 1 >= tokens.size()) {
            return null;
        }
        List<String> namespaces = null;
        List<String> unread = new ArrayList<>();
        for (String field : tokens.subList(1, type)) {
            for (String option : tokens(field, ',')) {
                String lower = option.toLowerCase(java.util.Locale.ROOT);
                if (lower.startsWith("namespaces=")) {
                    if (namespaces != null) {
                        // OpenSSH's choice between two is not one to guess at
                        unread.add("namespaces (given twice)");
                    }
                    namespaces = new ArrayList<>();
                    for (String name : unquote(option.substring("namespaces=".length())).split(",")) {
                        if (name.strip().startsWith("!")) {
                            // A denial in OpenSSH; read as a name it would deny nothing, and a * next to it all
                            unread.add("namespaces with the negated pattern " + name.strip());
                        } else if (!name.isBlank()) {
                            namespaces.add(name.strip());
                        }
                    }
                } else if (!option.isBlank()) {
                    unread.add(option.contains("=") ? option.substring(0, option.indexOf('=')) : option);
                }
            }
        }
        List<String> principals = new ArrayList<>();
        for (String principal : unquote(tokens.get(0)).split(",")) {
            if (!principal.isBlank()) {
                principals.add(principal.strip());
            }
        }
        return new Line(number, principals, tokens.get(type) + " " + tokens.get(type + 1), namespaces, unread);
    }

    /** {@code text} split at {@code separator} (a space stands for any whitespace) outside double quotes. */
    private static List<String> tokens(String text, char separator) {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            }
            boolean splits = !quoted && (separator == ' ' ? Character.isWhitespace(c) : c == separator);
            if (!splits) {
                token.append(c);
            } else if (!token.isEmpty()) {
                tokens.add(token.toString());
                token.setLength(0);
            }
        }
        if (!token.isEmpty()) {
            tokens.add(token.toString());
        }
        return tokens;
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }

    /**
     * The principals {@code publicKey} may sign as in {@code namespace}, in the order of the file, each once; empty
     * when the key is not allowed.
     *
     * @param publicKey the key as {@code type base64}, a comment after it is ignored
     */
    public List<String> principals(String publicKey, String namespace) {
        String key = keyOf(publicKey);
        Set<String> principals = new LinkedHashSet<>();
        for (Line line : lines) {
            if (line.key().equals(key) && line.unreadOptions().isEmpty() && allows(line, namespace)) {
                principals.addAll(line.principals());
            }
        }
        return List.copyOf(principals);
    }

    /** Why lines that name {@code publicKey} do not allow it in {@code namespace}: one text per such line. */
    public List<String> notes(String publicKey, String namespace) {
        String key = keyOf(publicKey);
        List<String> notes = new ArrayList<>();
        for (Line line : lines) {
            if (!line.key().equals(key)) {
                continue;
            }
            if (!line.unreadOptions().isEmpty()) {
                notes.add("line " + line.number() + " names the key with the option "
                        + String.join(", ", line.unreadOptions()) + ", which Traceworks does not read, so the line"
                        + " allows nothing");
            } else if (!allows(line, namespace)) {
                notes.add("line " + line.number() + " allows the key for the namespaces "
                        + String.join(", ", line.namespaces()) + " only, not for '" + namespace + "'");
            }
        }
        return notes;
    }

    /** The numbers of the lines that name no signer, counted from 1. */
    public List<Integer> unreadable() {
        return unreadable;
    }

    private static boolean allows(Line line, String namespace) {
        if (line.namespaces() == null) {
            return true;
        }
        for (String pattern : line.namespaces()) {
            String regex = Pattern.quote(pattern).replace("*", "\\E.*\\Q");
            if (Pattern.matches(regex, namespace)) {
                return true;
            }
        }
        return false;
    }

    private static String keyOf(String publicKey) {
        String[] parts = publicKey.strip().split("\\s+");
        return parts.length < 2 ? publicKey.strip() : parts[0] + " " + parts[1];
    }
}
