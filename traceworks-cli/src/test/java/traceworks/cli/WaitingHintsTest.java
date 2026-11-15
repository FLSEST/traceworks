package traceworks.cli;

import traceworks.core.record.RecordedEvent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WaitingHintsTest {

    private final ByteArrayOutputStream shown = new ByteArrayOutputStream();
    private final WaitingHints hints = new WaitingHints(new PrintStream(shown, true, StandardCharsets.UTF_8));

    private static RecordedEvent requested(String parent, String fields) {
        String line = "{\"seq\":3,\"at\":\"2026-11-03T12:34:56.789Z\",\"type\":\"approval.requested\",\"run\":\"child\""
                + (parent == null ? "" : ",\"parent\":\"" + parent + "\"") + ",\"prev\":\"00\"," + fields + "}";
        return RecordedEvent.parse(line.getBytes(StandardCharsets.UTF_8));
    }

    private String out() {
        return shown.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aParkingQuestion_getsNoHint() {
        hints.accept(requested(null, "\"step\":1,\"kind\":\"approval\",\"options\":[\"Yes\"],\"parks\":true"), "root");

        assertThat(out()).isEmpty();
    }

    @Test
    void aQuestionAnsweredFromAPreset_getsNoHint() {
        hints.accept(requested("root", "\"step\":0,\"kind\":\"approval\",\"options\":[\"Yes\"],\"parks\":false,\"preset\":true"),
                "root");

        assertThat(out()).isEmpty();
    }

    @Test
    void aNonParkingApproval_getsOneHintPerOption_atOnce() {
        hints.accept(requested("root", "\"step\":0,\"kind\":\"approval\",\"options\":[\"Yes\",\"It's no\"],\"parks\":false"),
                "root");

        assertThat(out()).isEqualTo("""
                waiting for an answer: traceworks approve 'root' --answer 'Yes'
                waiting for an answer: traceworks approve 'root' --answer 'It'\\''s no'
                """);
    }

    @Test
    void aNonParkingHumanInput_getsTheAnswerValueForm() {
        hints.accept(requested(null, "\"step\":0,\"kind\":\"human-input\",\"options\":[],\"parks\":false"), "root");

        assertThat(out()).isEqualTo("waiting for an answer: traceworks answer 'root' --value '<text>'\n");
    }

    @Test
    void otherEvents_getNoHint() {
        hints.accept(RecordedEvent.parse(("{\"seq\":1,\"at\":\"2026-11-03T12:34:56.789Z\",\"type\":\"run.parked\","
                + "\"run\":\"r\",\"prev\":\"00\",\"step\":1}").getBytes(StandardCharsets.UTF_8)), "root");

        assertThat(out()).isEmpty();
    }
}
