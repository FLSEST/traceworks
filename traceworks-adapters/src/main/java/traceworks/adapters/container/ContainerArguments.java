package traceworks.adapters.container;

import traceworks.adapters.host.BashScript;
import traceworks.core.port.CommandRunner.Mount;
import traceworks.core.port.ContainerLimits;
import traceworks.core.port.ContainerRuntime;
import traceworks.core.port.ContainerRuntime.ContainerRun;
import traceworks.core.port.Egress;
import traceworks.core.port.ReservedVariables;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * The command line that starts one container, after the client's executable. The secure defaults are
 * fixed here, in this order, and nothing in a run removes them:
 *
 * <pre>
 * run --rm --init --pull never --name &lt;name&gt; --label traceworks.run=&lt;run id&gt;
 * --user &lt;uid&gt;:&lt;gid&gt; --log-driver none [--userns keep-id] --read-only --tmpfs /tmp:rw,exec,nosuid,nodev,size=1g
 * --cap-drop ALL --security-opt no-new-privileges --memory &lt;m&gt; --pids-limit &lt;p&gt; --cpus &lt;c&gt;
 * [--workdir &lt;dir&gt;]
 * [--network none | --network &lt;name&gt;-net] --mount type=bind,src=&lt;real path&gt;,dst=&lt;path&gt;[,readonly]...
 * -e &lt;NAME&gt;... -e HOME=/tmp -e TMPDIR=/tmp -e LANG=C.UTF-8 -e TINI_KILL_PROCESS_GROUP=1
 * [-e HTTPS_PROXY=http://&lt;name&gt;-egress:3128 -e HTTP_PROXY=... -e https_proxy=... -e http_proxy=...]
 * [-i] [--entrypoint &lt;entrypoint&gt;] &lt;image id&gt; &lt;args&gt;...
 * </pre>
 *
 * <ul>
 *   <li><b>Podman</b> also maps the operator's uid to itself ({@code --userns keep-id}), so what a step writes to a
 *       mount belongs to the operator, and gets the image ID as the bare hex it reports.</li>
 *   <li><b>No daemon log:</b> {@code --log-driver none}. Traceworks reads a step's output from the attached streams of
 *       the client and never through {@code logs}, so the runtime has no use for a log. Without the driver the daemon
 *       keeps a second copy of everything the step printed, unredacted, until the container is removed, and a step that
 *       prints without end also pays for writing it.</li>
 *   <li><b>Network:</b> {@code --network none} is left out only for a step with network; it then has the runtime's
 *       default network.</li>
 *   <li><b>Listed hosts</b> ({@link Egress#hosts}, an AI call): the container joins only the run's network
 *       without a way out, {@code traceworks-<run id>-net} ({@link #internalNetwork}), and its clients are pointed
 *       at the egress proxy {@code <name>-egress} on it ({@link #proxy}), which is also on
 *       {@code traceworks-<run id>-out} and connects to the listed
 *       hosts only. A step cannot set a proxy variable itself: those names are reserved.</li>
 *   <li><b>Environment:</b> the command line names each variable ({@code -e NAME}); its value goes into the client's
 *       own environment ({@link Invocation#environment()}), where the client takes it from, so no value is ever on a
 *       command line. A name the client reads itself is refused ({@link ReservedVariables}).</li>
 *   <li><b>A shell command</b> ({@code /bin/bash -c <script>}) goes on stdin with {@link BashScript#RUNNER} as the
 *       argument, so the script is never on the client's command line, which every process on the machine can read.
 *       A run that brings its own stdin keeps the script as the argument; it must then fit one argument
 *       ({@value BashScript#ARGUMENT_LIMIT_BYTES} bytes), or the run is refused. Tool-mode arguments stay arguments.</li>
 *   <li><b>SIGTERM</b> on a timeout or cancel: the client hands it to the container's init ({@code --init}, tini),
 *       which sends it to the whole process group of the command with {@code TINI_KILL_PROCESS_GROUP=1}, so the
 *       running command gets it and its own TERM trap runs ({@link BashScript}).</li>
 *   <li><b>Refused</b> with an {@link IllegalArgumentException}, before anything starts: a value that would reach the
 *       client as an option (an image, name, working directory, entrypoint or user that starts with {@code -}), an
 *       image that is not a local image ID, a mount path that is not absolute or holds a comma, a quote or a line
 *       break ({@code --mount} would split or misread it), and an environment name that is no variable name.</li>
 * </ul>
 */
final class ContainerArguments {

    /** The label every container of a run carries, with the run ID as its value. */
    static final String RUN_LABEL = ContainerRuntime.RUN_LABEL;

    /** The only writable place in the container besides its writable mounts. */
    static final String TMP = "/tmp:rw,exec,nosuid,nodev,size=1g";
    /** The options of the tmpfs that covers the .git directory of the package: empty, and nothing can be put there. */
    private static final String GIT_COVER = "/.git:ro,size=4k,mode=555";

    /** The shell a shell command runs in ({@code core.container.ContainerCommandRunner}). */
    static final String SHELL = "/bin/bash";

    /** The port the egress proxy listens on, as its image has it. */
    static final int PROXY_PORT = 3128;

    /**
     * What marks a target of the egress proxy as one it also forwards a plain HTTP request to
     * ({@link Egress#plain()}), as the proxy's image reads its arguments.
     */
    static final String PLAIN_TARGET = "http://";

    /** The variables that point a container's clients at the egress proxy, in the order they are set. */
    static final List<String> PROXY_VARIABLES = List.of("HTTPS_PROXY", "HTTP_PROXY", "https_proxy", "http_proxy");

    /**
     * The option that leaves the host without an address on an internal network, so a container on it reaches no
     * service of the host either. Docker knows it from version 28.
     */
    static final String ISOLATED_GATEWAY = "com.docker.network.bridge.gateway_mode_ipv4=isolated";

    private static final Pattern IMAGE_ID = Pattern.compile("sha256:([0-9a-f]{64})");
    private static final Pattern VARIABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");
    private static final Pattern USER = Pattern.compile("[0-9]+:[0-9]+");

    /** The client the command line is for. */
    enum Client { DOCKER, PODMAN }

    /**
     * What starts the container.
     *
     * @param arguments   the client's arguments, after its executable
     * @param environment the variables the client gets on top of its own, whose values the container takes
     *                    ({@code -e NAME})
     * @param stdin       what the client gets on stdin; {@code null} closes it
     */
    record Invocation(List<String> arguments, Map<String, String> environment, String stdin) {
        Invocation {
            arguments = List.copyOf(arguments);
            environment = Map.copyOf(environment);
        }

        /** The invocation with the names of its environment variables, never their values. */
        @Override
        public String toString() {
            return "Invocation[arguments=" + arguments + ", environment="
                    + new java.util.TreeSet<>(environment.keySet()) + "]";
        }
    }

    private ContainerArguments() {
    }

    /**
     * The command line that starts {@code run}.
     *
     * @param user     {@code <uid>:<gid>}, the owner of the Traceworks home
     * @param declaredRealPath the host side of a mount the step declared as the runtime mounts it (its real path);
     *                         the run's own directories are resolved by {@link MountGuard#realRunDirectory}
     * @throws IllegalArgumentException for what the command line must not carry (see the class documentation)
     */
    static Invocation of(Client client, ContainerRun run, String user, UnaryOperator<String> declaredRealPath) {
        List<String> paths = new ArrayList<>();
        for (Mount mount : run.declaredMounts()) {
            paths.add(declaredRealPath.apply(mount.hostPath()));
        }
        return of(client, run, user, paths);
    }

    /**
     * The command line that starts {@code run}, with the host side of each mount the step declared as it was judged.
     *
     * @param declaredRealPaths the real path of each of {@link ContainerRun#declaredMounts()}, in their order: the
     *                          path the mount guard judged, which is mounted as it is and not resolved again
     * @throws IllegalArgumentException for what the command line must not carry, and for a declared mount without a
     *                                  judged path: a mount the guard did not judge is never mounted as given
     */
    static Invocation of(Client client, ContainerRun run, String user, List<String> declaredRealPaths) {
        if (declaredRealPaths.size() != run.declaredMounts().size() || declaredRealPaths.contains(null)) {
            throw new IllegalArgumentException("A mount of the step was not judged, so the container is not started");
        }
        if (user == null || !USER.matcher(user).matches()) {
            throw new IllegalArgumentException("The container user '" + user + "' is not <uid>:<gid>");
        }
        String image = image(client, run.imageId());
        List<String> arguments = new ArrayList<>(List.of("run", "--rm", "--init", "--pull", "never",
                "--name", checked("name", run.name(), NAME), "--label", RUN_LABEL + "=" + run.runId(),
                "--user", user, "--log-driver", "none"));
        if (client == Client.PODMAN) {
            arguments.addAll(List.of("--userns", "keep-id"));
        }
        ContainerLimits limits = run.limits();
        arguments.addAll(List.of("--read-only", "--tmpfs", TMP, "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges", "--memory", limits.memory(),
                "--pids-limit", Integer.toString(limits.pids()), "--cpus", limits.cpus()));
        if (run.workingDirectory() != null) {
            arguments.addAll(List.of("--workdir", absolute("working directory", run.workingDirectory())));
        }
        switch (run.network().mode()) {
            case NONE -> arguments.addAll(List.of("--network", "none"));
            case HOSTS -> arguments.addAll(List.of("--network", internalNetworkName(run)));
            case OPEN -> { }
        }
        // The run's own mounts come first and are read exactly as named; only a step's own mount is read as written
        int own = run.mounts().size() - run.declaredMounts().size();
        for (int i = 0; i < run.mounts().size(); i++) {
            Mount mount = run.mounts().get(i);
            String source = i < own ? MountGuard.realRunDirectory(mount.hostPath()) : declaredRealPaths.get(i - own);
            arguments.addAll(List.of("--mount", bind(mount, source)));
            if (isPackageWithGitDirectory(mount, run, source)) {
                arguments.addAll(List.of("--tmpfs", mountPath("container", mount.containerPath()) + GIT_COVER));
            }
        }
        for (String name : new TreeSet<>(run.environment().keySet())) {
            arguments.addAll(List.of("-e", variable(name)));
        }
        arguments.addAll(List.of("-e", "HOME=/tmp", "-e", "TMPDIR=/tmp", "-e", "LANG=C.UTF-8",
                "-e", "TINI_KILL_PROCESS_GROUP=1"));
        if (run.network().restricted()) {
            String proxy = "http://" + proxyName(run) + ":" + PROXY_PORT;
            PROXY_VARIABLES.forEach(name -> arguments.addAll(List.of("-e", name + "=" + proxy)));
        }

        List<String> args = run.args();
        String stdin = run.stdin();
        String script = script(run);
        if (script != null && stdin == null) {
            args = List.of("-c", BashScript.RUNNER);
            stdin = BashScript.stdin(script);
        } else if (script != null && BashScript.viaStdin(script)) {
            throw new IllegalArgumentException("The command is over 128 KiB, so it would go on stdin, but the"
                    + " step brings its own stdin; shorten the command");
        }
        if (stdin != null) {
            arguments.add("-i");
        }
        if (run.entrypoint() != null) {
            arguments.addAll(List.of("--entrypoint", notAnOption("entrypoint", run.entrypoint())));
        }
        arguments.add(image);
        arguments.addAll(args);
        return new Invocation(arguments, run.environment(), stdin);
    }

    /**
     * The name of the network that is the only one of a container with listed hosts:
     * {@code traceworks-<run id>-net}. It is the run's, not the container's: every such container of a run joins the
     * same one, so a run with a hundred AI calls makes two networks on the host, not two hundred.
     */
    static String internalNetworkName(ContainerRun run) {
        return runNetworkName(run.runId()) + "-net";
    }

    /** The name of the network the egress proxy reaches the listed hosts through: {@code traceworks-<run id>-out}. */
    static String outsideNetworkName(ContainerRun run) {
        return runNetworkName(run.runId()) + "-out";
    }

    /** The names of the two networks of run {@code runId}, the internal one first. */
    static List<String> runNetworkNames(String runId) {
        return List.of(runNetworkName(runId) + "-net", runNetworkName(runId) + "-out");
    }

    private static String runNetworkName(String runId) {
        return checked("name", "traceworks-" + runId, NAME);
    }

    /** The name of the egress proxy's container: {@code <name>-egress}. */
    static String proxyName(ContainerRun run) {
        return checked("name", run.name(), NAME) + "-egress";
    }

    /**
     * The command line that creates the step's network, which has no way out, with the run's label.
     *
     * @param isolatedGateway whether the runtime can leave the host without an address on it
     *                        ({@value #ISOLATED_GATEWAY}); only Docker is asked to
     */
    static List<String> internalNetwork(Client client, ContainerRun run, boolean isolatedGateway) {
        List<String> arguments = new ArrayList<>(List.of("network", "create", "--internal",
                "--label", RUN_LABEL + "=" + run.runId()));
        if (client == Client.DOCKER && isolatedGateway) {
            arguments.addAll(List.of("-o", ISOLATED_GATEWAY));
        }
        arguments.add(internalNetworkName(run));
        return arguments;
    }

    /** The command line that creates the network the proxy reaches the listed hosts through, with the run's label. */
    static List<String> outsideNetwork(ContainerRun run) {
        return List.of("network", "create", "--label", RUN_LABEL + "=" + run.runId(), outsideNetworkName(run));
    }

    /**
     * The command line that starts the egress proxy of {@code run}, detached: the secure defaults of every container
     * (read-only, no capabilities, no new privileges, the user, the limits, the run's label), both networks, no mount
     * and no variable of the step, and the listed hosts as its arguments, a host that also takes plain HTTP as
     * {@code http://host:port}. Unlike a step's container it keeps the
     * runtime's log, which holds only the proxy's lines and is read with {@code logs}, and it has no {@code --rm}:
     * Traceworks reads the log and then removes it. Podman takes several networks as one list.
     *
     * @throws IllegalArgumentException for a user that is not {@code <uid>:<gid>}, an image of the proxy that is not
     *                                  a local image ID, or a network that has no listed hosts
     */
    static List<String> proxy(Client client, ContainerRun run, String user) {
        if (user == null || !USER.matcher(user).matches()) {
            throw new IllegalArgumentException("The container user '" + user + "' is not <uid>:<gid>");
        }
        if (!run.network().restricted()) {
            throw new IllegalArgumentException("Only a container with listed hosts has an egress proxy");
        }
        String image = image(client, run.egressImageId());
        List<String> arguments = new ArrayList<>(List.of("run", "-d", "--init", "--pull", "never",
                "--name", proxyName(run), "--label", RUN_LABEL + "=" + run.runId(), "--user", user));
        if (client == Client.PODMAN) {
            arguments.addAll(List.of("--userns", "keep-id"));
        }
        ContainerLimits limits = run.limits();
        arguments.addAll(List.of("--read-only", "--tmpfs", TMP, "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges", "--memory", limits.memory(),
                "--pids-limit", Integer.toString(limits.pids()), "--cpus", limits.cpus()));
        if (client == Client.PODMAN) {
            arguments.addAll(List.of("--network", outsideNetworkName(run) + "," + internalNetworkName(run)));
        } else {
            arguments.addAll(List.of("--network", outsideNetworkName(run), "--network", internalNetworkName(run)));
        }
        arguments.addAll(List.of("-e", "HOME=/tmp", "-e", "TMPDIR=/tmp", "-e", "LANG=C.UTF-8",
                "-e", "TINI_KILL_PROCESS_GROUP=1"));
        arguments.add(image);
        for (String host : run.network().hosts()) {
            // A model server of the operator's that speaks http: the proxy also forwards a plain request to it
            arguments.add(run.network().plain().contains(host) ? PLAIN_TARGET + host : host);
        }
        return arguments;
    }

    /** The script of a shell command ({@code /bin/bash -c <script>}); {@code null} for anything else. */
    private static String script(ContainerRun run) {
        return SHELL.equals(run.entrypoint()) && run.args().size() == 2 && "-c".equals(run.args().get(0))
                ? run.args().get(1) : null;
    }

    /** The image as the client takes it: a local image ID, bare hex for Podman. */
    private static String image(Client client, String imageId) {
        var id = imageId == null ? null : IMAGE_ID.matcher(imageId);
        if (id == null || !id.matches()) {
            throw new IllegalArgumentException("The container image '" + imageId + "' is not a local image ID"
                    + " (sha256:<64 hex digits>)");
        }
        return client == Client.PODMAN ? id.group(1) : imageId;
    }

    /**
     * Whether {@code mount} shows the run's package and the package has a top-level {@code .git} directory. The
     * package digest leaves that directory out (git rewrites it without any file of the package changing), so a
     * container must not see what is in it: a script the digest covers could run a payload from there that the digest
     * does not cover. The directory is covered with an empty, read-only tmpfs. A {@code .git} file is hashed like any
     * other file and stays.
     */
    private static boolean isPackageWithGitDirectory(Mount mount, ContainerRun run, String source) {
        String pkg = run.directories() == null ? null : run.directories().packageDirectory();
        return pkg != null && pkg.equals(mount.hostPath())
                && Files.isDirectory(Path.of(source, ".git"), LinkOption.NOFOLLOW_LINKS);
    }

    /** @param source the host side as it is mounted: the real path that was judged */
    private static String bind(Mount mount, String source) {
        return "type=bind,src=" + mountPath("host", source) + ",dst=" + mountPath("container", mount.containerPath())
                + (mount.readOnly() ? ",readonly" : "");
    }

    /** {@code --mount} splits its value at commas and reads quotes; such a path is refused rather than mis-mounted. */
    private static String mountPath(String side, String path) {
        if (path.indexOf(',') >= 0 || path.indexOf('"') >= 0 || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Cannot mount a path with a comma, quote or line break: " + side
                    + " path " + path);
        }
        return absolute(side + " path of a mount", path);
    }

    private static String variable(String name) {
        if (!VARIABLE.matcher(name).matches()) {
            throw new IllegalArgumentException("'" + name + "' is not a name for an environment variable");
        }
        if (ReservedVariables.reserved(name)) {
            throw new IllegalArgumentException("The container variable " + name + " is refused: the container"
                    + " runtime's client reads it itself");
        }
        return name;
    }

    private static String absolute(String what, String path) {
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("The " + what + " '" + path + "' is not an absolute path");
        }
        return path;
    }

    private static String notAnOption(String what, String value) {
        if (value.isBlank() || value.startsWith("-")) {
            throw new IllegalArgumentException("The " + what + " '" + value + "' is blank or starts with '-'");
        }
        return value;
    }

    private static String checked(String what, String value, Pattern pattern) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("The container " + what + " '" + value + "' is not valid");
        }
        return value;
    }
}
