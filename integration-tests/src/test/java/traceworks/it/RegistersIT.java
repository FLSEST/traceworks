package traceworks.it;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A register across runs (docs/registers.md), with {@code examples/audit-hello}, an audit without any AI: the
 * findings of one run are compared with the register, the summary says what changed, {@code findings} reads and
 * verifies the register, a decision survives the next run, and the next run sees what the register knows.
 */
class RegistersIT extends IntegrationTest {

    @Test
    void anAuditFeedsItsRegister_acrossRuns_andADecisionReachesTheNextRun() throws IOException {
        file("a.txt", 12);
        file("b.txt", 3);

        Result first = traceworks.runExample("audit-hello");

        assertThat(first.code()).as(first.describe()).isZero();
        assertThat(first.out()).contains("register hello: 1 new, 0 resolved, 0 reopened, 1 open now");
        assertThat(traceworks.folder(first).output("known")).contains("(no known findings)");

        file("a.txt", 4);
        file("b.txt", 15);

        Result second = traceworks.runExample("audit-hello");

        assertThat(second.code()).as(second.describe()).isZero();
        assertThat(second.out()).contains("register hello: 1 new, 1 resolved, 0 reopened, 1 open now");
        assertThat(traceworks.folder(second).output("known"))
                .contains("OPEN | LOW | long-file | a.txt | a.txt has 12 lines, more than 10 | Evidence: long_file: a.txt (12 lines)");

        Result registers = traceworks.exec("findings");
        assertThat(registers.code()).as(registers.describe()).isZero();
        assertThat(registers.out()).contains("hello  example  OPEN 1  RESOLVED 1");
        Result list = traceworks.exec("findings", "hello", "list");
        assertThat(list.code()).as(list.describe()).isZero();
        assertThat(list.out()).contains("OPEN       LOW       long-file  b.txt  b.txt has 15 lines, more than 10")
                .doesNotContain("a.txt");
        Result all = traceworks.exec("findings", "hello", "list", "--all");
        assertThat(all.out()).contains("RESOLVED   LOW       long-file  a.txt  a.txt has 12 lines, more than 10");
        Result verify = traceworks.exec("findings", "hello", "verify");
        assertThat(verify.code()).as(verify.describe()).isZero();
        assertThat(traceworks.home().resolve("registers/hello/report.html")).isRegularFile();

        String id = list.out().lines().filter(line -> line.contains("b.txt")).findFirst().orElseThrow().substring(0, 8);
        Result accept = traceworks.exec("findings", "hello", "accept", id, "--note", "an example");
        assertThat(accept.code()).as(accept.describe()).isZero();
        assertThat(accept.out()).startsWith("accepted " + id + " as a risk, review by ");
        assertThat(traceworks.exec("findings", "hello", "list", "--status", "open").out()).doesNotContain("b.txt");
        assertThat(traceworks.exec("findings", "hello", "list").out())
                .contains("ACCEPTED_RISK  LOW       long-file  b.txt");

        Result third = traceworks.runExample("audit-hello");

        assertThat(third.code()).as(third.describe()).isZero();
        assertThat(third.out()).contains("register hello: 0 new, 0 resolved, 0 reopened, 0 open now");
        assertThat(traceworks.folder(third).output("known"))
                .contains("ACCEPTED_RISK | LOW | long-file | b.txt | b.txt has 15 lines, more than 10 | Evidence: "
                        + "long_file: b.txt (15 lines) | Disposition: an example");
        assertThat(traceworks.exec("findings", "hello", "verify").code()).isZero();
    }

    private void file(String name, int lines) {
        try {
            Files.writeString(traceworks.workspace().resolve(name), "line\n".repeat(lines), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
