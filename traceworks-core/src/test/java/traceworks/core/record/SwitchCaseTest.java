package traceworks.core.record;

import traceworks.core.model.StepStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SwitchCaseTest {

    private static StepView step(List<CommandView> commands) {
        return new StepView(0, "route", "switch", 0, false, StepStatus.COMPLETED, false, false, 5, 1, List.of(),
                commands, null, null, null, null, List.of(), List.of(), List.of(), List.of(), null, List.of());
    }

    private static CommandView command() {
        return new CommandView(1, null, null, null, "/w", null, null, List.of(), "none", List.of(), List.of(), true,
                new CommandView.Result(0, 5, false, List.of(), null));
    }

    @Test
    void theStepSaysWhetherACaseRan() {
        assertThat(SwitchCase.of(step(List.of()), "{\"matchedCase\":\"fast\",\"caseMatched\":true}"))
                .contains(new SwitchCase("fast", true));
        assertThat(SwitchCase.of(step(List.of()), "{\"matchedCase\":\"none\",\"caseMatched\":false}"))
                .contains(new SwitchCase("none", false));
        // "none" may be the value of a case that ran
        assertThat(SwitchCase.of(step(List.of()), "{\"matchedCase\":\"none\",\"caseMatched\":true}"))
                .contains(new SwitchCase("none", true));
    }

    @Test
    void aRecordOfAnEarlierVersion_saysNone_andOnlyACaseRanACommand() {
        assertThat(SwitchCase.of(step(List.of()), "{\"matchedCase\":\"none\"}"))
                .contains(new SwitchCase("none", false));
        assertThat(SwitchCase.of(step(List.of(command())), "{\"matchedCase\":\"none\"}"))
                .contains(new SwitchCase("none", true));
        assertThat(SwitchCase.of(step(List.of()), "{\"matchedCase\":\"default\"}"))
                .contains(new SwitchCase("default", true));
    }

    @Test
    void metadataThatIsNoJson_orNamesNoCase_isEmpty() {
        assertThat(SwitchCase.of(step(List.of()), "{not json")).isEmpty();
        assertThat(SwitchCase.of(step(List.of()), "")).isEmpty();
        assertThat(SwitchCase.of(step(List.of()), "{\"matchedCase\":7}")).isEmpty();
        assertThat(SwitchCase.of(step(List.of()), "{\"provider\":\"claude\"}")).isEmpty();
    }
}
