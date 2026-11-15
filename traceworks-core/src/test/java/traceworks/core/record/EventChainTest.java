package traceworks.core.record;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventChainTest {

    @Test
    void genesis_isTheHashOfThePrefixedRunId() {
        assertThat(EventChain.genesis("00000000-0000-0000-0000-000000000001"))
                .isEqualTo("6063347867ddde0ad06ce66949036d60b13f095f5becc6953944de398d880eed");
    }

    @Test
    void sha256Hex_isLowerCaseHex() {
        assertThat(EventChain.sha256Hex(new byte[0]))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }
}
