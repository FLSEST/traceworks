package traceworks.core.blocks.compress;

import java.util.Locale;

/**
 * Number formatting for compression notes and headers ("740,808", "−4,102 chars"), in English like every
 * other block's step output.
 */
public final class CompressionNumbers {

    private CompressionNumbers() {
    }

    public static String fmt(long n) {
        return String.format(Locale.ENGLISH, "%,d", n);
    }

    public static String chars(long removed) {
        return "−" + fmt(removed) + " chars";
    }

    /** Length change of a stage: "−N chars" when it shrank (or stayed equal), "+N chars" when it grew. */
    public static String signedChars(long delta) {
        return delta > 0 ? "+" + fmt(delta) + " chars" : chars(-delta);
    }

    public static String lines(long removed) {
        return "−" + fmt(removed) + " lines";
    }
}
