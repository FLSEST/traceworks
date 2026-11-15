package traceworks.core.testing;

import traceworks.core.port.HookRunner;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Records every hook call and answers with a fixed outcome. */
public final class FakeHookRunner implements HookRunner {

    public record Call(List<String> argv, byte[] stdin, String workingDirectory, Duration timeout) {
        public String payload() {
            return new String(stdin, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private final List<Call> calls = new ArrayList<>();
    private Outcome outcome = new Outcome(0, 5, "");

    public FakeHookRunner answering(Outcome outcome) {
        this.outcome = outcome;
        return this;
    }

    @Override
    public synchronized Outcome run(List<String> argv, byte[] stdin, String workingDirectory, Duration timeout) {
        calls.add(new Call(List.copyOf(argv), stdin.clone(), workingDirectory, timeout));
        return outcome;
    }

    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }
}
