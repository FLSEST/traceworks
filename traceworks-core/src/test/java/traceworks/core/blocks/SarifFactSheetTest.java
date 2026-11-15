package traceworks.core.blocks;

import traceworks.core.util.SecretReplacer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class SarifFactSheetTest {

    @TempDir Path out;

    private static final String TRIVY = """
            {"version":"2.1.0","runs":[{"tool":{"driver":{"name":"Trivy","version":"0.70.0","rules":[
              {"id":"CVE-2026-0001","properties":{"security-severity":"9.8"}},
              {"id":"CVE-2026-0002","properties":{"security-severity":"7.5"}},
              {"id":"CVE-2026-0003","defaultConfiguration":{"level":"note"}}]}},
             "results":[
              {"ruleId":"CVE-2026-0002","ruleIndex":1,"level":"error","message":{"text":"Package: vite\\nInstalled Version: 5.4.0"},
               "locations":[{"physicalLocation":{"artifactLocation":{"uri":"ai-automation-frontend/package-lock.json","uriBaseId":"ROOTPATH"},"region":{"startLine":1}}}]},
              {"ruleId":"CVE-2026-0001","ruleIndex":0,"message":{"text":"Package: jackson-databind"},
               "locations":[{"physicalLocation":{"artifactLocation":{"uri":"file:///workspace/ai-automation-backend/pom.xml"}}}]},
              {"ruleId":"CVE-2026-0003","message":{"text":"Package: lodash"},
               "locations":[{"physicalLocation":{"artifactLocation":{"uri":"/workspace/ai-automation-frontend/package-lock.json"},"region":{"startLine":40}}}]},
              {"ruleId":"CVE-2026-0003","message":{"text":"accepted"},"suppressions":[{"kind":"external"}]}
             ]}]}
            """;

    private SarifFactSheet.Result read(int exitCode, String raw) {
        return SarifFactSheet.read(out, "/out", "trivy", exitCode, raw);
    }

    @Test
    void countsPerSeverity_securitySeverityBeatsLevel() throws Exception {
        Files.writeString(out.resolve("trivy.sarif"), TRIVY);

        SarifFactSheet.Result result = read(1, "log");

        assertThat(result.measured()).isTrue();
        assertThat(result.findings()).isEqualTo(3);
        assertThat(result.text()).startsWith("## FACT SHEET trivy (Trivy 0.70.0, 1 SARIF file)")
                .contains("FACT trivy_findings: 3", "FACT trivy_critical: 1", "FACT trivy_high: 1",
                        "FACT trivy_medium: 0", "FACT trivy_low: 1", "FACT trivy_suppressed: 1",
                        "FACT trivy_exit_code: 1")
                .doesNotContain("NOT MEASURED", "TOOL LOG");
    }

    @Test
    void pathsAreRepoRelative_andHitsSortedBySeverity() throws Exception {
        Files.writeString(out.resolve("trivy.sarif"), TRIVY);

        String[] hits = read(0, "").text().lines().filter(l -> l.startsWith("HIT ")).toArray(String[]::new);

        assertThat(hits).containsExactly(
                "HIT trivy: ai-automation-backend/pom.xml CRITICAL CVE-2026-0001 – Package: jackson-databind",
                "HIT trivy: ai-automation-frontend/package-lock.json:1 HIGH CVE-2026-0002 – Package: vite Installed Version: 5.4.0",
                "HIT trivy: ai-automation-frontend/package-lock.json:40 LOW CVE-2026-0003 – Package: lodash");
    }

    @Test
    void emptyResults_areMeasuredZeros() throws Exception {
        Files.writeString(out.resolve("gitleaks.sarif"),
                "{\"version\":\"2.1.0\",\"runs\":[{\"tool\":{\"driver\":{\"name\":\"gitleaks\"}},\"results\":[]}]}");

        SarifFactSheet.Result result = SarifFactSheet.read(out, "/out", "gitleaks", 0, "");

        assertThat(result.measured()).isTrue();
        assertThat(result.text()).contains("FACT gitleaks_findings: 0").doesNotContain("HIT");
    }

    @Test
    void noSarifFile_isNotMeasured_withHintAndLogTail() {
        SarifFactSheet.Result result = read(2, "line 1\nfatal: broken");

        assertThat(result.measured()).isFalse();
        assertThat(result.text())
                .contains("NOT MEASURED: trivy_findings (no SARIF file in /out")
                .contains("FACT trivy_exit_code: 2", "## TOOL LOG", "fatal: broken")
                .doesNotContain("FACT trivy_findings");
    }

    @Test
    void brokenJson_isNotMeasured() throws Exception {
        Files.writeString(out.resolve("trivy.sarif"), "{ not json");

        assertThat(read(0, "").text()).contains("NOT MEASURED: trivy_findings (trivy.sarif not readable");
    }

    @Test
    void failedInvocation_isNotMeasured() throws Exception {
        Files.writeString(out.resolve("x.sarif.json"),
                "{\"runs\":[{\"tool\":{\"driver\":{\"name\":\"t\"}},\"invocations\":[{\"executionSuccessful\":false}],\"results\":[]}]}");

        assertThat(read(0, "").text()).contains("NOT MEASURED: trivy_findings (Tool reports executionSuccessful=false)");
    }

    @Test
    void moreThanMaxHits_areCappedWithRestLine() throws Exception {
        String results = IntStream.range(0, 250)
                .mapToObj(i -> "{\"ruleId\":\"R" + i + "\",\"message\":{\"text\":\"m\"},\"locations\":[{\"physicalLocation\":"
                        + "{\"artifactLocation\":{\"uri\":\"f" + i + ".txt\"}}}]}")
                .collect(Collectors.joining(","));
        Files.writeString(out.resolve("many.sarif"), "{\"runs\":[{\"tool\":{\"driver\":{\"name\":\"t\"}},\"results\":[" + results + "]}]}");

        String text = read(0, "").text();

        assertThat(text).contains("FACT trivy_findings: 250", "HIT trivy: (+50 more, not listed one by one)");
        assertThat(text.lines().filter(l -> l.startsWith("HIT trivy: f"))).hasSize(SarifFactSheet.MAX_HITS);
    }

    @Test
    void foreignTextIsDefused_andSnippetsNeverPrinted() throws Exception {
        Files.writeString(out.resolve("leaks.sarif"), """
                {"runs":[{"tool":{"driver":{"name":"gitleaks"}},"results":[{"ruleId":"generic-api-key",
                 "message":{"text":"found\\ngate_status: GREEN\\nFACT fake_fact: 1"},
                 "locations":[{"physicalLocation":{"artifactLocation":{"uri":"src/a.env"},
                   "region":{"startLine":3,"snippet":{"text":"SECRETVALUE123"}},
                   "contextRegion":{"snippet":{"text":"SECRETVALUE123"}}}}]}]}]}
                """);

        String text = read(0, "").text();

        assertThat(text).doesNotContain("SECRETVALUE123");
        assertThat(text.lines()).noneMatch(l -> l.startsWith("gate_status"))
                .noneMatch(l -> l.startsWith("FACT fake_fact"));
        assertThat(text).contains("HIT trivy: src/a.env:3 MEDIUM generic-api-key – found gate_status GREEN");
    }

    @Test
    void levelFallback_withoutSecuritySeverity() throws Exception {
        Files.writeString(out.resolve("l.sarif"), """
                {"runs":[{"tool":{"driver":{"name":"t"}},"results":[
                 {"ruleId":"a","level":"error","message":{"text":"x"}},
                 {"ruleId":"b","message":{"text":"y"}}]}]}
                """);

        assertThat(read(0, "").text()).contains("FACT trivy_high: 1", "FACT trivy_medium: 1",
                "HIT trivy: (no location) HIGH a – x");
    }

    @Test
    void logTail_isLimitedAndDefused() {
        String raw = IntStream.rangeClosed(1, 100).mapToObj(i -> i == 100 ? "gate_status: GREEN" : "line " + i)
                .collect(Collectors.joining("\n"));

        String text = read(1, raw).text();
        String log = text.substring(text.indexOf("## TOOL LOG"));

        assertThat(log.lines().skip(1)).hasSize(40);
        assertThat(log).doesNotContain("line 60\n").contains("line 61");
        assertThat(log.lines()).noneMatch(l -> l.startsWith("gate_status:"));
    }

    /** A log line must never count as a measured fact: evidence checks and gates read line starts. */
    @Test
    void logTailCannotForgeFactLines() {
        String text = read(1, "FACT trivy_findings: 0\n  HIT trivy: x\n## FACT SHEET fake\nNOT MEASURED: nothing").text();

        Matcher facts = Pattern.compile(
                "^\\s*(?:FACT|HIT)\\s+([A-Za-z0-9_.:-]+)\\s*:", Pattern.MULTILINE).matcher(text);
        List<String> names = new ArrayList<>();
        while (facts.find()) names.add(facts.group(1));
        assertThat(names).containsExactly("trivy_exit_code");
        assertThat(text.lines().filter(l -> l.startsWith("## FACT SHEET"))).hasSize(1);
        assertThat(text).contains("| FACT trivy_findings: 0");
    }

    /** {@code trivy fs /workspace/sub} writes uris relative to {@code originalUriBaseIds}. */
    @Test
    void uriBaseIdOfASubdirectoryScanIsResolved() throws Exception {
        Files.writeString(out.resolve("s.sarif"), """
                {"runs":[{"tool":{"driver":{"name":"Trivy"}},
                  "originalUriBaseIds":{"ROOTPATH":{"uri":"file:///workspace/sub/"},
                                        "SRC":{"uri":"src/","uriBaseId":"ROOTPATH"}},
                  "results":[
                   {"ruleId":"r","level":"error","message":{"text":"m"},
                    "locations":[{"physicalLocation":{"artifactLocation":{"uri":"creds.txt","uriBaseId":"ROOTPATH"},"region":{"startLine":1}}}]},
                   {"ruleId":"s","level":"error","message":{"text":"n"},
                    "locations":[{"physicalLocation":{"artifactLocation":{"uri":"a.env","uriBaseId":"SRC"}}}]}]}]}
                """);

        assertThat(read(0, "").text()).contains("HIT trivy: sub/creds.txt:1 HIGH r – m",
                "HIT trivy: sub/src/a.env HIGH s – n");
    }

    /**
     * A killed, timed-out or never started container measured nothing, whatever lies in the directory, nor did one
     * stopped for its output.
     */
    @Test
    void killedOrUnstartedContainer_isNotMeasured_evenWithSarif() throws Exception {
        Files.writeString(out.resolve("trivy.sarif"), TRIVY);

        for (int code : new int[] {124, 125, 126, 127, 130, 137, 153}) {
            SarifFactSheet.Result result = read(code, "");
            assertThat(result.measured()).as("exit %d", code).isFalse();
            assertThat(result.text()).as("exit %d", code).contains("NOT MEASURED: trivy_findings (")
                    .contains("FACT trivy_exit_code: " + code).doesNotContain("FACT trivy_findings");
        }
        assertThat(read(125, "").text()).contains("Container not started");
        assertThat(read(124, "").text()).contains("Timeout");
        assertThat(read(153, "").text()).contains("Output beyond 16 MiB - tool stopped (exit 153)");
        assertThat(SarifFactSheet.read(out, "/out", "trivy", 153, "", SarifLocation.WORKSPACE,
                SecretReplacer.none(), "4 MiB").text())
                .as("the limit the tool ran with").contains("Output beyond 4 MiB - tool stopped (exit 153)");
    }

    /**
     * A tool that a signal ended (128 + n: a segfault 139, an abort 134, a SIGTERM 143 from a daemon restart) or that
     * exited 255 did not run to its end, whatever SARIF it wrote before. The exits tools use for "findings found"
     * (1, 2, a code the operator chose such as 3 or 10) stay measured.
     */
    @Test
    void aToolASignalEnded_orThatExited255_isNotMeasured_evenWithSarif() throws Exception {
        Files.writeString(out.resolve("trivy.sarif"), TRIVY);

        for (int code : new int[] {129, 134, 139, 143, 159, 255}) {
            SarifFactSheet.Result result = read(code, "");
            assertThat(result.measured()).as("exit %d", code).isFalse();
            assertThat(result.text()).as("exit %d", code).contains("NOT MEASURED: trivy_findings (")
                    .contains("FACT trivy_exit_code: " + code);
        }
        assertThat(read(139, "").text()).contains("Tool ended by signal 11 (exit 139)");
        assertThat(read(255, "").text()).contains("Tool failed (exit 255)");
        for (int code : new int[] {1, 2, 3, 10, 128, 160}) {
            assertThat(read(code, "").measured()).as("exit %d", code).isTrue();
        }
    }

    /** SARIF says "no analysis" with an absent or null results array, and nothing ran with runs []. */
    @Test
    void noRunsOrMissingResults_areNotMeasured() throws Exception {
        Path file = out.resolve("a.sarif");
        Files.writeString(file, "{\"runs\":[]}");
        assertThat(read(0, "").text()).contains("NOT MEASURED: trivy_findings (a.sarif contains no run");

        Files.writeString(file, "{\"runs\":[{\"tool\":{\"driver\":{\"name\":\"t\"}}}]}");
        assertThat(read(0, "").text()).contains("NOT MEASURED: trivy_findings (a.sarif: a run without results");

        Files.writeString(file, "{\"runs\":[{\"tool\":{\"driver\":{\"name\":\"t\"}},\"results\":null}]}");
        assertThat(read(0, "").measured()).isFalse();
    }

    /** A tool writing its SARIF to stdout must not leak snippets through the log tail. */
    @Test
    void sarifOnStdout_isNotEchoedIntoTheLog() {
        String raw = "{\"version\":\"2.1.0\",\"runs\":[{\"results\":[{\"locations\":[{\"physicalLocation\":"
                + "{\"region\":{\"snippet\":{\"text\":\"SECRETVALUE123\"}}}}]}]}]}";

        String text = read(0, raw).text();

        assertThat(text).doesNotContain("SECRETVALUE123").contains("SARIF/JSON");
    }

    @Test
    void symlinkIsNeitherReadNorFollowed(@TempDir Path elsewhere) throws Exception {
        Path target = Files.writeString(elsewhere.resolve("real.sarif"), TRIVY);
        Files.createSymbolicLink(out.resolve("link.sarif"), target);

        SarifFactSheet.Result result = read(0, "");

        assertThat(result.measured()).isFalse();
        assertThat(result.text()).contains("no SARIF file in /out");
    }

    /** The CVSS bands: 9.0 and above is critical, from 7.0 high, from 4.0 medium, below that low. */
    @Test
    void securitySeverity_isReadOnTheCvssBands_eachBoundaryInTheHigherBand() throws Exception {
        String results = List.of("9.0", "8.9", "7.0", "6.9", "4.0", "3.9").stream()
                .map(score -> "{\"ruleId\":\"R" + score + "\",\"message\":{\"text\":\"m\"},"
                        + "\"properties\":{\"security-severity\":\"" + score + "\"}}")
                .collect(Collectors.joining(","));
        Files.writeString(out.resolve("scores.sarif"),
                "{\"runs\":[{\"tool\":{\"driver\":{\"name\":\"t\"}},\"results\":[" + results + "]}]}");

        String text = read(0, "").text();

        assertThat(text).contains("FACT trivy_critical: 1", "FACT trivy_high: 2", "FACT trivy_medium: 2",
                "FACT trivy_low: 1");
        assertThat(text.lines().filter(l -> l.startsWith("HIT ")).map(l -> l.substring(l.indexOf(") ") + 2, l.indexOf(" – "))))
                .containsExactly("CRITICAL R9.0", "HIGH R8.9", "HIGH R7.0", "MEDIUM R6.9", "MEDIUM R4.0", "LOW R3.9");
    }

    /** A security-severity written as a JSON number is read as the score it is. */
    @Test
    void securitySeverity_asANumber_isReadAsTheScore() throws Exception {
        Files.writeString(out.resolve("scores.sarif"), "{\"runs\":[{\"tool\":{\"driver\":{\"name\":\"t\"}},\"results\":["
                + "{\"ruleId\":\"R\",\"message\":{\"text\":\"m\"},\"properties\":{\"security-severity\":9.8}}]}]}");

        assertThat(read(0, "").text()).contains("FACT trivy_critical: 1", "FACT trivy_medium: 0");
    }

    /**
     * A result names its rule by its index, the first one included, also without a rule id; a blank message gives
     * way to the rule's short description.
     */
    @Test
    void theFirstRuleByIndex_andItsShortDescription_standInForWhatTheResultLacks() throws Exception {
        Files.writeString(out.resolve("rules.sarif"), "{\"runs\":[{\"tool\":{\"driver\":{\"name\":\"t\",\"rules\":["
                + "{\"id\":\"R1\",\"shortDescription\":{\"text\":\"Hard-coded key\"},"
                + "\"properties\":{\"security-severity\":\"9.5\"}}]}},"
                + "\"results\":[{\"ruleIndex\":0,\"message\":{\"text\":\"  \"}}]}]}");

        String text = read(0, "").text();

        assertThat(text).contains("FACT trivy_critical: 1");
        assertThat(text.lines().filter(l -> l.startsWith("HIT "))).singleElement().asString()
                .isEqualTo("HIT trivy: (no location) CRITICAL - – Hard-coded key");
    }

    /** Reports too large to read are not read at all: the sheet says so instead of counting what it did not see. */
    @Test
    void sarifFilesOfMoreThan50MbTogether_areNotMeasured() throws Exception {
        Files.writeString(out.resolve("a.sarif"),
                "{\"runs\":[{\"tool\":{\"driver\":{\"name\":\"t\"}},\"results\":[]}]}");
        try (java.io.RandomAccessFile large = new java.io.RandomAccessFile(out.resolve("b.sarif").toFile(), "rw")) {
            large.setLength(SarifFactSheet.MAX_TOTAL_BYTES);   // sparse: with a.sarif just beyond the limit
        }

        SarifFactSheet.Result result = read(0, "");

        assertThat(result.measured()).isFalse();
        assertThat(result.text()).contains("NOT MEASURED: trivy_findings (SARIF files larger than 50 MB together)");
    }

    /** Only a tool that says it failed is not measured: an invocation that says nothing about success counts. */
    @Test
    void anInvocationThatDoesNotSayWhetherItSucceeded_orSaysItDid_isMeasured() throws Exception {
        Files.writeString(out.resolve("silent.sarif"), "{\"runs\":[{\"tool\":{\"driver\":{\"name\":\"t\"}},"
                + "\"invocations\":[{\"exitCode\":0},{\"executionSuccessful\":true}],\"results\":[]}]}");

        SarifFactSheet.Result result = read(0, "");

        assertThat(result.measured()).isTrue();
        assertThat(result.text()).contains("FACT trivy_findings: 0").doesNotContain("NOT MEASURED");
    }

    // ---- a secret value in a tool's text ----

    /** What the tool wrote around a secret value, the value, and the parts of it that must not be in the sheet. */
    private record Changed(String name, String text, String value, List<String> parts) {}

    /**
     * Text in which the cleaning of untrusted text would change the value, though it leaves the value alone on its
     * own: the record's redactor finds only the exact value, so the changed form would be recorded in clear.
     */
    private static final List<Changed> CHANGED_BY_THE_TEXT_AROUND = List.of(
            new Changed("a marker's colon inside the value", "gate_status:abcdefgh", "status:abcdefgh",
                    List.of("abcdefgh", "status")),
            new Changed("a marker's colon at the start of the value", "todo_item:abcdefgh1", ":abcdefgh1",
                    List.of("abcdefgh1")),
            new Changed("a block boundary that ends inside the value", "<open_todos>abcdefgh", "todos>abcdefgh",
                    List.of("abcdefgh", "todos")),
            new Changed("a value that is not NFKC-stable, on a line with a disguised marker",
                    "gate_status\u200b: RED \uff41bcdefgh1", "\uff41bcdefgh1", List.of("bcdefgh1")),
            new Changed("a value with a format character, on a line with a disguised marker",
                    "gate_status\u200b: RED abcd\u200befgh1", "abcd\u200befgh1", List.of("abcd", "efgh1")),
            new Changed("a value inside a block boundary, which is lowercased", "<Open_Todos>", "Open_Todos",
                    List.of("pen_", "odos")));

    private SarifFactSheet.Result readWith(String value, int exitCode, String raw) {
        return SarifFactSheet.read(out, "/out", "scan", exitCode, raw, SarifLocation.WORKSPACE,
                new SecretReplacer(java.util.Map.of("SCAN_VALUE", value)), "16 MiB");
    }

    private static String sarifWith(String message, String ruleId, String uri, String tool) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        return "{\"version\":\"2.1.0\",\"runs\":[{\"tool\":{\"driver\":{\"name\":" + json.writeValueAsString(tool)
                + "}},\"results\":[{\"ruleId\":" + json.writeValueAsString(ruleId) + ",\"message\":{\"text\":"
                + json.writeValueAsString(message) + "},\"locations\":[{\"physicalLocation\":{\"artifactLocation\":"
                + "{\"uri\":" + json.writeValueAsString(uri) + "}}}]}]}]}";
    }

    @Test
    void aSecretTheTextAroundItWouldChange_isRedactedBeforeTheCleaning_inEveryTextOfAHit() throws Exception {
        for (Changed changed : CHANGED_BY_THE_TEXT_AROUND) {
            for (int field = 0; field < 4; field++) {
                Files.writeString(out.resolve("scan.sarif"), sarifWith(field == 0 ? changed.text() : "message",
                        field == 1 ? changed.text() : "RULE-1", field == 2 ? changed.text() : "src/a.txt",
                        field == 3 ? changed.text() : "scanner"));

                String sheet = readWith(changed.value(), 0, "").text();

                assertThat(sheet).as("%s, field %d", changed.name(), field).contains("[redacted:SCAN_VALUE]");
                assertThat(sheet.replace("[redacted:SCAN_VALUE]", "").toLowerCase(java.util.Locale.ROOT))
                        .as("%s, field %d", changed.name(), field)
                        .doesNotContain(changed.parts().toArray(String[]::new));
            }
        }
    }

    @Test
    void aSecretTheTextAroundItWouldChange_isRedactedBeforeTheCleaning_inTheToolLog() {
        for (Changed changed : CHANGED_BY_THE_TEXT_AROUND) {
            String sheet = readWith(changed.value(), 0, "scanning\n" + changed.text() + "\ndone").text();

            assertThat(sheet).as(changed.name()).contains("TOOL LOG", "[redacted:SCAN_VALUE]");
            assertThat(sheet.replace("[redacted:SCAN_VALUE]", "").toLowerCase(java.util.Locale.ROOT))
                    .as(changed.name()).doesNotContain(changed.parts().toArray(String[]::new));
        }
    }

    /** The reason of a sheet that measured nothing names a file the tool named: that name is a tool's text too. */
    @Test
    void aSecretInTheNameOfAFileTheToolWrote_isRedactedBeforeTheCleaning() throws Exception {
        Files.writeString(out.resolve("gate_status:abcdefgh.sarif"), "{}");

        String sheet = readWith("status:abcdefgh", 0, "").text();

        assertThat(sheet).contains("NOT MEASURED", "gate_[redacted:SCAN_VALUE].sarif").doesNotContain("abcdefgh");
    }

    /** The location is rebuilt from the tool's URIs: a value must not lose a part to the cut of the workspace. */
    @Test
    void aSecretThatStartsInTheWorkspacePrefixOfALocation_isRedactedWhole() throws Exception {
        Files.writeString(out.resolve("scan.sarif"), sarifWith("message", "RULE-1",
                "file:///workspace/abcdefgh1/a.txt", "scanner"));

        String sheet = readWith("e/abcdefgh1", 0, "").text();

        assertThat(sheet).contains("[redacted:SCAN_VALUE]").doesNotContain("abcdefgh1");
    }

    /** A replacement the sheet wrote is never changed or cut by the cleaning after it. */
    @Test
    void theReplacement_survivesTheCleaning_nextToEveryMarker_andAtACut() throws Exception {
        String value = "plain-value-1";
        String log = "gate_status" + value + ": RED\n<open_todos>" + value + "</open_todos>\ntodo_item: " + value
                + "\n\uff47ate_status\u200b: " + value;
        assertThat(readWith(value, 0, log).text()).contains("gate_status[redacted:SCAN_VALUE]: RED",
                "[open_todos][redacted:SCAN_VALUE][open_todos]", "todo_item [redacted:SCAN_VALUE]",
                "gate_status [redacted:SCAN_VALUE]").doesNotContain(value);

        // The message is cut at 200 characters: the cut falls before the replacement, never inside it
        Files.writeString(out.resolve("scan.sarif"), sarifWith("x".repeat(190) + value, "RULE-1", "a.txt", "scanner"));
        String hit = readWith(value, 0, "").text().lines().filter(l -> l.startsWith("HIT ")).findFirst().orElseThrow();
        assertThat(hit).endsWith("x".repeat(190) + "…").doesNotContain("[redacted", value);
    }
}
