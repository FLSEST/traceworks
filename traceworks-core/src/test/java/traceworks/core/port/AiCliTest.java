package traceworks.core.port;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiCliTest {

    /** The help and the refusal name a subscription's token: it must be a name the start check accepts. */
    @Test
    void subscriptionToken_isOneOfTheProvidersCredentials() {
        assertThat(AiCli.SUBSCRIPTION_TOKENS).isNotEmpty().allSatisfy(
                (provider, name) -> assertThat(AiCli.credentialNamesOf(provider)).as(provider).contains(name));
    }
}
