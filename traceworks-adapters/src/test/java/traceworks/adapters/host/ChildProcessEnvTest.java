package traceworks.adapters.host;

import org.junit.jupiter.api.Test;
import traceworks.core.compat.EarlierNames;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scrub of a child process's environment. Traceworks sets no run token and no run label of its own; what is
 * tested here is that the ones of a workflow service it was started from ({@link EarlierNames#SERVICE_ENVIRONMENT})
 * are removed from an inherited environment.
 */
class ChildProcessEnvTest {

    @Test
    void scrub_removesTheBackendSecrets_evenWhenTheParentWasStartedWithThem() {
        // A ProcessBuilder copies the parent's environment; a service started in a container gets its instance key
        // there
        ProcessBuilder pb = new ProcessBuilder("true");
        pb.environment().put("AUTOMATION_API_KEY", "instance-key");
        pb.environment().put("AUTOMATION_SSL_PASSWORD", "keystore-password");

        ChildProcessEnv.scrub(pb);

        assertThat(pb.environment())
                .doesNotContainKey("AUTOMATION_API_KEY")
                .doesNotContainKey("AUTOMATION_SSL_PASSWORD");
    }

    @Test
    void scrub_keepsTheProviderCredentialsAndOrdinarySettings() {
        Map<String, String> env = new HashMap<>(Map.of(
                "CLAUDE_CODE_OAUTH_TOKEN", "claude-token",
                "ANTHROPIC_API_KEY", "anthropic-key",
                "OPENAI_API_KEY", "openai-key",
                "AUTOMATION_PORT", "8080",
                "PATH", "/usr/bin",
                "AUTOMATION_API_KEY", "instance-key"));

        ChildProcessEnv.scrub(env);

        assertThat(env).containsOnlyKeys(
                "CLAUDE_CODE_OAUTH_TOKEN", "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "AUTOMATION_PORT", "PATH");
    }

    @Test
    void builder_mergesStderrSetsTheWorkingDirectoryAndScrubs() {
        ProcessBuilder pb = ChildProcessEnv.builder(List.of("claude", "-p"), "/tmp");

        assertThat(pb.command()).containsExactly("claude", "-p");
        assertThat(pb.redirectErrorStream()).isTrue();
        assertThat(pb.directory()).isEqualTo(new File("/tmp"));
        assertThat(pb.environment()).doesNotContainKeys("AUTOMATION_API_KEY", "AUTOMATION_SSL_PASSWORD");
    }

    @Test
    void builder_leavesTheWorkingDirectoryUnsetWhenBlank() {
        assertThat(ChildProcessEnv.builder(List.of("pi"), " ").directory()).isNull();
        assertThat(ChildProcessEnv.builder(List.of("pi"), null).directory()).isNull();
    }

    /** The environment a test or an embedding application hands in replaces the JVM's own, and is scrubbed too. */
    @Test
    void builder_withAnInheritedEnvironment_startsFromItAndScrubsIt() {
        ProcessBuilder pb = ChildProcessEnv.builder(List.of("true"), null,
                Map.of("AUTOMATION_API_KEY", "instance-key", "PATH", "/usr/bin"));

        assertThat(pb.environment()).containsExactlyEntriesOf(Map.of("PATH", "/usr/bin"));
    }

    @Test
    void scrub_doesNothingWhenNoSecretIsSet() {
        Map<String, String> env = new HashMap<>(Map.of("PATH", "/usr/bin"));

        ChildProcessEnv.scrub(env);

        assertThat(env).containsExactlyEntriesOf(Map.of("PATH", "/usr/bin"));
    }

    @Test
    void scrub_dropsAnInheritedRunToken() {
        // A Traceworks run started from inside a run of the service must not hand that run's token to its own children
        Map<String, String> env = new HashMap<>(Map.of(
                service("_RUN_TOKEN"), "parent-run-token", service("_API"), "http://localhost:8080/api/v1",
                "PATH", "/usr/bin"));

        ChildProcessEnv.scrub(env);

        assertThat(env).containsOnlyKeys("PATH");
    }

    @Test
    void scrub_dropsAnInheritedRunLabel() {
        // The run label names the service's run of the parent, not this one
        Map<String, String> inherited = new HashMap<>(Map.of(service("_RUN_ID"), "run-of-the-parent-backend",
                "PATH", "/usr/bin"));

        ChildProcessEnv.scrub(inherited);

        assertThat(inherited).containsOnlyKeys("PATH");
    }

    /** The one variable of the service's environment whose name ends with {@code suffix}. */
    private static String service(String suffix) {
        return EarlierNames.SERVICE_ENVIRONMENT.stream().filter(name -> name.endsWith(suffix)).findFirst()
                .orElseThrow();
    }
}
