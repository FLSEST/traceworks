package traceworks.selfaudit.build;

import traceworks.selfaudit.build.Xml.Element;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The POMs of a Maven reactor, read without Maven and without network: the root {@code pom.xml} of the workspace and
 * every module it names (in its profiles too), and the checks of the rule file against what they declare. A POM is
 * data from the audited project: it is read with {@link Xml}, a module path that leaves the workspace or is a symbolic
 * link is not followed, and every name lands in the facts as it is, for the report to escape.
 *
 * <p>What it can see: the declared dependencies, dependency management, plugins and plugin management of each POM and
 * its parents in the reactor, with the properties of that line resolved. What it cannot see: a parent outside the
 * reactor, and what an imported BOM manages; the Maven step resolves those.
 */
final class Poms {

    static final int MAX_POMS = 500;
    static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final String DEFAULT_PLUGIN_GROUP = "org.apache.maven.plugins";
    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");
    private static final Set<String> RUNTIME_SCOPES = Set.of("compile", "runtime", "provided", "system");

    /** One POM of the reactor. {@code parent} is the {@code groupId:artifactId} its parent names, or {@code ""}. */
    record Pom(String file, Path dir, Element root, String groupId, String artifactId, String version,
               String packaging, String parent, String parentVersion) {

        String coordinate() {
            return groupId + ":" + artifactId;
        }
    }

    /** The reactor: the POMs read, in the order Maven names them, and the ones that could not be read. */
    record Reactor(List<Pom> poms, List<Facts.Gap> notRead) {

        Set<String> coordinates() {
            Set<String> out = new HashSet<>();
            for (Pom p : poms) out.add(p.coordinate());
            return out;
        }

        Pom byCoordinate(String coordinate) {
            for (Pom p : poms) {
                if (p.coordinate().equals(coordinate)) return p;
            }
            return null;
        }

        /** {@code pom} and its parents in the reactor, nearest first. */
        List<Pom> lineage(Pom pom) {
            List<Pom> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Pom p = pom; p != null && seen.add(p.coordinate()); p = p.parent().isEmpty() ? null
                    : byCoordinate(p.parent())) {
                out.add(p);
            }
            return out;
        }

        /** The properties {@code pom} sees: its parents' first, its own over them, and the project's coordinates. */
        Map<String, String> properties(Pom pom) {
            Map<String, String> props = new LinkedHashMap<>();
            List<Pom> line = lineage(pom);
            for (int i = line.size() - 1; i >= 0; i--) {
                Element properties = line.get(i).root().child("properties");
                if (properties == null) continue;
                for (Element p : properties.children()) props.put(p.name(), p.text());
            }
            for (String prefix : List.of("project.", "pom.", "")) {
                props.put(prefix + "groupId", pom.groupId());
                props.put(prefix + "artifactId", pom.artifactId());
                props.put(prefix + "version", pom.version());
            }
            props.put("project.parent.version", pom.parentVersion());
            props.put("project.basedir", "${project.basedir}");
            return props;
        }
    }

    private Poms() {
    }

    /** The reactor that starts at {@code root/pom.xml}. */
    static Reactor read(Path root) throws IOException {
        Path rootReal = root.toRealPath();
        List<Pom> poms = new ArrayList<>();
        List<Facts.Gap> notRead = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        Deque<Path> queue = new ArrayDeque<>();
        queue.add(rootReal);
        while (!queue.isEmpty()) {
            Path dir = queue.removeFirst();
            String rel = relative(rootReal, dir.resolve("pom.xml"));
            if (!seen.add(dir)) continue;
            if (poms.size() >= MAX_POMS) {
                notRead.add(new Facts.Gap(rel, "beyond " + MAX_POMS + " POMs"));
                continue;
            }
            Path file = dir.resolve("pom.xml");
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                notRead.add(new Facts.Gap(rel, Files.isSymbolicLink(file) ? "a symbolic link, not followed"
                        : "no pom.xml there"));
                continue;
            }
            Element element;
            try {
                element = Xml.parse(utf8(file));
            } catch (IllegalArgumentException e) {
                notRead.add(new Facts.Gap(rel, e.getMessage()));
                continue;
            }
            if (!element.name().equals("project")) {
                notRead.add(new Facts.Gap(rel, "its root element is no <project>"));
                continue;
            }
            Element parent = element.child("parent");
            String parentGroup = parent == null ? "" : parent.text("groupId");
            String parentArtifact = parent == null ? "" : parent.text("artifactId");
            String parentVersion = parent == null ? "" : parent.text("version");
            String groupId = element.text("groupId").isEmpty() ? parentGroup : element.text("groupId");
            String version = element.text("version").isEmpty() ? parentVersion : element.text("version");
            String packaging = element.text("packaging").isEmpty() ? "jar" : element.text("packaging");
            poms.add(new Pom(rel, dir, element, groupId, element.text("artifactId"), version, packaging,
                    parent == null ? "" : parentGroup + ":" + parentArtifact, parentVersion));

            List<Element> modules = new ArrayList<>(element.path("modules/module"));
            modules.addAll(element.path("profiles/profile/modules/module"));
            for (Element module : modules) {
                String name = module.text();
                Path next = dir.resolve(name).normalize();
                String nextRel = relative(rootReal, next.resolve("pom.xml"));
                if (name.isEmpty() || !next.startsWith(rootReal)) {
                    notRead.add(new Facts.Gap(nextRel, "a module outside the workspace, not followed"));
                    continue;
                }
                if (Files.isSymbolicLink(next) || !Files.isDirectory(next, LinkOption.NOFOLLOW_LINKS)) {
                    notRead.add(new Facts.Gap(nextRel, Files.isSymbolicLink(next)
                            ? "a symbolic link to a directory, not followed" : "the module's directory does not exist"));
                    continue;
                }
                Path real = next.toRealPath();
                if (!real.startsWith(rootReal)) {
                    notRead.add(new Facts.Gap(nextRel, "its real path leaves the workspace, not followed"));
                    continue;
                }
                queue.add(real);
            }
        }
        return new Reactor(List.copyOf(poms), List.copyOf(notRead));
    }

    /** Runs every check of the rule file on the reactor and adds the items, the facts and the inventory. */
    static void check(Reactor reactor, RuleFile rules, Facts facts) {
        for (Facts.Gap gap : reactor.notRead()) facts.item("pom_not_read", gap.what(), 0, gap.what(), gap.reason());
        facts.fact("poms", reactor.poms().size());
        facts.fact("poms_not_read", reactor.notRead().size());
        Set<String> reactorCoordinates = reactor.coordinates();
        List<Map<String, Object>> inventory = new ArrayList<>();
        Map<String, Long> scopes = new TreeMap<>();
        int declared = 0;
        int plugins = 0;
        Set<String> repositories = new TreeSet<>();
        int extensions = 0;

        for (Pom pom : reactor.poms()) {
            Map<String, String> props = reactor.properties(pom);
            List<Pom> line = reactor.lineage(pom);
            if (!pom.parent().isEmpty() && reactor.byCoordinate(pom.parent()) == null) {
                version(facts, pom, pom.root().child("parent"), "parent", pom.parent(), pom.parentVersion(), props,
                        reactorCoordinates, inventory);
            }
            for (Container c : containers(pom.root())) {
                for (Element dep : c.element().path("dependencies/dependency")) {
                    declared++;
                    String ga = dep.text("groupId") + ":" + dep.text("artifactId");
                    String scope = dep.text("scope");
                    String managedScope = managed(line, ga, "scope");
                    String effectiveScope = !scope.isEmpty() ? scope : managedScope.isEmpty() ? "compile" : managedScope;
                    effectiveScope = resolve(effectiveScope, props);
                    scopes.merge(effectiveScope, 1L, Long::sum);
                    String version = dep.text("version");
                    if (!version.isEmpty()) {
                        version(facts, pom, dep, "dependency", ga, version, props, reactorCoordinates, inventory);
                    } else if (managed(line, ga, "version").isEmpty() && !importsBom(line)) {
                        facts.item("version_not_pinned", pom.file(), dep.line(), ga,
                                "no version, and no POM of the reactor manages one");
                    }
                    if (RUNTIME_SCOPES.contains(effectiveScope) && !reactorCoordinates.contains(ga)
                            && !rules.allowsRuntime(ga)) {
                        facts.item("runtime_declared", pom.file(), dep.line(), ga,
                                "declared in scope " + effectiveScope + c.where());
                    }
                }
                for (Element dep : c.element().path("dependencyManagement/dependencies/dependency")) {
                    String ga = dep.text("groupId") + ":" + dep.text("artifactId");
                    boolean bom = "import".equals(dep.text("scope"));
                    version(facts, pom, dep, bom ? "import" : "managed", ga, dep.text("version"), props,
                            reactorCoordinates, inventory);
                }
                for (String where : List.of("build/plugins/plugin", "build/pluginManagement/plugins/plugin")) {
                    boolean management = where.contains("Management");
                    for (Element plugin : c.element().path(where)) {
                        plugins++;
                        String ga = pluginCoordinate(plugin);
                        String version = plugin.text("version");
                        if (!version.isEmpty()) {
                            version(facts, pom, plugin, management ? "plugin-managed" : "plugin", ga, version, props,
                                    reactorCoordinates, inventory);
                        } else if (!management && pluginVersion(line, ga).isEmpty()) {
                            facts.item("plugin_without_version", pom.file(), plugin.line(), ga,
                                    "no version here or in the plugin management of the reactor" + c.where());
                        }
                        if ("true".equals(plugin.text("extensions"))) {
                            extensions++;
                            facts.item("build_extension", pom.file(), plugin.line(), ga,
                                    "a plugin with extensions: Maven loads it when it reads the project" + c.where());
                        }
                        for (Element dep : plugin.path("dependencies/dependency")) {
                            version(facts, pom, dep, "plugin-dependency", dep.text("groupId") + ":"
                                    + dep.text("artifactId"), dep.text("version"), props, reactorCoordinates, null);
                        }
                    }
                }
                for (Element extension : c.element().path("build/extensions/extension")) {
                    extensions++;
                    String ga = extension.text("groupId") + ":" + extension.text("artifactId");
                    facts.item("build_extension", pom.file(), extension.line(), ga,
                            "a build extension: Maven loads it when it reads the project" + c.where());
                    version(facts, pom, extension, "extension", ga, extension.text("version"), props,
                            reactorCoordinates, null);
                }
                for (String where : List.of("repositories/repository", "pluginRepositories/pluginRepository")) {
                    for (Element repo : c.element().path(where)) {
                        String url = resolve(repo.text("url"), props);
                        repositories.add(url);
                        if (url.regionMatches(true, 0, "http://", 0, 7)) {
                            facts.item("insecure_repository", pom.file(), repo.line(), url,
                                    "a repository over plain http" + c.where());
                        }
                    }
                }
            }
        }
        facts.fact("dependencies_declared", declared);
        scopes.forEach((scope, n) -> facts.fact("scope_" + scope.replaceAll("[^a-z0-9]", "_"), n));
        facts.fact("plugins_declared", plugins);
        facts.fact("repositories", repositories.isEmpty() ? "none declared" : String.join(", ", repositories));
        facts.fact("build_extensions", extensions);

        pinnedPlugins(reactor, rules, facts);
        javaRelease(reactor, rules, facts);
        outputTimestamp(reactor, rules, facts);
        enforcerGuard(reactor, rules, facts);
        facts.data.put("maven", inventory);
    }

    /** The root of a POM and each of its profiles: where dependencies and plugins can be declared. */
    private record Container(Element element, String profile) {
        String where() {
            return profile.isEmpty() ? "" : " (profile " + profile + ")";
        }
    }

    private static List<Container> containers(Element root) {
        List<Container> out = new ArrayList<>();
        out.add(new Container(root, ""));
        for (Element profile : root.path("profiles/profile")) {
            out.add(new Container(profile, profile.text("id").isEmpty() ? "?" : profile.text("id")));
        }
        return out;
    }

    /**
     * Checks one explicit version and adds it to the inventory when it names an artifact outside the reactor: a range,
     * {@code LATEST}, {@code RELEASE}, and a snapshot of an artifact outside the reactor are not pinned; a property
     * that does not resolve is not read.
     */
    private static void version(Facts facts, Pom pom, Element at, String kind, String ga, String raw,
                                Map<String, String> props, Set<String> reactor, List<Map<String, Object>> inventory) {
        int line = at == null ? 0 : at.line();
        if (raw.isEmpty()) {
            if (kind.equals("extension") || kind.equals("import") || kind.equals("plugin-dependency")) {
                facts.item("version_not_pinned", pom.file(), line, ga, "a " + kind + " without a version");
            }
            return;
        }
        String version = resolve(raw, props);
        if (version.contains("${")) {
            facts.item("version_unresolved", pom.file(), line, ga, raw + " does not resolve from the reactor's properties");
            return;
        }
        boolean own = reactor.contains(ga);
        String problem = null;
        if (version.matches("^[\\[(].*[])]$") || version.contains(",")) problem = "a version range " + version;
        else if (version.equals("LATEST") || version.equals("RELEASE")) problem = version + " is no version";
        else if (version.endsWith("-SNAPSHOT") && !own) problem = "a snapshot of an artifact outside the reactor: " + version;
        if (problem != null) {
            facts.item("version_not_pinned", pom.file(), line, ga, problem);
            return;
        }
        if (own || inventory == null) return;
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("kind", kind);
        entry.put("coordinate", ga);
        entry.put("version", version);
        entry.put("file", pom.file());
        entry.put("line", (long) line);
        inventory.add(entry);
    }

    /** {@code text} with every {@code ${name}} that {@code props} knows replaced, up to ten rounds deep. */
    static String resolve(String text, Map<String, String> props) {
        String current = text;
        for (int round = 0; round < 10 && current.contains("${"); round++) {
            Matcher m = PROPERTY.matcher(current);
            StringBuilder out = new StringBuilder();
            boolean changed = false;
            while (m.find()) {
                String value = props.get(m.group(1));
                if (value != null && !value.equals(m.group())) {
                    m.appendReplacement(out, Matcher.quoteReplacement(value));
                    changed = true;
                } else {
                    m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
                }
            }
            m.appendTail(out);
            current = out.toString();
            if (!changed) break;
        }
        return current;
    }

    /** What the dependency management of the lineage says for {@code ga} ({@code version} or {@code scope}). */
    private static String managed(List<Pom> line, String ga, String field) {
        for (Pom p : line) {
            for (Container c : containers(p.root())) {
                for (Element dep : c.element().path("dependencyManagement/dependencies/dependency")) {
                    if ((dep.text("groupId") + ":" + dep.text("artifactId")).equals(ga) && !dep.text(field).isEmpty()) {
                        return dep.text(field);
                    }
                }
            }
        }
        return "";
    }

    private static boolean importsBom(List<Pom> line) {
        for (Pom p : line) {
            for (Container c : containers(p.root())) {
                for (Element dep : c.element().path("dependencyManagement/dependencies/dependency")) {
                    if ("import".equals(dep.text("scope"))) return true;
                }
            }
        }
        return false;
    }

    /** The version the lineage gives a plugin: in its plugins or its plugin management; {@code ""} for none. */
    private static String pluginVersion(List<Pom> line, String ga) {
        for (Pom p : line) {
            for (Container c : containers(p.root())) {
                for (String where : List.of("build/plugins/plugin", "build/pluginManagement/plugins/plugin")) {
                    for (Element plugin : c.element().path(where)) {
                        if (pluginCoordinate(plugin).equals(ga) && !plugin.text("version").isEmpty()) {
                            return plugin.text("version");
                        }
                    }
                }
            }
        }
        return "";
    }

    private static String pluginCoordinate(Element plugin) {
        String group = plugin.text("groupId").isEmpty() ? DEFAULT_PLUGIN_GROUP : plugin.text("groupId");
        return group + ":" + plugin.text("artifactId");
    }

    /** Each plugin of {@code plugin-pinned} needs a version in some POM of the reactor. */
    private static void pinnedPlugins(Reactor reactor, RuleFile rules, Facts facts) {
        Set<String> pinned = new HashSet<>();
        for (Pom pom : reactor.poms()) {
            for (Container c : containers(pom.root())) {
                for (String where : List.of("build/plugins/plugin", "build/pluginManagement/plugins/plugin")) {
                    for (Element plugin : c.element().path(where)) {
                        if (!plugin.text("version").isEmpty()) pinned.add(plugin.text("artifactId"));
                    }
                }
            }
        }
        String rootFile = reactor.poms().isEmpty() ? "pom.xml" : reactor.poms().getFirst().file();
        for (String id : rules.pinnedPlugins()) {
            if (!pinned.contains(id)) {
                facts.item("plugin_not_pinned", rootFile, 0, id,
                        "the build uses it, and no POM gives it a version: it comes from the Maven that runs the build");
            }
        }
    }

    /** The release every module that compiles is built for: {@code maven.compiler.release} or the compiler's own. */
    private static void javaRelease(Reactor reactor, RuleFile rules, Facts facts) {
        Set<String> releases = new LinkedHashSet<>();
        for (Pom pom : reactor.poms()) {
            if (pom.packaging().equals("pom")) continue;
            Map<String, String> props = reactor.properties(pom);
            String release = props.getOrDefault("maven.compiler.release", "");
            for (Pom p : reactor.lineage(pom)) {
                for (Element plugin : p.root().path("build/plugins/plugin")) {
                    if (plugin.text("artifactId").equals("maven-compiler-plugin") && plugin.child("configuration") != null
                            && !plugin.child("configuration").text("release").isEmpty()) {
                        release = plugin.child("configuration").text("release");
                    }
                }
                if (!release.isEmpty()) break;
            }
            release = resolve(release, props);
            releases.add(release.isEmpty() ? "not set" : release);
            if (rules.javaRelease() != null && !rules.javaRelease().equals(release)) {
                facts.item("java_release_differs", pom.file(), 0, pom.coordinate(),
                        "compiles for " + (release.isEmpty() ? "no release (maven.compiler.release not set)" : release)
                                + ", the rule file says " + rules.javaRelease());
            }
        }
        facts.fact("java_release", releases.isEmpty() ? "no module compiles" : String.join(", ", releases));
    }

    /** {@code project.build.outputTimestamp} in every module that builds an artifact. */
    private static void outputTimestamp(Reactor reactor, RuleFile rules, Facts facts) {
        Set<String> values = new LinkedHashSet<>();
        for (Pom pom : reactor.poms()) {
            if (pom.packaging().equals("pom")) continue;
            String value = resolve(reactor.properties(pom).getOrDefault("project.build.outputTimestamp", ""),
                    reactor.properties(pom));
            values.add(value.isEmpty() ? "not set" : value);
            if (value.isEmpty() && rules.outputTimestamp()) {
                facts.item("output_timestamp_missing", pom.file(), 0, pom.coordinate(),
                        "project.build.outputTimestamp is not set: the jar's entries carry the time of the build");
            }
        }
        facts.fact("output_timestamp", values.isEmpty() ? "no module builds an artifact" : String.join(", ", values));
    }

    /**
     * The guard of the runtime dependencies: a {@code bannedDependencies} rule of the enforcer in the plugins (not
     * only the plugin management) of a POM that every module inherits from, banning the compile, runtime, provided and
     * system scopes, and including exactly what the rule file's {@code runtime-allow} lines allow, transitively.
     */
    private static void enforcerGuard(Reactor reactor, RuleFile rules, Facts facts) {
        record Guard(Pom pom, Element rule) {
        }
        List<Guard> guards = new ArrayList<>();
        for (Pom pom : reactor.poms()) {
            for (Element plugin : pom.root().path("build/plugins/plugin")) {
                if (!plugin.text("artifactId").equals("maven-enforcer-plugin")) continue;
                List<Element> configurations = new ArrayList<>(plugin.path("configuration"));
                configurations.addAll(plugin.path("executions/execution/configuration"));
                for (Element configuration : configurations) {
                    for (Element rule : configuration.path("rules/bannedDependencies")) guards.add(new Guard(pom, rule));
                }
            }
        }
        facts.fact("enforcer_guard", guards.isEmpty() ? "none" : String.join(", ",
                guards.stream().map(g -> g.pom().file() + ":" + g.rule().line()).toList()));
        if (!rules.enforcerGuard()) return;
        for (Pom pom : reactor.poms()) {
            boolean covered = false;
            for (Pom p : reactor.lineage(pom)) {
                for (Guard g : guards) covered |= g.pom() == p;
            }
            if (!covered) {
                facts.item("enforcer_guard_missing", pom.file(), 0, pom.coordinate(),
                        "no bannedDependencies rule of the enforcer in this POM or a parent in the reactor");
            }
        }
        Set<String> allowed = new TreeSet<>(rules.runtimeAllow());
        for (Guard g : guards) {
            Set<String> includes = new TreeSet<>();
            for (Element include : g.rule().path("includes/include")) includes.add(include.text());
            Set<String> excludes = new HashSet<>();
            for (Element exclude : g.rule().path("excludes/exclude")) excludes.add(exclude.text());
            List<String> problems = new ArrayList<>();
            if (!includes.equals(allowed)) {
                problems.add("it allows " + (includes.isEmpty() ? "nothing" : String.join(", ", includes))
                        + ", the rule file allows " + (allowed.isEmpty() ? "nothing" : String.join(", ", allowed)));
            }
            List<String> open = new ArrayList<>();
            for (String scope : List.of("compile", "runtime", "provided", "system")) {
                if (!excludes.contains("*") && !excludes.contains("*:*:*:*:" + scope)
                        && !excludes.contains("*:*:*:*:" + scope + ":*")) {
                    open.add(scope);
                }
            }
            if (!open.isEmpty()) problems.add("it does not ban the scope " + String.join(", ", open));
            if ("false".equals(g.rule().text("searchTransitive"))) {
                problems.add("searchTransitive is false: only direct dependencies are checked");
            }
            if (!problems.isEmpty()) {
                facts.item("enforcer_guard_differs", g.pom().file(), g.rule().line(), "bannedDependencies",
                        String.join("; ", problems));
            }
        }
    }

    static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /** The file as UTF-8, at most {@value #MAX_BYTES} bytes; anything else is refused. */
    static String utf8(Path file) throws IOException {
        if (Files.size(file) > MAX_BYTES) throw new IllegalArgumentException("larger than " + MAX_BYTES + " bytes");
        byte[] bytes = Files.readAllBytes(file);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("not UTF-8");
        }
    }
}
