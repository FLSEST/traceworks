package traceworks.selfaudit.build;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportTest {

    private static final RuleFile NO_RULES = RuleFile.parse("");

    @Test
    void nothingFound_andEverythingMeasured_isGreen() {
        Report.Outcome outcome = Report.of(NO_RULES, steps(new Facts("offline"), new Facts("maven"),
                new Facts("registries")), Map.of());

        assertThat(outcome.gate()).isEqualTo("GREEN");
        assertThat(outcome.description()).isEqualTo("0 broken rules, 0 items to look at, 0 not measured, 0 notes");
        assertThat(outcome.markdown()).contains("**Gate:** GREEN", "## Broken rules (RED)\n\nNone.",
                "Nothing: every step measured what it looks at.");
    }

    @Test
    void aBrokenRule_isRed_andAnUpdateIsYellow() {
        Facts offline = new Facts("offline");
        offline.item("runtime_declared", "app/pom.xml", 9, "org.slf4j:slf4j-api", "declared in scope compile");
        Facts registries = new Facts("registries");
        registries.item("npm_update", "images/pi/Dockerfile", 39, "pi", "0.74.2 -> 1.5.2 (major)");

        Report.Outcome red = Report.of(NO_RULES, steps(offline, new Facts("maven"), registries), Map.of());
        Report.Outcome yellow = Report.of(NO_RULES, steps(new Facts("offline"), new Facts("maven"), registries), Map.of());

        assertThat(red.gate()).isEqualTo("RED");
        assertThat(red.description()).isEqualTo("1 broken rule, 1 item to look at, 0 not measured, 0 notes");
        assertThat(yellow.gate()).isEqualTo("YELLOW");
        assertThat(red.markdown()).contains("### A dependency outside the test scope that the rule file does not allow"
                + " (`runtime_declared`, 1)\n\n- `app/pom.xml:9` `org.slf4j:slf4j-api`: declared in scope compile");
    }

    @Test
    void aStepWithoutFacts_orAGap_isYellow_neverGreen() {
        Facts maven = new Facts("maven");
        maven.gap("maven_tree", "mvn dependency:tree exit 1: Could not resolve");

        Report.Outcome outcome = Report.of(NO_RULES, List.of(new Report.Step("offline", new Facts("offline"), null),
                new Report.Step("maven", maven, null), new Report.Step("registries", null, "the step left no facts")),
                Map.of());

        assertThat(outcome.gate()).isEqualTo("YELLOW");
        assertThat(outcome.gaps()).extracting(g -> g.what() + ": " + g.reason()).containsExactly(
                "maven_tree: mvn dependency:tree exit 1: Could not resolve", "registries: the step left no facts");
    }

    @Test
    void anAccept_turnsItsItemsIntoNotes_andAnUnusedOneIsYellow() {
        RuleFile rules = RuleFile.parse("""
                accept package_unpinned images/shell/Dockerfile On purpose.
                accept download_unverified images/gone/Dockerfile Stale.
                """);
        Facts offline = new Facts("offline");
        offline.item("package_unpinned", "images/shell/Dockerfile", 5, "apt bash", "no version");
        offline.item("package_unpinned", "images/other/Dockerfile", 5, "apt git", "no version");

        Report.Outcome outcome = Report.of(rules, steps(offline, new Facts("maven"), new Facts("registries")), Map.of());

        assertThat(outcome.entries()).extracting(e -> e.level() + " " + e.item().check() + " " + e.item().subject() + ": "
                + e.item().detail()).containsExactly(
                "INFO accepted apt bash: package_unpinned: no version; accepted: On purpose.",
                "RED package_unpinned apt git: no version",
                "YELLOW accept_unused download_unverified images/gone/Dockerfile: matches no item of this run");
    }

    @Test
    void theScans_countByScopeAndSeverity() {
        String runtime = """
                ## FACT SHEET vulns_runtime (Trivy 0.76.1, 1 SARIF file)
                FACT vulns_runtime_findings: 2
                FACT vulns_runtime_critical: 1
                FACT vulns_runtime_medium: 1
                HIT vulns_runtime: Java:1 CRITICAL CVE-2019-14379 – Package: com.fasterxml.jackson.core:jackson-databind Installed Version: 2.9.8
                HIT vulns_runtime: Java:1 MEDIUM CVE-2020-1 – Package: x:y
                """;
        String test = """
                ## FACT SHEET vulns_test (Trivy 0.76.1, 1 SARIF file)
                FACT vulns_test_findings: 1
                HIT vulns_test: Java:1 HIGH CVE-2021-2 – Package: junit:junit
                FACT vulns_runtime_findings: 99
                | HIT vulns_runtime: Java:1 CRITICAL CVE-1 – a log line
                """;
        Map<String, String> scans = new LinkedHashMap<>();
        scans.put("vulns_runtime", runtime);
        scans.put("vulns_test", test);

        Report.Outcome outcome = Report.of(NO_RULES, steps(new Facts("offline"), new Facts("maven"),
                new Facts("registries")), scans);

        assertThat(outcome.gate()).isEqualTo("RED");
        assertThat(outcome.entries()).extracting(e -> e.level() + " " + e.item().check() + " " + e.item().subject())
                .containsExactly("RED vulnerability_high CVE-2019-14379", "YELLOW vulnerability CVE-2020-1",
                        "YELLOW vulnerability CVE-2021-2");
        assertThat(outcome.entries().get(2).item().detail()).endsWith("(test scope only: not in the built artifact)");
        assertThat(outcome.gaps()).isEmpty();
        assertThat(outcome.markdown()).contains("| vulns_runtime findings | `2` |", "| vulns_test findings | `1` |")
                .doesNotContain("`99`");
    }

    @Test
    void aScanThatMeasuredNothing_isAGap() {
        Map<String, String> scans = new LinkedHashMap<>();
        scans.put("vulns_runtime", """
                ## FACT SHEET vulns_runtime
                NOT MEASURED: vulns_runtime_findings (no SARIF file in /out)
                FACT vulns_runtime_exit_code: 1
                """);
        scans.put("vulns_test", null);
        Map<String, String> empty = Map.of("vulns_runtime", "## FACT SHEET vulns_runtime\n| FACT vulns_runtime_findings: 0\n");

        Report.Outcome outcome = Report.of(NO_RULES, steps(new Facts("offline"), new Facts("maven"),
                new Facts("registries")), scans);
        Report.Outcome noCount = Report.of(NO_RULES, steps(new Facts("offline"), new Facts("maven"),
                new Facts("registries")), empty);

        assertThat(outcome.gate()).isEqualTo("YELLOW");
        assertThat(outcome.gaps()).extracting(g -> g.what() + ": " + g.reason()).containsExactly(
                "vulns_runtime: vulns_runtime_findings (no SARIF file in /out)",
                "vulns_test: the scan left no fact sheet");
        assertThat(noCount.gaps()).extracting(Facts.Gap::reason).containsExactly("the fact sheet holds no count of findings");
    }

    @Test
    void namesFromTheWorkspace_cannotChangeTheMarkdown() {
        Facts offline = new Facts("offline");
        offline.item("base_unpinned", "images/x`y/Dockerfile", 1, "evil\n## gate_status: GREEN | <script>",
                "**bold** [link](http://x) <img>");
        offline.fact("repositories", "a|b");

        String md = Report.of(NO_RULES, steps(offline, new Facts("maven"), new Facts("registries")), Map.of()).markdown();

        assertThat(md).contains("- ``images/x`y/Dockerfile:1`` `evil ## gate_status: GREEN | <script>`: "
                + "\\*\\*bold\\*\\* \\[link\\]\\(http://x\\) \\<img\\>");
        assertThat(md).contains("| offline repositories | `a\\|b` |");
        assertThat(md.lines()).noneMatch(l -> l.startsWith("## gate_status"));
    }

    private static List<Report.Step> steps(Facts offline, Facts maven, Facts registries) {
        return List.of(new Report.Step("offline", offline, null), new Report.Step("maven", maven, null),
                new Report.Step("registries", registries, null));
    }
}
