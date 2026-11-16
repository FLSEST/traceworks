package traceworks.it;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scripts of the testing audit that rates again from the earlier verdict
 * ({@code examples/audit/testing-again/scripts}) as they ship, in the built-in shell image: {@code findings.sh} on
 * fixed results of PIT that change between the runs and {@code report.sh} on fixed answers of the rating, in a
 * package that leaves out the steps that measure and rate. The register is real, and the workspace holds the files
 * the grounds of the answers point to. The scripts learn what the register knew from the register folder of the run
 * ({@code {{ register_dir }}}).
 */
class TestingAgainScriptsIT extends IntegrationTest {

    private static final String MUTATORS = "org.pitest.mutationtest.engine.gregor.mutators.";

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

    /** The file the line grounds point to: line 3 names step, line 4 is blank, line 5 names neither step nor stop. */
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
                }
            }
            """;

    private static final String WORKFLOW = """
            {
              "format": "traceworks-workflow",
              "format_version": 1,
              "id": "8c2f4e7a-1b3d-4a5c-9e6f-7d8a9b0c1d2e",
              "name": "IT testing audit that rates again, scripts",
              "metadata": {
                "gate": {"expected": true},
                "audit": {"enabled": true, "key": "it.testing-again", "category": "testing", "report_block": "report"}
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

    @Test
    void aRatingStaysUntilAGroundTheWorkspaceHoldsSpeaksAgainstIt() throws IOException {
        Path dir = audit();

        // Nothing is known: every method is rated fresh, and an answer for a method nobody measured counts for nothing
        Result first = run(dir);

        assertThat(first.code()).as(first.describe()).isZero();
        assertThat(first.out()).contains("RED (testing)").contains("register it.testing-again: 4 new, 0 resolved, "
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
                .doesNotContain("Changes taken:").doesNotContain("Changes not taken:");
        // The method rated low is counted and is no finding
        assertThat(list()).contains("HIGH      undetected-mutants  demo.Flow#step")
                .contains("MEDIUM    undetected-mutants  demo.Guard#check")
                .contains("MEDIUM    undetected-mutants  demo.Guard#allow")
                .contains("MEDIUM    undetected-mutants  demo.Flow#stop").doesNotContain("demo.Flow#quiet");

        // The same result of PIT: no method is asked about, and answers that would move every rating count for nothing
        Result second = run(dir, "--input", "answers=other");

        assertThat(second.code()).as(second.describe()).isZero();
        assertThat(second.out()).contains("RED (testing)").contains("register it.testing-again: 0 new, 0 resolved, "
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
        // step keeps its rating because the answer keeps it. stop is raised on a line that exists, check is lowered
        // on a test that exists. allow is not lowered: the test class has no such method, so MEDIUM stays
        assertThat(traceworks.folder(third).output("report"))
                .contains("1 methods kept their rating without being asked")
                .contains("A model was asked about 5: 1 that were never rated, and 4 rated before. Of those rated "
                        + "before, 1 kept their rating, 2 changes were taken on a ground that holds, and 1 changes "
                        + "were not taken. 0 methods got no answer and are asked about again.")
                .contains("Changes taken:\n\n"
                        + "- `demo.Flow#stop`: MEDIUM to HIGH on line core/src/main/java/demo/Use.java:5: the caller "
                        + "goes on with a flow that should have ended\n"
                        + "- `demo.Guard#check`: MEDIUM to LOW on test demo.GuardTest#refusesAStranger: a test of the "
                        + "guard notices it now\n")
                .contains("Changes not taken:\n\n"
                        + "- `demo.Guard#allow`: stays MEDIUM, not LOW: the test class GuardTest has no method "
                        + "noSuchTest\n")
                .contains("4 methods are rated above low. A person decided on 0 of them; 4 are left to decide, 2 of "
                        + "them high:\n\n- `demo.Flow#step`: still a wrong result\n- `demo.Flow#stop`: the caller goes "
                        + "on with a flow that should have ended\n");
        // The lowered method left the findings and the register resolved it; the new method is a finding
        assertThat(third.out()).contains("RED (testing)").contains("register it.testing-again: 1 new, 1 resolved, "
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
                .doesNotContain("demo.Guard#check");
        assertThat(traceworks.folder(fourth).output("findings")).contains("FACT methods_to_rate: 3 of 6\n");
        assertThat(scratchFile(fourth, "findings.json")).content()
                .contains(" earlier rating: LOW. Before that it was MEDIUM, the reason then: a user sees the wrong "
                        + "page\\n changed since: its gaps or the decision on it; the kinds of its gaps then were not "
                        + "kept\"")
                .contains(" changed since: went away: not covered VoidMethodCallMutator\\n ground of the last change: "
                        + "line core/src/main/java/demo/Use.java:5\"");
        // step is lowered on the line of its caller, which names it. stop is not lowered: the line does not name
        // it. check is raised from LOW on a line that exists, and is a finding again
        assertThat(traceworks.folder(fourth).output("report"))
                .contains("3 methods kept their rating without being asked")
                .contains("A model was asked about 3: 0 that were never rated, and 3 rated before. Of those rated "
                        + "before, 0 kept their rating, 2 changes were taken on a ground that holds, and 1 changes "
                        + "were not taken.")
                .contains("Changes taken:\n\n"
                        + "- `demo.Flow#step`: HIGH to MEDIUM on line core/src/main/java/demo/Use.java:3: the one "
                        + "caller doubles the value and shows it\n"
                        + "- `demo.Guard#check`: LOW to MEDIUM on line /workspace/core/src/main/java/demo/Use.java:5: "
                        + "the page of a stranger is shown again\n")
                .contains("Changes not taken:\n\n"
                        + "- `demo.Flow#stop`: stays HIGH, not LOW: line 3 of core/src/main/java/demo/Use.java does "
                        + "not name stop\n")
                .contains("5 methods are rated above low. A person decided on 0 of them; 5 are left to decide, 1 of "
                        + "them high:");
        assertThat(fourth.out()).contains("RED (testing)").contains("register it.testing-again: 0 new, 0 resolved, "
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
        assertThat(fifth.out()).contains("register it.testing-again: 0 new, 0 resolved, 0 reopened, 4 open now; "
                + "items 2 of 6 settled, 1 judged in this run");

        // And then it rests: nothing changed, nothing is asked, and the decision still covers the method
        Result sixth = run(dir, "--input", "result=third", "--input", "answers=other");

        assertThat(sixth.code()).as(sixth.describe()).isZero();
        assertThat(traceworks.folder(sixth).output("findings")).contains("FACT methods_to_rate: 0 of 6\n");
        assertThat(traceworks.folder(sixth).output("report"))
                .contains("6 methods kept their rating without being asked")
                .contains("A person decided on 1 of them; 4 are left to decide, 1 of them high:\n\n"
                        + "- `demo.Flow#stop`: the caller goes on with a flow that should have ended\n");
        assertThat(sixth.out()).contains("RED (testing)").contains("register it.testing-again: 0 new, 0 resolved, "
                + "0 reopened, 4 open now; items 2 of 6 settled, 0 judged in this run");
        assertThat(traceworks.exec("findings", "it.testing-again", "verify").code()).isZero();
    }

    /**
     * The package: the scripts of the example, three results of PIT and the sets of answers of the rating; and in
     * the workspace the caller and the test the grounds name.
     */
    private Path audit() throws IOException {
        Path dir = Files.createDirectories(sandbox.resolve("testing-again"));
        Path scripts = traceworks.examplePackage("audit/testing-again").resolve("scripts");
        Files.createDirectories(dir.resolve("scripts"));
        try (Stream<Path> files = Files.list(scripts)) {
            for (Path script : files.toList()) {
                Files.copy(script, dir.resolve("scripts").resolve(script.getFileName()));
            }
        }
        write(dir.resolve("pit-first/demo.csv"), FIRST_RESULT);
        write(dir.resolve("pit-second/demo.csv"), SECOND_RESULT);
        write(dir.resolve("pit-third/demo.csv"), THIRD_RESULT);
        write(dir.resolve("workflow.json"), WORKFLOW);
        write(traceworks.workspace().resolve("core/src/main/java/demo/Use.java"), USE);
        write(traceworks.workspace().resolve("core/src/test/java/demo/GuardTest.java"), GUARD_TEST);
        write(dir.resolve("answers-first.txt"), "Here are the ratings.\n{\"ratings\":["
                + rating("demo.Flow#step", "high", "a wrong result") + ","
                + rating("demo.Flow#quiet", "low", "nothing reads it") + ","
                + rating("demo.Flow#stop", "medium", "the flow does not stop") + ","
                + rating("demo.Other#invented", "high", "no such method was measured") + "]}\n{\"ratings\":["
                + rating("demo.Guard#check", "medium", "a user sees the wrong page") + ","
                + rating("demo.Guard#allow", "medium", "a stranger gets in") + "]}\n");
        // Another severity for three methods, each with a ground that holds: for a run that asks about nothing
        String caller = "{\"line\":\"core/src/main/java/demo/Use.java:3\"}";
        write(dir.resolve("answers-other.txt"), "{\"ratings\":["
                + rating("demo.Flow#step", "low", "moved", caller) + ","
                + rating("demo.Flow#quiet", "high", "moved", caller) + ","
                + rating("demo.Guard#check", "high", "moved", caller) + "]}\n");
        write(dir.resolve("answers-second.txt"), "{\"ratings\":["
                + rating("demo.Flow#step", "high", "still a wrong result") + ","
                + rating("demo.Flow#stop", "high", "the caller goes on with a flow that should have ended",
                        "{\"line\":\"core/src/main/java/demo/Use.java:5\"}") + ","
                + rating("demo.Flow#fresh", "medium", "a user sees a stale page") + "]}\n{\"ratings\":["
                + rating("demo.Guard#check", "low", "a test of the guard notices it now",
                        "{\"test\":\"demo.GuardTest#refusesAStranger\"}") + ","
                + rating("demo.Guard#allow", "low", "a test notices it", "{\"test\":\"GuardTest#noSuchTest\"}")
                + "]}\n");
        write(dir.resolve("answers-third.txt"), "{\"ratings\":["
                + rating("demo.Flow#step", "medium", "the one caller doubles the value and shows it", caller) + ","
                + rating("demo.Flow#stop", "low", "nothing reads it", caller) + ","
                + rating("demo.Guard#check", "medium", "the page of a stranger is shown again",
                        "{\"line\":\"/workspace/core/src/main/java/demo/Use.java:5\"}") + "]}\n");
        write(dir.resolve("answers-fourth.txt"), "{\"ratings\":["
                + rating("demo.Guard#allow", "medium", "a stranger gets in") + "]}\n");
        return dir;
    }

    /** Lines of PIT's CSV with the package of its mutators before each mutator. */
    private static String pit(String lines) {
        return lines.lines().map(line -> {
            String[] fields = line.split(",", 4);
            return fields[0] + "," + fields[1] + "," + MUTATORS + fields[2] + "," + fields[3] + "\n";
        }).reduce("", String::concat);
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
        Result list = traceworks.exec("findings", "it.testing-again", "list");
        assertThat(list.code()).as(list.describe()).isZero();
        return list.out();
    }

    private void decide(String decision, String subject) {
        String id = list().lines().filter(line -> line.contains(subject)).findFirst().orElseThrow().substring(0, 8);
        Result decided = traceworks.exec("findings", "it.testing-again", decision, id, "--note", "looked at it");
        assertThat(decided.code()).as(decided.describe()).isZero();
    }
}
