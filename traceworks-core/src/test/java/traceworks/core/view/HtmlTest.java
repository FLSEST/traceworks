package traceworks.core.view;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The escaping of record text for the page: what could change how the page reads is shown by its code point. */
class HtmlTest {

    private static String around(int cp) {
        return "a" + Character.toString(cp) + "b";
    }

    /** Every mark, embedding, override and isolate of the bidirectional algorithm, the first and last of each run. */
    @Test
    void text_showsEveryDirectionCharacterByItsCodePoint() {
        for (int cp : new int[] {0x061C, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067,
                0x2068, 0x2069}) {
            assertThat(Html.text(around(cp))).as("%04X", cp).isEqualTo("a[U+" + String.format("%04X", cp) + "]b");
        }
    }

    /** Content keeps its other characters, also those next to the direction ones. */
    @Test
    void text_keepsTheCharactersBesideTheDirectionOnes() {
        for (int cp : new int[] {0x200D, 0x2029, 0x202F, 0x2065, 0x206A}) {
            assertThat(Html.text(around(cp))).as("%04X", cp).isEqualTo(around(cp));
        }
    }

    /** The tag range is shown by code point to its first and last code point, assigned or not; what follows is not. */
    @Test
    void line_showsTheWholeTagRangeByCodePoint() {
        assertThat(Html.line(around(0xE0000))).isEqualTo("a[U+E0000]b");
        assertThat(Html.line(around(0xE0FFF))).isEqualTo("a[U+E0FFF]b");
        assertThat(Html.line(around(0xE1000))).isEqualTo(around(0xE1000));
    }

    @Test
    void cut_ofATextOfExactlyMax_keepsIt_andACutToNothingIsEmpty() {
        assertThat(Html.cut("ab\uD800", 3)).isEqualTo("ab\uD800");
        assertThat(Html.cut("abc", 0)).isEmpty();
        assertThat(Html.cut("a😀b", 2)).as("a pair is not split").isEqualTo("a");
    }
}
