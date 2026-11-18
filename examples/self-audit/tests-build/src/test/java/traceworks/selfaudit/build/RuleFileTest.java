package traceworks.selfaudit.build;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleFileTest {

    @Test
    void parse_readsEveryKeyword() {
        RuleFile rules = RuleFile.parse("""
                # comment

                runtime-allow    com.fasterxml.jackson.core:*
                runtime-allow    io.github.flsest:*
                require          enforcer-guard
                require          output-timestamp
                java-release     21
                plugin-pinned    maven-compiler-plugin maven-jar-plugin
                plugin-pinned    maven-jar-plugin maven-resources-plugin
                stable-version   ^\\d+\\.\\d+$
                maven-repository https://repo.example.org/maven2/
                npm-registry     http://127.0.0.1:4873
                maven-extensions allow
                dockerfile       images/*/Dockerfile
                accept           package_unpinned images/shell/Dockerfile Debian's packages, on purpose.
                """);

        assertThat(rules.allowsRuntime("com.fasterxml.jackson.core:jackson-databind")).isTrue();
        assertThat(rules.allowsRuntime("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")).isFalse();
        assertThat(rules.allowsRuntime("io.github.flsest:traceworks-core")).isTrue();
        assertThat(rules.enforcerGuard()).isTrue();
        assertThat(rules.outputTimestamp()).isTrue();
        assertThat(rules.javaRelease()).isEqualTo("21");
        assertThat(rules.pinnedPlugins()).containsExactly("maven-compiler-plugin", "maven-jar-plugin",
                "maven-resources-plugin");
        assertThat(rules.stable("2.1")).isTrue();
        assertThat(rules.stable("2.1.3")).isFalse();
        assertThat(rules.mavenRepository()).isEqualTo("https://repo.example.org/maven2");
        assertThat(rules.npmRegistry()).isEqualTo("http://127.0.0.1:4873");
        assertThat(rules.mavenExtensions()).isTrue();
        assertThat(rules.isDockerfile(Path.of("images/shell/Dockerfile"))).isTrue();
        assertThat(rules.isDockerfile(Path.of("integration-tests/fake/Dockerfile"))).isFalse();
        assertThat(rules.accepts()).containsExactly(new RuleFile.Accept("package_unpinned", "images/shell/Dockerfile",
                "Debian's packages, on purpose.", 15));
    }

    @Test
    void theDefaults_areCentralNpmAndEveryDockerfile() {
        RuleFile rules = RuleFile.parse("");

        assertThat(rules.mavenRepository()).isEqualTo("https://repo.maven.apache.org/maven2");
        assertThat(rules.npmRegistry()).isEqualTo("https://registry.npmjs.org");
        assertThat(rules.stable("1.18.17")).isTrue();
        assertThat(rules.stable("1.18.17-jdk5")).isFalse();
        assertThat(rules.stable("4.0.0-M1")).isFalse();
        assertThat(rules.isDockerfile(Path.of("a/b/Dockerfile"))).isTrue();
        assertThat(rules.allowsRuntime("any:thing")).isFalse();
        assertThat(rules.enforcerGuard()).isFalse();
        assertThat(rules.javaRelease()).isNull();
    }

    @Test
    void anError_namesItsLine() {
        assertRefused("frobnicate x", "rule file line 1: unknown keyword 'frobnicate'");
        assertRefused("\nruntime-allow", "rule file line 2: 'runtime-allow' needs a value");
        assertRefused("runtime-allow jackson", "line 1: runtime-allow takes <groupId>:<artifactId>");
        assertRefused("require everything", "line 1: require takes enforcer-guard or output-timestamp");
        assertRefused("java-release 21\njava-release 17", "line 2: java-release is given twice");
        assertRefused("java-release twenty-one", "line 1: java-release takes a number");
        assertRefused("stable-version ([", "line 1: stable-version is no regular expression");
        assertRefused("maven-repository http://repo.example.org", "line 1: 'http://repo.example.org' is neither https");
        assertRefused("npm-registry file:///etc/passwd", "is neither https nor http to a loopback address");
        assertRefused("dockerfile /etc/Dockerfile", "line 1: a dockerfile glob is relative to the workspace");
        assertRefused("dockerfile ../x/Dockerfile", "stays inside it");
        assertRefused("accept base_unpinned images/a/Dockerfile because", "accept takes one of download_unverified,"
                + " package_unpinned, not 'base_unpinned'");
        assertRefused("accept package_unpinned images/a/Dockerfile", "line 1: accept takes <check> <file> <reason>");
        assertRefused("maven-extensions yes", "maven-extensions takes allow");
        assertRefused("plugin-pinned maven-jar-plugin a/b", "'a/b' is no artifactId");
        assertRefused("image-registry ghcr.io\nimage-registry GHCR.io", "line 2: image-registry ghcr.io is given twice");
        assertRefused("image-registry docker.io\nimage-registry index.docker.io", "line 2: image-registry docker.io is given twice");
        for (String broken : List.of("https://ghcr.io", "ghcr.io/owner", "user@ghcr.io", "ghcr.io:", "ghcr.io:99999",
                "-ghcr.io", "ghcr..io", "ghcr.io:443:1", "*.ghcr.io")) {
            assertRefused("\nimage-registry " + broken, "rule file line 2: image-registry takes a host name with an"
                    + " optional port and an optional token host");
        }
        assertRefused("image-registry ghcr.io auth.ghcr.io extra", "image-registry takes a host name");
        assertRefused("image-registry ghcr.io https://auth.ghcr.io", "image-registry takes a host name");
    }

    @Test
    void imageRegistry_namesTheHostsTheLookupsMayAsk() {
        RuleFile rules = RuleFile.parse("""
                image-registry   docker.io
                image-registry   GHCR.io
                image-registry   registry.gitlab.com gitlab.com
                image-registry   127.0.0.1:5000
                """);

        assertThat(rules.imageRegistry("docker.io")).contains(new RuleFile.ImageRegistry("docker.io", null, 1));
        assertThat(rules.imageRegistry("ghcr.io")).contains(new RuleFile.ImageRegistry("ghcr.io", null, 2));
        assertThat(rules.imageRegistry("registry.gitlab.com"))
                .contains(new RuleFile.ImageRegistry("registry.gitlab.com", "gitlab.com", 3));
        assertThat(rules.imageRegistry("127.0.0.1:5000")).isPresent();
        assertThat(rules.imageRegistry("127.0.0.1")).isEmpty();
        assertThat(rules.imageRegistry("quay.io")).isEmpty();
    }

    @Test
    void imageRegistry_defaultsToDockerHub_underEachOfItsNames() {
        for (String text : List.of("", "image-registry docker.io", "image-registry index.docker.io",
                "image-registry registry-1.docker.io")) {
            assertThat(RuleFile.parse(text).imageRegistries()).as(text).extracting(RuleFile.ImageRegistry::host)
                    .containsExactly("docker.io");
        }
        assertThat(RuleFile.registryName(null)).isEqualTo("docker.io");
        assertThat(RuleFile.registryName("index.docker.io")).isEqualTo("docker.io");
        assertThat(RuleFile.registryName("registry-1.docker.io")).isEqualTo("docker.io");
        assertThat(RuleFile.registryName("Docker.IO")).isEqualTo("docker.io");
        assertThat(RuleFile.registryName("ghcr.io")).isEqualTo("ghcr.io");
        // A line for another registry replaces the default: Docker Hub then needs its own line
        assertThat(RuleFile.parse("image-registry ghcr.io").imageRegistry("docker.io")).isEmpty();
    }

    @Test
    void secure_takesHttpsAndLoopbackHttpOnly() {
        assertThat(RuleFile.secure(URI.create("https://registry.npmjs.org/x"))).isTrue();
        assertThat(RuleFile.secure(URI.create("http://localhost:8080/x"))).isTrue();
        assertThat(RuleFile.secure(URI.create("http://127.0.0.1:8080/x"))).isTrue();
        assertThat(RuleFile.secure(URI.create("http://[::1]:8080/x"))).isTrue();
        assertThat(RuleFile.secure(URI.create("http://registry.npmjs.org/x"))).isFalse();
        assertThat(RuleFile.secure(URI.create("ftp://example.org/x"))).isFalse();
        assertThat(RuleFile.secure(URI.create("https:///nohost"))).isFalse();
    }

    @Test
    void thePackagesRuleFile_parses() throws Exception {
        RuleFile rules = RuleFile.parse(Files.readString(Path.of("../build/rules/build.rules")));

        assertThat(rules.runtimeAllow()).containsExactly("com.fasterxml.jackson.core:*", "io.github.flsest:*");
        assertThat(rules.enforcerGuard()).isTrue();
        assertThat(rules.isDockerfile(Path.of("examples/self-audit/build/images/facts/Dockerfile"))).isTrue();
        assertThat(rules.isDockerfile(Path.of("integration-tests/fake-ai/claude/Dockerfile"))).isFalse();
        assertThat(rules.imageRegistries()).extracting(RuleFile.ImageRegistry::host).containsExactly("docker.io");
        for (String maven : List.of("examples/mutation-testing/images/maven/Dockerfile",
                "examples/mutation-testing-grounded/images/maven/Dockerfile")) {
            assertThat(rules.isDockerfile(Path.of(maven))).as(maven).isTrue();
            assertThat(rules.accepts()).as(maven).filteredOn(a -> a.file().equals(maven)).singleElement()
                    .satisfies(a -> assertThat(a.check()).isEqualTo("package_unpinned"));
        }
    }

    private static void assertRefused(String text, String message) {
        assertThatThrownBy(() -> RuleFile.parse(text)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }
}
