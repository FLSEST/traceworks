package traceworks.core.port;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Port to the configured AI providers: picks the provider for an AI step.
 */
public interface AiProviders {

    /**
     * The provider for a step or workflow setting.
     *
     * @param providerId the provider id, compared case-insensitively (for example {@code CLAUDE}); {@code null}
     *                   or blank selects the {@linkplain #defaultProviderId() default provider}
     * @throws IllegalArgumentException if {@code providerId} names no configured provider, or if it is
     *                                  {@code null} or blank and no default provider is configured.
     *                                  An unknown id never falls back to the default provider: a step
     *                                  that asks for one model never silently runs on another.
     */
    AiProvider resolve(String providerId);

    /** The id of the provider that steps without their own provider setting use; {@code null} when none. */
    String defaultProviderId();

    /**
     * The providers of a fixed list, with the lookup rules of {@link #resolve}.
     *
     * @param defaultProviderId the id of the default provider, compared case-insensitively; {@code null} or blank
     *                          for none
     * @param providers         the providers, keyed by their upper-cased {@link AiProvider#getId() id}
     * @throws IllegalArgumentException if the default provider is not among {@code providers}
     */
    static AiProviders of(String defaultProviderId, Collection<? extends AiProvider> providers) {
        Map<String, AiProvider> byId = new LinkedHashMap<>();
        for (AiProvider provider : providers) {
            byId.put(normalize(provider.getId()), provider);
        }
        String configured = byId.isEmpty() ? "none" : String.join(", ", byId.keySet());
        String defaultId = defaultProviderId == null || defaultProviderId.isBlank() ? null : normalize(defaultProviderId);
        if (defaultId != null && !byId.containsKey(defaultId)) {
            throw new IllegalArgumentException(
                    "Default AI provider '" + defaultProviderId + "' is not configured; configured: " + configured);
        }
        return new AiProviders() {
            @Override
            public AiProvider resolve(String providerId) {
                if (providerId == null || providerId.isBlank()) {
                    if (defaultId == null) {
                        throw new IllegalArgumentException("No AI provider configured");
                    }
                    return byId.get(defaultId);
                }
                AiProvider provider = byId.get(normalize(providerId));
                if (provider == null) {
                    throw new IllegalArgumentException(
                            "unknown AI provider '" + providerId.strip() + "'; configured: " + configured);
                }
                return provider;
            }

            @Override
            public String defaultProviderId() {
                return defaultId;
            }
        };
    }

    private static String normalize(String providerId) {
        return providerId.strip().toUpperCase(Locale.ROOT);
    }
}
