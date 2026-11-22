package traceworks.cli;

import traceworks.adapters.config.Projects;
import traceworks.adapters.record.FileRunStore;
import traceworks.core.app.Credentials;
import traceworks.core.app.Findings;
import traceworks.core.app.Images;
import traceworks.core.app.RunServices;
import traceworks.core.app.Seals;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * What a command works with: its streams, the environment, the working directory, and the use cases of the chosen
 * home. Nothing here reads the real environment or console; {@link Main#main} passes them in.
 *
 * @param console  the standard input of a terminal a person types on; {@code null} when there is none
 * @param homeFlag the value of {@code --home}; {@code null} when not given
 * @param runtimes where the container runtime comes from: the machine, or a test's
 * @param secrets  reads a secret value a person gives; {@code null} when there is no input to read one from
 * @param stdin    the standard input of the process as it is, a terminal or not, for the one command that reads
 *                 messages from it ({@code mcp}); {@code null} when there is none
 */
public record Context(PrintStream out, PrintStream err, Map<String, String> env, Path workingDirectory,
                      InputStream console, String homeFlag, RuntimeSource runtimes, SecretReader secrets,
                      InputStream stdin) {

    public Context {
        runtimes = runtimes == null ? RuntimeSource.MACHINE : runtimes;
    }

    /** A context of a command that reads no messages from the standard input. */
    public Context(PrintStream out, PrintStream err, Map<String, String> env, Path workingDirectory,
                   InputStream console, String homeFlag, RuntimeSource runtimes, SecretReader secrets) {
        this(out, err, env, workingDirectory, console, homeFlag, runtimes, secrets, null);
    }

    /** The home of this context: {@code --home}, else {@code TRACEWORKS_HOME}, else {@code ~/.traceworks}. */
    public Path home() {
        return Wiring.home(homeFlag, env, workingDirectory);
    }

    /** A context that finds its container runtime on the machine. */
    public Context(PrintStream out, PrintStream err, Map<String, String> env, Path workingDirectory,
                   InputStream console, String homeFlag) {
        this(out, err, env, workingDirectory, console, homeFlag, RuntimeSource.MACHINE, null);
    }

    /** The findings use case: the registers of this context's home. */
    public Findings findings() {
        return services().findings();
    }

    /** The projects this context's home declares: the folders its operator bound to project keys. */
    public Projects projects() {
        return Wiring.projects(Wiring.home(homeFlag, env, workingDirectory));
    }

    /** The seal use case: the seals of this context's home. */
    public Seals seals() {
        return services().seals();
    }

    /**
     * Whether a run started here counts as attended for the operator's {@code require_seal: unattended}: there is a
     * terminal, whether or not the run may ask on it, or the command line carries the operator's flag.
     */
    public boolean attended(boolean allowUnsealed) {
        return console != null || allowUnsealed;
    }

    /** The use case of the stored credentials of this context's home. It touches no file until it is used. */
    public Credentials credentials() {
        return Wiring.credentials(Wiring.home(homeFlag, env, workingDirectory));
    }

    /**
     * The use case of the images, on the container runtime of this context.
     *
     * @throws traceworks.core.app.RunRefusedException if there is no container runtime
     */
    public Images images() {
        return Wiring.images(Wiring.home(homeFlag, env, workingDirectory), runtimes);
    }

    /** What the home lets a server change: any of {@code run}, {@code answer}, {@code decide} (docs/cli.md, "mcp"). */
    public Set<String> mcpAllowed() {
        return Wiring.mcpAllowed(home());
    }

    /** The use cases, built on first use so that {@code --help} and usage errors touch no files. */
    public RunServices services() {
        return Wiring.services(Wiring.home(homeFlag, env, workingDirectory), env, runtimes);
    }

    /**
     * The folder a run reference names when it is written as a path, as an absolute path; {@code null} for an id or
     * a prefix, which names a run of the home. A command given a folder reads that folder and names it in its hints:
     * the id in the folder's record may be unknown to the home, or be another run of the home.
     */
    public String runFolder(String runRef) {
        return runRef != null && FileRunStore.writtenAsPath(runRef)
                ? workingDirectory.resolve(runRef).toString() : null;
    }

    /** A path as the person meant it: relative to the working directory. */
    public Path resolve(String path) {
        return workingDirectory.resolve(path).normalize();
    }

    /** Whether questions can be asked here: there is a terminal and the person did not switch it off. */
    public boolean interactive(boolean noInput) {
        return console != null && !noInput;
    }
}
