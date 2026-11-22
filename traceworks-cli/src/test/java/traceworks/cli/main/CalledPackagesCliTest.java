package traceworks.cli.main;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import traceworks.cli.RuntimeSource;

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

/**
 * The packages a run calls, end to end with real adapters in a temporary home, on the host: a parent whose one
 * block calls the workflow of another package. The package digest of the run covers the parent only; the manifest,
 * {@code diff}, {@code show}, {@code run.html} and the live lines name the called package, and {@code verify}
 * checks the manifest's list.
 */
class CalledPackagesCliTest {

    private static final Pattern RUN_ID = Pattern.compile("(?m)^run ([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-"
            + "[0-9a-f]{12}) [A-Z_]+$");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;
    private Path home;
    private Path work;
    private Path parent;
    private Path child;

    private record Result(int code, String out, String err) {
    }

    @BeforeEach
    void packages() throws IOException {
        home = Files.createDirectories(temp.resolve("home"));
        work = Files.createDirectories(temp.resolve("work"));
        parent = Files.createDirectories(temp.resolve("parent"));
        child = Files.createDirectories(temp.resolve("child"));
        Files.writeString(parent.resolve("workflow.json"), """
                {"id": "parent", "name": "Parent", "blocks": [
                  {"type": "sub-workflow", "name": "call", "config": {"workflow_id": "../child"}}
                ]}""");
        child("echo same-output");
    }

    /** The called package, whose one shell step runs {@code command}. */
    private void child(String command) throws IOException {
        Files.writeString(child.resolve("workflow.json"), """
                {"id": "child", "name": "Child", "blocks": [
                  {"type": "shell", "name": "say", "config": {"command": "%s"}}
                ]}""".formatted(command));
    }

    private Result exec(String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--home");
        all.add(home.toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(all.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Map.of("TRACEWORKS_ACTOR", "tester"), work, null,
                RuntimeSource.NONE);
        return new Result(code, traceworks.cli.Unbroken.of(out.toString(StandardCharsets.UTF_8)),
                traceworks.cli.Unbroken.of(err.toString(StandardCharsets.UTF_8)));
    }

    /** Runs the parent on the host and returns what the command printed. */
    private Result run(String... more) {
        List<String> args = new ArrayList<>(List.of("run", parent.toString(), "--project", "shop",
                "--allow-host-execution", "--no-input"));
        args.addAll(List.of(more));
        Result result = exec(args.toArray(String[]::new));
        assertThat(result.code()).as(result.out() + result.err()).isZero();
        return result;
    }

    private static String id(Result run) {
        Matcher m = RUN_ID.matcher(run.out());
        assertThat(m.find()).as(run.out()).isTrue();
        return m.group(1);
    }

    private Path runFolder(String id) throws IOException {
        try (Stream<Path> days = Files.walk(home.resolve("runs"), 2)) {
            return days.filter(p -> p.getFileName().toString().equals(id)).findFirst().orElseThrow();
        }
    }

    private JsonNode manifest(String id) throws IOException {
        return JSON.readTree(Files.readString(runFolder(id).resolve("manifest.json")));
    }

    /** The digest the manifest of {@code id} names for the one package the run called. */
    private String calledDigest(String id) throws IOException {
        JsonNode called = manifest(id).path("called_packages");
        assertThat(called).as(manifest(id).toPrettyString()).hasSize(1);
        assertThat(called.get(0).path("workflow_id").asText()).isEqualTo("child");
        return called.get(0).path("package_digest").asText();
    }

    /**
     * The reproduced case: the called workflow runs another command with the same output. Both runs have the same
     * package digest, and {@code diff} said "same", exit 0.
     */
    @Test
    void aCalledWorkflowThatChanged_isAnotherDigestInTheManifest_andDiffExits11AndNamesIt() throws IOException {
        String a = id(run("--quiet"));
        child("printf 'same-output\\\\n'");
        String b = id(run("--quiet"));

        String before = calledDigest(a);
        String after = calledDigest(b);
        assertThat(before).matches("[0-9a-f]{64}").isNotEqualTo(after);
        assertThat(manifest(a).path("package_digest")).isEqualTo(manifest(b).path("package_digest"));

        Result diff = exec("diff", a, b);

        assertThat(diff.code()).as(diff.out() + diff.err()).isEqualTo(11);
        // The field is named after a workflow id, a text from outside, so its line is marked as one
        assertThat(diff.out()).contains("| called package child A " + before + "\n", " B " + after + "\n")
                .containsPattern("(?m)^\\| call > say +COMPLETED +same output$")
                .contains("differs: run B did not reproduce run A", "| a called package differs: child")
                .doesNotContain("the package digest differs").doesNotContain("note:");
        assertThat(diff.out()).containsPattern("(?m)^  same: workflow id, workflow name, package digest, ");

        child("echo same-output");
        Result same = exec("diff", a, id(run("--quiet")));
        assertThat(same.code()).as(same.out() + same.err()).isZero();
        assertThat(same.out()).containsPattern("(?m)^  same: .*package commit, called package child, inputs digest")
                .contains("same: run B reproduced run A (package digest, called packages, inputs,");
    }

    @Test
    void showAndRunHtml_nameTheCalledPackage_whereTheyNameThePackage() throws IOException {
        String id = id(run("--quiet"));
        String digest = calledDigest(id);

        Result show = exec("show", id);

        assertThat(show.code()).as(show.out() + show.err()).isZero();
        assertThat(show.out()).contains("Package   " + parent + "\n", "Calls     child  " + digest + "  " + child + "\n");
        assertThat(show.out().lines().filter(line -> line.startsWith("Calls ")).count()).isEqualTo(1);
        String html = Files.readString(runFolder(id).resolve("run.html"));
        assertThat(html).contains("<tr><th>calls</th><td>child " + digest + "</td></tr>",
                "<tr><th>calls</th><td>child, digest " + digest + ", package " + child + "</td></tr>");
    }

    /** A run that calls no other package: the views and the manifest are what they were. */
    @Test
    void aRunThatCallsNoOtherPackage_hasNoSuchLine() throws IOException {
        Files.writeString(parent.resolve("workflow.json"), """
                {"id": "parent", "name": "Parent", "blocks": [
                  {"type": "shell", "name": "say", "config": {"command": "echo alone"}}
                ]}""");
        String id = id(run("--quiet"));

        assertThat(exec("show", id).out()).doesNotContain("Calls ");
        assertThat(manifest(id).has("called_packages")).isFalse();
        assertThat(Files.readString(runFolder(id).resolve("run.html"))).doesNotContain("<th>calls</th>");
    }

    /** While the run goes on, the line of the child run says which workflow of which package it runs. */
    @Test
    void theLiveLines_nameThePackageOfTheChildRun() throws IOException {
        Result run = run();

        String digest = calledDigest(id(run));
        assertThat(run.out()).containsPattern("(?m)^\\d\\d:\\d\\d:\\d\\d   workflow Child \\(child\\) of the package "
                + Pattern.quote(child.toString()) + ", digest " + digest + "$");
    }

    /** The list is part of the manifest the record gives: one that was changed, or cut out, does not verify. */
    @Test
    void verify_failsForAManifestWhoseCalledPackagesWereChanged() throws IOException {
        String id = id(run("--quiet"));
        Path file = runFolder(id).resolve("manifest.json");
        String original = Files.readString(file);
        assertThat(exec("verify", id).code()).isZero();

        Files.writeString(file, original.replace(calledDigest(id), "0".repeat(64)));
        Result changed = exec("verify", id);
        assertThat(changed.code()).as(changed.out() + changed.err()).isEqualTo(8);
        assertThat(changed.out() + changed.err()).contains("manifest");

        String without = original.replaceFirst("(?s)  \"called_packages\" : \\[.*?\\],\\R", "");
        assertThat(without).doesNotContain("called_packages");
        Files.writeString(file, without);
        assertThat(exec("verify", id).code()).isEqualTo(8);

        Files.writeString(file, original);
        assertThat(exec("verify", id).code()).isZero();
    }

    /** Every file of the home with its bytes: what a refused command must leave as it is, the locks included. */
    private java.util.SortedMap<String, String> homeTree() throws IOException {
        java.util.SortedMap<String, String> tree = new java.util.TreeMap<>();
        try (java.util.stream.Stream<Path> files = Files.walk(home)) {
            for (Path file : files.toList()) {
                tree.put(home.relativize(file).toString(), Files.isRegularFile(file)
                        ? java.util.HexFormat.of().formatHex(Files.readAllBytes(file)) : "(folder)");
            }
        }
        return tree;
    }

    /**
     * A run that calls its own package as a child is not resumed with a changed package, with the flag either: the
     * child would be loaded from the package as it is now, beside the definition recorded at the start. Exit 6, as
     * for a changed package without the flag, and the home is left byte for byte, with no lock more than before.
     * With the package restored the run goes on.
     */
    @Test
    void aRunThatCallsItsOwnPackage_isNotResumedWithAChangedPackage_alsoWithTheFlag_andTheHomeIsLeftAsItWas()
            throws IOException {
        Path self = Files.createDirectories(temp.resolve("self"));
        String definition = """
                {"id": "self", "name": "Self", "blocks": [
                  {"type": "approval", "name": "go", "config": {"question": "Go on?", "options": ["Yes", "No"]}},
                  {"type": "sub-workflow", "name": "again", "config": {"workflow_id": ".", "run_if": "false"}},
                  {"type": "shell", "name": "say", "config": {"command": "echo %s"}}
                ]}""";
        Files.writeString(self.resolve("workflow.json"), definition.formatted("one"));
        Result parked = exec("run", self.toString(), "--allow-host-execution", "--no-input");
        assertThat(parked.code()).as(parked.out() + parked.err()).isEqualTo(5);
        Matcher run = RUN_ID.matcher(parked.out());
        assertThat(run.find()).as(parked.out()).isTrue();
        String id = run.group(1);
        assertThat(exec("approve", id, "--answer", "Yes").code()).isZero();
        Files.writeString(self.resolve("workflow.json"), definition.formatted("two"));
        java.util.SortedMap<String, String> before = homeTree();

        Result withTheFlag = exec("resume", id, "--no-input", "--allow-host-execution", "--allow-changed-package");
        Result without = exec("resume", id, "--no-input", "--allow-host-execution");

        for (Result refused : List.of(withTheFlag, without)) {
            assertThat(refused.code()).as(refused.out() + refused.err()).isEqualTo(6);
            assertThat(refused.err()).contains("cannot resume with its changed package: its block 'again' runs the"
                            + " run's own package as a child workflow (workflow_id '.')",
                    "the run would go on with two versions of one package",
                    "Restore the package as it was at the start", "or start a new run");
            assertThat(refused.out()).isEmpty();
        }
        assertThat(homeTree()).as("no line, no ledger entry and no lock was written").isEqualTo(before);

        Files.writeString(self.resolve("workflow.json"), definition.formatted("one"));
        Result resumed = exec("resume", id, "--no-input", "--allow-host-execution");
        assertThat(resumed.code()).as(resumed.out() + resumed.err()).isZero();
    }
}
