package traceworks.selfaudit.areas;

import traceworks.selfaudit.areas.Finder.Hit;
import traceworks.selfaudit.areas.Finder.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class FinderTest {

    @TempDir
    Path root;

    private Path sources() throws IOException {
        return Files.createDirectories(root.resolve("mod/src/main/java"));
    }

    private void write(String path, String content) throws IOException {
        Path file = sources().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private Result find(String rules) throws IOException {
        return Finder.find(root, List.of(sources()), RuleFile.parse(rules));
    }

    @Test
    void aCallAcrossLines_isFoundByItsType_inItsMember() throws IOException {
        write("p/A.java", """
                package p;
                class A {
                    void run(String command) throws Exception {
                        new ProcessBuilder(command,
                                "x").start();
                        Runtime.getRuntime()
                               .exec(command);
                    }
                }
                """);

        Result result = find("""
                rule process_start
                  why Starts a process.
                  new java.lang.ProcessBuilder
                  call java.lang.Runtime#exec
                """);

        assertThat(result.hits()).extracting(Hit::rule, Hit::file, Hit::line, Hit::what, Hit::resolved)
                .containsExactly(
                        tuple("process_start", "mod/src/main/java/p/A.java", 4, "new java.lang.ProcessBuilder", "type"),
                        tuple("process_start", "mod/src/main/java/p/A.java", 7, "call java.lang.Runtime#exec", "type"));
        assertThat(result.hits().getFirst().member()).isEqualTo(new Finder.Member("A.run(String)", 3, 8, false));
        assertThat(result.files()).singleElement().satisfies(f -> {
            assertThat(f.path()).isEqualTo("mod/src/main/java/p/A.java");
            assertThat(f.lines()).hasSize(9);
        });
        assertThat(result.notParsed()).isEmpty();
        assertThat(result.unresolved()).isZero();
    }

    @Test
    void aSubtype_andAnAnonymousSubclass_match() throws IOException {
        write("p/A.java", """
                package p;
                import java.util.HashMap;
                class A {
                    void f(HashMap<String, String> map) {
                        map.put("a", "b");
                        new Thread() { };
                    }
                }
                """);

        Result result = find("""
                rule maps
                  why x
                  call java.util.Map#put
                  new java.lang.Thread
                """);

        assertThat(result.hits()).extracting(Hit::line, Hit::what)
                .containsExactly(tuple(5, "call java.util.Map#put"), tuple(6, "new java.lang.Thread"));
    }

    /** A rule on a subtype matches a call of a method the subtype inherits, through the type of the receiver. */
    @Test
    void aRuleOnASubtype_matchesAMethodItInherits_throughTheReceiver() throws IOException {
        write("p/A.java", """
                package p;
                import java.util.LinkedHashMap;
                class A {
                    void f(LinkedHashMap<String, String> map) {
                        map.put("a", "b");
                    }
                }
                """);

        Result result = find("""
                rule ordered
                  why x
                  call java.util.LinkedHashMap#put
                """);

        assertThat(result.hits()).extracting(Hit::line, Hit::resolved).containsExactly(tuple(5, "type"));
    }

    /** A library type written with its package and no import is matched as the type it names. */
    @Test
    void anUnresolvedTypeWrittenWithItsPackage_isMatchedAsThatType() throws IOException {
        write("p/A.java", """
                package p;
                class A {
                    Object f(String s) throws Exception {
                        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(s);
                    }
                }
                """);

        Result result = find("""
                rule parser
                  why x
                  new com.fasterxml.jackson.databind.ObjectMapper
                  call com.fasterxml.jackson.databind.ObjectMapper#readTree
                """);

        assertThat(result.hits()).extracting(Hit::line, Hit::what, Hit::resolved).containsExactly(
                tuple(4, "call com.fasterxml.jackson.databind.ObjectMapper#readTree", "import"),
                tuple(4, "new com.fasterxml.jackson.databind.ObjectMapper", "import"));
    }

    @Test
    void methodReferences_match_asCallsAndCreations() throws IOException {
        write("p/A.java", """
                package p;
                import java.nio.file.Path;
                import java.util.function.Function;
                import java.util.function.Supplier;
                class A {
                    Supplier<StringBuilder> s = StringBuilder::new;
                    Function<Path, Path> f = Path::normalize;
                }
                """);

        Result result = find("""
                rule refs
                  why x
                  new java.lang.StringBuilder
                  call java.nio.file.Path#normalize nonconstant
                """);

        assertThat(result.hits()).extracting(Hit::line, Hit::what, h -> h.member().label())
                .containsExactly(tuple(6, "new java.lang.StringBuilder", "A.s"),
                        tuple(7, "call java.nio.file.Path#normalize", "A.f"));
    }

    /**
     * A library type that is not on the class path does not resolve; the compilation unit's import names it. Later
     * classes are still attributed, although the first one has errors.
     */
    @Test
    void anUnresolvedType_isMatchedThroughItsImport_andLaterClassesStillResolve() throws IOException {
        write("p/A.java", """
                package p;
                import com.fasterxml.jackson.databind.ObjectMapper;
                class A {
                    private final ObjectMapper mapper = new ObjectMapper();
                    Object read(String s) throws Exception {
                        return mapper.readValue(s, Object.class);
                    }
                }
                """);
        write("p/B.java", """
                package p;
                class B {
                    void run() throws Exception {
                        new ProcessBuilder("a").start();
                    }
                }
                """);

        Result result = find("""
                rule parser
                  why x
                  new com.fasterxml.jackson.databind.ObjectMapper
                  call com.fasterxml.jackson.databind.ObjectMapper#readValue
                rule process_start
                  why y
                  call java.lang.ProcessBuilder#start
                """);

        assertThat(result.hits()).extracting(Hit::file, Hit::line, Hit::rule, Hit::resolved).containsExactly(
                tuple("mod/src/main/java/p/A.java", 4, "parser", "import"),
                tuple("mod/src/main/java/p/A.java", 6, "parser", "import"),
                tuple("mod/src/main/java/p/B.java", 4, "process_start", "type"));
        assertThat(result.unresolved()).isEqualTo(2);
    }

    @Test
    void argumentConditions_tellConstantsFromData() throws IOException {
        write("p/A.java", """
                package p;
                import java.util.regex.Pattern;
                class A {
                    static final String FIXED = "a";
                    static final boolean ON = true;
                    void f(String regex, int flags, String text) {
                        Pattern.compile(regex);
                        Pattern.compile(FIXED);
                        Pattern.compile("x" + "y");
                        Pattern.compile("(a.*)b.*c");
                        Pattern.compile(A.FIXED + regex);
                        Pattern.compile("a", flags);
                        Pattern.compile("(a" + '+' + ")+");
                        Pattern.compile(ON ? "x" : regex);
                        text.replaceAll("a", text);
                        text.replaceAll(regex, "b");
                        System.out.printf("%s", text);
                    }
                }
                """);

        Result result = find("""
                rule redos
                  why x
                  call java.util.regex.Pattern#compile nonconstant
                  call java.util.regex.Pattern#compile constant \\.[*+].*\\.[*+]|\\([^)]*[*+]\\)[*+]
                  call java.lang.String#replaceAll nonconstant 0
                  call java.io.PrintStream#printf nonconstant
                """);

        assertThat(result.hits()).extracting(Hit::line).containsExactly(7, 10, 11, 12, 13, 16, 17);
    }

    @Test
    void literals_wildcardMethods_andScopes() throws IOException {
        write("p/A.java", """
                package p;
                class A {
                    String[] flags() { return new String[] {"--network", "none"}; }
                    void say() { System.out.println("hi"); }
                }
                """);
        write("p/inner/B.java", """
                package p.inner;
                class B {
                    void say() { System.err.printf("%s", "hi"); }
                }
                """);

        Result result = find("""
                rule container_flag
                  why x
                  literal ^--network$
                rule terminal_output
                  why y
                  call java.io.PrintStream#*
                  in p.inner
                """);

        assertThat(result.hits()).extracting(Hit::file, Hit::rule, Hit::what).containsExactly(
                tuple("mod/src/main/java/p/A.java", "container_flag", "literal \"--network\""),
                tuple("mod/src/main/java/p/inner/B.java", "terminal_output", "call java.io.PrintStream#printf"));
    }

    @Test
    void members_areTheMembersOfNamedClasses() throws IOException {
        write("p/A.java", """
                package p;
                class A {
                    static { new StringBuilder(); }
                    private final Object field = new StringBuilder();
                    A(String name, java.util.List<String> more) { new StringBuilder(); }
                    void outer() {
                        Runnable r = new Runnable() {
                            public void run() { new StringBuilder(); }
                        };
                    }
                    static class Inner {
                        void m(int... values) { new StringBuilder(); }
                    }
                    @Deprecated(since = "--network") void annotated() { }
                }
                """);

        Result result = find("""
                rule sb
                  why x
                  new java.lang.StringBuilder
                  literal --network
                """);

        assertThat(result.hits()).extracting(h -> h.member().label()).containsExactly(
                "A.{static initializer}", "A.field", "A(String, java.util.List)", "A.outer()", "A.Inner.m(int...)",
                "A.annotated()");
        assertThat(result.hits().get(3).member()).isEqualTo(new Finder.Member("A.outer()", 6, 10, false));
    }

    @Test
    void aHitOutsideAnyMember_isAClassLevelHit() throws IOException {
        write("p/A.java", """
                package p;
                @SuppressWarnings("--network")
                class A {
                }
                """);

        Result result = find("rule f\n  why x\n  literal --network");

        assertThat(result.hits()).singleElement().satisfies(h -> {
            assertThat(h.line()).isEqualTo(2);
            assertThat(h.member()).isEqualTo(new Finder.Member("A", 2, 2, true));
        });
    }

    @Test
    void aFileThatDoesNotParse_isNotMeasured_andTheOthersStillAre() throws IOException {
        write("p/Broken.java", """
                package p;
                class Broken {
                    void f() { new ProcessBuilder("a") }
                }
                """);
        write("p/Fine.java", """
                package p;
                class Fine {
                    void f() { new ProcessBuilder("a"); }
                }
                """);

        Result result = find("rule p\n  why x\n  new java.lang.ProcessBuilder");

        assertThat(result.notParsed()).singleElement().satisfies(n -> {
            assertThat(n.file()).isEqualTo("mod/src/main/java/p/Broken.java");
            assertThat(n.reason()).startsWith("line 3: ").contains("expected");
        });
        assertThat(result.hits()).extracting(Hit::file).containsExactly("mod/src/main/java/p/Fine.java");
        assertThat(result.files()).extracting(Finder.SourceFile::path).containsExactly("mod/src/main/java/p/Fine.java");
    }

    /** javac reports a limited number of errors by default; each broken file must still be named. */
    @Test
    void everyFileThatDoesNotParse_isNamed_alsoAfterManyUnresolvedTypes() throws IOException {
        StringBuilder unresolved = new StringBuilder("package p;\nclass Many {\n");
        for (int i = 0; i < 150; i++) unresolved.append("    Missing").append(i).append(" f").append(i).append(";\n");
        write("p/Many.java", unresolved.append("}\n").toString());
        write("p/X1.java", "package p;\nclass X1 { void f() { int a = } }\n");
        write("p/X2.java", "package p;\nclass X2 { void f() { new ProcessBuilder(\"a\") } }\n");

        Result result = find("rule p\n  why x\n  new java.lang.ProcessBuilder");

        assertThat(result.notParsed()).extracting(Finder.NotParsed::file)
                .containsExactly("mod/src/main/java/p/X1.java", "mod/src/main/java/p/X2.java");
        assertThat(result.hits()).isEmpty();
    }

    /**
     * A receiver whose type did not resolve is matched through the type it is written with: a creation's type, or a
     * variable's declared type through the imports of the file that declares it. Without one (a failed overload),
     * the method name alone matches, and the hit says so.
     */
    @Test
    void aCallOnAnUnresolvedReceiver_isMatchedThroughTheWrittenType_elseByName() throws IOException {
        Path holder = Files.createDirectories(sources().resolve("q"));
        Files.writeString(holder.resolve("Holder.java"), """
                package q;
                import com.fasterxml.jackson.databind.ObjectMapper;
                public class Holder { public static final ObjectMapper MAPPER = new ObjectMapper(); }
                """);
        write("p/A.java", """
                package p;
                import com.fasterxml.jackson.databind.ObjectMapper;
                import java.nio.file.Path;
                class A {
                    Object f(String s) throws Exception { return new ObjectMapper().readTree(s); }
                    Path g(Path home, com.fasterxml.jackson.databind.JsonNode n) {
                        return home.resolve(n.asText()).normalize();
                    }
                }
                """);
        write("p/B.java", """
                package p;
                class B {
                    Object f(String s) throws Exception { return q.Holder.MAPPER.readValue(s, Object.class); }
                }
                """);

        Result result = find("""
                rule parser
                  why x
                  call com.fasterxml.jackson.databind.ObjectMapper#readTree
                  call com.fasterxml.jackson.databind.ObjectMapper#readValue
                rule path_check
                  why y
                  call java.nio.file.Path#normalize
                """);

        assertThat(result.hits()).extracting(Hit::file, Hit::line, Hit::rule, Hit::resolved).containsExactly(
                tuple("mod/src/main/java/p/A.java", 5, "parser", "import"),
                tuple("mod/src/main/java/p/A.java", 7, "path_check", "name"),
                tuple("mod/src/main/java/p/B.java", 3, "parser", "import"));
        assertThat(result.unresolvedTargets()).containsExactly("com.fasterxml.jackson.databind.ObjectMapper");
    }

    /**
     * A {@code var} is matched through its initializer's creation. A receiver written with another library type is
     * that type: it does not match by name ({@code MissingNode.getInstance()} is no {@code MessageDigest#getInstance}).
     */
    @Test
    void aVar_isMatchedThroughItsCreation_andAnotherWrittenTypeNeverByName() throws IOException {
        write("p/A.java", """
                package p;
                import com.fasterxml.jackson.databind.ObjectMapper;
                import com.fasterxml.jackson.databind.node.MissingNode;
                class A {
                    Object f(String s) throws Exception { var m = new ObjectMapper(); return m.readTree(s); }
                    Object g() { return MissingNode.getInstance(); }
                }
                """);

        Result result = find("""
                rule parser
                  why x
                  call com.fasterxml.jackson.databind.ObjectMapper#*
                rule integrity
                  why y
                  call java.security.MessageDigest#getInstance
                """);

        assertThat(result.hits()).extracting(Hit::line, Hit::rule, Hit::resolved)
                .containsExactly(tuple(5, "parser", "import"));
    }

    /** Records, enum constants with bodies, and switch expressions give their hits to the right member. */
    @Test
    void recordsEnumsAndSwitchExpressions_giveTheirHitsToTheirMembers() throws IOException {
        write("p/R.java", """
                package p;
                record R(String a) {
                    R { new StringBuilder(); }
                    enum E { ONE { void f() { new StringBuilder(); } }; }
                    static int g(int x) { return switch (x) { case 1 -> new StringBuilder().length(); default -> 0; }; }
                }
                """);

        Result result = find("rule sb\n  why x\n  new java.lang.StringBuilder");

        assertThat(result.hits()).extracting(Hit::line, h -> h.member().label())
                .containsExactly(tuple(3, "R(String)"), tuple(4, "R.E.ONE"), tuple(5, "R.g(int)"));
    }

    /** A library method a source class inherits does not resolve either; it matches by name and is counted. */
    @Test
    void aLibraryMethodInheritedThroughASourceSubclass_matchesByName_butAWildcardNever() throws IOException {
        write("p/MyMapper.java", """
                package p;
                import com.fasterxml.jackson.databind.ObjectMapper;
                class MyMapper extends ObjectMapper {
                    Object f(String s) throws Exception { return readTree(s); }
                    Object g(String s) throws Exception { return new MyMapper().readTree(s); }
                    void h() { new MyMapper().anything(); }
                }
                """);

        Result result = find("""
                rule parser
                  why x
                  call com.fasterxml.jackson.databind.ObjectMapper#readTree
                rule all
                  why y
                  call com.fasterxml.jackson.databind.ObjectMapper#*
                """);

        assertThat(result.hits()).extracting(Hit::line, Hit::rule, Hit::resolved)
                .containsExactly(tuple(4, "parser", "name"), tuple(5, "parser", "name"));
        assertThat(result.unresolved()).isGreaterThanOrEqualTo(3);
    }

    /** Constructor chaining is no call of the type, and a constructor javac generates is no member. */
    @Test
    void thisAndSuperCalls_andGeneratedConstructors_giveNoHits() throws IOException {
        write("p/Red.java", """
                package p;
                class Red { void a() { } void b() { a(); } }
                class Sub extends Red { Sub() { super(); } Sub(int x) { this(); } }
                class Sub2 extends Red { }
                """);

        Result result = find("rule red\n  why x\n  call p.Red#*");

        assertThat(result.hits()).extracting(Hit::line, h -> h.member().label()).containsExactly(tuple(2, "Red.b()"));
    }

    /** Names that would encode alike as a URI are two files, and both are read. */
    @Test
    void twoFilesWhoseNamesEncodeAlike_areBothRead() throws IOException {
        write("p/X Y.java", "package p;\nclass XY1 { void f() { new ProcessBuilder(\"a\"); } }\n");
        write("p/X%20Y.java", "package p;\nclass XY2 { void f() { new ProcessBuilder(\"a\"); } }\n");

        Result result = find("rule p\n  why x\n  new java.lang.ProcessBuilder");

        assertThat(result.hits()).extracting(Hit::file)
                .containsExactly("mod/src/main/java/p/X Y.java", "mod/src/main/java/p/X%20Y.java");
    }

    @Test
    void aSymbolicLinkToADirectory_isNamed() throws IOException {
        Path elsewhere = Files.createDirectories(root.resolve("elsewhere"));
        Files.writeString(elsewhere.resolve("Hidden.java"), "package p.linked;\nclass Hidden { }\n");
        Files.createDirectories(sources().resolve("p"));
        Files.createSymbolicLink(sources().resolve("p/linked"), elsewhere);
        write("p/A.java", "package p;\nclass A { }\n");

        Result result = find("rule f\n  why x\n  new java.lang.Object");

        assertThat(result.notParsed()).extracting(Finder.NotParsed::file, Finder.NotParsed::reason)
                .containsExactly(tuple("mod/src/main/java/p/linked", "a symbolic link to a directory, not read"));
    }

    /** The second copy of a class is never attributed: it is named, not counted as read. */
    @Test
    void theSameClassInTwoSourceRoots_isNamed() throws IOException {
        write("p/Dup.java", "package p;\nclass Dup { void f() { new ProcessBuilder(\"a\"); } }\n");
        Path other = Files.createDirectories(root.resolve("other/src/main/java/p"));
        Files.writeString(other.resolve("Dup.java"), "package p;\nclass Dup { void f() { new ProcessBuilder(\"b\"); } }\n");

        Result result = Finder.find(root, List.of(sources(), root.resolve("other/src/main/java")),
                RuleFile.parse("rule p\n  why x\n  new java.lang.ProcessBuilder"));

        assertThat(result.hits()).extracting(Hit::file).containsExactly("mod/src/main/java/p/Dup.java");
        assertThat(result.notParsed()).singleElement().satisfies(n -> {
            assertThat(n.file()).isEqualTo("other/src/main/java/p/Dup.java");
            assertThat(n.reason()).contains("duplicate class");
        });
        assertThat(result.files()).extracting(Finder.SourceFile::path).containsExactly("mod/src/main/java/p/Dup.java");
    }

    /** A typo in a rule names a method its type does not have: refused, not silently matching nothing. */
    @Test
    void aRuleThatNamesAMissingMethodOfAKnownType_isRefused() throws IOException {
        write("p/A.java", "package p;\nclass A { }\n");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> find("rule p\n  why x\n  call java.lang.Runtime#exce"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rule p: java.lang.Runtime has no method exce");
    }

    @Test
    void typesResolveAcrossSourceRoots() throws IOException {
        Path other = Files.createDirectories(root.resolve("other/src/main/java/q"));
        Files.writeString(other.resolve("Base.java"), "package q;\npublic class Base { public void go() { } }\n");
        write("p/A.java", """
                package p;
                class A extends q.Base {
                    void f(A a) { a.go(); }
                }
                """);

        Result result = Finder.find(root, List.of(sources(), root.resolve("other/src/main/java")),
                RuleFile.parse("rule base\n  why x\n  call q.Base#go"));

        assertThat(result.hits()).extracting(Hit::file, Hit::resolved)
                .containsExactly(tuple("mod/src/main/java/p/A.java", "type"));
    }

    @Test
    void symbolicLinks_andModuleDeclarations_areNotRead() throws IOException {
        write("p/A.java", "package p;\nclass A { }\n");
        write("module-info.java", "module m { }\n");
        Path outside = Files.writeString(root.resolve("Outside.java"), "class Outside { }\n");
        Files.createSymbolicLink(sources().resolve("p/Link.java"), outside);

        Result result = find("rule f\n  why x\n  new java.lang.Object");

        assertThat(result.files()).extracting(Finder.SourceFile::path).containsExactly("mod/src/main/java/p/A.java");
        assertThat(result.notParsed()).singleElement().satisfies(n -> {
            assertThat(n.file()).isEqualTo("mod/src/main/java/p/Link.java");
            assertThat(n.reason()).isEqualTo("a symbolic link, not read");
        });
    }

    @Test
    void aFileThatIsNoUtf8_isNotMeasured() throws IOException {
        Path file = sources().resolve("p/Latin.java");
        Files.createDirectories(file.getParent());
        Files.write(file, "package p; class Latin { String s = \"\u00e9\"; }".getBytes(StandardCharsets.ISO_8859_1));

        Result result = find("rule f\n  why x\n  new java.lang.Object");

        assertThat(result.notParsed()).singleElement()
                .satisfies(n -> assertThat(n.reason()).isEqualTo("not UTF-8, not read"));
    }
}
