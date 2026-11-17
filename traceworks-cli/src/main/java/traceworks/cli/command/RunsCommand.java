package traceworks.cli.command;

import traceworks.cli.TerminalFormat;
import traceworks.cli.Args.Parsed;
import traceworks.cli.Args.Spec;
import traceworks.cli.Command;
import traceworks.cli.Context;
import traceworks.cli.TerminalText;
import traceworks.cli.UsageException;
import traceworks.core.app.RunListing;
import traceworks.core.model.RunStatus;
import traceworks.core.record.RunView;
import traceworks.core.util.Message;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** {@code traceworks runs}: one line per run, newest first. */
public final class RunsCommand implements Command {

    private static final int DEFAULT_LIMIT = 20;

    private static final Spec SPEC = Spec.of("runs", "Lists the newest runs: the first 8 characters of the id, status,"
                    + " workflow and start time (UTC).", null)
            .option("status", "S", "only runs with this status, such as PARKED")
            .option("workflow", "ID_OR_NAME", "only runs of this workflow")
            .option("limit", "N", "at most N runs (default " + DEFAULT_LIMIT + ")")
            .flag("long", "add the gate, the duration, the first 8 characters of the package digest and of the"
                    + " inputs digest, and the models; a * marks a digest that differs from the run of the same"
                    + " workflow listed below");

    @Override
    public Spec spec() {
        return SPEC;
    }

    @Override
    public int run(Context context, Parsed args) {
        RunStatus status = status(args.option("status"));
        String workflow = args.option("workflow");
        int limit = limit(args.option("limit"));
        boolean filtered = status != null || workflow != null;
        int read = filtered ? Integer.MAX_VALUE : limit;
        if (args.has("long")) {
            longLines(context.services().listing(read).stream()
                    .filter(run -> matches(run.view(), status, workflow)).limit(limit).toList())
                    .forEach(line -> TerminalText.println(context.out(), line));
            return 0;
        }
        List<RunView> runs = context.services().runs(read);
        runs.stream()
                .filter(run -> matches(run, status, workflow))
                .limit(limit)
                .forEach(run -> TerminalText.println(context.out(), line(run)));
        return 0;
    }

    private static boolean matches(RunView run, RunStatus status, String workflow) {
        return (status == null || run.status() == status) && (workflow == null || workflow.equals(run.workflowId())
                || workflow.equalsIgnoreCase(run.workflowName()));
    }

    /**
     * The long lines: the plain line of each run, then its gate, its duration, the package digest and the inputs
     * digest (8 characters each) and its models. A digest gets a star when the run of the same workflow listed below
     * has another: the workflow's version, or the inputs, changed between the two.
     */
    private static List<List<Object>> longLines(List<RunListing> runs) {
        List<List<Object>> lines = new ArrayList<>();
        for (int i = 0; i < runs.size(); i++) {
            RunView run = runs.get(i).view();
            RunView earlier = null;
            for (int j = i + 1; j < runs.size() && earlier == null; j++) {
                if (Objects.equals(run.workflowId(), runs.get(j).view().workflowId())) {
                    earlier = runs.get(j).view();
                }
            }
            String inputs = inputsDigest(run);
            List<String> models = runs.get(i).models();
            lines.add(List.of(line(run), "  gate ", run.gate() == null ? "-" : TerminalText.word(String.valueOf(run.gate().status())),
                    "  " + duration(run) + "  pkg ",
                    digest(run.packageDigest(), earlier == null ? null : earlier.packageDigest(), earlier),
                    "  inputs ", digest(inputs, earlier == null ? null : inputsDigest(earlier), earlier),
                    "  models ", models.isEmpty() ? "-" : models(models)));
        }
        return lines;
    }

    private static String inputsDigest(RunView run) {
        return run.inputs() == null ? null : run.inputs().sha256();
    }

    /** The first 8 characters of a digest, with a star when the run it is compared with has another. */
    private static Object digest(String digest, String earlierDigest, RunView earlier) {
        Object shown = digest == null ? "-" : TerminalText.word(digest.substring(0, Math.min(8, digest.length())));
        return earlier != null && !Objects.equals(digest, earlierDigest) ? TerminalText.seq(shown, "*") : shown;
    }

    /** The models of a run, each a name of the shape Traceworks records or else a text from outside. */
    private static Object models(List<String> models) {
        List<Object> parts = new java.util.ArrayList<>();
        for (String model : models) {
            parts.add(parts.isEmpty() ? "" : ",");
            parts.add(TerminalText.word(model));
        }
        return parts;
    }

    /** From start to end; {@code -} for a run that has not ended. */
    private static String duration(RunView run) {
        if (run.startedAt() == null || run.finishedAt() == null) {
            return "-";
        }
        long ms = Math.max(0, Duration.between(run.startedAt(), run.finishedAt()).toMillis());
        if (ms < 1_000) {
            return ms + "ms";
        }
        if (ms < 60_000) {
            return String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
        }
        long seconds = ms / 1_000;
        return seconds < 3_600 ? String.format(Locale.ROOT, "%dm%02ds", seconds / 60, seconds % 60)
                : String.format(Locale.ROOT, "%dh%02dm", seconds / 3_600, seconds % 3_600 / 60);
    }

    /** The plain line of a run, as parts: its name is the workflow's text, and a long one goes on on marked lines. */
    private static Object line(RunView run) {
        String started = run.startedAt() == null ? "-" : run.startedAt().truncatedTo(ChronoUnit.SECONDS).toString();
        return TerminalText.seq(TerminalText.word(TerminalFormat.shortId(run.runId())).padded(8),
                String.format("  %-21s  ", run.status()),
                TerminalText.oneLine(run.workflowName() == null ? "-" : run.workflowName()), "  " + started);
    }

    private static RunStatus status(String value) {
        if (value == null) {
            return null;
        }
        try {
            return RunStatus.valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new UsageException(Message.of("--status takes one of " + List.of(RunStatus.values()) + ", got '",
                    Message.typed(value), "'"));
        }
    }

    private static int limit(String value) {
        if (value == null) {
            return DEFAULT_LIMIT;
        }
        try {
            int limit = Integer.parseInt(value.strip());
            if (limit > 0) {
                return limit;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new UsageException(Message.of("--limit takes a positive number, got '", Message.typed(value), "'"));
    }
}
