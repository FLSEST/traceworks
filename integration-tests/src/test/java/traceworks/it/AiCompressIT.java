package traceworks.it;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code compress-ai} against the fake Claude (package {@code ai-compress}, docs/cli.md "AI steps"): one AI call that
 * has no tools and gets the source text and the instruction; the fake's answer, under a header, is the step's output.
 */
class AiCompressIT extends AiIntegrationTest {

    /** About the one call of compress-ai, not about the network: no proxy and no network per run. */
    @Override
    boolean behindTheEgressProxy() {
        return false;
    }

    @Test
    void oneCallWithoutTools_condensesTheSource() {
        Result run = traceworks.run("ai-compress");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        int index = folder.stepIndex("short");
        assertThat(folder.stepStatus("short")).isEqualTo("COMPLETED");
        String output = folder.output("short");
        assertThat(output).contains("Summary: AI (CLAUDE)", "fake-claude answered");
        assertThat(folder.ofStep("ai.request", index)).hasSize(1);
        assertThat(folder.ofStep("ai.request", index).getFirst().path("tools").asText()).isEqualTo("none");
        assertThat(folder.blob(folder.ofStep("ai.request", index).getFirst().path("prompt")))
                .contains("ERROR disk full", "Keep the errors");
        assertThat(output).contains("[--tools] []");
    }
}
