package traceworks.core.compare;

import traceworks.core.compare.RunComparison.AiCall;
import traceworks.core.compare.RunComparison.Field;
import traceworks.core.compare.RunComparison.Input;
import traceworks.core.compare.RunComparison.Output;
import traceworks.core.compare.RunComparison.Step;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepStatus;
import traceworks.core.port.BlobRef;
import traceworks.core.record.AiCallView;
import traceworks.core.record.GateView;
import traceworks.core.record.ItemView;
import traceworks.core.record.Manifest;
import traceworks.core.record.RunView;
import traceworks.core.record.StepView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The comparison of two runs, on views built by hand: what is the same, what differs, and what fails the check. */
class RunComparerTest {

    private static final Instant T0 = Instant.parse("2026-11-12T10:00:00Z");

    /** A blob named by a short text: equal texts are equal blobs. */
    private static BlobRef blob(String text) {
        return text == null ? null : new BlobRef(String.format("%064x", text.hashCode() & 0xffffffffL), text.length());
    }

    /** A run under construction: its header values, then its steps in order. */
    private static final class Run {
        final String id;
        String digest = "d".repeat(64);
        String commit = "c0ffee";
        boolean dirty = false;
        String version = "0.1.0";
        String execution = "container";
        String network = "restricted";
        RunStatus status = RunStatus.COMPLETED;
        GateView gate;
        Map<String, Object> inputs = new LinkedHashMap<>(Map.of("name", "Anna"));
        String inputsDigest = "same-inputs";
        List<Manifest.Image> images = List.of(new Manifest.Image("shell", "sha256:1"));
        List<String> models = List.of();
        Map<String, String> aiImages = Map.of();
        Map<String, List<String>> aiHosts = Map.of();
        List<traceworks.core.model.ModelPick> modelChoices = List.of();
        /** Whether the record can name a cut of an output; an earlier version's cannot. */
        boolean recordsCuts = true;
        final List<StepView> steps = new ArrayList<>();
        final Map<String, RunView> children = new LinkedHashMap<>();

        Run(String id) {
            this.id = id;
        }

        Run shell(String name, String output) {
            return step(name, "shell", false, StepStatus.COMPLETED, output, List.of(), List.of(), List.of());
        }

        /** A shell step with these commands, in order. */
        Run commands(String name, traceworks.core.record.CommandView... commands) {
            steps.add(new StepView(steps.size(), name, "foreach", steps.size(), false, StepStatus.COMPLETED, false,
                    false, 5, 1, List.of(), List.of(commands), blob("in"), blob("out"), null, null, List.of(),
                    List.of(), List.of(), List.of(), null, List.of()));
            return this;
        }

        Run ai(String name, String output, AiCallView... calls) {
            return step(name, "simple", true, StepStatus.COMPLETED, output, List.of(), List.of(calls), List.of());
        }

        Run step(String name, String type, boolean ai, StepStatus status, String output, List<ItemView> items,
                 List<AiCallView> calls, List<String> childRuns) {
            steps.add(new StepView(steps.size(), name, type, steps.size(), ai, status, false, false, 5, 1, List.of(),
                    List.of(), blob("in"), blob(output), null, null, List.of(), items, childRuns, List.of(), null,
                    calls));
            return this;
        }

        RunView view() {
            return view(id, steps, children);
        }

        static RunView view(String id, List<StepView> steps, Map<String, RunView> children) {
            return new RunView(id, "wf", "Wf", "/pkg", null, null, null, "flo", "MANUAL", null, T0, T0.plusSeconds(3),
                    RunStatus.COMPLETED, null, null, steps, null, null, null, blob("definition"), null, children, 10,
                    "head", List.of(), List.of(), null, Map.of());
        }

        RunFacts facts() {
            RunView view = new RunView(id, "wf", "Wf", "/pkg", digest, commit, dirty, "flo", "MANUAL", execution, T0,
                    T0.plusSeconds(3), status, null, gate, steps, null, null, null, blob("definition"),
                    blob(inputsDigest), children, 10, "head", List.of(), List.of(), network, aiHosts, null, null,
                    modelChoices, null, false, null, recordsCuts);
            Manifest manifest = new Manifest(id, "wf", "Wf", digest, blob(inputsDigest).sha256(), execution, images,
                    models, 10, "head", status, gate, T0, T0.plusSeconds(3), version);
            return new RunFacts(view, manifest, inputs, aiImages);
        }
    }

    private static AiCallView call(Integer item, String model, String tools, String prompt, String answeredBy,
                                   String output) {
        return new AiCallView(1, item, "CLAUDE", model, null, tools, "none", List.of(), blob(prompt), null,
                List.of(), new AiCallView.Response(answeredBy, 100, 20, 0.01, 1, 0, blob(output), null));
    }

    private static AiCallView call(String prompt, String output) {
        return call(null, "opus", "read", prompt, "claude-opus-5-5", output);
    }

    /** A call that differs from another one made by this method in its web access and its system prompt only. */
    private static AiCallView callWith(String web, List<String> webHosts, String systemPrompt) {
        return new AiCallView(1, null, "CLAUDE", "opus", null, "read", web, webHosts, blob("Review"),
                blob(systemPrompt), List.of(),
                new AiCallView.Response("claude-opus-5-5", 100, 20, 0.01, 1, 0, blob("fine"), null));
    }

    /** A command of {@code item} whose end names a cut of {@code cutStream}, or none for {@code null}. */
    private static traceworks.core.record.CommandView command(Integer item, String cutStream) {
        return new traceworks.core.record.CommandView(1, item, blob("cmd"), null, "/w", null, null, List.of(), "none",
                List.of(), List.of(), true, new traceworks.core.record.CommandView.Result(0, 5, false, List.of(),
                cutStream == null ? null
                        : new traceworks.core.port.CommandRunner.OutputCut(List.of(cutStream), 5000)));
    }

    /** The same number of cuts in both runs, but of other commands: that is not the same run. */
    @Test
    void anOutputCutOfAnotherCommand_differs_andTheSameCommandsCutDoNot() {
        Run a = new Run("run-a").commands("each", command(2, "stdout"), command(7, null), command(7, null));
        Run b = new Run("run-b").commands("each", command(2, null), command(7, null), command(7, "stdout"));
        Run c = new Run("run-c").commands("each", command(2, "stdout"), command(7, null), command(7, null));

        assertThat(compare(a, b).differences()).containsExactly("step each: the output was cut differently"
                + " (item 2, command 1: stdout after 5000 ms in run A, item 7, command 2: stdout after 5000 ms in run B)");
        assertThat(compare(a, c).reproduced()).isTrue();
    }

    /**
     * A record of an earlier version does not say whether an output was cut. Against a run that names a cut, that is
     * no difference: a note says that the cuts are not compared. Without any cut there is nothing to say.
     */
    @Test
    void aRecordThatCannotNameACut_isANote_notADifference() {
        Run old = new Run("run-old").commands("build", command(null, null));
        old.recordsCuts = false;
        Run cut = new Run("run-new").commands("build", command(null, "stdout"));
        Run whole = new Run("run-whole").commands("build", command(null, null));

        RunComparison comparison = compare(old, cut);

        assertThat(comparison.reproduced()).as(comparison.differences().toString()).isTrue();
        assertThat(comparison.notes()).containsExactly("run run-old is of a version that does not record whether an"
                + " output was cut: the cuts of the two runs are not compared");
        assertThat(compare(cut, old).notes()).hasSize(1);
        assertThat(compare(old, whole).notes()).as("no cut anywhere").isEmpty();
        assertThat(compare(whole, cut).differences()).as("both can say").hasSize(1);
    }

    private static RunComparison compare(Run a, Run b) {
        return RunComparer.compare(a.facts(), b.facts());
    }

    private static Step step(RunComparison comparison, String label) {
        return comparison.steps().stream().filter(s -> s.label().equals(label)).findFirst().orElseThrow();
    }

    private static Field field(RunComparison comparison, String name) {
        return comparison.header().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void twoEqualRuns_areReproduced_andNoHeaderFieldDiffers() {
        Run a = new Run("run-a").shell("greet", "Hello").shell("count", "3");
        Run b = new Run("run-b").shell("greet", "Hello").shell("count", "3");

        RunComparison comparison = compare(a, b);

        assertThat(comparison.reproduced()).isTrue();
        assertThat(comparison.differences()).isEmpty();
        assertThat(comparison.header()).allMatch(Field::same);
        assertThat(comparison.header()).extracting(Field::name).contains("workflow id", "workflow name",
                "package digest", "package commit", "inputs digest", "Traceworks version", "execution",
                "image shell", "ai_network", "models", "status", "gate");
        assertThat(comparison.inputs()).singleElement().satisfies(input -> {
            assertThat(input.key()).isEqualTo("name");
            assertThat(input.same()).isTrue();
        });
        assertThat(comparison.steps()).extracting(Step::label).containsExactly("greet", "count");
        assertThat(comparison.steps()).allSatisfy(step -> {
            assertThat(step.ai()).isFalse();
            assertThat(step.output()).isEqualTo(Output.SAME);
            assertThat(step.statusA()).isEqualTo("COMPLETED").isEqualTo(step.statusB());
        });
    }

    /** Two runs with different picks of a model choice: the inputs differ, and the comparison says which pick. */
    @Test
    void aDifferentPickOfAModelChoice_failsTheCheck_andIsNamedPlainly() {
        Run a = new Run("run-a").shell("greet", "Hello");
        Run b = new Run("run-b").shell("greet", "Hello");
        a.modelChoices = List.of(new traceworks.core.model.ModelPick("judge", "fable", "CLAUDE",
                "claude-fable-5-2", true));
        b.modelChoices = List.of(new traceworks.core.model.ModelPick("judge", "astra", "PI",
                "openai/gpt-6-astra", false));
        b.inputs = new LinkedHashMap<>(Map.of("name", "Anna", "judge", "astra"));
        b.inputsDigest = "other-inputs";

        RunComparison comparison = compare(a, b);

        assertThat(comparison.reproduced()).isFalse();
        assertThat(comparison.differences()).containsExactly("the inputs differ", "the model choice judge differs:"
                + " fable (the default): CLAUDE claude-fable-5-2 in run A, astra (picked): PI openai/gpt-6-astra in"
                + " run B");
        assertThat(field(comparison, "model choice judge")).isEqualTo(new Field("model choice judge",
                "fable (the default): CLAUDE claude-fable-5-2", "astra (picked): PI openai/gpt-6-astra"));
        b.modelChoices = a.modelChoices;
        assertThat(compare(a, b).differences()).as("the same pick is no difference of its own")
                .containsExactly("the inputs differ");
    }

    @Test
    void aDifferentPackageDigest_failsTheCheck_andTheHeaderNamesBothValues() {
        Run a = new Run("run-a").shell("greet", "Hello");
        Run b = new Run("run-b").shell("greet", "Hello");
        b.digest = "e".repeat(64);
        b.commit = "beef";

        RunComparison comparison = compare(a, b);

        assertThat(comparison.reproduced()).isFalse();
        assertThat(comparison.differences()).containsExactly("the package digest differs");
        assertThat(field(comparison, "package digest")).isEqualTo(new Field("package digest", "d".repeat(64),
                "e".repeat(64)));
        assertThat(field(comparison, "package commit").same()).isFalse();
    }

    @Test
    void differentInputs_failTheCheck_andAreListedKeyByKey() {
        Run a = new Run("run-a").shell("greet", "Hello");
        Run b = new Run("run-b").shell("greet", "Hello");
        a.inputs = new LinkedHashMap<>(Map.of("name", "Anna", "only_a", 1));
        b.inputs = new LinkedHashMap<>(Map.of("name", "Bert", "only_b", List.of("x")));
        b.inputsDigest = "other-inputs";

        RunComparison comparison = compare(a, b);

        assertThat(comparison.differences()).containsExactly("the inputs differ");
        assertThat(comparison.inputs()).containsExactlyInAnyOrder(
                new Input("name", false, false, "\"Anna\"", "\"Bert\""),
                new Input("only_a", false, false, "1", null),
                new Input("only_b", false, false, null, "[\"x\"]"));
    }

    /** A value the record redacted is never handed on, only whether the two records hold the same. */
    @Test
    void aRedactedInput_isNeverHandedOn() {
        Run a = new Run("run-a").shell("greet", "Hello");
        Run b = new Run("run-b").shell("greet", "Hello");
        a.inputs = new LinkedHashMap<>(Map.of("token", "x [redacted:TOKEN]", "same", "[redacted:KEY]"));
        b.inputs = new LinkedHashMap<>(Map.of("token", "plain-but-other", "same", "[redacted:KEY]"));
        b.inputsDigest = "other-inputs";

        RunComparison comparison = compare(a, b);

        assertThat(comparison.inputs()).containsExactlyInAnyOrder(new Input("token", false, true, null, null),
                new Input("same", true, true, null, null));
    }

    /** The plain value of the other run would tell what the redacted one is not, so both sides are withheld. */
    @Test
    void anInputRedactedInTheSecondRunOnly_isWithheldOnBothSides() {
        Run a = new Run("run-a").shell("greet", "Hello");
        Run b = new Run("run-b").shell("greet", "Hello");
        a.inputs = new LinkedHashMap<>(Map.of("login", "plain-in-the-first-run"));
        b.inputs = new LinkedHashMap<>(Map.of("login", "[redacted:LOGIN]"));
        b.inputsDigest = "other-inputs";

        RunComparison comparison = compare(a, b);

        assertThat(comparison.inputs()).containsExactly(new Input("login", false, true, null, null));
    }

    @Test
    void deletedInputs_areComparedByTheirDigestOnly_andANoteSaysSo() {
        Run a = new Run("run-a").shell("greet", "Hello");
        Run b = new Run("run-b").shell("greet", "Hello");
        a.inputs = null;

        RunComparison comparison = compare(a, b);

        assertThat(comparison.reproduced()).isTrue();
        assertThat(comparison.inputs()).isEmpty();
        assertThat(comparison.notes()).singleElement().asString().contains("run-a", "deleted", "digest");
    }

    @Test
    void headerDifferencesOtherThanPackageAndInputs_areReported_butDoNotFailTheCheck() {
        Run a = new Run("run-a").shell("greet", "Hello");
        Run b = new Run("run-b").shell("greet", "Hello");
        b.version = "0.2.0";
        b.execution = "host";
        b.network = "open";
        b.images = List.of(new Manifest.Image("shell", "sha256:2"), new Manifest.Image("./images/x", "sha256:9"));
        b.aiImages = Map.of("claude", "registry.example/claude:7");
        b.aiHosts = Map.of("claude", List.of("gateway.example:443"));
        b.models = List.of("opus");
        b.status = RunStatus.COMPLETED_WITH_ERRORS;
        b.gate = new GateView("RED", "lint", null, null);

        RunComparison comparison = compare(a, b);

        assertThat(comparison.reproduced()).isTrue();
        assertThat(comparison.header().stream().filter(f -> !f.same())).containsExactlyInAnyOrder(
                new Field("Traceworks version", "0.1.0", "0.2.0"),
                new Field("execution", "container", "host"),
                new Field("image shell", "sha256:1", "sha256:2"),
                new Field("image ./images/x", null, "sha256:9"),
                new Field("ai_images claude", null, "registry.example/claude:7"),
                new Field("ai_network", "restricted", "open"),
                new Field("ai_hosts claude", null, "gateway.example:443"),
                new Field("models", null, "opus"),
                new Field("status", "COMPLETED", "COMPLETED_WITH_ERRORS"),
                new Field("gate", null, "RED (lint)"));
    }

    @Test
    void aDeterministicStepWhoseOutputOrStatusDiffers_failsTheCheck() {
        Run a = new Run("run-a").shell("greet", "Hello").shell("count", "3");
        Run b = new Run("run-b").shell("greet", "Hello")
                .step("count", "shell", false, StepStatus.FAILED, "4", List.of(), List.of(), List.of());

        RunComparison comparison = compare(a, b);

        assertThat(comparison.differences()).containsExactly("step count: status COMPLETED in A, FAILED in B",
                "step count: the output differs");
        assertThat(step(comparison, "count").output()).isEqualTo(Output.DIFFERS);
        assertThat(step(comparison, "count").outputA()).isEqualTo(blob("3"));
        assertThat(step(comparison, "greet").output()).isEqualTo(Output.SAME);
    }

    @Test
    void aStepOfOneRunOnly_isListedAsSuch_andFailsTheCheckWhenItIsDeterministic() {
        Run a = new Run("run-a").shell("greet", "Hello").shell("extra", "x");
        Run b = new Run("run-b").shell("greet", "Hello").shell("late", "y");

        RunComparison comparison = compare(a, b);

        assertThat(comparison.steps()).extracting(Step::label).containsExactly("greet", "extra", "late");
        assertThat(step(comparison, "extra").statusB()).isNull();
        assertThat(step(comparison, "late").statusA()).isNull();
        assertThat(comparison.differences()).containsExactly("step extra: only in run A", "step late: only in run B");
    }

    /** Steps are matched by block name; a name that ran several times by its occurrence, an unnamed block by position. */
    @Test
    void stepsAreMatchedByNameAndOccurrence_andUnnamedOnesByPosition() {
        Run a = new Run("run-a").shell("poll", "1").shell("poll", "2").shell(null, "x");
        Run b = new Run("run-b").shell("poll", "1").shell("poll", "other").shell(null, "x");

        RunComparison comparison = compare(a, b);

        assertThat(comparison.steps()).extracting(Step::label).containsExactly("poll", "poll #2",
                "(unnamed, position 2)");
        assertThat(comparison.differences()).containsExactly("step poll #2: the output differs");
    }

    @Test
    void anAiStepWithTheSameSetup_mayAnswerDifferently_withoutFailingTheCheck() {
        Run a = new Run("run-a").ai("review", "fine", call("Review", "fine"));
        Run b = new Run("run-b").ai("review", "bad", call("Review", "bad"));

        RunComparison comparison = compare(a, b);

        assertThat(comparison.reproduced()).isTrue();
        Step review = step(comparison, "review");
        assertThat(review.ai()).isTrue();
        assertThat(review.output()).isEqualTo(Output.DIFFERS);
        AiCall call = review.calls().getFirst();
        assertThat(call.setupDifferences()).isEmpty();
        assertThat(call.promptSame()).isTrue();
        assertThat(call.output()).isEqualTo(Output.DIFFERS);
        assertThat(call.a().provider()).isEqualTo("CLAUDE");
        assertThat(call.a().model()).isEqualTo("opus");
        assertThat(call.a().answeredBy()).isEqualTo("claude-opus-5-5");
        assertThat(call.a().tools()).isEqualTo("read");
        assertThat(call.a().web()).isEqualTo("none");
        assertThat(call.a().inputTokens()).isEqualTo(100);
        assertThat(call.a().outputTokens()).isEqualTo(20);
        assertThat(call.a().costUsd()).isEqualTo(0.01);
    }

    @Test
    void aDifferentAiSetup_failsTheCheck_namingWhatDiffers() {
        Run a = new Run("run-a").ai("review", "fine", call(null, "opus", "read", "Review", "claude-opus-5-5", "fine"));
        Run b = new Run("run-b").ai("review", "fine", call(null, "sonnet", "shell", "Review", "claude-sonnet-5", "fine"));

        RunComparison comparison = compare(a, b);

        assertThat(step(comparison, "review").calls().getFirst().setupDifferences())
                .containsExactly("model", "tools", "answering model");
        assertThat(comparison.differences()).containsExactly(
                "step review, AI call 1: the AI setup differs (model, tools, answering model)");
    }

    @Test
    void aDifferentProviderOrEffort_failsTheCheck_namingWhatDiffers() {
        Run a = new Run("run-a").ai("review", "fine", new AiCallView(1, null, "CLAUDE", "opus", "high", "read",
                "none", List.of(), blob("Review"), null, List.of(),
                new AiCallView.Response("m", 100, 20, 0.01, 1, 0, blob("fine"), null)));
        Run b = new Run("run-b").ai("review", "fine", new AiCallView(1, null, "PI", "opus", "low", "read",
                "none", List.of(), blob("Review"), null, List.of(),
                new AiCallView.Response("m", 100, 20, 0.01, 1, 0, blob("fine"), null)));

        RunComparison comparison = compare(a, b);

        assertThat(step(comparison, "review").calls().getFirst().setupDifferences())
                .containsExactly("provider", "effort");
        assertThat(comparison.reproduced()).isFalse();
    }

    /** A call that moved to a model server of the operator's, or to another one, is another setup. */
    @Test
    void aCallOnALocalBackend_andOneAtTheProvider_orAtAnotherServer_differInTheirSetup() {
        AiCallView.Response response = new AiCallView.Response("m", 100, 20, 0.01, 1, 0, blob("fine"), null);
        AiCallView provider = new AiCallView(1, null, "PI", "llm/qwen", null, "none", "none", List.of(),
                blob("Review"), null, List.of(), response);
        AiCallView local = new AiCallView(1, null, "PI", "llm/qwen", null, "none", "none", List.of(),
                blob("Review"), null, List.of(), response,
                new AiCallView.Local("llm", "http://llm.internal:8080/v1", "openai-completions"));
        AiCallView moved = new AiCallView(1, null, "PI", "llm/qwen", null, "none", "none", List.of(),
                blob("Review"), null, List.of(), response,
                new AiCallView.Local("llm", "http://other.internal:8080/v1", "openai-completions"));

        RunComparison toLocal = compare(new Run("run-a").ai("review", "fine", provider),
                new Run("run-b").ai("review", "fine", local));
        RunComparison toAnotherServer = compare(new Run("run-a").ai("review", "fine", local),
                new Run("run-b").ai("review", "fine", moved));
        RunComparison same = compare(new Run("run-a").ai("review", "fine", local),
                new Run("run-b").ai("review", "fine", local));

        assertThat(step(toLocal, "review").calls().getFirst().setupDifferences()).containsExactly("local backend");
        assertThat(toLocal.reproduced()).isFalse();
        assertThat(step(toLocal, "review").calls().getFirst().a().local()).isNull();
        assertThat(step(toLocal, "review").calls().getFirst().b().local())
                .isEqualTo("local backend llm at http://llm.internal:8080/v1");
        assertThat(step(toAnotherServer, "review").calls().getFirst().setupDifferences())
                .containsExactly("local backend");
        assertThat(step(same, "review").calls().getFirst().setupDifferences()).isEmpty();
        assertThat(same.reproduced()).isTrue();
    }

    @Test
    void aDifferentSystemPrompt_failsTheCheck_alsoWhenThePromptIsTheSame() {
        Run a = new Run("run-a").ai("review", "fine", callWith("none", List.of(), "Be strict"));
        Run b = new Run("run-b").ai("review", "fine", callWith("none", List.of(), "Be lenient"));

        RunComparison comparison = compare(a, b);

        assertThat(step(comparison, "review").calls().getFirst().setupDifferences()).containsExactly("system prompt");
        assertThat(comparison.reproduced()).isFalse();
        assertThat(comparison.differences()).containsExactly(
                "step review, AI call 1: the AI setup differs (system prompt)");
    }

    @Test
    void aSystemPromptInOneRunOnly_failsTheCheck() {
        Run a = new Run("run-a").ai("review", "fine", callWith("none", List.of(), null));
        Run b = new Run("run-b").ai("review", "fine", callWith("none", List.of(), "Be lenient"));

        assertThat(step(compare(a, b), "review").calls().getFirst().setupDifferences())
                .containsExactly("system prompt");
    }

    /** A call that could reach the web is another call than one that could not, whatever it answered. */
    @Test
    void aDifferentWebAccess_failsTheCheck_byModeAndByTheHostsOfAFetch() {
        Run none = new Run("run-a").ai("review", "fine", callWith("none", List.of(), null));
        Run search = new Run("run-b").ai("review", "fine", callWith("search", List.of(), null));
        Run fetchDocs = new Run("run-c").ai("review", "fine", callWith("fetch", List.of("docs.example.org"), null));
        Run fetchAny = new Run("run-d").ai("review", "fine", callWith("fetch", List.of("*"), null));

        RunComparison byMode = compare(none, search);
        assertThat(step(byMode, "review").calls().getFirst().setupDifferences()).containsExactly("web");
        assertThat(byMode.reproduced()).isFalse();
        assertThat(byMode.differences()).containsExactly("step review, AI call 1: the AI setup differs (web)");

        assertThat(step(compare(fetchDocs, fetchAny), "review").calls().getFirst().setupDifferences())
                .containsExactly("web");
        assertThat(compare(fetchDocs, fetchDocs).reproduced()).as("the same web access").isTrue();
    }

    /** A prompt that differs although no AI output before it did is another prompt: something outside the record changed it. */
    @Test
    void aDifferentPrompt_withNoDifferingAiOutputBeforeIt_failsTheCheck() {
        Run a = new Run("run-a").ai("review", "fine", call("Review A", "fine"));
        Run b = new Run("run-b").ai("review", "fine", call("Review B", "fine"));

        RunComparison comparison = compare(a, b);

        AiCall call = step(comparison, "review").calls().getFirst();
        assertThat(call.promptSame()).isFalse();
        assertThat(call.promptFollowsAiOutput()).isFalse();
        assertThat(comparison.differences()).containsExactly("step review, AI call 1: the AI setup differs (prompt)");
    }

    /** The answer to a prompt excuses the prompts after it, not the prompt it answers. */
    @Test
    void aDifferentPrompt_whoseOwnAnswerDiffers_failsTheCheck() {
        Run a = new Run("run-a").ai("review", "fine", call("Review A", "fine"));
        Run b = new Run("run-b").ai("review", "bad", call("Review B", "bad"));

        RunComparison comparison = compare(a, b);

        assertThat(comparison.reproduced()).isFalse();
        AiCall call = step(comparison, "review").calls().getFirst();
        assertThat(call.promptFollowsAiOutput()).isFalse();
        assertThat(comparison.differences()).containsExactly("step review, AI call 1: the AI setup differs (prompt)");
    }

    /** An AI step that ended the same and answered the same carries nothing on: a later prompt that differs fails. */
    @Test
    void aDifferentPrompt_afterAnAiStepWithTheSameStatusAndOutput_failsTheCheck() {
        Run a = new Run("run-a").ai("draft", "one", call("Draft", "one")).ai("review", "ok", call("Review A", "ok"));
        Run b = new Run("run-b").ai("draft", "one", call("Draft", "one")).ai("review", "ok", call("Review B", "ok"));

        RunComparison comparison = compare(a, b);

        assertThat(comparison.reproduced()).isFalse();
        AiCall call = step(comparison, "review").calls().getFirst();
        assertThat(call.promptSame()).isFalse();
        assertThat(call.promptFollowsAiOutput()).isFalse();
        assertThat(comparison.differences()).containsExactly("step review, AI call 1: the AI setup differs (prompt)");
    }

    /** A prompt that differs after an AI output differed carries that output on: the same setup, fed another answer. */
    @Test
    void aDifferentPrompt_afterADifferingAiOutput_doesNotFailTheCheck() {
        Run a = new Run("run-a").ai("draft", "one", call("Draft", "one")).ai("review", "ok", call("Review one", "ok"));
        Run b = new Run("run-b").ai("draft", "two", call("Draft", "two")).ai("review", "ok", call("Review two", "ok"));

        RunComparison comparison = compare(a, b);

        assertThat(comparison.reproduced()).isTrue();
        AiCall call = step(comparison, "review").calls().getFirst();
        assertThat(call.promptSame()).isFalse();
        assertThat(call.promptFollowsAiOutput()).isTrue();
        assertThat(call.setupDifferences()).isEmpty();
    }

    /** Within one step an answer feeds the next call's prompt (an agentic loop): that prompt differs as a result. */
    @Test
    void aDifferentPrompt_afterADifferingAnswerOfTheSameStep_doesNotFailTheCheck() {
        Run a = new Run("run-a").ai("fix", "done", call("Plan", "edit A"), call("Next after edit A", "ok"));
        Run b = new Run("run-b").ai("fix", "done", call("Plan", "edit B"), call("Next after edit B", "ok"));

        RunComparison comparison = compare(a, b);

        assertThat(comparison.reproduced()).isTrue();
        AiCall second = step(comparison, "fix").calls().get(1);
        assertThat(second.promptFollowsAiOutput()).isTrue();
        assertThat(second.setupDifferences()).isEmpty();
    }

    /** An equal answer carries nothing on: a later prompt of the same step that differs is another prompt. */
    @Test
    void aDifferentPrompt_afterAnEqualAnswerOfTheSameStep_failsTheCheck() {
        Run a = new Run("run-a").ai("fix", "done", call("Plan", "same"), call("Next A", "ok"));
        Run b = new Run("run-b").ai("fix", "done", call("Plan", "same"), call("Next B", "ok"));

        assertThat(compare(a, b).differences()).containsExactly("step fix, AI call 2: the AI setup differs (prompt)");
    }

    @Test
    void aPackageWithUncommittedChanges_isNamedInTheCommitField() {
        Run a = new Run("run-a").shell("greet", "Hello");
        Run b = new Run("run-b").shell("greet", "Hello");
        b.dirty = true;

        assertThat(field(compare(a, b), "package commit"))
                .isEqualTo(new Field("package commit", "c0ffee", "c0ffee (with uncommitted changes)"));
    }

    /** An AI step of one run only is an AI output that differed, not a failed check of its own. */
    @Test
    void anAiStepOfOneRunOnly_doesNotFailTheCheck_andCountsAsAnAiOutputThatDiffered() {
        Run a = new Run("run-a").ai("review", "fine", call("Review", "fine")).shell("publish", "x");
        Run b = new Run("run-b").shell("publish", "y");

        RunComparison comparison = compare(a, b);

        assertThat(comparison.differences()).containsExactly(
                "step publish: the output differs (after an AI output that differed)");
    }

    /** An AI step without calls of its own (a wrapper) whose output differs carries that output on, as a call does. */
    @Test
    void anAiWrapperWhoseOutputDiffers_letsALaterPromptDiffer_withoutFailingTheCheck() {
        Run a = new Run("run-a").step("wrap", "foreach", true, StepStatus.COMPLETED, "one", List.of(), List.of(),
                List.of()).ai("review", "ok", call("Review one", "ok"));
        Run b = new Run("run-b").step("wrap", "foreach", true, StepStatus.COMPLETED, "two", List.of(), List.of(),
                List.of()).ai("review", "ok", call("Review two", "ok"));

        RunComparison comparison = compare(a, b);

        assertThat(comparison.reproduced()).isTrue();
        assertThat(step(comparison, "review").calls().getFirst().promptFollowsAiOutput()).isTrue();
    }

    /** The strict rule the owner decided: a deterministic output counts even when an AI answer before it differed. */
    @Test
    void aDeterministicStepAfterADifferingAiOutput_stillFailsTheCheck_andTheReasonSaysWhatCameBefore() {
        Run a = new Run("run-a").ai("draft", "one", call("Draft", "one")).shell("publish", "one");
        Run b = new Run("run-b").ai("draft", "two", call("Draft", "two")).shell("publish", "two");

        RunComparison comparison = compare(a, b);

        assertThat(comparison.differences()).containsExactly(
                "step publish: the output differs (after an AI output that differed)");
    }

    @Test
    void aiCallsAreMatchedByItemAndOrder_andACallOfOneRunOnlyIsListed() {
        Run a = new Run("run-a").ai("judge", "x", call(0, "opus", "none", "P0", "m", "a0"),
                call(1, "opus", "none", "P1", "m", "a1"), call(1, "opus", "none", "P1 again", "m", "a1b"));
        Run b = new Run("run-b").ai("judge", "x", call(1, "opus", "none", "P1", "m", "a1"),
                call(0, "opus", "none", "P0", "m", "other"));

        RunComparison comparison = compare(a, b);

        List<AiCall> calls = step(comparison, "judge").calls();
        assertThat(calls).extracting(AiCall::label).containsExactly("item 0, AI call 1", "item 1, AI call 1",
                "item 1, AI call 2");
        assertThat(calls.get(0).output()).isEqualTo(Output.DIFFERS);
        assertThat(calls.get(1).output()).isEqualTo(Output.SAME);
        assertThat(calls.get(2).b()).isNull();
        assertThat(calls.get(2).output()).isEqualTo(Output.ONLY_A);
        assertThat(comparison.reproduced()).isTrue();
    }

    @Test
    void foreachItemsAreMatchedByIndex_andADeterministicItemThatDiffersFailsTheCheck() {
        List<ItemView> itemsA = List.of(new ItemView(0, 2, blob("a"), "COMPLETED", blob("out a"), null),
                new ItemView(1, 2, blob("b"), "COMPLETED", blob("out b"), null));
        List<ItemView> itemsB = List.of(new ItemView(0, 3, blob("a"), "COMPLETED", blob("out a"), null),
                new ItemView(1, 3, blob("b2"), "FAILED", blob("other"), null),
                new ItemView(2, 3, blob("c"), "COMPLETED", blob("out c"), null));
        Run a = new Run("run-a").step("each", "foreach", false, StepStatus.COMPLETED, "sum", itemsA, List.of(),
                List.of());
        Run b = new Run("run-b").step("each", "foreach", false, StepStatus.COMPLETED, "sum", itemsB, List.of(),
                List.of());

        RunComparison comparison = compare(a, b);

        assertThat(step(comparison, "each").items()).extracting(item -> item.index() + " " + item.sameItem() + " "
                + item.statusA() + "/" + item.statusB() + " " + item.output()).containsExactly(
                "0 true COMPLETED/COMPLETED SAME", "1 false COMPLETED/FAILED DIFFERS", "2 false null/COMPLETED ONLY_B");
        assertThat(comparison.differences()).containsExactly("step each, item 1: differs", "step each, item 2: only in run B");
    }

    /** The steps of a child run are compared under the step that started it: by item for a foreach, else in order. */
    @Test
    void theStepsOfChildRuns_areComparedUnderTheirParentStep() {
        Run a = new Run("run-a");
        a.children.put("child-a", Run.view("child-a", new Run("x").shell("inner", "1").steps, Map.of()));
        a.step("sub", "sub-workflow", false, StepStatus.COMPLETED, "done", List.of(), List.of(), List.of("child-a"));
        Run b = new Run("run-b");
        b.children.put("child-b", Run.view("child-b", new Run("x").shell("inner", "2").steps, Map.of()));
        b.step("sub", "sub-workflow", false, StepStatus.COMPLETED, "done", List.of(), List.of(), List.of("child-b"));

        RunComparison comparison = compare(a, b);

        assertThat(comparison.steps()).extracting(Step::label).containsExactly("sub", "sub > inner");
        assertThat(comparison.differences()).containsExactly("step sub > inner: the output differs");
    }

    /** A child run only one run started is compared too: its steps are listed as of that run only. */
    @Test
    void theStepsOfAChildRunOfOneRunOnly_areListedUnderTheirParentStep() {
        Run a = new Run("run-a");
        a.children.put("child-a", Run.view("child-a", new Run("x").shell("inner", "1").steps, Map.of()));
        a.step("sub", "sub-workflow", false, StepStatus.COMPLETED, "done", List.of(), List.of(), List.of("child-a"));
        Run b = new Run("run-b").step("sub", "sub-workflow", false, StepStatus.COMPLETED, "done", List.of(),
                List.of(), List.of());

        RunComparison comparison = compare(a, b);

        assertThat(comparison.steps()).extracting(Step::label).containsExactly("sub", "sub > inner");
        assertThat(comparison.differences()).containsExactly("step sub > inner: only in run A");
    }

    /** The same for a child run only run B started. */
    @Test
    void theStepsOfAChildRunOfRunBOnly_areListedUnderTheirParentStep() {
        Run a = new Run("run-a").step("sub", "sub-workflow", false, StepStatus.COMPLETED, "done", List.of(),
                List.of(), List.of());
        Run b = new Run("run-b");
        b.children.put("child-b", Run.view("child-b", new Run("x").shell("inner", "1").steps, Map.of()));
        b.step("sub", "sub-workflow", false, StepStatus.COMPLETED, "done", List.of(), List.of(), List.of("child-b"));

        RunComparison comparison = compare(a, b);

        assertThat(comparison.steps()).extracting(Step::label).containsExactly("sub", "sub > inner");
        assertThat(comparison.differences()).containsExactly("step sub > inner: only in run B");
    }

    /** The child runs of a foreach are matched by their item, also when they started in another order. */
    @Test
    void theChildRunsOfForeachItems_areMatchedByItem_notByTheOrderTheyStarted() {
        Run a = new Run("run-a");
        a.children.put("a0", Run.view("a0", new Run("x").shell("inner", "0").steps, Map.of()));
        a.children.put("a1", Run.view("a1", new Run("x").shell("inner", "1").steps, Map.of()));
        a.step("each", "foreach", false, StepStatus.COMPLETED, "sum", List.of(
                new ItemView(0, 2, blob("p"), "COMPLETED", blob("o0"), "a0"),
                new ItemView(1, 2, blob("q"), "COMPLETED", blob("o1"), "a1")), List.of(), List.of("a0", "a1"));
        Run b = new Run("run-b");
        b.children.put("b1", Run.view("b1", new Run("x").shell("inner", "1").steps, Map.of()));
        b.children.put("b0", Run.view("b0", new Run("x").shell("inner", "0").steps, Map.of()));
        b.step("each", "foreach", false, StepStatus.COMPLETED, "sum", List.of(
                new ItemView(0, 2, blob("p"), "COMPLETED", blob("o0"), "b0"),
                new ItemView(1, 2, blob("q"), "COMPLETED", blob("o1"), "b1")), List.of(), List.of("b1", "b0"));

        RunComparison comparison = compare(a, b);

        assertThat(comparison.steps()).extracting(Step::label).containsExactly("each", "each [item 0] > inner",
                "each [item 1] > inner");
        assertThat(comparison.differences()).isEmpty();
    }
}
