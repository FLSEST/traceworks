package traceworks.cli.main;

import traceworks.adapters.config.HomeLayout;
import traceworks.adapters.images.BuiltInImages;
import traceworks.cli.RuntimeSource;
import traceworks.core.app.Images;
import traceworks.core.testing.FakeContainerRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The places of the runs, the ledger and the registers as {@code traceworks.json} sets them ({@code runs_dir},
 * {@code ledger_dir}, {@code registers_dir}), end to end: real adapters in a temporary home, host runs.
 */
class DataDirsTest {

    private static final Pattern RUN_ID = Pattern.compile("(?m)^run ([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-"
            + "[0-9a-f]{12}) [A-Z_]+$");

    @TempDir
    Path temp;
    private Path home;
    private Path work;
    /** Another disk, as far as a test can have one: a folder outside the home. */
    private Path disk;

    private record Result(int code, String out, String err) {
        String all() {
            return out + err;
        }
    }

    @BeforeEach
    void directories() throws IOException {
        home = Files.createDirectories(temp.resolve("home"));
        work = Files.createDirectories(temp.resolve("work"));
        disk = Files.createDirectories(temp.resolve("disk"));
    }

    private Result exec(String... args) {
        return execWith(RuntimeSource.NONE, args);
    }

    private Result execWith(RuntimeSource runtimes, String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--home");
        all.add(home.toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(all.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Map.of("TRACEWORKS_ACTOR", "tester",
                        "SSH_ASKPASS", "/bin/false", "SSH_ASKPASS_REQUIRE", "force"), work, null, runtimes);
        return new Result(code, traceworks.cli.Unbroken.of(out.toString(StandardCharsets.UTF_8)),
                traceworks.cli.Unbroken.of(err.toString(StandardCharsets.UTF_8)));
    }

    private Result runHost(Path pkg) {
        return exec("run", pkg.toString(), "--allow-host-execution", "--no-input");
    }

    private void config(String json) throws IOException {
        Files.writeString(home.resolve("traceworks.json"), json);
    }

    private Path pkg(String name, String metadata, String blocks) throws IOException {
        Path dir = Files.createDirectories(temp.resolve("packages").resolve(name));
        Files.writeString(dir.resolve("workflow.json"), "{\"id\":\"" + name + "\",\"name\":\"" + name + "\","
                + metadata + "\"blocks\":[" + blocks + "]}");
        return dir;
    }

    private Path plainPackage() throws IOException {
        return pkg("plain", "", "{\"type\":\"shell\",\"name\":\"say\",\"config\":{\"command\":\"echo hello\"}}");
    }

    private Path approvalPackage() throws IOException {
        return pkg("deploy", "", """
                {"type":"shell","name":"build","config":{"command":"echo built"}},
                {"type":"approval","name":"publish","config":{"question":"Publish it?","options":["Yes","No"]}},
                {"type":"shell","name":"done","config":{"command":"echo done"}}""");
    }

    /** An audit package whose report step prints an envelope with one finding. */
    private Path auditPackage() throws IOException {
        String command = "printf '%s\\n' '# Report' '<!-- traceworks:audit-result:v1' "
                + "'{\"snapshotComplete\":true,\"findings\":[{\"ruleKey\":\"size\",\"subject\":\"A.java\","
                + "\"severity\":\"MEDIUM\",\"title\":\"Big A.java\"}]}' '-->'";
        return pkg("audit", "\"metadata\":{\"audit\":{\"enabled\":true,\"key\":\"hello\",\"category\":\"example\","
                + "\"report_block\":\"report\"}},", "{\"type\":\"shell\",\"name\":\"report\",\"config\":{\"command\":"
                + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(command) + "}}");
    }

    private static String idOf(Result result) {
        Matcher m = RUN_ID.matcher(result.out());
        assertThat(m.find()).as(result.all()).isTrue();
        return m.group(1);
    }

    /** The folder of run {@code id} below {@code runs}: {@code <runs>/<day>/<id>}. */
    private static Path runFolder(Path runs, String id) throws IOException {
        try (Stream<Path> days = Files.walk(runs, 2)) {
            return days.filter(p -> p.getFileName().toString().equals(id)).findFirst().orElseThrow();
        }
    }

    private void placesOnTheDisk() throws IOException {
        config("{\"runs_dir\": \"" + disk.resolve("runs") + "\", \"ledger_dir\": \"audit/ledger\","
                + " \"registers_dir\": \"" + disk.resolve("registers") + "\"}");
    }

    @Test
    void withoutTheSettings_everythingLiesInTheFixedFoldersOfTheHome() throws IOException {
        Result run = runHost(auditPackage());

        assertThat(run.code()).as(run.all()).isZero();
        String id = idOf(run);
        assertThat(runFolder(home.resolve("runs"), id).resolve("events.jsonl")).isRegularFile();
        assertThat(home.resolve("ledger/runs/" + id + ".json")).isRegularFile();
        assertThat(home.resolve("ledger.lock")).isRegularFile();
        assertThat(home.resolve("registers/hello/state.json")).isRegularFile();
    }

    @Test
    void aRun_writesItsRecord_itsLedgerEntryAndItsRegister_whereTheSettingsSay() throws IOException {
        placesOnTheDisk();

        Result run = runHost(auditPackage());

        assertThat(run.code()).as(run.all()).isZero();
        String id = idOf(run);
        assertThat(runFolder(disk.resolve("runs"), id).resolve("events.jsonl")).isRegularFile();
        assertThat(home.resolve("audit/ledger/runs/" + id + ".json")).isRegularFile();
        assertThat(home.resolve("audit/ledger/.git")).isDirectory();
        assertThat(home.resolve("audit/ledger/ledger.lock")).as("the lock lies in a placed ledger").isRegularFile();
        assertThat(home.resolve("audit/ledger.lock")).doesNotExist();
        assertThat(disk.resolve("registers/hello/state.json")).isRegularFile();
        assertThat(disk.resolve("registers/hello/report.html")).isRegularFile();
        for (String fixed : List.of("runs", "ledger", "ledger.lock", "registers")) {
            assertThat(home.resolve(fixed)).as("nothing is written to the fixed " + fixed).doesNotExist();
        }
    }

    @Test
    void everyCommandThatReadsARun_findsItInThePlacesTheSettingsName() throws IOException {
        placesOnTheDisk();
        String id = idOf(runHost(auditPackage()));
        String other = idOf(runHost(auditPackage()));

        Result runs = exec("runs");
        Result show = exec("show", id);
        Result watch = exec("watch", id);
        Result verify = exec("verify", id);
        Result render = exec("render", id);
        Result diff = exec("diff", id, other);
        Result findings = exec("findings");
        Result list = exec("findings", "hello", "list");

        assertThat(runs.out()).as(runs.all()).contains(id.substring(0, 8), other.substring(0, 8));
        assertThat(show.code()).as(show.all()).isZero();
        assertThat(show.out()).contains(id);
        assertThat(watch.code()).as(watch.all()).isZero();
        assertThat(verify.code()).as(verify.all()).isZero();
        assertThat(verify.out()).containsPattern("(?m)^ledger +ok$");
        assertThat(render.code()).as(render.all()).isZero();
        assertThat(render.out()).contains(disk.resolve("runs").toString());
        assertThat(diff.code()).as(diff.all()).isZero();
        assertThat(findings.out()).as(findings.all()).contains("hello");
        assertThat(list.out()).as(list.all()).contains("OPEN", "A.java");
        String finding = list.out().substring(0, 8);
        Result detail = exec("findings", "hello", "show", finding);
        assertThat(detail.code()).as(detail.all()).isZero();
        assertThat(detail.out()).as("the run that reported it is found").contains("Last      run " + other);
        assertThat(exec("findings", "hello", "verify").code()).isZero();
        assertThat(exec("findings", "hello", "render").out())
                .contains(disk.resolve("registers/hello") + "/report.html");
    }

    @Test
    void aParkedRun_isAnsweredResumedAndCancelled_whereTheSettingsSay() throws IOException {
        placesOnTheDisk();
        Result parked = runHost(approvalPackage());
        assertThat(parked.code()).as(parked.all()).isEqualTo(5);
        String id = idOf(parked);
        String cancelled = idOf(runHost(approvalPackage()));

        assertThat(exec("runs", "--status", "parked").out()).contains(id.substring(0, 8));
        assertThat(exec("approve", id, "--answer", "Yes").code()).isZero();
        Result resumed = exec("resume", id, "--no-input", "--allow-host-execution");
        Result cancel = exec("cancel", cancelled);

        assertThat(resumed.code()).as(resumed.all()).isZero();
        assertThat(resumed.out()).contains("output: done");
        assertThat(cancel.code()).as(cancel.all()).isZero();
        assertThat(exec("verify", id).code()).isZero();
        assertThat(exec("verify", cancelled).code()).isZero();
        assertThat(home.resolve("runs")).doesNotExist();
    }

    /** The hook starts in the register's folder and gets the run's id: both hold where the settings put them. */
    @Test
    void theHookOfARegister_runsInTheRegistersFolderTheSettingNames() throws IOException {
        Path seen = temp.resolve("hook-saw");
        Path hook = Files.writeString(temp.resolve("hook.sh"), "#!/bin/sh\npwd > '" + seen + "'\ncat >> '" + seen
                + "'\n");
        Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwx------"));
        config("{\"runs_dir\": \"" + disk.resolve("runs") + "\", \"registers_dir\": \"" + disk.resolve("registers")
                + "\", \"hooks\": {\"register_changed\": [\"" + hook + "\"]}}");

        Result run = runHost(auditPackage());

        assertThat(run.code()).as(run.all()).isZero();
        assertThat(Files.readString(seen)).startsWith(disk.resolve("registers/hello").toRealPath() + "\n")
                .contains(idOf(run));
    }

    @Test
    void aSettingOfTheWrongType_refusesEveryCommandThatReadsTheHome_withExit6_andNamesTheKey() throws IOException {
        for (String key : List.of("runs_dir", "ledger_dir", "registers_dir")) {
            config("{\"" + key + "\": 5}");

            Result run = runHost(plainPackage());
            Result runs = exec("runs");
            Result findings = exec("findings");

            assertThat(run.code()).as(key).isEqualTo(6);
            assertThat(run.err()).contains("'" + key + "' in " + home.resolve("traceworks.json"));
            assertThat(runs.code()).as(key).isEqualTo(6);
            assertThat(findings.code()).as(key).isEqualTo(6);
        }
        assertThat(home.resolve("runs")).doesNotExist();
    }

    @Test
    void twoSettingsForOneFolder_refuseTheRun() throws IOException {
        config("{\"runs_dir\": \"" + disk + "\", \"ledger_dir\": \"" + disk + "\"}");

        Result run = runHost(plainPackage());

        assertThat(run.code()).isEqualTo(6);
        assertThat(run.err()).contains("'runs_dir'", "'ledger_dir'");
        assertThat(disk).isEmptyDirectory();
    }

    @Test
    void aRunsFolderThatCannotBeWritten_refusesTheRun_andNamesTheFolder() throws IOException {
        Path locked = Files.createDirectories(disk.resolve("locked"));
        config("{\"runs_dir\": \"" + locked.resolve("runs") + "\"}");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-x------"));
        try {
            Result run = runHost(plainPackage());

            assertThat(run.code()).as(run.all()).isEqualTo(6);
            assertThat(run.err()).contains("so the run is not started", locked.resolve("runs").toString())
                    .doesNotContain("internal error");
            assertThat(run.out()).isEmpty();
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void aLedgerFolderThatIsAFile_refusesTheRun_andCreatesNone() throws IOException {
        Path file = Files.writeString(disk.resolve("ledger"), "a file");
        config("{\"runs_dir\": \"" + disk.resolve("runs") + "\", \"ledger_dir\": \"" + file + "\"}");

        Result run = runHost(plainPackage());

        assertThat(run.code()).as(run.all()).isEqualTo(6);
        assertThat(run.err()).contains("'ledger_dir'", "not a directory");
        assertThat(disk.resolve("runs")).doesNotExist();
    }

    /** The ledger is asked before a run starts; a register is written after it, and the summary says when it was not. */
    @Test
    void aLedgerOrARegistersFolderThatCannotBeWritten_refusesTheRun_orIsNamedInItsSummary() throws IOException {
        Path locked = Files.createDirectories(disk.resolve("locked"));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-x------"));
        try {
            config("{\"ledger_dir\": \"" + locked.resolve("ledger") + "\"}");
            Result noLedger = runHost(plainPackage());
            assertThat(home.resolve("runs")).as("no run without a ledger").doesNotExist();
            config("{\"registers_dir\": \"" + locked.resolve("registers") + "\"}");
            Result noRegister = runHost(auditPackage());

            assertThat(noLedger.code()).as(noLedger.all()).isEqualTo(6);
            assertThat(noLedger.err()).contains("The ledger cannot take an entry", locked.resolve("ledger").toString());
            assertThat(noRegister.code()).as(noRegister.all()).isZero();
            assertThat(noRegister.out()).contains("register hello: not applied",
                    locked.resolve("registers").toString());
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    /**
     * Traceworks moves nothing when a setting changes. A run that stays in the old folder is no run of the home any
     * more: its id is unknown, its folder is read as a copied one. Moved to the new folder it is the run it was.
     */
    @Test
    void afterTheRunsSettingChanged_anEarlierRunIsFoundAgain_onceItsFolderIsMoved() throws IOException {
        String done = idOf(runHost(plainPackage()));
        Result parked = runHost(approvalPackage());
        String waiting = idOf(parked);
        Path before = runFolder(home.resolve("runs"), done);
        config("{\"runs_dir\": \"" + disk.resolve("runs") + "\"}");

        Result byId = exec("show", done);
        Result byPath = exec("show", before.toString());
        Result verifyByPath = exec("verify", before.toString());

        assertThat(byId.code()).as(byId.all()).isEqualTo(2);
        assertThat(exec("runs").out()).isEmpty();
        assertThat(byPath.code()).as(byPath.all()).isZero();
        assertThat(verifyByPath.out()).as("no claim about a ledger entry").containsPattern("(?m)^ledger +not checked");

        Files.move(home.resolve("runs"), disk.resolve("runs"));

        assertThat(exec("show", done).code()).isZero();
        Result verify = exec("verify", done);
        assertThat(verify.code()).as(verify.all()).isZero();
        assertThat(verify.out()).as("the ledger did not move, and names runs by id").containsPattern("(?m)^ledger +ok$");
        assertThat(exec("approve", waiting, "--answer", "Yes").code()).isZero();
        Result resumed = exec("resume", waiting, "--no-input", "--allow-host-execution");
        assertThat(resumed.code()).as(resumed.all()).isZero();
        assertThat(exec("verify", waiting).code()).isZero();
    }

    /** A ledger that was left behind is not the home's ledger: verify says so instead of trusting the record alone. */
    @Test
    void afterTheLedgerSettingChanged_verifyFails_untilTheLedgerIsMoved() throws IOException {
        String id = idOf(runHost(plainPackage()));
        config("{\"ledger_dir\": \"" + disk.resolve("ledger") + "\"}");

        Result without = exec("verify", id);

        assertThat(without.code()).as(without.all()).isEqualTo(8);
        assertThat(without.out()).containsPattern("(?m)^ledger +missing").doesNotContainPattern("(?m)^ledger +ok");
        assertThat(disk.resolve("ledger")).as("a command that reads creates no ledger").doesNotExist();

        Files.move(home.resolve("ledger"), disk.resolve("ledger"));

        Result with = exec("verify", id);
        assertThat(with.code()).as(with.all()).isZero();
        String next = idOf(runHost(plainPackage()));
        assertThat(disk.resolve("ledger/runs/" + next + ".json")).isRegularFile();
        assertThat(home.resolve("ledger")).doesNotExist();
    }

    /** A register names the run that reported a finding by its id, so it survives a move of either folder. */
    @Test
    void afterBothFoldersMoved_aFindingStillLeadsToItsRun() throws IOException {
        String id = idOf(runHost(auditPackage()));
        Files.move(home.resolve("runs"), disk.resolve("runs"));
        Files.move(home.resolve("registers"), disk.resolve("registers"));
        config("{\"runs_dir\": \"" + disk.resolve("runs") + "\", \"registers_dir\": \"" + disk.resolve("registers")
                + "\"}");

        Result list = exec("findings", "hello", "list");
        Result detail = exec("findings", "hello", "show", list.out().substring(0, 8));

        assertThat(detail.code()).as(detail.all()).isZero();
        assertThat(detail.out()).contains("Last      run " + id).doesNotContain("is not in this home");
        assertThat(exec("findings", "hello", "verify").code()).isZero();
    }

    /** The workspace is mounted into every container of a run: it may not be, lie in or hold a place of the records. */
    @Test
    void aWorkspaceInOrAroundAConfiguredPlace_isRefusedBeforeTheRunStarts() throws IOException {
        placesOnTheDisk();
        Path runs = Files.createDirectories(disk.resolve("runs"));
        Path registers = Files.createDirectories(disk.resolve("registers/hello"));
        String shell = Images.builtInTag(new BuiltInImages().all().stream()
                .filter(image -> image.name().equals("shell")).findFirst().orElseThrow());

        for (Path workspace : List.of(runs, registers, disk)) {
            FakeContainerRuntime runtime = new FakeContainerRuntime().present(shell, "sha256:" + "5".repeat(64));

            Result run = execWith(RuntimeSource.of(runtime), "run", plainPackage().toString(), "--no-input",
                    "--workspace", workspace.toString());

            assertThat(run.code()).as(workspace + run.all()).isEqualTo(6);
            assertThat(run.err()).contains("the workspace " + workspace + " cannot be mounted",
                    "what runs leave behind");
            assertThat(runtime.runs()).isEmpty();
        }
        assertThat(runs).isEmptyDirectory();
    }

    /**
     * Steps write their scratch directories in the runs folder, and wrote them in the fixed one before the setting
     * named another: no package, no local source and no child workflow is read from either.
     */
    @Test
    void aPackage_aLocalSource_andAChild_inTheConfiguredOrTheFixedRunsFolder_areRefused() throws IOException {
        placesOnTheDisk();
        for (Path runs : List.of(disk.resolve("runs"), home.resolve("runs"))) {
            Path planted = Files.createDirectories(runs.resolve("2026-11-14/some-run/scratch/pkg"));
            Files.copy(plainPackage().resolve("workflow.json"), planted.resolve("workflow.json"));
            Path parent = pkg("parent", "", "{\"type\":\"sub-workflow\",\"name\":\"child\",\"config\":"
                    + "{\"workflow_id\":\"" + planted + "\"}}");

            Result pkg = runHost(planted);
            Result path = exec("run", plainPackage().toString(), "--allow-host-execution", "--no-input", "--source",
                    planted.toString());
            Result url = exec("run", plainPackage().toString(), "--allow-host-execution", "--no-input", "--source",
                    "file://" + planted);
            Result child = runHost(parent);

            for (Result r : List.of(pkg, path, url)) {
                assertThat(r.code()).as(runs + r.all()).isEqualTo(6);
                assertThat(r.err()).as(runs + r.all()).contains("lies in " + runs + ", where the runs' folders");
                assertThat(r.out()).as("no run was started").isEmpty();
            }
            // The child is loaded when its step runs: the step fails, and nothing of the child ran
            assertThat(child.code()).as(runs + child.all()).isEqualTo(3);
            assertThat(child.out()).contains("child FAILED", "cannot be loaded").doesNotContain("output: hello");
        }
        assertThat(home.resolve("workspaces")).as("nothing was cloned").doesNotExist();
    }

    /** A placed ledger needs no more than its own folder: its parent may be a mount point nobody can write. */
    @Test
    void aLedgerWhoseParentCannotBeWritten_takesItsEntries() throws IOException {
        Path mount = Files.createDirectories(disk.resolve("mnt"));
        Path ledger = Files.createDirectories(mount.resolve("audit"));
        config("{\"ledger_dir\": \"" + ledger + "\"}");
        Files.setPosixFilePermissions(mount, PosixFilePermissions.fromString("r-x------"));
        try {
            Result run = runHost(plainPackage());

            assertThat(run.code()).as(run.all()).isZero();
            String id = idOf(run);
            assertThat(ledger.resolve("runs/" + id + ".json")).isRegularFile();
            assertThat(ledger.resolve("ledger.lock")).isRegularFile();
            Result verify = exec("verify", id);
            assertThat(verify.out()).as(verify.all()).containsPattern("(?m)^ledger +ok$");
            assertThat(new ProcessBuilder("git", "-C", ledger.toString(), "ls-files").start().inputReader().lines()
                    .toList()).as("the lock is no part of the ledger").containsExactly("runs/" + id + ".json", "starts/" + id + ".json");
        } finally {
            Files.setPosixFilePermissions(mount, PosixFilePermissions.fromString("rwx------"));
        }
    }

    /** The container runtime guards the mounts of the steps: it gets the places of the home, not the fixed folders. */
    @Test
    void theContainerRuntime_isGivenThePlacesTheSettingsName() throws IOException {
        placesOnTheDisk();
        String shell = Images.builtInTag(new BuiltInImages().all().stream()
                .filter(image -> image.name().equals("shell")).findFirst().orElseThrow());
        FakeContainerRuntime runtime = new FakeContainerRuntime().present(shell, "sha256:" + "5".repeat(64));
        List<HomeLayout> given = new ArrayList<>();
        RuntimeSource source = (configured, layout, limit) -> {
            given.add(layout);
            return java.util.Optional.of(runtime);
        };

        Result run = execWith(source, "run", plainPackage().toString(), "--no-input");
        Result build = execWith(source, "images", "build");

        assertThat(run.code()).as(run.all()).isZero();
        assertThat(build.code()).as(build.all()).isZero();
        assertThat(given).hasSize(2).allSatisfy(layout -> {
            assertThat(layout.home()).isEqualTo(home);
            assertThat(layout.runs()).isEqualTo(disk.resolve("runs"));
            assertThat(layout.ledger()).isEqualTo(home.resolve("audit/ledger"));
            assertThat(layout.registers()).isEqualTo(disk.resolve("registers"));
        });
    }

    /**
     * A parked run left in the old runs folder is a copied folder to this home, and is treated as any run folder
     * given by its path: it can be answered and resumed, its entry goes to the ledger the home has now, and verify
     * of the folder does not ask that ledger.
     */
    @Test
    void aParkedRunLeftInTheOldRunsFolder_isResumedByItsPath_asACopiedFolderIs() throws IOException {
        String id = idOf(runHost(approvalPackage()));
        Path left = runFolder(home.resolve("runs"), id);
        config("{\"runs_dir\": \"" + disk.resolve("runs") + "\", \"ledger_dir\": \"" + disk.resolve("ledger")
                + "\"}");

        assertThat(exec("resume", id, "--no-input", "--allow-host-execution").code()).as("not by its id").isEqualTo(2);
        assertThat(exec("approve", left.toString(), "--answer", "Yes").code()).isZero();
        Result resumed = exec("resume", left.toString(), "--no-input", "--allow-host-execution");
        Result verify = exec("verify", left.toString());

        assertThat(resumed.code()).as(resumed.all()).isZero();
        assertThat(resumed.out()).contains("COMPLETED", "  record  " + left);
        assertThat(disk.resolve("ledger/runs/" + id + ".json")).isRegularFile();
        assertThat(verify.code()).as(verify.all()).isZero();
        assertThat(verify.out()).containsPattern("(?m)^ledger +not checked");
        assertThat(disk.resolve("runs")).doesNotExist();
    }

    /**
     * Seals with the places set: the seal log and the allowed signers stay in the home whatever the settings say. A
     * sealed package runs and its record names the seal; changed, it is refused before a run exists, and the
     * refusal is in the seal log of the home.
     */
    @Test
    void aSealAdmission_worksWithThePlacesSet_andTheSealLogStaysInTheHome() throws Exception {
        Path key = Files.createDirectories(temp.resolve("keys")).resolve("seal");
        Process keygen = new ProcessBuilder("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "test@traceworks",
                "-f", key.toString()).redirectErrorStream(true).start();
        assertThat(keygen.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(keygen.exitValue()).isZero();
        config("{\"runs_dir\": \"" + disk.resolve("runs") + "\", \"ledger_dir\": \"" + disk.resolve("ledger")
                + "\", \"registers_dir\": \"" + disk.resolve("registers") + "\", \"signing_key\": \"" + key
                + "\"}");
        Files.writeString(home.resolve("allowed_signers"),
                "release@example.org " + Files.readString(Path.of(key + ".pub")));
        Path pkg = plainPackage();

        Result sealed = exec("seal", pkg.toString());
        Result run = runHost(pkg);

        assertThat(sealed.code()).as(sealed.all()).isZero();
        assertThat(home.resolve("seals/events.jsonl")).isRegularFile();
        assertThat(run.code()).as(run.all()).isZero();
        String id = idOf(run);
        assertThat(runFolder(disk.resolve("runs"), id)).isDirectory();
        Result verify = exec("verify", id);
        assertThat(verify.code()).as(verify.all()).isZero();
        assertThat(verify.out()).containsPattern("(?m)^seal +ok \\(sealed by tester").containsPattern("(?m)^ledger +ok$");
        assertThat(exec("seals", "verify", pkg.toString()).code()).isZero();

        Files.writeString(pkg.resolve("extra.sh"), "echo changed\n");
        long logged = Files.readAllLines(home.resolve("seals/events.jsonl")).size();
        Result refused = runHost(pkg);
        Result build = exec("images", "build", pkg.toString());

        assertThat(refused.code()).as(refused.all()).isEqualTo(6);
        assertThat(refused.err()).contains("changed since it was sealed");
        assertThat(build.code()).as(build.all()).isEqualTo(6);
        assertThat(exec("seals", "verify", pkg.toString()).code()).isEqualTo(8);
        assertThat(exec("check", pkg.toString()).out()).contains("sealed-package-changed");
        assertThat(exec("seals", "log").out()).contains("REFUSED");
        assertThat(Files.readAllLines(home.resolve("seals/events.jsonl")).size()).isGreaterThan((int) logged);
        try (Stream<Path> days = Files.list(disk.resolve("runs"))) {
            assertThat(days.flatMap(day -> day.toFile().list() == null ? Stream.<String>empty()
                    : Stream.of(day.toFile().list()))).as("no run of the refused package").containsExactly(id);
        }
        for (Path place : List.of(disk.resolve("runs"), disk.resolve("ledger"), disk.resolve("registers"), disk)) {
            assertThat(place.resolve("seals")).as("no seal log beside a place").doesNotExist();
        }
        assertThat(home.resolve("runs")).doesNotExist();
    }

    /** Steps write their scratch directories in the runs folder: no package is loaded from there. */
    @Test
    void aPackageInTheConfiguredRunsFolder_isRefused() throws IOException {
        placesOnTheDisk();
        Path planted = Files.createDirectories(disk.resolve("runs/2026-11-14/some-run/scratch/pkg"));
        Files.copy(plainPackage().resolve("workflow.json"), planted.resolve("workflow.json"));

        Result run = runHost(planted);

        assertThat(run.code()).as(run.all()).isEqualTo(6);
        assertThat(run.err()).contains("lies in " + disk.resolve("runs"));
    }

    /**
     * The anchor of a register goes where the ledger is: with the places set, a run and a decision anchor the
     * register's head in the configured ledger, under the lock in that folder, and findings verify reads it there.
     */
    @Test
    void theAnchorOfARegister_isWrittenToTheConfiguredLedger_andVerifyReadsItThere() throws IOException {
        placesOnTheDisk();

        Result run = runHost(auditPackage());

        assertThat(run.code()).as(run.all()).isZero();
        assertThat(run.out()).doesNotContain("not anchored");
        Path anchors = home.resolve("audit/ledger/registers/hello.jsonl");
        assertThat(anchors).isRegularFile();
        assertThat(Files.readAllLines(anchors)).hasSize(1).first().asString().startsWith("{\"seq\":3,\"head\":\"");
        assertThat(home.resolve("audit/ledger/ledger.lock")).isRegularFile();
        assertThat(home.resolve("ledger")).doesNotExist();
        Result verify = exec("findings", "hello", "verify");
        assertThat(verify.code()).as(verify.all()).isZero();
        assertThat(verify.out()).contains("anchor    ok (seq 3 is the head the ledger holds");

        String id = exec("findings", "hello", "list").out().lines().findFirst().orElseThrow().substring(0, 8);
        Result dismissed = exec("findings", "hello", "dismiss", id, "--note", "not ours");
        assertThat(dismissed.code()).as(dismissed.all()).isZero();
        assertThat(dismissed.err()).isEmpty();
        assertThat(Files.readAllLines(anchors)).hasSize(2);

        // A changed last line of the register in its configured folder is found against the configured ledger
        Path log = disk.resolve("registers/hello/events.jsonl");
        Files.writeString(log, Files.readString(log).replace("\"note\":\"not ours\"", "\"note\":\"not mine\""));
        Result tampered = exec("findings", "hello", "verify");
        assertThat(tampered.code()).isEqualTo(8);
        assertThat(tampered.out()).contains("the line is not the one the ledger anchors");
    }

    /**
     * A ledger left behind after the setting changed holds the anchors; the configured one holds none. findings
     * verify then says that the ledger holds no anchor of the register, and names another ledger as a reason. It
     * does not fail: a register from before anchors existed reads the same. With the ledger moved, it compares again.
     */
    @Test
    void afterTheLedgerSettingChanged_findingsVerifySaysThatTheLedgerHoldsNoAnchor_untilTheLedgerIsMoved()
            throws IOException {
        assertThat(runHost(auditPackage()).code()).isZero();
        assertThat(exec("findings", "hello", "verify").out()).contains("anchor    ok");
        config("{\"ledger_dir\": \"" + disk.resolve("ledger") + "\"}");

        Result without = exec("findings", "hello", "verify");

        assertThat(without.code()).as(without.all()).isZero();
        assertThat(without.out()).contains("anchor    none: the ledger holds no anchor of this register")
                .contains("or with another ledger");
        assertThat(disk.resolve("ledger")).as("a command that reads creates no ledger").doesNotExist();

        Files.move(home.resolve("ledger"), disk.resolve("ledger"));

        assertThat(exec("findings", "hello", "verify").out()).contains("anchor    ok (seq 3");
    }
}
