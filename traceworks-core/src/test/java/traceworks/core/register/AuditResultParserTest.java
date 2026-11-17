package traceworks.core.register;

import traceworks.core.compat.EarlierNames;
import traceworks.core.register.AuditResultParser.ParseResult;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audit envelope, {@code <!-- traceworks:audit-result:v1 {...} -->}, and its strict reading: a boolean
 * {@code snapshotComplete}, an integer {@code expectedFindings}, string fields only, limits, and every error
 * collected.
 */
class AuditResultParserTest {

    private static ParseResult parse(String text) {
        return AuditResultParser.parse(text);
    }

    private static String envelope(String extraField, int findingCount) {
        StringBuilder findings = new StringBuilder();
        for (int i = 0; i < findingCount; i++) {
            if (i > 0) findings.append(",");
            findings.append("{\"ruleKey\":\"size.class\",\"subject\":\"C").append(i)
                    .append(".java\",\"severity\":\"LOW\",\"title\":\"t").append(i).append("\"}");
        }
        return "<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true" + extraField
                + ",\"findings\":[" + findings + "]}\n-->";
    }

    private static String envelopeWith(String ruleKey, String subject) {
        return "<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,\"findings\":[{\"ruleKey\":\"" + ruleKey
                + "\",\"subject\":\"" + subject + "\",\"severity\":\"MEDIUM\",\"title\":\"t\"}]}\n-->";
    }

    private static String envelopeDescribing(String description) {
        return "<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,\"findings\":[{\"ruleKey\":\"sec.xss\","
                + "\"subject\":\"Report.tsx\",\"severity\":\"LOW\",\"title\":\"t\",\"description\":\""
                + description + "\"},{\"ruleKey\":\"sec.xss\",\"subject\":\"Other.tsx\",\"severity\":\"LOW\","
                + "\"title\":\"t\"}]}\n-->";
    }

    /**
     * The one test of the earlier marker: a report written before the marker became {@code traceworks:audit-result:v1}
     * is read like any other, alone and as the last envelope after one under the marker of today.
     */
    @Test
    void theEarlierMarker_isStillRead() {
        String earlier = "<!-- " + EarlierNames.AUDIT_ENVELOPE_WORD + ":audit-result:v1\n{\"snapshotComplete\":true,\"findings\":[{\"ruleKey\":"
                + "\"size.class\",\"subject\":\"Old.java\",\"severity\":\"LOW\",\"title\":\"t\"}]}\n-->";

        ParseResult alone = parse("# Report\n\n" + earlier);
        ParseResult last = parse(envelope("", 2) + "\n# Report\n\n" + earlier);

        assertThat(alone.valid()).isTrue();
        assertThat(alone.markdown()).isEqualTo("# Report");
        assertThat(alone.findings()).extracting(ReportedFinding::subject).containsExactly("Old.java");
        assertThat(AuditResultParser.hasEnvelopeStart(earlier)).isTrue();
        assertThat(last.findings()).as("the last envelope counts, whichever marker it has")
                .extracting(ReportedFinding::subject).containsExactly("Old.java");
        assertThat(last.markdown()).isEqualTo("# Report");
    }

    @Test
    void parsesCompleteSnapshotAndRemovesEnvelopeFromMarkdown() {
        String output = """
                # Security report

                Details remain visible.

                <!-- traceworks:audit-result:v1
                {"snapshotComplete":true,"findings":[{
                  "ruleKey":"security.secret",
                  "subject":"application.yml",
                  "severity":"HIGH",
                  "title":"Secret in configuration",
                  "description":"Move it to the environment",
                  "evidence":"application.yml:12",
                  "tags":["backend","security"]
                }]}
                -->
                """;

        ParseResult result = parse(output);

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.markdown()).isEqualTo("# Security report\n\nDetails remain visible.");
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.ruleKey()).isEqualTo("security.secret");
            assertThat(finding.subject()).isEqualTo("application.yml");
            assertThat(finding.severity()).isEqualTo(Severity.HIGH);
            assertThat(finding.title()).isEqualTo("Secret in configuration");
            assertThat(finding.description()).isEqualTo("Move it to the environment");
            assertThat(finding.evidence()).isEqualTo("application.yml:12");
            assertThat(finding.tags()).containsExactly("backend", "security");
        });
    }

    // ---- "gone": the findings the run checked and found gone ----

    private static String envelopeGone(String gone) {
        return "# R\n<!-- traceworks:audit-result:v1 {\"snapshotComplete\":true,\"findings\":[],\"gone\":" + gone
                + "} -->";
    }

    @Test
    void readsTheFindingsTheRunFoundGone() {
        ParseResult result = parse(envelopeGone("[{\"ruleKey\":\"split\",\"subject\":\"A.java\",\"reason\":\"moved\"},"
                + "{\"ruleKey\":\"move\",\"subject\":\"B.java\"}]"));

        assertThat(result.valid()).as(result.errors().toString()).isTrue();
        assertThat(result.gone()).containsExactly(new GoneFinding("split", "A.java"), new GoneFinding("move", "B.java"));
    }

    @Test
    void anEnvelopeWithoutGone_hasNone() {
        ParseResult result = parse("<!-- traceworks:audit-result:v1 {\"snapshotComplete\":true,\"findings\":[]} -->");

        assertThat(result.valid()).isTrue();
        assertThat(result.gone()).isEmpty();
    }

    @Test
    void rejectsAGoneListThatCannotBeRead_namingEveryProblem() {
        assertThat(parse(envelopeGone("\"all\"")).errors()).containsExactly("gone must be an array");
        assertThat(parse(envelopeGone("[7,{\"ruleKey\":\"r\"},{\"ruleKey\":\"\",\"subject\":3}]")).errors())
                .containsExactly("gone[0] must be an object", "gone[1].subject is required",
                        "gone[2].ruleKey is required", "gone[2].subject must be a string");
        ParseResult tooMany = parse(envelopeGone("[" + "{\"ruleKey\":\"r\",\"subject\":\"s\"},".repeat(1000)
                + "{\"ruleKey\":\"r\",\"subject\":\"s\"}]"));
        assertThat(tooMany.valid()).isFalse();
        assertThat(tooMany.errors()).containsExactly("the envelope names 1001 findings as gone, at most 1000 are read");
    }

    /** A gone list of exactly the most entries is read. */
    @Test
    void acceptsAGoneListOfExactlyTheMostEntries() {
        ParseResult most = parse(envelopeGone("[" + "{\"ruleKey\":\"r\",\"subject\":\"s\"},".repeat(999)
                + "{\"ruleKey\":\"r\",\"subject\":\"s\"}]"));

        assertThat(most.errors()).isEmpty();
    }

    /** An expectedFindings of null says nothing, as a missing one does. */
    @Test
    void acceptsAnExpectedFindingsOfNull() {
        ParseResult result = parse("Report\n<!-- traceworks:audit-result:v1\n"
                + "{\"snapshotComplete\":true,\"findings\":[],\"expectedFindings\":null}\n-->");

        assertThat(result.valid()).as(String.valueOf(result.errors())).isTrue();
    }

    @Test
    void acceptsEmptyCompleteSnapshot() {
        ParseResult result = parse("Report\n<!-- traceworks:audit-result:v1\n"
                + "{\"snapshotComplete\":true,\"findings\":[]}\n-->");

        assertThat(result.valid()).isTrue();
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void rejectsPartialSnapshot() {
        ParseResult result = parse("<!-- traceworks:audit-result:v1\n"
                + "{\"snapshotComplete\":false,\"findings\":[]}\n-->");

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).singleElement().asString().contains("snapshotComplete");
    }

    @Test
    void rejectsMissingStableIdentity() {
        ParseResult result = parse("<!-- traceworks:audit-result:v1\n"
                + "{\"snapshotComplete\":true,\"findings\":[{\"severity\":\"LOW\",\"title\":\"x\"}]}\n-->");

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anySatisfy(e -> assertThat(e).contains("findings[0].ruleKey is required"))
                .anySatisfy(e -> assertThat(e).contains("findings[0].subject is required"));
    }

    @Test
    void rejectsOutputWithoutEnvelope() {
        ParseResult result = parse("# Normal workflow output");

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).containsExactly("Audit result envelope v1 is missing");
        assertThat(result.markdown()).isEqualTo("# Normal workflow output");
    }

    @Test
    void rejectsEnvelopeThatDeliversFewerFindingsThanPromised() {
        ParseResult result = parse(envelope(",\"expectedFindings\":5", 2));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).singleElement().asString().contains("expectedFindings says 5")
                .contains("carries 2");
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void acceptsEnvelopeWhosePromiseMatches() {
        ParseResult result = parse(envelope(",\"expectedFindings\":2", 2));

        assertThat(result.valid()).isTrue();
        assertThat(result.findings()).hasSize(2);
    }

    @Test
    void rejectsNonNumericPromise() {
        ParseResult result = parse(envelope(",\"expectedFindings\":\"many\"", 2));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).singleElement().asString().contains("expectedFindings must be an integer");
    }

    @Test
    void envelopeWithoutPromiseStaysValid() {
        assertThat(parse(envelope("", 2)).findings()).hasSize(2);
    }

    @Test
    void anEmptySnapshotWithAPromiseOfZero_isValid() {
        ParseResult result = parse(envelope(",\"expectedFindings\":0", 0));

        assertThat(result.valid()).isTrue();
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void twoEnvelopes_theLastOneCounts_andNeitherStaysInTheMarkdown() {
        String output = "Replay of the previous run:\n" + envelopeWith("size.class", "Old.java")
                + "\n\n# New report\n" + envelopeWith("size.class", "New.java");

        ParseResult result = parse(output);

        assertThat(result.valid()).isTrue();
        assertThat(result.findings()).singleElement()
                .satisfies(finding -> assertThat(finding.subject()).isEqualTo("New.java"));
        assertThat(result.markdown()).isEqualTo("Replay of the previous run:\n\n\n# New report")
                .doesNotContain("traceworks:audit-result");
    }

    @Test
    void oneEnvelope_behavesAsBefore_withTextOnBothSides() {
        ParseResult result = parse("Lead-in\n" + envelopeWith("size.class", "A.java") + "\nPostscript");

        assertThat(result.valid()).isTrue();
        assertThat(result.markdown()).isEqualTo("Lead-in\n\nPostscript");
    }

    @Test
    void truncatedEchoBeforeTheOwnEnvelope_doesNotSpoilIt() {
        String echo = "<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,\"findings\":[{\"ruleKey\":"
                + "\"size.class\",\"subject\":\"Old.java\"\n[...truncated]";

        ParseResult result = parse(echo + "\n\n# New report\n" + envelopeWith("size.class", "New.java"));

        assertThat(result.valid()).isTrue();
        assertThat(result.findings()).singleElement()
                .satisfies(finding -> assertThat(finding.subject()).isEqualTo("New.java"));
    }

    @Test
    void cutOffOwnEnvelope_doesNotFallBackToAnEarlierOne() {
        String output = envelopeWith("size.class", "Old.java") + "\n\n# New report\n"
                + "<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,\"findings\":[{\"ruleKey\":\"size.cl";

        ParseResult result = parse(output);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).containsExactly("The last audit result envelope v1 is incomplete or unreadable");
        assertThat(result.findings()).isEmpty();
    }

    /** A cut-off envelope that starts right at the end of a complete one does not fall back to it either. */
    @Test
    void cutOffOwnEnvelope_rightAfterAnEarlierOne_doesNotFallBackToIt() {
        String output = envelopeWith("size.class", "Old.java")
                + "<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,\"findings\":[{\"ruleKey\":\"size.cl";

        ParseResult result = parse(output);

        assertThat(result.valid()).isFalse();
        assertThat(result.findings()).isEmpty();
    }

    /** The output limit of a command cuts a report wherever it falls: a cut envelope rejects the report. */
    @Test
    void cutEnvelope_isIncomplete_andRejected() {
        String text = "report\n<!-- traceworks:audit-result:v1 {\"snapshotComplete\":true,\"findings\":["
                + "{\"ruleKey\":\"a\"";

        ParseResult result = parse(text);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors().getFirst()).startsWith("The last audit result envelope v1 is incomplete or unreadable");
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void envelopeStartInsideAFinding_isNoEnvelopeOfItsOwn() {
        String output = "# Report\n<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,\"findings\":[{"
                + "\"ruleKey\":\"sec.xss\",\"subject\":\"Report.tsx\",\"severity\":\"LOW\",\"title\":\"t\","
                + "\"description\":\"Shows <!-- traceworks:audit-result:v1 from user text\"}]}\n-->";

        ParseResult result = parse(output);

        assertThat(result.valid()).isTrue();
        assertThat(result.findings()).singleElement()
                .satisfies(finding -> assertThat(finding.description()).contains("from user text"));
        assertThat(result.markdown()).isEqualTo("# Report");
    }

    @Test
    void quotedStartWithJsonInsideAFinding_keepsTheEnclosingEnvelope() {
        for (String quote : new String[] {"Shows <!-- traceworks:audit-result:v1 {\\\"a\\\":1 here",
                "Shows <!-- traceworks:audit-result:v1 {} here"}) {
            ParseResult result = parse("# Report\n" + envelopeDescribing(quote));

            assertThat(result.valid()).as(quote).isTrue();
            assertThat(result.findings()).as(quote).extracting(ReportedFinding::subject)
                    .containsExactly("Report.tsx", "Other.tsx");
        }
    }

    @Test
    void quotedMarkerAfterTheEnvelope_withoutJson_isNoEnvelope() {
        ParseResult result = parse(envelopeWith("size.class", "A.java")
                + "\nThe envelope starts with `<!-- traceworks:audit-result:v1`.");

        assertThat(result.valid()).isTrue();
        assertThat(result.markdown()).isEqualTo("The envelope starts with `<!-- traceworks:audit-result:v1`.");
    }

    @Test
    void startWithJsonAfterTheLastCompleteEnvelope_makesTheReportInvalid() {
        ParseResult result = parse(envelopeWith("size.class", "A.java")
                + "\nPostscript: <!-- traceworks:audit-result:v1 {\"snapshotComplete\":");

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).containsExactly("The last audit result envelope v1 is incomplete or unreadable");
    }

    @Test
    void unreadableJson_isInvalid_andNamesTheParserReason() {
        ParseResult result =
                parse("<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,\"findings\":[],}\n-->");

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).singleElement().asString()
                .startsWith("The last audit result envelope v1 is incomplete or unreadable: ")
                .contains("Unexpected character");
        assertThat(result.markdown()).isEmpty();
    }

    @Test
    void severity_doesNotDependOnTheDefaultLocale() throws Exception {
        String output = "Report\n<!-- traceworks:audit-result:v1\n"
                + "{\"snapshotComplete\":true,\"findings\":[{\"ruleKey\":\"r\",\"subject\":\"s\","
                + "\"severity\":\"critical\",\"title\":\"t\"}]}\n-->";

        ParseResult result = inLocale(Locale.forLanguageTag("tr-TR"), () -> parse(output));

        assertThat(result.valid()).isTrue();
        assertThat(result.findings()).singleElement()
                .satisfies(finding -> assertThat(finding.severity()).isEqualTo(Severity.CRITICAL));
    }

    private static <T> T inLocale(Locale locale, Callable<T> call) throws Exception {
        Locale before = Locale.getDefault();
        Locale.setDefault(locale);
        try {
            return call.call();
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void anUnknownSeverity_isAnError_namingTheFinding() {
        ParseResult result = parse("<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,\"findings\":[{"
                + "\"ruleKey\":\"r\",\"subject\":\"s\",\"severity\":\"URGENT\",\"title\":\"t\"}]}\n-->");

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).singleElement().asString().contains("findings[0].severity")
                .contains("CRITICAL, HIGH, MEDIUM, LOW");
    }

    // --- Strict reading (docs/workflow-format.md) ---

    @Test
    void snapshotComplete_mustBeTheJsonBooleanTrue() {
        for (String value : new String[] {"\"true\"", "1"}) {
            ParseResult result = parse("<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":" + value
                    + ",\"findings\":[]}\n-->");

            assertThat(result.valid()).as(value).isFalse();
            assertThat(result.errors()).as(value).singleElement().asString()
                    .isEqualTo("snapshotComplete must be the JSON boolean true");
        }
    }

    @Test
    void expectedFindings_mustBeAnInteger() {
        ParseResult result = parse(envelope(",\"expectedFindings\":2.7", 2));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).singleElement().asString().contains("expectedFindings must be an integer");
    }

    @Test
    void aNonStringDescriptionOrEvidence_isAnError() {
        ParseResult result = parse("<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,\"findings\":[{"
                + "\"ruleKey\":\"r\",\"subject\":\"s\",\"severity\":\"LOW\",\"title\":\"t\",\"description\":7,"
                + "\"evidence\":[\"x\"]}]}\n-->");

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).containsExactly("findings[0].description must be a string",
                "findings[0].evidence must be a string");
    }

    @Test
    void aTagThatIsNoString_isAnError() {
        ParseResult result = parse("<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,\"findings\":[{"
                + "\"ruleKey\":\"r\",\"subject\":\"s\",\"severity\":\"LOW\",\"title\":\"t\",\"tags\":[\"a\",1]}]}\n-->");

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).containsExactly("findings[0].tags[1] must be a string");
    }

    @Test
    void moreThan1000Findings_isAnError_namingTheLimit() {
        ParseResult result = parse(envelope("", 1001));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).singleElement().asString().contains("1001 findings").contains("at most 1000");
        assertThat(parse(envelope("", 1000)).valid()).isTrue();
    }

    @Test
    void aFieldBeyondItsCap_isAnError_namingTheField() {
        String subject = "s".repeat(AuditResultParser.SUBJECT_LIMIT + 1);
        ParseResult result = parse(envelopeWith("r", subject));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).singleElement().asString().contains("findings[0].subject")
                .contains("at most " + AuditResultParser.SUBJECT_LIMIT + " characters");
    }

    @Test
    void errors_areCollected_upTo20() {
        StringBuilder findings = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            if (i > 0) findings.append(",");
            findings.append("{\"subject\":\"s\",\"severity\":\"LOW\",\"title\":\"t\"}");
        }
        ParseResult result = parse("<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,\"findings\":["
                + findings + "]}\n-->");

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).hasSize(21);
        assertThat(result.errors().getFirst()).isEqualTo("findings[0].ruleKey is required");
        assertThat(result.errors().get(19)).isEqualTo("findings[19].ruleKey is required");
        assertThat(result.errors().getLast()).isEqualTo("and 5 more");
    }

    @Test
    void exactly20Errors_areAllNamed_withoutAMoreLine() {
        StringBuilder findings = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            if (i > 0) findings.append(",");
            findings.append("{\"subject\":\"s\",\"severity\":\"LOW\",\"title\":\"t\"}");
        }
        ParseResult result = parse("<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,\"findings\":["
                + findings + "]}\n-->");

        assertThat(result.errors()).hasSize(20).last().isEqualTo("findings[19].ruleKey is required");
    }

    /** A report whose envelope carries no list of findings is no empty snapshot: it is not read. */
    @Test
    void anEnvelopeWithoutAFindingsArray_isInvalid() {
        for (String findings : new String[] {"", ",\"findings\":{}", ",\"findings\":null"}) {
            ParseResult result = parse("<!-- traceworks:audit-result:v1 {\"snapshotComplete\":true" + findings
                    + "} -->");

            assertThat(result.valid()).as(findings).isFalse();
            assertThat(result.errors()).as(findings).containsExactly("findings must be an array");
        }
    }

    @Test
    void aFindingThatIsNoObject_orTagsThatAreNoArray_areErrors() {
        assertThat(parse("<!-- traceworks:audit-result:v1 {\"snapshotComplete\":true,\"findings\":[\"x\"]} -->")
                .errors()).containsExactly("findings[0] must be an object");
        assertThat(parse(envelopeOf("{\"ruleKey\":\"r\",\"subject\":\"s\",\"severity\":\"LOW\",\"title\":\"t\","
                + "\"tags\":\"backend\"}")).errors()).containsExactly("findings[0].tags must be an array of strings");
    }

    @Test
    void hasEnvelopeStart_tellsAnEnvelopeFromTodoItemLines() {
        assertThat(AuditResultParser.hasEnvelopeStart("x <!-- traceworks:audit-result:v1 {")).isTrue();
        assertThat(AuditResultParser.hasEnvelopeStart("todo_item: a | HIGH | t")).isFalse();
        assertThat(AuditResultParser.hasEnvelopeStart("the marker <!-- traceworks:audit-result:v1 alone")).isFalse();
    }

    @Test
    void nullOrBlankText_hasNoEnvelope_andAnEmptyMarkdown() {
        assertThat(parse(null).valid()).isFalse();
        assertThat(parse(null).markdown()).isEmpty();
        assertThat(parse("  \n").markdown()).isEmpty();
    }


    @Test
    void manyStartsThatNeverClose_areReadInLinearTime() {
        // Each start used to scan to the end of the text for its end, so the time grew with the square of the output
        String text = "<!--traceworks:audit-result:v1{".repeat(60_000);

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () -> {
            AuditResultParser.ParseResult result = AuditResultParser.parse(text);
            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).singleElement().asString().startsWith(AuditResultParser.INCOMPLETE);
            assertThat(result.markdown()).isEqualTo(text);
        });
    }

    @Test
    void manyStartsBeforeOneEnd_areReadInLinearTime_andTheLastStartIsTheEnvelope() {
        String starts = "<!--traceworks:audit-result:v1{".repeat(60_000);
        String text = starts + "\"snapshotComplete\": true, \"findings\": []} -->";

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () -> {
            AuditResultParser.ParseResult result = AuditResultParser.parse(text);
            assertThat(result.valid()).isTrue();
            assertThat(result.findings()).isEmpty();
        });
    }

    private static String envelopeOf(String finding) {
        return "<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,\"findings\":[" + finding + "]}\n-->";
    }

    /** A blank identity is as good as none: two findings with a blank subject could not be told apart. */
    @Test
    void aRequiredFieldThatIsBlank_isAnErrorLikeAMissingOne() {
        ParseResult result = parse(envelopeOf(
                "{\"ruleKey\":\"r\",\"subject\":\"  \",\"severity\":\"LOW\",\"title\":\"\"}"));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).containsExactly("findings[0].subject is required",
                "findings[0].title is required");
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void blankTags_areLeftOut() {
        ParseResult result = parse(envelopeOf("{\"ruleKey\":\"r\",\"subject\":\"s\",\"severity\":\"LOW\","
                + "\"title\":\"t\",\"tags\":[\"backend\",\"\",\"  \",\"security\"]}"));

        assertThat(result.valid()).isTrue();
        assertThat(result.findings()).singleElement()
                .satisfies(finding -> assertThat(finding.tags()).containsExactly("backend", "security"));
    }

    @Test
    void aFieldOfExactlyItsCap_isRead() {
        String subject = "s".repeat(AuditResultParser.SUBJECT_LIMIT);

        ParseResult result = parse(envelopeWith("r", subject));

        assertThat(result.valid()).isTrue();
        assertThat(result.findings()).singleElement()
                .satisfies(finding -> assertThat(finding.subject()).isEqualTo(subject));
    }

    // ---- items: what the audit judges, each with the digest of what its verdict saw (docs/registers.md, "Items")

    private static String withItems(String items, String extraField, String findingExtra) {
        return "<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true" + extraField
                + ",\"findings\":[{\"ruleKey\":\"r\",\"subject\":\"p.A#1\",\"severity\":\"LOW\",\"title\":\"t\""
                + findingExtra + "}],\"items\":" + items + "}\n-->";
    }

    @Test
    void items_areRead_withDigestAndJudged() {
        ParseResult result = parse(withItems("[{\"name\":\"p.A\",\"digest\":\"3fa9c2d1e07b4a55\",\"judged\":true,"
                + "\"more\":\"not read\"},{\"name\":\"p.B\",\"digest\":\"00ff\",\"judged\":false}]", "", ""));

        assertThat(result.errors()).isEmpty();
        assertThat(result.items()).containsExactly(new ReportedItem("p.A", "3fa9c2d1e07b4a55", true),
                new ReportedItem("p.B", "00ff", false));
        assertThat(result.itemsComplete()).as("no expectedItems: the list may be cut").isFalse();
    }

    @Test
    void withoutItems_theResultHasNoList() {
        ParseResult result = parse(envelope("", 1));

        assertThat(result.valid()).isTrue();
        assertThat(result.items()).isNull();
        assertThat(result.itemsComplete()).isFalse();
    }

    @Test
    void expectedItems_mustEqualTheList_andMakesItComplete() {
        String one = "[{\"name\":\"p.A\",\"digest\":\"aa\",\"judged\":true}]";

        assertThat(parse(withItems(one, ",\"expectedItems\":1", "")).itemsComplete()).isTrue();
        assertThat(parse(withItems(one, ",\"expectedItems\":2", "")).errors())
                .containsExactly("expectedItems says 2, envelope carries 1");
        assertThat(parse(withItems(one, ",\"expectedItems\":\"1\"", "")).errors())
                .containsExactly("expectedItems must be an integer");
        assertThat(parse(envelope(",\"expectedItems\":0", 0)).errors())
                .containsExactly("expectedItems needs the list items");
    }

    @Test
    void anItem_needsAName_ADigest_andABooleanJudged() {
        ParseResult result = parse(withItems("[{\"digest\":\"aa\",\"judged\":true},"
                + "{\"name\":\"p.B\",\"judged\":true},{\"name\":\"p.C\",\"digest\":\"aa\",\"judged\":\"true\"},"
                + "{\"name\":\"p.D\",\"digest\":\"aa\"},{\"name\":\"p.E\",\"digest\":\"not a digest\",\"judged\":true},"
                + "\"p.F\"]", "", ""));

        assertThat(result.valid()).isFalse();
        assertThat(result.items()).isNull();
        assertThat(result.errors()).containsExactly("items[0].name is required", "items[1].digest is required",
                "items[2].judged must be the JSON boolean true or false",
                "items[3].judged must be the JSON boolean true or false",
                "items[4].digest must be 1 to 128 characters of letters, digits and . _ : = + / -",
                "items[5] must be an object");
        assertThat(parse(withItems("{}", "", "")).errors()).containsExactly("items must be an array");
    }

    @Test
    void anItemName_withAControlCharacter_orTwice_orTooLong_isRefused() {
        String item = "{\"name\":\"%s\",\"digest\":\"aa\",\"judged\":true}";

        assertThat(parse(withItems("[" + item.formatted("p.A\\nclean | aa | p.B") + "]", "", "")).errors())
                .containsExactly("items[0].name holds a control character");
        assertThat(parse(withItems("[" + item.formatted("p.A\\u2028p.B") + "]", "", "")).errors())
                .as("a line separator").containsExactly("items[0].name holds a control character");
        assertThat(parse(withItems("[" + item.formatted("p.A") + "," + item.formatted("p.A") + "]", "", ""))
                .errors()).containsExactly("items[1].name p.A is in the list twice");
        assertThat(parse(withItems("[" + item.formatted("x".repeat(301)) + "]", "", "")).errors())
                .containsExactly("items[0].name is 301 characters long, at most 300 characters are read");
        assertThat(parse(withItems("[" + item.formatted("x".repeat(300)) + "]", "", "")).valid()).isTrue();
    }

    @Test
    void moreThanAThousandItems_areRefused() {
        StringBuilder items = new StringBuilder("[");
        for (int i = 0; i <= AuditResultParser.MAX_ITEMS; i++) {
            items.append(i == 0 ? "" : ",").append("{\"name\":\"i").append(i).append("\",\"digest\":\"aa\",\"judged\":false}");
        }

        assertThat(parse(withItems(items.append("]").toString(), "", "")).errors())
                .containsExactly("the envelope carries 1001 items, at most 1000 are read");
    }

    @Test
    void exactlyAThousandItems_areRead() {
        StringBuilder items = new StringBuilder("[");
        for (int i = 0; i < AuditResultParser.MAX_ITEMS; i++) {
            items.append(i == 0 ? "" : ",").append("{\"name\":\"i").append(i).append("\",\"digest\":\"aa\",\"judged\":false}");
        }

        ParseResult result = parse(withItems(items.append("]").toString(), "", ""));

        assertThat(result.errors()).isEmpty();
        assertThat(result.items()).hasSize(AuditResultParser.MAX_ITEMS);
    }

    /** A finding may leave out its item; one it names is no blank text. */
    @Test
    void aBlankItemOnAFinding_isAnError() {
        assertThat(parse(withItems("[{\"name\":\"p.A\",\"digest\":\"aa\",\"judged\":true}]", "", ",\"item\":\"  \""))
                .errors()).containsExactly("findings[0].item is blank");
    }

    @Test
    void aFindingsItem_mustBeOneOfTheItems_whenTheEnvelopeListsThem() {
        String one = "[{\"name\":\"p.A\",\"digest\":\"aa\",\"judged\":true}]";

        ParseResult named = parse(withItems(one, "", ",\"item\":\"p.A\""));
        assertThat(named.errors()).isEmpty();
        assertThat(named.findings().getFirst().item()).isEqualTo("p.A");
        assertThat(parse(withItems(one, "", "")).findings().getFirst().item()).as("a measured finding").isNull();
        assertThat(parse(withItems(one, "", ",\"item\":\"p.Z\"")).errors())
                .containsExactly("findings[0].item names p.Z, which is none of the items");
        assertThat(parse(withItems(one, "", ",\"item\":7")).errors())
                .containsExactly("findings[0].item must be a string");
    }

    @Test
    void aFindingsItem_isKept_withoutAList() {
        ParseResult result = parse("<!-- traceworks:audit-result:v1 {\"snapshotComplete\":true,\"findings\":["
                + "{\"ruleKey\":\"r\",\"subject\":\"s\",\"severity\":\"LOW\",\"title\":\"t\",\"item\":\"p.A\"}]} -->");

        assertThat(result.valid()).isTrue();
        assertThat(result.findings().getFirst().item()).isEqualTo("p.A");
    }

    /**
     * The register unmasks {@code ¦} to {@code |} in an item's name and in a finding's item (a row of
     * {@code audit_items} shows the pipe masked), so the parser compares the names as the register will hold them.
     */
    @Test
    void aFindingsItem_isComparedWithTheItems_asTheRegisterHoldsThem_theMaskUndone() {
        String item = "{\"name\":\"%s\",\"digest\":\"aa\",\"judged\":true}";

        assertThat(parse(withItems("[" + item.formatted("a|b") + "]", "", ",\"item\":\"a\u00a6b\"")).errors())
                .as("the list names the pipe, the finding its mask").isEmpty();
        assertThat(parse(withItems("[" + item.formatted("a\u00a6b") + "]", "", ",\"item\":\"a|b\"")).errors())
                .as("the list names the mask, the finding the pipe").isEmpty();
        assertThat(parse(withItems("[" + item.formatted("a|b") + "," + item.formatted("a\u00a6b") + "]", "", ""))
                .errors()).as("one name in the register").containsExactly(
                        "items[1].name a\u00a6b is in the list twice (as a|b: the register reads \u00a6 as |)");
    }
}
