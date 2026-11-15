package traceworks.core.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiResponseMetadataTest {

    /** The cached input is reported beside {@code inputTokens}, which stays the provider's own figure. */
    @Test
    void toMap_carriesTheCacheAndThinkingTokens() {
        var meta = new AiResponseMetadata(2, 918, 0.408808, "example-model", false, 8794L, 1, "sess", List.of(),
                13720, 48462, 300);

        assertThat(meta.toMap())
                .containsEntry("inputTokens", 2)
                .containsEntry("outputTokens", 918)
                .containsEntry("cacheReadInputTokens", 13720)
                .containsEntry("cacheCreationInputTokens", 48462)
                .containsEntry("thinkingTokens", 300);
    }

    @Test
    void withoutCacheFigures_noCacheKeys_insteadOfAMadeUpZero() {
        var meta = new AiResponseMetadata(100, 50, null, null, false, null, null, null, List.of());

        assertThat(meta.cacheReadInputTokens()).isNull();
        assertThat(meta.thinkingTokens()).isNull();
        assertThat(meta.toMap())
                .doesNotContainKeys("cacheReadInputTokens", "cacheCreationInputTokens", "thinkingTokens");
        assertThat(AiResponseMetadata.estimated("prompt text", "answer").toMap())
                .doesNotContainKeys("cacheReadInputTokens", "cacheCreationInputTokens");
    }

    @Test
    void constructorWithCacheFigures_leavesThinkingUnset() {
        var meta = new AiResponseMetadata(10, 5, null, null, false, null, null, null, List.of(), 7, 8);

        assertThat(meta.cacheReadInputTokens()).isEqualTo(7);
        assertThat(meta.cacheCreationInputTokens()).isEqualTo(8);
        assertThat(meta.thinkingTokens()).isNull();
    }

    @Test
    void estimated_fromStrings() {
        AiResponseMetadata meta = AiResponseMetadata.estimated("Hello World!!", "OK");

        assertThat(meta.inputTokens()).isEqualTo(3); // 13 chars / 4 = 3
        assertThat(meta.outputTokens()).isEqualTo(0); // 2 chars / 4 = 0
        assertThat(meta.estimated()).isTrue();
        assertThat(meta.costUsd()).isNull();
        assertThat(meta.model()).isNull();
    }

    @Test
    void estimated_fromNullStrings() {
        AiResponseMetadata meta = AiResponseMetadata.estimated((String) null, (String) null);

        assertThat(meta.inputTokens()).isZero();
        assertThat(meta.outputTokens()).isZero();
        assertThat(meta.estimated()).isTrue();
    }

    @Test
    void estimated_fromTokenCounts() {
        AiResponseMetadata meta = AiResponseMetadata.estimated(250, 100);

        assertThat(meta.inputTokens()).isEqualTo(250);
        assertThat(meta.outputTokens()).isEqualTo(100);
        assertThat(meta.estimated()).isTrue();
        assertThat(meta.costUsd()).isNull();
        assertThat(meta.model()).isNull();
        assertThat(meta.toolNames()).isEmpty();
    }

    @Test
    void toMap_withAllFields() {
        var meta = new AiResponseMetadata(1000, 500, 0.05, "example-model", false, 1500L, 3, "sess-123",
                List.of("read_file", "write_file"));

        Map<String, Object> map = meta.toMap();

        assertThat(map).containsEntry("inputTokens", 1000);
        assertThat(map).containsEntry("outputTokens", 500);
        assertThat(map).containsEntry("costUsd", 0.05);
        assertThat(map).containsEntry("model", "example-model");
        assertThat(map).doesNotContainKey("estimated");
        assertThat(map).containsEntry("apiDurationMs", 1500L);
        assertThat(map).containsEntry("numTurns", 3);
        assertThat(map).containsEntry("sessionId", "sess-123");
        assertThat(map).containsEntry("toolNames", List.of("read_file", "write_file"));
        assertThat(map).containsEntry("toolUseCount", 2);
    }

    @Test
    void toMap_estimatedFlagIncludedWhenTrue() {
        var meta = AiResponseMetadata.estimated(100, 50);

        Map<String, Object> map = meta.toMap();

        assertThat(map).containsEntry("estimated", true);
        assertThat(map).doesNotContainKey("costUsd");
        assertThat(map).doesNotContainKey("model");
        assertThat(map).doesNotContainKey("apiDurationMs");
        assertThat(map).doesNotContainKey("numTurns");
        assertThat(map).doesNotContainKey("sessionId");
        assertThat(map).doesNotContainKey("toolNames");
        assertThat(map).doesNotContainKey("toolUseCount");
    }

    @Test
    void toMap_emptyToolListExcluded() {
        var meta = new AiResponseMetadata(100, 50, null, null, false, null, null, null, List.of());

        Map<String, Object> map = meta.toMap();

        assertThat(map).doesNotContainKey("toolNames");
        assertThat(map).doesNotContainKey("toolUseCount");
    }

    @Test
    void toMap_nullToolListExcluded() {
        var meta = new AiResponseMetadata(100, 50, null, null, false, null, null, null, null);

        Map<String, Object> map = meta.toMap();

        assertThat(map).doesNotContainKey("toolNames");
        assertThat(map).doesNotContainKey("toolUseCount");
    }

    /** The web requests a CLI reports are metadata of the call like its tokens; absent, they are no keys. */
    @Test
    void webRequests_areInTheMap_onlyWhenTheCliReportedThem() {
        var meta = new AiResponseMetadata(10, 5, null, null, false, null, null, null, List.of(), null, null, null,
                3, 1);

        assertThat(meta.toMap()).containsEntry("webSearchRequests", 3).containsEntry("webFetchRequests", 1);
        assertThat(new AiResponseMetadata(10, 5, null, null, false, null, null, null, List.of()).toMap())
                .doesNotContainKeys("webSearchRequests", "webFetchRequests");
    }
}
