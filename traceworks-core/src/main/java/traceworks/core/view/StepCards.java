package traceworks.core.view;

import traceworks.core.container.ImageFacts;
import traceworks.core.port.BlobStore;
import traceworks.core.model.StepStatus;
import traceworks.core.record.AiCallView;
import traceworks.core.record.CommandView;
import traceworks.core.record.FileView;
import traceworks.core.record.GateView;
import traceworks.core.record.ItemView;
import traceworks.core.record.QuestionView;
import traceworks.core.record.RunView;
import traceworks.core.record.StepFiles;
import traceworks.core.record.StepView;

import java.util.regex.Matcher;
import java.util.Map;
import java.util.regex.Pattern;

/** Renders the step cards of a run, with the cards of child runs nested under the step that started them. */
final class StepCards {

    /** What the run's images hold, by reference, from the root run's record; filled when a page is rendered. */
    private final Map<String, ImageFacts> images = new java.util.HashMap<>();

    private static final Pattern BLOCK_OUTPUT = Pattern.compile("block\\.(.+)\\.output");
    private static final Pattern STEP_OUTPUT = Pattern.compile("step_(\\d+)_output");

    /** The child runs the page has drawn, by id: a child is drawn once, where a step first names it. */
    private final java.util.Set<String> drawn = new java.util.HashSet<>();

    private final Content content;

    StepCards(BlobStore blobs) {
        this.content = new Content(blobs);
    }

    Content content() {
        return content;
    }

    /** The cards of all steps of the run, in step order. {@code scope} is empty for the root run. */
    void render(StringBuilder out, RunView run, String scope) {
        if (scope.isEmpty()) {
            drawn.clear();
            images.clear();
            run.images().forEach(image -> images.putIfAbsent(image.reference(), image));
        }
        for (StepView step : run.steps()) {
            card(out, run, step, scope);
        }
    }

    static String anchor(String scope, int stepIndex) {
        return scope.isEmpty() ? "step-" + stepIndex : "run-" + scope + "-step-" + stepIndex;
    }

    static String badge(String css, String label) {
        return "<span class=\"badge " + css + "\">" + Html.line(label) + "</span>";
    }

    static String gate(GateView gate, Content content) {
        if (gate == null) {
            return "";
        }
        String text = "<span class=\"gate " + Html.line(gate.status().toLowerCase(java.util.Locale.ROOT)) + "\">gate "
                + Html.line(gate.status()) + "</span>";
        if (gate.category() != null) {
            text += " " + Html.line(gate.category());
        }
        if (gate.description() != null) {
            text += " " + content.inline(gate.description());
        }
        return text;
    }

    private void card(StringBuilder out, RunView run, StepView step, String scope) {
        out.append("<section class=\"step ").append(Html.line(step.shownStatus().toLowerCase(java.util.Locale.ROOT)))
                .append("\" id=\"").append(Html.line(anchor(scope, step.stepIndex()))).append("\">\n");
        out.append("<h3>").append(step.stepIndex()).append(" &middot; ").append(Html.line(step.label()))
                .append(" <small>").append(Html.line(step.blockType())).append("</small> ")
                .append(step.ai() ? badge("ai", "AI") : badge("det", "deterministic"));
        if (step.commands().stream().anyMatch(CommandView::host)) {
            out.append(' ').append(badge("host", "host"));
        }
        int denied = step.commands().stream().mapToInt(command -> command.connections()[1]).sum();
        if (denied > 0) {
            out.append(' ').append(badge("problem", denied + (denied == 1 ? " connection" : " connections")
                    + " denied"));
        }
        if (step.commands().stream().anyMatch(command -> command.outputCut() != null)) {
            // A mark like a denied connection: the step's status is as it was, its output may be incomplete
            out.append(' ').append(badge("problem", "output cut"));
        }
        out.append("</h3>\n<p>status <strong>").append(Html.line(step.shownStatus())).append("</strong>");
        if (step.interrupted()) {
            out.append(" (interrupted)");
        }
        if (run.paused() && step.status() == StepStatus.RUNNING) {
            // The loop a pause stopped between two items: nothing runs, the resume runs the step again
            out.append(" (paused: the resume runs it again, with the items that finished)");
        }
        out.append(" &middot; ").append(step.durationMs()).append(" ms &middot; ").append(step.attempts())
                .append(step.attempts() == 1 ? " attempt" : " attempts").append("</p>\n");
        uses(out, run, step, scope);
        if (step.gate() != null) {
            out.append("<p>").append(gate(step.gate(), content)).append("</p>\n");
        }
        for (CommandView command : step.commands()) {
            command(out, command);
        }
        for (AiCallView call : step.aiCalls()) {
            aiCall(out, call);
        }
        content.details(out, "input", step.input());
        content.details(out, "output", step.output());
        content.details(out, "metadata", step.metadata());
        files(out, step.files());
        for (QuestionView question : step.questions()) {
            question(out, question);
        }
        for (StepView.Attempt attempt : step.earlierAttempts()) {
            attempt(out, attempt, step.attempts());
        }
        items(out, step, run, scope);
        for (String childId : step.childRunIds()) {
            child(out, run, childId);
        }
        out.append("</section>\n");
    }

    /** How many file names the collapsed files line shows before an ellipsis. */
    private static final int FILE_NAMES_SHOWN = 6;

    /** The scratch directory after the step: path, size and a link to the blob, which lies next to the page. */
    private static void files(StringBuilder out, StepFiles files) {
        if (files == null) {
            return;
        }
        out.append("<details class=\"files\"><summary>files (").append(files.files().size() + files.links().size()
                + files.unreadable().size())
                .append(files.truncated() ? ", listing cut" : "").append(")");
        if (!files.files().isEmpty()) {
            out.append(": ");
            out.append(String.join(", ", files.files().stream().limit(FILE_NAMES_SHOWN)
                    .map(file -> "<code>" + fileName(file.path()) + "</code>").toList()));
            if (files.files().size() > FILE_NAMES_SHOWN) {
                out.append(", …");
            }
        }
        out.append("</summary>\n<table><tr><th>path</th><th>size</th><th>content</th></tr>\n");
        for (FileView file : files.files()) {
            out.append("<tr><td><code>").append(fileName(file.path())).append("</code></td><td>")
                    .append(file.size()).append("</td><td>");
            if (file.blob() != null && file.blob().sha256().matches("[0-9a-f]{64}")) {
                out.append("<a href=\"blobs/").append(file.blob().sha256()).append("\">")
                        .append(file.blob().sha256(), 0, 12).append("</a>");
            } else {
                out.append("not stored: ").append(Html.line(file.reason() == null ? "unknown" : file.reason()));
            }
            out.append("</td></tr>\n");
        }
        for (StepFiles.Link link : files.links()) {
            out.append("<tr><td><code>").append(fileName(link.path())).append("</code></td><td></td><td>link to <code>")
                    .append(fileName(link.target())).append("</code> (not followed)</td></tr>\n");
        }
        for (StepFiles.Unreadable entry : files.unreadable()) {
            out.append("<tr><td><code>").append(fileName(entry.path())).append("</code></td><td></td><td>"
                    + "unreadable: ").append(Html.line(entry.reason())).append("</td></tr>\n");
        }
        out.append("</table>\n");
        if (!files.removed().isEmpty()) {
            out.append("<p>removed:");
            files.removed().forEach(path -> out.append(" <code>").append(fileName(path)).append("</code>"));
            out.append("</p>\n");
        }
        out.append("</details>\n");
    }

    /** A file name from a step, escaped for the page as every one-line text is ({@link Html#line}). */
    static String fileName(String name) {
        return Html.line(name);
    }

    private void uses(StringBuilder out, RunView run, StepView step, String scope) {
        if (step.uses().isEmpty()) {
            return;
        }
        out.append("<p class=\"uses\">uses:");
        for (String key : step.uses()) {
            Integer target = target(run, key);
            out.append(' ');
            if (target == null) {
                out.append("<code>").append(Html.line(key)).append("</code>");
            } else {
                out.append("<a href=\"#").append(Html.line(anchor(scope, target))).append("\"><code>")
                        .append(Html.line(key)).append("</code></a>");
            }
        }
        out.append("</p>\n");
    }

    private static Integer target(RunView run, String key) {
        Matcher block = BLOCK_OUTPUT.matcher(key);
        if (block.matches()) {
            for (StepView step : run.steps()) {
                if (block.group(1).equals(step.name())) {
                    return step.stepIndex();
                }
            }
            return null;
        }
        Matcher numbered = STEP_OUTPUT.matcher(key);
        if (numbered.matches() && numbered.group(1).length() < 10) {
            int index = Integer.parseInt(numbered.group(1));
            for (StepView step : run.steps()) {
                if (step.stepIndex() == index) {
                    return index;
                }
            }
        }
        return null;
    }

    private void command(StringBuilder out, CommandView command) {
        out.append("<div class=\"command\">");
        out.append(command.host() ? badge("host", "host") : badge("container", "container"));
        if (command.item() != null) {
            out.append(" item ").append(command.item());
        }
        if (command.image() != null) {
            out.append(" image <code>").append(Html.line(command.image())).append("</code>");
            ImageFacts facts = images.get(command.image());
            if (facts != null && (facts.base() != null || facts.description() != null)) {
                out.append(" <small>").append(Html.line(String.join(": ", java.util.stream.Stream.of(facts.base(),
                        facts.description()).filter(java.util.Objects::nonNull).toList()))).append("</small>");
            }
        }
        if (command.network() != null) {
            out.append(" network <code>").append(Html.line(command.network())).append("</code>");
            command.networkHosts().forEach(host -> out.append(" <code>").append(Html.line(host)).append("</code>"));
        }
        if (command.workingDir() != null) {
            out.append(" in <code>").append(Html.line(command.workingDir())).append("</code>");
        }
        CommandView.Result result = command.result();
        if (result == null) {
            out.append(" &middot; no result (the command threw)");
        } else {
            out.append(" &middot; exit ").append(result.exitCode() == null ? "none" : result.exitCode().toString())
                    .append(" &middot; ").append(result.durationMs()).append(" ms");
            if (result.timedOut()) {
                out.append(" &middot; timed out");
            }
            if (result.outputCut() != null) {
                // The reading ended at its bound, not at the end of the stream; the names come from the record
                out.append(" &middot; <strong class=\"problem\">output cut: ")
                        .append(Html.line(result.outputCut().summary())).append("</strong>");
            }
            result.egress().forEach(connection -> egress(out, connection));
        }
        if (!command.envNames().isEmpty()) {
            out.append(" &middot; env");
            command.envNames().forEach(name -> out.append(" <code>").append(Html.line(name)).append("</code>"));
        }
        for (CommandView.Mount mount : command.mounts()) {
            out.append(" &middot; mount <code>").append(Html.line(mount.host())).append(" : ")
                    .append(Html.line(mount.container())).append(mount.readOnly() ? " (ro)" : " (rw)")
                    .append("</code>");
        }
        content.pre(out, command.command());
        content.values(out, command.values());
        out.append("</div>\n");
    }

    /**
     * What the command's container asked the egress proxy for, one target and verdict. A denied one is marked so
     * that nobody overlooks it; the target comes from inside the container and is escaped text.
     */
    private static void egress(StringBuilder out, CommandView.Connection connection) {
        String text = "egress " + (connection.denied() ? "DENIED" : Html.line(connection.verdict())) + " <code>"
                + Html.line(connection.target()) + "</code> &times;" + connection.count();
        out.append(" &middot; ").append(connection.denied() ? "<strong class=\"problem\">" + text + "</strong>"
                : text);
    }

    /**
     * An AI call: what was asked of which provider, with which tools, every tool call with its arguments and result,
     * and how it ended. Everything that comes from the model or its tools is escaped text.
     */
    private void aiCall(StringBuilder out, AiCallView call) {
        out.append("<div class=\"ai-call\">").append(badge("ai", "AI call")).append(' ')
                .append(Html.line(call.provider() == null ? "?" : call.provider()));
        if (call.item() != null) {
            out.append(" item ").append(call.item());
        }
        if (call.model() != null) {
            out.append(" model <code>").append(Html.line(call.model())).append("</code>");
        }
        if (call.local() != null) {
            // A call that went to a model server of the operator's, not to a provider
            out.append(" local backend <code>").append(Html.line(call.local().backend())).append("</code> at <code>")
                    .append(Html.line(call.local().url())).append("</code>");
        }
        if (call.effort() != null) {
            out.append(" effort <code>").append(Html.line(call.effort())).append("</code>");
        }
        out.append(" tools <code>").append(Html.line(call.tools() == null ? "?" : call.tools())).append("</code>");
        if (call.hasWeb()) {
            out.append(" web <code>").append(Html.line(call.web())).append("</code>");
            call.webHosts().forEach(host -> out.append(" <code>").append(Html.line(host)).append("</code>"));
        }
        AiCallView.Response response = call.response();
        if (response == null) {
            out.append(" &middot; no response (the call did not end)");
        } else {
            if (response.model() != null) {
                out.append(" &middot; answered by <code>").append(Html.line(response.model())).append("</code>");
            }
            if (response.inputTokens() != null || response.outputTokens() != null) {
                out.append(" &middot; ").append(response.inputTokens()).append(" in / ")
                        .append(response.outputTokens()).append(" out tokens");
            }
            if (response.costUsd() != null) {
                out.append(" &middot; ").append(response.costUsd()).append(" USD");
            }
            if (response.numTurns() != null) {
                out.append(" &middot; ").append(response.numTurns()).append(response.numTurns() == 1 ? " turn"
                        : " turns");
            }
            out.append(" &middot; exit ").append(response.exitCode() == null ? "none" : response.exitCode().toString());
            String why = AiCallView.firstErrorLine(content.text(response.error()));
            if (why != null) {
                // Why it failed belongs where the call is: the whole error is one click further, under "error"
                out.append(" &middot; <strong class=\"problem\">failed: ").append(Html.line(why)).append("</strong>");
            }
        }
        out.append(" &middot; ").append(call.toolCalls().size())
                .append(call.toolCalls().size() == 1 ? " tool call" : " tool calls");
        content.details(out, "prompt", call.prompt());
        content.details(out, "system prompt", call.systemPrompt());
        for (AiCallView.ToolCallView tool : call.toolCalls()) {
            out.append("<div class=\"tool-call\">tool <code>").append(Html.line(tool.tool() == null ? "?"
                    : tool.tool())).append("</code>");
            if (tool.error()) {
                out.append(" &middot; tool error");
            }
            content.details(out, "arguments", tool.arguments());
            content.details(out, "result", tool.result());
            out.append("</div>\n");
        }
        if (response != null) {
            content.details(out, "answer", response.output());
            content.details(out, "error", response.error());
        }
        out.append("</div>\n");
    }

    private void question(StringBuilder out, QuestionView question) {
        out.append("<div class=\"question\"><strong>").append(Html.line(question.kind())).append("</strong> ")
                .append(content.inline(question.question()));
        if (!question.options().isEmpty()) {
            out.append(" options");
            question.options().forEach(o -> out.append(" <code>").append(Html.line(o)).append("</code>"));
        }
        QuestionView.Answer answer = question.answered();
        if (answer == null) {
            out.append(" &middot; unanswered");
        } else {
            out.append("<br>answer ").append(content.inline(answer.answer()));
            by(out, answer);
            QuestionView.Answer given = answer.given();
            if (given != null) {
                out.append("<br>given");
                if (given.actor() != null) {
                    out.append(" by ").append(Html.line(given.actor()));
                }
                if (given.source() != null) {
                    out.append(" via ").append(Html.line(given.source()));
                }
                if (given.at() != null) {
                    out.append(" at ").append(Html.line(given.at().toString()));
                }
                out.append(", while the run was parked");
                if (given.comment() != null) {
                    out.append("<br>comment ").append(content.inline(given.comment()));
                }
            }
            if (answer.comment() != null) {
                out.append("<br>comment ").append(content.inline(answer.comment()));
            }
        }
        out.append("</div>\n");
    }

    private static void by(StringBuilder out, QuestionView.Answer answer) {
        if (answer.actor() != null) {
            out.append(" &middot; by ").append(Html.line(answer.actor()));
        }
        if (answer.source() != null) {
            out.append(" &middot; via ").append(Html.line(answer.source()));
        }
        if (answer.at() != null) {
            out.append(" &middot; at ").append(Html.line(answer.at().toString()));
        }
    }

    /** An attempt before the latest: what it ran and asked, so a resume hides nothing. */
    private void attempt(StringBuilder out, StepView.Attempt attempt, int attempts) {
        out.append("<div class=\"attempt\"><h4>attempt ").append(attempt.number()).append(" of ").append(attempts);
        if (attempt.startedAt() != null) {
            out.append(" <small>started ").append(Html.line(attempt.startedAt().toString())).append("</small>");
        }
        out.append("</h4>\n");
        for (CommandView command : attempt.commands()) {
            command(out, command);
        }
        for (AiCallView call : attempt.aiCalls()) {
            aiCall(out, call);
        }
        for (QuestionView question : attempt.questions()) {
            question(out, question);
        }
        out.append("</div>\n");
    }

    private void items(StringBuilder out, StepView step, RunView run, String scope) {
        if (step.items().isEmpty()) {
            return;
        }
        out.append("<table><tr><th>item</th><th>status</th><th>value</th><th>output</th><th>run</th></tr>\n");
        for (ItemView item : step.items()) {
            out.append("<tr><td>").append(item.index() + 1).append(" of ").append(item.total()).append("</td><td>")
                    .append(Html.line(item.status() == null ? "running" : item.status()))
                    // A resumed loop did not run this item again: its output is the one of the earlier event
                    .append(item.resumedFrom() == null ? ""
                            : " (taken over from event " + item.resumedFrom() + ", not run again)")
                    .append("</td><td>");
            content.details(out, "value", item.item());
            out.append("</td><td>");
            content.details(out, "output", item.output());
            out.append("</td><td>").append(item.subRun() == null ? "" : Html.line(item.subRun()))
                    .append("</td></tr>\n");
        }
        out.append("</table>\n");
    }

    private void child(StringBuilder out, RunView parent, String childId) {
        RunView child = parent.children().get(childId);
        if (child == null) {
            out.append("<p>child run <code>").append(Html.line(childId)).append("</code> (not in the record)</p>\n");
            return;
        }
        if (!drawn.add(childId)) {
            // Only a record nobody's run wrote names a child twice; drawn at each place, a page could grow without end
            out.append("<p>child run <code>").append(Html.line(childId))
                    .append("</code> (shown above, under the step that names it first)</p>\n");
            return;
        }
        out.append("<div class=\"child\"><h4>child run <code>").append(Html.line(childId)).append("</code> ")
                .append(Html.line(child.status().name())).append("</h4>\n");
        render(out, child, childId);
        out.append("</div>\n");
    }
}
