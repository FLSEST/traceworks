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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The projects of a home ({@code projects} in {@code traceworks.json}), on the command line: the folder a run works
 * in names its project, a run with another key than its folder says is refused before it exists, and
 * {@code findings <key>} in a declared folder takes that project. Host runs of real shell steps in a temporary home
 * that declares {@code shop}, {@code web} and {@code ghost}, whose folder does not exist.
 */
class ProjectsSettingCliTest {

    private static final Pattern RUN_ID = Pattern.compile("(?m)^run ([0-9a-f-]{36}) [A-Z_]+$");

    @TempDir
    Path temp;
    private Path home;
    private Path shop;
    private Path web;
    private Path other;

    private record Result(int code, String out, String err) {
    }

    /** How a run is told the folder it works in. */
    private enum By { WORKSPACE_OPTION, WORKING_DIRECTORY, SOURCE_PATH, SOURCE_FILE_URL }

    @BeforeEach
    void aHomeWithProjects() throws Exception {
        home = Files.createDirectories(temp.resolve("home"));
        shop = repository("shop", "a-long.txt");
        web = repository("web", "b-long.txt");
        other = repository("other", "c-long.txt");
        declare("{\"shop\": \"" + shop + "\", \"web\": \"" + web + "\", \"ghost\": \"" + temp.resolve("ghost")
                + "\"}");
    }

    private void declare(String projects) throws IOException {
        Files.writeString(home.resolve("traceworks.json"), "{\"projects\": " + projects + "}");
    }

    /** A folder that is a git repository with one long file, so that it can be a workspace and a source. */
    private Path repository(String name, String longFile) throws Exception {
        Path repo = Files.createDirectories(temp.resolve(name));
        Files.writeString(repo.resolve(longFile), "1\n2\n3\n4\n5\n");
        git(repo, "init", "-q");
        git(repo, "add", ".");
        git(repo, "commit", "-q", "-m", "init");
        return repo;
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString(), "-c", "user.name=t", "-c",
                "user.email=t@t.invalid", "-c", "commit.gpgsign=false", "-c", "init.defaultBranch=main"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().keySet().removeIf(name -> name.startsWith("GIT_"));
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("git " + String.join(" ", args) + ": " + output).isZero();
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

    private Path plain() throws IOException {
        Path dir = Files.createDirectories(temp.resolve("plain"));
        Files.writeString(dir.resolve("workflow.json"), "{\"id\":\"plain\",\"name\":\"Plain\",\"blocks\":["
                + "{\"type\":\"shell\",\"name\":\"say\",\"config\":{\"command\":\"echo hello\"}}]}");
        return dir;
    }

    /** One finding per file of the workspace that has more than three lines, by the name of the file. */
    private static final String REPORT = "set -euo pipefail\nfirst=1\nfindings=''\n"
            + "for f in *.txt; do [ -e \"$f\" ] || continue\n"
            + "  if [ \"$(wc -l < \"$f\")\" -gt 3 ]; then\n"
            + "    [ $first = 1 ] || findings=\"$findings,\"\n    first=0\n"
            + "    findings=\"$findings{\\\"ruleKey\\\":\\\"long-file\\\",\\\"subject\\\":\\\"$f\\\","
            + "\\\"severity\\\":\\\"LOW\\\",\\\"title\\\":\\\"$f is long\\\"}\"\n"
            + "  fi\ndone\n"
            + "printf '%s\\n' '# Report' '<!-- traceworks:audit-result:v1' "
            + "\"{\\\"snapshotComplete\\\":true,\\\"findings\\\":[$findings]}\" '-->'\n";

    /** The audit of {@code examples/audit-hello} in small, with the register {@code hello}. */
    private Path audit() throws IOException {
        return audit("audit", "");
    }

    /** The same audit behind a question, so that its run parks before the report. */
    private Path askingAudit() throws IOException {
        return audit("asking-audit", "{\"type\":\"approval\",\"name\":\"go\",\"config\":{\"question\":\"Go on?\","
                + "\"options\":[\"Yes\",\"No\"]}},");
    }

    private Path audit(String name, String blocksBefore) throws IOException {
        Path dir = Files.createDirectories(temp.resolve(name));
        Files.writeString(dir.resolve("workflow.json"), "{\"id\":\"audit\",\"name\":\"Audit\",\"metadata\":{\"audit\":"
                + "{\"enabled\":true,\"key\":\"hello\",\"category\":\"example\",\"report_block\":\"report\"}},"
                + "\"blocks\":[" + blocksBefore + "{\"type\":\"shell\",\"name\":\"report\",\"config\":{\"command\":"
                + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(REPORT) + "}}]}");
        return dir;
    }

    /** A run of {@code pkg} that works in {@code folder}, told {@code by}; {@code project} is null for no option. */
    private Result run(Path pkg, By by, Path folder, String project) {
        List<String> args = new ArrayList<>(List.of("run", pkg.toString(), "--allow-host-execution", "--no-input",
                "--quiet"));
        if (by == By.WORKSPACE_OPTION) {
            args.addAll(List.of("--workspace", folder.toString()));
        } else if (by == By.SOURCE_PATH) {
            args.addAll(List.of("--source", folder.toString()));
        } else if (by == By.SOURCE_FILE_URL) {
            // A file: URL names a path of this machine, so it is judged like the path itself
            args.addAll(List.of("--source", "file://" + folder));
        }
        if (project != null) {
            args.addAll(List.of("--project", project));
        }
        // A folder that is told by an option is not the working directory, which then has no say
        return execIn(by == By.WORKING_DIRECTORY ? folder : temp, args.toArray(String[]::new));
    }

    private static String runId(Result result) {
        Matcher m = RUN_ID.matcher(result.out());
        assertThat(m.find()).as(result.out() + result.err()).isTrue();
        return m.group(1);
    }

    /** The project line of {@code show} for the run of {@code result}; null when the run has no project. */
    private String projectOf(Result result) {
        assertThat(result.code()).as(result.out() + result.err()).isZero();
        return execIn(temp, "show", runId(result)).out().lines().filter(line -> line.startsWith("Project "))
                .map(line -> line.substring("Project ".length()).strip()).findFirst().orElse(null);
    }

    private List<String> entries(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(folder)) {
            return children.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private void assertNothingWritten() throws IOException {
        assertThat(entries(home)).as("nothing but the settings is in the home").containsExactly("traceworks.json");
    }

    // ---- which project a run is of: every row of the table, for each way a run is told its folder ----

    @Test
    void withoutTheOption_aRunInADeclaredFolder_isOfThatProject() throws IOException {
        for (By by : By.values()) {
            assertThat(projectOf(run(plain(), by, shop, null))).as(by.toString()).isEqualTo("shop");
            assertThat(projectOf(run(plain(), by, web, null))).as(by.toString()).isEqualTo("web");
        }
    }

    @Test
    void withoutTheOption_aRunInNoDeclaredFolder_hasNoProject() throws IOException {
        for (By by : By.values()) {
            assertThat(projectOf(run(plain(), by, other, null))).as(by.toString()).isNull();
        }
    }

    @Test
    void withoutTheOption_anAuditRunInNoDeclaredFolder_isRefused_andTheMessageNamesBothWays() throws IOException {
        for (By by : By.values()) {
            Result refused = run(audit(), by, other, null);

            assertThat(refused.code()).as(by + ": " + refused.out() + refused.err()).isEqualTo(6);
            assertThat(refused.err()).as(by.toString()).contains("the register hello").contains("--project KEY")
                    .contains("\"projects\": {\"KEY\": ").contains("traceworks.json");
            assertNothingWritten();
        }
    }

    @Test
    void withTheKeyOfItsFolder_aRunIsOfThatProject() throws IOException {
        for (By by : By.values()) {
            assertThat(projectOf(run(plain(), by, shop, "shop"))).as(by.toString()).isEqualTo("shop");
        }
    }

    @Test
    void withAnotherKeyThanItsFolderSays_aRunIsRefused_andNothingIsWritten() throws IOException {
        for (By by : By.values()) {
            // web is declared with another folder, and nobody declared 'elsewhere': the folder is of shop either way
            for (String key : List.of("web", "elsewhere")) {
                Result refused = run(audit(), by, shop, key);

                assertThat(refused.code()).as(by + " " + key + ": " + refused.out() + refused.err()).isEqualTo(6);
                assertThat(refused.err()).as(by + " " + key).contains("The run is refused")
                        .contains("--project names '" + key + "'").contains(shop.toString())
                        .contains("is of the project 'shop'").contains("traceworks.json");
                assertNothingWritten();
            }
        }
    }

    @Test
    void withADeclaredKey_aRunInNoDeclaredFolder_isRefused_andNothingIsWritten() throws IOException {
        for (By by : By.values()) {
            Result refused = run(audit(), by, other, "shop");

            assertThat(refused.code()).as(by + ": " + refused.out() + refused.err()).isEqualTo(6);
            assertThat(refused.err()).as(by.toString()).contains("The run is refused")
                    .contains("the project 'shop' is the folder " + shop).contains("the run works in " + other)
                    .contains("traceworks.json");
            assertNothingWritten();
        }
    }

    /** A declared folder that does not exist matches no workspace, and its key is still bound to it. */
    @Test
    void withAKeyWhoseDeclaredFolderDoesNotExist_aRunElsewhereIsRefused() throws IOException {
        Result refused = run(plain(), By.WORKSPACE_OPTION, other, "ghost");

        assertThat(refused.code()).as(refused.out() + refused.err()).isEqualTo(6);
        assertThat(refused.err()).contains("the project 'ghost' is the folder " + temp.resolve("ghost"))
                .contains("the run works in " + other);
        assertNothingWritten();
    }

    @Test
    void withAKeyNobodyDeclared_aRunInNoDeclaredFolder_isOfThatProject_asBefore() throws IOException {
        for (By by : By.values()) {
            assertThat(projectOf(run(plain(), by, other, "elsewhere"))).as(by.toString()).isEqualTo("elsewhere");
        }
    }

    /** The folder of a run is told one way: an option leaves the working directory no say. */
    @Test
    void theWorkingDirectory_hasNoSay_whenAnOptionNamesTheFolder() throws IOException {
        Result workspace = execIn(shop, "run", plain().toString(), "--allow-host-execution", "--no-input", "--quiet",
                "--workspace", other.toString());
        Result source = execIn(shop, "run", plain().toString(), "--allow-host-execution", "--no-input", "--quiet",
                "--source", other.toString());
        Result relative = execIn(temp, "run", plain().toString(), "--allow-host-execution", "--no-input", "--quiet",
                "--workspace", "web");

        assertThat(projectOf(workspace)).isNull();
        assertThat(projectOf(source)).isNull();
        assertThat(projectOf(relative)).as("a relative --workspace counts from the working directory")
                .isEqualTo("web");
    }

    // ---- a --source that is no path of this machine has no folder; a file: URL has one ----

    /**
     * A URL that is no local path. Every case below is refused before the run exists, so nothing is fetched from
     * it; that a key nobody declared is taken for such a run is {@code ProjectOptionTest}'s.
     */
    private static final String ELSEWHERE = "ssh://git@host.invalid/shop.git";

    /** In the declared folder of shop, which has no say for a run that works in a checkout. */
    @Test
    void aRunFromAUrl_hasNoFolder_soOnlyTheOptionNamesItsProject() throws IOException {
        Result without = execIn(shop, "run", audit().toString(), "--allow-host-execution", "--no-input", "--quiet",
                "--source", ELSEWHERE);

        assertThat(without.code()).as(without.out() + without.err()).isEqualTo(6);
        assertThat(without.err()).as("neither the URL nor the working directory names a project")
                .contains("the register hello").contains("--project KEY");
        assertNothingWritten();
    }

    /** A declared key is bound to its folder, and a URL is no folder: the table's fifth row. */
    @Test
    void aRunFromAUrl_withADeclaredKey_isRefused_andNamesNoUrl() throws IOException {
        Result refused = execIn(shop, "run", plain().toString(), "--allow-host-execution", "--no-input", "--quiet",
                "--source", "ssh://user:s3cr3t-T0KEN@host.invalid/shop.git", "--project", "shop");

        assertThat(refused.code()).as(refused.out() + refused.err()).isEqualTo(6);
        assertThat(refused.err()).contains("The run is refused").contains("the project 'shop' is the folder " + shop)
                .contains("a checkout of a URL (--source), which is no folder of this machine")
                .doesNotContain("s3cr3t");
        assertNothingWritten();
    }

    /**
     * Reproduced 2026-11-21: {@code --source file://<declared folder>/repo --project zz} ran and fed {@code zz},
     * and with the folder's own key the refusal said that a checkout of a URL is no folder of this machine. A
     * {@code file:} URL names a path of this machine: it is judged like {@code --source PATH}.
     */
    @Test
    void aRunFromAFileUrl_isJudgedByTheFolderItsPathNames() throws Exception {
        Path inside = repository("shop/repo", "d-long.txt");
        String url = "file://" + inside;

        Result otherKey = execIn(temp, "run", audit().toString(), "--allow-host-execution", "--no-input", "--quiet",
                "--source", url, "--project", "zz");

        assertThat(otherKey.code()).as(otherKey.out() + otherKey.err()).isEqualTo(6);
        assertThat(otherKey.err()).contains("--project names 'zz'").contains("which is of the project 'shop'");
        assertNothingWritten();

        Result ownKey = execIn(temp, "run", audit().toString(), "--allow-host-execution", "--no-input", "--quiet",
                "--source", url, "--project", "shop");
        Result noKey = execIn(temp, "run", audit().toString(), "--allow-host-execution", "--no-input", "--quiet",
                "--source", "file://localhost" + inside);

        assertThat(projectOf(ownKey)).isEqualTo("shop");
        assertThat(projectOf(noKey)).as("also with the host git takes for this machine").isEqualTo("shop");
        assertThat(entries(home.resolve("registers/hello"))).containsExactly("shop");
    }

    @Test
    void anAuditRunFromAFileUrlInNoDeclaredFolder_withoutTheOption_isRefused() throws IOException {
        Result refused = execIn(shop, "run", audit().toString(), "--allow-host-execution", "--no-input", "--quiet",
                "--source", "file://" + other);

        assertThat(refused.code()).as(refused.out() + refused.err()).isEqualTo(6);
        assertThat(refused.err()).as("the working directory has no say").contains("the register hello")
                .contains("--project KEY");
        assertNothingWritten();
    }

    // ---- below a declared folder, beside it, and behind a link ----

    @Test
    void aWorkspaceBelowADeclaredFolder_isOfTheProject_andASiblingWhoseNameStartsTheSame_isNot() throws IOException {
        Path below = Files.createDirectories(shop.resolve("module/src"));
        Path sibling = Files.createDirectories(temp.resolve("shop2"));

        assertThat(projectOf(run(plain(), By.WORKSPACE_OPTION, below, null))).isEqualTo("shop");
        assertThat(projectOf(run(plain(), By.WORKING_DIRECTORY, below, null))).isEqualTo("shop");
        assertThat(projectOf(run(plain(), By.WORKSPACE_OPTION, sibling, null))).isNull();
        assertThat(projectOf(run(plain(), By.WORKING_DIRECTORY, sibling, null))).isNull();
        Result refused = run(plain(), By.WORKSPACE_OPTION, sibling, "shop");
        assertThat(refused.code()).as(refused.out() + refused.err()).isEqualTo(6);
        assertThat(refused.err()).contains("the project 'shop' is the folder " + shop);
    }

    @Test
    void aWorkspaceBehindALink_andADeclaredLink_areComparedByTheirRealPaths() throws IOException {
        Path toShop = Files.createSymbolicLink(temp.resolve("to-shop"), shop);
        Path disk = Files.createDirectories(temp.resolve("disk/app"));
        Path app = Files.createSymbolicLink(temp.resolve("app"), disk);
        declare("{\"shop\": \"" + shop + "\", \"app\": \"" + app + "\"}");

        assertThat(projectOf(run(plain(), By.WORKSPACE_OPTION, toShop, null))).as("a link to the declared folder")
                .isEqualTo("shop");
        assertThat(projectOf(run(plain(), By.WORKSPACE_OPTION, disk, null))).as("the target of a declared link")
                .isEqualTo("app");
        assertThat(projectOf(run(plain(), By.WORKSPACE_OPTION, app, "app"))).isEqualTo("app");
    }

    // ---- the register of an audit run ----

    @Test
    void anAuditRunInEachOfTwoDeclaredFolders_withoutTheOption_feedsARegisterPerProject() throws IOException {
        Result onShop = run(audit(), By.WORKING_DIRECTORY, shop, null);
        Result onWeb = run(audit(), By.WORKSPACE_OPTION, web, null);

        assertThat(onShop.code()).as(onShop.out() + onShop.err()).isZero();
        assertThat(onShop.out()).contains("  register hello of shop: 1 new, 0 resolved, 0 reopened, 1 open now");
        assertThat(onWeb.code()).as(onWeb.out() + onWeb.err()).isZero();
        assertThat(onWeb.out()).contains("  register hello of web: 1 new, 0 resolved, 0 reopened, 1 open now");
        assertThat(entries(home.resolve("registers/hello"))).containsExactly("shop", "web");
        assertThat(execIn(temp, "findings", "hello", "list", "--project", "shop").out()).contains("a-long.txt")
                .doesNotContain("b-long.txt");
        assertThat(execIn(temp, "findings", "hello", "list", "--project", "web").out()).contains("b-long.txt")
                .doesNotContain("a-long.txt");
        String started = Files.readAllLines(home.resolve("runs").resolve(entries(home.resolve("runs")).getFirst())
                .resolve(runId(onShop)).resolve("events.jsonl")).getFirst();
        assertThat(started).as("the record names the project, and not where the key came from")
                .contains("\"project\":\"shop\"").doesNotContain("projects");
    }

    /** A resume takes the project of run.started and asks the setting nothing. */
    @Test
    void aResume_inAHomeWhoseSettingChangedSince_keepsTheProjectOfItsStart() throws IOException {
        Result parked = run(askingAudit(), By.WORKING_DIRECTORY, shop, null);
        assertThat(parked.code()).as(parked.out() + parked.err()).isEqualTo(5);
        String id = runId(parked);
        assertThat(execIn(shop, "approve", id, "--answer", "Yes").code()).isZero();

        // The folder is of another project now, and the key of the run is bound to another folder
        declare("{\"moved\": \"" + shop + "\", \"shop\": \"" + other + "\"}");
        Result resumed = execIn(shop, "resume", id, "--allow-host-execution");

        assertThat(resumed.code()).as(resumed.out() + resumed.err()).isZero();
        assertThat(resumed.out()).contains("register hello of shop: 1 new, 0 resolved, 0 reopened, 1 open now");
        assertThat(execIn(temp, "show", id).out()).contains("Project   shop");
        assertThat(entries(home.resolve("registers/hello"))).containsExactly("shop");
    }

    // ---- a wrong setting ----

    @Test
    void aWrongSetting_isReportedByEveryCommandThatReadsTheHome_likeAnyOtherWrongSetting() throws IOException {
        Files.writeString(home.resolve("traceworks.json"), "{\"output_limit_mib\": 0}");
        int otherRun = run(plain(), By.WORKING_DIRECTORY, other, null).code();
        int otherCheck = execIn(temp, "check", plain().toString()).code();
        int otherDescribe = execIn(temp, "describe", plain().toString()).code();
        int otherFindings = execIn(temp, "findings").code();

        declare("{\"shop\": \"" + shop + "\", \"web\": \"" + shop.resolve("web") + "\"}");

        Result run = run(plain(), By.WORKING_DIRECTORY, other, null);
        Result check = execIn(temp, "check", plain().toString());
        Result describe = execIn(temp, "describe", plain().toString());
        Result findings = execIn(temp, "findings");
        Result runs = execIn(temp, "runs");
        for (Result result : List.of(run, check, describe, findings, runs)) {
            assertThat(result.err()).contains("'projects'").contains("\"web\"").contains("lies in the folder")
                    .contains(home.resolve("traceworks.json").toString());
        }
        assertThat(run.code()).isEqualTo(otherRun).isEqualTo(6);
        assertThat(check.code()).isEqualTo(otherCheck);
        assertThat(describe.code()).isEqualTo(otherDescribe);
        assertThat(findings.code()).isEqualTo(otherFindings);
        assertThat(entries(home)).containsExactly("traceworks.json");
    }

    /**
     * Reproduced 2026-11-21: a key that stood twice in "projects" was accepted, and the last one won without a
     * word. It is refused as a setting by every command that reads the home, check and findings among them, with
     * the code each gives for any other wrong setting, and nothing is written.
     */
    @Test
    void aProjectKeyThatStandsTwice_isRefusedAsASetting_byCheckAndFindingsAndEveryOtherCommand() throws IOException {
        Files.writeString(home.resolve("traceworks.json"), "{\"output_limit_mib\": 0}");
        int otherRun = run(plain(), By.WORKING_DIRECTORY, other, null).code();
        int otherCheck = execIn(temp, "check", plain().toString()).code();
        int otherDescribe = execIn(temp, "describe", plain().toString()).code();
        int otherFindings = execIn(temp, "findings").code();

        declare("{\"shop\": \"" + shop + "\", \"shop\": \"" + web + "\"}");

        Result run = run(plain(), By.WORKING_DIRECTORY, shop, null);
        Result audit = run(audit(), By.WORKING_DIRECTORY, shop, null);
        Result check = execIn(temp, "check", plain().toString());
        Result describe = execIn(temp, "describe", plain().toString());
        Result findings = execIn(temp, "findings");
        Result ofAKey = execIn(shop, "findings", "hello", "list");
        Result runs = execIn(temp, "runs");
        for (Result result : List.of(run, audit, check, describe, findings, ofAKey, runs)) {
            assertThat(result.err()).doesNotContain("internal error").contains("'projects' in "
                    + home.resolve("traceworks.json") + " holds the key \"shop\" twice: a project is bound to one"
                    + " folder");
        }
        assertThat(run.code()).isEqualTo(otherRun).isEqualTo(6);
        assertThat(audit.code()).isEqualTo(6);
        assertThat(check.code()).isEqualTo(otherCheck).isNotZero();
        assertThat(describe.code()).isEqualTo(otherDescribe).isNotZero();
        assertThat(findings.code()).isEqualTo(otherFindings).isNotZero();
        assertThat(ofAKey.code()).isEqualTo(otherFindings);
        assertThat(entries(home)).as("nothing was written: no run, no ledger, no register").containsExactly(
                "traceworks.json");
    }

    // ---- findings <key> <command> without --project ----

    private String idIn(String project) {
        return execIn(temp, "findings", "hello", "list", "--project", project).out().substring(0, 8);
    }

    /** First: the declared project the working directory lies in, when the key has a register of that project. */
    @Test
    void findings_inADeclaredFolder_takeItsProject_whenTheKeyHasARegisterOfIt() throws IOException {
        run(audit(), By.WORKING_DIRECTORY, shop, null);
        run(audit(), By.WORKING_DIRECTORY, web, null);
        Path below = Files.createDirectories(web.resolve("module"));

        Result inShop = execIn(shop, "findings", "hello", "list");
        Result belowWeb = execIn(below, "findings", "hello", "list");
        Result dismissed = execIn(below, "findings", "hello", "dismiss", idIn("web"), "--note", "not ours");

        assertThat(inShop.code()).as(inShop.err()).isZero();
        assertThat(inShop.out()).contains("a-long.txt").doesNotContain("b-long.txt");
        assertThat(belowWeb.code()).as(belowWeb.err()).isZero();
        assertThat(belowWeb.out()).contains("b-long.txt").doesNotContain("a-long.txt");
        assertThat(dismissed.code()).as(dismissed.err()).isZero();
        assertThat(execIn(temp, "findings", "hello", "list", "--project", "web").out()).contains("DISMISSED");
        assertThat(execIn(temp, "findings", "hello", "list", "--project", "shop").out()).contains("OPEN")
                .doesNotContain("DISMISSED");
        assertThat(execIn(shop, "findings", "hello", "verify").out()).contains("verified");
        assertThat(execIn(shop, "findings", "hello", "render").out().strip())
                .isEqualTo("rendered the register hello of shop: "
                        + home.resolve("registers/hello/shop/report.html"));
        assertThat(dismissed.out()).as("the line names the register it acted on")
                .startsWith("dismissed " + idIn("web") + " in the register hello of web");
    }

    /**
     * The folder of another project is no silent default. Reproduced 2026-11-21: with a register of web only and
     * the working directory in the declared folder of shop, {@code dismiss} hit the register of web. In a folder
     * of no declared project the command means the only project the key has, as before.
     */
    @Test
    void findings_inADeclaredFolderWithoutARegisterOfItsProject_areRefused_andNameTheProjectsTheKeyHas()
            throws IOException {
        run(audit(), By.WORKING_DIRECTORY, web, null);
        String id = idIn("web");

        for (String[] command : List.of(new String[] {"list"}, new String[] {"show", id}, new String[] {"items"},
                new String[] {"dismiss", id, "--note", "not ours"}, new String[] {"accept", id, "--note", "fine"},
                new String[] {"reopen", id}, new String[] {"render"}, new String[] {"verify"})) {
            List<String> args = new ArrayList<>(List.of("findings", "hello"));
            args.addAll(List.of(command));

            Result inShop = execIn(shop, args.toArray(String[]::new));

            assertThat(inShop.code()).as(String.join(" ", command) + ": " + inShop.out() + inShop.err())
                    .isEqualTo(2);
            assertThat(inShop.err()).as(String.join(" ", command))
                    .contains("The working directory lies in the project 'shop'")
                    .contains("the key 'hello' has no register of that project")
                    .contains("its projects are web").contains("--project KEY");
            assertThat(inShop.out()).isEmpty();
        }
        assertThat(execIn(temp, "findings", "hello", "list", "--project", "web").out())
                .as("no decision reached the register of the other project").contains("OPEN")
                .doesNotContain("DISMISSED");

        Result inNone = execIn(other, "findings", "hello", "list");
        Result named = execIn(shop, "findings", "hello", "list", "--project", "web");

        assertThat(inNone.code()).as(inNone.err()).isZero();
        assertThat(inNone.out()).as("a folder of no project: the only project the key has").contains("b-long.txt");
        assertThat(named.code()).as(named.err()).isZero();
        assertThat(named.out()).as("the option wins whatever the folder says").isEqualTo(inNone.out());
    }

    /** Else: refused, and the projects are named. */
    @Test
    void findings_withSeveralProjectsAndNoneOfTheFolder_areRefused_andNameTheProjects() throws IOException {
        run(audit(), By.WORKING_DIRECTORY, web, null);
        run(audit(), By.WORKING_DIRECTORY, other, "elsewhere");

        Result inShop = execIn(shop, "findings", "hello", "list");
        Result inNone = execIn(temp, "findings", "hello", "list");
        Result inOther = execIn(other, "findings", "hello", "list");

        for (Result refused : List.of(inNone, inOther)) {
            assertThat(refused.code()).as(refused.out() + refused.err()).isEqualTo(2);
            assertThat(refused.err()).contains("'hello' has 2 projects").contains("elsewhere, web")
                    .contains("--project KEY");
        }
        assertThat(inShop.code()).as(inShop.out() + inShop.err()).isEqualTo(2);
        assertThat(inShop.err()).as("the folder is of a project the key has no register of")
                .contains("The working directory lies in the project 'shop'").contains("elsewhere, web")
                .contains("--project KEY");
    }

    /** The option wins, and is not checked against the working directory: a decision is about a register. */
    @Test
    void findings_withTheOption_meanThatProject_whateverTheFolderSays() throws IOException {
        run(audit(), By.WORKING_DIRECTORY, shop, null);
        run(audit(), By.WORKING_DIRECTORY, web, null);

        Result ofWebInShop = execIn(shop, "findings", "hello", "list", "--project", "web");
        Result dismissed = execIn(shop, "findings", "hello", "dismiss", idIn("web"), "--note", "not ours",
                "--project", "web");
        Result unknown = execIn(shop, "findings", "hello", "list", "--project", "ghost");

        assertThat(ofWebInShop.code()).as(ofWebInShop.err()).isZero();
        assertThat(ofWebInShop.out()).contains("b-long.txt").doesNotContain("a-long.txt");
        assertThat(dismissed.code()).as(dismissed.err()).isZero();
        assertThat(execIn(shop, "findings", "hello", "list").out()).as("the register of the folder is untouched")
                .contains("OPEN").doesNotContain("DISMISSED");
        assertThat(execIn(web, "findings", "hello", "list").out()).contains("DISMISSED");
        assertThat(unknown.code()).isEqualTo(2);
        assertThat(unknown.err()).contains("No register 'hello' of the project 'ghost'");
    }
}
