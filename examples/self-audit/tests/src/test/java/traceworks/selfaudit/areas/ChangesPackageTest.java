package traceworks.selfaudit.areas;

import traceworks.cli.RuntimeSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The package {@code security-changes} as it ships. It judges with the tool, the rules and the catalog of the
 * security self-audit: each package keeps its own copy, so each image's build context stays inside its package, and
 * the copies stay the same bytes, so a fix in one that is not made in the other fails the build. What is the short
 * check's own is its workflow and the script that asks git.
 */
class ChangesPackageTest {

    private static final Path PACKAGE = Path.of("../security-changes").toAbsolutePath().normalize();
    private static final Path SECURITY = Path.of("../security").toAbsolutePath().normalize();

    @TempDir
    Path temp;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> workflow(Path pkg) throws IOException {
        return (Map<String, Object>) Json.parse(Files.readString(pkg.resolve("workflow.json")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> config(Map<String, Object> workflow, String block) {
        return ((List<Map<String, Object>>) workflow.get("blocks")).stream().filter(b -> block.equals(b.get("name")))
                .map(b -> (Map<String, Object>) b.get("config")).findFirst().orElseThrow();
    }

    private static List<String> files(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).map(p -> dir.relativize(p).toString()).sorted().toList();
        }
    }

    @Test
    void theToolTheRulesAndTheCatalog_areTheSecurityPackagesByteForByte() throws IOException {
        assertThat(files(PACKAGE.resolve("images"))).isEqualTo(files(SECURITY.resolve("images"))).isNotEmpty();
        for (String file : files(SECURITY.resolve("images"))) {
            assertThat(PACKAGE.resolve("images").resolve(file)).as(file)
                    .hasBinaryContent(Files.readAllBytes(SECURITY.resolve("images").resolve(file)));
        }
        for (String file : List.of("rules/security.rules", "catalog.json")) {
            assertThat(PACKAGE.resolve(file)).as(file).hasBinaryContent(Files.readAllBytes(SECURITY.resolve(file)));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePackage_keepsTheJudgeTheToolPolicyAndTheProtectionsOfTheSecurityAudit_inARegisterOfItsOwn()
            throws IOException {
        Map<String, Object> own = workflow(PACKAGE);
        Map<String, Object> security = workflow(SECURITY);

        assertThat(own.get("id")).isNotEqualTo(security.get("id"));
        assertThat((Map<String, Object>) ((Map<String, Object>) own.get("metadata")).get("audit"))
                .containsEntry("key", "security-changes").containsEntry("report_block", "report");
        assertThat(own.get("model_choices")).isEqualTo(security.get("model_choices"));
        assertThat(own.get("requires")).isEqualTo(security.get("requires"));
        assertThat(own.get("default_context")).as("without the input, the base comes from the register")
                .isEqualTo(Map.of("since", "", "skip", ""));
        Map<String, Object> verdicts = config(own, "verdicts");
        Map<String, Object> theirs = config(security, "verdicts");
        for (String key : List.of("execution_mode", "model_choice", "tools", "untrusted_input", "items_command",
                "continue_on_error", "on_failure")) {
            assertThat(verdicts.get(key)).as(key).isEqualTo(theirs.get(key)).isNotNull();
        }
        assertThat(String.valueOf(verdicts.get("command"))).contains("{{ area.reason }}", "{{ area.connected }}",
                "{{ area.code }}", "{{ area.known_text }}");
        // The input and what the register hands the run reach a command by name, never as shell text
        Map<String, Object> changes = config(own, "changes");
        assertThat(changes.get("values")).isEqualTo(Map.of("SINCE", "{{ since }}",
                "PREVIOUS_REPORT", "{{ previous_report }}", "SKIP_COMMITS", "{{ skip }}"));
        assertThat(String.valueOf(changes.get("command"))).doesNotContain("{{ since }}", "{{ previous_report }}")
                .contains("\"$SINCE\" \"$PREVIOUS_REPORT\" \"$SKIP_COMMITS\"").doesNotContain("{{ skip }}");
        assertThat(changes).as("git is in the shell image: the step needs no image of the package, no network and"
                + " no writable workspace").doesNotContainKeys("container_image", "network", "workspace_writable");
        Map<String, Object> areas = config(own, "areas");
        assertThat(String.valueOf(areas.get("command"))).contains(" --changes {{ scratch_dir }}/changes ",
                " --register security-changes ", " --items-file {{ register_dir }}/items.jsonl ",
                " --judge {{ judge }} ").doesNotContain("{{ audit_findings }}");
        assertThat(areas).doesNotContainKeys("network", "workspace_writable");
    }

    @Test
    void thePackage_passesCheck_andIsDescribedWithItsInputAndItsFourSteps() {
        String check = cli("check", PACKAGE.toString(), "--home", temp.resolve("home").toString());
        assertThat(check).doesNotContain("ERROR").doesNotContain("WARN");

        String described = cli("describe", PACKAGE.toString(), "--home", temp.resolve("none").toString());
        assertThat(described).contains("since", "changes", "areas", "verdicts", "report", "security-changes",
                "  judge  pick with --input judge=<option>; without it: fable");
    }

    private String cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        traceworks.cli.main.Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Map.of("TRACEWORKS_ACTOR", "tester"), temp, null,
                RuntimeSource.NONE);
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }
}
