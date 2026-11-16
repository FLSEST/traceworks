package traceworks.core.view;

import traceworks.core.port.WebAccess;
import traceworks.core.ai.cli.CliAiProvider;
import traceworks.core.ai.ToolCall;
import traceworks.core.ai.ToolPolicy;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.RunStatus;
import traceworks.core.model.StepStatus;
import traceworks.core.model.TriggerSource;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.AiCli;
import traceworks.core.port.AiProvider;
import traceworks.core.port.AiProviders;
import traceworks.core.port.Clock;
import traceworks.core.port.AiNetwork;
import traceworks.core.port.CommandRunner.CommandRequest;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.CommandRunner.Connection;
import traceworks.core.port.Egress;
import traceworks.core.port.RunEvent;
import traceworks.core.port.RunEvent.*;
import traceworks.core.record.ChainReport;
import traceworks.core.record.RecordedEvent;
import traceworks.core.record.RunRecorder;
import traceworks.core.record.RunStart;
import traceworks.core.record.RunView;
import traceworks.core.testing.FakeAiCli;
import traceworks.core.testing.InMemoryBlobStore;
import traceworks.core.testing.InMemoryEventLog;
import traceworks.core.testing.InMemoryRunStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RunHtmlTest {

    private static final String RUN = "00000000-0000-0000-0000-000000000001";

    private final InMemoryEventLog log = new InMemoryEventLog();
    private final InMemoryBlobStore blobs = new InMemoryBlobStore();
    private final AtomicLong tick = new AtomicLong();
    private final Clock clock = () -> Instant.parse("2026-11-03T12:00:00Z").plusMillis(tick.incrementAndGet() * 10);
    private final RunRecorder recorder = RunRecorder.start(log, blobs, clock, RUN, definition());

    private static WorkflowDefinition definition() {
        BlockDefinition fetch = BlockDefinition.builder().type("shell").name("fetch")
                .config(Map.of("command", "echo hi")).build();
        BlockDefinition use = BlockDefinition.builder().type("simple").name("summarise")
                .config(Map.of("prompt", "{{ block.fetch.output }} {{ ticket }}")).build();
        return new WorkflowDefinition("wf", "Wf", null, List.of(fetch, use), null, null, null, null, null, null, null,
                null, null);
    }

    private RunHtmlTest started() {
        return started("host");
    }

    /** A run that started with {@code execution} as its target: {@code host} or {@code container}. */
    private RunHtmlTest started(String execution) {
        recorder.runStarted(new RunStart("wf", "Wf <b>", "/pkg", "digest-1", null, null,
                "{\"id\":\"wf\"}".getBytes(StandardCharsets.UTF_8), Map.of("ticket", "T-1"), TriggerSource.MANUAL,
                "flo@box", "0.1.0", execution, null, "/ws"));
        return this;
    }

    private void emit(RunEvent... events) {
        for (RunEvent event : events) {
            recorder.emit(event);
        }
    }

    private RunView view() {
        List<RecordedEvent> events = log.readAll().stream().map(RecordedEvent::parse).toList();
        return RunView.of(events, false);
    }

    private ChainReport intact() {
        return new ChainReport(true, log.readAll().size(), recorder.head(), List.of(), List.of());
    }

    private String render() {
        return RunHtml.render(view(), blobs, intact(), "1.2.3");
    }

    private void finishedFetch(String output) {
        emit(new StepStarted(0, "fetch", "shell", 0, false), new OutputRecorded(0, "fetch", output),
                new StepFinished(0, "fetch", "shell", StepStatus.COMPLETED, "i", 5, Map.of()));
    }

    /** A step the cancel ended shows CANCELLED, with the run status's wording and its own neutral look. */
    @Test
    void aCancelledStep_isShownCancelled_notFailed() {
        started();
        emit(new StepStarted(0, "fetch", "shell", 0, false), new OutputRecorded(0, "fetch", "half"),
                new StepFinished(0, "fetch", "shell", StepStatus.CANCELLED, "i", 5, Map.of()),
                new RunFinished(RunStatus.CANCELLED, Map.of(), null));

        String html = render();

        assertThat(html).contains("<section class=\"step cancelled\" id=\"step-0\">",
                "status <strong>CANCELLED</strong>").doesNotContain("class=\"step failed\"");
        assertThat(html).contains(".step.cancelled{border-left-color:var(--muted)}");
    }

    @Test
    void outputWithMarkup_isEscaped() {
        started();
        finishedFetch("</pre></details><script>alert(1)</script>");

        String page = render();

        assertThat(page).contains("&lt;/pre&gt;&lt;/details&gt;&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(page).doesNotContain("<script");
        assertThat(page).contains("Wf &lt;b&gt;").doesNotContain("Wf <b>");
    }

    /** The header names where the run ran its steps, as {@code run.started} records it. */
    @Test
    void header_namesTheRecordedExecution() {
        started();

        assertThat(render()).contains("<tr><th>execution</th><td>host</td></tr>");
    }

    @Test
    void page_loadsNothingExternal() {
        started();
        finishedFetch("see https://example.com/x and http://example.com");

        String page = RunHtml.render(view(), blobs, intact(), "1.2.3");
        String withoutContent = page.replace("https://example.com/x", "").replace("http://example.com", "");

        assertThat(withoutContent).doesNotContain("http://", "https://", "src=", "href=\"//");
        assertThat(page).contains("<meta http-equiv=\"Content-Security-Policy\" "
                + "content=\"default-src 'none'; style-src 'unsafe-inline'\">");
        assertThat(page).contains("<meta charset=\"utf-8\">");
    }

    @Test
    void largeOutput_isCut_andNamesTheBlob() {
        started();
        String big = "a".repeat(70_000);
        finishedFetch(big);

        String page = render();

        assertThat(page).contains("a".repeat(65_536)).doesNotContain("a".repeat(65_537));
        assertThat(page).contains(view().steps().getFirst().output().sha256()).contains("cut at 65536 of 70000");
    }

    @Test
    void cut_doesNotSplitASurrogatePair() {
        assertThat(Html.cut("ab😀cd", 3)).isEqualTo("ab");
        assertThat(Html.cut("ab😀cd", 4)).isEqualTo("ab😀");
        assertThat(Html.cut("abc", 10)).isEqualTo("abc");
    }

    @Test
    void escape_coversAllFiveCharacters() {
        assertThat(Html.escape("&<>\"'")).isEqualTo("&amp;&lt;&gt;&quot;&#39;");
    }

    @Test
    void deletedBlob_showsContentDeleted() {
        started();
        finishedFetch("secret");
        String sha = view().steps().getFirst().output().sha256();
        blobs.delete(sha);

        String page = render();

        assertThat(page).contains("content deleted").contains(sha);
    }

    @Test
    void dataFlow_linksTheProducingStep() {
        started();
        finishedFetch("data");
        emit(new StepStarted(1, "summarise", "simple", 1, true),
                new StepFinished(1, "summarise", "simple", StepStatus.COMPLETED, "i", 5, Map.of()));

        String page = render();

        assertThat(page).contains("id=\"step-0\"").contains("id=\"step-1\"");
        assertThat(page).contains("<a href=\"#step-0\"><code>block.fetch.output</code></a>");
        assertThat(page).contains("<code>ticket</code>").doesNotContain("<a href=\"#step-0\"><code>ticket");
    }

    @Test
    void unnamedStep_beforeAStepThatUsesABlockOutput_rendersAsUnnamed() {
        BlockDefinition unnamed = BlockDefinition.builder().type("shell").config(Map.of("command", "true")).build();
        BlockDefinition second = BlockDefinition.builder().type("shell").name("second")
                .config(Map.of("command", "echo 2")).build();
        BlockDefinition third = BlockDefinition.builder().type("shell").name("third")
                .config(Map.of("command", "echo {{ block.second.output }}")).build();
        RunRecorder own = RunRecorder.start(log, blobs, clock, RUN, new WorkflowDefinition("wf", "Wf", null,
                List.of(unnamed, second, third), null, null, null, null, null, null, null, null, null));
        own.runStarted(new RunStart("wf", "Wf", "/pkg", "digest-1", null, null,
                "{\"id\":\"wf\"}".getBytes(StandardCharsets.UTF_8), Map.of(), TriggerSource.MANUAL, "flo@box",
                "0.1.0", "host", null, "/ws"));
        own.emit(new StepStarted(0, null, "shell", 0, false));
        own.emit(new StepFinished(0, null, "shell", StepStatus.COMPLETED, "true", 1, Map.of()));
        own.emit(new StepStarted(1, "second", "shell", 1, false));
        own.emit(new StepFinished(1, "second", "shell", StepStatus.COMPLETED, "echo 2", 1, Map.of()));
        own.emit(new StepStarted(2, "third", "shell", 2, false));
        own.emit(new StepFinished(2, "third", "shell", StepStatus.COMPLETED, "echo 2", 1, Map.of()));

        String page = RunHtml.render(view(), blobs, new ChainReport(true, log.readAll().size(), own.head(),
                List.of(), List.of()), "1.2.3");

        assertThat(page).contains("0 &middot; (unnamed)").doesNotContain("null")
                .contains("<a href=\"#step-1\"><code>block.second.output</code></a>");
    }

    @Test
    void resumedStep_showsItsEarlierAttempt_theAnswerGivenWhileParked_andTheTimeline() {
        started();
        emit(new StepStarted(0, "fetch", "approval", 0, false),
                new ApprovalRequested(0, "b1", "approval", "Ship?", List.of("yes", "no"), List.of(), null, null,
                        true, null, null, null),
                new RunParked(0, "approval"));
        RunRecorder next = RunRecorder.continueAfter(log, blobs, clock, RUN, definition(),
                log.readAll().stream().map(RecordedEvent::parse).toList());
        next.attributeNextAnswer("anna@box", "cli", "req-1", "looks <good>");
        next.emit(new ApprovalAnswered(0, "approval", "yes"));
        next.record("run.resumed", Map.of("actor", "flo@box", "package_changed", false));
        next.attributeNextAnswer("anna@box", "resume", null, null);
        for (RunEvent event : List.of(new StepStarted(0, "fetch", "approval", 0, false),
                new ApprovalRequested(0, "b1", "approval", "Ship?", List.of("yes", "no"), List.of(), null, null,
                        false, null, null, null),
                new ApprovalAnswered(0, "approval", "yes"),
                new StepFinished(0, "fetch", "approval", StepStatus.COMPLETED, "i", 5, Map.of()))) {
            next.emit(event);
        }
        next.record("request.refused", Map.of("kind", "cancel", "request", "r-9", "actor", "eve@box",
                "reason", "the run had <ended>"));

        String page = RunHtml.render(view(), blobs, new ChainReport(true, log.readAll().size(), next.head(),
                List.of(), List.of()), "1.2.3");

        assertThat(page).contains("attempt 1").contains("via cli").contains("looks &lt;good&gt;")
                .contains("given by anna@box via cli");
        assertThat(page).contains("<h2>Interventions</h2>").contains("resumed the run")
                .contains("eve@box").contains("the run had &lt;ended&gt;");
        assertThat(page.indexOf("<h2>Interventions</h2>")).isLessThan(page.indexOf("<h2>Steps</h2>"));
    }

    @Test
    void badges_showDeterministicAndAi() {
        started();
        finishedFetch("data");
        emit(new StepStarted(1, "summarise", "simple", 1, true),
                new StepFinished(1, "summarise", "simple", StepStatus.COMPLETED, "i", 5, Map.of()));

        String page = render();

        assertThat(page).contains(">deterministic</span>").contains(">AI</span>");
        assertThat(page.indexOf(">deterministic<")).isLessThan(page.indexOf(">AI<"));
    }

    @Test
    void parkedRun_showsTheQuestionAndHowToAnswer() {
        started();
        emit(new StepStarted(0, "ask", "approval", 0, false),
                new ApprovalRequested(0, "b1", "approval", "Ship <it>?", List.of("yes", "no"), List.of("no"), null,
                        null, true, null, null, null),
                new StepWaiting(0, true), new RunParked(0, "approval"));

        String page = render();

        assertThat(page).contains("<h2>Waiting for an answer</h2>", "<p>options: <code>yes</code> <code>no</code></p>")
                .doesNotContain("Parked, and answered");
        assertThat(page).contains("Ship &lt;it&gt;?")
                .contains("traceworks approve &#39;" + RUN + "&#39; --answer &#39;yes&#39;")
                .contains("traceworks approve &#39;" + RUN + "&#39; --answer &#39;no&#39;")
                .contains("traceworks resume &#39;" + RUN + "&#39; --allow-host-execution</pre>");
    }

    /**
     * The resume of a host run needs the consent again, so every resume hint of a host run carries the flag; that of
     * a container run does not. The page cannot know the operator config, where the flag is not needed: it is
     * accepted there too.
     */
    @Test
    void resumeHint_ofAContainerRun_hasNoHostFlag() {
        started("container");
        emit(new StepStarted(0, "ask", "approval", 0, false),
                new ApprovalRequested(0, "b1", "approval", "Ship?", List.of("yes", "no"), List.of("no"), null,
                        null, true, null, null, null),
                new StepWaiting(0, true), new RunParked(0, "approval"));

        assertThat(render()).contains("traceworks resume &#39;" + RUN + "&#39;</pre>")
                .doesNotContain("--allow-host-execution");
    }

    @Test
    void pausedContainerRun_resumeHintHasNoHostFlag() {
        started("container");
        emit(new StepStarted(0, "each", "foreach", 0, false), new RunParked(0, RunParked.PAUSE));

        assertThat(render()).contains("continue with <code>traceworks resume &#39;" + RUN + "&#39;</code>")
                .doesNotContain("--allow-host-execution");
    }

    @Test
    void pausedRun_saysItIsPaused_andHowToGoOn() {
        started();
        emit(new StepStarted(0, "each", "foreach", 0, false), new RunParked(0, RunParked.PAUSE));

        String page = render();

        assertThat(page).contains("<h2>Paused</h2>", "continue with <code>traceworks resume &#39;" + RUN
                + "&#39; --allow-host-execution</code>", "<strong>RUNNING</strong> (paused: the resume runs it again")
                .doesNotContain("Waiting for an answer", "traceworks approve");
    }

    @Test
    void parkedRunWithARecordedAnswer_saysItIsAnswered_andHowToGoOn() {
        started();
        emit(new StepStarted(0, "ask", "approval", 0, false),
                new ApprovalRequested(0, "b1", "approval", "Ship?", List.of("yes", "no"), List.of(), null,
                        null, true, null, null, null),
                new RunParked(0, "approval"));
        RunRecorder next = RunRecorder.continueAfter(log, blobs, clock, RUN, definition(),
                log.readAll().stream().map(RecordedEvent::parse).toList());
        next.attributeNextAnswer("anna@box", "cli", "req-1", null);
        next.emit(new ApprovalAnswered(0, "approval", "yes"));

        String page = RunHtml.render(view(), blobs, new ChainReport(true, log.readAll().size(), next.head(),
                List.of(), List.of()), "1.2.3");

        assertThat(page).contains("answered &mdash; continue with <code>traceworks resume &#39;" + RUN
                + "&#39; --allow-host-execution</code>").doesNotContain("traceworks approve");
        assertThat(page).contains("<h2>Parked, and answered</h2>").doesNotContain("Waiting for an answer");
    }

    @Test
    void humanInputForm_getsOneFieldHintPerField() {
        started();
        emit(new StepStarted(0, "ask", "human_input", 0, false),
                new ApprovalRequested(0, "b1", "human-input", "Who and when?", List.of(), List.of(), "form", null,
                        true, null, null, List.of(Map.of("variable_name", "who"), Map.of("variable_name", "it's"))),
                new RunParked(0, "human-input"));

        assertThat(render()).contains("traceworks answer &#39;" + RUN + "&#39; --field &#39;who=&lt;value&gt;&#39;"
                + " --field &#39;it&#39;\\&#39;&#39;s=&lt;value&gt;&#39;\n").doesNotContain("--value")
                .as("a question without options names none").doesNotContain("<p>options:");
    }

    /** The header names the run, its status, who started it, how, when it started and ended, and the package. */
    @Test
    void header_namesTheRun_itsStatusActorTriggerTimesAndPackage_andTheGateWithItsSummary() {
        started();
        finishedFetch("hi");
        emit(new RunFinished(RunStatus.COMPLETED, Map.of("gate_status", "YELLOW", "gate_category", "quality",
                "gate_description", "two warnings", "gate_summary", "sum <b>"), null));
        RunView view = view();

        assertThat(render()).contains("<tr><th>run</th><td>" + RUN + "</td></tr>",
                "<tr><th>status</th><td>COMPLETED</td></tr>",
                "<tr><th>actor</th><td>flo@box</td></tr>",
                "<tr><th>trigger</th><td>MANUAL</td></tr>",
                "<tr><th>started</th><td>" + view.startedAt() + "</td></tr>",
                "<tr><th>finished</th><td>" + view.finishedAt() + "</td></tr>",
                "<tr><th>package</th><td>digest-1</td></tr>",
                "<br><code>sum &lt;b&gt;</code></td></tr>",
                "<details><summary>inputs</summary>");
    }

    /** What an intervention set aside is shown with it; one that kept nothing shows nothing after its words. */
    @Test
    void anIntervention_showsWhatItKept() {
        started();
        recorder.record("run.recovered", Map.of("discarded", "{\"seq\":9".getBytes(StandardCharsets.UTF_8)));
        recorder.record("run.resumed", Map.of("actor", "flo@box"));

        assertThat(render()).contains("a writer found a torn last line, set it aside and went on: <code>{&quot;seq&quot;:9"
                + "</code> <small>seq", "<td>resumed the run <small>seq");
    }

    /**
     * Where a command ran is never in doubt: one on the host is marked so at the command and at its step, one in a
     * container says so and its step carries no host mark.
     */
    @Test
    void aCommandOnTheHost_isMarkedAtTheCommandAndItsStep_andOneInAContainerIsNot() {
        started();
        emit(new StepStarted(0, "fetch", "shell", 0, false));
        recorder.recording(request -> new CommandResult(0, "", "", 1, false))
                .run(new CommandRequest("make", "/w", 30, null, null, null, false));
        emit(new StepFinished(0, "fetch", "shell", StepStatus.COMPLETED, "i", 5, Map.of()),
                new StepStarted(1, "summarise", "simple", 1, true));
        recorder.recording(request -> new CommandResult(0, "", "", 1, false))
                .run(CommandRequest.entrypoint("img", List.of("call"), "/w", 30, null, null, false));
        emit(new StepFinished(1, "summarise", "simple", StepStatus.COMPLETED, "i", 5, Map.of()));

        String html = render();
        String onTheHost = html.substring(html.indexOf("id=\"step-0\""), html.indexOf("id=\"step-1\""));
        String inAContainer = html.substring(html.indexOf("id=\"step-1\""));

        assertThat(onTheHost).contains("<small>shell</small> <span class=\"badge det\">deterministic</span>"
                        + " <span class=\"badge host\">host</span></h3>",
                "<div class=\"command\"><span class=\"badge host\">host</span> network <code>none</code> in <code>/w</code>",
                " &middot; 1 attempt</p>");
        assertThat(inAContainer).contains("<div class=\"command\"><span class=\"badge container\">container</span>"
                + " image <code>img</code>").doesNotContain("badge host");
    }

    /** The values of a command stand under it, each by name, escaped and cut like any content. */
    @Test
    void theValuesOfACommand_areShownUnderIt_byName() {
        started();
        emit(new StepStarted(0, "store", "shell", 0, false));
        Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("REVIEW", "it's <b>$(not run)</b>\nsecond line");
        values.put("LONG", "x".repeat(70_000));
        recorder.recording(request -> new CommandResult(0, "", "", 1, false))
                .run(new CommandRequest("printf '%s' \"$REVIEW\"", "/w", 30, null, null, null, false)
                        .withValues(values));
        emit(new StepFinished(0, "store", "shell", StepStatus.COMPLETED, "i", 5, Map.of()));

        String html = render();

        assertThat(html).contains("<pre>printf &#39;%s&#39; &quot;$REVIEW&quot;</pre>",
                "<details><summary>value <code>REVIEW</code> (34 characters)</summary><pre>it&#39;s"
                        + " &lt;b&gt;$(not run)&lt;/b&gt;\nsecond line</pre></details>",
                "<details><summary>value <code>LONG</code> (70000 characters)</summary><pre>",
                "<p class=\"cut\">cut at 65536 of 70000 characters; the full values are blob ");
    }

    @Test
    void verificationProblems_areShown() {
        started();
        ChainReport broken = new ChainReport(false, 3, "abc", List.of("seq 2: <bad> hash"), List.of("deadbeef"));

        String page = RunHtml.render(view(), blobs, broken, "1.2.3");

        assertThat(page).contains("seq 2: &lt;bad&gt; hash").contains("deadbeef").contains("NOT intact");
        assertThat(render()).contains("chain intact, ").contains(recorder.head());
    }

    @Test
    void render_isDeterministic() {
        started();
        finishedFetch("data");
        emit(new StepStarted(1, "summarise", "simple", 1, true));

        assertThat(render()).isEqualTo(render()).contains("1.2.3");
    }

    /**
     * A record Traceworks writes names a child run under one step. A crafted one can name the same child under many
     * steps, at every level, and a page that drew the child each time would grow without bound. It is drawn once.
     */
    @Test
    void aChildRunNamedByTwoSteps_isDrawnOnce_andTheSecondPlacePointsToIt() {
        started();
        emit(new StepStarted(0, "loop", "foreach", 0, false),
                new ChildRunEvent("child-1", new StepStarted(0, "inner", "shell", 0, false)),
                new ChildRunEvent("child-1", new RunFinished(RunStatus.COMPLETED, Map.of(), null)));
        recorder.record("step.finished", Map.of("step", 0, "block_name", "loop", "block_type", "foreach",
                "status", "COMPLETED", "input", "i", "duration_ms", 1));
        emit(new StepStarted(1, "again", "foreach", 1, false));
        recorder.record("foreach.item.finished", Map.of("step", 1, "item_index", 0, "total", 1, "status", "COMPLETED",
                "sub_run", "child-1"));

        String page = render();

        assertThat(page.split("id=\"run-child-1-step-0\"", -1)).as("the card of the child's step").hasSize(2);
        assertThat(page).contains("child run <code>child-1</code> (shown above, under the step that names it first)");
    }

    @Test
    void childRun_isNestedUnderItsStep() {
        started();
        emit(new StepStarted(0, "fetch", "sub_workflow", 0, false),
                new ChildRunEvent("child-1", new StepStarted(0, "inner", "shell", 0, false)),
                new ChildRunEvent("child-1", new RunFinished(RunStatus.COMPLETED, Map.of(), null)));
        recorder.record("step.finished", Map.of("step", 0, "block_name", "fetch", "block_type", "sub_workflow",
                "status", "COMPLETED", "input", "i", "duration_ms", 1, "subRunId", "child-1"));

        String page = render();

        assertThat(page).contains("child run <code>child-1</code>").contains("id=\"run-child-1-step-0\"");
    }

    @Test
    void parkedHints_areShellQuotedOnePerOption() {
        started();
        emit(new StepStarted(0, "ask", "approval", 0, false),
                new ApprovalRequested(0, "b1", "approval", "q", List.of("a; rm -rf ~", "it's", "x & y"), List.of(),
                        null, null, true, null, null, null),
                new RunParked(0, "approval"));

        String page = render();

        assertThat(page).contains("--answer &#39;a; rm -rf ~&#39;\n")
                .contains("--answer &#39;it&#39;\\&#39;&#39;s&#39;\n")
                .contains("--answer &#39;x &amp; y&#39;\n");
        assertThat(RunHtml.shellQuote("it's")).isEqualTo("'it'\\''s'");
    }

    @Test
    void humanInputHint_isAPlaceholder() {
        started();
        emit(new StepStarted(0, "ask", "human_input", 0, false),
                new ApprovalRequested(0, "b1", "human-input", "name?", List.of(), List.of(), "text", null, true,
                        null, null, null),
                new RunParked(0, "human-input"));

        assertThat(render()).contains("traceworks answer &#39;" + RUN + "&#39; --value &#39;&lt;text&gt;&#39;")
                .contains("traceworks resume &#39;" + RUN + "&#39;");
    }

    @Test
    void hostileMarkup_isEscapedInEveryRecordDerivedField() {
        String evil = "<b>x</b>\"'&";
        started();
        emit(new StepStarted(0, "n" + evil, "shell", 0, false),
                new ApprovalRequested(0, "b1", "approval", "q", List.of("o" + evil), List.of(), null, null, true,
                        null, null, null));
        var runner = recorder.recording(new traceworks.core.testing.FakeCommandRunner()
                .on("echo", traceworks.core.testing.FakeCommandRunner.ok("hi")));
        runner.run(new traceworks.core.port.CommandRunner.CommandRequest("echo", "/w" + evil, 30,
                Map.of("E" + evil, "v"), "img" + evil, List.of(), false, null));
        recorder.attributeNextAnswer("actor" + evil, "src" + evil, null, "comment" + evil);
        emit(new ApprovalAnswered(0, "approval", "answer" + evil),
                new ChildRunEvent("child" + evil, new StepStarted(0, "inner", "shell", 0, false)),
                new RunFinished(RunStatus.FAILED, Map.of(), "error" + evil));
        recorder.record("step.finished", Map.of("step", 0, "block_name", "n" + evil, "block_type", "shell",
                "status", "FAILED", "input", "i", "duration_ms", 1, "subRunId", "child" + evil));

        String page = render();

        for (String word : List.of("n", "o", "E", "/w", "img", "actor", "src", "comment", "answer", "child",
                "error")) {
            assertThat(page).as(word).contains(word + "&lt;b&gt;x&lt;/b&gt;&quot;&#39;&amp;");
        }
        assertThat(page).doesNotContain("<b>").doesNotContain("</b>");
    }

    /**
     * A direction override or a control character changes how a page reads: {@code "\u202Etcejer"} reads as
     * "reject" while the answer command copies the real option. Every field from the record shows such a character as
     * its code point, as a file name does; content (an output, an answer) shows its direction characters so too, and
     * keeps its lines.
     */
    @Test
    void directionAndControlCharacters_areShownByTheirCodePoint_inEveryRecordDerivedField() {
        String rlo = "\u202E";
        started();
        emit(new StepStarted(0, "n" + rlo, "shell", 0, false),
                new ApprovalRequested(0, "b1", "approval", "q" + rlo, List.of(rlo + "tcejer"), List.of(), null,
                        null, true, null, null, null),
                new GateReported(0, traceworks.core.model.GateStatus.RED, "cat" + rlo + "\u0007",
                        "desc" + rlo),
                new OutputRecorded(0, "n", "line one\nline " + rlo + "two"));
        var runner = recorder.recording(new traceworks.core.testing.FakeCommandRunner()
                .on("echo", traceworks.core.testing.FakeCommandRunner.ok("hi")));
        runner.run(new traceworks.core.port.CommandRunner.CommandRequest("echo", "/w" + rlo, 30,
                Map.of("E" + rlo, "v"), "img" + rlo + "\u001b[31m", List.of(), false, null));
        emit(new RunParked(0, "approval"));

        String page = render();

        assertThat(page).doesNotContain(rlo).doesNotContain("\u0007").doesNotContain("\u001b");
        assertThat(page).contains("--answer &#39;[U+202E]tcejer&#39;").contains("<code>[U+202E]tcejer</code>")
                .contains("cat[U+202E][U+0007]").contains("img[U+202E][U+001B][31m").contains("/w[U+202E]")
                .contains("E[U+202E]").contains("n[U+202E]").contains("line one\nline [U+202E]two");
    }

    /**
     * A tool result is whatever the model's tool read, a page it fetched included: it shows as text, as do the tool's
     * name, its arguments, the prompt and the answer.
     */
    @Test
    void stepCard_showsToolCalls_escaped() throws Exception {
        started();
        emit(new StepStarted(0, "summarise", "simple", 1, true));
        FakeAiCli cli = FakeAiCli.claude();
        cli.answersAfter("answer <i>x</i>",
                new ToolCall("Read<b>", "{\"file\":\"<a href=x>\"}", "<script>alert(1)</script>", false),
                new ToolCall("Grep", "{}", "no match", true));
        AiProviders providers = recorder.recording(AiProviders.of("CLAUDE", List.of(new CliAiProvider(cli,
                recorder.recording(request -> new CommandResult(0, "", "", 1, false)), "traceworks/claude:abc",
                Map.of("ANTHROPIC_API_KEY", "sk-ant-test-value-01")))));
        providers.resolve("CLAUDE").executeAction(new AiProvider.ActionRequest("prompt <u>p</u>", "opus", null,
                null, null, null, ToolPolicy.READ, false, null));
        emit(new StepFinished(0, "summarise", "simple", StepStatus.COMPLETED, "i", 5, Map.of()));

        String page = render();

        assertThat(page).contains("&lt;script&gt;alert(1)&lt;/script&gt;").doesNotContain("<script>alert");
        assertThat(page).contains("Read&lt;b&gt;").doesNotContain("Read<b>");
        assertThat(page).contains("{&quot;file&quot;:&quot;&lt;a href=x&gt;&quot;}");
        assertThat(page).contains("prompt &lt;u&gt;p&lt;/u&gt;").contains("answer &lt;i&gt;x&lt;/i&gt;");
        assertThat(page).contains("tools <code>read</code>").contains("CLAUDE").contains("opus");
        assertThat(page).contains("Grep").contains("tool error");
        assertThat(page).contains("fake-model").contains("100 in").contains("20 out");
        assertThat(page).as("the error is the failed tool's own").contains("tool <code>Grep</code> &middot; tool error")
                .doesNotContain("tool <code>Read&lt;b&gt;</code> &middot; tool error");
        assertThat(page).contains("<span class=\"badge ai\">AI call</span> CLAUDE model <code>opus</code> tools"
                + " <code>read</code> &middot; answered by <code>fake-model</code> &middot; 100 in / 20 out tokens"
                + " &middot; 0.01 USD &middot; 1 turn &middot; exit 0 &middot; 2 tool calls");
    }

    /** A failed AI call says why where the call is, not only behind "error": the first line of the CLI's error, escaped. */
    @Test
    void stepCard_namesWhyAnAiCallFailed() throws Exception {
        started();
        emit(new StepStarted(0, "summarise", "simple", 1, true));
        FakeAiCli cli = FakeAiCli.claude();
        cli.then(new AiCli.AiOutcome("\nCredit balance <b>is</b> too low " + "z".repeat(400) + "\nsecond line", 1, true,
                null, List.of()));
        AiProviders providers = recorder.recording(AiProviders.of("CLAUDE", List.of(new CliAiProvider(cli,
                recorder.recording(request -> new CommandResult(0, "", "", 1, false)), "traceworks/claude:abc",
                Map.of("ANTHROPIC_API_KEY", "sk-ant-test-value-01")))));
        try {
            providers.resolve("CLAUDE").executeAction(new AiProvider.ActionRequest("p", null, null, null));
        } catch (RuntimeException e) {
            // the failed call throws in the step; the record has it
        }
        emit(new StepFinished(0, "summarise", "simple", StepStatus.FAILED, "i", 5, Map.of()));

        String page = render();

        assertThat(page).contains("exit 1 &middot; <strong class=\"problem\">failed: Credit balance &lt;b&gt;is&lt;/b&gt;"
                + " too low zzz").contains("z...</strong>").doesNotContain("failed: Credit balance <b>");
    }

    /** A hostile outcome renders: a capped tool name, a blob of megabytes cut on the page, a lone surrogate. */
    @Test
    void stepCard_rendersAHostileOutcome() throws Exception {
        started();
        emit(new StepStarted(0, "summarise", "simple", 1, true));
        FakeAiCli cli = FakeAiCli.claude();
        cli.then(new AiCli.AiOutcome("ok", 0, false, null, List.of(new ToolCall("T".repeat(3_000_000),
                "{\"a\":\"" + "x".repeat(3_000_000) + "\"}", "half \uD800 a pair", false))));
        AiProviders providers = recorder.recording(AiProviders.of("CLAUDE", List.of(new CliAiProvider(cli,
                recorder.recording(request -> new CommandResult(0, "", "", 1, false)), "traceworks/claude:abc",
                Map.of("ANTHROPIC_API_KEY", "sk-ant-test-value-01")))));
        providers.resolve("CLAUDE").executeAction(new AiProvider.ActionRequest("p", null, null, null));
        emit(new StepFinished(0, "summarise", "simple", StepStatus.COMPLETED, "i", 5, Map.of()));

        String page = render();

        assertThat(page).contains("tool <code>" + "T".repeat(256) + "</code>").doesNotContain("T".repeat(257))
                .contains("half ? a pair").contains("cut at 65536 of 3000008 characters");
    }

    @Test
    void envNamesAndChildren_keepAStableOrder() {
        started();
        emit(new StepStarted(0, "fetch", "sub_workflow", 0, false));
        var runner = recorder.recording(new traceworks.core.testing.FakeCommandRunner()
                .on("echo", traceworks.core.testing.FakeCommandRunner.ok("hi")));
        runner.run(new traceworks.core.port.CommandRunner.CommandRequest("echo", "/w", 30,
                Map.of("ZED", "1", "ALPHA", "2", "MID", "3"), null, List.of(), false, null));
        emit(new ChildRunEvent("zz-child", new StepStarted(0, "i", "shell", 0, false)),
                new ChildRunEvent("aa-child", new StepStarted(0, "i", "shell", 0, false)));
        recorder.record("step.finished", Map.of("step", 0, "block_name", "fetch", "block_type", "sub_workflow",
                "status", "COMPLETED", "input", "i", "duration_ms", 1, "subRunId", "zz-child"));

        String page = render();

        assertThat(page.indexOf("<code>ALPHA</code>")).isPositive()
                .isLessThan(page.indexOf("<code>MID</code>"));
        assertThat(page.indexOf("<code>MID</code>")).isLessThan(page.indexOf("<code>ZED</code>"));
        assertThat(page.indexOf("child run <code>zz-child")).isPositive()
                .isLessThan(page.indexOf("child run <code>aa-child"));
    }

    /** The page says which workflow ran and how it is built: the package, and each block with the step it became. */
    @Test
    void workflowSection_showsThePackageAndEachBlockWithItsStep() {
        String definition = """
                {"id":"wf","description":"Fetches & summarises <b>.","blocks":[
                  {"type":"shell","name":"fetch","config":{"command":"echo hi"}},
                  {"type":"simple","name":"summarise","config":{"provider":"claude","model":"m1","prompt":"Sum up"}},
                  {"type":"shell","config":{"command":"echo never"}}]}""";
        recorder.runStarted(new RunStart("wf", "Wf", "/pkg", "digest-1", "abc123", true,
                definition.getBytes(StandardCharsets.UTF_8), Map.of(), TriggerSource.MANUAL, "flo@box", "0.1.0",
                "host", null, "/ws"));
        finishedFetch("hi");
        emit(new StepStarted(1, "summarise", "simple", 1, true),
                new StepFinished(1, "summarise", "simple", StepStatus.FAILED, "p", 5, Map.of()));

        String html = render();

        assertThat(html).contains("<h2>Workflow</h2>", "<p>Fetches &amp; summarises &lt;b&gt;.</p>",
                "<th>package</th><td>/pkg</td>", "<th>commit</th><td>abc123 (with uncommitted changes)</td>",
                "<th>digest</th><td>digest-1</td>",
                "<tr><th>definition</th><td>blob " + view().definition().sha256() + "</td></tr>");
        assertThat(html).contains("<tr><td>0</td><td>fetch</td><td>shell</td><td><code>echo hi</code></td>"
                        + "<td><a href=\"#step-0\">step 0</a> COMPLETED</td></tr>",
                "<td>summarise</td><td>simple <small>claude m1</small></td><td><code>Sum up</code></td>"
                        + "<td><a href=\"#step-1\">step 1</a> FAILED</td>",
                "<td>2</td><td><em>unnamed</em></td><td>shell</td><td><code>echo never</code></td>"
                        + "<td><span class=\"muted\">did not run</span></td>");
        assertThat(html.indexOf("<h2>Workflow</h2>")).isLessThan(html.indexOf("<h2>Steps</h2>"));
    }

    /** A run of a sealed package says so, with who sealed it and the digest the seal covers; another run has no such row. */
    @Test
    void workflowSection_namesTheSealOfASealedPackage() {
        recorder.runStarted(new RunStart("wf", "Wf", "/pkg", "digest-1", null, null,
                "{\"id\":\"wf\",\"blocks\":[]}".getBytes(StandardCharsets.UTF_8), Map.of(), TriggerSource.MANUAL,
                "flo@box", "0.1.0", "host", null, 16, Map.of(), "/ws", null, null, 128, null, List.of(), null,
                new traceworks.core.record.SealView("wf", "seal-digest-1", "flo <b>@box",
                        "2026-11-13T12:00:00Z", "SHA256:abc", List.of("release@example.org"), "sig")));

        String html = render();

        assertThat(html).contains("<th>seal</th><td>sealed by flo &lt;b&gt;@box at 2026-11-13T12:00:00Z, signed as"
                + " release@example.org with the key SHA256:abc; seal digest seal-digest-1</td>");
        assertThat(new RunHtmlTest().started().render()).contains("<th>seal</th><td>unsealed</td>");
    }

    /**
     * A run of a workflow with a model choice names the picked option, its provider and model; a run of any other
     * workflow has no such row, and its {@code run.started} no such field.
     */
    @Test
    void workflowSection_namesThePickedOptionOfAModelChoice() {
        recorder.runStarted(new RunStart("wf", "Wf", "/pkg", "digest-1", null, null,
                "{\"id\":\"wf\",\"blocks\":[]}".getBytes(StandardCharsets.UTF_8), Map.of("judge", "astra"),
                TriggerSource.MANUAL, "flo@box", "0.1.0", "host", null, 16, Map.of(), "/ws", null, null, 128, null,
                List.of(), null, null, List.of(new traceworks.core.model.ModelPick("judge", "astra",
                "PI", "openai/gpt-6-<astra>", false))));

        String html = render();

        assertThat(html).contains("<th>model choice judge</th><td>astra (picked): PI openai/gpt-6-&lt;astra&gt;</td>");
        assertThat(view().modelChoices()).singleElement().satisfies(pick -> {
            assertThat(pick.summary()).isEqualTo("judge = astra (picked): PI openai/gpt-6-<astra>");
            assertThat(pick.theDefault()).isFalse();
        });
        RunHtmlTest plain = new RunHtmlTest().started();
        assertThat(plain.render()).doesNotContain("model choice");
        assertThat(new String(plain.log.readAll().getFirst(), StandardCharsets.UTF_8))
                .doesNotContain("model_choices");
    }

    /** What each image holds is in the record, so the page says it: a name like ./images/areas says nothing. */
    @Test
    void workflowSection_listsTheImages_withBaseAndDescription_andTheStepCardRepeatsThem() {
        java.util.List<traceworks.core.container.ImageFacts> images = java.util.List.of(
                new traceworks.core.container.ImageFacts("shell", "debian:bookworm-slim",
                        "bash, coreutils, git, python3, jq & <ca-certificates>"),
                new traceworks.core.container.ImageFacts("./images/areas",
                        "eclipse-temurin:21-jdk-noble", "the area finder on JDK 21"),
                new traceworks.core.container.ImageFacts("alpine:3.20", null, null));
        recorder.runStarted(new RunStart("wf", "Wf", "/pkg", "digest-1", null, null,
                "{\"id\":\"wf\"}".getBytes(StandardCharsets.UTF_8), Map.of(), TriggerSource.MANUAL, "flo@box", "0.1.0",
                "container", new RunStart.Runtime("docker", "29.1.3"), 16, Map.of(), "/ws", null, null, 128, null, images));
        emit(new StepStarted(0, "fetch", "shell", 0, false));
        traceworks.core.port.CommandRunner.CommandFacts facts =
                new traceworks.core.port.CommandRunner.CommandFacts("/workspace", "./images/areas",
                        "sha256:abc", List.of(), false, List.of(), false);
        recorder.recording(new traceworks.core.port.CommandRunner() {
            @Override
            public CommandResult run(traceworks.core.port.CommandRunner.CommandRequest request) {
                throw new AssertionError("planned");
            }

            @Override
            public traceworks.core.port.CommandRunner.CommandPlan plan(
                    traceworks.core.port.CommandRunner.CommandRequest request) {
                return new traceworks.core.port.CommandRunner.CommandPlan(facts,
                        () -> new CommandResult(0, "", "", 1, false));
            }
        }).run(new traceworks.core.port.CommandRunner.CommandRequest("areas find", "/w", 30,
                Map.of(), null, null, false));
        emit(new StepFinished(0, "fetch", "shell", StepStatus.COMPLETED, "i", 5, Map.of()));

        String html = render();

        assertThat(html).contains("<h3>Images</h3>",
                "<tr><td><code>shell</code></td><td>debian:bookworm-slim</td>"
                        + "<td>bash, coreutils, git, python3, jq &amp; &lt;ca-certificates&gt;</td></tr>",
                "<tr><td><code>./images/areas</code></td><td>eclipse-temurin:21-jdk-noble</td>"
                        + "<td>the area finder on JDK 21</td></tr>",
                "<tr><td><code>alpine:3.20</code></td><td><span class=\"muted\">a registry image</span></td>"
                        + "<td></td></tr>");
        assertThat(html).contains("image <code>./images/areas</code> <small>eclipse-temurin:21-jdk-noble: the area"
                + " finder on JDK 21</small>");
        assertThat(html.indexOf("<h3>Images</h3>")).isLessThan(html.indexOf("<h2>Steps</h2>"));
    }

    @Test
    void workflowSection_withoutRecordedImages_hasNoImagesTable() {
        started();

        assertThat(render()).doesNotContain("<h3>Images</h3>");
    }

    @Test
    void workflowSection_withoutAReadableDefinition_saysSo() {
        recorder.runStarted(new RunStart("wf", "Wf", "/pkg", "digest-1", null, null,
                "nonsense".getBytes(StandardCharsets.UTF_8), Map.of(), TriggerSource.MANUAL, "flo@box", "0.1.0",
                "host", null, "/ws"));

        assertThat(render()).contains("<h2>Workflow</h2>", "<th>package</th><td>/pkg</td>",
                "the record holds no readable definition").doesNotContain("<th>commit</th>");
    }

    @Test
    void filesLine_namesTheFilesWhileCollapsed() {
        started();
        InMemoryRunStore.InMemoryScratch scratch = new InMemoryRunStore.InMemoryScratch();
        for (int i = 0; i < 8; i++) {
            scratch.put("f" + i + ".txt", "x".getBytes(StandardCharsets.UTF_8));
        }
        recorder.withScratch(scratch);
        emit(new StepStarted(0, "fetch", "shell", 0, false),
                new StepFinished(0, "fetch", "shell", StepStatus.COMPLETED, "i", 5, Map.of()));

        assertThat(render()).contains("<summary>files (8): <code>f0.txt</code>, <code>f1.txt</code>, "
                + "<code>f2.txt</code>, <code>f3.txt</code>, <code>f4.txt</code>, <code>f5.txt</code>, …</summary>");
    }

    @Test
    void stepCard_listsTheFilesOfTheStep() {
        started();
        InMemoryRunStore.InMemoryScratch scratch = new InMemoryRunStore.InMemoryScratch();
        scratch.put("out/report.txt", "hello".getBytes(StandardCharsets.UTF_8));
        scratch.put("<b>\u202eevil\u0007.txt", "x".getBytes(StandardCharsets.UTF_8));
        scratch.putLink("lnk", "../events.jsonl");
        recorder.withScratch(scratch);
        emit(new StepStarted(0, "fetch", "shell", 0, false),
                new StepFinished(0, "fetch", "shell", StepStatus.COMPLETED, "i", 5, Map.of()));
        String sha = traceworks.core.record.EventChain
                .sha256Hex("hello".getBytes(StandardCharsets.UTF_8));

        String html = render();

        assertThat(html).contains("<code>out/report.txt</code>").contains("<td>5</td>")
                .contains("<a href=\"blobs/" + sha + "\">");
        assertThat(html).contains("<code>lnk</code>").contains("<code>../events.jsonl</code>");
        assertThat(html).doesNotContain("\u202e").doesNotContain("\u0007").doesNotContain("<b>\u202e");
        // As visible text: a numeric reference would put the override itself back on the page
        assertThat(html).contains("&lt;b&gt;[U+202E]evil[U+0007].txt").doesNotContain("&#x202e;");
    }

    @Test
    void aLoneSurrogateInAFileName_isShownByItsCodePoint() {
        assertThat(StepCards.fileName("a\uD800b")).isEqualTo("a[U+D800]b");
    }

    @Test
    void aTagCharacterInAFileName_isShownByItsCodePoint() {
        started();
        InMemoryRunStore.InMemoryScratch scratch = new InMemoryRunStore.InMemoryScratch();
        scratch.put("a󠁁b", "x".getBytes(StandardCharsets.UTF_8));
        scratch.putUnreadable("dir󠁿", "permission denied");
        recorder.withScratch(scratch);
        emit(new StepStarted(0, "fetch", "shell", 0, false),
                new StepFinished(0, "fetch", "shell", StepStatus.COMPLETED, "i", 5, Map.of()));

        String html = render();

        assertThat(html).doesNotContain("󠁁").doesNotContain("󠁿");
        assertThat(html).contains("a[U+E0041]b").contains("dir[U+E007F]").contains("permission denied");
    }

    @Test
    void anUnassignedCodePointOfTheTagRange_isShownByItsCodePoint() {
        started();
        InMemoryRunStore.InMemoryScratch scratch = new InMemoryRunStore.InMemoryScratch();
        scratch.put("a󠿿b", "x".getBytes(StandardCharsets.UTF_8));
        recorder.withScratch(scratch);
        emit(new StepStarted(0, "fetch", "shell", 0, false),
                new StepFinished(0, "fetch", "shell", StepStatus.COMPLETED, "i", 5, Map.of()));

        assertThat(render()).doesNotContain("󠿿").contains("a[U+E0FFF]b");
    }

    /**
     * A command through the egress proxy shows its allowed hosts and every connection; a denied one cannot be
     * overlooked: marked at the connection, at the step and in the run's summary, which counts them.
     */
    @Test
    void aDeniedConnection_isMarkedAtTheCommand_theStep_andInTheSummary() {
        started();
        emit(new StepStarted(0, "fetch", "simple", 0, true));
        recorder.recording(request -> new CommandResult(0, "", "", 1, false).withConnections(List.of(
                        new Connection("api.example:443", "ALLOW", 3), new Connection("evil.example:443", "DENY", 2),
                        new Connection("<b>x", "FAIL", 1))))
                .run(CommandRequest.entrypoint("img", List.of("call"), "/w", 30, null, null, false)
                        .withNetwork(Egress.hosts(List.of("api.example:443", "b.example:8443"))));
        emit(new StepFinished(0, "fetch", "simple", StepStatus.COMPLETED, "i", 5, Map.of()));

        String html = render();

        assertThat(html).contains("network <code>hosts</code> <code>api.example:443</code> <code>b.example:8443</code>");
        assertThat(html).contains("egress ALLOW <code>api.example:443</code> &times;3");
        assertThat(html).contains("<strong class=\"problem\">egress DENIED <code>evil.example:443</code> &times;2"
                + "</strong>");
        assertThat(html).contains("egress FAIL <code>&lt;b&gt;x</code> &times;1").doesNotContain("<b>x");
        assertThat(html).contains("<span class=\"badge problem\">2 connections denied</span>");
        assertThat(html).contains("<tr><th>AI network</th><td>restricted</td></tr>");
        assertThat(html).contains("<tr><th>egress</th><td>6 connections asked of the egress proxy by 1 command,"
                + " <strong class=\"problem\">2 denied</strong></td></tr>");
    }

    /**
     * A command whose output was cut at the bound cannot be overlooked: marked at the command, at the step and in
     * the run's summary. The step's status is as it was.
     */
    @Test
    void anOutputCut_isMarkedAtTheCommand_theStep_andInTheSummary() {
        started();
        emit(new StepStarted(0, "serve", "shell", 0, false));
        recorder.recording(request -> new CommandResult(0, "", "", 1, false)
                        .withOutputCut(new traceworks.core.port.CommandRunner.OutputCut(List.of("stdout"), 5000)))
                .run(new CommandRequest("serve &", "/w", 30, null, null, null, false));
        emit(new StepFinished(0, "serve", "shell", StepStatus.COMPLETED, "i", 5, Map.of()));

        String html = render();

        assertThat(html).contains("<strong class=\"problem\">output cut: stdout after 5000 ms</strong>");
        assertThat(html).contains("<span class=\"badge problem\">output cut</span>");
        assertThat(html).contains("<tr><th>output</th><td><strong class=\"problem\">1 command whose output was cut"
                + "</strong> before the end of its stream: it may be incomplete</td></tr>");
        assertThat(html).contains("status <strong>COMPLETED</strong>");
    }

    /** A record without the field, as every record of an earlier version, shows no cut. */
    @Test
    void aCommandWithoutAnOutputCut_isNotMarked() {
        started();
        emit(new StepStarted(0, "serve", "shell", 0, false));
        recorder.recording(request -> new CommandResult(0, "", "", 1, false))
                .run(new CommandRequest("ls", "/w", 30, null, null, null, false));
        emit(new StepFinished(0, "serve", "shell", StepStatus.COMPLETED, "i", 5, Map.of()));

        assertThat(render()).doesNotContain("output cut").doesNotContain("<th>output</th>");
    }

    /** One denied connection is named in the singular. */
    @Test
    void oneDeniedConnection_isNamedInTheSingular() {
        started();
        emit(new StepStarted(0, "fetch", "simple", 0, true));
        recorder.recording(request -> new CommandResult(0, "", "", 1, false).withConnections(List.of(
                        new Connection("evil.example:443", "DENY", 1))))
                .run(CommandRequest.entrypoint("img", List.of("call"), "/w", 30, null, null, false)
                        .withNetwork(Egress.hosts(List.of("api.example:443"))));
        emit(new StepFinished(0, "fetch", "simple", StepStatus.COMPLETED, "i", 5, Map.of()));

        assertThat(render()).contains("<span class=\"badge problem\">1 connection denied</span>");
    }

    /** A command that ran out of time says so; one that ended in time does not. */
    @Test
    void aCommandThatTimedOut_isMarked_andOneThatEndedInTimeIsNot() {
        started();
        emit(new StepStarted(0, "slow", "shell", 0, false));
        recorder.recording(request -> new CommandResult(124, "", "", 30_000, true))
                .run(new CommandRequest("sleep 60", "/w", 30, null, null, null, false));
        emit(new StepFinished(0, "slow", "shell", StepStatus.FAILED, "i", 5, Map.of()),
                new StepStarted(1, "fast", "shell", 1, false));
        recorder.recording(request -> new CommandResult(0, "", "", 1, false))
                .run(new CommandRequest("true", "/w", 30, null, null, null, false));
        emit(new StepFinished(1, "fast", "shell", StepStatus.COMPLETED, "i", 5, Map.of()));

        String html = render();

        assertThat(html).containsOnlyOnce("timed out");
        assertThat(html.indexOf("timed out")).isLessThan(html.indexOf("fast"));
    }

    /** A step that ended with its process is marked interrupted; it is not paused, since the run was not. */
    @Test
    void anInterruptedStep_ofARunThatWasNotPaused_isInterrupted_notPaused() {
        started();
        emit(new StepStarted(0, "build", "shell", 0, false));

        String html = render();

        assertThat(html).contains("<strong>RUNNING</strong> (interrupted)").doesNotContain("(paused");
    }

    /** A file whose blob reference is no sha256 is not linked: the reference is never written into a link. */
    @Test
    void aFileWithABlobReferenceThatIsNoSha256_isNotLinked() {
        started();
        emit(new StepStarted(0, "fetch", "shell", 0, false));
        recorder.record(traceworks.core.record.RecordTypes.STEP_FILES, Map.of("step", 0,
                "files", List.of(Map.of("path", "a.txt", "size", 1, "blob",
                        Map.of("sha256", "\"><script>x</script>", "size", 1)))));
        emit(new StepFinished(0, "fetch", "shell", StepStatus.COMPLETED, "i", 5, Map.of()));

        String html = render();

        assertThat(html).contains("<code>a.txt</code>").doesNotContain("<script>x").doesNotContain("href=\"blobs/\"");
    }

    /** Without a denied connection nothing is marked, and a run without the proxy has no egress row. */
    @Test
    void egressWithoutADeniedConnection_isNotMarked_andARunWithoutTheProxyHasNoRow() {
        started();
        emit(new StepStarted(0, "fetch", "simple", 0, true));
        String before = render();
        recorder.recording(request -> new CommandResult(0, "", "", 1, false).withConnections(List.of(
                        new Connection("api.example:443", "ALLOW", 1))))
                .run(CommandRequest.entrypoint("img", List.of("call"), "/w", 30, null, null, false)
                        .withNetwork(Egress.hosts(List.of("api.example:443"))));
        emit(new StepFinished(0, "fetch", "simple", StepStatus.COMPLETED, "i", 5, Map.of()));

        String html = render();

        assertThat(before).doesNotContain("<th>egress</th>");
        assertThat(html).contains("<tr><th>egress</th><td>1 connection asked of the egress proxy by 1 command,"
                + " none denied</td></tr>").doesNotContain("DENIED").doesNotContain("connections denied");
    }

    /** The operator's hosts and an open AI network are in the header, as the run started with them. */
    @Test
    void theAiNetworkOfTheRun_isInTheHeader() {
        recorder.runStarted(new RunStart("wf", "Wf", "/pkg", "digest-1", null, null,
                "{\"id\":\"wf\"}".getBytes(StandardCharsets.UTF_8), Map.of(), TriggerSource.MANUAL, "flo@box", "0.1.0",
                "container", null, 16, Map.of(), "/ws", null, null, 128, null, List.of(),
                new AiNetwork(true, Map.of("claude", List.of("gateway.example:8443"), "pi", List.of("a.example:1",
                        "b.example:2")))));

        assertThat(render()).contains("<tr><th>AI network</th><td>open (every AI container can connect anywhere)"
                + " &middot; more hosts for claude: gateway.example:8443 &middot; more hosts for pi: a.example:1,"
                + " b.example:2</td></tr>");
    }

    /**
     * A call with web shows the mode and the hosts next to its tools. With an open AI network the hosts are recorded
     * but nothing enforces them, and the header says so where it marks the open network.
     */
    @Test
    void anAiCallWithWeb_showsItsModeAndHosts_andAnOpenNetworkSaysTheyAreNotEnforced() throws Exception {
        recorder.runStarted(new RunStart("wf", "Wf", "/pkg", "digest-1", null, null,
                ("{\"id\":\"wf\",\"blocks\":[{\"type\":\"simple\",\"name\":\"look\",\"config\":{\"command\":\"Look\","
                        + "\"web\":\"fetch\",\"web_hosts\":[\"*\"]}}]}").getBytes(StandardCharsets.UTF_8), Map.of(),
                TriggerSource.MANUAL, "flo@box", "0.1.0", "container", null, 16, Map.of(), "/ws", null, null, 128, null,
                List.of(), new AiNetwork(true, Map.of())));
        emit(new StepStarted(0, "look", "simple", 0, true));
        FakeAiCli cli = FakeAiCli.claude();
        cli.answers("found");
        AiProviders providers = recorder.recording(AiProviders.of("CLAUDE", List.of(new CliAiProvider(cli,
                recorder.recording(request -> new CommandResult(0, "", "", 1, false)), "traceworks/claude:1",
                Map.of("ANTHROPIC_API_KEY", "sk-ant-test-value-01"), new AiNetwork(true, Map.of())))));
        providers.resolve("CLAUDE").executeAction(new AiProvider.ActionRequest("Look", null, null, "/w", 30, null,
                ToolPolicy.READ, false, null).withWeb(new WebAccess(WebAccess.Mode.FETCH, List.of("*"))));
        emit(new StepFinished(0, "look", "simple", StepStatus.COMPLETED, "Look", 5, Map.of()));

        String html = render();

        assertThat(html).contains("tools <code>read</code> web <code>fetch</code> <code>*</code>");
        assertThat(html).contains("<tr><th>AI network</th><td>open (every AI container can connect anywhere; the"
                + " web_hosts of a step are recorded, but not enforced)</td></tr>");
        assertThat(html).as("the plan of the workflow names the web mode of the block")
                .contains("<small>web fetch</small>");
    }

    /** Whether a container could write to a host directory is what a reader of the record wants to know of a mount. */
    @Test
    void aCommandsMounts_areShownWithHostPathContainerPath_andWhetherTheyAreReadOnly() {
        started();
        emit(new StepStarted(0, "fetch", "shell", 0, false));
        recorder.recording(new traceworks.core.testing.FakeCommandRunner()
                        .on("echo", traceworks.core.testing.FakeCommandRunner.ok("hi")))
                .run(new CommandRequest("echo", "/w", 30, Map.of(), "img", List.of(
                        new traceworks.core.port.CommandRunner.Mount("/host/ws", "/workspace", true),
                        new traceworks.core.port.CommandRunner.Mount("/host/scratch", "/scratch",
                                false)), false, null));
        emit(new StepFinished(0, "fetch", "shell", StepStatus.COMPLETED, "i", 5, Map.of()));

        String page = render();

        assertThat(page).contains("mount <code>/host/ws : /workspace (ro)</code>")
                .contains("mount <code>/host/scratch : /scratch (rw)</code>");
    }

    /** {@code step_<n>_output} links the step with that index; a number no step has is shown without a link. */
    @Test
    void dataFlow_linksAStepOutputVariableToItsStep_andOnlyWhenThatStepExists() {
        BlockDefinition fetch = BlockDefinition.builder().type("shell").name("fetch")
                .config(Map.of("command", "echo hi")).build();
        BlockDefinition use = BlockDefinition.builder().type("simple").name("summarise")
                .config(Map.of("prompt", "{{ step_0_output }} {{ step_7_output }}")).build();
        InMemoryEventLog ownLog = new InMemoryEventLog();
        RunRecorder own = RunRecorder.start(ownLog, blobs, clock, RUN, new WorkflowDefinition("wf", "Wf", null,
                List.of(fetch, use), null, null, null, null, null, null, null, null, null));
        own.runStarted(new RunStart("wf", "Wf", "/pkg", "digest-1", null, null,
                "{\"id\":\"wf\"}".getBytes(StandardCharsets.UTF_8), Map.of(), TriggerSource.MANUAL,
                "flo@box", "0.1.0", "host", null, "/ws"));
        own.emit(new StepStarted(0, "fetch", "shell", 0, false));
        own.emit(new StepFinished(0, "fetch", "shell", StepStatus.COMPLETED, "i", 5, Map.of()));
        own.emit(new StepStarted(1, "summarise", "simple", 1, true));
        own.emit(new StepFinished(1, "summarise", "simple", StepStatus.COMPLETED, "i", 5, Map.of()));
        List<RecordedEvent> events = ownLog.readAll().stream().map(RecordedEvent::parse).toList();

        String page = RunHtml.render(RunView.of(events, false), blobs,
                new ChainReport(true, events.size(), own.head(), List.of(), List.of()), "1.2.3");

        assertThat(page).contains("<a href=\"#step-0\"><code>step_0_output</code></a>");
        assertThat(page).contains("<code>step_7_output</code>").doesNotContain("href=\"#step-7\"");
    }

    @Test
    void stepCard_namesTheFilesAStepRemoved_andSaysNothingOfRemovalsWhereThereAreNone() {
        started();
        InMemoryRunStore.InMemoryScratch scratch = new InMemoryRunStore.InMemoryScratch();
        scratch.put("draft.txt", "x".getBytes(StandardCharsets.UTF_8));
        scratch.put("keep.txt", "y".getBytes(StandardCharsets.UTF_8));
        recorder.withScratch(scratch);
        finishedFetch("data");
        scratch.remove("draft.txt");
        scratch.put("final.txt", "z".getBytes(StandardCharsets.UTF_8));
        emit(new StepStarted(1, "summarise", "simple", 1, true),
                new StepFinished(1, "summarise", "simple", StepStatus.COMPLETED, "i", 5, Map.of()));

        String page = render();

        assertThat(page).contains("<p>removed: <code>draft.txt</code></p>");
        assertThat(page.split("<p>removed:", -1)).as("only the step that removed a file says so").hasSize(2);
    }
}
