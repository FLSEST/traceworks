package traceworks.core.app;

import org.junit.jupiter.api.Test;
import traceworks.core.model.RunStatus;
import traceworks.core.port.Ledger;
import traceworks.core.register.RegisterEvent;
import traceworks.core.register.RegisterEvents;
import traceworks.core.testing.EarlierRegister;
import traceworks.core.testing.InMemoryEventLog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static traceworks.core.app.AppFixture.request;

/**
 * No internal error from a register. Whatever the fold, the store or a listing throws for a register (a time that
 * cannot be parsed, a folder that cannot be listed, a file that cannot be written) is a refusal with the reason,
 * and a problem for the check of the home. A command whose line was appended and whose derived files then failed
 * says exactly that: the decision is recorded, and what was not written.
 */
class RegisterNoInternalErrorTest {

    private static final Path AUDIT = Path.of("/pkgs/audit");

    private final AppFixture fixture = new AppFixture();

    RegisterNoInternalErrorTest() {
        fixture.packages.put(AUDIT, """
                {"id": "wf-audit", "name": "Audit", "metadata": {"audit": {"enabled": true, "key": "hello",
                   "category": "example", "report_block": "report"}}, "blocks": [
                  {"type": "shell", "name": "report", "config": {"command": "./report"}}
                ]}""");
        fixture.commands.on("./report", "# Report\n\n<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,"
                + "\"findings\":[{\"ruleKey\":\"size\",\"subject\":\"A.java\",\"severity\":\"MEDIUM\","
                + "\"title\":\"Big A.java\"}]}\n-->\n");
    }

    private RunResult run(String project) {
        return fixture.services().start(request(AUDIT).inProject(project), null, null);
    }

    private static List<String> types(InMemoryEventLog log) {
        return log.readAll().stream().map(line -> RegisterEvent.parse(line).type()).toList();
    }

    /**
     * Puts {@code with} in the place of {@code text} in the one line of the log that holds it, and chains every
     * line after it to the line before it again: the chain holds, and what is wrong is the value alone.
     */
    private void edit(String project, String text, String with) {
        InMemoryEventLog log = fixture.registers.log("hello", project);
        List<byte[]> lines = log.readAll();
        log.dropLast(lines.size());
        int edited = 0;
        byte[] before = null;
        for (byte[] line : lines) {
            String was = new String(line, StandardCharsets.UTF_8);
            edited += was.contains(text) ? 1 : 0;
            String now = was.replace(text, with);
            if (before != null) {
                now = now.replaceFirst("\"prev\":\"[0-9a-f]{64}\"", "\"prev\":\""
                        + traceworks.core.record.EventChain.sha256Hex(before) + "\"");
            }
            before = now.getBytes(StandardCharsets.UTF_8);
            log.append(before);
        }
        assertThat(edited).as("the lines that hold " + text).isEqualTo(1);
    }

    private static void assertUnusable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String project) {
        assertThatThrownBy(call).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("The register 'hello' of the project '" + project + "' cannot be used: ")
                .hasMessageContaining("'traceworks findings hello verify --project " + project + "' says what is"
                        + " wrong");
    }

    /**
     * Reproduced 2026-11-21: a line whose time cannot be parsed ({@code finished_at} of a report, {@code until} of
     * an acceptance) was {@code internal error: DateTimeParseException} for every findings command.
     */
    @Test
    void aLineWhoseTimeCannotBeParsed_isARefusalOfEveryCommand_andAProblemOfTheCheck() {
        run("shop");
        run("web");
        Findings shop = fixture.services().findings().ofProject("shop");
        Findings web = fixture.services().findings().ofProject("web");
        String id = shop.state("hello").findings().getFirst().id();
        web.accept("hello", id, "a risk we take", null);
        String finishedAt = "\"finished_at\":\"";
        edit("shop", finishedAt, finishedAt + "not a time ");
        edit("web", "\"until\":\"", "\"until\":\"some day ");

        for (String project : List.of("shop", "web")) {
            Findings findings = fixture.services().findings().ofProject(project);
            List<byte[]> lines = fixture.registers.log("hello", project).readAll();
            byte[] state = fixture.registers.stateFile("hello", project);
            int anchors = fixture.ledger.registerAnchors("hello", project).size();
            assertThat(findings.verified("hello").report().problems()).as("the line is well chained: what is wrong"
                    + " with it is its time").isNotEmpty().noneSatisfy(problem -> assertThat(problem)
                    .contains("prev does not match"));

            // state is what list, show and items of the command line read
            assertUnusable(() -> findings.state("hello"), project);
            assertUnusable(() -> findings.events("hello"), project);
            assertUnusable(() -> findings.accept("hello", id, "a risk we take", null), project);
            assertUnusable(() -> findings.dismiss("hello", id, "not ours"), project);
            assertUnusable(() -> findings.reopen("hello", id, "again"), project);
            assertUnusable(() -> findings.render("hello"), project);
            assertThatThrownBy(() -> run(project)).isInstanceOf(RunRefusedException.class)
                    .hasMessageContaining("the register hello of " + project + " cannot be read");
            assertThat(findings.verified("hello").report().intact()).isFalse();
            assertThat(fixture.registers.log("hello", project).readAll()).as("nothing was written: the log is"
                    + " byte for byte what it was").usingElementComparator(java.util.Arrays::compare).isEqualTo(lines);
            assertThat(fixture.registers.log("hello", project).tail()).isEmpty();
            assertThat(fixture.registers.stateFile("hello", project)).as("and so is state.json").isEqualTo(state);
            assertThat(fixture.ledger.registerAnchors("hello", project)).as("and nothing was anchored")
                    .hasSize(anchors);
        }
        assertThat(fixture.services().ledgerReport().problems()).extracting(LedgerReport.Problem::runId)
                .contains(Ledger.registerName("hello", "shop"), Ledger.registerName("hello", "web"));
    }

    /**
     * Reproduced 2026-11-21: a key folder that cannot be listed was exit 70 for {@code verify}, {@code findings} and
     * {@code findings hello list}.
     */
    @Test
    void aKeyWhoseRegistersCannotBeListed_isARefusal_andAProblemOfTheCheck_andTheOtherKeysGoOn() {
        run("shop");
        fixture.registers.putFromBefore(EarlierRegister.KEY, EarlierRegister.lines(), EarlierRegister.blobs(),
                EarlierRegister.state());
        fixture.registers.failProjects("hello", new UncheckedIOException("Cannot list the registers in"
                + " /home/registers/hello: Permission denied", new IOException("Permission denied")));
        Findings findings = fixture.services().findings();

        for (org.assertj.core.api.ThrowableAssert.ThrowingCallable call
                : List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(() -> findings.projects("hello"),
                () -> findings.unassigned("hello"), () -> findings.state("hello"),
                () -> findings.ofProject("shop").state("hello"), () -> findings.ofProject("shop").render("hello"),
                () -> findings.verified("hello"), () -> findings.assign("hello", "shop"), () -> run("shop"))) {
            assertThatThrownBy(call).isInstanceOf(RunRefusedException.class)
                    .hasMessageContaining("Cannot list the registers in /home/registers/hello: Permission denied");
        }
        assertThat(findings.unassigned(EarlierRegister.KEY)).as("another key is listed as ever").isTrue();

        LedgerReport report = fixture.services().ledgerReport();

        assertThat(report.problems()).filteredOn(problem -> problem.runId().equals(Ledger.registerName("hello",
                null))).singleElement().satisfies(problem -> {
                    assertThat(problem.code()).isEqualTo("register-broken");
                    assertThat(problem.text()).contains("the registers of the key hello cannot be listed")
                            .contains("Permission denied");
                });
    }

    /** Reproduced 2026-11-21: with the registers folder itself unlistable, {@code run} was exit 70. */
    @Test
    void registersThatCannotBeListedAtAll_refuseARunAndFindings_andFailTheCheck() {
        run("shop");
        fixture.registers.failKeys(new UncheckedIOException("Cannot list the registers in /home/registers:"
                + " Permission denied", new IOException("Permission denied")));
        Findings findings = fixture.services().findings();
        String reason = "Cannot list the registers in /home/registers: Permission denied";

        assertThatThrownBy(findings::keys).isInstanceOf(RunRefusedException.class).hasMessageContaining(reason);
        assertThatThrownBy(() -> findings.state("hello")).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining(reason);
        assertThatThrownBy(() -> run("shop")).isInstanceOf(RunRefusedException.class).hasMessageContaining(reason);
        assertThatThrownBy(() -> fixture.services().ledgerReport()).isInstanceOf(RecordDamagedException.class)
                .hasMessageContaining(reason);
    }

    /** Reproduced 2026-11-21: a decision with a log that cannot be written was exit 70. */
    @Test
    void aDecisionOrARender_whoseLogCannotBeWritten_isRefused_andSaysThatNothingIsRecorded() {
        run("shop");
        Findings findings = fixture.services().findings();
        String id = findings.state("hello").findings().getFirst().id();
        InMemoryEventLog log = fixture.registers.log("hello");
        log.tearTail("{\"seq\":9".getBytes(StandardCharsets.UTF_8));   // so a render has a line to write too
        List<String> before = types(log);
        fixture.registers.failWrites(new UncheckedIOException("Cannot append to events.jsonl: Read-only file"
                + " system", new IOException("Read-only file system")));

        assertThatThrownBy(() -> findings.render("hello")).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("The register 'hello' of the project 'shop' cannot be written: ")
                .hasMessageContaining("Read-only file system");
        assertThatThrownBy(() -> findings.dismiss("hello", id, "not ours")).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("The register 'hello' of the project 'shop' cannot be written, and the"
                        + " decision is not recorded: ").hasMessageContaining("Read-only file system");

        fixture.registers.failWrites(null);
        assertThat(types(log)).isEqualTo(before);
        assertThat(findings.state("hello").finding(id).status().name()).isEqualTo("OPEN");
    }

    /**
     * Reproduced 2026-11-21: with a folder that cannot be written, {@code dismiss} was exit 70 after its line was
     * appended. The decision is recorded, and the command says so and what was not written.
     */
    @Test
    void aDecisionWhoseDerivedFilesCannotBeWritten_isRecorded_andSaysWhatWasNotWritten() {
        run("shop");
        Findings findings = fixture.services().findings();
        String id = findings.state("hello").findings().getFirst().id();
        byte[] state = fixture.registers.stateFile("hello");
        fixture.registers.failStateWrites(new UncheckedIOException("Cannot write state.json: Permission denied",
                new IOException("Permission denied")));

        findings.dismiss("hello", id, "not ours");

        assertThat(findings.notWritten()).hasValueSatisfying(what -> assertThat(what)
                .contains("state.json or report.html of the register could not be written")
                .contains("Permission denied"));
        assertThat(findings.unanchored()).as("the line is anchored").isEmpty();
        assertThat(types(fixture.registers.log("hello"))).endsWith(RegisterEvents.FINDING_DISMISSED);
        assertThat(fixture.registers.stateFile("hello")).as("the file is as it was").isEqualTo(state);
        fixture.registers.failStateWrites(null);
        assertThat(findings.state("hello").finding(id).status().name()).isEqualTo("DISMISSED");

        findings.render("hello");

        assertThat(findings.notWritten()).as("a write that goes through says nothing").isEmpty();
        assertThat(findings.verify("hello").intact()).isTrue();
    }

    /** A render that cannot write its files is refused with the reason: its files are what it is for. */
    @Test
    void aRenderWhoseFilesCannotBeWritten_isRefused_withWhatWasNotWritten() {
        run("shop");
        Findings findings = fixture.services().findings();
        fixture.registers.failStateWrites(new UncheckedIOException("Cannot write state.json: Permission denied",
                new IOException("Permission denied")));

        assertThatThrownBy(() -> findings.render("hello")).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("The register 'hello' of the project 'shop' is not rendered: state.json or"
                        + " report.html of the register could not be written")
                .hasMessageContaining("Permission denied");
    }

    /**
     * Reproduced 2026-11-21: {@code assign} with a log that cannot be written, and with a register that cannot be
     * moved, was exit 70.
     */
    @Test
    void anAssignThatCannotWriteOrMove_isRefused_andSaysWhatIsRecorded() {
        fixture.registers.putFromBefore(EarlierRegister.KEY, EarlierRegister.lines(), EarlierRegister.blobs(),
                EarlierRegister.state());
        Findings findings = fixture.services().findings();
        fixture.registers.failWrites(new UncheckedIOException("Cannot append to events.jsonl: Read-only file system",
                new IOException("Read-only file system")));

        assertThatThrownBy(() -> findings.assign(EarlierRegister.KEY, "shop")).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("The register 'arch' cannot be written, and it is not assigned: ")
                .hasMessageContaining("Read-only file system");
        assertThat(fixture.registers.logFromBefore(EarlierRegister.KEY).readAll()).hasSize(
                EarlierRegister.lines().size());

        fixture.registers.failWrites(null);
        fixture.registers.failMoves(new UncheckedIOException("Cannot move the register: Permission denied",
                new IOException("Permission denied")));

        assertThatThrownBy(() -> findings.assign(EarlierRegister.KEY, "shop")).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("The assignment of the register 'arch' to shop is recorded in its log"
                        + " (register.assigned) and not finished: ")
                .hasMessageContaining("Permission denied")
                .hasMessageContaining("'traceworks findings arch assign --project shop' finishes it");
        assertThat(fixture.registers.unassigned(EarlierRegister.KEY)).isTrue();

        fixture.registers.failMoves(null);
        findings.assign(EarlierRegister.KEY, "shop");
        assertThat(fixture.registers.projects(EarlierRegister.KEY)).containsExactly("shop");
        assertThat(fixture.registers.unassigned(EarlierRegister.KEY)).as("the same command finished it").isFalse();
        assertThat(findings.state(EarlierRegister.KEY).project()).isEqualTo("shop");
        assertThat(fixture.registers.log(EarlierRegister.KEY, "shop").readAll().stream()
                .filter(line -> RegisterEvents.ASSIGNED.equals(RegisterEvent.parse(line).type())).count())
                .as("its line is there once").isEqualTo(1);
        Findings.Verified verified = findings.verified(EarlierRegister.KEY);
        assertThat(verified.report().intact()).as(verified.report().problems().toString()).isTrue();
        RunResult other = run("shop");
        assertThat(other.status()).as("and a run of another key goes on as ever").isEqualTo(RunStatus.COMPLETED);
        assertThat(other.register().applied()).isTrue();
    }

    /**
     * A decision whose line is in the log and whose register then could not be closed says exactly that: it is
     * recorded. (The branch had no test.)
     */
    @Test
    void aDecisionWhoseRegisterCannotBeClosed_isRecorded_andTheRefusalSaysSo() {
        run("shop");
        Findings findings = fixture.services().findings();
        String id = findings.state("hello").findings().getFirst().id();
        int lines = fixture.registers.log("hello").readAll().size();
        fixture.registers.failCloses(new UncheckedIOException("Cannot sync the folder: Input/output error",
                new IOException("Input/output error")));

        assertThatThrownBy(() -> findings.dismiss("hello", id, "not ours")).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("The register 'hello' of the project 'shop' cannot be written: the decision is"
                        + " recorded in its log, and the register could not be closed: ")
                .hasMessageContaining("Input/output error");

        fixture.registers.failCloses(null);
        assertThat(types(fixture.registers.log("hello"))).as("its line is there").hasSize(lines + 1)
                .endsWith(RegisterEvents.FINDING_DISMISSED);
        assertThat(findings.state("hello").finding(id).status().name()).isEqualTo("DISMISSED");
        assertThat(findings.verified("hello").report().intact()).isTrue();
    }

    /**
     * Reproduced 2026-11-21: a blob edited in place ended a command with a hint that named no register
     * ({@code traceworks findings <key> verify --project <project>}). The hint of a register's command names the
     * key and the project, in a form that works.
     */
    @Test
    void aChangedBlob_endsACommandAsADamagedRecordDoes_andTheHintNamesTheKeyAndTheProject() {
        run("shop");
        Findings findings = fixture.services().findings().ofProject("shop");
        String id = findings.state("hello").findings().getFirst().id();
        String blob = findings.events("hello").stream().filter(event -> event.text("findings_blob") != null)
                .findFirst().orElseThrow().text("findings_blob");
        fixture.registers.blobs("hello").putRaw(blob, "[]".getBytes(StandardCharsets.UTF_8));
        int lines = fixture.registers.log("hello").readAll().size();

        for (org.assertj.core.api.ThrowableAssert.ThrowingCallable command
                : List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(() -> findings.state("hello"),
                () -> findings.events("hello"), () -> findings.dismiss("hello", id, "not ours"),
                () -> findings.accept("hello", id, "a risk", null), () -> findings.render("hello"))) {
            assertThatThrownBy(command).isInstanceOf(traceworks.core.port.BlobStore.ChangedBlobException.class)
                    .hasMessageContaining("The blob " + blob + " holds other content than its name says")
                    .hasMessageContaining("check the register with 'traceworks findings hello verify --project"
                            + " shop'")
                    .hasMessageNotContaining("<key>").hasMessageNotContaining("<project>");
        }
        assertThat(fixture.registers.log("hello").readAll()).as("nothing was written").hasSize(lines);
    }

    /** A status that the run's own code gives: the register's failure never changes it. */
    @Test
    void theOtherRegisters_goOn_whenOneCannotBeUsed() {
        run("shop");
        run("web");
        edit("shop", "\"finished_at\":\"", "\"finished_at\":\"not a time ");

        assertThat(run("web").status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fixture.services().findings().ofProject("web").state("hello").findings()).hasSize(1);
    }
}
