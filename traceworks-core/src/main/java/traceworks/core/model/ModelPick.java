package traceworks.core.model;

/**
 * The option a run picked of one model choice its workflow declares ({@code modelChoices}): what the steps that
 * refer to the choice run with.
 *
 * @param choice     the name of the choice, which is also the name of the run input that picks
 * @param option     the name of the picked option
 * @param provider   the option's AI provider id, as declared
 * @param model      the option's model, as declared
 * @param theDefault whether the picked option is the choice's default
 */
public record ModelPick(String choice, String option, String provider, String model, boolean theDefault) {

    /** The pick on one line, such as {@code judge = astra (picked): PI openai/gpt-6-astra}. */
    public String summary() {
        return choice + " = " + picked();
    }

    /** The picked option without the choice's name, such as {@code fable (the default): CLAUDE claude-fable-5-2}. */
    public String picked() {
        return option + (theDefault ? " (the default)" : " (picked)") + ": " + provider + " " + model;
    }
}
