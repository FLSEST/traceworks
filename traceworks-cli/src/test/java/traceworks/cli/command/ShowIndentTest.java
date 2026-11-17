package traceworks.cli.command;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** The stream {@code show} prints the lines of a child run through, each behind an indent. */
class ShowIndentTest {

    private static byte[] printed(Charset terminal, String line) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream target = new PrintStream(bytes, true, terminal);
        PrintStream inner = ShowCommand.indented(target, "    ");
        inner.print(line + "\n\nnext\n");
        inner.flush();
        target.flush();
        return bytes.toByteArray();
    }

    /**
     * The lines of a child run reach the terminal in the terminal's own encoding, as every other line does. Encoded
     * as UTF-8 on a terminal that is not, a harmless letter such as U+00DB would arrive as the bytes C3 9B, and 9B
     * is the control sequence introducer there: text of a step would steer the terminal.
     */
    @Test
    void theLinesOfAChildRun_areEncodedAsTheTerminalIs_soNoLetterBecomesAControlByte() {
        String line = "step 0  Û31m name Ý0;title";

        byte[] latin = printed(StandardCharsets.ISO_8859_1, line);

        for (byte b : latin) {
            assertThat((b & 0xFF) >= 0x80 && (b & 0xFF) <= 0x9F).as("a C1 control byte").isFalse();
        }
        assertThat(new String(latin, StandardCharsets.ISO_8859_1)).isEqualTo("    " + line + "\n\n    next\n");
        assertThat(new String(printed(StandardCharsets.UTF_8, line), StandardCharsets.UTF_8))
                .isEqualTo("    " + line + "\n\n    next\n");
    }
}
