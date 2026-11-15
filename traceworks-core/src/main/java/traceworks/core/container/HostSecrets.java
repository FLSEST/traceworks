package traceworks.core.container;

import traceworks.core.compat.EarlierNames;

import java.util.List;
import java.util.stream.Stream;

/**
 * What lies below a user's home that no step may see. One list for both sides that judge a mount: the analyzer's
 * {@code secret-mount} rule reads the text of a definition, the container adapter's {@code MountGuard} the real paths
 * when the step runs. A login added here is refused by both.
 */
public final class HostSecrets {

    private HostSecrets() {
    }

    /**
     * Logins below the home that must not reach a container: SSH, GPG, cloud, container registry, git and package
     * registry logins, passwords, the AI CLIs' logins and the directories of
     * {@link EarlierNames#SERVICE_HOME_SECRETS}. Paths relative to the home.
     */
    public static final List<String> HOME_SECRETS = Stream.concat(Stream.of(".ssh", ".gnupg", ".aws", ".azure",
            ".kube", ".docker", ".config/gcloud", ".netrc", ".password-store", ".claude", ".claude.json", ".codex",
            ".pi", ".config/gh", ".git-credentials", ".npmrc", ".pypirc"),
            EarlierNames.SERVICE_HOME_SECRETS.stream()).toList();

    /**
     * The storage of a rootless runtime below the home: the images and containers later runs use, which a writable
     * mount could change. Paths relative to the home.
     */
    public static final List<String> HOME_STORAGE = List.of(".local/share/containers", ".local/share/docker");
}
