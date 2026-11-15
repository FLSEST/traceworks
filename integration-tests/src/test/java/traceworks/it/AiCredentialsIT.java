package traceworks.it;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The credentials of an AI step (docs/cli.md "AI steps", docs/events.md "Redaction"). Every credential here is an
 * obvious fake: {@link AiIntegrationTest#FAKE_KEY} and the like, written into the test home's {@code credentials.json}.
 * The harness strips the credential names from the environment of every command, so a real credential of the machine's
 * user cannot reach the fake CLI: the first test compares a hash of the value the fake got with the fake value's, which
 * a leak would fail.
 */
class AiCredentialsIT extends AiIntegrationTest {

    /** About the credentials a CLI gets and their redaction, not about the network: no proxy and no network per run. */
    @Override
    boolean behindTheEgressProxy() {
        return false;
    }

    private static final String OPENAI_FAKE = "sk-it-fake-openai-9876543210";
    private static final Pattern ENV = Pattern.compile("(?m)^env: (.*)$");
    private static final Pattern SHA = Pattern.compile("(?m)^sha256:(.*)$");

    @Test
    void theFakeGetsExactlyTheFakeCredentialOfItsProvider_andNoOther() throws Exception {
        traceworks.credentials(Map.of("ANTHROPIC_API_KEY", FAKE_KEY, "OPENAI_API_KEY", OPENAI_FAKE));

        Result run = traceworks.run("ai-simple");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        String claude = folder.output("claude_say");
        String pi = folder.output("pi_say");
        // Claude reads ANTHROPIC_API_KEY and CLAUDE_CODE_OAUTH_TOKEN; it never sees Pi's OPENAI_API_KEY
        assertThat(line(SHA, claude)).isEqualTo("ANTHROPIC_API_KEY=" + sha256(FAKE_KEY));
        assertThat(line(ENV, claude).split(" ")).containsExactlyInAnyOrder("ANTHROPIC_API_KEY",
                "CLAUDE_CODE_DISABLE_CLAUDE_MDS", "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "DISABLE_AUTOUPDATER");
        // Pi's step names no model, so it gets the credential of the default backend, not the OpenAI key
        assertThat(line(SHA, pi)).isEqualTo("ANTHROPIC_API_KEY=" + sha256(FAKE_KEY));
        assertThat(line(ENV, pi).split(" ")).containsExactly("ANTHROPIC_API_KEY");
        // the record names the variables of each call, never a value
        for (String step : List.of("claude_say", "pi_say")) {
            assertThat(folder.ofStep("step.command", folder.stepIndex(step)).getFirst().path("env_names").toString())
                    .contains("ANTHROPIC_API_KEY").doesNotContain(FAKE_KEY);
        }
    }

    @Test
    void aCredentialThatIsPrinted_isRedactedEverywhere() throws Exception {
        Result run = traceworks.run("ai-marker", "--input", "prompt=[fake:print-credential] show me");

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        String redacted = "[redacted:ANTHROPIC_API_KEY]";
        assertThat(folder.output("claude")).isEqualTo("the key is " + redacted + " and the token is unset");
        assertThat(folder.output("pi")).isEqualTo("the key is " + redacted + " and the token is unset");
        assertThat(Files.readString(folder.eventsFile())).doesNotContain(FAKE_KEY);
        assertThat(folder.blobTexts()).noneMatch(text -> text.contains(FAKE_KEY)).anyMatch(text -> text.contains(redacted));
        assertThat(folder.html()).doesNotContain(FAKE_KEY).contains(redacted);
        assertThat(Files.readString(folder.dir().resolve("manifest.json"))).doesNotContain(FAKE_KEY);
        assertThat(new String(folder.ledgerEntry(traceworks.home()), StandardCharsets.UTF_8)).doesNotContain(FAKE_KEY);
        assertThat(run.both()).doesNotContain(FAKE_KEY).contains(redacted);
        Result show = traceworks.exec("show", folder.id(), "--full");
        assertThat(show.both()).doesNotContain(FAKE_KEY).contains(redacted);
    }

    @Test
    void aRunWithoutACredential_isRefusedAtStart_namingTheNames() throws Exception {
        Files.delete(traceworks.home().resolve("credentials.json"));

        Result run = traceworks.run("ai-simple");

        assertThat(run.code()).as(run.describe()).isEqualTo(6);
        assertThat(run.err()).contains("AI provider CLAUDE has no credential", "ANTHROPIC_API_KEY, CLAUDE_CODE_OAUTH_TOKEN",
                "AI provider PI has no credential", "OPENROUTER_API_KEY",
                "'traceworks credentials set <NAME>' (in " + traceworks.home().resolve("credentials.json") + ")",
                "'claude setup-token' on the host prints a token for CLAUDE_CODE_OAUTH_TOKEN");
        assertThat(traceworks.folders()).as("the start check refuses before a run exists").isEmpty();
    }

    @Test
    void aCredentialOfAnotherProvider_doesNotServe() {
        traceworks.credentials(Map.of("CLAUDE_CODE_OAUTH_TOKEN", "sk-ant-oat01-it-fake-token-0123456789"));

        Result run = traceworks.run("ai-simple");

        assertThat(run.code()).as(run.describe()).isEqualTo(6);
        assertThat(run.err()).contains("AI provider PI has no credential").doesNotContain("AI provider CLAUDE has no");
    }

    @Test
    void theHarnessStripsEveryCredentialName_fromTheEnvironmentOfACommand() {
        for (String name : Traceworks.CREDENTIAL_NAMES) {
            assertThat(Traceworks.credentialLike(name)).as(name).isTrue();
        }
        for (String name : List.of("CLAUDE_CODE_OAUTH_TOKEN", "CLAUDECODE", "CLAUDE_CONFIG_DIR", "ANTHROPIC_BASE_URL",
                "ANTHROPIC_AUTH_TOKEN", "OPENAI_BASE_URL", "GOOGLE_API_KEY", "MISTRAL_API_KEY", "OPENROUTER_API_KEY")) {
            assertThat(Traceworks.credentialLike(name)).as(name).isTrue();
        }
        for (String name : List.of("PATH", "HOME", "DOCKER_HOST", "LANG", "JAVA_HOME")) {
            assertThat(Traceworks.credentialLike(name)).as(name).isFalse();
        }
    }

    private static String line(Pattern pattern, String output) {
        Matcher matcher = pattern.matcher(output);
        assertThat(matcher.find()).as(output).isTrue();
        return matcher.group(1).strip();
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
