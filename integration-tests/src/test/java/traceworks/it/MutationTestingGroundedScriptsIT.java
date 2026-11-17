package traceworks.it;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scripts of the mutation testing audit that rates again from the earlier verdict, and moves a rating only on a
 * ground ({@code examples/mutation-testing-grounded/scripts}), as they ship, in the built-in shell image:
 * {@code findings.sh} on fixed results of PIT that change between the runs and {@code report.sh} on fixed answers of the rating, in a
 * package that leaves out the steps that measure and rate. The register is real, and the workspace holds the files
 * the grounds of the answers point to. The scripts learn what the register knew from the register folder of the run
 * ({@code {{ register_dir }}}).
 */
class MutationTestingGroundedScriptsIT extends IntegrationTest {

    private static final String MUTATORS = "org.pitest.mutationtest.engine.gregor.mutators.";
    private static final String MAIN = "core/src/main/java/demo/";
    private static final String TESTS = "core/src/test/java/demo/";

    /** Five methods with a change no test detected; a lambda counts for its method. */
    private static final String FIRST_RESULT = pit("""
            Flow.java,demo.Flow,MathMutator,step,10,SURVIVED,none
            Flow.java,demo.Flow,NegateConditionalsMutator,step,11,KILLED,demo.FlowTest
            Flow.java,demo.Flow$Inner,VoidMethodCallMutator,lambda$quiet$0,20,SURVIVED,none
            Flow.java,demo.Flow,MathMutator,stop,30,SURVIVED,none
            Guard.java,demo.Guard,returns.BooleanTrueReturnValsMutator,check,5,NO_COVERAGE,none
            Guard.java,demo.Guard,NegateConditionalsMutator,allow,8,SURVIVED,none
            """);

    /** step, stop, check and allow each have one more gap, fresh is new, quiet is what it was. */
    private static final String SECOND_RESULT = pit("""
            Flow.java,demo.Flow,MathMutator,step,10,SURVIVED,none
            Flow.java,demo.Flow,NegateConditionalsMutator,step,11,SURVIVED,none
            Flow.java,demo.Flow$Inner,VoidMethodCallMutator,lambda$quiet$0,20,SURVIVED,none
            Flow.java,demo.Flow,MathMutator,stop,30,SURVIVED,none
            Flow.java,demo.Flow,VoidMethodCallMutator,stop,31,NO_COVERAGE,none
            Flow.java,demo.Flow,MathMutator,fresh,40,SURVIVED,none
            Guard.java,demo.Guard,returns.BooleanTrueReturnValsMutator,check,5,NO_COVERAGE,none
            Guard.java,demo.Guard,VoidMethodCallMutator,check,6,SURVIVED,none
            Guard.java,demo.Guard,NegateConditionalsMutator,allow,8,SURVIVED,none
            Guard.java,demo.Guard,MathMutator,allow,9,SURVIVED,none
            """);

    /** step, stop and check each lost a gap; allow, fresh and quiet are what they were. */
    private static final String THIRD_RESULT = pit("""
            Flow.java,demo.Flow,NegateConditionalsMutator,step,11,SURVIVED,none
            Flow.java,demo.Flow$Inner,VoidMethodCallMutator,lambda$quiet$0,20,SURVIVED,none
            Flow.java,demo.Flow,MathMutator,stop,30,SURVIVED,none
            Flow.java,demo.Flow,MathMutator,fresh,40,SURVIVED,none
            Guard.java,demo.Guard,returns.BooleanTrueReturnValsMutator,check,5,NO_COVERAGE,none
            Guard.java,demo.Guard,NegateConditionalsMutator,allow,8,SURVIVED,none
            Guard.java,demo.Guard,MathMutator,allow,9,SURVIVED,none
            """);

    /** allow lost a gap; the others are what they were. */
    private static final String FOURTH_RESULT = pit("""
            Flow.java,demo.Flow,NegateConditionalsMutator,step,11,SURVIVED,none
            Flow.java,demo.Flow$Inner,VoidMethodCallMutator,lambda$quiet$0,20,SURVIVED,none
            Flow.java,demo.Flow,MathMutator,stop,30,SURVIVED,none
            Flow.java,demo.Flow,MathMutator,fresh,40,SURVIVED,none
            Guard.java,demo.Guard,returns.BooleanTrueReturnValsMutator,check,5,NO_COVERAGE,none
            Guard.java,demo.Guard,NegateConditionalsMutator,allow,8,SURVIVED,none
            """);

    /** The methods of demo.Calc of the test of the grounds, each named after the ground its answer gives. */
    private static final List<String> CALC = List.of("own", "of", "gen", "intest", "kw", "called", "commented",
            "slashes", "sub", "outside", "stranger", "noground", "readme", "blank", "viatest", "silent", "ref",
            "<init>", "tested", "up");

    /** A caller of Flow: line 3 calls step, line 4 is blank, line 5 is code that names neither step nor stop. */
    private static final String USE = """
            package demo;
            class Use {
                int twice() { return new Flow().step(1) * 2; }

                void end() { halt(); }
            }
            """;

    private static final String GUARD_TEST = """
            package demo;
            class GuardTest {
                @Test
                void refusesAStranger() {
                    new Guard().check();
                }
            }
            """;

    /** The class of the rated methods itself: line 3 declares own. */
    private static final String CALC_CLASS = """
            package demo;
            class Calc {
                int own(int x) { return x; }
            }
            """;

    /**
     * Another class: line 3 is a comment that calls slashes, line 4 calls resub (not sub), line 5 references ref,
     * line 6 creates a Calc, line 7 is blank, line 8 is code.
     */
    private static final String USER = """
            package demo;
            class User {
                // slashes(1) is called here
                int a() { return resub(1); }
                Runnable r = Calc::ref;
                Calc c = new Calc();

                int b() { return 1; }
            }
            """;

    /** A test that names Calc: it declares real; commented stands in a comment only, if and assertEquals are calls. */
    private static final String A_TEST = """
            package demo;
            class ATest {
                // void commented() { } checks Calc
                @Test
                void real() {
                    if (true) { assertEquals(1, new Calc().called()); }
                    // intest(1)
                }
            }
            """;

    /** A test that declares stranger and does not name the class of the rated methods. */
    private static final String OTHER_TEST = """
            package demo;
            class OtherTest {
                @Test
                void stranger() {
                }
            }
            """;

    private static final String WORKFLOW = """
            {
              "format": "traceworks-workflow",
              "format_version": 1,
              "id": "8c2f4e7a-1b3d-4a5c-9e6f-7d8a9b0c1d2e",
              "name": "IT mutation testing grounded, scripts",
              "metadata": {
                "gate": {"expected": true},
                "audit": {"enabled": true, "key": "it.grounded", "category": "testing", "report_block": "report"}
              },
              "default_context": {"result": "first", "answers": "first"},
              "blocks": [
                {
                  "type": "shell",
                  "name": "findings",
                  "config": {
                    "command": "set -euo pipefail; cp -r {{ package_dir }}/pit-{{ result }} {{ scratch_dir }}/pit; bash {{ package_dir }}/scripts/findings.sh {{ scratch_dir }} {{ register_dir }}",
                    "shell_escape_variables": true
                  }
                },
                {
                  "type": "shell",
                  "name": "report",
                  "config": {
                    "command": "set -euo pipefail; bash {{ package_dir }}/scripts/report.sh {{ scratch_dir }} {{ project_path }} < {{ package_dir }}/answers-{{ answers }}.txt",
                    "shell_escape_variables": true
                  }
                }
              ]
            }
            """;

    private static final String ASKED_AGAIN = " A method without an answer or with a raise that was not taken is asked"
            + " about again in the next run.";

    @Test
    void aRatingStaysUntilAnAnswerNamesAGroundTheWorkspaceHolds() throws IOException {
        Path dir = audit();

        // Nothing is known: every method is rated fresh. An answer for a method nobody measured counts for nothing,
        // and so does an entry that is no rating
        Result first = run(dir);

        assertThat(first.code()).as(first.describe()).isZero();
        assertThat(first.out()).contains("RED (testing)").contains("register it.grounded: 4 new, 0 resolved, "
                + "0 reopened, 4 open now; items 1 of 5 settled, 5 judged in this run");
        assertThat(traceworks.folder(first).output("findings")).contains("FACT mutants: 6\n")
                .contains("FACT survived: 4\n").contains("FACT not_covered: 1\n")
                .contains("FACT methods_to_rate: 5 of 5\n")
                .contains("HIT undetected: demo.Flow#quiet (1 survived, 0 not covered, of 1)\n")
                .contains("HIT undetected: demo.Guard#check (0 survived, 1 not covered, of 1)");
        assertThat(scratchFile(first, "findings.json")).content()
                .contains("demo.Flow#step\\n survived: 10 MathMutator\\n not covered: \\n earlier rating: none, "
                        + "rate it fresh\"");
        assertThat(traceworks.folder(first).output("report"))
                .contains("0 methods kept their rating without being asked")
                .contains("A model was asked about 5: 5 that were never rated, and 0 rated before.")
                .contains("4 methods are rated above low. A person decided on 0 of them; 4 are left to decide, 1 of "
                        + "them high:\n\n- `demo.Flow#step`: a wrong result\n")
                .doesNotContain("Changes taken:").doesNotContain("Lowerings not taken").doesNotContain("Raises not");
        // The method rated low is counted and is no finding
        assertThat(list()).contains("HIGH      undetected-mutants  demo.Flow#step")
                .contains("MEDIUM    undetected-mutants  demo.Guard#check")
                .contains("MEDIUM    undetected-mutants  demo.Guard#allow")
                .contains("MEDIUM    undetected-mutants  demo.Flow#stop").doesNotContain("demo.Flow#quiet");

        // The same result of PIT: no method is asked about, and answers that would move every rating count for nothing
        Result second = run(dir, "--input", "answers=other");

        assertThat(second.code()).as(second.describe()).isZero();
        assertThat(second.out()).contains("RED (testing)").contains("register it.grounded: 0 new, 0 resolved, "
                + "0 reopened, 4 open now; items 1 of 5 settled, 0 judged in this run");
        assertThat(traceworks.folder(second).output("findings")).contains("FACT methods_to_rate: 0 of 5\n");
        assertThat(traceworks.folder(second).output("report"))
                .contains("5 methods kept their rating without being asked")
                .contains("A model was asked about 0: ")
                .contains("4 are left to decide, 1 of them high:\n\n- `demo.Flow#step`: a wrong result\n");

        // Four methods changed and one is new. The model gets each earlier rating with what changed
        Result third = run(dir, "--input", "result=second", "--input", "answers=second");

        assertThat(third.code()).as(third.describe()).isZero();
        assertThat(traceworks.folder(third).output("findings")).contains("FACT methods_to_rate: 5 of 6\n");
        assertThat(scratchFile(third, "findings.json")).content()
                .contains(" earlier rating: HIGH, the reason then: a wrong result\\n changed since: added: survived "
                        + "NegateConditionalsMutator\"")
                .contains(" earlier rating: MEDIUM, the reason then: the flow does not stop\\n changed since: added: "
                        + "not covered VoidMethodCallMutator\"")
                .contains("demo.Flow#fresh\\n survived: 40 MathMutator\\n not covered: \\n earlier rating: none, "
                        + "rate it fresh\"");
        // step keeps its rating because the answer keeps it. stop is raised on a line of code, check is lowered on
        // a test that is declared and names Guard. allow is not lowered: the test class declares no such method
        assertThat(traceworks.folder(third).output("report"))
                .contains("1 methods kept their rating without being asked")
                .contains("A model was asked about 5: 1 that were never rated, and 4 rated before. Of those rated "
                        + "before, 1 kept their rating, 2 changes were taken on a ground that holds, 1 lowerings were "
                        + "not taken, and 0 raises were not taken. 0 methods got no answer." + ASKED_AGAIN)
                .contains("Changes taken:\n\n"
                        + "- `demo.Flow#stop`: MEDIUM to HIGH on line core/src/main/java/demo/Use.java:5: the caller "
                        + "goes on with a flow that should have ended\n"
                        + "- `demo.Guard#check`: MEDIUM to LOW on test demo.GuardTest#refusesAStranger: a test of the "
                        + "guard notices it now\n")
                .contains("Lowerings not taken, the rating stands:\n\n"
                        + "- `demo.Guard#allow`: stays MEDIUM, not LOW: the test class GuardTest declares no method "
                        + "void noSuchTest(\n")
                .contains("4 methods are rated above low. A person decided on 0 of them; 4 are left to decide, 2 of "
                        + "them high:\n\n- `demo.Flow#step`: still a wrong result\n- `demo.Flow#stop`: the caller goes "
                        + "on with a flow that should have ended\n");
        // The lowered method left the findings and the register resolved it; the new method is a finding. Its reason
        // holds the end and the start of an HTML comment: the register applied the report all the same
        assertThat(third.out()).contains("RED (testing)").contains("register it.grounded: 1 new, 1 resolved, "
                + "0 reopened, 4 open now; items 2 of 6 settled, 5 judged in this run");
        assertThat(list()).contains("HIGH      undetected-mutants  demo.Flow#stop")
                .contains("MEDIUM    undetected-mutants  demo.Guard#allow")
                .contains("MEDIUM    undetected-mutants  demo.Flow#fresh");

        // step, stop and check changed again. check was rated low and is no finding: its earlier rating is LOW, and
        // resolved.jsonl still has what it was rated before that
        Result fourth = run(dir, "--input", "result=third", "--input", "answers=third");

        assertThat(fourth.code()).as(fourth.describe()).isZero();
        assertThat(registerFile(fourth, "resolved.jsonl")).content()
                .contains("\"description\":\"Rating: a user sees the wrong page\\nKinds: /BooleanTrueReturnValsMutator\\n")
                .contains("\"status\":\"RESOLVED\",\"subject\":\"demo.Guard#check\"");
        assertThat(registerFile(fourth, "findings.jsonl")).content()
                .contains("\"description\":\"Rating: the caller goes on with a flow that should have ended\\nGround: "
                        + "line core/src/main/java/demo/Use.java:5\\nKinds: MathMutator/VoidMethodCallMutator\\n")
                // the reason with the comment marks, as the model wrote it (the register's file writes < as <)
                .contains("\"description\":\"Rating: a stale page} --> \\u003c!-- shown\\nKinds: MathMutator/\\n")
                .doesNotContain("demo.Guard#check");
        assertThat(traceworks.folder(fourth).output("findings")).contains("FACT methods_to_rate: 3 of 6\n");
        assertThat(scratchFile(fourth, "findings.json")).content()
                .contains(" earlier rating: LOW. Before that it was MEDIUM, the reason then: a user sees the wrong "
                        + "page\\n changed since: its gaps or the decision on it; the kinds of its gaps then were not "
                        + "kept\"")
                .contains(" changed since: went away: not covered VoidMethodCallMutator\\n ground of the last change: "
                        + "line core/src/main/java/demo/Use.java:5\"");
        // step is lowered on the line of its caller in another file. stop is not lowered: the line does not call
        // it. check is raised from LOW on a line of code, and is a finding again
        assertThat(traceworks.folder(fourth).output("report"))
                .contains("3 methods kept their rating without being asked")
                .contains("A model was asked about 3: 0 that were never rated, and 3 rated before. Of those rated "
                        + "before, 0 kept their rating, 2 changes were taken on a ground that holds, 1 lowerings were "
                        + "not taken, and 0 raises were not taken.")
                .contains("Changes taken:\n\n"
                        + "- `demo.Flow#step`: HIGH to MEDIUM on line core/src/main/java/demo/Use.java:3: the one "
                        + "caller doubles the value and shows it\n"
                        + "- `demo.Guard#check`: LOW to MEDIUM on line /workspace/core/src/main/java/demo/Use.java:5: "
                        + "the page of a stranger is shown again\n")
                .contains("Lowerings not taken, the rating stands:\n\n"
                        + "- `demo.Flow#stop`: stays HIGH, not LOW: line 3 of core/src/main/java/demo/Use.java does "
                        + "not call or reference stop\n")
                .contains("5 methods are rated above low. A person decided on 0 of them; 5 are left to decide, 1 of "
                        + "them high:");
        assertThat(fourth.out()).contains("RED (testing)").contains("register it.grounded: 0 new, 0 resolved, "
                + "1 reopened, 5 open now; items 1 of 6 settled, 3 judged in this run");
        assertThat(list()).contains("MEDIUM    undetected-mutants  demo.Flow#step")
                .contains("MEDIUM    undetected-mutants  demo.Guard#check");

        // A person's decision is part of what a rating sees: the method is asked about once more, with the note,
        // keeps its rating and no longer counts
        decide("dismiss", "demo.Guard#allow");
        Result fifth = run(dir, "--input", "result=third", "--input", "answers=fourth");

        assertThat(fifth.code()).as(fifth.describe()).isZero();
        assertThat(traceworks.folder(fifth).output("findings")).contains("FACT methods_to_rate: 1 of 6\n");
        assertThat(scratchFile(fifth, "findings.json")).content()
                .contains(" earlier rating: MEDIUM, the reason then: a stranger gets in\\n changed since: the kinds of "
                        + "its gaps are what they were\\n a person decided: DISMISSED at MEDIUM. Note: looked at it\"");
        assertThat(traceworks.folder(fifth).output("report"))
                .contains("5 methods kept their rating without being asked")
                .contains("A model was asked about 1: 0 that were never rated, and 1 rated before. Of those rated "
                        + "before, 1 kept their rating, 0 changes were taken")
                .contains("5 methods are rated above low. A person decided on 1 of them; 4 are left to decide, 1 of "
                        + "them high:");
        assertThat(fifth.out()).contains("register it.grounded: 0 new, 0 resolved, 0 reopened, 4 open now; "
                + "items 2 of 6 settled, 1 judged in this run");

        // Nothing changed: nothing is asked, and the decision still covers the method
        Result sixth = run(dir, "--input", "result=third", "--input", "answers=other");

        assertThat(sixth.code()).as(sixth.describe()).isZero();
        assertThat(traceworks.folder(sixth).output("findings")).contains("FACT methods_to_rate: 0 of 6\n");
        assertThat(traceworks.folder(sixth).output("report"))
                .contains("6 methods kept their rating without being asked")
                .contains("A person decided on 1 of them; 4 are left to decide, 1 of them high:\n\n"
                        + "- `demo.Flow#stop`: the caller goes on with a flow that should have ended\n");
        assertThat(sixth.out()).contains("RED (testing)").contains("register it.grounded: 0 new, 0 resolved, "
                + "0 reopened, 4 open now; items 2 of 6 settled, 0 judged in this run");

        // The dismissed method changed and is lowered on a test: it leaves the findings of the report
        Result seventh = run(dir, "--input", "result=fourth", "--input", "answers=fifth");

        assertThat(seventh.code()).as(seventh.describe()).isZero();
        assertThat(traceworks.folder(seventh).output("findings")).contains("FACT methods_to_rate: 1 of 6\n");
        assertThat(traceworks.folder(seventh).output("report"))
                .contains("Changes taken:\n\n- `demo.Guard#allow`: MEDIUM to LOW on test GuardTest#refusesAStranger: "
                        + "the test of the guard notices it\n")
                .contains("4 methods are rated above low. A person decided on 0 of them; 4 are left to decide, 1 of "
                        + "them high:");

        // The register keeps a dismissed finding for a while after a report left it out. The next run reads it as
        // what the method was rated before it was rated low: the method stays low and is not asked about, also
        // when an answer would raise it
        Result eighth = run(dir, "--input", "result=fourth", "--input", "answers=other");

        assertThat(eighth.code()).as(eighth.describe()).isZero();
        assertThat(registerFile(eighth, "findings.jsonl")).content()
                .contains("{\"absent_reports\":1,\"description\":\"Rating: a stranger gets in\\n");
        assertThat(traceworks.folder(eighth).output("findings")).contains("FACT methods_to_rate: 0 of 6\n");
        assertThat(traceworks.folder(eighth).output("report"))
                .contains("6 methods kept their rating without being asked")
                .contains("4 methods are rated above low. A person decided on 0 of them; 4 are left to decide, 1 of "
                        + "them high:")
                .doesNotContain("\"subject\":\"demo.Guard#allow\"");
        assertThat(traceworks.exec("findings", "it.grounded", "verify").code()).isZero();
    }

    /**
     * What a ground must be to be taken, on twenty methods of one class that were each rated MEDIUM and changed. A
     * lowering that is not taken is final; a raise that is not taken, and a method without an answer, are asked
     * about again.
     */
    @Test
    void aGroundIsALineOfCodeThatCallsTheMethod_orADeclaredTestThatNamesItsClass() throws IOException {
        Path dir = audit();
        Result first = run(dir, "--input", "result=calc-first", "--input", "answers=calc-first");
        assertThat(first.code()).as(first.describe()).isZero();
        assertThat(first.out()).contains("register it.grounded: 20 new, 0 resolved, 0 reopened, 20 open now; "
                + "items 0 of 20 settled, 20 judged in this run");

        Result second = run(dir, "--input", "result=calc-second", "--input", "answers=calc-second");

        assertThat(second.code()).as(second.describe()).isZero();
        assertThat(traceworks.folder(second).output("findings")).contains("FACT methods_to_rate: 20 of 20\n");
        assertThat(traceworks.folder(second).output("report"))
                .contains("A model was asked about 20: 0 that were never rated, and 20 rated before. Of those rated "
                        + "before, 0 kept their rating, 4 changes were taken on a ground that holds, 12 lowerings "
                        + "were not taken, and 3 raises were not taken. 1 methods got no answer." + ASKED_AGAIN)
                // a line that creates a Calc, a line that references the method, a declared test that names Calc,
                // and for a raise a line of code
                .contains("Changes taken:\n\n"
                        + "- `demo.Calc#<init>`: MEDIUM to LOW on line core/src/main/java/demo/User.java:6: w\n"
                        + "- `demo.Calc#ref`: MEDIUM to LOW on line core/src/main/java/demo/User.java:5: w\n"
                        + "- `demo.Calc#tested`: MEDIUM to LOW on test demo.ATest#real: w\n"
                        + "- `demo.Calc#up`: MEDIUM to HIGH on line core/src/main/java/demo/User.java:8: w\n")
                .contains("Lowerings not taken, the rating stands:\n\n"
                        // a call in a test is no declaration, nor is a declaration in a comment, nor a keyword
                        + "- `demo.Calc#called`: stays MEDIUM, not LOW: the test class ATest declares no method void "
                        + "assertEquals(\n"
                        + "- `demo.Calc#commented`: stays MEDIUM, not LOW: the test class ATest declares no method "
                        + "void commented(\n"
                        // a file below target, a test, and a file that is no Java source
                        + "- `demo.Calc#gen`: stays MEDIUM, not LOW: target/src/main/java/demo/Gen.java is no .java "
                        + "file below a src/main folder\n"
                        + "- `demo.Calc#intest`: stays MEDIUM, not LOW: core/src/test/java/demo/ATest.java is no "
                        + ".java file below a src/main folder\n"
                        + "- `demo.Calc#kw`: stays MEDIUM, not LOW: the test class ATest declares no method void if(\n"
                        + "- `demo.Calc#noground`: stays MEDIUM, not LOW: the answer gives no ground\n"
                        + "- `demo.Calc#of`: stays MEDIUM, not LOW: README.md is no .java file below a src/main "
                        + "folder\n"
                        // a path that leaves the workspace
                        + "- `demo.Calc#outside`: stays MEDIUM, not LOW: the workspace has no file "
                        + "../elsewhere/src/main/java/demo/X.java\n"
                        // the method's own declaration
                        + "- `demo.Calc#own`: stays MEDIUM, not LOW: core/src/main/java/demo/Calc.java is the file of "
                        + "the method's own class, a caller is in another file\n"
                        // a comment, a test that does not name the class, and a longer name that ends like this one
                        + "- `demo.Calc#slashes`: stays MEDIUM, not LOW: core/src/main/java/demo/User.java has no code "
                        + "in line 3\n"
                        + "- `demo.Calc#stranger`: stays MEDIUM, not LOW: the test class OtherTest does not mention "
                        + "Calc\n"
                        + "- `demo.Calc#sub`: stays MEDIUM, not LOW: line 4 of core/src/main/java/demo/User.java does "
                        + "not call or reference sub\n")
                .contains("Raises not taken, asked about again in the next run:\n\n"
                        + "- `demo.Calc#blank`: stays MEDIUM for now, not HIGH: core/src/main/java/demo/User.java has "
                        + "no code in line 7\n"
                        + "- `demo.Calc#readme`: stays MEDIUM for now, not HIGH: README.md is no .java file below a "
                        + "src/main folder\n"
                        + "- `demo.Calc#viatest`: stays MEDIUM for now, not HIGH: a rating is raised on the "
                        + "<path>:<line> of the place that breaks, not on a test\n")
                .contains("17 methods are rated above low. A person decided on 0 of them; 17 are left to decide, 1 of "
                        + "them high:\n\n- `demo.Calc#up`: w\n");
        assertThat(second.out()).contains("register it.grounded: 0 new, 3 resolved, 0 reopened, 17 open now; "
                + "items 3 of 20 settled, 16 judged in this run");

        // The three raises that were not taken and the method without an answer are asked about again, with what
        // changed since their last verdict; the twelve lowerings that were not taken are not
        Result third = run(dir, "--input", "result=calc-second", "--input", "answers=none");

        assertThat(third.code()).as(third.describe()).isZero();
        assertThat(traceworks.folder(third).output("findings")).contains("FACT methods_to_rate: 4 of 20\n");
        assertThat(scratchFile(third, "findings.json")).content()
                .contains("demo.Calc#readme\\n survived: 13 MathMutator, 13 NegateConditionalsMutator\\n not covered: "
                        + "\\n earlier rating: MEDIUM, the reason then: wrong sum\\n changed since: added: survived "
                        + "NegateConditionalsMutator\"");
        assertThat(traceworks.folder(third).output("report"))
                .contains("16 methods kept their rating without being asked")
                .contains("A model was asked about 4: 0 that were never rated, and 4 rated before.")
                .contains("4 methods got no answer.");
        assertThat(third.out()).contains("register it.grounded: 0 new, 0 resolved, 0 reopened, 17 open now; "
                + "items 3 of 20 settled, 0 judged in this run");
    }

    /**
     * The methods are the items of the register, and a report with more than 1000 items is rejected as a whole. So
     * the step that maps the result fails, and the run ends before a model would be asked.
     */
    @Test
    void moreMethodsThanARegisterTakes_failTheFindingsStep() throws IOException {
        Path dir = audit();
        StringBuilder csv = new StringBuilder();
        for (int i = 1; i <= 1001; i++) {
            csv.append("Calc.java,demo.Calc," + MUTATORS + "MathMutator,m" + i + "," + i + ",SURVIVED,none\n");
        }
        write(dir.resolve("pit-many/demo.csv"), csv.toString());

        Result run = run(dir, "--input", "result=many");

        assertThat(run.code()).as(run.describe()).isNotZero();
        assertThat(traceworks.folder(run).stepStatus("findings")).isEqualTo("FAILED");
        // No later step left an output: the report was never written
        assertThat(traceworks.folder(run).ownEvents("step.output").stream()
                .map(event -> event.path("name").asText())).doesNotContain("report");
    }

    /**
     * The package: the scripts of the example, the results of PIT and the sets of answers of the rating; and in the
     * workspace the callers and the tests the grounds name.
     */
    private Path audit() throws IOException {
        Path dir = Files.createDirectories(sandbox.resolve("mutation-testing-grounded"));
        Path scripts = traceworks.examplePackage("mutation-testing-grounded").resolve("scripts");
        Files.createDirectories(dir.resolve("scripts"));
        try (Stream<Path> files = Files.list(scripts)) {
            for (Path script : files.toList()) {
                Files.copy(script, dir.resolve("scripts").resolve(script.getFileName()));
            }
        }
        write(dir.resolve("pit-first/demo.csv"), FIRST_RESULT);
        write(dir.resolve("pit-second/demo.csv"), SECOND_RESULT);
        write(dir.resolve("pit-third/demo.csv"), THIRD_RESULT);
        write(dir.resolve("pit-fourth/demo.csv"), FOURTH_RESULT);
        write(dir.resolve("pit-calc-first/demo.csv"), calc(false));
        write(dir.resolve("pit-calc-second/demo.csv"), calc(true));
        write(dir.resolve("workflow.json"), WORKFLOW);
        Path workspace = traceworks.workspace();
        write(workspace.resolve(MAIN + "Use.java"), USE);
        write(workspace.resolve(MAIN + "Calc.java"), CALC_CLASS);
        write(workspace.resolve(MAIN + "User.java"), USER);
        write(workspace.resolve(TESTS + "GuardTest.java"), GUARD_TEST);
        write(workspace.resolve(TESTS + "ATest.java"), A_TEST);
        write(workspace.resolve(TESTS + "OtherTest.java"), OTHER_TEST);
        write(workspace.resolve("README.md"), "# Demo\npart of the demo\n");
        write(workspace.resolve("target/src/main/java/demo/Gen.java"),
                "class Gen { int g() { return new Calc().gen(1); } }\n");

        // Entries that are no rating stand among the ratings of the first answer
        write(dir.resolve("answers-first.txt"), "Here are the ratings.\n{\"ratings\":[\"no rating\",7,null,"
                + rating("demo.Flow#step", "high", "a wrong result") + ","
                + rating("demo.Flow#quiet", "low", "nothing reads it") + ","
                + rating("demo.Flow#stop", "medium", "the flow does not stop") + ","
                + rating("demo.Other#invented", "high", "no such method was measured") + "]}\n{\"ratings\":["
                + rating("demo.Guard#check", "medium", "a user sees the wrong page") + ","
                + rating("demo.Guard#allow", "medium", "a stranger gets in") + "]}\n");
        // Another severity for four methods, each with a ground that holds: for a run that asks about nothing
        String caller = line(MAIN + "Use.java:3");
        write(dir.resolve("answers-other.txt"), "{\"ratings\":["
                + rating("demo.Flow#step", "low", "moved", caller) + ","
                + rating("demo.Flow#quiet", "high", "moved", caller) + ","
                + rating("demo.Guard#check", "high", "moved", caller) + ","
                + rating("demo.Guard#allow", "high", "moved", caller) + "]}\n");
        // The reason of the new method holds the end and the start of an HTML comment
        write(dir.resolve("answers-second.txt"), "{\"ratings\":["
                + rating("demo.Flow#step", "high", "still a wrong result") + ","
                + rating("demo.Flow#stop", "high", "the caller goes on with a flow that should have ended",
                        line(MAIN + "Use.java:5")) + ","
                + rating("demo.Flow#fresh", "medium", "a stale page} --> <!-- shown") + "]}\n{\"ratings\":["
                + rating("demo.Guard#check", "low", "a test of the guard notices it now",
                        test("demo.GuardTest#refusesAStranger")) + ","
                + rating("demo.Guard#allow", "low", "a test notices it", test("GuardTest#noSuchTest")) + "]}\n");
        write(dir.resolve("answers-third.txt"), "{\"ratings\":["
                + rating("demo.Flow#step", "medium", "the one caller doubles the value and shows it", caller) + ","
                + rating("demo.Flow#stop", "low", "nothing reads it", caller) + ","
                + rating("demo.Guard#check", "medium", "the page of a stranger is shown again",
                        line("/workspace/" + MAIN + "Use.java:5")) + "]}\n");
        write(dir.resolve("answers-fourth.txt"), "{\"ratings\":["
                + rating("demo.Guard#allow", "medium", "a stranger gets in") + "]}\n");
        write(dir.resolve("answers-fifth.txt"), "{\"ratings\":["
                + rating("demo.Guard#allow", "low", "the test of the guard notices it",
                        test("GuardTest#refusesAStranger")) + "]}\n");
        write(dir.resolve("answers-none.txt"), "");

        write(dir.resolve("answers-calc-first.txt"), "{\"ratings\":[" + CALC.stream()
                .map(name -> rating("demo.Calc#" + name, "medium", "wrong sum")).collect(Collectors.joining(","))
                + "]}\n");
        // No answer for silent
        write(dir.resolve("answers-calc-second.txt"), "{\"ratings\":["
                + calc("own", "low", line(MAIN + "Calc.java:3")) + ","
                + calc("of", "low", line("README.md:2")) + ","
                + calc("gen", "low", line("target/src/main/java/demo/Gen.java:1")) + ","
                + calc("intest", "low", line(TESTS + "ATest.java:7")) + ","
                + calc("kw", "low", test("ATest#if")) + ","
                + calc("called", "low", test("ATest#assertEquals")) + ","
                + calc("commented", "low", test("ATest#commented")) + ","
                + calc("slashes", "low", line(MAIN + "User.java:3")) + ","
                + calc("sub", "low", line(MAIN + "User.java:4")) + ","
                + calc("outside", "low", line("../elsewhere/src/main/java/demo/X.java:1")) + ","
                + calc("stranger", "low", test("OtherTest#stranger")) + ","
                + rating("demo.Calc#noground", "low", "w") + "]}\n{\"ratings\":["
                + calc("readme", "high", line("README.md:1")) + ","
                + calc("blank", "high", line(MAIN + "User.java:7")) + ","
                + calc("viatest", "high", test("ATest#real")) + ","
                + calc("ref", "low", line(MAIN + "User.java:5")) + ","
                + calc("<init>", "low", line(MAIN + "User.java:6")) + ","
                + calc("tested", "low", test("demo.ATest#real")) + ","
                + calc("up", "high", line(MAIN + "User.java:8")) + "]}\n");
        return dir;
    }

    /** Lines of PIT's CSV with the package of its mutators before each mutator. */
    private static String pit(String lines) {
        return lines.lines().map(line -> {
            String[] fields = line.split(",", 4);
            return fields[0] + "," + fields[1] + "," + MUTATORS + fields[2] + "," + fields[3] + "\n";
        }).reduce("", String::concat);
    }

    /** A result of PIT for the methods of demo.Calc: one survived change each, or two. */
    private static String calc(boolean changed) {
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i < CALC.size(); i++) {
            String line = "Calc.java,demo.Calc," + MUTATORS + "%s," + CALC.get(i) + "," + (i + 1) + ",SURVIVED,none\n";
            csv.append(line.formatted("MathMutator"));
            if (changed) {
                csv.append(line.formatted("NegateConditionalsMutator"));
            }
        }
        return csv.toString();
    }

    private static String calc(String method, String severity, String ground) {
        return rating("demo.Calc#" + method, severity, "w", ground);
    }

    private static String line(String at) {
        return "{\"line\":\"" + at + "\"}";
    }

    private static String test(String at) {
        return "{\"test\":\"" + at + "\"}";
    }

    private static String rating(String method, String severity, String why) {
        return "{\"method\":\"" + method + "\",\"severity\":\"" + severity + "\",\"why\":\"" + why + "\"}";
    }

    private static String rating(String method, String severity, String why, String ground) {
        return "{\"method\":\"" + method + "\",\"severity\":\"" + severity + "\",\"why\":\"" + why
                + "\",\"ground\":" + ground + "}";
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private Result run(Path dir, String... more) {
        String[] args = Stream.concat(Stream.of("run", dir.toString(), "--workspace", traceworks.workspace().toString(),
                "--no-input"), Stream.of(more)).toArray(String[]::new);
        return traceworks.exec(args);
    }

    /** A file a step of the run left in its scratch directory. */
    private Path scratchFile(Result run, String name) {
        return traceworks.folder(run).dir().resolve("scratch").resolve(name);
    }

    /** A file of the register folder a run was handed: what the register knew when the run started. */
    private Path registerFile(Result run, String name) {
        return traceworks.folder(run).dir().resolve("register").resolve(name);
    }

    private String list() {
        Result list = traceworks.exec("findings", "it.grounded", "list");
        assertThat(list.code()).as(list.describe()).isZero();
        return list.out();
    }

    private void decide(String decision, String subject) {
        String id = list().lines().filter(line -> line.contains(subject)).findFirst().orElseThrow().substring(0, 8);
        Result decided = traceworks.exec("findings", "it.grounded", decision, id, "--note", "looked at it");
        assertThat(decided.code()).as(decided.describe()).isZero();
    }
}
