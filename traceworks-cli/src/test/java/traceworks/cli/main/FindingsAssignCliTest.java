package traceworks.cli.main;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import traceworks.cli.RuntimeSource;
import traceworks.core.testing.EarlierRegister;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A home an earlier version left: a register in the folder of its key, anchored in the ledger under the key alone.
 * Nothing assigns it to a project by itself; {@code traceworks findings <key> assign --project KEY} does. Real
 * adapters in a temporary home, with the register and the ledger as that version wrote them.
 */
class FindingsAssignCliTest {

    private static final String HINT = "'traceworks findings arch assign --project KEY'";

    @TempDir
    Path temp;
    private Path home;
    private Path work;

    private record Result(int code, String out, String err) {
    }

    @BeforeEach
    void anEarlierHome() throws Exception {
        home = Files.createDirectories(temp.resolve("home"));
        work = Files.createDirectories(temp.resolve("work"));
        EarlierRegister.writeTo(home.resolve("registers/arch"));
        // The ledger of that version: one commit per write of the register, each adding a line under the key alone
        Path ledger = Files.createDirectories(home.resolve("ledger/registers"));
        git("-c", "init.defaultBranch=main", "-c", "init.templateDir=", "init", "-q");
        String anchors = "";
        for (int seq : new int[] {5, EarlierRegister.lastSeq()}) {
            anchors += "{\"seq\":" + seq + ",\"head\":\"" + sha256(EarlierRegister.lines().get(seq)) + "\"}\n";
            Files.writeString(ledger.resolve("arch.jsonl"), anchors);
            git("add", "--", "registers/arch.jsonl");
            git("commit", "-q", "-m", "register arch seq " + seq + " sha256:"
                    + sha256(anchors.getBytes(StandardCharsets.UTF_8)), "--", "registers/arch.jsonl");
        }
    }

    private static String sha256(byte[] content) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
    }

    private String git(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", home.resolve("ledger").toString()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(Map.of("GIT_AUTHOR_NAME", "Traceworks", "GIT_AUTHOR_EMAIL",
                "traceworks@localhost.invalid", "GIT_COMMITTER_NAME", "Traceworks", "GIT_COMMITTER_EMAIL",
                "traceworks@localhost.invalid", "GIT_CONFIG_GLOBAL", "/dev/null", "GIT_CONFIG_NOSYSTEM", "1"));
        Process process = builder.start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(out).isZero();
        return out;
    }

    private Result exec(String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--home");
        all.add(home.toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(all.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Map.of("TRACEWORKS_ACTOR", "tester"), work, null,
                RuntimeSource.NONE);
        return new Result(code, traceworks.cli.Unbroken.of(out.toString(StandardCharsets.UTF_8)),
                traceworks.cli.Unbroken.of(err.toString(StandardCharsets.UTF_8)));
    }

    /** The audit that fed the register: the workflow its first line names, with the key {@code arch}. */
    private Path audit() throws IOException {
        String command = "printf '%s\\n' '# Report' '<!-- traceworks:audit-result:v1' '{\"snapshotComplete\":true,"
                + "\"findings\":[{\"ruleKey\":\"size\",\"subject\":\"C.java\",\"severity\":\"HIGH\",\"title\":"
                + "\"C is long\",\"evidence\":\"E1\"}]}' '-->'";
        Path dir = Files.createDirectories(temp.resolve("audit"));
        Files.writeString(dir.resolve("workflow.json"), "{\"id\":\"" + EarlierRegister.WORKFLOW + "\",\"name\":"
                + "\"Arch\",\"metadata\":{\"audit\":{\"enabled\":true,\"key\":\"arch\",\"category\":\"architecture\","
                + "\"report_block\":\"report\"}},\"blocks\":[{\"type\":\"shell\",\"name\":\"report\",\"config\":"
                + "{\"command\":" + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(command)
                + "}}]}");
        return dir;
    }

    private Result run(String project) throws IOException {
        return exec("run", audit().toString(), "--project", project, "--allow-host-execution", "--no-input",
                "--quiet");
    }

    private List<String> entries(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(folder)) {
            return children.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void untilItIsAssigned_theRegisterRefusesRunsAndFindings_andTheOverviewSaysSo() throws IOException {
        Result overview = exec("findings");
        Result refusedRun = run("shop");

        assertThat(overview.code()).as(overview.err()).isZero();
        assertThat(overview.out()).isEqualTo("arch  not assigned to a project: a register from before registers had"
                + " projects; assign it with " + HINT + "\n");
        assertThat(refusedRun.code()).as(refusedRun.out() + refusedRun.err()).isEqualTo(6);
        assertThat(refusedRun.err()).contains("the register arch is from before registers had projects")
                .contains(HINT);
        assertThat(entries(home.resolve("runs"))).as("no run exists").isEmpty();
        for (String[] command : List.of(new String[] {"list"}, new String[] {}, new String[] {"show", "9b9a750d"},
                new String[] {"items"}, new String[] {"dismiss", "9b9a750d", "--note", "no"},
                new String[] {"accept", "9b9a750d", "--note", "no"}, new String[] {"reopen", "9b9a750d"},
                new String[] {"render"}, new String[] {"verify", "--project", "shop"},
                new String[] {"list", "--project", "shop"})) {
            List<String> args = new ArrayList<>(List.of("findings", "arch"));
            args.addAll(List.of(command));

            Result refused = exec(args.toArray(String[]::new));

            assertThat(refused.code()).as(String.join(" ", command) + ": " + refused.out() + refused.err())
                    .isEqualTo(6);
            assertThat(refused.err()).as(String.join(" ", command))
                    .contains("is from before registers had projects").contains(HINT);
        }
        assertThat(entries(home.resolve("registers/arch"))).as("as that version left it").containsExactly("blobs",
                "events.jsonl", "register.lock", "report.html", "state.json");
        assertThat(Files.readAllLines(home.resolve("registers/arch/events.jsonl"))).hasSize(10);
    }

    /**
     * Reproduced 2026-11-21: the log of the register from before was gone while the ledger still anchored it under
     * the key alone. Its blobs folder was listed as a project, a run with {@code --project a} began a new register
     * and {@code render --project blobs} wrote into the old blobs folder.
     */
    @Test
    void whenItsLogIsGone_theKeyRefusesEveryRunAndCommand_andItsBlobsFolderIsNoProject() throws IOException {
        Files.delete(home.resolve("registers/arch/events.jsonl"));
        List<String> left = entries(home.resolve("registers/arch"));
        List<String> blobs = entries(home.resolve("registers/arch/blobs"));
        String gone = "he ledger holds anchors of the register arch from before registers had projects, and this"
                + " home holds neither that register nor one that was assigned from it";

        Result refusedRun = run("a");
        Result overview = exec("findings");

        assertThat(refusedRun.code()).as(refusedRun.out() + refusedRun.err()).isEqualTo(6);
        assertThat(refusedRun.err()).contains(gone)
                .contains("Put the register's folder back, or feed a register of another key")
                .contains("'traceworks verify' says what the ledger holds of it");
        assertThat(entries(home.resolve("runs"))).as("no run exists").isEmpty();
        assertThat(overview.code()).as("the register of the list is gone: " + overview.err()).isEqualTo(6);
        assertThat(overview.out()).isEqualTo("arch  refused: T" + gone + ": no register of arch is begun beside the"
                + " anchors of the one that was there, for any project. Put the register's folder back, or feed a"
                + " register of another key. 'traceworks verify' says what the ledger holds of it; an"
                + " acknowledgement there silences that check and begins nothing anew\n");
        for (String[] command : List.of(new String[] {"list"}, new String[] {"render", "--project", "blobs"},
                new String[] {"list", "--project", "a"}, new String[] {"render", "--project", "a"},
                new String[] {"dismiss", "9b9a750d", "--note", "no", "--project", "blobs"},
                new String[] {"assign", "--project", "a"})) {
            List<String> args = new ArrayList<>(List.of("findings", "arch"));
            args.addAll(List.of(command));

            Result refused = exec(args.toArray(String[]::new));

            assertThat(refused.code()).as(String.join(" ", command) + ": " + refused.out() + refused.err())
                    .isEqualTo(6);
            assertThat(refused.err()).as(String.join(" ", command)).contains(gone)
                    .doesNotContain("internal error");
        }
        assertThat(entries(home.resolve("registers/arch"))).as("nothing was begun, and nothing written")
                .isEqualTo(left);
        assertThat(entries(home.resolve("registers/arch/blobs"))).as("nor into its blobs folder").isEqualTo(blobs);
        Result verify = exec("verify");
        assertThat(verify.code()).as(verify.out() + verify.err()).isEqualTo(8);
        assertThat(verify.out()).contains("registers.arch  the ledger holds anchors of the register arch from before"
                + " registers had projects").doesNotContain("anchors.4.arch.blobs");
    }

    /**
     * Reproduced 2026-11-21: with the register from before gone, a folder in the place of a project's log
     * ({@code registers/arch/zz/events.jsonl/}) lifted the refusal of the key, and a run began a register beside
     * the anchors of the one that was gone. A project that cannot be read accounts for nothing; and the check of
     * the home listed that project twice, in two wordings.
     */
    @Test
    void aProjectWhoseLogIsAFolder_doesNotLiftTheRefusalOfTheKey_andTheCheckListsItOnce() throws IOException {
        Path key = home.resolve("registers/arch");
        remove(key);
        Files.createDirectories(key.resolve("zz/events.jsonl"));

        Result refusedRun = run("shop");
        Result overview = exec("findings");
        Result verify = exec("verify");

        assertThat(refusedRun.code()).as(refusedRun.out() + refusedRun.err()).isEqualTo(6);
        assertThat(refusedRun.err()).contains("he ledger holds anchors of the register arch from before registers"
                        + " had projects, and this home holds neither that register nor one that was assigned from"
                        + " it (the log of the register arch of zz cannot be read (")
                .contains("so it accounts for nothing").doesNotContain("internal error");
        assertThat(entries(home.resolve("runs"))).as("no run exists").isEmpty();
        assertThat(entries(key)).as("no register was begun beside it").containsExactly("zz");
        assertThat(overview.code()).isEqualTo(6);
        assertThat(overview.out()).startsWith("arch  refused: The ledger holds anchors of the register arch from"
                + " before registers had projects").contains("the log of the register arch of zz cannot be read");
        assertThat(verify.code()).as(verify.out()).isEqualTo(8);
        assertThat(verify.out().lines().filter(line -> line.startsWith("  anchors.4.arch.zz  ")).toList())
                .as("the register that cannot be read, once").singleElement().asString()
                .isEqualTo("  anchors.4.arch.zz  the register cannot be read: Cannot read "
                        + key.resolve("zz/events.jsonl") + ": a folder is in its place");
        assertThat(refusedRun.err()).contains("(the log of the register arch of zz cannot be read (Cannot read "
                + key.resolve("zz/events.jsonl") + ": a folder is in its place), so it accounts for nothing)");
        assertThat(verify.out()).contains("  registers.arch  the ledger holds anchors of the register arch from"
                + " before registers had projects", "problems (2)");
    }

    private static void mode(Path path, String permissions) throws IOException {
        Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString(permissions));
    }

    /**
     * Reproduced 2026-11-21: {@code assign} with a read-only {@code events.jsonl} was exit 70, and so was one whose
     * register could not be moved.
     */
    @Test
    void anAssignThatCannotWrite_isARefusal_andSaysWhatIsRecorded_andTheSameCommandFinishesIt() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeFalse("root".equals(System.getProperty("user.name")));
        Path key = home.resolve("registers/arch");
        mode(key.resolve("events.jsonl"), "r--------");
        try {
            Result refused = exec("findings", "arch", "assign", "--project", "shop");

            assertThat(refused.code()).as(refused.out() + refused.err()).isEqualTo(6);
            assertThat(refused.err()).doesNotContain("internal error")
                    .contains("The register 'arch' cannot be written, and it is not assigned: ");
            assertThat(Files.readAllLines(key.resolve("events.jsonl"))).hasSize(10);
        } finally {
            mode(key.resolve("events.jsonl"), "rw-------");
        }
        mode(key, "r-x------");
        try {
            Result refused = exec("findings", "arch", "assign", "--project", "shop");

            assertThat(refused.code()).as(refused.out() + refused.err()).isEqualTo(6);
            assertThat(refused.err()).doesNotContain("internal error")
                    .contains("The register 'arch' cannot be assigned: ");
            assertThat(Files.readAllLines(key.resolve("events.jsonl"))).as("no line").hasSize(10);
        } finally {
            mode(key, "rwx------");
        }
        // Something in the place where the register is gathered before it is moved: the line is written by then
        Files.writeString(key.resolve(".assigning"), "in the way");
        Result notMoved = exec("findings", "arch", "assign", "--project", "shop");

        assertThat(notMoved.code()).as(notMoved.out() + notMoved.err()).isEqualTo(6);
        assertThat(notMoved.err()).doesNotContain("internal error")
                .contains("The assignment of the register 'arch' to shop is recorded in its log"
                        + " (register.assigned) and not finished: ")
                .contains("'traceworks findings arch assign --project shop' finishes it");
        assertThat(Files.readAllLines(key.resolve("events.jsonl"))).as("its line").hasSize(11);
        Files.delete(key.resolve(".assigning"));

        // Cut off after its line: the overview and verify say that, with the command that finishes it, and not
        // that the register is one nobody began to assign, or that its state file was edited
        Result overview = exec("findings");
        Result verify = exec("findings", "arch", "verify");
        Result check = exec("verify");
        assertThat(overview.out()).isEqualTo("arch  not assigned to a project: its assignment to shop was begun and"
                + " not finished; finish it with 'traceworks findings arch assign --project shop'\n");
        assertThat(verify.code()).as(verify.out()).isEqualTo(8);
        assertThat(verify.out()).contains("chain     ok (11 events", "| the assignment of the register arch to the"
                + " project shop was begun (register.assigned at seq 10) and not finished: 'traceworks findings arch"
                + " assign --project shop' finishes it", "register arch does not verify")
                .doesNotContain("state.json");
        assertThat(check.code()).isEqualTo(8);
        assertThat(check.out()).contains("  registers.arch  the assignment of the register arch to the project shop"
                + " was begun");

        Result finished = exec("findings", "arch", "assign", "--project", "shop");

        assertThat(finished.code()).as(finished.out() + finished.err()).isZero();
        assertThat(Files.readAllLines(key.resolve("shop/events.jsonl"))).as("nothing twice").hasSize(11);
        assertThat(exec("findings", "arch", "verify").code()).isZero();
    }

    @Test
    void assign_movesTheRegisterToItsProject_anchorsIt_andEveryCheckHolds() throws Exception {
        byte[] earlierAnchors = Files.readAllBytes(home.resolve("ledger/registers/arch.jsonl"));

        Result assigned = exec("findings", "arch", "assign", "--project", "shop");

        assertThat(assigned.code()).as(assigned.out() + assigned.err()).isZero();
        assertThat(assigned.out()).isEqualTo("assigned the register arch to the project shop: "
                + home.resolve("registers/arch/shop") + "\n");
        assertThat(entries(home.resolve("registers/arch"))).containsExactly("shop");
        assertThat(entries(home.resolve("registers/arch/shop"))).containsExactly("blobs", "events.jsonl",
                "register.lock", "report.html", "state.json");
        List<String> log = Files.readAllLines(home.resolve("registers/arch/shop/events.jsonl"));
        assertThat(log).hasSize(11);
        assertThat(log.getLast()).contains("\"type\":\"register.assigned\"", "\"project\":\"shop\"",
                "\"actor\":\"tester\"");
        assertThat(Files.readString(home.resolve("registers/arch/shop/state.json"))).contains("\"project\":\"shop\"");
        // The ledger: what it held under the key alone is as it was, and the new head is under key and project
        assertThat(git("show", "HEAD:registers/arch.jsonl").getBytes(StandardCharsets.UTF_8)).isEqualTo(earlierAnchors);
        // and says what was written since the anchors of the earlier version: the assignment, and by whom
        assertThat(git("show", "HEAD:anchors/arch/shop.jsonl")).isEqualTo("{\"seq\":10,\"head\":\""
                + sha256(log.getLast().getBytes(StandardCharsets.UTF_8))
                + "\",\"lines\":{\"register.assigned\":1},\"actor\":\"tester\"}\n");
        assertThat(git("log", "-1", "--format=%s").strip()).matches("anchor arch/shop seq 10 sha256:[0-9a-f]{64}");

        Result verify = exec("findings", "arch", "verify");
        assertThat(verify.code()).as(verify.out()).isZero();
        assertThat(verify.out()).contains("chain     ok (11 events",
                "anchor    ok (seq 10 is the head the ledger holds, 3 anchors)", "verified");
        assertThat(exec("verify").code()).as("the check of the whole home").isZero();
        assertThat(exec("findings").out()).isEqualTo("arch  shop  architecture  OPEN 1  ACCEPTED_RISK 1  DISMISSED 1\n");
        assertThat(exec("findings", "arch", "list").out()).contains("C.java", "A.java", "B.java");

        Result next = run("shop");
        assertThat(next.code()).as(next.out() + next.err()).isZero();
        assertThat(next.out()).contains("register arch of shop: 0 new, 0 resolved, 0 reopened, 1 open now");
        assertThat(exec("findings", "arch", "verify").code()).isZero();

        Result again = exec("findings", "arch", "assign", "--project", "shop");
        assertThat(again.code()).isEqualTo(6);
        assertThat(again.err()).contains("is not from before registers had projects");
    }

    /**
     * A ledger that can be read and cannot be written: the assignment and a render are done and kept, each says
     * that its head is not anchored, and neither ends as a command that did all it was for (exit 6).
     */
    @Test
    void anAssignAndARenderTheLedgerCannotAnchor_areKept_saySo_andExit6() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeFalse("root".equals(System.getProperty("user.name")));
        Path refs = home.resolve("ledger/.git/refs/heads");
        Path key = home.resolve("registers/arch");
        mode(refs, "r-x------");
        try {
            Result assigned = exec("findings", "arch", "assign", "--project", "shop");

            assertThat(assigned.code()).as(assigned.out() + assigned.err()).isEqualTo(6);
            assertThat(assigned.out()).startsWith("assigned the register arch to the project shop: ");
            assertThat(assigned.err()).doesNotContain("internal error").contains("traceworks: warning: the register"
                    + " is assigned, but its head is not anchored in the ledger (")
                    .contains("'traceworks findings arch render --project shop' anchors it once the ledger can be"
                            + " written");
            assertThat(entries(key)).containsExactly("shop");

            Result render = exec("findings", "arch", "render");

            assertThat(render.code()).as(render.out() + render.err()).isEqualTo(6);
            assertThat(render.out()).startsWith("rendered the register arch of shop: ");
            assertThat(render.err()).doesNotContain("internal error").contains("traceworks: warning: the head of the"
                    + " register is not anchored in the ledger (");
        } finally {
            mode(refs, "rwx------");
        }
        Result render = exec("findings", "arch", "render");
        assertThat(render.code()).as(render.out() + render.err()).isZero();
        Result verify = exec("findings", "arch", "verify");
        assertThat(verify.code()).as(verify.out()).isZero();
        assertThat(verify.out()).contains("anchor    ok (seq ");
    }

    /**
     * An assignment whose line is written and whose derived files then cannot be: it is recorded and not finished,
     * says so with the command that finishes it, and exits 6.
     */
    @Test
    void anAssignWhoseDerivedFilesCannotBeWritten_isRecordedAndNotFinished_saysSo_andExits6() throws IOException {
        Path key = home.resolve("registers/arch");
        Files.delete(key.resolve("state.json"));
        Files.createDirectories(key.resolve("state.json/in-the-way"));

        Result refused = exec("findings", "arch", "assign", "--project", "shop");

        assertThat(refused.code()).as(refused.out() + refused.err()).isEqualTo(6);
        assertThat(refused.out()).as("no confirmation").isEmpty();
        assertThat(refused.err()).doesNotContain("internal error")
                .contains("The assignment of the register 'arch' to shop is recorded in its log"
                        + " (register.assigned) and not finished: ")
                .contains("'traceworks findings arch assign --project shop' finishes it");
        assertThat(Files.readAllLines(key.resolve("events.jsonl"))).as("its line").hasSize(11);
        assertThat(key.resolve("shop")).doesNotExist();
        remove(key.resolve("state.json"));

        Result finished = exec("findings", "arch", "assign", "--project", "shop");

        assertThat(finished.code()).as(finished.out() + finished.err()).isZero();
        assertThat(Files.readAllLines(key.resolve("shop/events.jsonl"))).as("nothing twice").hasSize(11);
        assertThat(exec("findings", "arch", "verify").code()).isZero();
    }

    /** Copies a folder with everything in it, as a backup made by hand, or one put back. */
    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path path : paths.toList()) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void remove(Path folder) throws IOException {
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    /**
     * Reproduced 2026-11-21: the register was assigned to shop, a backup of the earlier folder was put back at the
     * key and assigned to portal. Two registers held the same decisions, and every verify held. One register from
     * before is assigned once: the second assignment is refused, and nothing is written.
     */
    @Test
    void theEarlierFolderPutBackAfterTheAssignment_isNotAssignedToASecondProject_andNothingIsWritten()
            throws Exception {
        Path key = home.resolve("registers/arch");
        Path backup = temp.resolve("backup");
        copy(key, backup);
        assertThat(exec("findings", "arch", "assign", "--project", "shop").code()).isZero();
        copy(backup, key);
        String ledgerHead = git("rev-parse", "HEAD");

        Result second = exec("findings", "arch", "assign", "--project", "portal");

        assertThat(second.code()).as(second.out() + second.err()).isEqualTo(6);
        assertThat(second.err()).contains("The register 'arch' cannot be assigned to portal: the register from before"
                + " registers had projects of this key was assigned to the project shop already (the log of the"
                + " register arch of shop holds register.assigned). A register from before is assigned once, so what"
                + " lies at the key is a copy of what was assigned: remove it");
        assertThat(second.out()).isEmpty();
        assertThat(entries(key)).as("no folder of portal, no lock or staging folder of an assignment")
                .containsExactly("blobs", "events.jsonl", "register.lock", "report.html", "shop", "state.json");
        assertThat(Files.readAllBytes(key.resolve("events.jsonl"))).as("no line was added")
                .isEqualTo(Files.readAllBytes(backup.resolve("events.jsonl")));
        assertThat(Files.readAllBytes(key.resolve("state.json"))).isEqualTo(Files.readAllBytes(
                backup.resolve("state.json")));
        assertThat(git("rev-parse", "HEAD")).as("nothing was anchored").isEqualTo(ledgerHead);
        Result same = exec("findings", "arch", "assign", "--project", "shop");
        assertThat(same.code()).as("nor to the project that has it").isEqualTo(6);
        assertThat(same.err()).contains("was assigned to the project shop already");

        // The way out the refusal names: the copy at the key is removed, and everything holds
        for (String entry : List.of("events.jsonl", "state.json", "report.html", "register.lock")) {
            Files.delete(key.resolve(entry));
        }
        remove(key.resolve("blobs"));
        assertThat(exec("findings", "arch", "verify", "--project", "shop").code()).isZero();
        Result home = exec("verify");
        assertThat(home.code()).as(home.out()).isZero();
    }

    /** Also when the folder of the first project is lost: the ledger anchors its assignment. */
    @Test
    void theEarlierFolderPutBack_isNotAssignedToASecondProject_alsoWhenOnlyTheLedgerKnowsTheFirst() throws Exception {
        Path key = home.resolve("registers/arch");
        Path backup = temp.resolve("backup");
        copy(key, backup);
        assertThat(exec("findings", "arch", "assign", "--project", "shop").code()).isZero();
        remove(key.resolve("shop"));
        copy(backup, key);

        Result second = exec("findings", "arch", "assign", "--project", "portal");

        assertThat(second.code()).as(second.out() + second.err()).isEqualTo(6);
        assertThat(second.err()).contains("was assigned to the project shop already (the ledger anchors that"
                + " assignment)").contains("remove it");
        assertThat(entries(key)).containsExactly("blobs", "events.jsonl", "register.lock", "report.html",
                "state.json");
        assertThat(Files.readAllLines(key.resolve("events.jsonl"))).hasSize(10);
    }

    /**
     * Reproduced 2026-11-21: a backup assigned again to the project whose folder was lost. The assignment said
     * nothing, and its anchor said {@code "lines":{}}. It is taken, and says that the log lacks what the ledger
     * anchors for the project; the anchor says what was written.
     */
    @Test
    void theEarlierFolderPutBack_isAssignedAgainToTheProjectWhoseFolderWasLost_andTheAssignmentSaysTheMismatch()
            throws Exception {
        Path key = home.resolve("registers/arch");
        Path backup = temp.resolve("backup");
        copy(key, backup);
        assertThat(exec("findings", "arch", "assign", "--project", "shop").code()).isZero();
        String open = exec("findings", "arch", "list", "--status", "OPEN").out().substring(0, 8);
        assertThat(exec("findings", "arch", "dismiss", open, "--note", "not ours").code()).isZero();
        remove(key.resolve("shop"));
        copy(backup, key);

        Result again = exec("findings", "arch", "assign", "--project", "shop");

        assertThat(again.code()).as(again.out() + again.err()).isZero();
        assertThat(again.out()).startsWith("assigned the register arch to the project shop: " + key.resolve("shop")
                + "\n\nnote: the register does not match its anchors in the ledger, and it is assigned as it is:"
                + " 'traceworks findings arch verify --project shop' says where, and goes on reporting it");
        List<String> log = Files.readAllLines(key.resolve("shop/events.jsonl"));
        assertThat(log).hasSize(11);
        List<String> anchors = git("show", "HEAD:anchors/arch/shop.jsonl").lines().toList();
        assertThat(anchors).hasSize(3);
        assertThat(anchors.getLast()).isEqualTo("{\"seq\":10,\"head\":\""
                + sha256(log.getLast().getBytes(StandardCharsets.UTF_8))
                + "\",\"lines\":{\"register.assigned\":1},\"actor\":\"tester\"}");
        Result verify = exec("findings", "arch", "verify", "--project", "shop");
        assertThat(verify.code()).as("verify goes on reporting it").isEqualTo(8);
        assertThat(verify.out()).contains("seq 10: the line is not the one the ledger anchors",
                "seq 11: the ledger anchors this line");
    }

    // ---- a staging folder beside the register moves nothing by itself ----

    /** Every folder and every file below {@code root}, the files with the SHA-256 of their bytes. */
    private static java.util.SortedMap<String, String> tree(Path root) throws Exception {
        java.util.SortedMap<String, String> tree = new java.util.TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.toList()) {
                tree.put(root.relativize(path).toString(), Files.isDirectory(path) ? "folder"
                        : sha256(Files.readAllBytes(path)));
            }
        }
        return tree;
    }

    /**
     * Reproduced 2026-11-21: with a folder {@code .assigning} beside the register, an assign that was refused
     * ("was assigned already: remove it") had moved the whole register into that folder before it was refused,
     * and nothing lay at the key afterwards. A refused assign leaves every file with its bytes at its place: with
     * an empty staging folder and with one that holds a lock, whatever the refusal.
     */
    @Test
    void aRefusedAssign_besideAStagingFolder_leavesTheTreeByteForByteAndPlaceForPlace() throws Exception {
        Path key = home.resolve("registers/arch");
        Path staging = key.resolve(".assigning");
        Path backup = temp.resolve("backup");
        copy(key, backup);
        String intact = Files.readString(key.resolve("events.jsonl"));
        for (boolean withLock : List.of(false, true)) {
            Files.createDirectories(staging);
            if (withLock) {
                Files.createFile(staging.resolve("register.lock"));
            }
            String ledgerHead = git("rev-parse", "HEAD");

            // the place of the project is taken
            Files.createDirectories(key.resolve("shop"));
            java.util.SortedMap<String, String> before = tree(key);
            Result taken = exec("findings", "arch", "assign", "--project", "shop");
            assertThat(taken.code()).as(taken.out() + taken.err()).isEqualTo(6);
            assertThat(taken.err()).contains("the project shop has a register of arch already");
            assertThat(tree(key)).as("a taken place, with a lock in the staging folder: " + withLock)
                    .isEqualTo(before);
            Files.delete(key.resolve("shop"));

            // the register does not verify
            Files.writeString(key.resolve("events.jsonl"), intact.replace("\"seq\":4", "\"seq\":4,\"x\":1"));
            before = tree(key);
            Result broken = exec("findings", "arch", "assign", "--project", "shop");
            assertThat(broken.code()).as(broken.out() + broken.err()).isEqualTo(8);
            assertThat(tree(key)).as("a register that does not verify, with a lock in the staging folder: "
                    + withLock).isEqualTo(before);
            Files.writeString(key.resolve("events.jsonl"), intact);

            assertThat(git("rev-parse", "HEAD")).as("and nothing was anchored").isEqualTo(ledgerHead);
            remove(staging);
        }

        // the register was assigned already: a backup of the earlier folder is put back at the key
        assertThat(exec("findings", "arch", "assign", "--project", "shop").code()).isZero();
        copy(backup, key);
        for (boolean withLock : List.of(false, true)) {
            Files.createDirectories(staging);
            if (withLock) {
                Files.createFile(staging.resolve("register.lock"));
            }
            java.util.SortedMap<String, String> before = tree(key);
            String ledgerHead = git("rev-parse", "HEAD");

            Result once = exec("findings", "arch", "assign", "--project", "portal");

            assertThat(once.code()).as(once.out() + once.err()).isEqualTo(6);
            assertThat(once.err()).contains("was assigned to the project shop already").contains("remove it");
            assertThat(tree(key)).as("assigned once, with a lock in the staging folder: " + withLock
                    + ": the copy lies at the key as it was put back, and nothing lies in the staging folder")
                    .isEqualTo(before);
            assertThat(entries(key)).containsExactly(".assigning", "blobs", "events.jsonl", "register.lock",
                    "report.html", "shop", "state.json");
            assertThat(git("rev-parse", "HEAD")).isEqualTo(ledgerHead);
            remove(staging);
        }
    }

    /**
     * Reproduced 2026-11-21: a log at the key and a log an assignment had staged. The move replaced the staged log
     * (its {@code register.assigned} line was lost), and {@code blobs} at both places ended in the raw "Cannot
     * move … directory not empty". No move replaces a file or a folder: the assign is refused before anything
     * moves, names both paths, and loses no line.
     */
    @Test
    void aPartOfTheRegisterAtTheKeyAndInTheStagingFolder_refusesTheAssign_namesBothPaths_andLosesNoLine()
            throws Exception {
        Path key = home.resolve("registers/arch");
        Path staging = Files.createDirectories(key.resolve(".assigning"));
        String staged = Files.readString(key.resolve("events.jsonl")) + "{\"seq\":10,\"at\":"
                + "\"2026-11-21T00:00:00.000Z\",\"type\":\"register.assigned\",\"register\":\"arch\",\"prev\":\""
                + sha256(EarlierRegister.lines().getLast()) + "\",\"actor\":\"tester\",\"project\":\"shop\"}\n";
        Files.writeString(staging.resolve("events.jsonl"), staged);
        java.util.SortedMap<String, String> before = tree(key);

        Result twoLogs = exec("findings", "arch", "assign", "--project", "shop");

        assertThat(twoLogs.code()).as(twoLogs.out() + twoLogs.err()).isEqualTo(6);
        assertThat(twoLogs.err()).doesNotContain("internal error").contains("The register 'arch' cannot be assigned:"
                + " a part of the register arch lies in two places: " + key.resolve("events.jsonl") + " and "
                + staging.resolve("events.jsonl") + "; one of the two is a copy, and an assignment replaces"
                + " neither: remove the copy by hand");
        assertThat(twoLogs.out()).isEmpty();
        assertThat(tree(key)).as("no line was lost, and nothing moved").isEqualTo(before);
        assertThat(Files.readString(staging.resolve("events.jsonl"))).isEqualTo(staged);

        Files.delete(staging.resolve("events.jsonl"));
        Files.createDirectories(staging.resolve("blobs"));
        Files.writeString(staging.resolve("blobs").resolve("a".repeat(64)), "a blob of another state");
        before = tree(key);

        Result twoBlobs = exec("findings", "arch", "assign", "--project", "shop");

        assertThat(twoBlobs.code()).as(twoBlobs.out() + twoBlobs.err()).isEqualTo(6);
        assertThat(twoBlobs.err()).doesNotContain("internal error").doesNotContain("Cannot move")
                .contains("lies in two places: " + key.resolve("blobs") + " and " + staging.resolve("blobs"))
                .contains("remove the copy by hand");
        assertThat(tree(key)).isEqualTo(before);

        // The way out the refusal names
        remove(staging);
        Result assigned = exec("findings", "arch", "assign", "--project", "shop");
        assertThat(assigned.code()).as(assigned.out() + assigned.err()).isZero();
        assertThat(exec("findings", "arch", "verify").code()).isZero();
    }

    /**
     * Reproduced 2026-11-21: the first assignment was not anchored (the ledger could not be written), the folder
     * of its project could not be entered ({@code chmod 000}), and a backup of the earlier folder lay at the key.
     * The log of the project read as a log without lines, so the register counted as never assigned, and was
     * assigned to a second project. What cannot be asked is not "not assigned".
     */
    @Test
    void anAssign_whileTheProjectItWasAssignedToCannotBeEntered_andTheLedgerHoldsNoAnchorOfIt_isRefused()
            throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse("root".equals(System.getProperty("user.name")));
        Path key = home.resolve("registers/arch");
        Path backup = temp.resolve("backup");
        copy(key, backup);
        Path refs = home.resolve("ledger/.git/refs/heads");
        mode(refs, "r-x------");
        try {
            Result first = exec("findings", "arch", "assign", "--project", "shop");
            assertThat(first.code()).as("assigned, and not anchored: " + first.out() + first.err()).isEqualTo(6);
            assertThat(first.out()).startsWith("assigned the register arch to the project shop: ");
        } finally {
            mode(refs, "rwx------");
        }
        assertThat(git("ls-tree", "-r", "--name-only", "HEAD")).as("the ledger holds no anchor of that assignment")
                .doesNotContain("anchors/");
        copy(backup, key);
        String ledgerHead = git("rev-parse", "HEAD");
        mode(key.resolve("shop"), "---------");
        try {
            Result second = exec("findings", "arch", "assign", "--project", "portal");

            assertThat(second.code()).as(second.out() + second.err()).isEqualTo(6);
            assertThat(second.err()).doesNotContain("internal error").contains("The register 'arch' cannot be"
                    + " assigned to portal: the log of the register arch of shop cannot be read (Cannot read "
                    + key.resolve("shop/events.jsonl") + ": access denied), so it cannot be said whether the"
                    + " register from before registers had projects was assigned to a project already");
            assertThat(second.out()).isEmpty();
            assertThat(entries(key)).as("no folder of portal, nothing moved").containsExactly("blobs",
                    "events.jsonl", "register.lock", "report.html", "shop", "state.json");
            assertThat(Files.readAllBytes(key.resolve("events.jsonl"))).as("no line was added").isEqualTo(
                    Files.readAllBytes(backup.resolve("events.jsonl")));
            assertThat(Files.readAllBytes(key.resolve("state.json"))).isEqualTo(
                    Files.readAllBytes(backup.resolve("state.json")));
            assertThat(git("rev-parse", "HEAD")).as("nothing was anchored").isEqualTo(ledgerHead);
        } finally {
            mode(key.resolve("shop"), "rwx------");
        }
        Result now = exec("findings", "arch", "assign", "--project", "portal");
        assertThat(now.code()).isEqualTo(6);
        assertThat(now.err()).as("and once it can be read, it says that it was assigned")
                .contains("was assigned to the project shop already");
    }

    /** What the ledger said of the log before the assignment still holds it. */
    @Test
    void afterTheAssignment_aLineThatWasChangedBeforeIt_isFoundByTheAnchorsOfTheEarlierShape() throws IOException {
        exec("findings", "arch", "assign", "--project", "shop");
        Path log = home.resolve("registers/arch/shop/events.jsonl");
        Files.writeString(log, Files.readString(log).replace("\"note\":\"fix it after all\"", "\"note\":\"fine\""));

        Result verify = exec("findings", "arch", "verify", "--project", "shop");

        assertThat(verify.code()).isEqualTo(8);
        assertThat(verify.out()).contains("anchor    NOT ok")
                .contains("seq 9: the line is not the one the ledger anchors")
                .contains("register arch of shop does not verify");
    }

    /**
     * A declared folder ({@code projects} in {@code traceworks.json}) names no project for an assignment: nothing
     * but the option says which project the register was kept for.
     */
    @Test
    void assign_inADeclaredFolder_stillNeedsItsProject() throws IOException {
        Files.writeString(home.resolve("traceworks.json"), "{\"projects\": {\"shop\": \"" + work + "\"}}");

        Result withoutProject = exec("findings", "arch", "assign");

        assertThat(withoutProject.code()).isEqualTo(2);
        assertThat(withoutProject.err()).contains("findings arch assign needs --project KEY");
        assertThat(exec("findings", "arch", "list").err()).as("the register is still of no project").contains(HINT);
        assertThat(home.resolve("registers/arch/events.jsonl")).isRegularFile();
    }

    @Test
    void assign_needsItsProject_andRefusesWhatIsNotFromBefore() throws IOException {
        Result withoutProject = exec("findings", "arch", "assign");
        Result badKey = exec("findings", "arch", "assign", "--project", "../shop");
        Result unknown = exec("findings", "nothing", "assign", "--project", "shop");
        Result withFinding = exec("findings", "arch", "assign", "9b9a750d", "--project", "shop");

        assertThat(withoutProject.code()).isEqualTo(2);
        assertThat(withoutProject.err()).contains("findings arch assign needs --project KEY");
        assertThat(badKey.code()).isEqualTo(2);
        assertThat(badKey.err()).contains("[a-z0-9][a-z0-9._-]{0,127}");
        assertThat(unknown.code()).isEqualTo(2);
        assertThat(unknown.err()).contains("No register 'nothing'");
        assertThat(withFinding.code()).isEqualTo(2);
        assertThat(Files.readAllLines(home.resolve("registers/arch/events.jsonl"))).as("nothing was assigned")
                .hasSize(10);

        assertThat(exec("findings", "arch", "assign", "--project", "blobs").code()).isZero();
        assertThat(entries(home.resolve("registers/arch"))).as("any valid key, also the name of a folder it held")
                .containsExactly("blobs");
        assertThat(exec("findings", "arch", "list").code()).isZero();
        Result notFromBefore = exec("findings", "arch", "assign", "--project", "web");
        assertThat(notFromBefore.code()).isEqualTo(6);
        assertThat(notFromBefore.err()).contains("is not from before registers had projects").contains("blobs");
    }

    // ---- assign judges the register before it stamps it, and verify says what is wrong with it ----

    /** The one findings command besides assign that works on a register from before. */
    @Test
    void verify_worksOnARegisterFromBefore_andChangesNothing() throws IOException {
        Result verify = exec("findings", "arch", "verify");

        assertThat(verify.code()).as(verify.out() + verify.err()).isZero();
        assertThat(verify.out()).contains("chain     ok (10 events",
                "anchor    ok (seq 9 is the head the ledger holds, 2 anchors)", "verified");
        assertThat(entries(home.resolve("registers/arch"))).as("as that version left it").containsExactly("blobs",
                "events.jsonl", "register.lock", "report.html", "state.json");
        assertThat(exec("findings").out()).as("still not assigned").contains("not assigned to a project");
    }

    @Test
    void assign_isRefused_forARegisterThatDoesNotVerify_andVerifySaysWhatIsWrong() throws IOException {
        Path register = home.resolve("registers/arch");
        Path log = register.resolve("events.jsonl");
        String intact = Files.readString(log);
        List<String> lines = intact.lines().toList();
        java.util.Map<String, String> damaged = new java.util.LinkedHashMap<>();
        damaged.put(intact.replace("\"note\":\"fix it after all\"", "\"note\":\"fine\"") + "{\"seq\":",
                "the line is not the one the ledger anchors");
        damaged.put(String.join("\n", lines.subList(0, 4)) + "\n" + lines.get(4).replace("\"seq\":4", "\"seq\":4,"
                + "\"x\":1") + "\n" + String.join("\n", lines.subList(5, lines.size())) + "\n",
                "seq 5: prev does not match the hash of seq 4");
        damaged.put(String.join("\n", lines.subList(0, 9)) + "\nnot a line\n", "line 9 (after seq 8)");

        for (java.util.Map.Entry<String, String> damage : damaged.entrySet()) {
            Files.writeString(log, damage.getKey());
            boolean lastLine = damage.getValue().contains("the ledger anchors");

            Result assign = exec("findings", "arch", "assign", "--project", "shop");
            Result verify = exec("findings", "arch", "verify");

            if (lastLine) {
                // The chain of a log cannot say that its last line is the one that was written: the anchors can.
                // The register is assigned all the same, since a refusal would leave it without any way to a
                // project; the mismatch stays in the record, and the assignment says so in a paragraph of its
                // own, with the command that goes on reporting it
                assertThat(assign.code()).as(assign.out() + assign.err()).isZero();
                assertThat(assign.out()).startsWith("assigned the register arch to the project shop: ")
                        .contains("\n\nnote: the register does not match its anchors in the ledger, and it is"
                                + " assigned as it is: 'traceworks findings arch verify --project shop' says where,"
                                + " and goes on reporting it");
                assertThat(assign.err()).as("said once, where the assignment is said")
                        .doesNotContain("does not match its anchors");
                Result after = exec("findings", "arch", "verify", "--project", "shop");
                assertThat(after.code()).isEqualTo(8);
                assertThat(after.out()).contains(damage.getValue());
                // back to a register from before, for the next case
                try (Stream<Path> walk = Files.walk(register)) {
                    for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.delete(path);
                    }
                }
                EarlierRegister.writeTo(register);
                continue;
            }
            assertThat(assign.code()).as(damage.getValue() + ": " + assign.out() + assign.err()).isEqualTo(8);
            assertThat(assign.err()).doesNotContain("internal error")
                    .contains("The register 'arch' does not verify, so it is not assigned")
                    .contains(damage.getValue()).contains("'traceworks findings arch verify' says what is wrong");
            assertThat(Files.readString(log)).as("nothing was written, also no register.repaired")
                    .isEqualTo(damage.getKey());
            assertThat(entries(register)).as("and no lock of the assignment is left").containsExactly("blobs",
                    "events.jsonl", "register.lock", "report.html", "state.json");
            assertThat(verify.code()).as("the command the refusal names works: " + verify.err()).isEqualTo(8);
            assertThat(verify.out()).contains(damage.getValue()).contains("register arch does not verify");
        }

        Files.writeString(log, intact);
        Files.delete(register.resolve("blobs").resolve(EarlierRegister.blobs().keySet().iterator().next()));
        Result assign = exec("findings", "arch", "assign", "--project", "shop");
        assertThat(assign.code()).as(assign.out() + assign.err()).isEqualTo(8);
        assertThat(assign.err()).doesNotContain("internal error").contains("is deleted");
        assertThat(exec("findings", "arch", "verify").code()).isEqualTo(8);
        assertThat(Files.readString(log)).isEqualTo(intact);
    }

    /** An assignment that is refused for any reason leaves the folder as it was. */
    @Test
    void aRefusedAssign_leavesNoLockOfTheAssignment() throws IOException {
        Files.createDirectories(home.resolve("registers/arch/shop"));

        Result refused = exec("findings", "arch", "assign", "--project", "shop");

        assertThat(refused.code()).as(refused.out() + refused.err()).isEqualTo(6);
        assertThat(refused.err()).contains("the project shop has a register of arch already");
        assertThat(entries(home.resolve("registers/arch"))).containsExactly("blobs", "events.jsonl",
                "register.lock", "report.html", "shop", "state.json");
    }

    /**
     * Reproduced 2026-11-21: a refused assign created {@code register.lock} in a register from before that had
     * none. The lock is taken, since a writer of the version that left the register takes it too; it is not left.
     */
    @Test
    void aRefusedAssign_createsNoRegisterLock_whereThereWasNone() throws IOException {
        Files.delete(home.resolve("registers/arch/register.lock"));
        Files.createDirectories(home.resolve("registers/arch/shop"));

        Result taken = exec("findings", "arch", "assign", "--project", "shop");

        assertThat(taken.code()).as(taken.out() + taken.err()).isEqualTo(6);
        assertThat(entries(home.resolve("registers/arch"))).as("as it was").containsExactly("blobs", "events.jsonl",
                "report.html", "shop", "state.json");

        Path log = home.resolve("registers/arch/events.jsonl");
        Files.writeString(log, Files.readString(log).replace("\"seq\":4", "\"seq\":4,\"x\":1"));
        Result broken = exec("findings", "arch", "assign", "--project", "web");

        assertThat(broken.code()).as(broken.out() + broken.err()).isEqualTo(8);
        assertThat(entries(home.resolve("registers/arch"))).as("as it was").containsExactly("blobs", "events.jsonl",
                "report.html", "shop", "state.json");
    }

    /** An assignment that is made moves the lock it took with the register, as it moves one that was there. */
    @Test
    void anAssignOfARegisterWithoutALock_leavesTheRegisterOfTheProjectWithOne() throws IOException {
        Files.delete(home.resolve("registers/arch/register.lock"));

        Result assigned = exec("findings", "arch", "assign", "--project", "shop");

        assertThat(assigned.code()).as(assigned.out() + assigned.err()).isZero();
        assertThat(entries(home.resolve("registers/arch"))).containsExactly("shop");
        assertThat(entries(home.resolve("registers/arch/shop"))).containsExactly("blobs", "events.jsonl",
                "register.lock", "report.html", "state.json");
    }
}
