package traceworks.core.context;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionContextTaintTest {

    private final ExecutionContextTaint taint = new ExecutionContextTaint();

    @Test
    void cleanRun_hasNoUntrustedInput_andExportsNothing() {
        assertThat(taint.hasUntrustedInput()).isFalse();
        assertThat(taint.export()).isEmpty();
        assertThat(taint.snapshot()).isSameAs(TemplateResolver.Taint.NONE);
    }

    @Test
    void externalVariable_taintsImmediately_andIsExported() {
        taint.markUntrusted("payload");

        assertThat(taint.isUntrusted("payload")).isTrue();
        assertThat(taint.hasUntrustedInput()).isTrue();
        assertThat(taint.untrustedVariables()).containsExactly("payload");
        assertThat(taint.export()).containsEntry(ExecutionContext.UNTRUSTED_KEYS_VARIABLE, List.of("payload"));
    }

    @Test
    void platformVariable_isEscapedButTaintsOnlyWhenSubstitutedIntoShell() {
        taint.markPlatform("open_todos");

        assertThat(taint.isUntrusted("open_todos")).isTrue();
        assertThat(taint.snapshot().variables()).containsExactly("open_todos");
        assertThat(taint.hasUntrustedInput()).isFalse();
        assertThat(taint.untrustedVariables()).isEmpty();

        taint.substitutedIntoShell("open_todos");

        assertThat(taint.hasUntrustedInput()).isTrue();
        assertThat(taint.export()).containsEntry(ExecutionContext.PLATFORM_IN_SHELL_VARIABLE, true);
    }

    @Test
    void substitutedIntoShell_ignoresTrustedAndExternalKeys() {
        taint.markUntrusted("payload");
        taint.substitutedIntoShell("payload");
        taint.substitutedIntoShell("trusted");

        assertThat(taint.export()).doesNotContainKey(ExecutionContext.PLATFORM_IN_SHELL_VARIABLE);
    }

    /** Text a model wrote is quoted and handed on, and the run stays one without untrusted input. */
    @Test
    void modelText_isQuoted_exportedAndKept_andIsNoUntrustedInput() {
        taint.markModelAsked();
        taint.markModelOutput("ask");
        taint.markModelVariable("step_0_output");

        assertThat(taint.modelAsked()).isTrue();
        assertThat(taint.hasUntrustedInput()).isFalse();
        assertThat(taint.isUntrusted("step_0_output")).isFalse();
        assertThat(taint.untrustedVariables()).isEmpty();
        assertThat(taint.snapshot()).isEqualTo(
                new TemplateResolver.Taint(java.util.Set.of("step_0_output"), java.util.Set.of("ask"), true));
        assertThat(taint.untrustedSnapshot()).isSameAs(TemplateResolver.Taint.NONE);
        assertThat(taint.export()).containsOnly(
                java.util.Map.entry(ExecutionContext.MODEL_ASKED_VARIABLE, true),
                java.util.Map.entry(ExecutionContext.MODEL_KEYS_VARIABLE, List.of("step_0_output")),
                java.util.Map.entry(ExecutionContext.MODEL_OUTPUTS_VARIABLE, List.of("ask", "previous_output")));

        ExecutionContextTaint restored = new ExecutionContextTaint();
        restored.restore(taint.state());
        assertThat(restored.state()).isEqualTo(taint.state());
        assertThat(restored.snapshot()).isEqualTo(taint.snapshot());
    }

    /** The last label of a variable holds: set as untrusted, or as trusted, it is no model text any more. */
    @Test
    void aModelVariable_setAgain_takesTheNewLabel() {
        taint.markModelVariable("v");
        taint.markUntrusted("v");
        assertThat(taint.state().modelText().variables()).isEmpty();
        assertThat(taint.untrustedVariables()).containsExactly("v");

        taint.markModelVariable("v");
        assertThat(taint.untrustedVariables()).isEmpty();
        taint.markTrusted("v");
        assertThat(taint.snapshot()).isSameAs(TemplateResolver.Taint.NONE);
    }

    @Test
    void markTrusted_clearsEarlierLabel() {
        taint.markPlatform("v");
        taint.markTrusted("v");
        taint.substitutedIntoShell("v");

        assertThat(taint.isUntrusted("v")).isFalse();
        assertThat(taint.hasUntrustedInput()).isFalse();
    }

    @Test
    void platformInPrompt_isConsumedOnce_andDoesNotTaintTheRun() {
        taint.markPlatform("previous_report");
        taint.substitutedIntoPrompt("other");
        assertThat(taint.consumePlatformPrompt()).isFalse();

        taint.substitutedIntoPrompt("previous_report");

        assertThat(taint.hasUntrustedInput()).isFalse();
        assertThat(taint.consumePlatformPrompt()).isTrue();
        assertThat(taint.consumePlatformPrompt()).isFalse();
        assertThat(taint.export()).isEmpty();
    }

    @Test
    void markedOutputs_taintTheRun_andAppearInSnapshotAndExport() {
        taint.markOutput("build");
        taint.markBlockOutput("lint");

        assertThat(taint.hasUntrustedInput()).isTrue();
        assertThat(taint.snapshot().outputs()).containsExactlyInAnyOrder("build", "lint");
        assertThat(taint.snapshot().previousOutput()).isTrue();
        assertThat(taint.export().get(ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE))
                .isEqualTo(List.of("build", "lint", ExecutionContext.PREVIOUS_OUTPUT_NAME));
    }

    @Test
    void markPreviousOutput_alone_taints() {
        taint.markPreviousOutput();

        assertThat(taint.hasUntrustedInput()).isTrue();
        assertThat(taint.export().get(ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE))
                .isEqualTo(List.of(ExecutionContext.PREVIOUS_OUTPUT_NAME));
    }

    @Test
    void reappliedPlatformFlag_taintsResumedSegment() {
        taint.markPlatformUsedInShell();

        assertThat(taint.hasUntrustedInput()).isTrue();
        assertThat(taint.export()).containsEntry(ExecutionContext.PLATFORM_IN_SHELL_VARIABLE, true);
    }
}
