package traceworks.core.port;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@link AiProviders#of}: the reference behaviour of the port, which refuses an unknown provider id. */
class AiProvidersTest {

    private static AiProvider provider(String id) {
        AiProvider provider = mock(AiProvider.class);
        when(provider.getId()).thenReturn(id);
        return provider;
    }

    private final AiProvider claude = provider("CLAUDE");
    private final AiProvider pi = provider("PI");
    private final AiProviders providers = AiProviders.of("claude", List.of(claude, pi));

    @Test
    void resolve_findsTheProvider_ignoringCaseAndPadding() {
        assertThat(providers.resolve("PI")).isSameAs(pi);
        assertThat(providers.resolve(" pi ")).isSameAs(pi);
        assertThat(providers.defaultProviderId()).isEqualTo("CLAUDE");
    }

    @Test
    void resolve_nullOrBlank_isTheDefaultProvider() {
        assertThat(providers.resolve(null)).isSameAs(claude);
        assertThat(providers.resolve("  ")).isSameAs(claude);
    }

    /** No fallback to the default provider here: Traceworks refuses, so a step never silently runs on another model. */
    @Test
    void resolve_unknownId_isRefusedWithTheConfiguredIds() {
        assertThatThrownBy(() -> providers.resolve("GEMINI"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown AI provider 'GEMINI'; configured: CLAUDE, PI");
    }

    @Test
    void withoutProviders_everyLookupIsRefused() {
        AiProviders none = AiProviders.of(null, List.of());

        assertThat(none.defaultProviderId()).isNull();
        assertThatThrownBy(() -> none.resolve(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No AI provider configured");
        assertThatThrownBy(() -> none.resolve("CLAUDE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown AI provider 'CLAUDE'; configured: none");
    }

    @Test
    void aDefaultThatIsNotConfigured_isRefusedRightAway() {
        assertThatThrownBy(() -> AiProviders.of("PI", List.of(claude)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Default AI provider 'PI' is not configured; configured: CLAUDE");
    }
}
