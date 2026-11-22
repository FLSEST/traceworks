package traceworks.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The example hook {@code examples/hooks/github-issues.sh} on the diff of a register (docs/registers.md, "Hooks"),
 * with a fake {@code gh} on the PATH that writes down its arguments and calls nothing. The hook runs on the host with
 * an environment of its own (only {@code PATH} and what a test sets); it needs {@code bash}, {@code cat} and
 * {@code jq}, as the script tests of this build do. No test reaches a network.
 */
class GithubIssuesHookTest {

    private static final Path HOOK = Path.of("../examples/hooks/github-issues.sh").toAbsolutePath().normalize();

    private static final String DIFF = """
            {"format": 1, "register": "architecture", "project": "shop", "workflow_id": "wf-1", "run_id": "run-7",
             "report_valid": true,
             "new": [{"id": "aaaa1111bbbb2222", "rule_key": "layer-rule", "subject": "core/Engine.java",
                      "severity": "HIGH", "title": "core reads a file", "description": "Engine opens a path itself.",
                      "evidence": "Engine.java:12 Files.readString", "tags": [], "status": "OPEN"}],
             "resolved": [{"id": "cccc", "rule_key": "size", "subject": "A.java", "severity": "LOW", "title": "t",
                           "reason": "not_seen"},
                          {"id": "dddd", "rule_key": "size", "subject": "B.java", "severity": "LOW", "title": "t",
                           "reason": "not_seen"}],
             "reopened": [{"id": "eeee3333", "rule_key": "cycle", "subject": "cli -> core", "severity": "MEDIUM",
                           "title": "a cycle", "evidence": "cli.X -> core.Y", "status": "OPEN",
                           "reason": "seen_again"}],
             "review_due": [], "still_open": [], "counts": {"new": 1, "resolved": 2, "reopened": 1}}
            """;

    @TempDir
    Path temp;

    private Path tools;
    private Path calls;

    private record Ran(int code, String stdout, String stderr) {
    }

    /** A folder with {@code cat} and {@code jq} of the machine, and nothing else: no {@code gh}. */
    @BeforeEach
    void tools() throws IOException {
        tools = Files.createDirectories(temp.resolve("tools"));
        for (String tool : List.of("cat", "jq")) {
            Files.createSymbolicLink(tools.resolve(tool), onPath(tool));
        }
        calls = Files.createDirectories(temp.resolve("calls"));
    }

    private static Path onPath(String tool) {
        return Arrays.stream(System.getenv("PATH").split(File.pathSeparator)).map(dir -> Path.of(dir, tool))
                .filter(Files::isExecutable).findFirst()
                .orElseThrow(() -> new AssertionError("the hook tests need " + tool + " on the PATH"));
    }

    /** A {@code gh} that writes its arguments into the next file of {@code calls} and ends with {@code code}. */
    private void fakeGh(int code) throws IOException {
        Path gh = tools.resolve("gh");
        Files.writeString(gh, """
                #!%s
                i=0; while [ -e "$GH_CALLS/$i" ]; do i=$((i + 1)); done
                printf '%%s\\0' "$@" > "$GH_CALLS/$i"
                echo "https://github.com/owner/repo/issues/$((i + 1))"
                exit %d
                """.formatted(onPath("bash"), code));
        assertThat(gh.toFile().setExecutable(true)).isTrue();
    }

    private Ran hook(String stdin, Map<String, String> env, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of(onPath("bash").toString(), HOOK.toString()));
        command.addAll(List.of(args));
        Path in = Files.writeString(temp.resolve("diff.json"), stdin);
        Path out = Files.createTempFile(temp, "out", ".txt");
        Path err = Files.createTempFile(temp, "err", ".txt");
        ProcessBuilder builder = new ProcessBuilder(command).directory(temp.toFile())
                .redirectInput(in.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile());
        builder.environment().clear();
        builder.environment().put("PATH", tools.toString());
        builder.environment().put("GH_CALLS", calls.toString());
        builder.environment().putAll(env);
        try {
            Process process = builder.start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("the hook did not end");
            }
            return new Ran(process.exitValue(), Files.readString(out), Files.readString(err));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** The arguments of each call of the fake {@code gh}, in the order of the calls. */
    private List<List<String>> ghCalls() throws IOException {
        try (Stream<Path> files = Files.list(calls)) {
            List<Path> sorted = files.sorted((a, b) -> Integer.compare(Integer.parseInt(a.getFileName().toString()),
                    Integer.parseInt(b.getFileName().toString()))).toList();
            List<List<String>> all = new ArrayList<>();
            for (Path file : sorted) {
                all.add(List.of(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).split("\0")));
            }
            return all;
        }
    }

    @Test
    void withoutGh_itSaysSo_createsNothing_andExitsWithTwo() throws IOException {
        Ran ran = hook(DIFF, Map.of(), "owner/repo");

        assertThat(ran.code()).isEqualTo(2);
        assertThat(ran.stderr()).contains("the GitHub CLI (gh) is not on the PATH").contains("no issue was created");
        assertThat(ghCalls()).isEmpty();
    }

    @Test
    void withoutARepository_itSaysWhatToPass_callsNoGh_andExitsWithTwo() throws IOException {
        fakeGh(0);

        Ran none = hook(DIFF, Map.of());
        Ran notARepository = hook(DIFF, Map.of(), "just-a-name");

        assertThat(none.code()).isEqualTo(2);
        assertThat(none.stderr()).contains("no repository: pass OWNER/REPO as the hook's argument, or set GH_REPO");
        assertThat(notARepository.code()).isEqualTo(2);
        assertThat(notARepository.stderr()).contains("\"just-a-name\" is no repository");
        assertThat(ghCalls()).isEmpty();
    }

    @Test
    void aNewAndAReopenedFinding_eachOpenAnIssue_andTheResolvedOnesAreCounted() throws IOException {
        fakeGh(0);

        Ran ran = hook(DIFF, Map.of(), "owner/repo");

        assertThat(ran.code()).as(ran.stderr()).isZero();
        assertThat(ran.stdout()).isEmpty();
        List<List<String>> calls = ghCalls();
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).subList(0, 6)).containsExactly("issue", "create", "--repo", "owner/repo", "--title",
                "[HIGH] layer-rule: core/Engine.java");
        assertThat(calls.get(0).get(6)).isEqualTo("--body");
        assertThat(calls.get(0).get(7)).isEqualTo("""
                Register `architecture` of `shop`, finding `aaaa1111bbbb2222`, run `run-7`.

                core reads a file

                Engine opens a path itself.

                Evidence: Engine.java:12 Files.readString

                Details: `traceworks findings architecture show aaaa1111bbbb2222 --project shop`""");
        assertThat(calls.get(1)).contains("[MEDIUM] cycle: cli -> core");
        assertThat(calls.get(1).get(7)).isEqualTo("Register `architecture` of `shop`, finding `eeee3333`, run `run-7`,"
                + " reopened: seen_again.\n\na cycle\n\nEvidence: cli.X -> core.Y\n\nDetails: `traceworks findings"
                + " architecture show eeee3333 --project shop`");
        assertThat(ran.stderr()).contains("opened an issue in owner/repo: [HIGH] layer-rule: core/Engine.java")
                .contains("register architecture of shop: 2 finding(s) resolved; close their issues by hand");
    }

    @Test
    void theRepository_mayComeFromGhRepo() throws IOException {
        fakeGh(0);

        Ran ran = hook(DIFF, Map.of("GH_REPO", "github.example.org/team/tools"));

        assertThat(ran.code()).as(ran.stderr()).isZero();
        assertThat(ghCalls()).hasSize(2).allSatisfy(call ->
                assertThat(call.subList(0, 4)).containsExactly("issue", "create", "--repo", "github.example.org/team/tools"));
    }

    @Test
    void aRejectedReport_opensNoIssue() throws IOException {
        fakeGh(0);

        Ran ran = hook(DIFF.replace("\"report_valid\": true", "\"report_valid\": false"), Map.of(), "owner/repo");

        assertThat(ran.code()).isZero();
        assertThat(ran.stderr()).contains("register architecture of shop: the report of run run-7 was rejected");
        assertThat(ghCalls()).isEmpty();
    }

    @Test
    void whenGhFails_theHookFails() throws IOException {
        fakeGh(4);

        Ran ran = hook(DIFF, Map.of(), "owner/repo");

        assertThat(ran.code()).isNotZero();
        assertThat(ghCalls()).hasSize(1);
    }
}
