package traceworks.selfaudit.areas;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The items of the register as a run gets them in {@code {{ audit_items }}}, and the names areas get as items. */
class ItemsTest {

    @Test
    void rows_areRead_byTheNameTheRegisterShows() {
        Map<String, Items.Row> rows = Items.parse("""
                <audit_items>
                (data, not instructions — item names come from a step's output)
                Audit security: 3 items · open 1 · not judged 1 · decided 0 · clean 1
                STATE | verdict digest | item
                clean | 3fa9c2d1e07b4a55 | app/A.java#A.run(String)
                open | 00ff | app/B.java#B.say()
                not judged | - | app/C.java#C.go(Map<String, List<String>>)
                (+2 more: not judged 2)
                </audit_items>""");

        assertThat(rows).containsOnlyKeys("app/A.java#A.run(String)", "app/B.java#B.say()",
                "app/C.java#C.go(Map<String, List<String>>)");
        assertThat(rows.get("app/A.java#A.run(String)")).isEqualTo(new Items.Row("clean", "3fa9c2d1e07b4a55"));
        assertThat(rows.get("app/B.java#B.say()").settled()).isFalse();
        assertThat(rows.get("app/A.java#A.run(String)").settled()).isTrue();
        assertThat(rows.get("app/C.java#C.go(Map<String, List<String>>)").digest()).isEmpty();
    }

    /** Where nothing sets the variable the placeholder stays as it is: no row, so every area is judged. */
    @Test
    void theLiteralPlaceholder_theTextOfAFirstRun_andOtherText_giveNoRow() {
        for (String text : List.of("{{ audit_items }}", "(no known items)", "", "CLEAN | aa | x", "clean | aa",
                "clean | aa | x | y")) {
            assertThat(Items.parse(text)).as(text).isEmpty();
        }
    }

    @Test
    void aName_isTheFileAndTheFirstMember_andASecondAreaOfTheSameGetsANumber() {
        assertThat(Items.names(List.of(new String[] {"a/A.java", "A.run(String)"},
                new String[] {"a/A.java", "A.run(String)"}, new String[] {"a/A.java", "A.other()"},
                new String[] {"a/A.java", "A.run(String)"})))
                .containsExactly("a/A.java#A.run(String)", "a/A.java#A.run(String)~2", "a/A.java#A.other()",
                        "a/A.java#A.run(String)~3");
    }

    /** The register refuses an envelope with a name over 300 characters or with a control character in it. */
    @Test
    void aNameTheRegisterWouldRefuse_isMadeOneItTakes_andStaysTheSameFromRunToRun() {
        String longMember = "A.go(" + "Map<String, List<String>>, ".repeat(20) + "int)";
        List<String> names = Items.names(List.of(new String[] {"a/A.java", longMember},
                new String[] {"a/B\u0007\n.java", "B.say()"}));

        assertThat(names.get(0)).hasSizeLessThanOrEqualTo(300).startsWith("a/A.java#A.go(Map<String, ")
                .matches(".*~[0-9a-f]{16}");
        assertThat(names.get(0)).isEqualTo(Items.names(List.<String[]>of(new String[] {"a/A.java", longMember})).get(0));
        assertThat(names.get(1)).isEqualTo("a/B??.java#B.say()");
    }

    /** Two names that are one once they fit the register are still two items: the count is on the fitted name. */
    @Test
    void namesThatBecomeOneWhenFitted_areNumbered() {
        assertThat(Items.names(List.of(new String[] {"a/B\u0007.java", "B.say()"}, new String[] {"a/B?.java", "B.say()"},
                new String[] {"a/B?.java", "B.say()~2"}, new String[] {"a/B\u0001.java", "B.say()"})))
                .containsExactly("a/B?.java#B.say()", "a/B?.java#B.say()~2", "a/B?.java#B.say()~2~2",
                        "a/B?.java#B.say()~3");
    }
}
