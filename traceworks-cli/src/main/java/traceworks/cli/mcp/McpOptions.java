package traceworks.cli.mcp;

import java.nio.file.Path;
import java.util.List;

/**
 * What the operator who started the server allows and says. No client can change any of it.
 *
 * @param allowRun     the tools that start, resume, pause and cancel a run
 * @param allowAnswer  the tools that answer the question of a run
 * @param allowDecide  the tool that accepts, dismisses and reopens a finding
 * @param allowHost    the operator's consent to a host run, for every run of this server
 * @param agent        the agent every change is recorded with ({@code --agent}, else the variable of a harness),
 *                     checked; {@code null} when the name of the client counts
 * @param packageRoots the real paths of the folders a client may name packages in
 */
record McpOptions(boolean allowRun, boolean allowAnswer, boolean allowDecide, boolean allowHost, String agent,
                  List<Path> packageRoots) {

    McpOptions {
        packageRoots = List.copyOf(packageRoots);
    }

    /** What a tool needs to be offered. */
    enum Access {
        READ(null), RUN("--allow-run"), ANSWER("--allow-answer"), DECIDE("--allow-decide");

        private final String flag;

        Access(String flag) {
            this.flag = flag;
        }

        /** The switch of {@code traceworks mcp} that opens it; {@code null} for what is always offered. */
        String flag() {
            return flag;
        }
    }

    boolean allows(Access access) {
        return switch (access) {
            case READ -> true;
            case RUN -> allowRun;
            case ANSWER -> allowAnswer;
            case DECIDE -> allowDecide;
        };
    }
}
