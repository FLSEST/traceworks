package traceworks.cli.main;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import traceworks.cli.RuntimeSource;

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
 * A register per workflow key and project, on the command line: {@code run --project KEY} feeds the register of
 * that project, and {@code findings <key> ... [--project KEY]} reads and decides in it. Audit runs on the host, in a
 * temporary home, each in a workspace of its own.
 */
class FindingsProjectCliTest {

    @TempDir
    Path temp;
    private Path home;

    private record Result(int code, String out, String err) {
    }

    @BeforeEach
    void directories() throws IOException {
        home = Files.createDirectories(temp.resolve("home"));
    }

    private Result exec(String... args) {
        return execIn(temp, args);
    }

    private Result execIn(Path work, String... args) {
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

    /**
     * The audit of {@code examples/audit-hello} in small: one finding per file of the workspace that has more than
     * three lines, by the name of the file.
     */
    private Path audit() throws IOException {
        String report = "set -euo pipefail\nfirst=1\nfindings=''\n"
                + "for f in *.txt; do [ -e \"$f\" ] || continue\n"
                + "  if [ \"$(wc -l < \"$f\")\" -gt 3 ]; then\n"
                + "    [ $first = 1 ] || findings=\"$findings,\"\n    first=0\n"
                + "    findings=\"$findings{\\\"ruleKey\\\":\\\"long-file\\\",\\\"subject\\\":\\\"$f\\\","
                + "\\\"severity\\\":\\\"LOW\\\",\\\"title\\\":\\\"$f is long\\\"}\"\n"
                + "  fi\ndone\n"
                + "printf '%s\\n' '# Report' '<!-- traceworks:audit-result:v1' "
                + "\"{\\\"snapshotComplete\\\":true,\\\"findings\\\":[$findings]}\" '-->'\n";
        Path dir = Files.createDirectories(temp.resolve("audit"));
        Files.writeString(dir.resolve("workflow.json"), "{\"id\":\"audit\",\"name\":\"Audit\",\"metadata\":{\"audit\":"
                + "{\"enabled\":true,\"key\":\"hello\",\"category\":\"example\",\"report_block\":\"report\"}},"
                + "\"blocks\":[{\"type\":\"shell\",\"name\":\"report\",\"config\":{\"command\":"
                + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(report) + "}}]}");
        return dir;
    }

    /** A workspace with one long file of this name. */
    private Path workspace(String name, String longFile) throws IOException {
        Path work = Files.createDirectories(temp.resolve(name));
        Files.writeString(work.resolve(longFile), "1\n2\n3\n4\n5\n");
        return work;
    }

    private Result run(Path work, String project) throws IOException {
        List<String> args = new ArrayList<>(List.of("run", audit().toString(), "--allow-host-execution", "--no-input",
                "--quiet"));
        if (project != null) {
            args.add("--project");
            args.add(project);
        }
        return execIn(work, args.toArray(String[]::new));
    }

    private List<String> entries(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(folder)) {
            return children.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    // ---- run --project ----

    /** The first case that was reproduced with one shared register: the run on B resolved the finding of A. */
    @Test
    void twoProjectsAndOnePackage_giveTwoRegisters_andTheRunOnBResolvesNothingOfA() throws IOException {
        Result onA = run(workspace("project-a", "a-long.txt"), "project-a");
        Result onB = run(workspace("project-b", "b-long.txt"), "project-b");

        assertThat(onA.code()).as(onA.out() + onA.err()).isZero();
        assertThat(onA.out()).contains("  register hello of project-a: 1 new, 0 resolved, 0 reopened, 1 open now");
        assertThat(onB.out()).contains("  register hello of project-b: 1 new, 0 resolved, 0 reopened, 1 open now");
        assertThat(entries(home.resolve("registers/hello"))).containsExactly("project-a", "project-b");
        assertThat(entries(home.resolve("registers/hello/project-a"))).contains("events.jsonl", "blobs", "state.json",
                "report.html", "register.lock");
        assertThat(entries(home.resolve("ledger/anchors/hello"))).containsExactly("project-a.jsonl",
                "project-b.jsonl");
        Result ofA = exec("findings", "hello", "list", "--project", "project-a");
        assertThat(ofA.out()).as("nothing changed in A").contains("OPEN").contains("a-long.txt")
                .doesNotContain("b-long.txt");
    }

    /** The second case: a finding of D was DISMISSED by a decision made for C. */
    @Test
    void aDismissalInOneProject_leavesTheSameFindingOfAnotherProjectOpen() throws IOException {
        run(workspace("project-c", "same.txt"), "project-c");
        String id = exec("findings", "hello", "list").out().substring(0, 8);
        Result dismissed = exec("findings", "hello", "dismiss", id, "--note", "not ours", "--project", "project-c");

        Result onD = run(workspace("project-d", "same.txt"), "project-d");

        assertThat(dismissed.code()).as(dismissed.err()).isZero();
        assertThat(onD.out()).contains("  register hello of project-d: 1 new, 0 resolved, 0 reopened, 1 open now");
        assertThat(exec("findings", "hello", "list", "--project", "project-d").out()).contains(id + "  OPEN");
        assertThat(exec("findings", "hello", "list", "--project", "project-c").out()).contains(id + "  DISMISSED");
    }

    @Test
    void anAuditRunWithoutAProject_isRefused_andNothingIsWritten() throws IOException {
        Result refused = run(workspace("project-a", "a-long.txt"), null);

        assertThat(refused.code()).as(refused.out() + refused.err()).isEqualTo(6);
        assertThat(refused.err()).contains("the register hello").contains("--project KEY");
        assertThat(entries(home.resolve("runs"))).as("no run folder").isEmpty();
        assertThat(home.resolve("ledger")).as("no ledger entry").doesNotExist();
        assertThat(home.resolve("registers")).as("no register folder").doesNotExist();
    }

    // ---- findings <key> <command> [--project KEY] ----

    @Test
    void withOneProject_findingsNeedNoOption_andTheOptionNamesTheSameRegister() throws IOException {
        run(workspace("shop", "a-long.txt"), "shop");

        Result plain = exec("findings", "hello", "list");
        Result named = exec("findings", "hello", "list", "--project", "shop");

        assertThat(plain.code()).as(plain.err()).isZero();
        assertThat(plain.out()).contains("a-long.txt");
        assertThat(named.code()).as(named.err()).isZero();
        assertThat(named.out()).isEqualTo(plain.out());
        String id = plain.out().substring(0, 8);
        assertThat(exec("findings", "hello", "show", id).out()).contains("Register  hello of shop");
        assertThat(exec("findings", "hello", "verify").out()).contains("verified");
        assertThat(exec("findings", "hello", "render").out().strip()).as("the line names the register it acted on")
                .isEqualTo("rendered the register hello of shop: "
                        + home.resolve("registers/hello/shop/report.html"));
    }

    @Test
    void withTwoProjects_everyFindingsCommandNeedsTheOption_andTheRefusalNamesThem() throws IOException {
        run(workspace("shop", "a-long.txt"), "shop");
        run(workspace("web", "b-long.txt"), "web");
        String id = exec("findings", "hello", "list", "--project", "web").out().substring(0, 8);

        for (String[] command : List.of(new String[] {"list"}, new String[] {"show", id}, new String[] {"items"},
                new String[] {"dismiss", id, "--note", "no"}, new String[] {"accept", id, "--note", "no"},
                new String[] {"reopen", id}, new String[] {"render"}, new String[] {"verify"})) {
            List<String> args = new ArrayList<>(List.of("findings", "hello"));
            args.addAll(List.of(command));

            Result refused = exec(args.toArray(String[]::new));

            assertThat(refused.code()).as(command[0] + ": " + refused.out() + refused.err()).isEqualTo(2);
            assertThat(refused.err()).as(command[0]).contains("'hello' has 2 projects").contains("shop, web")
                    .contains("--project KEY");
        }
        Result ofWeb = exec("findings", "hello", "list", "--project", "web");
        assertThat(ofWeb.code()).as(ofWeb.err()).isZero();
        assertThat(ofWeb.out()).as("nothing was decided").contains(id + "  OPEN").contains("b-long.txt")
                .doesNotContain("a-long.txt");
        assertThat(exec("findings", "hello", "verify", "--project", "shop").out()).contains("verified");
    }

    @Test
    void aProjectTheKeyDoesNotHave_andAKeyThatIsNoProjectKey_areUsageErrors() throws IOException {
        run(workspace("shop", "a-long.txt"), "shop");

        Result unknown = exec("findings", "hello", "list", "--project", "web");
        Result bad = exec("findings", "hello", "list", "--project", "../shop");
        Result withoutKey = exec("findings", "--project", "shop");

        assertThat(unknown.code()).isEqualTo(2);
        assertThat(unknown.err()).contains("No register 'hello' of the project 'web'").contains("shop");
        assertThat(bad.code()).isEqualTo(2);
        assertThat(bad.err()).contains("--project").contains("[a-z0-9][a-z0-9._-]{0,127}");
        assertThat(withoutKey.code()).isEqualTo(2);
        assertThat(withoutKey.err()).contains("--project");
        assertThat(entries(home.resolve("registers/hello"))).as("asking made no register").containsExactly("shop");
    }

    @Test
    void theOverview_listsEveryRegisterWithItsProject() throws IOException {
        run(workspace("shop", "a-long.txt"), "shop");
        run(workspace("web", "b-long.txt"), "web");
        String id = exec("findings", "hello", "list", "--project", "web").out().substring(0, 8);
        exec("findings", "hello", "dismiss", id, "--note", "not ours", "--project", "web");

        Result overview = exec("findings");

        assertThat(overview.code()).as(overview.err()).isZero();
        assertThat(overview.out().lines()).containsExactly("hello  shop  example  OPEN 1",
                "hello  web  example  DISMISSED 1");
    }

    // ---- hints name the project ----

    @Test
    void aHintToAFindingsCommand_namesTheProject_inAFormThatWorks() throws IOException {
        run(workspace("shop", "a-long.txt"), "shop");
        run(workspace("web", "b-long.txt"), "web");
        Path log = home.resolve("registers/hello/web/events.jsonl");
        Files.writeString(log, Files.readString(log) + "{\"seq\":9,\"garbage\":true}\n");

        Result refused = run(temp.resolve("web"), "web");

        assertThat(refused.code()).as(refused.out() + refused.err()).isEqualTo(6);
        assertThat(refused.err()).contains("the register hello of web cannot be read")
                .contains("'traceworks findings hello verify --project web' says what is wrong");
        Result verify = exec("findings", "hello", "verify", "--project", "web");
        assertThat(verify.code()).as("the hint is a command that works: " + verify.err()).isEqualTo(8);
        assertThat(verify.out()).contains("register hello of web does not verify");
        assertThat(run(temp.resolve("shop"), "shop").code()).as("the other project is not touched").isZero();
    }

    // ---- a register that cannot be used is a refusal, never an internal error ----

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

    private void assertRefusedWithTheCommandThatSaysMore(Result result, String project, String reason) {
        assertThat(result.code()).as(result.out() + result.err()).isEqualTo(6);
        assertThat(result.err()).doesNotContain("internal error")
                .contains("The register 'hello' of the project '" + project + "' cannot be used: ").contains(reason)
                .contains("'traceworks findings hello verify --project " + project + "' says what is wrong");
    }

    /** The folder of project shop copied to where the register of web would lie: its log names shop. */
    @Test
    void aCopiedFolder_isARefusalOfEveryFindingsCommand_andTheOverviewNamesItAndGoesOn() throws IOException {
        run(workspace("shop", "a-long.txt"), "shop");
        String id = exec("findings", "hello", "list").out().substring(0, 8);
        copy(home.resolve("registers/hello/shop"), home.resolve("registers/hello/web"));
        Files.createDirectories(home.resolve("registers/zeta/shop"));
        String copied = "names the project shop";

        for (String[] command : List.of(new String[] {"list"}, new String[] {"items"}, new String[] {"show", id},
                new String[] {"render"}, new String[] {"dismiss", id, "--note", "no"},
                new String[] {"accept", id, "--note", "no"}, new String[] {"reopen", id})) {
            List<String> args = new ArrayList<>(List.of("findings", "hello"));
            args.addAll(List.of(command));
            args.addAll(List.of("--project", "web"));
            assertRefusedWithTheCommandThatSaysMore(exec(args.toArray(String[]::new)), "web", copied);
        }
        Result verify = exec("findings", "hello", "verify", "--project", "web");
        assertThat(verify.code()).as(verify.out() + verify.err()).isEqualTo(8);
        assertThat(verify.out()).contains(copied).contains("register hello of web does not verify");

        Result overview = exec("findings");

        assertThat(overview.err()).doesNotContain("internal error");
        assertThat(overview.out().lines().toList()).as("one line for the register that cannot be used, and the"
                + " others as ever").hasSize(3);
        assertThat(overview.out().lines().toList().get(0)).isEqualTo("hello  shop  example  OPEN 1");
        assertThat(overview.out().lines().toList().get(1)).startsWith("hello  web  refused: The register 'hello' of the project 'web' cannot be used: ")
                .contains(copied).contains("'traceworks findings hello verify --project web' says what is wrong");
        assertThat(overview.out().lines().toList().get(2)).as("the keys after it are listed").startsWith("zeta  shop");
        assertThat(overview.code()).as("the list is finished, and a register in it was refused").isEqualTo(6);
    }

    @Test
    void aLogWithALineThatIsNone_andABlobThatIsGone_areRefusals_andVerifySaysWhatIsWrong() throws IOException {
        run(workspace("shop", "a-long.txt"), "shop");
        Path register = home.resolve("registers/hello/shop");
        String log = Files.readString(register.resolve("events.jsonl"));

        Files.writeString(register.resolve("events.jsonl"), "garbage\n" + log);
        assertRefusedWithTheCommandThatSaysMore(exec("findings", "hello", "list"), "shop", "");
        Result overview = exec("findings");
        assertThat(overview.err()).doesNotContain("internal error");
        assertThat(overview.out()).startsWith("hello  shop  refused: The register 'hello' of the project 'shop' cannot"
                + " be used: ");
        Result verify = exec("findings", "hello", "verify");
        assertThat(verify.code()).as(verify.out() + verify.err()).isEqualTo(8);

        Files.writeString(register.resolve("events.jsonl"), log);
        assertThat(exec("findings", "hello", "list").code()).isZero();
        try (Stream<Path> blobs = Files.list(register.resolve("blobs"))) {
            for (Path blob : blobs.toList()) {
                Files.delete(blob);
            }
        }
        assertRefusedWithTheCommandThatSaysMore(exec("findings", "hello", "list"), "shop", "is deleted");
        verify = exec("findings", "hello", "verify");
        assertThat(verify.code()).as(verify.out() + verify.err()).isEqualTo(8);
        assertThat(verify.out()).contains("is deleted");
    }

    /** A key that holds a register from before and the folder of a project: the overview says both and goes on. */
    @Test
    void aKeyWithARegisterFromBeforeAndAProjectFolder_isListedByTheOverview_whichGoesOn() throws IOException {
        run(workspace("shop", "a-long.txt"), "shop");
        Path key = home.resolve("registers/hello");
        Files.copy(key.resolve("shop/events.jsonl"), key.resolve("events.jsonl"));
        Files.createDirectories(home.resolve("registers/zeta/shop"));

        Result overview = exec("findings");

        assertThat(overview.err()).doesNotContain("internal error");
        List<String> lines = overview.out().lines().toList();
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).startsWith("hello  not assigned to a project");
        assertThat(lines.get(1)).startsWith("hello  shop  refused: ")
                .contains("is from before registers had projects");
        assertThat(lines.get(2)).startsWith("zeta  shop");
        assertThat(overview.code()).as("a register in the list was refused: " + overview.err()).isEqualTo(6);
    }

    // ---- a register the ledger knows and whose log is not there is gone, for every command ----

    private static void assertGone(Result result, String project) {
        assertThat(result.code()).as(result.out() + result.err()).isEqualTo(6);
        assertThat(result.err()).doesNotContain("internal error")
                .contains("The ledger holds anchors of the register hello of " + project + ", and its log is gone")
                .contains("Put the register's folder back, or feed another project key (--project KEY)")
                .contains("'traceworks verify' says what the ledger holds of it");
    }

    /**
     * Reproduced 2026-11-21: with the log cut to a fragment the run was refused, {@code render} then wrote
     * {@code register.repaired} at seq 0 and anchored it, and the next run began anew at seq 1; on an emptied log
     * {@code render} overwrote state.json and report.html, the overview printed {@code hello a -} and {@code list}
     * nothing, exit 0.
     */
    @Test
    void aRegisterWhoseLogIsAFragment_isGoneForEveryCommand_andNothingIsWritten() throws IOException {
        Path work = workspace("shop", "a-long.txt");
        run(work, "shop");
        String id = exec("findings", "hello", "list").out().substring(0, 8);
        Path register = home.resolve("registers/hello/shop");
        byte[] state = Files.readAllBytes(register.resolve("state.json"));
        byte[] view = Files.readAllBytes(register.resolve("report.html"));
        byte[] anchors = Files.readAllBytes(home.resolve("ledger/anchors/hello/shop.jsonl"));
        for (String left : List.of("{\"seq\":0,\"at\":\"2026", "")) {
            Files.writeString(register.resolve("events.jsonl"), left);

            for (String[] command : List.of(new String[] {"list"}, new String[] {"items"}, new String[] {"show", id},
                    new String[] {"render"}, new String[] {"dismiss", id, "--note", "no"},
                    new String[] {"accept", id, "--note", "no"}, new String[] {"reopen", id},
                    new String[] {"list", "--project", "shop"}, new String[] {"render", "--project", "shop"})) {
                List<String> args = new ArrayList<>(List.of("findings", "hello"));
                args.addAll(List.of(command));
                assertGone(exec(args.toArray(String[]::new)), "shop");
            }
            Result onIt = run(work, "shop");
            assertThat(onIt.code()).as(onIt.out() + onIt.err()).isEqualTo(6);
            assertThat(onIt.err()).contains("the ledger holds anchors of the register hello of shop, and its log is"
                    + " gone");
            Result overview = exec("findings");
            assertThat(overview.out()).startsWith("hello  shop  refused: The ledger holds anchors of the register"
                    + " hello of shop, and its log is gone");
            assertThat(overview.code()).as(overview.err()).isEqualTo(6);
            Result verify = exec("findings", "hello", "verify");
            assertThat(verify.code()).as("verify says what is wrong: " + verify.out()).isEqualTo(8);
            assertThat(verify.out()).contains("the ledger anchors this line");

            assertThat(Files.readString(register.resolve("events.jsonl"))).as("no line, also no note").isEqualTo(left);
            assertThat(Files.readAllBytes(register.resolve("state.json"))).isEqualTo(state);
            assertThat(Files.readAllBytes(register.resolve("report.html"))).isEqualTo(view);
            assertThat(Files.readAllBytes(home.resolve("ledger/anchors/hello/shop.jsonl"))).isEqualTo(anchors);
        }
    }

    /**
     * Reproduced 2026-11-21: with the whole folder of one project removed, {@code findings hello list} meant the
     * other project and said nothing.
     */
    @Test
    void aProjectWhoseWholeFolderWasRemoved_stillCounts_andIsGone() throws IOException {
        run(workspace("shop", "a-long.txt"), "shop");
        run(workspace("web", "b-long.txt"), "web");
        delete(home.resolve("registers/hello/shop"));

        Result unnamed = exec("findings", "hello", "list");
        Result ofShop = exec("findings", "hello", "list", "--project", "shop");
        Result render = exec("findings", "hello", "render", "--project", "shop");
        Result overview = exec("findings");

        assertThat(unnamed.code()).as(unnamed.out() + unnamed.err()).isEqualTo(2);
        assertThat(unnamed.err()).contains("has 2 projects").contains("shop").contains("web");
        assertGone(ofShop, "shop");
        assertGone(render, "shop");
        assertThat(overview.out().lines().toList()).hasSize(2);
        assertThat(overview.out().lines().toList().get(0)).startsWith("hello  shop  refused: The ledger holds anchors"
                + " of the register hello of shop, and its log is gone");
        assertThat(overview.out().lines().toList().get(1)).isEqualTo("hello  web  example  OPEN 1");
        assertThat(overview.code()).as("a register in the list is gone: " + overview.err()).isEqualTo(6);
        assertThat(entries(home.resolve("registers/hello"))).as("no folder was made for it").containsExactly("web");

        delete(home.resolve("registers/hello"));

        Result ofAGoneKey = exec("findings", "hello", "list", "--project", "web");
        assertGone(ofAGoneKey, "web");
        Result ofGoneFolders = exec("findings");
        assertThat(ofGoneFolders.out().lines().toList()).as("the ledger still names both").hasSize(2)
                .allSatisfy(line -> assertThat(line).contains("  refused: ").contains("its log is gone"));
        assertThat(ofGoneFolders.code()).isEqualTo(6);
        assertThat(home.resolve("registers/hello")).doesNotExist();
    }

    /**
     * Reproduced 2026-11-21: with the folder of one project removed and the ledger damaged (its {@code .git}
     * without a HEAD), the overview listed the other project alone, exit 0, and a {@code dismiss} without
     * {@code --project} went to it. A ledger that is there and cannot be listed does not shrink the projects of a
     * key.
     */
    @Test
    void aLedgerThatCannotBeListed_doesNotShrinkTheProjectsOfAKey() throws IOException {
        run(workspace("shop", "a-long.txt"), "shop");
        run(workspace("web", "b-long.txt"), "web");
        String ofWeb = exec("findings", "hello", "list", "--project", "web").out().substring(0, 8);
        delete(home.resolve("registers/hello/shop"));
        Files.delete(home.resolve("ledger/.git/HEAD"));
        byte[] logOfWeb = Files.readAllBytes(home.resolve("registers/hello/web/events.jsonl"));

        for (String[] command : List.of(new String[] {"list"}, new String[] {}, new String[] {"items"},
                new String[] {"show", ofWeb}, new String[] {"dismiss", ofWeb, "--note", "no"},
                new String[] {"accept", ofWeb, "--note", "no"}, new String[] {"reopen", ofWeb},
                new String[] {"render"}, new String[] {"verify"})) {
            List<String> args = new ArrayList<>(List.of("findings", "hello"));
            args.addAll(List.of(command));

            Result refused = exec(args.toArray(String[]::new));

            assertThat(refused.code()).as(String.join(" ", command) + ": " + refused.out() + refused.err())
                    .isEqualTo(6);
            assertThat(refused.err()).as(String.join(" ", command)).doesNotContain("internal error")
                    .contains("The ledger cannot say which projects the register 'hello' has (")
                    .contains("name the project with --project KEY");
            assertThat(refused.out()).isEmpty();
        }
        assertThat(Files.readAllBytes(home.resolve("registers/hello/web/events.jsonl")))
                .as("no decision went to the other project").isEqualTo(logOfWeb);

        Result ofWebNamed = exec("findings", "hello", "list", "--project", "web");
        Result ofShop = exec("findings", "hello", "list", "--project", "shop");
        Result overview = exec("findings");

        assertThat(ofWebNamed.code()).as("a register whose log has begun is used as before: " + ofWebNamed.err())
                .isZero();
        assertThat(ofWebNamed.out()).contains("b-long.txt");
        assertThat(ofShop.code()).as(ofShop.out() + ofShop.err()).isEqualTo(6);
        assertThat(ofShop.err()).contains("This home holds no register 'hello' of the project 'shop', and the ledger"
                + " cannot say whether it anchors one (");
        assertThat(overview.code()).as(overview.out() + overview.err()).isEqualTo(6);
        List<String> lines = overview.out().lines().toList();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).isEqualTo("hello  web  example  OPEN 1");
        assertThat(lines.get(1)).startsWith("hello  refused: the ledger cannot say which projects the key has (")
                .contains("a project whose folder is gone is not listed here");
        assertThat(entries(home.resolve("registers/hello"))).containsExactly("web");
    }

    /**
     * Reproduced 2026-11-21: one register with a blob edited in place ended the overview after it (exit 8, the
     * registers after it not listed), with a hint that named no register. The overview prints one line for it,
     * with the reason and the verify command of that register, finishes the list, and exits 8.
     */
    @Test
    void aChangedBlob_doesNotEndTheOverview_whichListsItWithItsVerifyCommand_andExits8() throws IOException {
        run(workspace("portal", "a-long.txt"), "portal");
        run(workspace("shop", "b-long.txt"), "shop");
        run(workspace("web", "c-long.txt"), "web");
        Path portal = home.resolve("registers/hello/portal");
        java.util.regex.Matcher blob = java.util.regex.Pattern.compile("\"findings_blob\":\"([0-9a-f]{64})\"")
                .matcher(Files.readString(portal.resolve("events.jsonl")));
        assertThat(blob.find()).isTrue();
        mode(portal.resolve("blobs").resolve(blob.group(1)), "rw-------");
        Files.writeString(portal.resolve("blobs").resolve(blob.group(1)), "[]");

        Result overview = exec("findings");
        Result list = exec("findings", "hello", "list", "--project", "portal");

        assertThat(overview.err()).doesNotContain("internal error");
        List<String> lines = overview.out().lines().toList();
        assertThat(lines).as(overview.out() + overview.err()).hasSize(3);
        assertThat(lines.get(0)).startsWith("hello  portal  damaged: The blob " + blob.group(1) + " holds other"
                + " content than its name says").endsWith("check the register with 'traceworks findings hello verify"
                + " --project portal'");
        assertThat(lines.get(1)).as("it goes on").isEqualTo("hello  shop  example  OPEN 1");
        assertThat(lines.get(2)).isEqualTo("hello  web  example  OPEN 1");
        assertThat(overview.code()).as("a register in the list has a changed blob").isEqualTo(8);
        assertThat(list.code()).isEqualTo(8);
        assertThat(list.err()).contains("check the register with 'traceworks findings hello verify --project"
                + " portal'").doesNotContain("<key>").doesNotContain("<project>");

        // A refused register beside it: a changed blob is the exit code all the same
        Files.writeString(home.resolve("registers/hello/shop/events.jsonl"), "");
        Result both = exec("findings");
        assertThat(both.out().lines().toList()).hasSize(3);
        assertThat(both.out().lines().toList().get(1)).startsWith("hello  shop  refused: ");
        assertThat(both.code()).isEqualTo(8);
    }

    /**
     * Reproduced 2026-11-21: a folder {@code .assigning} that held a lock alone made the key a register from
     * before: the overview said "not assigned" and refused both projects, every command on them was refused, and
     * {@code assign} said that the log had not begun and left the folder. It is nothing, and the next assign
     * removes it.
     */
    @Test
    void aStagingFolderWithoutALog_isNothing_andTheNextAssignRemovesIt() throws IOException {
        run(workspace("shop", "a-long.txt"), "shop");
        run(workspace("web", "b-long.txt"), "web");
        Path staging = Files.createDirectories(home.resolve("registers/hello/.assigning"));
        Files.createFile(staging.resolve("register.lock"));

        Result overview = exec("findings");
        Result list = exec("findings", "hello", "list", "--project", "shop");
        Result verify = exec("verify");

        assertThat(overview.code()).as(overview.out() + overview.err()).isZero();
        assertThat(overview.out()).isEqualTo("hello  shop  example  OPEN 1\nhello  web  example  OPEN 1\n");
        assertThat(list.code()).as(list.err()).isZero();
        assertThat(list.out()).contains("a-long.txt");
        assertThat(verify.code()).as(verify.out()).isZero();
        assertThat(run(workspace("shop", "a-long.txt"), "shop").code()).as("a run on a project of the key").isZero();
        assertThat(entries(home.resolve("registers/hello"))).as("a reader removes nothing").containsExactly(
                ".assigning", "shop", "web");

        Result assign = exec("findings", "hello", "assign", "--project", "zz");

        assertThat(assign.code()).as(assign.out() + assign.err()).isEqualTo(6);
        assertThat(assign.err()).contains("The register 'hello' is not from before registers had projects, so there"
                + " is nothing to assign");
        assertThat(entries(home.resolve("registers/hello"))).as("the next assign removed it").containsExactly("shop",
                "web");
    }

    /**
     * Reproduced 2026-11-21: {@code assign --project shop} on a home whose register of shop is a project's, with a
     * fragment at the end of its log, was refused (exit 6) and had written {@code register.repaired} into the log.
     * A refused assign changes no byte of a project's register.
     */
    @Test
    void aRefusedAssign_onAProjectsRegisterWhoseLogEndsInAFragment_changesNoByteOfIt() throws Exception {
        run(workspace("shop", "a-long.txt"), "shop");
        Path register = home.resolve("registers/hello/shop");
        Files.writeString(register.resolve("events.jsonl"), Files.readString(register.resolve("events.jsonl"))
                + "{\"seq\":9,\"type\":\"fin");
        byte[] log = Files.readAllBytes(register.resolve("events.jsonl"));
        byte[] state = Files.readAllBytes(register.resolve("state.json"));
        byte[] view = Files.readAllBytes(register.resolve("report.html"));
        List<String> entries = entries(register);
        String ledgerHead = git("rev-parse", "HEAD");

        for (String project : List.of("shop", "web")) {
            Result refused = exec("findings", "hello", "assign", "--project", project);

            assertThat(refused.code()).as(project + ": " + refused.out() + refused.err()).isEqualTo(6);
            assertThat(refused.err()).contains("The register 'hello' is not from before registers had projects, so"
                    + " there is nothing to assign: it is kept for shop");
        }

        assertThat(Files.readAllBytes(register.resolve("events.jsonl"))).as("no register.repaired, no byte changed")
                .isEqualTo(log);
        assertThat(Files.readAllBytes(register.resolve("state.json"))).isEqualTo(state);
        assertThat(Files.readAllBytes(register.resolve("report.html"))).isEqualTo(view);
        assertThat(entries(register)).isEqualTo(entries);
        assertThat(entries(home.resolve("registers/hello"))).containsExactly("shop");
        assertThat(git("rev-parse", "HEAD")).as("and nothing was anchored").isEqualTo(ledgerHead);
    }

    /**
     * Reproduced 2026-11-21: the check listed a log that cannot be read as {@code Cannot read <file>} and no more.
     * The sentence holds the reason, so an acknowledgement covers that reason only.
     */
    @Test
    void aLogThatCannotBeRead_saysWhyInTheCheck_andAnAcknowledgementCoversThatReasonOnly() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse("root".equals(System.getProperty("user.name")));
        run(workspace("shop", "a-long.txt"), "shop");
        Path log = home.resolve("registers/hello/shop/events.jsonl");
        byte[] lines = Files.readAllBytes(log);
        Files.delete(log);
        Files.createDirectories(log);

        Result folder = exec("verify");

        assertThat(folder.code()).as(folder.out() + folder.err()).isEqualTo(8);
        assertThat(folder.out()).contains("  anchors.5.hello.shop  the register cannot be read: Cannot read " + log
                + ": a folder is in its place\n");
        Result acknowledged = exec("verify", "--acknowledge", "anchors.5.hello.shop", "--note", "looked at");
        assertThat(acknowledged.code()).as(acknowledged.out() + acknowledged.err()).isZero();
        assertThat(exec("verify").code()).as("the same reason stays acknowledged").isZero();

        Files.delete(log);
        Files.write(log, lines);
        mode(log, "---------");
        try {
            Result denied = exec("verify");

            assertThat(denied.code()).as("another reason is another problem: " + denied.out()).isEqualTo(8);
            assertThat(denied.out()).contains("  anchors.5.hello.shop  the register cannot be read: Cannot read "
                    + log + ": access denied\n");
        } finally {
            mode(log, "rw-------");
        }
    }

    // ---- no internal error from a register ----

    /**
     * Reproduced 2026-11-21: a line whose time cannot be parsed was {@code internal error: DateTimeParseException},
     * exit 70, for the overview, list, show, dismiss and render, and the overview did not go on.
     */
    @Test
    void aLineWhoseTimeCannotBeParsed_isARefusal_andTheOverviewFinishesItsListAndExits6() throws IOException {
        run(workspace("shop", "a-long.txt"), "shop");
        run(workspace("web", "b-long.txt"), "web");
        String id = exec("findings", "hello", "list", "--project", "shop").out().substring(0, 8);
        Path register = home.resolve("registers/hello/shop");
        Path log = register.resolve("events.jsonl");
        // The line with the bad time, and every line after it chained to the line before it again: the chain
        // holds, and what is wrong is the time alone
        StringBuilder chained = new StringBuilder();
        String before = null;
        for (String line : Files.readAllLines(log)) {
            String now = line.replace("\"finished_at\":\"", "\"finished_at\":\"not a time ");
            if (before != null) {
                now = now.replaceFirst("\"prev\":\"[0-9a-f]{64}\"", "\"prev\":\"" + sha256(before) + "\"");
            }
            chained.append(now).append('\n');
            before = now;
        }
        Files.writeString(log, chained);
        assertThat(exec("findings", "hello", "verify", "--project", "shop").out()).as("the chain holds")
                .doesNotContain("prev does not match");
        byte[] logBytes = Files.readAllBytes(log);
        byte[] state = Files.readAllBytes(register.resolve("state.json"));
        byte[] view = Files.readAllBytes(register.resolve("report.html"));
        byte[] anchors = Files.readAllBytes(home.resolve("ledger/anchors/hello/shop.jsonl"));

        for (String[] command : List.of(new String[] {"list"}, new String[] {"show", id}, new String[] {"items"},
                new String[] {"render"}, new String[] {"dismiss", id, "--note", "no"},
                new String[] {"accept", id, "--note", "no"}, new String[] {"reopen", id})) {
            List<String> args = new ArrayList<>(List.of("findings", "hello"));
            args.addAll(List.of(command));
            args.addAll(List.of("--project", "shop"));
            assertRefusedWithTheCommandThatSaysMore(exec(args.toArray(String[]::new)), "shop", "not a time");
        }
        assertThat(Files.readAllBytes(log)).as("nothing was written: the log is byte for byte what it was")
                .isEqualTo(logBytes);
        assertThat(Files.readAllBytes(register.resolve("state.json"))).isEqualTo(state);
        assertThat(Files.readAllBytes(register.resolve("report.html"))).isEqualTo(view);
        assertThat(Files.readAllBytes(home.resolve("ledger/anchors/hello/shop.jsonl"))).isEqualTo(anchors);
        Result overview = exec("findings");

        assertThat(overview.err()).doesNotContain("internal error");
        assertThat(overview.out().lines().toList()).hasSize(2);
        assertThat(overview.out().lines().toList().get(0)).startsWith("hello  shop  refused: The register 'hello' of"
                + " the project 'shop' cannot be used: ");
        assertThat(overview.out().lines().toList().get(1)).as("it goes on").isEqualTo("hello  web  example  OPEN 1");
        assertThat(overview.code()).as("and then says that a register was refused").isEqualTo(6);
        Result verify = exec("verify");
        assertThat(verify.code()).as(verify.out() + verify.err()).isEqualTo(8);
        assertThat(verify.err()).doesNotContain("internal error");
    }

    private static String sha256(String line) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(line.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void mode(Path path, String permissions) throws IOException {
        Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString(permissions));
    }

    /**
     * Reproduced 2026-11-21: a key folder that cannot be listed was exit 70 for {@code verify}, {@code findings}
     * and {@code findings hello list}, and with the registers folder itself unlistable {@code run} was exit 70.
     */
    @Test
    void aFolderThatCannotBeListed_isARefusal_andAProblemOfTheCheck() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeFalse("root".equals(System.getProperty("user.name")));
        Path work = workspace("shop", "a-long.txt");
        run(work, "shop");
        Files.createDirectories(home.resolve("registers/zeta/shop"));
        Path key = home.resolve("registers/hello");
        mode(key, "---------");
        try {
            Result verify = exec("verify");
            Result overview = exec("findings");
            Result list = exec("findings", "hello", "list");
            Result onIt = run(work, "shop");

            for (Result result : List.of(verify, overview, list, onIt)) {
                assertThat(result.err()).as(result.out() + result.err()).doesNotContain("internal error");
            }
            assertThat(verify.code()).as(verify.out() + verify.err()).isEqualTo(8);
            assertThat(verify.out()).contains("registers.hello  the registers of the key hello cannot be listed: ");
            assertThat(overview.code()).as(overview.out() + overview.err()).isEqualTo(6);
            assertThat(overview.out().lines().toList()).hasSize(2);
            assertThat(overview.out().lines().toList().get(0)).startsWith("hello  refused: The registers cannot be"
                    + " listed: ");
            assertThat(overview.out().lines().toList().get(1)).startsWith("zeta  shop");
            assertThat(list.code()).as(list.out() + list.err()).isEqualTo(6);
            assertThat(list.err()).contains("The registers cannot be listed: ");
            assertThat(onIt.code()).as(onIt.out() + onIt.err()).isEqualTo(6);
        } finally {
            mode(key, "rwx------");
        }
        Path registers = home.resolve("registers");
        mode(registers, "---------");
        try {
            Result onIt = run(work, "shop");
            Result overview = exec("findings");
            Result verify = exec("verify");

            assertThat(onIt.code()).as(onIt.out() + onIt.err()).isEqualTo(6);
            assertThat(onIt.err()).doesNotContain("internal error").contains("Cannot list the registers in");
            assertThat(overview.code()).as(overview.out() + overview.err()).isEqualTo(6);
            assertThat(overview.err()).doesNotContain("internal error").contains("The registers cannot be listed: ");
            assertThat(verify.code()).as(verify.out() + verify.err()).isEqualTo(8);
            assertThat(verify.err()).doesNotContain("internal error")
                    .contains("The registers of this home cannot be listed");
        } finally {
            mode(registers, "rwx------");
        }
    }

    /**
     * Reproduced 2026-11-21: {@code dismiss} with a read-only {@code events.jsonl} was exit 70, and {@code dismiss}
     * and {@code render} with a read-only folder were exit 70 after their line was appended.
     */
    @Test
    void aWriteThatFails_isARefusal_andSaysWhatIsRecordedAndWhatIsNot() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeFalse("root".equals(System.getProperty("user.name")));
        run(workspace("shop", "a-long.txt"), "shop");
        String id = exec("findings", "hello", "list").out().substring(0, 8);
        Path register = home.resolve("registers/hello/shop");
        Path log = register.resolve("events.jsonl");
        long lines = Files.readAllLines(log).size();
        mode(log, "r--------");
        try {
            Result dismissed = exec("findings", "hello", "dismiss", id, "--note", "not ours");

            assertThat(dismissed.code()).as(dismissed.out() + dismissed.err()).isEqualTo(6);
            assertThat(dismissed.err()).doesNotContain("internal error").contains("The register 'hello' of the"
                    + " project 'shop' cannot be written, and the decision is not recorded: ");
            assertThat(dismissed.out()).as("no confirmation").isEmpty();
            assertThat(Files.readAllLines(log)).hasSize((int) lines);
        } finally {
            mode(log, "rw-------");
        }
        byte[] state = Files.readAllBytes(register.resolve("state.json"));
        mode(register, "r-x------");
        try {
            Result render = exec("findings", "hello", "render");

            assertThat(render.code()).as(render.out() + render.err()).isEqualTo(6);
            assertThat(render.err()).doesNotContain("internal error").contains("The register 'hello' of the project"
                    + " 'shop' is not rendered: ").contains("state.json or report.html of the register could not be"
                    + " written");
            assertThat(render.out()).as("no path of a file that was not written").isEmpty();

            Result dismissed = exec("findings", "hello", "dismiss", id, "--note", "not ours");

            assertThat(dismissed.code()).as(dismissed.out() + dismissed.err()).isEqualTo(6);
            assertThat(dismissed.out()).as("the decision is recorded, and the command says so")
                    .contains("dismissed " + id);
            assertThat(dismissed.err()).doesNotContain("internal error")
                    .contains("traceworks: warning: the decision is recorded in the log of the register, but"
                            + " state.json or report.html of the register could not be written (")
                    .contains("'traceworks findings hello render --project shop' writes them once they can be"
                            + " written");
            assertThat(Files.readAllLines(log)).as("its line").hasSize((int) lines + 1);
            assertThat(Files.readAllBytes(register.resolve("state.json"))).as("the file is as it was")
                    .isEqualTo(state);
        } finally {
            mode(register, "rwx------");
        }
        assertThat(exec("findings", "hello", "list").out()).contains("DISMISSED");
        Result render = exec("findings", "hello", "render");
        assertThat(render.code()).as(render.out() + render.err()).isZero();
        assertThat(exec("findings", "hello", "verify").code()).isZero();
    }

    // ---- verify without a run checks every register ----

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

    private static void delete(Path folder) throws IOException {
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    /**
     * Reproduced 2026-11-21: for each of these the check of the whole home printed "verified", exit 0. A copied
     * folder whose log names the other project; the anchor line of one register appended to the anchors of another
     * by a well-formed commit; a register whose log, or whose folder, was removed while the ledger anchors it.
     */
    @Test
    void theCheckOfTheHome_checksEveryRegister_andListsEachProblemUnderTheNameOfItsRegister() throws Exception {
        run(workspace("shop", "a-long.txt"), "shop");
        run(workspace("web", "b-long.txt"), "web");
        run(temp.resolve("shop"), "shop");
        Result intact = exec("verify");
        assertThat(intact.code()).as(intact.out() + intact.err()).isZero();

        // a copied folder
        copy(home.resolve("registers/hello/shop"), home.resolve("registers/hello/copy"));
        Result copied = exec("verify");
        assertThat(copied.code()).as(copied.out()).isEqualTo(8);
        assertThat(copied.out()).contains("problems (1)")
                .contains("anchors.5.hello.copy  the log of the register hello of copy names the project shop")
                .contains("verification FAILED");
        Result quick = exec("verify", "--quick");
        assertThat(quick.code()).as("--quick skips the registers as it skips the records: " + quick.out()).isZero();
        assertThat(quick.out()).contains("registers not verified (--quick)");
        delete(home.resolve("registers/hello/copy"));
        assertThat(exec("verify").code()).isZero();

        // the last anchor of shop, appended to the anchors of web by a commit as Traceworks writes them
        List<String> ofShop = Files.readAllLines(home.resolve("ledger/anchors/hello/shop.jsonl"));
        String ofWeb = Files.readString(home.resolve("ledger/anchors/hello/web.jsonl")) + ofShop.getLast() + "\n";
        Files.writeString(home.resolve("ledger/anchors/hello/web.jsonl"), ofWeb);
        git("commit", "-q", "-m", "anchor hello/web seq 9 sha256:" + java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(ofWeb.getBytes(StandardCharsets.UTF_8))),
                "--", "anchors/hello/web.jsonl");
        Result foreign = exec("verify");
        assertThat(foreign.code()).as(foreign.out()).isEqualTo(8);
        assertThat(foreign.out()).contains("anchors.5.hello.web  seq ").contains("the ledger anchors this line")
                .doesNotContain("anchors.5.hello.shop  ");
        Result ofTheRegister = exec("findings", "hello", "verify", "--project", "web");
        assertThat(ofTheRegister.code()).as("as findings verify says of that register").isEqualTo(8);

        // a register whose log is gone, and one whose folder is gone
        Files.delete(home.resolve("registers/hello/shop/events.jsonl"));
        delete(home.resolve("registers/hello/web"));
        Result gone = exec("verify");
        assertThat(gone.code()).as(gone.out()).isEqualTo(8);
        assertThat(gone.out()).contains("anchors.5.hello.shop  seq ").contains("but the log is empty")
                .contains("anchors.5.hello.web  the ledger anchors the register hello of web, and this home holds"
                        + " no such register");

        // Neither is begun anew: a run on such a project is refused at its start, and names the check
        for (String project : List.of("shop", "web")) {
            Result refused = run(temp.resolve(project), project);
            assertThat(refused.code()).as(refused.out() + refused.err()).isEqualTo(6);
            assertThat(refused.err()).contains("the ledger holds anchors of the register hello of " + project)
                    .contains("its log is gone").contains("'traceworks verify'");
        }
        assertThat(home.resolve("registers/hello/shop/events.jsonl")).doesNotExist();
        assertThat(home.resolve("registers/hello/web")).doesNotExist();
    }
}
