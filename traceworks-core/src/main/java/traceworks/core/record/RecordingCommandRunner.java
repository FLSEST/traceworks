package traceworks.core.record;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import traceworks.core.port.CommandRunner;

import java.util.function.Supplier;

/**
 * A {@link CommandRunner} that records what it runs, attributed by the recorder to the innermost running step:
 * {@code step.command} with the facts the delegate planned (image, image ID, effective mounts, network, environment
 * names, host) before the command runs, and {@code step.command.finished} after it, with the streams that went beyond
 * the output limit ({@code truncated}, empty when the output was whole) and, only when the reading of the output
 * ended at its bound because something the command left behind still held it, {@code output_cut}. The network is {@code none}, {@code open} or
 * {@code hosts}; with {@code hosts} the command names them ({@code network_hosts}) and its end names every connection
 * its container asked the egress proxy for ({@code egress}, by target and verdict). A planned refusal (a missing
 * image) is recorded the same way, with its exit code. An exception of the delegate propagates: a plan that cannot be
 * made records nothing, and a command that throws leaves its {@code step.command} without {@code .finished}.
 */
final class RecordingCommandRunner implements CommandRunner {

    private final CommandRunner delegate;
    private final RunRecorder recorder;
    private final Blobs blobs;

    RecordingCommandRunner(CommandRunner delegate, RunRecorder recorder, Blobs blobs) {
        this.delegate = delegate;
        this.recorder = recorder;
        this.blobs = blobs;
    }

    @Override
    public CommandResult run(CommandRequest request) {
        return plan(request).execution().get();
    }

    @Override
    public String imageReference(String image) {
        return delegate.imageReference(image);
    }

    /** The delegate's plan, whose execution records the command around the delegate's execution. */
    @Override
    public CommandPlan plan(CommandRequest request) {
        CommandPlan plan = delegate.plan(request);
        return new CommandPlan(plan.facts(), () -> recorded(request, plan.facts(), plan.execution()));
    }

    private CommandResult recorded(CommandRequest request, CommandFacts facts, Supplier<CommandResult> execution) {
        ObjectNode fields = Blobs.MAPPER.createObjectNode();
        fields.set("command", blobs.of(request.runsEntrypoint() ? Blobs.json(request.entrypointArgs())
                : request.command()));
        if (!request.values().isEmpty()) {
            // The shell variables the command found set, by name: no part of the command's text
            fields.set("values", blobs.of(Blobs.json(request.values())));
        }
        fields.put("working_dir", facts.workingDirectory());
        fields.put("image", facts.image());
        fields.put("image_id", facts.imageId());
        ArrayNode mounts = fields.putArray("mounts");
        for (Mount mount : facts.mounts()) {
            ObjectNode node = mounts.addObject();
            node.put("host", mount.hostPath());
            node.put("container", mount.containerPath());
            node.put("read_only", mount.readOnly());
        }
        fields.put("network", facts.network().label());
        if (facts.network().restricted()) {
            ArrayNode hosts = fields.putArray("network_hosts");
            facts.network().hosts().forEach(hosts::add);
        }
        ArrayNode names = fields.putArray("env_names");
        facts.environmentNames().forEach(names::add);
        fields.put("host", facts.host());
        Attribution where = recorder.appendCommand(RecordTypes.STEP_COMMAND, fields, null);

        CommandResult result = execution.get();

        ObjectNode finished = Blobs.MAPPER.createObjectNode();
        finished.put("exit_code", result.exitCode());
        finished.put("duration_ms", result.durationMs());
        finished.put("timed_out", result.timedOut());
        ArrayNode truncated = finished.putArray("truncated");
        result.truncatedStreams().forEach(truncated::add);
        if (result.outputCut() != null) {
            // Only then: a record without the field says that the output was read to its end
            ObjectNode cut = finished.putObject("output_cut");
            ArrayNode streams = cut.putArray("streams");
            result.outputCut().streams().forEach(streams::add);
            cut.put("after_ms", result.outputCut().afterMs());
        }
        if (facts.network().restricted() || !result.connections().isEmpty()) {
            ArrayNode egress = finished.putArray("egress");
            for (Connection connection : result.connections()) {
                ObjectNode node = egress.addObject();
                node.put("target", connection.target());
                node.put("verdict", connection.verdict());
                node.put("count", connection.count());
            }
        }
        recorder.appendCommand(RecordTypes.STEP_COMMAND_FINISHED, finished, where);
        return result;
    }

    /** Where a command ran: the run and its parent, the step and the foreach item. */
    record Attribution(String run, String parent, Integer step, Integer item) {}
}
