package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The frame of every test that runs an AI block (docs/cli.md, "AI steps"): no model is ever called, and no real
 * credential is ever used.
 *
 * <ul>
 *   <li><b>Fake images, set through {@code ai_images}.</b> Before each test the sandbox's {@code traceworks.json} replaces
 *       the built-in images of Claude and Pi with the fake images of {@code integration-tests/fake-ai}
 *       ({@link ItEnvironment#fakeImage}), whose CLIs answer in the real CLIs' stream formats without a model and without
 *       network use. A test cannot forget it.</li>
 *   <li><b>The real egress proxy, where a test needs it.</b> An AI call's container connects only through the proxy
 *       of the built-in image {@code egress}, to the hosts of its provider: that is the default of Traceworks and of
 *       this frame, and the suite builds the image ({@link ItEnvironment#egressImage}). Each run behind the proxy
 *       makes two networks on the host and starts one more container per call. A class whose tests are about
 *       something else (the arguments a CLI gets, the record of a call, a gate) says so by overriding
 *       {@link #behindTheEgressProxy}: its sandboxes get {@code ai_network: "open"}, and its runs make no network
 *       and start no proxy, which is checked after each test. The fakes connect nowhere unless a test asks them
 *       to.</li>
 *   <li><b>Fake credentials.</b> The sandbox's {@code credentials.json} holds {@link #FAKE_KEY} as
 *       {@code ANTHROPIC_API_KEY}, which Claude and Pi both read. The harness strips every credential name from the
 *       environment of each command ({@link Traceworks#credentialLike}), so what the machine's user has set cannot
 *       reach a container.</li>
 *   <li><b>The built-in AI images stay as they are.</b> The suite never builds, tags or removes
 *       {@code traceworks/claude} or {@code traceworks/pi}, which may be the real images of whoever runs the suite. Before
 *       the tests of a class the repositories' image IDs are noted, and after them compared: absent is fine, changed is a
 *       failure. After each test, no command of any run used one of the built-in AI images.</li>
 * </ul>
 */
abstract class AiIntegrationTest extends IntegrationTest {

    /** The credential every AI test uses: an obvious fake, which no provider accepts. */
    static final String FAKE_KEY = "sk-ant-it-fake-0123456789";

    private static List<String> builtInAiImagesBefore;

    @BeforeAll
    static void noteTheBuiltInAiImages() {
        builtInAiImagesBefore = ItEnvironment.get().builtInAiImages();
    }

    @AfterAll
    static void theBuiltInAiImagesAreUntouched() {
        assertThat(ItEnvironment.get().builtInAiImages())
                .as("the image IDs of traceworks/claude and traceworks/pi, which the suite must never touch")
                .isEqualTo(builtInAiImagesBefore);
    }

    /**
     * Whether the AI calls of this class's tests run behind the egress proxy, as they do by default. A class returns
     * {@code false} when none of its tests is about where an AI container can connect, what the proxy records, or
     * what is left of a call that was stopped: the classes that are stay as they are.
     */
    boolean behindTheEgressProxy() {
        return true;
    }

    @BeforeEach
    void useTheFakeCliAndAFakeKey() {
        ItEnvironment environment = traceworks.environment();
        environment.egressImage();
        if (!behindTheEgressProxy()) {
            traceworks.openAiNetwork();
        }
        traceworks.aiImages(Map.of("claude", environment.fakeImage("claude"), "pi", environment.fakeImage("pi")));
        traceworks.credentials(Map.of("ANTHROPIC_API_KEY", FAKE_KEY));
    }

    @AfterEach
    void noCommandRanInABuiltInAiImage() {
        for (RunFolder folder : traceworks.folders()) {
            for (JsonNode command : folder.events("step.command")) {
                assertThat(command.path("image").asText(""))
                        .as("the image of a command of run %s", folder.id())
                        .doesNotStartWith("traceworks/claude:").doesNotStartWith("traceworks/pi:");
            }
        }
    }

    /** A class that asked for the open AI network got it: no command of its runs went through a proxy. */
    @AfterEach
    void aClassWithoutTheProxy_madeNoNetwork() {
        if (behindTheEgressProxy()) {
            return;
        }
        for (RunFolder folder : traceworks.folders()) {
            for (JsonNode command : folder.events("step.command")) {
                assertThat(command.path("network").asText(""))
                        .as("the network of a command of run %s, in a class that runs without the egress proxy",
                                folder.id())
                        .isNotEqualTo("hosts");
            }
        }
    }

    /** The fake image of Claude, as the runs of this sandbox name it. */
    String fakeClaude() {
        return traceworks.environment().fakeImage("claude");
    }

    /** The fake image of Pi. */
    String fakePi() {
        return traceworks.environment().fakeImage("pi");
    }
}
