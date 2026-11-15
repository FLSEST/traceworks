package traceworks.core.engine;

import java.util.regex.Pattern;

/** The variable that holds a step's output, {@code step_N_output}: the one spelling of its name. */
public final class StepOutputs {

    private static final Pattern NAME = Pattern.compile("step_\\d+_output");

    private StepOutputs() {
    }

    /** The name of the output variable of the step with index {@code stepIndex} (0-based). */
    public static String name(int stepIndex) {
        return "step_" + stepIndex + "_output";
    }

    /** Whether {@code name} is a step's output variable: it counts as the step's own text. */
    public static boolean isStepOutput(String name) {
        return NAME.matcher(name).matches();
    }
}
