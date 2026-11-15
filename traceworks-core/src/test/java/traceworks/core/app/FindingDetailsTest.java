package traceworks.core.app;

import traceworks.core.register.Finding;
import traceworks.core.register.FindingStatus;
import traceworks.core.register.Severity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static traceworks.core.app.AppFixture.request;
import static org.assertj.core.api.Assertions.assertThat;

/** Where the detail of a finding is looked for, and the one line that says why there is none. */
class FindingDetailsTest {

    private final AppFixture fixture = new AppFixture();

    private static Finding lastSeenIn(String runId) {
        Finding.Seen seen = runId == null ? null : new Finding.Seen(runId, Instant.parse("2026-11-14T10:00:00Z"));
        return new Finding("id-1", "size", "A.java", Severity.MEDIUM, "Big A.java", null, "files 3", List.of(),
                FindingStatus.OPEN, seen, seen, 1, 0, false, null, null);
    }

    @Test
    void aFindingThatNamesNoRun_hasNoDetail_andSaysSo() {
        FindingDetails.Detail detail = FindingDetails.of(fixture.services(), lastSeenIn(null));

        assertThat(detail.runId()).isNull();
        assertThat(detail.missing()).isEqualTo("the register names no run that reported it");
        assertThat(detail.entries()).isEmpty();
    }

    @Test
    void aRunThatIsNotInThisHome_isNamed() {
        String unknown = "00000000-0000-0000-0000-00000000dead";

        FindingDetails.Detail detail = FindingDetails.of(fixture.services(), lastSeenIn(unknown));

        assertThat(detail.runId()).isEqualTo(unknown);
        assertThat(detail.missing()).isEqualTo("run " + unknown + " is not in this home");
    }

    @Test
    void aRunThatLeftNoFindingsFile_saysSo() {
        fixture.packages.put(java.nio.file.Path.of("/pkgs/plain"), """
                {"id": "wf-plain", "name": "Plain", "blocks": [
                  {"type": "shell", "name": "build", "config": {"command": "./build"}}
                ]}""");
        fixture.commands.on("./build", "built");
        String runId = fixture.services().start(request(java.nio.file.Path.of("/pkgs/plain")), null, null).runId();

        FindingDetails.Detail detail = FindingDetails.of(fixture.services(), lastSeenIn(runId));

        assertThat(detail.missing()).isEqualTo("run " + runId + " recorded no " + FindingDetails.FILE);
        assertThat(detail.entries()).isEmpty();
    }
}
