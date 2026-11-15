package traceworks.selfaudit.testing;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static traceworks.selfaudit.testing.Workspace.logic;
import static traceworks.selfaudit.testing.Workspace.testClass;
import static org.assertj.core.api.Assertions.assertThat;

/** {@code scripts/facts.py}: each measured rule with a hit and with what only looks like one, and what it says. */
class FactsTest {

    private static final String CLASS = "class-without-test";
    private static final String ASSERTION = "test-without-assertion";
    private static final String DISABLED = "disabled-test";
    private static final String COMMAND = "command-without-integration-test";

    @TempDir
    Path temp;

    private Workspace work;

    @BeforeEach
    void aWorkspace() {
        work = new Workspace(temp);
    }

    // ---- class-without-test ----

    @Test
    void aClassWithLogic_thatNoTestNames_isAFinding() {
        work.main("p/Big.java", logic("p", "Big"))
                .test("p/OtherTest.java", testClass("p", "OtherTest", "assertThat(1).isEqualTo(1);"));

        Workspace.Run run = work.facts();

        assertThat(run.exit()).as(run.describe()).isZero();
        assertThat(work.subjects(CLASS)).containsExactly("p.Big");
        JsonNode finding = work.factsJson().path("findings").get(0);
        assertThat(finding.path("severity").asText()).isEqualTo("LOW");
        assertThat(finding.path("evidence").asText())
                .isEqualTo("class_without_test: app/src/main/java/p/Big.java (11 lines, 3 branches)");
        assertThat(run.out()).contains("## FACT SHEET testing\n")
                .contains("\nFACT class_without_test_count: 1\n")
                .contains("\nHIT class_without_test: app/src/main/java/p/Big.java (11 lines, 3 branches)\n");
    }

    @Test
    void aClassInAWeightedLayer_isAFindingOfTheHigherSeverity() {
        work.main("p/engine/Big.java", logic("p.engine", "Big"))
                .test("p/OtherTest.java", testClass("p", "OtherTest", "assertThat(1).isEqualTo(1);"));

        work.measured();

        assertThat(work.factsJson().path("findings").get(0).path("severity").asText()).isEqualTo("MEDIUM");
    }

    @Test
    void aClassWithATestOfItsName_orNamedByATest_isNoFinding() {
        work.main("p/Big.java", logic("p", "Big"))
                .main("p/Named.java", logic("p", "Named"))
                .main("p/Checked.java", logic("p", "Checked"))
                .test("p/BigTest.java", testClass("p", "BigTest", "assertThat(1).isEqualTo(1);"))
                .test("p/CheckedIT.java", testClass("p", "CheckedIT", "assertThat(1).isEqualTo(1);"))
                .test("p/OtherTest.java", testClass("p", "OtherTest", "assertThat(new Named().pick(1, 2)).isEqualTo(2);"));

        work.measured();

        assertThat(work.subjects(CLASS)).isEmpty();
    }

    @Test
    void aClassNamedOnlyInAComment_AString_OrAnImport_isStillAFinding() {
        work.main("p/Big.java", logic("p", "Big"))
                .test("q/OtherTest.java", "package q;\n\nimport p.Big;\nimport org.junit.jupiter.api.Test;\n\n"
                        + "/** Not a test of {@link Big}. */\nclass OtherTest {\n    @Test\n    void works() {\n"
                        + "        // new Big().pick(1, 2)\n        assertThat(\"Big\").isNotEmpty();\n    }\n}\n");

        work.measured();

        assertThat(work.subjects(CLASS)).containsExactly("p.Big");
    }

    @Test
    void aSmallClass_aRecord_andAnInterface_areBelowTheThresholds() {
        work.main("p/Pair.java", "package p;\n\npublic record Pair(String left, String right) {\n}\n")
                .main("p/Port.java", "package p;\n\npublic interface Port {\n    String a();\n    String b();\n"
                        + "    String c();\n    String d();\n    String e();\n    String f();\n    String g();\n}\n")
                .main("p/Failure.java", "package p;\n\npublic class Failure extends RuntimeException {\n"
                        + "    public Failure(String message) {\n        super(message);\n    }\n}\n")
                .test("p/OtherTest.java", testClass("p", "OtherTest", "assertThat(1).isEqualTo(1);"));

        work.measured();

        assertThat(work.subjects(CLASS)).isEmpty();
        assertThat(work.factsJson().path("counts").path("main_classes").asInt()).isEqualTo(3);
    }

    @Test
    void theThresholds_comeFromTheRuleFile() {
        work.main("p/Big.java", logic("p", "Big"))
                .test("p/OtherTest.java", testClass("p", "OtherTest", "assertThat(1).isEqualTo(1);"));

        work.rule("class_min_branches", "4").measured();
        assertThat(work.subjects(CLASS)).isEmpty();

        work.rule("class_min_branches", "3").rule("class_min_ncloc", "12").measured();
        assertThat(work.subjects(CLASS)).isEmpty();

        work.rule("class_min_ncloc", "11").measured();
        assertThat(work.subjects(CLASS)).containsExactly("p.Big");
    }

    // ---- test-without-assertion ----

    @Test
    void aTestThatAssertsNothing_isAFinding() {
        work.test("p/BigTest.java", testClass("p", "BigTest", "new Big().pick(1, 2);"));

        Workspace.Run run = work.facts();

        assertThat(run.exit()).as(run.describe()).isZero();
        assertThat(work.subjects(ASSERTION)).containsExactly("p.BigTest#works");
        assertThat(run.out()).contains("\nHIT test_without_assertion: p.BigTest#works (app/src/test/java/p/BigTest.java)\n")
                .contains("\nFACT test_methods: 1\n");
    }

    @Test
    void anAssertion_aVerification_aFailure_andAnExpectation_areNoFinding() {
        work.test("p/BigTest.java", "package p;\n\nclass BigTest {\n"
                + "    @Test\n    void a() {\n        assertThat(new Big().pick(1, 2)).isEqualTo(2);\n    }\n"
                + "    @Test\n    void b() {\n        Assertions.assertEquals(2, new Big().pick(1, 2));\n    }\n"
                + "    @Test\n    void c() {\n        verify(mock).pick(1, 2);\n    }\n"
                + "    @Test\n    void d() {\n        try {\n            new Big().pick(1, 2);\n            fail(\"no\");\n"
                + "        } catch (RuntimeException expected) {\n        }\n    }\n"
                + "    @Test\n    void e() {\n        assertThatThrownBy(() -> {\n            new Big().pick(1, 2);\n"
                + "        }).isInstanceOf(RuntimeException.class);\n    }\n"
                + "    @Test\n    void f() {\n        server.expectRequest(\"/x\");\n    }\n"
                + "}\n");

        work.measured();

        assertThat(work.subjects(ASSERTION)).isEmpty();
        assertThat(work.factsJson().path("counts").path("test_methods").asInt()).isEqualTo(6);
    }

    @Test
    void aCommentOrAString_thatOnlyLooksLikeAnAssertion_isAFinding() {
        work.test("p/BigTest.java", "package p;\n\nclass BigTest {\n"
                + "    @Test\n    void comment() {\n        new Big().pick(1, 2); // assertThat(x).isEqualTo(2)\n"
                + "        /* verify(mock).pick(1, 2); */\n    }\n"
                + "    @Test\n    void string() {\n        log(\"assertEquals(1, 2) and fail()\");\n"
                + "        String block = \"\"\"\n            assertThat(1).isEqualTo(1);\n            \"\"\";\n    }\n"
                + "    @Test\n    void word() {\n        int assertions = 3;\n        boolean verify = true;\n    }\n"
                + "    void log(String text) {\n    }\n"
                + "}\n");

        work.measured();

        assertThat(work.subjects(ASSERTION)).containsExactly("p.BigTest#comment", "p.BigTest#string", "p.BigTest#word");
    }

    @Test
    void anAssertionThroughAHelperOfTheClass_alsoAHelperOfAHelper_isNoFinding() {
        work.test("p/BigTest.java", "package p;\n\nclass BigTest {\n"
                + "    @Test\n    void direct() {\n        picks(1, 2, 2);\n    }\n"
                + "    @Test\n    void twoSteps() {\n        this.bothWays(1, 2);\n    }\n"
                + "    @Test\n    void none() {\n        prepare();\n    }\n"
                + "    private void bothWays(int a, int b) {\n        picks(a, b, b);\n        picks(b, a, b);\n    }\n"
                + "    private static void picks(int a, int b, int expected) {\n"
                + "        assertThat(new Big().pick(a, b)).isEqualTo(expected);\n    }\n"
                + "    private void prepare() {\n        prepare();\n        new Big();\n    }\n"
                + "}\n");

        work.measured();

        assertThat(work.subjects(ASSERTION)).containsExactly("p.BigTest#none");
    }

    @Test
    void anAssertionThroughAFixture_aBaseClass_orAStaticImport_isNoFinding() {
        work.test("p/Harness.java", "package p;\n\nfinal class Harness {\n"
                + "    Result run(String name) {\n        Result result = start(name);\n"
                + "        assertThat(result.code()).isZero();\n        return result;\n    }\n"
                + "    Result start(String name) {\n        return new Result(0);\n    }\n"
                + "    static void same(int a, int b) {\n        assertThat(a).isEqualTo(b);\n    }\n"
                + "}\n")
                .test("p/Base.java", "package p;\n\nabstract class Base {\n    protected Harness harness = new Harness();\n"
                        + "    void checked(int a) {\n        Harness.same(a, a);\n    }\n}\n")
                .test("p/RunTest.java", "package p;\n\nimport static p.Harness.same;\n\nclass RunTest extends Base {\n"
                        + "    private final Harness tool = new Harness();\n"
                        + "    @Test\n    void throughAField() {\n        tool.run(\"a\");\n    }\n"
                        + "    @Test\n    void throughALocal() {\n        var local = new Harness();\n        local.run(\"a\");\n    }\n"
                        + "    @Test\n    void throughTheType() {\n        Harness.same(1, 1);\n    }\n"
                        + "    @Test\n    void throughTheImport() {\n        same(1, 1);\n    }\n"
                        + "    @Test\n    void throughTheBase() {\n        checked(1);\n    }\n"
                        + "    @Test\n    void onlyStarts() {\n        tool.start(\"a\");\n    }\n"
                        + "    @Test\n    void onAnotherType() {\n        String text = \"a\";\n        text.run(\"a\");\n    }\n"
                        + "}\n");

        work.measured();

        assertThat(work.subjects(ASSERTION)).containsExactly("p.RunTest#onAnotherType", "p.RunTest#onlyStarts");
    }

    @Test
    void aTestOfANestedClass_isReadWithItsClass_andAHelperOfTheOuterClassCounts() {
        work.test("p/BigTest.java", "package p;\n\nclass BigTest {\n"
                + "    @Nested\n    class Negative {\n"
                + "        @Test\n        void throughTheOuter() {\n            picks(-1, -2, 0);\n        }\n"
                + "        @Test\n        void nothing() {\n            new Big().pick(-1, -2);\n        }\n"
                + "    }\n"
                + "    @Test\n    void nothing() {\n        new Big();\n    }\n"
                + "    void picks(int a, int b, int expected) {\n        assertThat(new Big().pick(a, b)).isEqualTo(expected);\n    }\n"
                + "}\n");

        work.measured();

        assertThat(work.subjects(ASSERTION)).containsExactly("p.BigTest#nothing", "p.BigTest.Negative#nothing");
    }

    @Test
    void aParameterizedTest_withAnnotationsThatHoldBraces_isRead() {
        work.test("p/BigTest.java", "package p;\n\nclass BigTest {\n"
                + "    @ParameterizedTest(name = \"{0}\")\n    @ValueSource(strings = {\"a\", \"b\"})\n"
                + "    public void each(String value) throws Exception {\n        new Big();\n    }\n"
                + "    @org.junit.jupiter.api.Test @Tag(\"slow\") void qualified() {\n        new Big();\n    }\n"
                + "    @RepeatedTest(3)\n    <T> void generic() {\n        assertThat(1).isEqualTo(1);\n    }\n"
                + "}\n");

        work.measured();

        assertThat(work.subjects(ASSERTION)).containsExactly("p.BigTest#each", "p.BigTest#qualified");
        assertThat(work.factsJson().path("counts").path("test_methods").asInt()).isEqualTo(3);
    }

    // ---- disabled-test ----

    @Test
    void aDisabledTest_aDisabledClass_andAnAssumption_areFindingsWithTheirReason() {
        work.rule("assumption_report", "finding");
        work.test("p/BigTest.java", "package p;\n\nclass BigTest {\n"
                + "    @Test\n    @Disabled(\"until the parser is fixed\")\n    void off() {\n        assertThat(1).isEqualTo(1);\n    }\n"
                + "    @Disabled\n    @Test\n    void offWithoutReason() {\n        assertThat(1).isEqualTo(1);\n    }\n"
                + "    @Test\n    void skips() {\n        assumeTrue(docker(), \"no docker\");\n        assertThat(1).isEqualTo(1);\n    }\n"
                + "    @Test\n    void runs() {\n        // @Disabled(\"not really\") and assumeTrue(false)\n"
                + "        assertThat(\"@Disabled\").isNotEmpty();\n    }\n"
                + "}\n")
                .test("p/OffTest.java", "package p;\n\n@Disabled(\"needs a cluster\")\nclass OffTest {\n"
                        + "    @Test\n    void works() {\n        assertThat(1).isEqualTo(1);\n    }\n}\n");

        Workspace.Run run = work.facts();

        assertThat(run.exit()).as(run.describe()).isZero();
        assertThat(work.subjects(DISABLED))
                .containsExactly("p.BigTest#off", "p.BigTest#offWithoutReason", "p.BigTest#skips", "p.OffTest");
        assertThat(run.out())
                .contains("HIT disabled_test: p.BigTest#off (app/src/test/java/p/BigTest.java) @Disabled: until the parser is fixed\n")
                .contains("HIT disabled_test: p.BigTest#offWithoutReason (app/src/test/java/p/BigTest.java) @Disabled: no reason given\n")
                .contains("HIT disabled_test: p.BigTest#skips (app/src/test/java/p/BigTest.java) assumption: "
                        + "assumeTrue(docker(), \"no docker\");\n")
                .contains("HIT disabled_test: p.OffTest (app/src/test/java/p/OffTest.java) @Disabled: needs a cluster\n");
        assertThat(work.subjects(ASSERTION)).isEmpty();
    }

    /** The rule file of the package: a test that skips itself is counted, and no finding. */
    @Test
    void withAssumptionsReportedAsAFact_aTestThatSkipsItself_isCounted_andOnlyADisabledOneIsAFinding() {
        work.test("p/BigTest.java", "package p;\n\nclass BigTest {\n"
                + "    @Test\n    @Disabled(\"until the parser is fixed\")\n    void off() {\n        assertThat(1).isEqualTo(1);\n    }\n"
                + "    @Test\n    void skips() {\n        assumeTrue(docker(), \"no docker\");\n        assertThat(1).isEqualTo(1);\n    }\n"
                + "}\n");

        Workspace.Run run = work.facts();

        assertThat(run.exit()).as(run.describe()).isZero();
        assertThat(work.subjects(DISABLED)).containsExactly("p.BigTest#off");
        assertThat(run.out()).contains("FACT tests_that_may_skip_count: 1\n").doesNotContain("assumption:");
    }

    // ---- command-without-integration-test ----

    @Test
    void aCommandNoIntegrationTestRuns_isAFinding() {
        commands("run", "check", "watch");
        work.file("it/src/test/java/it/RunIT.java", "package it;\n\nclass RunIT {\n"
                + "    @Test\n    void runs() {\n        Result result = tool.exec(\"run\", \"pkg\", \"check\");\n"
                + "        // tool.exec(\"watch\")\n        assertThat(result.code()).isZero();\n"
                + "        assertThat(tool.exec(TIMEOUT, name(\"x\"), \"check\", \"pkg\").code()).isZero();\n"
                + "        other(\"watch\");\n    }\n}\n");

        Workspace.Run run = work.facts();

        assertThat(run.exit()).as(run.describe()).isZero();
        assertThat(work.subjects(COMMAND)).containsExactly("watch");
        assertThat(run.out()).contains("\nFACT commands_declared: 3\n").contains("\nFACT commands_run: 2\n")
                .contains("\nHIT command_without_integration_test: watch (app/src/main/java/cli/Commands.java)\n");
    }

    @Test
    void theCommands_areNotMeasured_withoutTheirDirectories_orWithoutADeclaration() {
        work.main("p/Big.java", logic("p", "Big")).test("p/BigTest.java", testClass("p", "BigTest", "assertThat(1).isEqualTo(1);"));

        assertThat(work.facts().out()).contains("\nNOT MEASURED: command-without-integration-test (no directory "
                + "it/src/test/java, where the rule file says the integration tests are)\n");

        work.file("it/src/test/java/it/RunIT.java", testClass("it", "RunIT", "assertThat(tool.exec(\"run\")).isNotNull();"));
        assertThat(work.facts().out()).contains("\nNOT MEASURED: command-without-integration-test (no command "
                + "declaration found below app/src/main/java with the pattern of the rule file)\n");
        assertThat(work.subjects(COMMAND)).isEmpty();
    }

    // ---- what is not measured ----

    @Test
    void withoutATestSourceRoot_nothingAboutTestsIsMeasured_andItSaysSo() {
        work.main("p/Big.java", logic("p", "Big"));

        Workspace.Run run = work.facts();

        assertThat(run.exit()).as(run.describe()).isZero();
        assertThat(run.out()).contains("\nNOT MEASURED: class-without-test, test-without-assertion, disabled-test and "
                + "the classes to judge (no test source root below the workspace: **/src/test/java)\n");
        assertThat(work.subjects(CLASS)).isEmpty();
        assertThat(work.candidateLines()).isEmpty();
        assertThat(work.factsJson().path("not_measured")).isNotEmpty();
    }

    @Test
    void withoutAMainSourceRoot_itSaysSo() {
        work.test("p/BigTest.java", testClass("p", "BigTest", "assertThat(1).isEqualTo(1);"));

        assertThat(work.facts().out()).contains("\nNOT MEASURED: class-without-test and the classes to judge (no main "
                + "source root below the workspace: **/src/main/java, examples/self-audit/*/images/*/src, images/*/src)\n");
    }

    @Test
    void aJavaFileOutsideTheRoots_aFileThatCannotBeRead_andALink_areNamed() throws IOException {
        work.main("p/Big.java", logic("p", "Big"))
                .main("p/Torn.java", "package p;\n\nclass Torn {\n    void open() {\n        if (true) {\n    }\n")
                .test("p/BigTest.java", testClass("p", "BigTest", "assertThat(1).isEqualTo(1);"))
                .file("tools/gen/Gen.java", "class Gen {\n}\n")
                .file("target/classes/Skipped.java", "class Skipped {\n}\n");
        Files.write(work.root.resolve("app/src/main/java/p/Latin.java"), new byte[] {'/', '/', ' ', (byte) 0xe4, '\n'});
        Files.createSymbolicLink(work.root.resolve("app/src/main/java/p/Link.java"), work.root.resolve("tools/gen/Gen.java"));

        Workspace.Run run = work.facts();

        assertThat(run.exit()).as(run.describe()).isZero();
        assertThat(run.out())
                .contains("\nNOT MEASURED: the file app/src/main/java/p/Torn.java (its braces and parentheses do not match up)\n")
                .contains("\nNOT MEASURED: the file app/src/main/java/p/Latin.java (not UTF-8)\n")
                .contains("\nNOT MEASURED: the file app/src/main/java/p/Link.java (a symbolic link, not followed)\n")
                .contains("\nNOT MEASURED: Java files outside the source roots of the rule file (1, in tools/gen)\n")
                .doesNotContain("Skipped");
        assertThat(work.factsJson().path("counts").path("main_files").asInt()).isEqualTo(1);
    }

    @Test
    void theSourceRoots_comeFromTheRuleFile() {
        work.file("tools/gen/src/p/Big.java", logic("p", "Big"))
                .test("p/OtherTest.java", testClass("p", "OtherTest", "assertThat(1).isEqualTo(1);"));

        work.rule("main_root", "**/src/main/java", "tools/*/src").measured();

        assertThat(work.subjects(CLASS)).containsExactly("p.Big");
        assertThat(work.factsJson().path("roots").path("main")).extracting(JsonNode::asText).containsExactly("tools/gen/src");
        assertThat(work.factsJson().path("not_measured").toString()).doesNotContain("outside the source roots");
    }

    @Test
    void aRuleFileWithAnError_isRefused() {
        work.main("p/Big.java", logic("p", "Big")).test("p/BigTest.java", testClass("p", "BigTest", "assertThat(1).isEqualTo(1);"));

        Workspace.Run run = work.rule("class_min_ncloc", "many").facts();

        assertThat(run.exit()).isNotZero();
        assertThat(run.err()).contains("\"class_min_ncloc\" must be a number, not \"many\"");
    }

    // ---- the classes to judge ----

    @Test
    void theCandidates_areTheTestedClassesWithTheHighestScore_theLayerFirst() {
        work.main("p/Plain.java", logic("p", "Plain"))
                .main("p/engine/Core.java", logic("p.engine", "Core"))
                .main("p/Untested.java", logic("p", "Untested"))
                .main("p/Pair.java", "package p;\n\npublic record Pair(String left, String right) {\n}\n")
                .test("p/PlainTest.java", testClass("p", "PlainTest", "assertThat(new Plain()).isNotNull();"))
                .test("p/PairTest.java", testClass("p", "PairTest", "assertThat(new Pair(\"a\", \"b\")).isNotNull();"))
                .test("p/engine/CoreTest.java", testClass("p.engine", "CoreTest", "assertThat(new Core()).isNotNull();"))
                .test("p/engine/FlowTest.java", testClass("p.engine", "FlowTest",
                        "assertThat(new Core().pick(1, 2)).isEqualTo(new Core().pick(2, 2));"));

        Workspace.Run run = work.facts();

        assertThat(run.exit()).as(run.describe()).isZero();
        List<JsonNode> candidates = work.candidateLines().stream().map(Workspace::json).toList();
        assertThat(candidates).extracting(c -> c.path("class").asText()).containsExactly("p.engine.Core", "p.Plain");
        JsonNode core = candidates.get(0);
        assertThat(core.path("id").asText()).isEqualTo("C01");
        assertThat(core.path("file").asText()).isEqualTo("app/src/main/java/p/engine/Core.java");
        // (11 lines + 4 * 3 branches) * 3 for the layer
        assertThat(core.path("score").asDouble()).isEqualTo(69.0);
        assertThat(candidates.get(1).path("score").asDouble()).isEqualTo(23.0);
        assertThat(core.path("tests")).extracting(JsonNode::asText)
                .containsExactly("app/src/test/java/p/engine/CoreTest.java", "app/src/test/java/p/engine/FlowTest.java");
        assertThat(core.path("tests_text").asText())
                .isEqualTo("- app/src/test/java/p/engine/CoreTest.java\n- app/src/test/java/p/engine/FlowTest.java");
        assertThat(core.path("why").asText()).isEqualTo("11 lines of code and 3 branches; it is in the layer p.engine, "
                + "which the rule file weighs 3 times; score 69");
        assertThat(core.path("code").asText()).startsWith(" 1| package p.engine;\n 2| \n")
                .contains("\n10|         if (a < 0 && b < 0) {\n").endsWith("\n15| }");
        assertThat(core.path("shown_text").asText()).isEqualTo("The whole file.");
        assertThat(core.path("token").asText()).matches("[0-9a-f]{32}")
                .isNotEqualTo(candidates.get(1).path("token").asText());
        assertThat(core.path("fence").asText()).matches("[0-9a-f]{16}").isEqualTo(candidates.get(1).path("fence").asText());
        assertThat(run.out()).contains("\nHIT candidate: app/src/main/java/p/engine/Core.java (C01, p.engine.Core, score 69)\n")
                .contains("\nFACT candidates_count: 2\n").contains("\nFACT candidates_cut_count: 0\n");
        assertThat(work.subjects(CLASS)).containsExactly("p.Untested");
    }

    @Test
    void theCap_cutsTheLowestScores_andNamesThem() {
        for (String name : List.of("A", "B", "C")) {
            work.main("p/" + name + ".java", logic("p", name))
                    .test("p/" + name + "Test.java", testClass("p", name + "Test", "assertThat(1).isEqualTo(1);"));
        }
        work.main("p/Longer.java", logic("p", "Longer").replace("return b;", "if (b > 9) {\n            return 9;\n        }\n        return b;"))
                .test("p/LongerTest.java", testClass("p", "LongerTest", "assertThat(1).isEqualTo(1);"));

        Workspace.Run run = work.rule("candidates_max", "2").facts();

        assertThat(run.exit()).as(run.describe()).isZero();
        assertThat(work.candidateLines().stream().map(Workspace::json)).extracting(c -> c.path("class").asText())
                .containsExactly("p.Longer", "p.A");
        assertThat(work.factsJson().path("cut")).extracting(c -> c.path("class").asText()).containsExactly("p.B", "p.C");
        assertThat(run.out()).contains("\nFACT candidates_count: 2\n").contains("\nFACT candidates_cut_count: 2\n");
    }

    @Test
    void aLongClass_reachesThePromptInPart_andTheFurtherTestFilesAreCounted() {
        work.main("p/Big.java", logic("p", "Big"));
        for (String name : List.of("BigTest", "OneTest", "TwoTest")) {
            work.test("p/" + name + ".java", testClass("p", name, "assertThat(new Big()).isNotNull();"));
        }

        work.rule("max_code_lines", "5").rule("max_tests_listed", "2").measured();

        JsonNode candidate = Workspace.json(work.candidateLines().get(0));
        assertThat(candidate.path("code").asText()).isEqualTo("1| package p;\n2| \n3| /** Picks a number. */\n"
                + "4| public class Big {\n5| ");
        assertThat(candidate.path("shown_text").asText())
                .isEqualTo("The first 5 of its 15 lines; read the rest of the file yourself.");
        assertThat(candidate.path("tests_text").asText()).isEqualTo("- app/src/test/java/p/BigTest.java\n"
                + "- app/src/test/java/p/OneTest.java\n(1 more test files name the class)");
    }

    @Test
    void theScripts_leaveNothingInThePackage() throws IOException {
        work.main("p/Big.java", logic("p", "Big")).test("p/BigTest.java", testClass("p", "BigTest", "assertThat(1).isEqualTo(1);"));

        work.measured();
        work.report("");

        try (var files = Files.walk(Workspace.PACKAGE)) {
            assertThat(files.map(p -> p.getFileName().toString())).doesNotContain("__pycache__");
        }
    }

    private void commands(String... names) {
        StringBuilder source = new StringBuilder("package cli;\n\nclass Commands {\n"
                + "    // Spec.of(\"commented\", \"not a command\")\n");
        for (String name : names) {
            source.append("    static final Spec ").append(name.toUpperCase()).append(" = Spec.of(\"").append(name)
                    .append("\", \"Does it.\");\n");
        }
        work.main("cli/Commands.java", source.append("}\n").toString());
    }

    // ---- items: every class that could be judged, and which of them this run judges (docs/registers.md, "Items") ----

    /** Four classes with a test each; by score: p.Longer, then p.A, p.B, p.C. */
    private void fourClasses() {
        for (String name : List.of("A", "B", "C")) {
            work.main("p/" + name + ".java", logic("p", name))
                    .test("p/" + name + "Test.java", testClass("p", name + "Test", "assertThat(1).isEqualTo(1);"));
        }
        work.main("p/Longer.java", logic("p", "Longer").replace("return b;",
                        "if (b > 9) {\n            return 9;\n        }\n        return b;"))
                .test("p/LongerTest.java", testClass("p", "LongerTest", "assertThat(1).isEqualTo(1);"));
        work.rule("candidates_max", "2");
    }

    /**
     * The placeholder stays a literal where nothing sets it (an older Traceworks): then no item is
     * known, and the run judges as it did before items existed.
     */
    @Test
    void withoutAuditItems_everyEligibleClassIsAnItem_andTheFirstByScoreAreChosen() {
        fourClasses();

        for (String nothing : List.of("{{ audit_items }}", "(no known items)", "")) {
            work.measuredWithItems(nothing);

            assertThat(work.chosen()).as(nothing).containsExactly("p.Longer", "p.A");
            assertThat(work.factsJson().path("items")).extracting(i -> i.path("name").asText() + " "
                    + i.path("chosen").asBoolean() + " '" + i.path("state").asText() + "'")
                    .containsExactly("p.Longer true ''", "p.A true ''", "p.B false ''", "p.C false ''");
            assertThat(work.factsJson().path("cut")).extracting(c -> c.path("class").asText())
                    .containsExactly("p.B", "p.C");
        }
        assertThat(work.digestOf("p.A")).matches("[0-9a-f]{16}").isNotEqualTo(work.digestOf("p.B"));
        JsonNode first = Workspace.json(work.candidateLines().get(0));
        assertThat(first.path("item").asText()).isEqualTo("p.Longer");
        assertThat(first.path("digest").asText()).isEqualTo(work.digestOf("p.Longer"));
        assertThat(work.lastOut).contains("\nFACT items_count: 4\n", "\nFACT items_settled_count: 0\n",
                "\nFACT candidates_count: 2\n", "\nFACT candidates_cut_count: 2\n");
        work.measured();
        assertThat(work.chosen()).as("without --items").containsExactly("p.Longer", "p.A");
    }

    @Test
    void aCleanClassWithTheSameDigest_isNotChosen_andTheNextByScoreTakesItsPlace() {
        fourClasses();
        work.measured();

        work.measuredWithItems("clean | " + work.digestOf("p.Longer") + " | p.Longer",
                "not judged | - | p.B");

        assertThat(work.chosen()).containsExactly("p.A", "p.B");
        assertThat(work.factsJson().path("items")).extracting(i -> i.path("name").asText() + " "
                + i.path("chosen").asBoolean() + " '" + i.path("state").asText() + "'")
                .containsExactly("p.Longer false 'clean'", "p.A true ''", "p.B true 'not judged'", "p.C false ''");
        assertThat(work.factsJson().path("cut")).extracting(c -> c.path("class").asText()).containsExactly("p.C");
        assertThat(work.lastOut).contains("\nFACT items_count: 4\n", "\nFACT items_settled_count: 1\n",
                "\nFACT candidates_cut_count: 1\n");
    }

    @Test
    void aDecidedClassWithTheSameDigest_isNotChosen() {
        fourClasses();
        work.measured();

        work.measuredWithItems("decided | " + work.digestOf("p.Longer") + " | p.Longer",
                "decided | " + work.digestOf("p.A") + " | p.A");

        assertThat(work.chosen()).containsExactly("p.B", "p.C");
    }

    /** What a verdict saw is the class and the test files that name it: a change of either asks for a new one. */
    @Test
    void aCleanClass_isChosenAgain_whenItOrATestFileThatNamesItChanged() {
        fourClasses();
        work.measured();
        String longer = work.digestOf("p.Longer");
        String a = work.digestOf("p.A");

        work.test("p/LongerTest.java", testClass("p", "LongerTest", "assertThat(new Longer().pick(1, 2)).isEqualTo(2);"));
        work.main("p/A.java", logic("p", "A").replace("a > b", "a >= b"));
        work.measuredWithItems("clean | " + longer + " | p.Longer", "clean | " + a + " | p.A");

        assertThat(work.chosen()).containsExactly("p.Longer", "p.A");
        assertThat(work.digestOf("p.Longer")).isNotEqualTo(longer);
        assertThat(work.digestOf("p.A")).isNotEqualTo(a);
    }

    @Test
    void aTestFileThatDoesNotNameTheClass_leavesItsDigestAsItIs() {
        fourClasses();
        work.measured();
        String longer = work.digestOf("p.Longer");

        work.test("p/BTest.java", testClass("p", "BTest", "assertThat(2).isEqualTo(2);"));
        work.measured();

        assertThat(work.digestOf("p.Longer")).isEqualTo(longer);
    }

    /**
     * An open class is asked about again, but only in the places the classes leave that changed or were never
     * judged: so every class is judged once before one is judged twice.
     */
    @Test
    void anOpenClass_isChosenAfterTheChangedOnes_whenPlacesAreLeft() {
        fourClasses();
        work.measured();
        String[] rows = {"open | " + work.digestOf("p.Longer") + " | p.Longer", "clean | " + work.digestOf("p.A") + " | p.A"};

        work.measuredWithItems(rows);
        assertThat(work.chosen()).containsExactly("p.B", "p.C");
        assertThat(work.factsJson().path("cut")).extracting(c -> c.path("class").asText()).containsExactly("p.Longer");

        work.rule("candidates_max", "3").measuredWithItems(rows);
        assertThat(work.chosen()).containsExactly("p.B", "p.C", "p.Longer");

        work.main("p/Longer.java", logic("p", "Longer").replace("return b;",
                "if (b > 8) {\n            return 8;\n        }\n        return b;"));
        work.rule("candidates_max", "2").measuredWithItems(rows);
        assertThat(work.chosen()).as("an open class that changed is a changed class").containsExactly("p.Longer", "p.B");
    }

    /** The question is part of what a verdict saw: who changes it raises the version, and every class is judged again. */
    @Test
    void raisingVerdictVersion_changesEveryDigest() {
        fourClasses();
        work.measured();
        String longer = work.digestOf("p.Longer");

        work.rule("verdict_version", "2").measuredWithItems("clean | " + longer + " | p.Longer");

        assertThat(work.digestOf("p.Longer")).isNotEqualTo(longer);
        assertThat(work.chosen()).containsExactly("p.Longer", "p.A");
    }

    /** A name is compared as the register shows it; a row that is cut or belongs to no class changes nothing. */
    @Test
    void rowsThatBelongToNoClass_changeNothing() {
        fourClasses();
        work.measured();

        work.measuredWithItems("clean | " + work.digestOf("p.Longer") + " | p.Long…", "clean | ffff | p.Gone",
                "CLEAN | " + work.digestOf("p.Longer") + " | p.Longer", "clean | " + work.digestOf("p.Longer"));

        assertThat(work.chosen()).containsExactly("p.Longer", "p.A");
    }

    /**
     * A finding of a run before items existed names no item, so the register may call its class clean. The open
     * finding itself says otherwise: a class that has one is asked about, whatever its row says.
     */
    @Test
    void aClassWithAnOpenFinding_isJudged_evenWhenItsRowSaysClean() {
        fourClasses();
        work.measured();
        List<String> clean = List.of("clean | " + work.digestOf("p.Longer") + " | p.Longer",
                "clean | " + work.digestOf("p.A") + " | p.A");
        String quote = "if (a < 0 && b < 0) {";
        String digest = java.util.HexFormat.of().formatHex(sha256(quote)).substring(0, 12);
        String open = "OPEN | HIGH | uncaught-mutation | p.A#" + digest + " | A: no test notices | Evidence: candidate: "
                + "app/src/main/java/p/A.java ¦ " + quote;
        String dismissed = open.replace("OPEN | ", "DISMISSED | ").replace("p.A", "p.Longer") + " | Disposition: fine";

        work.measuredKnowing(List.of(open, dismissed), clean);

        assertThat(work.chosen()).as("p.A is open; p.Longer is decided and stays out").containsExactly("p.B", "p.C");
        assertThat(work.factsJson().path("cut")).extracting(c -> c.path("class").asText()).containsExactly("p.A");
        work.rule("candidates_max", "3").measuredKnowing(List.of(open, dismissed), clean);
        assertThat(work.chosen()).containsExactly("p.B", "p.C", "p.A");
        assertThat(Workspace.json(work.candidateLines().get(2)).path("known_text").asText()).startsWith("K1 | ");
    }

    private static byte[] sha256(String text) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
