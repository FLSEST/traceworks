package traceworks.selfaudit.areas;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Who judges a run, as the step is told it: the picked option of the model choice, its provider and its model. */
class JudgeTest {

    @Test
    void theJudge_isWhatTheRunNames_trimmed_andGoesThroughTheSummary() {
        Judge judge = Judge.of(" astra ", "PI", "openai/gpt-6-astra");

        assertThat(judge).isEqualTo(new Judge("astra", "PI", "openai/gpt-6-astra"));
        assertThat(judge.toJson()).containsExactly(Map.entry("name", "astra"), Map.entry("provider", "PI"),
                Map.entry("model", "openai/gpt-6-astra"));
        assertThat(Judge.fromJson(Json.parse(Json.write(judge.toJson())))).isEqualTo(judge);
        assertThat(Judge.fromJson(null)).as("a summary without a judge").isNull();
        assertThat(Judge.fromJson("astra")).isNull();
    }

    @Test
    void aValueThatIsNoText_isRefused_withoutBeingShown() {
        assertThatThrownBy(() -> Judge.of("", "PI", "m")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("the judge must be a text");
        assertThatThrownBy(() -> Judge.of("astra", null, "m")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("the judge's provider must be a text");
        assertThatThrownBy(() -> Judge.of("astra", "PI", "evil\u001b[31m")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("the judge's model must be a text").hasMessageNotContaining("evil");
        assertThatThrownBy(() -> Judge.of("x".repeat(201), "PI", "m")).isInstanceOf(IllegalArgumentException.class);
    }
}
