package traceworks.selfaudit.architecture;

import traceworks.selfaudit.architecture.Scan.IoUse;
import traceworks.selfaudit.architecture.Scan.Member;
import traceworks.selfaudit.architecture.Scan.Model;
import traceworks.selfaudit.architecture.Scan.Reference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class ScanTest {

    @TempDir
    Path root;

    private Fixture fixture() {
        return new Fixture(root);
    }

    @Test
    void references_followTheResolvedElement_notTheImport() throws IOException {
        Model model = fixture()
                .write("a/A.java", """
                        package a;
                        import b.B;
                        import b.Unused;
                        /** The A of the test. More text. */
                        public class A {
                            void run(B b) {
                                b.go();
                                new b.C();
                            }
                        }
                        """)
                .write("b/B.java", "package b;\npublic class B { public void go() { } }\n")
                .write("b/C.java", "package b;\npublic class C { }\n")
                .write("b/Unused.java", "package b;\npublic class Unused { }\n")
                .model(Fixture.NO_RULES);

        assertThat(model.references()).extracting(Reference::from, Reference::to, Reference::line, Reference::member)
                .containsExactlyInAnyOrder(
                        tuple("a.A", "b.B", 6, null),
                        tuple("a.A", "b.B", 7, "go()"),
                        tuple("a.A", "b.C", 8, "new C()"),
                        tuple("a.A", "b.C", 8, null));
        assertThat(model.types().get("a.A").summary()).isEqualTo("The A of the test.");
        assertThat(model.types().get("a.A").file()).isEqualTo("mod/src/main/java/a/A.java");
    }

    @Test
    void aMemberUsedOnAValue_dependsOnTheValuesType_andAConstantCounts() throws IOException {
        Model model = fixture()
                .write("a/A.java", """
                        package a;
                        class A {
                            String f(b.Holder h) { return h.status().name() + b.Words.YES; }
                        }
                        """)
                .write("b/Holder.java", "package b;\npublic class Holder { public c.Status status() { return null; } }\n")
                .write("b/Words.java", "package b;\npublic final class Words { public static final String YES = \"y\"; }\n")
                .write("c/Status.java", "package c;\npublic enum Status { OK }\n")
                .model(Fixture.NO_RULES);

        assertThat(model.references()).extracting(Reference::from, Reference::to, Reference::member)
                .contains(tuple("a.A", "c.Status", "name()"), tuple("a.A", "b.Words", "YES"),
                        tuple("a.A", "b.Holder", "status()"));
    }

    @Test
    void nestedTypes_countForTheirTopLevelType_withTheirName() throws IOException {
        Model model = fixture()
                .write("a/A.java", """
                        package a;
                        class A {
                            static class Inner { void f() { new b.B.Part(); } }
                        }
                        """)
                .write("b/B.java", "package b;\npublic class B { public static class Part { } }\n")
                .model(Fixture.NO_RULES);

        assertThat(model.references()).extracting(Reference::from, Reference::to, Reference::member)
                .contains(tuple("a.A", "b.B", "Part.new Part()"), tuple("a.A", "b.B", "Part"));
    }

    @Test
    void io_matchesTypesPackagesAndCallsOnSubtypes_inItsPackagesOnly() throws IOException {
        Model model = fixture()
                .write("core/A.java", """
                        package core;
                        import java.nio.file.Files;
                        import java.nio.file.Path;
                        class A {
                            String f(Path p) throws Exception {
                                String home = System.getenv("HOME");
                                var in = A.class.getResourceAsStream("x");
                                java.net.http.HttpClient c = null;
                                return Files.readString(p);
                            }
                        }
                        """)
                .write("other/B.java", "package other;\nclass B { String f() { return System.getenv(\"X\"); } }\n")
                .model("""
                        io core_io
                          why No I/O.
                          in   core.*
                          type java.nio.file.Files java.net.http.*
                          call java.lang.System#getenv
                          call java.lang.Class#getResourceAsStream
                        """);

        assertThat(model.io()).extracting(IoUse::type, IoUse::line, IoUse::what).containsExactly(
                tuple("core.A", 6, "java.lang.System#getenv"),
                tuple("core.A", 7, "java.lang.Class#getResourceAsStream"),
                tuple("core.A", 8, "java.net.http.HttpClient"),
                tuple("core.A", 9, "java.nio.file.Files"));
    }

    @Test
    void aCallOnAPlatformType_thatHasNoSuchMethod_isRefused() {
        assertThatThrownBy(() -> fixture().write("a/A.java", "package a;\nclass A { }\n").model("""
                io x
                  why x
                  in a
                  call java.lang.System#nothing
                """)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rule x: java.lang.System has no method nothing");
    }

    @Test
    void members_haveTheirLinesAndComplexity() throws IOException {
        Model model = fixture().write("a/A.java", """
                package a;
                class A {
                    private int x;
                    A() { }
                    int f(int a, boolean b, boolean c) {
                        if (a > 0) {                     // +1
                            for (int i = 0; i < a; i++) { // +2 (nesting 1)
                                if (b && c || b) { }      // +3 (nesting 2) +2 (&&, ||)
                            }
                        } else if (a < 0) {              // +1
                            return b ? 1 : 2;            // +2 (nesting 1)
                        } else {                         // +1
                            Runnable r = () -> { while (c) { } }; // +3 (nesting 2: else, lambda)
                        }
                        try { } catch (RuntimeException e) { } // +1
                        outer: for (;;) { break outer; } // +1, +1 for the label
                    }
                    static class Inner { void g() { } }
                }
                """).model(Fixture.NO_RULES);

        assertThat(model.members()).extracting(Member::label, Member::kind, Member::start, Member::end,
                Member::complexity).containsExactly(
                tuple("A.x", "field", 3, 3, 0),
                tuple("A()", "constructor", 4, 4, 0),
                tuple("A.f(int, boolean, boolean)", "method", 5, 17, 18),
                tuple("A.Inner", "type", 18, 18, 0),
                tuple("A.Inner.g()", "method", 18, 18, 0));
    }

    /** A run of one logical operator adds 1 however long it is; parentheses do not start a new run. */
    @Test
    void aRunOfOneLogicalOperator_addsOneToTheComplexity() throws IOException {
        Model model = fixture().write("a/A.java", """
                package a;
                class A {
                    boolean f(boolean a, boolean b, boolean c, boolean d) {
                        if (a && b && (c && d)) { }      // +1, +1 for the one run of &&
                        return a || b || c;              // +1 for the one run of ||
                    }
                }
                """).model(Fixture.NO_RULES);

        assertThat(model.members()).filteredOn(member -> member.kind().equals("method"))
                .extracting(Member::complexity).containsExactly(3);
    }

    @Test
    void unresolvedTypeNames_areCollected() throws IOException {
        Model model = fixture().write("a/A.java", """
                package a;
                import com.fasterxml.jackson.databind.JsonNode;
                class A { JsonNode node; int f() { return node.size(); } }
                """).model(Fixture.NO_RULES);

        assertThat(model.unresolvedNames()).containsExactly("JsonNode");
        assertThat(model.references()).isEmpty();
    }

    @Test
    void aPackageInfo_givesThePackagesSummary() throws IOException {
        Model model = fixture()
                .write("a/package-info.java", "/** The package a. It has more. */\npackage a;\n")
                .write("a/A.java", "package a;\nclass A { }\n")
                .model(Fixture.NO_RULES);

        assertThat(model.packageSummaries()).containsEntry("a", "The package a.");
        assertThat(model.types()).containsOnlyKeys("a.A");
    }
}
