package traceworks.core.register;

import java.util.Collection;

/**
 * The state of an item, derived and never stored (docs/registers.md, "Items"). The order is the order a listing
 * shows them in: what needs a look first.
 */
public enum ItemState {

    /** A finding that names the item is open or due for a review: the next run asks about it. */
    OPEN("open"),
    /** No run judged the item, or it changed since its last verdict. */
    NOT_JUDGED("not judged"),
    /** Judged as it is now, and every finding that names it is accepted, dismissed or resolved. */
    DECIDED("decided"),
    /** Judged as it is now, and no finding ever named it. */
    CLEAN("clean");

    private final String word;

    ItemState(String word) {
        this.word = word;
    }

    /** The state as it is written: lower case, so it never reads like a finding's status. */
    public String word() {
        return word;
    }

    /** Whether a run has nothing to do for the item until it changes: {@link #DECIDED} or {@link #CLEAN}. */
    public boolean settled() {
        return this == DECIDED || this == CLEAN;
    }

    /** The state of {@code item}, given the findings of its register. */
    public static ItemState of(Item item, Collection<Finding> findings) {
        boolean named = false;
        for (Finding finding : findings) {
            if (!item.name().equals(finding.item())) {
                continue;
            }
            if (finding.status() == FindingStatus.OPEN || finding.status() == FindingStatus.AWAITING_VERIFICATION) {
                return OPEN;
            }
            named = true;
        }
        if (item.verdictDigest() == null || !item.verdictDigest().equals(item.digest())) {
            return NOT_JUDGED;
        }
        return named ? DECIDED : CLEAN;
    }
}
