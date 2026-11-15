package traceworks.selfaudit.architecture;

import traceworks.selfaudit.architecture.Facts.Candidate;
import traceworks.selfaudit.architecture.Facts.Cycle;
import traceworks.selfaudit.architecture.Facts.Edge;
import traceworks.selfaudit.architecture.Facts.IoFinding;
import traceworks.selfaudit.architecture.Facts.Limits;
import traceworks.selfaudit.architecture.Facts.Result;
import traceworks.selfaudit.architecture.Facts.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class FactsTest {

    @TempDir
    Path root;

    private static final String RULES = """
            prefix x
            layer inner
              why  Inner knows nothing of outer.
              from core.port
              not  core.app ghost
            io core_io
              why   No I/O.
              in    core.*
              type  java.nio.file.Files
              allow core.app.Allowed documented in the docs
              allow core.app.Gone documented, but gone
            """;

    /** core.port.P uses core.app.App (a violation); cli and cli.cmd form a cycle; two files do I/O. */
    private Result facts(Limits limits) throws IOException {
        return new Fixture(root)
                .write("x/core/port/P.java", "package x.core.port;\npublic class P { x.core.app.App app; }\n")
                .write("x/core/app/App.java", "package x.core.app;\npublic class App { x.core.port.P p; }\n")
                .write("x/core/app/Allowed.java", """
                        package x.core.app;
                        class Allowed { String f(java.nio.file.Path p) throws Exception { return java.nio.file.Files.readString(p); } }
                        """)
                .write("x/core/app/Reader.java", """
                        package x.core.app;
                        class Reader {
                            boolean f(java.nio.file.Path p) {
                                return java.nio.file.Files.exists(p);
                            }
                        }
                        """)
                .write("x/cli/Main.java", "package x.cli;\npublic class Main { x.cli.cmd.Run run; }\n")
                .write("x/cli/Args.java", "package x.cli;\npublic class Args { }\n")
                .write("x/cli/cmd/Run.java", "package x.cli.cmd;\npublic class Run { x.cli.Args a; }\n")
                .write("x/cli/cmd/Show.java", "package x.cli.cmd;\npublic class Show { x.cli.Args a; void f(boolean b) { if (b) { } } }\n")
                .facts(RULES, limits);
    }

    @Test
    void thePackageGraph_itsCycle_andItsWeakestEdge() throws IOException {
        Result facts = facts(Limits.DEFAULT);

        assertThat(facts.packages()).containsExactly("cli", "cli.cmd", "core.app", "core.port");
        assertThat(facts.edges()).containsExactly(
                new Edge("cli", "cli.cmd", List.of("cli.Main")),
                new Edge("cli.cmd", "cli", List.of("cli.cmd.Run", "cli.cmd.Show")),
                new Edge("core.app", "core.port", List.of("core.app.App")),
                new Edge("core.port", "core.app", List.of("core.port.P")));
        assertThat(facts.cycles()).extracting(Cycle::name, c -> c.weakest().from(), c -> c.weakest().to())
                .containsExactly(tuple("cli+cli.cmd", "cli", "cli.cmd"), tuple("core.app+core.port", "core.app", "core.port"));
    }

    @Test
    void aLayerViolation_namesItsTypesAndLines() throws IOException {
        Result facts = facts(Limits.DEFAULT);

        assertThat(facts.violations()).singleElement().satisfies(v -> {
            assertThat(v).extracting(Violation::rule, Violation::from, Violation::to)
                    .containsExactly("inner", "core.port", "core.app");
            assertThat(v.sites()).singleElement().satisfies(s -> {
                assertThat(s.type()).isEqualTo("core.port.P");
                assertThat(s.file()).isEqualTo("mod/src/main/java/x/core/port/P.java");
                assertThat(s.lines()).isEqualTo(Map.of(2, List.of("core.app.App")));
            });
        });
        assertThat(facts.unusedPatterns()).containsExactly("inner ghost");
    }

    @Test
    void io_isAllowedOnlyWhereTheRuleFileSaysSo_andAStaleAllowIsNamed() throws IOException {
        Result facts = facts(Limits.DEFAULT);

        assertThat(facts.io()).extracting(IoFinding::type, IoFinding::allowed, IoFinding::uses).containsExactly(
                tuple("core.app.Allowed", "documented in the docs", Map.of(2, List.of("java.nio.file.Files"))),
                tuple("core.app.Reader", null, Map.of(4, List.of("java.nio.file.Files"))));
        assertThat(facts.staleAllows()).containsExactly("core_io core.app.Gone");
    }

    /** Every candidate, in order. Which of them a run judges, and the cap, is chosen later (MainTest). */
    @Test
    void theCandidates_findingsFirst_thenSize_thenComplexity() throws IOException {
        Result facts = facts(new Limits(3, 1, 4, 800, 12_000));

        assertThat(facts.candidates()).extracting(Candidate::file, Candidate::reasons, Candidate::measured)
                .containsExactly(
                        tuple("mod/src/main/java/x/core/port/P.java",
                                List.of(new Facts.Reason("layer", "layer inner: core.port -> core.app")), true),
                        tuple("mod/src/main/java/x/core/app/Reader.java",
                                List.of(new Facts.Reason("io", "io core_io: java.nio.file.Files"),
                                        new Facts.Reason("size", "6 non-comment lines")), true),
                        tuple("mod/src/main/java/x/cli/Main.java", List.of(new Facts.Reason("cycle",
                                "cycle cli+cli.cmd: makes the edge cli -> cli.cmd, the edge of the cycle with the "
                                        + "fewest types")), true),
                        tuple("mod/src/main/java/x/core/app/App.java", List.of(new Facts.Reason("cycle",
                                "cycle core.app+core.port: makes the edge core.app -> core.port, the edge of the "
                                        + "cycle with the fewest types")), true),
                        tuple("mod/src/main/java/x/cli/cmd/Show.java", List.of(new Facts.Reason("complexity",
                                "Show.f(boolean) has cognitive complexity 1")), false));
    }

    @Test
    void tarjan_findsEveryStronglyConnectedSet() {
        List<Edge> edges = List.of(new Edge("a", "b", List.of("a.A")), new Edge("b", "c", List.of("b.B")),
                new Edge("c", "a", List.of("c.C", "c.D")), new Edge("c", "d", List.of("c.C")),
                new Edge("d", "e", List.of("d.D")), new Edge("e", "d", List.of("e.E")));

        assertThat(Facts.cycles(List.of("a", "b", "c", "d", "e"), edges))
                .extracting(c -> c.packages(), c -> c.weakest())
                .containsExactly(tuple(List.of("a", "b", "c"), new Edge("a", "b", List.of("a.A"))),
                        tuple(List.of("d", "e"), new Edge("d", "e", List.of("d.D"))));
    }
}
