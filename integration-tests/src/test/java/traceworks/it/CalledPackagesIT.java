package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The packages a run calls, in containers: a parent whose one block calls the workflow of another package, whose
 * shell step changes between two runs to another command with the same output. The package digest of both runs is
 * the same; the {@code run.package} line of the child run, the manifest, the end entry of the ledger, {@code diff}
 * and {@code show} name the called package with its digest, and {@code verify} checks the manifest's list.
 */
class CalledPackagesIT extends IntegrationTest {

    private static final String CHILD_ID = "it-called-child";

    private Path parent() throws IOException {
        Path dir = Files.createDirectories(sandbox.resolve("packages").resolve("parent"));
        Files.writeString(dir.resolve("workflow.json"), """
                {
                  "format": "traceworks-workflow",
                  "format_version": 1,
                  "id": "it-called-parent",
                  "name": "IT called parent",
                  "blocks": [
                    {"type": "sub-workflow", "name": "call", "config": {"workflow_id": "../child"}}
                  ]
                }
                """);
        return dir;
    }

    /** The called package, next to the parent, whose one shell step runs {@code command}. */
    private Path child(String command) throws IOException {
        Path dir = Files.createDirectories(sandbox.resolve("packages").resolve("child"));
        Files.writeString(dir.resolve("workflow.json"), """
                {
                  "format": "traceworks-workflow",
                  "format_version": 1,
                  "id": "%s",
                  "name": "IT called child",
                  "blocks": [
                    {"type": "shell", "name": "say", "config": {"command": "%s"}}
                  ]
                }
                """.formatted(CHILD_ID, command));
        return dir;
    }

    private RunFolder run(Path pkg) {
        Result run = traceworks.exec("run", pkg.toString(), "--workspace", traceworks.workspace().toString(),
                "--no-input");
        assertThat(run.code()).as(run.describe()).isZero();
        return traceworks.folder(run);
    }

    /** The one entry of the manifest's {@code called_packages}: its digest. */
    private static String calledDigest(RunFolder folder) {
        JsonNode called = folder.manifest().path("called_packages");
        assertThat(called).as(folder.manifest().toPrettyString()).hasSize(1);
        assertThat(called.get(0).fieldNames()).toIterable().containsExactly("workflow_id", "package_digest");
        assertThat(called.get(0).path("workflow_id").asText()).isEqualTo(CHILD_ID);
        return called.get(0).path("package_digest").asText();
    }

    @Test
    void aCalledWorkflowThatChangedBetweenTwoRuns_isInTheManifestAndTheLedger_andDiffExits11() throws IOException {
        Path parent = parent();
        Path child = child("echo same-output");
        RunFolder a = run(parent);
        child("printf 'same-output\\\\n'");
        RunFolder b = run(parent);

        // Both runs ran their step in a container, with the same output, from the same package digest
        for (RunFolder folder : List.of(a, b)) {
            assertThat(folder.manifest().path("execution").asText()).isEqualTo("container");
            assertThat(folder.manifest().path("images")).isNotEmpty();
            String childRun = folder.events("run.package").getFirst().path("run").asText();
            assertThat(folder.outputOf(childRun, "say")).isEqualTo("same-output");
        }
        assertThat(a.manifest().path("package_digest")).isEqualTo(b.manifest().path("package_digest"));

        // The record: the child run starts with the line that names its package
        List<JsonNode> lines = a.events("run.package");
        assertThat(lines).hasSize(1);
        JsonNode line = lines.getFirst();
        String childRun = line.path("run").asText();
        assertThat(line.path("parent").asText()).isEqualTo(a.id());
        assertThat(a.events().stream().filter(e -> childRun.equals(e.path("run").asText())).findFirst()
                .orElseThrow().path("type").asText()).as("the first line of the child run").isEqualTo("run.package");
        assertThat(line.path("workflow").path("id").asText()).isEqualTo(CHILD_ID);
        assertThat(line.path("workflow").path("name").asText()).isEqualTo("IT called child");
        assertThat(line.path("reference").asText()).isEqualTo("../child");
        assertThat(line.path("package").path("dir").asText()).isEqualTo(child.toString());
        assertThat(a.blob(line.path("definition"))).contains("echo same-output");
        assertThat(a.only("run.started").path("records")).extracting(JsonNode::asText).contains("called_packages");

        // The manifest and the end entry of the ledger: the digest of the called package, another in each run
        String before = calledDigest(a);
        String after = calledDigest(b);
        assertThat(before).matches("[0-9a-f]{64}").isEqualTo(line.path("package").path("digest").asText());
        assertThat(after).matches("[0-9a-f]{64}").isNotEqualTo(before);
        for (RunFolder folder : List.of(a, b)) {
            assertThat(folder.ledgerEntry(traceworks.home())).as("the end entry is the manifest")
                    .isEqualTo(Files.readAllBytes(folder.dir().resolve("manifest.json")));
        }

        Result diff = traceworks.exec("diff", a.id(), b.id());

        assertThat(diff.code()).as(diff.describe()).isEqualTo(11);
        assertThat(diff.out()).contains("| called package " + CHILD_ID + " A " + before + "\n", " B " + after + "\n")
                .containsPattern("(?m)^\\| call > say +COMPLETED +same output$")
                .contains("differs: run B did not reproduce run A", "| a called package differs: " + CHILD_ID)
                .doesNotContain("the package digest differs").doesNotContain("note:");

        Result show = traceworks.exec("show", a.id());
        assertThat(show.code()).as(show.describe()).isZero();
        assertThat(show.out()).contains("Calls     " + CHILD_ID + "  " + before + "  " + child + "\n");
        assertThat(a.html()).contains("<tr><th>calls</th><td>" + CHILD_ID + " " + before + "</td></tr>");

        // verify: the list is part of the manifest the record gives
        Path manifest = b.dir().resolve("manifest.json");
        byte[] original = Files.readAllBytes(manifest);
        Files.writeString(manifest, new String(original, StandardCharsets.UTF_8).replace(after, before));
        Result changed = traceworks.exec("verify", b.id());
        assertThat(changed.code()).as(changed.describe()).isEqualTo(8);
        Files.write(manifest, original);
        Result restored = traceworks.exec("verify", b.id());
        assertThat(restored.code()).as(restored.describe()).isZero();
    }

    /** A step, a question, and a last step whose command names a version and prints the same. */
    private Path parking(String version) throws IOException {
        Path dir = Files.createDirectories(sandbox.resolve("packages").resolve("parking"));
        Files.writeString(dir.resolve("workflow.json"), """
                {
                  "format": "traceworks-workflow",
                  "format_version": 1,
                  "id": "it-resumed-with",
                  "name": "IT resumed with",
                  "blocks": [
                    {"type": "shell", "name": "greet", "config": {"command": "echo hi"}},
                    {"type": "approval", "name": "publish", "config": {"question": "Go on?",
                      "options": ["Yes", "No"]}},
                    {"type": "shell", "name": "done", "config": {"command": "echo same-output # %s"}}
                  ]
                }
                """.formatted(version));
        return dir;
    }

    /** A run of {@code pkg} that parks at its question, with the answer recorded for its resume. */
    private RunFolder parkedAndAnswered(Path pkg) {
        Result run = traceworks.exec("run", pkg.toString(), "--workspace", traceworks.workspace().toString(),
                "--no-input");
        assertThat(run.code()).as(run.describe()).isEqualTo(5);
        Result approve = traceworks.exec("approve", run.runId(), "--answer", "Yes");
        assertThat(approve.code()).as(approve.describe()).isZero();
        return traceworks.folder(run);
    }

    /**
     * Reproduced 2026-11-21 with containers: two parked runs of one package; before the second resume the last
     * step's command was changed ({@code --allow-changed-package}). Both manifests named the package digest of the
     * start alone, and {@code diff} said "same", exit 0. The manifest and the end entry of the ledger name the
     * digest the run was resumed with, {@code verify} checks it, and {@code diff} exits 11.
     */
    @Test
    void aRunResumedWithAChangedPackage_isInTheManifestAndTheLedger_andDiffExits11() throws IOException {
        Path pkg = parking("v1");
        RunFolder a = parkedAndAnswered(pkg);
        RunFolder b = parkedAndAnswered(pkg);
        Result resumedA = traceworks.exec("resume", a.id(), "--no-input");
        assertThat(resumedA.code()).as(resumedA.describe()).isZero();
        parking("v2");
        Result refused = traceworks.exec("resume", b.id(), "--no-input");
        assertThat(refused.code()).as("a changed package is resumed only when asked for: " + refused.describe())
                .isEqualTo(6);
        Result resumedB = traceworks.exec("resume", b.id(), "--no-input", "--allow-changed-package");
        assertThat(resumedB.code()).as(resumedB.describe()).isZero();

        // Both runs went on in a container, with the same output, from the same package digest at their start
        for (RunFolder folder : List.of(a, b)) {
            assertThat(folder.manifest().path("execution").asText()).isEqualTo("container");
            assertThat(folder.outputOf(folder.id(), "done")).isEqualTo("same-output");
            assertThat(folder.only("run.started").path("records")).extracting(JsonNode::asText)
                    .contains("resumed_with");
            assertThat(folder.only("run.resumed").path("records")).extracting(JsonNode::asText)
                    .contains("resumed_with");
        }
        assertThat(a.manifest().path("package_digest")).isEqualTo(b.manifest().path("package_digest"));

        // The record, the manifest and the end entry of the ledger: the digest run B was resumed with
        JsonNode resumed = b.only("run.resumed");
        assertThat(resumed.path("package_changed").asBoolean()).isTrue();
        String digest = resumed.path("package_digest").asText();
        assertThat(digest).matches("[0-9a-f]{64}").isNotEqualTo(b.manifest().path("package_digest").asText());
        assertThat(b.manifest().path("resumed_with")).extracting(JsonNode::asText).containsExactly(digest);
        assertThat(a.only("run.resumed").path("package_changed").asBoolean()).isFalse();
        assertThat(a.manifest().has("resumed_with")).as("resumed with the package it started with").isFalse();
        for (RunFolder folder : List.of(a, b)) {
            assertThat(folder.ledgerEntry(traceworks.home())).as("the end entry is the manifest")
                    .isEqualTo(Files.readAllBytes(folder.dir().resolve("manifest.json")));
        }

        Result diff = traceworks.exec("diff", a.id(), b.id());

        assertThat(diff.code()).as(diff.describe()).isEqualTo(11);
        assertThat(diff.out()).contains("differs: run B did not reproduce run A", "| the package changed at a resume:"
                + " run A went on with the package it started with, run B with " + digest)
                .doesNotContain("the package digest differs");
        Result same = traceworks.exec("diff", a.id(), a.id());
        assertThat(same.code()).as(same.describe()).isZero();

        // verify: the list is part of the manifest the record gives
        Path manifest = b.dir().resolve("manifest.json");
        byte[] original = Files.readAllBytes(manifest);
        Files.writeString(manifest, new String(original, StandardCharsets.UTF_8).replaceFirst(
                "  \"resumed_with\" : \\[.*?\\],\\R", ""));
        Result changed = traceworks.exec("verify", b.id());
        assertThat(changed.code()).as(changed.describe()).isEqualTo(8);
        Files.write(manifest, original);
        Result restored = traceworks.exec("verify", b.id());
        assertThat(restored.code()).as(restored.describe()).isZero();
    }
}
