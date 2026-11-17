package traceworks.selfaudit.functionality;

import com.fasterxml.jackson.databind.JsonNode;
import traceworks.core.register.EvidenceVerification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code scripts/facts.py}: each deterministic rule with a hit and without, what is not measured, the sections. */
class FactsTest {

    @TempDir
    Path temp;
    Fixture fixture;

    @BeforeEach
    void tree() {
        fixture = new Fixture(temp);
    }

    @Test
    void documentationAndCodeThatAgree_haveNoFinding_andNothingIsLeftUnmeasured() {
        Fixture.Ran ran = fixture.facts();

        assertThat(ran.code()).as(ran.describe()).isZero();
        assertThat(ran.err()).isEmpty();
        assertThat(fixture.findings()).isEmpty();
        assertThat(fixture.notMeasured()).isEmpty();
        assertThat(ran.out()).startsWith("## FACT SHEET functionality\n")
                .contains("FACT func_commands_in_code: 3\n", "FACT func_commands_in_docs: 3\n",
                        "FACT func_options_in_code: 3\n", "FACT func_exit_codes_in_code: 2\n",
                        "FACT func_exit_codes_in_docs: 2\n", "FACT func_example_packages: 1\n",
                        "FACT func_block_types_in_code: 1\n", "FACT func_findings: 0\n", "FACT func_not_measured: 0\n")
                .doesNotContain("NOT MEASURED");
    }

    @Test
    void aCommandWithoutASection_isUndocumented() {
        fixture.write(Fixture.SOURCES + "PurgeCommand.java", """
                package demo;
                final class PurgeCommand implements Command {
                    private static final Spec SPEC = Spec.of("purge", "Removes old runs.", null);
                }
                """);
        fixture.replace(Fixture.SOURCES + "Main.java", "new WatchCommand()", "new WatchCommand(), new PurgeCommand()");

        Fixture.Ran ran = fixture.facts();

        assertThat(fixture.findings()).containsExactly("undocumented-command purge");
        assertThat(ran.out()).contains("HIT func_undocumented_command: purge (The command 'purge' has no section in"
                + " docs/cli.md)");
    }

    @Test
    void aSectionForACommandTheCodeDoesNotHave_isAnUnknownCommand() {
        fixture.replace("docs/cli.md", "## Exit codes", "### purge\n\n`tool purge` removes old runs.\n\n## Exit codes");

        fixture.facts();

        assertThat(fixture.findings()).containsExactly("unknown-command-in-docs purge");
    }

    @Test
    void aCommandClassTheMainFileDoesNotCreate_isNotDispatched() {
        fixture.replace(Fixture.SOURCES + "Main.java", ",\n            new WatchCommand()", "");

        Fixture.Ran ran = fixture.facts();

        assertThat(fixture.findings()).containsExactly("unknown-command-in-docs watch");
        assertThat(ran.out()).contains("NOT MEASURED: the command 'watch' (" + Fixture.SOURCES + "WatchCommand.java"
                + " declares it, and " + Fixture.SOURCES + "Main.java does not create its class: it counts as not"
                + " dispatched)");
    }

    @Test
    void anOptionOfACommandThatItsSectionDoesNotName_isUndocumented() {
        fixture.replace(Fixture.SOURCES + "ShowCommand.java", ".flag(\"full\", \"add each output whole\")",
                ".flag(\"full\", \"add each output whole\")\n            .option(\"limit\", \"N\", \"at most N steps\")");
        // Another command's section naming it does not document it for show
        fixture.replace("docs/cli.md", "prints the summary only.", "prints the summary only. Not `--limit`.");

        fixture.facts();

        assertThat(fixture.findings()).contains("undocumented-option show --limit")
                .doesNotContain("unknown-option-in-docs run --limit").hasSize(1);
    }

    @Test
    void anOptionInASectionThatNoCommandTakes_isUnknown_andACommentOrATextBlockDeclaresNone() {
        fixture.replace("docs/cli.md", "prints the summary only.",
                "prints the summary only. `--ghost` and `--fake=1` do more, `--full` belongs to show.");

        fixture.facts();

        // --full is an option of another command, named in passing; --home and --help are global
        assertThat(fixture.findings()).containsExactly("unknown-option-in-docs run --fake",
                "unknown-option-in-docs run --ghost");
    }

    @Test
    void anOptionOfAnotherProgram_isNoFindingOnceTheRuleFileNamesIt() {
        fixture.replace("docs/cli.md", "follows it.", "follows it. Clean up with `docker ps --filter x`,\n--version"
                + " prints the version.");
        fixture.rule("# docker's own option");
        fixture.rule("foreign_option filter");

        fixture.facts();

        assertThat(fixture.findings()).isEmpty();
    }

    @Test
    void anOptionOfAnotherProgram_namedWithAWord_isNoFindingOnlyInALineThatHoldsTheWordBeforeIt() {
        fixture.replace("docs/cli.md", "follows it.", """
                follows it. A Dockerfile may use `COPY --from=` or
                `RUN --mount=...,from=`, and a step hands a file to its tool:

                ```json
                {"container_args": ["scan", "--from", "answer.txt"]}
                ```
                """);
        fixture.rule("foreign_option from COPY");
        fixture.rule("foreign_option from container_args");
        fixture.rule("foreign_option mount RUN");

        fixture.facts();

        assertThat(fixture.findings()).isEmpty();
    }

    @Test
    void anOptionOfAnotherProgram_namedWithAWord_isStillUnknownAnywhereElse() {
        // The word after the option or in the line before does not count, and "run" is not RUN
        fixture.replace("docs/cli.md", "follows it.", """
                follows it. `tool watch --from 3` starts later, unlike COPY
                with `--from=` in the next line, `tool run --mount x` mounts, and `--mount` RUN too.
                """);
        fixture.rule("foreign_option from COPY");
        fixture.rule("foreign_option mount RUN");

        fixture.facts();

        assertThat(fixture.findings()).containsExactly("unknown-option-in-docs show/watch --from",
                "unknown-option-in-docs show/watch --mount");
    }

    @Test
    void aForeignOptionWithMoreThanOneWord_isRefused() {
        fixture.rule("foreign_option from COPY and more");

        Fixture.Ran ran = fixture.facts();

        assertThat(ran.code()).isNotZero();
        assertThat(ran.err()).contains("'foreign_option' takes an option's name, or the name and one word");
    }

    @Test
    void anExitCodeOnOneSideOnly_isAMismatch_bothWays() {
        fixture.replace(Fixture.SOURCES + "ExitCodes.java", "USAGE = 2;", "USAGE = 2;\n    public static final int"
                + " FAILED = 3;\n    static final String NAME = \"7\";\n    // public static final int GONE = 9;");
        fixture.replace("docs/cli.md", "| 2 | usage error |", "| 2 | usage error |\n| 5 | parked |");

        Fixture.Ran ran = fixture.facts();

        assertThat(fixture.findings()).containsExactly("exit-code-mismatch exit 3", "exit-code-mismatch exit 5");
        assertThat(ran.out()).contains("Exit code 3 (FAILED) is not in the table of docs/cli.md",
                "docs/cli.md documents exit code 5, which the code does not declare");
    }

    @Test
    void anExamplePackageNoDocumentNames_isNotMentioned_andItsOwnReadmeDoesNotCount() {
        fixture.write("examples/audit/more/workflow.json", "{}\n");
        fixture.write("examples/audit/more/README.md", "Run examples/audit/more.\n");
        fixture.write("examples/target/built/workflow.json", "{}\n");

        fixture.facts();

        assertThat(fixture.findings()).containsExactly("example-not-mentioned examples/audit/more");

        // The README of a directory above it is a document that names it
        fixture.write("examples/audit/README.md", "The packages: `examples/audit/more`.\n");
        fixture.facts();

        assertThat(fixture.findings()).isEmpty();
    }

    @Test
    void anExamplePathADocumentNames_mustExist() {
        fixture.write("docs/other.md", "See examples/hello, examples/hello/README.md and (examples/gone/run.sh).\n"
                + "Not a path of this tree: my-examples/none. Every package is below examples/.\n");

        Fixture.Ran ran = fixture.facts();

        assertThat(fixture.findings()).containsExactly("missing-example-path examples/gone/run.sh");
        assertThat(ran.out()).contains("HIT func_missing_example_path: examples/gone/run.sh (docs/other.md names"
                + " examples/gone/run.sh, which does not exist)");
    }

    @Test
    void aBlockTypeOnOneSideOnly_isAFinding_bothWays() {
        fixture.write(Fixture.SOURCES + "RetryBlockExecutor.java", """
                package demo;
                public final class RetryBlockExecutor implements BlockExecutor {
                    public Set<String> supportedTypes() {
                        return Set.of("retry", "again");
                    }
                }
                """);
        fixture.replace(Fixture.SOURCES + "CoreBlocks.java", "new ShellBlockExecutor()",
                "new ShellBlockExecutor(), new RetryBlockExecutor()");
        fixture.write("docs/other.md", "See examples/hello. A `retry` block repeats, a `teleport` block does not"
                + " exist, and `checked_command` blocks are no type.\n");

        fixture.facts();

        assertThat(fixture.findings()).containsExactly("undocumented-block-type again",
                "unknown-block-type-in-docs teleport");

        fixture.rule("not_a_block_type teleport");
        fixture.facts();

        assertThat(fixture.findings()).containsExactly("undocumented-block-type again");
    }

    @Test
    void aMissingDocument_isNotMeasured_andSaysSo() {
        fixture.delete("docs/cli.md");

        Fixture.Ran ran = fixture.facts();

        assertThat(ran.code()).as(ran.describe()).isZero();
        assertThat(ran.out()).contains("NOT MEASURED: the documentation of the command line (docs/cli.md does not exist"
                        + " or cannot be read)",
                "NOT MEASURED: the sections the AI judges (docs/cli.md does not exist or cannot be read)",
                "FACT func_not_measured: ");
        assertThat(fixture.findings()).isEmpty();
        assertThat(fixture.sectionLines()).isEmpty();
    }

    @Test
    void codeTheScriptCannotRead_isNotMeasured_andSaysSo() {
        fixture.delete(Fixture.SOURCES + "ExitCodes.java");
        fixture.replace(Fixture.SOURCES + "ShellBlockExecutor.java", "Set.of(TYPE)", "Types.all()");
        fixture.replace("docs/cli.md", "## Commands", "## The commands");

        Fixture.Ran ran = fixture.facts();

        assertThat(ran.out()).contains("NOT MEASURED: the exit codes of the code (no single class ExitCodes below a"
                        + " src/main/java)",
                "NOT MEASURED: the block types of ShellBlockExecutor (supportedTypes() in " + Fixture.SOURCES
                        + "ShellBlockExecutor.java names no string literal or constant of the file)",
                "NOT MEASURED: commands and options (docs/cli.md has no section '## Commands')");
        assertThat(fixture.findings()).isEmpty();
    }

    @Test
    void aTreeWithoutJavaSources_isNotMeasured() {
        Fixture.Ran ran = Fixture.python(null, "facts.py", "--root", temp.resolve("scratch").toString(), "--rules",
                fixture.rules.toString(), "--out", fixture.out.toString());

        assertThat(ran.code()).as(ran.describe()).isZero();
        assertThat(ran.out()).contains("NOT MEASURED: the code (no Java file below a src/main/java of the workspace)",
                "NOT MEASURED: the example packages (the workspace has no directory examples/)");
    }

    @Test
    void theSections_areEachCommandSectionAndEveryOtherSecondLevelSection() {
        Fixture.Ran ran = fixture.facts();

        List<JsonNode> sections = fixture.sections();
        assertThat(sections).extracting(s -> s.get("id").asText()).containsExactly("cli.md#a-first-run", "cli.md#run",
                "cli.md#show-and-watch", "cli.md#exit-codes", "cli.md#blocks");
        JsonNode run = fixture.section("cli.md#run");
        assertThat(run.get("doc").asText()).isEqualTo("docs/cli.md");
        assertThat(run.get("heading").asText()).isEqualTo("run");
        assertThat(run.get("start").asInt()).isEqualTo(11);
        assertThat(run.get("end").asInt()).isEqualTo(14);
        assertThat(run.get("text").asText()).startsWith("### run\n\n`tool run <package>` runs a package.")
                .endsWith("prints the summary only.");
        assertThat(run.get("where").asText()).isEqualTo("The command is declared in " + Fixture.SOURCES
                + "RunCommand.java.");
        assertThat(fixture.section("cli.md#show-and-watch").get("where").asText())
                .contains("ShowCommand.java and ", "WatchCommand.java");
        // The heading inside the code fence starts no section
        assertThat(fixture.section("cli.md#blocks").get("text").asText()).contains("## not a heading");
        // One frame and one mark per run, the same in every line, and hex
        assertThat(sections).extracting(s -> s.get("fence").asText()).containsOnly(run.get("fence").asText());
        assertThat(run.get("fence").asText()).matches("[0-9a-f]{16}");
        assertThat(run.get("mark").asText()).matches("[0-9a-f]{16}").isNotEqualTo(run.get("fence").asText());
        assertThat(ran.out()).contains("HIT func_section: cli.md#run (docs/cli.md, lines 11 to 14)",
                "FACT func_sections: 5\n");
    }

    @Test
    void aLongSection_isJudgedInParts_cutAtEmptyLines_andTheCapIsSaid() {
        String paragraph = "A sentence about the home that fills a line of the document, again and again.\n".repeat(5);
        fixture.replace("docs/cli.md", "## Exit codes", "## Home\n\n" + (paragraph + "\n").repeat(4)
                + "### A deeper heading\n\nIt belongs to Home.\n\n## Exit codes");
        fixture.rule("max_section_chars 900");
        fixture.rule("max_sections 5");

        Fixture.Ran ran = fixture.facts();

        List<JsonNode> sections = fixture.sections();
        assertThat(sections).extracting(s -> s.get("id").asText()).containsExactly("cli.md#a-first-run", "cli.md#run",
                "cli.md#show-and-watch", "cli.md#home/1", "cli.md#home/2");
        JsonNode first = fixture.section("cli.md#home/1");
        JsonNode second = fixture.section("cli.md#home/2");
        assertThat(first.get("parts").asInt()).isEqualTo(2);
        assertThat(first.get("heading").asText()).isEqualTo("Home");
        assertThat(first.get("text").asText().length()).isLessThanOrEqualTo(900);
        assertThat(first.get("text").asText()).startsWith("## Home").endsWith("again and again.");
        assertThat(second.get("start").asInt()).isEqualTo(first.get("end").asInt() + 1);
        assertThat(second.get("text").asText()).startsWith("A sentence about the home")
                .endsWith("### A deeper heading\n\nIt belongs to Home.");
        assertThat(ran.out()).contains("FACT func_sections: 5\n", "FACT func_sections_cut: 2\n",
                "NOT MEASURED: the section cli.md#exit-codes (beyond max_sections of the rule file)",
                "NOT MEASURED: the section cli.md#blocks (beyond max_sections of the rule file)");
        assertThat(fixture.factsJson().get("sections_cut")).hasSize(2);
    }

    @Test
    void everyFinding_namesAFactOfTheSheet_soTheRegisterCanCheckItsEvidence() {
        fixture.replace("docs/cli.md", "| 2 | usage error |", "| 2 | usage error |\n| 5 | parked |");
        fixture.replace("docs/cli.md", "prints the summary only.", "prints the summary only, `--ghost` too.");

        Fixture.Ran ran = fixture.facts();

        Set<String> names = EvidenceVerification.factNames(List.of(ran.out()));
        assertThat(names).contains("func_exit_code_mismatch", "func_unknown_option_in_docs", "func_section");
        assertThat(fixture.factsJson().get("findings")).hasSize(2).allSatisfy(finding ->
                assertThat(EvidenceVerification.isSupported(finding.get("evidence").asText(), names)).isTrue());
    }

    @Test
    void aRuleFileWithAnUnknownKey_isRefused() {
        fixture.rule("max_section 3");

        Fixture.Ran ran = fixture.facts();

        assertThat(ran.code()).isNotZero();
        assertThat(ran.err()).contains("line 2: unknown key 'max_section'");
    }

    @Test
    void thePackagesOwnRuleFile_isRead() {
        Fixture.Ran ran = Fixture.python(null, "facts.py", "--root", fixture.root.toString(), "--rules",
                Fixture.PACKAGE.resolve("rules/functionality.rules").toString(), "--out", fixture.out.toString());

        assertThat(ran.code()).as(ran.describe()).isZero();
        // Its block_doc, docs/workflow-format.md, is not in this tree
        assertThat(ran.out()).contains("NOT MEASURED: the block types of docs/workflow-format.md (the file does not"
                + " exist or cannot be read)");
    }

    // ---- items: every part of a section is one, and which of them this run judges (docs/registers.md, "Items") ----

    private static final String FIRST = "docs/cli.md#A first run";
    private static final String RUN = "docs/cli.md#run";
    private static final String SHOW = "docs/cli.md#show and watch";
    private static final String CODES = "docs/cli.md#Exit codes";
    private static final String BLOCKS = "docs/cli.md#Blocks";

    /**
     * The placeholder stays a literal where nothing sets it (an older Traceworks): then no item is
     * known, and the run judges every section, as it did before items existed.
     */
    @Test
    void withoutAuditItems_everySectionIsAnItem_andEverySectionIsJudged() {
        for (String nothing : List.of("{{ audit_items }}", "(no known items)", "")) {
            Fixture.Ran ran = fixture.factsWithItems(nothing);

            assertThat(fixture.chosen()).as(nothing).containsExactly(FIRST, RUN, SHOW, CODES, BLOCKS);
            assertThat(fixture.items()).containsExactly(FIRST + " true '' false", RUN + " true '' false",
                    SHOW + " true '' false", CODES + " true '' false", BLOCKS + " true '' false");
            assertThat(ran.out()).contains("\nFACT func_items: 5\n", "\nFACT func_items_settled: 0\n",
                    "\nFACT func_sections: 5\n", "\nFACT func_sections_cut: 0\n");
        }
        assertThat(fixture.digestOf(RUN)).matches("[0-9a-f]{16}").isNotEqualTo(fixture.digestOf(SHOW));
        JsonNode run = fixture.section("cli.md#run");
        assertThat(run.get("item").asText()).isEqualTo(RUN);
        assertThat(run.get("digest").asText()).isEqualTo(fixture.digestOf(RUN));
        String digest = fixture.digestOf(RUN);

        fixture.facts();

        assertThat(fixture.chosen()).as("without --items").containsExactly(FIRST, RUN, SHOW, CODES, BLOCKS);
        assertThat(fixture.digestOf(RUN)).as("the frame of a run is no part of the digest").isEqualTo(digest);
    }

    @Test
    void aCleanOrDecidedSectionWithTheSameDigest_isLeftOut_andMarkedAsSettled() {
        fixture.facts();

        Fixture.Ran ran = fixture.factsWithItems("clean | " + fixture.digestOf(RUN) + " | " + RUN,
                "decided | " + fixture.digestOf(BLOCKS) + " | " + BLOCKS, "not judged | - | " + SHOW);

        assertThat(fixture.chosen()).containsExactly(FIRST, SHOW, CODES);
        assertThat(fixture.items()).containsExactly(FIRST + " true '' false", RUN + " false 'clean' true",
                SHOW + " true 'not judged' false", CODES + " true '' false", BLOCKS + " false 'decided' true");
        assertThat(ran.out()).contains("\nFACT func_items: 5\n", "\nFACT func_items_settled: 2\n",
                "\nFACT func_sections: 3\n", "HIT func_section: cli.md#show-and-watch (")
                .doesNotContain("HIT func_section: cli.md#run (");
    }

    /**
     * What a verdict saw is the text of the section and the files its command is declared in: a change of either
     * asks for a new one. A section that only moved in the document, or another command's file, does not.
     */
    @Test
    void aCleanSection_isJudgedAgain_whenItsTextOrTheFileOfItsCommandChanged() {
        fixture.facts();
        String run = fixture.digestOf(RUN);
        String show = fixture.digestOf(SHOW);
        String blocks = fixture.digestOf(BLOCKS);
        String[] rows = {"clean | " + run + " | " + RUN, "clean | " + show + " | " + SHOW,
                "clean | " + blocks + " | " + BLOCKS, "clean | " + fixture.digestOf(FIRST) + " | " + FIRST,
                "clean | " + fixture.digestOf(CODES) + " | " + CODES};

        fixture.factsWithItems(rows);
        assertThat(fixture.chosen()).as("nothing changed").isEmpty();

        // The text of one section, the file of another one's command, and a line above them all
        fixture.replace("docs/cli.md", "A `shell` block runs a command.", "A `shell` block runs one command.");
        fixture.replace(Fixture.SOURCES + "RunCommand.java", "? 0 : 2;", "? 0 : 1;");
        fixture.replace("docs/cli.md", "# The command line\n", "# The command line\n\nOne more line.\n");
        fixture.factsWithItems(rows);

        assertThat(fixture.chosen()).containsExactly(RUN, BLOCKS);
        assertThat(fixture.digestOf(RUN)).isNotEqualTo(run);
        assertThat(fixture.digestOf(BLOCKS)).isNotEqualTo(blocks);
        assertThat(fixture.digestOf(SHOW)).as("it only moved, and its commands are in other files").isEqualTo(show);
        assertThat(fixture.section("cli.md#run").get("files")).extracting(JsonNode::asText)
                .containsExactly(Fixture.SOURCES + "RunCommand.java");
    }

    /**
     * An open section is asked about again, but only in the places the sections leave that changed or were never
     * judged: so every section is judged once before one is judged twice.
     */
    @Test
    void anOpenSection_isChosenAfterTheChangedOnes_whenPlacesAreLeft() {
        fixture.facts();
        String[] rows = {"open | " + fixture.digestOf(FIRST) + " | " + FIRST,
                "clean | " + fixture.digestOf(RUN) + " | " + RUN};
        fixture.rule("max_sections 3");

        Fixture.Ran ran = fixture.factsWithItems(rows);
        assertThat(fixture.chosen()).containsExactly(SHOW, CODES, BLOCKS);
        assertThat(fixture.factsJson().get("sections_cut")).extracting(JsonNode::asText)
                .containsExactly("cli.md#a-first-run");
        assertThat(fixture.items()).contains(FIRST + " false 'open' false", RUN + " false 'clean' true");
        assertThat(ran.out()).contains("\nFACT func_items_settled: 1\n", "\nFACT func_sections_cut: 1\n");

        fixture.replace("docs/cli.md", "Run `tool run examples/hello`", "First run `tool run examples/hello`");
        fixture.factsWithItems(rows);
        assertThat(fixture.chosen()).as("an open section that changed is a changed section")
                .containsExactly(FIRST, SHOW, CODES);
    }

    @Test
    void withPlacesLeft_anOpenSectionThatDidNotChange_isAskedAboutAgain() {
        fixture.facts();

        fixture.factsWithItems("open | " + fixture.digestOf(FIRST) + " | " + FIRST,
                "clean | " + fixture.digestOf(RUN) + " | " + RUN);

        assertThat(fixture.chosen()).containsExactly(SHOW, CODES, BLOCKS, FIRST);
    }

    /** The question is part of what a verdict saw: who changes it raises the version, and every section is judged again. */
    @Test
    void raisingVerdictVersion_changesEveryDigest() {
        fixture.facts();
        String run = fixture.digestOf(RUN);
        fixture.rule("verdict_version 2");

        fixture.factsWithItems("clean | " + run + " | " + RUN);

        assertThat(fixture.digestOf(RUN)).isNotEqualTo(run);
        assertThat(fixture.chosen()).containsExactly(FIRST, RUN, SHOW, CODES, BLOCKS);
    }

    /** A name is compared as the register shows it; a row that is cut or belongs to no section changes nothing. */
    @Test
    void rowsThatBelongToNoSection_changeNothing() {
        fixture.facts();
        String run = fixture.digestOf(RUN);

        fixture.factsWithItems("clean | " + run + " | docs/cli.md#ru\u2026", "clean | ffff | docs/cli.md#gone",
                "CLEAN | " + run + " | " + RUN, "clean | " + run);

        assertThat(fixture.chosen()).containsExactly(FIRST, RUN, SHOW, CODES, BLOCKS);
    }

    /**
     * A finding of a run before items existed names no item, so the register may call its section clean. The open
     * finding itself says otherwise: a section that has one is asked about, whatever its row says.
     */
    @Test
    void aSectionWithAnOpenFinding_isJudged_evenWhenItsRowSaysClean() {
        fixture.facts();
        String[] clean = {"clean | " + fixture.digestOf(RUN) + " | " + RUN,
                "clean | " + fixture.digestOf(SHOW) + " | " + SHOW};
        String open = "OPEN | MEDIUM | docs-contradict-code | docs/cli.md#run:0123456789ab | t | Evidence: func_section:"
                + " cli.md#run; the document: \"`--quiet` prints the summary only.\"; the code, x: \"y\"";
        String dismissed = open.replace("OPEN | ", "DISMISSED | ").replace("#run:", "#show and watch:")
                + " | Disposition: fine";

        fixture.factsKnowing(List.of(open, dismissed), clean);

        assertThat(fixture.chosen()).as("run is open and comes last; show and watch is decided and stays out")
                .containsExactly(FIRST, CODES, BLOCKS, RUN);
        assertThat(fixture.section("cli.md#run").get("known_text").asText()).startsWith("K1 | ");
        assertThat(fixture.items()).contains(RUN + " true 'clean' false", SHOW + " false 'clean' true");

        fixture.rule("max_sections 3");
        fixture.factsKnowing(List.of(open, dismissed), clean);
        assertThat(fixture.chosen()).containsExactly(FIRST, CODES, BLOCKS);
        assertThat(fixture.factsJson().get("sections_cut")).extracting(JsonNode::asText).containsExactly("cli.md#run");
        assertThat(fixture.items()).contains(RUN + " false 'clean' false");
    }

    /**
     * The register wants every item name once, on one line, within 300 characters: a section judged in parts is one
     * item per part, a heading the document has twice is numbered, and a name too long or with a control character
     * is made one the register takes.
     */
    @Test
    void anItemName_isThereOnce_isOneLine_andFitsTheRegister() {
        String paragraph = "A sentence about the home that fills a line of the document, again and again.\n".repeat(5);
        String longHeading = "A heading that goes on " + "and on ".repeat(60) + "to its end";
        fixture.replace("docs/cli.md", "## Exit codes", "## Home\n\n" + (paragraph + "\n").repeat(4)
                + "## Blocks\n\nThe same heading, once more.\n\n## Odd\u0001  heading\n\nText.\n\n## " + longHeading
                + "\n\nText.\n\n## Exit codes");
        fixture.rule("max_section_chars 900");

        fixture.facts();

        List<String> names = fixture.chosen();
        assertThat(names).hasSize(10).doesNotHaveDuplicates().startsWith(FIRST, RUN, SHOW, "docs/cli.md#Home/1",
                "docs/cli.md#Home/2", BLOCKS, "docs/cli.md#Odd? heading").endsWith(CODES, BLOCKS + "~2");
        String cut = names.get(7);
        assertThat(cut).hasSize(300).startsWith("docs/cli.md#A heading that goes on and on ").matches(".*~[0-9a-f]{16}");
        assertThat(names).allSatisfy(name -> assertThat(name.chars()).noneMatch(Character::isISOControl));
        assertThat(fixture.factsJson().path("items")).extracting(i -> i.path("name").asText())
                .containsExactlyElementsOf(names);
    }

    /**
     * Two headings that are one name once they fit the register (inner blanks collapsed, a control character as a
     * question mark) are still two items: the count is on the name as the register gets it.
     */
    @Test
    void headingsThatBecomeOneNameWhenFitted_areNumbered() {
        fixture.replace("docs/cli.md", "## Exit codes", "## Two  blanks\n\nText.\n\n## Two blanks\n\nText.\n\n"
                + "## Odd\u0001mark\n\nText.\n\n## Odd?mark\n\nText.\n\n## Two blanks~2\n\nText.\n\n## Exit codes");

        fixture.facts();

        assertThat(fixture.chosen()).doesNotHaveDuplicates().contains("docs/cli.md#Two blanks",
                "docs/cli.md#Two blanks~2", "docs/cli.md#Odd?mark", "docs/cli.md#Odd?mark~2", "docs/cli.md#Two blanks~2~2");
    }

    /** Each package carries the scripts it runs, so items.py is in two: a fix in one copy must be made in the other. */
    @Test
    void itemsPy_isTheSameInTheTestingPackage() {
        assertThat(Fixture.read(Fixture.PACKAGE.resolve("scripts/items.py")))
                .isEqualTo(Fixture.read(Fixture.PACKAGE.resolve("../testing/scripts/items.py")));
    }

    @Test
    void theScripts_leaveNothingInThePackage() throws java.io.IOException {
        fixture.factsWithItems("(no known items)");
        fixture.report("");

        try (var files = java.nio.file.Files.walk(Fixture.PACKAGE)) {
            assertThat(files.map(p -> p.getFileName().toString())).doesNotContain("__pycache__");
        }
    }
}
