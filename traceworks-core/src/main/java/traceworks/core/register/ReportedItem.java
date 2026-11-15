package traceworks.core.register;

import java.util.Objects;

/**
 * One item of an audit as a report lists it: what one verdict is about, such as a class or an area of the code
 * (docs/registers.md, "Items").
 *
 * @param name   the item's stable name
 * @param digest the digest of what a verdict about the item sees, as the audit computed it in this run
 * @param judged whether this run gave a verdict for the item; {@code false} for one left out or one whose call failed
 */
public record ReportedItem(String name, String digest, boolean judged) {

    public ReportedItem {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(digest, "digest");
    }
}
