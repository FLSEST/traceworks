package traceworks.cli;

import traceworks.cli.Args.Parsed;
import traceworks.cli.Args.Spec;

/** One command of the command line. */
public interface Command {

    Spec spec();

    /**
     * @return the exit code
     * @throws UsageException                                                      for a wrong command line
     * @throws traceworks.core.app.RunRefusedException            if a use case refuses
     */
    int run(Context context, Parsed args) throws Exception;
}
