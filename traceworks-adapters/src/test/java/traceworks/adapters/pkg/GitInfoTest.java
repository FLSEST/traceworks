package traceworks.adapters.pkg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitInfoTest {

    @TempDir Path temp;

    private void git(Path dir, String... args) throws Exception {
        String[] command = new String[args.length + 5];
        command[0] = "git";
        command[1] = "-C";
        command[2] = dir.toString();
        command[3] = "-c";
        command[4] = "user.name=t";
        System.arraycopy(args, 0, command, 5, args.length);
        ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
        builder.environment().put("GIT_AUTHOR_EMAIL", "t@t.invalid");
        builder.environment().put("GIT_COMMITTER_EMAIL", "t@t.invalid");
        builder.environment().put("GIT_COMMITTER_NAME", "t");
        assertEquals(0, builder.start().waitFor());
    }

    @Test
    void inheritedGitDir_doesNotMakeAnotherRepositoryTheGitState() throws Exception {
        Path other = Files.createDirectories(temp.resolve("other"));
        git(other, "init", "-q");
        Files.writeString(other.resolve("f"), "x");
        git(other, "add", "f");
        git(other, "commit", "-q", "-m", "x");
        Path pkg = Files.createDirectories(temp.resolve("pkg"));
        Map<String, String> env = Map.of("GIT_DIR", other.resolve(".git").toString());
        GitInfo info = GitInfo.of(pkg, "git", 10_000L, env);
        assertNull(info.commit());
        assertNull(info.dirty());
    }

    /**
     * {@code -c protocol.allow=never} is git's default policy only, and a {@code protocol.<name>.allow} of the
     * package's own config beats it. {@code GIT_ALLOW_PROTOCOL}, set and empty, is the allowlist git reads before any
     * config, so no protocol is left.
     */
    @Test
    void everyGitCall_getsAnEmptyProtocolAllowlist_whichNoConfigOfThePackageCanWiden() throws Exception {
        Path pkg = Files.createDirectories(temp.resolve("pkg"));
        Path seen = temp.resolve("environment");
        Path fake = temp.resolve("fake-git");
        Files.writeString(fake, "#!/bin/sh\nenv >> '" + seen + "'\nexit 1\n");
        assertEquals(true, fake.toFile().setExecutable(true));

        GitInfo.of(pkg, fake.toString(), 10_000L, Map.of());

        assertEquals(true, Files.readAllLines(seen).contains("GIT_ALLOW_PROTOCOL="));
    }

    /**
     * A committed package whose own {@code .git/config} adds {@code configSections} and whose
     * {@code .gitattributes} applies {@code attribute} to {@code workflow.json}, touched so that {@code git status}
     * compares its content and so would run the filter driver.
     */
    private Path packageWithFilters(String configSections, String attribute) throws Exception {
        Path pkg = Files.createDirectories(temp.resolve("pkg"));
        Files.writeString(pkg.resolve("workflow.json"), "{}\n");
        git(pkg, "init", "-q");
        git(pkg, "add", ".");
        git(pkg, "commit", "-q", "-m", "init");
        Files.writeString(pkg.resolve(".gitattributes"), "*.json " + attribute + "\n");
        Files.writeString(pkg.resolve(".git/config"), Files.readString(pkg.resolve(".git/config")) + configSections);
        Files.setLastModifiedTime(pkg.resolve("workflow.json"), java.nio.file.attribute.FileTime.from(
                java.time.Instant.now().plusSeconds(60)));
        return pkg;
    }

    /**
     * A filter driver whose name holds {@code =} cannot be emptied with {@code -c}: its names count as unknown, so
     * no {@code git status} runs it, and the git state is unknown.
     */
    @Test
    void aFilterDriverWhoseNameHoldsAnEqualsSign_isNeverRun_andTheGitStateIsUnknown() throws Exception {
        Path marker = temp.resolve("filter-ran");
        Path pkg = packageWithFilters("[filter \"a=b\"]\n\tclean = touch '" + marker + "'; cat\n\tsmudge = cat\n",
                "filter=a=b");

        GitInfo info = GitInfo.of(pkg, "git", 10_000L, Map.of());

        assertEquals(false, Files.exists(marker), "the package's filter driver ran");
        assertNull(info.commit());
        assertNull(info.dirty());
    }

    /**
     * A {@code filter.*} key that names no driver ({@code [filter]} without a name) leaves the names unknown, the
     * drivers beside it too: no {@code git status} runs them.
     */
    @Test
    void aFilterKeyWithoutADriverName_makesTheDriversUnknown_soNoneIsRun() throws Exception {
        Path marker = temp.resolve("filter-ran");
        Path pkg = packageWithFilters("[filter]\n\tclean = cat\n"
                + "[filter \"evil\"]\n\tclean = touch '" + marker + "'; cat\n\tsmudge = cat\n", "filter=evil");

        GitInfo info = GitInfo.of(pkg, "git", 10_000L, Map.of());

        assertEquals(false, Files.exists(marker), "the package's filter driver ran");
        assertNull(info.commit());
        assertNull(info.dirty());
    }

    /** A listing of the filter drivers that fails says nothing of them: no {@code git status} runs. */
    @Test
    void aListingOfTheFilterDriversThatFails_runsNoStatus_andTheGitStateIsUnknown() throws Exception {
        Path pkg = Files.createDirectories(temp.resolve("pkg"));
        Path status = temp.resolve("status-ran");
        Path fake = temp.resolve("fake-git");
        Files.writeString(fake, "#!/bin/sh\ncase \"$*\" in\n"
                + "  *rev-parse*) echo " + "c".repeat(40) + ";;\n"
                + "  *--get-regexp*) echo 'error: bad config' >&2; exit 3;;\n"
                + "  *status*) touch '" + status + "';;\n"
                + "esac\n");
        assertEquals(true, fake.toFile().setExecutable(true));

        GitInfo info = GitInfo.of(pkg, fake.toString(), 10_000L, Map.of());

        assertEquals(false, Files.exists(status), "git status ran");
        assertNull(info.commit());
        assertNull(info.dirty());
    }

    /**
     * A driver name is bytes to git, and it reaches {@code -c} as text: a name that is no valid UTF-8 (here the one
     * byte 0xFF) would come back as U+FFFD, so the {@code -c} would empty a driver of another name and
     * {@code git status} would run the package's own. Such a name leaves the drivers unknown: no status runs.
     */
    @Test
    void aFilterDriverWhoseNameIsNotValidText_isNeverRun_andTheGitStateIsUnknown() throws Exception {
        Path marker = temp.resolve("filter-ran");
        Path pkg = packageWithFilters("", "text");
        byte[] name = {(byte) 0xFF};
        java.io.ByteArrayOutputStream config = new java.io.ByteArrayOutputStream();
        config.write(Files.readAllBytes(pkg.resolve(".git/config")));
        config.write("[filter \"".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        config.write(name);
        config.write(("\"]\n\tclean = touch '" + marker + "'; cat\n\tsmudge = cat\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(pkg.resolve(".git/config"), config.toByteArray());
        java.io.ByteArrayOutputStream attributes = new java.io.ByteArrayOutputStream();
        attributes.write("*.json filter=".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        attributes.write(name);
        attributes.write('\n');
        Files.write(pkg.resolve(".gitattributes"), attributes.toByteArray());

        GitInfo info = GitInfo.of(pkg, "git", 10_000L, Map.of());

        assertEquals(false, Files.exists(marker), "the package's filter driver ran");
        assertNull(info.commit());
        assertNull(info.dirty());
    }

    /**
     * A name outside ASCII that is valid UTF-8 is refused as well: how the JVM encodes an argument depends on its
     * locale, so the name git gets back need not be the name it listed.
     */
    @Test
    void aFilterDriverWithAnAccentedName_leavesTheGitStateUnknown() throws Exception {
        Path marker = temp.resolve("filter-ran");
        Path pkg = packageWithFilters("[filter \"café\"]\n\tclean = touch '" + marker + "'; cat\n\tsmudge = cat\n",
                "filter=café");

        GitInfo info = GitInfo.of(pkg, "git", 10_000L, Map.of());

        assertEquals(false, Files.exists(marker), "the package's filter driver ran");
        assertNull(info.commit());
        assertNull(info.dirty());
    }
}
