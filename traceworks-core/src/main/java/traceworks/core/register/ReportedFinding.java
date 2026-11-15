package traceworks.core.register;

import java.util.List;
import java.util.Objects;

/**
 * One finding as a report states it: the identity of a finding is {@code ruleKey} and {@code subject}.
 *
 * @param description what is wrong, or {@code null}
 * @param evidence    what shows it, or {@code null}
 * @param tags        never {@code null}
 * @param item        the name of the item of the audit the finding is about, or {@code null} for a finding that
 *                    belongs to none, such as a measured one
 */
public record ReportedFinding(String ruleKey, String subject, Severity severity, String title, String description,
                              String evidence, List<String> tags, String item) {

    /** A finding that names no item. */
    public ReportedFinding(String ruleKey, String subject, Severity severity, String title, String description,
                           String evidence, List<String> tags) {
        this(ruleKey, subject, severity, title, description, evidence, tags, null);
    }

    public ReportedFinding {
        Objects.requireNonNull(ruleKey, "ruleKey");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(title, "title");
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
