package traceworks.core.blocks;

import traceworks.core.port.CommandRunner.CommandResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** How the shell blocks read a {@link CommandResult}: one output text. */
class ShellResultTest {

    @Test
    void stdoutThenStderr_areJoinedAndTrimmed() {
        ShellResult result = ShellResult.of(new CommandResult(1, "out\n", "err\n", 7, false), 60, List.of());

        assertThat(result.output()).isEqualTo("out\nerr");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.durationMs()).isEqualTo(7);
    }

    @Test
    void stdoutWithoutTrailingNewline_getsOneBeforeStderr() {
        assertThat(ShellResult.of(new CommandResult(0, "out", "err", 1, false), 60, List.of()).output())
                .isEqualTo("out\nerr");
    }

    @Test
    void missingStreams_areEmpty() {
        assertThat(ShellResult.of(new CommandResult(0, null, null, 1, false), 60, List.of()).output()).isEmpty();
        assertThat(ShellResult.of(new CommandResult(0, "", "only err", 1, false), 60, List.of()).output())
                .isEqualTo("only err");
    }

    /** A timeout exits 124 whatever the runner reports, and the output says after how long. */
    @Test
    void timedOut_exitsWith124_andSaysSo() {
        ShellResult result = ShellResult.of(new CommandResult(137, "partial\n", "", 60_000, true), 60, List.of());

        assertThat(result.exitCode()).isEqualTo(ShellResult.TIMEOUT_EXIT_CODE).isEqualTo(124);
        assertThat(result.output()).isEqualTo("partial\n\nCommand timed out after 60 seconds");
        assertThat(result.durationMs()).isEqualTo(60_000);
    }

    /** A cut output says so after what was kept, and is never taken for a command that succeeded. */
    @Test
    void truncated_saysSoAfterWhatWasKept_andFails() {
        ShellResult result = ShellResult.of(new CommandResult(153, "y\ny\n", "", 900, false, List.of("stdout")), 60,
                List.of());
        ShellResult endedWithZero = ShellResult.of(new CommandResult(0, " y", "", 1, false, List.of("stderr")), 60,
                List.of());

        assertThat(result.exitCode()).isEqualTo(153);
        assertThat(result.truncated()).isTrue();
        assertThat(result.truncationNote()).isEqualTo("The command's standard output exceeded 16 MiB; Traceworks"
                + " stopped it and kept the first 16 MiB");
        assertThat(result.output()).isEqualTo("y\ny\n\n" + result.truncationNote());
        assertThat(endedWithZero.exitCode()).as("a runner that reports 0").isEqualTo(153);
        assertThat(endedWithZero.output()).as("kept as it came, not trimmed").isEqualTo(" y\nThe command's"
                + " standard error exceeded 16 MiB; Traceworks stopped it and kept the first 16 MiB");
    }

    @Test
    void truncatedAndTimedOut_saysBoth_andExitsWith124() {
        ShellResult result = ShellResult.of(new CommandResult(124, "y", "", 60_000, true, List.of("stdout",
                "stderr")), 60, List.of());

        assertThat(result.exitCode()).isEqualTo(124);
        assertThat(result.truncated()).isTrue();
        assertThat(result.output()).isEqualTo("y\nCommand timed out after 60 seconds\nThe command's standard output"
                + " and standard error exceeded 16 MiB each; Traceworks stopped it and kept the first 16 MiB of each");
    }

    @Test
    void wholeOutput_isNotTruncated() {
        ShellResult result = ShellResult.of(new CommandResult(0, "out", "", 1, false), 60, List.of());

        assertThat(result.truncated()).isFalse();
        assertThat(result.truncationNote()).isEmpty();
    }

    private static final String SECRET = "s3cr3t-d3ploy-t0ken";

    /**
     * A stream cut inside a secret value keeps none of its characters, so the record, which redacts only whole values,
     * holds no part of it: on stdout, on stderr, and with the other stream after it.
     */
    @Test
    void truncatedInsideASecret_keepsNoneOfIt() {
        String cut = "curl -H 'Authorization: Bearer " + SECRET.substring(0, 9);

        ShellResult onStdout = ShellResult.of(new CommandResult(153, cut, "warning\n", 900, false,
                List.of("stdout")), 60, List.of(SECRET));
        ShellResult onStderr = ShellResult.of(new CommandResult(153, "started\n", cut, 900, false,
                List.of("stderr")), 60, List.of(SECRET));

        assertThat(onStdout.output()).isEqualTo("curl -H 'Authorization: Bearer \nwarning\n\n"
                + onStdout.truncationNote());
        assertThat(onStderr.output()).isEqualTo("started\ncurl -H 'Authorization: Bearer \n"
                + onStderr.truncationNote());
        assertThat(onStdout.output() + onStderr.output()).doesNotContain(SECRET.substring(0, 3));
    }

    /**
     * The limit stopped the command, so the stream beside the cut one broke off too and ends before a value as well;
     * a stream far from any secret at its end stays as it came.
     */
    @Test
    void truncated_theStreamBesideItBrokeOffToo_andAStreamWithoutASecretAtItsEndIsKept() {
        ShellResult result = ShellResult.of(new CommandResult(153, "y\ny\n", "token " + SECRET.substring(0, 4),
                900, false, List.of("stdout")), 60, List.of(SECRET));
        ShellResult far = ShellResult.of(new CommandResult(153, "y\ny\n", "token " + SECRET.substring(0, 4)
                + " and more", 900, false, List.of("stdout")), 60, List.of(SECRET));

        assertThat(result.output()).isEqualTo("y\ny\ntoken \n" + result.truncationNote());
        assertThat(far.output()).isEqualTo("y\ny\ntoken " + SECRET.substring(0, 4) + " and more\n"
                + far.truncationNote());
    }

    /**
     * A command stopped for time, by a cancel (143, 130) or by a kill (137) broke off where it was stopped, and so did
     * one that failed in some other way: each stream ends before the first characters of a value it ends with, as one
     * cut at the limit does.
     */
    @Test
    void aStreamThatDidNotEndByItself_endsBeforeAPartOfASecret() {
        String broken = "config: token=" + SECRET.substring(0, 12);

        ShellResult timedOut = ShellResult.of(new CommandResult(143, broken, "", 60_000, true), 60, List.of(SECRET));
        ShellResult cancelled = ShellResult.of(new CommandResult(143, broken, "", 900, false), 60, List.of(SECRET));
        ShellResult killed = ShellResult.of(new CommandResult(137, "", broken, 900, false), 60, List.of(SECRET));
        ShellResult failed = ShellResult.of(new CommandResult(1, broken, broken, 900, false), 60, List.of(SECRET));

        assertThat(timedOut.output()).isEqualTo("config: token=\nCommand timed out after 60 seconds");
        assertThat(cancelled.output()).isEqualTo("config: token=");
        assertThat(killed.output()).isEqualTo("config: token=");
        assertThat(failed.output()).isEqualTo("config: token=\nconfig: token=");
    }

    /**
     * A command that exited 0 ended its streams itself: they are kept whole, also one that ends with what could be the
     * first characters of a value. An output that ends with a line feed never loses anything to the cut, since no
     * value holds one.
     */
    @Test
    void aStreamThatEndedByItself_isKeptWhole() {
        ShellResult exitedZero = ShellResult.of(new CommandResult(0, "prefix " + SECRET.substring(0, 6), "", 9,
                false), 60, List.of(SECRET));
        ShellResult endsWithALine = ShellResult.of(new CommandResult(1, "prefix " + SECRET.substring(0, 6) + "\n",
                "", 9, false), 60, List.of(SECRET));

        assertThat(exitedZero.output()).isEqualTo("prefix " + SECRET.substring(0, 6));
        assertThat(endsWithALine.output()).isEqualTo("prefix " + SECRET.substring(0, 6));
    }
}
