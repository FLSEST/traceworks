package traceworks.cli;

import traceworks.cli.Args.Parsed;
import traceworks.cli.Args.Spec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArgsTest {

    private final Spec run = Spec.of("run", "Runs a package.", "<package>")
            .repeated("input", "K=V", "an input")
            .option("workspace", "DIR", "the workspace")
            .flag("quiet", "no events");

    @Test
    void repeatedOption_collectsEveryValueInOrder() {
        Parsed parsed = Args.parse(run, List.of("pkg", "--input", "a=1", "--input", "b=2"));

        assertThat(parsed.options("input")).containsExactly("a=1", "b=2");
        assertThat(parsed.positional()).isEqualTo("pkg");
    }

    @Test
    void theValueOfAnOption_keepsEverythingAfterTheFirstEquals() {
        Parsed parsed = Args.parse(run, List.of("--input", "a=b=c", "pkg"));

        assertThat(parsed.option("input")).isEqualTo("a=b=c");
    }

    @Test
    void flagEqualsValueForm_isAccepted() {
        Parsed parsed = Args.parse(run, List.of("pkg", "--workspace=/tmp/w", "--input=x=y", "--home=/h"));

        assertThat(parsed.option("workspace")).isEqualTo("/tmp/w");
        assertThat(parsed.option("input")).isEqualTo("x=y");
        assertThat(parsed.option("home")).isEqualTo("/h");
    }

    @Test
    void aValueMayStartWithADash() {
        assertThat(Args.parse(run, List.of("pkg", "--input", "--odd")).option("input")).isEqualTo("--odd");
    }

    @Test
    void switches_areRecorded_andAnAbsentOneIsFalse() {
        assertThat(Args.parse(run, List.of("pkg", "--quiet")).has("quiet")).isTrue();
        assertThat(Args.parse(run, List.of("pkg")).has("quiet")).isFalse();
    }

    @Test
    void unknownFlag_isAUsageError() {
        assertThatThrownBy(() -> Args.parse(run, List.of("pkg", "--nope")))
                .isInstanceOf(UsageException.class).hasMessageContaining("--nope");
    }

    @Test
    void missingValue_isAUsageError() {
        assertThatThrownBy(() -> Args.parse(run, List.of("pkg", "--workspace")))
                .isInstanceOf(UsageException.class).hasMessageContaining("--workspace needs a value");
    }

    @Test
    void aSwitchWithAValue_isAUsageError() {
        assertThatThrownBy(() -> Args.parse(run, List.of("pkg", "--quiet=yes")))
                .isInstanceOf(UsageException.class).hasMessageContaining("takes no value");
    }

    @Test
    void aSingleOptionGivenTwice_isAUsageError() {
        assertThatThrownBy(() -> Args.parse(run, List.of("pkg", "--workspace", "a", "--workspace", "b")))
                .isInstanceOf(UsageException.class).hasMessageContaining("twice");
    }

    @Test
    void positionalCount_isCheckedPerCommand() {
        Spec runs = Spec.of("runs", "Lists runs.", null);

        assertThatThrownBy(() -> Args.parse(run, List.of())).isInstanceOf(UsageException.class)
                .hasMessageContaining("needs <package>");
        assertThatThrownBy(() -> Args.parse(run, List.of("a", "b"))).isInstanceOf(UsageException.class);
        assertThatThrownBy(() -> Args.parse(runs, List.of("a"))).isInstanceOf(UsageException.class)
                .hasMessageContaining("takes no argument");
        assertThat(Args.parse(runs, List.of()).positional()).isNull();
    }

    @Test
    void help_needsNoArguments() {
        assertThat(Args.parse(run, List.of("--help")).has("help")).isTrue();
    }

    @Test
    void doubleDash_endsTheFlags() {
        assertThat(Args.parse(run, List.of("--", "--odd-name")).positional()).isEqualTo("--odd-name");
    }

    @Test
    void usage_namesTheFlags() {
        assertThat(run.usage()).contains("traceworks run <package>", "--input K=V", "--quiet", "--home DIR");
    }

    @Test
    void usage_endsWithTheNotes_eachAfterAnEmptyLine() {
        Spec noted = Spec.of("x", "Does x.", null).note("first:\n  one").note("second");

        assertThat(noted.usage()).endsWith(String.format("show this text%n%nfirst:%n  one%n%nsecond%n"));
    }
}
