package traceworks.it;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scripts of the testing audit ({@code examples/audit/testing/scripts}) as they ship, in the built-in shell
 * image: {@code findings.sh} on a fixed result of PIT and {@code report.sh} on fixed answers of the rating, in a
 * package that leaves out the steps that measure and rate. The register is real, so the runs show what a decision
 * of a person does to the gate and to what is rated again. The scripts learn what the register knew from the register
 * folder of the run ({@code {{ register_dir }}}), which is {@code register/} in the run's folder.
 */
class TestingAuditScriptsIT extends IntegrationTest {

    /** Four mutants in three methods: a nested class counts for the class of its file, a lambda for its method. */
    private static final String PIT = """
            Flow.java,demo.Flow,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,step,10,SURVIVED,none
            Flow.java,demo.Flow,org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator,step,11,KILLED,demo.FlowTest
            Flow.java,demo.Flow$Inner,org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator,lambda$quiet$0,20,SURVIVED,none
            Guard.java,demo.Guard,org.pitest.mutationtest.engine.gregor.mutators.returns.BooleanTrueReturnValsMutator,check,5,NO_COVERAGE,none
            """;

    private static final String WORKFLOW = """
            {
              "format": "traceworks-workflow",
              "format_version": 1,
              "id": "5f0c7b1e-2a4d-4c3b-9e8f-6d1a2b3c4d5e",
              "name": "IT testing audit scripts",
              "metadata": {
                "gate": {"expected": true},
                "audit": {"enabled": true, "key": "it.testing", "category": "testing", "report_block": "report"}
              },
              "default_context": {"answers": "first", "report": "report"},
              "blocks": [
                {
                  "type": "shell",
                  "name": "findings",
                  "config": {
                    "command": "set -euo pipefail; cp -r {{ package_dir }}/pit {{ scratch_dir }}/pit; bash {{ package_dir }}/scripts/findings.sh {{ scratch_dir }} {{ register_dir }}",
                    "shell_escape_variables": true
                  }
                },
                {
                  "type": "shell",
                  "name": "report",
                  "config": {
                    "command": "set -euo pipefail; bash {{ package_dir }}/scripts/{{ report }}.sh {{ scratch_dir }} < {{ package_dir }}/answers-{{ answers }}.txt",
                    "shell_escape_variables": true
                  }
                }
              ]
            }
            """;

    @Test
    void theGateCountsWhatIsLeftToDecide_andADecisionCoversTheSeverityItWasMadeOn() throws IOException {
        Path dir = audit();

        Result first = run(dir);

        assertThat(first.code()).as(first.describe()).isZero();
        assertThat(first.out()).contains("RED (testing)")
                .contains("register it.testing: 2 new, 0 resolved, 0 reopened, 2 open now");
        assertThat(traceworks.folder(first).output("findings")).contains("FACT mutants: 4\n")
                .contains("FACT survived: 2\n").contains("FACT not_covered: 1\n")
                .contains("FACT packages_to_rate: 1 of 1\n")
                .contains("HIT undetected: demo.Flow#step (1 survived, 0 not covered, of 2)\n")
                .contains("HIT undetected: demo.Flow#quiet (1 survived, 0 not covered, of 1)\n")
                .contains("HIT undetected: demo.Guard#check (0 survived, 1 not covered, of 1)");
        assertThat(traceworks.folder(first).output("report"))
                .contains("2 methods are rated above low. A person decided on 0 of them; 2 are left to decide, 1 of "
                        + "them high:\n\n- `demo.Flow#step`: a wrong result\n")
                .contains("gate_description: 3 of 4 changes went unnoticed, in 3 methods, 2 of them rated above low; "
                        + "2 left to decide, 1 of them high\n");
        // The method rated low is counted and is no finding
        assertThat(list()).contains("HIGH      undetected-mutants  demo.Flow#step")
                .contains("MEDIUM    undetected-mutants  demo.Guard#check").doesNotContain("demo.Flow#quiet");

        decide("accept", "demo.Flow#step");
        Result second = run(dir);

        assertThat(second.code()).as(second.describe()).isZero();
        // The decision is part of what a rating sees, so the package is rated again; the accepted method stays a
        // finding of the report and no longer counts
        assertThat(second.out()).contains("YELLOW (testing)")
                .contains("register it.testing: 0 new, 0 resolved, 0 reopened, 1 open now");
        assertThat(traceworks.folder(second).output("findings")).contains("FACT packages_to_rate: 1 of 1\n");
        assertThat(traceworks.folder(second).output("report"))
                .contains("A person decided on 1 of them; 1 are left to decide, 0 of them high:")
                .contains("2 of them rated above low; 1 left to decide, 0 of them high\n");

        decide("dismiss", "demo.Guard#check");
        Result third = run(dir);

        assertThat(third.code()).as(third.describe()).isZero();
        assertThat(third.out()).contains("GREEN (testing)")
                .contains("register it.testing: 0 new, 0 resolved, 0 reopened, 0 open now");
        assertThat(traceworks.folder(third).output("report"))
                .contains("2 of them rated above low; 0 left to decide, 0 of them high\n");

        Result fourth = run(dir);

        // Nothing changed since the package was rated: it is not rated again, and its methods keep their rating
        assertThat(fourth.out()).contains("GREEN (testing)");
        assertThat(traceworks.folder(fourth).output("findings")).contains("FACT packages_to_rate: 0 of 1\n");

        Result fifth = run(dir, "--input", "answers=second");

        // What the fourth run, which rated nothing, left in the register: the reason of each rating is still the
        // first line of the description, and the evidence is the fact alone
        assertThat(registerFile(fifth, "findings.jsonl")).content()
                .contains("\"description\":\"Rating: a wrong result\\nSurvived (a test runs the line")
                .contains("\"evidence\":\"undetected: demo.Flow#step (1 survived, 0 not covered, of 2)\"")
                .contains("\"description\":\"Rating: a user sees the wrong page\\nSurvived (a test runs the line")
                .doesNotContain("Rated:");
        // The findings of this audit name no item, so a package that was rated as it is now is clean
        assertThat(registerFile(fifth, "items.jsonl")).content().contains("\"name\":\"demo\",\"state\":\"clean\"");

        // Dismissed as MEDIUM and rated HIGH now: the decision does not cover it, here and in the register. (The
        // report takes an answer for a method whatever its package; the rating loop asks only about a package
        // that changed.)
        assertThat(fifth.code()).as(fifth.describe()).isZero();
        assertThat(fifth.out()).contains("RED (testing)")
                .contains("register it.testing: 0 new, 0 resolved, 1 reopened, 1 open now");
        assertThat(traceworks.folder(fifth).output("report"))
                .contains("A person decided on 1 of them; 1 are left to decide, 1 of them high:\n\n"
                        + "- `demo.Guard#check`: the check lets everything through\n");

        decide("dismiss", "demo.Guard#check");
        Result sixth = run(dir);
        Result seventh = run(dir, "--input", "answers=second");

        // Dismissed as HIGH, then rated MEDIUM, then HIGH again: the finding names what the decision was made on,
        // and the decision still covers the method
        assertThat(sixth.out()).contains("GREEN (testing)");
        assertThat(registerFile(seventh, "findings.jsonl")).content()
                .contains("\"kind\":\"DISMISSED\",\"note\":\"looked at it\",\"severity\":\"HIGH\"")
                .contains("\"severity\":\"MEDIUM\",\"status\":\"DISMISSED\",\"subject\":\"demo.Guard#check\"");
        assertThat(seventh.code()).as(seventh.describe()).isZero();
        assertThat(seventh.out()).contains("GREEN (testing)")
                .contains("register it.testing: 0 new, 0 resolved, 0 reopened, 0 open now");
        assertThat(traceworks.folder(seventh).output("report"))
                .contains("A person decided on 2 of them; 0 are left to decide, 0 of them high:");
        assertThat(traceworks.exec("findings", "it.testing", "verify").code()).isZero();
    }

    /**
     * Where the operator lets a decision stand at a higher severity, the register keeps the finding decided, and
     * the gate follows it from the next run on: the report sees the register as it was before its run.
     */
    @Test
    void aDecisionTheRegisterKeepsAtAHigherSeverity_coversThatSeverityFromTheNextRunOn() throws IOException {
        traceworks.configure(Map.of("decision_on_higher_severity", "keep"));
        Path dir = audit();
        assertThat(run(dir).out()).contains("RED (testing)");
        decide("accept", "demo.Flow#step");
        decide("dismiss", "demo.Guard#check");

        Result raised = run(dir, "--input", "answers=second");

        assertThat(raised.code()).as(raised.describe()).isZero();
        assertThat(raised.out()).contains("RED (testing)")
                .contains("register it.testing: 0 new, 0 resolved, 0 reopened, 0 open now");

        Result next = run(dir, "--input", "answers=second");

        assertThat(next.code()).as(next.describe()).isZero();
        assertThat(registerFile(next, "findings.jsonl")).content()
                .contains("\"kind\":\"DISMISSED\",\"note\":\"looked at it\",\"severity\":\"MEDIUM\"")
                .contains("\"severity\":\"HIGH\",\"status\":\"DISMISSED\",\"subject\":\"demo.Guard#check\"");
        assertThat(next.out()).contains("GREEN (testing)");
        assertThat(traceworks.folder(next).output("report"))
                .contains("A person decided on 2 of them; 0 are left to decide, 0 of them high:");
    }

    /**
     * A register that the version before this one wrote holds the reason of a rating at the end of the evidence
     * ({@code . Rated: ...}) and none in the description. The first run on it rates nothing again and keeps the
     * rating with its reason, now in the description. And a reason is a model's text: one with the end of an HTML
     * comment in it does not cut the envelope, and an entry of an answer that is no rating is left out.
     */
    @Test
    void aRegisterOfTheVersionBeforeKeepsItsRatings_andAReasonCannotCutTheEnvelope() throws IOException {
        Path dir = audit();
        // The report of the version before, for the package as findings.sh sees it now
        Files.writeString(dir.resolve("scripts/before.sh"), """
                #!/usr/bin/env bash
                set -euo pipefail
                cat > /dev/null
                digest=$(cut -f2 "$1/digests.tsv")
                echo "gate_status: YELLOW"
                echo "<!-- traceworks:audit-result:v1 {\\"snapshotComplete\\":true,\\"expectedFindings\\":1,\\"findings\\":[\
                {\\"ruleKey\\":\\"undetected-mutants\\",\\"subject\\":\\"demo.Guard#check\\",\\"severity\\":\\"MEDIUM\\",\
                \\"title\\":\\"1 of 1 changes of Guard#check go unnoticed by the tests\\",\
                \\"description\\":\\"Not covered (no test runs the line): 5 BooleanTrueReturnValsMutator\\",\
                \\"evidence\\":\\"undetected: demo.Guard#check (0 survived, 1 not covered, of 1). Rated: a user sees the wrong page\\"}],\
                \\"expectedItems\\":1,\\"items\\":[{\\"name\\":\\"demo\\",\\"digest\\":\\"$digest\\",\\"judged\\":true}]} -->"
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("answers-none.txt"), "", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("answers-hostile.txt"), "{\"ratings\":[\"no rating\",7,null,"
                + "{\"method\":\"demo.Guard#check\",\"severity\":\"high\",\"why\":\"x} --> <!-- y\"}]}\n",
                StandardCharsets.UTF_8);
        Result before = run(dir, "--input", "report=before");
        assertThat(before.code()).as(before.describe()).isZero();
        assertThat(before.out()).contains("register it.testing: 1 new, 0 resolved, 0 reopened, 1 open now");

        Result first = run(dir, "--input", "answers=none");

        assertThat(first.code()).as(first.describe()).isZero();
        assertThat(registerFile(first, "findings.jsonl")).content()
                .contains("\"description\":\"Not covered (no test runs the line)").contains(". Rated: a user sees");
        assertThat(traceworks.folder(first).output("findings")).contains("FACT packages_to_rate: 0 of 1\n");
        assertThat(traceworks.folder(first).output("report"))
                .contains("A model rated 0 of them in this run, in 0 of 1 packages; the others keep their rating. 1 "
                        + "methods are rated above low. A person decided on 0 of them; 1 are left to decide, 0 of them "
                        + "high:");
        assertThat(first.out()).contains("YELLOW (testing)")
                .contains("register it.testing: 0 new, 0 resolved, 0 reopened, 1 open now");

        Result hostile = run(dir, "--input", "answers=hostile");

        // What the first run wrote: the reason is the first line of the description, the evidence the fact alone
        assertThat(registerFile(hostile, "findings.jsonl")).content()
                .contains("\"description\":\"Rating: a user sees the wrong page\\nSurvived (a test runs the line")
                .contains("\"evidence\":\"undetected: demo.Guard#check (0 survived, 1 not covered, of 1)\"")
                .doesNotContain("Rated:");
        assertThat(hostile.code()).as(hostile.describe()).isZero();
        assertThat(traceworks.folder(hostile).output("report")).contains("- `demo.Guard#check`: x} --> <!-- y\n");
        assertThat(hostile.out()).contains("RED (testing)")
                .contains("register it.testing: 0 new, 0 resolved, 0 reopened, 1 open now");

        Result after = run(dir, "--input", "answers=none");

        // The register took the report with the hostile reason, and holds the reason as it was written (its file
        // writes < as \\u003c)
        assertThat(registerFile(after, "findings.jsonl")).content()
                .contains("\"description\":\"Rating: x} --> \\u003c!-- y\\nSurvived (a test runs the line")
                .contains("\"severity\":\"HIGH\",\"status\":\"OPEN\",\"subject\":\"demo.Guard#check\"");
        assertThat(traceworks.exec("findings", "it.testing", "verify").code()).isZero();
    }

    /** The package: the scripts of the example, the result of PIT and two sets of answers of the rating. */
    private Path audit() throws IOException {
        Path dir = Files.createDirectories(sandbox.resolve("testing-audit"));
        Path scripts = traceworks.examplePackage("audit/testing").resolve("scripts");
        Files.createDirectories(dir.resolve("scripts"));
        try (Stream<Path> files = Files.list(scripts)) {
            for (Path script : files.toList()) {
                Files.copy(script, dir.resolve("scripts").resolve(script.getFileName()));
            }
        }
        Files.createDirectories(dir.resolve("pit"));
        Files.writeString(dir.resolve("pit/demo.csv"), PIT, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("workflow.json"), WORKFLOW, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("answers-first.txt"), "Here are the ratings.\n{\"ratings\":["
                + "{\"method\":\"demo.Flow#step\",\"severity\":\"high\",\"why\":\"a wrong result\"},"
                + "{\"method\":\"demo.Guard#check\",\"severity\":\"medium\",\"why\":\"a user sees the wrong page\"},"
                + "{\"method\":\"demo.Flow#quiet\",\"severity\":\"low\",\"why\":\"nothing reads it\"},"
                + "{\"method\":\"demo.Other#invented\",\"severity\":\"high\",\"why\":\"no such method was measured\"}]}\n",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("answers-second.txt"), "{\"ratings\":["
                + "{\"method\":\"demo.Guard#check\",\"severity\":\"high\",\"why\":\"the check lets everything through\"}]}\n",
                StandardCharsets.UTF_8);
        return dir;
    }

    private Result run(Path dir, String... more) {
        String[] args = Stream.concat(Stream.of("run", dir.toString(), "--workspace", traceworks.workspace().toString(),
                "--no-input"), Stream.of(more)).toArray(String[]::new);
        return traceworks.exec(args);
    }

    /** A file of the register folder a run was handed: what the register knew when the run started. */
    private Path registerFile(Result run, String name) {
        return traceworks.folder(run).dir().resolve("register").resolve(name);
    }

    private String list() {
        Result list = traceworks.exec("findings", "it.testing", "list");
        assertThat(list.code()).as(list.describe()).isZero();
        return list.out();
    }

    private void decide(String decision, String subject) {
        String id = list().lines().filter(line -> line.contains(subject)).findFirst().orElseThrow().substring(0, 8);
        Result decided = traceworks.exec("findings", "it.testing", decision, id, "--note", "looked at it");
        assertThat(decided.code()).as(decided.describe()).isZero();
    }
}
