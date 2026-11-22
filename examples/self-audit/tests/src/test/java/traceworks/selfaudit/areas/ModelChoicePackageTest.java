package traceworks.selfaudit.areas;

import traceworks.cli.RuntimeSource;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.ContainerRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The model that judges the security self-audit is a model choice of the package: one verdict step refers to the
 * choice {@code judge}, the package declares its options, and the input {@code judge} of a run picks one. These
 * tests run the package as it ships through {@code check}, {@code describe} and the start check of {@code run}.
 * Nothing here starts a container or calls a model: the runtime is a stand-in that has no image, and the credentials
 * are an environment the test hands in.
 */
class ModelChoicePackageTest {

    private static final Path PACKAGE = Path.of("../security").toAbsolutePath().normalize();

    @TempDir
    Path temp;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> workflow() throws IOException {
        return (Map<String, Object>) Json.parse(Files.readString(PACKAGE.resolve("workflow.json")));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> blocks() throws IOException {
        return (List<Map<String, Object>>) workflow().get("blocks");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> config(Map<String, Object> block) {
        return (Map<String, Object>) block.get("config");
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePackage_declaresTheChoiceJudge_withFableAsItsDefault_andOneVerdictStepRefersToIt() throws IOException {
        Map<String, Object> judge = (Map<String, Object>) ((Map<String, Object>) workflow().get("model_choices"))
                .get("judge");

        assertThat(judge.get("default")).isEqualTo("fable");
        assertThat((Map<String, Object>) judge.get("options")).containsExactly(
                Map.entry("fable", Map.of("provider", "CLAUDE", "model", "claude-fable-5-2")),
                Map.entry("opus", Map.of("provider", "CLAUDE", "model", "claude-opus-5-5")),
                Map.entry("astra", Map.of("provider", "PI", "model", "openai/gpt-6-astra")));
        List<Map<String, Object>> verdicts = blocks().stream().filter(b -> "foreach".equals(b.get("type"))).toList();
        assertThat(verdicts).as("one question, one step, whoever answers").hasSize(1);
        assertThat(config(verdicts.getFirst())).containsEntry("model_choice", "judge")
                .doesNotContainKeys("provider", "model");
        assertThat(workflow().get("default_context")).as("the default stands in the declaration").isNull();
        assertThat((List<String>) workflow().get("requires")).contains("ai.model_choice");
    }

    /** The first step is told who judges, by the variables Traceworks sets for the pick, and reads no declaration. */
    @Test
    @SuppressWarnings("unchecked")
    void theAreasStep_isToldThePickedOption_andOneRegisterIsFedWhoeverJudges() throws IOException {
        List<Map<String, Object>> blocks = blocks();
        String areas = String.valueOf(config(blocks.getFirst()).get("command"));

        assertThat(blocks.getFirst().get("name")).isEqualTo("areas");
        assertThat(areas).contains(" --judge {{ judge }} --judge-provider {{ judge_provider }} --judge-model"
                + " {{ judge_model }} ").doesNotContain("workflow.json");
        assertThat((Map<String, Object>) ((Map<String, Object>) workflow().get("metadata")).get("audit"))
                .as("one register, whoever judges").containsEntry("key", "security")
                .containsEntry("report_block", "report");
    }

    @Test
    void thePackage_passesCheck_andIsDescribedWithTheChoiceItsOptionsAndTheStepThatUsesIt() {
        String check = cli(Map.of(), RuntimeSource.NONE, "check", PACKAGE.toString(), "--home",
                temp.resolve("home").toString());
        assertThat(check).doesNotContain("ERROR").doesNotContain("WARN");

        String described = cli(Map.of(), RuntimeSource.NONE, "describe", PACKAGE.toString(), "--home",
                temp.resolve("none").toString());
        assertThat(described).contains("Model choices",
                        "  judge  pick with --input judge=<option>; without it: fable",
                        "    fable  CLAUDE claude-fable-5-2  (the default)",
                        "    opus  CLAUDE claude-opus-5-5",
                        "    astra  PI openai/gpt-6-astra",
                        "    used by verdicts",
                        "foreach (model choice judge)",
                        "ai: model choice judge: fable (provider CLAUDE, model claude-fable-5-2) unless the run picks"
                                + " opus or astra")
                .doesNotContain("invalid value").doesNotContain("not shown:").doesNotContain("not declared");
    }

    /**
     * The start check knows the pick. With a runtime that has no image at all and an environment that holds only the
     * Anthropic key, a run with the default or with {@code opus} is asked for the claude image and nothing of Pi; a
     * run with {@code astra} for the pi image and the OpenAI key, and nothing of Claude.
     */
    @Test
    void theStartCheck_asksForTheImageAndTheCredentialOfThePickedOptionOnly() {
        Map<String, String> onlyAnthropic = Map.of("ANTHROPIC_API_KEY", "sk-ant-api03-Fake_key-0123456789");
        for (List<String> input : List.of(List.<String>of(), List.of("--input", "judge=fable"),
                List.of("--input", "judge=opus"))) {
            String output = run(onlyAnthropic, input);

            assertThat(output).as(String.valueOf(input)).contains("refused", "traceworks/claude:", "is missing")
                    .doesNotContain("traceworks/pi:").doesNotContain("AI provider PI")
                    .doesNotContain("OPENAI_API_KEY").doesNotContain("has no credential")
                    .doesNotContain("check reports").doesNotContain("warning: input");
        }

        String astra = run(onlyAnthropic, List.of("--input", "judge=astra"));

        assertThat(astra).contains("refused", "traceworks/pi:", "AI provider PI has no credential for the model"
                + " openai/gpt-6-astra", "OPENAI_API_KEY").doesNotContain("traceworks/claude:")
                .doesNotContain("AI provider CLAUDE");
        assertThat(run(Map.of("OPENAI_API_KEY", "sk-proj-Fake_key-0123456789"), List.of("--input", "judge=astra")))
                .as("with the OpenAI key only the images are missing").contains("traceworks/pi:")
                .doesNotContain("has no credential");
        assertThat(temp.resolve("home-run/runs")).as("no run was started").doesNotExist();
    }

    /** A pick the package does not declare is refused before a run exists, alone, with the options listed. */
    @Test
    void aPickOutsideTheOptions_isRefusedBeforeAnyStep_withTheOptionsListed() {
        String output = run(Map.of(), List.of("--input", "judge=sonnet"));

        assertThat(output).contains("The run is refused: the model choice \"judge\" has no option \"sonnet\": the"
                + " input judge picks one of fable, opus, astra (the default is fable)")
                .doesNotContain("is missing").doesNotContain("has no credential");
        assertThat(temp.resolve("home-run/runs")).as("no run was started").doesNotExist();
    }

    /** The operator narrows: with {@code allowed_models} for Claude only, the Pi option is refused, by name. */
    @Test
    void allowedModelsOfTheHome_refuseAPickThePackageAllows() throws IOException {
        Path home = Files.createDirectories(temp.resolve("home-run"));
        Files.writeString(home.resolve("traceworks.json"),
                "{\"allowed_models\": [{\"provider\": \"CLAUDE\", \"model\": \"claude-*\"}]}");

        String astra = run(Map.of(), List.of("--input", "judge=astra"));
        String fable = run(Map.of(), List.of());

        assertThat(astra).contains("the model openai/gpt-6-astra of PI may not run in this home: allowed_models in"
                + " traceworks.json allows CLAUDE claude-*").doesNotContain("is missing");
        assertThat(fable).as("the default is allowed, and refused only for what this machine lacks")
                .doesNotContain("allowed_models").contains("is missing");
    }

    private String run(Map<String, String> credentials, List<String> input) {
        List<String> args = new ArrayList<>(List.of("run", PACKAGE.toString(), "--project", "shop", "--home",
                temp.resolve("home-run").toString(), "--workspace", temp.toString(), "--no-input"));
        args.addAll(input);
        return cli(credentials, RuntimeSource.of(new NoImages()), args.toArray(String[]::new));
    }

    private String cli(Map<String, String> credentials, RuntimeSource runtime, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Map<String, String> env = new HashMap<>(credentials);
        env.put("TRACEWORKS_ACTOR", "tester");
        traceworks.cli.main.Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), env, temp, null, runtime);
        // As it reads: a line Traceworks broke for an 80 column terminal goes on behind "|+"
        return (out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8))
                .replaceAll("\\R *\\|\\+", "");
    }

    /** A stand-in for a container runtime: it answers, has no image, and starts nothing. */
    private static final class NoImages implements ContainerRuntime {

        @Override
        public String name() {
            return "stand-in";
        }

        @Override
        public String version() {
            return "0";
        }

        @Override
        public Optional<String> imageId(String reference) {
            return Optional.empty();
        }

        @Override
        public CommandResult run(ContainerRun run) {
            throw new IllegalStateException("no test here starts a container");
        }

        @Override
        public void build(String tag, String contextDirectory) {
            throw new IllegalStateException("no test here builds an image");
        }

        @Override
        public void pull(String reference) {
            throw new IllegalStateException("no test here pulls an image");
        }

        @Override
        public boolean removeLeftovers(String runId) {
            return true;
        }

        @Override
        public boolean mayStillRun(String runId) {
            return false;
        }
    }
}
