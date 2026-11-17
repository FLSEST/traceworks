package traceworks.cli.main;

import traceworks.cli.RuntimeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The start entry of the ledger on the command line: {@code verify <run>} compares it, {@code show} and
 * {@code run.html} name it, a run whose start cannot be noted runs no step, and {@code verify} without a run says
 * which runs the ledger saw start and never end, also after their folder was removed. Real host runs, no container.
 */
class LedgerStartCommandTest {

    private static final Pattern RUN_ID = Pattern.compile("(?m)^run ([0-9a-f-]{36}) [A-Z_]+$");

    @TempDir
    Path temp;
    private Path home;
    private Path work;

    private record Result(int code, String out, String err) {
    }

    @BeforeEach
    void directories() throws IOException {
        home = Files.createDirectories(temp.resolve("home"));
        work = Files.createDirectories(temp.resolve("work"));
    }

    private Result exec(String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--home");
        all.add(home.toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(all.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Map.of("TRACEWORKS_ACTOR", "tester"), work,
                null, RuntimeSource.NONE);
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Path pkg(String name, String blocks) throws IOException {
        Path dir = Files.createDirectories(temp.resolve(name));
        Files.writeString(dir.resolve("workflow.json"),
                "{\"id\":\"" + name + "\",\"name\":\"" + name + " flow\",\"blocks\":[" + blocks + "]}");
        return dir;
    }

    private Path steps() throws IOException {
        return pkg("steps", "{\"type\":\"shell\",\"name\":\"say\",\"config\":{\"command\":\"echo ran >> '"
                + temp.resolve("ran.txt") + "'\"}}");
    }

    private Path asking() throws IOException {
        return pkg("asking", """
                {"type":"approval","name":"go","config":{"question":"Go on?","options":["Yes","No"]}},
                {"type":"shell","name":"say","config":{"command":"true"}}""");
    }

    private static String idOf(Result result) {
        Matcher m = RUN_ID.matcher(result.out());
        assertThat(m.find()).as(result.out() + result.err()).isTrue();
        return m.group(1);
    }

    private Path folderOf(String id) throws IOException {
        try (Stream<Path> found = Files.walk(home.resolve("runs"), 2)) {
            return found.filter(path -> path.getFileName().toString().equals(id)).findFirst().orElseThrow();
        }
    }

    private static void delete(Path folder) throws IOException {
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
    }

    @Test
    void aRun_hasAStartEntry_thatVerifyComparesAndShowAndRunHtmlName() throws Exception {
        Result run = exec("run", steps().toString(), "--allow-host-execution", "--no-input");
        assertThat(run.code()).as(run.out() + run.err()).isZero();
        String id = idOf(run);

        assertThat(Files.readString(home.resolve("ledger/starts/" + id + ".json")))
                .contains("\"run_id\" : \"" + id + "\"", "\"run_started\" : \"", "\"workflow_id\" : \"steps\"");
        Result verify = exec("verify", id);
        assertThat(verify.code()).as(verify.out()).isZero();
        assertThat(verify.out()).contains("start     ok (the ledger noted the start, run.started ");
        assertThat(exec("show", id).out()).contains("Ledger    start noted in the ledger: yes");
        assertThat(Files.readString(folderOf(id).resolve("run.html"))).contains("start noted in the ledger: yes");

        Result home = exec("verify");
        assertThat(home.code()).as(home.out() + home.err()).isZero();
        assertThat(home.out()).contains("ledger    1 run started, 1 ended; 0 run folders from before the ledger",
                "started, not ended (0)", "ended, folder gone (0)", "acknowledged (0)", "problems (0)", "verified");
    }

    /** The case the start entry is for: a run that never ended, and whose folder someone removed. */
    @Test
    void verifyWithoutARun_namesARunThatStartedAndNeverEnded_alsoWhenItsFolderIsGone() throws Exception {
        String finished = idOf(exec("run", steps().toString(), "--allow-host-execution", "--no-input"));
        Result parked = exec("run", asking().toString(), "--allow-host-execution", "--no-input");
        assertThat(parked.code()).as(parked.out() + parked.err()).isEqualTo(5);
        String id = idOf(parked);

        Result there = exec("verify");
        assertThat(there.code()).as(there.out()).isZero();
        assertThat(there.out()).contains("ledger    2 runs started, 1 ended;", "started, not ended (1)")
                .containsPattern("(?m)^  " + id + "  \\S+Z  asking  folder PARKED$");

        delete(folderOf(id));
        delete(folderOf(finished));

        Result gone = exec("verify");
        assertThat(gone.code()).as(gone.out()).isEqualTo(8);
        assertThat(gone.out()).containsPattern("(?m)^  " + id + "  \\S+Z  asking  folder GONE$")
                .contains("ended, folder gone (1)", "  " + finished + "  COMPLETED", "problems (1)",
                        "  " + id + "  started, never ended, and the folder is gone",
                        "verification FAILED: 1 problem");
        assertThat(exec("runs").out()).as("the runs folder no longer knows either").doesNotContain(id.substring(0, 8));
    }

    /** Someone removes the start entry and keeps the folder: the run's own verify says that the entry is missing. */
    @Test
    void aRunWhoseStartEntryWasTakenOutOfTheLedger_failsVerify() throws Exception {
        Result parked = exec("run", asking().toString(), "--allow-host-execution", "--no-input");
        String id = idOf(parked);
        git("rm", "-q", "starts/" + id + ".json");
        git("commit", "-q", "-m", BY_HAND);

        Result verify = exec("verify", id);
        assertThat(verify.code()).as(verify.out()).isEqualTo(8);
        assertThat(verify.out()).contains("start     missing: run.started says the ledger noted the start");
        Result all = exec("verify");
        assertThat(all.code()).isEqualTo(8);
        assertThat(all.out()).contains("problems (2)").containsPattern("(?m)^  " + id
                + "  commit [0-9a-f]{12} \\(\"run " + id + " STARTED sha256:[0-9a-f]{64}\"\\) names starts/" + id
                + "\\.json, and the ledger does not hold it");
    }

    /** Fail closed: a ledger that takes no start entry lets no step run. The run ends FAILED and the start is refused. */
    @Test
    void aRunWhoseStartTheLedgerCannotNote_runsNoStep_andIsRefused() throws Exception {
        // The ledger can be prepared, and a file is in the way of its start entries
        Files.createDirectories(home.resolve("ledger"));
        Files.writeString(home.resolve("ledger/starts"), "in the way");

        Result run = exec("run", steps().toString(), "--allow-host-execution", "--no-input");

        assertThat(run.code()).as(run.out() + run.err()).isEqualTo(6);
        assertThat(run.err()).contains("The ledger could not note the start of run", "no step ran", "traceworks resume");
        assertThat(temp.resolve("ran.txt")).doesNotExist();
        assertThat(exec("runs").out()).contains("FAILED");

        Files.delete(home.resolve("ledger/starts"));
        String id = exec("runs").out().substring(0, 8);
        Result resumed = exec("resume", id, "--allow-host-execution");
        assertThat(resumed.code()).as(resumed.out() + resumed.err()).isEqualTo(3);
        assertThat(temp.resolve("ran.txt")).as("the run stays ended: no step runs later either").doesNotExist();
        assertThat(exec("verify", id).code()).isZero();
    }

    /** rm -rf the folder and mkdir it again: no better than no folder. */
    @Test
    void aRunFolderMadeAgainEmpty_failsTheCheck() throws Exception {
        String id = idOf(exec("run", asking().toString(), "--allow-host-execution", "--no-input"));
        Path folder = folderOf(id);
        delete(folder);
        Files.createDirectories(folder);

        Result verify = exec("verify");

        assertThat(verify.code()).as(verify.out() + verify.err()).isEqualTo(8);
        assertThat(verify.out()).containsPattern("(?m)^  " + id + "  \\S+Z  asking  folder UNREADABLE$")
                .contains("holds no record that can be read");
    }

    /** The ledger is deleted and the tool makes a new one: the run folders say what it should hold. */
    @Test
    void aLedgerThatWasDeleted_isFoundFromTheRunFolders() throws Exception {
        String parked = idOf(exec("run", asking().toString(), "--allow-host-execution", "--no-input"));
        String finished = idOf(exec("run", steps().toString(), "--allow-host-execution", "--no-input"));
        delete(home.resolve("ledger"));

        Result verify = exec("verify");

        assertThat(verify.code()).as(verify.out() + verify.err()).isEqualTo(8);
        assertThat(verify.out()).contains("ledger    0 runs started, 0 ended", "problems (3)",
                "  " + parked + "  the record says the ledger noted the start (PARKED), and the ledger holds no"
                        + " start entry of the run",
                "  " + finished + "  the record says the ledger noted the start (COMPLETED), and the ledger holds no"
                        + " start entry of the run");
        Result one = exec("verify", parked);
        assertThat(one.code()).isEqualTo(8);
        assertThat(one.out()).contains("start     missing:");
    }

    /** An honest cause is settled on the record: who, when and a note, kept in the ledger and listed for good. */
    @Test
    void aLostRun_isAcknowledgedWithANote_andStaysListed() throws Exception {
        String id = idOf(exec("run", asking().toString(), "--allow-host-execution", "--no-input"));
        delete(folderOf(id));
        assertThat(exec("verify").code()).isEqualTo(8);

        Result silent = exec("verify", "--acknowledge", id);
        assertThat(silent.code()).as(silent.err()).isEqualTo(2);
        assertThat(silent.err()).contains("--note");
        Result unknown = exec("verify", "--acknowledge", "ffffffff", "--note", "why not");
        assertThat(unknown.code()).as(unknown.err()).isEqualTo(6);
        assertThat(exec("verify").code()).isEqualTo(8);

        Result ack = exec("verify", "--acknowledge", id.substring(0, 8), "--note", "deleted by hand, the disk was full");

        assertThat(ack.code()).as(ack.out() + ack.err()).isZero();
        assertThat(ack.out()).contains("acknowledged (1)",
                "  " + id + "  started, never ended, and the folder is gone",
                "    by tester at ", ": deleted by hand, the disk was full", "problems (0)", "verified")
                .containsPattern("(?m)^  " + id + "  \\S+Z  asking  folder GONE$");
        assertThat(Files.readString(home.resolve("ledger/acks/" + id + ".jsonl")))
                .contains("\"actor\":\"tester\"", "\"problem\":\"lost\"",
                        "\"note\":\"deleted by hand, the disk was full\"");
        Result again = exec("verify");
        assertThat(again.code()).isZero();
        assertThat(again.out()).contains("acknowledged (1)");
    }

    /**
     * No history is rewritten: a later, ordinary commit writes the start entry again, with the same fields. The
     * entry is no longer what the commit that names it added, and both checks name its commit, the path and both hashes.
     */
    @Test
    void anEntryChangedByALaterOrdinaryCommit_failsBothChecks() throws Exception {
        String id = idOf(exec("run", steps().toString(), "--allow-host-execution", "--no-input"));
        assertThat(exec("verify", id).code()).isZero();
        Path entry = home.resolve("ledger/starts/" + id + ".json");
        Files.writeString(entry, Files.readString(entry).replace(" : ", ": "));
        git("commit", "-q", "-m", BY_HAND, "--", "starts/" + id + ".json");

        Result one = exec("verify", id);
        assertThat(one.code()).as(one.out()).isEqualTo(8);
        assertThat(one.out()).containsPattern("(?m)^ledger +changed: starts/" + id + "\\.json is not what commit"
                + " [0-9a-f]{12} \\(\"run " + id + " STARTED sha256:[0-9a-f]{64}\"\\) left: that names the SHA-256"
                + " [0-9a-f]{64}, and the ledger holds [0-9a-f]{64}");
        Result all = exec("verify");
        assertThat(all.code()).as(all.out()).isEqualTo(8);
        assertThat(all.out()).contains("problems (2)", "  " + id + "  starts/" + id + ".json is not what commit ");
    }

    /** The rule for the ledger's own git settings holds at the start too: no run starts that could not be checked. */
    @Test
    void aForeignSettingInTheLedger_refusesTheRunBeforeItStarts() throws Exception {
        exec("run", steps().toString(), "--allow-host-execution", "--no-input");
        git("config", "core.autocrlf", "true");

        Result run = exec("run", steps().toString(), "--allow-host-execution", "--no-input");

        assertThat(run.code()).as(run.out() + run.err()).isEqualTo(6);
        assertThat(run.err()).contains("core.autocrlf", "config --unset", "the run is not started");
        assertThat(exec("runs").out().lines().count()).as("no second run").isEqualTo(1);
    }

    /** A file that is no entry refuses the run before run.started is written: no FAILED run folder is left. */
    @Test
    void aStrayFileInTheLedger_refusesTheRunBeforeItStarts_withTheRemedy() throws Exception {
        exec("run", steps().toString(), "--allow-host-execution", "--no-input");
        Files.writeString(home.resolve("ledger/README"), "notes");
        git("add", "README");
        git("commit", "-q", "-m", BY_HAND);

        Result run = exec("run", steps().toString(), "--allow-host-execution", "--no-input");

        assertThat(run.code()).as(run.out() + run.err()).isEqualTo(6);
        assertThat(run.err()).contains("README", "reset --hard HEAD~1", "the run is not started");
        assertThat(exec("runs").out().lines().count()).as("no second run folder").isEqualTo(1);
    }

    /**
     * A ledger of an earlier version, whose commits name no hash: no command reads or writes it, and each says what
     * to do. With the folder moved aside the next run starts a new ledger, and the runs stay where they are.
     */
    @Test
    void aLedgerOfAnEarlierVersion_isRefusedWithWhatToDo_andANewOneStartsOnceItIsMovedAside() throws Exception {
        Files.createDirectories(home.resolve("ledger/runs"));
        git("init", "-q", "-b", "main");
        Files.writeString(home.resolve("ledger/runs/old.json"), "{\"run_id\":\"old\",\"status\":\"COMPLETED\"}");
        git("add", "runs/old.json");
        git("commit", "-q", "-m", "run old COMPLETED");

        Result run = exec("run", steps().toString(), "--allow-host-execution", "--no-input");
        assertThat(run.code()).as(run.out() + run.err()).isEqualTo(6);
        assertThat(run.err()).contains("was written by an earlier version of Traceworks", "Move the folder aside",
                "the runs and the registers stay where they are", "the run is not started");
        assertThat(exec("runs").out()).isBlank();
        Result verify = exec("verify");
        assertThat(verify.code()).as(verify.out() + verify.err()).isEqualTo(8);
        assertThat(verify.err()).contains("was written by an earlier version of Traceworks");

        Files.move(home.resolve("ledger"), home.resolve("ledger-earlier"));

        Result again = exec("run", steps().toString(), "--allow-host-execution", "--no-input");
        assertThat(again.code()).as(again.out() + again.err()).isZero();
        assertThat(exec("verify").code()).isZero();
        assertThat(exec("verify", idOf(again)).code()).isZero();
    }

    /**
     * verify --json goes to a terminal too. JSON escapes only what is below U+0020, so everything from DEL on is
     * escaped as well: no control or format character of a text in the report arrives raw, and a reader of the JSON
     * gets the same text.
     */
    @Test
    void verifyJson_isAsciiOnly_soNoControlCharacterReachesTheTerminal() throws Exception {
        String lost = idOf(exec("run", asking().toString(), "--allow-host-execution", "--no-input"));
        delete(folderOf(lost));
        String note = "the disk was full \u009b31m\u007f‮ é";
        Result ack = exec("verify", "--acknowledge", lost, "--note", note);
        assertThat(ack.code()).as(ack.out() + ack.err()).isZero();

        Result home = exec("verify", "--json");

        assertThat(home.code()).as(home.out() + home.err()).isZero();
        assertThat(home.out().chars().allMatch(c -> c == '\n' || c == '\r' || c >= 0x20 && c < 0x7f))
                .as(home.out()).isTrue();
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(home.out()).path("acknowledged").get(0)
                .path("note").asText()).isEqualTo(note);
    }

    /** verify --json: the classes and the counts for a script, with the same exit codes. */
    @Test
    void verifyJson_givesTheClassesAndCounts_andTheSameExitCodes() throws Exception {
        String finished = idOf(exec("run", steps().toString(), "--allow-host-execution", "--no-input"));
        String lost = idOf(exec("run", asking().toString(), "--allow-host-execution", "--no-input"));
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

        Result fine = exec("verify", "--json");
        assertThat(fine.code()).as(fine.out() + fine.err()).isZero();
        com.fasterxml.jackson.databind.JsonNode home = json.readTree(fine.out());
        assertThat(home.path("verified").asBoolean()).isTrue();
        assertThat(home.path("started").asInt()).isEqualTo(2);
        assertThat(home.path("ended").asInt()).isEqualTo(1);
        assertThat(home.path("records_verified").asBoolean()).isTrue();
        assertThat(home.path("open").get(0).path("run").asText()).isEqualTo(lost);
        assertThat(home.path("open").get(0).path("folder").asText()).isEqualTo("PARKED");
        assertThat(home.path("problems")).isEmpty();

        delete(folderOf(lost));
        Result failed = exec("verify", "--json", "--quick");
        assertThat(failed.code()).isEqualTo(8);
        com.fasterxml.jackson.databind.JsonNode broken = json.readTree(failed.out());
        assertThat(broken.path("verified").asBoolean()).isFalse();
        assertThat(broken.path("records_verified").asBoolean()).isFalse();
        assertThat(broken.path("problems").get(0).path("run").asText()).isEqualTo(lost);
        assertThat(broken.path("problems").get(0).path("code").asText()).isEqualTo("lost");

        Result one = exec("verify", finished, "--json");
        assertThat(one.code()).as(one.out() + one.err()).isZero();
        com.fasterxml.jackson.databind.JsonNode run = json.readTree(one.out());
        assertThat(run.path("verified").asBoolean()).isTrue();
        assertThat(run.path("chain").path("intact").asBoolean()).isTrue();
        assertThat(run.path("ledger").asText()).isEqualTo("ok");
        assertThat(run.path("start").asText()).startsWith("ok");
    }

    /** verify --json says every class of the home, and a failed check exits 8 also when an acknowledgement leaves problems. */
    @Test
    void verifyJson_listsEveryClassOfAHomeThatDoesNotHold_andExitsEight() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        // A run of a version without a start entry: no ledger_start in its first line, and the ledger knows nothing of it
        String old = idOf(exec("run", asking().toString(), "--allow-host-execution", "--no-input"));
        delete(home.resolve("ledger"));
        Path oldEvents = folderOf(old).resolve("events.jsonl");
        String eventLines = Files.readString(oldEvents);
        assertThat(eventLines).contains("\"ledger_start\":true");
        Files.writeString(oldEvents, eventLines.replaceFirst("\"ledger_start\":true", "\"ledger_start\":false"));
        // the ledger's first commit has a time in whole seconds: the new ledger must begin after the old run started
        Thread.sleep(1100);

        String finished = idOf(exec("run", steps().toString(), "--allow-host-execution", "--no-input"));
        String lostOne = idOf(exec("run", asking().toString(), "--allow-host-execution", "--no-input"));
        String lostTwo = idOf(exec("run", asking().toString(), "--allow-host-execution", "--no-input"));
        delete(folderOf(finished));
        delete(folderOf(lostOne));
        delete(folderOf(lostTwo));
        // A run whose writer is alive and whose record has no line yet
        try (traceworks.core.port.StoredRun writing = new traceworks.adapters.record.FileRunStore(home.resolve("runs"))
                .create("in-flight-run", java.time.Instant.now())) {
            Result ack = exec("verify", "--acknowledge", lostOne, "--note", "disk full", "--json");
            assertThat(ack.code()).as(ack.out() + ack.err()).isEqualTo(8);
            com.fasterxml.jackson.databind.JsonNode after = json.readTree(ack.out());
            assertThat(after.path("verified").asBoolean()).isFalse();
            assertThat(after.path("acknowledged")).hasSize(1);
            assertThat(after.path("acknowledged").get(0).path("run").asText()).isEqualTo(lostOne);

            Result failed = exec("verify", "--json", "--quick");
            assertThat(failed.code()).as(failed.out() + failed.err()).isEqualTo(8);
            com.fasterxml.jackson.databind.JsonNode report = json.readTree(failed.out());
            assertThat(report.path("ended_folder_gone")).hasSize(1);
            assertThat(report.path("ended_folder_gone").get(0).path("run").asText()).isEqualTo(finished);
            assertThat(report.path("before_ledger_not_ended")).hasSize(1);
            assertThat(report.path("before_ledger_not_ended").get(0).path("run").asText()).isEqualTo(old);
            assertThat(report.path("in_flight")).hasSize(1);
            assertThat(report.path("in_flight").get(0).path("run").asText()).isEqualTo("in-flight-run");
            assertThat(report.path("acknowledged")).hasSize(1);
            assertThat(report.path("problems")).hasSize(1);
            assertThat(report.path("problems").get(0).path("run").asText()).isEqualTo(lostTwo);
        }
    }

    /** verify <run> --json: a record that does not hold exits 8 and lists the problems of its chain. */
    @Test
    void verifyJson_ofATamperedRun_exitsEight_andListsTheChainProblems() throws Exception {
        String id = idOf(exec("run", steps().toString(), "--allow-host-execution", "--no-input"));
        Path events = folderOf(id).resolve("events.jsonl");
        String text = Files.readString(events);
        assertThat(text).contains("\"say\"");
        Files.writeString(events, text.replaceFirst("\"say\"", "\"sax\""));

        Result result = exec("verify", id, "--json");

        assertThat(result.code()).as(result.out() + result.err()).isEqualTo(8);
        com.fasterxml.jackson.databind.JsonNode run = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.out());
        assertThat(run.path("verified").asBoolean()).isFalse();
        assertThat(run.path("chain").path("intact").asBoolean()).isFalse();
        assertThat(run.path("chain").path("problems")).isNotEmpty();
    }

    /** A subject this version writes, for a commit made by hand: the ledger stays one of this version. */
    private static final String BY_HAND = "run by-hand ACKNOWLEDGED sha256:" + "0".repeat(64);

    private void git(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", home.resolve("ledger").toString(),
                "-c", "user.name=x", "-c", "user.email=x@x.invalid", "-c", "commit.gpgsign=false"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(out).isZero();
    }
}
