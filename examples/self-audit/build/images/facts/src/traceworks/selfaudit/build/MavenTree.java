package traceworks.selfaudit.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The dependencies as Maven resolves them: one {@code dependency:tree} over the reactor, and what it shows against the
 * rule file. Maven runs on a copy of the reactor's POMs only (no sources, no {@code .mvn}), with no lifecycle phase, so
 * no plugin the audited POMs bind runs; with one repository (the rule file's, as a mirror of every other), checksums
 * checked strictly, and its local repository in a fresh folder of the container's {@code /tmp}, never in the scratch
 * directory, which the record keeps. A build extension that a POM declares would still load, so the step refuses to
 * run Maven on such a reactor unless the rule file allows it.
 */
final class MavenTree {

    /** The dependency plugin the step runs, pinned: Maven fetches it by this version and checks its checksums. */
    static final String TREE_GOAL = "org.apache.maven.plugins:maven-dependency-plugin:3.11.0:tree";
    static final Set<String> RUNTIME_SCOPES = Set.of("compile", "runtime", "provided", "system");

    private static final Pattern PART = Pattern.compile("[A-Za-z0-9_.+-]+");
    private static final Pattern CONFLICT = Pattern.compile("omitted for conflict with ([A-Za-z0-9_.+-]+)");
    private static final Pattern MANAGED = Pattern.compile("version managed from ([A-Za-z0-9_.+-]+)");

    /**
     * One node of a tree.
     *
     * @param depth   0 for the module itself
     * @param omitted whether Maven left it out (a duplicate or a conflict); {@code note} says why
     * @param via     the chain of artifacts from the module down to this node, as {@code a > b > c}
     */
    record Node(int depth, String group, String artifact, String classifier, String version, String scope,
                boolean omitted, String note, String via) {

        String coordinate() {
            return group + ":" + artifact;
        }
    }

    /** One module's tree: the module's coordinate and its nodes below it, in order. */
    record Module(String coordinate, List<Node> nodes) {
    }

    /** What runs Maven; a test gives a fake {@code mvn}. */
    record Options(String mvn, Path workParent, Duration timeout) {
    }

    private MavenTree() {
    }

    /**
     * Runs Maven on the reactor of {@code root} and adds what it found to {@code facts}; writes the runtime and the
     * test dependencies as CycloneDX SBOMs (to each path that is not {@code null}) when Maven resolved them, and never
     * when it did not.
     */
    static void run(Path root, RuleFile rules, Options options, Path sbomRuntime, Path sbomTest, Facts facts)
            throws IOException {
        Poms.Reactor reactor = Poms.read(root);
        if (reactor.poms().isEmpty()) {
            facts.gap("maven_tree", "no POM to resolve: " + (reactor.notRead().isEmpty() ? "none"
                    : reactor.notRead().getFirst().what() + " " + reactor.notRead().getFirst().reason()));
            return;
        }
        Facts check = new Facts("check");
        Poms.check(reactor, rules, check);
        List<Facts.Item> extensions = check.items.stream().filter(i -> i.check().equals("build_extension")).toList();
        if (!extensions.isEmpty() && !rules.mavenExtensions()) {
            facts.gap("maven_tree", "the POMs declare " + Registries.count(extensions.size(), "build extension")
                    + " (" + extensions.getFirst().where() + " " + extensions.getFirst().subject() + "), which Maven"
                    + " would load and run; the rule file does not allow that (maven-extensions allow)");
            return;
        }
        Path work = Files.createTempDirectory(options.workParent(), "maven-");
        try {
            Path src = Files.createDirectories(work.resolve("src"));
            Files.createDirectories(src.resolve(".mvn"));
            for (Poms.Pom pom : reactor.poms()) {
                Path target = src.resolve(pom.file()).normalize();
                if (!target.startsWith(src)) continue;
                Files.createDirectories(target.getParent());
                Files.copy(pom.dir().resolve("pom.xml"), target);
            }
            Path settings = work.resolve("settings.xml");
            Files.writeString(settings, settings(rules.mavenRepository()), StandardCharsets.UTF_8);
            Path tree = work.resolve("tree.txt");
            Path log = work.resolve("maven.log");
            List<String> command = List.of(options.mvn(), "-B", "-ntp", "-C", "-Dstyle.color=never", "-s",
                    settings.toString(), "-Dmaven.repo.local=" + work.resolve("m2"), "-f", src.resolve("pom.xml")
                            .toString(), TREE_GOAL, "-Dverbose", "-DoutputFile=" + tree, "-DappendOutput=true");
            ProcessBuilder builder = new ProcessBuilder(command).directory(src.toFile())
                    .redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().remove("MAVEN_ARGS");
            builder.environment().remove("MAVEN_OPTS");
            long started = System.nanoTime();
            Process process = builder.start();
            boolean ended;
            try {
                ended = process.waitFor(options.timeout().toSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ended = false;
            }
            if (!ended) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            long seconds = Duration.ofNanos(System.nanoTime() - started).toSeconds();
            facts.fact("seconds", seconds);
            facts.fact("repository_mib", size(work.resolve("m2")) / (1024 * 1024));
            if (!ended) {
                facts.gap("maven_tree", "Maven did not end within " + options.timeout().toSeconds() + " s");
                return;
            }
            facts.fact("exit", process.exitValue());
            if (process.exitValue() != 0 || !Files.isRegularFile(tree)) {
                facts.gap("maven_tree", "mvn dependency:tree exit " + process.exitValue() + ": " + firstError(log));
                return;
            }
            List<Module> modules = parse(Poms.utf8(tree));
            check(modules, reactor, rules, facts);
            Set<String> runtime = new TreeSet<>();
            Set<String> test = new TreeSet<>();
            for (Module m : modules) {
                for (Node n : m.nodes()) {
                    if (n.omitted() || reactor.coordinates().contains(n.coordinate())) continue;
                    String gav = n.group() + ":" + n.artifact() + ":" + n.version()
                            + (n.classifier().isEmpty() ? "" : ":" + n.classifier());
                    (RUNTIME_SCOPES.contains(n.scope()) ? runtime : test).add(gav);
                }
            }
            test.removeAll(runtime);
            facts.fact("runtime_artifacts", runtime.size());
            facts.fact("test_artifacts", test.size());
            String name = reactor.poms().getFirst().coordinate();
            if (sbomRuntime != null) Files.writeString(sbomRuntime, Json.write(sbom(name, runtime)) + "\n", StandardCharsets.UTF_8);
            if (sbomTest != null) Files.writeString(sbomTest, Json.write(sbom(name, test)) + "\n", StandardCharsets.UTF_8);
        } finally {
            delete(work);
        }
    }

    /** A settings file that sends every repository to the one the rule file names. */
    static String settings(String repository) {
        String url = repository.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
        return """
                <settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
                  <mirrors>
                    <mirror>
                      <id>build-audit</id>
                      <mirrorOf>*</mirrorOf>
                      <url>%s</url>
                    </mirror>
                  </mirrors>
                </settings>
                """.formatted(url);
    }

    /** The trees of {@code dependency:tree -Dverbose} as text, one per module. */
    static List<Module> parse(String text) {
        List<Module> modules = new ArrayList<>();
        List<Node> nodes = null;
        String module = null;
        List<String> chain = new ArrayList<>();
        for (String raw : text.split("\r?\n")) {
            if (raw.isBlank()) continue;
            int depth = 0;
            int at = 0;
            while (raw.startsWith("|  ", at) || raw.startsWith("   ", at) || raw.startsWith("+- ", at)
                    || raw.startsWith("\\- ", at)) {
                at += 3;
                depth++;
            }
            String rest = raw.substring(at).strip();
            if (depth == 0) {
                String[] parts = rest.split(":");
                if (parts.length < 4) throw new IllegalArgumentException("not a module line of a tree: " + shown(rest));
                if (nodes != null) modules.add(new Module(module, List.copyOf(nodes)));
                module = parts[0] + ":" + parts[1];
                nodes = new ArrayList<>();
                chain.clear();
                chain.add(parts[1]);
                continue;
            }
            if (nodes == null) throw new IllegalArgumentException("a tree line before the first module line");
            boolean omitted = rest.startsWith("(") && rest.endsWith(")");
            String body = omitted ? rest.substring(1, rest.length() - 1) : rest;
            String coordinates;
            String note;
            if (omitted) {
                int dash = body.indexOf(" - ");
                coordinates = dash < 0 ? body : body.substring(0, dash);
                note = dash < 0 ? "" : body.substring(dash + 3);
            } else {
                int space = body.indexOf(' ');
                coordinates = space < 0 ? body : body.substring(0, space);
                note = space < 0 ? "" : body.substring(space + 1).replaceAll("^\\(|\\)$", "");
            }
            String[] p = coordinates.split(":");
            String classifier;
            String version;
            String scope;
            if (p.length == 5) {
                classifier = "";
                version = p[3];
                scope = p[4];
            } else if (p.length == 6) {
                classifier = p[3];
                version = p[4];
                scope = p[5];
            } else {
                throw new IllegalArgumentException("not a dependency of a tree: " + shown(coordinates));
            }
            for (String part : p) {
                if (!PART.matcher(part).matches()) throw new IllegalArgumentException("not a dependency: " + shown(coordinates));
            }
            while (chain.size() > depth) chain.removeLast();
            String via = String.join(" > ", chain);
            if (!omitted) chain.add(p[1]);
            else chain.add("(" + p[1] + ")");
            nodes.add(new Node(depth, p[0], p[1], classifier, version, scope, omitted, note, via));
        }
        if (nodes != null) modules.add(new Module(module, List.copyOf(nodes)));
        return List.copyOf(modules);
    }

    /** The runtime policy, the conflicts and the managed downgrades of the trees. */
    static void check(List<Module> modules, Poms.Reactor reactor, RuleFile rules, Facts facts) {
        Set<String> own = reactor.coordinates();
        Set<String> seen = new LinkedHashSet<>();
        int conflicts = 0;
        for (Module m : modules) {
            for (Node n : m.nodes()) {
                String ga = n.coordinate();
                if (!n.omitted() && RUNTIME_SCOPES.contains(n.scope()) && !own.contains(ga) && !rules.allowsRuntime(ga)
                        && seen.add("r " + ga)) {
                    facts.item("runtime_resolved", "", 0, ga, n.version() + " in scope " + n.scope() + " of " + m.coordinate()
                            + ", via " + n.via());
                }
                Matcher conflict = CONFLICT.matcher(n.note());
                if (n.omitted() && conflict.find() && seen.add("c " + ga + " " + n.version() + " " + conflict.group(1))) {
                    conflicts++;
                    String won = conflict.group(1);
                    facts.item("conflict", "", 0, ga, n.version() + " wanted via " + n.via() + ", Maven takes " + won
                            + (Versions.compare(won, n.version()) < 0 ? " (a downgrade)" : "") + " in " + m.coordinate()
                            + " (scope " + n.scope() + ")");
                }
                Matcher managed = MANAGED.matcher(n.note());
                if (managed.find() && Versions.compare(managed.group(1), n.version()) > 0
                        && seen.add("m " + ga + " " + managed.group(1) + " " + n.version())) {
                    facts.item("managed_downgrade", "", 0, ga, managed.group(1) + " wanted via " + n.via()
                            + ", the dependency management gives " + n.version() + " in " + m.coordinate()
                            + " (scope " + n.scope() + ")");
                }
            }
        }
        facts.fact("modules_resolved", modules.size());
        facts.fact("conflicts", conflicts);
        if (modules.size() < reactor.poms().size()) {
            facts.gap("maven_tree_modules", "Maven gave " + modules.size() + " trees for " + reactor.poms().size()
                    + " POMs of the reactor");
        }
    }

    /** A CycloneDX 1.5 SBOM of these {@code groupId:artifactId:version[:classifier]} entries. */
    static Map<String, Object> sbom(String name, Set<String> artifacts) {
        List<Object> components = new ArrayList<>();
        for (String gav : artifacts) {
            String[] p = gav.split(":");
            String purl = "pkg:maven/" + p[0] + "/" + p[1] + "@" + p[2] + (p.length > 3 ? "?classifier=" + p[3] : "");
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type", "library");
            c.put("bom-ref", purl);
            c.put("group", p[0]);
            c.put("name", p[1]);
            c.put("version", p[2]);
            c.put("purl", purl);
            components.add(c);
        }
        Map<String, Object> bom = new LinkedHashMap<>();
        bom.put("bomFormat", "CycloneDX");
        bom.put("specVersion", "1.5");
        bom.put("version", 1L);
        bom.put("metadata", Map.of("component", Map.of("type", "application", "name", name, "bom-ref", "root")));
        bom.put("components", components);
        return bom;
    }

    private static String firstError(Path log) {
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            List<String> errors = lines.stream().filter(l -> l.startsWith("[ERROR]") && l.length() > 8)
                    .map(l -> l.substring(8).strip()).toList();
            if (errors.isEmpty()) return lines.isEmpty() ? "no output" : shown(lines.getLast());
            // "Some problems were encountered ...:" says what follows; the line after it says what went wrong
            String first = errors.getFirst();
            return shown(first.endsWith(":") && errors.size() > 1 ? first + " " + errors.get(1) : first);
        } catch (IOException e) {
            return "its log is not readable";
        }
    }

    private static String shown(String text) {
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }

    private static long size(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        }
    }

    private static void delete(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
