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

    private static List<Range> changed(String before, String now) {
        return Changes.changedLines(before.isEmpty() ? List.of() : List.of(before.split(" ")),
                now.isEmpty() ? List.of() : List.of(now.split(" ")));
    }

    @Test
    void theChangedLines_areTheLinesTheBaseDoesNotHaveInTheirPlace_andTheLinesAroundARemoval() {
        assertThat(changed("a b c", "a b c")).isEmpty();
        assertThat(changed("a b c", "a X c")).as("rewritten").containsExactly(new Range(2, 2));
        assertThat(changed("a b c", "a b n1 n2 c")).as("added").containsExactly(new Range(3, 4));
        assertThat(changed("a b c d", "a d")).as("removed: the lines around the gap").containsExactly(new Range(1, 2));
        assertThat(changed("a b c", "b c")).as("removed at the start").containsExactly(new Range(1, 1));
        assertThat(changed("a b c", "a b")).as("removed at the end").containsExactly(new Range(2, 3));
        assertThat(changed("a b c d e", "a X c e n")).as("several places")
                .containsExactly(new Range(2, 2), new Range(3, 4), new Range(5, 5));
        assertThat(changed("", "a b")).as("a file that was empty").containsExactly(new Range(1, 2));
        assertThat(changed("a b", "")).as("a file that is empty now").containsExactly(new Range(1, 1));
        assertThat(changed("a b a b", "b a b a")).as("moved lines are a removal and an addition")
                .containsExactly(new Range(1, 1), new Range(4, 4));
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

    /** A folder as the step before writes it, with the given manifest. */
    private Path folder(String manifest) throws IOException {
        Path dir = Files.createDirectories(root.resolve("changes/base")).getParent();
        Files.writeString(dir.resolve("base-commit"), "a".repeat(40) + "\n");
        Files.writeString(dir.resolve("head"), "b".repeat(40) + "\n");
        Files.writeString(dir.resolve("from"), "input\n");
        Files.writeString(dir.resolve("since"), "v1.0\n");
        Files.writeString(dir.resolve("previous-head"), "\n");
        Files.writeString(dir.resolve("dirty"), "true\n");
        Files.writeString(dir.resolve("base-date"), "2026-11-10T09:00:00+01:00\n");
        Files.writeString(dir.resolve("skipped"), "3\n");
        Files.writeString(dir.resolve("manifest"), manifest);
        return dir;
    }

    @Test
    void theFolderOfTheStepBefore_givesTheBaseAndEveryFileThatDiffers_withTheLinesOfAChangedJavaFile()
            throws IOException {
        Path work = Files.createDirectories(root.resolve("work/src"));
        Files.writeString(work.resolve("A.java"), "one\r\nTWO\r\nthree\r\n");
        Files.write(work.resolve("Latin.java"), new byte[] {'a', '\n', (byte) 0xE4, '\n'});
        Path dir = folder("M\0src/A.java\0" + "1\0" + "M\0src/Latin.java\0" + "2\0" + "M\0pom.xml\0\0"
                + "A\0src/New.java\0\0" + "D\0src/Gone.java\0\0" + "U\0src/Odd.java\0\0" + "M\0src/Lost.java\0" + "3\0");
        Files.writeString(dir.resolve("base/1"), "one\ntwo\nthree\n");
        Files.write(dir.resolve("base/2"), new byte[] {'a', '\n', (byte) 0xF6, '\n'});
        Files.writeString(dir.resolve("base/3"), "x\n");

        Changes.Base base = Changes.read(dir, root.resolve("work"));

        assertThat(base.base()).isEqualTo("a".repeat(40));
        assertThat(base.head()).isEqualTo("b".repeat(40));
        assertThat(base.from()).isEqualTo("input");
        assertThat(base.since()).isEqualTo("v1.0");
        assertThat(base.previousHead()).isEmpty();
        assertThat(base.dirty()).isTrue();
        assertThat(base.baseDate()).isEqualTo("2026-11-10T09:00:00+01:00");
        assertThat(base.skipped()).isEqualTo("3");
        assertThat(base.lines()).as("line ends alone are no change; bytes that are no UTF-8 are compared all the same")
                .containsOnly(Map.entry("src/A.java", List.of(new Range(2, 2))),
                        Map.entry("src/Latin.java", List.of(new Range(2, 2))), Map.entry("pom.xml", List.of()),
                        Map.entry("src/New.java", List.of(new Range(1, Integer.MAX_VALUE))),
                        Map.entry("src/Lost.java", List.of(new Range(1, Integer.MAX_VALUE))));
        assertThat(base.removed()).containsExactly("src/Gone.java");
        assertThat(base.unmappable()).as("a changed file is never dropped: what could not be compared is named")
                .containsExactly("src/Odd.java (not a readable regular file)",
                        "src/Lost.java (it differs from the base, and its lines could not be compared)");
        assertThat(base.files()).isEqualTo(7);
    }

    @Test
    void aFolderThatDoesNotHold_isRefused() throws IOException {
        Path work = Files.createDirectories(root.resolve("work"));
        Path dir = folder("M\0pom.xml\0");
        assertThatThrownBy(() -> Changes.read(dir, work)).hasMessageContaining("the manifest").hasMessageContaining("is cut");
        Files.writeString(dir.resolve("manifest"), "X\0pom.xml\0\0");
        assertThatThrownBy(() -> Changes.read(dir, work)).hasMessageContaining("an unknown status");
        Files.writeString(dir.resolve("manifest"), "");
        Files.writeString(dir.resolve("base-commit"), "main\n");
        assertThatThrownBy(() -> Changes.read(dir, work)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is no commit id");
    }

    @Test
    void aChangedLineOutsideEveryMember_isNamed_andPicksNoArea() throws IOException {
        project();
        Map<String, List<Range>> lines = Map.of(SRC + "p/Runner.java", List.of(new Range(1, 2)));

        assertThat(reasons(lines)).isEmpty();
        Result result = Finder.find(root, List.of(root.resolve(SRC)), RuleFile.parse(RULES));
        assertThat(Changes.outsideMembers(result, lines)).containsExactly(SRC + "p/Runner.java:1-2");
        assertThat(Changes.outsideMembers(result, Map.of(SRC + "p/Runner.java",
                List.of(new Range(1, Integer.MAX_VALUE))))).as("a new file has members").isEmpty();
    }
}
