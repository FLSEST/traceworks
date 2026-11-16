package traceworks.selfaudit.areas;

import traceworks.selfaudit.areas.Areas.Area;
import traceworks.selfaudit.areas.Changes.Pick;
import traceworks.selfaudit.areas.Changes.Range;
import traceworks.selfaudit.areas.Finder.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The changed lines of a diff, the areas they pick, the neighbours one call away, and the reasons. */
class ChangesTest {

    @TempDir
    Path root;

    private static final String RULES = """
            rule process_start
              why Starts a process.
              new java.lang.ProcessBuilder
            rule env_pass
              why Reads the environment.
              call java.lang.System#getenv
            """;

    private static final String SRC = "mod/src/main/java/";

    private void write(String path, String content) throws IOException {
        Path file = root.resolve(SRC).resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** Runner.run starts a process and calls Helper.help; Caller.call calls Runner.run; Far.far calls Caller.call. */
    private void project() throws IOException {
        write("p/Runner.java", """
                package p;
                class Runner {
                    void run(String c) throws Exception {
                        new ProcessBuilder(Helper.help(c)).start();
                    }
                }
                """);
        write("p/Helper.java", """
                package p;
                class Helper {
                    static String help(String c) {
                        return c.strip();
                    }
                    static String unused(String c) {
                        return c;
                    }
                }
                """);
        write("p/Caller.java", """
                package p;
                class Caller {
                    void call(Runner runner) throws Exception {
                        runner.run(System.getenv("CMD"));
                    }
                }
                """);
        write("p/Far.java", """
                package p;
                class Far {
                    void far(Caller caller) throws Exception {
                        System.getenv("X");
                        caller.call(new Runner());
                    }
                }
                """);
    }

    private Map<String, String> reasons(Map<String, List<Range>> lines) throws IOException {
        RuleFile rules = RuleFile.parse(RULES);
        Result result = Finder.find(root, List.of(root.resolve(SRC)), rules);
        List<Area> areas = Areas.of(result, rules, 160, 15);
        Map<String, Pick> picks = Changes.picks(areas, result, lines);
        Map<String, String> reasons = new LinkedHashMap<>();
        for (Area area : areas) {
            if (picks.containsKey(area.id())) reasons.put(area.subject().substring(SRC.length()), picks.get(area.id()).reason());
        }
        return reasons;
    }

    @Test
    void theLinesOfADiff_areTheAddedAndRewrittenLinesOfEachFile_andTheLinesAroundARemoval() {
        String diff = """
                diff --git a/p/A.java b/p/A.java
                index 1111111..2222222 100644
                --- a/p/A.java
                +++ b/p/A.java
                @@ -3 +3,2 @@ class A {
                -old
                +new
                +++ b/p/Fake.java
                @@ -10,2 +10,0 @@
                -gone
                -gone too
                @@ -20,0 +19 @@
                +added
                \\ No newline at end of file
                diff --git a/p/Removed.java b/p/Removed.java
                deleted file mode 100644
                --- a/p/Removed.java
                +++ /dev/null
                @@ -1,3 +0,0 @@
                -a
                -b
                -c
                diff --git a/p/New File.java b/p/New File.java
                new file mode 100644
                --- /dev/null
                +++ b/p/New File.java\t
                @@ -0,0 +1,2 @@
                +x
                +y
                diff --git "a/p/Um\\303\\244.java" "b/p/Um\\303\\244.java"
                --- "a/p/Um\\303\\244.java"
                +++ "b/p/Um\\303\\244.java"
                @@ -1 +1 @@
                -a
                +b
                """;

        Map<String, List<Range>> lines = Changes.lines(diff, "p/Untracked.java\0notes.txt\0");

        assertThat(lines).containsOnlyKeys("p/A.java", "p/New File.java", "p/Umä.java", "p/Untracked.java", "notes.txt");
        assertThat(lines.get("p/A.java")).as("a line of code that looks like a file header is code")
                .containsExactly(new Range(3, 4), new Range(10, 11), new Range(19, 19));
        assertThat(lines.get("p/New File.java")).containsExactly(new Range(1, 2));
        assertThat(lines.get("p/Umä.java")).containsExactly(new Range(1, 1));
        assertThat(lines.get("p/Untracked.java")).containsExactly(new Range(1, Integer.MAX_VALUE));
    }

    @Test
    void anAreaWithAChangedLine_isChanged_andTheAreasOneCallAwayAreItsNeighbours_inBothDirections() throws IOException {
        project();

        Map<String, String> reasons = reasons(Map.of(SRC + "p/Runner.java", List.of(new Range(4, 4))));

        assertThat(reasons).as("Caller.call calls the changed member; Far.far is two calls away")
                .containsExactly(Map.entry("p/Caller.java#Caller.call(Runner)", "neighbour of Runner.run(String)"),
                        Map.entry("p/Runner.java#Runner.run(String)", "changed"));
    }

    @Test
    void aChangedMemberThatIsNoArea_makesTheAreaThatCallsItANeighbour_andItsCodeIsShown() throws IOException {
        project();
        RuleFile rules = RuleFile.parse(RULES);
        Map<String, List<Range>> lines = Map.of(SRC + "p/Helper.java", List.of(new Range(4, 4)));

        assertThat(reasons(lines)).containsExactly(Map.entry("p/Runner.java#Runner.run(String)",
                "neighbour of Helper.help(String)"));

        Result result = Finder.find(root, List.of(root.resolve(SRC)), rules);
        List<Area> areas = Areas.of(result, rules, 160, 15);
        Pick pick = Changes.picks(areas, result, lines).values().iterator().next();
        assertThat(pick.changed()).isFalse();
        assertThat(Changes.connectedText(pick.connected(), result, lines)).isEqualTo(SRC + "p/Helper.java, "
                + "Helper.help(String):\n3|     static String help(String c) {\n4|         return c.strip();\n5|     }");
        assertThat(Changes.changedMembers(result, lines)).extracting(m -> m.member().label())
                .containsExactly("Helper.help(String)");
    }

    @Test
    void aChangedMemberNothingCalls_picksNoArea_andAFileThatIsNoSourceIsNamedAsNotMapped() throws IOException {
        project();
        Map<String, List<Range>> lines = Map.of(SRC + "p/Helper.java", List.of(new Range(7, 7)),
                "pom.xml", List.of(new Range(1, 3)));

        assertThat(reasons(lines)).isEmpty();
        Result result = Finder.find(root, List.of(root.resolve(SRC)), RuleFile.parse(RULES));
        assertThat(Changes.unmapped(result, lines)).containsExactly("pom.xml");
    }

    @Test
    void aChangedImplementation_makesTheAreaThatCallsThroughTheInterfaceANeighbour_andTheOtherWayRound() throws IOException {
        write("p/Port.java", "package p;\ninterface Port {\n    void send(String s) throws Exception;\n}\n");
        write("p/Impl.java", """
                package p;
                class Impl implements Port {
                    public void send(String s) throws Exception {
                        new ProcessBuilder(s).start();
                    }
                }
                """);
        write("p/User.java", """
                package p;
                class User {
                    void use(Port port) throws Exception {
                        port.send(System.getenv("CMD"));
                    }
                }
                """);

        assertThat(reasons(Map.of(SRC + "p/Impl.java", List.of(new Range(4, 4)))))
                .containsExactly(Map.entry("p/Impl.java#Impl.send(String)", "changed"),
                        Map.entry("p/User.java#User.use(Port)", "neighbour of Impl.send(String)"));
        assertThat(reasons(Map.of(SRC + "p/User.java", List.of(new Range(4, 4)))))
                .containsExactly(Map.entry("p/Impl.java#Impl.send(String)", "neighbour of User.use(Port)"),
                        Map.entry("p/User.java#User.use(Port)", "changed"));
    }

    @Test
    void aChangedAreaNamesNoNeighbour_butItsVerdictSeesTheChangedMemberItIsConnectedWith() throws IOException {
        project();
        RuleFile rules = RuleFile.parse(RULES);
        Map<String, List<Range>> lines = Map.of(SRC + "p/Runner.java", List.of(new Range(4, 4)),
                SRC + "p/Helper.java", List.of(new Range(4, 4)));
        Result result = Finder.find(root, List.of(root.resolve(SRC)), rules);
        List<Area> areas = Areas.of(result, rules, 160, 15);
        Area runner = areas.stream().filter(a -> a.file().endsWith("Runner.java")).findFirst().orElseThrow();

        Pick pick = Changes.picks(areas, result, lines).get(runner.id());

        assertThat(pick.changed()).isTrue();
        assertThat(pick.reason()).isEqualTo("changed");
        assertThat(pick.connected()).extracting(m -> m.member().label()).containsExactly("Helper.help(String)");
    }

    @Test
    void theFolderOfTheStepBefore_givesTheBaseTheHeadAndTheLines_andARefIsNoCommitId() throws IOException {
        Path dir = Files.createDirectories(root.resolve("changes"));
        Files.writeString(dir.resolve("base"), "a".repeat(40) + "\n");
        Files.writeString(dir.resolve("head"), "b".repeat(40) + "\n");
        Files.writeString(dir.resolve("from"), "input\n");
        Files.writeString(dir.resolve("since"), "v1.0\n");
        Files.writeString(dir.resolve("previous-head"), "\n");
        Files.writeString(dir.resolve("dirty"), "true\n");
        Files.writeString(dir.resolve("diff"), "+++ b/A.java\n@@ -1 +1 @@\n-a\n+b\n");
        Files.writeString(dir.resolve("untracked"), "");

        Changes.Base base = Changes.read(dir);

        assertThat(base.base()).isEqualTo("a".repeat(40));
        assertThat(base.head()).isEqualTo("b".repeat(40));
        assertThat(base.from()).isEqualTo("input");
        assertThat(base.since()).isEqualTo("v1.0");
        assertThat(base.previousHead()).isEmpty();
        assertThat(base.dirty()).isTrue();
        assertThat(base.lines()).containsOnlyKeys("A.java");

        Files.writeString(dir.resolve("base"), "main\n");
        assertThatThrownBy(() -> Changes.read(dir)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is no commit id");
    }
}
