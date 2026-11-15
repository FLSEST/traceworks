package traceworks.core.blocks;

import traceworks.core.context.ExecutionContext;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.InvalidSettingException;
import traceworks.core.model.StepResult;
import traceworks.core.model.StepStatus;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.CommandRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The probes of the review (B1 to B7, L1) at run time: a setting of the wrong type or out of range fails the block
 * with a message that names the key, what it must be and what was found, before any command runs. Never a
 * {@link ClassCastException}, never a silent default. The analyzer's side is in {@code ConfigKeyRulesTest.Types}.
 */
class BlockSettingsReadTest {

    private final List<String> ran = new ArrayList<>();
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        CommandRunner runner = request -> {
            ran.add(request.command());
            return new CommandResult(0, "ok", "", 1, false);
        };
        context = ExecutionContext.builder().containerId("c").workflowRunId("run-1").commandRunner(runner).build();
        context.setProjectPath("/test/project");
    }

    private static BlockDefinition block(String type, Object... keysAndValues) {
        Map<String, Object> config = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            config.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return BlockDefinition.builder().type(type).name("b").config(config).build();
    }

    private void refused(BlockDefinition block, String message) {
        var executor = CoreBlocks.registry(null).executorFor(block.getType());
        assertThatThrownBy(() -> executor.execute(block, context))
                .isInstanceOf(InvalidSettingException.class)
                .hasMessage(message);
        assertThat(ran).as("no command runs").isEmpty();
    }

    /** B1: the text "true" turned escaping off; a previous output {@code x; echo INJECTED} then ran. */
    @Test
    void b1_shellEscapeVariablesAsText_failsTheBlock() {
        context.setVariable("previous_output", "x; echo INJECTED");
        refused(block("shell", "command", "echo {{ previous_output }}", "shell_escape_variables", "true"),
                "shell: 'shell_escape_variables' must be true or false, found the text \"true\"");
    }

    @Test
    void b1_otherFlagsAsText_failTheBlock() {
        refused(block("shell", "command", "make", "checked_command", "true"),
                "shell: 'checked_command' must be true or false, found the text \"true\"");
        refused(block("shell", "command", "make", "network", "true"),
                "shell: 'network' must be true or false, found the text \"true\"");
        refused(block("shell", "command", "make", "workspace_writable", "yes"),
                "shell: 'workspace_writable' must be true or false, found the text \"yes\"");
        refused(block("retry", "execution_mode", "shell", "command", "make", "exponential_backoff", "true"),
                "retry: 'exponential_backoff' must be true or false, found the text \"true\"");
        refused(block("foreach", "execution_mode", "shell", "command", "echo {{ item }}", "items", List.of("a"),
                "network", "true"), "foreach: 'network' must be true or false, found the text \"true\"");
    }

    /** The one exception: untrusted_input keeps reading the text "true" as true, so the safe side stays on. */
    @Test
    void b1_untrustedInputAsText_staysOn() {
        assertThat(traceworks.core.context.UntrustedInput.declared(Map.of("untrusted_input", "true")))
                .isTrue();
    }

    /** B3: a number or a list written as text was a ClassCastException. */
    @Test
    void b3_numbersAndListsAsText_failTheBlock() {
        refused(block("shell", "command", "make", "timeout_seconds", "5"),
                "shell: 'timeout_seconds' must be a whole number between 1 and 86,400, found the text \"5\"");
        refused(block("foreach", "execution_mode", "shell", "command", "echo {{ item }}", "items", "a,b",
                "timeout_seconds", "9"),
                "foreach: 'timeout_seconds' must be a whole number between 1 and 86,400, found the text \"9\"");
        refused(block("while", "execution_mode", "shell", "command", "true", "condition_command", "false",
                "max_iterations", "3"),
                "while: 'max_iterations' must be a whole number between 1 and 10,000, found the text \"3\"");
        refused(block("approval", "question", "ok?", "options", "Yes,No"),
                "approval: 'options' must be a list of texts, found the text \"Yes,No\"");
    }

    /** B4 and L1: 0 and -3 timed out at once, 0.5 was 0, values beyond an int wrapped, a null was a NPE. */
    @Test
    void b4_l1_timeoutsOutOfRange_failTheBlock() {
        String must = "shell: 'timeout_seconds' must be a whole number between 1 and 86,400, found ";
        refused(block("shell", "command", "make", "timeout_seconds", 0), must + "0");
        refused(block("shell", "command", "make", "timeout_seconds", -3), must + "-3");
        refused(block("shell", "command", "make", "timeout_seconds", 0.5), must + "0.5");
        refused(block("shell", "command", "make", "timeout_seconds", 3_000_000_000L), must + "3000000000");
        refused(block("shell", "command", "make", "timeout_seconds", 4_294_967_297L), must + "4294967297");
        refused(block("shell", "container_image", "img", "container_args", List.of("scan"), "timeout_seconds", "60"),
                must + "the text \"60\"");
    }

    /** A null counts as absent: the default applies, no NullPointerException. */
    @Test
    void l1_aNullTimeout_isTheDefault() {
        StepResult step = new ShellBlockExecutor((java.nio.file.Path) null)
                .execute(block("shell", "command", "make", "timeout_seconds", null), context);

        assertThat(step.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(ran).containsExactly("make");
    }

    @Test
    void l1_retryArithmetic_isGuarded() {
        refused(block("retry", "execution_mode", "shell", "command", "make", "delay", -1),
                "retry: 'delay' must be a whole number between 0 and 3,600, found -1");
        refused(block("retry", "execution_mode", "shell", "command", "make", "max_retries", Integer.MAX_VALUE),
                "retry: 'max_retries' must be a whole number between 0 and 100, found 2147483647");
        refused(block("while", "execution_mode", "shell", "command", "true", "condition_command", "false",
                "max_iterations", 0), "while: 'max_iterations' must be a whole number between 1 and 10,000, found 0");
    }

    /** B5: a list as command ran its Java text ({@code [echo, x]}). */
    @Test
    void b5_commandAsList_failsTheBlock() {
        refused(block("shell", "command", List.of("echo", "x")), "shell: 'command' must be text, found a list");
    }

    /** B6: mounts as text was dropped silently. */
    @Test
    void b6_mountsAsText_failsTheBlock() {
        refused(block("shell", "command", "ls", "container_image", "alpine:3", "mounts", "/data:/data:ro"),
                "shell: 'mounts' must be a list of texts, found the text \"/data:/data:ro\"");
        refused(block("foreach", "execution_mode", "shell", "command", "ls", "items", List.of("a"),
                "container_image", "alpine:3", "mounts", "/data:/data:ro"),
                "foreach: 'mounts' must be a list of texts, found the text \"/data:/data:ro\"");
    }

    /** A switch case is read as a whole before any case runs. */
    @Test
    void switchCases_ofTheWrongShape_failTheBlock() {
        refused(block("switch", "expression", "a", "cases", List.of(Map.of("value", 1, "blocks", List.of()))),
                "switch: 'cases[0].value' must be text, found 1");
        refused(block("switch", "expression", "a", "cases", "a"),
                "switch: 'cases' must be a list of objects, found the text \"a\"");
    }

    /** compress-ai: its timeout is checked as its max_chars is, and fails the step with the run's message. */
    @Test
    void compressAi_timeoutOutOfRange_failsTheStep() {
        StepResult step = new CompressAiBlockExecutor()
                .execute(block("compress-ai", "source", "previous", "timeout_seconds", "60"), context);

        assertThat(step.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(step.getOutput()).isEqualTo("Compression failed: compress-ai: 'timeout_seconds' must be a whole"
                + " number between 1 and 86,400");
    }
}
