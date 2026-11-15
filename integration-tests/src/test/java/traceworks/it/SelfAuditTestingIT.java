package traceworks.it;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The testing self-audit ({@code examples/self-audit/testing}) end to end, as it ships: its scripts in the built-in
 * shell image, without network and with a read-only workspace, its {@code verdicts} step against the fake Claude
 * CLI, and the register {@code testing} across two runs: the second run's facts step gets what the register knows
 * ({@code known-findings.txt}). The fake answers with the prompt it got, which holds the
 * answer's template: a finding that quotes no line of the class, so the report lists it as unverified and the
 * register gets the measured findings only.
 */
class SelfAuditTestingIT extends AiIntegrationTest {

    @Test
    void theAudit_measures_asksOncePerClass_checksTheAnswer_andFeedsItsRegister() throws IOException {
        StringBuilder flow = new StringBuilder("package traceworks.core.engine;\n\npublic class Flow {\n");
        for (int i = 0; i < 30; i++) {
            flow.append("    int step").append(i).append("(int a) {\n        if (a > ").append(i)
                    .append(") {\n            return a;\n        }\n        return ").append(i).append(";\n    }\n");
        }
        file("traceworks-core/src/main/java/traceworks/core/engine/Flow.java", flow.append("}\n").toString());
        file("traceworks-core/src/test/java/traceworks/core/engine/FlowTest.java",
                "package traceworks.core.engine;\n\nclass FlowTest {\n    @Test\n    void steps() {\n"
                        + "        assertThat(new Flow().step1(2)).isEqualTo(2);\n    }\n}\n");
        file("traceworks-cli/src/main/java/cli/Commands.java", "package cli;\n\nclass Commands {\n"
                + "    static final Spec RUN = Spec.of(\"run\", \"Runs.\");\n"
                + "    static final Spec WATCH = Spec.of(\"watch\", \"Watches.\");\n}\n");
        file("integration-tests/src/test/java/it/RunIT.java", "package it;\n\nclass RunIT {\n    @Test\n    void runs() {\n"
                + "        assertThat(tool.exec(\"run\", \"pkg\").code()).isZero();\n    }\n}\n");

        Result first = traceworks.runExample("self-audit/testing");

        assertThat(first.code()).as(first.describe()).isZero();
        assertThat(first.out()).contains("YELLOW (testing)")
                .contains("register testing: 1 new, 0 resolved, 0 reopened, 1 open now");
        RunFolder folder = traceworks.folder(first);
        assertThat(folder.output("facts")).contains("## FACT SHEET testing\n")
                .contains("\nHIT command_without_integration_test: watch (traceworks-cli/src/main/java/cli/Commands.java)\n")
                .contains("\nHIT candidate: traceworks-core/src/main/java/traceworks/core/engine/Flow.java (C01, ")
                .doesNotContain("NOT MEASURED");
        assertThat(folder.output("verdicts")).contains("[--tools] [Read,Grep,Glob]")
                .contains("Judge the tests of the class traceworks.core.engine.Flow (C01)")
                .contains("- traceworks-core/src/test/java/traceworks/core/engine/FlowTest.java")
                .contains("\n  4|     int step0(int a) {\n")
                // The register knows nothing yet, so the class has no open finding to answer for
                .contains("and the file and the line it quoted:\n(none)\n");
        assertThat(folder.events("ai.request")).hasSize(1);
        assertThat(folder.output("report")).startsWith("gate_status: YELLOW\ngate_category: testing\ngate_description: "
                        + "1 measured finding; 0 uncaught changes verified (0 high), 1 unverified; 1 of 1 class judged; "
                        + "0 not measured; 0 of 1 item settled (0 clean, 0 decided; 0 open, 1 not judged)\n")
                .contains("## Unverified AI findings (1)")
                .contains("the quoted line is not a line of code of the file; quoted: `<the line of the class you would "
                        + "change, copied exactly>`");

        Result second = traceworks.runExample("self-audit/testing");

        assertThat(second.code()).as(second.describe()).isZero();
        assertThat(second.out()).contains("register testing: 0 new, 0 resolved, 0 reopened, 1 open now");
        // The facts step got what the register knows and sorted it: a measured finding is no question for a model,
        // so the prompt of the class still has no open finding, and the row is not in it
        assertThat(traceworks.folder(second).output("verdicts"))
                .contains("and the file and the line it quoted:\n(none)\n")
                .doesNotContain("command-without-integration-test | watch");
        assertThat(traceworks.folder(second).dir().resolve("scratch/known-findings.txt")).content()
                .contains("OPEN | MEDIUM | command-without-integration-test | watch | no integration test runs the "
                        + "command \"watch\" | Evidence: command_without_integration_test: watch "
                        + "(traceworks-cli/src/main/java/cli/Commands.java)");
        Result list = traceworks.exec("findings", "testing", "list");
        assertThat(list.out()).contains("command-without-integration-test  watch").doesNotContain("unverified");
        assertThat(traceworks.exec("findings", "testing", "verify").code()).isZero();
    }

    private void file(String name, String content) throws IOException {
        Path path = traceworks.workspace().resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
