package traceworks.core.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TruncationUtilTest {

    // --- head(s, max) ---

    @Test
    void head_null_returnsEmpty() {
        assertEquals("", TruncationUtil.head(null, 10));
    }

    @Test
    void head_withinBudget_returnsUnchanged() {
        assertEquals("abc", TruncationUtil.head("abc", 3));
    }

    @Test
    void head_longerThanBudget_keepsTheStartAndMarksTheCut() {
        assertEquals("abc...", TruncationUtil.head("abcdef", 3));
    }

    // --- smart(s, head, tail) ---

    @Test
    void smart_null_returnsEmpty() {
        assertEquals("", TruncationUtil.smart(null, 100, 400));
    }

    @Test
    void smart_shorterThanBudget_returnsUnchanged() {
        assertEquals("short output", TruncationUtil.smart("short output", 100, 400));
    }

    @Test
    void smart_exactBudget_returnsUnchangedWithoutMarker() {
        String s = "a".repeat(500);
        String result = TruncationUtil.smart(s, 100, 400);
        assertEquals(s, result);
        assertFalse(result.contains("skipped"));
    }

    @Test
    void smart_longerThanBudget_keepsHeadAndTail() {
        String s = "H".repeat(100) + "M".repeat(300) + "T".repeat(400);
        String result = TruncationUtil.smart(s, 100, 400);
        assertTrue(result.startsWith("H".repeat(100)));
        assertTrue(result.endsWith("T".repeat(400)));
        assertFalse(result.contains("M"));
    }

    @Test
    void smart_marker_countsSkippedAndTotal() {
        String s = "x".repeat(800);
        String result = TruncationUtil.smart(s, 100, 400);
        assertTrue(result.contains("[... skipped 300 of 800 characters ...]"));
    }

    @Test
    void smart_errorAtEnd_survivesTruncation() {
        String s = "noise ".repeat(500) + "HTTP 502 Bad Gateway";
        String result = TruncationUtil.smart(s, 100, 400);
        assertTrue(result.endsWith("HTTP 502 Bad Gateway"));
    }

    // --- window(s, offset, max) ---

    @Test
    void window_null_returnsEmpty() {
        assertEquals("", TruncationUtil.window(null, 0, 8000));
    }

    @Test
    void window_offsetZero_returnsPrefixWithPosition() {
        String s = "abcdefghij";
        String result = TruncationUtil.window(s, 0, 5);
        assertTrue(result.contains("[showing characters 0-5 of 10]"));
        assertTrue(result.contains("abcde"));
        assertFalse(result.contains("fghij"));
    }

    @Test
    void window_midOffset_returnsSliceAndContinuationHint() {
        String s = "0123456789ABCDEFGHIJ";
        String result = TruncationUtil.window(s, 5, 10);
        assertTrue(result.contains("[showing characters 5-15 of 20]"));
        assertTrue(result.contains("56789ABCDE"));
        assertTrue(result.contains("Continue with ?offset=15"));
    }

    @Test
    void window_lastSlice_hasNoContinuationHint() {
        String s = "0123456789";
        String result = TruncationUtil.window(s, 5, 100);
        assertTrue(result.contains("[showing characters 5-10 of 10]"));
        assertTrue(result.contains("56789"));
        assertFalse(result.contains("Continue"));
    }

    @Test
    void window_offsetBeyondLength_returnsExplanation() {
        String result = TruncationUtil.window("abc", 10, 100);
        assertTrue(result.contains("offset 10"));
        assertTrue(result.contains("length 3"));
    }

    @Test
    void window_negativeOffset_clampsToZero() {
        String result = TruncationUtil.window("abcde", -5, 3);
        assertTrue(result.contains("[showing characters 0-3 of 5]"));
        assertTrue(result.contains("abc"));
    }

    @Test
    void window_offsetAtTheEnd_isBeyondTheOutput_andAnEmptyOutputShowsItsEmptySlice() {
        assertEquals("[offset 3 is beyond output length 3]", TruncationUtil.window("abc", 3, 100));
        assertEquals("[showing characters 0-0 of 0]\n", TruncationUtil.window("", 0, 100));
    }

    @Test
    void window_hint_countsTheCharactersAfterTheSlice() {
        assertEquals("[showing characters 5-15 of 20]\n56789ABCDE\n[... 5 more characters. Continue with ?offset=15 ...]",
                TruncationUtil.window("0123456789ABCDEFGHIJ", 5, 10));
    }

    @Test
    void tailAndTailInline_ofATextOfExactlyMax_returnItUnchanged() {
        assertEquals("exact", TruncationUtil.tail("exact", 5));
        assertEquals("exact", TruncationUtil.tailInline("exact", 5));
    }

    // --- cutAfter(s, at, whole) ---

    /** A cut that keeps what follows it falls after a value it would split, also one that starts the text. */
    @Test
    void cutAfter_insideAValueAtTheStart_fallsAfterTheValue() {
        assertEquals(6, TruncationUtil.cutAfter("SECRET-tail", 3, List.of("SECRET")));
        assertEquals(3, TruncationUtil.cutAfter("SECRET-tail", 3, List.of("tail")));
    }

    // --- tail(s, max) ---

    @Test
    void tail_null_returnsEmpty() {
        assertEquals("", TruncationUtil.tail(null, 100));
    }

    @Test
    void tail_shorterThanMax_returnsUnchanged() {
        assertEquals("short", TruncationUtil.tail("short", 100));
    }

    // --- tailInline(s, max) ---

    @Test
    void tailInline_shorterThanMax_returnsUnchanged() {
        assertEquals("short", TruncationUtil.tailInline("short", 100));
    }

    @Test
    void tailInline_longerThanMax_keepsEndSingleLine() {
        String s = "prefix ".repeat(50) + "exit code 7";
        String result = TruncationUtil.tailInline(s, 50);
        assertTrue(result.endsWith("exit code 7"));
        assertTrue(result.startsWith("…"));
        assertEquals(51, result.length());
        assertFalse(result.contains("\n"));
    }

    @Test
    void tail_longerThanMax_keepsEndWithMarker() {
        String s = "x".repeat(900) + "HTTP 500";
        String result = TruncationUtil.tail(s, 100);
        assertTrue(result.endsWith("HTTP 500"));
        assertTrue(result.contains("[... skipped 808 of 908 characters ...]"));
        assertEquals(100, result.length() - result.indexOf("]\n") - 2);
    }

    // --- cutBeforeOpenEnd(s, whole) ---

    /** A text that a limit cut inside a value ends before the value's first characters. */
    @Test
    void cutBeforeOpenEnd_endInsideAValue_endsBeforeIt() {
        String cut = "curl -H 'Authorization: Bearer s3cr3t-t";

        assertEquals("curl -H 'Authorization: Bearer ".length(),
                TruncationUtil.cutBeforeOpenEnd(cut, List.of("s3cr3t-token-0123")));
    }

    /** Its first character alone is a start too, since what followed is not known. */
    @Test
    void cutBeforeOpenEnd_aValuesFirstCharacterAtTheEnd_isLeftOut() {
        assertEquals(4, TruncationUtil.cutBeforeOpenEnd("echos", List.of("s3cr3t-token-0123")));
    }

    @Test
    void cutBeforeOpenEnd_noValueNearTheEnd_keepsTheWholeText() {
        String text = "y\n".repeat(20);

        assertEquals(text.length(), TruncationUtil.cutBeforeOpenEnd(text, List.of("s3cr3t-token-0123")));
        assertEquals(text.length(), TruncationUtil.cutBeforeOpenEnd(text, List.of()));
    }

    /** A whole value at the end is kept: the record finds and redacts it. */
    @Test
    void cutBeforeOpenEnd_aWholeValueAtTheEnd_isKept() {
        assertEquals(18, TruncationUtil.cutBeforeOpenEnd(" s3cr3t-token-0123", List.of("s3cr3t-token-0123")));
    }

    /** The earliest start counts, of whichever value. */
    @Test
    void cutBeforeOpenEnd_twoValuesStartingAtTheEnd_endsBeforeTheEarlierStart() {
        assertEquals(2, TruncationUtil.cutBeforeOpenEnd("a=abcab", List.of("abcabd-0123", "cab-0123")));
    }

    /** A cut before one value's start must not split a whole value there: it ends before that one too. */
    @Test
    void cutBeforeOpenEnd_aCutThatWouldSplitAWholeValue_endsBeforeIt() {
        assertEquals(2, TruncationUtil.cutBeforeOpenEnd("a=TOKEN-ONE-1", List.of("TOKEN-ONE-1", "1-AND-MORE")));
    }

    @Test
    void cutBeforeOpenEnd_nullOrEmptyValues_areIgnored() {
        assertEquals(3, TruncationUtil.cutBeforeOpenEnd("abc", java.util.Arrays.asList(null, "")));
        assertEquals(0, TruncationUtil.cutBeforeOpenEnd(null, List.of("abc-0123")));
    }
}
