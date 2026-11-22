package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The anchor of a register says what was written, with {@code examples/audit-hello} run in a container and a
 * decision after it: the two anchor lines in the ledger of the home say the run and the count of each type of its
 * lines, and who decided which finding, and nothing of what the register holds. {@code verify} holds, and it
 * fails in a copy of the home in which one field of an anchor says something else.
 */
class RegisterAnchorIT extends IntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theAnchorsOfARunAndADecision_sayWhatWasWritten_andVerifyFindsAFieldThatWasChanged() throws Exception {
        Path shop = Files.createDirectories(sandbox.resolve("shop"));
        Files.writeString(shop.resolve("a.txt"), "line\n".repeat(12), StandardCharsets.UTF_8);

        Result run = traceworks.exec("run", traceworks.examplePackage("audit-hello").toString(), "--workspace",
                shop.toString(), "--no-input", "--project", "shop");
        assertThat(run.code()).as(run.describe()).isZero();
        assertThat(run.out()).contains("register hello of shop: 1 new, 0 resolved, 0 reopened, 1 open now");
        String runId = traceworks.folder(run).id();
        Result list = traceworks.exec("findings", "hello", "list", "--json");
        assertThat(list.code()).as(list.describe()).isZero();
        String finding = JSON.readTree(list.out()).get(0).get("id").asText();
        assertThat(finding).hasSize(64);
        Result dismiss = traceworks.exec("findings", "hello", "dismiss", finding.substring(0, 12), "--note",
                "a.txt may be long");
        assertThat(dismiss.code()).as(dismiss.describe()).isZero();

        // The ledger alone says what happened to the register, and holds no content of it
        Path home = traceworks.home();
        Path anchors = home.resolve("ledger/anchors/hello/shop.jsonl");
        List<String> log = Files.readAllLines(home.resolve("registers/hello/shop/events.jsonl"));
        List<String> lines = Files.readAllLines(anchors);
        assertThat(lines).as("one anchor for the run's report, one for the decision").hasSize(2);
        JsonNode ofTheRun = JSON.readTree(lines.get(0));
        assertThat(ofTheRun.fieldNames()).toIterable().containsExactly("seq", "head", "lines", "run");
        assertThat(ofTheRun.path("run").asText()).as("the run that wrote").isEqualTo(runId);
        assertThat(ofTheRun.path("lines").toString()).as("its lines, by type and count").isEqualTo(
                "{\"finding.new\":1,\"register.created\":1,\"register.requires\":1,\"report.applied\":1}");
        int seqOfTheRun = ofTheRun.path("seq").asInt();
        assertThat(ofTheRun.path("head").asText()).isEqualTo(sha256(log.get(seqOfTheRun)));
        assertThat(lines.get(1)).as("the decision: who decided which finding, and no run").isEqualTo("{\"seq\":"
                + (log.size() - 1) + ",\"head\":\"" + sha256(log.getLast()) + "\",\"lines\":{\"finding.dismissed\":1},"
                + "\"actor\":\"" + Traceworks.ACTOR + "\",\"decided\":[\"" + finding + "\"]}");
        assertThat(log.subList(seqOfTheRun + 1, log.size())).as("the lines the second anchor covers").hasSize(1);
        assertThat(Files.readString(anchors)).doesNotContain("a.txt").doesNotContain("may be long")
                .doesNotContain("long-file");
        assertThat(git(home, "log", "--format=%s", "--", "anchors/hello/shop.jsonl").lines())
                .as("the commits say what they said").hasSize(2)
                .allMatch(subject -> subject.matches("anchor hello/shop seq \\d+ sha256:[0-9a-f]{64}"));

        Result ofTheRegister = traceworks.exec("findings", "hello", "verify");
        assertThat(ofTheRegister.code()).as(ofTheRegister.describe()).isZero();
        assertThat(ofTheRegister.out()).contains("anchor    ok (seq " + (log.size() - 1)).contains("verified");
        Result ofTheHome = traceworks.exec("verify");
        assertThat(ofTheHome.code()).as(ofTheHome.describe()).isZero();

        // A copy of the home in which the anchor of the decision names another actor, by a commit in the place of
        // the last one that is as Traceworks writes them: only the register's log says that the line is wrong
        Path copy = sandbox.resolve("home-copy");
        copy(home, copy);
        String changed = String.join("\n", lines.get(0), lines.get(1).replace("\"actor\":\"" + Traceworks.ACTOR
                + "\"", "\"actor\":\"somebody-else\"")) + "\n";
        Files.writeString(copy.resolve("ledger/anchors/hello/shop.jsonl"), changed, StandardCharsets.UTF_8);
        git(copy, "commit", "-q", "--amend", "-m", "anchor hello/shop seq " + (log.size() - 1) + " sha256:"
                + sha256(changed), "--", "anchors/hello/shop.jsonl");
        git(copy, "reflog", "expire", "--expire=now", "--all");
        git(copy, "gc", "-q", "--prune=now");

        String problem = "seq " + (log.size() - 1) + ": the anchor in the ledger says actor somebody-else, and the"
                + " lines it covers (seq " + (log.size() - 1) + ") give actor " + Traceworks.ACTOR;
        Result changedHome = traceworks.exec("--home", copy.toString(), "verify");
        assertThat(changedHome.code()).as(changedHome.describe()).isEqualTo(8);
        assertThat(changedHome.out()).as(changedHome.describe()).contains("problems (1)")
                .contains("  anchors.5.hello.shop  " + problem).contains("verification FAILED: 1 problem");
        Result changedRegister = traceworks.exec("--home", copy.toString(), "findings", "hello", "verify");
        assertThat(changedRegister.code()).as(changedRegister.describe()).isEqualTo(8);
        assertThat(changedRegister.out()).contains("anchor    NOT ok").contains(problem)
                .contains("register hello of shop does not verify");
        assertThat(traceworks.exec("verify").code()).as("the home itself is as it was").isZero();
    }

    private String git(Path home, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(home.resolve("ledger").toFile())
                .redirectErrorStream(true);
        builder.environment().putAll(Map.of("GIT_CONFIG_GLOBAL", "/dev/null", "GIT_CONFIG_NOSYSTEM", "1",
                "GIT_AUTHOR_NAME", "Tester", "GIT_AUTHOR_EMAIL", "tester@example.invalid",
                "GIT_COMMITTER_NAME", "Tester", "GIT_COMMITTER_EMAIL", "tester@example.invalid"));
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
                    Files.copy(source, target);
                }
            }
        }
    }

    private static String sha256(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
