package traceworks.it;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The places of the runs, the ledger and the registers as {@code traceworks.json} sets them ({@code runs_dir},
 * {@code ledger_dir}, {@code registers_dir}; docs/cli.md, "Places of the runs, the ledger and the registers"), with
 * real containers: a run works with its scratch directory in a runs folder outside the home, and no container gets
 * to see a place, neither through a step's mount nor as the workspace.
 */
class DataDirsIT extends IntegrationTest {

    private Path runs;
    private Path ledger;
    private Path registers;

    private void placesOutsideTheHome() throws IOException {
        Path disk = Files.createDirectories(sandbox.resolve("disk"));
        runs = disk.resolve("runs");
        ledger = disk.resolve("ledger");
        registers = disk.resolve("registers");
        traceworks.configure(Map.of("runs_dir", runs.toString(), "ledger_dir", ledger.toString(), "registers_dir",
                registers.toString()));
    }

    /** The folder {@code <runs>/<day>/<runId>} of the configured runs folder. */
    private RunFolder folder(String runId) throws IOException {
        try (Stream<Path> days = Files.list(runs)) {
            return days.map(day -> day.resolve(runId)).filter(Files::isDirectory).findFirst().map(RunFolder::new)
                    .orElseThrow(() -> new AssertionError("No run folder of run " + runId + " in " + runs));
        }
    }

    @Test
    void aContainerRun_keepsItsRecordAndItsLedgerEntry_inThePlacesTheSettingsName() throws Exception {
        placesOutsideTheHome();
        Path readOnly = Files.createDirectories(sandbox.resolve("ro-data"));
        Files.writeString(readOnly.resolve("given.txt"), "given-content\n");
        Path writable = Files.createDirectories(sandbox.resolve("rw-data"));

        Result run = traceworks.run("mounts", "--input", "ro_dir=" + readOnly, "--input", "rw_dir=" + writable);

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = folder(run.runId());
        assertThat(folder.output("read_ro")).isEqualTo("given-content");
        assertThat(folder.output("write_rw")).isEqualTo("from-the-container");
        assertThat(Files.readAllBytes(ledger.resolve("runs").resolve(run.runId() + ".json")))
                .isEqualTo(Files.readAllBytes(folder.dir().resolve("manifest.json")));
        assertThat(ledger.resolve("ledger.lock")).as("the lock of a placed ledger lies in it").isRegularFile();
        for (String fixed : new String[] {"runs", "ledger", "ledger.lock", "registers"}) {
            assertThat(traceworks.home().resolve(fixed)).as(fixed).doesNotExist();
        }
        Result verify = traceworks.exec("verify", run.runId());
        assertThat(verify.code()).as(verify.describe()).isZero();
        assertThat(verify.out()).containsPattern("(?m)^ledger +ok$");
        Result show = traceworks.exec("show", run.runId());
        assertThat(show.code()).as(show.describe()).isZero();
    }

    @Test
    void aStepsMountOfAConfiguredPlace_isRefused_andStartsNothing() throws Exception {
        placesOutsideTheHome();
        Path writable = Files.createDirectories(sandbox.resolve("rw-data"));
        Files.createDirectories(ledger);

        for (Path place : new Path[] {runs, ledger, ledger.getParent()}) {
            Result run = traceworks.run("mounts", "--input", "ro_dir=" + place, "--input", "rw_dir=" + writable);

            assertThat(run.code()).as(run.describe()).isEqualTo(3);
            RunFolder folder = folder(run.runId());
            assertThat(folder.output("read_ro")).as(place.toString()).contains(":/data-ro:ro is refused",
                    "what runs leave behind");
            assertThat(folder.ofStep("step.command.finished", folder.stepIndex("read_ro")).getFirst()
                    .path("exit_code").asInt()).as("nothing was started").isEqualTo(125);
        }
    }

    @Test
    void aWorkspaceThatIsOrHoldsAConfiguredPlace_isRefusedBeforeTheRunIsCreated() throws Exception {
        placesOutsideTheHome();
        Files.createDirectories(runs);
        Path readOnly = Files.createDirectories(sandbox.resolve("ro-data"));

        for (Path workspace : new Path[] {runs, runs.getParent()}) {
            Result run = traceworks.exec("run", traceworks.packageDir("mounts").toString(), "--no-input", "--input",
                    "ro_dir=" + readOnly, "--input", "rw_dir=" + readOnly, "--workspace", workspace.toString());

            assertThat(run.code()).as(run.describe()).isEqualTo(6);
            assertThat(run.err()).contains("cannot be mounted", "what runs leave behind");
        }
        assertThat(runs).isEmptyDirectory();
    }
}
