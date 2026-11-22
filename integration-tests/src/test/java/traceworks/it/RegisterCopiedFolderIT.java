package traceworks.it;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A home in which the register folder of one project was copied to the place of another project's, with
 * {@code examples/audit-hello} run in containers: the log of the copy names the project it was copied from, so it
 * is the register of no other. The check of the whole home fails and names it, {@code findings} lists it as
 * refused and the register it was copied from as ever, and no command on it ends in an internal error.
 */
class RegisterCopiedFolderIT extends IntegrationTest {

    @Test
    void aCopiedProjectFolder_failsTheCheckOfTheHome_andIsListedAsRefusedBesideTheOtherOne() throws IOException {
        Path shop = workspace("shop", "a.txt");
        Path web = workspace("web", "b.txt");
        Result onShop = runOn(shop, "shop");
        assertThat(onShop.code()).as(onShop.describe()).isZero();
        assertThat(onShop.out()).contains("register hello of shop: 1 new, 0 resolved, 0 reopened, 1 open now");
        Result intact = traceworks.exec("verify");
        assertThat(intact.code()).as(intact.describe()).isZero();
        Path registers = traceworks.home().resolve("registers/hello");

        copy(registers.resolve("shop"), registers.resolve("web"));

        Result verify = traceworks.exec("verify");
        assertThat(verify.code()).as(verify.describe()).isEqualTo(8);
        assertThat(verify.out()).as(verify.describe())
                .contains("problems (1)")
                .contains("  anchors.5.hello.web  the log of the register hello of web names the project shop: a"
                        + " folder that was copied or renamed is not the register of web")
                .contains("verification FAILED: 1 problem")
                .doesNotContain("anchors.5.hello.shop  ");
        Result quick = traceworks.exec("verify", "--quick");
        assertThat(quick.code()).as("the quick check reads no register: " + quick.describe()).isZero();
        assertThat(quick.out()).contains("registers not verified (--quick)");

        Result overview = traceworks.exec("findings");
        assertThat(overview.code()).as("the list is finished, and a register in it was refused: "
                + overview.describe()).isEqualTo(6);
        assertThat(overview.err()).as(overview.describe()).doesNotContain("internal error");
        List<String> lines = overview.out().lines().filter(line -> line.startsWith("hello  ")).toList();
        assertThat(lines).as(overview.describe()).hasSize(2);
        assertThat(lines.get(0)).as("the register it was copied from, as ever")
                .isEqualTo("hello  shop  example  OPEN 1");
        assertThat(lines.get(1)).as("one line for the copy, with the reason and the command that says more")
                .startsWith("hello  web  refused: The register 'hello' of the project 'web' cannot be used: ")
                .contains("names the project shop")
                .contains("'traceworks findings hello verify --project web' says what is wrong");

        Result list = traceworks.exec("findings", "hello", "list", "--project", "web");
        assertThat(list.code()).as(list.describe()).isEqualTo(6);
        assertThat(list.err()).doesNotContain("internal error").contains("names the project shop");
        Result ofTheCopy = traceworks.exec("findings", "hello", "verify", "--project", "web");
        assertThat(ofTheCopy.code()).as(ofTheCopy.describe()).isEqualTo(8);
        assertThat(ofTheCopy.out()).contains("names the project shop")
                .contains("register hello of web does not verify");
        Result ofShop = traceworks.exec("findings", "hello", "list", "--project", "shop");
        assertThat(ofShop.code()).as(ofShop.describe()).isZero();
        assertThat(ofShop.out()).contains("OPEN").contains("a.txt");

        // No run of the project the folder was copied to is handed the findings of the other, or feeds the copy
        int folders = traceworks.folders().size();
        Result onWeb = runOn(web, "web");
        assertThat(onWeb.code()).as(onWeb.describe()).isEqualTo(6);
        assertThat(onWeb.err()).contains("the register hello of web cannot be read")
                .contains("'traceworks findings hello verify --project web' says what is wrong");
        assertThat(traceworks.folders()).as("refused before the run exists").hasSize(folders);
        Result againOnShop = runOn(shop, "shop");
        assertThat(againOnShop.code()).as(againOnShop.describe()).isZero();
        assertThat(againOnShop.out()).contains("register hello of shop: 0 new, 0 resolved, 0 reopened, 1 open now");
        assertThat(Files.readAllLines(registers.resolve("web/events.jsonl")))
                .as("the copy got no line: it is as it was copied")
                .isEqualTo(Files.readAllLines(registers.resolve("shop/events.jsonl")).subList(0,
                        Files.readAllLines(registers.resolve("web/events.jsonl")).size()));
        assertThat(traceworks.home().resolve("ledger/anchors/hello/web.jsonl")).as("and no anchor").doesNotExist();
    }

    /**
     * The same copy, made to say that it is the other project's: one more {@code register.created} that names
     * {@code web}, appended with the right {@code seq} and {@code prev}. A log says once whose it is, in the line it
     * begins with, so the copy is still no register of {@code web}: with its decisions it would otherwise be one.
     */
    @Test
    void aCopiedProjectFolderWithASecondRegisterCreated_isStillNoRegisterOfTheOtherProject() throws IOException {
        Path shop = workspace("shop", "a.txt");
        Path web = workspace("web", "b.txt");
        Result onShop = runOn(shop, "shop");
        assertThat(onShop.code()).as(onShop.describe()).isZero();
        String id = traceworks.exec("findings", "hello", "list", "--project", "shop").out().substring(0, 8);
        Result dismissed = traceworks.exec("findings", "hello", "dismiss", id, "--project", "shop", "--note",
                "not ours");
        assertThat(dismissed.code()).as(dismissed.describe()).isZero();
        Path registers = traceworks.home().resolve("registers/hello");
        copy(registers.resolve("shop"), registers.resolve("web"));
        Path log = registers.resolve("web/events.jsonl");
        List<String> copied = Files.readAllLines(log, StandardCharsets.UTF_8);
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode created =
                (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(copied.getFirst());
        assertThat(created.path("type").asText()).isEqualTo("register.created");
        created.put("seq", copied.size());
        created.put("prev", sha256(copied.getLast()));
        created.put("project", "web");
        Files.writeString(log, json.writeValueAsString(created) + "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        String again = "seq " + copied.size() + ": register.created stands after the line the log begins with (seq"
                + " 0, register.created): a log says once whose it is";

        Result verify = traceworks.exec("verify");
        assertThat(verify.code()).as(verify.describe()).isEqualTo(8);
        assertThat(verify.out()).as(verify.describe()).contains("  anchors.5.hello.web  " + again)
                .contains("names the project shop").doesNotContain("anchors.5.hello.shop  ");
        Result ofTheCopy = traceworks.exec("findings", "hello", "verify", "--project", "web");
        assertThat(ofTheCopy.code()).as(ofTheCopy.describe()).isEqualTo(8);
        assertThat(ofTheCopy.out()).contains(again).contains("register hello of web does not verify");

        Result overview = traceworks.exec("findings");
        assertThat(overview.code()).as(overview.describe()).isEqualTo(6);
        assertThat(overview.err()).as(overview.describe()).doesNotContain("internal error");
        assertThat(overview.out().lines().filter(line -> line.startsWith("hello  web  ")).toList())
                .as(overview.describe()).singleElement().asString()
                .startsWith("hello  web  refused: The register 'hello' of the project 'web' cannot be used: ")
                .contains(again);
        for (String[] command : List.of(new String[] {"findings", "hello", "list", "--project", "web"},
                new String[] {"findings", "hello", "show", id, "--project", "web"},
                new String[] {"findings", "hello", "reopen", id, "--project", "web"},
                new String[] {"findings", "hello", "render", "--project", "web"})) {
            Result refused = traceworks.exec(command);
            assertThat(refused.code()).as(refused.describe()).isEqualTo(6);
            assertThat(refused.err()).as(refused.describe()).contains(again).doesNotContain("internal error");
        }
        int folders = traceworks.folders().size();
        Result onWeb = runOn(web, "web");
        assertThat(onWeb.code()).as(onWeb.describe()).isEqualTo(6);
        assertThat(onWeb.err()).contains("the register hello of web cannot be read").contains(again);
        assertThat(traceworks.folders()).as("refused before the run exists").hasSize(folders);
        assertThat(Files.readAllLines(log, StandardCharsets.UTF_8)).as("the copy got no line")
                .hasSize(copied.size() + 1);
        assertThat(traceworks.home().resolve("ledger/anchors/hello/web.jsonl")).as("and no anchor").doesNotExist();
    }

    private static String sha256(String line) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(line.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path source : walk.toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target);
                }
            }
        }
    }
}
