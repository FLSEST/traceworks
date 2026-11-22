package traceworks.it;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A register the ledger knows and whose log is not there is gone, with {@code examples/audit-hello} run in
 * containers: the log of one project's register is removed while the ledger still anchors it. The next run of that
 * project is refused before it exists, every {@code findings} command on it is refused and writes nothing, the
 * overview and both checks say so, and the register of the other project goes on.
 */
class RegisterLogGoneIT extends IntegrationTest {

    @Test
    void aRegisterWhoseLogWasRemoved_refusesTheRun_andFindingsAndVerifySaySo() throws IOException {
        Path shop = workspace("shop", "a.txt");
        Path web = workspace("web", "b.txt");
        Result onShop = runOn(shop, "shop");
        assertThat(onShop.code()).as(onShop.describe()).isZero();
        assertThat(onShop.out()).contains("register hello of shop: 1 new, 0 resolved, 0 reopened, 1 open now");
        Result onWeb = runOn(web, "web");
        assertThat(onWeb.code()).as(onWeb.describe()).isZero();
        String id = traceworks.exec("findings", "hello", "list", "--project", "shop").out().substring(0, 8);
        Path register = traceworks.home().resolve("registers/hello/shop");
        byte[] state = Files.readAllBytes(register.resolve("state.json"));
        byte[] view = Files.readAllBytes(register.resolve("report.html"));
        byte[] anchors = Files.readAllBytes(traceworks.home().resolve("ledger/anchors/hello/shop.jsonl"));

        Files.delete(register.resolve("events.jsonl"));

        String gone = "he ledger holds anchors of the register hello of shop, and its log is gone";
        int folders = traceworks.folders().size();
        Result refused = runOn(shop, "shop");
        assertThat(refused.code()).as(refused.describe()).isEqualTo(6);
        assertThat(refused.err()).as(refused.describe()).contains(gone)
                .contains("Put the register's folder back, or feed another project key (--project KEY)")
                .contains("'traceworks verify' says what the ledger holds of it");
        assertThat(traceworks.folders()).as("refused before the run exists").hasSize(folders);

        for (String[] command : List.of(new String[] {"findings", "hello", "list", "--project", "shop"},
                new String[] {"findings", "hello", "show", id, "--project", "shop"},
                new String[] {"findings", "hello", "items", "--project", "shop"},
                new String[] {"findings", "hello", "dismiss", id, "--note", "no", "--project", "shop"},
                new String[] {"findings", "hello", "render", "--project", "shop"})) {
            Result command6 = traceworks.exec(command);
            assertThat(command6.code()).as(command6.describe()).isEqualTo(6);
            assertThat(command6.err()).as(command6.describe()).contains(gone).doesNotContain("internal error");
        }
        Result overview = traceworks.exec("findings");
        assertThat(overview.code()).as(overview.describe()).isEqualTo(6);
        assertThat(overview.err()).as(overview.describe()).doesNotContain("internal error");
        List<String> lines = overview.out().lines().filter(line -> line.startsWith("hello  ")).toList();
        assertThat(lines).as(overview.describe()).hasSize(2);
        assertThat(lines.get(0)).startsWith("hello  shop  refused: T" + gone);
        assertThat(lines.get(1)).as("the other register, as ever").isEqualTo("hello  web  example  OPEN 1");

        Result ofIt = traceworks.exec("findings", "hello", "verify", "--project", "shop");
        assertThat(ofIt.code()).as(ofIt.describe()).isEqualTo(8);
        assertThat(ofIt.out()).contains("the ledger anchors this line").contains("the log is empty");
        Result verify = traceworks.exec("verify");
        assertThat(verify.code()).as(verify.describe()).isEqualTo(8);
        assertThat(verify.out()).as(verify.describe()).contains("  anchors.5.hello.shop  ")
                .contains("the ledger anchors this line").doesNotContain("anchors.5.hello.web  ");

        assertThat(register.resolve("events.jsonl")).as("no log was begun in its place").doesNotExist();
        assertThat(Files.readAllBytes(register.resolve("state.json"))).as("state.json is as it was").isEqualTo(state);
        assertThat(Files.readAllBytes(register.resolve("report.html"))).isEqualTo(view);
        assertThat(Files.readAllBytes(traceworks.home().resolve("ledger/anchors/hello/shop.jsonl")))
                .as("and no anchor was added").isEqualTo(anchors);
        Result againOnWeb = runOn(web, "web");
        assertThat(againOnWeb.code()).as(againOnWeb.describe()).isZero();
        assertThat(againOnWeb.out()).contains("register hello of web: 0 new, 0 resolved, 0 reopened, 1 open now");
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
}
