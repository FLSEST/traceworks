package traceworks.adapters.container;

import traceworks.adapters.config.HomeLayout;
import traceworks.core.port.HostDirectories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The start check of the directories every container of a run mounts: the workspace and the package,
 * which the check of a step's own mounts ({@link MountGuard#check}) never sees.
 */
class RunDirectoryGuardTest {

    @TempDir
    Path home;
    @TempDir
    Path outside;

    private Path traceworksHome;
    private RunDirectoryGuard guard;

    @BeforeEach
    void layout() throws IOException {
        traceworksHome = Files.createDirectories(home.resolve(".traceworks"));
        guard = new RunDirectoryGuard(traceworksHome, home, Map.of());
    }

    private Optional<String> refusal(Path directory) {
        return Optional.ofNullable(guard.check(directory).refusal());
    }

    @Test
    void aProjectBelowTheHome_andAPackageInTheTraceworksHome_areAllowed() throws IOException {
        Path project = Files.createDirectories(home.resolve("src/project"));
        Path pkg = Files.createDirectories(traceworksHome.resolve("packages/hello"));
        Path elsewhere = Files.createDirectories(outside.resolve("work"));

        assertThat(refusal(project)).isEmpty();
        assertThat(refusal(pkg)).as("the package path may lie in the home").isEmpty();
        assertThat(refusal(elsewhere)).isEmpty();
    }

    /**
     * The Traceworks home's records, ledger and caches. With {@code workspace_writable: true} a container
     * could rewrite another run's record or the ledger; a run's own scratch directory is mounted on its own.
     */
    @Test
    void theRunRecords_theLedgerAndTheCaches_areRefused_aCheckoutInWorkspacesIsAllowed() throws IOException {
        Path runFolder = Files.createDirectories(traceworksHome.resolve("runs/2026-11-04/run-1"));
        Path scratch = Files.createDirectories(runFolder.resolve("scratch"));
        Path ledger = Files.createDirectories(traceworksHome.resolve("ledger"));
        Path tools = Files.createDirectories(traceworksHome.resolve("cache/tools"));
        Path checkout = Files.createDirectories(traceworksHome.resolve("workspaces/run-2"));

        for (Path refused : List.of(traceworksHome.resolve("runs"), runFolder, scratch, ledger, ledger.getParent()
                .resolve("cache"), tools)) {
            assertThat(refusal(refused)).as(refused.toString())
                    .hasValueSatisfying(r -> assertThat(r).contains("Traceworks home"));
        }
        assertThat(refusal(runFolder)).hasValueSatisfying(r -> assertThat(r).contains("runs"));
        assertThat(refusal(ledger)).hasValueSatisfying(r -> assertThat(r).contains("ledger"));
        assertThat(refusal(checkout)).as("sources are checked out under workspaces/").isEmpty();
    }

    /**
     * A name that ends in white space is refused: a path that is cut at its ends anywhere on the way to the runtime
     * would name another entry, the link {@code wf} beside the directory {@code "wf "}.
     */
    @Test
    void aDirectoryWhoseNameEndsInWhiteSpace_isRefused() throws IOException {
        Path spaced = Files.createDirectories(outside.resolve("wf "));
        Files.createSymbolicLink(outside.resolve("wf"), Files.createDirectories(outside.resolve("elsewhere")));
        Path linkToSpaced = Files.createSymbolicLink(outside.resolve("plain"), spaced);

        assertThat(refusal(spaced)).hasValueSatisfying(r -> assertThat(r).contains("white space"));
        assertThat(refusal(linkToSpaced)).as("judged by its real path too")
                .hasValueSatisfying(r -> assertThat(r).contains("white space"));
        assertThat(refusal(outside.resolve("elsewhere"))).isEmpty();
    }

    /** The name as given counts too: {@code "wf "} is refused even when it is a link to a plainly named directory. */
    @Test
    void aLinkWhoseNameEndsInWhiteSpace_isRefused_alsoWhenItsTargetHasAPlainName() throws IOException {
        Path plain = Files.createDirectories(outside.resolve("wf"));
        Path spacedLink = Files.createSymbolicLink(outside.resolve("wf "), plain);

        assertThat(refusal(spacedLink)).hasValueSatisfying(r -> assertThat(r).contains("white space"));
    }

    /**
     * {@code link/..} resolves to the link target's parent, the way the kernel and {@link Path#toRealPath} read it,
     * not to the folder that holds the link, as a lexical normalization reads it. The check must judge what is
     * mounted: here {@code /etc}.
     */
    @Test
    void aLinkFollowedByDotDot_isJudgedByWhatItResolvesTo() throws IOException {
        // A directory below /etc whose real parent is /etc itself (not so on macOS, where /etc is /private/etc)
        Path below = Path.of("/etc/ssl");
        assumeTrue(Files.isDirectory(below) && below.toRealPath().getParent().equals(Path.of("/etc")),
                "needs /etc/ssl directly below a real /etc");
        Path project = Files.createDirectories(home.resolve("src/project"));
        Files.createSymbolicLink(project.resolve("certs"), below);
        Path given = Path.of(project + "/certs/..");

        assertThat(guard.realPath(given)).isEqualTo("/etc");
        assertThat(refusal(given)).hasValueSatisfying(r -> assertThat(r).contains("forbidden path /etc"));
    }

    @Test
    void aLinkIntoTheRunRecords_isRefusedAsItsTarget() throws IOException {
        Path runFolder = Files.createDirectories(traceworksHome.resolve("runs/2026-11-04/run-1"));
        Path link = Files.createSymbolicLink(outside.resolve("innocent"), runFolder);

        assertThat(refusal(link)).hasValueSatisfying(r -> assertThat(r).contains("runs"));
    }

    @Test
    void workspaceThatIsOrContainsTheHome_isRefused() {
        assertThat(refusal(home)).hasValueSatisfying(r -> assertThat(r).contains("home directory",
                "run from a project directory or pass --workspace").doesNotContain("mount a directory"));
        assertThat(refusal(home.getParent())).hasValueSatisfying(r -> assertThat(r).containsAnyOf("home directory",
                "forbidden path"));
    }

    /** A package is not chosen by where the run starts or by --workspace, so its refusal gives its own advice. */
    @Test
    void packageThatIsOrContainsTheHome_isRefused_withAdviceForAPackage() {
        assertThat(Optional.ofNullable(guard.check(home, HostDirectories.Role.PACKAGE).refusal()))
                .hasValueSatisfying(r -> assertThat(r).contains("home directory",
                        "keep the package (workflow.json and its files) in a folder of its own")
                        .doesNotContain("--workspace", "project directory"));
        assertThat(Optional.ofNullable(guard.check(home, HostDirectories.Role.WORKSPACE).refusal()))
                .hasValueSatisfying(r -> assertThat(r).contains("run from a project directory or pass --workspace"));
    }

    @Test
    void theTraceworksHome_andAFolderHoldingIt_areRefused() throws IOException {
        Path ownHome = Files.createDirectories(outside.resolve("tw/home"));
        RunDirectoryGuard elsewhere = new RunDirectoryGuard(ownHome, home, Map.of());

        assertThat(Optional.ofNullable(elsewhere.check(ownHome).refusal()))
                .hasValueSatisfying(r -> assertThat(r).contains("Traceworks home"));
        assertThat(Optional.ofNullable(elsewhere.check(ownHome.getParent()).refusal()))
                .hasValueSatisfying(r -> assertThat(r).contains("Traceworks home"));
    }

    @Test
    void theRoot_andTheForbiddenSystemPaths_areRefused() {
        assertThat(refusal(Path.of("/"))).hasValueSatisfying(r -> assertThat(r).contains("forbidden path /"));
        for (String forbidden : List.of("/etc", "/proc", "/sys", "/root", "/run/user", "/run/user/1000/app")) {
            assertThat(refusal(Path.of(forbidden))).as(forbidden)
                    .hasValueSatisfying(r -> assertThat(r).contains("forbidden path"));
        }
        assertThat(refusal(Path.of("/var"))).as("it holds the Docker socket and the runtimes' storage")
                .hasValueSatisfying(r -> assertThat(r).contains("forbidden path /var/"));
    }

    /**
     * {@code /dev} is refused as a run's directory like the other system paths, but for a folder in
     * {@code /dev/shm}: a memory disk is an honest place for a build's workspace or a package. What stays refused
     * is {@code /dev/shm} itself, which holds the shared memory of other programs, the rest of {@code /dev}, and a
     * path that only passes through {@code /dev/shm}.
     */
    @Test
    void aFolderInTheMemoryDisk_isAllowedAsARunDirectory_theRestOfDevIsNot() throws IOException {
        Path shm = Path.of("/dev/shm");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(shm) && Files.isWritable(shm)
                && shm.toRealPath().equals(shm), "no /dev/shm to write here");
        Path build = Files.createTempDirectory(shm, "traceworks-test-");
        try {
            Path work = Files.createDirectories(build.resolve("work"));
            assertThat(refusal(build)).as("a folder in /dev/shm").isEmpty();
            assertThat(refusal(work)).as("a folder deeper in /dev/shm").isEmpty();
            assertThat(guard.check(work).realPath()).isEqualTo(work.toString());
            assertThat(refusal(build.resolve("../../pts"))).as("through /dev/shm into /dev")
                    .hasValueSatisfying(r -> assertThat(r).contains("forbidden path /dev"));
            Path link = Files.createSymbolicLink(build.resolve("terminals"), Path.of("/dev/pts"));
            assertThat(refusal(link)).as("a link in /dev/shm to /dev/pts")
                    .hasValueSatisfying(r -> assertThat(r).contains("forbidden path /dev"));
        } finally {
            Files.deleteIfExists(build.resolve("terminals"));
            Files.deleteIfExists(build.resolve("work"));
            Files.deleteIfExists(build);
        }
        for (String refused : List.of("/dev", "/dev/shm", "/dev/pts", "/dev/shm/../pts")) {
            assertThat(refusal(Path.of(refused))).as(refused)
                    .hasValueSatisfying(r -> assertThat(r).contains("forbidden path /dev"));
        }
    }

    /**
     * A named pipe or a device node directly in a run's directory is a line to a process or to the host, as a
     * socket there is: the directory is refused, by the same one-level rule.
     */
    @Test
    void aDirectoryThatDirectlyHoldsANamedPipe_isRefused() throws Exception {
        Path project = Files.createDirectories(outside.resolve("project/sub"));
        assertThat(new ProcessBuilder("mkfifo", project.resolve("pipe").toString()).inheritIO().start().waitFor())
                .isZero();

        assertThat(refusal(project)).hasValueSatisfying(r -> assertThat(r).contains("named pipe"));
        assertThat(refusal(project.getParent())).as("two levels down is not looked at").isEmpty();
    }

    /** The storage of a runtime holds the images and containers later runs use. */
    @Test
    void theRuntimesStorage_isRefused() throws IOException {
        for (String storage : List.of("/var/lib/docker", "/var/lib/containers/storage")) {
            assertThat(refusal(Path.of(storage))).as(storage)
                    .hasValueSatisfying(r -> assertThat(r).contains("forbidden path /var/lib/"));
        }
        Path rootless = Files.createDirectories(home.resolve(".local/share/containers/storage"));

        assertThat(refusal(rootless)).hasValueSatisfying(r -> assertThat(r).contains("container runtime's storage"));
    }

    @Test
    void theOperatorsSessionPaths_areRefused() throws IOException {
        Path runtimeDir = Files.createDirectories(outside.resolve("xdg"));
        RunDirectoryGuard session = new RunDirectoryGuard(traceworksHome, home,
                Map.of("XDG_RUNTIME_DIR", runtimeDir.toString()));

        assertThat(Optional.ofNullable(session.check(runtimeDir).refusal()))
                .hasValueSatisfying(r -> assertThat(r).contains("session"));
        assertThat(Optional.ofNullable(session.check(outside).refusal())).as("a folder holding it")
                .hasValueSatisfying(r -> assertThat(r).contains("session"));
    }

    @Test
    void aLoginBelowTheHome_isRefused() throws IOException {
        Path ssh = Files.createDirectories(home.resolve(".ssh/keys"));

        assertThat(refusal(ssh)).hasValueSatisfying(r -> assertThat(r).contains("~/.ssh"));
    }

    /**
     * A project may hold settings named like a credential store ({@code .aws} with profiles): the run's own
     * directories are the operator's choice, and only a step's own mounts are read as a copy of a home.
     */
    @Test
    void aProjectWithItsOwnCredentialStore_isAllowed() throws IOException {
        Path project = Files.createDirectories(outside.resolve("infra"));
        Files.createDirectories(project.resolve(".aws"));

        assertThat(refusal(project)).isEmpty();
    }

    @Test
    void aDirectoryThatCannotBeResolved_orIsNoDirectory_isRefused() throws IOException {
        Path file = Files.writeString(outside.resolve("file.txt"), "x");

        assertThat(refusal(outside.resolve("missing"))).hasValueSatisfying(r -> assertThat(r).contains("not exist"));
        assertThat(refusal(file)).hasValueSatisfying(r -> assertThat(r).contains("not a directory"));
        assertThat(refusal(Path.of("relative/dir"))).hasValueSatisfying(r -> assertThat(r).contains("absolute"));
    }

    @Test
    void aDirectoryThatDirectlyHoldsASocket_isRefused() throws IOException {
        Path project = Files.createDirectories(outside.resolve("sockets"));
        Path socket = project.resolve("s");
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket));

            assertThat(refusal(project)).hasValueSatisfying(r -> assertThat(r).contains("socket"));
        }
    }

    @Test
    void aPathTheMountCannotCarry_isRefused() throws IOException {
        Path comma = Files.createDirectories(outside.resolve("a,b"));

        assertThat(refusal(comma)).hasValueSatisfying(r -> assertThat(r).contains("comma"));
    }

    /**
     * {@code --mount} reads a quote and ends its value at a line break, so a run is refused such a directory when it
     * starts, with the reason, and not at its first container. The real path counts too: it is what is mounted.
     */
    @Test
    void aPathWithAQuoteOrALineBreak_isRefused_alsoBehindALinkWithAPlainName() throws IOException {
        Path quote = Files.createDirectories(outside.resolve("say \"hi\""));
        Path lineBreak = Files.createDirectories(outside.resolve("two\nlines"));
        Path carriageReturn = Files.createDirectories(outside.resolve("two\rlines"));
        Path link = Files.createSymbolicLink(outside.resolve("plain-name"), quote);

        for (Path directory : List.of(quote, lineBreak, carriageReturn, link)) {
            assertThat(refusal(directory)).as(directory.toString())
                    .hasValueSatisfying(r -> assertThat(r).contains("cannot be mounted"));
        }
    }

    /** The check hands back the real path it judged, so a caller needs no second resolution that could differ. */
    @Test
    void theCheck_handsBackTheRealPathItJudged() throws IOException {
        Path project = Files.createDirectories(outside.resolve("real-project"));
        Path link = Files.createSymbolicLink(outside.resolve("link"), project);

        assertThat(guard.check(link).isRefused()).isFalse();
        assertThat(guard.check(link).realPath()).isEqualTo(project.toRealPath().toString());
        assertThat(guard.check(outside.resolve("real-project/../real-project")).realPath())
                .isEqualTo(project.toRealPath().toString());
    }

    @Test
    void aLinkedDirectory_isCheckedAndResolvedAsItsTarget() throws IOException {
        Path project = Files.createDirectories(outside.resolve("real-project"));
        Path link = Files.createSymbolicLink(outside.resolve("link"), project);
        Path toHome = Files.createSymbolicLink(outside.resolve("to-home"), home);

        assertThat(guard.realPath(link)).isEqualTo(project.toRealPath().toString());
        assertThat(guard.realPath(outside.resolve("real-project/../real-project")))
                .isEqualTo(project.toRealPath().toString());
        assertThat(refusal(toHome)).hasValueSatisfying(r -> assertThat(r).contains("home directory"));
        assertThat(guard.check(toHome).realPath()).as("a refused directory has no path to mount").isNull();
        assertThatThrownBy(() -> guard.realPath(outside.resolve("missing")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("missing");
    }

    /**
     * The places the operator names in traceworks.json ({@code runs_dir}, {@code ledger_dir}, {@code registers_dir})
     * are records wherever they lie: a workspace or a package that is one, lies in one or holds one would hand a
     * step the record through the workspace mount.
     */
    @Test
    void thePlacesTheOperatorSet_areRefused_asTheFixedFoldersAre(@TempDir Path disk) throws IOException {
        Path runs = Files.createDirectories(disk.resolve("project/.traceworks-runs"));
        Path ledger = Files.createDirectories(disk.resolve("audit/ledger"));
        Path registers = Files.createDirectories(disk.resolve("audit/registers"));
        Path runFolder = Files.createDirectories(runs.resolve("2026-11-14/run-1/scratch")).getParent();
        Path free = Files.createDirectories(disk.resolve("other-project"));
        Path oldRuns = Files.createDirectories(traceworksHome.resolve("runs/2026-11-01/run-0"));
        RunDirectoryGuard placed = new RunDirectoryGuard(new HomeLayout(traceworksHome, runs, ledger, registers),
                home, Map.of());

        for (Path refused : List.of(runs, runFolder, runFolder.resolve("scratch"), runs.getParent(), ledger,
                registers, Files.createDirectories(registers.resolve("hello")), ledger.getParent(), disk, oldRuns)) {
            assertThat(placed.check(refused).refusal()).as(refused.toString()).isNotNull()
                    .contains("what runs leave behind");
        }
        assertThat(placed.check(runFolder).refusal()).contains(runs.toString());
        assertThat(placed.check(free).refusal()).isNull();
    }

    /** A place named through a symbolic link is judged where it really lies, and so is the directory. */
    @Test
    void aPlaceBehindASymbolicLink_isRefused_byItsLinkAndByItsRealPath(@TempDir Path disk) throws IOException {
        Path realRuns = Files.createDirectories(disk.resolve("volume/runs"));
        Path link = Files.createSymbolicLink(disk.resolve("runs-link"), realRuns);
        Path runFolder = Files.createDirectories(realRuns.resolve("2026-11-14/run-1"));
        Path alias = Files.createSymbolicLink(disk.resolve("innocent"), runFolder);
        RunDirectoryGuard placed = new RunDirectoryGuard(new HomeLayout(traceworksHome, link,
                traceworksHome.resolve("ledger"), traceworksHome.resolve("registers")), home, Map.of());

        for (Path refused : List.of(link, realRuns, runFolder, link.resolve("2026-11-14/run-1"), alias,
                realRuns.getParent())) {
            assertThat(placed.check(refused).refusal()).as(refused.toString()).isNotNull()
                    .contains("what runs leave behind");
        }
        assertThat(placed.check(Files.createDirectories(disk.resolve("volume2"))).refusal()).isNull();
    }

    /**
     * The seal log says which packages are sealed and what was refused: with a writable workspace a step could
     * rewrite it. A workspace that is the folder, lies in it or holds it is refused, also where a link puts the log.
     */
    @Test
    void theSealLogOfTheHome_isRefused_alsoWhereALinkPutsIt(@TempDir Path disk) throws IOException {
        Path seals = Files.createDirectories(traceworksHome.resolve("seals"));

        assertThat(refusal(seals)).hasValueSatisfying(r -> assertThat(r).contains("seals", "what runs leave behind"));
        assertThat(refusal(Files.createDirectories(seals.resolve("sub")))).isPresent();
        assertThat(refusal(traceworksHome)).isPresent();

        Path linkedHome = Files.createDirectories(home.resolve(".linked"));
        Path real = Files.createDirectories(disk.resolve("volume/seal-log"));
        Files.createSymbolicLink(linkedHome.resolve("seals"), real);
        RunDirectoryGuard linked = new RunDirectoryGuard(linkedHome, home, Map.of());
        for (Path refused : List.of(real, real.getParent(), linkedHome.resolve("seals"))) {
            assertThat(linked.check(refused).refusal()).as(refused.toString()).isNotNull()
                    .contains("what runs leave behind");
        }
    }

    /**
     * What the start check of a run asks of a writable mount a workflow writes out: the home's
     * {@code writable_mounts}, by the real path, as the mount check asks it again when the step runs.
     */
    @Test
    void aWritableMountAWorkflowWritesOut_isRefused_unlessAListedFolderHoldsIt() throws IOException {
        Path out = Files.createDirectories(home.resolve("out"));
        Files.createDirectories(home.resolve("documents"));
        RunDirectoryGuard listing = new RunDirectoryGuard(traceworks.adapters.config.HomeLayout.of(traceworksHome)
                .withWritableMounts(List.of(out)), home, Map.of());

        assertThat(guard.writableMountRefusal("~/out")).as("without the setting")
                .hasValueSatisfying(r -> assertThat(r).contains("writable_mounts",
                        traceworksHome.resolve("traceworks.json").toString()));
        assertThat(listing.writableMountRefusal("~/out")).isEmpty();
        assertThat(listing.writableMountRefusal(out.resolve("reports").toString())).isEmpty();
        assertThat(listing.writableMountRefusal("~/documents")).isPresent();
        assertThat(listing.writableMountRefusal(out + "/../documents")).as("by the real path").isPresent();
    }

    @Test
    void theRegistersOfTheHome_areRefused() throws IOException {
        Path register = Files.createDirectories(traceworksHome.resolve("registers/hello"));

        assertThat(refusal(register)).hasValueSatisfying(r -> assertThat(r).contains("registers"));
        assertThat(refusal(register.getParent())).isPresent();
    }
}
