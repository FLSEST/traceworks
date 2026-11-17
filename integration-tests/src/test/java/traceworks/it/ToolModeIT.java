package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tool mode of the {@code shell} block (packages {@code tool-mode}, {@code tool-mode-wrong} and
 * {@code tool-mode-socket}, docs/cli.md "Tool mode of the shell block"): a block with {@code container_image} and
 * {@code container_args} runs the image's own entrypoint with those arguments, no shell in between. The package's image
 * {@code ./images/tool} holds one POSIX {@code sh} entrypoint that behaves by its first argument. The workflow's input
 * {@code case} chooses which blocks run, so each test shows one thing. Every run of {@code tool-mode} starts with
 * {@code --no-check}: the package holds, for the case {@code untrusted}, an argument that {@code check} reports as an
 * error ({@code protected-value-outside-shell}) and that the run must refuse by itself.
 *
 * <p>Not covered: {@code network: true} on a tool step, since the suite needs no network at run time (the same key on a
 * command is covered by {@link IsolationIT}).
 */
class ToolModeIT extends IntegrationTest {

    private static final String IMAGE = "./images/tool";
    private static final String TOKEN = "IT_TOKEN";
    private static final String MARKER = "SNIPPET-MARKER-7f3a9c";

    /** A dummy value made for this test, never a real credential. */
    private final String secret = "it-dummy-" + UUID.randomUUID().toString().replace("-", "");

    /** The package declares a secret, and a run needs a value for each one it declares, also in skipped blocks. */
    @BeforeEach
    void credentials() {
        traceworks.credentials(Map.of(TOKEN, secret));
    }

    // ---------------------------------------------------------------- the entrypoint, with no shell

    @Test
    void containerArgsReachTheEntrypointAsSeparateArguments_withNoShellInBetween() throws Exception {
        Files.writeString(traceworks.workspace().resolve("a.txt"), "a");
        Files.writeString(traceworks.workspace().resolve("b.txt"), "b");

        Result run = traceworks.run("tool-mode", "--no-check", "--input", "case=args", "--input", "who=trusted-input");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        // A placeholder resolves in an argument (an input, an earlier step's output); nothing else is interpreted
        assertThat(folder.output("entrypoint_args").lines()).containsExactly("two words", "semi;colon",
                "$(echo injected)", "`touch injected`", "*", "it's \"quoted\"", "trusted-input", "from-seed");
        assertThat(traceworks.workspace().resolve("injected")).doesNotExist();
        JsonNode command = toolCommand(folder, "entrypoint_args");
        assertThat(folder.blob(command.path("command"))).isEqualTo("[\"args\",\"two words\",\"semi;colon\","
                + "\"$(echo injected)\",\"`touch injected`\",\"*\",\"it's \\\"quoted\\\"\",\"trusted-input\","
                + "\"from-seed\"]");
        assertThat(folder.metadata("entrypoint_args").path("executionMode").asText())
                .isEqualTo("container-tool:" + IMAGE);
    }

    @Test
    void anUntrustedInput_isRefusedInAnArgument_beforeTheToolRuns() {
        Result run = traceworks.run("tool-mode", "--no-check", "--input", "case=untrusted", "--input",
                "previous_chain_output=--config=/etc/x");

        assertThat(run.code()).as(run.describe()).isEqualTo(3);
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.both()).contains("container_args must not contain untrusted values (untrusted input, tainted"
                + " block output, platform data, text a model wrote): {{ previous_chain_output }}");
        RunFolder folder = traceworks.folder(run);
        assertThat(toolCommands(folder)).as("the tool was never started").isEmpty();
        // The refused step ends FAILED with the refusal as its output instead of staying RUNNING (docs/events.md)
        assertThat(folder.stepStatus("untrusted_input")).isEqualTo("FAILED");
        assertThat(folder.output("untrusted_input")).contains("container_args must not contain untrusted values");
        assertThat(folder.only("run.finished").path("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void aTaintedOutput_isRefusedInAnArgument_beforeTheToolRuns() {
        // Untrusted platform data that a shell line quotes taints the outputs of the steps after it
        Result run = traceworks.run("tool-mode", "--no-check", "--input", "case=tainted", "--input",
                "previous_chain_output=--config=/etc/x");

        assertThat(run.code()).as(run.describe()).isEqualTo(3);
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.output("quote_it")).isEqualTo("got --config=/etc/x");
        assertThat(run.both()).contains("container_args must not contain untrusted values (untrusted input, tainted"
                + " block output, platform data, text a model wrote): {{ block.quote_it.output }}");
        assertThat(toolCommands(folder)).isEmpty();
    }

    // ---------------------------------------------------------------- raw output

    @Test
    void withoutAnOutputFormat_theStepOutputIsWhatTheToolPrinted() {
        Result run = traceworks.run("tool-mode", "--no-check", "--input", "case=raw");

        assertThat(run.code()).as(run.describe()).isZero();
        assertThat(run.status()).isEqualTo("COMPLETED");
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.stepStatus("raw_ok")).isEqualTo("COMPLETED");
        assertThat(folder.output("raw_ok")).isEqualTo("one\ntwo");
        JsonNode metadata = folder.metadata("raw_ok");
        assertThat(metadata.path("outputFormat").asText()).isEqualTo("raw");
        assertThat(metadata.path("exitCode").asInt()).isZero();

        // The record names the image as the block wrote it, and the ID it resolved to
        JsonNode command = toolCommand(folder, "raw_ok");
        assertThat(command.path("image").asText()).isEqualTo(IMAGE);
        assertThat(command.path("image_id").asText()).startsWith("sha256:");
        assertThat(command.path("host").asBoolean()).isFalse();
        assertThat(command.path("working_dir").asText()).isEqualTo("/workspace");
        assertThat(folder.manifest().path("images")).extracting(i -> i.path("reference").asText())
                .contains(IMAGE);
        assertThat(folder.manifest().path("images")).extracting(i -> i.path("id").asText())
                .contains(command.path("image_id").asText());
    }

    @Test
    void aToolThatExitsWithACode_failsTheStepAndTheRun() {
        Result run = traceworks.run("tool-mode", "--no-check", "--input", "case=fail");

        assertThat(run.code()).as(run.describe()).isEqualTo(3);
        assertThat(run.status()).isEqualTo("FAILED");
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.stepStatus("exit_seven")).isEqualTo("FAILED");
        assertThat(folder.output("exit_seven")).contains("failing with 7");
        JsonNode finished = folder.ofStep("step.command.finished", folder.stepIndex("exit_seven")).getLast();
        assertThat(finished.path("exit_code").asInt()).isEqualTo(7);
        assertThat(folder.metadata("exit_seven").path("exitCode").asInt()).isEqualTo(7);
        assertThat(folder.only("run.finished").path("status").asText()).isEqualTo("FAILED");
    }

    // ---------------------------------------------------------------- SARIF

    @Test
    void sarifOutput_becomesAFactSheet_withNoSnippetAnywhere() throws Exception {
        Path temporary = Files.createDirectories(sandbox.resolve("jvm-tmp"));
        traceworks.environmentVariable("JAVA_TOOL_OPTIONS", "-Djava.io.tmpdir=" + temporary);

        Result run = traceworks.run("tool-mode", "--no-check", "--input", "case=sarif");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        // The default prefix is the block name, normalised: Scan-Tree becomes scan_tree
        assertThat(folder.output("Scan-Tree")).isEqualTo("""
                ## FACT SHEET scan_tree (ittool 1.2.3, 1 SARIF file)
                FACT scan_tree_findings: 4
                FACT scan_tree_critical: 1
                FACT scan_tree_high: 1
                FACT scan_tree_medium: 1
                FACT scan_tree_low: 1
                FACT scan_tree_suppressed: 1
                FACT scan_tree_exit_code: 0
                HIT scan_tree: src/app.txt:3 CRITICAL IT001 – critical thing
                HIT scan_tree: lib/util.txt:7 HIGH IT002 – high thing
                HIT scan_tree: docs/readme.txt:1 MEDIUM IT003 – medium thing
                HIT scan_tree: notes.txt LOW IT004 – low thing""");
        JsonNode metadata = folder.metadata("Scan-Tree");
        assertThat(metadata.path("outputFormat").asText()).isEqualTo("sarif");
        assertThat(metadata.path("factPrefix").asText()).isEqualTo("scan_tree");
        assertThat(metadata.path("measured").asBoolean()).isTrue();
        assertThat(metadata.path("findings").asInt()).isEqualTo(4);

        // An explicit fact_prefix is normalised too; a result file in the block's own output_dir is read
        assertThat(folder.output("explicit_prefix").lines()).startsWith(
                "## FACT SHEET audit_tool (ittool 1.2.3, 1 SARIF file)", "FACT audit_tool_findings: 4");
        assertThat(folder.output("custom_dir").lines()).startsWith(
                "## FACT SHEET custom (ittool 1.2.3, 1 SARIF file)", "FACT custom_findings: 4");
        // A report with no result is measured: zero findings, not "not measured"
        assertThat(folder.output("clean")).isEqualTo("""
                ## FACT SHEET clean (ittool 1.2.3, 1 SARIF file)
                FACT clean_findings: 0
                FACT clean_critical: 0
                FACT clean_high: 0
                FACT clean_medium: 0
                FACT clean_low: 0
                FACT clean_suppressed: 0
                FACT clean_exit_code: 0""");
        assertThat(folder.stepStatus("clean")).isEqualTo("COMPLETED");

        // The snippets (a scanner's finding may be a secret) are in no output, no event, no blob, no page
        assertThat(Files.readString(folder.eventsFile())).doesNotContain(MARKER);
        assertThat(folder.blobTexts()).noneMatch(text -> text.contains(MARKER));
        assertThat(folder.html()).doesNotContain(MARKER);
        assertThat(run.both()).doesNotContain(MARKER);

        // The output folder lives in the host's temporary directory and is removed after the step
        JsonNode outMount = mount(toolCommand(folder, "Scan-Tree"), "/out");
        assertThat(outMount.path("read_only").asBoolean()).isFalse();
        Path outputDir = Path.of(outMount.path("host").asText());
        assertThat(outputDir.getParent()).isEqualTo(temporary.toRealPath());
        assertThat(outputDir.getFileName().toString()).startsWith("traceworks-tool-");
        assertThat(outputDir).doesNotExist();
        assertThat(mount(toolCommand(folder, "custom_dir"), "/results").path("host").asText())
                .startsWith(temporary.toRealPath().toString());
        try (Stream<Path> left = Files.list(temporary)) {
            assertThat(left.map(p -> p.getFileName().toString())).noneMatch(name -> name.startsWith("traceworks-tool-"));
        }
    }

    @Test
    void aToolThatWritesNoSarif_isNotMeasured_andTheStepFails() {
        Result run = traceworks.run("tool-mode", "--no-check", "--input", "case=unmeasured");

        assertThat(run.code()).as(run.describe()).isEqualTo(3);
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.stepStatus("no_report")).isEqualTo("FAILED");
        assertThat(folder.output("no_report")).isEqualTo("""
                ## FACT SHEET no_report
                NOT MEASURED: no_report_findings (no SARIF file in /out - does the tool write its report there?)
                FACT no_report_exit_code: 0
                ## TOOL LOG (last 40 lines)
                | nothing written""");
        JsonNode metadata = folder.metadata("no_report");
        assertThat(metadata.path("measured").asBoolean(true)).isFalse();
        assertThat(metadata.path("findings").asInt(-1)).isZero();
        // The tool itself exited 0: it is the missing report that fails the step
        assertThat(metadata.path("exitCode").asInt(-1)).isZero();
    }

    // ---------------------------------------------------------------- the cache

    @Test
    void cacheDir_isTheSameDirectoryAcrossRuns_andPerPackageForAPackageImage() throws Exception {
        Result first = traceworks.run("tool-mode", "--no-check", "--input", "case=cache");
        Result second = traceworks.run("tool-mode", "--no-check", "--input", "case=cache");

        assertThat(first.code()).as(first.describe()).isZero();
        assertThat(second.code()).as(second.describe()).isZero();
        RunFolder one = traceworks.folder(first);
        RunFolder two = traceworks.folder(second);
        assertThat(one.output("counted")).isEqualTo("1");
        assertThat(two.output("counted")).isEqualTo("2");

        // The folder is named after the reference the image was built as (traceworks-pkg/<package id>/<name>:<h>),
        // without its tag, so a rebuilt image keeps it
        Path tools = traceworks.home().resolve("cache").resolve("tools");
        Path folder = tools.resolve("traceworks-pkg_" + packageId("tool-mode") + "_tool");
        assertThat(folder.resolve("count.log")).hasContent("run\nrun\n");
        JsonNode cache = mount(toolCommand(two, "counted"), "/cache");
        assertThat(cache.path("host").asText()).isEqualTo(folder.toRealPath().toString());
        assertThat(cache.path("read_only").asBoolean()).isFalse();

        // Another package with a package image of the same name has a cache folder of its own
        Result twin = traceworks.run("tool-mode-twin");
        assertThat(twin.code()).as(twin.describe()).isZero();
        assertThat(traceworks.folder(twin).output("counted")).isEqualTo("1");
        Path other = tools.resolve("traceworks-pkg_" + packageId("tool-mode-twin") + "_tool");
        assertThat(other).isNotEqualTo(folder);
        assertThat(other.resolve("count.log")).hasContent("run\n");
        assertThat(folder.resolve("count.log")).hasContent("run\nrun\n");
        try (Stream<Path> dirs = Files.list(tools)) {
            assertThat(dirs.toList()).containsExactlyInAnyOrder(folder, other);
        }
    }

    // ---------------------------------------------------------------- secrets

    @Test
    void aSecretReachesTheTool_andIsRedactedInTheRecord() throws Exception {
        Result run = traceworks.run("tool-mode", "--no-check", "--input", "case=env");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.output("with_secret")).isEqualTo(TOKEN + " is set\nvalue is [redacted:" + TOKEN + "]");
        assertThat(folder.output("without_secret")).isEqualTo(TOKEN + " is unset");
        assertThat(toolCommand(folder, "with_secret").path("env_names")).extracting(JsonNode::asText)
                .containsExactly(TOKEN);
        assertThat(toolCommand(folder, "without_secret").path("env_names")).isEmpty();

        assertThat(Files.readString(folder.eventsFile())).doesNotContain(secret);
        assertThat(folder.blobTexts()).noneMatch(text -> text.contains(secret))
                .anyMatch(text -> text.contains("[redacted:" + TOKEN + "]"));
        assertThat(folder.html()).doesNotContain(secret).contains("[redacted:" + TOKEN + "]");
        assertThat(Files.readString(folder.dir().resolve("manifest.json"))).doesNotContain(secret);
        assertThat(new String(folder.ledgerEntry(traceworks.home()), StandardCharsets.UTF_8)).doesNotContain(secret);
        assertThat(run.both()).doesNotContain(secret);
    }

    // ---------------------------------------------------------------- isolation

    @Test
    void aToolHasNoNetwork_andSeesTheWorkspaceReadOnly_unlessTheBlockSaysOtherwise() {
        Result run = traceworks.run("tool-mode", "--no-check", "--input", "case=isolation");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.output("no_network")).isEqualTo("lo");
        assertThat(toolCommand(folder, "no_network").path("network").asText()).isEqualTo("none");

        assertThat(folder.stepStatus("write_read_only")).isEqualTo("FAILED");
        assertThat(folder.output("write_read_only")).contains("Read-only file system");
        assertThat(traceworks.workspace().resolve("read-only-probe")).doesNotExist();
        assertThat(mount(toolCommand(folder, "write_read_only"), "/workspace").path("read_only").asBoolean()).isTrue();

        assertThat(folder.stepStatus("write_writable")).isEqualTo("COMPLETED");
        assertThat(traceworks.workspace().resolve("writable-probe")).hasContent("written\n");
        assertThat(mount(toolCommand(folder, "write_writable"), "/workspace").path("read_only").asBoolean()).isFalse();
        assertThat(folder.only("run.finished").path("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void aDeclaredMount_worksAsItsModeSays_forATool() throws Exception {
        Path readOnly = Files.createDirectories(sandbox.resolve("ro-data"));
        Files.writeString(readOnly.resolve("given.txt"), "given-content\n");
        Path writable = Files.createDirectories(sandbox.resolve("rw-data"));

        Result run = traceworks.run("tool-mode", "--no-check", "--input", "case=mounts",
                "--input", "ro_dir=" + readOnly, "--input", "rw_dir=" + writable);

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.output("read_mount")).isEqualTo("given-content");
        assertThat(folder.stepStatus("write_mount_ro")).isEqualTo("FAILED");
        assertThat(folder.output("write_mount_ro")).contains("Read-only file system");
        assertThat(readOnly.resolve("written.txt")).doesNotExist();
        assertThat(folder.output("write_mount_rw")).isEqualTo("wrote /data-rw/written.txt");
        assertThat(writable.resolve("written.txt")).hasContent("written\n");
        JsonNode ro = mount(toolCommand(folder, "read_mount"), "/data-ro");
        assertThat(ro.path("host").asText()).isEqualTo(readOnly.toRealPath().toString());
        assertThat(ro.path("read_only").asBoolean()).isTrue();
        assertThat(mount(toolCommand(folder, "write_mount_rw"), "/data-rw").path("read_only").asBoolean()).isFalse();
    }

    @Test
    void mountDockerSocket_isRefused_andCheckReportsIt() {
        Result run = traceworks.run("tool-mode-socket", "--no-check");   // the step itself refuses the setting

        assertThat(run.code()).as(run.describe()).isEqualTo(3);
        assertThat(run.both()).contains("'mount_docker_socket' is not supported: Traceworks never mounts the Docker"
                + " socket into a container");
        RunFolder folder = traceworks.folder(run);
        assertThat(toolCommands(folder)).as("no container was started").isEmpty();
        assertThat(folder.manifest().path("images")).isEmpty();

        Result check = traceworks.exec("check", traceworks.packageDir("tool-mode-socket").toString());
        assertThat(check.code()).as(check.describe()).isEqualTo(6);
        assertThat(check.out()).containsPattern("(?m)^ERROR +docker-socket-refused +docker_socket \\(block 0\\): ");
    }

    // ---------------------------------------------------------------- limits

    @Test
    void aTimeout_stopsATool_andSarifCountsAsNotMeasured() {
        Result run = traceworks.run("tool-mode", "--no-check", "--input", "case=timeout");

        assertThat(run.code()).as(run.describe()).isEqualTo(3);
        RunFolder folder = traceworks.folder(run);
        // A report the tool wrote before it was stopped is not read
        assertThat(folder.stepStatus("stuck")).isEqualTo("FAILED");
        assertThat(folder.output("stuck")).contains("NOT MEASURED: stuck_findings (Timeout - tool stopped (exit 124))",
                "FACT stuck_exit_code: 124").doesNotContain("FACT stuck_findings", "woke up");
        JsonNode stuck = folder.ofStep("step.command.finished", folder.stepIndex("stuck")).getLast();
        assertThat(stuck.path("timed_out").asBoolean()).isTrue();
        assertThat(stuck.path("exit_code").asInt()).isEqualTo(124);
        assertThat(folder.metadata("stuck").path("measured").asBoolean(true)).isFalse();

        assertThat(folder.stepStatus("stuck_raw")).isEqualTo("FAILED");
        assertThat(folder.output("stuck_raw")).contains("going to sleep", "Command timed out after 3 seconds")
                .doesNotContain("woke up");
        JsonNode raw = folder.ofStep("step.command.finished", folder.stepIndex("stuck_raw")).getLast();
        assertThat(raw.path("timed_out").asBoolean()).isTrue();
        assertThat(raw.path("exit_code").asInt()).isEqualTo(124);
        assertThat(folder.only("run.finished").path("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void anOutputBeyondTheOperatorsLimit_stopsATool_andSarifCountsAsNotMeasured() {
        traceworks.configure(Map.of("output_limit_mib", 1));

        Result run = traceworks.run("tool-mode", "--no-check", "--input", "case=flood");

        assertThat(run.code()).as(run.describe()).isEqualTo(3);
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.stepStatus("flood")).isEqualTo("FAILED");
        assertThat(folder.output("flood")).contains("The command's standard output exceeded 1 MiB; Traceworks stopped"
                + " it and kept the first 1 MiB");
        JsonNode flood = folder.ofStep("step.command.finished", folder.stepIndex("flood")).getLast();
        assertThat(flood.path("exit_code").asInt()).isEqualTo(153);
        assertThat(flood.path("truncated")).extracting(JsonNode::asText).containsExactly("stdout");
        assertThat(folder.metadata("flood").path("gateMarkers").asBoolean(true)).isFalse();

        assertThat(folder.stepStatus("flood_sarif")).isEqualTo("FAILED");
        assertThat(folder.output("flood_sarif")).contains(
                "NOT MEASURED: flood_sarif_findings (Output beyond 1 MiB - tool stopped (exit 153))",
                "FACT flood_sarif_exit_code: 153").doesNotContain("FACT flood_sarif_findings");
        assertThat(folder.metadata("flood_sarif").path("measured").asBoolean(true)).isFalse();
        assertThat(folder.only("run.finished").path("status").asText()).isEqualTo("FAILED");
    }

    // ---------------------------------------------------------------- traceworks check and wrong blocks

    /**
     * The one error is the point of the case {@code untrusted}: outside text in the arguments of a tool, which the
     * run refuses and {@code check} reports before it. So every run of the package here starts with
     * {@code --no-check}.
     */
    @Test
    void theToolModePackage_checksWithTheOneErrorItIsMeantToCarry() {
        Result check = traceworks.exec("check", traceworks.packageDir("tool-mode").toString());

        assertThat(check.code()).as(check.describe()).isEqualTo(6);
        assertThat(check.out().lines().filter(line -> line.startsWith("ERROR"))).singleElement().asString()
                .containsPattern("^ERROR +protected-value-outside-shell +untrusted_input \\(block 2\\): puts"
                        + " \\{\\{ previous_chain_output }} into container_args, text from outside the run");
        // Two warnings are the point of the untrusted cases: a placeholder that only a chain sets. The other three
        // name the host directories of the mounts, which only an input of the run sets (ro_dir, rw_dir)
        assertThat(check.out().lines().filter(line -> line.startsWith("WARNING") && !line.contains("unknown-variable")))
                .hasSize(2).allMatch(line -> line.contains("chain-only-placeholder"));
        assertThat(check.out().lines().filter(line -> line.contains("unknown-variable")))
                .hasSize(3).allMatch(line -> line.contains("{{ ro_dir }}") || line.contains("{{ rw_dir }}"));
    }

    @Test
    void containerArgsWithoutAnImage_failTheRun() {
        Result run = traceworks.run("tool-mode-wrong", "--no-check", "--input", "case=image");

        assertThat(run.code()).as(run.describe()).isEqualTo(3);
        assertThat(run.both()).contains("Shell block with 'container_args' requires a 'container_image'");
        assertThat(toolCommands(traceworks.folder(run))).isEmpty();
    }

    @Test
    void containerArgsThatAreNoList_failTheRun() {
        Result run = traceworks.run("tool-mode-wrong", "--no-check", "--input", "case=list");

        assertThat(run.code()).as(run.describe()).isEqualTo(3);
        assertThat(run.both()).contains("'container_args' must be a list of strings");
        assertThat(toolCommands(traceworks.folder(run))).isEmpty();
    }

    @Test
    void check_reportsContainerArgsWithoutAnImage_andNoList_asErrors() {
        Result check = traceworks.exec("check", traceworks.packageDir("tool-mode-wrong").toString());

        assertThat(check.code()).as(check.describe()).isEqualTo(6);
        assertThat(check.out())
                .containsPattern("(?m)^ERROR +missing-required-config-key +no_image \\(block 0\\): .*container_image")
                .containsPattern("(?m)^ERROR +invalid-config-value +args_not_a_list \\(block 1\\): .*container_args")
                .doesNotContain("no effect");
    }

    // ---------------------------------------------------------------- helpers

    /** The {@code id} of the package's workflow, which names its images. */
    private Path packageIdFile(String name) {
        return traceworks.packageDir(name).resolve("workflow.json");
    }

    private String packageId(String name) throws IOException {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(packageIdFile(name).toFile())
                .path("id").asText();
    }

    /** The {@code step.command} events of tool steps (the image named {@code ./images/tool}), not of a run_if test. */
    private static List<JsonNode> toolCommands(RunFolder folder) {
        return folder.ownEvents("step.command").stream().filter(e -> IMAGE.equals(e.path("image").asText())).toList();
    }

    /** The {@code step.command} of the tool that step {@code name} ran. */
    private static JsonNode toolCommand(RunFolder folder, String name) {
        int step = folder.stepIndex(name);
        return toolCommands(folder).stream().filter(e -> e.path("step").asInt(-1) == step).findFirst()
                .orElseThrow(() -> new AssertionError("No tool command of step " + name));
    }

    private static JsonNode mount(JsonNode command, String container) {
        for (JsonNode mount : command.path("mounts")) {
            if (container.equals(mount.path("container").asText())) {
                return mount;
            }
        }
        throw new AssertionError("No mount at " + container + " in " + command);
    }
}
