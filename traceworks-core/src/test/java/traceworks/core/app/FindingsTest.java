package traceworks.core.app;

import traceworks.core.record.ChainReport;
import traceworks.core.register.Finding;
import traceworks.core.register.FindingStatus;
import traceworks.core.register.RegisterEvent;
import traceworks.core.register.RegisterEvents;

import java.util.List;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static traceworks.core.app.AppFixture.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The findings use case reads a register from its log, never from the state file. */
class FindingsTest {

    private final RegistersTest audit = new RegistersTest();

    @Test
    void state_isReplayedFromTheLog_notFromTheFile() {
        RegistersTest.AUDIT.toString();
        AppFixture fixture = fixtureOf(audit);
        fixture.services().start(request(RegistersTest.AUDIT), null, null);
        fixture.registers.editState("hello", "{\"key\":\"hello\",\"findings\":[]}\n".getBytes(StandardCharsets.UTF_8));

        Findings findings = fixture.services().findings();

        assertThat(findings.keys()).containsExactly("hello");
        assertThat(findings.state("hello").findings()).hasSize(2);
    }

    @Test
    void verify_reportsAnEditedState_andAnUnknownKey() {
        AppFixture fixture = fixtureOf(audit);
        fixture.services().start(request(RegistersTest.AUDIT), null, null);
        Findings findings = fixture.services().findings();
        assertThat(findings.verify("hello").problems()).isEmpty();

        fixture.registers.editState("hello", "{}".getBytes(StandardCharsets.UTF_8));
        ChainReport report = findings.verify("hello");

        assertThat(report.intact()).isFalse();
        assertThat(report.problems()).anySatisfy(p -> assertThat(p).contains("state.json differs from the log"));
        assertThatThrownBy(() -> findings.state("nope")).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("No register").hasMessageContaining("nope");
    }

    private static AppFixture fixtureOf(RegistersTest test) {
        try {
            java.lang.reflect.Field field = RegistersTest.class.getDeclaredField("fixture");
            field.setAccessible(true);
            return (AppFixture) field.get(test);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void accept_dismiss_andReopen_areRecorded_withTheActor_andReplay() {
        AppFixture fixture = fixtureOf(audit);
        fixture.services().start(request(RegistersTest.AUDIT), null, null);
        Findings findings = fixture.services().findings();
        List<Finding> open = findings.state("hello").findings();
        String a = open.get(0).id();
        String b = open.get(1).id();

        Finding accepted = findings.accept("hello", a.substring(0, 8), "bounded", null);
        Finding dismissed = findings.dismiss("hello", b, "not ours");

        assertThat(accepted.status()).isEqualTo(FindingStatus.ACCEPTED_RISK);
        assertThat(accepted.disposition().actor()).isEqualTo(AppFixture.ACTOR);
        assertThat(accepted.disposition().until())
                .isEqualTo(accepted.disposition().at().plus(java.time.Duration.ofDays(180)));
        assertThat(dismissed.status()).isEqualTo(FindingStatus.DISMISSED);
        assertThat(findings.state("hello").finding(a).status()).isEqualTo(FindingStatus.ACCEPTED_RISK);
        List<RegisterEvent> events = findings.events("hello");
        assertThat(events).extracting(RegisterEvent::type).endsWith(RegisterEvents.FINDING_ACCEPTED,
                RegisterEvents.FINDING_DISMISSED);
        RegisterEvent acceptedEvent = events.get(events.size() - 2);
        assertThat(acceptedEvent.text("note")).isEqualTo("bounded");
        assertThat(acceptedEvent.text("actor")).isEqualTo(AppFixture.ACTOR);
        assertThat(acceptedEvent.fields().get("signature").isNull()).isTrue();
        assertThat(acceptedEvent.text("state_blob")).hasSize(64);
        assertThat(findings.verify("hello").intact()).isTrue();

        Finding reopened = findings.reopen("hello", a, "fix it");
        assertThat(reopened.status()).isEqualTo(FindingStatus.OPEN);
        assertThat(findings.state("hello").finding(a).disposition()).isNull();
        assertThat(findings.verify("hello").problems()).isEmpty();
    }

    @Test
    void aWrongTransition_orAnUnknownFinding_isRefused_andRecordsNothing() {
        AppFixture fixture = fixtureOf(audit);
        fixture.services().start(request(RegistersTest.AUDIT), null, null);
        Findings findings = fixture.services().findings();
        String a = findings.state("hello").findings().getFirst().id();
        int lines = findings.events("hello").size();

        assertThatThrownBy(() -> findings.reopen("hello", a, null)).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("OPEN");
        assertThatThrownBy(() -> findings.accept("hello", "0000000000", "n", null))
                .isInstanceOf(RunRefusedException.class).hasMessageContaining("No finding");
        assertThatThrownBy(() -> findings.accept("hello", a, " ", null)).isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("note");
        assertThat(findings.events("hello")).hasSize(lines);
    }
}
