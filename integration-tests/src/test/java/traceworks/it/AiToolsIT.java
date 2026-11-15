package traceworks.it;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tool policy of an AI step (package {@code ai-tools}, docs/cli.md "AI steps"): each {@code tools} value reaches
 * each CLI as the documented flags. The fake CLI answers with the arguments it got, each in brackets, so the empty
 * argument of {@code --tools ""} shows as {@code []}.
 */
class AiToolsIT extends AiIntegrationTest {

    /** About the arguments each tools value gives a CLI, not about the network: no proxy and no network per run. */
    @Override
    boolean behindTheEgressProxy() {
        return false;
    }

    private static final Pattern ARGS = Pattern.compile("(?m)^args:(.*)$");

    /** The end of the arguments each step must have, by step name. */
    private static final Map<String, String> FLAGS = new LinkedHashMap<>();

    static {
        FLAGS.put("claude_none", "[--tools] []");
        FLAGS.put("claude_read", "[--tools] [Read,Grep,Glob] [--dangerously-skip-permissions]");
        FLAGS.put("claude_write", "[--tools] [Read,Grep,Glob,Edit,Write] [--dangerously-skip-permissions]");
        FLAGS.put("claude_shell", "[--tools] [Read,Grep,Glob,Edit,Write,Bash] [--dangerously-skip-permissions]");
        FLAGS.put("pi_none", "[--no-tools]");
        FLAGS.put("pi_read", "[--tools] [read,grep,find,ls]");
        FLAGS.put("pi_write", "[--tools] [read,grep,find,ls,edit,write]");
        FLAGS.put("pi_shell", "[--tools] [read,grep,find,ls,edit,write,bash]");
    }

    @Test
    void eachToolsValue_reachesItsCliAsTheDocumentedFlags() {
        Result run = traceworks.run("ai-tools");
        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);

        FLAGS.forEach((step, flags) -> {
            String output = folder.output(step);
            Matcher matcher = ARGS.matcher(output);
            assertThat(matcher.find()).as(output).isTrue();
            String args = matcher.group(1).strip();
            assertThat(args).as(step).endsWith(flags);
            assertThat(folder.ofStep("ai.request", folder.stepIndex(step)).getFirst().path("tools").asText())
                    .as(step).isEqualTo(step.substring(step.indexOf('_') + 1));
            if (step.startsWith("claude")) {
                assertThat(args).as(step).startsWith("[-p] [--output-format] [stream-json] [--verbose]"
                        + " [--strict-mcp-config] [--setting-sources] [] [--settings] [{\"disableAllHooks\":true}]"
                        + " [--disable-slash-commands]");
            } else {
                assertThat(args).as(step).startsWith("[-p] [--no-session] [--no-context-files] [--mode] [json]"
                        + " [--offline] [--no-extensions] [--no-mcp] [--no-approve] [--no-skills] [--no-prompt-templates]"
                        + " [--no-themes] [--thinking] [medium] [--system-prompt] [] [--append-system-prompt] []");
            }
        });
    }

    /**
     * Package {@code ai-wrapper-tools}: the AI step of a wrapper carries the wrapper's {@code tools}, and has none
     * without the key.
     */
    @Test
    void aWrappersAiStep_carriesTheWrappersTools() {
        Result run = traceworks.run("ai-wrapper-tools");
        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);

        assertThat(folder.events("ai.request")).extracting(request -> request.path("tools").asText())
                .containsExactly("read", "read", "none");
        assertThat(folder.blobTexts()).anySatisfy(text -> assertThat(text)
                        .contains("[--tools] [Read,Grep,Glob] [--dangerously-skip-permissions]"))
                .anySatisfy(text -> assertThat(text).contains("[--tools] [read,grep,find,ls]"))
                .anySatisfy(text -> assertThat(text).contains("[--tools] []"));
    }
}
