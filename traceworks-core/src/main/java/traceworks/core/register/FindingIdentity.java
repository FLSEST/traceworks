package traceworks.core.register;

import traceworks.core.record.EventChain;

import java.nio.charset.StandardCharsets;

/**
 * The identity of a finding: the register key, the rule key and the subject, joined and hashed. There is no
 * normalisation of subjects (docs/workflow-format.md): a reworded subject is a new finding.
 */
public final class FindingIdentity {

    private FindingIdentity() {
    }

    /** {@code sha256(key | ruleKey | subject)} as 64 hex characters, with {@code |} and {@code \} in a part escaped. */
    public static String of(String auditKey, String ruleKey, String subject) {
        String joined = escape(auditKey) + "|" + escape(ruleKey) + "|" + escape(subject);
        return EventChain.sha256Hex(joined.getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String part) {
        return part.replace("\\", "\\\\").replace("|", "\\|");
    }
}
