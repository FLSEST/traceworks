package traceworks.cli.main;

import traceworks.cli.RuntimeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The answer to a question of a run says whether an agent gave it: {@code --agent NAME} on {@code approve} and
 * {@code answer}, {@code TRACEWORKS_AGENT} as the fallback, and the same variable for the answer typed at the prompt of
 * a run in the foreground. Real adapters in a temporary home, shell steps on the host.
 */
class ApprovalAgentTest {

    private static final Pattern RUN_ID = Pattern.compile("(?m)^run ([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-"
            + "[0-9a-f]{12}) [A-Z_]+$");
    /** A run this version did not write: started, answered and resumed before an answer could name an agent. */
    private static final String EARLIER = "14e0f516-8858-443b-8218-9bf214b21f95";

    @TempDir
    Path temp;
    private Path home;
    private Path work;
    private final Map<String, String> env = new HashMap<>(Map.of("TRACEWORKS_ACTOR", "tester"));

    private record Result(int code, String out, String err) {
    }

    @BeforeEach
    void directories() throws IOException {
        home = Files.createDirectories(temp.resolve("home"));
        work = Files.createDirectories(temp.resolve("work"));
    }

    private Path pkg(String name, String blocks) throws IOException {
        Path dir = Files.createDirectories(temp.resolve(name));
        Files.writeString(dir.resolve("workflow.json"),
                "{\"id\":\"" + name + "\",\"name\":\"" + name + " flow\",\"blocks\":[" + blocks + "]}");
        return dir;
    }

    private Path approvalPackage() throws IOException {
        return pkg("deploy", """
                {"type":"shell","name":"build","config":{"command":"echo built"}},
                {"type":"approval","name":"publish","config":{"question":"Publish it?","options":["Yes","No"]}},
                {"type":"shell","name":"done","config":{"command":"echo done"}}""");
    }

    private Result exec(String... args) {
        return execWithConsole(null, args);
    }

    private Result execWithConsole(String console, String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--home");
        all.add(home.toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(all.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Map.copyOf(env), work,
                console == null ? null : new ByteArrayInputStream(console.getBytes(StandardCharsets.UTF_8)),
                RuntimeSource.NONE);
        return new Result(code, traceworks.cli.Unbroken.of(out.toString(StandardCharsets.UTF_8)),
                traceworks.cli.Unbroken.of(err.toString(StandardCharsets.UTF_8)));
    }

    private static String idOf(Result result) {
        Matcher m = RUN_ID.matcher(result.out());
        assertThat(m.find()).as(result.out() + result.err()).isTrue();
        return m.group(1);
    }

    private String parkedRun() throws IOException {
        Result parked = exec("run", approvalPackage().toString(), "--allow-host-execution", "--no-input");
        assertThat(parked.code()).as(parked.out() + parked.err()).isEqualTo(5);
        return idOf(parked);
    }

    private Path runFolder(String id) throws IOException {
        try (Stream<Path> days = Files.walk(home.resolve("runs"), 2)) {
            return days.filter(p -> p.getFileName().toString().equals(id)).findFirst().orElseThrow();
        }
    }

    /** The {@code approval.answered} lines of the run's record, as they stand in the file. */
    private List<String> answers(String id) throws IOException {
        return Files.readAllLines(runFolder(id).resolve("events.jsonl")).stream()
                .filter(line -> line.contains("\"type\":\"approval.answered\"")).toList();
    }

    // ---- approve and answer ----

    @Test
    void approve_withAnAgent_recordsItBesideTheActor_andSaysTheNameBack() throws IOException {
        String id = parkedRun();

        Result approve = exec("approve", id, "--answer", "Yes", "--agent", "claude-code");

        assertThat(approve.code()).as(approve.err()).isZero();
        assertThat(approve.out()).contains("is recorded through the agent claude-code;");
        assertThat(answers(id)).singleElement().asString()
                .contains("\"actor\":\"tester\"", "\"agent\":\"claude-code\"", "\"source\":\"cli\"");
    }

    @Test
    void approve_withoutAnAgent_recordsNoAgent_andEveryViewReadsAsBefore() throws IOException {
        String id = parkedRun();

        Result approve = exec("approve", id, "--answer", "Yes");
        Result resume = exec("resume", id, "--no-input", "--allow-host-execution");

        assertThat(approve.code()).as(approve.err()).isZero();
        assertThat(resume.code()).as(resume.out() + resume.err()).isZero();
        assertThat(answers(id)).hasSize(2).allSatisfy(line -> assertThat(line).doesNotContain("\"agent\":"));
        assertThat(approve.out() + resume.out() + exec("show", id).out()
                + Files.readString(runFolder(id).resolve("run.html"))).doesNotContain("through the agent");
    }

    @Test
    void theVariableNamesTheAgent_andTheOptionWins() throws IOException {
        String byVariable = parkedRun();
        String byOption = parkedRun();
        env.put("TRACEWORKS_AGENT", " harness/1 ");

        assertThat(exec("approve", byVariable, "--answer", "Yes").code()).isZero();
        env.put("TRACEWORKS_AGENT", "not\na name");
        Result option = exec("approve", byOption, "--answer", "Yes", "--agent", "claude-code");

        assertThat(option.code()).as(option.err()).isZero();
        assertThat(answers(byVariable)).singleElement().asString().contains("\"agent\":\"harness/1\"");
        assertThat(answers(byOption)).singleElement().asString().contains("\"agent\":\"claude-code\"");
    }

    @Test
    void aBlankOrBadName_isAUsageError_recordsNothing_andTheApprovalStillWaits() throws IOException {
        String id = parkedRun();

        Result blank = exec("approve", id, "--answer", "Yes", "--agent", "  ");
        Result bad = exec("approve", id, "--answer", "Yes", "--agent", "two  spaces");
        env.put("TRACEWORKS_AGENT", "");
        Result variable = exec("approve", id, "--answer", "Yes");
        env.remove("TRACEWORKS_AGENT");

        assertThat(blank.code()).isEqualTo(2);
        assertThat(blank.err()).contains("--agent is blank");
        assertThat(bad.code()).isEqualTo(2);
        assertThat(bad.err()).contains("--agent takes a name of");
        assertThat(variable.code()).isEqualTo(2);
        assertThat(variable.err()).contains("TRACEWORKS_AGENT is blank");
        assertThat(answers(id)).isEmpty();
        assertThat(exec("show", id).out()).contains("PARKED");
        assertThat(exec("approve", id, "--answer", "Yes").code()).as("the approval still waits").isZero();
    }

    @Test
    void answer_toAHumanInput_takesTheAgentToo() throws IOException {
        Result parked = exec("run", pkg("ask", """
                {"type":"human-input","name":"who","config":{"prompt":"Who?","variable_name":"who"}}""").toString(),
                "--allow-host-execution", "--no-input");
        String id = idOf(parked);

        Result answer = exec("answer", id, "--value", "operator", "--agent", "claude-code");

        assertThat(answer.code()).as(answer.out() + answer.err()).isZero();
        assertThat(answers(id)).singleElement().asString().contains("\"agent\":\"claude-code\"");
    }

    // ---- the views, and a resume ----

    @Test
    void aResume_takesTheAnswerOverWithItsAgent_andShowAndRunHtmlNameIt() throws IOException {
        String id = parkedRun();
        assertThat(exec("approve", id, "--answer", "Yes", "--agent", "claude-code").code()).isZero();
        // Whoever resumes is not who answered: the agent of the answer stays, the variable of the resume says nothing
        env.put("TRACEWORKS_AGENT", "another-harness");

        Result resume = exec("resume", id, "--no-input", "--allow-host-execution");

        assertThat(resume.code()).as(resume.out() + resume.err()).isZero();
        assertThat(resume.out()).contains("answered: Yes by tester through the agent claude-code");
        assertThat(answers(id)).hasSize(2).allSatisfy(line -> assertThat(line).contains("\"agent\":\"claude-code\""));
        assertThat(answers(id).get(1)).contains("\"source\":\"resume\"");
        assertThat(Files.readString(runFolder(id).resolve("events.jsonl"))).doesNotContain("another-harness");
        assertThat(exec("show", id).out())
                .contains("tester  answered step 1 (approval) via cli through the agent claude-code");
        assertThat(Files.readString(runFolder(id).resolve("run.html")))
                .contains("by tester through the agent claude-code &middot; via resume")
                .contains("given by tester through the agent claude-code via cli");
        assertThat(exec("verify", id).code()).isZero();
    }

    @Test
    void theStartAndTheResume_sayThatTheirAnswersNameAnAgent() throws IOException {
        String id = parkedRun();
        assertThat(exec("approve", id, "--answer", "Yes").code()).isZero();
        assertThat(exec("resume", id, "--no-input", "--allow-host-execution").code()).isZero();

        assertThat(Files.readAllLines(runFolder(id).resolve("events.jsonl")).stream()
                .filter(line -> line.contains("\"type\":\"run.started\"") || line.contains("\"type\":\"run.resumed\"")))
                .hasSize(2).allSatisfy(line -> assertThat(line).contains("\"answer_agent\""));
    }

    // ---- the prompt of a run in the foreground ----

    @Test
    void anAnswerAtThePrompt_isTheAgents_whenTheVariableOfTheRunsProcessNamesOne() throws IOException {
        env.put("TRACEWORKS_AGENT", "claude-code");

        Result r = execWithConsole("YES\n", "run", approvalPackage().toString(), "--allow-host-execution");

        assertThat(r.code()).as(r.out() + r.err()).isZero();
        assertThat(r.out()).contains("answered: Yes by tester through the agent claude-code");
        assertThat(answers(idOf(r))).singleElement().asString()
                .contains("\"agent\":\"claude-code\"", "\"source\":\"terminal\"");
    }

    @Test
    void aBadVariable_refusesARunThatWouldAskAtThePrompt_andSaysNothingToARunThatAsksNobody() throws IOException {
        env.put("TRACEWORKS_AGENT", "   ");

        Result asking = execWithConsole("YES\n", "run", approvalPackage().toString(), "--allow-host-execution");

        assertThat(asking.code()).isEqualTo(2);
        assertThat(asking.err()).contains("TRACEWORKS_AGENT is blank");
        assertThat(home.resolve("runs")).doesNotExist();

        Result silent = exec("run", approvalPackage().toString(), "--allow-host-execution", "--no-input");

        assertThat(silent.code()).as(silent.out() + silent.err()).isEqualTo(5);
    }

    // ---- a record of an earlier version ----

    @Test
    void aRecordOfAnEarlierVersion_verifies_andShowsItsAnswerAsBefore() throws IOException, URISyntaxException {
        Path source = Path.of(getClass().getResource("/records/answered-before-agent/" + EARLIER).toURI());
        Path folder = temp.resolve(EARLIER);
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.toList()) {
                Path to = folder.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(file, to);
                }
            }
        }
        assertThat(Files.readString(folder.resolve("events.jsonl"))).doesNotContain("\"agent\":", "answer_agent");

        Result verify = exec("verify", folder.toString());
        Result show = exec("show", folder.toString());
        Result render = exec("render", folder.toString());

        assertThat(verify.code()).as(verify.out() + verify.err()).isZero();
        assertThat(verify.out()).contains("chain     ok (27 events", "manifest  ok", "verified");
        assertThat(show.code()).as(show.err()).isZero();
        assertThat(show.out()).contains("Status    COMPLETED")
                .containsPattern("(?m)^  \\S+  tester  answered step 1 \\(approval\\) via cli$")
                .doesNotContain("through the agent");
        assertThat(render.code()).as(render.out() + render.err()).isZero();
        assertThat(Files.readString(folder.resolve("run.html"))).contains("given by tester via cli")
                .doesNotContain("through the agent");
    }
}
