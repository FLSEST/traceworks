package traceworks.core.view;

import traceworks.core.port.BlobStore;
import traceworks.core.record.ChainReport;
import traceworks.core.record.QuestionView;
import traceworks.core.container.ImageFacts;
import traceworks.core.record.RunView;
import traceworks.core.record.StepView;
import traceworks.core.record.WorkflowOutline;
import traceworks.core.util.ShellQuoting;

import java.util.List;
import java.util.Map;

/**
 * Renders a run as one self-contained HTML page: no JavaScript, no external resources, every text escaped. The page is
 * a pure function of its inputs, so the same record gives the same bytes.
 */
public final class RunHtml {

    /** The style sheet of every page Traceworks renders, {@code report.html} included. */
    static final String CSS = """
            :root{--bg:#fff;--fg:#1d2127;--muted:#5d6670;--line:#d5dae0;--card:#f6f8fa;--green:#1a7f37;--yellow:#9a6700;--red:#cf222e;--blue:#0969da}
            @media (prefers-color-scheme:dark){:root{--bg:#0d1117;--fg:#e6edf3;--muted:#8b949e;--line:#30363d;--card:#161b22;--green:#3fb950;--yellow:#d29922;--red:#f85149;--blue:#58a6ff}}
            body{font:15px/1.5 system-ui,sans-serif;background:var(--bg);color:var(--fg);max-width:60rem;margin:0 auto;padding:1rem}
            a{color:var(--blue)}code,pre{font-family:ui-monospace,monospace;font-size:13px}
            pre{background:var(--card);border:1px solid var(--line);padding:.5rem;overflow:auto;white-space:pre-wrap;word-break:break-word}
            .step{border:1px solid var(--line);border-left-width:4px;background:var(--card);padding:.5rem 1rem;margin:1rem 0;border-radius:4px}
            .step.completed{border-left-color:var(--green)}.step.failed{border-left-color:var(--red)}
            .step.cancelled{border-left-color:var(--muted)}
            .step.waiting_approval,.step.interrupted{border-left-color:var(--yellow)}
            .child{margin-left:1rem;border-left:2px dashed var(--line);padding-left:1rem}
            .badge{font-size:12px;border:1px solid var(--line);border-radius:3px;padding:0 .4em;font-weight:normal}
            .badge.ai{color:var(--blue)}.badge.host{color:var(--yellow)}
            .gate.green{color:var(--green)}.gate.yellow{color:var(--yellow)}.gate.red{color:var(--red)}
            .deleted,.cut,.problem{color:var(--yellow)}.problem{color:var(--red)}
            .parked{border:2px solid var(--yellow);padding:.5rem 1rem;border-radius:4px}
            .attempt{border-top:1px dashed var(--line);margin-top:.5rem}
            .ai-call{border-top:1px solid var(--line);margin-top:.5rem}.tool-call{margin-left:1rem}
            table{border-collapse:collapse}td,th{border:1px solid var(--line);padding:.25rem .5rem;vertical-align:top}
            small,.muted{color:var(--muted)}
            """;

    private RunHtml() {
    }

    /**
     * @param chain   the verification of the record, shown as the verification line
     * @param version the version of the renderer, printed in the header
     */
    public static String render(RunView view, BlobStore blobs, ChainReport chain, String version) {
        StepCards cards = new StepCards(blobs);
        Content content = cards.content();
        StringBuilder out = new StringBuilder(8192);
        out.append("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n")
                .append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>Run ").append(Html.line(view.runId())).append("</title>\n<style>").append(CSS)
                .append("</style></head><body>\n");
        header(out, view, version, content);
        verification(out, chain);
        workflow(out, view, blobs);
        parked(out, view, content);
        if (view.error() != null) {
            content.details(out, "error", view.error());
        }
        content.details(out, "inputs", view.inputs());
        interventions(out, view, content);
        out.append("<h2>Steps</h2>\n");
        cards.render(out, view, "");
        out.append("</body></html>\n");
        return out.toString();
    }

    private static void header(StringBuilder out, RunView view, String version, Content content) {
        out.append("<h1>").append(Html.line(view.workflowName() == null ? view.workflowId() : view.workflowName()))
                .append(" <small>").append(Html.line(view.workflowId())).append("</small></h1>\n<table>\n");
        row(out, "run", view.runId());
        row(out, "status", view.status().name());
        if (view.gate() != null) {
            out.append("<tr><th>gate</th><td>").append(StepCards.gate(view.gate(), content));
            if (view.gate().summary() != null) {
                out.append("<br>").append(content.inline(view.gate().summary()));
            }
            out.append("</td></tr>\n");
        }
        row(out, "actor", view.actor());
        row(out, "trigger", view.trigger());
        row(out, "started", view.startedAt() == null ? null : view.startedAt().toString());
        row(out, "finished", view.finishedAt() == null ? null : view.finishedAt().toString());
        row(out, "execution", view.execution());
        aiNetwork(out, view);
        row(out, "package", view.packageDigest());
        row(out, "renderer", version);
        out.append("</table>\n");
    }

    /**
     * Which workflow this was and how it is built: where the package lies, and one row per block of the recorded
     * definition with the step it became. Nothing when the record holds no definition.
     */
    /**
     * Where the run's AI containers could connect, and what they asked the egress proxy for: the connections of the
     * whole run, with the denied ones counted and marked. A denied connection fails nothing; it is there to be seen.
     */
    private static void aiNetwork(StringBuilder out, RunView view) {
        if (view.aiNetwork() != null) {
            StringBuilder text = new StringBuilder(Html.line(view.aiNetwork()));
            if (view.aiNetwork().equals("open")) {
                text.append(view.webHostsNotEnforced() ? " (every AI container can connect anywhere; the web_hosts"
                        + " of a step are recorded, but not enforced)" : " (every AI container can connect anywhere)");
            }
            view.aiHosts().forEach((cli, hosts) -> text.append(" &middot; more hosts for ").append(Html.line(cli))
                    .append(": ").append(Html.line(String.join(", ", hosts))));
            out.append("<tr><th>AI network</th><td>").append(text).append("</td></tr>\n");
        }
        RunView.Egress egress = view.egress();
        if (egress.commands() > 0 || egress.connections() > 0) {
            out.append("<tr><th>egress</th><td>").append(egress.connections())
                    .append(egress.connections() == 1 ? " connection" : " connections")
                    .append(" asked of the egress proxy by ").append(egress.commands())
                    .append(egress.commands() == 1 ? " command, " : " commands, ")
                    .append(egress.denied() == 0 ? "none denied"
                            : "<strong class=\"problem\">" + egress.denied() + " denied</strong>")
                    .append("</td></tr>\n");
        }
    }

    private static void workflow(StringBuilder out, RunView view, BlobStore blobs) {
        out.append("<h2>Workflow</h2>\n");
        WorkflowOutline outline = view.definition() == null ? null
                : blobs.get(view.definition().sha256()).flatMap(WorkflowOutline::of).orElse(null);
        if (outline != null && outline.description() != null) {
            out.append("<p>").append(Html.text(outline.description())).append("</p>\n");
        }
        out.append("<table>\n");
        row(out, "package", view.packageDir());
        if (view.packageCommit() != null) {
            row(out, "commit", view.packageCommit()
                    + (Boolean.TRUE.equals(view.packageDirty()) ? " (with uncommitted changes)" : ""));
        }
        row(out, "digest", view.packageDigest());
        row(out, "seal", view.seal() == null ? "unsealed" : view.seal().summary());
        view.modelChoices().forEach(pick -> row(out, "model choice " + pick.choice(), pick.picked()));
        if (view.definition() != null) {
            row(out, "definition", "blob " + view.definition().sha256());
        }
        out.append("</table>\n");
        if (outline == null) {
            out.append("<p class=\"muted\">the record holds no readable definition</p>\n");
            return;
        }
        images(out, view);
        Map<Integer, StepView> steps = new java.util.HashMap<>();
        view.steps().forEach(step -> steps.putIfAbsent(step.position(), step));
        out.append("<table><tr><th>#</th><th>block</th><th>type</th><th>what</th><th>step</th></tr>\n");
        for (WorkflowOutline.Block block : outline.blocks()) {
            out.append("<tr><td>").append(block.position()).append("</td><td>")
                    .append(block.name() == null ? "<em>unnamed</em>" : Html.line(block.name()))
                    .append("</td><td>").append(Html.line(block.type() == null ? "?" : block.type()));
            if (block.provider() != null || block.model() != null) {
                out.append(" <small>").append(Html.line(String.join(" ", java.util.stream.Stream.of(block.provider(),
                        block.model()).filter(java.util.Objects::nonNull).toList()))).append("</small>");
            }
            if (block.web() != null) {
                out.append(" <small>web ").append(Html.line(block.web())).append("</small>");
            }
            out.append("</td><td>").append(block.what() == null ? "" : "<code>" + Html.line(block.what()) + "</code>")
                    .append("</td><td>");
            StepView step = steps.get(block.position());
            if (step == null) {
                out.append("<span class=\"muted\">did not run</span>");
            } else {
                out.append("<a href=\"#").append(Html.line(StepCards.anchor("", step.stepIndex()))).append("\">step ")
                        .append(step.stepIndex()).append("</a> ").append(Html.line(step.shownStatus()));
            }
            out.append("</td></tr>\n");
        }
        out.append("</table>\n");
    }

    /**
     * What each image the run may use holds, as the record says: a name like {@code ./images/areas} says nothing, so
     * the page shows the base image and the description its Dockerfile gives. Nothing for a record without images.
     */
    private static void images(StringBuilder out, RunView view) {
        if (view.images().isEmpty()) {
            return;
        }
        out.append("<h3>Images</h3>\n<table><tr><th>image</th><th>base</th><th>holds</th></tr>\n");
        for (ImageFacts image : view.images()) {
            out.append("<tr><td><code>").append(Html.line(image.reference())).append("</code></td><td>")
                    .append(image.base() == null ? "<span class=\"muted\">a registry image</span>"
                            : Html.line(image.base()))
                    .append("</td><td>").append(image.description() == null ? "" : Html.line(image.description()))
                    .append("</td></tr>\n");
        }
        out.append("</table>\n");
    }

    /** What people and processes did to the run from outside its steps, in log order. */
    private static void interventions(StringBuilder out, RunView view, Content content) {
        if (view.interventions().isEmpty()) {
            return;
        }
        out.append("<h2>Interventions</h2>\n<table><tr><th>time</th><th>who</th><th>what</th></tr>\n");
        for (RunView.Intervention intervention : view.interventions()) {
            out.append("<tr><td>").append(Html.line(intervention.at().toString())).append("</td><td>")
                    .append(Html.line(intervention.actor() == null ? "" : intervention.actor())).append("</td><td>")
                    .append(Html.line(intervention.description()));
            if (intervention.content() != null) {
                out.append(": ").append(content.inline(intervention.content()));
            }
            out.append(" <small>seq ").append(intervention.seq()).append("</small></td></tr>\n");
        }
        out.append("</table>\n");
    }

    private static void row(StringBuilder out, String label, String value) {
        if (value != null) {
            out.append("<tr><th>").append(Html.line(label)).append("</th><td>").append(Html.line(value))
                    .append("</td></tr>\n");
        }
    }

    private static void verification(StringBuilder out, ChainReport chain) {
        if (chain.intact()) {
            out.append("<p class=\"verification\">chain intact, ").append(chain.events()).append(" events, head ")
                    .append(Html.line(chain.head())).append("</p>\n");
        } else {
            out.append("<div class=\"verification problem\"><p>chain NOT intact, ").append(chain.events())
                    .append(" events read</p><ul>\n");
            chain.problems().forEach(p -> out.append("<li>").append(Html.line(p)).append("</li>\n"));
            out.append("</ul></div>\n");
        }
        if (!chain.deletedBlobs().isEmpty()) {
            out.append("<p class=\"deleted\">deleted blobs:");
            chain.deletedBlobs().forEach(b -> out.append(" <code>").append(Html.line(b)).append("</code>"));
            out.append("</p>\n");
        }
    }

    private static void parked(StringBuilder out, RunView view, Content content) {
        if (view.paused()) {
            out.append("<div class=\"parked\"><h2>Paused</h2>\n<p>The run was asked to pause. It finished what was in")
                    .append(" flight and waits for nothing but its resume; a loop that was in flight runs only the")
                    .append(" items that did not finish.</p>\n<p>continue with <code>")
                    .append(Html.line(resumeCommand(view))).append("</code></p></div>\n");
            return;
        }
        QuestionView question = view.pendingQuestion();
        if (question == null) {
            return;
        }
        out.append("<div class=\"parked\"><h2>").append(view.parkedAnswer() == null ? "Waiting for an answer"
                : "Parked, and answered").append("</h2>\n<p>")
                .append(content.inline(question.question())).append("</p>\n");
        if (!question.options().isEmpty()) {
            out.append("<p>options:");
            question.options().forEach(o -> out.append(" <code>").append(Html.line(o)).append("</code>"));
            out.append("</p>\n");
        }
        String run = shellQuote(view.runId());
        if (view.parkedAnswer() != null) {
            out.append("<p>answered &mdash; continue with <code>").append(Html.line(resumeCommand(view)))
                    .append("</code></p></div>\n");
            return;
        }
        out.append("<pre>");
        if ("human-input".equals(question.kind())) {
            List<Map<String, Object>> fields = question.fields() == null ? List.of() : question.fields();
            if (fields.isEmpty()) {
                out.append(Html.line("traceworks answer " + run + " --value '<text>'")).append('\n');
            } else {
                StringBuilder command = new StringBuilder("traceworks answer " + run);
                fields.forEach(field -> command.append(" --field ")
                        .append(shellQuote(field.get("variable_name") + "=<value>")));
                out.append(Html.line(command.toString())).append('\n');
            }
        } else if (question.options().isEmpty()) {
            out.append(Html.line("traceworks approve " + run + " --answer '<option>'")).append('\n');
        } else {
            for (String option : question.options()) {
                out.append(Html.line("traceworks approve " + run + " --answer " + shellQuote(option)))
                        .append('\n');
            }
        }
        out.append(Html.line(resumeCommand(view))).append("</pre></div>\n");
    }

    /**
     * The command that resumes the run. A host run needs the consent to run on the host again, so its command
     * carries {@code --allow-host-execution}: the page does not know whether the operator config of the machine
     * that resumes gives the consent, and the flag is accepted where it does.
     */
    private static String resumeCommand(RunView view) {
        return "traceworks resume " + shellQuote(view.runId())
                + ("host".equals(view.execution()) ? " --allow-host-execution" : "");
    }

    /** Single-quotes a value for a POSIX shell; an embedded single quote becomes {@code '\\''}. */
    public static String shellQuote(String value) {
        return ShellQuoting.quote(value);
    }
}
