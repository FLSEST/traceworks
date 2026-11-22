package traceworks.cli;

import traceworks.adapters.config.Projects;
import traceworks.cli.Args.Parsed;
import traceworks.core.app.RunRefusedException;
import traceworks.core.model.ProjectKey;
import traceworks.core.util.Message;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The project of a command. This is the one place of the command line that resolves it, from its two sources, both
 * the operator's word: {@code --project KEY}, and the projects of the home ({@code projects} in
 * {@code traceworks.json}, {@link Projects}), which bind a key to a folder, so that the folder a run works in names
 * its project. The key never comes from the workspace itself: a repository that could name its own project could
 * take over the decisions made for another.
 */
public final class ProjectOption {

    /** The name of the option. */
    public static final String NAME = "project";

    /** What the option takes, for a usage text. */
    public static final String VALUE = "KEY";

    private ProjectOption() {
    }

    /**
     * @return the key the command line names; {@code null} when it names none
     * @throws UsageException if the key is no project key ({@link ProjectKey})
     */
    public static String of(Parsed args) {
        String key = args.option(NAME);
        if (key != null && !ProjectKey.matches(key)) {
            throw new UsageException(Message.of("--project takes a project key, which must match "
                    + ProjectKey.PATTERN + ", got '", Message.typed(key), "'"));
        }
        return key;
    }

    /**
     * The project of a run that works in {@code folder}:
     * <ul>
     *   <li>without {@code --project}, the declared project the folder lies in, and none when it lies in no
     *       declared folder;</li>
     *   <li>with {@code --project P}, {@code P}: when the folder lies in the folder of {@code P}, or when the
     *       folder lies in no declared folder and nobody declared {@code P}, which is the operator's word alone.</li>
     * </ul>
     * A run with another key than its folder says would mix two projects, so it is refused: when the folder is of
     * another project, and when {@code P} is declared with a folder the run does not work in. The record does not
     * say which of the two sources named the key.
     *
     * @param folder the folder the run works in: its workspace, or the repository a {@code --source} names when
     *               it names a path of this machine (a path, or a {@code file:} URL); {@code null} for a run that
     *               works in a checkout of a source that is no local path, which has no folder
     * @return the key; {@code null} for a run that names no project
     * @throws UsageException      if {@code --project} names no project key
     * @throws RunRefusedException if the key and the folder name two projects; nothing was written
     */
    public static String ofRun(Parsed args, Projects projects, Path folder) {
        String named = of(args);
        Optional<Projects.Declared> here = folder == null ? Optional.empty() : projects.holding(folder);
        if (named == null) {
            return here.map(Projects.Declared::key).orElse(null);
        }
        if (here.isPresent()) {
            Projects.Declared declared = here.get();
            if (declared.key().equals(named)) {
                return named;
            }
            throw new RunRefusedException(Message.of("The run is refused: --project names '", Message.typed(named),
                    "', and the run works in ", folder, ", which is of the project '",
                    Message.quoted(declared.key()), "': \"" + Projects.SETTING + "\" in ", projects.file(),
                    " binds that key to ", declared.folder(), ". Start the run without --project, or change the"
                    + " setting"));
        }
        Optional<Path> bound = projects.folder(named);
        if (bound.isPresent()) {
            throw new RunRefusedException(Message.of("The run is refused: the project '", Message.typed(named),
                    "' is the folder ", bound.get(), " (\"" + Projects.SETTING + "\" in ", projects.file(),
                    "), and the run works in ", folder == null ? "a checkout of a URL (--source), which is no folder"
                    + " of this machine" : folder, ". Start the run in that folder, name another project, or change"
                    + " the setting"));
        }
        return named;
    }

    /**
     * The declared project {@code folder} lies in, by real paths; {@code null} when it lies in no declared folder.
     * A {@code findings} command asks it of the working directory.
     */
    public static String ofFolder(Projects projects, Path folder) {
        return projects.holding(folder).map(Projects.Declared::key).orElse(null);
    }
}
