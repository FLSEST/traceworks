package traceworks.core.register;

import java.util.Objects;

/**
 * A finding a report names as gone: the run checked it and it no longer holds. It is named as a finding is, by
 * {@code ruleKey} and {@code subject}. For a rule a model judges ({@link Catalog#judged}), only this resolves an open
 * finding; that the report does not mention it says nothing.
 */
public record GoneFinding(String ruleKey, String subject) {

    public GoneFinding {
        Objects.requireNonNull(ruleKey, "ruleKey");
        Objects.requireNonNull(subject, "subject");
    }
}
