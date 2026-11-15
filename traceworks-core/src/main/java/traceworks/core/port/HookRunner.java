package traceworks.core.port;

import java.time.Duration;
import java.util.List;

/**
 * Runs an operator's hook: a program on the host, started without a shell, with a payload on its standard input.
 * The hook is the operator's own code (docs/cli.md, "traceworks.json"); a workflow never configures one.
 */
public interface HookRunner {

    /**
     * How a hook ended.
     *
     * @param exitCode   the exit code; 124 for a timeout, 127 when it could not start
     * @param stderrTail the last characters of its standard error, at most {@value #STDERR_TAIL_CHARS}
     */
    record Outcome(int exitCode, long durationMs, String stderrTail) {
    }

    int STDERR_TAIL_CHARS = 2000;

    /**
     * @param argv             the program and its arguments
     * @param stdin            what the program reads on its standard input
     * @param workingDirectory where it runs
     * @param timeout          after which it is ended
     */
    Outcome run(List<String> argv, byte[] stdin, String workingDirectory, Duration timeout);
}
