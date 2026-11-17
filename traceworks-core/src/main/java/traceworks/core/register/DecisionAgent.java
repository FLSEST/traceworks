package traceworks.core.register;

import java.util.Map;

/**
 * The agent that made a decision in a register under the actor who ran the command: an AI agent that works with the
 * operator's user is the operator to the machine, so it says itself that it decided, with {@code --agent NAME} or,
 * for a harness that sets it once, the environment variable {@value #ENV}. The line of the decision then names it
 * beside the actor ({@code agent}), and so does every view of the decision.
 *
 * <p><b>The name</b> is a label, bounded so that it can stand in a line and a column of every view as it is: at most
 * {@value #MAX_CHARS} characters, each an ASCII letter or digit, one of {@code . _ - : / @ +}, or a single space
 * between two of those. White space around it is no part of it. So no name holds a control or format character, a
 * line break, two spaces in a row (what separates the columns of a list), a quote, a bracket, a comma or the
 * {@code |} of a row. A blank name is refused and not taken for none: who set the option or the variable meant to
 * name an agent, and the decision must not be recorded as a person's.
 *
 * <p><b>What it is not:</b> proof. The name is what the caller says, unsigned. An agent that leaves it out is
 * recorded as the actor alone, and any caller can give any name (docs/registers.md, "Who decided").
 */
public final class DecisionAgent {

    /** The environment variable that names the agent when the command line does not. */
    public static final String ENV = "TRACEWORKS_AGENT";

    /** The option of {@code findings <key> accept|dismiss|reopen} that names the agent. */
    public static final String OPTION = "--agent";

    /** How long a name may be. */
    public static final int MAX_CHARS = 64;

    private static final String PUNCTUATION = "._-:/@+";

    private DecisionAgent() {
    }

    /**
     * The agent of a decision: the option when it is given, else the variable, else none. The option wins whatever
     * the variable holds.
     *
     * @param option the value of {@value #OPTION}; {@code null} when it was not given
     * @return the name, without the white space around it; {@code null} when neither names one
     * @throws IllegalArgumentException if the one that counts is blank or no name; the message names the source
     */
    public static String resolve(String option, Map<String, String> env) {
        if (option != null) {
            return checked(option, OPTION);
        }
        String fromEnv = env.get(ENV);
        return fromEnv == null ? null : checked(fromEnv, ENV);
    }

    /**
     * @param source what gave the name, for the message
     * @return the name without the white space around it
     * @throws IllegalArgumentException if it is blank or no name
     */
    public static String checked(String raw, String source) {
        String problem = problem(raw);
        if (problem != null) {
            throw new IllegalArgumentException(source + " " + problem);
        }
        return raw.strip();
    }

    /**
     * Why {@code raw} is no name of an agent, as the end of a sentence that starts with what gave it; {@code null}
     * when it is one. The words show no character of the name: a refused one may be what a terminal must not get.
     */
    public static String problem(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty()) {
            return "is blank: name the agent, or leave it out for a decision a person makes";
        }
        if (name.length() > MAX_CHARS) {
            return "takes a name of at most " + MAX_CHARS + " characters, got " + name.length();
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean letterOrDigit = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9';
            boolean singleSpace = c == ' ' && name.charAt(i - 1) != ' ';   // never the first: the name is stripped
            if (!letterOrDigit && PUNCTUATION.indexOf(c) < 0 && !singleSpace) {
                return "takes a name of letters, digits, the characters . _ - : / @ + and single spaces, all ASCII;"
                        + " got " + String.format("U+%04X", (int) c) + " at " + (i + 1);
            }
        }
        return null;
    }

    /**
     * What a view says after the actor of a decision: {@code " through the agent NAME"}, and nothing for a decision
     * without an agent. The caller makes the name safe for where it prints it: a log that was edited can hold any.
     */
    public static String through(String agent) {
        return agent == null ? "" : " through the agent " + agent;
    }
}
