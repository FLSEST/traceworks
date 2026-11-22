package traceworks.it;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One register from before registers had projects is assigned once. The home is one an earlier version left: the
 * register {@code arch} in the folder of its key (the fixture {@code registers/decided-before-agent} of the core's
 * tests, byte for byte what that version wrote) and its anchors in the ledger under the key alone. The register is
 * assigned to a project and fed by a run of its audit in a container; then a backup of the earlier folder is put
 * back at the key. A second assignment, to another project, is refused and writes nothing, the checks hold, and
 * once the copy is removed everything is as after the first assignment.
 */
class RegisterAssignedOnceIT extends IntegrationTest {

    /** The workflow the first line of the fixture's log names. */
    private static final String WORKFLOW = "wf-1";

    @Test
    void aRegisterFromBefore_isAssignedOnce_andTheEarlierFolderPutBackIsNotAssignedToASecondProject()
            throws Exception {
        Path key = traceworks.home().resolve("registers/arch");
        anEarlierHome(key);
        Path backup = sandbox.resolve("backup");
        copy(key, backup);

        Result assigned = traceworks.exec("findings", "arch", "assign", "--project", "shop");
        assertThat(assigned.code()).as(assigned.describe()).isZero();
        assertThat(assigned.out()).contains("assigned the register arch to the project shop: " + key.resolve("shop"));
        assertThat(entries(key)).containsExactly("shop");

        // The assigned register is the one a run of its audit reads and feeds, in a container
        Result run = traceworks.exec("run", audit().toString(), "--workspace", traceworks.workspace().toString(),
                "--no-input", "--project", "shop");
        assertThat(run.code()).as(run.describe()).isZero();
        assertThat(traceworks.folder(run).manifest().path("execution").asText()).isEqualTo("container");
        assertThat(run.out()).contains("register arch of shop: 0 new, 0 resolved, 0 reopened, 1 open now");
        byte[] ofShop = Files.readAllBytes(key.resolve("shop/events.jsonl"));
        String ledgerHead = git("rev-parse", "HEAD");

        // A backup of the earlier folder is put back at the key
        copy(backup, key);

        Result second = traceworks.exec("findings", "arch", "assign", "--project", "portal");
        assertThat(second.code()).as(second.describe()).isEqualTo(6);
        assertThat(second.err()).as(second.describe()).doesNotContain("internal error")
                .contains("The register 'arch' cannot be assigned to portal: the register from before registers had"
                        + " projects of this key was assigned to the project shop already (the log of the register"
                        + " arch of shop holds register.assigned). A register from before is assigned once, so what"
                        + " lies at the key is a copy of what was assigned: remove it");
        assertThat(second.out()).as("no confirmation").isEmpty();
        Result again = traceworks.exec("findings", "arch", "assign", "--project", "shop");
        assertThat(again.code()).as(again.describe()).isEqualTo(6);
        assertThat(again.err()).contains("was assigned to the project shop already");

        // Nothing was written: no folder of the second project, no line, no anchor, no lock of an assignment
        assertThat(entries(key)).containsExactly("blobs", "events.jsonl", "register.lock", "report.html", "shop",
                "state.json");
        assertThat(Files.readAllBytes(key.resolve("events.jsonl"))).as("the copy at the key is as it was put back")
                .isEqualTo(Files.readAllBytes(backup.resolve("events.jsonl")));
        assertThat(Files.readAllBytes(key.resolve("state.json"))).isEqualTo(
                Files.readAllBytes(backup.resolve("state.json")));
        assertThat(Files.readAllBytes(key.resolve("shop/events.jsonl"))).as("the register of shop is as it was")
                .isEqualTo(ofShop);
        assertThat(git("rev-parse", "HEAD")).as("nothing was anchored").isEqualTo(ledgerHead);
        assertThat(traceworks.home().resolve("ledger/anchors/arch/portal.jsonl")).doesNotExist();
        Result verify = traceworks.exec("verify");
        assertThat(verify.code()).as("every check holds, since nothing was copied into a register: "
                + verify.describe()).isZero();
        assertThat(verify.out()).contains("problems (0)", "verified");
        Result ofTheCopy = traceworks.exec("findings", "arch", "verify");
        assertThat(ofTheCopy.code()).as(ofTheCopy.describe()).isZero();

        // The way out the refusal names: the copy is removed, and the register of shop is used as before
        for (String entry : List.of("events.jsonl", "state.json", "report.html", "register.lock")) {
            Files.delete(key.resolve(entry));
        }
        Traceworks.deleteTree(key.resolve("blobs"));
        Result ofShopNow = traceworks.exec("findings", "arch", "verify", "--project", "shop");
        assertThat(ofShopNow.code()).as(ofShopNow.describe()).isZero();
        assertThat(ofShopNow.out()).contains("anchor    ok (seq ", "verified");
        Result home = traceworks.exec("verify");
        assertThat(home.code()).as(home.describe()).isZero();
        Result overview = traceworks.exec("findings");
        assertThat(overview.code()).as(overview.describe()).isZero();
        assertThat(overview.out()).isEqualTo("arch  shop  architecture  OPEN 1  ACCEPTED_RISK 1  DISMISSED 1\n");
    }

    /**
     * The home as a version before registers had projects left it: the register in the folder of its key, and a
     * ledger with one commit per write of the register, each adding a line under the key alone.
     */
    private void anEarlierHome(Path key) throws Exception {
        Path fixture = traceworks.examplePackage("audit-hello").getParent().getParent()
                .resolve("traceworks-core/src/test/resources/registers/decided-before-agent");
        assertThat(fixture.resolve("events.jsonl")).as("the fixture of the core's tests").isRegularFile();
        copy(fixture, key);
        Files.writeString(key.resolve("report.html"), "<html>as that version rendered it</html>\n");
        Files.write(key.resolve("register.lock"), new byte[0]);
        List<String> lines = Files.readAllLines(key.resolve("events.jsonl"));
        Path ledger = Files.createDirectories(traceworks.home().resolve("ledger/registers"));
        git("-c", "init.defaultBranch=main", "-c", "init.templateDir=", "init", "-q");
        String anchors = "";
        for (int seq : new int[] {5, lines.size() - 1}) {
            anchors += "{\"seq\":" + seq + ",\"head\":\"" + sha256(lines.get(seq)) + "\"}\n";
            Files.writeString(ledger.resolve("arch.jsonl"), anchors);
            git("add", "--", "registers/arch.jsonl");
            git("commit", "-q", "-m", "register arch seq " + seq + " sha256:" + sha256(anchors), "--",
                    "registers/arch.jsonl");
        }
    }

    /** The audit that fed the register: the workflow its first line names, with the key {@code arch}. */
    private Path audit() throws IOException {
        Path dir = Files.createDirectories(sandbox.resolve("packages").resolve("arch"));
        Files.writeString(dir.resolve("workflow.json"), """
                {
                  "format": "traceworks-workflow",
                  "format_version": 1,
                  "id": "%s",
                  "name": "IT arch audit",
                  "metadata": {"audit": {"enabled": true, "key": "arch", "category": "architecture",
                    "report_block": "report"}},
                  "blocks": [
                    {"type": "shell", "name": "report", "config": {"command":
                      "printf '%%s\\\\n' '# Report' '<!-- traceworks:audit-result:v1' '{\\"snapshotComplete\\":true,\\"findings\\":[{\\"ruleKey\\":\\"size\\",\\"subject\\":\\"C.java\\",\\"severity\\":\\"HIGH\\",\\"title\\":\\"C is long\\",\\"evidence\\":\\"E1\\"}]}' '-->'"}}
                  ]
                }
                """.formatted(WORKFLOW));
        return dir;
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private String git(String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(traceworks.home().resolve("ledger").toFile()).redirectErrorStream(true);
        builder.environment().putAll(Map.of("GIT_CONFIG_GLOBAL", "/dev/null", "GIT_CONFIG_NOSYSTEM", "1",
                "GIT_AUTHOR_NAME", "Traceworks", "GIT_AUTHOR_EMAIL", "traceworks@localhost.invalid",
                "GIT_COMMITTER_NAME", "Traceworks", "GIT_COMMITTER_EMAIL", "traceworks@localhost.invalid"));
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

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path source : walk.toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static List<String> entries(Path folder) throws IOException {
        try (Stream<Path> children = Files.list(folder)) {
            return children.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}
