package traceworks.selfaudit.build;

import traceworks.selfaudit.build.Xml.Element;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The versions the offline step found, against what their registries offer today: each Maven dependency and plugin
 * against its {@code maven-metadata.xml}, each pinned npm package against the npm registry, and each base image's tag
 * against its registry. Every lookup that fails is a gap with its reason, never an error of the step: no network, a
 * rate limit or an answer that does not parse leaves that version not measured.
 *
 * <p>The rule file, not the audited project, names the hosts: Maven and npm have one address each, and an image's
 * registry is asked only when an {@code image-registry} line names it. A base image on another registry is not
 * contacted at all and stays not measured. A registry's token address must be the registry itself, the token host its
 * line names, or {@code auth.docker.io} for Docker Hub, and {@link Http} follows no redirect to another host.
 *
 * <p>The answers are data from outside. Each is bounded in size, parsed strictly ({@link Xml}, {@link Json}), and only
 * versions, dates and digests that match their expected form reach the facts.
 */
final class Registries {

    static final int MAX_METADATA = 2 * 1024 * 1024;
    static final int MAX_PACKUMENT = 32 * 1024 * 1024;
    static final int MAX_MANIFEST = 4 * 1024 * 1024;
    static final int MAX_TOKEN = 64 * 1024;

    private static final Pattern COORDINATE_PART = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern NPM_NAME = Pattern.compile("(@[a-z0-9._~-]+/)?[a-z0-9._~-]+");
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z.+_-]{1,100}");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern IMAGE = Pattern.compile(
            // A first part is a registry host only with a dot or a port, or as localhost, as Docker reads it
            "(?:(?<host>[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+(?::\\d{1,5})?|[A-Za-z0-9-]+:\\d{1,5}|localhost)/)?(?<repo>[a-z0-9]+(?:[._/-][a-z0-9]+)*)"
                    + "(?::(?<tag>[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}))?@(?<digest>sha256:[0-9a-f]{64})");
    private static final Pattern CHALLENGE_PART = Pattern.compile("(\\w+)=\"([^\"]*)\"");
    private static final String MANIFEST_TYPES = String.join(", ",
            "application/vnd.oci.image.index.v1+json",
            "application/vnd.docker.distribution.manifest.list.v2+json",
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.docker.distribution.manifest.v2+json");

    private final RuleFile rules;
    private final Http.Client http;
    private final Facts out;
    private int requests;

    Registries(RuleFile rules, Http.Client http, Facts out) {
        this.rules = rules;
        this.http = http;
        this.out = out;
    }

    /** Looks up everything in the offline step's inventory. */
    void lookUp(Facts offline) {
        int failedBefore = out.gaps.size();
        maven(Facts.list(offline.data.get("maven")));
        npm(Facts.list(offline.data.get("npm")));
        images(Facts.list(offline.data.get("images")));
        out.fact("requests", requests);
        out.fact("lookups_failed", out.gaps.size() - failedBefore);
    }

    private void maven(List<?> inventory) {
        // One lookup per artifact; each place that names a version of it is compared on its own
        Map<String, List<Map<String, Object>>> byCoordinate = new LinkedHashMap<>();
        for (Object entry : inventory) {
            Map<String, Object> m = Facts.object(entry);
            byCoordinate.computeIfAbsent(Facts.text(m.get("coordinate")), k -> new ArrayList<>()).add(m);
        }
        int looked = 0;
        for (Map.Entry<String, List<Map<String, Object>>> e : byCoordinate.entrySet()) {
            String coordinate = e.getKey();
            String[] parts = coordinate.split(":");
            if (parts.length != 2 || !COORDINATE_PART.matcher(parts[0]).matches()
                    || !COORDINATE_PART.matcher(parts[1]).matches()) {
                out.gap("maven_update " + coordinate, "not a groupId:artifactId that can be looked up");
                continue;
            }
            URI uri = URI.create(rules.mavenRepository() + "/" + parts[0].replace('.', '/') + "/" + parts[1]
                    + "/maven-metadata.xml");
            List<String> versions;
            try {
                Http.Response r = send("GET", uri, Map.of(), MAX_METADATA);
                if (r.status() != 200) {
                    out.gap("maven_update " + coordinate, Http.status(r) + " from the repository");
                    continue;
                }
                versions = metadataVersions(new String(r.body(), StandardCharsets.UTF_8));
            } catch (IOException | IllegalArgumentException ex) {
                out.gap("maven_update " + coordinate, Http.reason(ex));
                continue;
            }
            looked++;
            Map<String, Boolean> seen = new LinkedHashMap<>();
            for (Map<String, Object> place : e.getValue()) {
                String current = Facts.text(place.get("version"));
                String kind = Facts.text(place.get("kind"));
                boolean plugin = kind.startsWith("plugin");
                if (seen.put((plugin ? "p" : "d") + current, true) != null) continue;
                List<String> newer = Versions.newer(current, versions, rules);
                if (newer.isEmpty()) continue;
                String latest = newer.getLast();
                out.item(plugin ? "plugin_update" : "dependency_update", Facts.text(place.get("file")),
                        (int) Facts.number(place.get("line")), coordinate, current + " -> " + latest + " ("
                                + Versions.jump(current, latest) + "; " + count(newer.size(), "newer release") + ")");
            }
        }
        out.fact("maven_artifacts_looked_up", looked);
    }

    /** The {@code <versions>} of a {@code maven-metadata.xml}, each checked for its form. */
    static List<String> metadataVersions(String xml) {
        Element root = Xml.parse(xml);
        if (!root.name().equals("metadata")) throw new IllegalArgumentException("no maven-metadata.xml");
        List<String> out = new ArrayList<>();
        for (Element v : root.path("versioning/versions/version")) {
            if (VERSION.matcher(v.text()).matches()) out.add(v.text());
        }
        if (out.isEmpty()) throw new IllegalArgumentException("the metadata lists no version");
        return out;
    }

    private void npm(List<?> inventory) {
        int looked = 0;
        for (Object entry : inventory) {
            Map<String, Object> place = Facts.object(entry);
            String name = Facts.text(place.get("name"));
            String pinned = Facts.text(place.get("version"));
            String what = "npm_update " + name;
            if (!NPM_NAME.matcher(name).matches()) {
                out.gap(what, "not an npm package name that can be looked up");
                continue;
            }
            URI uri = URI.create(rules.npmRegistry() + "/" + URLEncoder.encode(name, StandardCharsets.UTF_8)
                    .replace("%40", "@"));
            Map<String, Object> doc;
            try {
                Http.Response r = send("GET", uri, Map.of("Accept", "application/json"), MAX_PACKUMENT);
                if (r.status() != 200) {
                    out.gap(what, Http.status(r) + " from the registry");
                    continue;
                }
                doc = Facts.object(Json.parse(new String(r.body(), StandardCharsets.UTF_8)));
            } catch (IOException | IllegalArgumentException ex) {
                out.gap(what, Http.reason(ex));
                continue;
            }
            Object tags = doc.get("dist-tags");
            String latest = tags instanceof Map<?, ?> t ? Facts.text(t.get("latest")) : "";
            if (!VERSION.matcher(latest).matches()) {
                out.gap(what, "the registry names no latest version");
                continue;
            }
            looked++;
            List<String> versions = doc.get("versions") instanceof Map<?, ?> v
                    ? v.keySet().stream().map(String::valueOf).filter(s -> VERSION.matcher(s).matches()).toList()
                    : List.of();
            Map<?, ?> times = doc.get("time") instanceof Map<?, ?> t ? t : Map.of();
            String file = Facts.text(place.get("file"));
            int line = (int) Facts.number(place.get("line"));
            if (!versions.contains(pinned)) {
                out.item("npm_version_unknown", file, line, name + "@" + pinned,
                        "the registry does not list the pinned version");
                continue;
            }
            if (Versions.compare(latest, pinned) <= 0) continue;
            List<String> newer = Versions.newer(pinned, versions, rules).stream()
                    .filter(v -> Versions.compare(v, latest) <= 0).toList();
            out.item("npm_update", file, line, name, pinned + " -> " + latest + " (" + Versions.jump(pinned, latest)
                    + "; " + count(newer.size(), "newer release") + "; pinned " + date(times.get(pinned))
                    + ", latest " + date(times.get(latest)) + ")");
        }
        out.fact("npm_packages_looked_up", looked);
    }

    private static String date(Object value) {
        String text = Facts.text(value);
        return text.length() >= 10 && DATE.matcher(text.substring(0, 10)).matches() ? text.substring(0, 10)
                : "of unknown date";
    }

    private void images(List<?> inventory) {
        int looked = 0;
        // The current digest per reference, or why it is not known: a base that several Dockerfiles share is asked once
        Map<String, String> known = new LinkedHashMap<>();
        Map<String, String> failed = new LinkedHashMap<>();
        for (Object entry : inventory) {
            Map<String, Object> place = Facts.object(entry);
            String reference = Facts.text(place.get("reference"));
            String file = Facts.text(place.get("file"));
            int line = (int) Facts.number(place.get("line"));
            String what = "base_image " + reference;
            Matcher m = IMAGE.matcher(reference);
            if (!m.matches()) {
                out.gap(what, "not an image reference this tool can look up");
                continue;
            }
            String tag = m.group("tag");
            String pinned = m.group("digest");
            if (tag == null) {
                out.item("base_no_tag", file, line, reference, "pinned by digest only: there is no tag to compare");
                continue;
            }
            String name = RuleFile.registryName(m.group("host"));
            String repo = m.group("repo");
            String host = name;
            if (name.equals(RuleFile.DOCKER_HUB)) {
                host = "registry-1.docker.io";
                if (!repo.contains("/")) repo = "library/" + repo;
            }
            String scheme = host.startsWith("localhost") || host.startsWith("127.0.0.1") ? "http" : "https";
            URI uri = URI.create(scheme + "://" + host + "/v2/" + repo + "/manifests/" + tag);
            if (!known.containsKey(reference) && !failed.containsKey(reference)) {
                // The audited Dockerfile names the host; only the rule file decides whether it is asked
                RuleFile.ImageRegistry registry = rules.imageRegistry(name).orElse(null);
                if (registry == null) {
                    failed.put(reference, "the rule file allows no lookup on " + name + "; a line 'image-registry "
                            + name + "' would allow it");
                } else {
                    try {
                        String current = digest(uri, registry);
                        known.put(reference, current.equals(pinned) || listsPlatform(uri, pinned, registry) ? "" : current);
                        looked++;
                    } catch (IOException | IllegalArgumentException ex) {
                        failed.put(reference, Http.reason(ex));
                    }
                }
            }
            if (failed.containsKey(reference)) {
                out.gap(what + " (" + file + ")", failed.get(reference));
                continue;
            }
            String current = known.get(reference);
            if (current.isEmpty()) continue;
            out.item("base_image_moved", file, line, reference.substring(0, reference.indexOf('@')), "the tag now points to " + current.substring(0, 19)
                    + "..., the Dockerfile pins " + pinned.substring(0, 19) + "...: a newer build of the tag is out");
        }
        out.fact("base_images_looked_up", looked);
    }

    /** The digest the tag points to now: the registry's {@code Docker-Content-Digest}, else the body's SHA-256. */
    private String digest(URI uri, RuleFile.ImageRegistry registry) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>(Map.of("Accept", MANIFEST_TYPES));
        Http.Response r = authorized("HEAD", uri, headers, MAX_MANIFEST, registry);
        if (r.status() == 200) {
            String d = r.header("docker-content-digest").orElse("");
            if (d.matches("sha256:[0-9a-f]{64}")) return d;
        }
        r = authorized("GET", uri, headers, MAX_MANIFEST, registry);
        if (r.status() != 200) throw new IOException(Http.status(r) + " from the registry");
        String d = r.header("docker-content-digest").orElse("");
        return d.matches("sha256:[0-9a-f]{64}") ? d : "sha256:" + sha256(r.body());
    }

    /** Whether the tag's index lists {@code pinned} as one of its platform manifests. */
    private boolean listsPlatform(URI uri, String pinned, RuleFile.ImageRegistry registry) throws IOException {
        Http.Response r = authorized("GET", uri, new LinkedHashMap<>(Map.of("Accept", MANIFEST_TYPES)), MAX_MANIFEST,
                registry);
        if (r.status() != 200) throw new IOException(Http.status(r) + " from the registry");
        Object manifest = Json.parse(new String(r.body(), StandardCharsets.UTF_8));
        if (!(manifest instanceof Map<?, ?> index) || !(index.get("manifests") instanceof List<?> manifests)) return false;
        for (Object m : manifests) {
            if (m instanceof Map<?, ?> entry && pinned.equals(entry.get("digest"))) return true;
        }
        return false;
    }

    /**
     * A request that answers a registry's Bearer challenge with an anonymous token, once. The challenge names the token
     * address; it is asked only when the registry's rule line allows its host, so the answer cannot send the tool on.
     */
    private Http.Response authorized(String method, URI uri, Map<String, String> headers, int max,
                                     RuleFile.ImageRegistry registry) throws IOException {
        Http.Response r = send(method, uri, headers, max);
        if (r.status() != 401 || headers.containsKey("Authorization")) return r;
        String challenge = r.header("www-authenticate").orElse("");
        if (!challenge.regionMatches(true, 0, "Bearer ", 0, 7)) return r;
        Map<String, String> parts = new LinkedHashMap<>();
        Matcher m = CHALLENGE_PART.matcher(challenge);
        while (m.find()) parts.put(m.group(1).toLowerCase(java.util.Locale.ROOT), m.group(2));
        URI realm;
        try {
            realm = new URI(parts.getOrDefault("realm", ""));
        } catch (Exception e) {
            throw new IOException("the registry's token address is no URL");
        }
        if (!RuleFile.secure(realm)) throw new IOException("the registry's token address is not https");
        String tokenHost = Http.address(realm);
        if (!registry.allowsTokenFrom(tokenHost, Http.address(uri))) {
            throw new IOException("refused: the registry " + registry.host() + " sends for its token to " + tokenHost
                    + "; a line 'image-registry " + registry.host() + " " + tokenHost + "' would allow it");
        }
        StringBuilder query = new StringBuilder();
        for (String key : List.of("service", "scope")) {
            if (!parts.containsKey(key)) continue;
            query.append(query.isEmpty() ? "?" : "&").append(key).append('=')
                    .append(URLEncoder.encode(parts.get(key), StandardCharsets.UTF_8));
        }
        Http.Response t = send("GET", URI.create(realm + query.toString()), Map.of(), MAX_TOKEN);
        if (t.status() != 200) throw new IOException(Http.status(t) + " for an anonymous token");
        Map<String, Object> token = Facts.object(Json.parse(new String(t.body(), StandardCharsets.UTF_8)));
        String value = Facts.text(token.containsKey("token") ? token.get("token") : token.get("access_token"));
        if (!value.matches("[A-Za-z0-9._~+/=-]{1,8192}")) throw new IOException("the registry gave no usable token");
        headers.put("Authorization", "Bearer " + value);
        return send(method, uri, headers, max);
    }

    private Http.Response send(String method, URI uri, Map<String, String> headers, int max) throws IOException {
        requests++;
        return http.send(method, uri, headers, max);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
