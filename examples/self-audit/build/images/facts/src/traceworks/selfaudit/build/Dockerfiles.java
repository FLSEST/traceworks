package traceworks.selfaudit.build;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the Dockerfiles of the workspace pin, read as text, without building anything: the base image of each stage by
 * digest, the version of each package that {@code apt-get}, {@code apk}, {@code npm -g} and {@code pip} install, and a
 * checksum for each download. A Dockerfile is data from the audited project; the tool reads it and never runs it.
 *
 * <p>The reading is lexical. A package that a {@code RUN} installs and removes again in the same {@code RUN} never
 * reaches the image and counts as build-only. A download counts as verified when the same {@code RUN} checks a
 * checksum ({@code sha256sum -c}, {@code sha512sum -c}, their {@code --check}) or a signature ({@code gpg --verify}),
 * or when {@code ADD} has {@code --checksum}. Variables are replaced from {@code ARG} and {@code ENV} defaults and from
 * assignments earlier in the same {@code RUN}; a here-document in a {@code RUN} is not read and is reported.
 */
final class Dockerfiles {

    static final int MAX_DEPTH = 10;
    static final int MAX_FILES = 200;
    private static final Set<String> SKIPPED = Set.of("target", "node_modules", "build");
    private static final Pattern DIGEST = Pattern.compile("@sha256:[0-9a-f]{64}$");
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{?([A-Za-z_][A-Za-z0-9_]*)(?::?-[^}]*)?}?");
    private static final Pattern NPM_EXACT = Pattern.compile("\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?");

    /** One instruction: its keyword in upper case, its arguments with continuation lines joined, its first line. */
    record Instruction(int line, String keyword, String arguments) {
    }

    private Dockerfiles() {
    }

    /** The Dockerfiles below {@code root} that the rule file names, sorted, and those that are symbolic links. */
    static List<Path> find(Path root, RuleFile rules, Facts facts) throws IOException {
        List<Path> found = new ArrayList<>();
        Files.walkFileTree(root, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) return FileVisitResult.CONTINUE;
                String name = dir.getFileName().toString();
                return SKIPPED.contains(name) || name.startsWith(".") ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path rel = root.relativize(file);
                if (!rules.isDockerfile(rel)) return FileVisitResult.CONTINUE;
                if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                    facts.item("dockerfile_not_read", Poms.relative(root, file), 0, Poms.relative(root, file),
                            "a symbolic link or no regular file, not read");
                } else {
                    found.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        found.sort(null);
        if (found.size() > MAX_FILES) {
            for (Path cut : found.subList(MAX_FILES, found.size())) {
                facts.item("dockerfile_not_read", Poms.relative(root, cut), 0, Poms.relative(root, cut),
                        "beyond " + MAX_FILES + " Dockerfiles");
            }
            return List.copyOf(found.subList(0, MAX_FILES));
        }
        return List.copyOf(found);
    }

    /** Reads every Dockerfile the rule file names and adds the items, the facts and the inventory. */
    static void check(Path root, RuleFile rules, Facts facts) throws IOException {
        List<Path> files = find(root, rules, facts);
        List<Map<String, Object>> images = new ArrayList<>();
        List<Map<String, Object>> npm = new ArrayList<>();
        int read = 0;
        for (Path file : files) {
            String rel = Poms.relative(root, file);
            String text;
            try {
                text = Poms.utf8(file);
            } catch (IllegalArgumentException e) {
                facts.item("dockerfile_not_read", rel, 0, rel, e.getMessage());
                continue;
            }
            read++;
            check(rel, text, facts, images, npm);
        }
        facts.fact("dockerfiles", read);
        facts.fact("dockerfile_globs", String.join(", ", rules.dockerfiles()));
        facts.data.put("images", images);
        facts.data.put("npm", npm);
    }

    /** Checks the text of one Dockerfile. */
    static void check(String file, String text, Facts facts, List<Map<String, Object>> images,
                      List<Map<String, Object>> npm) {
        Map<String, String> vars = new HashMap<>();
        Set<String> stages = new HashSet<>();
        for (Instruction ins : instructions(text)) {
            String args = ins.arguments();
            switch (ins.keyword()) {
                case "ARG", "ENV" -> assign(args, vars, ins.keyword().equals("ENV"));
                case "FROM" -> {
                    List<String> words = new ArrayList<>(words(args));
                    words.removeIf(w -> w.startsWith("--"));
                    if (words.isEmpty()) continue;
                    String ref = expand(words.getFirst(), vars);
                    base(file, ins.line(), ref, stages, facts, images);
                    if (words.size() >= 3 && words.get(1).equalsIgnoreCase("as")) {
                        stages.add(words.get(2).toLowerCase(Locale.ROOT));
                    }
                }
                case "COPY" -> {
                    for (String w : words(args)) {
                        if (w.startsWith("--from=")) {
                            String ref = expand(w.substring(7), vars);
                            if (!ref.matches("\\d+")) base(file, ins.line(), ref, stages, facts, images);
                        }
                    }
                }
                case "ADD" -> {
                    List<String> words = words(args);
                    boolean checksum = words.stream().anyMatch(w -> w.startsWith("--checksum="));
                    for (String w : words) {
                        if (w.matches("(?i)(https?|ftp)://.*") && !checksum) {
                            facts.item("download_unverified", file, ins.line(), expand(w, vars),
                                    "ADD of a URL without --checksum");
                        }
                    }
                }
                case "RUN" -> run(file, ins.line(), args, new HashMap<>(vars), facts, npm);
                default -> {
                }
            }
        }
    }

    private static void base(String file, int line, String ref, Set<String> stages, Facts facts,
                             List<Map<String, Object>> images) {
        if (ref.equalsIgnoreCase("scratch") || stages.contains(ref.toLowerCase(Locale.ROOT))) return;
        if (ref.contains("$")) {
            facts.item("base_unpinned", file, line, ref, "the image names a variable without a default");
            return;
        }
        Matcher digest = DIGEST.matcher(ref);
        if (!digest.find()) {
            facts.item("base_unpinned", file, line, ref, "no @sha256: digest: the tag can point to other bytes tomorrow");
            return;
        }
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("reference", ref);
        image.put("file", file);
        image.put("line", (long) line);
        images.add(image);
    }

    /** The commands of one {@code RUN}: installs, removals and downloads, with assignments that come before them. */
    private static void run(String file, int line, String args, Map<String, String> vars, Facts facts,
                            List<Map<String, Object>> npm) {
        if (args.matches("(?s).*<<-?\\s*['\"]?[A-Za-z_].*")) {
            facts.item("dockerfile_not_read", file, line, "RUN", "a here-document, which this tool does not read");
        }
        String flat = args.replaceAll("^(--[a-z]+=\\S+\\s+)+", "");
        List<List<String>> commands = commands(flat);
        Set<String> removed = new HashSet<>();
        boolean verified = false;
        for (List<String> command : commands) {
            String tool = command.isEmpty() ? "" : command.getFirst();
            boolean apt = tool.equals("apt-get") || tool.equals("apt");
            if (apt && (command.contains("purge") || command.contains("remove"))
                    || tool.equals("apk") && command.contains("del")) {
                for (String w : command) if (!w.startsWith("-")) removed.add(w);
            }
            if (tool.matches("sha(1|224|256|384|512)sum") && (command.contains("-c") || command.contains("--check"))
                    || tool.equals("gpg") && command.contains("--verify")) {
                verified = true;
            }
        }
        record Install(String manager, String spec) {
        }
        List<Install> unpinned = new ArrayList<>();
        List<String> downloads = new ArrayList<>();
        for (List<String> command : commands) {
            List<String> words = new ArrayList<>();
            for (String w : command) words.add(expand(w, vars));
            while (!words.isEmpty() && words.getFirst().matches("[A-Za-z_][A-Za-z0-9_]*=.*")) {
                String w = words.removeFirst();
                int eq = w.indexOf('=');
                vars.put(w.substring(0, eq), w.substring(eq + 1));
            }
            if (words.isEmpty()) continue;
            if (words.getFirst().equals("sudo")) words.removeFirst();
            if (words.isEmpty()) continue;
            String tool = words.getFirst();
            switch (tool) {
                case "apt-get", "apt" -> {
                    int at = words.indexOf("install");
                    if (at < 0) break;
                    for (String w : packages(words.subList(at + 1, words.size()), Set.of("-o", "-t"))) {
                        if (!w.contains("=")) unpinned.add(new Install("apt", w));
                    }
                }
                case "apk" -> {
                    int at = words.indexOf("add");
                    if (at < 0) break;
                    for (String w : packages(words.subList(at + 1, words.size()), Set.of("--virtual", "-t",
                            "--repository", "-X"))) {
                        if (!w.contains("=")) unpinned.add(new Install("apk", w));
                    }
                }
                case "npm" -> {
                    if (words.size() < 2 || !Set.of("install", "i", "add").contains(words.get(1))) break;
                    if (!words.contains("-g") && !words.contains("--global")) break;
                    for (String w : packages(words.subList(2, words.size()), Set.of())) {
                        int at = w.lastIndexOf('@');
                        String name = at > 0 ? w.substring(0, at) : w;
                        String version = at > 0 ? w.substring(at + 1) : "";
                        if (!NPM_EXACT.matcher(version).matches()) {
                            facts.item("package_unpinned", file, line, "npm " + w,
                                    version.isEmpty() ? "no version" : "not an exact version: " + version);
                            continue;
                        }
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("name", name);
                        entry.put("version", version);
                        entry.put("file", file);
                        entry.put("line", (long) line);
                        npm.add(entry);
                        facts.item("npm_dependencies", file, line, "npm " + name + "@" + version,
                                "its own npm dependencies resolve on the day of the build (no lock file)");
                    }
                }
                case "pip", "pip3" -> {
                    if (words.size() < 2 || !words.get(1).equals("install")) break;
                    for (String w : packages(words.subList(2, words.size()), Set.of("-r", "-c", "-i", "--index-url"))) {
                        if (!w.contains("==")) unpinned.add(new Install("pip", w));
                    }
                }
                case "curl", "wget" -> {
                    for (String w : words) {
                        if (w.matches("(?i)(https?|ftp)://.*")) downloads.add(w);
                    }
                }
                default -> {
                }
            }
        }
        for (Install i : unpinned) {
            String name = i.spec().replaceAll("[=<>].*", "");
            if (removed.contains(name)) {
                facts.item("package_build_only", file, line, i.manager() + " " + i.spec(),
                        "not pinned, and removed again in the same RUN: it never reaches the image");
            } else {
                facts.item("package_unpinned", file, line, i.manager() + " " + i.spec(), "no version");
            }
        }
        for (String url : downloads) {
            if (!verified) {
                facts.item("download_unverified", file, line, url, "a download, and the same RUN checks no checksum");
            }
        }
    }

    /** The package arguments: words that are no option, and not the value of an option that takes one. */
    private static List<String> packages(List<String> words, Set<String> withValue) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            if (withValue.contains(w)) {
                i++;
                continue;
            }
            if (w.startsWith("-") || w.isEmpty()) continue;
            out.add(w);
        }
        return out;
    }

    /** The instructions of a Dockerfile: comments dropped, continuation lines joined. */
    static List<Instruction> instructions(String text) {
        List<Instruction> out = new ArrayList<>();
        char escape = '\\';
        String[] lines = text.split("\r?\n", -1);
        boolean directives = true;
        StringBuilder current = null;
        int start = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String stripped = line.strip();
            if (directives) {
                Matcher d = Pattern.compile("#\\s*escape\\s*=\\s*(\\S)").matcher(stripped);
                if (d.matches()) {
                    escape = d.group(1).charAt(0);
                    continue;
                }
                if (!stripped.matches("#\\s*[a-z]+\\s*=.*")) directives = false;
            }
            if (stripped.startsWith("#")) continue;
            if (current == null) {
                if (stripped.isEmpty()) continue;
                current = new StringBuilder();
                start = i + 1;
            }
            boolean continues = !stripped.isEmpty() && stripped.charAt(stripped.length() - 1) == escape;
            current.append(continues ? stripped.substring(0, stripped.length() - 1) : stripped).append(' ');
            if (!continues) {
                String joined = current.toString().strip();
                String[] parts = joined.split("\\s+", 2);
                out.add(new Instruction(start, parts[0].toUpperCase(Locale.ROOT), parts.length > 1 ? parts[1] : ""));
                current = null;
            }
        }
        if (current != null && !current.toString().isBlank()) {
            String joined = current.toString().strip();
            String[] parts = joined.split("\\s+", 2);
            out.add(new Instruction(start, parts[0].toUpperCase(Locale.ROOT), parts.length > 1 ? parts[1] : ""));
        }
        return out;
    }

    /** The commands of a shell line, split at {@code &&}, {@code ||}, {@code ;} and {@code |} outside quotes. */
    static List<List<String>> commands(String line) {
        List<List<String>> out = new ArrayList<>();
        List<String> command = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean inWord = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else word.append(c);
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                inWord = true;
                continue;
            }
            if (c == ';' || c == '|' || c == '&') {
                if (inWord) command.add(word.toString());
                word.setLength(0);
                inWord = false;
                if (!command.isEmpty()) out.add(command);
                command = new ArrayList<>();
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (inWord) command.add(word.toString());
                word.setLength(0);
                inWord = false;
                continue;
            }
            word.append(c);
            inWord = true;
        }
        if (inWord) command.add(word.toString());
        if (!command.isEmpty()) out.add(command);
        return out;
    }

    private static List<String> words(String args) {
        List<String> out = new ArrayList<>();
        for (List<String> c : commands(args)) out.addAll(c);
        return out;
    }

    /** {@code ARG a=1 b=2}, {@code ENV a=1}, {@code ENV a 1}: the defaults a later instruction sees. */
    private static void assign(String args, Map<String, String> vars, boolean env) {
        List<String> words = words(args);
        if (env && words.size() == 2 && !words.getFirst().contains("=")) {
            vars.put(words.get(0), words.get(1));
            return;
        }
        for (String w : words) {
            int eq = w.indexOf('=');
            if (eq > 0) vars.put(w.substring(0, eq), expand(w.substring(eq + 1), vars));
        }
    }

    /** {@code $name} and {@code ${name}} replaced from {@code vars}; an unknown one stays as it is. */
    static String expand(String text, Map<String, String> vars) {
        Matcher m = VARIABLE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = vars.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group() : value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
