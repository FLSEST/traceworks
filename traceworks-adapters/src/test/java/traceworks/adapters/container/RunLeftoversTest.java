package traceworks.adapters.container;

import traceworks.core.port.CommandRunner;
import traceworks.core.port.CommandRunner.CommandResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the ledger does with a name the client lists that could pass for an option, and with a listing that fails.
 * The rest of its behaviour is stated through the port, in {@link CliContainerRuntimeTest}.
 */
class RunLeftoversTest {

    private static final List<String> PS = List.of("ps", "-a", "--filter",
            "label=traceworks.run=r1", "--format", "{{.Names}}");
    private static final List<String> NETWORKS = List.of("network", "ls", "--filter",
            "label=traceworks.run=r1", "--format", "{{.Name}}");

    private final List<List<String>> calls = new ArrayList<>();

    @Test
    void aContainerWhoseNameCouldPassForAnOption_isNotHandedToTheClient_andMayStillRun() {
        List<String> removal = List.of("rm", "-f", "traceworks-r1-1");
        RunLeftovers leftovers = leftovers(call -> !call.equals(PS) ? ""
                : calls.contains(removal) ? "-v\n" : "-v\ntraceworks-r1-1\n");

        assertThat(leftovers.removeLeftovers("r1")).isFalse();
        assertThat(calls).containsExactly(PS, removal, PS, NETWORKS);

        assertThat(leftovers.mayStillRun("r1")).isTrue();
        assertThat(calls).as("listed again, and no removal that would name it")
                .containsExactly(PS, removal, PS, NETWORKS, PS, NETWORKS);
    }

    @Test
    void aNetworkWhoseNameCouldPassForAnOption_isNotHandedToTheClient_andStaysALeftover() {
        RunLeftovers leftovers = leftovers(call -> call.equals(NETWORKS) ? "--all\n" : "");

        assertThat(leftovers.removeLeftovers("r1")).isFalse();
        assertThat(calls).containsExactly(PS, NETWORKS, NETWORKS);

        calls.clear();
        assertThat(leftovers.mayStillRun("r1")).isTrue();
        assertThat(calls).containsExactly(PS, NETWORKS);
    }

    /** A network that could pass for an option stays a leftover also beside one that was removed. */
    @Test
    void aNetworkThatCouldPassForAnOption_beside_aValidOne_staysALeftover() {
        RunLeftovers leftovers = leftovers(call -> call.equals(NETWORKS) ? "--all\ntraceworks-r1-net\n" : "");

        assertThat(leftovers.removeLeftovers("r1")).isFalse();
        assertThat(leftovers.mayStillRun("r1")).isTrue();
    }

    /** A container that could pass for an option may still run also beside one whose removal succeeded. */
    @Test
    void aContainerThatCouldPassForAnOption_beside_aValidOne_mayStillRun() {
        RunLeftovers leftovers = leftovers(call -> call.equals(PS) ? "-v\ntraceworks-r1-1\n" : "");

        assertThat(leftovers.removeLeftovers("r1")).isFalse();
        assertThat(leftovers.mayStillRun("r1")).isTrue();
    }

    /**
     * A cleanup that cannot list the run's containers knows nothing of them, even when its networks are listed and
     * none is there: it confirms nothing, and while the listing fails a container of the run may still run.
     */
    @Test
    void aRunWhoseContainersCannotBeListed_isNotConfirmed_andMayStillRun() {
        RunLeftovers leftovers = leftovers(call -> "", call -> call.equals(PS));

        assertThat(leftovers.removeLeftovers("r1")).isFalse();
        assertThat(leftovers.mayStillRun("r1")).isTrue();
    }

    /** The same for a cleanup that cannot list the run's networks. */
    @Test
    void aRunWhoseNetworksCannotBeListed_isNotConfirmed_andMayStillRun() {
        RunLeftovers leftovers = leftovers(call -> "", call -> call.equals(NETWORKS));

        assertThat(leftovers.removeLeftovers("r1")).isFalse();
        assertThat(leftovers.mayStillRun("r1")).isTrue();
    }

    /** A removal whose second listing fails is not confirmed: what was there may still be there. */
    @Test
    void aRemovalThatCannotBeListedAgain_isNotConfirmed() {
        List<String> container = List.of("rm", "-f", "traceworks-r1-1");
        List<String> network = List.of("network", "rm", "traceworks-r1-net");
        RunLeftovers containers = leftovers(call -> call.equals(PS) ? "traceworks-r1-1\n" : "",
                call -> call.equals(PS) && calls.contains(container));
        assertThat(containers.removeLeftovers("r1")).isFalse();

        calls.clear();
        RunLeftovers networks = leftovers(call -> call.equals(NETWORKS) ? "traceworks-r1-net\n" : "",
                call -> call.equals(NETWORKS) && calls.contains(network));
        assertThat(networks.removeLeftovers("r1")).isFalse();
    }

    /**
     * A container whose first removal failed and whose second, from {@link RunLeftovers#mayStillRun}, succeeded is
     * confirmed gone: the next question asks the client nothing.
     */
    @Test
    void aContainerRemovedOnceMore_isConfirmed_andTheNextQuestionAsksNothing() {
        List<String> removal = List.of("rm", "-f", "traceworks-r1-1");
        RunLeftovers leftovers = leftovers(
                call -> call.equals(PS) && java.util.Collections.frequency(calls, removal) < 2 ? "traceworks-r1-1\n" : "",
                call -> call.equals(removal) && !calls.contains(removal));

        assertThat(leftovers.removeLeftovers("r1")).isFalse();
        assertThat(leftovers.mayStillRun("r1")).isFalse();
        assertThat(calls).as("removed once more").containsSubsequence(removal, PS, NETWORKS, removal);

        int asked = calls.size();
        assertThat(leftovers.mayStillRun("r1")).isFalse();
        assertThat(calls.subList(asked, calls.size())).as("nothing is left to ask about").isEmpty();
    }

    /** The same for a network. */
    @Test
    void aNetworkRemovedOnceMore_isConfirmed_andTheNextQuestionAsksNothing() {
        List<String> removal = List.of("network", "rm", "traceworks-r1-net");
        RunLeftovers leftovers = leftovers(
                call -> call.equals(NETWORKS) && java.util.Collections.frequency(calls, removal) < 2
                        ? "traceworks-r1-net\n" : "",
                call -> call.equals(removal) && !calls.contains(removal));

        assertThat(leftovers.removeLeftovers("r1")).isFalse();
        assertThat(leftovers.mayStillRun("r1")).isFalse();
        assertThat(calls).as("removed once more").containsSubsequence(removal, PS, NETWORKS, removal);

        int asked = calls.size();
        assertThat(leftovers.mayStillRun("r1")).isFalse();
        assertThat(calls.subList(asked, calls.size())).as("nothing is left to ask about").isEmpty();
    }

    /** A ledger whose client succeeds with what {@code printed} gives for a call, and notes every call. */
    private RunLeftovers leftovers(Function<List<String>, String> printed) {
        return leftovers(printed, call -> false);
    }

    /** Like {@link #leftovers(Function)}, but a call that {@code fails} accepts ends with exit code 1. */
    private RunLeftovers leftovers(Function<List<String>, String> printed, Predicate<List<String>> fails) {
        return new RunLeftovers(arguments -> {
            List<String> call = List.of(arguments);
            boolean failed = fails.test(call);
            calls.add(call);
            return new CommandResult(failed ? 1 : 0, failed ? "" : printed.apply(call), failed ? "failed" : "", 0,
                    false, List.of(), CommandRunner.OUTPUT_LIMIT_BYTES);
        });
    }
}
