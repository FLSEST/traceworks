package traceworks.core.port;

import traceworks.core.port.CommandRunner.CommandFacts;
import traceworks.core.port.CommandRunner.CommandPlan;
import traceworks.core.port.CommandRunner.CommandRequest;
import traceworks.core.port.CommandRunner.CommandResult;
import traceworks.core.port.CommandRunner.Mount;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandRunnerTest {

    @Test
    void request_withoutEnvironmentOrMounts_hasEmptyOnes() {
        CommandRequest request = new CommandRequest("echo hi", "/work", 60, null, null, null, false);

        assertThat(request.environment()).isEmpty();
        assertThat(request.mounts()).isEmpty();
    }

    @Test
    void request_copiesEnvironmentAndMounts_andKeepsThemReadOnly() {
        Map<String, String> environment = new HashMap<>(Map.of("A", "1"));
        List<Mount> mounts = new ArrayList<>(List.of(new Mount("/host/src", "/workspace", true)));
        CommandRequest request = new CommandRequest("ls", "/workspace", 30, environment, "shell:1", mounts, true);

        environment.put("B", "2");
        mounts.clear();

        assertThat(request.environment()).containsExactly(Map.entry("A", "1"));
        assertThat(request.mounts()).containsExactly(new Mount("/host/src", "/workspace", true));
        assertThatThrownBy(() -> request.environment().put("C", "3"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> request.mounts().add(new Mount("/a", "/b", false)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void runnerIsAFunctionOfTheRequest() {
        CommandRunner runner = request -> new CommandResult(0, request.command(), "", 5L, false);

        CommandResult result = runner.run(new CommandRequest("echo hi", null, 10, Map.of(), null, List.of(), false));

        assertThat(result).isEqualTo(new CommandResult(0, "echo hi", "", 5L, false));
    }

    /** A result names the limit its runner had: 16 MiB by default, the operator's otherwise. */
    @Test
    void result_namesTheOutputLimitItsRunnerHad() {
        CommandResult byDefault = new CommandResult(153, "y", "", 1, false, List.of("stdout"));
        CommandResult fourMib = new CommandResult(153, "y", "", 1, false, List.of("stdout"), 4 * 1024 * 1024);
        CommandResult both = new CommandResult(153, "y", "n", 1, false, List.of("stdout", "stderr"), 1024 * 1024);

        assertThat(byDefault.outputLimitBytes()).isEqualTo(CommandRunner.OUTPUT_LIMIT_BYTES);
        assertThat(byDefault.truncationNote()).isEqualTo("The command's standard output exceeded 16 MiB; Traceworks"
                + " stopped it and kept the first 16 MiB");
        assertThat(fourMib.outputLimit()).isEqualTo("4 MiB");
        assertThat(fourMib.truncationNote()).isEqualTo("The command's standard output exceeded 4 MiB; Traceworks"
                + " stopped it and kept the first 4 MiB");
        assertThat(both.truncationNote()).isEqualTo("The command's standard output and standard error exceeded"
                + " 1 MiB each; Traceworks stopped it and kept the first 1 MiB of each");
        assertThat(new CommandResult(0, "", "", 1, false, List.of(), 1024).truncationNote()).isEmpty();
        assertThatThrownBy(() -> new CommandResult(0, "", "", 1, false, List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sizeText_namesWholeMebibytes_kibibytes_orBytes() {
        assertThat(CommandRunner.sizeText(16L * 1024 * 1024)).isEqualTo("16 MiB");
        assertThat(CommandRunner.sizeText(1024L * 1024 * 1024)).isEqualTo("1024 MiB");
        assertThat(CommandRunner.sizeText(64 * 1024)).isEqualTo("64 KiB");
        assertThat(CommandRunner.sizeText(1000)).isEqualTo("1000 bytes");
        assertThat(CommandRunner.sizeText(1)).isEqualTo("1 byte");
    }

    /** Tool mode: the image's own entrypoint gets the arguments, no shell in between. */
    @Test
    void entrypointRequest_runsTheImageEntrypointWithArguments() {
        List<String> args = new ArrayList<>(List.of("fs", "/workspace"));
        CommandRequest request = CommandRequest.entrypoint("trivy:1", args, "/workspace", 300, null,
                List.of(new Mount("/p", "/workspace", true)), false);

        args.clear();

        assertThat(request.command()).isNull();
        assertThat(request.entrypointArgs()).containsExactly("fs", "/workspace");
        assertThat(request.runsEntrypoint()).isTrue();
        assertThat(request.image()).isEqualTo("trivy:1");
        assertThatThrownBy(() -> request.entrypointArgs().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new CommandRequest("ls", "/p", 1, null, null, null, false).runsEntrypoint()).isFalse();
    }

    @Test
    void entrypointRequest_needsAnImage_andNoShellCommand() {
        assertThatThrownBy(() -> CommandRequest.entrypoint(null, List.of(), "/w", 1, null, null, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("image");
        assertThatThrownBy(() -> new CommandRequest("ls", "/w", 1, null, "img", null, false, List.of("x")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not both");
        assertThatThrownBy(() -> new CommandRequest(null, "/w", 1, null, "img", null, false, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("command");
    }

    /** The fields added for containers default so that every existing request means what it meant. */
    @Test
    void request_withoutStdinOrWritableWorkspace_hasNoneAndAReadOnlyWorkspace() {
        CommandRequest shell = new CommandRequest("ls", "/w", 1, null, null, null, false);
        CommandRequest tool = CommandRequest.entrypoint("img", List.of("x"), "/w", 1, null, null, false);
        CommandRequest withArgs = new CommandRequest(null, "/w", 1, null, "img", null, false, List.of("x"));

        for (CommandRequest request : List.of(shell, tool, withArgs)) {
            assertThat(request.stdin()).isNull();
            assertThat(request.writableWorkspace()).isFalse();
        }
        CommandRequest writable = shell.withWritableWorkspace(true);
        assertThat(writable.writableWorkspace()).isTrue();
        assertThat(writable).isEqualTo(new CommandRequest("ls", "/w", 1, null, null, null, false, null, null, true));
        assertThat(new CommandRequest("cat", "/w", 1, null, "img", null, false, null, "input", false).stdin())
                .isEqualTo("input");
    }

    /** A runner that plans nothing itself runs the request as it is: on the host when it names no image. */
    @Test
    void defaultPlan_isTheRequestsOwnFacts_andRunsTheRequest() {
        List<CommandRequest> ran = new ArrayList<>();
        CommandRunner runner = request -> {
            ran.add(request);
            return new CommandResult(0, "ok", "", 1, false);
        };
        CommandRequest request = new CommandRequest("make", "/w", 10, Map.of("B", "2", "A", "1"), "img",
                List.of(new Mount("/a", "/b", true)), true);

        CommandPlan plan = runner.plan(request);

        assertThat(plan.facts()).isEqualTo(new CommandFacts("/w", "img", null, List.of(new Mount("/a", "/b", true)),
                true, List.of("A", "B"), false));
        assertThat(ran).as("planning runs nothing").isEmpty();
        assertThat(plan.execution().get().stdout()).isEqualTo("ok");
        assertThat(ran).containsExactly(request);
        assertThat(runner.plan(new CommandRequest("ls", "/w", 1, null, null, null, false)).facts().host())
                .as("no image").isTrue();
    }

    @Test
    void facts_sortTheEnvironmentNames_andCopyTheirLists() {
        List<Mount> mounts = new ArrayList<>(List.of(new Mount("/a", "/b", true)));
        CommandFacts facts = new CommandFacts("/w", null, null, mounts, false, new ArrayList<>(List.of("Z", "A")),
                true);

        mounts.clear();

        assertThat(facts.mounts()).hasSize(1);
        assertThat(facts.environmentNames()).containsExactly("A", "Z");
        assertThatThrownBy(() -> facts.environmentNames().add("X")).isInstanceOf(UnsupportedOperationException.class);
    }
}
