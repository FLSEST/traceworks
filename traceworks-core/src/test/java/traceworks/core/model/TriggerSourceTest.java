package traceworks.core.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TriggerSourceTest {

    @Test
    void onlyTheTraceworksSourcesExist() {
        assertThat(TriggerSource.values()).containsExactlyInAnyOrder(
                TriggerSource.MANUAL, TriggerSource.RESUME, TriggerSource.API,
                TriggerSource.CHAIN, TriggerSource.SUB_WORKFLOW);
        assertThat(TriggerSource.parse("SCHEDULE")).as("a source Traceworks does not have").isNull();
        assertThat(TriggerSource.parse("WEBHOOK")).as("a source Traceworks does not have").isNull();
    }

    @Test
    void parse_readsTheName_andReturnsNullForAnythingElse() {
        assertThat(TriggerSource.parse("API")).isEqualTo(TriggerSource.API);
        assertThat(TriggerSource.parse(null)).isNull();
        assertThat(TriggerSource.parse("")).isNull();
        assertThat(TriggerSource.parse("manual")).isNull();
    }

    @Test
    void directSourcesAreTheirOwnEffectiveSource() {
        assertThat(TriggerSource.API.effective(Map.of(TriggerSource.PARENT_CONTEXT_KEY, "MANUAL")))
                .isEqualTo(TriggerSource.API);
        assertThat(TriggerSource.MANUAL.inheritsFromParent()).isFalse();
        assertThat(TriggerSource.RESUME.inheritsFromParent()).isFalse();
        assertThat(TriggerSource.CHAIN.inheritsFromParent()).isTrue();
        assertThat(TriggerSource.SUB_WORKFLOW.inheritsFromParent()).isTrue();
    }

    @Test
    void inheritingSourcesTakeTheParentFromTheReservedKey() {
        assertThat(TriggerSource.CHAIN.effective(Map.of(TriggerSource.PARENT_CONTEXT_KEY, "API")))
                .isEqualTo(TriggerSource.API);
        assertThat(TriggerSource.SUB_WORKFLOW.effective(Map.of())).isNull();
        assertThat(TriggerSource.SUB_WORKFLOW.effective(null)).isNull();
        assertThat(TriggerSource.CHAIN.effective(Map.of(TriggerSource.PARENT_CONTEXT_KEY, "nonsense"))).isNull();
        assertThat(TriggerSource.CHAIN.effective(Map.of(TriggerSource.PARENT_CONTEXT_KEY, "CHAIN"))).isNull();
    }

    @Test
    void effectiveSourceOfARunFollowsNesting() {
        Map<String, Object> chainEntryRun = new HashMap<>();
        chainEntryRun.put("trigger_source", "CHAIN");
        chainEntryRun.put(TriggerSource.PARENT_CONTEXT_KEY, "RESUME");
        assertThat(TriggerSource.effectiveOfRun(chainEntryRun)).isEqualTo(TriggerSource.RESUME);

        assertThat(TriggerSource.effectiveOfRun(Map.of("trigger_source", "MANUAL",
                TriggerSource.PARENT_CONTEXT_KEY, "API"))).isEqualTo(TriggerSource.MANUAL);
        assertThat(TriggerSource.effectiveOfRun(Map.of("trigger_source", ""))).isNull();
    }

    /** Only a run the chain executor started records the chain run; the key alone proves nothing. */
    @Test
    void chainRunIdIsReadOnlyForChainRuns() {
        Map<String, Object> entryContext = Map.of(TriggerSource.CHAIN_RUN_CONTEXT_KEY, "chain-run-7");

        assertThat(TriggerSource.chainRunIdOf(TriggerSource.CHAIN, entryContext)).isEqualTo("chain-run-7");
        assertThat(TriggerSource.chainRunIdOf(TriggerSource.RESUME, entryContext)).as("a resume keeps the context").isNull();
        assertThat(TriggerSource.chainRunIdOf(TriggerSource.SUB_WORKFLOW, entryContext)).as("inherited from the parent").isNull();
        assertThat(TriggerSource.chainRunIdOf(TriggerSource.MANUAL, entryContext)).isNull();
        assertThat(TriggerSource.chainRunIdOf(null, entryContext)).isNull();
        assertThat(TriggerSource.chainRunIdOf(TriggerSource.CHAIN, null)).isNull();
        assertThat(TriggerSource.chainRunIdOf(TriggerSource.CHAIN, Map.of(TriggerSource.CHAIN_RUN_CONTEXT_KEY, " "))).isNull();
        assertThat(TriggerSource.chainRunIdOf(TriggerSource.CHAIN, Map.of(TriggerSource.CHAIN_RUN_CONTEXT_KEY, 42))).isNull();
    }
}
