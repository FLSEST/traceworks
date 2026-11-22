package traceworks.adapters.config;

import com.fasterxml.jackson.databind.JsonNode;
import traceworks.core.model.ProjectKey;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The projects of a home: {@code projects} in {@code traceworks.json}, an object that binds a project key
 * ({@link ProjectKey}) to a folder, so that a run whose workspace is that folder or lies below it is of that project
 * without the operator naming it. The setting is the operator's word, as {@code --project} is: nothing in a
 * workspace or a package names a project. No command writes it.
 *
 * <p>A folder is a string: an absolute path, or one that starts with {@code ~/}, which is the operator's home
 * directory, as an entry of {@code writable_mounts} is written. Missing, {@code null} or an empty object is no
 * project. A folder need not exist: it holds nothing then. Refused, naming the key: a key that is no project key;
 * a value that is no string, begins or ends with white space, or is not absolute; {@code /}, as written or behind a
 * link, which would make every folder of the machine one project; two keys for one folder; and a folder that lies in the folder of another key,
 * since which project a workspace there is of would be a guess. Two folders are compared as they are written, with
 * {@code .} and {@code ..} applied, and by their real paths where both exist.
 *
 * <p>Which project a folder is of ({@link #holding}) is asked by real paths, links resolved, at the time it is
 * asked.
 *
 * @param file    the {@code traceworks.json} the setting is read from, which a refusal names; it need not exist
 * @param folders the folder of each key, sorted by key: absolute, a leading {@code ~/} already replaced
 */
public record Projects(Path file, Map<String, Path> folders) {

    /** The key of {@code traceworks.json} that binds project keys to folders. */
    public static final String SETTING = "projects";

    /**
     * A project the operator declared.
     *
     * @param key    its key
     * @param folder its folder, as the setting names it
     */
    public record Declared(String key, Path folder) {
    }

    public Projects {
        folders = Collections.unmodifiableMap(new TreeMap<>(folders));
    }

    /**
     * The projects that the {@code traceworks.json} of {@code home} declares; none when there is no such file.
     *
     * @throws IllegalArgumentException for a file that cannot be parsed, an unknown key, or a setting that is refused:
     *                                  among the reasons, a key that stands twice
     */
    public static Projects read(Path home) {
        return read(home, operatorHome());
    }

    /**
     * {@link #read(Path)} for an operator whose home directory is {@code userHome}.
     *
     * @param userHome where a leading {@code ~/} of a folder points
     */
    static Projects read(Path home, Path userHome) {
        Path file = home.resolve(OperatorConfigFile.FILE);
        JsonNode root = OperatorConfigFile.root(file);
        if (root != null && root.has(SETTING)) {
            refuseAKeyThatStandsTwice(file);
        }
        return from(file, root == null ? null : root.get(SETTING), userHome);
    }

    /**
     * The check every reader of {@code file} makes of the setting, whether it uses the projects or not: the
     * object {@code root} of the file must hold a setting that is not refused, and that names no key twice.
     *
     * @throws IllegalArgumentException for a setting that is refused
     */
    static void checked(Path file, JsonNode root) {
        if (root.has(SETTING)) {
            refuseAKeyThatStandsTwice(file);
        }
        from(file, root.get(SETTING));
    }

    /**
     * Refuses a setting that names a key twice, or that stands twice itself. A JSON object read as a tree keeps
     * the last of two equal keys and says nothing, so a key bound to two folders would be bound to the second one
     * without a word; the file is read once more, key by key.
     *
     * @throws IllegalArgumentException that names the key and the file
     */
    private static void refuseAKeyThatStandsTwice(Path file) {
        String in = "'" + SETTING + "' in " + file;
        try (com.fasterxml.jackson.core.JsonParser parser = new com.fasterxml.jackson.core.JsonFactory()
                .createParser(java.nio.file.Files.readAllBytes(file))) {
            if (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.START_OBJECT) {
                return;
            }
            boolean seen = false;
            while (parser.nextToken() == com.fasterxml.jackson.core.JsonToken.FIELD_NAME) {
                boolean setting = SETTING.equals(parser.currentName());
                com.fasterxml.jackson.core.JsonToken value = parser.nextToken();
                if (!setting || value != com.fasterxml.jackson.core.JsonToken.START_OBJECT) {
                    parser.skipChildren();
                    continue;
                }
                if (seen) {
                    throw new IllegalArgumentException(in + " stands twice: \"" + SETTING + "\" is one object of"
                            + " project key to folder");
                }
                seen = true;
                java.util.Set<String> keys = new java.util.HashSet<>();
                while (parser.nextToken() == com.fasterxml.jackson.core.JsonToken.FIELD_NAME) {
                    String key = parser.currentName();
                    if (!keys.add(key)) {
                        throw new IllegalArgumentException(in + " holds the key \"" + key + "\" twice: a project"
                                + " is bound to one folder");
                    }
                    parser.nextToken();
                    parser.skipChildren();
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    /** The projects that the value {@code value} of {@code projects} in {@code file} declares. */
    static Projects from(Path file, JsonNode value) {
        return from(file, value, operatorHome());
    }

    /** The home directory of the user this process runs as: where {@code ~} points. */
    private static Path operatorHome() {
        return Path.of(System.getProperty("user.home"));
    }

    /** {@link #from(Path, JsonNode)} for an operator whose home directory is {@code userHome}. */
    static Projects from(Path file, JsonNode value, Path userHome) {
        Map<String, Path> folders = new TreeMap<>();
        if (value == null || value.isNull()) {
            return new Projects(file, folders);
        }
        String in = "'" + SETTING + "' in " + file;
        if (!value.isObject()) {
            throw new IllegalArgumentException(in + " must be an object of project key to folder, each folder an"
                    + " absolute path or one that starts with ~/");
        }
        for (Map.Entry<String, JsonNode> entry : value.properties()) {
            String key = entry.getKey();
            if (!ProjectKey.matches(key)) {
                throw new IllegalArgumentException(in + " holds the key \"" + key + "\", which is no project key:"
                        + " it must match " + ProjectKey.PATTERN);
            }
            if (!entry.getValue().isTextual()) {
                throw new IllegalArgumentException(in + " gives \"" + key + "\" a value that is no string: a"
                        + " project is bound to a folder, an absolute path or one that starts with ~/");
            }
            folders.put(key, folder(in, key, entry.getValue().textValue(), userHome));
        }
        List<String> keys = new ArrayList<>(folders.keySet());
        for (int i = 0; i < keys.size(); i++) {
            for (int j = i + 1; j < keys.size(); j++) {
                refuseOverlap(in, keys.get(i), folders.get(keys.get(i)), keys.get(j), folders.get(keys.get(j)));
            }
        }
        return new Projects(file, folders);
    }

    /** The folder {@code text} names for {@code key}. */
    private static Path folder(String in, String key, String text, Path userHome) {
        String holds = in + " binds \"" + key + "\" to \"" + text + "\", which ";
        if (!text.equals(text.strip())) {
            throw new IllegalArgumentException(holds + "begins or ends with white space");
        }
        Path folder;
        try {
            if (text.equals("~") || text.startsWith("~/")) {
                folder = userHome.resolve(text.substring(1).replaceFirst("^/+", "")).normalize();
            } else if (text.startsWith("/")) {
                folder = Path.of(text).normalize();
            } else {
                throw new IllegalArgumentException(holds + "is not an absolute path: write the whole path of the"
                        + " folder, or one that starts with ~/ for a folder below your home directory");
            }
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(holds + "is not a valid path: " + e.getMessage(), e);
        }
        if (folder.getNameCount() == 0 || realOrWritten(folder).getNameCount() == 0) {
            throw new IllegalArgumentException(holds + "is the root of the file system: every folder of the"
                    + " machine would be of this project; name the folder of the project");
        }
        return folder;
    }

    /** Refuses two keys whose folders are one folder, or lie one in the other, as written or by their real paths. */
    private static void refuseOverlap(String in, String one, Path oneFolder, String other, Path otherFolder) {
        Path oneReal = realOrWritten(oneFolder);
        Path otherReal = realOrWritten(otherFolder);
        if (oneFolder.equals(otherFolder)) {
            throw new IllegalArgumentException(in + " gives \"" + one + "\" and \"" + other + "\" the same folder "
                    + oneFolder + ": a folder is of one project");
        }
        if (oneReal.equals(otherReal)) {
            throw new IllegalArgumentException(in + " gives \"" + one + "\" and \"" + other + "\" the same folder: "
                    + oneFolder + " and " + otherFolder + " are both " + oneReal + "; a folder is of one project");
        }
        if (otherFolder.startsWith(oneFolder) || otherReal.startsWith(oneReal)) {
            throw new IllegalArgumentException(inside(in, other, otherFolder, otherReal, one, oneFolder));
        }
        if (oneFolder.startsWith(otherFolder) || oneReal.startsWith(otherReal)) {
            throw new IllegalArgumentException(inside(in, one, oneFolder, oneReal, other, otherFolder));
        }
    }

    /** The real path of {@code folder}; the path as written for one that does not exist, which holds nothing yet. */
    private static Path realOrWritten(Path folder) {
        try {
            return folder.toRealPath();
        } catch (IOException e) {
            return folder;
        }
    }

    private static String inside(String in, String inner, Path innerFolder, Path innerReal, String outer,
                                 Path outerFolder) {
        return in + " binds \"" + inner + "\" to " + innerFolder + (innerFolder.startsWith(outerFolder) ? ""
                : " (" + innerReal + ")") + ", which lies in the folder " + outerFolder + " of \"" + outer
                + "\": which project a workspace there is of would be a guess; give each project a folder of its"
                + " own";
    }

    /** The folder the operator bound {@code key} to; empty for a key the setting does not declare. */
    public Optional<Path> folder(String key) {
        return Optional.ofNullable(folders.get(key));
    }

    /**
     * The declared project that {@code folder} is of: the one whose folder it is or lies below, by real paths, as the
     * file system resolves both now. Empty when no declared folder holds it, and for a folder that does not exist. A
     * declared folder that does not exist holds nothing.
     */
    public Optional<Declared> holding(Path folder) {
        Path real;
        try {
            real = folder.toRealPath();
        } catch (IOException e) {
            return Optional.empty();
        }
        for (Map.Entry<String, Path> declared : folders.entrySet()) {
            try {
                if (real.startsWith(declared.getValue().toRealPath())) {
                    return Optional.of(new Declared(declared.getKey(), declared.getValue()));
                }
            } catch (IOException e) {
                // Not there: it matches no folder
            }
        }
        return Optional.empty();
    }
}
