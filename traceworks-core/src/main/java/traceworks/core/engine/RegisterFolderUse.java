package traceworks.core.engine;

import traceworks.core.context.ExecutionContext;

import java.util.regex.Pattern;

/**
 * Where a step may name the register folder of a run ({@code {{ register_dir }}}, docs/registers.md, "The register
 * folder of a run"), judged when the step runs, before anything of it starts.
 *
 * <p>The folder belongs to the run: only a run of an audit workflow has one, and a workflow it starts sees the same
 * one. {@code traceworks check} judges a definition by its own {@code metadata.audit} and cannot know which run
 * starts it, so the two can disagree for a child workflow. These checks make sure the placeholder never reaches a
 * command as text, and never reaches a model at all.
 */
public final class RegisterFolderUse {

    /** The placeholder as the resolver reads it: {@code {{register_dir}}} or {@code {{ register_dir }}}. */
    private static final Pattern NAMED = Pattern.compile("\\{\\{" + RunVariables.REGISTER_DIR + "}}|\\{\\{ "
            + RunVariables.REGISTER_DIR + " }}");

    private RegisterFolderUse() {
    }

    /** Whether {@code template} names the folder. */
    public static boolean names(String template) {
        return template != null && NAMED.matcher(template).find();
    }

    /**
     * For a text a command is made of (a shell command, a tool's argument, a mount): the run must have the folder.
     *
     * @throws WorkflowExecutionException if the text names the folder and the run has none
     */
    public static void inCommand(String template, ExecutionContext context) {
        if (names(template) && !context.resolvesPlaceholder(RunVariables.REGISTER_DIR)) {
            throw new WorkflowExecutionException("{{ register_dir }} names the register folder of a run, and this run"
                    + " has none: only a run of an audit workflow (metadata.audit with enabled: true) has the folder,"
                    + " and a workflow started by a run that is no audit gets none. Nothing was run");
        }
    }

    /**
     * For a text that goes to a model: it must not name the folder, in any run.
     *
     * @throws WorkflowExecutionException if it does
     */
    public static void inPrompt(String template) {
        if (names(template)) {
            throw new WorkflowExecutionException("{{ register_dir }} stands in the prompt of an AI step: the container"
                    + " of an AI call does not get the register folder, and no model is handed its path. Let a shell"
                    + " step read the files and put what the model needs into its output");
        }
    }
}
