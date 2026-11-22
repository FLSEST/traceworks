package traceworks.core.app;

import org.junit.jupiter.api.Test;
import traceworks.core.register.RegisterEvents;
import traceworks.core.testing.InMemoryEventLog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static traceworks.core.app.AppFixture.request;

/**
 * A refused command writes nothing. {@code findings} {@code accept}, {@code dismiss}, {@code reopen} and
 * {@code render} judge the register before they open its log for writing: opening it for writing discards a torn
 * tail and records that ({@code register.repaired}), and a command that is refused must leave no such line.
 */
class FindingsRefusedWritesNothingTest {

    private static final Path AUDIT = Path.of("/pkgs/audit");
    private static final byte[] TORN = "{\"seq\":9,\"at\":\"2026".getBytes(StandardCharsets.UTF_8);

    private final AppFixture fixture = new AppFixture();

    FindingsRefusedWritesNothingTest() {
        fixture.packages.put(AUDIT, """
                {"id": "wf-audit", "name": "Audit", "metadata": {"audit": {"enabled": true, "key": "hello",
                   "category": "example", "report_block": "report"}}, "blocks": [
                  {"type": "shell", "name": "report", "config": {"command": "./report"}}
                ]}""");
        fixture.commands.on("./report", "# Report\n\n<!-- traceworks:audit-result:v1\n{\"snapshotComplete\":true,"
                + "\"findings\":[{\"ruleKey\":\"size\",\"subject\":\"A.java\",\"severity\":\"MEDIUM\","
                + "\"title\":\"Big A.java\"}]}\n-->\n");
    }

    private static List<String> types(InMemoryEventLog log) {
        return log.readAll().stream().map(line -> traceworks.core.register.RegisterEvent.parse(line).type()).toList();
    }

    /** The folder of one project copied to the place of another's: its log names the project it was copied from. */
    @Test
    void aDecisionAndARender_onARegisterThatCannotBeUsed_leaveItsLogAsItWas_alsoItsTornTail() {
        fixture.services().start(request(AUDIT).inProject("project-a"), null, null);
        String id = fixture.services().findings().ofProject("project-a").state("hello").findings().getFirst().id();
        fixture.registers.copy("hello", "project-a", "project-b");
        InMemoryEventLog log = fixture.registers.log("hello", "project-b");
        log.tearTail(TORN);
        List<String> before = types(log);
        Findings findings = fixture.services().findings().ofProject("project-b");

        for (Runnable command : List.<Runnable>of(() -> findings.accept("hello", id, "a risk we take", null),
                () -> findings.dismiss("hello", id, "not ours"), () -> findings.reopen("hello", id, "again"),
                () -> findings.render("hello"))) {
            assertThatThrownBy(command::run).isInstanceOf(RunRefusedException.class)
                    .hasMessageContaining("cannot be used").hasMessageContaining("names the project project-a");
        }

        assertThat(types(log)).as("no line, also none that says a torn tail was discarded").isEqualTo(before)
                .doesNotContain(RegisterEvents.REPAIRED);
        assertThat(log.tail()).as("the torn tail is where it was").isEqualTo(TORN);
        assertThat(fixture.ledger.registerAnchors("hello", "project-b")).isEmpty();
    }

    /** A decision that is refused for its own reason writes nothing either; one that is taken repairs first. */
    @Test
    void aDecisionThatIsRefused_onARegisterWithATornTail_writesNothing_andOneThatIsTakenRepairsIt() {
        fixture.services().start(request(AUDIT), null, null);
        Findings findings = fixture.services().findings();
        String id = findings.state("hello").findings().getFirst().id();
        InMemoryEventLog log = fixture.registers.log("hello");
        log.tearTail(TORN);
        List<String> before = types(log);

        assertThatThrownBy(() -> findings.dismiss("hello", "0".repeat(12), "not ours"))
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("No finding of");
        assertThatThrownBy(() -> findings.reopen("hello", id, "it is open")).isInstanceOf(RunRefusedException.class);
        assertThatThrownBy(() -> findings.accept("hello", id, " ", null)).isInstanceOf(RunRefusedException.class);

        assertThat(types(log)).as("no line of a refused decision").isEqualTo(before);
        assertThat(log.tail()).isEqualTo(TORN);

        findings.dismiss("hello", id, "not ours");

        assertThat(log.tail()).isEmpty();
        assertThat(types(log).subList(before.size(), types(log).size())).startsWith(RegisterEvents.REPAIRED)
                .endsWith(RegisterEvents.FINDING_DISMISSED);
        assertThat(findings.state("hello").finding(id).status().name()).isEqualTo("DISMISSED");
        assertThat(findings.verify("hello").intact()).isTrue();
    }
}
