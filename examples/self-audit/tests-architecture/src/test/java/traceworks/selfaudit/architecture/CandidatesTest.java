package traceworks.selfaudit.architecture;

import traceworks.selfaudit.architecture.Candidates.Item;
import traceworks.selfaudit.architecture.Facts.Limits;
import traceworks.selfaudit.architecture.Scan.Model;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CandidatesTest {

    @TempDir
    Path root;

    private static final String RULES = """
            layer inner
              why  Inner knows nothing of outer.
              from port
              not  app
            """;

    private List<Item> items(Fixture fixture, Limits limits) throws IOException {
        RuleFile rules = RuleFile.parse(RULES);
        Model model = Scan.of(fixture.load(), rules);
        return Candidates.of(model, rules, Facts.of(model, rules, limits), limits);
    }

    private Fixture project() throws IOException {
        return new Fixture(root)
                .write("port/package-info.java", "/** The ports. */\npackage port;\n")
                .write("port/Clock.java", """
                        package port;
                        /** The time. */
                        public interface Clock {
                            app.Service service();
                        }
                        """)
                .write("app/Service.java", """
                        package app;
                        /** The use cases. */
                        public class Service {
                            port.Clock clock;
                            public void start() { clock.service(); }
                        }
                        """)
                .write("app/Caller.java", """
                        package app;
                        class Caller { void f(Service s) { s.start(); } }
                        """);
    }

    @Test
    void aCandidateWithAFinding_carriesItsLinesItsContextAndItsWholeCode() throws IOException {
        List<Item> items = items(project(), Limits.DEFAULT);

        // Clock and Service form a cycle too; of its two edges of one type each, app -> port comes first by name
        assertThat(items).extracting(Item::id, Item::file).containsExactly(
                org.assertj.core.groups.Tuple.tuple("C01", "mod/src/main/java/port/Clock.java"),
                org.assertj.core.groups.Tuple.tuple("C02", "mod/src/main/java/app/Service.java"));
        Item item = items.getFirst();
        assertThat(item.measured()).isTrue();
        assertThat(item.findings()).isEqualTo("""
                Layer violation (rule inner): package port depends on app, which the rule forbids. This file's \
                references to app:
                - line 4: app.Service""");
        assertThat(item.context()).contains("This file: interface port.Clock: The time.")
                .contains("Used by (1 type of the sources):\n- app.Service (class): The use cases. [uses service()]")
                .contains("Uses (1 type of the sources):\n- app.Service (class): The use cases.\n")
                .contains("The packages of the sources:\n- app (2 types)\n- port (1 type): The ports.");
        assertThat(item.shown()).isEqualTo("whole");
        assertThat(item.code()).isEqualTo("""
                1| package port;
                2| /** The time. */
                3| public interface Clock {
                4|     app.Service service();
                5| }""");
        assertThat(item.members()).extracting(m -> m.get("name")).containsExactly("service");

        Map<String, Object> json = item.toJson("token", "fence");
        assertThat(json).containsEntry("shown_text", "The whole file, lines 1 to 5.")
                .containsEntry("reasons_text", "- layer inner: port -> app").containsEntry("token", "token");
    }

    @Test
    void aLongFile_isShownInPart_findingMembersAndTheMostComplexFirst() throws IOException {
        StringBuilder big = new StringBuilder("package port;\npublic class Big {\n");
        for (int i = 0; i < 10; i++) big.append("    void plain").append(i).append("() {\n        int x = 1;\n    }\n");
        big.append("    void complex(boolean b) {\n        if (b) { }\n    }\n");
        big.append("    app.Service bad() {\n        return null;\n    }\n}\n");
        Fixture fixture = project().write("port/Big.java", big.toString());

        Item item = items(fixture, new Limits(300, 30, 24, 13, 12_000)).stream()
                .filter(i -> i.file().endsWith("Big.java")).findFirst().orElseThrow();

        // Header (lines 1-2), bad() with the finding (36-38), complex() (33-35), then plain0() (3-5) fills the budget
        assertThat(item.shown()).isEqualTo("part: lines 1-2, 3-5, 33-35, 36-38 of 39");
        assertThat(item.code()).startsWith("1| package port;\n2| public class Big {\n3|     void plain0() {")
                .contains("5|     }\n...| (lines 6 to 32 not shown)\n33|     void complex(boolean b) {")
                .endsWith("38|     }\n...| (lines 39 to 39 not shown)");
        assertThat((String) item.toJson("t", "f").get("shown_text")).startsWith("Only part of the file")
                .contains("- Big.bad() (method, lines 36-38)").contains("- Big.complex(boolean) (method, lines 33-35, complexity 1)");
    }

    @Test
    void theContext_staysWithinItsBudget_andNamesWhatItLeftOut() throws IOException {
        Fixture fixture = project();
        for (int i = 0; i < 20; i++) {
            fixture.write("app/User" + i + ".java", "package app;\n/** User number " + i + " of the clock. */\n"
                    + "class User" + i + " { void f(port.Clock c) { c.service(); } }\n");
        }

        Item item = items(fixture, new Limits(300, 30, 24, 800, 400)).getFirst();

        assertThat(item.context()).contains("Used by (21 types of the sources):").containsPattern(
                "- and \\d+ more, not described: app\\.User");
    }
}
