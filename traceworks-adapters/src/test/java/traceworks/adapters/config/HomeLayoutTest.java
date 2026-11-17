package traceworks.adapters.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HomeLayoutTest {

    @TempDir
    Path temp;

    private Path home() throws IOException {
        return Files.createDirectories(temp.resolve("home"));
    }

    private void config(String json) throws IOException {
        Files.writeString(home().resolve("traceworks.json"), json);
    }

    private void refused(String json, String... parts) throws IOException {
        config(json);
        Path home = home();
        assertThatThrownBy(() -> HomeLayout.read(home)).as(json).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContainingAll(parts).hasMessageContaining(home.resolve("traceworks.json").toString());
    }

    @Test
    void withoutTheSettings_everyPlaceIsTheFixedFolderOfTheHome() throws IOException {
        Path home = home();
        for (String json : new String[] {null, "{}", "{\"actor\": \"operator\"}",
                "{\"runs_dir\": null, \"ledger_dir\": null, \"registers_dir\": null}"}) {
            if (json != null) config(json);

            HomeLayout layout = HomeLayout.read(home);

            assertThat(layout).as(String.valueOf(json)).isEqualTo(HomeLayout.of(home));
            assertThat(layout.home()).isEqualTo(home);
            assertThat(layout.runs()).isEqualTo(home.resolve("runs"));
            assertThat(layout.ledger()).isEqualTo(home.resolve("ledger"));
            assertThat(layout.ledgerLock()).isEqualTo(home.resolve("ledger.lock"));
            assertThat(layout.registers()).isEqualTo(home.resolve("registers"));
            assertThat(layout.workspaces()).isEqualTo(home.resolve("workspaces"));
            assertThat(layout.toolCache()).isEqualTo(home.resolve("cache").resolve("tools"));
            assertThat(layout.seals()).isEqualTo(home.resolve("seals"));
            assertThat(layout.allowedSigners()).isEqualTo(home.resolve("allowed_signers"));
        }
    }

    @Test
    void readingTheLayout_createsNothing() throws IOException {
        config("{\"runs_dir\": \"" + temp.resolve("disk/runs") + "\"}");

        HomeLayout.read(home());

        assertThat(temp.resolve("disk")).doesNotExist();
        assertThat(home().resolve("ledger")).doesNotExist();
    }

    @Test
    void anAbsolutePath_isTakenAsItIs_andARelativeOneCountsFromTheHome() throws IOException {
        Path disk = temp.resolve("disk");
        config("{\"runs_dir\": \"" + disk.resolve("runs") + "\", \"ledger_dir\": \"audit/ledger\","
                + " \"registers_dir\": \"../beside/./registers\"}");

        HomeLayout layout = HomeLayout.read(home());

        assertThat(layout.runs()).isEqualTo(disk.resolve("runs"));
        assertThat(layout.ledger()).isEqualTo(home().resolve("audit/ledger"));
        assertThat(layout.registers()).isEqualTo(temp.resolve("beside/registers"));
        assertThat(layout.workspaces()).isEqualTo(home().resolve("workspaces"));
    }

    /**
     * The fixed ledger keeps its lock beside it, as ever. A ledger the operator placed keeps it inside: its parent
     * may be a mount point, or a folder Traceworks cannot write.
     */
    @Test
    void theLockOfAPlacedLedger_liesInTheLedger_andTheOneOfTheFixedLedgerBesideIt() throws IOException {
        config("{\"ledger_dir\": \"" + temp.resolve("disk/the-ledger") + "\"}");
        assertThat(HomeLayout.read(home()).ledgerLock()).isEqualTo(temp.resolve("disk/the-ledger/ledger.lock"));

        config("{\"ledger_dir\": \"ledger\"}");
        assertThat(HomeLayout.read(home()).ledgerLock()).isEqualTo(home().resolve("ledger.lock"));
        config("{\"ledger_dir\": \"" + home().resolve("ledger") + "\"}");
        assertThat(HomeLayout.read(home())).isEqualTo(HomeLayout.of(home()));
    }

    @Test
    void runsFolders_areTheOneOfNow_andTheFixedOne() throws IOException {
        config("{\"runs_dir\": \"" + temp.resolve("disk/runs") + "\"}");

        assertThat(HomeLayout.read(home()).runsFolders()).containsExactly(temp.resolve("disk/runs"),
                home().resolve("runs"));
        assertThat(HomeLayout.of(home()).runsFolders()).containsExactly(home().resolve("runs"));
    }

    @Test
    void whiteSpaceAroundAValue_isRefused_notMadeAFolderOfTheHome() throws IOException {
        for (String bad : List.of(" /data/runs", "/data/runs ", "\\t/data/runs", "runs2\\n")) {
            refused("{\"runs_dir\": \"" + bad + "\"}", "'runs_dir'", "white space");
        }
    }

    /** The runs folder holds the scratch directories, which every container mounts; a mount cannot carry these. */
    @Test
    void aRunsFolderWithACommaOrAQuote_isRefused_withTheReason() throws IOException {
        refused("{\"runs_dir\": \"" + temp.resolve("a,b/runs") + "\"}", "'runs_dir'", "comma", "mount");
        refused("{\"runs_dir\": \"" + temp.resolve("a\\\"b") + "\"}", "'runs_dir'", "quote", "mount");
        config("{\"ledger_dir\": \"" + temp.resolve("a,b/ledger") + "\"}");
        assertThat(HomeLayout.read(home()).ledger()).as("no container mounts the ledger")
                .isEqualTo(temp.resolve("a,b/ledger"));
    }

    @Test
    void aPlaceNamedLikeAFileOfTheHome_isRefused() throws IOException {
        for (String file : List.of("credentials.json", "traceworks.json", "ledger.lock", "ledger.lock/runs")) {
            refused("{\"runs_dir\": \"" + file + "\"}", "'runs_dir'", home().resolve(file.split("/")[0]).toString());
        }
        refused("{\"registers_dir\": \"" + temp.resolve("disk/ledger/ledger.lock") + "\", \"ledger_dir\": \""
                + temp.resolve("disk/ledger") + "\"}", "'registers_dir'", "'ledger_dir'");
    }

    @Test
    void aPlaceInsideItsOwnFixedFolder_isRefused() throws IOException {
        refused("{\"runs_dir\": \"runs/sub\"}", "'runs_dir'", home().resolve("runs").toString());
        refused("{\"ledger_dir\": \"ledger/x/y\"}", "'ledger_dir'", home().resolve("ledger").toString());
        refused("{\"registers_dir\": \"registers/team\"}", "'registers_dir'");
    }

    /**
     * A link without a target is a place that whoever creates the target chooses: a step with a writable workspace
     * could, and the register that is written after the run would land in the workspace.
     */
    @Test
    void aPlaceThatIsALinkWithoutATarget_orLiesBehindOne_isRefused() throws IOException {
        Path link = Files.createSymbolicLink(temp.resolve("dangling"), temp.resolve("work/not-there"));
        refused("{\"registers_dir\": \"" + link + "\"}", "'registers_dir'", link.toString(), "link");
        refused("{\"runs_dir\": \"" + link.resolve("runs") + "\"}", "'runs_dir'", link.toString(), "link");

        Path target = Files.createDirectories(temp.resolve("real-disk"));
        Path good = Files.createSymbolicLink(temp.resolve("good-link"), target);
        config("{\"registers_dir\": \"" + good + "\"}");
        assertThat(HomeLayout.read(home()).registers()).as("a link to a folder is a folder").isEqualTo(good);
    }

    @Test
    void records_areThePlacesNoStepMaySee_theOldFoldersOfTheHomeToo() throws IOException {
        Path disk = temp.resolve("disk");
        config("{\"runs_dir\": \"" + disk.resolve("runs") + "\"}");

        assertThat(HomeLayout.read(home()).records()).containsExactlyInAnyOrder(disk.resolve("runs"),
                home().resolve("runs"), home().resolve("ledger"), home().resolve("registers"),
                home().resolve("cache"), home().resolve("seals"));
        assertThat(HomeLayout.of(home()).records()).containsExactlyInAnyOrder(home().resolve("runs"),
                home().resolve("ledger"), home().resolve("registers"), home().resolve("cache"),
                home().resolve("seals"));
    }

    @Test
    void aValueThatIsNoString_orIsBlank_isRefused_namingTheKey() throws IOException {
        for (String key : HomeLayout.KEYS) {
            for (String bad : List.of("5", "true", "[\"/x\"]", "{}", "\"\"", "\"  \"")) {
                refused("{\"" + key + "\": " + bad + "}", "'" + key + "'");
            }
        }
    }

    @Test
    void aTilde_isNotExpanded_butRefused() throws IOException {
        refused("{\"runs_dir\": \"~/traceworks-runs\"}", "'runs_dir'", "~", "absolute");
        refused("{\"ledger_dir\": \"~\"}", "'ledger_dir'", "~");
    }

    // --- writable_mounts: the folders a package may mount writable ---

    private Path userHome() throws IOException {
        return Files.createDirectories(temp.resolve("user"));
    }

    private void rootRefused(String entry, String... parts) throws IOException {
        config("{\"writable_mounts\": [" + entry + "]}");
        Path home = home();
        Path userHome = userHome();
        assertThatThrownBy(() -> HomeLayout.read(home, userHome)).as(entry)
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'writable_mounts'")
                .hasMessageContainingAll(parts).hasMessageContaining(home.resolve("traceworks.json").toString());
    }

    @Test
    void withoutWritableMounts_noFolderIsListed() throws IOException {
        assertThat(HomeLayout.of(home()).writableMounts()).isEmpty();
        config("{\"writable_mounts\": null}");
        assertThat(HomeLayout.read(home(), userHome()).writableMounts()).isEmpty();
        config("{\"writable_mounts\": []}");
        assertThat(HomeLayout.read(home(), userHome()).writableMounts()).isEmpty();
    }

    @Test
    void writableMounts_areAbsolute_orStartAtTheOperatorsHome() throws IOException {
        Path data = temp.resolve("disk/data");
        config("{\"writable_mounts\": [\"" + data + "\", \"~/out\", \"" + temp.resolve("a/../b") + "\"]}");

        HomeLayout layout = HomeLayout.read(home(), userHome());

        assertThat(layout.writableMounts()).containsExactly(data, userHome().resolve("out"), temp.resolve("b"));
        assertThat(Files.exists(data)).as("reading the layout creates nothing").isFalse();
        assertThat(layout.withSealFiles(temp.resolve("key"), null).writableMounts()).as("kept with the seal files")
                .isEqualTo(layout.writableMounts());
    }

    @Test
    void aWritableMountsRoot_thatIsTheRootOfTheFileSystem_theHomeOrAFolderHoldingIt_isRefused() throws IOException {
        Path userHome = userHome();
        rootRefused("\"/\"", "\"/\"", "every");
        rootRefused("\"/usr/..\"", "every");
        rootRefused("\"~\"", "home directory");
        rootRefused("\"~/\"", "home directory");
        rootRefused("\"~/docs/..\"", "home directory");
        rootRefused("\"" + userHome + "\"", "home directory");
        rootRefused("\"" + userHome.getParent() + "\"", "home directory");
        Path link = Files.createSymbolicLink(temp.resolve("link-to-user"), userHome);
        rootRefused("\"" + link + "\"", "home directory");
    }

    @Test
    void aWritableMountsRoot_thatIsNotAbsolute_orNoString_isRefused() throws IOException {
        rootRefused("\"data/out\"", "data/out", "absolute", "~/");
        rootRefused("\"~other/out\"", "~other/out", "absolute");
        rootRefused("\"\"", "absolute");
        rootRefused("\" /data\"", "white space");
        rootRefused("7", "a list of directories");
        config("{\"writable_mounts\": \"/data\"}");
        Path home = home();
        assertThatThrownBy(() -> HomeLayout.read(home, userHome())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'writable_mounts'").hasMessageContaining("a list of directories");
    }

    // --- workspace_git_writable: whether a step may write the .git of a writable workspace ---

    @Test
    void withoutWorkspaceGitWritable_theGitOfAWritableWorkspaceIsReadOnly() throws IOException {
        assertThat(HomeLayout.of(home()).workspaceGitWritable()).isFalse();
        config("{\"workspace_git_writable\": null}");
        assertThat(HomeLayout.read(home(), userHome()).workspaceGitWritable()).isFalse();
        config("{\"workspace_git_writable\": false}");
        assertThat(HomeLayout.read(home(), userHome()).workspaceGitWritable()).isFalse();
    }

    @Test
    void workspaceGitWritable_isRead_andKeptWithTheOtherSettings() throws IOException {
        config("{\"workspace_git_writable\": true, \"writable_mounts\": [\"/data\"]}");

        HomeLayout layout = HomeLayout.read(home(), userHome());

        assertThat(layout.workspaceGitWritable()).isTrue();
        assertThat(layout.writableMounts()).containsExactly(Path.of("/data"));
        assertThat(layout.withSealFiles(temp.resolve("key"), null).workspaceGitWritable()).isTrue();
        assertThat(layout.withWritableMounts(List.of()).workspaceGitWritable()).isTrue();
        assertThat(layout.withWorkspaceGitWritable(false).writableMounts()).containsExactly(Path.of("/data"));
        assertThat(HomeLayout.of(home()).withWorkspaceGitWritable(true).workspaceGitWritable()).isTrue();
    }

    @Test
    void aWorkspaceGitWritable_thatIsNoBoolean_isRefused() throws IOException {
        for (String value : List.of("\"true\"", "1", "[true]")) {
            config("{\"workspace_git_writable\": " + value + "}");
            Path home = home();
            Path userHome = userHome();
            assertThatThrownBy(() -> HomeLayout.read(home, userHome)).as(value)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'workspace_git_writable'")
                    .hasMessageContaining("true or false")
                    .hasMessageContaining(home.resolve("traceworks.json").toString());
        }
    }

    @Test
    void twoPlacesInOneFolder_orOneInsideTheOther_areRefused() throws IOException {
        Path disk = temp.resolve("disk");
        refused("{\"runs_dir\": \"" + disk + "\", \"ledger_dir\": \"" + disk + "\"}", "'runs_dir'", "'ledger_dir'");
        refused("{\"runs_dir\": \"" + disk + "\", \"registers_dir\": \"" + disk.resolve("registers") + "\"}",
                "'runs_dir'", "'registers_dir'");
        refused("{\"ledger_dir\": \"" + disk.resolve("a/b") + "\", \"registers_dir\": \"" + disk.resolve("a") + "\"}",
                "'ledger_dir'", "'registers_dir'");
    }

    @Test
    void aPlaceInTheFixedFolderOfAnotherPlace_isRefused() throws IOException {
        refused("{\"ledger_dir\": \"runs\"}", "'ledger_dir'", home().resolve("runs").toString());
        refused("{\"runs_dir\": \"" + temp.resolve("disk") + "\", \"registers_dir\": \"runs/registers\"}",
                "'registers_dir'", home().resolve("runs").toString());
        refused("{\"runs_dir\": \"ledger/runs\"}", "'runs_dir'", home().resolve("ledger").toString());
    }

    @Test
    void aPlaceThatIsOrHoldsTheHome_orTouchesItsCheckoutsOrCaches_isRefused() throws IOException {
        refused("{\"runs_dir\": \".\"}", "'runs_dir'", "Traceworks home");
        refused("{\"ledger_dir\": \"..\"}", "'ledger_dir'", "Traceworks home");
        refused("{\"registers_dir\": \"/\"}", "'registers_dir'", "Traceworks home");
        refused("{\"runs_dir\": \"workspaces\"}", "'runs_dir'", home().resolve("workspaces").toString());
        refused("{\"runs_dir\": \"workspaces/runs\"}", "'runs_dir'", home().resolve("workspaces").toString());
        refused("{\"ledger_dir\": \"cache/tools/ledger\"}", "'ledger_dir'", home().resolve("cache").toString());
    }

    /** The seal log and the allowed signers are no settings: they stay in the home, and no place may take theirs. */
    @Test
    void aPlaceThatTouchesTheSealLog_orIsNamedLikeTheAllowedSigners_isRefused() throws IOException {
        Path disk = temp.resolve("disk");
        config("{\"runs_dir\": \"" + disk.resolve("runs") + "\", \"ledger_dir\": \"" + disk.resolve("ledger")
                + "\", \"registers_dir\": \"" + disk.resolve("registers") + "\"}");
        assertThat(HomeLayout.read(home()).seals()).isEqualTo(home().resolve("seals"));
        assertThat(HomeLayout.read(home()).allowedSigners()).isEqualTo(home().resolve("allowed_signers"));

        refused("{\"runs_dir\": \"seals\"}", "'runs_dir'", home().resolve("seals").toString(), "seal log");
        refused("{\"registers_dir\": \"seals/registers\"}", "'registers_dir'", home().resolve("seals").toString());
        refused("{\"ledger_dir\": \"allowed_signers\"}", "'ledger_dir'",
                home().resolve("allowed_signers").toString());
        refused("{\"runs_dir\": \"allowed_signers/runs\"}", "'runs_dir'",
                home().resolve("allowed_signers").toString());
    }

    @Test
    void aPlaceBelowTheHome_underAnotherName_isAllowed() throws IOException {
        config("{\"runs_dir\": \"data/runs\", \"ledger_dir\": \"data/ledger\", \"registers_dir\": \"data/registers\"}");

        assertThat(HomeLayout.read(home()).runs()).isEqualTo(home().resolve("data/runs"));
    }

    @Test
    void aConfiguredPlaceThatIsAFile_isRefused_andAFolderOrNothingThereIsNot() throws IOException {
        Path file = Files.writeString(temp.resolve("a-file"), "not a folder");
        Path folder = Files.createDirectories(temp.resolve("a-folder"));

        refused("{\"runs_dir\": \"" + file + "\"}", "'runs_dir'", file.toString(), "not a directory");
        config("{\"runs_dir\": \"" + folder + "\", \"ledger_dir\": \"" + temp.resolve("not-there-yet") + "\"}");
        assertThat(HomeLayout.read(home()).runs()).isEqualTo(folder);
    }

    @Test
    void theOperatorConfiguration_knowsTheKeys_andRefusesWhatTheLayoutRefuses() throws IOException {
        config("{\"runs_dir\": \"data/runs\", \"actor\": \"operator\"}");
        assertThat(OperatorConfigFile.read(home()).actor()).isEqualTo("operator");

        config("{\"runs_dir\": 5}");
        Path home = home();
        assertThatThrownBy(() -> OperatorConfigFile.read(home)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'runs_dir'");
        config("{\"run_dir\": \"x\"}");
        assertThatThrownBy(() -> HomeLayout.read(home)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown key 'run_dir'").hasMessageContaining("runs_dir");
    }

    /** The layout knows the files the seal settings name, so the guards can keep steps from them. */
    @Test
    void theFilesOfTheSealSettings_areKnownToTheLayout_whereTheSettingsPutThem() throws IOException {
        assertThat(HomeLayout.read(home()).sealFiles()).isEmpty();

        config("{\"signing_key\": \"" + temp.resolve("keys/seal") + "\", \"allowed_signers\": \"trust/signers\"}");

        assertThat(HomeLayout.read(home()).sealFiles()).extracting(f -> f.setting() + " " + f.file())
                .containsExactly("signing_key " + temp.resolve("keys/seal"),
                        "allowed_signers " + home().resolve("trust/signers"));
    }
}
