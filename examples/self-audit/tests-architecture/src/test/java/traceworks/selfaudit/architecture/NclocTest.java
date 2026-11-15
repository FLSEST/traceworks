package traceworks.selfaudit.architecture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NclocTest {

    @Test
    void blankAndCommentLines_doNotCount() {
        assertThat(Ncloc.of("""
                /**
                 * Javadoc.
                 */
                package p;

                // a comment
                class A { /* inline */
                    /* a block
                       over lines */ int x; // after code
                }
                """)).isEqualTo(4);
    }

    @Test
    void commentMarkersInLiterals_areCode() {
        assertThat(Ncloc.of("""
                class A {
                    String s = "/* not a comment";
                    char c = '"';
                    String t = "// nor this \\" still a string";
                    int y;
                }
                """)).isEqualTo(6);
    }

    @Test
    void aTextBlock_countsItsLines_butNotItsBlankOnes() {
        String text = "class A {\n    String s = \"\"\"\n        one /* two\n\n        three \\\n        \"\"\";\n}\n";

        assertThat(Ncloc.of(text)).isEqualTo(6);
    }

    @Test
    void carriageReturns_endLines() {
        assertThat(Ncloc.of("class A {\r\n// c\r\n}\r")).isEqualTo(2);
        assertThat(Ncloc.of("")).isZero();
    }
}
