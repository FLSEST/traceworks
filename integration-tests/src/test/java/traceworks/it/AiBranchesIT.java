package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The AI condition of {@code conditional} and {@code while}, and the AI step of {@code retry} (package
 * {@code ai-branches}, docs/cli.md "AI steps"): the model is asked, its TRUE or FALSE picks the branch, and every one of
 * these calls runs with {@code tools: none}. The fake CLI answers by marker: {@code [fake:true]}, {@code [fake:false]},
 * {@code [fake:while=N]} (TRUE for the first N calls of the run) and {@code [fake:fail-until=N]} (a failing exit code for
 * the first N-1 calls), which it counts in the run's scratch directory.
 */
class AiBranchesIT extends AiIntegrationTest {

    /** One run covers every case: each block is checked on its own. */
    @Test
    void theAiConditionsAndTheAiRetry_followTheDocs() {
        String runtime = traceworks.environment().runtime();
        Result run;
        List<String> networks;
        try (Containers.NetworkWatch watch = runtime.equals("docker") ? new Containers.NetworkWatch(runtime) : null) {
            run = traceworks.run("ai-branches");
            // The run has ended, so its networks were made long ago: their events are in the stream by now
            networks = watch == null ? null : watch.created();
        }

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        aRunOfManyAiCalls_makesTwoNetworksOnTheHost(folder, networks);
        trueAnswerTakesTheThenBranch_falseTheElseBranch_forClaudeAndPi(folder);
        failedEvaluationFollowsOnConditionError(folder);
        whileAsksBeforeEachIteration_untilTheAnswerIsFalse(folder);
        retryRunsTheCommandAgain_untilItCompletes(folder);
    }

    /**
     * What a run costs the host does not grow with its AI calls: each network is a bridge device the machine's
     * network services react to, so a run makes its two networks once and every call uses them. Followed in Docker's
     * events while the run goes on, since the networks are gone when it has ended; Podman's events are not asked.
     *
     * @param networks the networks the runtime created while the run went on; {@code null} where they were not watched
     */
    private void aRunOfManyAiCalls_makesTwoNetworksOnTheHost(RunFolder folder, List<String> networks) {
        assertThat(folder.events("ai.request")).as("the AI calls of the run").hasSizeGreaterThan(3);
        if (networks != null) {
            assertThat(networks).filteredOn(name -> name.contains(folder.id())).containsExactlyInAnyOrder(
                    "traceworks-" + folder.id() + "-net", "traceworks-" + folder.id() + "-out");
        }
        assertThat(Containers.networksOf(traceworks.environment().runtime(), folder.id()))
                .as("networks left after the run").isEmpty();
    }

    private static void trueAnswerTakesTheThenBranch_falseTheElseBranch_forClaudeAndPi(RunFolder folder) {
        assertThat(folder.output("cond_true")).contains("prompt: then branch of true");
        assertThat(folder.output("cond_false")).contains("prompt: else branch of false");
        assertThat(folder.output("cond_pi")).startsWith("fake-pi answered").contains("prompt: then branch of pi");
        assertThat(folder.metadata("cond_true").path("conditionMet").asBoolean()).isTrue();
        assertThat(folder.metadata("cond_false").path("conditionMet").asBoolean(true)).isFalse();

        // two calls each: the condition, then the branch. The question asks for TRUE or FALSE, and no call has tools
        List<JsonNode> requests = folder.ofStep("ai.request", folder.stepIndex("cond_true"));
        assertThat(requests).hasSize(2);
        assertThat(folder.blob(requests.get(0).path("prompt"))).startsWith("Evaluate this condition and respond with"
                + " ONLY the word TRUE or FALSE").contains("is the sky blue?");
        assertThat(requests).allSatisfy(r -> assertThat(r.path("tools").asText()).isEqualTo("none"));
        assertThat(folder.ofStep("ai.request", folder.stepIndex("cond_pi")).getFirst().path("provider").asText())
                .isEqualTo("PI");
    }

    private static void failedEvaluationFollowsOnConditionError(RunFolder folder) {
        assertThat(folder.stepStatus("cond_error")).isEqualTo("COMPLETED");
        assertThat(folder.output("cond_error")).contains("prompt: else branch of error");
        assertThat(folder.metadata("cond_error").path("conditionError").asText()).contains("exit code 1");
        JsonNode failed = folder.ofStep("ai.response", folder.stepIndex("cond_error")).getFirst();
        assertThat(failed.path("exit_code").asInt()).isEqualTo(1);
    }

    private static void whileAsksBeforeEachIteration_untilTheAnswerIsFalse(RunFolder folder) {
        int index = folder.stepIndex("loop");
        assertThat(folder.output("loop")).contains("[iteration 1]: fake-claude answered", "[iteration 2]: ")
                .doesNotContain("[iteration 3]");
        assertThat(folder.metadata("loop").path("iterationCount").asInt()).isEqualTo(2);
        // three questions (TRUE, TRUE, FALSE) and two bodies
        assertThat(folder.ofStep("ai.request", index)).hasSize(5);
        assertThat(folder.ofStep("ai.request", index)).allSatisfy(r ->
                assertThat(r.path("tools").asText()).isEqualTo("none"));
    }

    private static void retryRunsTheCommandAgain_untilItCompletes(RunFolder folder) {
        int index = folder.stepIndex("flaky");
        assertThat(folder.stepStatus("flaky")).isEqualTo("COMPLETED");
        assertThat(folder.output("flaky")).startsWith("fake-claude answered");
        assertThat(folder.metadata("flaky").path("attempts").asInt()).isEqualTo(3);
        assertThat(folder.metadata("flaky").path("maxAttempts").asInt()).isEqualTo(4);
        assertThat(folder.ofStep("ai.response", index)).extracting(r -> r.path("exit_code").asInt())
                .containsExactly(1, 1, 0);
        assertThat(folder.ofStep("ai.request", index)).allSatisfy(r ->
                assertThat(r.path("tools").asText()).isEqualTo("none"));
    }
}
