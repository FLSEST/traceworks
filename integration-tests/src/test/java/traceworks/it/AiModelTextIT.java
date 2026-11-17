package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a model wrote, across blocks (package {@code combo-model-text}; docs/cli.md, "Values: how a protected value
 * reaches a command"): it reaches a command as a value and in no other way. The fake Claude repeats its prompt, so
 * the answer of the first step holds a command substitution, backticks and a semicolon with a command behind it;
 * each of them would create a file in the scratch directory if any shell ever read the answer as code.
 *
 * <p>A refused shell line, mount or tool argument ends its run whatever {@code on_failure} says (docs/cli.md, "When
 * a step fails"), so the line and the mount are refused in a child each, whose parent goes on, and the tool argument
 * in the last step of the run.
 */
class AiModelTextIT extends AiIntegrationTest {

    /** About what reaches a shell, not about the network: no proxy and no network per run. */
    @Override
    boolean behindTheEgressProxy() {
        return false;
    }

    @Test
    void modelText_crossesBlocksOnlyAsAValue() throws IOException {
        Result run = traceworks.run("combo-model-text");

        // The refused tool argument is the run's last step and ends it: on_failure: continue lets no run past a
        // refusal. A run that went on would end COMPLETED and start the step "never"
        assertThat(run.code()).as(run.describe()).isEqualTo(3);
        assertThat(run.status()).isEqualTo("FAILED");
        RunFolder folder = traceworks.folder(run);
        String answer = folder.output("ask");
        assertThat(answer).startsWith("fake-claude answered").contains("$(touch /scratch/pwned-dollar)",
                "`touch /scratch/pwned-tick`", "; touch /scratch/pwned-semicolon");

        // simple -> shell with values: every byte arrives (the command hashed what it found in the variable), and
        // nothing of it was run. A value that passed through the shell's parser would lose the substitution's text
        // and leave its file
        assertThat(folder.stepStatus("take")).isEqualTo("COMPLETED");
        assertThat(folder.output("take")).isEqualTo(sha256(answer) + "\npwned files: 0");
        JsonNode take = folder.ofStep("step.command", folder.stepIndex("take")).getFirst();
        assertThat(json(folder.blob(take.path("values"))).path("ANSWER").asText()).isEqualTo(answer);
        assertThat(folder.blob(take.path("command"))).as("the command is the workflow's text, without the value")
                .doesNotContain("pwned-dollar");

        // -> switch > simple: the case's AI step got the answer in its prompt, whole
        String second = folder.output("route");
        assertThat(second).startsWith("fake-claude answered").contains("Second opinion on what follows.",
                "$(touch /scratch/pwned-dollar)");
        JsonNode request = folder.ofStep("ai.request", folder.stepIndex("route")).getFirst();
        assertThat(folder.blob(request.path("prompt"))).startsWith("Second opinion on what follows.")
                .contains(answer);

        // -> a child: it gets the parent's last output as a value and prints it, byte for byte
        assertThat(folder.stepStatus("handed")).isEqualTo("COMPLETED");
        assertThat(folder.output("handed")).isEqualTo(second);
        String receiving = folder.metadata("handed").path("subRunId").asText();
        JsonNode received = folder.eventsOf(receiving, "step.command").getFirst();
        assertThat(json(folder.blob(received.path("values"))).path("HANDED").asText()).isEqualTo(second);

        // A shell line of a child that names it: refused before anything runs. on_failure: continue on the child's
        // step does not get the child past it, and only the parent's on_failure lets the parent go on
        assertThat(folder.stepStatus("in_line")).isEqualTo("FAILED");
        assertThat(folder.output("in_line")).contains("shell line refused, nothing ran: {{ previous_output }}");
        String inLine = folder.metadata("in_line").path("subRunId").asText();
        assertThat(folder.stepNamesOf(inLine)).as("the child did not go past its refused step")
                .containsExactly("inject");
        assertThat(folder.eventsOf(inLine, "step.command")).as("no command of the refused line").isEmpty();
        assertThat(folder.eventsOf(inLine, "run.finished").getFirst().path("status").asText()).isEqualTo("FAILED");

        assertThat(folder.output("again")).as("the parent went on, and the text is still whole").isEqualTo(answer);

        // The host path of a mount of a child that names it: refused, nothing started
        assertThat(folder.stepStatus("in_mount")).isEqualTo("FAILED");
        assertThat(folder.output("in_mount")).contains("mounts must not contain untrusted values (untrusted input,"
                + " tainted block output, platform data, text a model wrote): {{ previous_output }}:/data:ro");
        String inMount = folder.metadata("in_mount").path("subRunId").asText();
        assertThat(folder.stepNamesOf(inMount)).containsExactly("mounted");
        assertThat(folder.eventsOf(inMount, "step.command")).as("no command with the refused mount").isEmpty();

        // The argument of a tool: refused, and the run ends with it
        assertThat(folder.stepStatus("in_args")).isEqualTo("FAILED");
        String refusal = "container_args must not contain untrusted values (untrusted input, tainted block output,"
                + " platform data, text a model wrote): {{ block.ask.output }}";
        assertThat(folder.output("in_args")).contains(refusal);
        assertThat(folder.ofStep("step.command", folder.stepIndex("in_args"))).isEmpty();
        assertThat(folder.blob(folder.only("run.finished").path("error"))).contains(refusal);
        assertThat(folder.ownEvents("step.started")).extracting(e -> e.path("name").asText())
                .containsExactly("ask", "take", "route", "handed", "in_line", "again", "in_mount", "in_args");

        // Nothing ran for the three refusals, and no container started: the record holds the five commands of the
        // steps that ran (two AI calls, three shell steps), none in the tool's image, and the scratch directory
        // holds neither a file the model's text would have made nor the note of the tool
        List<JsonNode> commands = folder.events("step.command");
        assertThat(commands).hasSize(5);
        assertThat(commands).extracting(command -> command.path("image").asText()).doesNotContain("./images/tool");
        try (Stream<Path> files = Files.list(folder.dir().resolve("scratch"))) {
            assertThat(files.map(file -> file.getFileName().toString()).toList())
                    .noneMatch(name -> name.contains("pwned")).doesNotContain("tool-ran.txt");
        }
    }

    private static JsonNode json(String text) throws IOException {
        return new ObjectMapper().readTree(text);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
