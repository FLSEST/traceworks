package traceworks.core.register;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code todo_item:} lines as register input: the line format, each line one finding. */
class TodoItemLinesTest {

    @Test
    void aLine_isAFinding_withTheTitleAsSubject_thePriorityAsSeverity_andTheTagsAndDescription() {
        List<ReportedFinding> findings = TodoItemLines.parse(
                "todo_item: Test the chain executor | HIGH | backend,testing | 160-LOC loop untested. See docs/REPORT.md");

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.ruleKey()).isEqualTo("todo-item");
            assertThat(f.subject()).isEqualTo("Test the chain executor");
            assertThat(f.title()).isEqualTo("Test the chain executor");
            assertThat(f.severity()).isEqualTo(Severity.HIGH);
            assertThat(f.tags()).containsExactly("backend", "testing");
            assertThat(f.description()).isEqualTo("160-LOC loop untested. See docs/REPORT.md");
            assertThat(f.evidence()).isNull();
        });
    }

    @Test
    void theDescription_isOptional_andTheFormatIsTolerant() {
        String text = """
                Some prose first.
                ## todo_item: Heading form | MEDIUM | docs
                **todo_item**: Bold form | low | a, b
                [item 3]: `todo-item`: After a foreach prefix | CRITICAL | x | with text
                not a marker: todo_item: mid-line | HIGH | t
                todo_item: no tags field | HIGH
                """;

        List<ReportedFinding> findings = TodoItemLines.parse(text);

        assertThat(findings).extracting(ReportedFinding::title, ReportedFinding::severity, ReportedFinding::description)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Heading form", Severity.MEDIUM, null),
                        org.assertj.core.groups.Tuple.tuple("Bold form", Severity.LOW, null),
                        org.assertj.core.groups.Tuple.tuple("After a foreach prefix", Severity.CRITICAL, "with text"));
        assertThat(findings.get(1).tags()).containsExactly("a", "b");
    }

    @Test
    void aDescriptionMayContainAPipe_andMarkdownIsStripped() {
        List<ReportedFinding> findings = TodoItemLines.parse(
                "todo_item: `Title` | HIGH | t | a `code` | with a pipe\r\n");

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.title()).isEqualTo("Title");
            assertThat(f.description()).isEqualTo("a code | with a pipe");
        });
    }

    @Test
    void noLines_noFindings() {
        assertThat(TodoItemLines.parse(null)).isEmpty();
        assertThat(TodoItemLines.parse("")).isEmpty();
        assertThat(TodoItemLines.parse("todo_item: wrong priority | URGENT | t")).isEmpty();
    }


    @Test
    void aLineThatCostsTooMuch_isGivenUpAtTheTimeLimit_insteadOfKeepingTheRegisterStuck() {
        // A title followed by very many spaces and no pipe: the line's pattern backtracks in quadratic time
        String text = "todo_item: x" + " ".repeat(400_000);

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(20), () ->
                org.assertj.core.api.Assertions.assertThatThrownBy(
                                () -> TodoItemLines.parse(text, java.time.Duration.ofMillis(300)))
                        .isInstanceOf(traceworks.core.util.TimedRegex.TimedOut.class));
    }

    @Test
    void aLargeOrdinaryReport_isReadWellWithinTheTimeLimit() {
        String text = "todo_item: Title of a finding | HIGH | a,b | Some description text\n".repeat(50_000);

        assertThat(TodoItemLines.parse(text)).hasSize(50_000);
    }

    /**
     * A line states a finding as an envelope does, so it has the envelope's limits: a title or a description beyond
     * them refuses the report. Without them, one line of a
     * report was a finding of any size, which the register kept and every later replay had to read.
     */
    @Test
    void aTitleOrADescriptionBeyondTheEnvelopesLimit_isRefused() {
        String longTitle = "x".repeat(AuditResultParser.TITLE_LIMIT + 1);
        String longDescription = "y".repeat(AuditResultParser.DESCRIPTION_LIMIT + 1);

        assertThat(TodoItemLines.parse("todo_item: " + "x".repeat(AuditResultParser.TITLE_LIMIT) + " | LOW | t | "
                + "y".repeat(AuditResultParser.DESCRIPTION_LIMIT))).hasSize(1);
        assertThatThrownBy(() -> TodoItemLines.parse("todo_item: fine | LOW | t\ntodo_item: " + longTitle + " | LOW | t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("todo_item line 2: the title is 2001 characters long, at most 2000 characters are read");
        assertThatThrownBy(() -> TodoItemLines.parse("todo_item: fine | LOW | t | " + longDescription))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("todo_item line 1: the description is 20001 characters long, at most 20000 characters are"
                        + " read");
    }
}
