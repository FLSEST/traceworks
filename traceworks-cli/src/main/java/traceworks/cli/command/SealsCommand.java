package traceworks.cli.command;

import com.fasterxml.jackson.databind.JsonNode;
import traceworks.cli.Args.Parsed;
import traceworks.cli.Args.Spec;
import traceworks.cli.Command;
import traceworks.cli.Context;
import traceworks.cli.ExitCodes;
import traceworks.cli.TerminalFormat;
import traceworks.cli.UsageException;
import traceworks.core.app.SealStatus;
import traceworks.core.app.Seals;
import traceworks.core.seal.SealEvent;
import traceworks.core.seal.SealLogCheck;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * {@code traceworks seals [log | verify <package>]}: what the seal log of the home holds. Without a word it lists the
 * workflows sealed now; {@code log} prints every line; both verify the chain of the log first and exit 8 when it does
 * not hold. {@code verify <package>} says what a run of the package would find of its seal, and exits 8 when the seal
 * does not hold. Nothing here writes.
 */
public final class SealsCommand implements Command {

    private static final Spec SPEC = Spec.of("seals", "Lists the sealed packages of this home, prints its seal log, or"
                    + " checks the seal of a package: 'seals [log | verify <package>]'.", "[log | verify <package>]")
            .positionals(0, 2)
            .note("""
                    seals                   the workflows this home holds as sealed: id, the start of the seal digest,
                                            who sealed it and when, and where the package was
                    seals log               every line of the seal log: SEALED, UNSEALED, REFUSED (a run or a resume
                                            that a seal refused), REPAIRED
                    seals verify <package>  the seal of the package as a run would judge it; exits 8 when a run
                                            would be refused for it""");

    @Override
    public Spec spec() {
        return SPEC;
    }

    @Override
    public int run(Context context, Parsed args) {
        List<String> words = args.positionals();
        if (words.isEmpty()) {
            return list(context, false);
        }
        return switch (words.getFirst()) {
            case "log" -> {
                if (words.size() > 1) {
                    throw new UsageException("seals log takes no further argument");
                }
                yield list(context, true);
            }
            case "verify" -> {
                if (words.size() < 2) {
                    throw new UsageException("seals verify needs a package");
                }
                yield verify(context, context.resolve(words.get(1)));
            }
            default -> throw new UsageException("unknown seals command '" + words.getFirst()
                    + "'; the commands are 'log' and 'verify'");
        };
    }

    private static int list(Context context, boolean everyLine) {
        Seals seals = context.seals();
        SealLogCheck log = seals.log();
        PrintStream out = context.out();
        out.println("seal log  " + TerminalFormat.safe(seals.logLocation()) + ": "
                + (log.intact() ? "ok (" + log.events().size() + " lines)" : "BROKEN"));
        log.problems().forEach(problem -> out.println("  " + TerminalFormat.safe(problem)));
        if (everyLine) {
            log.events().forEach(event -> out.println(line(event)));
        } else if (log.intact()) {
            if (log.sealed().isEmpty()) {
                out.println("no package is sealed in this home");
            }
            log.sealed().values().forEach(sealed -> out.println(TerminalFormat.safe(sealed.workflowId()) + "  "
                    + TerminalFormat.safe(start(sealed.digest())) + "  " + TerminalFormat.safe(sealed.signer()) + "  "
                    + sealed.at() + "  " + TerminalFormat.safe(sealed.packageDir())));
        }
        if (!log.intact()) {
            out.println("the seal log does not hold: no seal of this home can be trusted, and every run is refused");
            return ExitCodes.VERIFY_FAILED;
        }
        return ExitCodes.OK;
    }

    /** One line of the log: its number, time and type, then its fields as {@code name=value}. */
    private static String line(SealEvent event) {
        StringBuilder text = new StringBuilder().append(event.seq()).append("  ").append(event.at()).append("  ")
                .append(TerminalFormat.safe(event.type()));
        for (Iterator<Map.Entry<String, JsonNode>> it = event.fields().fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            if (field.getValue().isNull()) {
                continue;
            }
            String value = field.getValue().isTextual() ? field.getValue().textValue() : field.getValue().toString();
            text.append("  ").append(TerminalFormat.safe(field.getKey())).append('=')
                    .append(TerminalFormat.safe(field.getKey().endsWith("digest") ? start(value) : value));
        }
        return text.toString();
    }

    private static String start(String digest) {
        return digest == null || digest.length() <= 12 ? String.valueOf(digest) : digest.substring(0, 12);
    }

    private static int verify(Context context, Path pkg) {
        if (!Files.exists(pkg)) {
            throw new UsageException("no such package: " + pkg);
        }
        SealStatus status = context.seals().verify(pkg);
        PrintStream out = context.out();
        switch (status.state()) {
            case UNSEALED -> out.println("unsealed  " + TerminalFormat.safe(pkg.toString()));
            case SEALED -> {
                out.println("sealed    " + TerminalFormat.safe(pkg.toString()));
                out.println("  digest  " + status.seal().digest());
                out.println("  signer  " + TerminalFormat.safe(String.join(", ", status.principals())) + " (key "
                        + TerminalFormat.safe(status.key()) + "), sealed by "
                        + TerminalFormat.safe(status.seal().signer()) + " at " + status.seal().sealedAt());
            }
            case REFUSED -> {
                out.println("the seal does NOT hold  " + TerminalFormat.safe(pkg.toString()));
                out.println("  " + TerminalFormat.safe(status.detail()));
            }
        }
        return status.state() == SealStatus.State.REFUSED ? ExitCodes.VERIFY_FAILED : ExitCodes.OK;
    }
}
