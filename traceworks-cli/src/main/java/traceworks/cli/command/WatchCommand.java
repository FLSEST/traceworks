package traceworks.cli.command;

import traceworks.cli.Args.Parsed;
import traceworks.cli.Args.Spec;
import traceworks.cli.Command;
import traceworks.cli.Context;
import traceworks.cli.ExitCodes;
import traceworks.cli.RunFollower;
import traceworks.core.app.RunServices;
import traceworks.core.model.RunStatus;
import traceworks.core.record.RecordedEvent;
import traceworks.core.record.RunView;

import java.util.List;

/**
 * {@code traceworks watch [<run>]}: prints a run's events as they happen, until it ends, parks or is interrupted.
 * Without a run, the one that is running now.
 */
public final class WatchCommand implements Command {

    private static final long POLL_MILLIS = 200;

    private static final Spec SPEC = Spec.of("watch", "Prints the events of a run until it finishes or parks.",
            "[<run>]").positionals(0, 1).note(RunningRun.NOTE);

    @Override
    public Spec spec() {
        return SPEC;
    }

    @Override
    public int run(Context context, Parsed args) throws InterruptedException {
        RunServices services = context.services();
        String ref = RunningRun.ref(context, args.positional(), "watch");
        int printed = 0;
        try (RunFollower follower = new RunFollower(services, context.out(), false, true, context.runFolder(ref))) {
            while (true) {
                RunView view = services.view(ref);   // before the events: whatever ended the run is in them
                List<RecordedEvent> events = services.events(ref);
                for (; printed < events.size(); printed++) {
                    follower.accept(events.get(printed));
                }
                if (view.status() != RunStatus.RUNNING && view.status() != RunStatus.PENDING) {
                    return ExitCodes.of(view.status());
                }
                Thread.sleep(POLL_MILLIS);
            }
        }
    }
}
