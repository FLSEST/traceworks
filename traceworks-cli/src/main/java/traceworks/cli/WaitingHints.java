package traceworks.cli;

import com.fasterxml.jackson.databind.JsonNode;
import traceworks.core.record.RecordTypes;
import traceworks.core.record.RecordedEvent;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Without a terminal, a question that does not park the run (a nested one, or one cast with an AI actor) leaves the
 * run waiting. Tells the person how to answer it. The record says which questions park ({@code parks}), so a
 * parking question gets no hint (the run ends and its summary names the commands), and neither does a question that
 * a resume answers at once ({@code preset}).
 */
final class WaitingHints {

    private final PrintStream out;

    WaitingHints(PrintStream out) {
        this.out = out;
    }

    /**
     * @param rootRunId the id of the run to answer through
     */
    void accept(RecordedEvent event, String rootRunId) {
        if (!RecordTypes.APPROVAL_REQUESTED.equals(event.type()) || event.fields().path("parks").asBoolean()
                || event.fields().path("preset").asBoolean()) {
            return;
        }
        JsonNode f = event.fields();
        List<String> options = new ArrayList<>();
        f.path("options").forEach(o -> options.add(o.asText()));
        List<String> names = new ArrayList<>();
        f.path("fields").forEach(field -> names.add(field.path("variable_name").asText()));
        AnswerCommands.of(LEAD, rootRunId, f.path("kind").asText(), options, names)
                .forEach(line -> out.println(LEAD + line));
    }

    private static final String LEAD = "waiting for an answer: ";
}
