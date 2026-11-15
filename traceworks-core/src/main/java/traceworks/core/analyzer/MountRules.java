package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.compat.EarlierNames;
import traceworks.core.container.HostSecrets;
import traceworks.core.engine.RunAdmission;
import traceworks.core.model.BlockDefinition;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code mount-mode-required} ({@code ERROR}): an entry of {@code mounts} is written {@code host:container}, without
 * {@code :ro} or {@code :rw}. Docker makes such a mount writable; the run admission refuses the workflow
 * ({@link RunAdmission#mountsWithoutMode}).
 *
 * <p>{@code secret-mount} ({@code ERROR}): an entry of {@code mounts} would hand a container what a step must never
 * see: the home as a whole, a login below it ({@code ~/.ssh}, the AI CLIs' and the registries' logins ...),
 * a workflow service's data directories ({@link EarlierNames#SERVICE_HOME_SECRETS}), the socket or the storage of a
 * container runtime (Docker, Podman, also a rootless
 * one's storage below the home), a user's runtime directory {@code /run/user/<uid>} with the sockets of the user's
 * session (a rootless Docker or Podman, the user's D-Bus and systemd, the agents), the X11 display sockets, or a
 * named volume instead of a host path. It reads the text only:
 * {@code ~} and {@code $HOME} stand for the home; placeholders are not judged, only the run knows their path. The
 * container adapter checks more when the step runs: real paths (and refuses what cannot be resolved), socket files
 * and folders holding one, and the operator's session paths.
 *
 * <p>{@code {{ home_dir }}} is not read as the home: Traceworks does not provide {@code home_dir}
 * ({@code unprovided-variable} reports it; a run input of that name is used as is), and the check of a mount when the
 * step runs belongs to the container adapter.
 */
final class MountRules {

    private MountRules() {
    }

    /** The home as a definition writes it, followed by the rest of the path or nothing. */
    private static final Pattern HOME = Pattern.compile("^(?:~|\\$HOME|\\$\\{HOME}|/home/[^/]+|/root)(?=/|$)");
    private static final List<String> DATA_DIRECTORIES = List.of(EarlierNames.SERVICE_DATA_DIRECTORY);
    /** What lies in a data directory, named as what it is; the rest of it is a home secret as a whole. */
    private static final List<String> DATA_SECRETS = List.of("api-key", "seal-signing.key",
            "integration-encryption.key", "backups");
    private static final String DOCKER = "the Docker socket (root on the host)";
    private static final String PODMAN = "the Podman socket (control over the host's containers)";
    private static final String X11 = "the X11 display sockets (control over the user's desktop)";
    private static final String STORAGE = "a container runtime's storage (the images and containers later runs use)";
    /** The sockets at fixed paths, in the order they are reported: {@code /run} reaches the Docker socket first. */
    private static final List<Map.Entry<String, String>> SOCKETS = List.of(
            Map.entry("/var/run/docker.sock", DOCKER),
            Map.entry("/run/docker.sock", DOCKER),
            Map.entry("/var/run/podman/podman.sock", PODMAN),
            Map.entry("/run/podman/podman.sock", PODMAN),
            Map.entry("/tmp/.X11-unix", X11),
            Map.entry("/var/lib/docker", STORAGE),
            Map.entry("/var/lib/containers", STORAGE));
    /** Where each user's runtime directory lies, under both names; it holds the sockets of the user's session. */
    private static final List<String> USER_RUNTIME = List.of("/run/user", "/var/run/user");
    private static final String USER_SESSION = "a user's runtime directory (the sockets of the user's session: a"
            + " rootless Docker or Podman, the user's D-Bus and systemd, the GPG and SSH agents; control over the"
            + " host)";

    static void apply(List<BlockDefinition> blocks, List<AnalyzerFinding> out) {
        BlockConfigs.walk(blocks, block -> check(block, out));
    }

    private static void check(BlockConfigs.Config block, List<AnalyzerFinding> out) {
        Map<String, Object> config = block.config();
        if (config == null || !(config.get("mounts") instanceof List<?> mounts)) {
            return;
        }
        for (Object mount : mounts) {
            if (!(mount instanceof String entry) || entry.isBlank()) {
                continue;
            }
            String what = exposes(entry.strip().split(":", 2)[0].strip());
            if (what != null) {
                out.add(AnalyzerFinding.of("secret-mount", Severity.ERROR, block.index(), block.name(),
                        block.where() + "mounts: \"" + entry + "\" binds " + what + " into the container — a step"
                                + " must never see it; remove the mount"));
            }
        }
        for (String entry : RunAdmission.mountsWithoutMode(config)) {
            out.add(AnalyzerFinding.of("mount-mode-required", Severity.ERROR, block.index(), block.name(),
                    block.where() + "mounts: " + RunAdmission.mountWithoutModeProblem(entry) + "; Docker would make"
                    + " it writable, Traceworks refuses the workflow"));
        }
    }

    /** What the host side hands over; null when nothing the rule knows of. */
    static String exposes(String host) {
        Matcher home = HOME.matcher(host);
        if (home.find()) {
            String rest = normalized("/" + host.substring(home.end()));
            if (rest.equals("/")) {
                return "the whole home directory (keys, logins, data directories)";
            }
            for (String dir : DATA_DIRECTORIES) {
                if (rest.equals("/" + dir)) {
                    return "a workflow service's data directory (API key, seal key, backups)";
                }
                for (String secret : DATA_SECRETS) {
                    if (within(rest, "/" + dir + "/" + secret)) {
                        return keys(dir + "/" + secret);
                    }
                }
            }
            for (String secret : HostSecrets.HOME_SECRETS) {
                if (within(rest, "/" + secret)) {
                    return keys(secret);
                }
            }
            for (String storage : HostSecrets.HOME_STORAGE) {
                if (within(rest, "/" + storage)) {
                    return STORAGE;
                }
            }
            return null;
        }
        if (host.startsWith("{{") || host.startsWith("$")) {
            return null; // a placeholder or variable: only the run knows the path
        }
        if (!host.startsWith("/")) {
            return "a named volume instead of a host path (a volume can hold keys the definition does not show)";
        }
        String path = normalized(host);
        if (path.equals("/") || path.equals("/home")) {
            return "every home directory";
        }
        return socket(path);
    }

    /**
     * The sockets or the runtime storage {@code path} reaches: one at a fixed path, itself or a directory above it, or
     * a user's runtime directory and anything in it; null for none.
     */
    private static String socket(String path) {
        for (Map.Entry<String, String> socket : SOCKETS) {
            if (reaches(path, socket.getKey())) {
                return socket.getValue();
            }
        }
        for (String runtime : USER_RUNTIME) {
            if (within(path, runtime)) {
                return USER_SESSION;
            }
        }
        return null;
    }

    private static boolean reaches(String path, String socket) {
        return within(path, socket) || socket.startsWith(path + "/");
    }

    private static String keys(String what) {
        return "credentials (~/" + what + ")";
    }

    private static boolean within(String path, String dir) {
        return path.equals(dir) || path.startsWith(dir + "/");
    }

    private static String normalized(String path) {
        try {
            return Path.of(path).normalize().toString();
        } catch (InvalidPathException e) {
            return path;
        }
    }
}
