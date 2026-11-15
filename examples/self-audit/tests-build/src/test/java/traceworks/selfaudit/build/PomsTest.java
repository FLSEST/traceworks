package traceworks.selfaudit.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PomsTest {

    private static final String RULES = """
            runtime-allow com.fasterxml.jackson.core:*
            require enforcer-guard
            require output-timestamp
            java-release 21
            plugin-pinned maven-compiler-plugin maven-resources-plugin
            """;

    private static final String GUARD = """
            <plugin>
              <artifactId>maven-enforcer-plugin</artifactId>
              <executions><execution><goals><goal>enforce</goal></goals><configuration><rules>
                <bannedDependencies>
                  <excludes>
                    <exclude>*:*:*:*:compile</exclude><exclude>*:*:*:*:runtime</exclude>
                    <exclude>*:*:*:*:provided</exclude><exclude>*:*:*:*:system</exclude>
                  </excludes>
                  <includes><include>com.fasterxml.jackson.core:*</include></includes>
                </bannedDependencies>
              </rules></configuration></execution></executions>
            </plugin>
            """;

    @TempDir
    Path root;

    @Test
    void aCleanReactor_hasNoItem_andItsVersionsAreInTheInventory() throws IOException {
        write("pom.xml", parent("""
                <properties>
                  <maven.compiler.release>21</maven.compiler.release>
                  <project.build.outputTimestamp>2026-11-07T00:00:00Z</project.build.outputTimestamp>
                  <jackson.version>2.22.4</jackson.version>
                </properties>
                <modules><module>core</module></modules>
                <dependencyManagement><dependencies>
                  <dependency><groupId>com.fasterxml.jackson</groupId><artifactId>jackson-bom</artifactId>
                    <version>${jackson.version}</version><type>pom</type><scope>import</scope></dependency>
                  <dependency><groupId>org.junit</groupId><artifactId>junit</artifactId><version>6.1.3</version></dependency>
                </dependencies></dependencyManagement>
                <build>
                  <pluginManagement><plugins>
                    <plugin><artifactId>maven-compiler-plugin</artifactId><version>3.15.0</version></plugin>
                    <plugin><artifactId>maven-resources-plugin</artifactId><version>3.3.1</version></plugin>
                    <plugin><artifactId>maven-enforcer-plugin</artifactId><version>3.6.3</version></plugin>
                  </plugins></pluginManagement>
                  <plugins>""" + GUARD + "</plugins></build>"));
        write("core/pom.xml", child("core", """
                <dependencies>
                  <dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId></dependency>
                  <dependency><groupId>org.junit</groupId><artifactId>junit</artifactId><scope>test</scope></dependency>
                </dependencies>
                <build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId></plugin></plugins></build>
                """));

        Facts facts = check(RULES);

        assertThat(facts.items).isEmpty();
        assertThat(facts.facts).containsEntry("poms", 2L).containsEntry("java_release", "21")
                .containsEntry("output_timestamp", "2026-11-07T00:00:00Z").containsEntry("scope_compile", 1L)
                .containsEntry("scope_test", 1L).containsEntry("enforcer_guard", "pom.xml:28");
        assertThat(Facts.list(facts.data.get("maven"))).extracting(e -> ((Map<?, ?>) e).get("coordinate") + " "
                        + ((Map<?, ?>) e).get("version") + " " + ((Map<?, ?>) e).get("kind"))
                .containsExactly("com.fasterxml.jackson:jackson-bom 2.22.4 import", "org.junit:junit 6.1.3 managed",
                        "org.apache.maven.plugins:maven-compiler-plugin 3.15.0 plugin-managed",
                        "org.apache.maven.plugins:maven-resources-plugin 3.3.1 plugin-managed",
                        "org.apache.maven.plugins:maven-enforcer-plugin 3.6.3 plugin-managed");
    }

    @Test
    void everyBrokenRule_isAnItem() throws IOException {
        write("pom.xml", parent("""
                <properties><maven.compiler.release>17</maven.compiler.release></properties>
                <modules><module>app</module></modules>
                <repositories><repository><id>old</id><url>http://repo.example.org/m2</url></repository></repositories>
                <build><extensions><extension><groupId>x</groupId><artifactId>ext</artifactId><version>1</version></extension></extensions></build>
                """).replace("<packaging>pom</packaging>", "<packaging>jar</packaging>"));
        write("app/pom.xml", child("app", """
                <dependencies>
                  <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>[2.0,3.0)</version></dependency>
                  <dependency><groupId>org.x</groupId><artifactId>latest</artifactId><version>LATEST</version><scope>test</scope></dependency>
                  <dependency><groupId>org.x</groupId><artifactId>snap</artifactId><version>1.0-SNAPSHOT</version><scope>test</scope></dependency>
                  <dependency><groupId>org.x</groupId><artifactId>nover</artifactId><scope>test</scope></dependency>
                  <dependency><groupId>org.x</groupId><artifactId>prop</artifactId><version>${missing.version}</version><scope>test</scope></dependency>
                  <dependency><groupId>org.x</groupId><artifactId>provided</artifactId><version>1</version><scope>provided</scope></dependency>
                </dependencies>
                <build><plugins><plugin><groupId>org.x</groupId><artifactId>loose-plugin</artifactId></plugin></plugins></build>
                <profiles><profile><id>extra</id><dependencies>
                  <dependency><groupId>org.y</groupId><artifactId>y</artifactId><version>1</version></dependency>
                </dependencies></profile></profiles>
                """));

        Facts facts = check(RULES);

        assertThat(facts.items).extracting(i -> i.check() + " " + i.where() + " " + i.subject())
                .containsExactlyInAnyOrder("runtime_declared app/pom.xml:6 org.slf4j:slf4j-api",
                        "runtime_declared app/pom.xml:11 org.x:provided",
                        "runtime_declared app/pom.xml:15 org.y:y",
                        "version_not_pinned app/pom.xml:6 org.slf4j:slf4j-api",
                        "version_not_pinned app/pom.xml:7 org.x:latest",
                        "version_not_pinned app/pom.xml:8 org.x:snap",
                        "version_not_pinned app/pom.xml:9 org.x:nover",
                        "version_unresolved app/pom.xml:10 org.x:prop",
                        "plugin_without_version app/pom.xml:13 org.x:loose-plugin",
                        "insecure_repository pom.xml:10 http://repo.example.org/m2",
                        "build_extension pom.xml:11 x:ext",
                        "plugin_not_pinned pom.xml maven-compiler-plugin",
                        "plugin_not_pinned pom.xml maven-resources-plugin",
                        "enforcer_guard_missing pom.xml example:parent",
                        "enforcer_guard_missing app/pom.xml example:app",
                        "java_release_differs pom.xml example:parent",
                        "java_release_differs app/pom.xml example:app",
                        "output_timestamp_missing pom.xml example:parent",
                        "output_timestamp_missing app/pom.xml example:app");
        assertThat(facts.items).filteredOn(i -> i.check().equals("runtime_declared") && i.subject().equals("org.y:y"))
                .extracting(Facts.Item::detail).containsExactly("declared in scope compile (profile extra)");
        assertThat(facts.facts).containsEntry("enforcer_guard", "none").containsEntry("build_extensions", 1L);
    }

    @Test
    void aGuardThatAllowsMore_differs() throws IOException {
        write("pom.xml", parent("<build><plugins>" + GUARD.replace(
                "<include>com.fasterxml.jackson.core:*</include>",
                "<include>com.fasterxml.jackson.core:*</include><include>org.slf4j:*</include>")
                .replace("<exclude>*:*:*:*:system</exclude>", "") .replace("<bannedDependencies>",
                        "<bannedDependencies><searchTransitive>false</searchTransitive>") + "</plugins></build>"));

        Facts facts = check(RULES);

        assertThat(facts.items).filteredOn(i -> i.check().equals("enforcer_guard_differs")).extracting(Facts.Item::detail)
                .containsExactly("it allows com.fasterxml.jackson.core:*, org.slf4j:*, the rule file allows"
                        + " com.fasterxml.jackson.core:*; it does not ban the scope system; searchTransitive is false:"
                        + " only direct dependencies are checked");
    }

    @Test
    void aModuleOutsideTheWorkspace_orMissing_orBroken_isNotRead() throws IOException {
        write("pom.xml", parent("<modules><module>../outside</module><module>missing</module><module>broken</module>"
                + "<module>link</module></modules>"));
        write("broken/pom.xml", "<project><unclosed></project>");
        Path elsewhere = Files.createDirectories(root.resolveSibling(root.getFileName() + "-elsewhere"));
        Files.writeString(elsewhere.resolve("pom.xml"), child("elsewhere", ""));
        Files.createSymbolicLink(root.resolve("link"), elsewhere);

        Poms.Reactor reactor = Poms.read(root);

        assertThat(reactor.poms()).extracting(Poms.Pom::file).containsExactly("pom.xml");
        assertThat(reactor.notRead()).extracting(g -> g.what() + ": " + g.reason()).hasSize(4).startsWith(
                "../outside/pom.xml: a module outside the workspace, not followed",
                "missing/pom.xml: the module's directory does not exist",
                "link/pom.xml: a symbolic link to a directory, not followed");
        assertThat(reactor.notRead().get(3).what()).isEqualTo("broken/pom.xml");
        assertThat(reactor.notRead().get(3).reason()).startsWith("not well-formed XML: ");
    }

    @Test
    void noPom_isNotRead() throws IOException {
        Facts facts = check(RULES);

        assertThat(facts.items).extracting(Facts.Item::check).contains("pom_not_read");
        assertThat(facts.facts).containsEntry("poms", 0L);
    }

    @Test
    void resolve_followsPropertiesAndKeepsWhatItDoesNotKnow() {
        assertThat(Poms.resolve("${a}-${b}-${c}", Map.of("a", "${b}", "b", "2"))).isEqualTo("2-2-${c}");
        assertThat(Poms.resolve("${loop}", Map.of("loop", "${loop}"))).isEqualTo("${loop}");
    }

    private Facts check(String rules) throws IOException {
        Facts facts = new Facts("offline");
        Poms.check(Poms.read(root), RuleFile.parse(rules), facts);
        return facts;
    }

    private void write(String file, String text) throws IOException {
        Path path = root.resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }

    private static String parent(String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0-SNAPSHOT</version>
                  <packaging>pom</packaging>
                """ + body + "\n</project>\n";
    }

    private static String child(String name, String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <parent><groupId>example</groupId><artifactId>parent</artifactId><version>1.0-SNAPSHOT</version></parent>
                  <artifactId>%s</artifactId>
                """.formatted(name) + body + "\n</project>\n";
    }
}
