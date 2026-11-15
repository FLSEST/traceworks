package traceworks.selfaudit.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MavenTreeTest {

    /** The shape of {@code dependency:tree -Dverbose} of the dependency plugin 3.11.0, as Maven 3.9.16 writes it. */
    static final String TREE = """
            example:parent:pom:1.0-SNAPSHOT
            \\- org.junit.jupiter:junit-jupiter:jar:6.1.3:test
               \\- (org.apiguardian:apiguardian-api:jar:1.1.2:test - omitted for duplicate)
            example:app:jar:1.0-SNAPSHOT
            +- example:core:jar:1.0-SNAPSHOT:compile
            |  +- com.fasterxml.jackson.core:jackson-databind:jar:2.22.4:compile
            |  |  +- com.fasterxml.jackson.core:jackson-annotations:jar:2.22:compile (version managed from 2.22)
            |  |  \\- com.fasterxml.jackson.core:jackson-core:jar:2.22.4:compile
            |  \\- org.slf4j:slf4j-api:jar:1.7.36:runtime (version managed from 2.0.9)
            +- example:core:jar:tests:1.0-SNAPSHOT:test
            +- org.mockito:mockito-core:jar:5.24.0:test
            |  +- net.bytebuddy:byte-buddy:jar:1.18.17:test (version managed from 1.17.7)
            |  \\- (com.fasterxml.jackson.core:jackson-core:jar:2.21.0:test - omitted for conflict with 2.22.4)
            \\- org.x:tool:jar:linux:1.0:provided
            example:core:jar:1.0-SNAPSHOT
            \\- com.fasterxml.jackson.core:jackson-databind:jar:2.22.4:compile
            """;

    @TempDir
    Path temp;

    @Test
    void parse_readsModulesNodesAndNotes() {
        List<MavenTree.Module> modules = MavenTree.parse(TREE);

        assertThat(modules).extracting(MavenTree.Module::coordinate).containsExactly("example:parent", "example:app",
                "example:core");
        List<MavenTree.Node> app = modules.get(1).nodes();
        assertThat(app).extracting(n -> n.depth() + " " + n.coordinate() + " " + n.version() + " " + n.scope())
                .startsWith("1 example:core 1.0-SNAPSHOT compile", "2 com.fasterxml.jackson.core:jackson-databind 2.22.4 compile");
        MavenTree.Node slf4j = app.get(4);
        assertThat(slf4j.note()).isEqualTo("version managed from 2.0.9");
        assertThat(slf4j.via()).isEqualTo("app > core");
        MavenTree.Node conflict = app.get(8);
        assertThat(conflict.omitted()).isTrue();
        assertThat(conflict.note()).isEqualTo("omitted for conflict with 2.22.4");
        assertThat(conflict.via()).isEqualTo("app > mockito-core");
        assertThat(app.get(5).classifier()).isEqualTo("tests");
        assertThat(app.get(9).classifier()).isEqualTo("linux");
    }

    @Test
    void check_findsThePolicyConflictsAndDowngrades() throws IOException {
        Facts facts = new Facts("maven");
        MavenTree.check(MavenTree.parse(TREE), reactor(), RuleFile.parse("runtime-allow com.fasterxml.jackson.core:*"),
                facts);

        assertThat(facts.items).extracting(i -> i.check() + " " + i.subject() + ": " + i.detail()).containsExactly(
                "runtime_resolved org.slf4j:slf4j-api: 1.7.36 in scope runtime of example:app, via app > core",
                "managed_downgrade org.slf4j:slf4j-api: 2.0.9 wanted via app > core, the dependency management gives"
                        + " 1.7.36 in example:app (scope runtime)",
                "conflict com.fasterxml.jackson.core:jackson-core: 2.21.0 wanted via app > mockito-core, Maven takes"
                        + " 2.22.4 in example:app (scope test)",
                "runtime_resolved org.x:tool: 1.0 in scope provided of example:app, via app");
        assertThat(facts.facts).containsEntry("modules_resolved", 3L).containsEntry("conflicts", 1L);
        assertThat(facts.gaps).isEmpty();
    }

    @Test
    void parse_refusesWhatIsNoTree() {
        assertThatThrownBy(() -> MavenTree.parse("+- a:b:jar:1:compile\n")).hasMessageContaining("before the first module");
        assertThatThrownBy(() -> MavenTree.parse("a:b:jar:1\n+- a:b\n")).hasMessageContaining("not a dependency");
        assertThatThrownBy(() -> MavenTree.parse("a:b:jar:1\n+- a:b:jar:1:compile`x\n"))
                .hasMessageContaining("not a dependency");
    }

    @Test
    void sbom_isCycloneDx() {
        Map<String, Object> bom = MavenTree.sbom("example:app", new java.util.TreeSet<>(Set.of("g:a:1.0",
                "g:b:2.0:linux")));

        assertThat(Json.write(bom)).isEqualTo("{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.5\",\"version\":1,"
                + "\"metadata\":{\"component\":" + Json.write(bom.get("metadata")).substring(13)
                .replaceAll("}$", "") + "},\"components\":[{\"type\":\"library\",\"bom-ref\":\"pkg:maven/g/a@1.0\","
                + "\"group\":\"g\",\"name\":\"a\",\"version\":\"1.0\",\"purl\":\"pkg:maven/g/a@1.0\"},"
                + "{\"type\":\"library\",\"bom-ref\":\"pkg:maven/g/b@2.0?classifier=linux\",\"group\":\"g\",\"name\":\"b\","
                + "\"version\":\"2.0\",\"purl\":\"pkg:maven/g/b@2.0?classifier=linux\"}]}");
    }

    @Test
    void run_resolvesACopyOfThePoms_andWritesTheSboms() throws IOException {
        Path root = project();
        Path log = temp.resolve("args.txt");
        Path mvn = fakeMvn("printf '%s\\n' \"$@\" > '" + log + "'\n"
                + "out=; for a in \"$@\"; do case \"$a\" in -DoutputFile=*) out=${a#-DoutputFile=};; esac; done\n"
                + "ls -A \"$(dirname \"$out\")/src\" > '" + temp.resolve("copied.txt") + "'\n"
                + "cat > \"$out\" <<'EOF'\n" + TREE + "EOF\n");
        Path runtime = temp.resolve("runtime.json");
        Path test = temp.resolve("test.json");
        Facts facts = new Facts("maven");

        MavenTree.run(root, RuleFile.parse("runtime-allow com.fasterxml.jackson.core:*\nruntime-allow org.*:*\n"
                + "maven-repository https://repo.example.org/m2"), options(mvn), runtime, test, facts);

        assertThat(facts.gaps).isEmpty();
        assertThat(facts.facts).containsEntry("exit", 0L).containsEntry("runtime_artifacts", 5L)
                .containsEntry("test_artifacts", 3L).containsKeys("seconds", "repository_mib");
        assertThat(Files.readAllLines(log)).contains("-C", "org.apache.maven.plugins:maven-dependency-plugin:3.11.0:tree",
                "-Dverbose").anyMatch(a -> a.startsWith("-Dmaven.repo.local=" + temp));
        assertThat(Files.readAllLines(temp.resolve("copied.txt"))).containsExactlyInAnyOrder(".mvn", "app", "core", "pom.xml");
        String sbom = Files.readString(runtime);
        assertThat(sbom).contains("pkg:maven/org.slf4j/slf4j-api@1.7.36", "pkg:maven/org.x/tool@1.0?classifier=linux")
                .doesNotContain("pkg:maven/example");
        assertThat(Files.readString(test)).contains("pkg:maven/org.mockito/mockito-core@5.24.0")
                .doesNotContain("jackson-core");
        try (Stream<Path> left = Files.list(temp)) {
            assertThat(left).noneMatch(p -> p.getFileName().toString().startsWith("maven-"));
        }
    }

    @Test
    void aFailingMaven_isAGap_andWritesNoSbom() throws IOException {
        Path mvn = fakeMvn("echo '[INFO] Scanning'; echo '[ERROR] Some problems were encountered:';"
                + " echo '[ERROR] Could not resolve dependencies for project x'; exit 1\n");
        Path runtime = temp.resolve("runtime.json");
        Facts facts = new Facts("maven");

        MavenTree.run(project(), RuleFile.parse(""), options(mvn), runtime, null, facts);

        assertThat(facts.gaps).extracting(g -> g.what() + ": " + g.reason())
                .containsExactly("maven_tree: mvn dependency:tree exit 1: Some problems were encountered: Could not"
                        + " resolve dependencies for project x");
        assertThat(runtime).doesNotExist();
    }

    @Test
    void aMavenThatHangs_isStoppedAtItsTimeout() throws IOException {
        Path mvn = fakeMvn("sleep 30\n");
        Facts facts = new Facts("maven");

        MavenTree.run(project(), RuleFile.parse(""), new MavenTree.Options(mvn.toString(), temp, Duration.ofSeconds(1)),
                null, null, facts);

        assertThat(facts.gaps).extracting(Facts.Gap::reason).containsExactly("Maven did not end within 1 s");
    }

    @Test
    void aBuildExtension_keepsMavenFromRunning_unlessTheRuleFileAllowsIt() throws IOException {
        Path root = project();
        Files.writeString(root.resolve("pom.xml"), Files.readString(root.resolve("pom.xml")).replace("</modules>",
                "</modules><build><extensions><extension><groupId>x</groupId><artifactId>ext</artifactId>"
                        + "<version>1</version></extension></extensions></build>"));
        Path marker = temp.resolve("ran");
        Path mvn = fakeMvn("touch '" + marker + "'; exit 1\n");
        Facts facts = new Facts("maven");

        MavenTree.run(root, RuleFile.parse(""), options(mvn), null, null, facts);

        assertThat(marker).doesNotExist();
        assertThat(facts.gaps).extracting(Facts.Gap::reason).singleElement().asString()
                .startsWith("the POMs declare 1 build extension (pom.xml:").contains("x:ext")
                .endsWith("the rule file does not allow that (maven-extensions allow)");

        MavenTree.run(root, RuleFile.parse("maven-extensions allow"), options(mvn), null, null, new Facts("maven"));
        assertThat(marker).exists();
    }

    @Test
    void settings_mirrorEveryRepository() {
        assertThat(MavenTree.settings("https://repo.example.org/m2?a=1&b=2")).contains("<mirrorOf>*</mirrorOf>")
                .contains("<url>https://repo.example.org/m2?a=1&amp;b=2</url>");
    }

    private Poms.Reactor reactor() throws IOException {
        return Poms.read(project());
    }

    private Path project() throws IOException {
        Path root = temp.resolve("project");
        if (Files.exists(root)) return root;
        Files.createDirectories(root.resolve("app/src/main/java"));
        Files.writeString(root.resolve("pom.xml"), """
                <project><groupId>example</groupId><artifactId>parent</artifactId><version>1.0-SNAPSHOT</version>
                <packaging>pom</packaging><modules><module>app</module><module>core</module></modules></project>
                """);
        for (String module : List.of("app", "core")) {
            Files.createDirectories(root.resolve(module));
            Files.writeString(root.resolve(module + "/pom.xml"), """
                    <project><parent><groupId>example</groupId><artifactId>parent</artifactId><version>1.0-SNAPSHOT</version>
                    </parent><artifactId>%s</artifactId></project>
                    """.formatted(module));
        }
        Files.writeString(root.resolve("app/src/main/java/A.java"), "class A {}");
        return root;
    }

    private Path fakeMvn(String body) throws IOException {
        Path mvn = temp.resolve("mvn-" + System.nanoTime());
        Files.writeString(mvn, "#!/usr/bin/env bash\n" + body);
        Files.setPosixFilePermissions(mvn, PosixFilePermissions.fromString("rwx------"));
        return mvn;
    }

    private MavenTree.Options options(Path mvn) {
        return new MavenTree.Options(mvn.toString(), temp, Duration.ofSeconds(60));
    }
}
