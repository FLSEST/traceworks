package traceworks.core.analyzer;

import traceworks.core.compat.EarlierNames;
import traceworks.core.container.HostSecrets;
import traceworks.core.model.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MountRulesTest {

    /** The directories of a workflow service below the home, and the one whose key files the rule names. */
    private static final List<String> SERVICE = EarlierNames.SERVICE_HOME_SECRETS;
    private static final String DATA = EarlierNames.SERVICE_DATA_DIRECTORY;

    private static List<AnalyzerFinding> findings(List<String> mounts) {
        BlockDefinition block = new BlockDefinition();
        block.setType("shell");
        block.setName("box");
        block.setConfig(new HashMap<>(Map.of("command", "ls", "container_image", "alpine:3.20", "mounts", mounts)));
        List<AnalyzerFinding> out = new ArrayList<>();
        MountRules.apply(List.of(block), out);
        return out;
    }

    /** The container adapter's {@code MountGuard} reads the same {@link HostSecrets}; a test there pins its side. */
    @Test
    void reportsEveryEntryOfTheSharedHostSecrets() {
        List<String> names = new ArrayList<>(HostSecrets.HOME_SECRETS);
        names.addAll(HostSecrets.HOME_STORAGE);
        assertThat(names).allSatisfy(name -> assertThat(findings(List.of("~/" + name + ":/x:ro")))
                .as(name).singleElement().satisfies(f -> assertThat(f.rule()).isEqualTo("secret-mount")));
    }

    @Test
    void reportsWhatAStepMustNotSee() {
        List<String> refused = List.of(
                "~:/h:ro", "$HOME/:/h:ro", "${HOME}:/h:ro", "/home/alice:/h:ro", "/root:/r:ro", // the whole home
                "~/" + DATA + ":/d:ro", "~/" + DATA + "/:/d:ro",                     // a service's data directory
                "~/" + DATA + "/api-key:/k:ro",                                      // a key file
                "~/" + DATA + "/backups/dev:/b:ro",                                  // the backups
                "~/work/../.ssh:/s:ro", "~/.aws:/a:ro",                              // logins
                "/var/run/docker.sock:/s:ro", "/run:/r:ro",                          // the Docker socket
                "/run/podman/podman.sock:/s:ro", "/var/run/podman:/p:ro",            // the Podman socket
                "/run/user/1000/podman/podman.sock:/s:ro", "/run/user/1000:/u:ro",   // a rootless Podman socket
                "/run/user:/u:ro", "/run/user/1000/docker.sock:/s:ro",              // ... or Docker socket
                "/run/user/1000/bus:/b:ro", "/run/user/1000/systemd/private:/p:ro",  // the user's D-Bus, systemd
                "/run/user/1000/gnupg/S.gpg-agent:/g:ro", "/run/user/1000/gcr/ssh:/s:ro", // the user's agents
                "/run/user/1000/app:/app:ro", "/var/run/user/1000/bus:/b:ro",        // a user's runtime directory
                "/tmp/.X11-unix/X0:/x:ro", "/tmp:/t:ro",                             // the X11 display sockets
                "~/.claude:/c:ro", "~/.claude.json:/c:ro", "~/.codex:/c:ro", "~/.pi:/p:ro", // the AI CLIs' logins
                "~/.config/gh:/g:ro", "~/.git-credentials:/g:ro",                    // git logins
                "~/.npmrc:/n:ro", "~/.pypirc:/p:ro",                                 // package registry logins
                "~/" + SERVICE.get(1) + ":/a:ro", "~/" + SERVICE.get(2) + "/ai.env:/a:ro", // a service's data
                "~/" + DATA + "/workspaces/p/r/repo:/w:ro",
                "/var/lib/docker:/d:ro", "/var/lib/docker/volumes/v1/_data:/d:rw",   // a runtime's storage
                "/var/lib/docker/containers:/c:ro", "/var/lib/containers/storage:/s:rw", "/var/lib:/l:ro",
                "~/.local/share/containers:/c:rw", "~/.local/share/docker/volumes:/v:ro", // ... a rootless one's
                "/:/all:ro", "/home:/homes:ro",
                "stable-secrets:/s:ro");                                             // a named volume

        assertThat(refused).allSatisfy(mount -> assertThat(findings(List.of(mount)))
                .as(mount).singleElement().satisfies(f -> {
                    assertThat(f.rule()).isEqualTo("secret-mount");
                    assertThat(f.severity()).isEqualTo(AnalyzerFinding.Severity.ERROR);
                    assertThat(f.detail()).doesNotContain("refuses the mount");
                }));
    }

    /**
     * {@code {{ home_dir }}} is a variable that Traceworks does not provide: like any other placeholder,
     * only the run knows the path ({@code unprovided-variable} reports the variable itself).
     */
    @Test
    void leavesOrdinaryMountsAndPlaceholdersAlone() {
        assertThat(findings(List.of(
                "~/documents:/documents:rw",
                "~/.m2/repository:/home/agent/.m2/repository:ro",
                "{{ output_dir }}/tools:/results:rw",
                "{{ home_dir }}:/h:ro",
                "{{ home_dir }}/.ssh:/s:ro",
                "~/.config/other/settings.txt:/h:ro",
                "/tmp/cache:/cache:rw",
                "/srv/data:/data:ro"))).isEmpty();
    }

    @Test
    void namesTheSocketItFinds() {
        assertThat(findings(List.of("/run/podman/podman.sock:/s:ro")))
                .singleElement().extracting(AnalyzerFinding::detail).asString()
                .contains("binds the Podman socket (control over the host's containers)");
        for (String mount : List.of("/run/user/1000/podman/podman.sock:/s:ro", "/run/user/1000/docker.sock:/s:ro",
                "/run/user/1000/bus:/b:ro")) {
            assertThat(findings(List.of(mount))).as(mount)
                    .singleElement().extracting(AnalyzerFinding::detail).asString()
                    .contains("binds a user's runtime directory");
        }
        assertThat(findings(List.of("/var/run/docker.sock:/s:rw")))
                .singleElement().extracting(AnalyzerFinding::detail).asString()
                .contains("binds the Docker socket (root on the host)");
    }

    /**
     * Docker makes a mount without a mode writable; Traceworks refuses it (run admission), the analyzer says so. A
     * malformed entry and one that is a single placeholder are the shell step's to refuse when it runs.
     */
    @Test
    void mountWithoutMode_isAnError() {
        List<AnalyzerFinding> found = findings(List.of("/srv/data:/data", "{{ out }}:/out", "/srv/cache:/cache:rw",
                "/data", "{{ mount }}"));

        assertThat(found).extracting(AnalyzerFinding::rule).containsOnly("mount-mode-required");
        assertThat(found).extracting(AnalyzerFinding::severity).containsOnly(AnalyzerFinding.Severity.ERROR);
        assertThat(found).extracting(AnalyzerFinding::detail).containsExactly(
                "mounts: \"/srv/data:/data\" has no mode — write \"/srv/data:/data:ro\" (read-only) or"
                        + " \"/srv/data:/data:rw\" (writable); Docker would make it writable, Traceworks refuses the"
                        + " workflow",
                "mounts: \"{{ out }}:/out\" has no mode — write \"{{ out }}:/out:ro\" (read-only) or"
                        + " \"{{ out }}:/out:rw\" (writable); Docker would make it writable, Traceworks refuses the"
                        + " workflow");
    }

    @Test
    void mountWithoutMode_isFound_alsoInANestedBlock_andNextToASecretMount() {
        Map<String, Object> inner = new HashMap<>();
        inner.put("type", "shell");
        inner.put("name", "inner");
        inner.put("config", Map.of("command", "ls", "container_image", "alpine:3.20",
                "mounts", List.of("/srv:/srv")));
        BlockDefinition pick = new BlockDefinition();
        pick.setType("switch");
        pick.setName("pick");
        pick.setConfig(new HashMap<>(Map.of("expression", "x", "default", List.of(inner))));
        List<AnalyzerFinding> out = new ArrayList<>();

        MountRules.apply(List.of(pick), out);

        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("mount-mode-required");
            assertThat(f.detail()).startsWith("Block 1 > ");
        });
        assertThat(findings(List.of("~/.ssh:/s"))).extracting(AnalyzerFinding::rule)
                .containsExactlyInAnyOrder("secret-mount", "mount-mode-required");
    }

    @Test
    void reportsNestedBlocksWithTheirPath() {
        Map<String, Object> inner = new HashMap<>();
        inner.put("type", "shell");
        inner.put("name", "inner");
        inner.put("config", Map.of("command", "ls", "container_image", "alpine:3.20", "mounts", List.of("~:/h:ro")));
        BlockDefinition pick = new BlockDefinition();
        pick.setType("switch");
        pick.setName("pick");
        pick.setConfig(new HashMap<>(Map.of("expression", "x", "default", List.of(inner))));
        List<AnalyzerFinding> out = new ArrayList<>();

        MountRules.apply(List.of(pick), out);

        assertThat(out).singleElement().satisfies(f -> assertThat(f.detail()).startsWith("Block 1 > "));
    }
}
