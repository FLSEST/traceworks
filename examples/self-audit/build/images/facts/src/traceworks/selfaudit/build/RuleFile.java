package traceworks.selfaudit.build;

import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The rules of the build audit, from a line-based file: what must hold for the project's build, and where the tool
 * looks. A line is a keyword and its values; blank lines and lines starting with {@code #} are ignored, and an error
 * is refused with its line number.
 *
 * <pre>
 * runtime-allow    &lt;groupId&gt;:&lt;artifactId&gt;   allowed outside the test scope; * matches any run of characters
 * require          enforcer-guard | output-timestamp
 * java-release     &lt;release&gt;
 * plugin-pinned    &lt;artifactId&gt;...           plugins the build uses that must have a version in a POM
 * stable-version   &lt;regex&gt;                   which versions count as releases (default ^\d+(\.\d+)*$)
 * maven-repository &lt;url&gt;                     the one repository Maven and the lookups use (default Maven Central)
 * npm-registry     &lt;url&gt;                     the npm registry of the lookups (default registry.npmjs.org)
 * image-registry   &lt;host&gt; [&lt;token-host&gt;]    an image registry the lookups may ask (default Docker Hub only)
 * maven-extensions allow                     let the Maven step load the build extensions the POMs declare
 * dockerfile       &lt;glob&gt;                    where Dockerfiles are, relative to the workspace
 * accept           &lt;check&gt; &lt;file&gt; &lt;reason&gt;   a measured gap in that Dockerfile that is so on purpose
 * </pre>
 */
final class RuleFile {

    static final String CENTRAL = "https://repo.maven.apache.org/maven2";
    static final String NPM = "https://registry.npmjs.org";
    static final String STABLE = "^\\d+(\\.\\d+)*$";

    /** Docker Hub's name in the rule file: what a Dockerfile means by an image without a registry host. */
    static final String DOCKER_HUB = "docker.io";

    /** The host Docker Hub sends to for its anonymous tokens: the one companion host the tool knows of itself. */
    static final String DOCKER_HUB_TOKENS = "auth.docker.io";

    /** A host name or IPv4 address with an optional port: no scheme, no path, no user, no wildcard. */
    private static final Pattern HOST = Pattern.compile(
            "(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*(?::(?<port>\\d{1,5}))?");

    /** The checks an {@code accept} line may name: gaps of a Dockerfile, never a broken rule of the build itself. */
    static final Set<String> ACCEPTABLE = Set.of("package_unpinned", "download_unverified");

    /** An {@code accept} line: every item of {@code check} in {@code file} is so on purpose, for {@code reason}. */
    record Accept(String check, String file, String reason, int line) {
    }

    /**
     * An {@code image-registry} line: the lookups may ask {@code host} for the digest of a tag, and, when the registry
     * asks for a token, fetch it from {@code host}, {@code tokenHost} or, for Docker Hub, {@code auth.docker.io}.
     * {@code tokenHost} is {@code null} when the line names none; {@code line} is 0 for the default.
     */
    record ImageRegistry(String host, String tokenHost, int line) {

        /** Whether a token may be fetched from {@code tokenAddress} (a host with an optional port). */
        boolean allowsTokenFrom(String tokenAddress, String requestHost) {
            return tokenAddress.equals(requestHost) || tokenAddress.equals(host) || tokenAddress.equals(tokenHost)
                    || host.equals(DOCKER_HUB) && tokenAddress.equals(DOCKER_HUB_TOKENS);
        }
    }

    private final List<String> runtimeAllow;
    private final List<Pattern> runtimeAllowPatterns;
    private final boolean enforcerGuard;
    private final boolean outputTimestamp;
    private final String javaRelease;
    private final List<String> pinnedPlugins;
    private final Pattern stableVersion;
    private final String mavenRepository;
    private final String npmRegistry;
    private final boolean mavenExtensions;
    private final List<String> dockerfiles;
    private final List<Accept> accepts;
    private final List<ImageRegistry> imageRegistries;

    private RuleFile(List<String> runtimeAllow, boolean enforcerGuard, boolean outputTimestamp, String javaRelease,
                     List<String> pinnedPlugins, Pattern stableVersion, String mavenRepository, String npmRegistry,
                     boolean mavenExtensions, List<String> dockerfiles, List<Accept> accepts,
                     List<ImageRegistry> imageRegistries) {
        this.runtimeAllow = List.copyOf(runtimeAllow);
        this.runtimeAllowPatterns = runtimeAllow.stream().map(RuleFile::coordinatePattern).toList();
        this.enforcerGuard = enforcerGuard;
        this.outputTimestamp = outputTimestamp;
        this.javaRelease = javaRelease;
        this.pinnedPlugins = List.copyOf(pinnedPlugins);
        this.stableVersion = stableVersion;
        this.mavenRepository = mavenRepository;
        this.npmRegistry = npmRegistry;
        this.mavenExtensions = mavenExtensions;
        this.dockerfiles = List.copyOf(dockerfiles);
        this.accepts = List.copyOf(accepts);
        this.imageRegistries = List.copyOf(imageRegistries);
    }

    /**
     * The rules of {@code text}.
     *
     * @throws IllegalArgumentException with the line number, for a line that is not a rule
     */
    static RuleFile parse(String text) {
        List<String> runtimeAllow = new ArrayList<>();
        boolean enforcerGuard = false;
        boolean outputTimestamp = false;
        String javaRelease = null;
        Set<String> pinnedPlugins = new LinkedHashSet<>();
        Pattern stable = null;
        String repository = null;
        String npm = null;
        boolean extensions = false;
        List<String> dockerfiles = new ArrayList<>();
        List<Accept> accepts = new ArrayList<>();
        List<ImageRegistry> imageRegistries = new ArrayList<>();

        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int no = i + 1;
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] words = line.split("\\s+", 2);
            String keyword = words[0];
            String rest = words.length > 1 ? words[1].strip() : "";
            if (rest.isEmpty()) throw error(no, "'" + keyword + "' needs a value");
            switch (keyword) {
                case "runtime-allow" -> {
                    if (!rest.matches("[A-Za-z0-9_.*-]+:[A-Za-z0-9_.*-]+")) {
                        throw error(no, "runtime-allow takes <groupId>:<artifactId>, with * as a wildcard");
                    }
                    runtimeAllow.add(rest);
                }
                case "require" -> {
                    switch (rest) {
                        case "enforcer-guard" -> enforcerGuard = true;
                        case "output-timestamp" -> outputTimestamp = true;
                        default -> throw error(no, "require takes enforcer-guard or output-timestamp");
                    }
                }
                case "java-release" -> {
                    if (javaRelease != null) throw error(no, "java-release is given twice");
                    if (!rest.matches("\\d{1,3}")) throw error(no, "java-release takes a number");
                    javaRelease = rest;
                }
                case "plugin-pinned" -> {
                    for (String id : rest.split("\\s+")) {
                        if (!id.matches("[A-Za-z0-9_.-]+")) throw error(no, "'" + id + "' is no artifactId");
                        pinnedPlugins.add(id);
                    }
                }
                case "stable-version" -> {
                    if (stable != null) throw error(no, "stable-version is given twice");
                    try {
                        stable = Pattern.compile(rest);
                    } catch (PatternSyntaxException e) {
                        throw error(no, "stable-version is no regular expression: " + e.getDescription());
                    }
                }
                case "maven-repository" -> {
                    if (repository != null) throw error(no, "maven-repository is given twice");
                    repository = url(no, rest);
                }
                case "npm-registry" -> {
                    if (npm != null) throw error(no, "npm-registry is given twice");
                    npm = url(no, rest);
                }
                case "image-registry" -> {
                    String[] hosts = rest.toLowerCase(Locale.ROOT).split("\\s+");
                    if (hosts.length > 2 || !Arrays.stream(hosts).allMatch(RuleFile::isHost)) {
                        throw error(no, "image-registry takes a host name with an optional port and an optional token"
                                + " host, without a scheme, a path or a user: not '" + rest + "'");
                    }
                    String host = registryName(hosts[0]);
                    if (imageRegistries.stream().anyMatch(r -> r.host().equals(host))) {
                        throw error(no, "image-registry " + host + " is given twice");
                    }
                    imageRegistries.add(new ImageRegistry(host, hosts.length > 1 ? hosts[1] : null, no));
                }
                case "maven-extensions" -> {
                    if (!rest.equals("allow")) throw error(no, "maven-extensions takes allow");
                    extensions = true;
                }
                case "dockerfile" -> {
                    if (rest.startsWith("/") || rest.contains("..") || rest.contains("\\")) {
                        throw error(no, "a dockerfile glob is relative to the workspace and stays inside it");
                    }
                    try {
                        FileSystems.getDefault().getPathMatcher("glob:" + rest);
                    } catch (IllegalArgumentException e) {
                        throw error(no, "'" + rest + "' is no glob: " + e.getMessage());
                    }
                    dockerfiles.add(rest);
                }
                case "accept" -> {
                    String[] parts = rest.split("\\s+", 3);
                    if (parts.length < 3) throw error(no, "accept takes <check> <file> <reason>");
                    if (!ACCEPTABLE.contains(parts[0])) {
                        throw error(no, "accept takes one of " + String.join(", ", ACCEPTABLE.stream().sorted()
                                .toList()) + ", not '" + parts[0] + "'");
                    }
                    accepts.add(new Accept(parts[0], parts[1], parts[2], no));
                }
                default -> throw error(no, "unknown keyword '" + keyword + "'");
            }
        }
        if (dockerfiles.isEmpty()) dockerfiles.add("**/Dockerfile");
        return new RuleFile(runtimeAllow, enforcerGuard, outputTimestamp, javaRelease, List.copyOf(pinnedPlugins),
                stable == null ? Pattern.compile(STABLE) : stable, repository == null ? CENTRAL : repository,
                npm == null ? NPM : npm, extensions, dockerfiles, accepts,
                imageRegistries.isEmpty() ? List.of(new ImageRegistry(DOCKER_HUB, null, 0)) : imageRegistries);
    }

    private static boolean isHost(String text) {
        Matcher m = HOST.matcher(text);
        return m.matches() && (m.group("port") == null || Integer.parseInt(m.group("port")) <= 65535);
    }

    /**
     * The name of an image's registry as the rule file writes it: the host in lower case, and {@code docker.io} for
     * Docker Hub, whether the image names no host ({@code null}), {@code docker.io}, {@code index.docker.io} or the
     * host the registry API answers on, {@code registry-1.docker.io}.
     */
    static String registryName(String host) {
        if (host == null) return DOCKER_HUB;
        String lower = host.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "docker.io", "index.docker.io", "registry-1.docker.io" -> DOCKER_HUB;
            default -> lower;
        };
    }

    /** An https URL, or http to a loopback address (a test's local registry); without a trailing slash. */
    static String url(int no, String text) {
        URI uri;
        try {
            uri = new URI(text);
        } catch (Exception e) {
            throw error(no, "'" + text + "' is no URL");
        }
        if (!secure(uri)) throw error(no, "'" + text + "' is neither https nor http to a loopback address");
        return text.replaceAll("/+$", "");
    }

    /** Whether a URL may be fetched: https, or http to {@code localhost}, {@code 127.0.0.1} or {@code [::1]}. */
    static boolean secure(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) return false;
        return scheme.equals("https")
                || scheme.equals("http") && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"));
    }

    private static Pattern coordinatePattern(String coordinate) {
        StringBuilder regex = new StringBuilder();
        for (String part : coordinate.split("\\*", -1)) {
            if (!regex.isEmpty()) regex.append(".*");
            regex.append(Pattern.quote(part));
        }
        return Pattern.compile(regex.toString());
    }

    private static IllegalArgumentException error(int line, String message) {
        return new IllegalArgumentException("rule file line " + line + ": " + message);
    }

    /** Whether {@code groupId:artifactId} may be a dependency outside the test scope. */
    boolean allowsRuntime(String coordinate) {
        for (Pattern p : runtimeAllowPatterns) {
            if (p.matcher(coordinate).matches()) return true;
        }
        return false;
    }

    /** Whether a workspace-relative path is one of the Dockerfiles the rule file names. */
    boolean isDockerfile(Path relative) {
        for (String glob : dockerfiles) {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
            if (matcher.matches(relative)) return true;
        }
        return false;
    }

    List<String> runtimeAllow() {
        return runtimeAllow;
    }

    boolean enforcerGuard() {
        return enforcerGuard;
    }

    boolean outputTimestamp() {
        return outputTimestamp;
    }

    /** The Java release every module must compile for, or {@code null} when the rule file names none. */
    String javaRelease() {
        return javaRelease;
    }

    List<String> pinnedPlugins() {
        return pinnedPlugins;
    }

    /** Whether a version counts as a release when the lookups look for a newer one. */
    boolean stable(String version) {
        return stableVersion.matcher(version).find();
    }

    String mavenRepository() {
        return mavenRepository;
    }

    String npmRegistry() {
        return npmRegistry;
    }

    boolean mavenExtensions() {
        return mavenExtensions;
    }

    List<String> dockerfiles() {
        return dockerfiles;
    }

    List<Accept> accepts() {
        return accepts;
    }

    /** The image registries the lookups may ask; Docker Hub alone when the rule file names none. */
    List<ImageRegistry> imageRegistries() {
        return imageRegistries;
    }

    /** The {@code image-registry} line of a registry, by its {@link #registryName(String) name}. */
    Optional<ImageRegistry> imageRegistry(String name) {
        String wanted = registryName(name);
        return imageRegistries.stream().filter(r -> r.host().equals(wanted)).findFirst();
    }
}
