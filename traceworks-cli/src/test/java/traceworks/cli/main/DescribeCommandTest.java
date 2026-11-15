package traceworks.cli.main;

import traceworks.cli.RuntimeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code traceworks describe}: what a package will do, as text and as one HTML page, without a run. */
class DescribeCommandTest {

    private static final Pattern RUN_ID = Pattern.compile("(?m)^run ([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-"
            + "[0-9a-f]{12}) [A-Z_]+$");

    @TempDir
    Path temp;

    private Path home;
    private Path work;

    private record Result(int code, String out, String err) {
    }

    @BeforeEach
    void directories() throws IOException {
        home = temp.resolve("home");
        work = Files.createDirectories(temp.resolve("work"));
    }

    private Path pkg(String name, String blocks) throws IOException {
        Path dir = Files.createDirectories(temp.resolve(name));
        Files.writeString(dir.resolve("workflow.json"), "{\"id\":\"" + name + "\",\"name\":\"" + name
                + " flow\",\"description\":\"Says hello.\",\"defaultContext\":{\"who\":\"world\"},\"blocks\":["
                + blocks + "]}");
        return dir;
    }

    private Path samplePackage() throws IOException {
        return pkg("sample", """
                {"type":"shell","name":"say","config":{"command":"echo hello {{ who }}"}},
                {"type":"approval","name":"publish","config":{"question":"Publish it?","options":["Yes","No"]}}""");
    }

    /** Without a container runtime: {@code describe} needs none. */
    private Result exec(String... args) {
        return execIn(work, args);
    }

    private Result execIn(Path directory, String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--home");
        all.add(home.toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(all.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Map.of("TRACEWORKS_ACTOR", "tester"), directory,
                null, RuntimeSource.NONE);
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static List<Path> filesBelow(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        }
    }

    @Test
    void describe_printsTheWorkflowItsInputsItsBlocksAndTheCounts() throws IOException {
        Path pkg = samplePackage();

        Result r = exec("describe", pkg.toString());

        assertThat(r.code()).as(r.out() + r.err()).isZero();
        assertThat(r.err()).isEmpty();
        assertThat(r.out().lines()).containsSubsequence(
                "Workflow  sample flow (sample)",
                "  Says hello.",
                "Package   " + pkg.toRealPath(),
                "Inputs",
                "  who = world",
                "Requires  nothing",
                "Blocks",
                "  0   say                      shell",
                "      echo hello {{ who }}",
                "      shell: network none, workspace read-only, image: the run's shell image",
                "  1   publish                  approval",
                "      Publish it?",
                "      person: a person is asked",
                "2 blocks: 0 AI steps, 0 shell steps with network, 1 step that asks a person");
        assertThat(r.out()).containsPattern("(?m)^Digest    [0-9a-f]{64}$").contains("traceworks check");
    }

    @Test
    void describe_takesTheWorkflowFileOfThePackageToo() throws IOException {
        Path pkg = samplePackage();

        Result r = exec("describe", pkg.resolve("workflow.json").toString());

        assertThat(r.code()).as(r.out() + r.err()).isZero();
        assertThat(r.out()).contains("Workflow  sample flow (sample)");
    }

    /** The digest printed here is the one a run of the unchanged package records. */
    @Test
    void theDigest_isThePackageDigestOfALaterRunOfTheUnchangedPackage() throws IOException {
        Path pkg = pkg("plain", """
                {"type":"shell","name":"say","config":{"command":"echo hello"}}""");
        Files.createDirectories(pkg.resolve("scripts"));
        Files.writeString(pkg.resolve("scripts/tool.sh"), "echo tool\n");

        Result described = exec("describe", pkg.toString());
        Matcher digest = Pattern.compile("(?m)^Digest    ([0-9a-f]{64})$").matcher(described.out());
        assertThat(digest.find()).as(described.out() + described.err()).isTrue();

        Result run = exec("run", pkg.toString(), "--allow-host-execution", "--no-input");
        assertThat(run.code()).as(run.out() + run.err()).isZero();
        Matcher id = RUN_ID.matcher(run.out());
        assertThat(id.find()).as(run.out()).isTrue();
        Path folder;
        try (Stream<Path> runs = Files.walk(home.resolve("runs"), 2)) {
            folder = runs.filter(p -> p.getFileName().toString().equals(id.group(1))).findFirst().orElseThrow();
        }
        assertThat(Files.readAllLines(folder.resolve("events.jsonl")).getFirst())
                .contains("\"type\":\"run.started\"", "\"digest\":\"" + digest.group(1) + "\"");
        assertThat(Files.readString(folder.resolve("manifest.json")))
                .containsPattern("\"package_digest\"\\s*:\\s*\"" + digest.group(1) + "\"");

        // and it follows the package: another file, another digest
        Files.writeString(pkg.resolve("scripts/tool.sh"), "echo changed\n");
        assertThat(exec("describe", pkg.toString()).out()).doesNotContain(digest.group(1));
    }

    @Test
    void describe_writesNothingAndStartsNothing() throws IOException {
        Path pkg = samplePackage();
        List<Path> before = filesBelow(temp);

        Result r = exec("describe", pkg.toString());

        assertThat(r.code()).isZero();
        assertThat(filesBelow(temp)).isEqualTo(before);
        assertThat(home).doesNotExist();
    }

    @Test
    void aSubWorkflowOfThePackage_isShownBelowItsBlock_andOneThatIsNotThereIsNamed() throws IOException {
        Path pkg = pkg("parent", """
                {"type":"sub-workflow","name":"inner","config":{"workflow_id":"./child"}},
                {"type":"sub-workflow","name":"gone","config":{"workflow_id":"./missing"}}""");
        Files.createDirectories(pkg.resolve("child"));
        Files.writeString(pkg.resolve("child/workflow.json"), """
                {"id":"child","name":"Child","blocks":[{"type":"shell","name":"work","config":{"command":"echo w"}}]}""");

        Result r = exec("describe", pkg.toString());

        assertThat(r.code()).as(r.out() + r.err()).isZero();
        assertThat(r.out().lines()).containsSubsequence(
                "  0   inner                    sub-workflow",
                "      workflow Child (child), of another package:",
                "        0   work                     shell",
                "            echo w",
                "  1   gone                     sub-workflow");
        assertThat(r.out()).containsPattern("(?m)^      workflow ./missing: not shown: .+$")
                .contains("3 blocks: ", "; 1 sub-workflow not shown, its blocks not counted");
    }

    @Test
    void aTerminalEscapeInThePackage_isNotPrinted() throws IOException {
        Path pkg = pkg("escape", """
                {"type":"shell","name":"a\\u001b[31mred","config":{"command":"echo \\u001b]0;title\\u0007"}}""");

        Result r = exec("describe", pkg.toString());

        assertThat(r.code()).isZero();
        assertThat(r.out()).doesNotContain("\u001b").doesNotContain("\u0007").contains("a?[31mred");
    }

    @Test
    void aPackageThatIsNotThere_isAUsageError_andOneThatCannotBeRead_isRefused_asCheckDoes() throws IOException {
        Result missing = exec("describe", temp.resolve("nowhere").toString());
        assertThat(missing.code()).isEqualTo(2);
        assertThat(missing.err()).contains("no such package");
        assertThat(exec("check", temp.resolve("nowhere").toString()).code()).isEqualTo(2);

        Path broken = Files.createDirectories(temp.resolve("broken"));
        Files.writeString(broken.resolve("workflow.json"), "{ not json");
        Result refused = exec("describe", broken.toString());
        Result check = exec("check", broken.toString());
        assertThat(refused.code()).isEqualTo(check.code()).isEqualTo(6);
        assertThat(refused.err()).isEqualTo(check.err());
        assertThat(refused.out()).isEmpty();
    }

    /** {@code describe} runs no analyzer: a package {@code check} has errors for is described all the same. */
    @Test
    void aPackageWithCheckErrors_isStillDescribed() throws IOException {
        Path pkg = pkg("faulty", """
                {"type":"shell","name":"net","config":{"command":"curl x","network":true}}""");
        assertThat(exec("check", pkg.toString()).code()).isEqualTo(6);

        Result r = exec("describe", pkg.toString());

        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("shell: network open");
    }

    @Test
    void html_writesOnePageIntoTheWorkingDirectory_andPrintsItsPath() throws IOException {
        Path pkg = samplePackage();

        Result r = exec("describe", pkg.toString(), "--html");

        assertThat(r.code()).as(r.out() + r.err()).isZero();
        Path page = work.resolve("sample.describe.html");
        assertThat(r.out()).isEqualTo(page + "\n");
        String html = Files.readString(page);
        assertThat(html).startsWith("<!DOCTYPE html>").contains("<title>Workflow sample flow</title>", "Says hello.",
                "echo hello {{ who }}", "a person is asked", "2 blocks: ");
        assertThat(html).doesNotContain("<script").doesNotContain("http://").doesNotContain("https://");
        assertThat(filesBelow(pkg)).containsExactly(pkg.resolve("workflow.json"));
        assertThat(home).doesNotExist();
    }

    /** The working directory may be a checkout nobody checked: a link under the page's name is not written through. */
    @Test
    void html_replacesALinkUnderThePagesName_andLeavesWhatItPointedAtAlone() throws IOException {
        Path pkg = samplePackage();
        Path victim = Files.writeString(work.resolve("victim.txt"), "keep me");
        Path page = Files.createSymbolicLink(work.resolve("sample.describe.html"), victim);

        Result r = exec("describe", pkg.toString(), "--html");

        assertThat(r.code()).as(r.out() + r.err()).isZero();
        assertThat(victim).hasContent("keep me");
        assertThat(Files.isSymbolicLink(page)).isFalse();
        assertThat(Files.readString(page)).startsWith("<!DOCTYPE html>");
        try (var files = Files.list(work)) {
            assertThat(files.map(f -> f.getFileName().toString())).noneMatch(name -> name.endsWith(".tmp"));
        }
    }

    @Test
    void html_takesTheFileToWrite_relativeToTheWorkingDirectory_andWritesItAgain() throws IOException {
        Path pkg = samplePackage();
        Files.writeString(work.resolve("out.html"), "old");

        Result r = exec("describe", pkg.toString(), "--html", "out.html");

        assertThat(r.code()).as(r.out() + r.err()).isZero();
        assertThat(r.out()).isEqualTo(work.resolve("out.html") + "\n");
        assertThat(Files.readString(work.resolve("out.html"))).startsWith("<!DOCTYPE html>");
        assertThat(work.resolve("sample.describe.html")).doesNotExist();
        // the flag may stand first
        assertThat(exec("describe", "--html", pkg.toString(), "second.html").code()).isZero();
        assertThat(work.resolve("second.html")).exists();
    }

    @Test
    void html_escapesWhatThePackageSays() throws IOException {
        Path pkg = pkg("evil", """
                {"type":"shell","name":"<script>alert(1)</script>","config":{"command":"echo <script>alert(2)</script>"}}""");

        assertThat(exec("describe", pkg.toString(), "--html").code()).isZero();

        assertThat(Files.readString(work.resolve("evil.describe.html"))).doesNotContain("<script")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;", "echo &lt;script&gt;alert(2)&lt;/script&gt;");
    }

    /** A file in the package changes its digest, so the page is never put there unasked. */
    @Test
    void html_neverWritesIntoThePackageUnasked() throws IOException {
        Path pkg = samplePackage();

        Result inside = execIn(pkg, "describe", ".", "--html");
        assertThat(inside.code()).isEqualTo(2);
        assertThat(inside.err()).contains("inside the package");
        Result below = execIn(Files.createDirectories(pkg.resolve("docs")), "describe", "..", "--html");
        assertThat(below.code()).isEqualTo(2);
        assertThat(filesBelow(pkg)).containsExactly(pkg.resolve("workflow.json"));

        Result told = execIn(pkg, "describe", ".", "--html", "page.html");
        assertThat(told.code()).as(told.err()).isZero();
        assertThat(pkg.resolve("page.html")).exists();
        assertThat(told.err()).contains("inside the package", "digest");
    }

    @Test
    void aFileWithoutHtml_andAFileThatCannotBeWritten_areUsageErrors() throws IOException {
        Path pkg = samplePackage();

        Result noFlag = exec("describe", pkg.toString(), "out.html");
        assertThat(noFlag.code()).isEqualTo(2);
        assertThat(noFlag.err()).contains("--html");
        assertThat(work.resolve("out.html")).doesNotExist();

        Result noDirectory = exec("describe", pkg.toString(), "--html", "no/such/dir/out.html");
        assertThat(noDirectory.code()).isEqualTo(2);
        assertThat(noDirectory.err()).contains("cannot write");
        Result directory = exec("describe", pkg.toString(), "--html", ".");
        assertThat(directory.code()).isEqualTo(2);
    }

    @Test
    void help_listsDescribe_andItsUsageNamesTheHtmlFlag() {
        assertThat(exec("--help").out()).containsPattern("(?m)^  describe ");
        Result usage = exec("describe", "--help");
        assertThat(usage.code()).isZero();
        assertThat(usage.out()).contains("usage: traceworks describe <package> [<file>]", "--html");
        assertThat(exec("describe").code()).isEqualTo(2);
    }
}
