package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A register across runs (docs/registers.md), with {@code examples/audit-hello}, an audit without any AI: the
 * findings of one run are compared with the register, the summary says what changed, {@code findings} reads and
 * verifies the register, a decision survives the next run, and the next run sees what the register knows. A
 * register is that of the workflow's key and the run's project: the same audit on two workspaces, each under its
 * project, feeds two registers.
 */
class RegistersIT extends IntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void anAuditFeedsItsRegister_acrossRuns_andADecisionReachesTheNextRun() throws IOException {
        file("a.txt", 12);
        file("b.txt", 3);

        Result first = traceworks.runExample("audit-hello");

        assertThat(first.code()).as(first.describe()).isZero();
        assertThat(first.out()).contains("register hello of it: 1 new, 0 resolved, 0 reopened, 1 open now");
        assertThat(traceworks.folder(first).output("known")).contains("(no known findings)");

        file("a.txt", 4);
        file("b.txt", 15);

        Result second = traceworks.runExample("audit-hello");

        assertThat(second.code()).as(second.describe()).isZero();
        assertThat(second.out()).contains("register hello of it: 1 new, 1 resolved, 0 reopened, 1 open now");
        assertThat(traceworks.folder(second).output("known"))
                .contains("OPEN | LOW | long-file | a.txt | a.txt has 12 lines, more than 10 | Evidence: long_file: a.txt (12 lines)");

        Result registers = traceworks.exec("findings");
        assertThat(registers.code()).as(registers.describe()).isZero();
        assertThat(registers.out()).contains("hello  it  example  OPEN 1  RESOLVED 1");
        Result list = traceworks.exec("findings", "hello", "list");
        assertThat(list.code()).as(list.describe()).isZero();
        assertThat(list.out()).contains("OPEN       LOW       long-file  b.txt  b.txt has 15 lines, more than 10")
                .doesNotContain("a.txt");
        Result all = traceworks.exec("findings", "hello", "list", "--all");
        assertThat(all.out()).contains("RESOLVED   LOW       long-file  a.txt  a.txt has 12 lines, more than 10");
        Result verify = traceworks.exec("findings", "hello", "verify");
        assertThat(verify.code()).as(verify.describe()).isZero();
        assertThat(traceworks.home().resolve("registers/hello/it/report.html")).isRegularFile();

        String id = list.out().lines().filter(line -> line.contains("b.txt")).findFirst().orElseThrow().substring(0, 8);
        Result accept = traceworks.exec("findings", "hello", "accept", id, "--note", "an example");
        assertThat(accept.code()).as(accept.describe()).isZero();
        assertThat(accept.out()).startsWith("accepted " + id + " as a risk in the register hello of it, review"
                + " by ");
        assertThat(traceworks.exec("findings", "hello", "list", "--status", "open").out()).doesNotContain("b.txt");
        assertThat(traceworks.exec("findings", "hello", "list").out())
                .contains("ACCEPTED_RISK  LOW       long-file  b.txt");

        Result third = traceworks.runExample("audit-hello");

        assertThat(third.code()).as(third.describe()).isZero();
        assertThat(third.out()).contains("register hello of it: 0 new, 0 resolved, 0 reopened, 0 open now");
        assertThat(traceworks.folder(third).output("known"))
                .contains("ACCEPTED_RISK | LOW | long-file | b.txt | b.txt has 15 lines, more than 10 | Evidence: "
                        + "long_file: b.txt (15 lines) | Disposition: an example");
        assertThat(traceworks.exec("findings", "hello", "verify").code()).isZero();

        // An agent that decides under the operator's user names itself, and the register says who decided
        Result reopen = traceworks.exec("findings", "hello", "reopen", id, "--note", "look again", "--agent",
                "it-agent");
        assertThat(reopen.code()).as(reopen.describe()).isZero();
        assertThat(reopen.out()).contains("reopened " + id + " in the register hello of it through the agent"
                + " it-agent");
        Result dismiss = traceworks.exec("findings", "hello", "dismiss", id, "--note", "not ours", "--agent",
                "it-agent");
        assertThat(dismiss.code()).as(dismiss.describe()).isZero();
        assertThat(traceworks.exec("findings", "hello", "show", id).out())
                .containsPattern("Decision  DISMISSED by \\S+ through the agent it-agent at ");
        assertThat(traceworks.exec("findings", "hello", "list").out())
                .contains("DISMISSED  LOW       long-file  b.txt").contains("(through the agent it-agent)");
        assertThat(traceworks.exec("findings", "hello", "verify").code()).isZero();
    }

    /**
     * One audit package, two workspaces, one home: each run names its project, and each project has a register of
     * its own. The run on the second workspace resolves nothing of the first, a decision made for one project does
     * not cover the same finding of the other, and each run is handed only what its project's register knows.
     */
    @Test
    void theSameAuditOnTwoWorkspaces_feedsARegisterPerProject_andNeitherSeesTheOther() throws IOException {
        Path shop = workspace("shop", "a.txt", "same.txt");
        Path web = workspace("web", "b.txt", "same.txt");

        Result onShop = runOn(shop, "shop");
        Result onWeb = runOn(web, "web");

        assertThat(onShop.code()).as(onShop.describe()).isZero();
        assertThat(onShop.out()).contains("register hello of shop: 2 new, 0 resolved, 0 reopened, 2 open now");
        assertThat(onWeb.code()).as(onWeb.describe()).isZero();
        assertThat(onWeb.out()).as("nothing of the shop is resolved by the run on the web")
                .contains("register hello of web: 2 new, 0 resolved, 0 reopened, 2 open now");
        assertThat(traceworks.folder(onWeb).output("known")).contains("(no known findings)");
        assertThat(traceworks.home().resolve("registers/hello/shop/events.jsonl")).isRegularFile();
        assertThat(traceworks.home().resolve("registers/hello/web/events.jsonl")).isRegularFile();
        assertThat(traceworks.home().resolve("ledger/anchors/hello/shop.jsonl")).isRegularFile();
        assertThat(traceworks.home().resolve("ledger/anchors/hello/web.jsonl")).isRegularFile();
        assertThat(traceworks.exec("findings").out()).contains("hello  shop  example  OPEN 2",
                "hello  web  example  OPEN 2");

        Result withoutProject = traceworks.exec("findings", "hello", "list");
        assertThat(withoutProject.code()).as(withoutProject.describe()).isEqualTo(2);
        assertThat(withoutProject.err()).contains("has 2 projects").contains("shop, web").contains("--project KEY");
        Result ofShop = traceworks.exec("findings", "hello", "list", "--project", "shop");
        assertThat(ofShop.out()).contains("a.txt", "same.txt").doesNotContain("b.txt");
        String same = ofShop.out().lines().filter(line -> line.contains("same.txt")).findFirst().orElseThrow()
                .substring(0, 8);
        Result dismiss = traceworks.exec("findings", "hello", "dismiss", same, "--note", "not ours", "--project",
                "shop");
        assertThat(dismiss.code()).as(dismiss.describe()).isZero();

        Result againOnWeb = runOn(web, "web");
        Result againOnShop = runOn(shop, "shop");

        assertThat(againOnWeb.out()).as("the decision was made for the shop")
                .contains("register hello of web: 0 new, 0 resolved, 0 reopened, 2 open now");
        assertThat(traceworks.folder(againOnWeb).output("known")).contains("OPEN | LOW | long-file | b.txt",
                "OPEN | LOW | long-file | same.txt", "DISMISSED 0").doesNotContain("a.txt", "DISMISSED | ");
        assertThat(againOnShop.out()).contains("register hello of shop: 0 new, 0 resolved, 0 reopened, 1 open now");
        assertThat(traceworks.folder(againOnShop).output("known")).contains("OPEN | LOW | long-file | a.txt",
                "DISMISSED | LOW | long-file | same.txt").doesNotContain("b.txt");
        assertThat(traceworks.exec("show", traceworks.folder(againOnWeb).id()).out()).contains("Project   web")
                .containsPattern("Register  hello of web at seq \\d+");
        for (String project : new String[] {"shop", "web"}) {
            Result verify = traceworks.exec("findings", "hello", "verify", "--project", project);
            assertThat(verify.code()).as(verify.describe()).isZero();
            assertThat(verify.out()).contains("anchor    ok").contains("verified");
        }
        Result home = traceworks.exec("verify");
        assertThat(home.code()).as(home.describe()).isZero();

        // The ledger is one for all runs of the home: it says of each run which project it worked on and who
        // started it, when it starts and when it ends, and it anchors each register by key and project
        Path ledger = traceworks.home().resolve("ledger");
        for (Result run : new Result[] {onShop, onWeb, againOnWeb, againOnShop}) {
            String project = run == onShop || run == againOnShop ? "shop" : "web";
            String id = traceworks.folder(run).id();
            JsonNode start = JSON.readTree(ledger.resolve("starts/" + id + ".json").toFile());
            JsonNode end = JSON.readTree(ledger.resolve("runs/" + id + ".json").toFile());
            assertThat(start.path("project").asText()).as("the start entry of " + id).isEqualTo(project);
            assertThat(start.path("actor").asText()).isEqualTo(Traceworks.ACTOR);
            assertThat(start.path("register").path("key").asText()).isEqualTo("hello");
            assertThat(start.path("register").path("project").asText()).isEqualTo(project);
            assertThat(end.path("project").asText()).as("the end entry of " + id).isEqualTo(project);
            assertThat(end.path("actor").asText()).isEqualTo(Traceworks.ACTOR);
            assertThat(Files.readAllBytes(ledger.resolve("runs/" + id + ".json")))
                    .as("the end entry is the manifest of the run").isEqualTo(Files.readAllBytes(
                            traceworks.folder(run).dir().resolve("manifest.json")));
        }
        assertThat(ledger.resolve("registers")).as("the folder of the anchors an earlier version wrote under a key"
                + " alone: this version writes none there").doesNotExist();
        try (Stream<Path> anchors = Files.walk(ledger.resolve("anchors"))) {
            assertThat(anchors.filter(Files::isRegularFile).map(file -> ledger.relativize(file).toString()))
                    .as("anchors per key and project, and none under the key alone")
                    .containsExactlyInAnyOrder("anchors/hello/shop.jsonl", "anchors/hello/web.jsonl");
        }
        for (String project : new String[] {"shop", "web"}) {
            List<String> log = Files.readAllLines(traceworks.home().resolve("registers/hello/" + project
                    + "/events.jsonl"));
            List<String> anchors = Files.readAllLines(ledger.resolve("anchors/hello/" + project + ".jsonl"));
            // each says what was written beside the head (RegisterAnchorIT reads what)
            assertThat(anchors).isNotEmpty().allSatisfy(line -> assertThat(line)
                    .matches("\\{\"seq\":\\d+,\"head\":\"[0-9a-f]{64}\",\"lines\":\\{.*\\}"));
            assertThat(anchors.getLast()).as("the last anchor of " + project + " is the head of its log")
                    .startsWith("{\"seq\":" + (log.size() - 1) + ",\"head\":\"" + sha256(log.getLast())
                            + "\",\"lines\":{");
        }
    }

    private static String sha256(String line) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(line.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An audit run without a project is refused before anything exists: no run, no ledger, no register. */
    @Test
    void anAuditRunWithoutAProject_isRefused_andLeavesNothing() throws IOException {
        file("a.txt", 12);

        Result refused = traceworks.exec("run", traceworks.examplePackage("audit-hello").toString(), "--workspace",
                traceworks.workspace().toString(), "--no-input");

        assertThat(refused.code()).as(refused.describe()).isEqualTo(6);
        assertThat(refused.err()).contains("the register hello").contains("--project KEY");
        assertThat(traceworks.folders()).isEmpty();
        assertThat(traceworks.home().resolve("ledger")).doesNotExist();
        assertThat(traceworks.home().resolve("registers")).doesNotExist();
    }

    /** A second workspace beside the sandbox's own, with one long file per name. */
    private Path workspace(String name, String... longFiles) throws IOException {
        Path dir = Files.createDirectories(sandbox.resolve(name));
        for (String file : longFiles) {
            Files.writeString(dir.resolve(file), "line\n".repeat(12), StandardCharsets.UTF_8);
        }
        return dir;
    }

    private Result runOn(Path workspace, String project) {
        return traceworks.exec("run", traceworks.examplePackage("audit-hello").toString(), "--workspace",
                workspace.toString(), "--no-input", "--project", project);
    }

    private void file(String name, int lines) {
        try {
            Files.writeString(traceworks.workspace().resolve(name), "line\n".repeat(lines), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
