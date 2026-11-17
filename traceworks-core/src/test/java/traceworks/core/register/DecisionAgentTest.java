package traceworks.core.register;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.port.RegisterStore;
import traceworks.core.record.ChainReport;
import traceworks.core.register.Reconciler.Report;
import traceworks.core.testing.InMemoryRegisterStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A decision names who made it: the actor who ran the command, and the agent that acted under that actor when one
 * stated itself. The agent stands in the line of the decision and in every state after it, a register that holds
 * one says so before its first such line, and a register without one has the bytes it always had.
 */
class DecisionAgentTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant T1 = Instant.parse("2026-11-17T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-17T11:00:00Z");
    private static final String FIXTURE = "/registers/decided-before-agent/";

    private final RegisterState empty = RegisterState.empty("arch", "wf-1", "architecture");

    private static Report report(RegisterStore.Opened opened, String... subjects) {
        List<ReportedFinding> findings = new java.util.ArrayList<>();
        for (String subject : subjects) {
            findings.add(new ReportedFinding("size", subject, Severity.LOW, subject + " is long", "d", "E1",
                    List.of()));
        }
        return new Report("run-1", T1, opened.blobs().put("# run-1".getBytes(StandardCharsets.UTF_8)).sha256(),
                findings, Set.of());
    }

    private static Finding of(RegisterState state, String subject) {
        return state.findings().stream().filter(f -> f.subject().equals(subject)).findFirst().orElseThrow();
    }

    private static List<Finding> replacing(RegisterState state, Finding after) {
        return state.findings().stream().map(f -> f.id().equals(after.id()) ? after : f).toList();
    }

    private static RegisterState dismiss(RegisterWriter writer, RegisterState state, String subject, String agent,
                                         Instant at) {
        Finding after = Disposition.dismiss(of(state, subject), "not ours", "flo@host", at, agent);
        return writer.disposition(state.with(replacing(state, after)), after, FindingStatus.DISMISSED, "not ours",
                "flo@host", agent, at, null);
    }

    private static void holds(RegisterStore.Opened opened) {
        ChainReport report = RegisterVerifier.verify("arch", opened.events().readAll(), opened.blobs(),
                opened.state());
        assertThat(report.intact()).as(report.problems().toString()).isTrue();
    }

    // --- the name ---

    @Test
    void theOptionWins_theVariableIsTheFallback_andWithoutEitherThereIsNoAgent() {
        assertThat(DecisionAgent.resolve("claude-code", Map.of("TRACEWORKS_AGENT", "harness"))).isEqualTo("claude-code");
        assertThat(DecisionAgent.resolve(null, Map.of("TRACEWORKS_AGENT", "harness"))).isEqualTo("harness");
        assertThat(DecisionAgent.resolve(null, Map.of())).isNull();
        assertThat(DecisionAgent.resolve("  Claude Code 2.1  ", Map.of())).as("the white space around it is no part")
                .isEqualTo("Claude Code 2.1");
        assertThat(DecisionAgent.resolve("pi:astra/session@host_1+a", Map.of())).isEqualTo("pi:astra/session@host_1+a");
        assertThat(DecisionAgent.resolve("a".repeat(64), Map.of())).hasSize(64);
    }

    /** A harness that sets the variable to nothing meant to name itself: it is refused, not taken for a person. */
    @Test
    void anEmptyOrBlankName_isRefused_notIgnored_fromEitherSource() {
        assertThatThrownBy(() -> DecisionAgent.resolve("", Map.of())).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--agent is blank: name the agent, or leave it out for a decision a person makes");
        assertThatThrownBy(() -> DecisionAgent.resolve(" \t", Map.of("TRACEWORKS_AGENT", "harness")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("--agent is blank");
        assertThatThrownBy(() -> DecisionAgent.resolve(null, Map.of("TRACEWORKS_AGENT", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("TRACEWORKS_AGENT is blank: name the agent, or leave it out for a decision a person makes");
        assertThatThrownBy(() -> DecisionAgent.resolve(null, Map.of("TRACEWORKS_AGENT", "  ")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("TRACEWORKS_AGENT is blank");
    }

    @Test
    void aNameBeyondTheBounds_isRefused_andTheMessageShowsNoCharacterOfIt() {
        assertThatThrownBy(() -> DecisionAgent.resolve("a".repeat(65), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--agent takes a name of at most 64 characters, got 65");
        for (String bad : List.of("claude\ncode", "claude\u001b[2Jcode", "claude\tcode", "a  b", "a|b", "a (b)",
                "a<b>", "a\"b", "a'b", "a,b", "a\\b", "a‮b", "a·b", "claudé", "a b", "a;b")) {
            assertThatThrownBy(() -> DecisionAgent.resolve(bad, Map.of())).as(bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("--agent takes a name of letters, digits, the characters . _ - : / @ +"
                            + " and single spaces, all ASCII; got U+")
                    .satisfies(e -> assertThat(e.getMessage().chars().allMatch(c -> c >= 0x20 && c < 0x7f)).isTrue());
        }
        assertThatThrownBy(() -> DecisionAgent.resolve("a\u001bb", Map.of())).hasMessageEndingWith("got U+001B at 2");
        assertThatThrownBy(() -> DecisionAgent.resolve("ab  c", Map.of()))
                .as("the second of two spaces").hasMessageEndingWith("got U+0020 at 4");
    }

    @Test
    void theWordsOfAView_nameTheAgentAfterTheActor_andSayNothingWithoutOne() {
        assertThat(DecisionAgent.through("claude-code")).isEqualTo(" through the agent claude-code");
        assertThat(DecisionAgent.through(null)).isEmpty();
    }

    // --- the log and the state ---

    @Test
    void theLineOfADecision_carriesTheAgentBesideTheActor_andTheStateDoesToo() throws IOException {
        InMemoryRegisterStore store = new InMemoryRegisterStore();
        try (RegisterStore.Opened opened = store.openForWrite("arch")) {
            RegisterLog log = new RegisterLog(opened, () -> T1);
            RegisterWriter writer = new RegisterWriter(log, opened.blobs());
            writer.created("wf-1", "architecture");
            RegisterState state = writer.applied(empty, report(opened, "A.java", "B.java"), null, "head-1").state();

            state = dismiss(writer, state, "A.java", "claude-code", T1);
            Finding accepted = Disposition.accept(of(state, "B.java"), "known", "flo@host", T2, null, "pi astra");
            state = writer.disposition(state.with(replacing(state, accepted)), accepted, FindingStatus.ACCEPTED_RISK,
                    "known", "flo@host", "pi astra", T2, accepted.disposition().until());
            opened.writeState(StateJson.write(state));

            List<RegisterEvent> events = log.read();
            assertThat(events).extracting(RegisterEvent::type).containsExactly("register.created",
                    "register.requires", "report.applied", "finding.new", "finding.new", "register.requires",
                    "finding.dismissed", "finding.accepted");
            assertThat(events.get(5).text("feature")).isEqualTo("decision_agent");
            assertThat(events.get(6).text("actor")).as("the actor is who it always was").isEqualTo("flo@host");
            assertThat(events.get(6).text("agent")).isEqualTo("claude-code");
            assertThat(events.get(7).text("actor")).isEqualTo("flo@host");
            assertThat(events.get(7).text("agent")).isEqualTo("pi astra");
            assertThat(of(state, "A.java").disposition().agent()).isEqualTo("claude-code");
            assertThat(of(state, "A.java").disposition().actor()).isEqualTo("flo@host");
            JsonNode json = JSON.readTree(StateJson.write(state));
            assertThat(json.path("findings")).allSatisfy(f -> assertThat(f.path("disposition").path("agent").asText())
                    .isIn("claude-code", "pi astra"));
            assertThat(StateJson.read(StateJson.write(state)).findings()).extracting(f -> f.disposition().agent())
                    .containsExactlyInAnyOrder("claude-code", "pi astra");
            assertThat(RegisterReplay.replay("arch", events, opened.blobs())).isEqualTo(state);
            holds(opened);
        }
    }

    @Test
    void aDecisionWithoutAnAgent_isTheLineItAlwaysWas_andTheRegisterRequiresNothingNew() {
        InMemoryRegisterStore store = new InMemoryRegisterStore();
        try (RegisterStore.Opened opened = store.openForWrite("arch")) {
            RegisterLog log = new RegisterLog(opened, () -> T1);
            RegisterWriter writer = new RegisterWriter(log, opened.blobs());
            writer.created("wf-1", "architecture");
            RegisterState state = writer.applied(empty, report(opened, "A.java"), null, "head-1").state();

            state = dismiss(writer, state, "A.java", null, T1);
            opened.writeState(StateJson.write(state));

            List<RegisterEvent> events = log.read();
            assertThat(events).extracting(RegisterEvent::type).containsExactly("register.created",
                    "register.requires", "report.applied", "finding.new", "finding.dismissed");
            assertThat(events.get(1).text("feature")).isEqualTo("decision_severity");
            assertThat(events.getLast().fields().has("agent")).as("no key, not a null").isFalse();
            assertThat(of(state, "A.java").disposition().agent()).isNull();
            assertThat(new String(StateJson.write(state), StandardCharsets.UTF_8)).doesNotContain("agent");
            assertThat(new String(RegisterFiles.of(state, null, null).get(RegisterFiles.FINDINGS),
                    StandardCharsets.UTF_8)).doesNotContain("agent");
            holds(opened);
        }
    }

    /** One line says it for the whole log: the second decision of an agent adds no second one. */
    @Test
    void theRequirement_isWrittenOnce_beforeTheFirstLineWithAnAgent_alsoForAReopening() {
        InMemoryRegisterStore store = new InMemoryRegisterStore();
        try (RegisterStore.Opened opened = store.openForWrite("arch")) {
            RegisterLog log = new RegisterLog(opened, () -> T1);
            RegisterWriter writer = new RegisterWriter(log, opened.blobs());
            writer.created("wf-1", "architecture");
            RegisterState state = writer.applied(empty, report(opened, "A.java", "B.java"), null, "head-1").state();
            state = dismiss(writer, state, "A.java", null, T1);

            Finding open = Disposition.reopen(of(state, "A.java"), "look again", "flo@host", T2);
            state = writer.disposition(state.with(replacing(state, open)), open, FindingStatus.OPEN, "look again",
                    "flo@host", "claude-code", T2, null);
            state = dismiss(writer, state, "A.java", "claude-code", T2);
            state = dismiss(writer, state, "B.java", "claude-code", T2);
            opened.writeState(StateJson.write(state));

            List<RegisterEvent> events = log.read();
            assertThat(events).extracting(RegisterEvent::type).containsExactly("register.created",
                    "register.requires", "report.applied", "finding.new", "finding.new", "finding.dismissed",
                    "register.requires", "finding.reopened", "finding.dismissed", "finding.dismissed");
            assertThat(events.get(6).text("feature")).isEqualTo("decision_agent");
            assertThat(events.get(7).text("agent")).as("a reopening names its agent in its line").isEqualTo("claude-code");
            assertThat(events.get(7).text("reason")).isEqualTo("manual");
            assertThat(RegisterReplay.replay("arch", events, opened.blobs())).isEqualTo(state);
            holds(opened);
        }
    }

    private static RegisterEvent line(int seq, String type, String json) {
        return RegisterEvent.parse(("{\"seq\":" + seq + ",\"at\":\"2026-11-11T10:00:00.000Z\",\"type\":\"" + type
                + "\",\"register\":\"arch\",\"prev\":\"p\"," + json + "}").getBytes(StandardCharsets.UTF_8));
    }

    /** The version before this one refuses the feature it does not know with these words: its code is unchanged here. */
    @Test
    void theRequirementOfAnAgent_isKnownToThisVersion_andAnotherOneIsStillRefused() {
        InMemoryRegisterStore store = new InMemoryRegisterStore();
        try (RegisterStore.Opened opened = store.open("arch")) {
            RegisterState state = RegisterReplay.replay("arch", List.of(line(0, "register.requires",
                    "\"feature\":\"decision_agent\"")), opened.blobs());

            assertThat(state.namesDecidedSeverity()).as("it says nothing about the severity").isFalse();
            assertThatThrownBy(() -> RegisterReplay.replay("arch", List.of(line(0, "register.requires",
                    "\"feature\":\"decision_signature\"")), opened.blobs())).isInstanceOf(IllegalStateException.class)
                    .hasMessage("seq 0: the register requires 'decision_signature', which this version of Traceworks"
                            + " does not know: a newer version wrote it");
        }
    }

    // --- the files a run gets, and the list for a script ---

    @Test
    void theFilesOfARun_andTheListForAScript_nameTheAgent_ofAListedAndOfAResolvedFinding() throws IOException {
        Finding.DispositionRecord byAgent = new Finding.DispositionRecord(FindingStatus.DISMISSED, "not ours",
                "flo@host", T1, null, null, Severity.LOW, "claude-code");
        Finding listed = new Finding("a".repeat(64), "size", "A.java", Severity.LOW, "A is long", "d", "E1",
                List.of(), FindingStatus.DISMISSED, new Finding.Seen("run-1", T1), new Finding.Seen("run-1", T1), 1, 0,
                false, null, byAgent);
        Finding resolved = new Finding("b".repeat(64), "size", "B.java", Severity.LOW, "B is long", "d", "E1",
                List.of(), FindingStatus.RESOLVED, new Finding.Seen("run-1", T1), new Finding.Seen("run-1", T1), 1, 0,
                false, T2, byAgent);
        RegisterState state = new RegisterState(1, "arch", "wf-1", "architecture", null, List.of(listed, resolved), 1);

        Map<String, byte[]> files = RegisterFiles.of(state, null, null);

        for (String name : List.of(RegisterFiles.FINDINGS, RegisterFiles.RESOLVED)) {
            JsonNode disposition = JSON.readTree(files.get(name)).path("disposition");
            assertThat(disposition.path("actor").asText()).as(name).isEqualTo("flo@host");
            assertThat(disposition.path("agent").asText()).as(name).isEqualTo("claude-code");
        }
        JsonNode list = JSON.readTree(StateJson.findings(List.of(listed)));
        assertThat(list.get(0).path("disposition").path("agent").asText()).isEqualTo("claude-code");
    }

    // --- a register the version before this one wrote: three decisions and a reopening, none with an agent ---

    private static byte[] resource(String name) {
        try (InputStream in = DecisionAgentTest.class.getResourceAsStream(FIXTURE + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The fixture in a store: the lines, the blobs and the state file as that version wrote them. */
    private static InMemoryRegisterStore earlierRegister() {
        InMemoryRegisterStore store = new InMemoryRegisterStore();
        String text = new String(resource("events.jsonl"), StandardCharsets.UTF_8);
        try (RegisterStore.Opened opened = store.openForWrite("arch")) {
            for (String line : text.split("\n")) {
                opened.events().append(line.getBytes(StandardCharsets.UTF_8));
                for (var fields = RegisterEvent.parse(line.getBytes(StandardCharsets.UTF_8)).fields().fields();
                     fields.hasNext(); ) {
                    var field = fields.next();
                    if (field.getKey().endsWith("_blob") && field.getValue().isTextual()) {
                        store.blobs("arch").putRaw(field.getValue().asText(), resource("blobs/"
                                + field.getValue().asText()));
                    }
                }
            }
            opened.writeState(resource("state.json"));
        }
        return store;
    }

    @Test
    void aRegisterOfTheVersionBefore_verifiesUnchanged_andFoldsToTheBytesItRecorded() {
        InMemoryRegisterStore store = earlierRegister();
        try (RegisterStore.Opened opened = store.open("arch")) {
            holds(opened);
            RegisterState state = RegisterReplay.replay("arch", new RegisterLog(opened, () -> T1).read(),
                    opened.blobs());

            assertThat(StateJson.write(state)).isEqualTo(resource("state.json"));
            assertThat(state.findings()).filteredOn(f -> f.disposition() != null).hasSize(2)
                    .allSatisfy(f -> assertThat(f.disposition().agent()).isNull());
            assertThat(RegisterFiles.of(state, null, null).get(RegisterFiles.FINDINGS)).asString().doesNotContain("agent");
        }
    }

    /** What this version adds to such a register without an agent is what that version would have added. */
    @Test
    void aDecisionWithoutAnAgentOnARegisterOfTheVersionBefore_addsNoRequirement() {
        InMemoryRegisterStore store = earlierRegister();
        try (RegisterStore.Opened opened = store.openForWrite("arch")) {
            RegisterLog log = new RegisterLog(opened, () -> T1);
            RegisterState state = RegisterReplay.replay("arch", log.read(), opened.blobs());
            int before = log.read().size();

            state = dismiss(new RegisterWriter(log, opened.blobs()), state, "C.java", null, T1);
            opened.writeState(StateJson.write(state));

            List<RegisterEvent> events = log.read();
            assertThat(events).hasSize(before + 1);
            assertThat(events.getLast().type()).isEqualTo("finding.dismissed");
            assertThat(events.getLast().fields().has("agent")).isFalse();
            assertThat(events).filteredOn(e -> "register.requires".equals(e.type())).extracting(e -> e.text("feature"))
                    .containsExactly("decision_severity");
            holds(opened);
        }
    }

    @Test
    void aDecisionOfAnAgentOnARegisterOfTheVersionBefore_statesTheRequirementFirst_andVerifies() {
        InMemoryRegisterStore store = earlierRegister();
        try (RegisterStore.Opened opened = store.openForWrite("arch")) {
            RegisterLog log = new RegisterLog(opened, () -> T1);
            RegisterState state = RegisterReplay.replay("arch", log.read(), opened.blobs());

            state = dismiss(new RegisterWriter(log, opened.blobs()), state, "C.java", "claude-code", T1);
            opened.writeState(StateJson.write(state));

            List<RegisterEvent> events = log.read();
            assertThat(events).extracting(RegisterEvent::type).endsWith("finding.reopened", "register.requires",
                    "finding.dismissed");
            assertThat(events.get(events.size() - 2).text("feature")).isEqualTo("decision_agent");
            assertThat(events.getLast().text("agent")).isEqualTo("claude-code");
            assertThat(of(state, "A.java").disposition().agent()).as("the decisions of before stay a person's").isNull();
            assertThat(RegisterReplay.replay("arch", events, opened.blobs())).isEqualTo(state);
            holds(opened);
        }
    }
}
