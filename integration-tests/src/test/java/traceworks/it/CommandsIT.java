package traceworks.it;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The commands no other integration test runs, through the jar as a user runs them (docs/cli.md): {@code describe},
 * {@code credentials} and {@code watch}.
 */
class CommandsIT extends IntegrationTest {

    @Test
    void describe_printsTheOutlineOfAPackage_andWritesItAsOnePage() throws Exception {
        Path pkg = traceworks.examplePackage("hello");

        Result text = traceworks.exec("describe", pkg.toString());
        Result html = traceworks.exec("describe", pkg.toString(), "--html", "outline.html");

        assertThat(text.code()).as(text.describe()).isZero();
        assertThat(text.out()).contains("Workflow  Hello", "greet", "publish", "approval")
                .contains("4 blocks: 0 AI steps").contains("Nothing was checked");
        assertThat(html.code()).as(html.describe()).isZero();
        Path page = traceworks.workspace().resolve("outline.html");
        assertThat(html.out()).contains(page.toString());
        assertThat(Files.readString(page)).startsWith("<!DOCTYPE html>").contains("<title>Workflow Hello</title>")
                .doesNotContain("<script");
        // Nothing ran: describing a package starts no run
        assertThat(traceworks.exec("runs").out()).doesNotContain("Hello");
    }

    @Test
    void credentials_storesAValueFromAPipe_listsNamesOnly_andRemovesIt() throws Exception {
        String value = "made-up-value-for-the-it";
        Path file = traceworks.home().resolve("credentials.json");

        Result empty = traceworks.exec("credentials", "list");
        Result stored = traceworks.execWithInput(value + "\n", "credentials", "set", "DEPLOY_PLACE");
        Result listed = traceworks.exec("credentials", "list");
        Result tooShort = traceworks.execWithInput("short\n", "credentials", "set", "OTHER_PLACE");
        Result removed = traceworks.exec("credentials", "remove", "DEPLOY_PLACE");
        Result removedAgain = traceworks.exec("credentials", "remove", "DEPLOY_PLACE");

        assertThat(empty.code()).as(empty.describe()).isZero();
        assertThat(empty.out()).contains("no credentials in " + file);
        assertThat(stored.code()).as(stored.describe()).isZero();
        assertThat(stored.out()).contains("stored DEPLOY_PLACE in " + file).doesNotContain(value);
        assertThat(listed.out().strip()).isEqualTo("DEPLOY_PLACE");
        assertThat(tooShort.code()).as(tooShort.describe()).isEqualTo(6);
        assertThat(tooShort.out() + tooShort.err()).doesNotContain("short\n");
        assertThat(removed.code()).as(removed.describe()).isZero();
        assertThat(removedAgain.code()).as(removedAgain.describe()).isEqualTo(6);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        assertThat(Files.readString(file)).doesNotContain(value);
    }

    @Test
    void watch_printsTheEventsOfARun_fromItsFirstEvent_untilItParks() {
        Result run = traceworks.runExample("hello");
        assertThat(run.code()).as(run.describe()).isEqualTo(5);

        Result watch = traceworks.exec("watch", run.runId());

        // The exit code of the run it watched: 5, parked at the approval
        assertThat(watch.code()).as(watch.describe()).isEqualTo(5);
        assertThat(watch.out()).contains("run started", "greet", "count", "publish");
        assertThat(watch.out().indexOf("greet")).isLessThan(watch.out().indexOf("publish"));
    }

    /**
     * The working directory is the workspace, and a step with a writable workspace can write there. A folder it
     * leaves under its run's id is not the run: an id is looked up in the home, and only a path opens a folder.
     */
    @Test
    void aFolderInTheWorkspaceNamedLikeARun_isNotTakenForTheRun() throws Exception {
        Result run = traceworks.runExample("hello");
        assertThat(run.code()).as(run.describe()).isEqualTo(5);
        String id = run.runId();
        Path forged = Files.createDirectories(traceworks.workspace().resolve(id));
        Files.writeString(forged.resolve("events.jsonl"), "");
        Path other = Files.createDirectories(traceworks.workspace().resolve("copied-run"));
        Files.writeString(other.resolve("events.jsonl"), "");

        Result byId = traceworks.exec("show", id);
        Result byName = traceworks.exec("show", "copied-run");

        assertThat(byId.code()).as(byId.describe()).isZero();
        assertThat(byId.out()).contains("Workflow  Hello").contains("greet");
        assertThat(byName.code()).as(byName.describe()).isNotZero();
        assertThat(byName.err()).contains("No run matches 'copied-run'", "write it as a path: ./copied-run");
    }
}
