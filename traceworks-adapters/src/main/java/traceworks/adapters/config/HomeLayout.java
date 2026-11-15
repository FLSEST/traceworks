package traceworks.adapters.config;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Where a home keeps what Traceworks writes: the one place that knows the paths. The runs, the ledger and the
 * registers lie in the folders {@code runs}, {@code ledger} and {@code registers} of the home unless the operator
 * names another folder for one of them in {@code traceworks.json} ({@code runs_dir}, {@code ledger_dir},
 * {@code registers_dir}). Everything else has its fixed place in the home: {@code traceworks.json} itself, which
 * is why it can be read before the places are known, {@code credentials.json}, the checkouts, the caches, the seal
 * log ({@code seals/}) and the allowed signers ({@code allowed_signers}, unless the operator names another file).
 *
 * <p>A setting is a string: an absolute path is taken as it is, a relative one counts from the home, as an entry of
 * {@code package_path} does. A {@code ~} is not expanded, since no shell reads the file; a value that starts with
 * one is refused, so that it does not become a folder of that name in the home. Missing or {@code null} is the
 * fixed folder. Reading the layout creates nothing: a folder is made when something is first written to it, as the
 * home is.
 *
 * <p>Refused, with the key: a value that is no string, is blank, or begins or ends with white space; a place that
 * exists and is no directory; a place that is a symbolic link without a target, or lies behind one; a place that is
 * the home or holds it, or has the name of a file of the home; a place that is, lies in or holds the home's
 * checkouts, caches or seal log; a place inside its own fixed folder; a runs folder whose path a container mount cannot carry
 * (a comma, a quote, a line break); and two places that are one folder or lie one inside the other, where the fixed
 * folder of another place counts as well as the folder that place has now, since its earlier runs, ledger or
 * registers may still lie there. The paths are
 * compared as they are written, with {@code .} and {@code ..} applied and no link followed: the check keeps an
 * operator from mixing the three by mistake. What keeps a step from them is the mount guard, which compares real
 * paths.
 *
 * @param home      the home
 * @param runs      where the run folders lie, {@code <runs>/<day>/<run-id>}
 * @param ledger    the ledger, a git repository
 * @param registers where the registers lie, one folder per key
 * @param sealFiles the files the seal settings name ({@code signing_key}, {@code allowed_signers}), where the
 *                  operator put them, in or outside the home: the mount guard keeps every step from them. Empty
 *                  without the settings; the allowed signers of the home are a file of the home then
 */
public record HomeLayout(Path home, Path runs, Path ledger, Path registers, List<SealFile> sealFiles) {

    static final String SIGNING_KEY = "signing_key";
    static final String ALLOWED_SIGNERS = "allowed_signers";

    /**
     * A file a seal setting names.
     *
     * @param setting the key of {@code traceworks.json}
     * @param file    the file as the setting names it, relative paths counted from the home; it may not exist yet
     */
    public record SealFile(String setting, Path file) {
    }

    /** A layout without files of seal settings. */
    public HomeLayout(Path home, Path runs, Path ledger, Path registers) {
        this(home, runs, ledger, registers, List.of());
    }

    /**
     * The same places, with the files the seal settings name; {@code null} for a setting that is not set.
     *
     * @param signingKey     the operator's {@code signing_key}: a private key no step may read
     * @param allowedSigners the operator's {@code allowed_signers}: who can write it can allow a key of their own
     */
    public HomeLayout withSealFiles(Path signingKey, Path allowedSigners) {
        List<SealFile> files = new ArrayList<>();
        if (signingKey != null) files.add(new SealFile(SIGNING_KEY, signingKey));
        if (allowedSigners != null) files.add(new SealFile(ALLOWED_SIGNERS, allowedSigners));
        return new HomeLayout(home, runs, ledger, registers, files);
    }

    static final String RUNS_KEY = "runs_dir";
    static final String LEDGER_KEY = "ledger_dir";
    static final String REGISTERS_KEY = "registers_dir";

    /** The files of the home; no place has the name of one. */
    private static final List<String> HOME_FILES = List.of(OperatorConfigFile.FILE, CredentialsFile.FILE, "ledger.lock",
            "allowed_signers");

    /** The keys of {@code traceworks.json} that name a place. */
    static final List<String> KEYS = List.of(RUNS_KEY, LEDGER_KEY, REGISTERS_KEY);

    public HomeLayout {
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(runs, "runs");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(registers, "registers");
        sealFiles = sealFiles == null ? List.of() : List.copyOf(sealFiles);
    }

    /** The layout without a setting: every place is its fixed folder of {@code home}. */
    public static HomeLayout of(Path home) {
        return new HomeLayout(home, fixed(home, RUNS_KEY), fixed(home, LEDGER_KEY), fixed(home, REGISTERS_KEY));
    }

    /**
     * The layout of {@code home} as its {@code traceworks.json} sets it; {@link #of} when there is no such file.
     *
     * @throws IllegalArgumentException for a file that cannot be parsed, an unknown key, or a place that is refused
     */
    public static HomeLayout read(Path home) {
        Path file = home.resolve(OperatorConfigFile.FILE);
        JsonNode root = OperatorConfigFile.root(file);
        return root == null ? of(home) : from(home, file, root);
    }

    /** The layout that the object {@code root} of {@code file} sets for {@code home}. */
    static HomeLayout from(Path home, Path file, JsonNode root) {
        Map<String, Path> places = new LinkedHashMap<>();
        Set<String> configured = new LinkedHashSet<>();
        for (String key : KEYS) {
            JsonNode value = root.get(key);
            if (value == null || value.isNull()) {
                places.put(key, fixed(home, key));
            } else {
                places.put(key, configuredPlace(home, file, key, value));
                configured.add(key);
            }
        }
        for (String key : configured) {
            String problem = problem(home, key, places);
            if (problem != null) {
                throw new IllegalArgumentException("'" + key + "' in " + file + " names " + places.get(key)
                        + ", which " + problem);
            }
        }
        return new HomeLayout(home, places.get(RUNS_KEY), places.get(LEDGER_KEY), places.get(REGISTERS_KEY))
                .withSealFiles(OperatorConfigFile.path(home, file, root, SIGNING_KEY),
                        OperatorConfigFile.path(home, file, root, ALLOWED_SIGNERS));
    }

    private static Path configuredPlace(Path home, Path file, String key, JsonNode value) {
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("'" + key + "' in " + file + " must be a directory: a string that is"
                    + " not blank, an absolute path or one relative to the home");
        }
        String text = value.textValue();
        if (!text.equals(text.strip())) {
            throw new IllegalArgumentException("'" + key + "' in " + file + " must not begin or end with white space:"
                    + " \"" + text.strip() + "\" is meant, or a folder that is hard to name");
        }
        if (text.startsWith("~")) {
            throw new IllegalArgumentException("'" + key + "' in " + file + " must not start with '~', which only a"
                    + " shell expands: write an absolute path, or one relative to the home, not \"" + text + "\"");
        }
        try {
            return home.resolve(text).normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("'" + key + "' in " + file + " is not a valid path: "
                    + e.getMessage(), e);
        }
    }

    /** Why the place of {@code key} cannot be where it is set; {@code null} when it can. */
    private static String problem(Path home, String key, Map<String, Path> places) {
        Path place = absolute(places.get(key));
        if (absolute(home).startsWith(place)) {
            return "is or holds the Traceworks home " + home + "; name a folder of its own";
        }
        HomeLayout fixed = of(home);
        for (String name : HOME_FILES) {
            Path file = absolute(home.resolve(name));
            if (place.startsWith(file)) {
                return "is or lies in " + home.resolve(name) + ", the name of a file of the home";
            }
        }
        Path own = fixed(home, key);
        if (!place.equals(absolute(own)) && overlap(place, own)) {
            return "lies in " + own + ", the folder of '" + key + "' without the setting, where its earlier content"
                    + " may lie; name a folder outside it";
        }
        Map<Path, String> kept = new LinkedHashMap<>();
        kept.put(fixed.workspaces(), "checkouts of the runs");
        kept.put(fixed.cache(), "caches");
        kept.put(fixed.seals(), "seal log");
        for (Map.Entry<Path, String> other : kept.entrySet()) {
            if (overlap(place, other.getKey())) {
                return "is, lies in or holds " + other.getKey() + ", where the home keeps the " + other.getValue()
                        + "; name a folder of its own";
            }
        }
        for (String otherKey : KEYS) {
            if (otherKey.equals(key)) continue;
            Path other = places.get(otherKey);
            if (overlap(place, other)) {
                return "is, lies in or holds " + other + ", the folder of '" + otherKey + "'; the runs, the ledger"
                        + " and the registers each need a folder of their own";
            }
            Path otherFixed = fixed(home, otherKey);
            if (overlap(place, otherFixed)) {
                return "is, lies in or holds " + otherFixed + ", the folder of '" + otherKey + "' without the"
                        + " setting, where its earlier content may lie; name another folder";
            }
        }
        if (key.equals(RUNS_KEY)) {
            String text = place.toString();
            if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0
                    || text.indexOf('\r') >= 0) {
                return "holds a comma, a quote or a line break: every container mounts its run's scratch directory"
                        + " from the runs folder, and a mount cannot carry such a path";
            }
        }
        // The deepest part that is there, the link itself counted: one without a target is a place that whoever
        // makes the target chooses
        for (Path part = place; part != null; part = part.getParent()) {
            if (Files.exists(part, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.exists(part)) {
                    return (part.equals(place) ? "is" : "lies behind " + part + ",")
                            + " a symbolic link whose target does not exist; create the target, or name the folder"
                            + " itself";
                }
                break;
            }
        }
        if (Files.exists(place) && !Files.isDirectory(place)) {
            return "is not a directory";
        }
        return null;
    }

    private static boolean overlap(Path place, Path other) {
        Path absolute = absolute(other);
        return place.startsWith(absolute) || absolute.startsWith(place);
    }

    private static Path absolute(Path path) {
        return path.toAbsolutePath().normalize();
    }

    /** The folder of the home that {@code key} replaces: its name without {@code _dir}. */
    private static Path fixed(Path home, String key) {
        return home.resolve(key.substring(0, key.length() - "_dir".length()));
    }

    /**
     * The lock file of the ledger's writers. For the fixed folder it is {@code <home>/ledger.lock}, beside the
     * ledger, as it always was. For a ledger the operator placed it is {@code ledger.lock} in the ledger folder, so
     * that nothing but that folder has to be writable (its parent may be a mount point) and every home that names
     * the ledger takes the same lock. The file is never part of the ledger: each commit names the one file it adds.
     */
    public Path ledgerLock() {
        return ledger.equals(fixed(home, LEDGER_KEY)) ? home.resolve("ledger.lock") : ledger.resolve("ledger.lock");
    }

    /** Where the checkouts of the runs started with {@code --source} lie: {@code <home>/workspaces}. */
    public Path workspaces() {
        return home.resolve("workspaces");
    }

    /** The seal log of the home: {@code <home>/seals}. No setting moves it. */
    public Path seals() {
        return home.resolve("seals");
    }

    /** The allowed signers of the home unless the operator names another file: {@code <home>/allowed_signers}. */
    public Path allowedSigners() {
        return home.resolve("allowed_signers");
    }

    /** The caches: {@code <home>/cache}. */
    public Path cache() {
        return home.resolve("cache");
    }

    /** The per-image caches of the tool steps, which a tool step may mount: {@code <home>/cache/tools}. */
    public Path toolCache() {
        return cache().resolve("tools");
    }

    /**
     * The folders that hold run folders with the scratch directories the steps write: the runs folder of now, and
     * the fixed one of the home, where runs lay before a setting named another place. No package and no local
     * source is read from them.
     */
    public List<Path> runsFolders() {
        Path fixed = fixed(home, RUNS_KEY);
        return runs.equals(fixed) ? List.of(runs) : List.of(runs, fixed);
    }

    /**
     * What runs leave behind, which no container may see: the runs, the ledger, the registers and the caches, where
     * they lie now and in their fixed folders of the home, where they lay before a setting named another place, and
     * the seal log, which says what is sealed and which runs a seal refused.
     */
    public List<Path> records() {
        Set<Path> records = new LinkedHashSet<>(List.of(runs, ledger, registers));
        HomeLayout fixed = of(home);
        records.addAll(List.of(fixed.runs, fixed.ledger, fixed.registers, cache(), seals()));
        return new ArrayList<>(records);
    }
}
