package traceworks.core.register;

/**
 * One change a report made to a register, or one thing worth noting about it.
 *
 * @param detail the reason or the place, such as {@code absent}, {@code regression} or {@code findings[3]};
 *               {@code null} when the kind says it all
 */
public record Change(Kind kind, String findingId, String detail) {

    public enum Kind {
        /** The finding was not known. */
        NEW,
        /** An open finding stated again. */
        SEEN,
        /**
         * An open or awaiting finding that is gone: of a measured rule, the report no longer states it
         * ({@code absent}); of a judged rule, the report names it as gone ({@code gone}).
         */
        RESOLVED,
        /**
         * An open or awaiting finding of a judged rule that the report neither states nor names as gone: it stays as
         * it is, since a model that does not name a finding again has not said that it is fixed.
         */
        NOT_MENTIONED,
        /** A resolved, awaiting or triaged finding that is open again; the detail says why. */
        REOPENED,
        /** A finding in a terminal status became due for a review (from Task 7 on). */
        REVIEW_DUE,
        /**
         * A finding of an identity that another finding of the same report has too, and that does not count: the
         * most severe of them counts, the first among equals. The detail is its place, {@code findings[3]}.
         */
        DUPLICATE_IN_REPORT,
        /** An accepted or dismissed finding stated again; it stays as it is. */
        UNCHANGED_TRIAGED
    }
}
