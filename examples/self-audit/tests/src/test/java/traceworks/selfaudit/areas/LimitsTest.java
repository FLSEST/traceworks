package traceworks.selfaudit.areas;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The stated limits the judge of the security self-audit gets. The documentation says what the defaults do not
 * protect; a judge that knows only the guarantees reports such a behaviour as a defect, and a person then spends
 * time to find that the documents already say so. So the instructions of the package list the limits, and three
 * things are pinned here: the limits file names every limit the documents state, each of its quotes is still in the
 * documents, and the instructions hold the list the file gives.
 */
class LimitsTest {

    private static final Path SECURITY = Path.of("../security").toAbsolutePath().normalize();
    private static final Path CHANGES = Path.of("../security-changes").toAbsolutePath().normalize();
    private static final Path DOCS = Path.of("../../..").toAbsolutePath().normalize();
    /** The document and the heading whose every part is a stated limit. */
    private static final String LIMITS_DOCUMENT = "docs/security.md";
    private static final String LIMITS_HEADING = "What they do not protect";
    /** The section of the guarantees: a sentence in it that states a limit is named by its passage, and quoted. */
    private static final String GUARANTEES_HEADING = "What the defaults protect";

    private static Limits limits() throws IOException {
        return Limits.parse(Files.readString(SECURITY.resolve("rules/security.limits")));
    }

    private static String document(String source) throws IOException {
        assertThat(source).as("a document of this repository").matches("docs/[a-z-]+\\.md");
        return Files.readString(DOCS.resolve(source));
    }

    /**
     * A new limit in the documents fails here until the file names it: every heading below "What they do not
     * protect", and under each every bullet, in the documents' order, as a limit or as an aside.
     */
    @Test
    void theFile_namesEveryHeadingAndEveryBulletOfWhatTheDefaultsDoNotProtect_inTheDocumentsOrder() throws IOException {
        Limits limits = limits();
        String document = document(LIMITS_DOCUMENT);

        assertThat(limits.sections().stream().filter(s -> s.source().equals(LIMITS_DOCUMENT))
                .map(Limits.Section::heading).filter(heading -> !heading.equals(GUARANTEES_HEADING)).toList())
                .as("the headings below \"" + LIMITS_HEADING + "\" of " + LIMITS_DOCUMENT + ": a new one needs a"
                        + " section in rules/security.limits")
                .isEqualTo(LimitDocs.subsections(document, LIMITS_HEADING));
        for (String heading : LimitDocs.subsections(document, LIMITS_HEADING)) {
            assertThat(limits.entries().stream().filter(e -> e.source().equals(LIMITS_DOCUMENT)
                    && e.section().equals(heading)).map(Limits.Entry::lead).toList())
                    .as("the bullets of \"" + heading + "\" in " + LIMITS_DOCUMENT + ": name a new one in"
                            + " rules/security.limits as a limit (with a quote and a says line, and the list in the"
                            + " instructions of both workflow.json made anew) or as an aside")
                    .isEqualTo(List.copyOf(LimitDocs.bullets(LimitDocs.section(document, heading)).keySet()));
        }
    }

    /**
     * A bullet whose text changed in the documents fails here, a limit or an aside, also by a sentence outside the
     * quotes: the line the judge gets may no longer be true. The file names a digest of each bullet's whole text (of
     * the whole passage, for a limit that stands among the guarantees), and the failure shows the line the judge
     * gets next to the bullet, so that whoever brings them in step sees both. A line may narrow the bullet, never
     * widen it: it keeps every bound the bullet sets. The digests as the documents have them now are written to
     * target/security-limits-digests.txt.
     */
    @Test
    void everyEntry_isABulletOfItsSection_whoseWholeTextHasTheDigestOfTheFile_andEveryQuoteIsInIt() throws IOException {
        Limits limits = limits();
        StringBuilder digests = new StringBuilder();
        List<String> changed = new java.util.ArrayList<>();

        assertThat(limits.entries().stream().filter(Limits.Entry::limit)).hasSizeGreaterThan(55);
        for (Limits.Entry entry : limits.entries()) {
            String document = document(entry.source());
            // A list of limits is read bullet by bullet; a limit inside a section of guarantees by its passage
            boolean listOfLimits = entry.source().equals(LIMITS_DOCUMENT)
                    && LimitDocs.subsections(document, LIMITS_HEADING).contains(entry.section());
            Map<String, String> bullets = listOfLimits ? LimitDocs.bullets(LimitDocs.section(document, entry.section()))
                    : LimitDocs.passages(LimitDocs.section(document, entry.section()));
            assertThat(bullets).as(entry.source() + ", \"" + entry.section() + "\"").containsKey(entry.lead());
            String bullet = bullets.get(entry.lead());
            String digest = LimitDocs.digest(bullet);
            digests.append(entry.lead()).append('\t').append(digest).append('\n');
            String both = "\n  the " + (entry.limit() ? "limit" : "aside") + " \"" + entry.lead() + "\" of "
                    + entry.source() + ", \"" + entry.section() + "\"\n  says:   "
                    + (entry.limit() ? entry.says() : "(an aside: not sent)") + "\n  bullet: " + bullet
                    + "\n  digest now: " + digest + "\n";
            if (!digest.equals(entry.digest())) changed.add(both);
            for (String quote : entry.quotes()) {
                assertThat(bullet).as("this limit rests on the quote: bring the quote and the says line in"
                        + " rules/security.limits in step with the bullet" + both).contains(quote);
            }
            if (entry.limit()) {
                assertThat(entry.says()).as("one line for the judge, in plain text" + both)
                        .doesNotContain("\"", "\\", "`").hasSizeLessThan(450).endsWith(".");
            }
        }
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/security-limits-digests.txt"), digests);
        assertThat(changed).as("bullets whose text is not the one rules/security.limits was checked against: read each"
                + " again, bring the says line in step (it may narrow the bullet, never widen it), put the digest now"
                + " into the file, and the list into both workflow.json").isEmpty();
    }

    /**
     * The judge gets the limits and is told how to use them, in both packages: the list is the one the file gives,
     * it stands between the guarantees and the rules of judging, and the instruction says the three things that
     * matter: a stated limit is no finding, more than the limit is one, and doubt is a finding with the limit quoted.
     */
    @Test
    void theInstructionsOfBothPackages_carryTheLimitsOfTheFile_andSayHowToUseThem() throws IOException {
        String rendered = limits().rendered();
        Files.createDirectories(Path.of("target"));
        // For whoever changes the file: the list as it goes between the quotes of a JSON string
        Files.writeString(Path.of("target/security-limits.json-text"), Json.write(rendered).replaceAll("^\"|\"$", ""));

        assertThat(rendered).startsWith("From docs/security.md, \"What the defaults protect\":\nL1. ")
                .contains("\nFrom docs/security.md, \"AI steps\":\n", "\nFrom docs/security.md, \"Seals\":\n",
                        "\nFrom docs/events.md, \"Redaction\":\nL")
                .doesNotContain("Many containers on a desktop machine");
        for (Path pkg : List.of(SECURITY, CHANGES)) {
            String instructions = String.valueOf(((Map<?, ?>) Json.parse(Files.readString(pkg.resolve("workflow.json"))))
                    .get("instructions"));
            assertThat(instructions).as(pkg.getFileName() + ": the list of rules/security.limits; it is written to"
                    + " target/security-limits.json-text as it goes into workflow.json").contains("\n" + rendered + "\n");
            assertThat(instructions).contains(
                    "What Traceworks states as a limit.",
                    "- A behaviour that a limit states is not a finding.",
                    "is answered \"gone\" with \"limit\": \"L<number>\".",
                    "A medium or high finding stays open with your answer as its note, for a person to decide; a low one"
                            + " is closed.",
                    "- It is a finding when the code does more than the limit says. Then report it, name the limit by"
                            + " its number in the issue and say what goes beyond it.",
                    "- When you cannot tell whether a limit covers what you see, report the finding and quote the"
                            + " limit in the issue: a person decides.");
            assertThat(instructions.indexOf("What Traceworks guarantees")).isLessThan(instructions.indexOf(rendered));
            assertThat(instructions.indexOf(rendered)).isLessThan(instructions.indexOf("How to judge:"));
        }
    }

    /** The limits change what counts as a finding, so the version of the question is raised: every area is judged again. */
    @Test
    void bothPackages_askTheQuestionInItsSecondVersion() throws IOException {
        for (Path pkg : List.of(SECURITY, CHANGES)) {
            assertThat(Files.readString(pkg.resolve("workflow.json"))).as(pkg.getFileName().toString())
                    .containsOnlyOnce(" --verdict-version 2 ");
        }
    }

    @Test
    void theFormat_refusesWhatItDoesNotKnow_andALimitWithoutItsQuoteOrItsLine() {
        assertThatThrownBy(() -> Limits.parse("source docs/a.md\nsection S\nlimit L\n  quote q\n"))
                .hasMessageContaining("needs a quote and a says line");
        assertThatThrownBy(() -> Limits.parse("section S\n")).hasMessageContaining("line 1: a section before a source");
        assertThatThrownBy(() -> Limits.parse("source docs/a.md\nlimit L\n")).hasMessageContaining("an entry before a section");
        assertThatThrownBy(() -> Limits.parse("source docs/a.md\nsection S\naside A\n  says x\n"))
                .hasMessageContaining("line 4: not a line of a limits file");
        assertThatThrownBy(() -> Limits.parse("source docs/a.md\nsection S\nwhatever x\n")).hasMessageContaining("line 3");

        Limits limits = Limits.parse("# c\nsource docs/a.md\nsection S\nlimit L one.\n  quote q\n  says One.\n"
                + "  digest 0123456789abcdef\naside A\n  digest fedcba9876543210\n"
                + "section Empty\nsource docs/b.md\nsection T\nlimit M\n  quote r\n  quote s\n  says Two.\n");
        assertThat(limits.rendered()).isEqualTo("From docs/a.md, \"S\":\nL1. One.\nFrom docs/b.md, \"T\":\nL2. Two.");
        assertThat(limits.numbered()).as("the line of each limit by its number").containsExactly("One.", "Two.");
        List<String> leads = new ArrayList<>();
        limits.entries().forEach(e -> leads.add(e.lead() + "=" + e.digest()));
        assertThat(leads).containsExactly("L one.=0123456789abcdef", "A=fedcba9876543210", "M=");
        assertThatThrownBy(() -> Limits.parse("source docs/a.md\nsection S\naside A\n  digest XYZ\n"))
                .as("a digest is 16 hex digits").hasMessageContaining("line 4");
    }

    /** What the pin reads of a document: the bullets with a bold lead, without the guarantees of a mixed section. */
    @Test
    void theBulletsOfASection_areTheOnesWithABoldLead_withoutAListOfGuarantees() {
        String document = "# D\n## Limits\n### One\nText.\n\nWhat a seal protects:\n\n- **A guarantee.** Yes.\n\n"
                + "What a seal does not protect:\n\n- **First.** a\n  b\n  - *nested* c\n- **Second** (d). e\n\nAfter.\n"
                + "### Two\nProse only.\n## Next\n- **Not** here\n";

        assertThat(LimitDocs.subsections(document, "Limits")).containsExactly("One", "Two");
        assertThat(LimitDocs.bullets(LimitDocs.section(document, "One"))).containsExactly(
                Map.entry("First.", "- **First.** a b - *nested* c"), Map.entry("Second", "- **Second** (d). e After."));
        assertThat(LimitDocs.bullets(LimitDocs.section(document, "Two"))).isEmpty();
        assertThatThrownBy(() -> LimitDocs.bullets(List.of("- no lead"))).hasMessageContaining("without a bold lead");
        assertThat(LimitDocs.passages(List.of("**Registers** (x). A.", "B. **Bold inside.** C.", "", "- no lead", "  more",
                "- **What nothing finds:** d", "### H", "lost"))).containsExactly(
                Map.entry("Registers", "**Registers** (x). A. B. **Bold inside.** C. - no lead more"),
                Map.entry("What nothing finds:", "- **What nothing finds:** d"));
        assertThatThrownBy(() -> LimitDocs.section(document, "Three")).hasMessageContaining("no heading 'Three'");
    }
}
