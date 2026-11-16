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
    void aChangedMemberNothingCalls_picksNoArea() throws IOException {
        project();

        assertThat(reasons(Map.of(SRC + "p/Helper.java", List.of(new Range(7, 7))))).isEmpty();
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

    private Path workFile(String path, String content) throws IOException {
        Path file = root.resolve("work").resolve(path);
        Files.createDirectories(file.getParent());
        return Files.write(file, content.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    @Test
    void theBlobId_isGits_inBothItsLengths() {
        assertThat(Changes.blobId(new byte[0], 40)).isEqualTo("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391");
        assertThat(Changes.blobId("hello\n".getBytes(), 40)).isEqualTo("ce013625030ba8dba906f756967f9e9ca394464a");
        assertThat(Changes.blobId(new byte[0], 64))
                .isEqualTo("473a0f4c3be8a93681a267e3b1e9a7dcda1185436fe141f7749120a303721813");
    }

    @Test
    void whatDiffersIsDecidedFromContent_forEveryFileOfTheTrees_andEveryJavaFileOfTheWorkspace() throws IOException {
        Path work = Files.createDirectories(root.resolve("work"));
        Map<String, String> before = new java.util.TreeMap<>(Map.of("src/Same.java", "same\n",
                "src/Changed.java", "one\n", "src/Gone.java", "gone\n", "run.sh", "echo 1\n", "docs/a.md", "a\n",
                "src/Odd.java", "odd\n", "\"quoted\".java", "old\n", "../outside.java", "x\n",
                "src/Um\u00e4 laut.java", "one\n"));
        Map<String, String> head = new java.util.TreeMap<>(before);
        head.put("Dockerfile", "FROM x\n");
        head.put("src/Changed.java", "two\n");
        head.remove("src/Gone.java");
        workFile("src/Same.java", "same\n");
        workFile("src/Changed.java", "two\n");
        workFile("run.sh", "echo 2\n");
        Files.writeString(Files.createDirectories(work.resolve("src")).resolve("Um\u00e4 laut.java"), "two\n");
        workFile("docs/a.md", "a\n");
        workFile("Dockerfile", "FROM x\n");
        workFile("src/Gone.java", "gone\n");
        workFile("\"quoted\".java", "new\n");
        workFile("quoted.java", "old\n");
        Files.createDirectories(work.resolve("src/Odd.java"));
        // In no commit: Java files wherever they lie, also where a build or a hidden directory would be
        workFile("src/main/java/p/build/New.java", "n\n");
        workFile("src/main/java/p/.hidden/target/node_modules/Deep.java", "d\n");
        workFile(".git/hooks/NotThis.java", "x\n");
        workFile("notes.txt", "in no commit, and no Java file\n");
        Path dir = Handover.write(root.resolve("changes"), before, head);

        Changes.Compared compared = Changes.compare(Changes.read(dir), work);

        assertThat(compared.status()).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("src/Changed.java", "M"), Map.entry("run.sh", "M"),
                Map.entry("Dockerfile", "A"), Map.entry("src/Odd.java", "U"), Map.entry("\"quoted\".java", "M"),
                Map.entry("../outside.java", "U"), Map.entry("quoted.java", "A"),
                Map.entry("src/Um\u00e4 laut.java", "M"),
                Map.entry("src/main/java/p/build/New.java", "A"),
                Map.entry("src/main/java/p/.hidden/target/node_modules/Deep.java", "A")));
        assertThat(compared.dirty()).isTrue();
        assertThat(Changes.wanted(compared)).as("the base blobs of the changed Java files, by id")
                .containsExactlyInAnyOrder(Changes.blobId("one\n".getBytes(), 40), Changes.blobId("old\n".getBytes(), 40));
    }

    @Test
    void aWorkspaceThatIsItsCommit_isNotDirty_andACommittedChangeAloneLeavesItClean() throws IOException {
        workFile("A.java", "two\n");
        workFile("run.sh", "x\n");
        Path dir = Handover.write(root.resolve("changes"), Map.of("A.java", "one\n"),
                Map.of("A.java", "two\n", "run.sh", "x\n"));

        Changes.Compared compared = Changes.compare(Changes.read(dir), root.resolve("work"));

        assertThat(compared.dirty()).isFalse();
        assertThat(compared.status()).containsOnly(Map.entry("A.java", "M"), Map.entry("run.sh", "A"));
    }

    @Test
    void aLineNumberMeansWhatItMeansToTheCompiler_whateverEndsTheLines() throws IOException {
        // Three lone carriage returns, a form feed and a line separator in the comment; then CRLF lines
        String top = "// a\r// b\r// c\r// d\f e  f\r\n";
        String now = top + "class A {\r\n    void m() {\r\n        changed();\r\n    }\r\n}\r\n";
        workFile("A.java", new String(now.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.ISO_8859_1));
        String before = new String(now.replace("changed();", "old();").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.ISO_8859_1);
        Path dir = Handover.write(root.resolve("changes"), Map.of("A.java", before), Map.of("A.java", before));
        Path work = root.resolve("work");
        Result result = Handover.read(work, "A.java");
        int line = result.files().getFirst().lines().indexOf("        changed();") + 1;

        Changes.Handed handed = Changes.read(dir);
        Changes.Base base = Changes.of(handed, Changes.compare(handed, work), result);

        assertThat(line).as("a lone carriage return ends a line for the finder and for javac; a form feed and a"
                + " line separator do not").isEqualTo(7);
        assertThat(base.lines()).containsOnly(Map.entry("A.java", List.of(new Range(line, line))));
        assertThat(base.unmappable()).isEmpty();
    }

    @Test
    void theLinesAreThoseOfTheFilesTheFinderRead_alsoBelowADirectoryCalledBuild_andNoFileItReadIsLeftOut()
            throws IOException {
        Path work = Files.createDirectories(root.resolve("work"));
        String code = "package p;\nclass %s {\n    void m() {\n        int x = %s;\n    }\n}\n";
        Map<String, String> before = new java.util.TreeMap<>();
        for (String dir : List.of("p", "p/build", "p/target", "p/node_modules", "p/.hidden")) {
            String name = "C" + Math.abs(dir.hashCode() % 1000);
            before.put("mod/src/main/java/" + dir + "/" + name + ".java", code.formatted(name, "1"));
            workFile("mod/src/main/java/" + dir + "/" + name + ".java", code.formatted(name, "2"));
        }
        workFile("mod/src/main/java/p/build/Fresh.java", code.formatted("Fresh", "3"));
        Path dir = Handover.write(root.resolve("changes"), before, before);
        Result result = Finder.find(work, List.of(work.resolve("mod/src/main/java")), RuleFile.parse(RULES));

        Changes.Handed handed = Changes.read(dir);
        Changes.Base base = Changes.of(handed, Changes.compare(handed, work), result);

        assertThat(result.files()).hasSize(6);
        assertThat(base.lines().keySet()).as("every file the finder read differs here, and each has its lines")
                .containsExactlyInAnyOrderElementsOf(result.files().stream().map(f -> f.path()).toList());
        assertThat(base.lines().get("mod/src/main/java/p/build/Fresh.java"))
                .containsExactly(new Range(1, Integer.MAX_VALUE));
        assertThat(base.lines().values().stream().filter(r -> r.equals(List.of(new Range(4, 4))))).hasSize(5);
        assertThat(Changes.kinds(base, result, work).uncompiled()).isEmpty();
    }

    @Test
    void aChangedFileWhoseBaseBytesAreNotThere_orAreNoText_countsAsAWhole_andIsNamed() throws IOException {
        workFile("A.java", "now\n");
        workFile("B.java", "now\n");
        Path dir = Handover.write(root.resolve("changes"), Map.of("A.java", "one\n", "B.java", "ä\n"), Map.of());
        Files.write(dir.resolve("blobs"), Handover.batch(List.of("ä\n")));
        Path work = root.resolve("work");

        Changes.Handed handed = Changes.read(dir);
        Changes.Base base = Changes.of(handed, Changes.compare(handed, work), Handover.read(work, "A.java", "B.java"));

        assertThat(base.lines()).containsOnly(Map.entry("A.java", List.of(new Range(1, Integer.MAX_VALUE))),
                Map.entry("B.java", List.of(new Range(1, Integer.MAX_VALUE))));
        assertThat(base.unmappable()).containsExactly(
                "A.java (it differs from the base, and its lines could not be compared)",
                "B.java (it differs from the base, and its lines could not be compared)");
    }

    @Test
    void theFilesNoAreaComesFrom_areSortedByKind() throws IOException {
        Path work = Files.createDirectories(root.resolve("work"));
        List<String> written = List.of("mod/src/main/java/p/A.java", "mod/src/test/java/p/ATest.java",
                "other/src/p/Moved.java", "README.md", "docs/plan.txt", "mod/notes.md", "run.sh", "mod/pom.xml",
                // No documentation: a file of a workflow package, below a source tree, below a docs that is not
                // the project's, in a hidden directory, and the instruction files an AI CLI reads
                "pkg/workflow.json", "pkg/README.md", "pkg/prompts/judge.md", "mod/src/main/resources/help.md",
                "mod/docs/a.txt", ".claude/rules/x.md", "AGENTS.md", "sub/CLAUDE.md");
        for (String path : written) workFile(path, "new\n");
        Map<String, String> head = new java.util.TreeMap<>();
        for (String path : written) if (!path.endsWith(".java")) head.put(path, "new\n");
        Path dir = Handover.write(root.resolve("changes"), Map.of("Gone.java", "x\n", "gone.sh", "x\n",
                "mod/src/test/java/p/GoneTest.java", "x\n"), head);

        Changes.Handed handed = Changes.read(dir);
        Result result = Handover.read(work, "mod/src/main/java/p/A.java");
        Changes.Base base = Changes.of(handed, Changes.compare(handed, work), result);
        Changes.Kinds kinds = Changes.kinds(base, result, work);

        assertThat(kinds.tests()).containsExactly("mod/src/test/java/p/ATest.java", "mod/src/test/java/p/GoneTest.java");
        assertThat(kinds.uncompiled()).containsExactly("other/src/p/Moved.java");
        assertThat(kinds.removedJava()).as("a removed Java file counts").containsExactly("Gone.java");
        assertThat(kinds.docs()).containsExactly("README.md", "docs/plan.txt", "mod/notes.md");
        assertThat(kinds.cannotJudge()).as("a removed script counts too").containsExactly(".claude/rules/x.md",
                "AGENTS.md", "gone.sh", "mod/docs/a.txt", "mod/pom.xml", "mod/src/main/resources/help.md",
                "pkg/README.md", "pkg/prompts/judge.md", "pkg/workflow.json", "run.sh", "sub/CLAUDE.md");
        assertThat(base.removed()).containsExactly("Gone.java", "gone.sh", "mod/src/test/java/p/GoneTest.java");
        assertThat(base.files()).isEqualTo(19);
    }

    @Test
    void aFolderThatDoesNotHold_isRefused() throws IOException {
        Path dir = Handover.write(root.resolve("changes"), Map.of("A.java", "one\n"), Map.of());
        assertThat(Changes.read(dir).blobs()).containsOnlyKeys(Changes.blobId("one\n".getBytes(), 40));

        byte[] tree = Handover.tree(Map.of("A.java", "one\n"));
        Files.write(dir.resolve("base-tree"), java.util.Arrays.copyOf(tree, tree.length - 1));
        assertThatThrownBy(() -> Changes.read(dir)).hasMessageContaining("is cut");
        Files.write(dir.resolve("base-tree"), ("160000 commit " + "c".repeat(40) + "\tsub\0").getBytes());
        assertThatThrownBy(() -> Changes.read(dir)).hasMessageContaining("the workspace has a submodule (sub)");
        Files.write(dir.resolve("base-tree"), "not a tree\0".getBytes());
        assertThatThrownBy(() -> Changes.read(dir)).hasMessageContaining("holds a line that is none");
        Files.write(dir.resolve("base-tree"), tree);

        Files.write(dir.resolve("blobs"), (Changes.blobId("one\n".getBytes(), 40) + " blob 4\nother\n").getBytes());
        assertThatThrownBy(() -> Changes.read(dir)).hasMessageContaining("the blobs of the base");
        Files.write(dir.resolve("blobs"), (Changes.blobId("one\n".getBytes(), 40) + " blob 4\ntwo\n\n"
                + "c".repeat(40) + " missing\n").getBytes());
        assertThat(Changes.read(dir).blobs()).as("bytes that do not give their id are no blob of the base").isEmpty();

        Files.writeString(dir.resolve("base-commit"), "main\n");
        assertThatThrownBy(() -> Changes.read(dir)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is no commit id");
    }

    @Test
    void aChangedLineOutsideEveryMember_isNamed_andMarksEveryAreaOfItsFile() throws IOException {
        project();
        Map<String, List<Range>> lines = Map.of(SRC + "p/Runner.java", List.of(new Range(1, 2)));

        assertThat(reasons(lines)).as("an import or the head of the class can change what every call in the file"
                + " resolves to, and what a call into the file reaches").containsExactly(
                Map.entry("p/Caller.java#Caller.call(Runner)", "neighbour of Runner.run(String)"),
                Map.entry("p/Far.java#Far.far(Caller)", "neighbour of Runner (the head of its class)"),
                Map.entry("p/Runner.java#Runner.run(String)", "changed: the head of its file (outside every member)"));
        Result result = Finder.find(root, List.of(root.resolve(SRC)), RuleFile.parse(RULES));
        assertThat(Changes.outsideMembers(result, lines)).containsExactly(SRC + "p/Runner.java:1-2");
        assertThat(Changes.outsideMembers(result, Map.of(SRC + "p/Runner.java",
                List.of(new Range(1, Integer.MAX_VALUE))))).as("a new file has members").isEmpty();
    }

    private static byte[] treeOf(String... entries) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (String entry : entries) {
            out.writeBytes(entry.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            out.write(0);
        }
        return out.toByteArray();
    }

    @Test
    void aFileWhoseNameIsNoUtf8_inATreeOrOnDisk_isNotInvisible_itIsADoubtByItsName() throws Exception {
        Path work = Files.createDirectories(root.resolve("work/src"));
        // The name holds the byte 0xff: written by a shell, since no Java string gives that name
        Process touch = new ProcessBuilder("bash", "-c", "printf 'class Evil { }\\n' > $'Ev\\xffil.java';"
                + " printf 'x\\n' > $'Un\\xfftracked.java'").directory(work.toFile()).inheritIO().start();
        assertThat(touch.waitFor()).isZero();
        Path dir = Handover.write(root.resolve("changes"), Map.of(), Map.of());
        String id = Changes.blobId("class Evil { }\n".getBytes(), 40);
        Files.write(dir.resolve("head-tree"), treeOf("100644 blob " + id + "\tsrc/Evÿil.java"));

        Changes.Handed handed = Changes.read(dir);
        Changes.Compared compared = Changes.compare(handed, root.resolve("work"));
        Changes.Base base = Changes.of(handed, compared, Handover.read(root.resolve("work")));

        assertThat(compared.status()).as("a file of the workspace commit that the base does not have, and one in no"
                + " commit").hasSize(2).containsValues("U");
        assertThat(base.unmappable()).hasSize(2).allMatch(name -> name.contains("its name is no UTF-8"))
                .anyMatch(name -> name.contains("Ev\\u00ffil.java") || name.contains("Ev\\ufffdil.java"));
        assertThat(compared.dirty()).isTrue();
    }

    @Test
    void aChangedMode_isAChangeOfTheFile_alsoWhenTheBytesAreTheSame() throws IOException {
        Path work = Files.createDirectories(root.resolve("work"));
        Files.writeString(work.resolve("run.sh"), "echo\n");
        assertThat(work.resolve("run.sh").toFile().setExecutable(true)).isTrue();
        Files.writeString(work.resolve("plain.sh"), "echo\n");
        Files.writeString(work.resolve("target.txt"), "t\n");
        Files.createSymbolicLink(work.resolve("Link.java"), Path.of("target.txt"));
        String echo = Changes.blobId("echo\n".getBytes(), 40);
        String link = Changes.blobId("target.txt".getBytes(), 40);
        String target = Changes.blobId("t\n".getBytes(), 40);
        Path dir = Handover.write(root.resolve("changes"), Map.of(), Map.of());
        // The base: run.sh not executable, and Link.java a file whose bytes are the name the link now points at
        Files.write(dir.resolve("base-tree"), treeOf("100644 blob " + link + "\tLink.java",
                "100644 blob " + echo + "\tplain.sh", "100644 blob " + echo + "\trun.sh",
                "100644 blob " + target + "\ttarget.txt"));
        Files.write(dir.resolve("head-tree"), treeOf("120000 blob " + link + "\tLink.java",
                "100644 blob " + echo + "\tplain.sh", "100755 blob " + echo + "\trun.sh",
                "100644 blob " + target + "\ttarget.txt"));

        Changes.Compared compared = Changes.compare(Changes.read(dir), work);

        assertThat(compared.status()).containsOnly(Map.entry("run.sh", "M"), Map.entry("Link.java", "M"));
        assertThat(compared.dirty()).isFalse();
    }

    /**
     * The rule of the check: every file that differs has one status, and every status leads to changed lines of a
     * file the finder read, or to a name in a list the report shows. Here every status meets every kind of file.
     */
    @Test
    void noStatusOfNoKindOfFile_isDroppedSilently() throws IOException {
        Path work = Files.createDirectories(root.resolve("work"));
        Map<String, String> before = new java.util.TreeMap<>();
        Map<String, String> head = new java.util.TreeMap<>();
        List<String> kindsOfFile = List.of("mod/src/main/java/p/%s.java", "mod/src/test/java/p/%sTest.java",
                "tool/src/%s.java", "docs/%s.txt", "%s.md", "scripts/%s.sh");
        for (String kind : kindsOfFile) {
            before.put(kind.formatted("Changed"), "old\n");
            workFile(kind.formatted("Changed"), "new\n");
            head.put(kind.formatted("Changed"), "new\n");
            before.put(kind.formatted("Removed"), "old\n");
            workFile(kind.formatted("Added"), "new\n");
            head.put(kind.formatted("Added"), "new\n");
            before.put(kind.formatted("Odd"), "old\n");
            Files.createDirectories(work.resolve(kind.formatted("Odd")));
        }
        Path dir = Handover.write(root.resolve("changes"), before, head);
        Result result = Handover.read(work, "mod/src/main/java/p/Changed.java", "mod/src/main/java/p/Added.java");

        Changes.Handed handed = Changes.read(dir);
        Changes.Base base = Changes.of(handed, Changes.compare(handed, work), result);
        Changes.Kinds kinds = Changes.kinds(base, result, work);

        assertThat(base.status()).hasSize(24);
        java.util.Set<String> accounted = new java.util.TreeSet<>(base.lines().keySet());
        for (List<String> list : List.of(kinds.tests(), kinds.uncompiled(), kinds.removedJava(), kinds.docs(),
                kinds.cannotJudge())) {
            for (String file : list) assertThat(accounted).as("a file is of one kind").doesNotContain(file);
            accounted.addAll(list);
        }
        for (String name : base.unmappable()) accounted.add(name.substring(0, name.indexOf(" (")));
        assertThat(accounted).containsExactlyElementsOf(new java.util.TreeSet<>(base.status().keySet()));
        // What is neither a judged line, a test, nor a document is a doubt of the gate
        assertThat(kinds.uncompiled()).containsExactly("tool/src/Added.java", "tool/src/Changed.java");
        assertThat(kinds.removedJava()).containsExactly("mod/src/main/java/p/Removed.java", "tool/src/Removed.java");
        assertThat(kinds.cannotJudge()).containsExactly("scripts/Added.sh", "scripts/Changed.sh", "scripts/Removed.sh");
        assertThat(base.unmappable()).hasSize(6);
    }

    @Test
    void aFileTheBaseHasAndTheWorkspaceCommitDoesNot_stillOnDisk_makesTheWorkspaceDiffer() throws IOException {
        workFile("old.sh", "x\n");
        Path dir = Handover.write(root.resolve("changes"), Map.of("old.sh", "x\n"), Map.of());

        Changes.Compared compared = Changes.compare(Changes.read(dir), root.resolve("work"));

        assertThat(compared.status()).isEmpty();
        assertThat(compared.dirty()).as("the commit removed it, the disk has it").isTrue();
    }
}
