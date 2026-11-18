package traceworks.adapters.container;

import traceworks.adapters.config.HomeLayout;
import traceworks.adapters.container.MountGuard.Context;
import traceworks.core.compat.EarlierNames;
import traceworks.core.container.HostSecrets;
import traceworks.core.port.CommandRunner.Mount;
import traceworks.core.port.ContainerLimits;
import traceworks.core.port.ContainerRuntime.ContainerRun;
import traceworks.core.port.ContainerRuntime.RunDirectories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The check of a step's own mounts. There is no switch that allows or forbids extra mounts and no list of allowed
 * prefixes, no security log, and no mask over a secret inside a mount: Traceworks refuses such a mount instead. The
 * Traceworks home is protected like a login.
 */
class MountGuardTest {

    @TempDir
    Path home;
    /**
     * Somewhere outside the home: the ordinary host directories a step mounts. The operator of these tests lists it
     * in {@code writable_mounts}, so a writable mount of a folder in it is refused only by the rule a test is about.
     */
    @TempDir
    Path outside;

    private Path traceworksHome;
    private Path scratch;
    private Path workspace;
    private Path packageDirectory;
    private Context context;

    @BeforeEach
    void layout() throws IOException {
        traceworksHome = Files.createDirectories(home.resolve(".traceworks"));
        scratch = Files.createDirectories(traceworksHome.resolve("runs/run-1/scratch"));
        workspace = Files.createDirectories(home.resolve("project"));
        packageDirectory = Files.createDirectories(home.resolve("packages/hello"));
        context = new Context(home, listing(outside), scratch.toString(), workspace.toString(), false,
                packageDirectory.toString(), List.of(), null);
    }

    /** The home's layout with {@code roots} as its {@code writable_mounts}. */
    private HomeLayout listing(Path... roots) {
        return HomeLayout.of(traceworksHome).withWritableMounts(List.of(roots));
    }

    /** {@link #context} of an operator who lists {@code roots}, and only them, in {@code writable_mounts}. */
    private Context withRoots(Path... roots) {
        return new Context(home, listing(roots), scratch.toString(), workspace.toString(), false,
                packageDirectory.toString(), List.of(), null);
    }

    private Optional<String> check(String host, String container, boolean readOnly) {
        return MountGuard.check(new Mount(host, container, readOnly), context);
    }

    private Optional<String> check(Path host) {
        return check(host.toString(), "/data", true);
    }

    // --- which entries may be mounted ---

    @Test
    void ordinaryHostDirectory_isAllowed() throws IOException {
        String data = Files.createDirectories(outside.resolve("user/data")).toString();
        String cache = Files.createDirectories(outside.resolve("cache")).toString();
        String file = Files.writeString(outside.resolve("settings.txt"), "x").toString();

        assertThat(check(data, "/workspace/data", false)).isEmpty();
        assertThat(check(data, "/data", false)).isEmpty();
        assertThat(check(cache, "/cache", false)).isEmpty();
        assertThat(check(file, "/settings.txt", true)).isEmpty();
    }

    @Test
    void dockerSocket_isRefused() {
        assertThat(check("/var/run/docker.sock", "/var/run/docker.sock", false))
                .hasValueSatisfying(r -> assertThat(r).contains("forbidden path /var/run/docker.sock"));
    }

    @Test
    void etcSubpath_isRefusedAsForbidden() {
        assertThat(check("/etc/passwd", "/data/passwd", true))
                .hasValueSatisfying(r -> assertThat(r).contains("forbidden path /etc"));
    }

    @Test
    void rootMount_isRefused() {
        assertThat(check("/", "/host", true)).hasValueSatisfying(r -> assertThat(r).contains("forbidden path /"));
    }

    /** The host side is harmless, but the container side is /etc. */
    @Test
    void containerSideForbidden_isRefused() {
        assertThat(check("/test/user/data", "/etc/myconf", false))
                .hasValueSatisfying(r -> assertThat(r).contains("forbidden path /etc"));
    }

    @Test
    void partiallyForbidden_refusesOnlyTheForbiddenOne() throws IOException {
        assertThat(check(Files.createDirectories(outside.resolve("data")).toString(), "/data", false)).isEmpty();
        assertThat(check("/etc", "/host-etc", true)).isPresent();
        assertThat(check(Files.createDirectories(outside.resolve("cache")).toString(), "/cache", false)).isEmpty();
    }

    /** A forbidden path is named as such, before any other reason applies. */
    @Test
    void forbiddenPath_isNamedFirst() {
        assertThat(check("/etc/passwd", "/data", true)).hasValue("forbidden path /etc");
    }

    /** A naive string check would let "documents/../../../etc" pass; the path is resolved as Docker resolves it. */
    @Test
    void pathTraversal_isResolvedBeforeTheCheck() throws IOException {
        Files.createDirectories(home.resolve("documents"));

        assertThat(check("/test/user/documents/../../../etc", "/data", false))
                .hasValueSatisfying(r -> assertThat(r).contains("forbidden path /etc"));
        assertThat(check(home + "/documents/./../.ssh", "/mnt", true))
                .hasValueSatisfying(r -> assertThat(r).contains("~/.ssh"));
        Files.createDirectories(home.resolve("documents/cv"));
        assertThat(check(home + "/documents/cv", "/data", true)).isEmpty();
    }

    @Test
    void blankHostPath_isRefused() {
        assertThat(check("", "/data", true)).isPresent();
        assertThat(check("   ", "/data", true)).isPresent();
    }

    @Test
    void namedVolumeOrRelativePath_isRefused() {
        assertThat(check("ai-automation-backend_stable-secrets", "/secrets", true))
                .hasValueSatisfying(r -> assertThat(r).contains("named volumes are not allowed"));
        assertThat(check("relative/dir", "/data", true))
                .hasValueSatisfying(r -> assertThat(r).contains("named volumes are not allowed"));
    }

    /** The Traceworks home is refused like a login below the home. */
    @Test
    void secretsOfTheHomeAndTheTraceworksHome_areRefused() throws IOException {
        Path credentials = Files.writeString(traceworksHome.resolve("credentials.json"), "{}");
        Files.createSymbolicLink(home.resolve("shortcut"), traceworksHome);
        Files.createDirectories(home.resolve("work"));
        Files.createDirectories(home.resolve(".ssh"));

        assertThat(check(home)).as("contains everything").isPresent();
        assertThat(check(traceworksHome)).as("the Traceworks home").isPresent();
        assertThat(check(credentials)).as("a key file").isPresent();
        assertThat(check(home.resolve("shortcut"))).as("the Traceworks home through a link").isPresent();
        assertThat(check(home.resolve("work/../.traceworks"))).as("... or through ..").isPresent();
        assertThat(check("~/.ssh", "/ssh", true)).as("a login below the home").isPresent();
    }

    @Test
    void folderBelowHome_isAllowedReadOnly_andMountedWithItsRealPath() throws IOException {
        Path docs = Files.createDirectories(home.resolve("documents"));
        Path link = Files.createSymbolicLink(home.resolve("docs-link"), docs);

        assertThat(check("~/documents", "/docs", true)).isEmpty();
        assertThat(check(link.toString(), "/docs2", true)).isEmpty();
        assertThat(MountGuard.realHostPath("~/documents", home)).isEqualTo(docs.toRealPath().toString());
        assertThat(MountGuard.realHostPath(link.toString(), home)).isEqualTo(docs.toRealPath().toString());
    }

    // --- writable mounts: only in or below a folder the operator lists (writable_mounts) ---

    /** Without the setting no package mounts a host path writable, wherever it lies; read-only is untouched. */
    @Test
    void aWritableMount_isRefusedWithoutAListedFolder_whereverItLies_andTheRefusalNamesTheLineToAdd()
            throws IOException {
        Path docs = Files.createDirectories(home.resolve("documents"));
        Path data = Files.createDirectories(outside.resolve("data"));
        Context none = withRoots();

        for (Path host : List.of(docs, data)) {
            assertThat(MountGuard.check(new Mount(host.toString(), "/x", false), none)).as(host.toString())
                    .hasValueSatisfying(reason -> assertThat(reason).contains("writable_mounts")
                            .contains(traceworksHome.resolve("traceworks.json").toString())
                            .contains("\"writable_mounts\": [\"" + MountGuard.real(host) + "\"]"));
            assertThat(MountGuard.check(new Mount(host.toString(), "/x", true), none)).as(host + ", read-only")
                    .isEmpty();
        }
        assertThat(MountGuard.check(new Mount("~/documents", "/x", false), none)).as("~ is the home").isPresent();
    }

    /** The line to add keeps what the list already holds, and names the folder of a file. */
    @Test
    void theRefusalOfAWritableMount_namesTheWholeList_andTheFolderOfAFile() throws IOException {
        Path listed = Files.createDirectories(outside.resolve("listed"));
        Path file = Files.writeString(Files.createDirectories(outside.resolve("other")).resolve("notes.txt"), "x");

        assertThat(MountGuard.check(new Mount(file.toString(), "/x", false), withRoots(listed)))
                .hasValueSatisfying(reason -> assertThat(reason).contains("\"writable_mounts\": [\"" + listed
                        + "\", \"" + MountGuard.real(file.getParent()) + "\"]"));
    }

    @Test
    void aWritableMount_inOrBelowAListedFolder_isAllowed_andMountedWithItsRealPath() throws IOException {
        Path out = Files.createDirectories(home.resolve("out"));
        Path deep = Files.createDirectories(out.resolve("reports/2026"));
        Path file = Files.writeString(out.resolve("result.json"), "{}");
        Path beside = Files.createDirectories(home.resolve("out-of-it"));
        Context listed = withRoots(out);

        for (String host : List.of("~/out", out.toString(), deep.toString(), file.toString())) {
            MountGuard.Judged judged = MountGuard.judge(new Mount(host, "/x", false), listed);
            assertThat(judged.refusal()).as(host).isEmpty();
            assertThat(judged.realPath()).isEqualTo(Path.of(MountGuard.expandHome(host, home)).toRealPath().toString());
        }
        assertThat(MountGuard.check(new Mount(beside.toString(), "/x", false), listed)).as("a name that only begins"
                + " like the folder").hasValueSatisfying(reason -> assertThat(reason).contains("writable_mounts"));
        assertThat(MountGuard.check(new Mount(home.resolve("out/..").toString() + "/out-of-it", "/x", false), listed))
                .as("out of the folder with ..").isPresent();
    }

    /** A link in a listed folder that leads out of it lists nothing; what counts is where the mount really lies. */
    @Test
    void aWritableMount_isJudgedByItsRealPath_andSoIsTheListedFolder() throws IOException {
        Path out = Files.createDirectories(home.resolve("out"));
        Path bin = Files.createDirectories(home.resolve(".local/bin"));
        Path wayOut = Files.createSymbolicLink(out.resolve("bin"), bin);
        Path wayIn = Files.createSymbolicLink(home.resolve("to-out"), out);
        Path listedLink = Files.createSymbolicLink(home.resolve("listed"), out);

        assertThat(MountGuard.check(new Mount(wayOut.toString(), "/x", false), withRoots(out)))
                .as("a link in the folder that leaves it")
                .hasValueSatisfying(reason -> assertThat(reason).contains(MountGuard.real(bin).toString()));
        assertThat(MountGuard.check(new Mount(wayIn.toString(), "/x", false), withRoots(out)))
                .as("a link from outside into the folder").isEmpty();
        assertThat(MountGuard.check(new Mount(out.toString(), "/x", false), withRoots(listedLink)))
                .as("the listed folder is a link").isEmpty();
    }

    /** A listed folder allows a writable mount; it lifts no other refusal. */
    @Test
    void aListedFolder_isNoWayAroundTheOtherRefusals() throws IOException {
        Path work = Files.createDirectories(home.resolve("work"));
        Path copyOfAHome = Files.createDirectories(work.resolve("backup"));
        Files.createDirectories(copyOfAHome.resolve(".ssh"));
        Path sockets = Files.createDirectories(work.resolve("sockets"));
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(sockets.resolve("s")));

            Context listed = new Context(home, listing(work, traceworksHome, packageDirectory.getParent(), workspace),
                    scratch.toString(), workspace.toString(), false, packageDirectory.toString(), List.of(), null);

            assertThat(MountGuard.check(new Mount(copyOfAHome.toString(), "/x", false), listed))
                    .as("a folder with a credential store of its own").isPresent()
                    .hasValueSatisfying(reason -> assertThat(reason).doesNotContain("writable_mounts"));
            assertThat(MountGuard.check(new Mount(sockets.toString(), "/x", false), listed)).as("a socket")
                    .hasValueSatisfying(reason -> assertThat(reason).contains("socket"));
            assertThat(MountGuard.check(new Mount(traceworksHome.resolve("runs").toString(), "/x", false), listed))
                    .as("the Traceworks home").hasValueSatisfying(reason -> assertThat(reason).contains("Traceworks home"));
            assertThat(MountGuard.check(new Mount(packageDirectory.toString(), "/x", false), listed)).as("the package")
                    .hasValueSatisfying(reason -> assertThat(reason).contains("package"));
            assertThat(MountGuard.check(new Mount(workspace.toString(), "/x", false), listed))
                    .as("the read-only workspace")
                    .hasValueSatisfying(reason -> assertThat(reason).contains("workspace_writable"));
            assertThat(MountGuard.check(new Mount(work.resolve("missing").toString(), "/x", false), listed))
                    .as("a path that does not exist")
                    .hasValueSatisfying(reason -> assertThat(reason).contains("does not exist"));
            assertThat(MountGuard.check(new Mount(Files.createDirectories(work.resolve("fine")).toString(), "/x",
                    false), listed)).isEmpty();
        }
    }

    /**
     * The setting refuses the home directory, a folder that holds it and {@code /} when the file is read
     * ({@code HomeLayoutTest}). The guard counts none of them either, whoever built the layout.
     */
    @Test
    void aListedFolder_thatIsTheHomeOrHoldsIt_listsNothing() throws IOException {
        Path startup = Files.writeString(home.resolve(".bashrc"), "# the operator's\n");
        Path docs = Files.createDirectories(home.resolve("documents"));

        for (Path root : List.of(home, home.getParent(), Path.of("/"), Path.of("relative"))) {
            Context listed = withRoots(root);
            assertThat(MountGuard.check(new Mount(startup.toString(), "/x", false), listed)).as(root.toString())
                    .hasValueSatisfying(reason -> assertThat(reason).contains("writable_mounts"));
            assertThat(MountGuard.check(new Mount(docs.toString(), "/x", false), listed)).as(root.toString())
                    .hasValueSatisfying(reason -> assertThat(reason).contains("writable_mounts"));
        }
    }

    /** What Traceworks itself manages needs no entry: the folder a tool step's findings are written to. */
    @Test
    void theOutputFolderTheEngineMakesForAToolStep_needsNoListedFolder() throws IOException {
        Path output = Files.createDirectories(outside.resolve("traceworks-tool-1"));
        Context none = withRoots();

        assertThat(MountGuard.check(new Mount(output.toString(), "/out", false, false, true), none)).isEmpty();
        assertThat(MountGuard.check(new Mount(output.toString(), "/out", false), none))
                .as("a mounts entry of the same folder is a package's").isPresent();
        assertThat(MountGuard.check(new Mount(home.toString(), "/out", false, false, true), none))
                .as("the other refusals hold for it").isPresent();
    }

    /** The start check asks the same question of a mount a workflow writes out, before any step runs. */
    @Test
    void writableMountRefusal_isTheRuleOfTheListedFolders_forAHostPathAsAWorkflowWritesIt() throws IOException {
        Path out = Files.createDirectories(home.resolve("out"));
        Files.createDirectories(home.resolve("documents"));

        assertThat(MountGuard.writableMountRefusal("~/out", home, listing(out))).isEmpty();
        assertThat(MountGuard.writableMountRefusal(" " + out + "/new ", home, listing(out))).isEmpty();
        assertThat(MountGuard.writableMountRefusal("~/documents", home, listing(out)))
                .hasValueSatisfying(reason -> assertThat(reason).contains("\"writable_mounts\": [\"" + out + "\", \""
                        + MountGuard.real(home.resolve("documents")) + "\"]"));
        assertThat(MountGuard.writableMountRefusal("a-volume", home, listing(out)))
                .as("no host path: the step refuses it for that").isEmpty();
        assertThat(MountGuard.writableMountRefusal("~/not-made-yet", home, listing(out)))
                .as("a folder that does not exist yet is the folder to list, not the one that holds it")
                .hasValueSatisfying(reason -> assertThat(reason).contains("\"" + MountGuard.real(home)
                        .resolve("not-made-yet") + "\"]"));
    }

    // --- forbidden paths ---

    @Test
    void forbiddenSegment_exactMatchAndChildPaths_areForbidden() {
        assertThat(MountGuard.forbiddenSegment("/etc", "/host")).isEqualTo("/etc");
        assertThat(MountGuard.forbiddenSegment("/etc/foo", "/data")).isEqualTo("/etc");
        assertThat(MountGuard.forbiddenSegment("/root", "/data")).isEqualTo("/root");
        assertThat(MountGuard.forbiddenSegment("/proc/1", "/data")).isEqualTo("/proc");
        assertThat(MountGuard.forbiddenSegment("/sys", "/data")).isEqualTo("/sys");
        assertThat(MountGuard.forbiddenSegment("/var/run/docker.sock", "/sock")).isEqualTo("/var/run/docker.sock");
        assertThat(MountGuard.forbiddenSegment("/test/user", "/data")).isNull();
        assertThat(MountGuard.forbiddenSegment("/etcetera", "/data")).as("not a child of /etc").isNull();
    }

    @Test
    void forbiddenSegment_dockerSocketThroughRun_isForbidden() {
        // /var/run is a link to /run on most systems: the same socket under its other name
        assertThat(MountGuard.forbiddenSegment("/run/docker.sock", "/sock")).isNotNull();
        // a folder that holds the socket hands it over as well
        assertThat(MountGuard.forbiddenSegment("/run", "/hostrun")).isNotNull();
        assertThat(MountGuard.forbiddenSegment("/var/run", "/hostrun")).isNotNull();
    }

    @Test
    void forbiddenSegment_dotDotSegments_areResolvedOnBothSides() {
        assertThat(MountGuard.forbiddenSegment("/tmp/../etc", "/data")).isEqualTo("/etc");
        assertThat(MountGuard.forbiddenSegment("/./root/x", "/data")).isEqualTo("/root");
        assertThat(MountGuard.forbiddenSegment("/data", "/data/../etc")).isEqualTo("/etc");
    }

    @Test
    void symlinkToForbiddenPath_isRefused(@TempDir Path dir) throws IOException {
        Path link = Files.createSymbolicLink(dir.resolve("innocent"), Path.of("/etc"));

        assertThat(MountGuard.forbiddenSegment(link.toString(), "/data")).isEqualTo("/etc");
        assertThat(check(link)).hasValue("forbidden path /etc");
    }

    // --- the home and its logins ---

    @Test
    void refusal_namesWhyAndLeavesOrdinaryFoldersAlone() throws IOException {
        Path backups = Files.createDirectories(traceworksHome.resolve("ledger"));

        assertThat(check(home)).hasValueSatisfying(r -> assertThat(r).contains("home directory",
                "mount a directory below it"));
        assertThat(check(home.getParent())).isPresent();
        assertThat(check(traceworksHome)).hasValueSatisfying(r -> assertThat(r).contains("Traceworks home"));
        assertThat(check(backups.resolve("dev"))).hasValueSatisfying(r -> assertThat(r).contains("Traceworks home"));
        assertThat(check(Files.createDirectories(home.resolve("documents")))).isEmpty();
    }

    /** The analyzer's {@code secret-mount} rule reads the same {@link HostSecrets}; a test there pins its side. */
    @Test
    void refusal_coversEveryEntryOfTheSharedHostSecrets() throws IOException {
        for (String name : java.util.stream.Stream.concat(HostSecrets.HOME_SECRETS.stream(),
                HostSecrets.HOME_STORAGE.stream()).toList()) {
            Path inside = Files.createDirectories(home.resolve(name));
            assertThat(check(inside)).as(name).isPresent();
        }
    }

    @Test
    void refusal_coversLoginsBelowTheHome() throws IOException {
        Files.createDirectories(home.resolve(".ssh"));
        Files.writeString(home.resolve(".ssh/id_ed25519"), "key");
        Files.createDirectories(home.resolve(".config/gcloud"));
        Files.writeString(home.resolve(".netrc"), "machine x");

        assertThat(check(home.resolve(".ssh"))).hasValueSatisfying(r -> assertThat(r).contains("~/.ssh"));
        assertThat(check(home.resolve(".ssh/id_ed25519"))).hasValueSatisfying(r -> assertThat(r).contains("~/.ssh"));
        assertThat(check(home.resolve(".netrc"))).hasValueSatisfying(r -> assertThat(r).contains("~/.netrc"));
        assertThat(check(home.resolve(".config/gcloud/x"))).isPresent();
        for (String secret : List.of(".gnupg", ".aws", ".azure", ".kube", ".docker", ".password-store")) {
            assertThat(check(home.resolve(secret))).as(secret).isPresent();
        }
    }

    /**
     * A secret inside a mount is not covered with an empty tmpfs; Traceworks refuses the mount and names
     * the secret: the folder that holds {@code ~/.config/gcloud}, a folder a login links into, and a
     * folder that holds its own {@code .ssh}, such as a copy of a home.
     */
    @Test
    void mountContainingAHomeSecret_isRefused_namingIt(@TempDir Path elsewhere) throws IOException {
        Files.createDirectories(home.resolve(".config/gcloud"));
        Path keys = Files.createDirectories(elsewhere.resolve("keys/ssh"));
        Files.createSymbolicLink(home.resolve(".ssh"), keys);
        Path backup = Files.createDirectories(elsewhere.resolve("backup/operator/.aws"));

        assertThat(check(home.resolve(".config"))).hasValueSatisfying(r -> assertThat(r).contains("~/.config/gcloud"));
        assertThat(check(elsewhere.resolve("keys"))).hasValueSatisfying(r -> assertThat(r).contains("~/.ssh"));
        assertThat(check(backup.getParent())).hasValueSatisfying(r -> assertThat(r).contains(backup.toString()));
        assertThat(check(Files.createDirectories(home.resolve(".config/other")))).isEmpty();
    }

    /** A folder counts as holding a login only when the login is there: an empty ~/.config may be mounted. */
    @Test
    void folderWhereALoginWouldLie_isAllowed_whileNoLoginIsInIt() throws IOException {
        Path config = Files.createDirectories(home.resolve(".config"));

        assertThat(check(config)).isEmpty();
    }

    @Test
    void expandHome_onlyALeadingTilde() {
        Path h = Path.of("/home/me");

        assertThat(MountGuard.expandHome("~", h)).isEqualTo("/home/me");
        assertThat(MountGuard.expandHome("~/documents", h)).isEqualTo("/home/me/documents");
        assertThat(MountGuard.expandHome("~other/x", h)).isEqualTo("~other/x");
        assertThat(MountGuard.expandHome("/data/~/x", h)).isEqualTo("/data/~/x");
    }

    // --- Traceworks ---

    @Test
    void traceworksHome_isForbidden_exceptToolCacheAndOwnScratch() throws IOException {
        Path cache = Files.createDirectories(traceworksHome.resolve("cache/tools/aquasec_trivy"));
        Path otherScratch = Files.createDirectories(traceworksHome.resolve("runs/run-2/scratch"));

        assertThat(MountGuard.check(new Mount(cache.toString(), "/cache", false, true), context))
                .as("the cache folder a tool step mounts for its image").isEmpty();
        assertThat(check(scratch.toString(), "/out", false)).as("the run's own scratch").isEmpty();
        assertThat(check(Files.createDirectories(scratch.resolve("reports")).toString(), "/out", false)).isEmpty();
        assertThat(check(otherScratch.toString(), "/out", true)).as("another run's scratch")
                .hasValueSatisfying(r -> assertThat(r).contains("Traceworks home"));
        assertThat(check(scratch.resolve("..").toString(), "/out", true)).as("the run folder").isPresent();
        assertThat(check(traceworksHome.resolve("cache").toString(), "/cache", true)).isPresent();
        assertThat(check(Files.createDirectories(traceworksHome.resolve("cache/toolsx")).toString(), "/cache", true))
                .hasValueSatisfying(r -> assertThat(r).contains("Traceworks home"));
        assertThat(check(traceworksHome.getParent().toString(), "/x", true)).isPresent();
    }

    /**
     * The tool caches are kept per image, and a later run of another package executes or trusts what is in them (a
     * Maven repository, a scanner's database). So only the folder a tool step mounts for its own image
     * ({@code cache_dir}) passes: a workflow's own {@code mounts} entry names neither the cache root nor a folder in
     * it, read-only or not, directly or through a link.
     */
    @Test
    void theToolCaches_areNoMountOfAWorkflow_onlyTheCacheFolderOfAToolStep() throws IOException {
        Path root = Files.createDirectories(traceworksHome.resolve("cache/tools"));
        Path other = Files.createDirectories(root.resolve("aquasec_trivy"));
        Path link = Files.createSymbolicLink(scratch.resolve("caches"), root);

        for (Path named : List.of(root, other, link)) {
            for (boolean readOnly : List.of(true, false)) {
                assertThat(check(named.toString(), "/c", readOnly)).as(named + " readOnly=" + readOnly)
                        .hasValueSatisfying(r -> assertThat(r).contains("Traceworks home").contains("cache_dir"));
            }
        }
        assertThat(MountGuard.check(new Mount(other.toString(), "/cache", false, true), context)).isEmpty();
        assertThat(MountGuard.check(new Mount(root.toString(), "/cache", false, true), context))
                .as("the cache root is no image's folder").isPresent();
        Path otherScratch = Files.createDirectories(traceworksHome.resolve("runs/run-2/scratch"));
        assertThat(MountGuard.check(new Mount(otherScratch.toString(), "/cache", false, true), context))
                .as("a cache mount outside the tool caches is judged like any mount").isPresent();
    }

    /**
     * {@code /dev} holds the terminals of the operator's session ({@code /dev/pts}), shared memory
     * ({@code /dev/shm}) and device nodes: a step that opens one talks to the host, which read-only does not cut.
     */
    @Test
    void devAndWhatIsInIt_isRefused_onEitherSide() {
        for (String host : List.of("/dev", "/dev/pts", "/dev/shm", "/dev/null", "/tmp/../dev/pts")) {
            assertThat(check(host, "/x", true)).as(host)
                    .hasValueSatisfying(r -> assertThat(r).contains("forbidden path /dev"));
        }
        assertThat(check(outside.toString(), "/dev/shm", true)).as("on the container side")
                .hasValueSatisfying(r -> assertThat(r).contains("forbidden path /dev"));
    }

    /** A named pipe is a line to whatever process holds its other end, as a socket is: no regular file or folder. */
    @Test
    void aNamedPipe_isRefused_alsoThroughALink() throws Exception {
        Path pipe = outside.resolve("pipe");
        assertThat(new ProcessBuilder("mkfifo", pipe.toString()).inheritIO().start().waitFor()).isZero();
        Path link = Files.createSymbolicLink(outside.resolve("pipe-link"), pipe);

        assertThat(check(pipe.toString(), "/p", true)).hasValueSatisfying(r -> assertThat(r).contains("named pipe"));
        assertThat(check(link.toString(), "/p", true)).hasValueSatisfying(r -> assertThat(r).contains("named pipe"));
    }

    /**
     * A folder that directly holds a named pipe is refused like one that directly holds a socket, by the same rule
     * of one level, links not followed.
     */
    @Test
    void aFolderDirectlyHoldingANamedPipe_isRefused_namingIt() throws Exception {
        Path folder = Files.createDirectories(outside.resolve("queue/in"));
        Path pipe = folder.resolve("requests");
        assertThat(new ProcessBuilder("mkfifo", pipe.toString()).inheritIO().start().waitFor()).isZero();

        assertThat(check(folder)).hasValueSatisfying(r -> assertThat(r).contains("named pipe").contains(pipe.toString()));
        assertThat(check(outside.resolve("queue"))).as("two levels down is not looked at").isEmpty();
        Path links = Files.createDirectories(outside.resolve("pipe-links"));
        Files.createSymbolicLink(links.resolve("to-pipe"), pipe);
        assertThat(check(links)).as("a link is not followed").isEmpty();
    }

    /**
     * A writable mount of a file the host runs later (a shell's startup file, {@code ~/.local/bin}, a repository's
     * hooks) lets a step leave commands that run outside every container, as the operator. A list of such files
     * could not be complete, so every writable mount is refused unless it lies in a folder the operator lists
     * ({@code writable_mounts}); a file directly in the home has no folder that can be listed.
     */
    @Test
    void aWritableMountOfAFileTheHostRuns_isRefused() throws IOException {
        Path startup = Files.writeString(home.resolve(".bashrc"), "# the operator's\n");
        Path bin = Files.createDirectories(home.resolve(".local/bin"));
        Path hooks = Files.createDirectories(outside.resolve("repository/.git/hooks"));

        assertThat(check(startup.toString(), "/x", false)).as("~/.bashrc, writable")
                .hasValueSatisfying(reason -> assertThat(reason).contains("writable_mounts")
                        .contains("home directory").doesNotContain("\"writable_mounts\": ["));
        assertThat(check(bin.toString(), "/x", false)).as("~/.local/bin, writable")
                .hasValueSatisfying(reason -> assertThat(reason).contains("writable_mounts"));
        assertThat(MountGuard.check(new Mount(hooks.toString(), "/x", false), withRoots()))
                .as("the hooks of a repository").isPresent();
        assertThat(check(startup.toString(), "/x", true)).as("read-only").isEmpty();
    }

    /** The operator's Traceworks home may be a link: a mount is judged against its real path. */
    @Test
    void aTraceworksHomeBehindALink_stillRefusesAnotherRunsCheckoutByItsRealPath(@TempDir Path disk)
            throws IOException {
        Path realHome = Files.createDirectories(disk.resolve("tw"));
        Path linkedHome = Files.createSymbolicLink(home.resolve(".tw-link"), realHome);
        Path otherCheckout = Files.createDirectories(realHome.resolve("workspaces/run-2/src"));
        Context linked = new Context(home, linkedHome, null, null, false, null, List.of());

        assertThat(MountGuard.check(new Mount(otherCheckout.toString(), "/other", true), linked))
                .hasValueSatisfying(r -> assertThat(r).contains("Traceworks home"));
    }

    @Test
    void folderHoldingTheTraceworksHome_isRefused_alsoWithoutAScratch(@TempDir Path outside) throws IOException {
        Path tw = Files.createDirectories(outside.resolve("tw"));
        Context noScratch = new Context(home, tw, null, null, false, null, List.of());

        assertThat(MountGuard.check(new Mount(outside.toString(), "/x", true), noScratch))
                .hasValueSatisfying(r -> assertThat(r).contains("contains the Traceworks home"));
        assertThat(MountGuard.check(new Mount(tw.resolve("runs").toString(), "/x", true), noScratch)).isPresent();
    }

    @Test
    void dockerAndPodmanSockets_areForbidden_alsoUnderRunUser() {
        for (String socket : List.of("/var/run/docker.sock", "/run/docker.sock", "/var/run/podman/podman.sock",
                "/run/podman/podman.sock")) {
            assertThat(check(socket, "/sock", false)).as(socket).isPresent();
        }
        assertThat(check("/run/podman", "/x", true)).isPresent();
        for (String rootless : List.of("/run/user/1000/podman/podman.sock", "/run/user/1000/docker.sock",
                "/run/user/1000/podman", "/run/user/1000", "/run/user")) {
            assertThat(check(rootless, "/sock", false)).as(rootless)
                    .hasValueSatisfying(r -> assertThat(r).contains("forbidden path /run/user"));
        }
        assertThat(check("/run/user/1000/podman/podman.sock", "/var/run/docker.sock", false)).isPresent();
    }

    /**
     * A user's runtime directory holds the sockets of the user's session. The user's D-Bus and
     * systemd's private socket start transient units (code on the host), the GPG and SSH agents sign as the user;
     * read-only does not stop a connect. The directory and everything in it is refused, also under its other name.
     */
    @Test
    void userRuntimeDirectory_andEverythingInIt_isRefused() {
        for (String path : List.of("/run/user/1000/docker.sock", "/run/user/1000/bus",
                "/run/user/1000/gnupg/S.gpg-agent", "/run/user/1000/gcr/ssh", "/run/user/1000/systemd/private",
                "/run/user/1000/app", "/var/run/user/1000/bus")) {
            assertThat(check(path, "/x", true)).as(path)
                    .hasValueSatisfying(r -> assertThat(r).contains("forbidden path /run/user"));
        }
        assertThat(check("/data", "/run/user/1000/bus", true)).as("the container side").isPresent();
    }

    /** {@code $XDG_RUNTIME_DIR} and the SSH agent's socket are the session's, wherever they lie. */
    @Test
    void sessionPaths_areRefused_whereverTheyLie_alsoInAFolderHoldingThem(@TempDir Path tmp) throws IOException {
        Path runtimeDir = Files.createDirectories(tmp.resolve("runtime-1000"));
        Path agent = Files.createDirectories(tmp.resolve("ssh-XYZ")).resolve("agent.42");
        Context session = new Context(home, traceworksHome, null, null, false, null, List.of(runtimeDir, agent));

        for (Path path : List.of(runtimeDir, runtimeDir.resolve("bus"), tmp, agent, agent.getParent())) {
            assertThat(MountGuard.check(new Mount(path.toString(), "/x", true), session)).as(path.toString())
                    .hasValueSatisfying(r -> assertThat(r).contains("session"));
        }
        assertThat(MountGuard.check(new Mount(Files.createDirectories(tmp.resolve("cache")).toString(), "/x", true),
                session)).isEmpty();
    }

    /** Any socket is a line to a process on the host: the SSH agent's below /tmp, the X11 display. */
    @Test
    void socketFile_isRefused_alsoThroughALink(@TempDir Path tmp) throws IOException {
        Path socket = tmp.resolve("agent.sock");
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket));
            Path link = Files.createSymbolicLink(tmp.resolve("innocent"), socket);

            assertThat(check(socket)).hasValueSatisfying(r -> assertThat(r).contains("socket"));
            assertThat(check(link)).hasValueSatisfying(r -> assertThat(r).contains("socket"));
        }
        assertThat(check("/tmp/.X11-unix/X0", "/x", true)).isPresent();
        assertThat(check("/tmp", "/hosttmp", true)).as("a folder holding the X11 sockets").isPresent();
    }

    /** The AI CLIs' logins, git and package registry credentials, the data of a workflow service. */
    @Test
    void loginsOfTheAiClisAndRegistries_areRefused() {
        List<String> secrets = new ArrayList<>(List.of(".claude", ".claude.json", ".codex", ".pi", ".config/gh",
                ".git-credentials", ".npmrc", ".pypirc"));
        secrets.addAll(EarlierNames.SERVICE_HOME_SECRETS);
        for (String secret : secrets) {
            assertThat(check(home.resolve(secret))).as(secret)
                    .hasValueSatisfying(r -> assertThat(r).contains("~/" + secret));
        }
        assertThat(check(home.resolve(EarlierNames.SERVICE_DATA_DIRECTORY + "/workspaces/p/r/repo"))).isPresent();
    }

    /**
     * A repository's own {@code .claude}, {@code .npmrc} or {@code .docker} holds its settings, not a login: a folder
     * that has one is no copy of a home. Only the credential stores mark one.
     */
    @Test
    void projectSettingsOfTheSameName_doNotMarkACopyOfAHome(@TempDir Path elsewhere) throws IOException {
        Path repository = Files.createDirectories(elsewhere.resolve("repository"));
        for (String settings : List.of(".claude", ".codex", ".pi", ".docker")) {
            Files.createDirectories(repository.resolve(settings));
        }
        Files.writeString(repository.resolve(".npmrc"), "engine-strict=true");

        assertThat(check(repository)).isEmpty();
    }

    /** A container of run {@code runId} whose workspace is {@code workspace}, mounted read-only or not. */
    private ContainerRun runIn(String runId, Path workspace, boolean readOnly) {
        return new ContainerRun("traceworks-" + runId + "-1", runId, "sha256:" + "5".repeat(64), null, List.of(),
                "/workspace", List.of(new Mount(workspace.toString(), "/workspace", readOnly),
                        new Mount(scratch.toString(), "/scratch", false)),
                new RunDirectories(workspace.toString(), null, scratch.toString()), false, Map.of(), null, 60,
                ContainerLimits.DEFAULTS);
    }

    /**
     * The run's own checkout of its source ({@code <home>/workspaces/<run-id>}) is its workspace: a step's mount in
     * it follows the rules of a mount in a workspace, a writable one only with {@code workspace_writable}. Another
     * run's checkout and the rest of the home stay refused.
     */
    @Test
    void mountInTheRunsOwnCheckout_followsTheRulesOfTheWorkspace() throws IOException {
        Path checkout = Files.createDirectories(traceworksHome.resolve("workspaces/run-1"));
        Path src = Files.createDirectories(checkout.resolve("src"));
        Path otherCheckout = Files.createDirectories(traceworksHome.resolve("workspaces/run-2/src"));
        Context readOnly = Context.of(home, traceworksHome, runIn("run-1", checkout, true), List.of());
        Context writable = Context.of(home, traceworksHome, runIn("run-1", checkout, false), List.of());

        assertThat(MountGuard.check(new Mount(src.toString(), "/src", true), readOnly)).isEmpty();
        assertThat(MountGuard.check(new Mount(checkout.toString(), "/src", true), readOnly)).isEmpty();
        assertThat(MountGuard.check(new Mount(src.toString(), "/src", false), readOnly))
                .hasValueSatisfying(r -> assertThat(r).contains("workspace_writable"));
        assertThat(MountGuard.check(new Mount(src.toString(), "/src", false), writable)).isEmpty();
        assertThat(MountGuard.check(new Mount(otherCheckout.toString(), "/other", true), readOnly))
                .hasValueSatisfying(r -> assertThat(r).contains("Traceworks home"));
        assertThat(MountGuard.check(new Mount(traceworksHome.resolve("workspaces").toString(), "/all", true),
                readOnly)).hasValueSatisfying(r -> assertThat(r).contains("Traceworks home"));
        assertThat(MountGuard.check(new Mount(traceworksHome.resolve("ledger").toString(), "/l", true), readOnly))
                .isPresent();
    }

    /** A run that works in another run's checkout ({@code --workspace} pointed there) has no checkout of its own. */
    @Test
    void mountInAnotherRunsCheckout_isRefused_evenWhenItIsTheWorkspace() throws IOException {
        Path checkout = Files.createDirectories(traceworksHome.resolve("workspaces/run-1"));
        Path src = Files.createDirectories(checkout.resolve("src"));

        Context borrowed = Context.of(home, traceworksHome, runIn("run-2", checkout, true), List.of());

        assertThat(MountGuard.check(new Mount(src.toString(), "/src", true), borrowed))
                .hasValueSatisfying(r -> assertThat(r).contains("Traceworks home"));
    }

    /** A context whose workspace is mounted writable, of an operator who lists {@code roots}. */
    private Context writableWorkspace(Path... roots) {
        return new Context(home, listing(roots), scratch.toString(), workspace.toString(), true,
                packageDirectory.toString(), List.of(), null);
    }

    /**
     * With {@code workspace_writable} the step writes the workspace anyway: a writable mount of the workspace or of
     * a folder in it shows the step nothing more to write, and needs no listed folder. By the real path: a link in
     * the workspace that leads out of it is a mount of where it leads. A folder that holds the workspace is more
     * than the workspace, and the package never is writable.
     */
    @Test
    void writableMountInAWritableWorkspace_needsNoListedFolder_andOneAroundItDoes() throws IOException {
        Path repository = Files.createDirectories(outside.resolve("repositories/project"));
        Path src = Files.createDirectories(repository.resolve("src"));
        Path elsewhere = Files.createDirectories(outside.resolve("elsewhere"));
        Path out = Files.createSymbolicLink(repository.resolve("out"), elsewhere);
        Context writable = new Context(home, listing(), scratch.toString(), repository.toString(), true,
                packageDirectory.toString(), List.of(), null);

        assertThat(MountGuard.check(new Mount(src.toString(), "/data", false), writable)).as("in it").isEmpty();
        assertThat(MountGuard.check(new Mount(repository.toString(), "/data", false), writable)).as("itself")
                .isEmpty();
        assertThat(MountGuard.check(new Mount(out.toString(), "/data", false), writable)).as("a link out of it")
                .hasValueSatisfying(r -> assertThat(r).contains("writable_mounts").contains(elsewhere.toString()));
        assertThat(MountGuard.check(new Mount(repository.getParent().toString(), "/data", false), writable))
                .as("a folder that holds it").hasValueSatisfying(r -> assertThat(r).contains("writable_mounts"));
        assertThat(MountGuard.check(new Mount(packageDirectory.toString(), "/data", false),
                writableWorkspace(packageDirectory.getParent())))
                .hasValueSatisfying(r -> assertThat(r).contains("package").doesNotContain("workspace_writable"));
    }

    /** While the workspace is read-only for the step, no listed folder makes a mount of it writable. */
    @Test
    void writableMountInAReadOnlyWorkspace_isRefused_alsoInAListedFolder() throws IOException {
        Path src = Files.createDirectories(workspace.resolve("src"));

        assertThat(MountGuard.check(new Mount(src.toString(), "/data", false), withRoots(workspace)))
                .hasValueSatisfying(r -> assertThat(r).contains("workspace_writable").contains("read-only"));
    }

    /**
     * A writable workspace is mounted with its {@code .git} read-only as a whole. A writable mount of the step's
     * own that is that {@code .git}, lies in it or holds it would show the repository writable at another place, so
     * it is refused, in a listed folder too, and the refusal names the setting that opens the repository.
     */
    @Test
    void writableMountInOrAroundTheGitOfAWritableWorkspace_isRefused() throws IOException {
        Path repository = Files.createDirectories(outside.resolve("repositories/project"));
        Path src = Files.createDirectories(repository.resolve("src"));
        Path hooks = Files.createDirectories(repository.resolve(".git/hooks"));
        Path toHooks = Files.createSymbolicLink(outside.resolve("to-hooks"), hooks);
        Context writable = new Context(home, listing(outside), scratch.toString(), repository.toString(), true,
                packageDirectory.toString(), List.of(), null);

        for (Path host : List.of(repository, repository.resolve(".git"), hooks, toHooks, repository.getParent())) {
            assertThat(MountGuard.check(new Mount(host.toString(), "/data", false), writable)).as(host.toString())
                    .hasValueSatisfying(r -> assertThat(r).contains(".git").contains("read-only")
                            .contains("workspace_git_writable"));
        }
        assertThat(MountGuard.check(new Mount(src.toString(), "/data", false), writable)).as("beside it").isEmpty();
        assertThat(MountGuard.check(new Mount(hooks.toString(), "/data", true), writable)).as("read-only").isEmpty();
        Path other = Files.createDirectories(outside.resolve("repositories/other/.git/hooks"));
        assertThat(MountGuard.check(new Mount(other.toString(), "/data", false), writable))
                .as("another repository in a listed folder is the operator's word").isEmpty();
    }

    @Test
    void writableMountOfAWritableWorkspace_isRefused_whenItsGitIsAFile_andAllowedWithoutOne() throws IOException {
        Context writable = writableWorkspace();

        assertThat(MountGuard.check(new Mount(workspace.toString(), "/data", false), writable)).as("no .git")
                .isEmpty();
        Path file = Files.writeString(workspace.resolve(".git"), "gitdir: /elsewhere/.git/worktrees/project\n");
        assertThat(MountGuard.check(new Mount(workspace.toString(), "/data", false), writable)).as("a .git file")
                .hasValueSatisfying(r -> assertThat(r).contains(".git"));
        assertThat(MountGuard.check(new Mount(file.toString(), "/data", false), writable)).as("the file itself")
                .hasValueSatisfying(r -> assertThat(r).contains(".git"));
    }

    /**
     * A hard link of the {@code .git} file of a linked worktree, in a listed folder, is that {@code .git} under
     * another name and path: only the comparison by the file each names finds it, and a writable mount of it is
     * refused like one of the {@code .git} itself.
     */
    @Test
    void writableMountOfAHardLinkToTheGitFileOfAWritableWorkspace_isRefused_inAListedFolder() throws IOException {
        Path repository = Files.createDirectories(outside.resolve("repositories/project"));
        Path git = Files.writeString(repository.resolve(".git"), "gitdir: /elsewhere/.git/worktrees/project\n");
        Path listed = Files.createDirectories(outside.resolve("listed"));
        Path link = Files.createLink(listed.resolve("gitfile"), git);
        Path beside = Files.writeString(listed.resolve("other"), "x");
        Context writable = new Context(home, listing(outside), scratch.toString(), repository.toString(), true,
                packageDirectory.toString(), List.of(), null);

        assertThat(MountGuard.check(new Mount(link.toString(), "/data", false), writable)).as("the hard link")
                .hasValueSatisfying(r -> assertThat(r).contains(".git").contains("workspace_git_writable"));
        assertThat(MountGuard.check(new Mount(beside.toString(), "/data", false), writable)).as("a file beside it")
                .isEmpty();
        assertThat(MountGuard.check(new Mount(link.toString(), "/data", true), writable)).as("read-only").isEmpty();
    }

    /**
     * Where the operator opened the {@code .git} of a writable workspace ({@code workspace_git_writable}), a step
     * writes it through the workspace, so a writable mount in it is a mount in the workspace like any other: no
     * refusal for the {@code .git}, and no listed folder asked. A folder that holds the workspace still needs one.
     */
    @Test
    void writableMountInTheGitOfAWritableWorkspace_isAMountInTheWorkspace_whereTheOperatorOpenedIt()
            throws IOException {
        Path repository = Files.createDirectories(outside.resolve("repositories/project"));
        Path hooks = Files.createDirectories(repository.resolve(".git/hooks"));
        Context opened = new Context(home, listing().withWorkspaceGitWritable(true), scratch.toString(),
                repository.toString(), true, packageDirectory.toString(), List.of(), null);
        Context openedReadOnly = new Context(home, listing(outside).withWorkspaceGitWritable(true),
                scratch.toString(), repository.toString(), false, packageDirectory.toString(), List.of(), null);

        assertThat(opened.workspaceGitWritable()).isTrue();
        for (Path host : List.of(repository, repository.resolve(".git"), hooks)) {
            assertThat(MountGuard.check(new Mount(host.toString(), "/data", false), opened)).as(host.toString())
                    .isEmpty();
        }
        assertThat(MountGuard.check(new Mount(repository.getParent().toString(), "/data", false), opened))
                .as("a folder that holds the workspace").hasValueSatisfying(r -> assertThat(r)
                        .contains("writable_mounts").doesNotContain("workspace_git_writable"));
        assertThat(MountGuard.check(new Mount(hooks.toString(), "/data", false), openedReadOnly))
                .as("the setting opens nothing of a workspace the step only reads")
                .hasValueSatisfying(r -> assertThat(r).contains("workspace_writable"));
    }

    /**
     * The run's own checkout of its source is a run's data, in which Traceworks runs no git once a step could have
     * written it: its {@code .git} is writable with the rest of it, without the operator's setting, and a writable
     * mount in it is refused for nothing. A workspace of the operator's own is not such a checkout.
     */
    @Test
    void theGitOfTheRunsOwnCheckout_isWritableWithTheCheckout_withoutTheSetting() throws IOException {
        Path checkout = Files.createDirectories(traceworksHome.resolve("workspaces/run-1"));
        Path hooks = Files.createDirectories(checkout.resolve(".git/hooks"));
        Context writable = Context.of(home, traceworksHome, runIn("run-1", checkout, false), List.of());
        Context readOnly = Context.of(home, traceworksHome, runIn("run-1", checkout, true), List.of());

        assertThat(writable.workspaceGitWritable()).isTrue();
        assertThat(MountGuard.check(new Mount(hooks.toString(), "/hooks", false), writable)).isEmpty();
        assertThat(MountGuard.check(new Mount(checkout.toString(), "/again", false), writable)).isEmpty();
        assertThat(MountGuard.check(new Mount(hooks.toString(), "/hooks", false), readOnly))
                .hasValueSatisfying(r -> assertThat(r).contains("workspace_writable"));
        assertThat(writableWorkspace().workspaceGitWritable()).as("the operator's own workspace").isFalse();
        assertThat(Context.of(home, traceworksHome, runIn("run-2", workspace, false), List.of())
                .workspaceGitWritable()).as("the operator's own workspace, of a run").isFalse();
    }

    /** A writable mount of the workspace or the package would undo its read-only standard mount. */
    @Test
    void writableMountInOrAroundTheWorkspaceOrThePackage_isRefused_readOnlyIsAllowed() throws IOException {
        Path src = Files.createDirectories(workspace.resolve("src"));

        assertThat(check(workspace.toString(), "/data", false))
                .hasValueSatisfying(r -> assertThat(r).contains("workspace_writable"));
        assertThat(check(src.toString(), "/data", false)).hasValueSatisfying(r -> assertThat(r).contains("workspace"));
        assertThat(check(packageDirectory.toString(), "/data", false))
                .hasValueSatisfying(r -> assertThat(r).contains("package"));
        assertThat(check(packageDirectory.getParent().toString(), "/data", false)).as("a folder holding the package")
                .hasValueSatisfying(r -> assertThat(r).contains("package"));
        assertThat(check(src.toString(), "/data", true)).as("read-only is what the step sees anyway").isEmpty();
        assertThat(check(packageDirectory.toString(), "/data", true)).isEmpty();
    }

    // --- host paths that cannot be resolved ---

    /**
     * Fail closed: a host path whose real path cannot be resolved passes no check by default. A rootful runtime would
     * mount it anyway, and inside the container the host's parent directories no longer guard it.
     */
    @Test
    void hostPathThatCannotBeResolved_isRefused() throws IOException {
        assertThat(check(outside.resolve("missing").toString(), "/data", true))
                .hasValueSatisfying(r -> assertThat(r).startsWith("cannot be resolved: ").contains("does not exist"));

        Path locked = Files.createDirectories(outside.resolve("locked"));
        Path inner = Files.createDirectories(locked.resolve("inner"));
        assumeFalse(Integer.valueOf(0).equals(Files.getAttribute(locked, "unix:uid")), "root reads everything");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            assertThat(check(inner.toString(), "/data", true))
                    .hasValueSatisfying(r -> assertThat(r).startsWith("cannot be resolved: "));
            assertThat(check(locked.toString(), "/data", true)).as("a folder whose entries cannot be read")
                    .hasValueSatisfying(r -> assertThat(r).contains("cannot be read"));
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    /** The runtimes' storage holds the images and containers of every run; a writable mount could change them. */
    @Test
    void containerStorageRoots_areRefused_alsoAFolderHoldingThem() throws IOException {
        for (String path : List.of("/var/lib/docker", "/var/lib/docker/volumes", "/var/lib/docker/volumes/v1/_data",
                "/var/lib/docker/containers", "/var/lib/containers", "/var/lib/containers/storage", "/var/lib")) {
            assertThat(check(path, "/x", false)).as(path)
                    .hasValueSatisfying(r -> assertThat(r).contains("forbidden path /var/lib/"));
        }
        Path podman = Files.createDirectories(home.resolve(".local/share/containers/storage"));
        Path docker = Files.createDirectories(home.resolve(".local/share/docker/volumes"));
        for (Path path : List.of(podman, podman.getParent(), docker, docker.getParent(), home.resolve(".local/share"),
                home.resolve(".local"))) {
            assertThat(check(path.toString(), "/x", false)).as(path.toString())
                    .hasValueSatisfying(r -> assertThat(r).contains("storage"));
        }
    }

    /**
     * A folder that directly holds a socket hands it over: a tmux or screen socket, the ICE sockets of a desktop
     * session. One level is looked at, links are not followed.
     */
    @Test
    void folderDirectlyHoldingASocket_isRefused_namingIt() throws IOException {
        List<Path> folders = List.of(outside.resolve("tmux-1000"), outside.resolve("screen/S-operator"),
                outside.resolve(".ICE-unix"), outside.resolve("tsx-1000"));
        List<String> names = List.of("default", "1234.pts-0.host", "4242", "sock");
        List<ServerSocketChannel> servers = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < folders.size(); i++) {
                Path socket = Files.createDirectories(folders.get(i)).resolve(names.get(i));
                ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                servers.add(server);
                server.bind(UnixDomainSocketAddress.of(socket));
                assertThat(check(folders.get(i))).as(folders.get(i).toString())
                        .hasValueSatisfying(r -> assertThat(r).contains("socket").contains(socket.toString()));
            }
            assertThat(check(outside.resolve("screen"))).as("two levels down is not looked at").isEmpty();
            Path links = Files.createDirectories(outside.resolve("links"));
            Files.createSymbolicLink(links.resolve("to-tmux"), folders.getFirst().resolve("default"));
            assertThat(check(links)).as("a link is not followed: in the container it points nowhere").isEmpty();
        } finally {
            for (ServerSocketChannel server : servers) server.close();
        }
    }

    /** The runtime's own socket, from {@code DOCKER_HOST} and {@code CONTAINER_HOST}, is a session path too. */
    @Test
    void sessionPaths_includeTheRuntimesUnixSockets() {
        assertThat(MountGuard.sessionPaths(Map.of("XDG_RUNTIME_DIR", "/run/user/1000",
                "SSH_AUTH_SOCK", "/tmp/ssh-x/agent.1", "DOCKER_HOST", "unix:///srv/docker/docker.sock",
                "CONTAINER_HOST", "unix:///srv/podman/podman.sock"))).containsExactlyInAnyOrder(
                Path.of("/run/user/1000"), Path.of("/tmp/ssh-x/agent.1"), Path.of("/srv/docker/docker.sock"),
                Path.of("/srv/podman/podman.sock"));
        assertThat(MountGuard.sessionPaths(Map.of("DOCKER_HOST", "tcp://10.0.0.1:2376",
                "CONTAINER_HOST", "ssh://core@host/run/podman/podman.sock", "SSH_AUTH_SOCK", "relative"))).isEmpty();
    }

    /** Docker reads a relative container side its own way; it is refused before the runtime sees it. */
    @Test
    void relativeContainerPath_isRefused() {
        assertThat(check("/test/user/data", "workspace", true))
                .hasValueSatisfying(r -> assertThat(r).contains("container path"));
        assertThat(check("/test/user/data", "./x", true)).isPresent();
    }

    /**
     * A link followed by {@code ..} names one folder to the kernel (which follows the link first) and another one as
     * text. The runtime mounts what the kernel resolves, so that is the path that is judged, and the one handed over.
     */
    @Test
    void aLinkFollowedByDotDot_isResolvedAsTheKernelDoes_notAsText(@TempDir Path base) throws IOException {
        Path deep = Files.createDirectories(base.resolve("deep/one/two"));
        Path physical = Files.createDirectories(base.resolve("deep/one/target"));
        Path asText = Files.createDirectories(base.resolve("target"));
        Files.createSymbolicLink(base.resolve("link"), deep);
        String written = base.resolve("link") + "/../target";

        assertThat(MountGuard.real(Path.of(written))).isEqualTo(physical.toRealPath()).isNotEqualTo(asText.toRealPath());
        assertThat(MountGuard.realHostPath(written, base)).isEqualTo(physical.toRealPath().toString());
        // What does not exist yet keeps its name below the real parent, and ".." still means the parent
        assertThat(MountGuard.real(base.resolve("deep/one/missing/../new")))
                .isEqualTo(base.toRealPath().resolve("deep/one/new"));
    }

    @Test
    void aLinkFollowedByDotDot_intoAForbiddenPath_isRefused(@TempDir Path base) throws IOException {
        // As text base/l/.. is base, which is harmless; the kernel follows the link first, and that is what is mounted
        Files.createSymbolicLink(base.resolve("l"), Path.of("/proc/self"));

        assertThat(check(base.resolve("l") + "/..", "/data", true)).hasValueSatisfying(reason ->
                assertThat(reason).contains("forbidden path /proc"));
        assertThat(MountGuard.realHostPath(base.resolve("l") + "/..", home)).isEqualTo("/proc");
    }

    @Test
    void packageWithAGitDirectory_isNotMountedAgain_norItsGitDirectory() throws IOException {
        // The run mounts the package with an empty cover over .git, which the package digest leaves out; a second
        // mount of the package, of a folder holding it or of its .git would show a step what the digest does not cover
        Path git = Files.createDirectories(packageDirectory.resolve(".git/hooks"));
        Path scripts = Files.createDirectories(packageDirectory.resolve("scripts"));

        for (Path mount : List.of(git, git.getParent(), packageDirectory, packageDirectory.getParent())) {
            assertThat(check(mount)).as(mount.toString()).hasValueSatisfying(reason ->
                    assertThat(reason).contains(".git").contains("package digest"));
        }
        assertThat(check(packageDirectory + "/.", "/p", true)).isPresent();
        assertThat(check(scripts)).isEmpty();
    }

    @Test
    void packageWithoutAGitDirectory_mayBeMountedReadOnly() {
        assertThat(check(packageDirectory)).isEmpty();
    }

    @Test
    void recordsOfTheTraceworksHome_areRefused_alsoWhenTheyLieElsewhereThroughALink(@TempDir Path disk)
            throws IOException {
        // <home>/runs on another disk: the real path of a run's record no longer starts with the real home
        Path linkedHome = Files.createDirectories(home.resolve(".linked"));
        Path runs = Files.createDirectories(disk.resolve("runs"));
        Files.createSymbolicLink(linkedHome.resolve("runs"), runs);
        Path ownScratch = Files.createDirectories(runs.resolve("run-1/scratch"));
        Files.createDirectories(runs.resolve("run-2/scratch"));
        Context linked = new Context(home, linkedHome, linkedHome.resolve("runs/run-1/scratch").toString(),
                workspace.toString(), false, packageDirectory.toString(), List.of());

        for (String host : List.of(linkedHome + "/runs/run-1/scratch/..", linkedHome + "/runs/run-1/scratch/../..",
                runs.resolve("run-2/scratch").toString(), runs.toString())) {
            assertThat(MountGuard.check(new Mount(host, "/r", false), linked)).as(host).hasValueSatisfying(reason ->
                    assertThat(reason).contains("runs"));
        }
        assertThat(MountGuard.check(new Mount(ownScratch.toString(), "/s", false), linked)).isEmpty();
        assertThat(MountGuard.check(new Mount(linkedHome + "/runs/run-1/scratch", "/s", false), linked)).isEmpty();
    }

    @Test
    void placesTheOperatorSetOutsideTheHome_areRefused_butForTheRunsOwnScratch(@TempDir Path disk)
            throws IOException {
        Path runs = Files.createDirectories(disk.resolve("runs"));
        Path ledger = Files.createDirectories(disk.resolve("audit/ledger"));
        Path registers = Files.createDirectories(disk.resolve("registers/hello"));
        Path ownScratch = Files.createDirectories(runs.resolve("2026-11-14/run-1/scratch"));
        Path otherRun = Files.createDirectories(runs.resolve("2026-11-14/run-2/scratch"));
        Path beside = Files.createDirectories(disk.resolve("project"));
        HomeLayout layout = new HomeLayout(traceworksHome, runs, ledger, registers.getParent());
        Context placed = new Context(home, layout, ownScratch.toString(), workspace.toString(), false,
                packageDirectory.toString(), List.of(), null);

        for (Path host : List.of(runs, otherRun, ownScratch.getParent(), ledger, ledger.resolve(".git"), registers,
                registers.getParent(), disk)) {
            assertThat(MountGuard.check(new Mount(host.toString(), "/r", true), placed)).as(host.toString())
                    .hasValueSatisfying(reason -> assertThat(reason).contains("what runs leave behind"));
        }
        assertThat(MountGuard.check(new Mount(ownScratch.toString(), "/s", false), placed)).isEmpty();
        assertThat(MountGuard.check(new Mount(beside.toString(), "/p", true), placed)).isEmpty();
        assertThat(MountGuard.check(new Mount(traceworksHome.resolve("runs").toString(), "/old", true), placed))
                .as("the fixed folder, where earlier runs may lie").isPresent();
    }

    @Test
    void aPlaceBehindASymbolicLink_isRefusedAsAMount_byItsLinkAndByItsRealPath(@TempDir Path disk)
            throws IOException {
        Path realRuns = Files.createDirectories(disk.resolve("volume/runs"));
        Path link = Files.createSymbolicLink(disk.resolve("runs-link"), realRuns);
        Path ownScratch = Files.createDirectories(realRuns.resolve("2026-11-14/run-1/scratch"));
        Path otherRun = Files.createDirectories(realRuns.resolve("2026-11-14/run-2"));
        HomeLayout layout = new HomeLayout(traceworksHome, link, traceworksHome.resolve("ledger"),
                traceworksHome.resolve("registers"));
        Context placed = new Context(home, layout, link.resolve("2026-11-14/run-1/scratch").toString(),
                workspace.toString(), false, packageDirectory.toString(), List.of(), null);

        for (Path host : List.of(link, realRuns, otherRun, link.resolve("2026-11-14/run-2"), realRuns.getParent())) {
            assertThat(MountGuard.check(new Mount(host.toString(), "/r", true), placed)).as(host.toString())
                    .hasValueSatisfying(reason -> assertThat(reason).contains("what runs leave behind"));
        }
        assertThat(MountGuard.check(new Mount(ownScratch.toString(), "/s", false), placed)).isEmpty();
    }

    /** A step's own mount of the seal log: refused in the home as everything there, and where a link puts the log. */
    @Test
    void theSealLog_isRefusedAsAMount_inTheHome_andWhereALinkPutsIt(@TempDir Path disk) throws IOException {
        Path seals = Files.createDirectories(traceworksHome.resolve("seals"));
        assertThat(check(seals)).isPresent();
        assertThat(check(seals.resolve("events.jsonl"))).isPresent();

        Path linkedHome = Files.createDirectories(home.resolve(".linked"));
        Path real = Files.createDirectories(disk.resolve("volume/seal-log"));
        Files.createSymbolicLink(linkedHome.resolve("seals"), real);
        Context linked = new Context(home, linkedHome, null, workspace.toString(), false,
                packageDirectory.toString(), List.of());
        for (Path host : List.of(real, real.resolve("events.jsonl"), real.getParent())) {
            assertThat(MountGuard.check(new Mount(host.toString(), "/s", true), linked)).as(host.toString())
                    .hasValueSatisfying(reason -> assertThat(reason).contains("what runs leave behind"));
        }
    }

    /**
     * The guard resolves a step's own mount once and says what it resolved to: that path is the one judged, and the
     * runtime mounts it without reading the given path again, so nothing can put another folder there in between.
     */
    @Test
    void judge_ofAMountThatMayBeMounted_namesTheRealPathItJudged() throws IOException {
        Path data = Files.createDirectories(outside.resolve("data"));
        Path link = Files.createSymbolicLink(outside.resolve("link"), data);

        MountGuard.Judged judged = MountGuard.judge(new Mount(link + "/../data/.", "/data", true), context);

        assertThat(judged.refusal()).isEmpty();
        assertThat(judged.realPath()).isEqualTo(data.toRealPath().toString());
    }

    /** A refused mount has no path to mount, whatever it resolves to; and the refusal is the one {@code check} gives. */
    @Test
    void judge_ofARefusedMount_namesNoPath() throws IOException {
        Path socketHolder = Files.createSymbolicLink(outside.resolve("to-home"), home);
        Mount mount = new Mount(socketHolder.toString(), "/x", true);

        MountGuard.Judged judged = MountGuard.judge(mount, context);

        assertThat(judged.refusal()).isPresent().isEqualTo(MountGuard.check(mount, context));
        assertThat(judged.realPath()).isNull();
        assertThat(MountGuard.judge(new Mount(outside.resolve("missing").toString(), "/x", true), context).refusal())
                .hasValueSatisfying(why -> assertThat(why).startsWith("cannot be resolved: ").contains("does not exist"));
    }

    /**
     * The single resolution and the places of the layout: a mount that reaches a configured place, the registers or
     * the seal log of the home through a link is judged on the real path, refused, and hands on no path to mount.
     * A folder beside them is handed on as its real path.
     */
    @Test
    void judge_refusesThePlacesOfTheLayout_theRegistersAndTheSealLog_onTheRealPath(@TempDir Path disk)
            throws IOException {
        Path runs = Files.createDirectories(disk.resolve("runs"));
        Path ledger = Files.createDirectories(disk.resolve("audit/ledger"));
        Path registers = Files.createDirectories(disk.resolve("registers"));
        Path ownScratch = Files.createDirectories(runs.resolve("2026-11-14/run-1/scratch"));
        Path beside = Files.createDirectories(disk.resolve("project"));
        Files.createDirectories(traceworksHome.resolve("registers/hello"));
        Files.createDirectories(traceworksHome.resolve("seals"));
        Context placed = new Context(home, new HomeLayout(traceworksHome, runs, ledger, registers),
                ownScratch.toString(), workspace.toString(), false, packageDirectory.toString(), List.of(), null);
        Path links = Files.createDirectories(outside.resolve("links"));
        int n = 0;
        for (Path place : List.of(runs, ledger, registers, traceworksHome.resolve("registers"),
                traceworksHome.resolve("registers/hello"), traceworksHome.resolve("seals"))) {
            Path link = Files.createSymbolicLink(links.resolve("l" + n++), place);

            MountGuard.Judged judged = MountGuard.judge(new Mount(link.toString(), "/x", true), placed);

            // A place outside the home is named as what runs leave behind, one in the home as part of the home
            assertThat(judged.refusal()).as(place.toString()).hasValueSatisfying(reason -> assertThat(reason)
                    .containsAnyOf("what runs leave behind", "it lies in the Traceworks home"));
            assertThat(judged.realPath()).as(place.toString()).isNull();
        }
        Path toBeside = Files.createSymbolicLink(links.resolve("beside"), beside);
        MountGuard.Judged allowed = MountGuard.judge(new Mount(toBeside.toString(), "/p", true), placed);
        assertThat(allowed.refusal()).isEmpty();
        assertThat(allowed.realPath()).isEqualTo(beside.toRealPath().toString());
    }

    // --- the files the seal settings name: allowed_signers and signing_key, where the operator put them ---

    private Context withSealFiles(Path signers, Path key) {
        HomeLayout layout = listing(outside).withSealFiles(key, signers);
        return new Context(home, layout, scratch.toString(), workspace.toString(), false, packageDirectory.toString(),
                List.of(), null);
    }

    /**
     * A step that can write the allowed signers file can allow a key of its own, and one that can read the signing
     * key can seal. So a step's mount that is either file, or a folder that holds it, is refused, read-only too, by
     * the real paths; a folder beside them is not.
     */
    @Test
    void aMountThatIsOrHoldsTheConfiguredSignersFileOrSigningKey_isRefused_andNamesTheSetting() throws IOException {
        Path trust = Files.createDirectories(outside.resolve("trust/keys"));
        Path signers = Files.writeString(trust.resolve("allowed_signers"), "x");
        Path key = Files.writeString(Files.createDirectories(outside.resolve("keyring")).resolve("seal"), "k");
        Path beside = Files.createDirectories(outside.resolve("trust/docs"));
        Path link = Files.createSymbolicLink(outside.resolve("to-trust"), trust);
        Context guarded = withSealFiles(signers, key);

        for (Path host : List.of(signers, trust, trust.getParent(), link, link.resolve("allowed_signers"))) {
            for (boolean readOnly : List.of(true, false)) {
                MountGuard.Judged judged = MountGuard.judge(new Mount(host.toString(), "/x", readOnly), guarded);
                assertThat(judged.refusal()).as(host + " " + readOnly).hasValueSatisfying(reason -> assertThat(reason)
                        .contains("'allowed_signers'").contains(signers.toString()));
                assertThat(judged.realPath()).isNull();
            }
        }
        assertThat(MountGuard.check(new Mount(key.toString(), "/k", true), guarded))
                .hasValueSatisfying(reason -> assertThat(reason).contains("'signing_key'"));
        assertThat(MountGuard.check(new Mount(beside.toString(), "/d", false), guarded)).isEmpty();
        assertThat(MountGuard.check(new Mount(trust.toString(), "/x", true), context)).as("without the settings").isEmpty();
    }

    /**
     * A signers file that is configured and does not exist yet is guarded as well: a step that creates it allows a
     * key. And where the setting names a link, the folder that holds the link is guarded like the one of its target.
     */
    @Test
    void aSignersFileThatDoesNotExistYet_andOneNamedThroughALink_areGuardedToo() throws IOException {
        Path future = Files.createDirectories(outside.resolve("later"));
        Path target = Files.createDirectories(outside.resolve("real-trust"));
        Files.writeString(target.resolve("allowed_signers"), "x");
        Path linkFolder = Files.createDirectories(outside.resolve("config"));
        Path named = Files.createSymbolicLink(linkFolder.resolve("signers"), target.resolve("allowed_signers"));

        assertThat(MountGuard.check(new Mount(future.toString(), "/x", false),
                withSealFiles(future.resolve("allowed_signers"), null))).hasValueSatisfying(SIGNERS_REASON);
        Context linked = withSealFiles(named, null);
        assertThat(MountGuard.check(new Mount(linkFolder.toString(), "/x", false), linked)).as("the link")
                .hasValueSatisfying(SIGNERS_REASON);
        assertThat(MountGuard.check(new Mount(target.toString(), "/x", false), linked)).as("its target")
                .hasValueSatisfying(SIGNERS_REASON);
    }

    private static final java.util.function.Consumer<String> SIGNERS_REASON = reason -> assertThat(reason)
            .contains("'allowed_signers'").contains("could allow a key of its own");

    /**
     * A directory on the way to the file is a link: who can write the folder that holds that link can point it at
     * another folder, with a signers file of their own. The folder of every link on the way is guarded.
     */
    @Test
    void aSealFileBehindADirectoryLink_guardsTheFolderOfThatLink() throws IOException {
        Path keys = Files.createDirectories(outside.resolve("volume/keys"));
        Files.writeString(keys.resolve("allowed_signers"), "x");
        Path cfg = Files.createDirectories(outside.resolve("cfg"));
        Files.createSymbolicLink(cfg.resolve("keys"), keys);
        Context guarded = withSealFiles(cfg.resolve("keys/allowed_signers"), null);

        for (Path host : List.of(cfg, keys, keys.getParent())) {
            assertThat(MountGuard.check(new Mount(host.toString(), "/x", false), guarded)).as(host.toString())
                    .hasValueSatisfying(SIGNERS_REASON);
        }
        assertThat(MountGuard.checkRunDirectory(cfg, home, guarded.layout(), List.of()).refusal())
                .as("as a workspace").isNotNull().contains("'allowed_signers'");
        assertThat(MountGuard.check(new Mount(Files.createDirectories(outside.resolve("free")).toString(), "/x", false),
                guarded)).isEmpty();
    }

    /** A final link whose target does not exist yet: the folder the target would be created in is guarded. */
    @Test
    void aSealFileThatIsALinkToAFileThatDoesNotExistYet_guardsTheFolderOfItsTarget() throws IOException {
        Path later = Files.createDirectories(outside.resolve("later"));
        Path cfg = Files.createDirectories(outside.resolve("cfg"));
        Path named = Files.createSymbolicLink(cfg.resolve("signers"), later.resolve("allowed_signers"));
        Context guarded = withSealFiles(named, null);

        for (Path host : List.of(later, cfg)) {
            assertThat(MountGuard.check(new Mount(host.toString(), "/x", false), guarded)).as(host.toString())
                    .hasValueSatisfying(SIGNERS_REASON);
        }
    }

    /** A chain of links: the folder of every hop is guarded, and so is the file at its end. */
    @Test
    void aSealFileNamedThroughAChainOfLinks_guardsTheFolderOfEveryHop() throws IOException {
        Path end = Files.createDirectories(outside.resolve("end"));
        Files.writeString(end.resolve("allowed_signers"), "x");
        Path mid = Files.createDirectories(outside.resolve("mid"));
        Files.createSymbolicLink(mid.resolve("hop"), Path.of("../end/allowed_signers"));
        Path cfg = Files.createDirectories(outside.resolve("cfg"));
        Path named = Files.createSymbolicLink(cfg.resolve("signers"), mid.resolve("hop"));
        Context guarded = withSealFiles(named, null);

        for (Path host : List.of(cfg, mid, end)) {
            assertThat(MountGuard.check(new Mount(host.toString(), "/x", false), guarded)).as(host.toString())
                    .hasValueSatisfying(SIGNERS_REASON);
        }
    }

    /** Links in a circle end the walk; what was met until then stays guarded, and nothing runs without end. */
    @Test
    void aSealFileNamedThroughLinksInACircle_guardsWhatWasMet() throws IOException {
        Path cfg = Files.createDirectories(outside.resolve("cfg"));
        Files.createSymbolicLink(cfg.resolve("a"), cfg.resolve("b"));
        Files.createSymbolicLink(cfg.resolve("b"), cfg.resolve("a"));

        assertThat(MountGuard.check(new Mount(cfg.toString(), "/x", false), withSealFiles(cfg.resolve("a"), null)))
                .hasValueSatisfying(SIGNERS_REASON);
    }

    /** A mount of the home directory gets the message of the home, also when a seal file lies below it. */
    @Test
    void aMountOfTheHomeDirectory_isRefusedAsTheHome_alsoWhenItHoldsASealFile() throws IOException {
        Path signers = Files.writeString(Files.createDirectories(home.resolve("trust")).resolve("allowed_signers"), "x");

        assertThat(MountGuard.check(new Mount(home.toString(), "/x", true), withSealFiles(signers, null)))
                .hasValueSatisfying(reason -> assertThat(reason).doesNotContain("'allowed_signers'"));
    }

    /** A workspace and a package that hold either file are refused before the run starts, as a mount is. */
    @Test
    void aWorkspaceOrAPackageThatHoldsAConfiguredSealFile_isRefused() throws IOException {
        Path project = Files.createDirectories(outside.resolve("project"));
        Path signers = Files.writeString(project.resolve("allowed_signers"), "x");
        Path other = Files.createDirectories(outside.resolve("other"));
        HomeLayout layout = HomeLayout.of(traceworksHome).withSealFiles(null, signers);

        for (traceworks.core.port.HostDirectories.Role role : traceworks.core.port.HostDirectories.Role.values()) {
            traceworks.core.port.HostDirectories.Checked checked = MountGuard.checkRunDirectory(project, home, layout, List.of(), role);
            assertThat(checked.refusal()).as(role.name()).isNotNull().contains("'allowed_signers'")
                    .contains(signers.toString());
        }
        assertThat(MountGuard.checkRunDirectory(other, home, layout, List.of()).refusal()).isNull();
    }
}
