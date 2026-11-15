package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ai_images} in {@code traceworks.json} (docs/cli.md "AI images", docs/security.md, docs/events.md
 * {@code run.started}), which every test of {@link AiIntegrationTest} relies on, and the start check of the AI blocks:
 * the AI call runs in the operator's image, the record and a stderr note say so, a missing operator image is refused
 * with a hint that fits, a workflow cannot set the key, and a workflow that names Codex is refused.
 */
class AiImagesIT extends AiIntegrationTest {

    /** About the images the AI calls run in, not about the network: no proxy and no network per run. */
    @Override
    boolean behindTheEgressProxy() {
        return false;
    }

    @Test
    void theAiCallsRunInTheOperatorsImages_andTheRecordAndStderrSayso() {
        Result run = traceworks.run("ai-simple");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.only("run.started").path("ai_images")).hasSize(2);
        assertThat(folder.only("run.started").path("ai_images").path("claude").asText()).isEqualTo(fakeClaude());
        assertThat(folder.only("run.started").path("ai_images").path("pi").asText()).isEqualTo(fakePi());
        for (String[] step : new String[][] {{"claude_say", fakeClaude()}, {"pi_say", fakePi()}}) {
            JsonNode command = folder.ofStep("step.command", folder.stepIndex(step[0])).getFirst();
            assertThat(command.path("image").asText()).as(step[0]).isEqualTo(step[1]);
            assertThat(command.path("image_id").asText()).as(step[0])
                    .isEqualTo(traceworks.environment().imageId(step[1]));
        }
        assertThat(run.err()).contains("traceworks: note: ai_images: claude runs in " + fakeClaude()
                + " instead of the built-in image", "traceworks: note: ai_images: pi runs in " + fakePi()
                + " instead of the built-in image");
        assertThat(folder.html()).contains("image <code>" + fakeClaude() + "</code>", "image <code>" + fakePi() + "</code>");
    }

    @Test
    void withoutAnEntry_nothingIsNoted_andTheRecordHoldsAnEmptyObject() {
        traceworks.aiImages(Map.of());

        Result run = traceworks.run("ai-codex");   // refused before any AI call, but after the config was read
        Result plain = traceworks.run("placeholders");

        assertThat(run.code()).as(run.describe()).isEqualTo(6);
        assertThat(plain.code()).as(plain.describe()).isZero();
        assertThat(plain.err()).doesNotContain("ai_images");
        JsonNode aiImages = traceworks.folder(plain).only("run.started").path("ai_images");
        assertThat(aiImages.isObject()).isTrue();
        assertThat(aiImages).isEmpty();
    }

    @Test
    void anOperatorImageThatIsNotThere_isRefusedAtStart_withAHintThatFits() {
        traceworks.aiImages(Map.of("claude", "traceworks-it/not-there:1", "pi", fakePi()));

        Result run = traceworks.run("ai-simple");

        assertThat(run.code()).as(run.describe()).isEqualTo(6);
        assertThat(run.err()).contains("image traceworks-it/not-there:1 is missing",
                "the image of claude in ai_images of traceworks.json", "never builds or pulls")
                .doesNotContain("traceworks images build", "traceworks/claude:");
        assertThat(traceworks.folders()).as("refused before a run exists").isEmpty();
    }

    @Test
    void anImageOfACliTheWorkflowDoesNotUse_isNotAskedFor() {
        traceworks.aiImages(Map.of("claude", fakeClaude(), "pi", "traceworks-it/not-there:1"));

        Result run = traceworks.run("ai-compress");   // compress-ai runs Claude only

        assertThat(run.code()).as(run.describe()).isZero();
    }

    @Test
    void aWorkflowThatNamesCodex_isRefused() {
        Result check = traceworks.exec("check", traceworks.packageDir("ai-codex").toString());
        Result checked = traceworks.run("ai-codex");
        assertThat(checked.code()).as(checked.describe()).isEqualTo(6);
        assertThat(checked.err()).contains("unsupported-provider", "the run is refused: check reports 1 error");
        assertThat(traceworks.folders()).as("refused before a run exists").isEmpty();
        Result run = traceworks.run("ai-codex", "--no-check");   // the engine's own admission

        assertThat(check.code()).as(check.describe()).isEqualTo(6);
        assertThat(check.out()).containsPattern("(?m)^ERROR +unsupported-provider +provider CODEX: Codex is not"
                + " supported");
        assertThat(run.code()).as(run.describe()).isEqualTo(6);
        assertThat(run.both()).contains("Codex is not supported; Traceworks runs Claude and Pi");
        assertThat(traceworks.folder(run).ownEvents("step.command")).as("no AI call ran").isEmpty();
        assertThat(traceworks.folder(run).ownEvents("ai.request")).isEmpty();
    }

    @Test
    void aBadAiImagesSetting_isRefusedLikeAnyBadConfigValue() throws IOException {
        for (String bad : new String[] {"{\"codex\": \"x:1\"}", "{\"claude\": \"-x\"}", "{\"claude\": \"a b\"}",
                "{\"claude\": \"./images/x\"}", "{\"claude\": 5}", "[]"}) {
            Files.writeString(traceworks.home().resolve("traceworks.json"), "{\"container_runtime\": \""
                    + traceworks.environment().runtime() + "\", \"ai_images\": " + bad + "}");

            Result run = traceworks.run("ai-simple");
            Result runs = traceworks.exec("runs");

            assertThat(run.code()).as(bad + "\n" + run.describe()).isEqualTo(6);
            assertThat(run.err()).as(bad).contains("ai_images");
            assertThat(runs.code()).as(bad).isEqualTo(6);
        }
        Files.writeString(traceworks.home().resolve("traceworks.json"), "{\"ai_images\": {\"codex\": \"x:1\"}}");
        assertThat(traceworks.run("ai-simple").err()).contains("Unknown key 'codex' in ai_images",
                "the keys are claude, pi");
        assertThat(traceworks.folders()).isEmpty();
    }

    @Test
    void noWorkflowAndNoEnvironmentVariable_canSetIt() throws IOException {
        Path copy = traceworks.workspace().resolve("copy");
        Files.createDirectories(copy);
        String workflow = Files.readString(traceworks.packageDir("ai-simple").resolve("workflow.json"));
        // at the top of a workflow: an unknown field, the package does not load
        Files.writeString(copy.resolve("workflow.json"), workflow.replaceFirst("\"blocks\"",
                "\"ai_images\": {\"claude\": \"traceworks-it/evil:1\"}, \"blocks\""));
        Result top = traceworks.exec("check", copy.toString());
        assertThat(top.code()).as(top.describe()).isEqualTo(6);
        assertThat(top.err()).contains("ai_images");

        // in a block: no effect, and check warns
        Files.writeString(copy.resolve("workflow.json"), workflow.replace("\"command\": \"Say hello to {{ name }}\"",
                "\"command\": \"Say hello to {{ name }}\", \"ai_images\": {\"claude\": \"traceworks-it/evil:1\"}"));
        Result check = traceworks.exec("check", copy.toString());
        assertThat(check.out()).containsPattern("(?m)^WARNING +unknown-config-key +claude_say \\(block 0\\): unknown"
                + " config key ai_images");
        traceworks.environmentVariable("TRACEWORKS_AI_IMAGES", "{\"claude\": \"traceworks-it/evil:2\"}");
        traceworks.environmentVariable("AI_IMAGES", "traceworks-it/evil:3");
        Result run = traceworks.exec("run", copy.toString(), "--workspace", traceworks.workspace().toString(),
                "--no-input");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.ofStep("step.command", folder.stepIndex("claude_say")).getFirst().path("image").asText())
                .isEqualTo(fakeClaude());
        assertThat(run.both()).doesNotContain("evil");
    }
}
