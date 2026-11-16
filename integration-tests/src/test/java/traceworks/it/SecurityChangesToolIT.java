package traceworks.it;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deterministic steps of the short security check ({@code examples/self-audit/security-changes}) in their real
 * containers, with the mounts every run has: the script in the shell image ({@code base}, {@code blobs}) and the
 * area tool in the package's own image ({@code changed}, {@code areas}, {@code report}), on a small git repository
 * as the workspace. The image, the rules and the script are the package's; a shell loop that finds nothing stands
 * in for the model, and no register is fed. It asserts which files differ, which areas are picked and what the gate
 * says. The image is built here, once for the class ({@code traceworks images build}), on the JDK image the package
 * pins by digest: that base image must be in the local image store, or the registry must be reachable. A later
 * test finds the image by its tag, which the package's id and the content of its image directory give.
 */
class SecurityChangesToolIT extends IntegrationTest {

    private static final String SRC = "app/src/main/java/p/";

    private static final String WORKFLOW = """
            {
              "format": "traceworks-workflow",
              "format_version": 1,
              "id": "5e1b1c0d-7a43-4c57-9f0a-3b7d2e94c611",
              "name": "IT security changes tool",
              "requires": ["package.images"],
              "metadata": {"gate": {"expected": true}},
              "default_context": {"since": ""},
              "blocks": [
                {"type": "shell", "name": "base", "config": {
                  "values": {"SINCE": "{{ since }}"},
                  "command": "set -euo pipefail; bash {{ package_dir }}/scripts/changes.sh base {{ scratch_dir }}/changes \\"$SINCE\\" ''",
                  "shell_escape_variables": true}},
                {"type": "shell", "name": "changed", "config": {
                  "container_image": "./images/areas",
                  "command": "set -euo pipefail; areas changed --changes {{ scratch_dir }}/changes --root .",
                  "shell_escape_variables": true}},
                {"type": "shell", "name": "blobs", "config": {
                  "command": "set -euo pipefail; bash {{ package_dir }}/scripts/changes.sh blobs {{ scratch_dir }}/changes",
                  "shell_escape_variables": true}},
                {"type": "shell", "name": "areas", "config": {
                  "container_image": "./images/areas",
                  "command": "set -euo pipefail; areas find --rules {{ package_dir }}/rules/security.rules --root . --changes {{ scratch_dir }}/changes --register security-changes --out {{ scratch_dir }}/areas.jsonl --summary {{ scratch_dir }}/areas-summary.json",
                  "timeout_seconds": 600,
                  "shell_escape_variables": true}},
                {"type": "foreach", "name": "verdicts", "config": {
                  "execution_mode": "shell", "items_source": "command",
                  "items_command": "cat {{ scratch_dir }}/areas.jsonl", "variable_name": "area",
                  "values": {"AREA_ID": "{{ area.id }}"},
                  "command": "printf '{\\"area\\":\\"%s\\",\\"findings\\":[]}' \\"$AREA_ID\\"",
                  "shell_escape_variables": true}},
                {"type": "shell", "name": "report", "config": {
                  "container_image": "./images/areas",
                  "values": {"VERDICTS": "{{ block.verdicts.output }}"},
                  "command": "set -euo pipefail; printf '%s' \\"$VERDICTS\\" | areas report --areas {{ scratch_dir }}/areas.jsonl --summary {{ scratch_dir }}/areas-summary.json --verdicts - --out {{ scratch_dir }}/report.md",
                  "shell_escape_variables": true}}
              ]
            }
            """;

    /** Six container steps, one of them javac over the workspace. */
    private static final long RUN_SECONDS = 900;
    private static boolean built;

    private Path pkg;
    private String first;

    private String git(String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(traceworks.workspace().toFile())
                .redirectErrorStream(true);
        builder.environment().putAll(Map.of("GIT_CONFIG_GLOBAL", "/dev/null", "GIT_CONFIG_NOSYSTEM", "1",
                "GIT_AUTHOR_NAME", "Tester", "GIT_AUTHOR_EMAIL", "tester@example.invalid",
                "GIT_COMMITTER_NAME", "Tester", "GIT_COMMITTER_EMAIL", "tester@example.invalid"));
        Path output = Files.createTempFile(sandbox, "git", ".txt");
        builder.redirectOutput(output.toFile());
        try {
            Process process = builder.start();
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).as(String.join(" ", args) + "\n" + Files.readString(output)).isZero();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        return Files.readString(output).strip();
    }

    private void write(String path, String content) throws IOException {
        Path file = traceworks.workspace().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private String commit() throws IOException {
        git("add", "-A");
        git("commit", "--quiet", "-m", "change");
        return git("rev-parse", "HEAD");
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path source : walk.toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) Files.createDirectories(target);
                else Files.copy(source, target);
            }
        }
    }

    @BeforeEach
    void repositoryAndPackage() throws IOException {
        git("init", "--quiet", "--initial-branch=main");
        write(SRC + "Runner.java", "package p;\nclass Runner {\n    void run(String c) throws Exception {\n"
                + "        new ProcessBuilder(c).start();\n    }\n}\n");
        write(SRC + "Caller.java", "package p;\nclass Caller {\n    void call(Runner runner) throws Exception {\n"
                + "        runner.run(System.getenv(\"CMD\"));\n    }\n}\n");
        write(SRC + "Lone.java", "package p;\nclass Lone {\n    String home() {\n        return System.getenv(\"HOME\");\n"
                + "    }\n}\n");
        first = commit();
        Path example = traceworks.examplePackage("self-audit/security-changes");
        pkg = Files.createDirectories(sandbox.resolve("changes-tool"));
        for (String part : List.of("images", "rules", "scripts")) copyTree(example.resolve(part), pkg.resolve(part));
        Files.writeString(pkg.resolve("workflow.json"), WORKFLOW, StandardCharsets.UTF_8);
        if (!built) {
            Result build = traceworks.exec(1800, "images", "build", pkg.toString());
            assertThat(build.code()).as(build.describe()).isZero();
            built = true;
        }
    }

    private Result run(String... more) {
        List<String> args = new ArrayList<>(List.of("run", pkg.toString(), "--workspace",
                traceworks.workspace().toString(), "--no-input"));
        args.addAll(List.of(more));
        return traceworks.exec(RUN_SECONDS, args.toArray(String[]::new));
    }

    @Test
    void aChangedMember_picksItsAreaAndItsNeighbour_andTheGateSpeaksAboutTheChanges() throws IOException {
        write(SRC + "Runner.java", "package p;\nclass Runner {\n    void run(String c) throws Exception {\n"
                + "        new ProcessBuilder(c, c).start();\n    }\n}\n");
        commit();

        Result run = run("--input", "since=" + first);

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.output("changed") + "\n").contains("FACT changes_files: 1\n", "FACT changes_uncommitted: false\n",
                "FACT changes_base_blobs_wanted: 1\n");
        assertThat(folder.output("blobs") + "\n").contains("FACT changes_base_blobs: 1\n");
        assertThat(folder.output("areas") + "\n").contains("FACT changes_areas_of_project: 3\n",
                "FACT changes_areas_changed: 1\n", "FACT changes_areas_neighbours: 1\n",
                "FACT changes_areas_not_looked_at: 1\n",
                " Runner.run(String) [process_start] because: changed\n",
                " Caller.call(Runner) [env_pass] because: neighbour of Runner.run(String)\n");
        assertThat(folder.output("report") + "\n").startsWith("gate_status: GREEN\ngate_category: security\n"
                + "gate_description: of the changes since " + first.substring(0, 12) + ": no verified finding; 2 of 2"
                + " areas judged; ").contains("1 of 3 areas of the project not looked at");
        assertThat(run.out()).contains("GREEN (security)");
    }

    @Test
    void aChangedImport_aChangedScript_andAFileInNoCommit_areEachSeenInTheContainers() throws IOException {
        // The head of a file, a script, and a Java file git ignores below a directory called build
        write(SRC + "Lone.java", "package p;\nimport java.util.List;\nclass Lone {\n    String home() {\n"
                + "        return System.getenv(\"HOME\");\n    }\n}\n");
        write("run.sh", "echo 1\n");
        commit();
        write(".git/info/exclude", "build/\n");
        write(SRC + "build/Fresh.java", "package p.build;\nclass Fresh {\n    String f() {\n"
                + "        return System.getenv(\"X\");\n    }\n}\n");

        Result run = run("--input", "since=" + first);

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.output("changed") + "\n").contains("FACT changes_files: 3\n", "FACT changes_uncommitted: true\n");
        assertThat(folder.output("areas") + "\n").contains("FACT changes_files_cannot_judge: 1\n",
                " Lone.home() [env_pass] because: changed: the head of its file (outside every member)\n",
                " Fresh.f() [env_pass] because: changed\n");
        assertThat(folder.output("report") + "\n").startsWith("gate_status: YELLOW\n")
                .contains("1 changed file this check cannot judge", "\nFACT changes_head: " + first + "\n");
    }

    @Test
    void aBaseThatIsTheUnchangedWorkspaceCommit_isRefusedByTheTool() {
        Result run = run("--input", "since=HEAD");

        assertThat(run.code()).as(run.describe()).isNotZero();
        assertThat(run.out() + run.err()).contains("nothing to check: the base 'HEAD' is the workspace commit itself");
    }
}
