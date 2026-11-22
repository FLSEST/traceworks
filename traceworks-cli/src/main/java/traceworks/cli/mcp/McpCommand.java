package traceworks.cli.mcp;

import traceworks.cli.Args.Parsed;
import traceworks.cli.Args.Spec;
import traceworks.cli.Command;
import traceworks.cli.Context;
import traceworks.cli.ExitCodes;
import traceworks.cli.Interrupts;
import traceworks.cli.UsageException;
import traceworks.core.app.RunRefusedException;
import traceworks.core.register.DecisionAgent;
import traceworks.core.util.Message;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code traceworks mcp}: a server for MCP clients on the standard input and output of the process (docs/cli.md,
 * "mcp"). It reads one JSON-RPC message per line and writes one per line, and ends when its input ends. Without a
 * switch it only reads; the operator who starts it opens what a client may change, as far as the home allows it
 * ({@code mcp.allow} in its {@code traceworks.json}).
 */
public final class McpCommand implements Command {

    private static final Spec SPEC = Spec.of("mcp", "Serves MCP clients on standard input and output: read-only"
                    + " unless a switch opens more.", "")
            .positionals(0, 0)
            .flag("allow-run", "offer the tools run, resume, pause and cancel")
            .flag("allow-answer", "offer the tools approve and answer: the client answers the questions of a run")
            .flag("allow-decide", "offer the tool findings_decide: the client accepts, dismisses and reopens"
                    + " findings")
            .flag("allow-host-execution", "your consent to a host run for every run this server starts or"
                    + " resumes, where no container runtime works; no client can give it")
            .option("agent", "NAME", "the agent every change of this server is recorded with, beside the actor;"
                    + " without it " + DecisionAgent.ENV + ", else the name the client gives")
            .repeated("packages", "DIR", "a folder whose packages a client may name (repeatable; default: the"
                    + " working directory; --allow-run needs it)")
            .note("""
                    The server speaks the Model Context Protocol, revision 2026-07-28 (each request carries its
                    metadata) and, after an initialize handshake, 2025-11-25, 2025-06-18, 2025-03-26 and 2024-11-05.
                    Standard output carries protocol messages only; what the server has to say goes to standard
                    error. The runs it started are asked to pause when its input ends.
                    A switch opens only what the home allows: "mcp": {"allow": ["run", "answer", "decide"]} in
                    its traceworks.json names what a server may be started to change. Without it every server
                    of the home only reads, and one started with a switch does not start.
                    Every change names an agent beside the actor, as --agent does for approve, answer and findings:
                    a name of at most 64 characters (ASCII letters and digits, . _ - : / @ + and single spaces).
                    It is what the client or you say, unsigned. More: docs/cli.md, "mcp".""");

    @Override
    public Spec spec() {
        return SPEC;
    }

    @Override
    public int run(Context context, Parsed args) {
        if (context.stdin() == null) {
            throw new UsageException(Message.of("'mcp' reads its messages from standard input, and there is none"));
        }
        String agent;
        try {
            agent = DecisionAgent.resolve(args.option("agent"), context.env());
        } catch (IllegalArgumentException e) {
            throw new UsageException(Message.of(e));
        }
        refuseWhatTheHomeDoesNotAllow(context, args);
        if (args.has("allow-run") && args.options("packages").isEmpty()) {
            // The default folder is the working directory, which is the workspace of the runs too: a step that
            // may write it could leave a package there that the client then runs
            throw new UsageException(Message.of("--allow-run needs --packages: name the folder whose packages a"
                    + " client may run. Without it the folder would be the working directory, where a run can"
                    + " write a package"));
        }
        McpOptions options = new McpOptions(args.has("allow-run"), args.has("allow-answer"),
                args.has("allow-decide"), args.has("allow-host-execution"), agent, roots(context, args));
        McpTools tools = new McpTools(options, context);
        McpServer server = new McpServer(tools, new McpResources(tools), context.err());
        // The end of the process (SIGTERM) leaves the runs of this server interrupted and resumable, as it does
        // for run and resume; the end of the input asks them to pause (McpTools.close)
        return Interrupts.guard(tools.services(), context.err(), () -> {
            try {
                server.serve(context.stdin(), context.out());
            } finally {
                tools.close();
            }
            return ExitCodes.OK;
        });
    }

    /**
     * A switch opens only what the home allows ({@code mcp.allow} in its {@code traceworks.json}): who can write the
     * configuration of a client can start this server with any switch, and shall not open a home by that alone.
     */
    private static void refuseWhatTheHomeDoesNotAllow(Context context, Parsed args) {
        Set<String> allowed = context.mcpAllowed();
        for (String change : List.of("run", "answer", "decide")) {
            if (args.has("allow-" + change) && !allowed.contains(change)) {
                throw new RunRefusedException(Message.of("--allow-" + change + " opens what this home does not"
                        + " allow a server to change. To allow it, add \"mcp\": {\"allow\": [\"" + change
                        + "\"]} to ", Message.typed(context.home().resolve("traceworks.json"))));
            }
        }
    }

    /** The folders a client may name packages in, by their real paths. */
    private static List<Path> roots(Context context, Parsed args) {
        List<String> given = args.options("packages");
        List<Path> roots = new ArrayList<>();
        for (String folder : given.isEmpty() ? List.of(".") : given) {
            Path path = context.resolve(folder);
            try {
                if (!Files.isDirectory(path)) {
                    throw new UsageException(Message.of("--packages takes a folder, and this is none: ",
                            Message.typed(path)));
                }
                roots.add(path.toRealPath());
            } catch (IOException e) {
                throw new UsageException(Message.of("--packages: cannot read ", Message.typed(path)));
            }
        }
        return roots;
    }
}
