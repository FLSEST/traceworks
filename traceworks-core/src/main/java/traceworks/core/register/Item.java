package traceworks.core.register;

import java.util.Objects;

/**
 * An item of an audit as the register knows it: what one verdict is about, such as a class or an area of the code.
 * The register keeps the digest of what the last verdict saw and the run that gave it; whether the item is clean,
 * open, decided or not judged follows from that and from the findings that name it ({@link ItemState}).
 *
 * @param name          the item's name, stable across runs
 * @param digest        the digest the last report that listed the item gave for it
 * @param verdictDigest the digest the item had when a run last judged it; {@code null} before any verdict
 * @param verdict       the run that last judged it; {@code null} before any verdict
 */
public record Item(String name, String digest, String verdictDigest, Finding.Seen verdict) {

    public Item {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(digest, "digest");
    }
}
