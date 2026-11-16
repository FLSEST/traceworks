package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a container may do by default (package {@code isolation}, docs/security.md): no network, a read-only workspace
 * and a read-only package, a writable scratch directory; {@code network: true} and {@code workspace_writable: true}
 * open one of them for their own step only. The steps that must fail carry {@code on_failure: continue}, so one run
 * shows all of it.
 */
class IsolationIT extends IntegrationTest {

    @Test
    void aStepHasNoNetwork_unlessItAsksForIt() {
        Result run = traceworks.run("isolation");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.stepStatus("reach_out")).isEqualTo("FAILED");
        assertThat(folder.output("reach_out")).contains("Network is unreachable");
        assertThat(folder.output("interfaces")).isEqualTo("lo");
        assertThat(command(folder, "interfaces").path("network").asText()).isEqualTo("none");
        // With network: true the step gets the runtime's default network, whose interface it sees next to lo
        List<String> interfaces = folder.output("interfaces_with_network").lines().toList();
        assertThat(interfaces).contains("lo").hasSizeGreaterThan(1);
        assertThat(command(folder, "interfaces_with_network").path("network").asText()).isEqualTo("open");
        // A failed step with on_failure: continue lets the run go on, and the run still completes
        assertThat(folder.only("run.finished").path("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void theWorkspaceIsReadOnly_unlessTheBlockMakesItWritable() {
        Result run = traceworks.run("isolation");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.stepStatus("write_read_only")).isEqualTo("FAILED");
        assertThat(folder.output("write_read_only")).contains("Read-only file system");
        assertThat(traceworks.workspace().resolve("read-only-probe")).doesNotExist();
        assertThat(mount(folder, "write_read_only", "/workspace").path("read_only").asBoolean()).isTrue();

        assertThat(folder.stepStatus("write_writable")).isEqualTo("COMPLETED");
        assertThat(traceworks.workspace().resolve("writable-probe")).exists();
        assertThat(mount(folder, "write_writable", "/workspace").path("read_only").asBoolean()).isFalse();
    }

    @Test
    void thePackageIsReadOnly_andTheScratchDirectoryIsWritable() {
        Result run = traceworks.run("isolation");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.output("write_package")).contains("Read-only file system");
        assertThat(traceworks.packageDir("isolation").resolve("package-probe")).doesNotExist();
        JsonNode packageMount = mount(folder, "write_package", "/package");
        assertThat(packageMount.path("read_only").asBoolean()).isTrue();
        assertThat(packageMount.path("host").asText()).isEqualTo(traceworks.packageDir("isolation").toString());

        assertThat(folder.output("write_scratch")).isEqualTo("hand-over");
        assertThat(mount(folder, "write_scratch", "/scratch").path("read_only").asBoolean()).isFalse();
        assertThat(folder.dir().resolve("scratch").resolve("note.txt")).hasContent("hand-over");
        JsonNode files = folder.ofStep("step.files", folder.stepIndex("write_scratch")).getFirst();
        assertThat(files.path("files")).extracting(f -> f.path("path").asText()).containsExactly("note.txt");
        assertThat(folder.blob(files.path("files").get(0).path("blob"))).isEqualTo("hand-over\n");
    }

    @Test
    void everyCommandRunsInTheShellImage_inTheWorkspace() throws Exception {
        Result run = traceworks.run("isolation");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        List<JsonNode> commands = folder.ownEvents("step.command");
        assertThat(commands).hasSize(7).allSatisfy(command -> {
            assertThat(command.path("image").asText()).isEqualTo("shell");
            assertThat(command.path("image_id").asText()).startsWith("sha256:");
            assertThat(command.path("host").asBoolean()).isFalse();
            assertThat(command.path("working_dir").asText()).isEqualTo("/workspace");
            assertThat(command.path("mounts")).extracting(m -> m.path("container").asText())
                    .startsWith("/workspace", "/package", "/scratch");
        });
        assertThat(commands.getFirst().path("mounts").get(0).path("host").asText())
                .isEqualTo(traceworks.workspace().toRealPath().toString());
        try (Stream<Path> written = Files.list(traceworks.workspace())) {
            assertThat(written.map(p -> p.getFileName().toString()))
                    .as("only the writable step wrote into the workspace").containsExactly("writable-probe");
        }
    }

    private static JsonNode command(RunFolder folder, String step) {
        return folder.ofStep("step.command", folder.stepIndex(step)).getFirst();
    }

    private static JsonNode mount(RunFolder folder, String step, String container) {
        for (JsonNode mount : command(folder, step).path("mounts")) {
            if (container.equals(mount.path("container").asText())) {
                return mount;
            }
        }
        throw new AssertionError("No mount at " + container + " for step " + step);
    }


    /**
     * The package digest leaves the package's top-level .git out (git rewrites it without any file of the package
     * changing), so a container does not see it: the directory is there and empty. Otherwise a hashed script could
     * run a payload from it that the digest of the run does not cover.
     */
    @Test
    void theGitDirectoryOfThePackage_isEmptyInAContainer() throws Exception {
        Path pkg = Files.createDirectories(traceworks.workspace().resolve("git-package"));
        Files.writeString(pkg.resolve("workflow.json"), "{\"format\": \"traceworks-workflow\", \"format_version\": 1,"
                + " \"id\": \"9a1b2c3d-0000-4000-8000-0000000000a1\", \"name\": \"IT git package\", \"blocks\": ["
                + "{\"type\": \"shell\", \"name\": \"look\", \"config\": {\"command\":"
                + " \"ls -A {{ package_dir }}/.git | wc -l; cat {{ package_dir }}/.git/payload || echo hidden\"}}]}");
        Files.createDirectories(pkg.resolve(".git"));
        Files.writeString(pkg.resolve(".git").resolve("payload"), "not covered by the digest");

        Result run = traceworks.exec("run", pkg.toString(), "--workspace", traceworks.workspace().toString(),
                "--no-input");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        assertThat(folder.output("look")).startsWith("0\nhidden").contains("No such file or directory")
                .doesNotContain("not covered");
        assertThat(pkg.resolve(".git").resolve("payload")).hasContent("not covered by the digest");
    }
}
