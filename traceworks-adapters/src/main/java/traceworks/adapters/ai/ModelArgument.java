package traceworks.adapters.ai;

/**
 * The check of a model before it becomes a value on an AI CLI's command line. The model is a string of the package
 * ({@code model} of a step, any text as far as the schema goes), and the CLIs' argument parsers are not Traceworks'
 * code: a value that begins with {@code -} may be read as a flag, one that begins with {@code @} as a file to read,
 * and neither CLI names a model with white space or a control character in it. Such a model is refused, so the fixed
 * flags of a call (the tools, no hooks, no MCP) cannot be changed from a package.
 */
final class ModelArgument {

    private ModelArgument() {
    }

    /**
     * Refuses a model that a CLI could read as something else than a model. Each part between {@code /} is checked
     * on its own, since Pi gets the backend and the id of {@code backend/id} as two values.
     *
     * @param model the model as the call names it; {@code null} or blank is no model and passes
     * @throws IllegalArgumentException for a model, or a part of it, that begins with {@code -} or {@code @}, or
     *                                  that holds white space or a control character
     */
    static void check(String model) {
        if (model == null || model.isBlank()) {
            return;
        }
        String reason = null;
        for (int i = 0; i < model.length() && reason == null; ) {
            int c = model.codePointAt(i);
            i += Character.charCount(c);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                reason = "holds white space";
            } else if (Character.isISOControl(c)) {
                reason = "holds a control character";
            }
        }
        for (String part : model.split("/", -1)) {
            if (reason == null && (part.startsWith("-") || part.startsWith("@"))) {
                reason = "has a part that begins with '" + part.charAt(0) + "', which the CLI could read as "
                        + (part.charAt(0) == '-' ? "a flag" : "a file");
            }
        }
        if (reason != null) {
            throw new IllegalArgumentException("The model '" + shown(model) + "' " + reason + ": it is not passed to"
                    + " the AI CLI");
        }
    }

    /** The model for a message: white space and control characters as {@code ?}, so the message stays one line. */
    private static String shown(String model) {
        StringBuilder out = new StringBuilder();
        model.codePoints().forEach(c -> out.appendCodePoint(
                Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c) ? '?' : c));
        return out.toString();
    }
}
