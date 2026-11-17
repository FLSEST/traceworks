package traceworks.core.app;

import org.junit.jupiter.api.Test;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.model.WorkflowJson;
import traceworks.core.port.LocalModels;
import traceworks.core.testing.FakeAiCli;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link RunPreparation#redactedNames}: the names whose values a run redacts, with a model server's secret. */
class RunPreparationRedactedNamesTest {

    private static final String LOCAL_STEP = """
            {"type": "simple", "name": "ask", "config": {"command": "Say hi", "model": "llm/qwen2.5-coder:7b"}}""";

    private final AppFixture fx = new AppFixture();

    private RunPreparation preparation(String secret) {
        FakeAiCli claude = FakeAiCli.claude();
        FakeAiCli pi = FakeAiCli.pi();
        LocalModels local = new LocalModels(Map.of("llm", new LocalModels.Backend("llm", "http://llm.internal:8080/v1",
                LocalModels.OPENAI_COMPLETIONS, List.of("qwen2.5-coder:7b"), secret)));
        pi.localModels(local);
        fx.settings = OperatorSettings.defaults().withLocalModels(local);
        // the Claude CLI first: the loop must skip a CLI that is not the step's provider
        return new RunPreparation(fx.containerSupport(), fx.packages, fx.settings, List.of(claude, pi), null, null,
                fx.clock, fx.registers);
    }

    private static WorkflowDefinition definition(String provider, String secrets, String block) throws Exception {
        String json = """
                {"id": "wf", "name": "Wf", "provider": "%s", "blocks": [%s]%s}""".formatted(provider, block,
                secrets);
        return WorkflowJson.read(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theSecretOfAModelServer_isRedacted_andOnceWhenTheStepDeclaresItToo() throws Exception {
        RunPreparation preparation = preparation("LLM_KEY");

        assertThat(preparation.redactedNames(definition("PI", "", LOCAL_STEP), null)).filteredOn("LLM_KEY"::equals)
                .hasSize(1);

        String declared = """
                {"type": "shell", "name": "s", "config": {"command": "true", "secrets": ["LLM_KEY"]}}, """ + LOCAL_STEP;
        assertThat(preparation.redactedNames(definition("PI", "", declared), null)).filteredOn("LLM_KEY"::equals)
                .hasSize(1);
    }

    @Test
    void aServerWithoutASecret_addsNoName() throws Exception {
        assertThat(preparation(null).redactedNames(definition("PI", "", LOCAL_STEP), null)).containsExactlyElementsOf(traceworks.core.port.AiCli.credentialNamesOf("PI"));
    }

    @Test
    void aDeclaredNameTheRedactorCannotUse_isNotAmongTheRedactedNames() throws Exception {
        String shell = """
                {"type": "shell", "name": "s", "config": {"command": "true", "secrets": ["DEPLOY_TOKEN", "PATH"]}}""";

        assertThat(preparation("LLM_KEY").redactedNames(definition("PI", "", shell), null))
                .contains("DEPLOY_TOKEN").doesNotContain("PATH");
    }
}
