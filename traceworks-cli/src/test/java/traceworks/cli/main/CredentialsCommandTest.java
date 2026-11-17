package traceworks.cli.main;

import traceworks.cli.RuntimeSource;
import traceworks.cli.SecretReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code traceworks credentials} end to end: the real file in a temporary home, the value from a piped input. */
class CredentialsCommandTest {

    private static final String VALUE = "not-a-real-credential-1";

    @TempDir
    Path temp;
    private Path home;
    private Path file;
    /** The prompts the command asked with. */
    private final List<String> prompts = new ArrayList<>();

    private record Result(int code, String out, String err) {
    }

    @BeforeEach
    void home() {
        home = temp.resolve("home");   // not created: the command makes it
        file = home.resolve("credentials.json");
    }

    /** Runs the command with {@code input} as the piped standard input; {@code null} gives it no input at all. */
    private Result exec(String input, String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--home");
        all.add(home.toString());
        SecretReader reader = input == null ? null : prompt -> {
            prompts.add(prompt);
            return SecretReader.piped(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))).read(prompt);
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(all.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Map.of(), temp, null, RuntimeSource.NONE, reader);
        return new Result(code, traceworks.cli.Unbroken.of(out.toString(StandardCharsets.UTF_8)),
                traceworks.cli.Unbroken.of(err.toString(StandardCharsets.UTF_8)));
    }

    private static String permissions(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }

    @Test
    void set_createsTheHomeAndTheFileForTheirOwnerAlone_andNeverPrintsTheValue() throws IOException {
        Result r = exec(VALUE + "\n", "credentials", "set", "CLAUDE_CODE_OAUTH_TOKEN");

        assertThat(r.code()).as(r.err()).isZero();
        assertThat(r.out()).isEqualTo("stored CLAUDE_CODE_OAUTH_TOKEN in " + file + System.lineSeparator());
        assertThat(r.out() + r.err()).doesNotContain(VALUE);
        assertThat(prompts).containsExactly("Value of CLAUDE_CODE_OAUTH_TOKEN (not shown): ");
        assertThat(permissions(home)).isEqualTo("rwx------");
        assertThat(permissions(file)).isEqualTo("rw-------");
        assertThat(Files.readString(file)).contains("\"CLAUDE_CODE_OAUTH_TOKEN\"", "\"" + VALUE + "\"");
    }

    @Test
    void set_replacesAValue_andKeepsTheOtherNames() throws IOException {
        exec(VALUE, "credentials", "set", "DEPLOY_TOKEN");
        exec("first-value-12", "credentials", "set", "OTHER_NAME");

        Result r = exec("second-value-12", "credentials", "set", "OTHER_NAME");

        assertThat(r.code()).as(r.err()).isZero();
        assertThat(r.out()).startsWith("replaced OTHER_NAME in ");
        assertThat(Files.readString(file)).contains(VALUE, "second-value-12").doesNotContain("first-value-12");
    }

    @Test
    void set_aValueNoRunWouldTake_isRefused_withoutShowingIt() {
        Result r = exec("tiny#1", "credentials", "set", "DEPLOY_TOKEN");

        assertThat(r.code()).isEqualTo(6);
        assertThat(r.err()).contains("the value given for DEPLOY_TOKEN", "shorter than 8 characters").doesNotContain("tiny#1");
        assertThat(file).doesNotExist();
    }

    @Test
    void set_withoutAValue_storesNothing() {
        Result r = exec("\n", "credentials", "set", "DEPLOY_TOKEN");

        assertThat(r.code()).isEqualTo(6);
        assertThat(r.err()).contains("no value given for DEPLOY_TOKEN; nothing was stored");
        assertThat(file).doesNotExist();
    }

    @Test
    void set_withoutAnInput_isAUsageError() {
        Result r = exec(null, "credentials", "set", "DEPLOY_TOKEN");

        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("no input to read the value from");
        assertThat(home).doesNotExist();
    }

    /** A value given as an argument is refused without being read as one, and without being repeated. */
    @Test
    void set_aValueAsAnArgument_isAUsageError() {
        Result three = exec(VALUE, "credentials", "set", "DEPLOY_TOKEN", "pasted-secret-value");
        Result lower = exec(VALUE, "credentials", "set", "deploy_token");

        assertThat(three.code()).isEqualTo(2);
        assertThat(three.err()).doesNotContain("pasted-secret-value");
        assertThat(lower.code()).isEqualTo(2);
        assertThat(lower.err()).contains("is not a credential name", "A value is never an argument");
        assertThat(prompts).isEmpty();
        assertThat(home).doesNotExist();
    }

    @Test
    void set_aFileOthersCanRead_isRefusedBeforeTheValueIsAsked() throws IOException {
        Files.createDirectories(home);
        Files.writeString(file, "{}");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        Result r = exec(VALUE, "credentials", "set", "DEPLOY_TOKEN");

        assertThat(r.code()).isEqualTo(6);
        assertThat(r.err()).contains(file.toString(), "chmod 600");
        assertThat(prompts).isEmpty();
        assertThat(Files.readString(file)).isEqualTo("{}");
    }

    @Test
    void list_printsTheNames_neverAValue() {
        exec(VALUE, "credentials", "set", "ZED_TOKEN");
        exec(VALUE, "credentials", "set", "ALPHA_KEY");

        Result r = exec(null, "credentials", "list");

        assertThat(r.code()).isZero();
        assertThat(r.out().lines()).containsExactly("ALPHA_KEY", "ZED_TOKEN");
    }

    @Test
    void list_withoutAFile_saysWhereItWouldBe_andCreatesNothing() {
        Result r = exec(null, "credentials", "list");

        assertThat(r.code()).isZero();
        assertThat(r.out()).isEqualTo("no credentials in " + file + System.lineSeparator());
        assertThat(home).doesNotExist();
    }

    @Test
    void remove_takesTheNameOut_andAnAbsentNameIsRefused() throws IOException {
        exec(VALUE, "credentials", "set", "DEPLOY_TOKEN");
        exec(VALUE, "credentials", "set", "OTHER_NAME");

        Result removed = exec(null, "credentials", "remove", "DEPLOY_TOKEN");
        Result again = exec(null, "credentials", "remove", "DEPLOY_TOKEN");

        assertThat(removed.code()).as(removed.err()).isZero();
        assertThat(removed.out()).isEqualTo("removed DEPLOY_TOKEN from " + file + System.lineSeparator());
        assertThat(again.code()).isEqualTo(6);
        assertThat(again.err()).contains("no DEPLOY_TOKEN in " + file);
        assertThat(Files.readString(file)).contains("OTHER_NAME").doesNotContain("DEPLOY_TOKEN");
        assertThat(permissions(file)).isEqualTo("rw-------");
    }

    @Test
    void usageErrors_exit2() {
        assertThat(exec(null, "credentials").code()).isEqualTo(2);
        assertThat(exec(null, "credentials", "set").err()).contains("needs a name");
        assertThat(exec(null, "credentials", "list", "X").err()).contains("takes no name");
        Result unknown = exec(null, "credentials", "show", "X");
        assertThat(unknown.code()).isEqualTo(2);
        assertThat(unknown.err()).contains("unknown credentials command 'show'");
    }

    @Test
    void help_saysThatAValueIsNeverAnArgument() {
        Result r = exec(null, "credentials", "--help");

        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("traceworks credentials set <NAME> | list | remove <NAME>",
                "It is never an argument", "prints the stored names, never a value");
        assertThat(r.out().lines()).allSatisfy(line -> assertThat(line.length()).as(line).isLessThanOrEqualTo(120));
    }

    /** The path of the file comes from {@code --home} or the environment: a control character in it is not printed. */
    @Test
    void everyLineThatNamesTheFile_printsItsPathWithoutControlCharacters() {
        home = temp.resolve("ho\u001b[2Kme");
        file = home.resolve("credentials.json");

        Result empty = exec(null, "credentials", "list");
        Result stored = exec(VALUE, "credentials", "set", "DEPLOY_TOKEN");
        Result missing = exec(null, "credentials", "remove", "OTHER_NAME");
        Result removed = exec(null, "credentials", "remove", "DEPLOY_TOKEN");

        assertThat(empty.out()).contains("no credentials in ");
        assertThat(stored.out()).contains("stored DEPLOY_TOKEN in ");
        assertThat(missing.err()).contains("no OTHER_NAME in ");
        assertThat(removed.out()).contains("removed DEPLOY_TOKEN from ");
        for (Result r : List.of(empty, stored, missing, removed)) {
            assertThat(r.out() + r.err()).doesNotContain("\u001b").contains("ho?[2Kme");
        }
    }
}
