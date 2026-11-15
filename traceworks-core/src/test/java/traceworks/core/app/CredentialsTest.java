package traceworks.core.app;

import traceworks.core.port.CredentialStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The stored credentials: one entry is set or removed, the others stay, and no message shows a value. */
class CredentialsTest {

    private static final String VALUE = "sk-ant-oat01-not-a-real-token";

    /** A store in memory that counts its writes. */
    private static final class MemoryStore implements CredentialStore {
        private Map<String, String> values = new TreeMap<>();
        private final List<Map<String, String>> writes = new ArrayList<>();
        private RuntimeException failure;

        @Override
        public String location() {
            return "/home/x/.traceworks/credentials.json";
        }

        @Override
        public Map<String, String> read() {
            if (failure != null) {
                throw failure;
            }
            return Map.copyOf(values);
        }

        @Override
        public void write(Map<String, String> next) {
            values = new TreeMap<>(next);
            writes.add(Map.copyOf(next));
        }
    }

    private final MemoryStore store = new MemoryStore();
    private final Credentials credentials = new Credentials(store);

    @Test
    void set_addsTheEntry_andKeepsTheOthers() {
        store.values.put("DEPLOY_TOKEN", "deploy-token-value");

        assertThat(credentials.set("CLAUDE_CODE_OAUTH_TOKEN", VALUE)).as("replaced").isFalse();

        assertThat(store.values).containsExactlyInAnyOrderEntriesOf(Map.of("DEPLOY_TOKEN", "deploy-token-value",
                "CLAUDE_CODE_OAUTH_TOKEN", VALUE));
        assertThat(credentials.names()).containsExactly("CLAUDE_CODE_OAUTH_TOKEN", "DEPLOY_TOKEN");
    }

    @Test
    void set_replacesTheValueOfAName() {
        store.values.put("DEPLOY_TOKEN", "deploy-token-value");

        assertThat(credentials.set("DEPLOY_TOKEN", "another-token-value")).as("replaced").isTrue();

        assertThat(store.values).containsExactly(Map.entry("DEPLOY_TOKEN", "another-token-value"));
    }

    /** What a run would refuse is not stored: the person learns it now, not at the next start. */
    @Test
    void set_refusesAValueNoRunTakes_withoutShowingIt_andWritesNothing() {
        assertThatThrownBy(() -> credentials.set("DEPLOY_TOKEN", "short"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DEPLOY_TOKEN")
                .hasMessageContaining("shorter than 8 characters").hasMessageNotContaining("short\"");
        assertThatThrownBy(() -> credentials.set("DEPLOY_TOKEN", "two-lines-of\nsecret-text"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("control character")
                .hasMessageNotContaining("two-lines-of").hasMessageNotContaining("secret-text");
        assertThatThrownBy(() -> credentials.set("DEPLOY_TOKEN", " padded-secret-value "))
                .hasMessageContaining("whitespace").hasMessageNotContaining("padded-secret-value");

        assertThat(store.writes).isEmpty();
    }

    @Test
    void aNameThatIsNoName_isRefused() {
        for (String name : List.of("lower", "1ST", "WITH-DASH", "", "A B", "{{x}}")) {
            assertThat(Credentials.isName(name)).as(name).isFalse();
            assertThatThrownBy(() -> credentials.set(name, VALUE)).as(name)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a credential name");
            assertThatThrownBy(() -> credentials.remove(name)).as(name).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(Credentials.isName(null)).isFalse();
        assertThat(Credentials.isName("ANTHROPIC_API_KEY")).isTrue();
        assertThat(Credentials.isName("_X9")).isTrue();
        assertThat(store.writes).isEmpty();
    }

    @Test
    void remove_takesTheEntryOut_andSaysWhetherThereWasOne() {
        store.values.put("DEPLOY_TOKEN", "deploy-token-value");
        store.values.put("OTHER_TOKEN", "other-token-value");

        assertThat(credentials.remove("DEPLOY_TOKEN")).isTrue();
        assertThat(credentials.remove("DEPLOY_TOKEN")).isFalse();

        assertThat(store.values).containsExactly(Map.entry("OTHER_TOKEN", "other-token-value"));
        assertThat(store.writes).as("a name that is not there writes nothing").hasSize(1);
    }

    /** A store that cannot be trusted is not written over: the failure of the read ends the change. */
    @Test
    void aStoreThatCannotBeRead_isNotWritten() {
        store.failure = new IllegalArgumentException("credentials.json can be read by others; run chmod 600");

        assertThatThrownBy(() -> credentials.set("DEPLOY_TOKEN", VALUE)).isSameAs(store.failure);
        assertThatThrownBy(() -> credentials.remove("DEPLOY_TOKEN")).isSameAs(store.failure);
        assertThatThrownBy(credentials::names).isSameAs(store.failure);

        assertThat(store.writes).isEmpty();
    }

    @Test
    void location_isTheStores() {
        assertThat(credentials.location()).isEqualTo("/home/x/.traceworks/credentials.json");
    }
}
