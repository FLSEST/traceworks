package traceworks.core.testing;

import traceworks.core.port.CommandRunner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A {@link CommandRunner} for tests that runs nothing: it answers each request with the result scripted for the
 * longest prefix of its command, and records every request in order. A request no script matches is refused with an
 * {@link IllegalStateException} unless {@link #otherwise} sets a fallback, so a test never passes on a command it did
 * not expect.
 *
 * <p>A shell request matches by its command line as the block resolved it, after the assignments the run puts
 * before it for the values it protects ({@link #line}); a {@code checked_command} starts with
 * {@code set -e -o pipefail} and a newline. A tool-mode request matches by its image, a space and its entrypoint
 * arguments joined with spaces.
 */
public final class FakeCommandRunner implements CommandRunner {

    private final Map<String, Function<CommandRequest, CommandResult>> scripts = new LinkedHashMap<>();
    private final List<CommandRequest> requests = new CopyOnWriteArrayList<>();
    private Function<CommandRequest, CommandResult> fallback;

    /** Exit code 0 with {@code stdout}. */
    public static CommandResult ok(String stdout) {
        return new CommandResult(0, stdout, "", 1, false);
    }

    /** A command that ended with {@code exitCode} and wrote {@code stdout}. */
    public static CommandResult exit(int exitCode, String stdout) {
        return new CommandResult(exitCode, stdout, "", 1, false);
    }

    /** Commands starting with {@code prefix} end with exit code 0 and write {@code stdout}. */
    public FakeCommandRunner on(String prefix, String stdout) {
        return on(prefix, ok(stdout));
    }

    /** Commands starting with {@code prefix} end as {@code result}. */
    public FakeCommandRunner on(String prefix, CommandResult result) {
        return on(prefix, request -> result);
    }

    /** Commands starting with {@code prefix} end as {@code answer} computes from the request. */
    public synchronized FakeCommandRunner on(String prefix, Function<CommandRequest, CommandResult> answer) {
        scripts.put(prefix, answer);
        return this;
    }

    /** Commands no script matches end as {@code result} instead of being refused. */
    public synchronized FakeCommandRunner otherwise(CommandResult result) {
        fallback = request -> result;
        return this;
    }

    @Override
    public CommandResult run(CommandRequest request) {
        requests.add(request);
        String line = line(commandLine(request));
        Function<CommandRequest, CommandResult> answer = scriptFor(line);
        if (answer == null) {
            throw new IllegalStateException("FakeCommandRunner: no scripted result for command: " + line);
        }
        return answer.apply(request);
    }

    private synchronized Function<CommandRequest, CommandResult> scriptFor(String line) {
        String best = null;
        for (String prefix : scripts.keySet()) {
            if (line.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
                best = prefix;
            }
        }
        return best != null ? scripts.get(best) : fallback;
    }

    /** Every request so far, in order. */
    public List<CommandRequest> requests() {
        return List.copyOf(requests);
    }

    /** The command line of every request so far, in order, as {@link #on} matches them. */
    public List<String> commands() {
        return requests.stream().map(FakeCommandRunner::commandLine).toList();
    }

    private static final java.util.regex.Pattern VALUE = java.util.regex.Pattern.compile(
            "IFS= read -r -d '' (TRACEWORKS_VALUE_\\d+) <<'(TRACEWORKS_VALUE_\\d+_END_*)' \\|\\| :\n");

    /**
     * {@code command} without the lines that set the shell variables of the values the run protects (per value a
     * {@code read} from a heredoc that holds it, and a line that takes off the heredoc's last newline): the shell
     * line as the workflow wrote it, with a reference where such a value stands.
     */
    public static String line(String command) {
        int at = 0;
        java.util.regex.Matcher start = VALUE.matcher(command);
        while (start.find(at) && start.start() == at) {
            String closing = "\n" + start.group(2) + "\n" + start.group(1) + "=${" + start.group(1) + "%$'\\n'}\n";
            int end = command.indexOf(closing, start.end() - 1);
            if (end < 0) break;
            at = end + closing.length();
        }
        return command.substring(at);
    }

    /**
     * The lines the run puts before a shell line to set the variable {@code TRACEWORKS_VALUE_<number>} to
     * {@code value}, for a test that states the whole text of a command.
     */
    public static String value(int number, String value) {
        String name = "TRACEWORKS_VALUE_" + number;
        return "IFS= read -r -d '' " + name + " <<'" + name + "_END' || :\n" + value + "\n" + name + "_END\n"
                + name + "=${" + name + "%$'\\n'}\n";
    }

    private static String commandLine(CommandRequest request) {
        return request.runsEntrypoint()
                ? request.image() + " " + String.join(" ", request.entrypointArgs())
                : request.command();
    }
}
