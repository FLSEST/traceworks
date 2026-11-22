package traceworks.it;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The projects of a home ({@code projects} in {@code traceworks.json}), with the real jar and container runs of
 * {@code examples/audit-hello}: the home declares two projects, each bound to a folder, and the folder a run works
 * in names its project. No run names one with {@code --project}, so {@link Traceworks#PROJECT} is not used here.
 */
class ProjectsSettingIT extends IntegrationTest {

    /**
     * One audit package, a run in each declared folder without {@code --project}: the one in the working directory
     * and the one a {@code --workspace} names (declared with {@code ~/}) feed a register each, a run with the other
     * project's key is refused before it exists, and {@code findings} in a declared folder takes its project.
     */
    @Test
    void theFolderOfARun_namesItsProject_andARunWithTheOtherProjectsKey_isRefused() throws IOException {
        Path shop = traceworks.workspace();   // the working directory of every command of the sandbox
        Path web = Files.createDirectories(traceworks.userHome().resolve("sites/web"));
        Files.writeString(shop.resolve("a.txt"), "line\n".repeat(12), StandardCharsets.UTF_8);
        Files.writeString(web.resolve("b.txt"), "line\n".repeat(12), StandardCharsets.UTF_8);
        traceworks.configure(Map.of("projects", Map.of("shop", shop.toString(), "web", "~/sites/web")));
        String audit = traceworks.examplePackage("audit-hello").toString();

        Result onShop = traceworks.exec("run", audit, "--no-input");
        Result onWeb = traceworks.exec("run", audit, "--workspace", web.toString(), "--no-input");

        assertThat(onShop.code()).as(onShop.describe()).isZero();
        assertThat(onShop.out()).contains("register hello of shop: 1 new, 0 resolved, 0 reopened, 1 open now");
        assertThat(onWeb.code()).as(onWeb.describe()).isZero();
        assertThat(onWeb.out()).as("nothing of the shop is resolved by the run on the web")
                .contains("register hello of web: 1 new, 0 resolved, 0 reopened, 1 open now");
        assertThat(traceworks.folder(onWeb).output("known")).contains("(no known findings)");
        assertThat(children(traceworks.home().resolve("registers/hello"))).containsExactly("shop", "web");
        assertThat(children(traceworks.home().resolve("ledger/anchors/hello")))
                .containsExactly("shop.jsonl", "web.jsonl");
        assertThat(traceworks.exec("show", traceworks.folder(onShop).id()).out()).contains("Project   shop")
                .containsPattern("Register  hello of shop");
        assertThat(traceworks.exec("show", traceworks.folder(onWeb).id()).out()).contains("Project   web");
        assertThat(Files.readAllLines(traceworks.folder(onShop).dir().resolve("events.jsonl")).getFirst())
                .as("the record names the project, and not where the key came from")
                .contains("\"project\":\"shop\"").doesNotContain("\"projects\"");

        // A run with the other project's key is refused before it exists, whichever way it is told its folder
        List<String> shopLog = Files.readAllLines(traceworks.home().resolve("registers/hello/shop/events.jsonl"));
        List<String> webLog = Files.readAllLines(traceworks.home().resolve("registers/hello/web/events.jsonl"));
        Result webKeyInShop = traceworks.exec("run", audit, "--no-input", "--project", "web");
        Result shopKeyInWeb = traceworks.exec("run", audit, "--workspace", web.toString(), "--no-input", "--project",
                "shop");

        assertThat(webKeyInShop.code()).as(webKeyInShop.describe()).isEqualTo(6);
        assertThat(webKeyInShop.err()).contains("The run is refused", "--project names 'web'",
                "is of the project 'shop'");
        assertThat(shopKeyInWeb.code()).as(shopKeyInWeb.describe()).isEqualTo(6);
        assertThat(shopKeyInWeb.err()).contains("The run is refused", "--project names 'shop'",
                "is of the project 'web'");
        assertThat(traceworks.folders()).as("no run exists but the two").hasSize(2);
        assertThat(Files.readAllLines(traceworks.home().resolve("registers/hello/shop/events.jsonl")))
                .isEqualTo(shopLog);
        assertThat(Files.readAllLines(traceworks.home().resolve("registers/hello/web/events.jsonl")))
                .isEqualTo(webLog);

        // The key of its own folder is no contradiction, and the next run knows what its project's register knows
        Result againOnWeb = traceworks.exec("run", audit, "--workspace", web.toString(), "--no-input", "--project",
                "web");
        assertThat(againOnWeb.code()).as(againOnWeb.describe()).isZero();
        assertThat(againOnWeb.out()).contains("register hello of web: 0 new, 0 resolved, 0 reopened, 1 open now");
        assertThat(traceworks.folder(againOnWeb).output("known")).contains("OPEN | LOW | long-file | b.txt")
                .doesNotContain("a.txt");

        // The key has two projects: findings in the working directory, the folder of shop, takes shop
        Result here = traceworks.exec("findings", "hello", "list");
        assertThat(here.code()).as(here.describe()).isZero();
        assertThat(here.out()).contains("a.txt").doesNotContain("b.txt");
        Result ofWeb = traceworks.exec("findings", "hello", "list", "--project", "web");
        assertThat(ofWeb.out()).contains("b.txt").doesNotContain("a.txt");
        for (String project : new String[] {"shop", "web"}) {
            Result verify = traceworks.exec("findings", "hello", "verify", "--project", project);
            assertThat(verify.code()).as(verify.describe()).isZero();
            assertThat(verify.out()).contains("anchor    ok").contains("verified");
        }
        Result home = traceworks.exec("verify");
        assertThat(home.code()).as(home.describe()).isZero();
    }

    private static List<String> children(Path folder) throws IOException {
        try (Stream<Path> children = Files.list(folder)) {
            return children.map(child -> child.getFileName().toString()).sorted().toList();
        }
    }
}
