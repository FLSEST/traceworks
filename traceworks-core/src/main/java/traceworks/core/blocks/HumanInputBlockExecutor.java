package traceworks.core.blocks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.blocks.StepActors.Actor;
import traceworks.core.context.ApprovalRequest;
import traceworks.core.context.ExecutionContext;
import traceworks.core.context.PresetAnswerException;
import traceworks.core.engine.BlockExecutor;
import traceworks.core.engine.EventLogException;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.BlockSettings;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.port.RunEvent.ApprovalAnswered;
import traceworks.core.port.RunEvent.ApprovalRequested;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@code human-input}: a person (or an AI actor, see {@link StepActors}) provides a value for {@code variable_name}
 * (default {@code user_input}), or with {@code fields} a form whose values go into one variable per field. The input
 * has a type ({@code input_type}: text, textarea, number, select, checkbox, date), optional {@code options}, a
 * {@code default_value} that answers after the timeout ({@code timeout_minutes}, default 30) unless
 * {@code use_default_on_timeout} is false, and an optional {@code validation_pattern}.
 *
 * <p>While the input is open, the run's events carry an {@link ApprovalRequested} of type {@code human-input}; the
 * answer, the default value after a timeout included, is recorded as an {@link ApprovalAnswered}. A failed write of
 * those events is never turned into a step result: the {@link EventLogException} propagates and the engine stops the
 * run.
 */
public class HumanInputBlockExecutor implements BlockExecutor {

    static final String TYPE = "human-input";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final StepActors actors;

    public HumanInputBlockExecutor() {
        this(new StepActors());
    }

    HumanInputBlockExecutor(StepActors actors) {
        this.actors = actors;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of(TYPE);
    }

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        int stepIndex = context.nextStepIndex();
        Instant start = context.getClock().now();
        Settings settings = Settings.from(block.getConfig(), context);
        String prompt = settings.prompt();

        // Who answers: a person as always, an AI call in the role, or both (shadow)
        Actor actor = StepActors.resolve(block, context);
        ActorDecision decision = actors.decide(actor, block, context, stepIndex,
                HumanInputAnswers.task(prompt, settings), text -> HumanInputAnswers.checked(text, settings));
        if (decision.failed()) {
            return buildResult(stepIndex, start, prompt, decision.failure(), StepStatus.FAILED, decision.aiMetadata(),
                    context);
        }

        String answer;
        Map<String, Object> metadata;
        // Only what a person typed is the person's. A model's answer and the default after a timeout are made of
        // what the run holds (the default may name a step's output), and are trusted as little as that
        boolean typed = false;
        if (decision.answeredByAi()) {
            answer = decision.answer();
            metadata = decision.aiMetadata();
        } else {
            PersonAnswer person = awaitPerson(stepIndex, settings, decision, context);
            if (person.failure() != null) {
                return buildResult(stepIndex, start, prompt, person.failure(), StepStatus.FAILED, person.metadata(),
                        context);
            }
            answer = person.answer();
            metadata = person.metadata();
            typed = person.typed();
        }

        String output;
        if (settings.multiField()) {
            output = storeMultiField(answer, settings, context, typed);
        } else {
            // Single field: the raw answer under variable_name
            store(context, settings.variableName(), answer, typed);
            output = answer;
        }
        return buildResult(stepIndex, start, prompt, output, StepStatus.COMPLETED, metadata, context);
    }

    /**
     * A person's answer, the default value after a timeout, or why there is none.
     *
     * @param failure the step output when the step fails (timeout without default, cancel)
     * @param typed   whether a person gave the answer; {@code false} for the default value
     */
    private record PersonAnswer(String answer, String failure, Map<String, Object> metadata, boolean typed) {

        static PersonAnswer failed(String failure, Map<String, Object> metadata) {
            return new PersonAnswer(null, failure, metadata, false);
        }
    }

    /**
     * Sets a variable to an answer. A person's answer is trusted, as it always was. Any other is derived from the
     * run: untrusted when the run carries untrusted input, so a shell step quotes it and a mount refuses it, as for a
     * step's output.
     */
    private static void store(ExecutionContext context, String name, String value, boolean typed) {
        if (typed) {
            context.setVariable(name, value);
        } else {
            context.setDerivedVariable(name, value);
        }
    }

    /**
     * Asks the person (in shadow mode with the AI's proposal below the prompt) and waits. After a timeout the default
     * value answers when the block allows it; nobody answered then, so a shadow step records no agreement either way.
     * A preset answer given to another question fails the step with the reason alone
     * ({@link PresetAnswerException}).
     */
    private PersonAnswer awaitPerson(int stepIndex, Settings settings, ActorDecision decision,
                                     ExecutionContext context) {
        String prompt = settings.prompt();
        ApprovalRequest request = ApprovalRequest.builder()
                .blockId("human-input-" + stepIndex)
                .question(decision.shown(prompt))
                .options(settings.options())
                .stepIndex(stepIndex)
                .type(TYPE)
                .inputType(settings.inputType())
                .defaultValue(settings.defaultValue())
                .variableName(settings.variableName())
                .required(settings.required())
                .validationPattern(settings.validationPattern())
                .fields(settings.fields())
                .timeoutMinutes(settings.timeoutMinutes())
                .shadow(decision.actor() == Actor.SHADOW)
                .build();
        CompletableFuture<String> pending = ask(request, context);
        String answer;
        Map<String, Object> metadata;
        boolean typed = true;
        try {
            answer = context.awaitApproval(pending, settings.timeoutMinutes(), TimeUnit.MINUTES);
            metadata = decision.afterPerson(HumanInputAnswers.same(answer, decision.proposalText(), settings));
        } catch (EventLogException e) {
            throw e;   // the end of the wait could not be recorded: the run stops, the step result does not matter
        } catch (TimeoutException e) {
            String defaultValue = settings.defaultValue();
            if (!settings.useDefaultOnTimeout() || defaultValue == null || defaultValue.isEmpty()) {
                return PersonAnswer.failed("Timed out waiting for human input", decision.unanswered());
            }
            answer = defaultValue;
            metadata = decision.unanswered();
            typed = false;
        } catch (PresetAnswerException e) {
            return PersonAnswer.failed(e.getMessage(), decision.unanswered());
        } catch (Exception e) {
            return PersonAnswer.failed("Human input cancelled: " + e.getMessage(), decision.unanswered());
        }
        context.getEvents().emit(new ApprovalAnswered(stepIndex, TYPE, answer));
        return new PersonAnswer(answer, null, metadata, typed);
    }

    /**
     * The settings of one human-input block. Prompt and default value are resolved like the approval question:
     * {@code {{ block.X.output }}} should appear in the dialog, not literally.
     *
     * @param fields a form with several inputs, {@code null} when the block has none
     */
    record Settings(String prompt, String inputType, String variableName, List<String> options,
                    String defaultValue, boolean required, String validationPattern, int timeoutMinutes,
                    boolean useDefaultOnTimeout, List<Map<String, Object>> fields) {

        static Settings from(Map<String, Object> config, ExecutionContext context) {
            BlockSettings settings = BlockSettings.of(TYPE, config);
            String prompt = settings.text("prompt", "Please provide input:");
            String inputType = settings.text("input_type", "text");
            String variableName = settings.text("variable_name", "user_input");
            List<String> options = settings.texts("options", List.of());
            String defaultValue = settings.text("default_value", "");
            boolean required = settings.flag("required", true);
            String validationPattern = settings.text("validation_pattern");
            int timeoutMinutes = settings.number("timeout_minutes", 30);
            boolean useDefaultOnTimeout = settings.flag("use_default_on_timeout", true);
            List<Map<String, Object>> fields = settings.objects("fields");
            return new Settings(context.resolveTemplate(prompt), inputType, variableName, options,
                    context.resolveTemplate(defaultValue), required, validationPattern, timeoutMinutes,
                    useDefaultOnTimeout, fields);
        }

        boolean multiField() {
            return fields != null && !fields.isEmpty();
        }
    }

    /**
     * Opens the request in the run, then announces it: whoever reacts to the {@link ApprovalRequested} finds the
     * request already open. The future carries the answer.
     */
    private static CompletableFuture<String> ask(ApprovalRequest request, ExecutionContext context) {
        CompletableFuture<String> pending = context.requestApproval(request);
        List<Map<String, Object>> fields = request.getFields() != null && !request.getFields().isEmpty()
                ? request.getFields() : null;
        context.getEvents().emit(new ApprovalRequested(request.getStepIndex(), request.getBlockId(), TYPE,
                request.getQuestion(), request.getOptions(), List.of(), request.getInputType(),
                request.getDefaultValue(), request.isRequired(), request.getValidationPattern(),
                request.getVariableName(), fields, context.parkedRequest() == request,
                context.answeredFromPreset(request)));
        return pending;
    }

    /**
     * A form with several fields: the answer is a JSON object, each field's value goes into the field's own variable
     * (a missing one as empty text). An answer that is no JSON object is kept whole under {@code variable_name}.
     * Returns the step output.
     *
     * @param typed whether a person gave the answer ({@link #store})
     */
    private static String storeMultiField(String answer, Settings settings, ExecutionContext context,
                                          boolean typed) {
        Map<String, String> parsed;
        try {
            parsed = JSON.readValue(answer == null ? "{}" : answer, new TypeReference<>() {});
        } catch (Exception e) {
            store(context, settings.variableName(), answer, typed);
            return answer;
        }
        for (Map<String, Object> field : settings.fields()) {
            Object varNameObj = field.get("variable_name");
            if (!(varNameObj instanceof String varName) || varName.isBlank()) continue;
            store(context, varName, parsed.getOrDefault(varName, ""), typed);
        }
        return answer == null ? "" : answer;
    }

    private static StepResult buildResult(int stepIndex, Instant start, String input, String output,
                                          StepStatus status, Map<String, Object> metadata, ExecutionContext context) {
        Instant end = context.getClock().now();
        return StepResult.builder()
                .stepIndex(stepIndex)
                .input("[Human Input] " + input)
                .output(output)
                .status(status)
                .startedAt(start)
                .completedAt(end)
                .durationMs(end.toEpochMilli() - start.toEpochMilli())
                .metadata(metadata == null ? null : new HashMap<>(metadata))
                .build();
    }
}
