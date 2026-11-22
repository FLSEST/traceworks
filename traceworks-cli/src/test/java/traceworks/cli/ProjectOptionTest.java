package traceworks.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import traceworks.adapters.config.Projects;
import traceworks.cli.Args.Parsed;
import traceworks.cli.Args.Spec;
import traceworks.core.app.RunRefusedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The project of a run that has no folder: one that works in a checkout of a source that is no path of this
 * machine. Only the option names its project then. The command line cannot show it without fetching from somewhere,
 * so it is shown here.
 */
class ProjectOptionTest {

    private static final Spec SPEC = Spec.of("run", "Runs a package.", "<package>").positionals(1, 1)
            .option(ProjectOption.NAME, ProjectOption.VALUE, "the project");

    @TempDir
    Path temp;

    private Projects projects() throws IOException {
        Path home = Files.createDirectories(temp.resolve("home"));
        Files.writeString(home.resolve("traceworks.json"), "{\"projects\": {\"shop\": \"" + temp.resolve("shop")
                + "\"}}");
        return Projects.read(home);
    }

    private static Parsed args(String... more) {
        List<String> all = new java.util.ArrayList<>(List.of("pkg"));
        all.addAll(List.of(more));
        return Args.parse(SPEC, all);
    }

    @Test
    void aRunWithoutAFolder_hasNoProject_withoutTheOption() throws IOException {
        assertThat(ProjectOption.ofRun(args(), projects(), null)).isNull();
    }

    @Test
    void aRunWithoutAFolder_isOfTheProjectTheOptionNames_whenNobodyDeclaredIt() throws IOException {
        assertThat(ProjectOption.ofRun(args("--project", "elsewhere"), projects(), null)).isEqualTo("elsewhere");
    }

    @Test
    void aRunWithoutAFolder_isRefused_withAKeyThatIsBoundToAFolder() throws IOException {
        Projects projects = projects();

        assertThatThrownBy(() -> ProjectOption.ofRun(args("--project", "shop"), projects, null))
                .isInstanceOf(RunRefusedException.class)
                .hasMessageContaining("a checkout of a URL (--source), which is no folder of this machine");
    }
}
