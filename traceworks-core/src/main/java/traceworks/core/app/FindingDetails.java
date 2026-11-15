package traceworks.core.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.port.NoSuchRunException;
import traceworks.core.record.FileView;
import traceworks.core.record.RunView;
import traceworks.core.record.StepView;
import traceworks.core.register.Finding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The detail of a finding: what is wrong and how to fix it, as the run that last reported it wrote it down. A
 * register holds what a report stated (rule, subject, severity, evidence), never the words of a model; an audit
 * writes those into {@value #FILE} in its scratch directory, one JSON object per finding of its envelope, and the
 * record of the run keeps the file as a blob. So the detail is read where it was recorded: the run, by the finding's
 * last sighting, and the entry with the finding's rule and subject.
 */
public final class FindingDetails {

    /** The file an audit's report step leaves in the scratch directory. */
    public static final String FILE = "findings.jsonl";
    /** The most entries read for one finding; a file is a step's product and may hold anything. */
    public static final int MAX_ENTRIES = 20;

    private static final ObjectMapper JSON = new ObjectMapper();

    private FindingDetails() {
    }

    /**
     * What was found.
     *
     * @param runId   the run the detail was looked for in; {@code null} when the finding names none
     * @param missing why there is no detail, in a few words; {@code null} when there are entries
     * @param entries the objects of {@value #FILE} with the finding's rule and subject, as the step wrote them:
     *                untrusted, text of a model among it
     */
    public record Detail(String runId, String missing, List<JsonNode> entries) {
        public Detail {
            entries = List.copyOf(entries);
        }
    }

    /** The detail of {@code finding} from the run that last reported it. */
    public static Detail of(RunServices services, Finding finding) {
        String runId = finding.lastSeen() == null ? null : finding.lastSeen().runId();
        if (runId == null) {
            return new Detail(null, "the register names no run that reported it", List.of());
        }
        RunView view;
        try {
            view = services.view(runId);
        } catch (NoSuchRunException e) {
            return missing(runId, "is not in this home");
        } catch (RuntimeException e) {
            return missing(runId, "cannot be read");
        }
        FileView file = file(view);
        if (file == null) {
            return missing(runId, "recorded no " + FILE);
        }
        if (file.blob() == null) {
            return missing(runId, "did not store its " + FILE + " (" + file.reason() + ")");
        }
        Optional<byte[]> bytes;
        try {
            bytes = services.content(runId, file.blob().sha256());
        } catch (RuntimeException e) {
            return missing(runId, "cannot be read");
        }
        if (bytes.isEmpty()) {
            return missing(runId, "no longer has its " + FILE + " (the blob is deleted)");
        }
        List<JsonNode> entries = new ArrayList<>();
        for (String line : new String(bytes.get(), StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank() || entries.size() == MAX_ENTRIES) {
                continue;
            }
            JsonNode entry;
            try {
                entry = JSON.readTree(line);
            } catch (IOException | RuntimeException e) {
                continue;   // a line that is no JSON says nothing about any finding
            }
            if (entry != null && entry.isObject() && same(entry.path("ruleKey"), finding.ruleKey())
                    && same(entry.path("subject"), finding.subject())) {
                entries.add(entry);
            }
        }
        return entries.isEmpty() ? missing(runId, "has no entry for this rule and subject in its " + FILE)
                : new Detail(runId, null, entries);
    }

    private static Detail missing(String runId, String why) {
        return new Detail(runId, "run " + runId + " " + why, List.of());
    }

    /** The register unmasks {@code ¦} to {@code |} in what a report states; the entry is compared the same way. */
    private static boolean same(JsonNode value, String expected) {
        return value.isTextual() && value.asText().replace('¦', '|').equals(expected);
    }

    /** {@value #FILE} as the last step that listed it left it; {@code null} when no step did. */
    private static FileView file(RunView view) {
        List<StepView> steps = view.steps();
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (steps.get(i).files() == null) {
                continue;
            }
            for (FileView file : steps.get(i).files().files()) {
                if (FILE.equals(file.path())) {
                    return file;
                }
            }
        }
        return null;
    }
}
